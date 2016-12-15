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

/**
 *
 * @author aleph
 */
public class BlockInfo {

    private final long offset;
    private final int size;
    private final String hash;

    public BlockInfo(long offset, int size, String hash) {
        checkNotNull(hash);
        this.offset = offset;
        this.size = size;
        this.hash = hash;
    }

    public long getOffset() {
        return offset;
    }

    public int getSize() {
        return size;
    }

    public String getHash() {
        return hash;
    }

}
