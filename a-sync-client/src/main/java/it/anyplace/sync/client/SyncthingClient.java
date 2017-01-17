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
package it.anyplace.sync.client;

import it.anyplace.sync.core.configuration.ConfigurationService;
import static com.google.common.base.Objects.equal;
import com.google.common.collect.Lists;
import it.anyplace.sync.core.beans.DeviceAddress;
import it.anyplace.sync.core.beans.FileInfo;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.Closeable;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.Subscribe;
import com.google.common.base.Supplier;
import javax.annotation.Nullable;
import it.anyplace.sync.core.cache.BlockCache;
import java.util.concurrent.atomic.AtomicBoolean;
import it.anyplace.sync.bep.BlockExchangeConnectionHandler;
import it.anyplace.sync.bep.BlockPuller;
import it.anyplace.sync.bep.BlockPuller.FileDownloadObserver;
import it.anyplace.sync.bep.BlockPusher;
import it.anyplace.sync.bep.BlockPusher.FileUploadObserver;
import it.anyplace.sync.bep.IndexHandler;
import it.anyplace.sync.core.beans.FileBlocks;
import org.apache.commons.lang3.tuple.Pair;
import it.anyplace.sync.core.beans.FolderInfo;
import it.anyplace.sync.repository.repo.SqlRepository;
import it.anyplace.sync.discovery.DiscoveryHandler;
import com.google.common.collect.Sets;
import it.anyplace.sync.discovery.DeviceAddressSupplier;
import java.util.Set;
import it.anyplace.sync.devices.DevicesHandler;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 *
 * @author aleph
 */
public class SyncthingClient implements Closeable {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ConfigurationService configuration;
    private final DiscoveryHandler discoveryHandler;
    private final SqlRepository sqlRepository;
    private final IndexHandler indexHandler;
    private final List<BlockExchangeConnectionHandler> connections = Collections.synchronizedList(Lists.<BlockExchangeConnectionHandler>newArrayList());
    private final List<BlockExchangeConnectionHandler> pool = Lists.<BlockExchangeConnectionHandler>newArrayList();
    private final DevicesHandler devicesHandler;

    public SyncthingClient(ConfigurationService configuration) {
        this.configuration = configuration;
        this.sqlRepository = new SqlRepository(configuration);
        indexHandler = new IndexHandler(configuration, sqlRepository);
        discoveryHandler = new DiscoveryHandler(configuration, sqlRepository);
        devicesHandler = new DevicesHandler(configuration);
        discoveryHandler.getEventBus().register(devicesHandler);
    }

    public void clearCacheAndIndex() {
        logger.info("clear cache");
        indexHandler.clearIndex();
        configuration.edit().setFolders(Collections.<FolderInfo>emptyList()).persistLater();
        BlockCache.getBlockCache(configuration).clear();
    }

    public DevicesHandler getDevicesHandler() {
        return devicesHandler;
    }

    private @Nullable
    BlockExchangeConnectionHandler borrowFromPool(final DeviceAddress deviceAddress) {
        synchronized (pool) {
            BlockExchangeConnectionHandler connectionHandler = Iterables.find(pool, new Predicate<BlockExchangeConnectionHandler>() {
                @Override
                public boolean apply(BlockExchangeConnectionHandler input) {
                    return equal(deviceAddress, input.getAddress());
                }
            }, null);
            if (connectionHandler != null) {
                pool.remove(connectionHandler);
                if (connectionHandler.isClosed()) { //TODO check live
                    return borrowFromPool(deviceAddress);
                } else {
                    return connectionHandler;
                }
            } else {
                return null;
            }
        }
    }

    private void returnToPool(BlockExchangeConnectionHandler connectionHandler) {
        synchronized (pool) {
            if (!connectionHandler.isClosed()) {
                pool.add(connectionHandler);
            }
        }
    }

