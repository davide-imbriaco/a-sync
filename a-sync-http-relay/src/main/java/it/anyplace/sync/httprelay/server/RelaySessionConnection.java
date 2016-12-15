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

import static com.google.common.base.Objects.equal;
import com.google.common.eventbus.EventBus;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.protobuf.ByteString;
import it.anyplace.sync.httprelay.protos.HttpRelayProtos;
import it.anyplace.sync.httprelay.protos.HttpRelayProtos.HttpRelayPeerMessage;
import it.anyplace.sync.httprelay.protos.HttpRelayProtos.HttpRelayServerMessage;
import java.io.File;
import java.io.FileOutputStream;
import org.apache.commons.io.FileUtils;
import static com.google.common.base.Preconditions.checkArgument;
import it.anyplace.sync.core.interfaces.RelayConnection;

/**
 *
 * @author aleph
 */
public class RelaySessionConnection implements Closeable {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final String sessionId;
    private final EventBus eventBus = new EventBus();
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private long peerToRelaySequence = 0, relayToPeerSequence = 0;
    private final ExecutorService readerExecutorService = Executors.newSingleThreadExecutor(), processorService = Executors.newSingleThreadExecutor();
    private File tempFile;
    private final RelayConnection relayConnection;

    public RelaySessionConnection(RelayConnection relayConnection) {
        this.relayConnection = relayConnection;
        this.sessionId = UUID.randomUUID().toString();
    }

    public long getPeerToRelaySequence() {
        return peerToRelaySequence;
    }

    public long getRelayToPeerSequence() {
        return relayToPeerSequence;
    }

    public void sendData(HttpRelayPeerMessage message) throws IOException {
        synchronized (outputStream) {
            checkArgument(equal(message.getMessageType(), HttpRelayProtos.HttpRelayPeerMessageType.PEER_TO_RELAY));
            checkArgument(equal(message.getSessionId(), sessionId));
            checkArgument(message.getSequence() == peerToRelaySequence + 1);
            if (message.getData() != null && !message.getData().isEmpty()) {
                try {
                    logger.debug("sending {} bytes of data from peer to relay", message.getData().size());
                    message.getData().writeTo(outputStream);
                    peerToRelaySequence = message.getSequence();
                } catch (IOException ex) {
                    close();
                    throw ex;
                }
            }
        }
    }

    private File getTempFile() {
        synchronized (inputStream) {
            if (tempFile == null) {
                tempFile = createTempFile();
            }
            return tempFile;
        }
    }

    private File createTempFile() {
        try {
            File file = File.createTempFile("http-relay-" + sessionId, null);
            file.deleteOnExit();
            return file;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private byte[] popTempFile() {
        try {
            File newFile;
            synchronized (inputStream) {
                if (!hasData()) {
                    return new byte[0];
                }
                newFile = createTempFile();
                getTempFile().renameTo(newFile);
                tempFile = null;
            }
            byte[] data = FileUtils.readFileToByteArray(newFile);
            FileUtils.deleteQuietly(newFile);
            logger.debug("returning {} bytes of data from relay to peer", data.length);
            return data;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public HttpRelayServerMessage getData() {
        checkArgument(!isClosed());
        byte[] data = popTempFile();
        return HttpRelayServerMessage.newBuilder()
            .setData(ByteString.copyFrom(data))
            .setMessageType(HttpRelayProtos.HttpRelayServerMessageType.RELAY_TO_PEER)
            .setSequence(++relayToPeerSequence)
            .build();
    }

    private boolean isClosed = false;

    public boolean isClosed() {
        return isClosed;
    }

    public boolean hasData() {
        synchronized (inputStream) {
            return getTempFile().exists() && getTempFile().length() > 0;
        }
    }

    public HttpRelayServerMessage waitForDataAndGet(long timeout) {
        synchronized (inputStream) {
            if (!isClosed() && !hasData()) {
                try {
                    inputStream.wait(timeout);
                } catch (InterruptedException ex) {
                }
            }
        }
        if (isClosed()) {
            return HttpRelayServerMessage.newBuilder().setMessageType(HttpRelayProtos.HttpRelayServerMessageType.SERVER_CLOSING).build();
        } else {
            return getData();
        }
    }

    public void connect() throws IOException {
        socket = relayConnection.getSocket();
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
        readerExecutorService.submit(new Runnable() {

            private final byte[] buffer = new byte[1024 * 10]; //10k

            @Override
            public void run() {
                while (!Thread.interrupted() && !isClosed()) {
                    try {
                        int count = inputStream.read(buffer);
                        if (count < 0) {
                            closeBg();
                            return;
                        }
                        if (count == 0) {
                            continue;
                        }
                        logger.info("received {} bytes from relay for session {}", count, sessionId);
                        synchronized (inputStream) {
                            try (OutputStream out = new FileOutputStream(getTempFile(), true)) {
                                out.write(buffer, 0, count);
                            }
                            inputStream.notifyAll();
                        }
                        processorService.submit(new Runnable() {
                            @Override
                            public void run() {
                                eventBus.post(DataReceivedEvent.INSTANCE);
                            }
                        });
                    } catch (IOException ex) {
                        if (isClosed()) {
                            return;
                        }
                        logger.error("error reading data", ex);
                        closeBg();
                        return;
                    }
                }
            }

            private void closeBg() {

                new Thread() {
                    @Override
                    public void run() {
                        close();
                    }
                }.start();
            }
        });
    }

    public String getSessionId() {
        return sessionId;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public void close() {
        if (!isClosed()) {
            isClosed = true;
            logger.info("closing connection for session = {}", sessionId);
            readerExecutorService.shutdown();
            processorService.shutdown();
            if (inputStream != null) {
                IOUtils.closeQuietly(inputStream);
                synchronized (inputStream) {
                    inputStream.notifyAll();
                }
                inputStream = null;
            }
            if (outputStream != null) {
                IOUtils.closeQuietly(outputStream);
                outputStream = null;
            }
            if (socket != null) {
                IOUtils.closeQuietly(socket);
                socket = null;
            }
            try {
                readerExecutorService.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
            }
            try {
                processorService.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
            }
            eventBus.post(ConnectionClosedEvent.INSTANCE);
        }
    }

    public boolean isServerSocket() {
        return relayConnection.isServerSocket();
    }

    public enum DataReceivedEvent {
        INSTANCE;
    }

    public enum ConnectionClosedEvent {
        INSTANCE;
    }

}
