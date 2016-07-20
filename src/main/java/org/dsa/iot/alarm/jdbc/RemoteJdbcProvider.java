/* THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD
 * TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN
 * NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL
 * DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER
 * IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
 * CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package org.dsa.iot.alarm.jdbc;

import org.dsa.iot.alarm.*;
import org.slf4j.*;
import java.sql.*;

/**
 * Jdbc provider that uses the connection details on the RemoteJdbcAlarmService to
 * connect to the database.
 *
 * @author Aaron Hansen
 */
public class RemoteJdbcProvider extends JdbcProvider {

    ///////////////////////////////////////////////////////////////////////////
    // Constants
    ///////////////////////////////////////////////////////////////////////////

    private static final Logger LOGGER = LoggerFactory.getLogger(
            RemoteJdbcProvider.class);

    ///////////////////////////////////////////////////////////////////////////
    // Fields
    ///////////////////////////////////////////////////////////////////////////

    ///////////////////////////////////////////////////////////////////////////
    // Constructors
    ///////////////////////////////////////////////////////////////////////////

    public RemoteJdbcProvider() {
    }

    ///////////////////////////////////////////////////////////////////////////
    // Methods
    ///////////////////////////////////////////////////////////////////////////

    /**
     * {@inheritDoc} <p/>
     * Uses the connection information from the RemoteJdbcAlarmService to acquire
     * a connection.
     */
    @Override protected Connection getConnection() {
        try {
            RemoteJdbcAlarmService svc = (RemoteJdbcAlarmService) getService();
            String user = svc.getDatabaseUser();
            if ((user != null) && (user.length() > 0)) {
                return DriverManager.getConnection(svc.getDatabaseUrl(), user,
                                                   svc.getDatabasePass());
            }
            return DriverManager.getConnection(svc.getDatabaseUrl());
        } catch (Exception x) {
            AlarmUtil.throwRuntime(x);
        }
        return null; //this will never be reached.
    }

    /**
     * {@inheritDoc} <p/>
     *
     * @return Database name from the service.
     */
    @Override protected String getDatabaseName() {
        RemoteJdbcAlarmService svc = (RemoteJdbcAlarmService) getService();
        return svc.getDatabaseName();
    }

    /**
     * {@inheritDoc} <p/>
     *
     * @return RemoteJdbcAlarmService instance.
     */
    @Override public AlarmService newAlarmService() {
        return new RemoteJdbcAlarmService();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Inner Classes
    ///////////////////////////////////////////////////////////////////////////

} //class
