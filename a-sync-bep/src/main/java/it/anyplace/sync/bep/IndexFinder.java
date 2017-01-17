/*
 * Copyright 2017 Davide Imbriaco <davide.imbriaco@gmail.com>.
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
package it.anyplace.sync.bep;

import static com.google.common.base.Objects.equal;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import it.anyplace.sync.core.beans.FileInfo;
import it.anyplace.sync.core.interfaces.IndexRepository;
import it.anyplace.sync.core.utils.ExecutorUtils;
import static it.anyplace.sync.core.utils.FileInfoOrdering.ALPHA_ASC_DIR_FIRST;
import java.io.Closeable;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexFinder implements Closeable {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private Comparator<FileInfo> ordering = ALPHA_ASC_DIR_FIRST;
    private final IndexRepository indexRepository;
    private final ExecutorService queryExecutorService = Executors.newSingleThreadExecutor(), eventProcessingService = Executors.newCachedThreadPool();
    private final EventBus eventBus = new AsyncEventBus(eventProcessingService);
    private final boolean dropQueriesOnNewSubmit = true;
    private final int maxResults;
//    private final Queue<Future> runningQueries = Queues.newConcurrentLinkedQueue();
    private Future previousQuery;

    private IndexFinder(IndexRepository indexRepository, int maxResults) {
        checkNotNull(indexRepository);
        checkArgument(maxResults > 0);
        this.indexRepository = indexRepository;
        this.maxResults = maxResults;
    }

    public IndexFinder setOrdering(Comparator<FileInfo> ordering) {
        checkNotNull(ordering);
        this.ordering = ordering;
        return this;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public synchronized IndexFinder submitSearch(final String query) {
        checkArgument(!StringUtils.isBlank(query), "query term cannot be blank");
        logger.info("submitSearch, term = '{}'", query);
        if (dropQueriesOnNewSubmit == true && previousQuery != null) {
//            previousQuery.cancel(true); note: cannot interrupt. interrupt break h2 connection to db
            previousQuery.cancel(false);
        }
        previousQuery = queryExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    logger.info("search file info for query = '{}'", query);
                    final long count = indexRepository.countFileInfoBySearchTerm(query);
                    final boolean hasTooManyResults = count > maxResults, hasGoodResults = count > 0 && count <= maxResults;
                    final List<FileInfo> list = hasGoodResults ? indexRepository.findFileInfoBySearchTerm(query) : null;
//                    final List<FileInfo> list = indexRepository.findFileInfoBySearchTerm(query);
//                    final boolean hasTooManyResults = list.size() > maxResults, hasGoodResults = !list.isEmpty() && !hasTooManyResults;
                    logger.info("got {} results for search term = '{}'", count, query);
                    if (!eventProcessingService.isShutdown()) {
                        eventBus.post(new SearchCompletedEvent() {
                            @Override
                            public String getQuery() {
                                return query;
                            }

                            @Override
                            public List<FileInfo> getResultList() {
                                checkNotNull(list, "this query has no good results (got either too many results or zero results)");
                                List<FileInfo> res = Lists.newArrayList(list);
                                Collections.sort(list, ordering);
                                return res;
                            }

                            @Override
                            public long getResultCount() {
                                return count;
                            }

                            @Override
                            public boolean hasZeroResults() {
                                return count == 0;
                            }

                            @Override
                            public boolean hasTooManyResults() {
                                return hasTooManyResults;
                            }

                            @Override
                            public boolean hasGoodResults() {
                                return hasGoodResults;
                            }
                        });
                    }
                } catch (Exception ex) {
                    if (Thread.currentThread().isInterrupted()) {
                        logger.warn("interrupted search for term = '{}', ex = {}", query, ex);
                    } else {
                        logger.error("error running file info search by term = '{}'", query);
                        logger.error("error running file info search by term", ex);
                    }
                }
            }
        });
        return this;
    }

    public SearchCompletedEvent doSearch(final String term) throws InterruptedException {
        final Object lock = new Object();
        final AtomicReference<SearchCompletedEvent> reference = new AtomicReference<>();
        final Object eventListener = new Object() {
            @Subscribe
            public void handleSearchCompletedEvent(SearchCompletedEvent event) {
                if (equal(event.getQuery(), term)) {
                    synchronized (lock) {
                        reference.set(event);
                        lock.notify();
                    }
                }
            }
        };
        synchronized (lock) {
            eventBus.register(eventListener);
            submitSearch(term);
            try {
                while (!Thread.currentThread().isInterrupted() && reference.get() == null) {
                    lock.wait();
                }
                checkNotNull(reference.get());
                return reference.get();
            } finally {
                eventBus.unregister(eventListener);
            }
        }
    }

    public interface SearchCompletedEvent {

        public String getQuery();

        public long getResultCount();

        public boolean hasZeroResults();

        public boolean hasTooManyResults();

        public boolean hasGoodResults();

        public List<FileInfo> getResultList();
    }

    @Override
    public void close() {
        queryExecutorService.shutdown();
        eventProcessingService.shutdown();
        ExecutorUtils.awaitTerminationSafe(eventProcessingService);
//        ExecutorUtils.awaitTerminationSafe(executorService); note: not waiting for temination (fast close)
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {

        private IndexRepository indexRepository;
        private int maxResults = 16;

        private Builder() {

        }

        public IndexRepository getIndexRepository() {
            return indexRepository;
        }

        public Builder setIndexRepository(IndexRepository indexRepository) {
            this.indexRepository = indexRepository;
            return this;
        }

        public int getMaxResults() {
            return maxResults;
        }

        public Builder setMaxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public IndexFinder build() {
            return new IndexFinder(indexRepository, maxResults);
        }
    }
}
