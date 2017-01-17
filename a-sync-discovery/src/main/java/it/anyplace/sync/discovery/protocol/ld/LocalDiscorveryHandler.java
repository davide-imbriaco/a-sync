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
package it.anyplace.sync.discovery.protocol.ld;

import it.anyplace.sync.discovery.protocol.ld.protos.LocalDiscoveryProtos;
import com.google.common.base.Function;
import it.anyplace.sync.core.beans.DeviceAddress;
import com.google.common.base.Functions;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Objects.equal;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.collect.HashMultimap;
import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;
import com.google.protobuf.ByteString;
import com.google.common.collect.Multimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;
import it.anyplace.sync.core.configuration.ConfigurationService;
import it.anyplace.sync.discovery.protocol.ld.protos.LocalDiscoveryProtos.Announce;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import org.apache.commons.io.IOUtils;
import static it.anyplace.sync.core.security.KeystoreHandler.deviceIdStringToHashData;
import static it.anyplace.sync.core.security.KeystoreHandler.hashDataToDeviceIdString;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.io.Closeable;
import static com.google.common.base.Preconditions.checkArgument;
import it.anyplace.sync.core.events.DeviceAddressReceivedEvent;
import static com.google.common.base.Preconditions.checkArgument;

/**
 *
 * @author aleph
 */
public class LocalDiscorveryHandler implements Closeable {

    private final static int MAGIC = 0x2EA7D90B;
    private final static int UDP_PORT = 21027, MAX_WAIT = 60 * 1000;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private DatagramSocket datagramSocket;
    private final ScheduledExecutorService listeningExecutorService = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService processingExecutorService = Executors.newCachedThreadPool();
    private final EventBus eventBus = new AsyncEventBus(processingExecutorService);
    private final Multimap<String, DeviceAddress> localDiscoveryRecords = HashMultimap.create();
    private final ConfigurationService configuration;

    public LocalDiscorveryHandler(ConfigurationService configuration) {
        this.configuration = configuration;
    }

    public List<DeviceAddress> queryAndClose(String deviceId) {
        return queryAndClose(Collections.singleton(deviceId));
    }

    public List<DeviceAddress> queryAndClose(final Set<String> deviceIds) {
        try {
            final Object lock = new Object();
            synchronized (lock) {
                eventBus.register(new Object() {
                    @Subscribe
                    public void handleMessageReceivedEvent(MessageReceivedEvent event) {
                        if (deviceIds.contains(event.getDeviceId())) {
                            synchronized (lock) {
                                lock.notify();
                            }
                        }
                    }
                });
                startListener();
                sendAnnounceMessage();
                try {
                    lock.wait(MAX_WAIT);
                } catch (InterruptedException ex) {
                }
                synchronized (localDiscoveryRecords) {
                    return Lists.newArrayList(Iterables.concat(Iterables.transform(deviceIds, Functions.forMap(localDiscoveryRecords.asMap()))));
                }
            }
        } finally {
            close();
        }
    }

    public List<DeviceAddress> getDeviceAddresses(String deviceId) {
        synchronized (localDiscoveryRecords) {
            return Lists.newArrayList(localDiscoveryRecords.get(deviceId));
        }
    }

    public List<DeviceAddress> getDeviceAddresses() {
        synchronized (localDiscoveryRecords) {
            return Lists.newArrayList(localDiscoveryRecords.values());
        }
    }

    public Future sendAnnounceMessage() {
        return processingExecutorService.submit(new Callable() {

            @Override
            public Object call() throws Exception {
                for (int i = 0; i < 10 && !isListening; i++) { //wait for listening
                    Thread.sleep(100);
                }
                if (!isListening) {
                    logger.warn("skipping announce message, udp listening is not active");
                    return null;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                new DataOutputStream(out).writeInt(MAGIC);
                Announce.newBuilder()
                    .setId(ByteString.copyFrom(deviceIdStringToHashData(configuration.getDeviceId())))
                    .setInstanceId(configuration.getInstanceId())
                    .build().writeTo(out);
                byte[] data = out.toByteArray();
                Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
                while (networkInterfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = networkInterfaces.nextElement();
                    for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                        InetAddress broadcastAddress = interfaceAddress.getBroadcast();
                        logger.trace("interface = {} address = {} broadcast = {}", networkInterface, interfaceAddress, broadcastAddress);
                        if (broadcastAddress != null) {
                            logger.debug("sending broadcast announce on {}", broadcastAddress);
                            try (DatagramSocket broadcastSocket = new DatagramSocket()) {
                                broadcastSocket.setBroadcast(true);
                                DatagramPacket datagramPacket = new DatagramPacket(data, data.length, broadcastAddress, UDP_PORT);
                                broadcastSocket.send(datagramPacket);
                            } catch (Exception ex) {
                                logger.warn("error sending datagram", ex);
                            }
                        }
                    }
                }
                return null;
            }
        });
    }

