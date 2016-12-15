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
package it.anyplace.sync.repository.repo;

import com.google.common.base.Function;
import static com.google.common.base.Objects.equal;
import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import it.anyplace.sync.core.Configuration;
import it.anyplace.sync.core.beans.BlockInfo;
import it.anyplace.sync.core.beans.FileInfo;
import it.anyplace.sync.core.beans.FileInfo.FileType;
import it.anyplace.sync.core.beans.FileInfo.Version;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Date;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import it.anyplace.sync.core.beans.IndexInfo;
import it.anyplace.sync.core.interfaces.Sequencer;
import java.util.Collections;
import java.util.Random;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import it.anyplace.sync.core.beans.FolderStats;
import static com.google.common.base.Strings.nullToEmpty;
import com.google.common.collect.Maps;
import com.google.common.eventbus.EventBus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import it.anyplace.sync.repository.repo.protos.IndexSerializationProtos;
import it.anyplace.sync.core.beans.FileBlocks;
import static it.anyplace.sync.core.beans.FileInfo.checkBlocks;
import it.anyplace.sync.core.utils.ExecutorUtils;
import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.tuple.Pair;
import static com.google.common.base.Preconditions.checkArgument;
import it.anyplace.sync.core.beans.DeviceAddress;
import it.anyplace.sync.core.interfaces.DeviceAddressRepository;
import it.anyplace.sync.core.interfaces.IndexRepository;
import it.anyplace.sync.core.interfaces.IndexRepository.FolderStatsUpdatedEvent;

/**
 *
 * @author aleph
 */
public class SqlRepository implements Closeable, IndexRepository, DeviceAddressRepository {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Configuration configuration;
    private final static int VERSION = 11;
    private Sequencer sequencer = new IndexRepoSequencer();
    private final String jdbcUrl;
    private final HikariConfig hikariConfig;
    private HikariDataSource dataSource;
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private boolean folderStatsDirty = true;
    private final EventBus eventBus = new EventBus();

