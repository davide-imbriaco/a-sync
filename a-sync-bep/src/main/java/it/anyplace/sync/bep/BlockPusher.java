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
import static com.google.common.base.Objects.equal;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import com.google.protobuf.ByteString;
import it.anyplace.sync.core.configuration.ConfigurationService;
import it.anyplace.sync.bep.protos.BlockExchageProtos.BlockInfo;
import it.anyplace.sync.bep.protos.BlockExchageProtos.Counter;
import it.anyplace.sync.bep.protos.BlockExchageProtos.IndexUpdate;
import it.anyplace.sync.bep.protos.BlockExchageProtos.Response;
import it.anyplace.sync.bep.protos.BlockExchageProtos.Vector;
import it.anyplace.sync.bep.BlockExchangeConnectionHandler.RequestMessageReceivedEvent;
import static it.anyplace.sync.core.security.KeystoreHandler.deviceIdStringToHashData;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.io.Closeable;
import java.util.concurrent.atomic.AtomicReference;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import it.anyplace.sync.core.beans.FileInfo;
import it.anyplace.sync.core.beans.FileInfo.Version;
import it.anyplace.sync.bep.protos.BlockExchageProtos.FileInfoType;
import java.util.Collections;
import javax.annotation.Nullable;
import java.io.IOException;
import org.apache.commons.lang3.tuple.Pair;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import org.apache.commons.io.FileUtils;
import static it.anyplace.sync.core.utils.FileUtils.createTempFile;
import it.anyplace.sync.core.utils.BlockUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 *
 * @author aleph
 */
public class BlockPusher {

    public final static int BLOCK_SIZE = 128 * 1024;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ConfigurationService configuration;
    private final BlockExchangeConnectionHandler connectionHandler;
    private IndexHandler indexHandler;
    private boolean closeConnection = false;

    public BlockPusher(ConfigurationService configuration, BlockExchangeConnectionHandler connectionHandler) {
        this.configuration = configuration;
        this.connectionHandler = connectionHandler;
    }

    public BlockPusher(ConfigurationService configuration, BlockExchangeConnectionHandler connectionHandler, boolean closeConnection) {
        this(configuration, connectionHandler);
        this.closeConnection = closeConnection;
    }

    public BlockPusher withIndexHandler(IndexHandler indexHandler) {
        this.indexHandler = indexHandler;
        return this;
    }

    public IndexEditObserver pushDelete(FileInfo fileInfo, String folder, String path) {
        checkArgument(connectionHandler.hasFolder(fileInfo.getFolder()), "supplied connection handler %s will not share folder %s", connectionHandler, fileInfo.getFolder());
        checkNotNull(fileInfo, "must provide file info for delete of path = %s", path);
        return new IndexEditObserver(sendIndexUpdate(folder, BlockExchageProtos.FileInfo.newBuilder()
            .setName(path)
            .setType(FileInfoType.valueOf(fileInfo.getType().name()))
            .setDeleted(true), fileInfo.getVersionList()));
    }

    public IndexEditObserver pushDir(String folder, String path) {
        checkArgument(connectionHandler.hasFolder(folder), "supplied connection handler %s will not share folder %s", connectionHandler, folder);
        return new IndexEditObserver(sendIndexUpdate(folder, BlockExchageProtos.FileInfo.newBuilder()
            .setName(path)
            .setType(BlockExchageProtos.FileInfoType.DIRECTORY), null));
    }

