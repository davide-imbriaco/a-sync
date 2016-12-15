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
package it.anyplace.sync.bep.beans;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.emptyToNull;
import javax.annotation.Nullable;

/**
 *
 * @author aleph
 */
public class ClusterConfigFolderInfo {

    private final String folder;
    private final String label;
    private final boolean announced;
    private final boolean shared;

    private ClusterConfigFolderInfo(String folder, @Nullable String label, boolean announced, boolean shared) {
        checkNotNull(emptyToNull(folder));
        this.folder = folder;
        this.label = firstNonNull(emptyToNull(label), folder);
        this.announced = announced;
        this.shared = shared;
    }

    public String getFolder() {
        return folder;
    }

    public String getLabel() {
        return label;
    }

    public boolean isAnnounced() {
        return announced;
    }

    public boolean isShared() {
        return shared;
    }

    @Override
    public String toString() {
        return "ClusterConfigFolderInfo{" + "folder=" + folder + ", label=" + label + ", announced=" + announced + ", shared=" + shared + '}';
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {

        private String folder, label;
        private boolean announced = false, shared = false;

        private Builder() {
        }

        public String getFolder() {
            return folder;
        }

        public Builder setFolder(String folder) {
            this.folder = folder;
            return this;
        }

        public String getLabel() {
            return label;
        }

        public Builder setLabel(String label) {
            this.label = label;
            return this;
        }

        public boolean isAnnounced() {
            return announced;
        }

        public Builder setAnnounced(boolean announced) {
            this.announced = announced;
            return this;
        }

        public boolean isShared() {
            return shared;
        }

        public Builder setShared(boolean shared) {
            this.shared = shared;
            return this;
        }

        public ClusterConfigFolderInfo build() {
            return new ClusterConfigFolderInfo(folder, label, announced, shared);
        }

    }

}
