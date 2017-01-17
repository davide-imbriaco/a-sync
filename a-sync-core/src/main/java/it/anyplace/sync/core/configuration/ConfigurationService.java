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
package it.anyplace.sync.core.configuration;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import static com.google.common.base.Objects.equal;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;
import static org.apache.commons.lang3.StringUtils.isBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.io.FileUtils;
import com.google.common.collect.Maps;
import it.anyplace.sync.core.beans.DeviceInfo;
import it.anyplace.sync.core.beans.FolderInfo;
import it.anyplace.sync.core.configuration.gsonbeans.DeviceConfig;
import it.anyplace.sync.core.configuration.gsonbeans.DeviceConfigList;
import it.anyplace.sync.core.configuration.gsonbeans.FolderConfig;
import it.anyplace.sync.core.configuration.gsonbeans.FolderConfigList;
import java.util.Map;
import it.anyplace.sync.core.utils.ExecutorUtils;
import java.io.Closeable;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.emptyToNull;
import com.google.common.collect.Sets;
import java.util.Enumeration;

public class ConfigurationService implements Closeable {

    private final static String DEVICE_NAME = "devicename",
        FOLDERS = "folders",
        PEERS = "peers",
        INDEX = "index",
        DATABASE = "database",
        TEMP = "temp",
        CACHE = "cache",
        KEYSTORE = "keystore",
        DEVICE_ID = "deviceid",
        KEYSTORE_ALGO = "keystorealgo",
        DISCOVERY_SERVERS = "discoveryserver",
        CONFIGURATION = "configuration",
        REPOSITORY_H2_CONFIG = "repository.h2.dboptions";
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private final Gson gson = new Gson();
    private long instanceId = Math.abs(new Random().nextLong());
    private boolean isDirty = false;
    private File cache, temp, database, configuration;
    private final String clientVersion;
    private String deviceName, deviceId, keystoreAlgo, repositoryH2Config;
    private Map<String, FolderInfo> folders;
    private Map<String, DeviceInfo> peers;
    private byte[] keystore;
    private final List<String> discoveryServers;

    private ConfigurationService(Properties properties) {
        deviceName = properties.getProperty(DEVICE_NAME);
        if (isBlank(deviceName)) {
            try {
                deviceName = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException ex) {
            }
            if (isBlank(deviceName) || equal(deviceName, "localhost")) {
                deviceName = "s-client";
            }
        }
        deviceId = properties.getProperty(DEVICE_ID);
        keystoreAlgo = properties.getProperty(KEYSTORE_ALGO);
        folders = Collections.synchronizedMap(Maps.<String, FolderInfo>newHashMap());
        String folderValue = properties.getProperty(FOLDERS);
        try {
            FolderConfigList folderConfigList = gson.fromJson(folderValue, FolderConfigList.class);
            for (FolderConfig folderConfig : folderConfigList.getFolders()) {
                folders.put(folderConfig.getFolder(), new FolderInfo(folderConfig.getFolder(), folderConfig.getLabel()));
            }
        } catch (Exception ex) {
            logger.error("error reading folder field = " + folderValue, ex);
        }
        String keystoreValue = properties.getProperty(KEYSTORE);
        if (!Strings.isNullOrEmpty(keystoreValue)) {
            keystore = BaseEncoding.base64().decode(keystoreValue);
        }
        String cacheDir = properties.getProperty(CACHE);
        if (!isBlank(cacheDir)) {
            cache = new File(cacheDir);
        } else {
            cache = new File(System.getProperty("java.io.tmpdir"), "a_sync_client_cache");
        }
        cache.mkdirs();
        checkArgument(cache.isDirectory() && cache.canWrite(), "invalid cache dir = %s", cache);
        String tempDir = properties.getProperty(TEMP);
        if (!isBlank(tempDir)) {
            temp = new File(tempDir);
        } else {
            temp = new File(System.getProperty("java.io.tmpdir"), "a_sync_client_temp");
        }
        temp.mkdirs();
        checkArgument(temp.isDirectory() && temp.canWrite(), "invalid temp dir = %s", temp);
        String dbDir = properties.getProperty(DATABASE);
        if (!isBlank(dbDir)) {
            database = new File(dbDir);
        } else {
            database = new File(System.getProperty("user.home"), ".config/sclient/db");
        }
        database.mkdirs();
        checkArgument(database.isDirectory() && database.canWrite(), "invalid database dir = %s", database);
        peers = Collections.synchronizedMap(Maps.<String, DeviceInfo>newHashMap());
        String peersValue = properties.getProperty(PEERS);
        try {
            DeviceConfigList deviceConfigList = gson.fromJson(peersValue, DeviceConfigList.class);
            for (DeviceConfig deviceConfig : deviceConfigList.getDevices()) {
                peers.put(deviceConfig.getDeviceId(), new DeviceInfo(deviceConfig.getDeviceId(), deviceConfig.getName()));
            }
        } catch (Exception ex) {
            logger.error("error reading peers field = " + peersValue, ex);
        }
        String discoveryServerValue = properties.getProperty(DISCOVERY_SERVERS);
        discoveryServers = Strings.isNullOrEmpty(discoveryServerValue) ? Collections.<String>emptyList() : Arrays.asList(discoveryServerValue.split(","));
        clientVersion = MoreObjects.firstNonNull(emptyToNull(getClass().getPackage().getImplementationVersion()), "0.0.0");// version info from MANIFEST, with 'safe' default fallback
        String configurationValue = properties.getProperty(CONFIGURATION);
        if (!isBlank(configurationValue)) {
            configuration = new File(configurationValue);
        }
        repositoryH2Config = properties.getProperty(REPOSITORY_H2_CONFIG);
    }

