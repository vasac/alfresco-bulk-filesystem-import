/*
 * Copyright (C) 2005-2011 Alfresco Software Limited.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.

 * As a special exception to the terms and conditions of version 2.0 of 
 * the GPL, you may redistribute this Program in connection with Free/Libre 
 * and Open Source Software ("FLOSS") applications as described in Alfresco's 
 * FLOSS exception.  You should have received a copy of the text describing 
 * the FLOSS exception, and it is also available here: 
 * http://www.alfresco.com/legal/licensing"
 */

package org.alfresco.extension.bulkfilesystemimport.impl;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.content.ContentStore;
import org.alfresco.repo.content.encoding.ContentCharsetFinder;
import org.alfresco.repo.content.filestore.FileContentStore;
import org.alfresco.repo.policy.BehaviourFilter;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.repo.version.VersionModel;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.model.FileExistsException;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.model.FileNotFoundException;
import org.alfresco.service.cmr.repository.ContentData;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.InvalidNodeRefException;
import org.alfresco.service.cmr.repository.MimetypeService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.security.AccessStatus;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.service.cmr.version.VersionService;
import org.alfresco.service.cmr.version.VersionType;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.Pair;

import org.alfresco.extension.bulkfilesystemimport.AnalysedDirectory;
import org.alfresco.extension.bulkfilesystemimport.BulkFilesystemImporter;
import org.alfresco.extension.bulkfilesystemimport.BulkImportStatus;
import org.alfresco.extension.bulkfilesystemimport.DirectoryAnalyser;
import org.alfresco.extension.bulkfilesystemimport.ImportableItem;
import org.alfresco.extension.bulkfilesystemimport.MetadataLoader;
import org.alfresco.extension.bulkfilesystemimport.ImportFilter;
import org.alfresco.extension.bulkfilesystemimport.impl.BulkImportStatusImpl.NodeState;


/**
 * An abstract BulkFilesystemImporter containing handy convenience methods for concrete
 * BulkFilesystemImporter implementations.
 *
 * @author Peter Monks (pmonks@alfresco.com)
 * @version $Id: AbstractBulkFilesystemImporter.java 136 2011-08-05 20:42:10Z pmonks@gmail.com $
 */
