package android.webkit;

public abstract class WebResourceError {
    protected WebResourceError() { }

    public abstract int getErrorCode();

    public abstract CharSequence getDescription();
}
