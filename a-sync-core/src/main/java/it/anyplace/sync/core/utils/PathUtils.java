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

import static com.google.common.base.Objects.equal;
import com.google.common.base.Strings;
import org.apache.commons.io.FilenameUtils;
import static com.google.common.base.Preconditions.checkArgument;

/**
 *
 * @author aleph
 */
public class PathUtils {

    public static final String ROOT_PATH = "", PATH_SEPARATOR = "/", PARENT_PATH = "..";

    public static String normalizePath(String path) {
        return FilenameUtils.normalizeNoEndSeparator(path, true).replaceFirst("^" + PATH_SEPARATOR, "");
    }

    public static boolean isRoot(String path) {
        return Strings.isNullOrEmpty(path);
    }

    public static boolean isParent(String path) {
        return equal(path, PARENT_PATH);
    }

    public static String getParentPath(String path) {
        checkArgument(!isRoot(path), "cannot get parent of root path");
        return normalizePath(path + PATH_SEPARATOR + PARENT_PATH);
    }

    public static String getFileName(String path) {
        return FilenameUtils.getName(path);
    }

    public static String buildPath(String dir, String file) {
        return normalizePath(dir + PATH_SEPARATOR + file);
    }
}
