/* THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD
 * TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN
 * NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL
 * DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER
 * IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
 * CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package org.dsa.iot.alarm;

import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.dsa.iot.dslink.DSLink;
import org.dsa.iot.dslink.link.Requester;
import org.dsa.iot.dslink.link.SubscriptionHelper;
import org.dsa.iot.dslink.methods.StreamState;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.actions.*;
import org.dsa.iot.dslink.node.actions.table.Row;
import org.dsa.iot.dslink.node.actions.table.Table;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.dsa.iot.dslink.util.TimeUtils;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.log.LogManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Represents the visible root node of the link.  Its purpose is to create alarm classes and manage
 * alarm records independent of alarm class.
 *
 * @author Aaron Hansen
 */
public class AlarmService extends AbstractAlarmObject {

    ///////////////////////////////////////////////////////////////////////////
    // Constants
    ///////////////////////////////////////////////////////////////////////////

    static final String LOG_LEVEL = "Log Level";
    static final String NEXT_HANDLE = "nextHandle";

    ///////////////////////////////////////////////////////////////////////////
    // Fields
    ///////////////////////////////////////////////////////////////////////////

    private AlarmLinkHandler alarmLinkHandler;
    private ScheduledFuture executeFuture;
    private boolean executing = false;
    private HashMap<Number, AlarmObject> handles = new HashMap<>();
    private HashSet<AlarmStreamer> openAlarmStreamListeners = new HashSet<>();
    private ArrayList<AlarmStreamer> openAlarmStreamListenerCache = new ArrayList<>();
    private boolean updating = false;
    private boolean updateCounts = true;

    ///////////////////////////////////////////////////////////////////////////
    // Constructors
    ///////////////////////////////////////////////////////////////////////////

    ///////////////////////////////////////////////////////////////////////////
    // Methods
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Action handler for acknowledging alarms with comma-separated UUIDs.
     */
    private void acknowledge(ActionResult event) {
        try {
            Value uuid = event.getParameter(UUID_STR);
            if (uuid == null) {
                throw new NullPointerException("Missing UUID");
            }
            Value user = event.getParameter(USER);
            if (user == null) {
                throw new NullPointerException("Missing User");
            }
            String items[] = uuid.getString()
                                 .replaceAll(" ", "")
                                 .split(",");
            for (String item : items) {
                if (item.isEmpty()) {
                    continue;
                }
                UUID uuidObj = UUID.fromString(item);
                Alarming.getProvider().acknowledge(uuidObj, user.getString());
                AlarmRecord rec = Alarming.getProvider().getAlarm(uuidObj);
                rec.getAlarmClass().notifyAllUpdates(rec);
            }
            updateCounts();
        } catch (Exception x) {
            AlarmUtil.logError(getNode().getPath(), x);
            AlarmUtil.throwRuntime(x);
        }
    }

    /**
     * Action handler for Acknowledging all open alarms of all alarm classes.
     */
    private void acknowledgeAllOpen(ActionResult event) {
        try {
            Value user = event.getParameter(USER);
            if (user == null) {
                throw new NullPointerException("Missing " + USER);
            }
            Alarming.Provider provider = Alarming.getProvider();
            AlarmCursor cur = Alarming.getProvider().queryOpenAlarms(null);
            while (cur.next()) {
                if (!cur.isAcknowledged()) {
                    provider.acknowledge(cur.getUuid(), user.getString());
                    AlarmRecord rec = Alarming.getProvider().getAlarm(cur.getUuid());
                    rec.getAlarmClass().notifyAllUpdates(rec);
                }
            }
            updateCounts();
        } catch (Exception x) {
            AlarmUtil.logError(getNode().getPath(), x);
            AlarmUtil.throwRuntime(x);
        }
    }

    /**
     * Action handler for adding child nodes representing alarm classes.
     */
    private void addAlarmClass(final ActionResult event) {
        try {
            Value name = event.getParameter(NAME);
            if (name == null) {
                throw new NullPointerException("Missing name");
            }
            String nameString = name.getString();
            Node parent = getNode();
            Node alarmClassNode = parent.getChild(nameString, true);
            if (alarmClassNode != null) {
                throw new IllegalArgumentException(
                        "Name already in use: " + name.getString());
            }
            //Create the child node representing the alarm class.
            alarmClassNode = parent.createChild(nameString, true)
                                   .setSerializable(true).build();
            AlarmClass alarmClass = Alarming.getProvider().newAlarmClass(nameString);
            alarmClass.init(alarmClassNode);
            addChild(alarmClass);
        } catch (Exception x) {
            AlarmUtil.logError(getNode().getPath(), x);
            AlarmUtil.throwRuntime(x);
        }
    }

