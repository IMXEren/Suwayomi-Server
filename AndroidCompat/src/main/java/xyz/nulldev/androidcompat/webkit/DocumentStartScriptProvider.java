package xyz.nulldev.androidcompat.webkit;

import java.lang.reflect.InvocationHandler;

public interface DocumentStartScriptProvider {
    InvocationHandler addDocumentStartJavaScript(String script, String[] allowedOriginRules);
}
