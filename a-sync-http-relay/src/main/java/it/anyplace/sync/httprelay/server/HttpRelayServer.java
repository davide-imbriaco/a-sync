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
package it.anyplace.sync.httprelay.server;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import com.google.protobuf.ByteString;
import it.anyplace.sync.core.configuration.ConfigurationService;
import it.anyplace.sync.httprelay.server.RelaySessionConnection.ConnectionClosedEvent;
import it.anyplace.sync.httprelay.protos.HttpRelayProtos;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.google.common.base.Strings.emptyToNull;
import it.anyplace.sync.client.protocol.rp.RelayClient;
import it.anyplace.sync.client.protocol.rp.beans.SessionInvitation;
import it.anyplace.sync.core.security.KeystoreHandler;
import it.anyplace.sync.core.interfaces.RelayConnection;
import java.io.File;
import static com.google.common.base.Preconditions.checkNotNull;
import java.io.Closeable;
import org.apache.commons.io.IOUtils;

/**
 *
 * @author aleph
 */
public class HttpRelayServer implements Closeable {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final InetSocketAddress relayServerAddress;
    private Server server;
    private final static long MAX_WAIT_FOR_DATA_SECS = 30;
    private ConfigurationService configuration;
    private final KeystoreHandler keystoreHandler;

    public HttpRelayServer(InetSocketAddress relayServerAddress) {
        this.relayServerAddress = relayServerAddress;
        try {
            this.configuration = ConfigurationService.newLoader().loadFrom(new File(System.getProperty("user.home"), ".config/a-sync-http-relay.properties"));
        } catch (Exception ex) {
            logger.warn("error loading config", ex);
            this.configuration = ConfigurationService.newLoader().load();
        }
        keystoreHandler = KeystoreHandler.newLoader().loadAndStore(configuration);
    }

    private final Map<String, RelaySessionConnection> relayConnectionsBySessionId = Maps.newConcurrentMap();

    private RelaySessionConnection openConnection(String deviceId) throws Exception {
        RelayClient relayClient = new RelayClient(configuration);
        SessionInvitation sessionInvitation = relayClient.getSessionInvitation(relayServerAddress, deviceId);
        RelayConnection relayConnection = relayClient.openConnectionSessionMode(sessionInvitation);
        final RelaySessionConnection relaySessionConnection = new RelaySessionConnection(relayConnection);
        relayConnectionsBySessionId.put(relaySessionConnection.getSessionId(), relaySessionConnection);
        relaySessionConnection.getEventBus().register(new Object() {
            @Subscribe
            public void handleConnectionClosedEvent(ConnectionClosedEvent event) {
                relayConnectionsBySessionId.remove(relaySessionConnection.getSessionId());
            }
        });
        relaySessionConnection.connect();
        return relaySessionConnection;
    }

    public void start(int port) throws Exception {
        server = new Server(port);
//            if (soapSsl) {
//                SslContextFactory sslContextFactory = new SslContextFactory();
//                sslContextFactory.setKeyStorePath(Main.class.getResource("/keystore.jks").toExternalForm());
//                sslContextFactory.setKeyStorePassword("cjstorepass");
//                sslContextFactory.setKeyManagerPassword("cjrestkeypass");
//                SslSocketConnector connector = new SslSocketConnector(sslContextFactory);
//                connector.setPort(serverPort);
//                server.setConnectors(new Connector[]{connector});
//            } else {
//                SocketConnector connector = new SocketConnector();
//                connector.setPort(port);
//                server.setConnectors(new Connector[]{connector});
//            }

        server.setHandler(new AbstractHandler() {

            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                logger.trace("handling requenst");
                HttpRelayProtos.HttpRelayServerMessage serverMessage;
                try {
                    HttpRelayProtos.HttpRelayPeerMessage peerMessage = HttpRelayProtos.HttpRelayPeerMessage.parseFrom(request.getInputStream());
                    logger.debug("handle peer message type = {} session id = {} sequence = {}", peerMessage.getMessageType(), peerMessage.getSessionId(), peerMessage.getSequence());
                    serverMessage = handleMessage(peerMessage);
                } catch (Exception ex) {
                    logger.error("error", ex);
                    serverMessage = HttpRelayProtos.HttpRelayServerMessage.newBuilder()
                        .setMessageType(HttpRelayProtos.HttpRelayServerMessageType.ERROR)
                        .setData(ByteString.copyFromUtf8("error : " + ex.toString()))
                        .build();
                }
                logger.debug("send server response message type = {} session id = {} sequence = {}", serverMessage.getMessageType(), serverMessage.getSessionId(), serverMessage.getSequence());
                try {
                    serverMessage.writeTo(response.getOutputStream());
                    response.getOutputStream().flush();
                } catch (Exception ex) {
                    logger.error("error", ex);
                }
            }
        });
        server.start();
        logger.info("http relay server READY on port {}", port);
    }

    private HttpRelayProtos.HttpRelayServerMessage handleMessage(HttpRelayProtos.HttpRelayPeerMessage message) throws Exception {
        switch (message.getMessageType()) {
            case CONNECT: {
                String deviceId = message.getDeviceId();
                checkNotNull(emptyToNull(deviceId));
                RelaySessionConnection connection = openConnection(deviceId);
                return HttpRelayProtos.HttpRelayServerMessage.newBuilder()
                    .setMessageType(HttpRelayProtos.HttpRelayServerMessageType.PEER_CONNECTED)
                    .setSessionId(connection.getSessionId())
                    .setIsServerSocket(connection.isServerSocket())
                    .build();
            }
            case PEER_CLOSING: {
                RelaySessionConnection connection = requireConnectionBySessionId(message.getSessionId());
                connection.close();
                return HttpRelayProtos.HttpRelayServerMessage.newBuilder()
                    .setMessageType(HttpRelayProtos.HttpRelayServerMessageType.SERVER_CLOSING)
                    .build();
            }
            case PEER_TO_RELAY: {
                RelaySessionConnection connection = requireConnectionBySessionId(message.getSessionId());
                connection.sendData(message);
                return HttpRelayProtos.HttpRelayServerMessage.newBuilder()
                    .setMessageType(HttpRelayProtos.HttpRelayServerMessageType.DATA_ACCEPTED)
                    .build();
            }
            case WAIT_FOR_DATA: {
                RelaySessionConnection connection = requireConnectionBySessionId(message.getSessionId());
                HttpRelayProtos.HttpRelayServerMessage response = connection.waitForDataAndGet(MAX_WAIT_FOR_DATA_SECS * 1000);
                return response;
            }
        }
        throw new IllegalArgumentException("unsupported message type = " + message.getMessageType());
    }

    private RelaySessionConnection requireConnectionBySessionId(String sessionId) {
        checkNotNull(Strings.emptyToNull(sessionId));
        RelaySessionConnection connection = relayConnectionsBySessionId.get(sessionId);
        checkNotNull(connection, "connection not found for sessionId = %s", sessionId);
        return connection;
    }

    public void join() throws InterruptedException {
        server.join();
    }

    @Override
    public void close() {
        try {
            server.stop();
        } catch (Exception ex) {
            logger.warn("error stopping server", ex);
        }
        IOUtils.closeQuietly(configuration);
    }

}
