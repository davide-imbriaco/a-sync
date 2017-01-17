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

import it.anyplace.sync.core.configuration.ConfigurationService;
import javax.annotation.Nullable;
import org.slf4j.LoggerFactory;

/**
 *
 * @author aleph
 */
public abstract class BlockCache {

    public static BlockCache getBlockCache(ConfigurationService configuration) {
        if (configuration.getCache() != null) {
            try {
                return new FileBlockCache(configuration.getCache());
            } catch (Exception ex) {
                LoggerFactory.getLogger(BlockCache.class).warn("unable to open cache", ex);
            }
        }
        return new DummyBlockCache();
    }

    /**
     * note: The data may be written to disk in background. Do not modify the
     * supplied array
     *
     * @param data
     * @return cache block code, or null in case of errors
     */
    public abstract String pushBlock(byte[] data);
    
    public abstract boolean pushData(String code, byte[] data);

    public abstract @Nullable
    byte[] pullBlock(String code);
    
    public abstract @Nullable
    byte[] pullData(String code);
    
    public void clear(){
        
    }

    private static class DummyBlockCache extends BlockCache {

        @Override
        public String pushBlock(byte[] data) {
            return null;
        }

        @Override
        public @Nullable
        byte[] pullBlock(String code) {
            return null;
        }

        @Override
        public boolean pushData(String code, byte[] data) {
            return false;
        }

        @Override
        public byte[] pullData(String code) {
            return null;
        }

    }

}