    /**
     * Action handler for adding a note to a specific alarm record.
     */
    private void addNote(ActionResult event) {
        try {
            Value uuid = event.getParameter(UUID_STR);
            if (uuid == null) {
                throw new NullPointerException("Missing UUID");
            }
            Value user = event.getParameter(USER);
            if (user == null) {
                throw new NullPointerException("Missing User");
            }
            Value note = event.getParameter(NOTE);
            if (note == null) {
                throw new NullPointerException("Missing User");
            }
            UUID uuidObj = UUID.fromString(uuid.getString());
            AlarmUtil.logInfo(user.getString() +
                                      " adding note to " +
                                      uuid.getString() +
                                      ": " +
                                      note.getString());
            Alarming.getProvider().addNote(uuidObj, user.getString(), note.getString());
            AlarmRecord rec = Alarming.getProvider().getAlarm(uuidObj);
            rec.getAlarmClass().notifyAllUpdates(rec);
        } catch (Exception x) {
            AlarmUtil.logError(getNode().getPath(), x);
            AlarmUtil.throwRuntime(x);
        }
    }

    /**
     * Creates a new alarm record, adds it to the provider, and returns it.
     */
    protected AlarmRecord createAlarm(AlarmClass alarmClass,
                                      AlarmWatch watch,
                                      String sourcePath,
                                      AlarmState createState,
                                      String message) {
        checkSteady();
        long now = System.currentTimeMillis();
        UUID uuid = null;
        synchronized (this) {
            uuid = java.util.UUID.randomUUID();
        }
        AlarmRecord alarmRecord = Alarming.getProvider().newAlarmRecord()
                                          .setUuid(uuid)
                                          .setAlarmClass(alarmClass)
                                          .setAlarmWatch(watch)
                                          .setAlarmType(createState)
                                          .setCreatedTime(now)
                                          .setMessage(message)
                                          .setSourcePath(sourcePath);
        AlarmUtil.logInfo("New alarm: " +
                                  alarmRecord.getOwner().getNode().getPath()
                                  + "= "
                                  + uuid);
        Alarming.getProvider().addAlarm(alarmRecord);
        updateCounts();
        return alarmRecord;
    }

    /**
     * Remove all traces of the alarm record for the given UUID_STR.
     */
    protected void deleteRecord(ActionResult event) {
        try {
            Value uuid = event.getParameter(UUID_STR);
            if (uuid == null) {
                throw new NullPointerException("Missing " + UUID_STR);
            }
            AlarmUtil.logTrace(getNode().getPath() + " deleting alarm " + uuid);
            Alarming.getProvider().deleteRecord(UUID.fromString(uuid.getString()));
            updateCounts();
        } catch (Exception x) {
            AlarmUtil.logError(getNode().getPath(), x);
            AlarmUtil.throwRuntime(x);
        }
    }

    /**
     * Schedules execute in the daemon thread pool.
     */
    @Override
    protected void doSteady() {
        try {
            Alarming.getProvider().start(this);
            syncWatchesToDatabase();
            executeFuture = Objects.getDaemonThreadPool().scheduleAtFixedRate(
                    new Runnable() {
                        @Override
                        public void run() {
                            execute();
                        }
                    }, 10, 10, TimeUnit.SECONDS);
        } catch (Exception x) {
            AlarmUtil.logError("Starting provider", x);
        }
    }

    /**
     * Cancels the execute callback.
     */
    @Override
    protected void doStop() {
        if (executeFuture != null) {
            executeFuture.cancel(false);
        }
        Alarming.getProvider().stop();
    }

    /**
     * Housekeeping, called by an executor.  Enforces max records and max record age.
     */
    protected void execute() {
        if (!isSteady()) {
            return;
        }
        synchronized (this) {
            if (executing) {
                return;
            }
            executing = true;
        }
        try {
            AlarmObject child;
            for (int i = 0, len = childCount(); i < len; i++) {
                child = getChild(i);
                if (child instanceof AlarmClass) {
                    ((AlarmClass) child).execute();
                }
            }
            updateCounts(false);
        } catch (Exception x) {
            AlarmUtil.logError(getNode().getPath(), x);
        } finally {
            executing = false;
        }
    }

