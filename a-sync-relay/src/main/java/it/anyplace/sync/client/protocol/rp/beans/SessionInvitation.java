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
package it.anyplace.sync.client.protocol.rp.beans;

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.base.Strings;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 *
 * @author aleph
 */
public class SessionInvitation {

    private final String from, key;
    private final InetAddress address;
    private final int port;
    private final boolean isServerSocket;

    private SessionInvitation(String from, String key, InetAddress address, int port, boolean isServerSocket) {
        checkNotNull(Strings.emptyToNull(from));
        checkNotNull(Strings.emptyToNull(key));
        checkNotNull(address);
        this.from = from;
        this.key = key;
        this.address = address;
        this.port = port;
        this.isServerSocket = isServerSocket;
    }

    public String getFrom() {
        return from;
    }

    public String getKey() {
        return key;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public boolean isServerSocket() {
        return isServerSocket;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public InetSocketAddress getSocketAddress() {
        return new InetSocketAddress(getAddress(), getPort());
    }

    public static class Builder {

        private String from, key;
        private InetAddress address;
        private int port;
        private boolean isServerSocket;

        private Builder() {

        }

        public String getFrom() {
            return from;
        }

        public String getKey() {
            return key;
        }

        public InetAddress getAddress() {
            return address;
        }

        public int getPort() {
            return port;
        }

        public boolean isServerSocket() {
            return isServerSocket;
        }

        public Builder setFrom(String from) {
            this.from = from;
            return this;
        }

        public Builder setKey(String key) {
            this.key = key;
            return this;
        }

        public Builder setAddress(InetAddress address) {
            this.address = address;
            return this;
        }

        public Builder setPort(int port) {
            this.port = port;
            return this;
        }

        public Builder setServerSocket(boolean isServerSocket) {
            this.isServerSocket = isServerSocket;
            return this;
        }

        public SessionInvitation build() {
            return new SessionInvitation(from, key, address, port, isServerSocket);
        }
    }

}
