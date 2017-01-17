/* 
 * Copyright (C) 2016 Davide Imbriaco
 *
 * This Java file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package it.anyplace.sync.bep;

import it.anyplace.sync.bep.protos.BlockExchageProtos;
import com.google.common.base.Function;
import static com.google.common.base.MoreObjects.firstNonNull;
import it.anyplace.sync.core.beans.FileInfo;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import java.util.Date;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import it.anyplace.sync.core.beans.BlockInfo;
import java.util.Collections;
import javax.annotation.Nullable;
import it.anyplace.sync.core.beans.FileInfo.Version;
import it.anyplace.sync.core.configuration.ConfigurationService;
import it.anyplace.sync.bep.BlockExchangeConnectionHandler.AnyIndexMessageReceivedEvent;
import java.io.IOException;
import org.apache.commons.io.FileUtils;
import com.google.common.io.BaseEncoding;
import it.anyplace.sync.bep.BlockExchangeConnectionHandler.ClusterConfigMessageProcessedEvent;
import com.google.common.base.Strings;
import com.google.common.eventbus.EventBus;
import it.anyplace.sync.core.beans.IndexInfo;
import it.anyplace.sync.core.interfaces.Sequencer;
import com.google.common.base.Stopwatch;
import static it.anyplace.sync.core.utils.FileUtils.createTempFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import it.anyplace.sync.core.beans.FolderInfo;
import static it.anyplace.sync.core.security.KeystoreHandler.hashDataToDeviceIdString;
import java.io.Closeable;
import org.apache.commons.lang3.tuple.Pair;
import it.anyplace.sync.core.beans.FileBlocks;
import it.anyplace.sync.core.interfaces.IndexRepository;
import it.anyplace.sync.core.utils.ExecutorUtils;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 *
 * @author aleph
 */
public class IndexHandler implements Closeable {

    private final static long DEFAULT_INDEX_TIMEOUT = 30;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ConfigurationService configuration;
    private final EventBus eventBus = new EventBus();
    private final Map<String, FolderInfo> folderInfoByFolder = Maps.newHashMap();
    private final IndexRepository indexRepository;
    private final IndexMessageProcessor indexMessageProcessor = new IndexMessageProcessor();
    private long lastIndexActivity = 0;
    private final Object writeAccessLock = new Object(), indexWaitLock = new Object();

    private long getLastActive() {
        return System.currentTimeMillis() - lastIndexActivity;
    }

    private void markActive() {
        lastIndexActivity = System.currentTimeMillis();
    }

    public IndexHandler(ConfigurationService configuration, IndexRepository indexRepository) {
        this.configuration = configuration;
        loadFolderInfoFromConfig();
        this.indexRepository = indexRepository;
    }

