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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.emptyToNull;
import com.google.common.collect.Lists;
import it.anyplace.sync.core.utils.BlockUtils;
import java.util.Collections;
import java.util.List;

/**
 *
 * @author aleph
 */
public class FileBlocks {

    private final List<BlockInfo> blocks;
    private final String hash, folder, path;
    private final long size;

    public FileBlocks(String folder, String path, Iterable<BlockInfo> blocks) {
        checkNotNull(emptyToNull(folder));
        checkNotNull(emptyToNull(path));
        checkNotNull(blocks);
        this.folder = folder;
        this.path = path;
        this.blocks = Collections.unmodifiableList(Lists.newArrayList(blocks));
        long num = 0;
        for (BlockInfo block : blocks) {
            num += block.getSize();
        }
        this.size = num;
        this.hash = BlockUtils.hashBlocks(this.blocks);
    }

    public List<BlockInfo> getBlocks() {
        return blocks;
    }

    public String getHash() {
        return hash;
    }

    public long getSize() {
        return size;
    }

    public String getFolder() {
        return folder;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return "FileBlocks{" + "blocks=" + blocks.size() + ", hash=" + hash + ", folder=" + folder + ", path=" + path + ", size=" + size + '}';
    }

}
