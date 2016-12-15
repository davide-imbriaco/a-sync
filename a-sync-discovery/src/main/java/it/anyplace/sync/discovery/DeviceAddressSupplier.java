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

import com.google.common.base.Function;
import com.google.common.collect.Ordering;
import com.google.common.eventbus.Subscribe;
import it.anyplace.sync.core.beans.DeviceAddress;
import java.io.Closeable;
import java.util.PriorityQueue;
import java.util.Queue;
import javax.annotation.Nullable;
import java.util.Iterator;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 *
 * @author aleph
 */
public class DeviceAddressSupplier implements Closeable, Iterable<DeviceAddress> {

    private final DiscoveryHandler discoveryHandler;
    private final Queue<DeviceAddress> deviceAddressQeue = new PriorityQueue<DeviceAddress>(11, Ordering.natural().onResultOf(new Function<DeviceAddress, Integer>() {
        @Override
        public Integer apply(DeviceAddress deviceAddress) {
            return deviceAddress.getScore();
        }
    }));
    private final Object queueLock = new Object();
    private final Object discoveryHandlerListener = new Object() {
        @Subscribe
        public void handleNewDeviceAddressAcquiredEvent(DiscoveryHandler.DeviceAddressUpdateEvent event) {
            if (event.getDeviceAddress().isWorking()) {
                synchronized (queueLock) {
                    deviceAddressQeue.add(event.getDeviceAddress());
                    queueLock.notify();
                }
            }
        }
    };

    protected DeviceAddressSupplier(DiscoveryHandler discoveryHandler) {
        checkNotNull(discoveryHandler);
        this.discoveryHandler = discoveryHandler;
        synchronized (queueLock) {
            discoveryHandler.getEventBus().register(discoveryHandlerListener);
            deviceAddressQeue.addAll(discoveryHandler.getAllWorkingDeviceAddresses());// note: slight risk of duplicate address loading
        }
    }

    public @Nullable
    DeviceAddress getDeviceAddress() {
        synchronized (queueLock) {
            return deviceAddressQeue.poll();
        }
    }

    public @Nullable
    DeviceAddress getDeviceAddressOrWait(long timeout) throws InterruptedException {
        synchronized (queueLock) {
            if (deviceAddressQeue.isEmpty()) {
                queueLock.wait(timeout);
            }
            return getDeviceAddress();
        }
    }

    public @Nullable
    DeviceAddress getDeviceAddressOrWait() throws InterruptedException {
        return getDeviceAddressOrWait(5000);
    }

    @Override
    public void close() {
        discoveryHandler.getEventBus().unregister(discoveryHandlerListener);
    }

    @Override
    public Iterator<DeviceAddress> iterator() {
        return new Iterator<DeviceAddress>() {

            private Boolean hasNext = null;
            private DeviceAddress next;

            @Override
            public boolean hasNext() {
                if (hasNext == null) {
                    try {
                        next = getDeviceAddressOrWait();
                    } catch (InterruptedException ex) {
                    }
                    hasNext = next != null;
                }
                return hasNext;
            }

            @Override
            public DeviceAddress next() {
                checkArgument(hasNext());
                DeviceAddress res = next;
                hasNext = null;
                next = null;
                return res;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