    /**
     * Action handler for getting a single alarm by UUID.
     */
    private void getAlarm(final ActionResult event) {
        try {
            Value uuid = event.getParameter(UUID_STR);
            if (uuid == null) {
                throw new NullPointerException("Missing UUID_STR");
            }
            AlarmRecord record = Alarming.getProvider().getAlarm(
                    UUID.fromString(uuid.getString()));
            event.setStreamState(StreamState.INITIALIZED);
            Table table = event.getTable();
            table.setMode(Table.Mode.APPEND);
            AlarmUtil.encodeAlarm(record, table, null, null);
            event.setStreamState(StreamState.CLOSED);
        } catch (Exception x) {
            AlarmUtil.logError(getNode().getPath(), x);
            AlarmUtil.throwRuntime(x);
        }
    }

    /**
     * Searches for the child object with the given name.
     */
    public AlarmClass getAlarmClass(String name) {
        AlarmObject obj = null;
        for (int i = 0, len = childCount(); i < len; i++) {
            obj = getChild(i);
            if (obj.getNode().getName().equals(name)) {
                return (AlarmClass) obj;
            }
        }
        return null;
    }

    /**
     * Action handler for getting alarms for a time range.
     */
    private void getAlarms(final ActionResult event) {
        Calendar from = Calendar.getInstance();
        Calendar to = Calendar.getInstance();
        Value timeRange = event.getParameter(TIME_RANGE);
        if (timeRange != null) {
            //just fail fast if invalid time range
            String[] parts = timeRange.getString().split("/");
            TimeUtils.decode(parts[0], from);
            TimeUtils.decode(parts[1], to);
            to.setTimeInMillis(to.getTimeInMillis() + 1); //dglux uses inclusive end
        } else {
            //Default to today.
            TimeUtils.alignDay(from);
            TimeUtils.addDays(1, to);
            TimeUtils.alignDay(to);
        }
        final AlarmCursor cursor = Alarming.getProvider().queryAlarms(null, from, to);
        AlarmStreamer streamer = new AlarmStreamer(null, event, cursor);
        AlarmUtil.run(streamer, "Get Alarms");
    }

    /**
     * Action handler for getting a 'page' of alarms.
     */
    private void getAlarmPage(final ActionResult event) {
        AlarmCursor cursor = getAlarmPageCursor(event);
        int pageSize = 500;
        Value value = event.getParameter(PAGE_SIZE);
        if ((value != null) && (value.getNumber() != null)) {
            pageSize = value.getNumber().intValue();
        }
        if (pageSize > 0) {
            int page = 0;
            value = event.getParameter(PAGE);
            if ((value != null) && (value.getNumber() != null)) {
                page = value.getNumber().intValue();
            }
            cursor.setPaging(page, pageSize);
        }
        AlarmStreamer streamer = new AlarmStreamer(null, event, cursor);
        AlarmUtil.run(streamer, "Get Alarm Page");
    }

    /**
     * Action handler for getting a 'page' of alarms.
     */
    private void getAlarmPageCount(final ActionResult event) {
        int pageSize = 500;
        Value value = event.getParameter(PAGE_SIZE);
        if ((value != null) && (value.getNumber() != null)) {
            pageSize = value.getNumber().intValue();
        }
        int pages = 0;
        if (pageSize > 0) {
            AlarmCursor cursor = getAlarmPageCursor(event);
            int count = 0;
            while (cursor.next()) {
                count++;
            }
            pages = count / pageSize;
            if (count % pageSize > 0) {
                pages++;
            }
        }
        Table table = event.getTable();
        table.addRow(Row.make(new Value(pages)));
    }

