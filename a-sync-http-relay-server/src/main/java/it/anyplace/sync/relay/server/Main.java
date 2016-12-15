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
package it.anyplace.sync.relay.server;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Strings.emptyToNull;
import it.anyplace.sync.core.beans.DeviceAddress;
import it.anyplace.sync.httprelay.server.HttpRelayServer;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author aleph
 */
public class Main {

    private final static Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws ParseException, Exception {
        Options options = new Options();
        options.addOption("r", "relay-server", true, "set relay server to serve for");
        options.addOption("p", "port", true, "set http server port");
        options.addOption("h", "help", false, "print help");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        if (cmd.hasOption("h")) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("s-client", options);
            return;
        }
        int port = cmd.hasOption("p") ? Integer.parseInt(cmd.getOptionValue("p")) : 22080;
        String relayServer = firstNonNull(emptyToNull(cmd.getOptionValue("r")), "relay://localhost");
        logger.info("starting http relay server :{} for relay server {}", port, relayServer);
        HttpRelayServer httpRelayServer = new HttpRelayServer(DeviceAddress.newBuilder().setDeviceId("relay").setAddress(relayServer).build().getSocketAddress());
        httpRelayServer.start(port);
        httpRelayServer.join();
    }
}
