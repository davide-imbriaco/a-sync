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
package it.anyplace.sync.core;

import com.google.common.base.Joiner;
import static com.google.common.base.Objects.equal;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;
import com.google.gson.Gson;
import static it.anyplace.sync.core.security.KeystoreHandler.CONFIGURATION_DEVICEID_PROP;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;
import static org.apache.commons.lang3.StringUtils.isBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.io.FileUtils;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.collect.Maps;
import it.anyplace.sync.core.beans.FolderInfo;
import it.anyplace.sync.core.configuration.gsonbeans.FolderConfig;
import it.anyplace.sync.core.configuration.gsonbeans.FolderConfigList;
import java.util.Collection;
import java.util.Map;

/**
 *
 * @author aleph
 */
public class Configuration extends Properties {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Gson gson = new Gson();
    private final long instanceId = Math.abs(new Random().nextLong());
    private final Supplier<Writer> outputSupplier;
    private File cache, temp, database;

    public Configuration() {
        this(null, null);
    }

    public Configuration(@Nullable Reader inputReader, @Nullable Supplier<Writer> outputSupplier) {
        try {
            this.outputSupplier = outputSupplier;
            load(new InputStreamReader(getClass().getResourceAsStream("/default.properties")));
            if (inputReader != null) {
                load(inputReader);
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public Configuration(@Nullable final File file) throws IOException {
        this(file == null || !file.canRead() ? null : new FileReader(file), file == null ? null : new Supplier<Writer>() {
            @Override
            public Writer get() {
                try {
                    if (!file.exists()) {
                        file.getParentFile().mkdirs();
                    }
                    return new FileWriter(file);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        });
        logger.debug("set config file = {}", file);
    }

    public String dumpToString() {
        try {
            StringWriter stringWriter = new StringWriter();
            store(stringWriter, null);
            return stringWriter.toString();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public final static String DEVICE_NAME = "devicename",
        FOLDERS = "folders",
        PEERS = "peers",
        INDEX = "index",
        CACHE = "cache";

    public String getDeviceName() {
        String deviceName = getProperty(DEVICE_NAME);
        if (deviceName == null) {
            try {
                deviceName = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException ex) {
            }
            if (isBlank(deviceName) || equal(deviceName, "localhost")) {
                deviceName = "s-client";
            }
            setDeviceName(deviceName);
        }
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        setProperty(DEVICE_NAME, deviceName);
    }

    /*
    The client_name and client_version identifies the implementation. 
    The values SHOULD be simple strings identifying the implementation name, as a user would expect to see it, and the version string in the same manner. 
    An example client name is “syncthing” and an example client version is “v0.7.2”. 
    The client version field SHOULD follow the patterns laid out in the Semantic Versioning standard.
     */
    public String getClientName() {
        return "syncthing-client";
    }

    public String getClientVersion() {
        return "1.0-SNAPSHOT";
    }

    public String getDeviceId() {
        return getProperty(CONFIGURATION_DEVICEID_PROP);
    }

    private Map<String, FolderInfo> folders;

    private Map<String, FolderInfo> getFolderMap() {
        if (folders == null) {
            folders = Collections.synchronizedMap(Maps.<String, FolderInfo>newHashMap());
            try {
                String folderValue = getProperty(FOLDERS);
                FolderConfigList folderConfigList = gson.fromJson(folderValue, FolderConfigList.class);
                for (FolderConfig folderConfig : folderConfigList.getFolders()) {
                    folders.put(folderConfig.getFolder(), new FolderInfo(folderConfig.getFolder(), folderConfig.getLabel()));
                }
            } catch (Exception ex) {
                logger.error("error reading folder field = " + folders, ex);
            }
        }
        return folders;
    }

    public Collection<FolderInfo> getFolders() {
        return Collections.unmodifiableCollection(getFolderMap().values());
    }

    private void storeFolders() {
        FolderConfigList folderConfigList = new FolderConfigList();
        for (FolderInfo folderInfo : getFolderMap().values()) {
            FolderConfig folderConfig = new FolderConfig();
            folderConfig.setFolder(folderInfo.getFolder());
            folderConfig.setLabel(folderInfo.getLabel());
            folderConfigList.getFolders().add(folderConfig);
        }
        setProperty(FOLDERS, gson.toJson(folderConfigList));
    }

    public Set<String> getFolderNames() {
        return Collections.unmodifiableSet(getFolderMap().keySet());
    }

    public void setFolders(Iterable<FolderInfo> folderList) {
        checkNotNull(folderList);
        getFolderMap().clear();
        addFolders(folderList);
    }

    public boolean addFolders(FolderInfo... folders) {
        return addFolders(Lists.newArrayList(folders));
    }

    public boolean addFolders(@Nullable Iterable<FolderInfo> newFolders) {
        boolean added = false;
        if (newFolders != null) {
            for (FolderInfo folderInfo : newFolders) {
                FolderInfo old = getFolderMap().put(folderInfo.getFolder(), folderInfo);
                if (old == null) {
                    added = true;
                }
            }
        }
        storeFolders(); //leave here, save always for 'setFolders'
        return added;
    }

    public synchronized void storeConfiguration() {
        if (outputSupplier != null) {
            try {
                Writer writer = outputSupplier.get();
                checkNotNull(writer);
                store(writer, null);
                logger.debug("config saved");
            } catch (Exception ex) {
                logger.error("unable to store configuration", ex);
            }
        } else {
            logger.debug("dummy save config, no file set");
        }
    }

    public byte[] getData(String key) {
        String value = getProperty(key);
        if (Strings.isNullOrEmpty(value)) {
            return null;
        } else {
            return BaseEncoding.base64().decode(value);
        }
    }

    public void storeData(String key, @Nullable byte[] data) {
        if (data == null) {
            remove(key);
        } else {
            setProperty(key, BaseEncoding.base64().encode(data));
        }
    }

    public boolean hasData(String key) {
        return !Strings.isNullOrEmpty(getProperty(key));
    }

    public long getInstanceId() {
        return instanceId;
    }

    public void setPeers(Iterable<String> peers) {
        setProperty(PEERS, Joiner.on(",").join(peers));
    }

    public List<String> getPeers() {
        String peers = getProperty(PEERS);
        return Strings.isNullOrEmpty(peers) ? Collections.<String>emptyList() : Arrays.asList(peers.split(","));
    }

    public boolean addPeers(String... newPeers) {
        return addPeers(Arrays.asList(newPeers));
    }

    public boolean addPeers(Iterable<String> newPeers) {
        if (newPeers != null) {
            List<String> peers = getPeers();
            Set<String> set = Sets.newTreeSet(Iterables.concat(peers, newPeers));
            setPeers(set);
            return set.size() != peers.size();
        } else {
            return false;
        }
    }

    public List<String> getDiscoveryServers() {
        String servers = getProperty("discoveryserver");
        return Strings.isNullOrEmpty(servers) ? Collections.<String>emptyList() : Arrays.asList(servers.split(","));
    }

    public @Nullable
    File getCache() {
        if (cache == null) {
            String cacheDir = getProperty(CACHE);
            if (!isBlank(cacheDir)) {
                cache = new File(cacheDir);
            } else {
                setCache(new File(System.getProperty("java.io.tmpdir"), "sclient_cache"));
            }
        }
        return cache;
    }

    public File getTemp() {
        if (temp == null) {
            setTemp(new File(System.getProperty("java.io.tmpdir"), "sclient_temp"));
        }
        return temp;
    }

    public File getDatabase() {
        if (database == null) {
            setDatabase(new File(System.getProperty("user.home"), ".config/sclient/db"));
        }
        return database;
    }

    public void setTemp(File temp) {
        temp.mkdirs();
        checkArgument(temp.isDirectory() && temp.canWrite(), "invalid temp dir = %s", temp);
        this.temp = temp;
    }

    public void clearTempDir() {
        FileUtils.deleteQuietly(getTemp());
        getTemp().mkdirs();
    }

    public void setDatabase(File database) {
        database.mkdirs();
        checkArgument(database.isDirectory() && database.canWrite(), "invalid db dir = %s", database);
        this.database = database;
    }

    public void setCache(File cache) {
        cache.mkdirs();
        checkArgument(cache.isDirectory() && cache.canWrite(), "invalid cache dir = %s", cache);
        this.cache = cache;
        setProperty(CACHE, cache.getAbsolutePath());
    }

    public StorageInfo getStorageInfo() {
        return new StorageInfo();
    }

    public boolean isKeystoreRequired() {
        return Boolean.valueOf(getProperty("keystore.required"));
    }

    public void setKeystoreRequired(boolean isRequired) {
        setProperty("keystore.required", Boolean.toString(isRequired));
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
}
