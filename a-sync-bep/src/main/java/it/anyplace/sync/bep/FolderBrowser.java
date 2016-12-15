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
package it.anyplace.sync.bep;

import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;
import it.anyplace.sync.core.beans.FolderInfo;
import it.anyplace.sync.core.beans.FolderStats;
import java.io.Closeable;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import static com.google.common.base.Preconditions.checkNotNull;
import it.anyplace.sync.core.interfaces.IndexRepository;

/**
 *
 * @author aleph
 */
public final class FolderBrowser implements Closeable {

    private final IndexHandler indexHandler;
    private final LoadingCache<String, FolderStats> folderStatsCache = CacheBuilder.newBuilder()
        .build(new CacheLoader<String, FolderStats>() {
            @Override
            public FolderStats load(String folder) throws Exception {
                return FolderStats.newBuilder()
                    .setFolder(folder)
                    .build();
            }
        });
    private final Object indexRepositoryEventListener = new Object() {
        @Subscribe
        public void handleFolderStatsUpdatedEvent(IndexRepository.FolderStatsUpdatedEvent event) {
            addFolderStats(event.getFolderStats());
        }
    };

    protected FolderBrowser(IndexHandler indexHandler) {
        checkNotNull(indexHandler);
        this.indexHandler = indexHandler;
        indexHandler.getIndexRepository().getEventBus().register(indexRepositoryEventListener);
        addFolderStats(indexHandler.getIndexRepository().findAllFolderStats());
    }

    private void addFolderStats(List<FolderStats> folderStatsList) {
        for (FolderStats folderStats : folderStatsList) {
            folderStatsCache.put(folderStats.getFolder(), folderStats);
        }
    }

    public FolderStats getFolderStats(String folder) {
        return folderStatsCache.getUnchecked(folder);
    }

    public FolderInfo getFolderInfo(String folder) {
        return indexHandler.getFolderInfo(folder);
    }

    public List<Pair<FolderInfo, FolderStats>> getFolderInfoAndStatsList() {
        return Lists.newArrayList(Iterables.transform(indexHandler.getFolderInfoList(), new Function<FolderInfo, Pair<FolderInfo, FolderStats>>() {
            @Override
            public Pair<FolderInfo, FolderStats> apply(FolderInfo folderInfo) {
                return Pair.of(folderInfo, getFolderStats(folderInfo.getFolder()));
            }
        }));
    }

    @Override
    public void close() {
        indexHandler.getIndexRepository().getEventBus().unregister(indexRepositoryEventListener);
    }
}