    private void loadFolderInfoFromConfig() {
        synchronized (writeAccessLock) {
            for (FolderInfo folderInfo : configuration.getFolders()) {
                folderInfoByFolder.put(folderInfo.getFolder(), folderInfo); //TODO reference 'folder info' repository
            }
        }
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public synchronized void clearIndex() {
        synchronized (writeAccessLock) {
            indexRepository.clearIndex();
            folderInfoByFolder.clear();
            loadFolderInfoFromConfig();
        }
    }

    public boolean isRemoteIndexAquired(BlockExchangeConnectionHandler connectionHandler) {
        boolean ready = true;
        for (String folder : connectionHandler.getClusterConfigInfo().getSharedFolders()) {
            IndexInfo indexSequenceInfo = indexRepository.findIndexInfoByDeviceAndFolder(connectionHandler.getDeviceId(), folder);
            if (indexSequenceInfo == null || indexSequenceInfo.getLocalSequence() < indexSequenceInfo.getMaxSequence()) {
                logger.debug("waiting for index on folder = {} sequenceInfo = {}", folder, indexSequenceInfo);
                ready = false;
            }
        }
        return ready;
    }

    public IndexHandler waitForRemoteIndexAquired(BlockExchangeConnectionHandler connectionHandler) throws InterruptedException {
        return waitForRemoteIndexAquired(connectionHandler, null);
    }

    public IndexHandler waitForRemoteIndexAquired(BlockExchangeConnectionHandler connectionHandler, @Nullable Long timeoutSecs) throws InterruptedException {
        long timeoutMillis = firstNonNull(timeoutSecs, DEFAULT_INDEX_TIMEOUT) * 1000;
        synchronized (indexWaitLock) {
            while (!isRemoteIndexAquired(connectionHandler)) {
                indexWaitLock.wait(timeoutMillis);
                checkArgument(connectionHandler.getLastActive() < timeoutMillis
                    || getLastActive() < timeoutMillis, "unable to aquire index from connection %s, timeout reached!", connectionHandler);
            }
        }
        logger.debug("aquired all indexes on connection {}", connectionHandler);
        return this;
    }

    @Subscribe
    public void handleClusterConfigMessageProcessedEvent(ClusterConfigMessageProcessedEvent event) {
        synchronized (writeAccessLock) {
            for (BlockExchageProtos.Folder folderRecord : event.getMessage().getFoldersList()) {
                String folder = folderRecord.getId();
                FolderInfo folderInfo = updateFolderInfo(folder, folderRecord.getLabel());
                logger.debug("aquired folder info from cluster config = {}", folderInfo);
                for (BlockExchageProtos.Device deviceRecord : folderRecord.getDevicesList()) {
                    String deviceId = hashDataToDeviceIdString(deviceRecord.getId().toByteArray());
                    if (deviceRecord.hasIndexId() && deviceRecord.hasMaxSequence()) {
                        IndexInfo folderIndexInfo = updateIndexInfo(folder, deviceId, deviceRecord.getIndexId(), deviceRecord.getMaxSequence(), null);
                        logger.debug("aquired folder index info from cluster config = {}", folderIndexInfo);
                    }
                }
            }
        }
    }

    @Subscribe
    public void handleIndexMessageReceivedEvent(AnyIndexMessageReceivedEvent event) {
        indexMessageProcessor.handleIndexMessageReceivedEvent(event);
    }

    public @Nullable
    FileInfo pushRecord(String folder, BlockExchageProtos.FileInfo bepFileInfo) {
        FileBlocks fileBlocks = null;
        FileInfo.Builder builder = FileInfo.newBuilder()
            .setFolder(folder)
            .setPath(bepFileInfo.getName())
            .setLastModified(new Date(bepFileInfo.getModifiedS() * 1000 + bepFileInfo.getModifiedNs() / 1000000))
            .setVersionList(Iterables.transform(firstNonNull(bepFileInfo.hasVersion() ? bepFileInfo.getVersion().getCountersList() : null, Collections.<BlockExchageProtos.Counter>emptyList()), new Function<BlockExchageProtos.Counter, Version>() {
                @Override
                public Version apply(BlockExchageProtos.Counter record) {
                    return new FileInfo.Version(record.getId(), record.getValue());
                }
            }))
            .setDeleted(bepFileInfo.getDeleted());
        switch (bepFileInfo.getType()) {
            case FILE:
                fileBlocks = new FileBlocks(folder, builder.getPath(), Iterables.transform(firstNonNull(bepFileInfo.getBlocksList(), Collections.<BlockExchageProtos.BlockInfo>emptyList()), new Function<BlockExchageProtos.BlockInfo, BlockInfo>() {
                    @Override
                    public BlockInfo apply(BlockExchageProtos.BlockInfo record) {
                        return new BlockInfo(record.getOffset(), record.getSize(), BaseEncoding.base16().encode(record.getHash().toByteArray()));
                    }
                }));
                builder
                    .setTypeFile()
                    .setHash(fileBlocks.getHash())
                    .setSize(bepFileInfo.getSize());
                break;
            case DIRECTORY:
                builder.setTypeDir();
                break;
            default:
                logger.warn("unsupported file type = {}, discarding file info", bepFileInfo.getType());
                return null;
        }
        return addRecord(builder.build(), fileBlocks);
    }

    private IndexInfo updateIndexInfo(final String folder, final String deviceId, @Nullable Long indexId, @Nullable Long maxSequence, @Nullable Long localSequence) {
        synchronized (writeAccessLock) {
            IndexInfo indexSequenceInfo = indexRepository.findIndexInfoByDeviceAndFolder(deviceId, folder);
            IndexInfo.Builder builder;
            if (indexSequenceInfo == null) {
                checkNotNull(indexId, "index sequence info not found, and supplied null index id (folder = %s, device = %s)", folder, deviceId);
                builder = IndexInfo.newBuilder()
                    .setFolder(folder)
                    .setDeviceId(deviceId)
                    .setIndexId(indexId)
                    .setLocalSequence(0)
                    .setMaxSequence(-1);
            } else {
                builder = indexSequenceInfo.copyBuilder();
            }
            if (indexId != null) {
                builder.setIndexId(indexId);
            }
            if (maxSequence != null && maxSequence > builder.getMaxSequence()) {
                builder.setMaxSequence(maxSequence);
            }
            if (localSequence != null && localSequence > builder.getLocalSequence()) {
                builder.setLocalSequence(localSequence);
            }
            indexSequenceInfo = builder.build();
            indexRepository.updateIndexInfo(indexSequenceInfo);
            return indexSequenceInfo;
        }
    }

    private @Nullable
    FileInfo addRecord(final FileInfo record, @Nullable final FileBlocks fileBlocks) {
        synchronized (writeAccessLock) {
            Date lastModified = indexRepository.findFileInfoLastModified(record.getFolder(), record.getPath());
            if (lastModified != null && !record.getLastModified().after(lastModified)) {
                logger.trace("discarding record = {}, modified before local record", record);
                return null;
            } else {
                indexRepository.updateFileInfo(record, fileBlocks);
                logger.trace("loaded new record = {}", record);
                eventBus.post(new IndexChangedEvent() {
                    @Override
                    public String getFolder() {
                        return record.getFolder();
                    }

                    @Override
                    public List<FileInfo> getNewRecords() {
                        return Collections.singletonList(record);
                    }
                });
                return record;
            }
        }
    }

    public IndexBrowser.Builder newIndexBrowserBuilder() {
        return IndexBrowser.newBuilder().setIndexHandler(this).setIndexRepository(indexRepository);
    }

    public IndexFinder.Builder newIndexFinderBuilder() {
        return IndexFinder.newBuilder().setIndexRepository(indexRepository);
    }

    public @Nullable
    FileInfo getFileInfoByPath(String folder, String path) {
        return indexRepository.findFileInfo(folder, path);
    }

    public @Nullable
    Pair<FileInfo, FileBlocks> getFileInfoAndBlocksByPath(String folder, String path) {
        FileInfo fileInfo = getFileInfoByPath(folder, path);
        if (fileInfo == null) {
            return null;
        } else {
            checkArgument(fileInfo.isFile());
            FileBlocks fileBlocks = indexRepository.findFileBlocks(folder, path);
            checkNotNull(fileBlocks, "file blocks not found for file info = %s", fileInfo);
            return Pair.of(fileInfo, fileBlocks);
        }
    }

    private FolderInfo updateFolderInfo(String folder, @Nullable String label) {
        FolderInfo folderInfo = folderInfoByFolder.get(folder);
        if (folderInfo == null || !Strings.isNullOrEmpty(label)) {
            folderInfo = new FolderInfo(folder, label);
            folderInfoByFolder.put(folderInfo.getFolder(), folderInfo);
//            configuration.addFolders(folderInfo); //update folders in config; not needed, added by connectionHandler
        }
        return folderInfo;
    }

    public FolderInfo getFolderInfo(String folder) {
        return folderInfoByFolder.get(folder);
    }

    public IndexInfo getIndexInfo(String device, String folder) {
        return indexRepository.findIndexInfoByDeviceAndFolder(device, folder);
    }

    public IndexRepository getIndexRepository() {
        return indexRepository;
    }

    public Sequencer getSequencer() {
        return indexRepository.getSequencer();
    }

    public List<String> getFolderList() {
        return Lists.newArrayList(folderInfoByFolder.keySet());
    }

    public List<FolderInfo> getFolderInfoList() {
        return Lists.newArrayList(folderInfoByFolder.values());
    }

    public FolderBrowser newFolderBrowser() {
        return new FolderBrowser(this);
    }

    @Override
    public void close() {
        indexMessageProcessor.stop();
    }

    public abstract class IndexRecordAquiredEvent {

        public abstract String getFolder();

        public abstract List<FileInfo> getNewRecords();

        public abstract IndexInfo getIndexInfo();

    }

    public abstract class IndexChangedEvent {

        public abstract String getFolder();

        public abstract List<FileInfo> getNewRecords();

    }

    public abstract class RemoteIndexAquiredEvent {

        public abstract String getFolder();

    }

    private class IndexMessageProcessor {

        private final Logger logger = LoggerFactory.getLogger(getClass());
        private final ExecutorService executorService = Executors.newSingleThreadExecutor();
        private int queuedMessages = 0;

        private synchronized void handleIndexMessageReceivedEvent(AnyIndexMessageReceivedEvent event) {
            logger.debug("received index message event, preparing (queue size = {})", queuedMessages);
            markActive();
            if (queuedMessages > 0) {
                storeAndProcessBg(event);
            } else {
                processBg(event);
            }
        }

        private synchronized void processBg(final AnyIndexMessageReceivedEvent event) {
            logger.debug("received index message event, queuing for processing");
            queuedMessages++;
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        doHandleIndexMessageReceivedEvent(BlockExchageProtos.IndexUpdate.newBuilder()
                            .addAllFiles(event.getFilesList())
                            .setFolder(event.getFolder())
                            .build(), event.getConnectionHandler());
                    } catch (Exception ex) {
                        logger.error("error processing index message", ex);
                    }
                    queuedMessages--;
                }

            });
        }

        private synchronized void storeAndProcessBg(AnyIndexMessageReceivedEvent event) {
            final File tempFile = createTempFile(configuration);
            final BlockExchangeConnectionHandler connectionHandler = event.getConnectionHandler();
            try (OutputStream out = new FileOutputStream(tempFile)) {
                BlockExchageProtos.IndexUpdate.newBuilder()
                    .addAllFiles(event.getFilesList())
                    .setFolder(event.getFolder())
                    .build().writeTo(out);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            logger.debug("received index message event, stored to temp file {}, queuing for processing", tempFile);
            queuedMessages++;
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        doHandleIndexMessageReceivedEvent(tempFile, connectionHandler);
                    } catch (Exception ex) {
                        logger.error("error processing index message", ex);
                    }
                    queuedMessages--;
                }

            });
        }

        private boolean isVersionOlderThanSequence(BlockExchageProtos.FileInfo fileInfo, long localSequence) {
            long fileSequence = fileInfo.getSequence();
            //TODO should we check last version instead of sequence? verify
            return fileSequence < localSequence;
        }

        private void doHandleIndexMessageReceivedEvent(File file, BlockExchangeConnectionHandler connectionHandler) throws IOException {
            synchronized (IndexHandler.this) {
                logger.debug("processing index message event from temp file {}", file);
                try (InputStream in = new FileInputStream(file)) {
                    markActive();
                    BlockExchageProtos.IndexUpdate message = BlockExchageProtos.IndexUpdate.parseFrom(in);
                    doHandleIndexMessageReceivedEvent(message, connectionHandler);
                } finally {
                    FileUtils.deleteQuietly(file);
                }
            }
        }

        private void doHandleIndexMessageReceivedEvent(BlockExchageProtos.IndexUpdate message, BlockExchangeConnectionHandler connectionHandler) throws IOException {
//            synchronized (writeAccessLock) {
            logger.debug("processing index message event ");
            String deviceId = connectionHandler.getDeviceId();
            final String folder = message.getFolder();
            long sequence = -1;
            final List<FileInfo> newRecords = Lists.newArrayList();
//                IndexInfo oldIndexInfo = indexRepository.findIndexInfoByDeviceAndFolder(deviceId, folder);
            Stopwatch stopwatch = Stopwatch.createStarted();
            logger.debug("processing {} index records for folder {}", message.getFilesList().size(), folder);
            for (BlockExchageProtos.FileInfo fileInfo : (List<BlockExchageProtos.FileInfo>) message.getFilesList()) {
                markActive();
//                    if (oldIndexInfo != null && isVersionOlderThanSequence(fileInfo, oldIndexInfo.getLocalSequence())) {
//                        logger.trace("skipping file {}, version older than sequence {}", fileInfo, oldIndexInfo.getLocalSequence());
//                    } else {
                try {
                    FileInfo newRecord = pushRecord(folder, fileInfo);
                    if (newRecord != null) {
                        newRecords.add(newRecord);
                    }
                } catch (Exception ex) {
                    logger.warn("error processing file record = {}, discarding", fileInfo);
                    logger.warn("error", ex);
                }
                sequence = Math.max(fileInfo.getSequence(), sequence);
                markActive();
//                    }
            }
            final IndexInfo newIndexInfo = updateIndexInfo(folder, deviceId, null, null, sequence);
            logger.info("processed {} index records, aquired {} ({} secs)", message.getFilesList().size(), newRecords.size(), stopwatch.elapsed(TimeUnit.MILLISECONDS) / 1000d);
            if (logger.isInfoEnabled() && newRecords.size() <= 10) {
                for (FileInfo fileInfo : newRecords) {
                    logger.info("aquired record = {}", fileInfo);
                }
            }
            if (!newRecords.isEmpty()) {
                eventBus.post(new IndexRecordAquiredEvent() {
                    @Override
                    public String getFolder() {
                        return folder;
                    }

                    @Override
                    public List<FileInfo> getNewRecords() {
                        return newRecords;
                    }

                    @Override
                    public IndexInfo getIndexInfo() {
                        return newIndexInfo;
                    }
                });
            }
            logger.debug("index info = {}", newIndexInfo);
            if (isRemoteIndexAquired(connectionHandler)) {
                logger.debug("index aquired");
                eventBus.post(new RemoteIndexAquiredEvent() {
                    @Override
                    public String getFolder() {
                        return folder;
                    }
                });
            }
//                IndexHandler.this.notifyAll();
            markActive();
            synchronized (indexWaitLock) {
                indexWaitLock.notifyAll();
            }
//            }
        }

        public void stop() {
            logger.info("stopping index record processor");
            executorService.shutdown();
            ExecutorUtils.awaitTerminationSafe(executorService);
        }

    }
}
