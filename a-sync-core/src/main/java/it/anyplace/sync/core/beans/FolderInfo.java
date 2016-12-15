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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.emptyToNull;
import javax.annotation.Nullable;

/**
 *
 * @author aleph
 */
public class FolderInfo {

    private final String folder;
    private final String label;

    public FolderInfo(String folder) {
        this(folder, null);
    }

    public FolderInfo(String folder, @Nullable String label) {
        checkNotNull(emptyToNull(folder));
        this.folder = folder;
        this.label = firstNonNull(emptyToNull(label), folder);
    }

    public String getFolder() {
        return folder;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return "FolderInfo{" + "folder=" + folder + ", label=" + label + '}';
    }

}
