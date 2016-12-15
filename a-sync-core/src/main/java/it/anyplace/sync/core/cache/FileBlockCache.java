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
package it.anyplace.sync.core.cache;

import com.google.common.base.Function;
import static com.google.common.base.Objects.equal;
import static com.google.common.base.Preconditions.checkArgument;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author aleph
 */
public class FileBlockCache extends BlockCache {

    private final static ExecutorService writerThread = Executors.newSingleThreadExecutor();

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final File dir;
    private long size;
    private final long MAX_SIZE = 50 * 1024 * 1024; //TODO max size from config
    private final double PERC_TO_DELETE = 0.5; //perc of files to delete on max size cleanup

    public FileBlockCache(File cacheDirectory) {
        this.dir = cacheDirectory;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        checkArgument(dir.isDirectory() && dir.canWrite());
        size = FileUtils.sizeOfDirectory(dir);
        runCleanup();
    }

    @Override
    public String pushBlock(final byte[] data) {
        String code = BaseEncoding.base16().encode(Hashing.sha256().hashBytes(data).asBytes());
        return pushData(code, data) ? code : null;
    }

    private void runCleanup() {
        if (size > MAX_SIZE) {
            logger.info("starting cleanup of cache directory, initial size = {}", FileUtils.byteCountToDisplaySize(size));
            List<File> files = Lists.newArrayList(dir.listFiles());
            Collections.sort(files, Ordering.natural().onResultOf(new Function<File, Long>() {
                @Override
                public Long apply(File input) {
                    return input.lastModified();
                }
            }));
            for (File file : Iterables.limit(files, (int) (files.size() * PERC_TO_DELETE))) {
                logger.debug("delete file {}", file);
                FileUtils.deleteQuietly(file);
            }
            size = FileUtils.sizeOfDirectory(dir);
            logger.info("cleanup of cache directory completed, final size = {}", FileUtils.byteCountToDisplaySize(size));
        }
    }

    @Override
    public @Nullable
    byte[] pullBlock(String code) {
        return pullFile(code, true);
    }

    private @Nullable
    byte[] pullFile(String code, boolean shouldCheck) {

        final File file = new File(dir, code);
        if (file.exists()) {
            try {
                byte[] data = FileUtils.readFileToByteArray(file);
                if (shouldCheck) {
                    String cachedDataCode = BaseEncoding.base16().encode(Hashing.sha256().hashBytes(data).asBytes());
                    checkArgument(equal(code, cachedDataCode), "cached data code %s does not match code %s", cachedDataCode, code);
                }
                writerThread.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            FileUtils.touch(file);
                        } catch (IOException ex) {
                            logger.warn("unable to 'touch' file {}", file);
                            logger.warn("unable to 'touch' file", ex);
                        }
                    }

                });
                logger.debug("read block {} from cache file {}", code, file);
                return data;
            } catch (Exception ex) {
                logger.warn("error reading block from cache", ex);
                FileUtils.deleteQuietly(file);
            }
        }
        return null;
    }

//        @Override
//        public void close() {
//            writerThread.shutdown();
//            try {
//                writerThread.awaitTermination(2, TimeUnit.SECONDS);
//            } catch (InterruptedException ex) {
//                logger.warn("pending threads on block cache writer executor");
//            }
//        }
    @Override
    public boolean pushData(final String code, final byte[] data) {
        try {
            writerThread.submit(new Runnable() {
                @Override
                public void run() {
                    File file = new File(dir, code);
                    if (!file.exists()) {
                        try {
                            FileUtils.writeByteArrayToFile(file, data);
                            logger.debug("cached block {} to file {}", code, file);
                            size += data.length;
                            runCleanup();
                        } catch (IOException ex) {
                            logger.warn("error writing block in cache", ex);
                            FileUtils.deleteQuietly(file);
                        }
                    }
                }
            });
            return true;
        } catch (Exception ex) {
            logger.warn("error caching block", ex);
            return false;
        }
    }

    @Override
    public byte[] pullData(String code) {
        return pullFile(code, false);
    }

    @Override
    public void clear() {
        writerThread.submit(new Runnable() {
            @Override
            public void run() {
                FileUtils.deleteQuietly(dir);
                dir.mkdirs();
            }

        });
    }
}
