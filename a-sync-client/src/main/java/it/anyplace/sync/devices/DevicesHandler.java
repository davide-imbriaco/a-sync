/*
 * Copyright 2016 Davide Imbriaco <davide.imbriaco@gmail.com>.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package it.anyplace.sync.devices;

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import it.anyplace.sync.core.configuration.ConfigurationService;
import it.anyplace.sync.core.beans.DeviceAddress;
import it.anyplace.sync.core.beans.DeviceInfo;
import it.anyplace.sync.core.beans.DeviceStats;
import it.anyplace.sync.core.beans.DeviceStats.DeviceStatus;
import it.anyplace.sync.core.events.DeviceAddressActiveEvent;
import it.anyplace.sync.core.events.DeviceAddressReceivedEvent;
import it.anyplace.sync.core.utils.ExecutorUtils;
import java.io.Closeable;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.google.common.base.Preconditions.checkNotNull;

public class DevicesHandler implements Closeable {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Map<String, DeviceStats> deviceStatsMap = Collections.synchronizedMap(Maps.<String, DeviceStats>newHashMap());
    private final ConfigurationService configuration;
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private final EventBus eventBus = new EventBus();

    public DevicesHandler(ConfigurationService configuration) {
        checkNotNull(configuration);
        this.configuration = configuration;
        loadDevicesFromConfiguration();
        scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                for (DeviceStats deviceStats : Lists.newArrayList(deviceStatsMap.values())) {
                    switch (deviceStats.getStatus()) {
                        case ONLINE_ACTIVE:
                            if (new Date().getTime() - deviceStats.getLastActive().getTime() > 5 * 1000) {
                                pushDeviceStats(deviceStats.copyBuilder().setStatus(DeviceStatus.ONLINE_INACTIVE).build());
                            }
                            break;
                    }
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    @Subscribe
    public void handleDeviceAddressActiveEvent(DeviceAddressActiveEvent event) {
        pushDeviceStats(getDeviceStats(event.getDeviceAddress().getDeviceId())
            .copyBuilder()
            .setLastActive(new Date())
            .setStatus(DeviceStats.DeviceStatus.ONLINE_ACTIVE)
            .build());
    }

    @Subscribe
    public void handleDeviceAddressReceivedEvent(DeviceAddressReceivedEvent event) {
        for (DeviceAddress deviceAddress : event.getDeviceAddresses()) {
            if (deviceAddress.isWorking()) {
                DeviceStats deviceStats = getDeviceStats(deviceAddress.getDeviceId());
                DeviceStatus newStatus;
                switch (deviceStats.getStatus()) {
                    case OFFLINE:
                        newStatus = DeviceStatus.ONLINE_INACTIVE;
                        break;
                    default:
                        newStatus = deviceStats.getStatus();
                }
                pushDeviceStats(deviceStats.copyBuilder().setStatus(newStatus).setLastSeen(new Date()).build());
            }
        }
    }

    public void clear() {
        deviceStatsMap.clear();
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    private void loadDevicesFromConfiguration() {
        for (DeviceInfo deviceInfo : configuration.getPeers()) {
            if (!deviceStatsMap.containsKey(deviceInfo.getDeviceId())) {
                pushDeviceStats(DeviceStats.newBuilder().setDeviceId(deviceInfo.getDeviceId()).setName(deviceInfo.getName()).build());
            }
        }
    }

    public DeviceStats getDeviceStats(String deviceId) {
        loadDevicesFromConfiguration();
        DeviceStats deviceStats = deviceStatsMap.get(deviceId);
        if (deviceStats == null) {
            pushDeviceStats(deviceStats = DeviceStats.newBuilder().setDeviceId(deviceId).build());
        }
        return deviceStats;
    }

    public Collection<DeviceStats> getDeviceStatsList() {
        loadDevicesFromConfiguration();
        return Collections.unmodifiableCollection(deviceStatsMap.values());
    }

    private void pushDeviceStats(final DeviceStats deviceStats) {
        deviceStatsMap.put(deviceStats.getDeviceId(), deviceStats);
        eventBus.post(new DeviceStatsUpdateEvent() {
            @Override
            public List<DeviceStats> getChangedDeviceStats() {
                return Collections.singletonList(deviceStats);
            }
        });
    }

    public interface DeviceStatsUpdateEvent {

        public List<DeviceStats> getChangedDeviceStats();
    }

    @Override
    public void close() {
        scheduledExecutorService.shutdown();
        ExecutorUtils.awaitTerminationSafe(scheduledExecutorService);
    }
}
