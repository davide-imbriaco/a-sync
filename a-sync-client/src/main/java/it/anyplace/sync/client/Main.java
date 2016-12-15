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

import it.anyplace.sync.core.Configuration;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import it.anyplace.sync.discovery.protocol.gd.GlobalDiscoveryHandler;
import it.anyplace.sync.core.beans.DeviceAddress;
import it.anyplace.sync.discovery.protocol.ld.LocalDiscorveryHandler;
import it.anyplace.sync.core.security.KeystoreHandler;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import it.anyplace.sync.bep.BlockExchangeConnectionHandler;
import it.anyplace.sync.bep.BlockPusher;
import it.anyplace.sync.bep.BlockPusher.IndexEditObserver;
import it.anyplace.sync.bep.IndexBrowser;
import it.anyplace.sync.core.beans.FileInfo;
import static com.google.common.base.Preconditions.checkArgument;
import it.anyplace.sync.discovery.DeviceAddressSupplier;

/**
 *
 * @author aleph
 */
public class Main {

    private final static Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.addOption("C", "set-config", true, "set config file for s-client");
        options.addOption("c", "config", false, "dump config");
        options.addOption("sp", "set-peers", true, "set peer, or comma-separated list of peers");
        options.addOption("q", "query", true, "query directory server for device id");
        options.addOption("d", "discovery", true, "discovery local network for device id");
        options.addOption("p", "pull", true, "pull file from network");
        options.addOption("P", "push", true, "push file to network");
        options.addOption("o", "output", true, "set output file/directory");
        options.addOption("i", "input", true, "set input file/directory");
        options.addOption("lp", "list-peers", false, "list peer addresses");
        options.addOption("a", "address", true, "use this peer addresses");
        options.addOption("L", "list-remote", false, "list folder (root) content from network");
        options.addOption("I", "list-info", false, "dump folder info from network");
        options.addOption("li", "list-info", false, "list folder info from local db");
//        options.addOption("l", "list-local", false, "list folder content from local (saved) index");
        options.addOption("D", "delete", true, "push delete to network");
        options.addOption("M", "mkdir", true, "push directory create to network");
        options.addOption("h", "help", false, "print help");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        if (cmd.hasOption("h")) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("s-client", options);
            return;
        }

        File configFile = cmd.hasOption("C") ? new File(cmd.getOptionValue("C")) : new File(System.getProperty("user.home"), ".s-client.properties");
        logger.info("using config file = {}", configFile);
        Configuration configuration = new Configuration(configFile);
        configuration.clearTempDir();
        KeystoreHandler keystoreHandler = new KeystoreHandler(configuration);//init key
        if (cmd.hasOption("c")) {
            logger.info("configuration =\n{}", configuration.dumpToString());
        } else {
            logger.trace("configuration =\n{}", configuration.dumpToString());
        }
        logger.debug("{}", configuration.getStorageInfo().dumpAvailableSpace());

        if (cmd.hasOption("sp")) {
            List<String> peers = Lists.newArrayList(Lists.transform(Arrays.<String>asList(cmd.getOptionValue("sp").split(",")),
                new Function<String, String>() {
                @Override
                public String apply(String input) {
                    return input.trim();
                }
            }));
            logger.info("set peers = {}", peers);
            for (String peer : peers) {
                KeystoreHandler.validateDeviceId(peer);
            }
            configuration.setPeers(peers);
            configuration.storeConfiguration();
        }

        if (cmd.hasOption("q")) {
            String deviceId = cmd.getOptionValue("q");
            logger.info("query device id = {}", deviceId);
            List<DeviceAddress> deviceAddresses = new GlobalDiscoveryHandler(configuration).query(deviceId);
            logger.info("server response = {}", deviceAddresses);
        }
        if (cmd.hasOption("d")) {
            String deviceId = cmd.getOptionValue("d");
            logger.info("discovery device id = {}", deviceId);
            List<DeviceAddress> deviceAddresses = new LocalDiscorveryHandler(configuration).queryAndClose(deviceId);
            logger.info("local response = {}", deviceAddresses);
        }

        if (cmd.hasOption("p")) {
            String path = cmd.getOptionValue("p");
            logger.info("file path = {}", path);
            String folder = path.split(":")[0];
            path = path.split(":")[1];
            try (SyncthingClient client = new SyncthingClient(configuration); BlockExchangeConnectionHandler connectionHandler = client.connectToBestPeer()) {
                InputStream inputStream = client.pullFile(connectionHandler, folder, path).waitForComplete().getInputStream();
                String fileName = client.getIndexHandler().getFileInfoByPath(folder, path).getFileName();
                File file;
                if (cmd.hasOption("o")) {
                    File param = new File(cmd.getOptionValue("o"));
                    file = param.isDirectory() ? new File(param, fileName) : param;
                } else {
                    file = new File(fileName);
                }
                FileUtils.copyInputStreamToFile(inputStream, file);
                logger.info("saved file to = {}", file.getAbsolutePath());
            }
        }
        if (cmd.hasOption("P")) {
            String path = cmd.getOptionValue("P");
            File file = new File(cmd.getOptionValue("i"));
            checkArgument(!path.startsWith("/")); //TODO check path syntax
            logger.info("file path = {}", path);
            String folder = path.split(":")[0];
            path = path.split(":")[1];
            try (SyncthingClient client = new SyncthingClient(configuration);
                BlockPusher.FileUploadObserver fileUploadObserver = client.pushFile(new FileInputStream(file), folder, path)) {
                while (!fileUploadObserver.isCompleted()) {
                    fileUploadObserver.waitForProgressUpdate();
                    logger.debug("upload progress {}", fileUploadObserver.getProgressMessage());
                }
                logger.info("uploaded file to network");
            }
        }
        if (cmd.hasOption("D")) {
            String path = cmd.getOptionValue("D");
            String folder = path.split(":")[0];
            path = path.split(":")[1];
            logger.info("delete path = {}", path);
            try (SyncthingClient client = new SyncthingClient(configuration);
                IndexEditObserver observer = client.pushDelete(folder, path)) {
                observer.waitForComplete();
                logger.info("deleted path");
            }
        }
        if (cmd.hasOption("M")) {
            String path = cmd.getOptionValue("M");
            String folder = path.split(":")[0];
            path = path.split(":")[1];
            logger.info("dir path = {}", path);
            try (SyncthingClient client = new SyncthingClient(configuration);
                IndexEditObserver observer = client.pushDir(folder, path)) {
                observer.waitForComplete();
                logger.info("uploaded dir to network");
            }
        }
        if (cmd.hasOption("L")) {
            try (SyncthingClient client = new SyncthingClient(configuration)) {
                client.waitForRemoteIndexAquired();
                for (String folder : client.getIndexHandler().getFolderList()) {
                    try (IndexBrowser indexBrowser = client.getIndexHandler().newIndexBrowserBuilder().setFolder(folder).build()) {
                        logger.info("list folder = {}", indexBrowser.getFolder());
                        for (FileInfo fileInfo : indexBrowser.listFiles()) {
                            logger.info("\t\t{} {} {}", fileInfo.getType().name().substring(0, 1), fileInfo.getPath(), fileInfo.describeSize());
                        }
                    }
                }
            }
        }
        if (cmd.hasOption("I")) {
            try (SyncthingClient client = new SyncthingClient(configuration)) {
                if (cmd.hasOption("a")) {
                    String deviceId = cmd.getOptionValue("a").substring(0, 63), address = cmd.getOptionValue("a").substring(64);
                    try (BlockExchangeConnectionHandler connection = client.getConnection(DeviceAddress.newBuilder().setDeviceId(deviceId).setAddress(address).build())) {
                        client.getIndexHandler().waitForRemoteIndexAquired(connection);
                    }
                } else {
                    client.waitForRemoteIndexAquired();
                }
                for (String folder : client.getIndexHandler().getFolderList()) {
                    logger.info("folder info = {}", client.getIndexHandler().getFolderInfo(folder));
                    logger.info("folder stats = {}", client.getIndexHandler().newFolderBrowser().getFolderStats(folder).dumpInfo());
                }
            }
        }
        if (cmd.hasOption("li")) {
            try (SyncthingClient client = new SyncthingClient(configuration)) {
                for (String folder : client.getIndexHandler().getFolderList()) {
                    logger.info("folder info = {}", client.getIndexHandler().getFolderInfo(folder));
                    logger.info("folder stats = {}", client.getIndexHandler().newFolderBrowser().getFolderStats(folder).dumpInfo());
                }
            }
        }
        if (cmd.hasOption("lp")) {
            try (SyncthingClient client = new SyncthingClient(configuration); DeviceAddressSupplier deviceAddressSupplier = client.getDiscoveryHandler().newDeviceAddressSupplier()) {
                for (DeviceAddress deviceAddress : Lists.newArrayList(deviceAddressSupplier)) {
                    logger.info("deviceAddress {} : {}", deviceAddress.getDeviceId(), deviceAddress.getAddress());
                }
            }
        }
//        if (cmd.hasOption("l")) {
//            String indexDump = new IndexHandler(configuration).dumpIndex();
//            logger.info("index dump = \n\n{}\n", indexDump);
//        }
    }

}