    private BlockExchangeConnectionHandler openConnection(DeviceAddress deviceAddress) throws Exception {
        final BlockExchangeConnectionHandler connectionHandler = new BlockExchangeConnectionHandler(configuration, deviceAddress);
        connectionHandler.setIndexHandler(indexHandler);
        connectionHandler.getEventBus().register(indexHandler);
        connectionHandler.getEventBus().register(devicesHandler);
        final AtomicBoolean shouldRestartForNewFolder = new AtomicBoolean(false);
        connectionHandler.getEventBus().register(new Object() {
            @Subscribe
            public void handleConnectionClosedEvent(BlockExchangeConnectionHandler.ConnectionClosedEvent event) {
                connections.remove(connectionHandler);
                synchronized (pool) {
                    pool.remove(connectionHandler);
                }
            }

            @Subscribe
            public void handleNewFolderSharedEvent(BlockExchangeConnectionHandler.NewFolderSharedEvent event) {
                shouldRestartForNewFolder.set(true);
            }
        });
        connectionHandler.connect();
        connections.add(connectionHandler);
        if (shouldRestartForNewFolder.get()) {
            logger.info("restart connection for new folder shared");
            connectionHandler.close();
            return openConnection(deviceAddress);
        } else {
            return connectionHandler;
        }
    }

    public BlockExchangeConnectionHandler getConnection(DeviceAddress deviceAddress) throws Exception {
        BlockExchangeConnectionHandler connectionHandlerFromPool = borrowFromPool(deviceAddress);
        if (connectionHandlerFromPool != null) {
            return connectionHandlerFromPool;
        } else {
            return openConnection(deviceAddress);
        }
    }

    public BlockExchangeConnectionHandler connectToBestPeer() throws Exception {
        try (DeviceAddressSupplier deviceAddressSupplier = discoveryHandler.newDeviceAddressSupplier()) {
            for (DeviceAddress deviceAddress : deviceAddressSupplier) {
                try {
                    logger.debug("connecting to device = {}", deviceAddress);
                    BlockExchangeConnectionHandler connectionHandler = getConnection(deviceAddress);
                    logger.info("aquired connection to device = {}", deviceAddress);
                    return connectionHandler;
                } catch (Exception ex) {
                    logger.warn("error connecting to device = {}", deviceAddress);
                    logger.warn("error connecting to device", ex);
                }
            }
        }
        throw new RuntimeException("unable to aquire connection");
    }

//    public List<DeviceAddress> getPeerAddressesRanked() {
//        logger.debug("retrieving all peer addresses");
//        List<DeviceAddress> list = Lists.newArrayList();
//        for (String deviceId : configuration.getPeerIds()) {
//            list.addAll(globalDiscoveryHandler.query(deviceId));
//        }
//        Iterables.addAll(list, Iterables.filter(localDiscorveryHandler.waitForAddresses().getDeviceAddresses(), new Predicate<DeviceAddress>() {
//            @Override
//            public boolean apply(DeviceAddress a) {
//                return configuration.getPeerIds().contains(a.getDeviceId());
//            }
//        }));
//        list = AddressRanker.testAndRank(list);
//        logger.info("peer addresses = \n\n{}\n", AddressRanker.dumpAddressRanking(list));
//        return list;
//    }
    /**
     * the supplier throws npe if connection cannot be aquired
     *
     * @return
     */
    public Supplier<BlockExchangeConnectionHandler> getPeerConnectionsSupplier() {
        return new Supplier<BlockExchangeConnectionHandler>() {
            private final Supplier<BlockExchangeConnectionHandler> supplier = getPeerConnectionsSupplierSafe();

            @Override
            public BlockExchangeConnectionHandler get() {
                BlockExchangeConnectionHandler connectionHandler = supplier.get();
                checkNotNull(connectionHandler, "unable to aquire connection");
                return connectionHandler;
            }
        };
    }

    /**
     *
     * the supplier returns null if connection cannot be aquired
     *
     * @return
     */
    public Supplier<BlockExchangeConnectionHandler> getPeerConnectionsSupplierSafe() {
        return new Supplier<BlockExchangeConnectionHandler>() {

            private final DeviceAddressSupplier deviceAddresses = discoveryHandler.newDeviceAddressSupplier(); //TODO close after use
            private final Set<String> deviceIds = Sets.newHashSet();

            @Override
            public BlockExchangeConnectionHandler get() {
                for (final DeviceAddress deviceAddress : deviceAddresses) {
                    if (deviceIds.contains(deviceAddress.getDeviceId())) {
                        continue;
                    }
                    try {
                        BlockExchangeConnectionHandler connection = getConnection(deviceAddress);
                        deviceIds.add(deviceAddress.getDeviceId());
                        return connection;
                    } catch (Exception ex) {
                        logger.warn("error connecting to device = {}", deviceAddress);
                        logger.warn("error connecting to device", ex);
                    }
                }
                return null;
            }
        };
    }