    private synchronized Properties export() {
        Properties properties = new Properties() {
            @Override
            public synchronized Enumeration<Object> keys() {
                List<String> list = (List) Collections.list(super.keys());
                Collections.sort(list);
                return Collections.enumeration((List) list);
            }

        };
        if (!isBlank(deviceName)) {
            properties.setProperty(DEVICE_NAME, deviceName);
        }
        if (!isBlank(deviceId)) {
            properties.setProperty(DEVICE_ID, deviceId);
        }
        FolderConfigList folderConfigList = new FolderConfigList();
        for (FolderInfo folderInfo : folders.values()) {
            FolderConfig folderConfig = new FolderConfig();
            folderConfig.setFolder(folderInfo.getFolder());
            folderConfig.setLabel(folderInfo.getLabel());
            folderConfigList.getFolders().add(folderConfig);
        }
        properties.setProperty(FOLDERS, gson.toJson(folderConfigList));
        DeviceConfigList deviceConfigList = new DeviceConfigList();
        for (DeviceInfo deviceInfo : peers.values()) {
            DeviceConfig deviceConfig = new DeviceConfig();
            deviceConfig.setDeviceId(deviceInfo.getDeviceId());
            deviceConfig.setName(deviceInfo.getName());
            deviceConfigList.getDevices().add(deviceConfig);
        }
        properties.setProperty(PEERS, gson.toJson(deviceConfigList));
        properties.setProperty(DATABASE, database.getAbsolutePath());
        properties.setProperty(TEMP, temp.getAbsolutePath());
        properties.setProperty(CACHE, cache.getAbsolutePath());
        if (keystore != null) {
            properties.setProperty(KEYSTORE, BaseEncoding.base64().encode(keystore));
        }
        if (!isBlank(keystoreAlgo)) {
            properties.setProperty(KEYSTORE_ALGO, keystoreAlgo);
        }
        properties.setProperty(DISCOVERY_SERVERS, Joiner.on(",").join(discoveryServers));
        return properties;
    }

