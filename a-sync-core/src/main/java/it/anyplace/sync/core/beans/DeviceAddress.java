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
package it.anyplace.sync.core.beans;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Objects.equal;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import static com.google.common.base.Strings.emptyToNull;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author aleph
 */
public class DeviceAddress {

    private final static Logger logger = LoggerFactory.getLogger(DeviceAddress.class);
    private final String deviceId;
    private final Long instanceId;
    private final String address;
    private final AddressProducer producer;
    private final int score;
    private final Date lastModified;

    private DeviceAddress(String deviceId, @Nullable Long instanceId, String address, AddressProducer producer, @Nullable Integer score, @Nullable Date lastModified) {
        this.deviceId = deviceId;
        this.instanceId = instanceId;
        this.address = address;
        this.producer = firstNonNull(producer, AddressProducer.UNKNOWN);
        this.score = firstNonNull(score, Integer.MAX_VALUE);
        this.lastModified = firstNonNull(lastModified, new Date());
    }

    public DeviceAddress(String deviceId, String address) {
        this(deviceId, null, address, null, null, null);
    }

    public Date getLastModified() {
        return lastModified;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public String getAddress() {
        return address;
    }

    public AddressProducer getProducer() {
        return producer;
    }

    public int getScore() {
        return score;
    }

    public InetAddress getInetAddress() {
        try {
            return InetAddress.getByName(address.replaceFirst("^[^:]+://", "").replaceFirst("(:[0-9]+)?(/.*)?$", ""));
        } catch (UnknownHostException ex) {
            throw new RuntimeException(ex);
        }
    }

    public int getPort() {
        if (address.matches("^[a-z]+://[^:]+:([0-9]+).*")) {
            return Integer.parseInt(address.replaceFirst("^[a-z]+://[^:]+:([0-9]+).*", "$1"));
        } else {
            return DEFAULT_PORT_BY_PROTOCOL.get(getType());
        }
    }
    private static final Map<AddressType, Integer> DEFAULT_PORT_BY_PROTOCOL = ImmutableMap.<AddressType, Integer>builder()
        .put(AddressType.TCP, 22000)
        .put(AddressType.RELAY, 22067)
        .put(AddressType.HTTP_RELAY, 80)
        .put(AddressType.HTTPS_RELAY, 443)
        .build();

    public AddressType getType() {
        if (Strings.isNullOrEmpty(address)) {
            return AddressType.NULL;
        } else if (address.startsWith("tcp://")) {
            return AddressType.TCP;
        } else if (address.startsWith("relay://")) {
            return AddressType.RELAY;
        } else if (address.startsWith("relay-http://")) {
            return AddressType.HTTP_RELAY;
        } else if (address.startsWith("relay-https://")) {
            return AddressType.HTTPS_RELAY;
        } else {
            return AddressType.OTHER;
        }
    }

    public InetSocketAddress getSocketAddress() {
        return new InetSocketAddress(getInetAddress(), getPort());
    }

    public boolean isWorking() {
        return score < Integer.MAX_VALUE;
    }

    public boolean isTcp() {
        return equal(getType(), AddressType.TCP);
    }

    public boolean containsUriParam(String key) {
        return getUriParam(key) != null;
    }

    public boolean containsUriParamValue(String key) {
        return !Strings.isNullOrEmpty(getUriParam(key));
    }

    public URI getUriSafe() {
        try {
            return URI.create(getAddress());
        } catch (Exception ex) {
            logger.warn("processing invalid url = {}, ex = {}; stripping params", getAddress(), ex.toString());
            return URI.create(getAddress().replaceFirst("^([^/]+://[^/]+)(/.*)?$", "$1"));
        }
    }

    public @Nullable
    String getUriParam(final String key) {
        Preconditions.checkNotNull(emptyToNull(key));
        try {
            NameValuePair record = Iterables.find(URLEncodedUtils.parse(getUriSafe(), StandardCharsets.UTF_8.name()), new Predicate<NameValuePair>() {
                @Override
                public boolean apply(NameValuePair input) {
                    return equal(input.getName(), key);
                }
            }, null);
            return record == null ? null : record.getValue();
        } catch (Exception ex) {
            logger.warn("ex", ex);
            return null;
        }
    }

    public enum AddressType {
        TCP, RELAY, OTHER, NULL, HTTP_RELAY, HTTPS_RELAY
    }

    public enum AddressProducer {
        LOCAL_DISCOVERY, GLOBAL_DISCOVERY, UNKNOWN
    }

    @Override
    public String toString() {
        return "DeviceAddress{" + "deviceId=" + deviceId + ", instanceId=" + instanceId + ", address=" + address + '}';
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 29 * hash + Objects.hashCode(this.deviceId);
        hash = 29 * hash + Objects.hashCode(this.address);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final DeviceAddress other = (DeviceAddress) obj;
        if (!Objects.equals(this.deviceId, other.deviceId)) {
            return false;
        }
        if (!Objects.equals(this.address, other.address)) {
            return false;
        }
        return true;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public Builder copyBuilder() {
        return new Builder(deviceId, instanceId, address, producer, score, lastModified);
    }

    public static class Builder {

        private String deviceId;
        private Long instanceId;
        private String address;
        private AddressProducer producer;
        private Integer score;
        private Date lastModified;

        private Builder() {
        }

        private Builder(String deviceId, Long instanceId, String address, AddressProducer producer, Integer score, Date lastModified) {
            this.deviceId = deviceId;
            this.instanceId = instanceId;
            this.address = address;
            this.producer = producer;
            this.score = score;
            this.lastModified = lastModified;
        }

        public Date getLastModified() {
            return lastModified;
        }

        public Builder setLastModified(Date lastModified) {
            this.lastModified = lastModified;
            return this;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public Builder setDeviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public Long getInstanceId() {
            return instanceId;
        }

        public Builder setInstanceId(Long instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public String getAddress() {
            return address;
        }

        public Builder setAddress(String address) {
            this.address = address;
            return this;
        }

        public AddressProducer getProducer() {
            return producer;
        }

        public Builder setProducer(AddressProducer producer) {
            this.producer = producer;
            return this;
        }

        public Integer getScore() {
            return score;
        }

        public Builder setScore(Integer score) {
            this.score = score;
            return this;
        }

        public DeviceAddress build() {
            return new DeviceAddress(deviceId, instanceId, address, producer, score, lastModified);
        }
    }
}
