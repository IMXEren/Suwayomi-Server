package android.util;

/** Screen metrics for JVM-backed WebViews. Chromium coordinates use CSS pixels (density 1). */
public class DisplayMetrics {
    public static final int DENSITY_LOW = 120;
    public static final int DENSITY_DEFAULT = 160;
    public static final int DENSITY_MEDIUM = 160;
    public static final int DENSITY_TV = 213;
    public static final int DENSITY_HIGH = 240;
    public static final int DENSITY_XHIGH = 320;
    public static final int DENSITY_XXHIGH = 480;
    public static final int DENSITY_XXXHIGH = 640;
    public static final int DENSITY_140 = 140;
    public static final int DENSITY_180 = 180;
    public static final int DENSITY_200 = 200;
    public static final int DENSITY_220 = 220;
    public static final int DENSITY_260 = 260;
    public static final int DENSITY_280 = 280;
    public static final int DENSITY_300 = 300;
    public static final int DENSITY_340 = 340;
    public static final int DENSITY_360 = 360;
    public static final int DENSITY_400 = 400;
    public static final int DENSITY_420 = 420;
    public static final int DENSITY_440 = 440;
    public static final int DENSITY_450 = 450;
    public static final int DENSITY_560 = 560;
    public static final int DENSITY_600 = 600;
    public static final int DENSITY_DEVICE_STABLE = DENSITY_DEFAULT;

    public int widthPixels;
    public int heightPixels;
    public float density;
    public int densityDpi;
    public float scaledDensity;
    public float xdpi;
    public float ydpi;

    public DisplayMetrics() {
        setToDefaults();
    }

    public void setTo(DisplayMetrics other) {
        widthPixels = other.widthPixels;
        heightPixels = other.heightPixels;
        density = other.density;
        densityDpi = other.densityDpi;
        scaledDensity = other.scaledDensity;
        xdpi = other.xdpi;
        ydpi = other.ydpi;
    }

    public void setToDefaults() {
        widthPixels = 1280;
        heightPixels = 720;
        density = scaledDensity = 1f;
        densityDpi = DENSITY_DEFAULT;
        xdpi = ydpi = DENSITY_DEFAULT;
    }

    public boolean equals(DisplayMetrics other) {
        return other != null && widthPixels == other.widthPixels && heightPixels == other.heightPixels
            && density == other.density && densityDpi == other.densityDpi
            && scaledDensity == other.scaledDensity && xdpi == other.xdpi && ydpi == other.ydpi;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DisplayMetrics && equals((DisplayMetrics) other);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(widthPixels, heightPixels, density, densityDpi, scaledDensity, xdpi, ydpi);
    }

    @Override
    public String toString() {
        return "DisplayMetrics{" + widthPixels + "x" + heightPixels + ", density=" + density + "}";
    }
}
