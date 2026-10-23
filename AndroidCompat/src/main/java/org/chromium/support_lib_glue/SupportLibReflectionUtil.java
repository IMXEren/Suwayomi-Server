package org.chromium.support_lib_glue;

import android.webkit.WebView;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import xyz.nulldev.androidcompat.webkit.DocumentStartScriptProvider;

public final class SupportLibReflectionUtil {
    private SupportLibReflectionUtil() {}

    public static InvocationHandler createWebViewProviderFactory() {
        return (proxy, method, args) -> {
            switch (method.getName()) {
                case "getSupportedFeatures":
                    return WebView.isDocumentStartScriptSupported()
                        ? new String[] {"DOCUMENT_START_SCRIPT:1"}
                        : new String[0];
                case "createWebView":
                    WebView view = (WebView) args[0];
                    return (InvocationHandler) (providerProxy, providerMethod, providerArgs) -> {
                        if (providerMethod.getName().equals("addDocumentStartJavaScript")) {
                            return ((DocumentStartScriptProvider) view.getWebViewProvider())
                                .addDocumentStartJavaScript((String) providerArgs[0], (String[]) providerArgs[1]);
                        }
                        return objectMethod(providerProxy, providerMethod, providerArgs);
                    };
                default:
                    return objectMethod(proxy, method, args);
            }
        };
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals": return proxy == args[0];
            case "hashCode": return System.identityHashCode(proxy);
            case "toString": return "AndroidCompatWebViewBoundary";
            default: throw new UnsupportedOperationException(method.getName());
        }
    }
}
