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

import it.anyplace.sync.core.security.KeystoreHandler;
import javax.annotation.Nullable;
import static org.apache.commons.lang3.StringUtils.isBlank;

public class DeviceInfo {

    private final String deviceId;
    private final String name;

    public DeviceInfo(String deviceId, @Nullable String name) {
        KeystoreHandler.validateDeviceId(deviceId);
        this.deviceId = deviceId;
        this.name = isBlank(name) ? deviceId.substring(0, 7) : name;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "DeviceInfo{" + "deviceId=" + deviceId + ", name=" + name + '}';
    }

}