    /**
     * Executes the alarm page query.
     */
    private AlarmCursor getAlarmPageCursor(final ActionResult event) {
        Calendar from = Calendar.getInstance();
        Calendar to = Calendar.getInstance();
        String sortBy = null;
        boolean ascending = true;
        AckFilter ackFilter = AckFilter.ANY;
        AlarmFilter alarmFilter = AlarmFilter.ANY;
        OpenFilter openFilter = OpenFilter.ANY;
        Value value = event.getParameter(TIME_RANGE);
        if (value != null) {
            //just fail fast if invalid time range
            String[] parts = value.getString().split("/");
            TimeUtils.decode(parts[0], from);
            TimeUtils.decode(parts[1], to);
            to.setTimeInMillis(to.getTimeInMillis() + 1); //dglux uses inclusive end
        } else {
            //Default to today.
            TimeUtils.alignDay(from);
            TimeUtils.addDays(1, to);
            TimeUtils.alignDay(to);
        }
        value = event.getParameter(ACK_STATE);
        if ((value != null) && (value.getString() != null)) {
            ackFilter = AckFilter.getMode(value.getString());
        }
        value = event.getParameter(ALARM_STATE);
        if ((value != null) && (value.getString() != null)) {
            alarmFilter = AlarmFilter.getMode(value.getString());
        }
        value = event.getParameter(OPEN_STATE);
        if ((value != null) && (value.getString() != null)) {
            openFilter = OpenFilter.getMode(value.getString());
        }
        value = event.getParameter(SORT_BY);
        if ((value != null) && (value.getString() != null)) {
            sortBy = value.getString();
        }
        value = event.getParameter(SORT_ASCENDING);
        if ((value != null) && (value.getBool() != null)) {
            ascending = value.getBool();
        }
        return Alarming.getProvider().queryAlarms(
                null, from, to, ackFilter, alarmFilter, openFilter, sortBy, ascending);
    }

    /**
     * Returns the object mapped to the given handle.
     */
    public synchronized AlarmObject getByHandle(int handle) {
        return handles.get(handle);
    }

    /**
     * Returns the DSLinkHandler
     */
    public AlarmLinkHandler getLinkHandler() {
        return alarmLinkHandler;
    }

    /**
     * Action handler for getting the notes for a specific alarm record.
     */
    private void getNotes(final ActionResult event) {
        NoteCursor cursor = null;
        try {
            Value uuid = event.getParameter(UUID_STR);
            if (uuid == null) {
                throw new NullPointerException("Missing UUID_STR");
            }
            cursor = Alarming.getProvider().getNotes(UUID.fromString(uuid.getString()));
            event.setStreamState(StreamState.INITIALIZED);
            Table table = event.getTable();
            table.setMode(Table.Mode.APPEND);
            StringBuilder buf = new StringBuilder();
            Calendar calendar = Calendar.getInstance();
            while (cursor.next()) {
                calendar.setTimeInMillis(cursor.getTimestamp());
                buf.setLength(0);
                TimeUtils.encode(calendar, true, buf);
                table.addRow(
                        Row.make(new Value(buf.toString()), new Value(cursor.getUser()),
                                 new Value(cursor.getText())));
            }
            event.setStreamState(StreamState.CLOSED);
        } catch (Exception x) {
            AlarmUtil.logError(getNode().getPath(), x);
            AlarmUtil.throwRuntime(x);
        }
        cursor.close();
    }

    /**
     * Action handler for getting all open alarms followed by a stream of all upates.
     */
    private void getOpenAlarms(final ActionResult event) {
        boolean updates = true;
        Value stream = event.getParameter(STREAM_UPDATES);
        if ((stream != null) && (stream.getBool() != null)) {
            updates = stream.getBool();
        }
        final AlarmCursor cursor = Alarming.getProvider().queryOpenAlarms(null);
        AlarmStreamer streamer = null;
        if (updates) {
            streamer = new AlarmStreamer(openAlarmStreamListeners, event, cursor);
        } else {
            streamer = new AlarmStreamer(null, event, cursor);
        }
        AlarmUtil.run(streamer, "Open Alarms");
    }

    private Requester getRequester() {
        DSLink link = getLinkHandler().getRequesterLink();
        if (link != null) {
            return link.getRequester();
        }
        return null;
    }

    /**
     * Use this for subscribing to alarm sources, the SDK cannot handle multiple subscribers to the
     * same path.
     */
    SubscriptionHelper getSubscriptions() {
        Requester requester = getRequester();
        if (requester != null) {
            return requester.getSubscriptionHelper();
        }
        return null;
    }

    /**
     * Adds all child watch objects to the given bucket.
     */
    Collection<AlarmWatch> getWatches() {
        HashSet<AlarmWatch> set = new HashSet<AlarmWatch>();
        AlarmObject child;
        for (int i = 0, len = childCount(); i < len; i++) {
            child = getChild(i);
            if (child instanceof AlarmClass) {
                ((AlarmClass) child).getWatches(set);
            }
        }
        return set;
    }

