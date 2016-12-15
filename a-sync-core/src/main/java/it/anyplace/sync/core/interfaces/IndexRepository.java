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
package it.anyplace.sync.core.interfaces;

import com.google.common.eventbus.EventBus;
import it.anyplace.sync.core.beans.FileBlocks;
import it.anyplace.sync.core.beans.FileInfo;
import it.anyplace.sync.core.beans.FolderStats;
import it.anyplace.sync.core.beans.IndexInfo;
import java.util.Date;
import java.util.List;
import javax.annotation.Nullable;

/**
 *
 * @author aleph
 */
public interface IndexRepository {

    public EventBus getEventBus();

    public Sequencer getSequencer();

    public void updateIndexInfo(IndexInfo indexInfo);

    public @Nullable
    IndexInfo findIndexInfoByDeviceAndFolder(String deviceId, String folder);

    public @Nullable
    FileInfo findFileInfo(String folder, String path);

    public @Nullable
    Date findFileInfoLastModified(String folder, String path);

    public @Nullable
    FileInfo findNotDeletedFileInfo(String folder, String path);

    public @Nullable
    FileBlocks findFileBlocks(String folder, String path);

    public void updateFileInfo(FileInfo fileInfo, @Nullable FileBlocks fileBlocks);

    public List<FileInfo> findNotDeletedFilesByFolderAndParent(String folder, String parentPath);

    public void clearIndex();

    public @Nullable
    FolderStats findFolderStats(String folder);

    public List<FolderStats> findAllFolderStats();

    public abstract class FolderStatsUpdatedEvent {

        public abstract List<FolderStats> getFolderStats();

    }

}
