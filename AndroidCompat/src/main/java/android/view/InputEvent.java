package android.view;

import android.os.Parcelable;

/** Base event type for the JVM WebView input bridge. */
public abstract class InputEvent implements Parcelable {
    public static final Parcelable.Creator<InputEvent> CREATOR = null;

    InputEvent() { }

    public abstract int getDeviceId();

    public final InputDevice getDevice() {
        return null;
    }

    public abstract int getSource();

    public boolean isFromSource(int source) {
        return (getSource() & source) == source;
    }

    public abstract long getEventTime();

    @Override
    public int describeContents() {
        return 0;
    }
}
