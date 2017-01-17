/*
 * Copyright 2016 Davide Imbriaco <davide.imbriaco@gmail.com>.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.emptyToNull;
import java.util.Date;
import static org.apache.commons.lang3.StringUtils.isBlank;

public class DeviceStats {

    private final String deviceId, name;

    private final Date lastActive, lastSeen;

    private final DeviceStatus status;

    private DeviceStats(String deviceId, String name, Date lastActive, Date lastSeen, DeviceStatus status) {
        checkNotNull(emptyToNull(deviceId));
        checkNotNull(status);
        checkNotNull(lastActive);
        checkNotNull(lastSeen);
        this.deviceId = deviceId;
        this.name = isBlank(name) ? deviceId.substring(0, 7) : name;
        this.lastActive = lastActive;
        this.lastSeen = lastSeen;
        this.status = status;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getName() {
        return name;
    }

    public Date getLastActive() {
        return lastActive;
    }

    public Date getLastSeen() {
        return lastSeen;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public Builder copyBuilder() {
        return new Builder(deviceId, name, lastActive, lastSeen, status);
    }

    @Override
    public String toString() {
        return "DeviceStats{" + "deviceId=" + deviceId + ", name=" + name + ", status=" + status + '}';
    }

    public static enum DeviceStatus {
        OFFLINE, ONLINE_ACTIVE, ONLINE_INACTIVE
    }

    public static class Builder {

        private String deviceId, name;

        private Date lastActive = new Date(0), lastSeen = new Date(0);

        private DeviceStatus status = DeviceStatus.OFFLINE;

        private Builder() {
        }

        private Builder(String deviceId, String name, Date lastActive, Date lastSeen, DeviceStatus status) {
            this.deviceId = deviceId;
            this.name = name;
            this.lastActive = lastActive;
            this.lastSeen = lastSeen;
            this.status = status;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public Builder setDeviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public String getName() {
            return name;
        }

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Date getLastActive() {
            return lastActive;
        }

        public Builder setLastActive(Date lastActive) {
            this.lastActive = lastActive;
            return this;
        }

        public Date getLastSeen() {
            return lastSeen;
        }

        public Builder setLastSeen(Date lastSeen) {
            this.lastSeen = lastSeen;
            return this;
        }

        public DeviceStatus getStatus() {
            return status;
        }

        public Builder setStatus(DeviceStatus status) {
            this.status = status;
            return this;
        }

        public DeviceStats build() {
            return new DeviceStats(deviceId, name, lastActive, lastSeen, status);
        }

    }
}
