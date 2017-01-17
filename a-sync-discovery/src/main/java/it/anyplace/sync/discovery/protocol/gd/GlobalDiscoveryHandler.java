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
package it.anyplace.sync.discovery.protocol.gd;

import com.google.common.base.Function;
import static com.google.common.base.MoreObjects.firstNonNull;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.gson.Gson;
import java.util.Collections;
import java.util.List;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import it.anyplace.sync.core.configuration.ConfigurationService;
import it.anyplace.sync.core.beans.DeviceAddress;
import it.anyplace.sync.discovery.utils.AddressRanker;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author aleph
 */
public class GlobalDiscoveryHandler implements Closeable {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ConfigurationService configuration;
    private final Gson gson = new Gson();
    private final LoadingCache<String, List<DeviceAddress>> cache = CacheBuilder.newBuilder()
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .refreshAfterWrite(10, TimeUnit.MINUTES)
        .build(new CacheLoader<String, List<DeviceAddress>>() {
            @Override
            public List<DeviceAddress> load(String deviceId) throws Exception {
                return doQuery(deviceId);
            }
        });
    private List<String> serverList = null;

//    private final ExecutorService executorService = Executors.newCachedThreadPool();
    public GlobalDiscoveryHandler(ConfigurationService configuration) {
        this.configuration = configuration;
    }

    public List<DeviceAddress> query(final String deviceId) {
        try {
            return cache.get(deviceId);
        } catch (ExecutionException ex) {
            throw new RuntimeException(ex);
        }
    }

    private List<DeviceAddress> doQuery(final String deviceId) {
        synchronized (this) {
            if (serverList == null) {
                logger.debug("ranking discovery server addresses");
                List<DeviceAddress> list = AddressRanker.testAndRank(Lists.transform(configuration.getDiscoveryServers(), new Function<String, DeviceAddress>() {
                    @Override
                    public DeviceAddress apply(String input) {
                        return new DeviceAddress(input, "tcp://" + input + ":443");
                    }
                }));
                logger.info("discovery server addresses = \n\n{}\n", AddressRanker.dumpAddressRanking(list));
                serverList = Lists.newArrayList(Lists.transform(list, new Function<DeviceAddress, String>() {
                    @Override
                    public String apply(DeviceAddress input) {
                        return input.getDeviceId();
                    }
                }));
            }
        }
        for (String server : serverList) {
            List<DeviceAddress> list = doQuery(server, deviceId);
            if (list != null) {
                return list;
            }
        }
        return Collections.emptyList();
    }

    /**
     * 
     * @param server
     * @param deviceId
     * @return null on error, empty list on 'not found', address list otherwise
     */
    private @Nullable
    List<DeviceAddress> doQuery(String server, final String deviceId) {
        try {
            logger.trace("querying server {} for device id {}", server, deviceId);
            HttpClient httpClient = HttpClients.custom()
                .setSSLSocketFactory(new SSLConnectionSocketFactory(new SSLContextBuilder().loadTrustMaterial(null, new TrustSelfSignedStrategy()).build(), SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER))
                .build();
            HttpGet httpGet = new HttpGet("https://" + server + "/v2/?device=" + deviceId);
            return httpClient.execute(httpGet, new ResponseHandler<List<DeviceAddress>>() {
                @Override
                public List<DeviceAddress> handleResponse(HttpResponse response) throws ClientProtocolException, IOException {
                    switch (response.getStatusLine().getStatusCode()) {
                        case HttpStatus.SC_NOT_FOUND:
                            logger.debug("device not found: {}", deviceId);
                            return Collections.<DeviceAddress>emptyList();
                        case HttpStatus.SC_OK:
                            AnnouncementMessageBean announcementMessageBean = gson.fromJson(EntityUtils.toString(response.getEntity()), AnnouncementMessageBean.class);
                            List<DeviceAddress> list = Lists.newArrayList(Iterables.transform(firstNonNull(announcementMessageBean.getAddresses(), Collections.<String>emptyList()), new Function<String, DeviceAddress>() {
                                @Override
                                public DeviceAddress apply(String address) {
                                    return new DeviceAddress(deviceId, address);
                                }
                            }));
                            logger.debug("found address list = {}", list);
                            return list;
                        default:
                            throw new IOException("http error " + response.getStatusLine());
                    }
                }
            });
        } catch (Exception ex) {
            logger.warn("error in global discovery for device = " + deviceId, ex);
        }
        return null;
    }
//
//    private List<DeviceAddress> doQuery(final String deviceId) {
//        List<String> serverList = configuration.getDiscoveryServers();
//        List<Future<List<DeviceAddress>>> futures = Lists.newArrayList();
//        for (final String server : serverList) {
//            futures.add(executorService.submit(new Callable<List<DeviceAddress>>() {
//                @Override
//                public List<DeviceAddress> call() throws Exception {
//                    return doQuery(server, deviceId);
//                }
//            }));
//        }
//        while (!futures.isEmpty()) {
//            Iterator<Future<List<DeviceAddress>>> iterator = futures.iterator();
//            while (iterator.hasNext()) {
//                Future<List<DeviceAddress>> future = iterator.next();
//                if (future.isDone()) {
//                    iterator.remove();
//                    try {
//                        List<DeviceAddress> list = future.get();
//                        if (!list.isEmpty()) {
//                            return list;
//                        }
//                    } catch (InterruptedException | ExecutionException ex) {
//                        throw new RuntimeException(ex);
//                    }
//                }
//            }
//        }
//        return Collections.emptyList();
//    }

    @Override
    public void close() {
//        executorService.shutdown();
//        try {
//            executorService.awaitTermination(2, TimeUnit.SECONDS);
//        } catch (InterruptedException ex) {
//        }
    }

    public static class AnnouncementMessageBean {

        private List<String> addresses;

        public List<String> getAddresses() {
            return addresses;
        }

        public void setAddresses(List<String> addresses) {
            this.addresses = addresses;
        }

    }
}