    private boolean isListening = false;

    public void startListener() {
        listeningExecutorService.submit(new Runnable() {

            private final int incomingBufferSize = 1024; //TODO check this

            @Override
            public void run() {
                try {
                    if (datagramSocket == null || datagramSocket.isClosed()) {
                        datagramSocket = new DatagramSocket(UDP_PORT, InetAddress.getByName("0.0.0.0"));
                        logger.info("opening upd socket {}", datagramSocket.getLocalSocketAddress());
                    }
                    final DatagramPacket datagramPacket = new DatagramPacket(new byte[incomingBufferSize], incomingBufferSize);
                    logger.trace("waiting for message on socket addr = {}", datagramSocket.getLocalSocketAddress());
                    isListening = true;
                    datagramSocket.receive(datagramPacket);
                    processingExecutorService.submit(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                final String sourceAddress = datagramPacket.getAddress().getHostAddress();
                                ByteBuffer byteBuffer = ByteBuffer.wrap(datagramPacket.getData(), datagramPacket.getOffset(), datagramPacket.getLength());
                                int magic = byteBuffer.getInt(); //4 bytes
                                checkArgument(magic == MAGIC, "magic mismatch, expected %s, got %s", MAGIC, magic);
                                final LocalDiscoveryProtos.Announce announce = LocalDiscoveryProtos.Announce.parseFrom(ByteString.copyFrom(byteBuffer));
                                final String deviceId = hashDataToDeviceIdString(announce.getId().toByteArray());
                                if (!equal(deviceId, configuration.getDeviceId())) {
//                                logger.debug("received local announce from device id = {}", deviceId);
                                    final List<DeviceAddress> deviceAddresses = Lists.newArrayList(Iterables.transform(firstNonNull(announce.getAddressesList(), Collections.<String>emptyList()), new Function<String, DeviceAddress>() {
                                        @Override
                                        public DeviceAddress apply(String address) {
//                                /*
//                                When interpreting addresses with an unspecified address, e.g., tcp://0.0.0.0:22000 or tcp://:42424, the source address of the discovery announcement is to be used.
//                                 */
                                            return DeviceAddress.newBuilder().setAddress(address.replaceFirst("tcp://(0.0.0.0|):", "tcp://" + sourceAddress + ":"))
                                                .setDeviceId(deviceId)
                                                .setInstanceId(announce.getInstanceId())
                                                .setProducer(DeviceAddress.AddressProducer.LOCAL_DISCOVERY)
                                                .build();
                                        }
                                    }));
                                    boolean isNew = false;
                                    synchronized (localDiscoveryRecords) {
                                        isNew = !localDiscoveryRecords.removeAll(deviceId).isEmpty();
                                        localDiscoveryRecords.putAll(deviceId, deviceAddresses);
                                    }
                                    eventBus.post(new MessageReceivedEvent() {
                                        @Override
                                        public List<DeviceAddress> getDeviceAddresses() {
                                            return Collections.unmodifiableList(deviceAddresses);
                                        }

                                        @Override
                                        public String getDeviceId() {
                                            return deviceId;
                                        }
                                    });
                                    if (isNew) {
                                        eventBus.post(new NewLocalPeerEvent() {
                                            @Override
                                            public String getDeviceId() {
                                                return deviceId;
                                            }
                                        });
                                    }
                                }
                            } catch (Exception ex) {
                                logger.warn("error processing datagram", ex);
                            }
                        }
                    });
                    listeningExecutorService.submit(this);
                } catch (Exception ex) {
                    isListening = false;
                    if (listeningExecutorService.isShutdown()) {
                        return;
                    }
                    if (datagramSocket != null) {
                        logger.warn("error receiving datagram", ex);
                        listeningExecutorService.submit(this);
                    } else if (ex instanceof java.net.BindException) {
                        logger.warn("error opening udp server socket : {}", ex.toString());
                    } else {
                        logger.warn("error opening udp server socket", ex);
                    }
                }
            }
        });
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public void close() {
        processingExecutorService.shutdown();
        listeningExecutorService.shutdown();
        if (datagramSocket != null) {
            IOUtils.closeQuietly(datagramSocket);
        }
    }

    public LocalDiscorveryHandler waitForAddresses() {
//        try {
//            Thread.sleep(3 * 1000);//TODO
//        } catch (InterruptedException ex) {
//        }
        return this;
    }

    public abstract class MessageReceivedEvent implements DeviceAddressReceivedEvent {

        @Override
        public abstract List<DeviceAddress> getDeviceAddresses();

        public abstract String getDeviceId();
    }

    public abstract class NewLocalPeerEvent {

        public abstract String getDeviceId();
    }

}
