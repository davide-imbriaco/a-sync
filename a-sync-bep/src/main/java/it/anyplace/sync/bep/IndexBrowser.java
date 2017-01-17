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
import static com.google.common.base.Objects.equal;
import com.google.common.collect.Lists;
import it.anyplace.sync.core.beans.FileInfo;
import java.util.Collections;
import java.util.List;
import it.anyplace.sync.core.utils.PathUtils;
import static it.anyplace.sync.core.utils.PathUtils.*;
import java.util.Comparator;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.eventbus.Subscribe;
import it.anyplace.sync.core.utils.ExecutorUtils;
import java.io.Closeable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static com.google.common.base.Strings.emptyToNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import it.anyplace.sync.core.interfaces.IndexRepository;
import static it.anyplace.sync.core.utils.FileInfoOrdering.ALPHA_ASC_DIR_FIRST;
import java.util.concurrent.atomic.AtomicInteger;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import java.util.Map;

/**
 *
 * @author aleph
 */
public final class IndexBrowser implements Closeable {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final LoadingCache<String, List<FileInfo>> listFolderCache = CacheBuilder.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        //        .weigher(new Weigher<String, List<FileInfo>>() {
        //            @Override
        //            public int weigh(String key, List<FileInfo> list) {
        //                return list.size();
        //            }
        //        })
        //        .maximumSize(1000)
        .build(new CacheLoader<String, List<FileInfo>>() {
            @Override
            public List<FileInfo> load(String path) throws Exception {
                return doListFiles(path);
            }

        });
    private final LoadingCache<String, FileInfo> fileInfoCache = CacheBuilder.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        //        .maximumSize(1000)
        //        .weigher(new Weigher<String, FileInfo>() {
        //            @Override
        //            public int weigh(String key, FileInfo fileInfo) {
        //                return fileInfo.getBlocks().size();
        //            }
        //        })
        .build(new CacheLoader<String, FileInfo>() {
            @Override
            public FileInfo load(String path) throws Exception {
                return doGetFileInfoByAbsolutePath(path);
            }
        });

    private final String folder;
    private final IndexRepository indexRepository;
    private final IndexHandler indexHandler;
    private String currentPath;
    private final boolean includeParentInList, allowParentInRoot;
    private final FileInfo PARENT_FILE_INFO, ROOT_FILE_INFO;
    private Comparator<FileInfo> ordering;
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private final Object indexHandlerEventListener = new Object() {
        @Subscribe
        public void handleIndexChangedEvent(IndexHandler.IndexChangedEvent event) {
            if (equal(event.getFolder(), folder)) {
                invalidateCache();
            }
        }

    };
    private final AtomicInteger jobsInQueue = new AtomicInteger(0);

