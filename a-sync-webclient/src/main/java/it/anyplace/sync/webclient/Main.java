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
package it.anyplace.sync.webclient;

import it.anyplace.sync.core.configuration.ConfigurationService;
import it.anyplace.sync.core.security.KeystoreHandler;
import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author aleph
 */
public class Main {

    private final static Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.addOption("C", "set-config", true, "set config file for s-client");
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
        try (ConfigurationService configuration = ConfigurationService.newLoader().loadFrom(configFile)) {
            FileUtils.cleanDirectory(configuration.getTemp());
            KeystoreHandler.newLoader().loadAndStore(configuration);
            logger.debug("{}", configuration.getStorageInfo().dumpAvailableSpace());
            try (HttpService httpService = new HttpService(configuration)) {
                httpService.start();
                if(Desktop.isDesktopSupported()){
                    Desktop.getDesktop().browse(URI.create("http://localhost:"+httpService.getPort()+"/web/webclient.html"));
                }
                httpService.join();
            }
        }
    }

}