    /**
     * Adds the necessary data to the alarm service node.
     */
    @Override
    protected void initActions() {
        //Acknowledge
        Action action = new Action(Permission.WRITE, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                acknowledge(event);
            }
        });
        action.addParameter(new Parameter(UUID_STR, ValueType.STRING));
        action.addParameter(new Parameter(USER, ValueType.STRING));
        getNode().createChild("Acknowledge", false)
                 .setSerializable(false)
                 .setAction(action)
                 .build();
        //Acknowledge All
        action = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                acknowledgeAllOpen(event);
            }
        });
        action.addParameter(new Parameter(USER, ValueType.STRING));
        getNode().createChild(ACKNOWLEDGE_ALL, false)
                 .setSerializable(false)
                 .setAction(action).build();
        //Add Alarm Class action
        action = new Action(Permission.WRITE, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                addAlarmClass(event);
            }
        });
        action.addParameter(new Parameter(NAME, ValueType.STRING));
        getNode().createChild("Add Alarm Class", false)
                 .setSerializable(false)
                 .setAction(action)
                 .build();
        //Add Note
        action = new Action(Permission.WRITE, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                addNote(event);
            }
        });
        action.addParameter(new Parameter(UUID_STR, ValueType.STRING));
        action.addParameter(new Parameter(USER, ValueType.STRING));
        action.addParameter(new Parameter(NOTE, ValueType.STRING));
        getNode().createChild("Add Note", false)
                 .setSerializable(false)
                 .setAction(action)
                 .build();
        //Delete All Records action
        action = new Action(Permission.WRITE, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                Alarming.getProvider().deleteAllRecords();
                updateCounts();
            }
        });
        getNode().createChild("Delete All Records", false)
                 .setSerializable(false)
                 .setAction(action)
                 .build();
        //Delete Record
        action = new Action(Permission.WRITE, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                deleteRecord(event);
            }
        });
        action.addParameter(new Parameter(UUID_STR, ValueType.STRING));
        getNode().createChild("Delete Record", false)
                 .setSerializable(false)
                 .setAction(action)
                 .build();
        //Get Alarm
        action = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                getAlarm(event);
            }
        });
        action.addParameter(new Parameter(UUID_STR, ValueType.STRING));
        action.setResultType(ResultType.TABLE);
        AlarmUtil.encodeAlarmColumns(action);
        getNode().createChild("Get Alarm", false)
                 .setSerializable(false)
                 .setAction(action)
                 .build();
        //Get Alarms
        action = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                getAlarms(event);
            }
        });
        action.addParameter(
                new Parameter(TIME_RANGE, ValueType.STRING, new Value("today"))
                        .setEditorType(EditorType.DATE_RANGE));
        action.setResultType(ResultType.STREAM);
        AlarmUtil.encodeAlarmColumns(action);
        getNode().createChild("Get Alarms", false)
                 .setSerializable(false)
                 .setAction(action)
                 .build();
        //Get Open Alarms
        action = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                getOpenAlarms(event);
            }
        });
        action.setResultType(ResultType.STREAM);
        action.addParameter(
                new Parameter(STREAM_UPDATES, ValueType.BOOL, new Value(true)));
        AlarmUtil.encodeAlarmColumns(action);
        getNode().createChild("Get Open Alarms", false)
                 .setSerializable(false)
                 .setAction(action)
                 .build();
        //Get Alarm Page
        action = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                getAlarmPage(event);
            }
        });
        action.addParameter(
                new Parameter(TIME_RANGE, ValueType.STRING, new Value("today"))
                        .setEditorType(EditorType.DATE_RANGE));
        action.addParameter(
                new Parameter(PAGE, ValueType.NUMBER, new Value(0)));
        action.addParameter(
                new Parameter(PAGE_SIZE, ValueType.NUMBER, new Value(500)));
        action.addParameter(
                new Parameter(ACK_STATE, ACK_STATE_ENUM, new Value(ANY)));
        action.addParameter(
                new Parameter(ALARM_STATE, ALARM_STATE_ENUM, new Value(ANY)));
        action.addParameter(
                new Parameter(OPEN_STATE, OPEN_STATE_ENUM, new Value(ANY)));
        action.addParameter(
                new Parameter(SORT_BY, SORT_TYPE, new Value(CREATED_TIME)));
        action.addParameter(
                new Parameter(SORT_ASCENDING, ValueType.BOOL, new Value(true)));
        action.setResultType(ResultType.TABLE);
        AlarmUtil.encodeAlarmColumns(action);
        getNode().createChild("Get Alarm Page", false)
                 .setSerializable(false)
                 .setAction(action)
                 .build();
        //Get Alarm Page Count
        action = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                getAlarmPageCount(event);
            }
        });
        action.addParameter(
                new Parameter(TIME_RANGE, ValueType.STRING, new Value("today"))
                        .setEditorType(EditorType.DATE_RANGE));
        action.addParameter(
                new Parameter(PAGE_SIZE, ValueType.NUMBER, new Value(500)));
        action.addParameter(
                new Parameter(ACK_STATE, ACK_STATE_ENUM, new Value(ANY)));
        action.addParameter(
                new Parameter(ALARM_STATE, ALARM_STATE_ENUM, new Value(ANY)));
        action.addParameter(
                new Parameter(OPEN_STATE, OPEN_STATE_ENUM, new Value(ANY)));
        action.setResultType(ResultType.VALUES);
        action.addResult(new Parameter(RESULT, ValueType.NUMBER));
        getNode().createChild("Get Alarm Page Count", false)
                 .setSerializable(false)
                 .setAction(action)
                 .build();
        //Get Notes
        action = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                getNotes(event);
            }
        });
        action.addParameter(new Parameter(UUID_STR, ValueType.STRING));
        action.setResultType(ResultType.TABLE);
        action.addResult(new Parameter(TIMESTAMP, ValueType.STRING));
        action.addResult(new Parameter(USER, ValueType.STRING));
        action.addResult(new Parameter(NOTE, ValueType.STRING));
        getNode().createChild("Get Notes", false)
                 .setSerializable(false)
                 .setAction(action)
                 .build();
        //Return To Normal
        action = new Action(Permission.WRITE, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                returnToNormal(event);
            }
        });
        action.addParameter(new Parameter(UUID_STR, ValueType.STRING));
        getNode().createChild("Return To Normal", false)
                 .setSerializable(false)
                 .setAction(action)
                 .build();
        //Update Counts
        action = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                updateCounts(true);
            }
        });
        getNode().createChild("Update Counts", false)
                 .setSerializable(false)
                 .setAction(action)
                 .build();
    }

    private Action createAction(Node node, String name) {
        Action action = new Action(Permission.WRITE,
                                   new org.dsa.iot.dslink.util.handler.Handler<ActionResult>() {
                                       @Override
                                       public void handle(ActionResult event) {
                                       }
                                   });
        node.createChild(name, false)
            .setAction(action)
            .setSerializable(true)
            .build();
        return action;
    }

    @Override
    protected void initData() {
        initAttribute("icon", new Value("service.png"));
        initConfig(NEXT_HANDLE, new Value(1), true);
        initProperty(ENABLED, new Value(true)).setWritable(Writable.CONFIG);
        initProperty("Documentation", new Value(
                "https://github.com/IOT-DSA/dslink-java-alarm/blob/master/Alarm-Link-User-Guide.pdf"))
                .createFakeBuilder()
                .setSerializable(false)
                .setWritable(Writable.NEVER);
        initProperty(IN_ALARM_COUNT, new Value(0)).createFakeBuilder()
                                                  .setSerializable(false)
                                                  .setWritable(Writable.NEVER);
        initProperty(OPEN_ALARM_COUNT, new Value(0)).createFakeBuilder()
                                                    .setSerializable(false)
                                                    .setWritable(Writable.NEVER);
        initProperty(TTL_ALARM_COUNT, new Value(0)).createFakeBuilder()
                                                   .setSerializable(false)
                                                   .setWritable(Writable.NEVER);
        initProperty(UNACKED_ALARM_COUNT, new Value(0)).createFakeBuilder()
                                                       .setSerializable(false)
                                                       .setWritable(Writable.NEVER);
    }

    private int nextHandle() {
        Value value = getConfig(NEXT_HANDLE);
        int handle = value.getNumber().intValue();
        value.set(handle + 1);
        return handle;
    }

    /**
     * Notify all getOpenAlarms streams of the given record.
     */
    void notifyOpenAlarmStreams(AlarmRecord record) {
        ArrayList<AlarmStreamer> list = openAlarmStreamListenerCache;
        synchronized (openAlarmStreamListeners) {
            if (openAlarmStreamListeners.size() != list.size()) {
                openAlarmStreamListenerCache = new ArrayList<>();
                list = openAlarmStreamListenerCache;
                list.addAll(openAlarmStreamListeners);
            }
        }
        for (int i = list.size(); --i >= 0; ) {
            list.get(i).update(record);
        }
    }

    /**
     * This will assign a persistent read-only int config to the inner node.  AlarmObjects can then
     * be quickly retrieved by calling getByHandle(int).  AbstractAlarmObject calls this in the
     * start method; custom implementations that do not subclass it would need to call in their
     * start implementation, even if they already have a handle.
     */
    public synchronized void register(AlarmObject obj) {
        Value handle = obj.getNode().getRoConfig(HANDLE);
        if (handle == null) {
            int val = nextHandle();
            handle = new Value(val);
            obj.getNode().setRoConfig(HANDLE, handle);
        }
        handles.put(handle.getNumber(), obj);
    }

    /**
     * Calls  Alarming.getProvider().returnToNormal() and notifies all update streams.
     */
    protected void returnToNormal(ActionResult event) {
        try {
            Value uuid = event.getParameter(UUID_STR);
            if (uuid == null) {
                throw new NullPointerException("Missing UUID");
            }
            returnToNormal(UUID.fromString(uuid.getString()));
        } catch (Exception x) {
            AlarmUtil.logError(getNode().getPath(), x);
            AlarmUtil.throwRuntime(x);
        }
    }

    /**
     * Calls  Alarming.getProvider().returnToNormal() and notifies all update streams.
     */
    void returnToNormal(UUID uuidObj) {
        try {
            Alarming.getProvider().returnToNormal(uuidObj);
            AlarmRecord rec = Alarming.getProvider().getAlarm(uuidObj);
            rec.getAlarmClass().notifyAllUpdates(rec);
            updateCounts();
        } catch (Exception x) {
            AlarmUtil.logError(getNode().getPath(), x);
            AlarmUtil.throwRuntime(x);
        }
    }

    void setLinkHandler(AlarmLinkHandler arg) {
        alarmLinkHandler = arg;
    }

    /**
     * Action handler for dynamically (and transiently) changing the log level.
     */
    private void setLogLevel(ActionResult event) {
        try {
            Value level = event.getParameter(LOG_LEVEL);
            if (level == null) {
                throw new NullPointerException("Missing log level");
            }
            LogManager.setLevel(level.getString());
        } catch (Exception x) {
            AlarmUtil.logError(getNode().getPath(), x);
            AlarmUtil.throwRuntime(x);
        }
    }

    /**
     * Sometimes the node database isn't fully saved at shutdown.  That can lead to inconsistent
     * state.  This method scans all open, non-normal alarms and syncs the watches.  Any remaining
     * watch that are not normal are then reset to normal. <p> Called when transitioning into the
     * steady state.
     */
    private void syncWatchesToDatabase() {
        try {
            Collection<AlarmWatch> watches = getWatches();
            ArrayList<UUID> toDelete = new ArrayList<UUID>();
            AlarmWatch watch;
            AlarmCursor cursor = Alarming.getProvider().queryOpenAlarms(null);
            while (cursor.next()) {
                watch = cursor.getAlarmWatch();
                if (watch == null) {
                    if (cursor.getAlarmClass() == null) {
                        toDelete.add(cursor.getUuid());
                    }
                    continue;
                } else if (cursor.isNormal()) {
                    continue;
                } else if (watch.getLastAlarmUuid() == null) {
                    watch.setLastAlarmUuid(cursor.getUuid());
                    //The most recent watch state wasn't persisted.
                    if (watch.getAlgorithm() != null) {
                        watch.setAlarmState(watch.getAlgorithm().getAlarmType());
                    }
                } else if (!watch.getLastAlarmUuid().equals(cursor.getUuid())) {
                    AlarmRecord last = watch.getLastAlarmRecord();
                    if (last.getCreatedTime() > cursor.getCreatedTime()) {
                        //The record in the database is wrong.  Doubt this will ever happen.
                        toDelete.add(cursor.getUuid());
                    } else {
                        if (!last.isNormal()) {
                            //The most recent watch state wasn't persisted.
                            toDelete.add(cursor.getUuid());
                        }
                        watch.setLastAlarmUuid(cursor.getUuid());
                        if (watch.getAlgorithm() != null) {
                            watch.setAlarmState(watch.getAlgorithm().getAlarmType());
                        }
                    }
                }
                watches.remove(watch);
            }
            boolean update = false;
            for (UUID uuid : toDelete) {
                AlarmUtil.logTrace("syncWatches delete: " + cursor.getUuid());
                Alarming.getProvider().deleteRecord(uuid);
                update = true;
            }
            //The following watches did not have an open alarm record
            for (AlarmWatch w : watches) {
                if (w.getAlarmState() != AlarmState.NORMAL) {
                    w.setAlarmState(AlarmState.NORMAL);
                    update = true;
                }
            }
            if (update) {
                updateCounts();
            }
        } catch (Exception x) {
            AlarmUtil.logError("syncWatchesToDatabase", x);
        }
    }

    /**
     * Clears the instance mapped to the handle.
     */
    public synchronized void unregister(AlarmObject obj) {
        Value handle = obj.getNode().getRoConfig(HANDLE);
        if (handle != null) {
            if (handles.get(handle.getNumber()) != obj) {
                throw new IllegalStateException("Invalid handle");
            }
            handles.remove(handle.getNumber());
        }
    }

    /**
     * Notify the service that counts need to be updated.
     */
    void updateCounts() {
        updateCounts = true;
    }

    /**
     * Iterates all alarms and updates various alarms counts for the service and all classes.
     */
    @SuppressFBWarnings("IS2_INCONSISTENT_SYNC")
    void updateCounts(boolean force) {
        if (!force) {
            if (!updateCounts) {
                return;
            }
        }
        synchronized (this) {
            if (updating) {
                return;
            }
            updating = true;
        }
        try {
            updateCounts = false;
            Counts svc = new Counts();
            AlarmCursor cursor = Alarming.getProvider()
                                         .queryAlarms(null, null, null);
            AlarmClass clazz = null;
            HashMap<AlarmClass, Counts> map = new HashMap<>();
            Counts counts = null;
            Counts noClazzCounts = new Counts();
            while (cursor.next()) {
                clazz = cursor.getAlarmClass();
                if (clazz != null) {
                    counts = map.get(clazz);
                } else {
                    counts = noClazzCounts;
                }
                if (counts == null) {
                    counts = new Counts();
                    map.put(clazz, counts);
                }
                svc.ttl++;
                counts.ttl++;
                if (cursor.isOpen()) {
                    svc.open++;
                    counts.open++;
                }
                if (!cursor.isNormal()) {
                    svc.alarms++;
                    counts.alarms++;
                }
                if (cursor.isAckRequired() && !cursor.isAcknowledged()) {
                    svc.unacked++;
                    counts.unacked++;
                }
                if (svc.ttl % 100 == 0) {
                    Thread.yield();
                }
            }
            setProperty(IN_ALARM_COUNT, new Value(svc.alarms));
            setProperty(OPEN_ALARM_COUNT, new Value(svc.open));
            setProperty(TTL_ALARM_COUNT, new Value(svc.ttl));
            setProperty(UNACKED_ALARM_COUNT, new Value(svc.unacked));
            AlarmObject obj;
            for (int i = childCount(); --i >= 0; ) {
                obj = getChild(i);
                if (obj instanceof AlarmClass) {
                    clazz = (AlarmClass) obj;
                    clazz.updateCounts(map.get(clazz));
                }
            }
        } finally {
            updating = false;
        }
    }

    /**
     * Used for updating various alarm counts in the service and child classes.
     */
    static class Counts {
        int alarms = 0; //in alarm
        int open = 0;
        int ttl = 0;
        int unacked = 0;

    }

    @Override
    protected void onPropertyChange(Node child, ValuePair valuePair) {
        if (!valuePair.isFromExternalSource()) {
            return;
        }
        if (!valuePair.getCurrent().equals(valuePair.getPrevious())) {
            if (EXTERNAL_DB_ACCESS_ENABLED.equals(child.getName())) {
                Alarming.getProvider().changeDatabaseAccessTo(valuePair.getCurrent().getBool());
            }
        }
        super.onPropertyChange(child, valuePair);
    }
}
