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
package it.anyplace.sync.core.utils;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import it.anyplace.sync.core.beans.BlockInfo;
import java.util.List;

/**
 *
 * @author aleph
 */
public class BlockUtils {

    public static String hashBlocks(List<BlockInfo> blocks) {
        return BaseEncoding.base16().encode(Hashing.sha256().hashBytes(Joiner.on(",").join(Iterables.transform(blocks, new Function<BlockInfo, String>() {
            @Override
            public String apply(BlockInfo input) {
                return input.getHash();
            }
        })).getBytes()).asBytes());
    }
}
