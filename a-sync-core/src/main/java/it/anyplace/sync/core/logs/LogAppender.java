/*
 * Copyright 2016 Davide Imbriaco <davide.imbriaco@gmail.com>.
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
package it.anyplace.sync.core.logs;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.eventbus.EventBus;

public class LogAppender extends AppenderBase<ILoggingEvent> {

    private final static EventBus globalLogEventBus = new EventBus();

    @Override
    protected void append(final ILoggingEvent eventObject) {
        checkNotNull(eventObject);
        globalLogEventBus.post(new LogEventReceivedMessage() {
            @Override
            public LogAppender getAppender() {
                return LogAppender.this;
            }

            @Override
            public ILoggingEvent getLogEvent() {
                return eventObject;
            }
        });
    }

    protected static EventBus getLogEventBus() {
        return globalLogEventBus;
    }

    public interface LogEventReceivedMessage {

        public LogAppender getAppender();

        public ILoggingEvent getLogEvent();
    }
}
