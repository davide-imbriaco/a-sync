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
package it.anyplace.sync.discovery;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import it.anyplace.sync.core.Configuration;
import it.anyplace.sync.core.beans.DeviceAddress;
import it.anyplace.sync.core.interfaces.DeviceAddressRepository;
import it.anyplace.sync.core.utils.ExecutorUtils;
import it.anyplace.sync.discovery.protocol.gd.GlobalDiscoveryHandler;
import it.anyplace.sync.discovery.protocol.ld.LocalDiscorveryHandler;
import it.anyplace.sync.discovery.utils.AddressRanker;
import java.io.Closeable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author aleph
 */
public class DiscoveryHandler implements Closeable {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Configuration configuration;
    private final GlobalDiscoveryHandler globalDiscoveryHandler;
    private final LocalDiscorveryHandler localDiscorveryHandler;
    private final DeviceAddressRepository deviceAddressRepository;
    private final EventBus eventBus = new EventBus();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Map<Pair<String, String>, DeviceAddress> deviceAddressMap = Collections.synchronizedMap(Maps.<Pair<String, String>, DeviceAddress>newHashMap());

    public DiscoveryHandler(Configuration configuration, DeviceAddressRepository deviceAddressRepository) {
        logger.info("init");
        this.configuration = configuration;
        this.deviceAddressRepository = deviceAddressRepository;
        localDiscorveryHandler = new LocalDiscorveryHandler(configuration);
        globalDiscoveryHandler = new GlobalDiscoveryHandler(configuration);
        localDiscorveryHandler.getEventBus().register(new Object() {
            @Subscribe
            public void handleMessageReceivedEvent(LocalDiscorveryHandler.MessageReceivedEvent event) {
                logger.info("received device address list from local discovery");
                processDeviceAddressBg(event.getDeviceAddresses());
            }
        });
    }

    boolean shouldLoadFromDb = true, shouldLoadFromGlobal = true, shouldStartLocalDiscovery = true;

    private void updateAddressesBg() {
        if (shouldLoadFromDb) {
            shouldLoadFromDb = false;
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        List<DeviceAddress> list = DiscoveryHandler.this.deviceAddressRepository.findAllDeviceAddress();
                        logger.info("received device address list from database");
                        processDeviceAddressBg(list);
                    } catch (Exception ex) {
                        logger.error("error loading device addresses from db", ex);
                    }
                }
            });
        }
        if (shouldStartLocalDiscovery) {
            shouldStartLocalDiscovery = false;
            localDiscorveryHandler.startListener();
            localDiscorveryHandler.sendAnnounceMessage();
        }
        if (shouldLoadFromGlobal) {
            shouldLoadFromGlobal = false; //TODO timeout for reload
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (String deviceId : DiscoveryHandler.this.configuration.getPeers()) {
                            List<DeviceAddress> list = globalDiscoveryHandler.query(deviceId);
                            logger.info("received device address list from global discovery");
                            processDeviceAddressBg(list);
                        }
                    } catch (Exception ex) {
                        logger.error("error loading device addresses from db", ex);
                    }
                }

            });
        }
    }

    private void processDeviceAddressBg(final Iterable<DeviceAddress> deviceAddresses) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    logger.info("processing device address list");
                    List<DeviceAddress> list = Lists.newArrayList(deviceAddresses);
                    Iterables.removeIf(list, new Predicate<DeviceAddress>() { //do not process address already processed
                        @Override
                        public boolean apply(DeviceAddress deviceAddress) {
                            return deviceAddressMap.containsKey(Pair.of(deviceAddress.getDeviceId(), deviceAddress.getAddress()));
                        }
                    });
                    list = AddressRanker.testAndRank(list);
                    for (DeviceAddress deviceAddress : list) {
                        putDeviceAddress(deviceAddress);
                    }
                } catch (Exception ex) {
                    logger.error("error processing device addresses", ex);
                }
            }
        });
    }

    private void putDeviceAddress(final DeviceAddress deviceAddress) {
        logger.info("acquired device address = {}", deviceAddress);
        deviceAddressMap.put(Pair.of(deviceAddress.getDeviceId(), deviceAddress.getAddress()), deviceAddress);
        deviceAddressRepository.updateDeviceAddress(deviceAddress);
        eventBus.post(new DeviceAddressUpdateEvent() {
            @Override
            public DeviceAddress getDeviceAddress() {
                return deviceAddress;
            }
        });
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public DeviceAddressSupplier newDeviceAddressSupplier() {
        DeviceAddressSupplier deviceAddressSupplier = new DeviceAddressSupplier(this);
        updateAddressesBg();
        return deviceAddressSupplier;
    }

    public List<DeviceAddress> getAllWorkingDeviceAddresses() {
        return Lists.newArrayList(Iterables.filter(deviceAddressMap.values(), new Predicate<DeviceAddress>() {
            @Override
            public boolean apply(DeviceAddress deviceAddress) {
                return deviceAddress.isWorking();
            }
        }));
    }

    @Override
    public void close() {
        if (localDiscorveryHandler != null) {
            localDiscorveryHandler.close();
        }
        if (globalDiscoveryHandler != null) {
            globalDiscoveryHandler.close();
        }
        executorService.shutdown();
        ExecutorUtils.awaitTerminationSafe(executorService);
    }

    public abstract class DeviceAddressUpdateEvent {

        public abstract DeviceAddress getDeviceAddress();
    }

}