    public SqlRepository(Configuration configuration) {
        this.configuration = configuration;
        logger.info("starting sql database");
        File dbDir = new File(configuration.getDatabase(), "index");
        dbDir.mkdirs();
        checkArgument(dbDir.isDirectory() && dbDir.canWrite());
        jdbcUrl = "jdbc:h2:file:" + dbDir.getAbsolutePath() + nullToEmpty(configuration.getProperty("repository.h2.dboptions"));
        logger.debug("jdbc url = {}", jdbcUrl);
        hikariConfig = new HikariConfig();
        hikariConfig.setDriverClassName("org.h2.Driver");
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setMinimumIdle(4);
        dataSource = new HikariDataSource(hikariConfig);
        checkDb();
        scheduledExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
            }
        });
        scheduledExecutorService.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                if (folderStatsDirty) {
                    folderStatsDirty = false;
                    updateFolderStats();
                }
            }
        }, 15, 30, TimeUnit.SECONDS);
        logger.debug("database ready");
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private void checkDb() {
        logger.debug("check db");
        try (Connection connection = getConnection()) {
            try {
                try (PreparedStatement statement = connection.prepareStatement("SELECT version_number FROM version")) {
                    ResultSet resultSet = statement.executeQuery();
                    checkArgument(resultSet.first());
                    int version = resultSet.getInt(1);
                    checkArgument(version == VERSION, "database version mismatch, expected %s, found %s", VERSION, version);
                    logger.info("database check ok, version = {}", version);
                }
            } catch (Exception ex) {
                logger.warn("invalid database, resetting db", ex);
                initDb();
            }
        } catch (Exception ex) {
            close();
            throw new RuntimeException(ex);
        }
    }

    private void initDb() {
        logger.info("init db");
        try (Connection connection = getConnection(); PreparedStatement prepareStatement = connection.prepareStatement("DROP ALL OBJECTS")) {
            prepareStatement.execute();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        try (Connection connection = getConnection()) {
            try (PreparedStatement prepareStatement = connection.prepareStatement("CREATE TABLE index_sequence (index_id BIGINT NOT NULL PRIMARY KEY, current_sequence BIGINT NOT NULL)")) {
                prepareStatement.execute();
            }
            try (PreparedStatement prepareStatement = connection.prepareStatement("CREATE TABLE folder_index_info (folder VARCHAR NOT NULL,"
                + "device_id VARCHAR NOT NULL,"
                + "index_id BIGINT NOT NULL,"
                + "local_sequence BIGINT NOT NULL,"
                + "max_sequence BIGINT NOT NULL,"
                + "PRIMARY KEY (folder, device_id))")) {
                prepareStatement.execute();
            }
            try (PreparedStatement prepareStatement = connection.prepareStatement("CREATE TABLE folder_stats (folder VARCHAR NOT NULL PRIMARY KEY,"
                + "file_count BIGINT NOT NULL,"
                + "dir_count BIGINT NOT NULL,"
                + "last_update BIGINT NOT NULL,"
                + "size BIGINT NOT NULL)")) {
                prepareStatement.execute();
            }
            try (PreparedStatement prepareStatement = connection.prepareStatement("CREATE TABLE file_info (folder VARCHAR NOT NULL,"
                + "path VARCHAR NOT NULL,"
                + "file_name VARCHAR NOT NULL,"
                + "parent VARCHAR NOT NULL,"
                + "size BIGINT,"
                + "hash VARCHAR,"
                + "last_modified BIGINT NOT NULL,"
                + "file_type VARCHAR NOT NULL,"
                + "version_id BIGINT NOT NULL,"
                + "version_value BIGINT NOT NULL,"
                + "is_deleted BOOLEAN NOT NULL,"
                + "PRIMARY KEY (folder, path))")) {
                prepareStatement.execute();
            }
            try (PreparedStatement prepareStatement = connection.prepareStatement("CREATE TABLE file_blocks (folder VARCHAR NOT NULL,"
                + "path VARCHAR NOT NULL,"
                + "hash VARCHAR NOT NULL,"
                + "size BIGINT NOT NULL,"
                + "blocks BINARY NOT NULL,"
                + "PRIMARY KEY (folder, path))")) {
                prepareStatement.execute();
            }
            try (PreparedStatement prepareStatement = connection.prepareStatement("CREATE TABLE device_address (device_id VARCHAR NOT NULL,"
                + "instance_id BIGINT,"
                + "address_url VARCHAR NOT NULL,"
                + "address_producer VARCHAR NOT NULL,"
                + "address_type VARCHAR NOT NULL,"
                + "address_score INT NOT NULL,"
                + "is_working BOOLEAN NOT NULL,"
                + "last_modified BIGINT NOT NULL,"
                + "PRIMARY KEY (device_id, address_url))")) {
                prepareStatement.execute();
            }
            try (PreparedStatement prepareStatement = connection.prepareStatement("CREATE INDEX file_info_folder ON file_info (folder)")) {
                prepareStatement.execute();
            }
            try (PreparedStatement prepareStatement = connection.prepareStatement("CREATE INDEX file_info_folder_path ON file_info (folder, path)")) {
                prepareStatement.execute();
            }
            try (PreparedStatement prepareStatement = connection.prepareStatement("CREATE INDEX file_info_folder_parent ON file_info (folder, parent)")) {
                prepareStatement.execute();
            }
            try (PreparedStatement prepareStatement = connection.prepareStatement("CREATE TABLE version (version_number INT NOT NULL)")) {
                prepareStatement.execute();
            }
            try (PreparedStatement prepareStatement = connection.prepareStatement("INSERT INTO index_sequence VALUES (?,?)")) {
                long newIndexId = Math.abs(new Random().nextLong()) + 1;
                long newStartingSequence = Math.abs(new Random().nextLong()) + 1;
                prepareStatement.setLong(1, newIndexId);
                prepareStatement.setLong(2, newStartingSequence);
                checkArgument(prepareStatement.executeUpdate() == 1);
            }
            try (PreparedStatement prepareStatement = connection.prepareStatement("INSERT INTO version (version_number) VALUES (?)")) {
                prepareStatement.setInt(1, VERSION);
                checkArgument(prepareStatement.executeUpdate() == 1);
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        logger.info("database initialized");
    }

    public Sequencer getSequencer() {
        return sequencer;
    }

    //INDEX INFO
    private final LoadingCache<Pair<String, String>, Optional<IndexInfo>> indexInfoByDeviceIdAndFolder = CacheBuilder.
        newBuilder().expireAfterAccess(1, TimeUnit.DAYS)
        .build(new CacheLoader<Pair<String, String>, Optional<IndexInfo>>() {
            @Override
            public Optional<IndexInfo> load(Pair<String, String> key) throws Exception {
                return Optional.fromNullable(doFindIndexInfoByDeviceAndFolder(key.getLeft(), key.getRight()));
            }

        });

    private IndexInfo readFolderIndexInfo(ResultSet resultSet) throws SQLException {
        return IndexInfo.newBuilder()
            .setFolder(resultSet.getString("folder"))
            .setDeviceId(resultSet.getString("device_id"))
            .setIndexId(resultSet.getLong("index_id"))
            .setLocalSequence(resultSet.getLong("local_sequence"))
            .setMaxSequence(resultSet.getLong("max_sequence"))
            .build();
    }

    public void updateIndexInfo(IndexInfo indexInfo) {
        try (Connection connection = getConnection()) {
            try (PreparedStatement prepareStatement = connection.prepareStatement("MERGE INTO folder_index_info"
                + " (folder,device_id,index_id,local_sequence,max_sequence)"
                + " VALUES (?,?,?,?,?)")) {
                prepareStatement.setString(1, indexInfo.getFolder());
                prepareStatement.setString(2, indexInfo.getDeviceId());
                prepareStatement.setLong(3, indexInfo.getIndexId());
                prepareStatement.setLong(4, indexInfo.getLocalSequence());
                prepareStatement.setLong(5, indexInfo.getMaxSequence());
                prepareStatement.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        indexInfoByDeviceIdAndFolder.put(Pair.of(indexInfo.getDeviceId(), indexInfo.getFolder()), Optional.of(indexInfo));
    }

    public @Nullable
    IndexInfo findIndexInfoByDeviceAndFolder(String deviceId, String folder) {
        return indexInfoByDeviceIdAndFolder.getUnchecked(Pair.of(deviceId, folder)).orNull();
    }

    private @Nullable
    IndexInfo doFindIndexInfoByDeviceAndFolder(final String deviceId, final String folder) {
        try (Connection connection = getConnection(); PreparedStatement prepareStatement = connection.prepareStatement("SELECT * FROM folder_index_info WHERE device_id=? AND folder=?")) {
            prepareStatement.setString(1, deviceId);
            prepareStatement.setString(2, folder);
            ResultSet resultSet = prepareStatement.executeQuery();
            if (resultSet.first()) {
                return readFolderIndexInfo(resultSet);
            } else {
                return null;
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    // FILE INFO
    public @Nullable
    FileInfo findFileInfo(String folder, String path) {
        try (Connection connection = getConnection(); PreparedStatement prepareStatement = connection.prepareStatement("SELECT * FROM file_info WHERE folder=? AND path=?")) {
            prepareStatement.setString(1, folder);
            prepareStatement.setString(2, path);
            ResultSet resultSet = prepareStatement.executeQuery();
            if (resultSet.first()) {
                return readFileInfo(resultSet);
            } else {
                return null;
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public @Nullable
    Date findFileInfoLastModified(String folder, String path) {
        try (Connection connection = getConnection(); PreparedStatement prepareStatement = connection.prepareStatement("SELECT last_modified FROM file_info WHERE folder=? AND path=?")) {
            prepareStatement.setString(1, folder);
            prepareStatement.setString(2, path);
            ResultSet resultSet = prepareStatement.executeQuery();
            if (resultSet.first()) {
                return new Date(resultSet.getLong("last_modified"));
            } else {
                return null;
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public @Nullable
    FileInfo findNotDeletedFileInfo(String folder, String path) {
        try (Connection connection = getConnection(); PreparedStatement prepareStatement = connection.prepareStatement("SELECT * FROM file_info WHERE folder=? AND path=? AND is_deleted=FALSE")) {
            prepareStatement.setString(1, folder);
            prepareStatement.setString(2, path);
            ResultSet resultSet = prepareStatement.executeQuery();
            if (resultSet.first()) {
                return readFileInfo(resultSet);
            } else {
                return null;
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    private FileInfo readFileInfo(ResultSet resultSet) throws SQLException {
        String folder = resultSet.getString("folder"),
            path = resultSet.getString("path");
        FileType fileType = FileType.valueOf(resultSet.getString("file_type"));
        Date lastModified = new Date(resultSet.getLong("last_modified"));
        List<Version> versionList = Collections.singletonList(new Version(resultSet.getLong("version_id"), resultSet.getLong("version_value")));
        boolean isDeleted = resultSet.getBoolean("is_deleted");
        FileInfo.Builder builder = FileInfo.newBuilder()
            .setFolder(folder)
            .setPath(path)
            .setLastModified(lastModified)
            .setVersionList(versionList)
            .setDeleted(isDeleted);
        if (equal(fileType, FileType.DIRECTORY)) {
            return builder.setTypeDir().build();
        } else {
            return builder.setTypeFile().setSize(resultSet.getLong("size")).setHash(resultSet.getString("hash")).build();
        }
    }

    public @Nullable
    FileBlocks findFileBlocks(String folder, String path) {
        try (Connection connection = getConnection(); PreparedStatement prepareStatement = connection.prepareStatement("SELECT * FROM file_blocks WHERE folder=? AND path=?")) {
            prepareStatement.setString(1, folder);
            prepareStatement.setString(2, path);
            ResultSet resultSet = prepareStatement.executeQuery();
            if (resultSet.first()) {
                return readFileBlocks(resultSet);
            } else {
                return null;
            }
        } catch (SQLException | InvalidProtocolBufferException ex) {
            throw new RuntimeException(ex);
        }
    }

    private FileBlocks readFileBlocks(ResultSet resultSet) throws SQLException, InvalidProtocolBufferException {
        IndexSerializationProtos.Blocks blocks = IndexSerializationProtos.Blocks.parseFrom(resultSet.getBytes("blocks"));
        List<BlockInfo> blockList = Lists.transform(blocks.getBlocksList(), new Function<IndexSerializationProtos.BlockInfo, BlockInfo>() {
            @Override
            public BlockInfo apply(IndexSerializationProtos.BlockInfo record) {
                return new BlockInfo(record.getOffset(), record.getSize(), BaseEncoding.base16().encode(record.getHash().toByteArray()));
            }
        });
        return new FileBlocks(resultSet.getString("folder"), resultSet.getString("path"), blockList);
    }

    public void updateFileInfo(FileInfo fileInfo, @Nullable FileBlocks fileBlocks) {
        Version version = Iterables.getLast(fileInfo.getVersionList());
        //TODO open transsaction, rollback
        try (Connection connection = getConnection()) {
            if (fileBlocks != null) {
                checkBlocks(fileInfo, fileBlocks);
                try (PreparedStatement prepareStatement = connection.prepareStatement("MERGE INTO file_blocks"
                    + " (folder,path,hash,size,blocks)"
                    + " VALUES (?,?,?,?,?)")) {
                    prepareStatement.setString(1, fileBlocks.getFolder());
                    prepareStatement.setString(2, fileBlocks.getPath());
                    prepareStatement.setString(3, fileBlocks.getHash());
                    prepareStatement.setLong(4, fileBlocks.getSize());
                    prepareStatement.setBytes(5, IndexSerializationProtos.Blocks.newBuilder()
                        .addAllBlocks(Iterables.transform(fileBlocks.getBlocks(), new Function<BlockInfo, IndexSerializationProtos.BlockInfo>() {
                            @Override
                            public IndexSerializationProtos.BlockInfo apply(BlockInfo input) {
                                return IndexSerializationProtos.BlockInfo.newBuilder()
                                    .setOffset(input.getOffset())
                                    .setSize(input.getSize())
                                    .setHash(ByteString.copyFrom(BaseEncoding.base16().decode(input.getHash())))
                                    .build();
                            }
                        })).build().toByteArray());
                    prepareStatement.executeUpdate();
                }
            }
            try (PreparedStatement prepareStatement = connection.prepareStatement("MERGE INTO file_info"
                + " (folder,path,file_name,parent,size,hash,last_modified,file_type,version_id,version_value,is_deleted)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                prepareStatement.setString(1, fileInfo.getFolder());
                prepareStatement.setString(2, fileInfo.getPath());
                prepareStatement.setString(3, fileInfo.getFileName());
                prepareStatement.setString(4, fileInfo.getParent());
                prepareStatement.setLong(7, fileInfo.getLastModified().getTime());
                prepareStatement.setString(8, fileInfo.getType().name());
                prepareStatement.setLong(9, version.getId());
                prepareStatement.setLong(10, version.getValue());
                prepareStatement.setBoolean(11, fileInfo.isDeleted());
                if (fileInfo.isDirectory()) {
                    prepareStatement.setNull(5, Types.BIGINT);
                    prepareStatement.setNull(6, Types.VARCHAR);
                } else {
                    prepareStatement.setLong(5, fileInfo.getSize());
                    prepareStatement.setString(6, fileInfo.getHash());
                }
                prepareStatement.executeUpdate();
            }
            folderStatsDirty = true;
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public List<FileInfo> findNotDeletedFilesByFolderAndParent(String folder, String parentPath) {
        List<FileInfo> list = Lists.newArrayList();
        try (Connection connection = getConnection(); PreparedStatement prepareStatement = connection.prepareStatement("SELECT * FROM file_info WHERE folder=? AND parent=? AND is_deleted=FALSE")) {
            prepareStatement.setString(1, folder);
            prepareStatement.setString(2, parentPath);
            ResultSet resultSet = prepareStatement.executeQuery();
            while (resultSet.next()) {
                list.add(readFileInfo(resultSet));
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return list;
    }

    // FILE INFO - END
    public void clearIndex() {
        initDb();
        sequencer = new IndexRepoSequencer();
        folderStatsDirty = true;
        indexInfoByDeviceIdAndFolder.invalidateAll();
    }

    // FOLDER STATS - BEGIN
    private FolderStats readFolderStats(ResultSet resultSet) throws SQLException {
        return FolderStats.newBuilder()
            .setFolder(resultSet.getString("folder"))
            .setDirCount(resultSet.getLong("dir_count"))
            .setFileCount(resultSet.getLong("file_count"))
            .setSize(resultSet.getLong("size"))
            .setLastUpdate(new Date(resultSet.getLong("last_update")))
            .build();
    }

    public @Nullable
    FolderStats findFolderStats(String folder) {
        try (Connection connection = getConnection(); PreparedStatement prepareStatement = connection.prepareStatement("SELECT * FROM folder_stats WHERE folder=?")) {
            prepareStatement.setString(1, folder);
            ResultSet resultSet = prepareStatement.executeQuery();
            if (resultSet.first()) {
                return readFolderStats(resultSet);
            } else {
                return null;
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public List<FolderStats> findAllFolderStats() {
        List<FolderStats> list = Lists.newArrayList();
        try (Connection connection = getConnection(); PreparedStatement prepareStatement = connection.prepareStatement("SELECT * FROM folder_stats")) {
            ResultSet resultSet = prepareStatement.executeQuery();
            while (resultSet.next()) {
                list.add(readFolderStats(resultSet));
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return list;
    }

    private void updateFolderStats() {
        logger.info("updateFolderStats BEGIN");
        final Map<String, FolderStats.Builder> map = Maps.newHashMap();
        final Function<String, FolderStats.Builder> func = new Function<String, FolderStats.Builder>() {
            @Override
            public FolderStats.Builder apply(String folder) {
                FolderStats.Builder res = map.get(folder);
                if (res == null) {
                    res = FolderStats.newBuilder().setFolder(folder);
                    map.put(folder, res);
                }
                return res;
            }
        };
        final List<FolderStats> list;
        try (Connection connection = getConnection()) {
            try (PreparedStatement prepareStatement = connection.prepareStatement("SELECT folder, COUNT(*) AS file_count, SUM(size) AS size, MAX(last_modified) AS last_update FROM file_info WHERE file_type=? AND is_deleted=FALSE GROUP BY folder")) {
                prepareStatement.setString(1, FileType.FILE.name());
                ResultSet resultSet = prepareStatement.executeQuery();
                while (resultSet.next()) {
                    FolderStats.Builder builder = func.apply(resultSet.getString("folder"));
                    builder.setSize(resultSet.getLong("size"));
                    builder.setFileCount(resultSet.getLong("file_count"));
                    builder.setLastUpdate(new Date(resultSet.getLong("last_update")));
                }
            }
            try (PreparedStatement prepareStatement = connection.prepareStatement("SELECT folder, COUNT(*) AS dir_count FROM file_info WHERE file_type=? AND is_deleted=FALSE GROUP BY folder")) {
                prepareStatement.setString(1, FileType.DIRECTORY.name());
                ResultSet resultSet = prepareStatement.executeQuery();
                while (resultSet.next()) {
                    FolderStats.Builder builder = func.apply(resultSet.getString("folder"));
                    builder.setDirCount(resultSet.getLong("dir_count"));
                }
            }
            list = Lists.newArrayList(Iterables.transform(map.values(), new Function<FolderStats.Builder, FolderStats>() {
                @Override
                public FolderStats apply(FolderStats.Builder builder) {
                    return builder.build();
                }
            }));
            for (FolderStats folderStats : list) {
                try (PreparedStatement prepareStatement = connection.prepareStatement("MERGE INTO folder_stats"
                    + " (folder,file_count,dir_count,size,last_update)"
                    + " VALUES (?,?,?,?,?)")) {
                    prepareStatement.setString(1, folderStats.getFolder());
                    prepareStatement.setLong(2, folderStats.getFileCount());
                    prepareStatement.setLong(3, folderStats.getDirCount());
                    prepareStatement.setLong(4, folderStats.getSize());
                    prepareStatement.setLong(5, folderStats.getLastUpdate().getTime());
                    prepareStatement.executeUpdate();
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        logger.info("updateFolderStats END");
        eventBus.post(new FolderStatsUpdatedEvent() {
            @Override
            public List<FolderStats> getFolderStats() {
                return Collections.unmodifiableList(list);
            }
        });
    }

    @Override
    public void close() {
        logger.info("closing index repository (sql)");
        scheduledExecutorService.shutdown();
        if (!dataSource.isClosed()) {
            dataSource.close();
        }
        ExecutorUtils.awaitTerminationSafe(scheduledExecutorService);
    }

    //SEQUENCER
    private class IndexRepoSequencer implements Sequencer {

        private Long indexId, currentSequence;

        private synchronized void loadFromDb() {
            try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT index_id,current_sequence FROM index_sequence")) {
                ResultSet resultSet = statement.executeQuery();
                checkArgument(resultSet.first());
                indexId = resultSet.getLong("index_id");
                currentSequence = resultSet.getLong("current_sequence");
                logger.info("loaded index info from db, index_id = {}, current_sequence = {}", indexId, currentSequence);
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public synchronized long indexId() {
            if (indexId == null) {
                loadFromDb();
            }
            return indexId;
        }

        @Override
        public synchronized long nextSequence() {
            long nextSequence = currentSequence() + 1;
            try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement("UPDATE index_sequence SET current_sequence=?")) {
                statement.setLong(1, nextSequence);
                checkArgument(statement.executeUpdate() == 1);
                logger.debug("update local index sequence to {}", nextSequence);
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
            return currentSequence = nextSequence;
        }

        @Override
        public synchronized long currentSequence() {
            if (currentSequence == null) {
                loadFromDb();
            }
            return currentSequence;
        }
    }

    /* device BEGIN */
    
    private DeviceAddress readDeviceAddress(ResultSet resultSet) throws SQLException {
        long instanceId = resultSet.getLong("instance_id");
        return DeviceAddress.newBuilder()
            .setAddress(resultSet.getString("address_url"))
            .setDeviceId(resultSet.getString("device_id"))
            .setInstanceId(instanceId == 0 ? null : instanceId)
            .setProducer(DeviceAddress.AddressProducer.valueOf(resultSet.getString("address_producer")))
            .setScore(resultSet.getInt("address_score"))
            .setLastModified(new Date(resultSet.getLong("last_modified")))
            .build();
    }

    public List<DeviceAddress> findAllDeviceAddress() {
        List<DeviceAddress> list = Lists.newArrayList();
        try (Connection connection = getConnection(); PreparedStatement prepareStatement = connection.prepareStatement("SELECT * FROM device_address ORDER BY last_modified DESC")) {
            ResultSet resultSet = prepareStatement.executeQuery();
            while (resultSet.next()) {
                list.add(readDeviceAddress(resultSet));
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return list;
    }

    public void updateDeviceAddress(DeviceAddress deviceAddress) {
        try (Connection connection = getConnection(); PreparedStatement prepareStatement = connection.prepareStatement("MERGE INTO device_address"
            + " (device_id,instance_id,address_url,address_producer,address_type,address_score,is_working,last_modified)"
            + " VALUES (?,?,?,?,?,?,?,?)")) {
            prepareStatement.setString(1, deviceAddress.getDeviceId());
            if (deviceAddress.getInstanceId() != null) {
                prepareStatement.setLong(2, deviceAddress.getInstanceId());
            } else {
                prepareStatement.setNull(2, Types.BIGINT);
            }
            prepareStatement.setString(3, deviceAddress.getAddress());
            prepareStatement.setString(4, deviceAddress.getProducer().name());
            prepareStatement.setString(5, deviceAddress.getType().name());
            prepareStatement.setInt(6, deviceAddress.getScore());
            prepareStatement.setBoolean(7, deviceAddress.isWorking());
            prepareStatement.setLong(8, deviceAddress.getLastModified().getTime());
            prepareStatement.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    /* device END */
}
