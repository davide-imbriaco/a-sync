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
package it.anyplace.sync.discovery.utils;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import static com.google.common.base.Objects.equal;
import com.google.common.base.Stopwatch;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import it.anyplace.sync.core.beans.DeviceAddress;
import it.anyplace.sync.core.beans.DeviceAddress.AddressType;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author aleph
 */
public class AddressRanker implements Closeable {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final List<DeviceAddress> sourceAddresses, targetAddresses;

    private AddressRanker(Iterable<DeviceAddress> sourceAddresses) {
        this.sourceAddresses = Collections.unmodifiableList(Lists.newArrayList(sourceAddresses));
        this.targetAddresses = Collections.synchronizedList(Lists.<DeviceAddress>newArrayList());
    }

    private List<DeviceAddress> preprocessDeviceAddresses(List<DeviceAddress> list) {
        Set<DeviceAddress> res = Sets.newLinkedHashSet();
        for (DeviceAddress deviceAddress : list) {
            logger.debug("preprocess address = {}", deviceAddress.getAddress());
            if (equal(deviceAddress.getType(), AddressType.RELAY) && deviceAddress.containsUriParamValue("httpUrl")) {
                String httpUrl = deviceAddress.getUriParam("httpUrl");
                DeviceAddress httpRelayAddress = deviceAddress.copyBuilder()
                    .setAddress("relay-" + httpUrl).build();
                logger.debug("extracted http relay address = {}", httpRelayAddress.getAddress());
                res.add(httpRelayAddress);
            }
            res.add(deviceAddress);
        }
        return Lists.newArrayList(res);
    }

    private void testAndRankAndWait() throws InterruptedException {
        logger.trace("testing and ranking peer addresses");
        List<Future<DeviceAddress>> futures = Lists.newArrayList();
        for (final DeviceAddress deviceAddress : preprocessDeviceAddresses(sourceAddresses)) {
            futures.add(executorService.submit(new Callable<DeviceAddress>() {

                @Override
                public DeviceAddress call() {
                    return testAndRank(deviceAddress);
                }
            }));
        }
        for (Future<DeviceAddress> future : futures) {
            try {
                DeviceAddress deviceAddress = future.get(TCP_CONNECTION_TIMEOUT * 2, TimeUnit.MILLISECONDS);
                if (deviceAddress != null) {
                    targetAddresses.add(deviceAddress);
                }
            } catch (ExecutionException ex) {
                throw new RuntimeException(ex);
            } catch (TimeoutException ex) {
                logger.warn("test address timeout : {}", ex.toString());
            }
        }
        Collections.sort(targetAddresses, Ordering.natural().onResultOf(new Function<DeviceAddress, Comparable>() {
            @Override
            public Comparable apply(DeviceAddress a) {
                return a.getScore();
            }
        }));
    }

    public static String dumpAddressRanking(List<DeviceAddress> list) {
        return Joiner.on("\n").join(Lists.transform(list, new Function<DeviceAddress, String>() {
            @Override
            public String apply(DeviceAddress a) {
                return "\t" + a.getDeviceId() + "\t" + a.getAddress();
            }
        }));
    }

    public String dumpAddressRanking() {
        return dumpAddressRanking(targetAddresses);
    }

    private static final int TCP_CONNECTION_TIMEOUT = 1000;
    private static final Map<AddressType, Integer> BASE_SCORE_MAP = ImmutableMap.<AddressType, Integer>builder()
        .put(AddressType.TCP, 0)
        .put(AddressType.RELAY, TCP_CONNECTION_TIMEOUT * 2)
        .put(AddressType.HTTP_RELAY, TCP_CONNECTION_TIMEOUT * TCP_CONNECTION_TIMEOUT * 2)
        .put(AddressType.HTTPS_RELAY, TCP_CONNECTION_TIMEOUT * TCP_CONNECTION_TIMEOUT * 2)
        .build();
    private static final Set<AddressType> ACCEPTED_ADDRESS_TYPES = BASE_SCORE_MAP.keySet();

    private @Nullable
    DeviceAddress testAndRank(DeviceAddress deviceAddress) {
        if (!ACCEPTED_ADDRESS_TYPES.contains(deviceAddress.getType())) {
            logger.trace("dropping unsupported address = {}", deviceAddress);
            return null;
        }
        try {
            int baseScore = BASE_SCORE_MAP.get(deviceAddress.getType());
            int ping = testTcpConnection(deviceAddress.getSocketAddress());
            if (ping < 0) {
                logger.trace("dropping unreacheable address = {}", deviceAddress);
                return null;
            } else {
                return deviceAddress.copyBuilder().setScore(ping + baseScore).build();
            }
        } catch (Exception ex) {
            logger.warn("error testing device address = {}, discarding address", deviceAddress);
            logger.warn("error testing device address, discarding address", ex);
            return null;
        }
    }

    public List<DeviceAddress> getTargetAddresses() {
        return targetAddresses;
    }

    public static List<DeviceAddress> testAndRank(Iterable<DeviceAddress> list) {
        try (AddressRanker addressRanker = new AddressRanker(list)) {
            addressRanker.testAndRankAndWait();
            return addressRanker.getTargetAddresses();
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

//    private boolean hasCompleted() {
//        return count == sourceAddresses.size();
//    }
    @Override
    public void close() {
        executorService.shutdown();
        try {
            executorService.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
        }
    }

    private final LoadingCache<Pair<String, Integer>, Integer> socketAddressScoreCache = CacheBuilder.newBuilder()
        .expireAfterAccess(10, TimeUnit.SECONDS)
        .build(new CacheLoader<Pair<String, Integer>, Integer>() {
            @Override
            public Integer load(Pair<String, Integer> key) throws Exception {
                return doTestTcpConnection(new InetSocketAddress(InetAddress.getByName(key.getLeft()), key.getRight()));
            }
        });

    private int doTestTcpConnection(SocketAddress socketAddress) {
        logger.debug("test tcp connection to address = {}", socketAddress);
        Stopwatch stopwatch = Stopwatch.createStarted();
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(TCP_CONNECTION_TIMEOUT);
            socket.connect(socketAddress, TCP_CONNECTION_TIMEOUT);
        } catch (IOException ex) {
            logger.debug("address unreacheable = {} ({})", socketAddress, ex.toString());
            logger.trace("address unreacheable", ex);
            return -1;
        }
        int time = (int) stopwatch.elapsed(TimeUnit.MILLISECONDS);
        logger.debug("tcp connection to address = {} is ok, time = {} ms", socketAddress, time);
        return time;
    }

    private int testTcpConnection(InetSocketAddress socketAddress) {
        return socketAddressScoreCache.getUnchecked(Pair.of(socketAddress.getAddress().getHostAddress(), socketAddress.getPort()));
    }
}