    public FileUploadObserver pushFile(InputStream inputStream, @Nullable FileInfo fileInfo, final String folder, final String path) {
        try {
            File tempFile = createTempFile(configuration);
            FileUtils.copyInputStreamToFile(inputStream, tempFile);
            logger.debug("use temp file = {} {}", tempFile, FileUtils.byteCountToDisplaySize(tempFile.length()));
            return pushFile(new FileDataSource(tempFile), fileInfo, folder, path); //TODO temp file cleanup on complete
            //TODO use mem source on small file
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public FileUploadObserver pushFile(final DataSource dataSource, @Nullable FileInfo fileInfo, final String folder, final String path) {
        checkArgument(connectionHandler.hasFolder(folder), "supplied connection handler %s will not share folder %s", connectionHandler, folder);
        checkArgument(fileInfo == null || equal(fileInfo.getFolder(), folder));
        checkArgument(fileInfo == null || equal(fileInfo.getPath(), path));
        try {
            final ExecutorService monitoringProcessExecutorService = Executors.newCachedThreadPool();
            final long fileSize = dataSource.getSize();
            final Set<String> sentBlocks = Sets.newConcurrentHashSet();
            final AtomicReference<Exception> uploadError = new AtomicReference<>();
            final AtomicBoolean isCompleted = new AtomicBoolean(false);
            final Object updateLock = new Object();
            final Object listener = new Object() {
                @Subscribe
                public void handleRequestMessageReceivedEvent(RequestMessageReceivedEvent event) {
                    BlockExchageProtos.Request request = event.getMessage();
                    if (equal(request.getFolder(), folder) && equal(request.getName(), path)) {
                        try {
                            final String hash = BaseEncoding.base16().encode(request.getHash().toByteArray());
                            logger.debug("handling block request = {}:{}-{} ({})", request.getName(), request.getOffset(), request.getSize(), hash);
                            byte[] data = dataSource.getBlock(request.getOffset(), request.getSize(), hash);
                            checkNotNull(data, "data not found for hash = %s", hash);
                            final Future future = connectionHandler.sendMessage(Response.newBuilder()
                                .setCode(BlockExchageProtos.ErrorCode.NO_ERROR)
                                .setData(ByteString.copyFrom(data))
                                .setId(request.getId())
                                .build());
                            monitoringProcessExecutorService.submit(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        future.get();
                                        sentBlocks.add(hash);
                                        synchronized (updateLock) {
                                            updateLock.notifyAll();
                                        }
                                        //TODO retry on error, register error and throw on watcher
                                    } catch (InterruptedException ex) {
                                        //return and do nothing
                                    } catch (ExecutionException ex) {
                                        uploadError.set(ex);
                                        synchronized (updateLock) {
                                            updateLock.notifyAll();
                                        }
                                    }
                                }
                            });
                        } catch (Exception ex) {
                            logger.error("error handling block request", ex);
                            connectionHandler.sendMessage(Response.newBuilder()
                                .setCode(BlockExchageProtos.ErrorCode.GENERIC)
                                .setId(request.getId())
                                .build());
                            uploadError.set(ex);
                            synchronized (updateLock) {
                                updateLock.notifyAll();
                            }
                        }
                    }
                }
            };
            connectionHandler.getEventBus().register(listener);
            logger.debug("send index update for file = {}", path);
            final Object indexListener = new Object() {

                @Subscribe
                public void handleIndexRecordAquiredEvent(IndexHandler.IndexRecordAquiredEvent event) {
                    if (equal(event.getFolder(), folder)) {
                        for (FileInfo fileInfo : event.getNewRecords()) {
                            if (equal(fileInfo.getPath(), path) && equal(fileInfo.getHash(), dataSource.getHash())) { //TODO check not invalid
//                                sentBlocks.addAll(dataSource.getHashes());
                                isCompleted.set(true);
                                synchronized (updateLock) {
                                    updateLock.notifyAll();
                                }
                            }
                        }
                    }
                }
            };
            if (indexHandler != null) {
                indexHandler.getEventBus().register(indexListener);
            }
            final IndexUpdate indexUpdate = sendIndexUpdate(folder, BlockExchageProtos.FileInfo.newBuilder()
                .setName(path)
                .setSize(fileSize)
                .setType(BlockExchageProtos.FileInfoType.FILE)
                .addAllBlocks(dataSource.getBlocks()), fileInfo == null ? null : fileInfo.getVersionList()).getRight();
            final FileUploadObserver messageUploadObserver = new FileUploadObserver() {
                @Override
                public void close() {
                    logger.debug("closing upload process");
                    try {
                        connectionHandler.getEventBus().unregister(listener);
                        monitoringProcessExecutorService.shutdown();
                        if (indexHandler != null) {
                            indexHandler.getEventBus().unregister(indexListener);
                        }
                    } catch (Exception ex) {
                    }
                    if (closeConnection && connectionHandler != null) {
                        connectionHandler.close();
                    }
                    if (indexHandler != null) {
                        FileInfo fileInfo = indexHandler.pushRecord(indexUpdate.getFolder(), Iterables.getOnlyElement(indexUpdate.getFilesList()));
                        logger.info("sent file info record = {}", fileInfo);
                    }
                }

                @Override
                public double getProgress() {
                    return isCompleted() ? 1d : sentBlocks.size() / ((double) dataSource.getHashes().size());
                }

                @Override
                public String getProgressMessage() {
                    return (Math.round(getProgress() * 1000d) / 10d) + "% " + sentBlocks.size() + "/" + dataSource.getHashes().size();
                }

                @Override
                public boolean isCompleted() {
//                    return sentBlocks.size() == dataSource.getHashes().size();
                    return isCompleted.get();
                }

                @Override
                public double waitForProgressUpdate() throws InterruptedException {
                    synchronized (updateLock) {
                        updateLock.wait();
                    }
                    if (uploadError.get() != null) {
                        throw new RuntimeException(uploadError.get());
                    }
                    return getProgress();
                }

                @Override
                public DataSource getDataSource() {
                    return dataSource;
                }

            };
            return messageUploadObserver;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private Pair<Future, IndexUpdate> sendIndexUpdate(String folder, BlockExchageProtos.FileInfo.Builder fileInfoBuilder, @Nullable Iterable<Version> oldVersions) {
        {
            long nextSequence = indexHandler.getSequencer().nextSequence();
            final List<Version> list = Lists.newArrayList(firstNonNull(oldVersions, Collections.<Version>emptyList()));
            logger.debug("version list = {}", list);
            final long id = ByteBuffer.wrap(deviceIdStringToHashData(configuration.getDeviceId())).getLong();
            Counter version = Counter.newBuilder()
                .setId(id)
                .setValue(nextSequence)
                .build();
            logger.debug("append new version = {}", version);
            fileInfoBuilder
                .setSequence(nextSequence)
                .setVersion(Vector.newBuilder().addAllCounters(Iterables.transform(list, new Function<Version, Counter>() {
                    @Override
                    public Counter apply(Version record) {
                        return Counter.newBuilder().setId(record.getId()).setValue(record.getValue()).build();
                    }
                })).addCounters(version));
        }
        Date lastModified = new Date();
        BlockExchageProtos.FileInfo fileInfo = fileInfoBuilder
            .setModifiedS(lastModified.getTime() / 1000)
            .setModifiedNs((int) ((lastModified.getTime() % 1000) * 1000000))
            .setNoPermissions(true)
            .build();
        IndexUpdate indexUpdate = IndexUpdate.newBuilder()
            .setFolder(folder)
            .addFiles(fileInfo)
            .build();
        logger.debug("index update = {}", fileInfo);
        return Pair.of(connectionHandler.sendMessage(indexUpdate), indexUpdate);
    }

    public abstract class FileUploadObserver implements Closeable {

        public abstract double getProgress();

        public abstract String getProgressMessage();

        public abstract boolean isCompleted();

        public abstract double waitForProgressUpdate() throws InterruptedException;

        public FileUploadObserver waitForComplete() throws InterruptedException {
            while (!isCompleted()) {
                waitForProgressUpdate();
            }
            return this;
        }

        public abstract DataSource getDataSource();
    }

    public class IndexEditObserver implements Closeable {

        private final Future future;
        private final IndexUpdate indexUpdate;

        public IndexEditObserver(Future future, IndexUpdate indexUpdate) {
            checkNotNull(future);
            checkNotNull(indexUpdate);
            this.future = future;
            this.indexUpdate = indexUpdate;
        }

        public IndexEditObserver(Pair<Future, IndexUpdate> pair) {
            this(pair.getLeft(), pair.getRight());
        }

        public IndexUpdate getIndexUpdate() {
            return indexUpdate;
        }

        public boolean isCompleted() {
            if (future.isDone()) {
                try {
                    future.get();
                } catch (InterruptedException | ExecutionException ex) {
                    throw new RuntimeException(ex); //throw exception if job has errors
                }
                return true;
            } else {
                return false;
            }
        }

        public void waitForComplete() throws InterruptedException {
            try {
                future.get();
            } catch (ExecutionException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public void close() throws IOException {
            if (indexHandler != null) {
                indexHandler.pushRecord(indexUpdate.getFolder(), Iterables.getOnlyElement(indexUpdate.getFilesList()));
            }
            if (closeConnection) {
                connectionHandler.close();
            }
        }

    }

    public static class ByteArrayDataSource extends DataSource {

        private final byte[] data;

        public ByteArrayDataSource(byte[] data) {
            this.data = data;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(data);
        }

    }

    public static class FileDataSource extends DataSource {

        private final File file;

        public FileDataSource(File file) {
            this.file = file;
        }

        @Override
        public InputStream getInputStream() {
            try {
                return new FileInputStream(file);
            } catch (FileNotFoundException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public long getSize() {
            if (size == null) {
                size = file.length();
            }
            return size;
        }

    }

    public abstract static class DataSource {

        protected Long size;
        protected List<BlockInfo> blocks;
        protected Set<String> hashes;

        protected void processStream() {
            try (InputStream in = getInputStream()) {
                List<BlockInfo> list = Lists.newArrayList();
                long offset = 0;
                while (true) {
                    byte[] block = new byte[BLOCK_SIZE];
                    int blockSize = in.read(block);
                    if (blockSize <= 0) {
                        break;
                    }
                    if (blockSize < block.length) {
                        block = Arrays.copyOf(block, blockSize);
                    }
                    byte[] hash = Hashing.sha256().hashBytes(block).asBytes();
                    list.add(BlockInfo.newBuilder()
                        .setHash(ByteString.copyFrom(hash))
                        .setOffset(offset)
                        .setSize(blockSize)
                        .build());
                    offset += blockSize;
                }
                size = offset;
                blocks = list;
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        public long getSize() {
            if (size == null) {
                processStream();
            }
            return size;
        }

        public List<BlockInfo> getBlocks() {
            if (blocks == null) {
                processStream();
            }
            return blocks;
        }

        public abstract InputStream getInputStream();

        public byte[] getBlock(long offset, int size, String hash) {
            byte[] buffer = new byte[size];
            try (InputStream in = getInputStream()) {
                IOUtils.skipFully(in, offset);
                IOUtils.readFully(in, buffer);
                checkArgument(equal(BaseEncoding.base16().encode(Hashing.sha256().hashBytes(buffer).asBytes()), hash), "block hash mismatch!");
                return buffer;
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        public Set<String> getHashes() {
            if (hashes == null) {
                hashes = Sets.newHashSet(Iterables.transform(getBlocks(), new Function<BlockInfo, String>() {
                    @Override
                    public String apply(BlockInfo input) {
                        return BaseEncoding.base16().encode(input.getHash().toByteArray());
                    }
                }));
            }
            return hashes;
        }

        private transient String hash;

        public String getHash() {
            if (hash == null) {
                hash = BlockUtils.hashBlocks(Lists.transform(getBlocks(), new Function<BlockInfo, it.anyplace.sync.core.beans.BlockInfo>() {
                    @Override
                    public it.anyplace.sync.core.beans.BlockInfo apply(BlockInfo input) {
                        return new it.anyplace.sync.core.beans.BlockInfo(input.getOffset(), input.getSize(), BaseEncoding.base16().encode(input.getHash().toByteArray()));
                    }
                }));
            }
            return hash;
        }
    }

}
