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

import java.util.Date;
import org.apache.commons.io.FileUtils;

/**
 *
 * @author aleph
 */
public class FolderStats extends FolderInfo {

    private final long fileCount, dirCount, size;
    private final Date lastUpdate;

    private FolderStats(long fileCount, long dirCount, long size, Date lastUpdate, String folder, String label) {
        super(folder, label);
        this.fileCount = fileCount;
        this.dirCount = dirCount;
        this.size = size;
        this.lastUpdate = lastUpdate;
    }

    public long getFileCount() {
        return fileCount;
    }

    public long getDirCount() {
        return dirCount;
    }

    public long getSize() {
        return size;
    }

    public Date getLastUpdate() {
        return lastUpdate;
    }

    public long getRecordCount() {
        return getDirCount() + getFileCount();
    }

    public String describeSize() {
        return FileUtils.byteCountToDisplaySize(getSize());
    }

    public String dumpInfo() {
        return "folder " + getLabel() + " (" + getFolder() + ") file count = " + getFileCount()
            + " dir count = " + getDirCount() + " folder size = " + describeSize() + " last update = " + getLastUpdate();
    }

    @Override
    public String toString() {
        return "FolderStats{folder=" + getFolder() + ", fileCount=" + fileCount + ", dirCount=" + dirCount + ", size=" + size + ", lastUpdate=" + lastUpdate + '}';
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {

        private long fileCount, dirCount, size;
        private Date lastUpdate = new Date(0);
        private String folder, label;

        private Builder() {
        }

        public long getFileCount() {
            return fileCount;
        }

        public Builder setFileCount(long fileCount) {
            this.fileCount = fileCount;
            return this;
        }

        public long getDirCount() {
            return dirCount;
        }

        public Builder setDirCount(long dirCount) {
            this.dirCount = dirCount;
            return this;
        }

        public long getSize() {
            return size;
        }

        public Builder setSize(long size) {
            this.size = size;
            return this;
        }

        public Date getLastUpdate() {
            return lastUpdate;
        }

        public Builder setLastUpdate(Date lastUpdate) {
            this.lastUpdate = lastUpdate;
            return this;
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

        public FolderStats build() {
            return new FolderStats(fileCount, dirCount, size, lastUpdate, folder, label);
        }

    }
}