public abstract class AbstractBulkFilesystemImporter
    implements BulkFilesystemImporter
{
    private final static Log log = LogFactory.getLog(AbstractBulkFilesystemImporter.class);
    
    private final static String OS_FILE_SEPARATOR     = System.getProperty("file.separator");
    private final static int    DEFAULT_BATCH_WEIGHT  = 100;
    private final static String DEFAULT_TEXT_ENCODING = "UTF-8";

    protected final ServiceRegistry      serviceRegistry;
    protected final BehaviourFilter      behaviourFilter;
    protected final ContentStore         configuredContentStore;
    protected final FileFolderService    fileFolderService;
    protected final NodeService          nodeService;
    protected final VersionService       versionService;
    protected final MimetypeService      mimeTypeService;
    
    protected final BulkImportStatusImpl importStatus;

    private DirectoryAnalyser  directoryAnalyser = null;
    private List<ImportFilter> importFilters     = null;
    private MetadataLoader     metadataLoader    = null;
    private int                batchWeight       = DEFAULT_BATCH_WEIGHT;


    protected AbstractBulkFilesystemImporter(final ServiceRegistry      serviceRegistry,
                                             final BehaviourFilter      behaviourFilter,
                                             final ContentStore         configuredContentStore,
                                             final BulkImportStatusImpl importStatus)
    {
        this.serviceRegistry        = serviceRegistry;
        this.behaviourFilter        = behaviourFilter;
        this.configuredContentStore = configuredContentStore;
        
        this.fileFolderService = serviceRegistry.getFileFolderService();
        this.nodeService       = serviceRegistry.getNodeService();
        this.versionService    = serviceRegistry.getVersionService();
        this.mimeTypeService   = serviceRegistry.getMimetypeService();
        
        this.importStatus      = importStatus;
        this.importFilters     = new ArrayList<ImportFilter>();
    }
    
    public final void setDirectoryAnalyser(final DirectoryAnalyser directoryAnalyser)
    {
        this.directoryAnalyser = directoryAnalyser;
    }
    
    public final void setImportFilters(final List<ImportFilter> importFilters)
    {
        if (importFilters != null)
        {
            this.importFilters.addAll(importFilters);
        }
    }
    
    public final void setMetadataLoader(final MetadataLoader metadataLoader)
    {
        this.metadataLoader = metadataLoader;
    }
    
    public final void setBatchWeight(final int batchWeight)
    {
        if (batchWeight > 0)
        {
            this.batchWeight = batchWeight;
        }
    }
    

    /**
     * @see org.alfresco.extension.bulkfilesystemimport.BulkFilesystemImporter#bulkImport(java.io.File, org.alfresco.service.cmr.repository.NodeRef, boolean)
     */
    public final void bulkImport(final NodeRef target, final File source, final boolean replaceExisting)
        throws Throwable
    {
        validateNodeRefIsWritableSpace(target);
        validateFileIsReadableDirectory(source);
        
        bulkImportImpl(target, source, replaceExisting, isInContentStore(source));
    }
    
    
    private final boolean isInContentStore(final File source)
    {
        boolean result = false;
        
        // Ignore non-file content stores
        if (configuredContentStore instanceof FileContentStore)
        {
            String storeRootLocation = configuredContentStore.getRootLocation();
            String sourcePath        = null;
            
            try
            {
                sourcePath = source.getCanonicalPath();
            }
            catch (final IOException ioe)
            {
                sourcePath = source.getAbsolutePath();
            }
            
            result = sourcePath.startsWith(storeRootLocation);
        }
        
        return(result);
    }


    /**
     * Method to be overridden by subclasses that performs an ingestion.  This method will only be called if the
     * bulk import could safely be initiated.  It should call importStatus.startImport() and one of the
     * importStatus.stopImport() methods, to ensure import status is captured correctly.
     * 
     * @param target          The target space to ingest the content into <i>(must not be null and must be a valid, writable space in the repository)</i>.
     * @param sourceRoot      The original directory from which this import was initiated <i>(must not be null)</i>.
     * @param source          The source directory on the local filesystem to read content from <i>(must not be null and must be a valid, readable directory on the local filesystem)</i>.
     * @param replaceExisting A flag indicating whether to replace (true) or skip (false) files that are already in the repository.
     * @param inPlaceImport   A flag indicating whether this is an "in place" import (i.e. the source directory is already located inside the configured content store).
     */
    protected abstract void bulkImportImpl(final NodeRef target,
                                           final File    source,
                                           final boolean replaceExisting,
                                           final boolean inPlaceImport)
        throws Throwable;

    
    /**
     * @see org.alfresco.extension.bulkfilesystemimport.BulkFilesystemImporter#getStatus()
     */
    public final BulkImportStatus getStatus()
    {
        return(importStatus);
    }
    
    
    /**
     * Method to be called by subclasses on a per-directory basis.  This method will import the given source directory only
     * (i.e. non-recursively), returning the list of its sub-directories.
     * 
     * @param target          The target space to ingest the content into <i>(must not be null and must be a valid, writable space in the repository)</i>.
     * @param sourceRoot      The original directory from which this import was initiated <i>(must not be null)</i>.
     * @param source          The source directory on the local filesystem to read content from <i>(must not be null and must be a valid, readable directory on the local filesystem)</i>.
     * @param replaceExisting A flag indicating whether to replace (true) or skip (false) files that are already in the repository.
     * @param inPlaceImport   A flag indicating whether this is an "in place" import (i.e. the source directory is already located inside the configured content store).
     * @return A list of sub-directories that have yet to be loaded, along with their associated NodeRefs in the repository <i>(will not be null, but may be empty)</i>.
     */
    protected final List<Pair<NodeRef, File>> importDirectory(final NodeRef target,
                                                              final String  sourceRoot,
                                                              final File    source,
                                                              final boolean replaceExisting,
                                                              final boolean inPlaceImport)
    {
        List<Pair<NodeRef, File>> result = new ArrayList<Pair<NodeRef, File>>();
        
        importStatus.setCurrentFileBeingProcessed(getFileName(source));
        
        // PHASE 1: analyse the source directory
        final AnalysedDirectory          analysedDirectory       = directoryAnalyser.analyseDirectory(source);
         
        // PHASE 2: filter ImportableItems from the source directory
        final List<ImportableItem>       filteredImportableItems = filterImportableItems(analysedDirectory.importableItems);
        
        // PHASE 3: batch ImportableItems
        final List<List<ImportableItem>> batchedImportableItems  = batchImportableItems(filteredImportableItems);

        if (log.isDebugEnabled()) log.debug("---- Bulk Filesystem Importer - Directory Analysis for: " + getFileName(source) +
                                            "\n\t" + analysedDirectory.originalListing.size() + " file"                     + (analysedDirectory.originalListing.size() == 1 ? "" : "s")  + 
                                            "\n\t" + analysedDirectory.importableItems.size() + " importable item"          + (analysedDirectory.importableItems.size() == 1 ? "" : "s")  +
                                            "\n\t" + filteredImportableItems.size()           + " filtered importable item" + (filteredImportableItems.size()           == 1 ? "" : "s")  +
                                            "\n\t" + batchedImportableItems.size()            + " batch"                    + (batchedImportableItems.size()            == 1 ? "" : "es"));
        
        // PHASE 4: load the batches
        result.addAll(importImportableItemBatches(target, sourceRoot, batchedImportableItems, replaceExisting, inPlaceImport));
        
        return(result);
    }
    
    
    private final List<ImportableItem> filterImportableItems(final List<ImportableItem> importableItems)
    {
        List<ImportableItem> result = new ArrayList<ImportableItem>();

        if (importableItems != null && importableItems.size() > 0 &&
            importableItems != null && importableItems.size() > 0)
        {
            if (importFilters == null || importFilters.size() == 0)
            {
                result.addAll(importableItems);
            }
            else
            {
                for (final ImportableItem importableItem : importableItems)
                {
                    boolean filterImportableItem = false;
                    
                    for (final ImportFilter filter : importFilters)
                    {
                        if (filter.shouldFilter(importableItem))
                        {
                            filterImportableItem = true;
                            break;
                        }
                    }
                    
                    if (!filterImportableItem)
                    {
                        result.add(importableItem);
                    }
                }
            }
        }
        
        return(result);
    }
    
    
    private final List<List<ImportableItem>> batchImportableItems(final List<ImportableItem> importableItems)
    {
        List<List<ImportableItem>> result             = new ArrayList<List<ImportableItem>>();
        int                        currentBatch       = 0;
        int                        currentBatchWeight = 0;
        
        result.add(new ArrayList<ImportableItem>());
        
        for (final ImportableItem importableItem : importableItems)
        {
            result.get(currentBatch).add(importableItem);
            currentBatchWeight += importableItem.weight();
            
            if (currentBatchWeight >= batchWeight)
            {
                result.add(new ArrayList<ImportableItem>());
                currentBatch++;
                currentBatchWeight = 0;
            }
        }
        
        return(result);
    }
    
        
    private final List<Pair<NodeRef, File>> importImportableItemBatches(final NodeRef                    target,
                                                                        final String                     sourceRoot,
                                                                        final List<List<ImportableItem>> batches,
                                                                        final boolean                    replaceExisting,
                                                                        final boolean                    inPlaceImport)
                                                                        
    {
        List<Pair<NodeRef, File>> result = new ArrayList<Pair<NodeRef, File>>();
        
        if (batches != null)
        {
            for (final List<ImportableItem> batch : batches)
            {
                result.addAll(importBatchInTxn(target, sourceRoot, batch, replaceExisting, inPlaceImport));

                // If we're running on a background thread that's been interrupted, terminate early
                if (Thread.interrupted())
                {
                    if (log.isWarnEnabled()) log.warn(Thread.currentThread().getName() + " was interrupted while importing batches.  Terminating early.");
                    break;
                }
            }
        }
        
        return(result);
    }

    
    private final List<Pair<NodeRef, File>> importBatchInTxn(final NodeRef              target,
                                                             final String               sourceRoot,
                                                             final List<ImportableItem> batch,
                                                             final boolean              replaceExisting,
                                                             final boolean              inPlaceImport)
    {
        List<Pair<NodeRef, File>> result    = new ArrayList<Pair<NodeRef, File>>();
        RetryingTransactionHelper txnHelper = serviceRegistry.getRetryingTransactionHelper();

        result.addAll(txnHelper.doInTransaction(new RetryingTransactionCallback<List<Pair<NodeRef, File>>>()
            {
                @Override
                public List<Pair<NodeRef, File>> execute()
                {
                    // Disable the auditable aspect's behaviours for this transaction, to allow creation & modification dates to be set 
                    behaviourFilter.disableBehaviour(ContentModel.ASPECT_AUDITABLE);
                    return(importBatch(target, sourceRoot, batch, replaceExisting, inPlaceImport));
                }
            },
            false,    // read only flag
            false));  // requires new txn flag
        
        importStatus.incrementNumberOfBatchesCompleted();
                            
        return(result);
    }
    
    
    private final List<Pair<NodeRef, File>> importBatch(final NodeRef              target,
                                                        final String               sourcePath,
                                                        final List<ImportableItem> batch,
                                                        final boolean              replaceExisting,
                                                        final boolean              inPlaceImport)
    {
        List<Pair<NodeRef, File>> result = new ArrayList<Pair<NodeRef, File>>();
        
        for (final ImportableItem importableItem : batch)
        {
            NodeRef nodeRef = importImportableItem(target, sourcePath, importableItem, replaceExisting, inPlaceImport);
            
            // If it's a directory, add it to the list of sub-directories to be processed
            if (nodeRef != null &&
                importableItem.getHeadRevision().contentFileExists() &&
                ImportableItem.FileType.DIRECTORY.equals(importableItem.getHeadRevision().getContentFileType()))
            {
                result.add(new Pair<NodeRef, File>(nodeRef, importableItem.getHeadRevision().getContentFile()));
            }
        }
        
        return(result);
    }
    
    
    private final NodeRef importImportableItem(final NodeRef          target,
                                               final String           sourceRoot,
                                               final ImportableItem   importableItem,
                                               final boolean          replaceExisting,
                                               final boolean          inPlaceImport)
                                               
    {
        if (log.isDebugEnabled()) log.debug("Importing " + String.valueOf(importableItem));

        NodeRef                             result      = null;
        MetadataLoader.Metadata             metadata    = loadMetadata(importableItem.getHeadRevision());
        Triple<NodeRef, Boolean, NodeState> node        = createOrFindNode(target, importableItem, replaceExisting, metadata);
        boolean                             isDirectory = node.getSecond() == null ? false : node.getSecond();  // Watch out for NPEs during unboxing!
        NodeState                           nodeState   = node.getThird();
        
        result = node.getFirst();
            
        if (result != null && nodeState != NodeState.SKIPPED)
        {
            int numVersionProperties = 0;
            
            importStatus.incrementImportableItemsRead(importableItem, isDirectory);
            
            // Load the item
            if (isDirectory)
            {
                importImportableItemDirectory(result, importableItem, metadata);
            }
            else
            {
                numVersionProperties = importImportableItemFile(result, importableItem, inPlaceImport, metadata);
            }
            
            importStatus.incrementNodesWritten(importableItem, isDirectory, nodeState, metadata.getProperties().size() + 4, numVersionProperties);
        }
        else
        {
            if (log.isInfoEnabled()) log.info("Skipping '" + getFileName(importableItem.getHeadRevision().getContentFile()) + "' as it already exists in the repository and 'replace existing' is false.");
            importStatus.incrementImportableItemsSkipped(importableItem, isDirectory);
        }
        
        return(result);
    }
    
    
    private final Triple<NodeRef, Boolean, NodeState> createOrFindNode(final NodeRef                 target,
                                                                       final ImportableItem          importableItem,
                                                                       final boolean                 replaceExisting,
                                                                       final MetadataLoader.Metadata metadata)
    {
        Triple<NodeRef, Boolean, NodeState> result      = null;
        boolean                             isDirectory = false;
        NodeState                           nodeState   = replaceExisting ? NodeState.REPLACED : NodeState.SKIPPED;
        String                              nodeName    = getImportableItemName(importableItem, metadata);
        NodeRef                             nodeRef     = null;
        
        //####TODO: handle this more elegantly
        if (nodeName == null)
        {
            throw new IllegalStateException("Unable to determine node name for " + String.valueOf(importableItem));
        }

        if (log.isDebugEnabled()) log.debug("Searching for node with name '" + nodeName + "' within node '" + target.toString() + "'.");
        nodeRef = fileFolderService.searchSimple(target, nodeName);
        
        // If we didn't find an existing item, create a new node in the repo. 
        if (nodeRef == null)
        {
            // But only if the content file exists - we don't create new nodes based on metadata-only importableItems
            if (importableItem.getHeadRevision().contentFileExists())
            {
                isDirectory = ImportableItem.FileType.DIRECTORY.equals(importableItem.getHeadRevision().getContentFileType());
                
                try
                {
                    if (log.isDebugEnabled()) log.debug("Creating new node of type '" + metadata.getType().toString() + "' with name '" + nodeName + "' within node '" + target.toString() + "'.");
                    nodeRef   = fileFolderService.create(target, nodeName, metadata.getType()).getNodeRef();
                    nodeState = NodeState.CREATED;
                }
                catch (final FileExistsException fee)
                {
                    if (log.isWarnEnabled()) log.warn("Node with name '" + nodeName + "' within node '" + target.toString() + "' was created concurrently to the bulk import.  Skipping importing it.", fee);
                    nodeRef   = null;
                    nodeState = NodeState.SKIPPED;
                }
            }
            else
            {
                if (log.isDebugEnabled()) log.debug("Skipping creation of new node '" + nodeName + "' within node '" + target.toString() + "' since it doesn't have a content file.");
                nodeRef   = null;
                nodeState = NodeState.SKIPPED;
            }
        }
        // We found the node in the repository.  Make sure we return the NodeRef, so that recursive loading works (we need the NodeRef of all sub-spaces, even if we didn't create them).
        else
        {
            if (replaceExisting)
            {
                boolean targetNodeIsSpace = fileFolderService.getFileInfo(nodeRef).isFolder();
                
                if (importableItem.getHeadRevision().contentFileExists())
                {
                    // If the source file exists, ensure that the target node is of the same type (i.e. file or folder) as it. 
                    isDirectory = ImportableItem.FileType.DIRECTORY.equals(importableItem.getHeadRevision().getContentFileType());
                
                    if (isDirectory != targetNodeIsSpace)
                    {
                        if (log.isWarnEnabled()) log.warn("Skipping replacement of " + (isDirectory ? "Directory " : "File ") +
                                                          "'" + getFileName(importableItem.getHeadRevision().getContentFile()) + "'. " +
                                                          "The target node in the repository is a " + (targetNodeIsSpace ? "space node" : "content node") + ".");
                        nodeState = NodeState.SKIPPED;
                    }
                }
                else
                {
                    isDirectory = targetNodeIsSpace;
                }
                
                if (nodeRef != null)
                {
                    if (metadata.getType() != null)
                    {
                        // Finally, specialise the type.
                        if (log.isDebugEnabled()) log.debug("Specialising type of node '" + nodeRef.toString() + "' to '" + String.valueOf(metadata.getType()) + "'.");
                        nodeService.setType(nodeRef, metadata.getType());
                    }
                    
                    nodeState = NodeState.REPLACED;
                }
            }
            else
            {
                if (log.isDebugEnabled()) log.debug("Found content node '" + nodeRef.toString() + "', but replaceExisting=false, so skipping it.");
                nodeState = NodeState.SKIPPED;
            }
        }
        
        result = new Triple<NodeRef, Boolean, NodeState>(nodeRef, isDirectory, nodeState);
        
        return(result);
    }
    

    private final int importImportableItemFile(final NodeRef                 nodeRef,
                                               final ImportableItem          importableItem,
                                               final boolean                 inPlaceImport, 
                                               final MetadataLoader.Metadata metadata)
    {
        int result = 0;
        
        if (importableItem.hasVersionEntries())
        {
            // If cm:versionable isn't listed as one of the aspects for this node, add it - cm:versionable is required for nodes that have versions
            if (!metadata.getAspects().contains(ContentModel.ASPECT_VERSIONABLE))
            {
                if (log.isWarnEnabled()) log.warn("Metadata for file '" + getFileName(importableItem.getHeadRevision().getContentFile()) + "' was missing the cm:versionable aspect, yet it has " + importableItem.getVersionEntries().size() + " versions.  Adding cm:versionable.");
                metadata.addAspect(ContentModel.ASPECT_VERSIONABLE);
            }
                    
            result = importContentVersions(nodeRef, importableItem, inPlaceImport);
        }
        
        if (log.isDebugEnabled()) log.debug("Creating head revision of node " + nodeRef.toString());
        
        importContentAndMetadata(nodeRef, importableItem.getHeadRevision(), inPlaceImport, metadata);
        
        return(result);
    }
    
    
    private final int importContentVersions(final NodeRef          nodeRef,
                                            final ImportableItem   importableItem,
                                            final boolean          inPlaceImport)
    {
        int result = 0;
        
        for (final ImportableItem.VersionedContentAndMetadata versionEntry : importableItem.getVersionEntries())
        {
            Map<String, Serializable> versionProperties = new HashMap<String, Serializable>();
            MetadataLoader.Metadata   metadata          = loadMetadata(versionEntry);
            
            importContentAndMetadata(nodeRef, versionEntry, inPlaceImport, metadata);

            if (log.isDebugEnabled()) log.debug("Creating v" + String.valueOf(versionEntry.getVersion()) + " of node '" + nodeRef.toString() + "' (note: version label in Alfresco will not be the same - it is not currently possible to explicitly force a particular version label).");
  
            // Note: PROP_VERSION_LABEL is a "reserved" property, and cannot be modified by custom code.
            // In other words, we can't use the version label on disk as the version label in Alfresco.  :-(
            // See: http://code.google.com/p/alfresco-bulk-filesystem-import/issues/detail?id=85
//            versionProperties.put(ContentModel.PROP_VERSION_LABEL.toPrefixString(), String.valueOf(versionEntry.getVersion()));
            versionProperties.put(VersionModel.PROP_VERSION_TYPE, VersionType.MAJOR);   // Load every version as a major version for now - see http://code.google.com/p/alfresco-bulk-filesystem-import/issues/detail?id=84
            versionService.createVersion(nodeRef, versionProperties);
            
            result += metadata.getProperties().size() + 4;  // Add 4 for "standard" metadata properties read from filesystem
        }
        
        return(result);
    }
    
    
    private final void importContentAndMetadata(final NodeRef                           nodeRef,
                                                final ImportableItem.ContentAndMetadata contentAndMetadata,
                                                final boolean                           inPlaceImport,
                                                final MetadataLoader.Metadata           metadata)
    {
        // Write the content of the file
        if (contentAndMetadata.contentFileExists())
        {
            importStatus.setCurrentFileBeingProcessed(getFileName(contentAndMetadata.getContentFile()));
            
            if (inPlaceImport)
            {
                // It's already in a content store, so simply "link" it into the repository
                if (log.isDebugEnabled()) log.debug("Linking ContentStore file '" + getFileName(contentAndMetadata.getContentFile()) + "' into node '" + nodeRef.toString() + "'.");
                
                metadata.addProperty(ContentModel.PROP_CONTENT, buildContentProperty(contentAndMetadata));
            }
            else
            {
                // File is outside a content store, so stream it into the repository
                if (log.isDebugEnabled()) log.debug("Streaming contents of file '" + getFileName(contentAndMetadata.getContentFile()) + "' into node '" + nodeRef.toString() + "'.");
                
                ContentWriter writer = fileFolderService.getWriter(nodeRef);
                writer.putContent(contentAndMetadata.getContentFile());
            }
        }
        else
        {
            if (log.isDebugEnabled()) log.debug("No content to stream into node '" + nodeRef.toString() + "' - importing metadata only.");
        }
        
        // Attach aspects and set all properties
        importImportableItemMetadata(nodeRef, contentAndMetadata.getContentFile(), metadata);
    }
    
    
    private final void importImportableItemDirectory(final NodeRef                 nodeRef,
                                                     final ImportableItem          importableItem,
                                                     final MetadataLoader.Metadata metadata)
    {
        if (importableItem.hasVersionEntries())
        {
            log.warn("Skipping versions for directory '" + getFileName(importableItem.getHeadRevision().getContentFile()) + "' - Alfresco does not support versioned spaces.");
        }
        
        // Attach aspects and set all properties
        importImportableItemMetadata(nodeRef, importableItem.getHeadRevision().getContentFile(), metadata);
    }
    
    
    private final ContentData buildContentProperty(final ImportableItem.ContentAndMetadata contentAndMetadata)
    {
        ContentData result = null;
        
        String contentUrl = FileContentStore.STORE_PROTOCOL + ContentStore.PROTOCOL_DELIMITER + contentAndMetadata.getContentFile().getAbsolutePath().replace(configuredContentStore.getRootLocation() + OS_FILE_SEPARATOR, "");
        String mimeType   = mimeTypeService.guessMimetype(contentAndMetadata.getContentFile().getName());
        String encoding   = DEFAULT_TEXT_ENCODING;
                
        if (mimeTypeService.isText(mimeType))
        {
            encoding = guessEncoding(contentAndMetadata.getContentFile(), mimeType);
        }
        
        result = new ContentData(contentUrl, mimeType, contentAndMetadata.getContentFileSize(), encoding);
        
        return(result);
    }
    
    
    private final void importImportableItemMetadata(final NodeRef                 nodeRef,
                                                    final File                    parentFile,
                                                    final MetadataLoader.Metadata metadata)
    {
        importStatus.setCurrentFileBeingProcessed(getFileName(parentFile) + " (metadata)");

        // Attach aspects
        if (metadata.getAspects() != null)
        {
            for (final QName aspect : metadata.getAspects())
            {
                if (log.isDebugEnabled()) log.debug("Attaching aspect '" + aspect.toString() + "' to node '" + nodeRef.toString() + "'.");
                
                nodeService.addAspect(nodeRef, aspect, null);  // Note: we set the aspect's properties separately, hence null for the third parameter
            }
        }
        
        // Set property values for both the type and any aspect(s)
        if (metadata.getProperties() != null)
        {
            if (log.isDebugEnabled()) log.debug("Adding properties to node '" + nodeRef.toString() + "':\n" + mapToString(metadata.getProperties()));
            
            try
            {
                nodeService.addProperties(nodeRef, metadata.getProperties());
            }
            catch (final InvalidNodeRefException inre)
            {
                if (!nodeRef.equals(inre.getNodeRef()))
                {
                    // Caused by an invalid NodeRef in the metadata (e.g. in an association)
                    throw new IllegalStateException("Invalid nodeRef found in metadata for '" + getFileName(parentFile) + "'.  " +
                                                    "Probable cause: an association is being populated via metadata, but the " +
                                                    "NodeRef for the target of that association ('" + inre.getNodeRef() + "') is invalid.  " +
                                                    "Please double check your metadata file and try again.", inre);
                }
                else
                {
                    // Logic bug in the BFSIT.  :-(
                    throw inre;
                }
            }
        }
    }
        
        
    private final void validateNodeRefIsWritableSpace(final NodeRef target)
    {
        final PermissionService permissionService = serviceRegistry.getPermissionService();
        
        
        if (target == null)
        {
            throw new RuntimeException("target must not be null.");
        }
        
        if (!fileFolderService.exists(target))
        {
            throw new RuntimeException("Target '" + target.toString() + "' doesn't exist.");
        }
        
        if (AccessStatus.DENIED.equals(permissionService.hasPermission(target, PermissionService.ADD_CHILDREN)))
        {
            throw new RuntimeException("Target '" + target.toString() + "' is not writeable.");
        }
        
        if (!fileFolderService.getFileInfo(target).isFolder())
        {
            throw new RuntimeException("Target '" + target.toString() + "' is not a space.");
        }
    }
    
    
    private final void validateFileIsReadableDirectory(final File source)
    {
        try
        {
            if (source == null)
            {
                throw new RuntimeException("source must not be null.");
            }
            
            if (!source.exists())
            {
                throw new RuntimeException("Source '" + source.getCanonicalPath() + "' doesn't exist.");
            }
            
            if (!source.canRead())
            {
                throw new RuntimeException("Source '" + source.getCanonicalPath() + "' is not readable.");
            }
            
            if (!source.isDirectory())
            {
                throw new RuntimeException("Source '" + source.getCanonicalPath() + "' is not a directory.");
            }
        }
        catch (final IOException ioe)
        {
            throw new RuntimeException(ioe);
        }
    }
    
    
    //####TODO: refactor this out into a separate utility class
    // Note: this method is *expensive*, primarily due to getCanonicalPath requiring IO (i.e. it hits disk)
    public final static String getFileName(final File file)
    {
        String result = null;
     
        if (file != null)
        {
            try
            {
                result = file.getCanonicalPath();
            }
            catch (final IOException ioe)
            {
                result = file.toString();
            }
        }
        
        return(result);
    }
    
    
    protected final String getRepositoryPath(final NodeRef nodeRef)
    {
        String result = null;
        
        if (nodeRef != null)
        {
            List<FileInfo> pathElements = null;
            
            try
            {
                pathElements = fileFolderService.getNamePath(null, nodeRef);

                if (pathElements != null && pathElements.size() > 0)
                {
                    StringBuilder temp = new StringBuilder();
                    
                    for (FileInfo pathElement : pathElements)
                    {
                        temp.append("/");
                        temp.append(pathElement.getName());
                    }
                    
                    result = temp.toString();
                }
            }
            catch (final FileNotFoundException fnfe)
            {
                // Do nothing
            }
        }
        
        return(result);
    }
    
    
    private final MetadataLoader.Metadata loadMetadata(final ImportableItem.ContentAndMetadata contentAndMetadata)
    {
        MetadataLoader.Metadata result = new MetadataLoader.Metadata();
        
        // Load "standard" metadata from the filesystem
        if (contentAndMetadata != null && contentAndMetadata.contentFileExists())
        {
            final String filename = contentAndMetadata.getContentFile().getName().trim().replaceFirst(DirectoryAnalyser.VERSION_SUFFIX_REGEX, "");  // Strip off the version suffix (if any)
            final Date   modified = new Date(contentAndMetadata.getContentFile().lastModified());
            final Date   created  = modified;    //TODO: determine proper file creation time (awaiting JDK 1.7 NIO2 library)
            
            result.setType(ImportableItem.FileType.FILE.equals(contentAndMetadata.getContentFileType()) ? ContentModel.TYPE_CONTENT : ContentModel.TYPE_FOLDER);
            result.addProperty(ContentModel.PROP_NAME,     filename);
            result.addProperty(ContentModel.PROP_TITLE,    filename);
            result.addProperty(ContentModel.PROP_CREATED,  created);
            result.addProperty(ContentModel.PROP_MODIFIED, modified);
        }
            
        if (metadataLoader != null)
        {
            metadataLoader.loadMetadata(contentAndMetadata, result);
        }
        
        return(result);
    }
    
    
    /*
     * Because commons-lang ToStringBuilder doesn't seem to like unmodifiable Maps
     */
    protected final String mapToString(final Map<?, ?> map)
    {
        StringBuffer result = new StringBuffer();
        
        if (map != null)
        {
            result.append('[');

            if (map.size() > 0)
            {
                for (Object key : map.keySet())
                {
                    result.append(String.valueOf(key));
                    result.append(" = ");
                    result.append(String.valueOf(map.get(key)));
                    result.append(",\n");
                }
                
                // Delete final dangling ", " value
                result.delete(result.length() - 2, result.length());
            }
            
            result.append(']');
        }
        else
        {
            result.append("(null)");
        }
        
        return(result.toString());
    }

    
    /**
     * @return the batchWeight
     */
    protected final int getBatchWeight()
    {
        return batchWeight;
    }
    
    
    /**
     * Returns the name of the given importable item.  This is the final name of the item, as it would appear in the repository,
     * after metadata renames are taken into account.
     * 
     * @param importableItem The importableItem with which to 
     * @param metadata 
     * @return
     */
    protected final String getImportableItemName(final ImportableItem importableItem, final MetadataLoader.Metadata metadata)
    {
        String result = null;

        // Step 1: attempt to get name from metadata
        if (metadata != null)
        {
            result = (String)metadata.getProperties().get(ContentModel.PROP_NAME);
        }
        
        // Step 2: attempt to get name from metadata file
        if (result         == null &&
            importableItem != null &&
            importableItem.getHeadRevision() != null)
        {
            File metadataFile = importableItem.getHeadRevision().getMetadataFile();
            
            if (metadataFile != null)
            {
                final String metadataFileName = metadataFile.getName();
                
                result = metadataFileName.substring(0, metadataFileName.length() -
                                                       (MetadataLoader.METADATA_SUFFIX.length() + metadataLoader.getMetadataFileExtension().length()));
            }
        }
                
        return(result);
    }
    
    
    /**
     * Logs a nice summary of the current status of an import.  Primarily useful for after an import succeeds.
     * 
     * @param importStatus The import status to log.
     */
    protected final void logStatus(final BulkImportStatus importStatus)
    {
        if (log.isInfoEnabled())
        {
            log.info("Bulk Import Statistics:" +
                      "\nScanned:" +
                      "\n\tFiles:                  " + importStatus.getNumberOfFilesScanned() +
                      "\n\tFolders:                " + importStatus.getNumberOfFoldersScanned() +
                      "\nRead:" +
                      "\n\tContent files:          " + importStatus.getNumberOfContentFilesRead() +
                      " (" +importStatus.getNumberOfContentBytesRead() + " bytes)" +
                      "\n\tMetadata files:         " + importStatus.getNumberOfMetadataFilesRead() +
                      " (" + importStatus.getNumberOfMetadataBytesRead() + " bytes)" +
                      "\n\tContent version files:  " + importStatus.getNumberOfContentVersionFilesRead() +
                      " (" + importStatus.getNumberOfContentVersionBytesRead() + " bytes)" +
                      "\n\tMetadata version files: " + importStatus.getNumberOfMetadataVersionFilesRead() +
                      " (" + importStatus.getNumberOfMetadataVersionBytesRead() + " bytes)" +
                      "\nWritten:" +
                      "\n\tContent nodes created:  " + importStatus.getNumberOfContentNodesCreated() +
                      "\n\tContent nodes replaced: " + importStatus.getNumberOfContentNodesReplaced() +
                      "\n\tContent nodes skipped:  " + importStatus.getNumberOfContentNodesSkipped() +
                      "\n\tSpace nodes created:    " + importStatus.getNumberOfSpaceNodesCreated() +
                      "\n\tSpace nodes replaced:   " + importStatus.getNumberOfSpaceNodesReplaced() +
                      "\n\tSpace nodes skipped:    " + importStatus.getNumberOfSpaceNodesSkipped());
        }
    }
    
    
    /**
     * Attempt to guess text file encoding, falling back to {@link #DEFAULT_TEXT_ENCODING}.
     * 
     * @param file      The {@link java.io.File} to test <i>(must not be null)</i>.
     * @param mimetype  the file mimetype. used to first distinguish between binary and text files <i>(must not be null)</i>.
     * @return The text encoding as a {@link String}.
     */
    private final String guessEncoding(final File file, final String mimeType)
    {
        String               result        = DEFAULT_TEXT_ENCODING;
        InputStream          is            = null;
        ContentCharsetFinder charsetFinder = mimeTypeService.getContentCharsetFinder();

        try
        {
           is     = new BufferedInputStream(new FileInputStream(file));
           result = charsetFinder.getCharset(is, mimeType).name();
        }
        catch (final IOException ioe)
        {
            if (log.isDebugEnabled()) log.debug("Failed to guess character encoding of file '" + getFileName(file) + "'.  Defaulting to '" + DEFAULT_TEXT_ENCODING + "'.");
            result = DEFAULT_TEXT_ENCODING;
        }
        finally
        {
           if (is != null)
           {
              try
              {
                  is.close();
              }
              catch (final IOException ioe)
              {
                  // Swallow and move on
              }
           }
        }
        
        return(result);
    }

    
}