    private IndexBrowser(IndexRepository indexRepository, IndexHandler indexHandler, String folder, boolean includeParentInList, boolean allowParentInRoot, Comparator<FileInfo> ordering) {
        checkNotNull(indexRepository);
        checkNotNull(indexHandler);
        checkNotNull(emptyToNull(folder));
        this.indexRepository = indexRepository;
        this.indexHandler = indexHandler;
        this.indexHandler.getEventBus().register(indexHandlerEventListener);
        this.folder = folder;
        this.includeParentInList = includeParentInList;
        this.allowParentInRoot = allowParentInRoot;
        this.ordering = ordering;
        PARENT_FILE_INFO = FileInfo.newBuilder()
            .setFolder(folder)
            .setTypeDir()
            .setPath(PARENT_PATH)
            .build();
        ROOT_FILE_INFO = FileInfo.newBuilder()
            .setFolder(folder)
            .setTypeDir()
            .setPath(ROOT_PATH)
            .build();
        this.currentPath = ROOT_PATH;
        executorService.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                logger.debug("folder cache cleanup");
                listFolderCache.cleanUp();
                fileInfoCache.cleanUp();
            }

        }, 1, 1, TimeUnit.MINUTES);
    }

    private void invalidateCache() {
        listFolderCache.invalidateAll();
        fileInfoCache.invalidateAll();
        preloadFileInfoForCurrentPath();
    }

    private void preloadFileInfoForCurrentPath() {
        logger.debug("trigger preload");
        final String preloadPath = currentPath;
        executorService.submit(new Runnable() {
            {
                jobsInQueue.addAndGet(1);
            }

            @Override
            public void run() {
                logger.info("folder preload BEGIN for path = '{}'", preloadPath);
                getFileInfoByAbsolutePath(preloadPath);
                if (!PathUtils.isRoot(preloadPath)) {
                    String parent = getParentPath(preloadPath);
                    getFileInfoByAbsolutePath(parent);
                    listFiles(parent);
                }
                for (FileInfo record : listFiles(preloadPath)) {
                    if (!equal(record.getPath(), PARENT_FILE_INFO.getPath()) && record.isDirectory()) {
                        listFiles(record.getPath());
                    }
                }
                logger.info("folder preload END for path = '{}'", preloadPath);
                synchronized (jobsInQueue) {
                    jobsInQueue.addAndGet(-1);
                    jobsInQueue.notifyAll();
                }
            }

        });
    }

    public boolean isCacheReady() {
        return jobsInQueue.get() == 0;
    }

    public boolean isCacheReadyAfterALittleWait() {
        synchronized (jobsInQueue) {
            if (!isCacheReady()) {
                try {
                    jobsInQueue.wait(100);
                } catch (InterruptedException ex) {
                }
            }
        }
        return isCacheReady();
    }

    public IndexBrowser waitForCacheReady() {
        logger.debug("waiting for cache to be ready");
        synchronized (jobsInQueue) {
            while (!isCacheReady()) {
                try {
                    jobsInQueue.wait();
                } catch (InterruptedException ex) {
                }
            }
        }
        return this;
    }

    public String getFolder() {
        return folder;
    }

    public String getCurrentPath() {
        return currentPath;
    }

    public FileInfo getCurrentPathInfo() {
        return getFileInfoByAbsolutePath(getCurrentPath());
    }

    public String getCurrentPathFileName() {
        return PathUtils.getFileName(getCurrentPath());
    }

    public IndexBrowser setOrdering(Comparator<FileInfo> ordering) {
        checkNotNull(ordering);
        this.ordering = ordering;
        //re-sort all data in cache
        for (Map.Entry<String, List<FileInfo>> entry : Lists.newArrayList(listFolderCache.asMap().entrySet())) {
            List<FileInfo> res = Lists.newArrayList(entry.getValue());
            Collections.sort(res, IndexBrowser.this.ordering);
            listFolderCache.put(entry.getKey(), res);
        }
        return this;
    }

    public List<FileInfo> listFiles() {
        return listFiles(currentPath);
    }

    public List<FileInfo> listFiles(String absoluteDirPath) {
        logger.debug("listFiles for path = '{}'", absoluteDirPath);
        return listFolderCache.getUnchecked(absoluteDirPath);
    }

    private List<FileInfo> doListFiles(String path) {
        logger.debug("doListFiles for path = '{}' BEGIN", path);
        List<FileInfo> list = indexRepository.findNotDeletedFilesByFolderAndParent(folder, path);
        logger.debug("doListFiles for path = '{}' : {} records loaded)", path, list.size());
        for (FileInfo fileInfo : list) {
            fileInfoCache.put(fileInfo.getPath(), fileInfo);
        }
        Collections.sort(list, ordering);
        if (includeParentInList && (!PathUtils.isRoot(path) || allowParentInRoot)) {
            list.add(0, PARENT_FILE_INFO);
        }
        logger.debug("doListFiles for path = '{}' : loaded list = {}", list);
        logger.debug("doListFiles for path = '{}' END", path);
        return Collections.unmodifiableList(list);
    }

    public boolean isRoot() {
        return PathUtils.isRoot(currentPath);
    }

    public List<String> listNames() {
        return Collections.unmodifiableList(Lists.transform(listFiles(), new Function<FileInfo, String>() {
            @Override
            public String apply(FileInfo input) {
                return input.getFileName();
            }
        }));
    }

    public FileInfo getFileInfoByRelativePath(String relativePath) {
        return getFileInfoByAbsolutePath(getAbsolutePath(relativePath));
    }

    public FileInfo getFileInfoByAbsolutePath(String path) {
        return PathUtils.isRoot(path) ? ROOT_FILE_INFO : fileInfoCache.getUnchecked(path);
    }

    private FileInfo doGetFileInfoByAbsolutePath(String path) {
        logger.debug("doGetFileInfoByAbsolutePath for path = '{}' BEGIN", path);
        FileInfo fileInfo = indexRepository.findNotDeletedFileInfo(folder, path);
        checkNotNull(fileInfo, "file not found for path = %s", path);
        logger.debug("doGetFileInfoByAbsolutePath for path = '{}' END", path);
        return fileInfo;
    }

    private String getAbsolutePath(String relativePath) {
        if (equal(PARENT_PATH, relativePath)) {
            return getParentPath(currentPath);
        } else {
            return normalizePath(currentPath + PATH_SEPARATOR + relativePath);
        }
    }

    public IndexBrowser navigateToRelativePath(String newPath) {
        return navigateToAbsolutePath(getAbsolutePath(newPath));
    }

    public IndexBrowser navigateTo(FileInfo fileInfo) {
        checkArgument(fileInfo.isDirectory());
        checkArgument(equal(fileInfo.getFolder(), folder));
        return equal(fileInfo.getPath(), PARENT_FILE_INFO.getPath()) ? navigateToParentPath() : navigateToAbsolutePath(fileInfo.getPath());
    }

    public IndexBrowser navigateToNearestPath(@Nullable String oldPath) {
        while (!StringUtils.isBlank(oldPath)) {
            try {
                return navigateToAbsolutePath(oldPath);
            } catch (Exception ex) {
                return navigateToNearestPath(PathUtils.getParentPath(oldPath));
            }
        }
        return this;
    }

    public IndexBrowser navigateToAbsolutePath(String newPath) {
        if (PathUtils.isRoot(newPath)) {
            currentPath = ROOT_PATH;
        } else {
            FileInfo fileInfo = getFileInfoByAbsolutePath(newPath);
            checkNotNull(fileInfo, "path %s does not exist", getAbsolutePath(newPath));
            checkArgument(fileInfo.isDirectory(), "cannot navigate to path %s: not a directory", fileInfo.getPath());
            currentPath = fileInfo.getPath();
        }
        logger.info("navigate to path = '{}'", currentPath);
        preloadFileInfoForCurrentPath();
        return this;
    }

    public IndexBrowser navigateToParentPath() {
        return navigateToAbsolutePath(getParentPath(currentPath));
    }

    @Override
    public void close() {
        logger.info("closing");
        this.indexHandler.getEventBus().unregister(indexHandlerEventListener);
        executorService.shutdown();
        ExecutorUtils.awaitTerminationSafe(executorService);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {

        private String folder;
        private IndexRepository indexRepository;
        private IndexHandler indexHandler;
        private boolean includeParentInList, allowParentInRoot;
        private Comparator<FileInfo> ordering = ALPHA_ASC_DIR_FIRST;

        private Builder() {

        }

        public String getFolder() {
            return folder;
        }

        public Builder setFolder(String folder) {
            this.folder = folder;
            return this;
        }

        public IndexRepository getIndexRepository() {
            return indexRepository;
        }

        public Builder setIndexRepository(IndexRepository indexRepository) {
            this.indexRepository = indexRepository;
            return this;
        }

        public IndexHandler getIndexHandler() {
            return indexHandler;
        }

        public Builder setIndexHandler(IndexHandler indexHandler) {
            this.indexHandler = indexHandler;
            return this;
        }

        public boolean doIncludeParentInList() {
            return includeParentInList;
        }

        public Builder includeParentInList(boolean includeParentInList) {
            this.includeParentInList = includeParentInList;
            return this;
        }

        public boolean doAllowParentInRoot() {
            return allowParentInRoot;
        }

        public Builder allowParentInRoot(boolean allowParentInRoot) {
            this.allowParentInRoot = allowParentInRoot;
            return this;
        }

        public Comparator<FileInfo> getOrdering() {
            return ordering;
        }

        public Builder setOrdering(Comparator<FileInfo> ordering) {
            checkNotNull(ordering);
            this.ordering = ordering;
            return this;
        }

        public IndexBrowser build() {
            return buildToAbsolutePath(ROOT_PATH);
        }

        public IndexBrowser buildToNearestPath(@Nullable String oldPath) {
            return new IndexBrowser(indexRepository, indexHandler, folder, includeParentInList, allowParentInRoot, ordering).navigateToNearestPath(oldPath);
        }

        public IndexBrowser buildToAbsolutePath(String absolutePath) {
            return new IndexBrowser(indexRepository, indexHandler, folder, includeParentInList, allowParentInRoot, ordering).navigateToAbsolutePath(absolutePath);
        }
    }
}
