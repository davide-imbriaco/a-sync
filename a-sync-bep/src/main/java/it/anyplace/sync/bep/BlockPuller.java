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

import com.google.common.base.Function;
import com.google.common.base.Functions;
import static com.google.common.base.Objects.equal;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import com.google.protobuf.ByteString;
import it.anyplace.sync.core.Configuration;
import it.anyplace.sync.core.beans.BlockInfo;
import it.anyplace.sync.bep.protos.BlockExchageProtos.ErrorCode;
import it.anyplace.sync.bep.protos.BlockExchageProtos.Request;
import it.anyplace.sync.bep.BlockExchangeConnectionHandler.ResponseMessageReceivedEvent;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import static com.google.common.base.Preconditions.checkArgument;
import static it.anyplace.sync.bep.BlockPusher.BLOCK_SIZE;
import it.anyplace.sync.core.beans.FileBlocks;
import it.anyplace.sync.core.cache.BlockCache;
import java.io.Closeable;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.io.FileUtils;

/**
 *
 * @author aleph
 */
public class BlockPuller {

    private BlockCache blockCache;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Configuration configuration;
    private final BlockExchangeConnectionHandler connectionHandler;
    private final Map<String, byte[]> blocksByHash = Maps.newConcurrentMap();
    private final List<String> hashList = Lists.newArrayList();
    private final Set<String> missingHashes = Sets.newConcurrentHashSet();
    private final Set<Integer> requestIds = Sets.newConcurrentHashSet();
    private boolean closeConnection = false;

    public BlockPuller(Configuration configuration, BlockExchangeConnectionHandler connectionHandler) {
        this.configuration = configuration;
        this.connectionHandler = connectionHandler;
        this.blockCache = BlockCache.getBlockCache(configuration);
    }

    public BlockPuller(Configuration configuration, BlockExchangeConnectionHandler connectionHandler, boolean closeConnection) {
        this(configuration, connectionHandler);
        this.closeConnection = closeConnection;
    }

    public FileDownloadObserver pullBlocks(FileBlocks fileBlocks) throws InterruptedException {
        logger.info("pulling file = {}", fileBlocks);
        checkArgument(connectionHandler.hasFolder(fileBlocks.getFolder()), "supplied connection handler %s will not share folder %s", connectionHandler, fileBlocks.getFolder());
        final Object lock = new Object();
        final AtomicReference<Exception> error = new AtomicReference<>();
        final Object listener = new Object() {
            @Subscribe
            public void handleResponseMessageReceivedEvent(ResponseMessageReceivedEvent event) {
                synchronized (lock) {
                    try {
                        if (!requestIds.contains(event.getMessage().getId())) {
                            return;
                        }
                        checkArgument(equal(event.getMessage().getCode(), ErrorCode.NO_ERROR), "received error response, code = %s", event.getMessage().getCode());
                        byte[] data = event.getMessage().getData().toByteArray();
                        String hash = BaseEncoding.base16().encode(Hashing.sha256().hashBytes(data).asBytes());
                        blockCache.pushBlock(data);
                        if (missingHashes.remove(hash)) {
                            blocksByHash.put(hash, data);
                            logger.debug("aquired block, hash = {}", hash);
                            lock.notify();
                        } else {
                            logger.warn("received not-needed block, hash = {}", hash);
                        }
                    } catch (Exception ex) {
                        error.set(ex);
                        lock.notify();
                    }
                }
            }
        };
        FileDownloadObserver fileDownloadObserver = new FileDownloadObserver() {

            private long getReceivedData() {
                return blocksByHash.size() * BLOCK_SIZE;
            }

            private long getTotalData() {
                return (blocksByHash.size() + missingHashes.size()) * BLOCK_SIZE;
            }

            @Override
            public double getProgress() {
                return isCompleted() ? 1d : getReceivedData() / ((double) getTotalData());
            }

            @Override
            public String getProgressMessage() {
                return (Math.round(getProgress() * 1000d) / 10d) + "% " + FileUtils.byteCountToDisplaySize(getReceivedData()) + " / " + FileUtils.byteCountToDisplaySize(getTotalData());
            }

            @Override
            public boolean isCompleted() {
                return missingHashes.isEmpty();
            }

            @Override
            public void checkError() {
                if (error.get() != null) {
                    throw new RuntimeException(error.get());
                }
            }

            @Override
            public double waitForProgressUpdate() throws InterruptedException {
                if (!isCompleted()) {
                    synchronized (lock) {
                        checkError();
                        lock.wait();
                        checkError();
                    }
                }
                return getProgress();
            }

            @Override
            public InputStream getInputStream() {
                checkArgument(missingHashes.isEmpty(), "pull failed, some blocks are still missing");
                List<byte[]> blockList = Lists.newArrayList(Lists.transform(hashList, Functions.forMap(blocksByHash)));
                return new SequenceInputStream(Collections.enumeration(Lists.transform(blockList, new Function<byte[], ByteArrayInputStream>() {
                    @Override
                    public ByteArrayInputStream apply(byte[] data) {
                        return new ByteArrayInputStream(data);
                    }
                })));
            }

            @Override
            public void close() {
                missingHashes.clear();
                hashList.clear();
                blocksByHash.clear();
                try {
                    connectionHandler.getEventBus().unregister(listener);
                } catch (Exception ex) {
                }
                if (closeConnection) {
                    connectionHandler.close();
                }
            }
        };
        try {
            synchronized (lock) {
                hashList.addAll(Lists.transform(fileBlocks.getBlocks(), new Function<BlockInfo, String>() {
                    @Override
                    public String apply(BlockInfo block) {
                        return block.getHash();
                    }
                }));
                missingHashes.addAll(hashList);
                for (String hash : missingHashes) {
                    byte[] block = blockCache.pullBlock(hash);
                    if (block != null) {
                        blocksByHash.put(hash, block);
                        missingHashes.remove(hash);
                    }
                }
                connectionHandler.getEventBus().register(listener);
                for (BlockInfo block : fileBlocks.getBlocks()) {
                    if (missingHashes.contains(block.getHash())) {
                        int requestId = Math.abs(new Random().nextInt());
                        requestIds.add(requestId);
                        connectionHandler.sendMessage(Request.newBuilder()
                            .setId(requestId)
                            .setFolder(fileBlocks.getFolder())
                            .setName(fileBlocks.getPath())
                            .setOffset(block.getOffset())
                            .setSize(block.getSize())
                            .setHash(ByteString.copyFrom(BaseEncoding.base16().decode(block.getHash())))
                            .build());
                        logger.debug("sent request for block, hash = {}", block.getHash());
                    }
                }
                return fileDownloadObserver;
            }
        } catch (Exception ex) {
            fileDownloadObserver.close();
            throw ex;
        }
    }

    public abstract class FileDownloadObserver implements Closeable {

        public abstract void checkError();

        public abstract double getProgress();

        public abstract String getProgressMessage();

        public abstract boolean isCompleted();

        public abstract double waitForProgressUpdate() throws InterruptedException;

        public FileDownloadObserver waitForComplete() throws InterruptedException {
            while (!isCompleted()) {
                waitForProgressUpdate();
            }
            return this;
        }

        public abstract InputStream getInputStream();

        @Override
        public abstract void close();

    }

}
