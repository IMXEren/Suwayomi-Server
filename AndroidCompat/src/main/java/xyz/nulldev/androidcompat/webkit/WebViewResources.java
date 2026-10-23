package xyz.nulldev.androidcompat.webkit;

import android.content.res.Resources;
import android.util.DisplayMetrics;
import java.lang.reflect.Field;
import sun.misc.Unsafe;

/** A per-view Resources instance: the Android stub constructor and getDisplayMetrics both throw. */
public final class WebViewResources extends Resources {
    private DisplayMetrics metrics;

    private WebViewResources() {
        super(null, null, null);
    }

    public static WebViewResources create() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            WebViewResources resources = (WebViewResources) ((Unsafe) field.get(null)).allocateInstance(WebViewResources.class);
            resources.metrics = new DisplayMetrics();
            return resources;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot initialize JVM WebView resources", e);
        }
    }

    @Override
    public DisplayMetrics getDisplayMetrics() {
        return metrics;
    }

    public void setViewport(int width, int height) {
        metrics.widthPixels = width;
        metrics.heightPixels = height;
    }
}
