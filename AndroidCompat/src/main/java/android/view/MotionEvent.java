package android.view;

import android.graphics.Matrix;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.Arrays;
import java.util.Objects;

/** Touch event values passed by Android extensions into a JVM-backed WebView. */
public final class MotionEvent extends InputEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_OUTSIDE = 4;
    public static final int ACTION_POINTER_DOWN = 5;
    public static final int ACTION_POINTER_UP = 6;
    public static final int ACTION_POINTER_1_DOWN = ACTION_POINTER_DOWN;
    public static final int ACTION_POINTER_1_UP = ACTION_POINTER_UP;
    public static final int ACTION_POINTER_2_DOWN = 261;
    public static final int ACTION_POINTER_2_UP = 262;
    public static final int ACTION_POINTER_3_DOWN = 517;
    public static final int ACTION_POINTER_3_UP = 518;
    public static final int ACTION_HOVER_MOVE = 7;
    public static final int ACTION_SCROLL = 8;
    public static final int ACTION_HOVER_ENTER = 9;
    public static final int ACTION_HOVER_EXIT = 10;
    public static final int ACTION_BUTTON_PRESS = 11;
    public static final int ACTION_BUTTON_RELEASE = 12;
    public static final int ACTION_MASK = 255;
    public static final int ACTION_POINTER_INDEX_MASK = 0xff00;
    public static final int ACTION_POINTER_INDEX_SHIFT = 8;
    public static final int ACTION_POINTER_ID_MASK = ACTION_POINTER_INDEX_MASK;
    public static final int ACTION_POINTER_ID_SHIFT = ACTION_POINTER_INDEX_SHIFT;
    public static final int INVALID_POINTER_ID = -1;
    public static final int TOOL_TYPE_UNKNOWN = 0;
    public static final int TOOL_TYPE_FINGER = 1;
    public static final int TOOL_TYPE_STYLUS = 2;
    public static final int TOOL_TYPE_MOUSE = 3;
    public static final int TOOL_TYPE_ERASER = 4;
    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_PRESSURE = 2;
    public static final int AXIS_SIZE = 3;
    public static final int AXIS_TOUCH_MAJOR = 4;
    public static final int AXIS_TOUCH_MINOR = 5;
    public static final int AXIS_TOOL_MAJOR = 6;
    public static final int AXIS_TOOL_MINOR = 7;
    public static final int AXIS_ORIENTATION = 8;
    public static final int CLASSIFICATION_NONE = 0;
    public static final int CLASSIFICATION_AMBIGUOUS_GESTURE = 1;
    public static final int CLASSIFICATION_DEEP_PRESS = 2;
    public static final int FLAG_WINDOW_IS_OBSCURED = 1;
    public static final int FLAG_WINDOW_IS_PARTIALLY_OBSCURED = 2;
    public static final int EDGE_TOP = 1;
    public static final int EDGE_BOTTOM = 2;
    public static final int EDGE_LEFT = 4;
    public static final int EDGE_RIGHT = 8;
    public static final int BUTTON_PRIMARY = 1;
    public static final int BUTTON_SECONDARY = 2;
    public static final int BUTTON_TERTIARY = 4;
    public static final int BUTTON_BACK = 8;
    public static final int BUTTON_FORWARD = 16;
    public static final int BUTTON_STYLUS_PRIMARY = 32;
    public static final int BUTTON_STYLUS_SECONDARY = 64;
    public static final Parcelable.Creator<MotionEvent> CREATOR = null;

    private final long downTime;
    private long eventTime;
    private int action;
    private final PointerProperties[] properties;
    private final PointerCoords[] coords;
    private final int metaState;
    private final float xPrecision;
    private final float yPrecision;
    private final int deviceId;
    private int edgeFlags;
    private int source;
    private final int flags;

    private MotionEvent(long downTime, long eventTime, int action, PointerProperties[] properties,
                        PointerCoords[] coords, int metaState, float xPrecision, float yPrecision,
                        int deviceId, int edgeFlags, int source, int flags) {
        if (properties.length == 0 || properties.length != coords.length) {
            throw new IllegalArgumentException("Each touch pointer needs coordinates and properties");
        }
        this.downTime = downTime;
        this.eventTime = eventTime;
        this.action = action;
        this.properties = Arrays.stream(properties).map(PointerProperties::new).toArray(PointerProperties[]::new);
        this.coords = Arrays.stream(coords).map(PointerCoords::new).toArray(PointerCoords[]::new);
        this.metaState = metaState;
        this.xPrecision = xPrecision;
        this.yPrecision = yPrecision;
        this.deviceId = deviceId;
        this.edgeFlags = edgeFlags;
        this.source = source;
        this.flags = flags;
    }

    public static MotionEvent obtain(long downTime, long eventTime, int action, int pointerCount,
                                     PointerProperties[] properties, PointerCoords[] coords,
                                     int metaState, int buttonState, float xPrecision, float yPrecision,
                                     int deviceId, int edgeFlags, int source, int flags) {
        if (pointerCount < 1 || properties.length < pointerCount || coords.length < pointerCount) {
            throw new IllegalArgumentException("Invalid touch pointer count");
        }
        return new MotionEvent(downTime, eventTime, action, Arrays.copyOf(properties, pointerCount),
            Arrays.copyOf(coords, pointerCount), metaState, xPrecision, yPrecision, deviceId, edgeFlags, source, flags);
    }

    public static MotionEvent obtain(long downTime, long eventTime, int action, float x, float y, int metaState) {
        PointerProperties property = new PointerProperties();
        property.toolType = TOOL_TYPE_FINGER;
        PointerCoords point = new PointerCoords();
        point.x = x;
        point.y = y;
        point.pressure = 1;
        return obtain(downTime, eventTime, action, 1, new PointerProperties[]{property},
            new PointerCoords[]{point}, metaState, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    public static MotionEvent obtain(MotionEvent other) {
        return new MotionEvent(other.downTime, other.eventTime, other.action, other.properties, other.coords,
            other.metaState, other.xPrecision, other.yPrecision, other.deviceId, other.edgeFlags, other.source, other.flags);
    }

    public static MotionEvent obtainNoHistory(MotionEvent other) {
        return obtain(other);
    }

    public void recycle() { }
    @Override public int getDeviceId() { return deviceId; }
    @Override public int getSource() { return source; }
    public void setSource(int source) { this.source = source; }
    public int getAction() { return action; }
    public int getActionMasked() { return action & ACTION_MASK; }
    public int getActionIndex() { return (action & ACTION_POINTER_INDEX_MASK) >> ACTION_POINTER_INDEX_SHIFT; }
    public int getFlags() { return flags; }
    public long getDownTime() { return downTime; }
    @Override public long getEventTime() { return eventTime; }
    public float getX() { return getX(0); }
    public float getY() { return getY(0); }
    public float getPressure() { return getPressure(0); }
    public float getSize() { return coords[0].size; }
    public float getTouchMajor() { return coords[0].touchMajor; }
    public float getTouchMinor() { return coords[0].touchMinor; }
    public float getToolMajor() { return coords[0].toolMajor; }
    public float getToolMinor() { return coords[0].toolMinor; }
    public float getOrientation() { return coords[0].orientation; }
    public float getAxisValue(int axis) { return coords[0].getAxisValue(axis); }
    public int getPointerCount() { return coords.length; }
    public int getPointerId(int index) { return properties[index].id; }
    public int getToolType(int index) { return properties[index].toolType; }
    public int findPointerIndex(int id) {
        for (int i = 0; i < properties.length; i++) if (properties[i].id == id) return i;
        return -1;
    }
    public float getX(int index) { return coords[index].x; }
    public float getY(int index) { return coords[index].y; }
    public float getPressure(int index) { return coords[index].pressure; }
    public float getSize(int index) { return coords[index].size; }
    public float getTouchMajor(int index) { return coords[index].touchMajor; }
    public float getTouchMinor(int index) { return coords[index].touchMinor; }
    public float getToolMajor(int index) { return coords[index].toolMajor; }
    public float getToolMinor(int index) { return coords[index].toolMinor; }
    public float getOrientation(int index) { return coords[index].orientation; }
    public float getAxisValue(int axis, int index) { return coords[index].getAxisValue(axis); }
    public void getPointerCoords(int index, PointerCoords target) { target.copyFrom(coords[index]); }
    public void getPointerProperties(int index, PointerProperties target) { target.copyFrom(properties[index]); }
    public int getMetaState() { return metaState; }
    public int getButtonState() { return 0; }
    public int getClassification() { return CLASSIFICATION_NONE; }
    public int getActionButton() { return 0; }
    public float getRawX() { return getX(); }
    public float getRawY() { return getY(); }
    public float getRawX(int index) { return getX(index); }
    public float getRawY(int index) { return getY(index); }
    public float getXPrecision() { return xPrecision; }
    public float getYPrecision() { return yPrecision; }
    public int getHistorySize() { return 0; }
    public int getEdgeFlags() { return edgeFlags; }
    public void setEdgeFlags(int edgeFlags) { this.edgeFlags = edgeFlags; }
    public void setAction(int action) { this.action = action; }
    public void offsetLocation(float dx, float dy) {
        for (PointerCoords point : coords) { point.x += dx; point.y += dy; }
    }
    public void setLocation(float x, float y) { offsetLocation(x - getX(), y - getY()); }
    public static String actionToString(int action) {
        switch (action & ACTION_MASK) {
            case ACTION_DOWN: return "ACTION_DOWN";
            case ACTION_UP: return "ACTION_UP";
            case ACTION_MOVE: return "ACTION_MOVE";
            case ACTION_CANCEL: return "ACTION_CANCEL";
            default: return "ACTION_" + action;
        }
    }
    @Override public String toString() { return "MotionEvent{" + actionToString(action) + " x=" + getX() + " y=" + getY() + "}"; }
    @Override public void writeToParcel(Parcel dest, int flags) { throw new UnsupportedOperationException("MotionEvent parcels are not supported"); }

    public static final class PointerProperties {
        public int id;
        public int toolType;
        public PointerProperties() { }
        public PointerProperties(PointerProperties other) { copyFrom(other); }
        public void clear() { id = toolType = 0; }
        public void copyFrom(PointerProperties other) { id = other.id; toolType = other.toolType; }
        @Override public boolean equals(Object other) {
            return other instanceof PointerProperties && id == ((PointerProperties) other).id && toolType == ((PointerProperties) other).toolType;
        }
        @Override public int hashCode() { return Objects.hash(id, toolType); }
    }

    public static final class PointerCoords {
        public float x, y, pressure, size, touchMajor, touchMinor, toolMajor, toolMinor, orientation;
        public PointerCoords() { }
        public PointerCoords(PointerCoords other) { copyFrom(other); }
        public void clear() { x = y = pressure = size = touchMajor = touchMinor = toolMajor = toolMinor = orientation = 0; }
        public void copyFrom(PointerCoords other) {
            x = other.x;
            y = other.y;
            pressure = other.pressure;
            size = other.size;
            touchMajor = other.touchMajor;
            touchMinor = other.touchMinor;
            toolMajor = other.toolMajor;
            toolMinor = other.toolMinor;
            orientation = other.orientation;
        }
        public float getAxisValue(int axis) {
            switch (axis) {
                case AXIS_X: return x;
                case AXIS_Y: return y;
                case AXIS_PRESSURE: return pressure;
                case AXIS_SIZE: return size;
                case AXIS_TOUCH_MAJOR: return touchMajor;
                case AXIS_TOUCH_MINOR: return touchMinor;
                case AXIS_TOOL_MAJOR: return toolMajor;
                case AXIS_TOOL_MINOR: return toolMinor;
                case AXIS_ORIENTATION: return orientation;
                default: return 0;
            }
        }
        public void setAxisValue(int axis, float value) {
            switch (axis) {
                case AXIS_X: x = value; break;
                case AXIS_Y: y = value; break;
                case AXIS_PRESSURE: pressure = value; break;
                case AXIS_SIZE: size = value; break;
                case AXIS_TOUCH_MAJOR: touchMajor = value; break;
                case AXIS_TOUCH_MINOR: touchMinor = value; break;
                case AXIS_TOOL_MAJOR: toolMajor = value; break;
                case AXIS_TOOL_MINOR: toolMinor = value; break;
                case AXIS_ORIENTATION: orientation = value; break;
                default: throw new IllegalArgumentException("Unsupported touch axis: " + axis);
            }
        }
    }
}
