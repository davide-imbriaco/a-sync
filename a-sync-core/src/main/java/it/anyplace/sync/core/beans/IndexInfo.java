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

import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 *
 * @author aleph
 */
public class IndexInfo extends FolderInfo {

    private final long indexId;
    private final String deviceId;
    private final long localSequence, maxSequence;

    private IndexInfo(String folder, String deviceId, long indexId, long localSequence, long maxSequence) {
        super(folder);
        checkNotNull(emptyToNull(deviceId));
        this.deviceId = deviceId;
        this.indexId = indexId;
        this.localSequence = localSequence;
        this.maxSequence = maxSequence;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public Builder copyBuilder() {
        return new Builder(getFolder(), indexId, deviceId, localSequence, maxSequence);
    }

    public String getDeviceId() {
        return deviceId;
    }

    public long getIndexId() {
        return indexId;
    }

    public long getLocalSequence() {
        return localSequence;
    }

    public long getMaxSequence() {
        return maxSequence;
    }

    public double getCompleted() {
        return maxSequence > 0 ? ((double) localSequence) / maxSequence : 0;
    }

    @Override
    public String toString() {
        return "FolderIndexInfo{" + "indexId=" + indexId + ", folder=" + getFolder() + ", deviceId=" + deviceId + ", localSequence=" + localSequence + ", maxSequence=" + maxSequence + '}';
    }

    public static class Builder {

        private long indexId;
        private String deviceId, folder;
        private long localSequence, maxSequence;

        private Builder() {
        }

        private Builder(String folder, long indexId, String deviceId, long localSequence, long maxSequence) {
            checkNotNull(emptyToNull(folder));
            checkNotNull(emptyToNull(deviceId));
            this.folder = folder;
            this.indexId = indexId;
            this.deviceId = deviceId;
            this.localSequence = localSequence;
            this.maxSequence = maxSequence;
        }

        public long getIndexId() {
            return indexId;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public String getFolder() {
            return folder;
        }

        public long getLocalSequence() {
            return localSequence;
        }

        public long getMaxSequence() {
            return maxSequence;
        }

        public Builder setIndexId(long indexId) {
            this.indexId = indexId;
            return this;
        }

        public Builder setDeviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public Builder setFolder(String folder) {
            this.folder = folder;
            return this;
        }

        public Builder setLocalSequence(long localSequence) {
            this.localSequence = localSequence;
            return this;
        }

        public Builder setMaxSequence(long maxSequence) {
            this.maxSequence = maxSequence;
            return this;
        }

        public IndexInfo build() {
            return new IndexInfo(folder, deviceId, indexId, localSequence, maxSequence);
        }

    }

}
