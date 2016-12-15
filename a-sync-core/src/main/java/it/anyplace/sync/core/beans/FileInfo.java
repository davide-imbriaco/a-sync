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

import com.google.common.base.MoreObjects;
import static com.google.common.base.Objects.equal;
import static com.google.common.base.Preconditions.checkArgument;
import com.google.common.base.Strings;
import java.util.Date;
import com.google.common.collect.Lists;
import java.util.Collections;
import java.util.List;
import com.google.common.collect.Iterables;
import it.anyplace.sync.core.utils.PathUtils;
import static it.anyplace.sync.core.utils.PathUtils.PARENT_PATH;
import javax.annotation.Nullable;
import static it.anyplace.sync.core.utils.PathUtils.ROOT_PATH;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.emptyToNull;
import org.apache.commons.io.FileUtils;

/**
 *
 * @author aleph
 */
public class FileInfo {

    private final String folder, fileName, path, parent, hash;
    private final Long size;
    private final Date lastModified;
    private final FileType type;
    private final List<Version> versionList;
    private final boolean deleted;

    private FileInfo(String folder, FileType type, String path, @Nullable Long size, @Nullable Date lastModified, @Nullable String hash, @Nullable List<Version> versionList, boolean deleted) {
        checkNotNull(Strings.emptyToNull(folder));
        checkNotNull(path);//allow empty for 'fake' root path
        checkNotNull(type);
        this.folder = folder;
        this.type = type;
        this.path = path;
        if (PathUtils.isParent(path)) {
            this.fileName = PARENT_PATH;
            this.parent = ROOT_PATH;
        } else {
            this.fileName = PathUtils.getFileName(path);
            this.parent = PathUtils.isRoot(path) ? ROOT_PATH : PathUtils.getParentPath(path);
        }
        this.lastModified = MoreObjects.firstNonNull(lastModified, new Date(0));
        if (type.equals(FileType.DIRECTORY)) {
            this.size = null;
            this.hash = null;
        } else {
            checkNotNull(size);
            checkNotNull(emptyToNull(hash));
            this.size = size;
            this.hash = hash;
        }
        this.versionList = Collections.unmodifiableList(MoreObjects.firstNonNull(versionList, Collections.<Version>emptyList()));
        this.deleted = deleted;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public String getFolder() {
        return folder;
    }

    public String getPath() {
        return path;
    }

    public String getFileName() {
        return fileName;
    }

    public String getParent() {
        return parent;
    }

    public Long getSize() {
        return size;
    }

    public Date getLastModified() {
        return lastModified;
    }

    public FileType getType() {
        return type;
    }

    public enum FileType {
        FILE, DIRECTORY
    }

    public String getHash() {
        return hash;
    }

    public List<Version> getVersionList() {
        return versionList;
    }

    public String describeSize() {
        return isFile() ? FileUtils.byteCountToDisplaySize(getSize()) : "";
    }

    @Override
    public String toString() {
        return "FileRecord{" + "folder=" + folder + ", path=" + path + ", size=" + size + ", lastModified=" + lastModified + ", type=" + type + ", last version = " + Iterables.getLast(versionList, null) + '}';
    }

    public boolean isDirectory() {
        return equal(getType(), FileType.DIRECTORY);
    }

    public boolean isFile() {
        return equal(getType(), FileType.FILE);
    }

    public static class Version {

        private final long id, value;

        public Version(long id, long value) {
            this.id = id;
            this.value = value;
        }

        public long getId() {
            return id;
        }

        public long getValue() {
            return value;
        }

        @Override
        public String toString() {
            return "Version{" + "id=" + id + ", value=" + value + '}';
        }

    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {

        private String folder, path, hash;
        private Long size;
        private Date lastModified = new Date(0);
        private FileType type;
        private List<Version> versionList;
        private boolean deleted = false;

        private Builder() {
        }

        public String getFolder() {
            return folder;
        }

        public Builder setFolder(String folder) {
            this.folder = folder;
            return this;
        }

        public String getPath() {
            return path;
        }

        public Builder setPath(String path) {
            this.path = path;
            return this;
        }

        public Long getSize() {
            return size;
        }

        public Builder setSize(Long size) {
            this.size = size;
            return this;
        }

        public Date getLastModified() {
            return lastModified;
        }

        public Builder setLastModified(Date lastModified) {
            this.lastModified = lastModified;
            return this;
        }

        public FileType getType() {
            return type;
        }

        public Builder setType(FileType type) {
            this.type = type;
            return this;
        }

        public Builder setTypeFile() {
            return setType(FileType.FILE);
        }

        public Builder setTypeDir() {
            return setType(FileType.DIRECTORY);
        }

        public List<Version> getVersionList() {
            return versionList;
        }

        public Builder setVersionList(@Nullable Iterable<Version> versionList) {
            this.versionList = versionList == null ? null : Lists.newArrayList(versionList);
            return this;
        }

        public boolean isDeleted() {
            return deleted;
        }

        public Builder setDeleted(boolean deleted) {
            this.deleted = deleted;
            return this;
        }

        public String getHash() {
            return hash;
        }

        public Builder setHash(String hash) {
            this.hash = hash;
            return this;
        }

        public FileInfo build() {
            return new FileInfo(folder, type, path, size, lastModified, hash, versionList, deleted);
        }

    }

    public static void checkBlocks(FileInfo fileInfo, FileBlocks fileBlocks) {
        checkArgument(equal(fileBlocks.getFolder(), fileInfo.getFolder()), "file info folder not match file block folder");
        checkArgument(equal(fileBlocks.getPath(), fileInfo.getPath()), "file info path does not match file block path");
        checkArgument(fileInfo.isFile(), "file info must be of type 'FILE' to have blocks");
        checkArgument(equal(fileBlocks.getSize(), fileInfo.getSize()), "file info size does not match file block size");
        checkArgument(equal(fileBlocks.getHash(), fileInfo.getHash()), "file info hash does not match file block hash");
    }

}
