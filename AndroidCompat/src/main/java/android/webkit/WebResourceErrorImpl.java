package android.webkit;

public final class WebResourceErrorImpl extends WebResourceError {
    private final int code;
    private final CharSequence description;

    public WebResourceErrorImpl(int code, CharSequence description) {
        this.code = code;
        this.description = description;
    }

    @Override
    public int getErrorCode() {
        return code;
    }

    @Override
    public CharSequence getDescription() {
        return description;
    }
}