    public String getRepositoryH2Config() {
        return repositoryH2Config;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getClientName() {
        return "syncthing-client";
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public String getKeystoreAlgo() {
        return keystoreAlgo;
    }

    public byte[] getKeystore() {
        return keystore;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public List<FolderInfo> getFolders() {
        return Lists.newArrayList(folders.values());
    }

    public Set<String> getFolderNames() {
        return Sets.newTreeSet(folders.keySet());
    }

    public long getInstanceId() {
        return instanceId;
    }

    public List<DeviceInfo> getPeers() {
        return Lists.newArrayList(peers.values());
    }

    public Set<String> getPeerIds() {
        return Sets.newTreeSet(peers.keySet());
    }

    public List<String> getDiscoveryServers() {
        return discoveryServers;
    }

    public File getCache() {
        return cache;
    }

    public File getTemp() {
        return temp;
    }

    public File getDatabase() {
        return database;
    }

    public StorageInfo getStorageInfo() {
        return new StorageInfo();
    }

    @Override
    public void close() {
        executorService.shutdown();
        ExecutorUtils.awaitTerminationSafe(executorService);
    }

    public class StorageInfo {

        public long getAvailableSpace() {
            return getDatabase().getFreeSpace();
        }

        public long getUsedSpace() {
            return FileUtils.sizeOfDirectory(getDatabase());
        }

        public long getUsedTempSpace() {
            return FileUtils.sizeOfDirectory(getCache()) + FileUtils.sizeOfDirectory(getTemp());
        }

        public String dumpAvailableSpace() {
            StringWriter stringWriter = new StringWriter();
            stringWriter.append("dir / used space / free space");
            stringWriter.append("\n\tcache = " + getCache()
                + " " + FileUtils.byteCountToDisplaySize(FileUtils.sizeOfDirectory(getCache()))
                + " / " + FileUtils.byteCountToDisplaySize(getCache().getFreeSpace()));
            stringWriter.append("\n\ttemp = " + getTemp()
                + " " + FileUtils.byteCountToDisplaySize(FileUtils.sizeOfDirectory(getTemp()))
                + " / " + FileUtils.byteCountToDisplaySize(getTemp().getFreeSpace()));
            stringWriter.append("\n\tdatabase = " + getDatabase()
                + " " + FileUtils.byteCountToDisplaySize(FileUtils.sizeOfDirectory(getDatabase()))
                + " / " + FileUtils.byteCountToDisplaySize(getDatabase().getFreeSpace()));
            return stringWriter.toString();
        }

    }

    public Editor edit() {
        return new Editor();
    }

    public class Editor {

        private Editor() {

        }

        public Editor setKeystore(@Nullable byte[] keystore) {
            ConfigurationService.this.keystore = keystore;
            return this;
        }

        public Editor setKeystoreAlgo(@Nullable String keystoreAlgo) {
            ConfigurationService.this.keystoreAlgo = keystoreAlgo;
            return this;
        }

        public Editor setDeviceName(String deviceName) {
            ConfigurationService.this.deviceName = deviceName;
            return this;
        }

        public Editor setFolders(Iterable<FolderInfo> folderList) {
            checkNotNull(folderList);
            folders.clear();
            addFolders(folderList);
            return this;
        }

        public boolean addFolders(FolderInfo... folders) {
            return addFolders(Lists.newArrayList(folders));
        }

        public boolean addFolders(@Nullable Iterable<FolderInfo> newFolders) {
            boolean added = false;
            if (newFolders != null) {
                for (FolderInfo folderInfo : newFolders) {
                    FolderInfo old = folders.put(folderInfo.getFolder(), folderInfo);
                    if (old == null) {
                        added = true;
                    }
                }
            }
            return added;
        }

        public boolean addPeers(DeviceInfo... peers) {
            return addPeers(Arrays.asList(peers));
        }

        public boolean addPeers(Iterable<DeviceInfo> peers) {
            boolean added = false;
            if (peers != null) {
                for (DeviceInfo deviceInfo : peers) {
                    DeviceInfo old = ConfigurationService.this.peers.put(deviceInfo.getDeviceId(), deviceInfo);
                    if (old == null) {
                        added = true;
                    }
                }
            }
            return added;
        }

        public Editor setPeers(Iterable<DeviceInfo> peers) {
            ConfigurationService.this.peers.clear();
            addPeers(peers);
            return this;
        }

        public Editor removePeer(String deviceId) {
            peers.remove(deviceId);
            return this;
        }

        public void persistNow() {
            isDirty = true;
            storeConfiguration();
        }

        public void persistLater() {
            isDirty = true;
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    storeConfiguration();
                }
            });
        }

        private void storeConfiguration() {
            if (configuration != null) {
                if (isDirty) {
                    isDirty = false;
                    try {
                        newWriter().writeTo(configuration);
                    } catch (Exception ex) {
                        logger.error("unable to store configuration", ex);
                    }
                }
            } else {
                logger.debug("dummy save config, no file set");
            }
        }

        public Editor setDeviceId(String deviceId) {
            ConfigurationService.this.deviceId = deviceId;
            return this;
        }
    }

    public static Loader newLoader() {
        return new Loader();
    }

    public Writer newWriter() {
        return new Writer();
    }

    public static class Loader {

        private final Logger logger = LoggerFactory.getLogger(getClass());
        private final Properties customProperties = new Properties();

        public Loader setTemp(File temp) {
            customProperties.setProperty(TEMP, temp.getAbsolutePath());
            return this;
        }

        public Loader setDatabase(File database) {
            customProperties.setProperty(DATABASE, database.getAbsolutePath());
            return this;
        }

        public Loader setCache(File cache) {
            customProperties.setProperty(CACHE, cache.getAbsolutePath());
            return this;
        }

        public ConfigurationService loadFrom(@Nullable File file) {
            Properties properties = new Properties();
            try {
                properties.load(new InputStreamReader(getClass().getResourceAsStream("/default.properties")));
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            if (file != null) {
                if (file.isFile() && file.canRead()) {
                    try {
                        properties.load(new FileReader(file));
                    } catch (IOException ex) {
                        logger.error("error loading configuration from file = " + file, ex);
                    }
                }
                properties.put(CONFIGURATION, file.getAbsolutePath());
            }
            properties.putAll(customProperties);
            return new ConfigurationService(properties);
        }

        public ConfigurationService load() {
            return loadFrom(null);
        }
    }

    public class Writer {

        private Writer() {
        }

        public void writeTo(File file) {
            Properties properties = export();
            if (!file.exists()) {
                file.getParentFile().mkdirs();
            }
            try (FileWriter fileWriter = new FileWriter(file)) {
                properties.store(fileWriter, null);
                logger.debug("configuration saved to {}", file);
            } catch (IOException ex) {
                logger.error("error storing configuration to file = " + file, ex);
            }
        }

        public String dumpToString() {
            try {
                Properties properties = export();
                properties.setProperty("volatile_instanceid", String.valueOf(getInstanceId()));
                properties.setProperty("volatile_clientname", getClientName());
                properties.setProperty("volatile_clientversion", getClientVersion());
                StringWriter stringWriter = new StringWriter();
                properties.store(stringWriter, null);
                return stringWriter.toString();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

    }
}