    public IndexHandler waitForRemoteIndexAquired() throws InterruptedException {
        Supplier<BlockExchangeConnectionHandler> supplier = getPeerConnectionsSupplierSafe();
        while (true) {
            try (BlockExchangeConnectionHandler connection = supplier.get()) {
                if (connection == null) {
                    return indexHandler;
                } else {
                    try {
                        indexHandler.waitForRemoteIndexAquired(connection);
                    } catch (Exception ex) {
                        logger.warn("exception while waiting for index", ex);
                    }
                }
            }
        }
    }

    public BlockExchangeConnectionHandler getConnectionForFolder(String folder) {
        Supplier<BlockExchangeConnectionHandler> supplier = getPeerConnectionsSupplier();
        while (true) {
            BlockExchangeConnectionHandler connectionHandler = supplier.get();
            if (connectionHandler.hasFolder(folder)) {
                return connectionHandler;
            } else {
                connectionHandler.close();
            }
        }
    }

    public FileDownloadObserver pullFile(BlockExchangeConnectionHandler connectionHandler, String folder, String path) throws InterruptedException {
        Pair<FileInfo, FileBlocks> fileInfoAndBlocks = indexHandler.waitForRemoteIndexAquired(connectionHandler).getFileInfoAndBlocksByPath(folder, path);
        checkNotNull(fileInfoAndBlocks, "file not found in local index for folder = %s path = %s", folder, path);
        return new BlockPuller(configuration, connectionHandler).pullBlocks(fileInfoAndBlocks.getValue());
    }

    public FileDownloadObserver pullFile(String folder, String path) throws InterruptedException {
        BlockExchangeConnectionHandler connectionHandler = getConnectionForFolder(folder);
        Pair<FileInfo, FileBlocks> fileInfoAndBlocks = indexHandler.waitForRemoteIndexAquired(connectionHandler).getFileInfoAndBlocksByPath(folder, path);
        checkNotNull(fileInfoAndBlocks, "file not found in local index for folder = %s path = %s", folder, path);
        return new BlockPuller(configuration, connectionHandler, true).pullBlocks(fileInfoAndBlocks.getValue());
    }

    public FileUploadObserver pushFile(InputStream data, String folder, String path) throws InterruptedException {
        BlockExchangeConnectionHandler connectionHandler = getConnectionForFolder(folder);
        return new BlockPusher(configuration, connectionHandler, true).withIndexHandler(indexHandler).pushFile(data, indexHandler.waitForRemoteIndexAquired(connectionHandler).getFileInfoByPath(folder, path), folder, path);
    }

    public BlockPusher.IndexEditObserver pushDir(String folder, String path) throws InterruptedException {
        BlockExchangeConnectionHandler connectionHandler = getConnectionForFolder(folder);
        return new BlockPusher(configuration, connectionHandler, true).withIndexHandler(indexHandler).pushDir(folder, path);
    }

    public BlockPusher.IndexEditObserver pushDelete(String folder, String path) throws InterruptedException {
        BlockExchangeConnectionHandler connectionHandler = getConnectionForFolder(folder);
        return new BlockPusher(configuration, connectionHandler, true).withIndexHandler(indexHandler).pushDelete(indexHandler.waitForRemoteIndexAquired(connectionHandler).getFileInfoByPath(folder, path), folder, path);
    }

    public DiscoveryHandler getDiscoveryHandler() {
        return discoveryHandler;
    }

    public IndexHandler getIndexHandler() {
        return indexHandler;
    }

    @Override
    public void close() {
        devicesHandler.close();
        discoveryHandler.close();
        for (BlockExchangeConnectionHandler connectionHandler : Lists.newArrayList(connections)) {
            connectionHandler.close();
        }
        indexHandler.close();
        sqlRepository.close();
    }

}
