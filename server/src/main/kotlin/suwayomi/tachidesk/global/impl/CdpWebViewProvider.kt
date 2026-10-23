package suwayomi.tachidesk.global.impl

import android.net.Uri
import android.os.Handler
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceErrorImpl
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewProvider
import android.webkit.WebViewProvider.ViewDelegate
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.hildan.chrome.devtools.domains.emulation.SetTouchEmulationEnabledRequest
import org.hildan.chrome.devtools.domains.fetch.ContinueRequestRequest
import org.hildan.chrome.devtools.domains.fetch.EnableRequest
import org.hildan.chrome.devtools.domains.fetch.FailRequestRequest
import org.hildan.chrome.devtools.domains.fetch.FulfillRequestRequest
import org.hildan.chrome.devtools.domains.fetch.HeaderEntry
import org.hildan.chrome.devtools.domains.fetch.RequestPattern
import org.hildan.chrome.devtools.domains.fetch.events.FetchEvent
import org.hildan.chrome.devtools.domains.input.DispatchTouchEventRequest
import org.hildan.chrome.devtools.domains.input.TouchEventType
import org.hildan.chrome.devtools.domains.input.TouchPoint
import org.hildan.chrome.devtools.domains.network.CookieParam
import org.hildan.chrome.devtools.domains.network.ErrorReason
import org.hildan.chrome.devtools.domains.network.GetCookiesRequest
import org.hildan.chrome.devtools.domains.network.ResourceType
import org.hildan.chrome.devtools.domains.network.SetExtraHTTPHeadersRequest
import org.hildan.chrome.devtools.domains.page.AddScriptToEvaluateOnNewDocumentRequest
import org.hildan.chrome.devtools.domains.page.NavigateRequest
import org.hildan.chrome.devtools.domains.page.ReloadRequest
import org.hildan.chrome.devtools.domains.page.RemoveScriptToEvaluateOnNewDocumentRequest
import org.hildan.chrome.devtools.domains.runtime.EvaluateRequest
import org.hildan.chrome.devtools.protocol.ExperimentalChromeApi
import org.hildan.chrome.devtools.sessions.PageSession
import xyz.nulldev.androidcompat.webkit.DocumentStartScriptProvider
import xyz.nulldev.androidcompat.webkit.KcefWebSettings
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/** Android WebView methods backed by a reserved page in Prowl's existing browser. */
@OptIn(ExperimentalChromeApi::class)
internal class CdpWebViewProvider private constructor(
    private val view: WebView,
) : InvocationHandler {
    private val logger = KotlinLogging.logger {}
    private val interceptionDispatcher = Dispatchers.IO.limitedParallelism(8)
    private val handler = Handler(view.webViewLooper)
    private val settings = KcefWebSettings()

    @Volatile private var viewClient = WebViewClient()

    @Volatile private var chromeClient = WebChromeClient()

    @Volatile private var currentUrl: String? = null

    @Volatile private var currentTitle: String? = null
    private val interfaces = ConcurrentHashMap<String, Any>()
    private val pendingCallbacks = AtomicInteger()
    private val cookieStore by lazy { AppCookieStore() }
    private val browser = CdpBrowser(::ready, ::harvest, ::failed, ::tick)
    private val bindings = ConcurrentHashMap<String, String>()
    private val bridgeHandlers = ConcurrentHashMap<String, BridgeHandler>()
    private val pendingDocument = AtomicReference<DocumentOverride?>(null)
    private var routed = false
    private var lastTouchMoveNanos = 0L
    private var lastTouchMove: TouchPoint? = null
    private val viewDelegate =
        Proxy.newProxyInstance(ViewDelegate::class.java.classLoader, arrayOf(ViewDelegate::class.java)) { _, method, args ->
            when (method.name) {
                "onTouchEvent" -> dispatchTouch(args!![0] as MotionEvent)
                else -> throw UnsupportedOperationException("WebView view delegate: ${method.name}")
            }
        } as ViewDelegate

    override fun invoke(
        proxy: Any,
        method: Method,
        arguments: Array<out Any?>?,
    ): Any? {
        val args = arguments.orEmpty()
        return when (method.name) {
            "init" -> {
                Unit
            }

            "destroy" -> {
                interfaces.clear()
                browser.close()
            }

            "addDocumentStartJavaScript" -> {
                addDocumentStartJavaScript(args[0] as String, args[1] as Array<*>)
            }

            "setWebViewClient" -> {
                val client = args[0] as WebViewClient
                viewClient = client
                if (client.javaClass != WebViewClient::class.java) {
                    browser.submit { page -> routeIfNeeded(page) }
                }
                Unit
            }

            "getWebViewClient" -> {
                viewClient
            }

            "setWebChromeClient" -> {
                chromeClient = args[0] as WebChromeClient
            }

            "getWebChromeClient" -> {
                chromeClient
            }

            "getSettings" -> {
                settings
            }

            "addJavascriptInterface" -> {
                val name = args[1] as String
                val obj = args[0] ?: throw IllegalArgumentException("A JavaScript interface must have an object")
                interfaces[name] = obj
                browser.submit { installBridge(it, name, obj) }
            }

            "removeJavascriptInterface" -> {
                val name = args[0] as String
                interfaces.remove(name)
                bindings.remove(name)?.let(bridgeHandlers::remove)
                Unit
            }

            "loadUrl" -> {
                val url = args[0] as String

                @Suppress("UNCHECKED_CAST")
                val headers = args.getOrNull(1) as? Map<String, String> ?: emptyMap()
                navigate(url, headers)
            }

            "loadData" -> {
                loadData(null, args[0] as String, args[1] as? String)
            }

            "loadDataWithBaseURL" -> {
                loadData(args[0] as? String, args[1] as String, args[2] as? String)
            }

            "evaluateJavaScript" -> {
                val script = (args[0] as String).removePrefix("javascript:")

                @Suppress("UNCHECKED_CAST")
                val callback = args[1] as? ValueCallback<String>
                browser.submit { page ->
                    val result =
                        try {
                            evaluate(page, script)?.toString() ?: "null"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn { "WebView JavaScript evaluation failed (${e.javaClass.simpleName})" }
                            "null"
                        }
                    handler.post { callback?.onReceiveValue(result) }
                }
            }

            "stopLoading" -> {
                browser.submit { page -> evaluate(page, "window.stop()") }
            }

            "getViewDelegate" -> {
                viewDelegate
            }

            "insertVisualStateCallback" -> {
                val id = args[0] as Long
                val callback = args[1] as WebView.VisualStateCallback
                browser.submit { page ->
                    val deadline = System.currentTimeMillis() + 1_000
                    while (System.currentTimeMillis() < deadline) {
                        if (evaluate(page, "document.readyState")?.toString() == "\"complete\"") break
                        delay(50.milliseconds)
                    }
                    handler.post { callback.onComplete(id) }
                }
            }

            "reload" -> {
                browser.submit { page ->
                    flushBridge(page)
                    page.page.reload(ReloadRequest())
                }
            }

            "getUrl", "getOriginalUrl" -> {
                currentUrl
            }

            "getTitle" -> {
                currentTitle
            }

            "isPaused", "isPrivateBrowsingEnabled" -> {
                false
            }

            "onPause", "onResume", "pauseTimers", "resumeTimers" -> {
                Unit
            }

            "toString" -> {
                "CdpWebViewProvider($currentUrl)"
            }

            "hashCode" -> {
                System.identityHashCode(proxy)
            }

            "equals" -> {
                proxy === args[0]
            }

            else -> {
                logger.warn { "CDP WebView.${method.name} unsupported" }
                throw UnsupportedOperationException("WebView.${method.name} is not supported by the CDP provider")
            }
        }
    }

    private suspend fun ready(
        scope: CoroutineScope,
        page: PageSession,
    ) {
        page.page.enable(
            org.hildan.chrome.devtools.domains.page
                .EnableRequest(),
        )
        page.network.enable(
            org.hildan.chrome.devtools.domains.network
                .EnableRequest(),
        )
        page.runtime.enable()
        page.emulation.setTouchEmulationEnabled(SetTouchEmulationEnabledRequest(enabled = true))
        scope.launch {
            page.runtime.exceptionThrownEvents().collect { event ->
                logger.debug { "CDP page exception: ${event.exceptionDetails.text.take(140)}" }
            }
        }
        scope.launch {
            page.runtime.consoleAPICalledEvents().collect { event ->
                logger.debug { "CDP page console: ${event.type}" }
            }
        }
        scope.launch {
            page.page.frameNavigatedEvents().collect { event ->
                val frame = event.frame
                if (frame.parentId == null) {
                    currentUrl = frame.url
                    handler.post { viewClient.onPageStarted(view, frame.url, null) }
                }
            }
        }
        scope.launch {
            page.page.loadEventFiredEvents().collect {
                val url = currentUrl.orEmpty()
                handler.post {
                    chromeClient.onProgressChanged(view, 100)
                    viewClient.onPageFinished(view, url)
                }
                currentTitle = evaluate(page, "document.title")?.toString()?.trim('"')
            }
        }
        scope.launch {
            page.network.loadingFailedEvents().collect { event ->
                if (event.type == ResourceType.Document) receivedError(currentUrl.orEmpty(), "Navigation failed")
            }
        }
        scope.launch {
            page.fetch.requestPausedEvents().collect { event ->
                // CDP command replies use the same event stream; don't wait for them in its collector.
                launch(interceptionDispatcher) { intercept(page, event) }
            }
        }
    }

    private fun addDocumentStartJavaScript(
        script: String,
        allowedOriginRules: Array<*>,
    ): InvocationHandler {
        val origins = allowedOriginRules.map { it as String }
        require(
            origins.isNotEmpty() &&
                origins.all { origin ->
                    origin == "*" ||
                        origin.toHttpUrlOrNull()?.let { url ->
                            origin == url.toString().removeSuffix("/") && url.scheme in setOf("http", "https")
                        } == true
                },
        ) { "Document-start scripts require exact HTTP(S) origins or *" }
        val allowed = JsonArray(origins.map(::JsonPrimitive)).toString()
        val source = "if ($allowed.includes('*') || $allowed.includes(location.origin)) {\n$script\n}"
        val removed = AtomicBoolean(false)
        var identifier: String? = null
        browser.submit { page ->
            if (!removed.get()) {
                identifier = page.page.addScriptToEvaluateOnNewDocument(AddScriptToEvaluateOnNewDocumentRequest(source)).identifier
            }
        }
        return InvocationHandler { proxy, method, args ->
            when (method.name) {
                "remove" -> {
                    if (removed.compareAndSet(false, true)) {
                        browser.submit { page ->
                            identifier?.let {
                                page.page.removeScriptToEvaluateOnNewDocument(
                                    RemoveScriptToEvaluateOnNewDocumentRequest(it),
                                )
                            }
                        }
                    }
                    Unit
                }

                "equals" -> {
                    proxy === args?.getOrNull(0)
                }

                "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                "toString" -> {
                    "CdpDocumentStartScript"
                }

                else -> {
                    throw UnsupportedOperationException(method.name)
                }
            }
        }
    }

    private suspend fun routeIfNeeded(page: PageSession) {
        if (routed) return
        routed = true
        page.fetch.enable(EnableRequest(patterns = listOf(RequestPattern(urlPattern = "*"))))
    }

    private fun navigate(
        url: String,
        headers: Map<String, String>,
    ) {
        currentUrl = url
        browser.submit { page ->
            flushBridge(page)
            routeIfNeeded(page)
            seed(page, url)
            if (headers.isNotEmpty()) {
                page.network.setExtraHTTPHeaders(
                    SetExtraHTTPHeadersRequest(
                        headers = buildJsonObject { headers.forEach { (name, value) -> put(name, JsonPrimitive(value)) } },
                    ),
                )
            }
            handler.post { chromeClient.onProgressChanged(view, 0) }
            page.page.navigate(NavigateRequest(url = url))
        }
    }

    private fun loadData(
        baseUrl: String?,
        data: String,
        mimeType: String?,
    ) {
        browser.submit { page ->
            flushBridge(page)
            routeIfNeeded(page)
            if (baseUrl == null || !baseUrl.startsWith("http")) {
                evaluate(
                    page,
                    "(function(){document.open();document.write(${JsonPrimitive(data)});document.close();})()",
                )
            } else {
                seed(page, baseUrl)
                pendingDocument.set(DocumentOverride(baseUrl, data, mimeType ?: "text/html"))
                page.page.navigate(NavigateRequest(url = baseUrl))
            }
        }
    }

    private suspend fun intercept(
        page: PageSession,
        event: FetchEvent.RequestPaused,
    ) {
        val request = event.request
        val override = pendingDocument.get()
        if (override != null && request.url == override.url && event.resourceType == ResourceType.Document) {
            if (pendingDocument.compareAndSet(override, null)) {
                fulfill(page, event, override.html, override.mimeType, emptyMap())
                return
            }
        }
        val resource =
            object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse(request.url)

                override fun isForMainFrame(): Boolean = event.resourceType == ResourceType.Document

                override fun isRedirect(): Boolean = false

                override fun hasGesture(): Boolean = false

                override fun getMethod(): String = request.method

                override fun getRequestHeaders(): Map<String, String> = request.headers.mapValues { it.value.toString().trim('"') }
            }
        try {
            val response: WebResourceResponse? = viewClient.shouldInterceptRequest(view, resource)
            when {
                response != null -> {
                    val body = response.data?.use { it.readBytes() } ?: byteArrayOf()
                    fulfill(
                        page,
                        event,
                        body.toString(Charsets.ISO_8859_1),
                        response.mimeType ?: "application/octet-stream",
                        response.responseHeaders.orEmpty(),
                        response.statusCode,
                    )
                }

                settings.blockNetworkImage && event.resourceType == ResourceType.Image -> {
                    page.fetch.failRequest(FailRequestRequest(event.requestId, ErrorReason.BlockedByClient))
                }

                else -> {
                    page.fetch.continueRequest(ContinueRequestRequest(event.requestId))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "WebView request interception failed (${e.javaClass.simpleName})" }
            page.fetch.failRequest(FailRequestRequest(event.requestId, ErrorReason.Failed))
        }
    }

    private suspend fun fulfill(
        page: PageSession,
        event: FetchEvent.RequestPaused,
        body: String,
        mimeType: String,
        headers: Map<String, String>,
        status: Int = 200,
    ) {
        page.fetch.fulfillRequest(
            FulfillRequestRequest(
                requestId = event.requestId,
                responseCode = status.takeIf { it > 0 } ?: 200,
                responseHeaders =
                    buildList {
                        add(HeaderEntry("Content-Type", mimeType))
                        headers.forEach { (name, value) -> add(HeaderEntry(name, value)) }
                    },
                body = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.ISO_8859_1)),
            ),
        )
    }

    private suspend fun evaluate(
        page: PageSession,
        script: String,
    ): JsonElement? {
        val result =
            page.runtime.evaluate(
                EvaluateRequest(expression = script, returnByValue = true, awaitPromise = true, userGesture = true),
            )
        result.exceptionDetails?.let { logger.debug { "WebView JavaScript exception (${it.text})" } }
        return result.result.value
    }

    private class BridgeHandler(
        val interfaceName: String,
        val target: Any,
        val methods: Map<String, Method>,
    )

    private fun dispatchBridge(payload: String) {
        val call =
            try {
                Json.parseToJsonElement(payload).jsonObject
            } catch (e: Exception) {
                logger.warn { "CDP bridge payload rejected (${e.javaClass.simpleName})" }
                return
            }
        val binding = (call["binding"] as? JsonPrimitive)?.content.orEmpty()
        val bridge = bridgeHandlers[binding] ?: return
        val methodName = (call["method"] as? JsonPrimitive)?.content.orEmpty()
        logger.debug { "CDP bridge call ${bridge.interfaceName}.$methodName" }
        val method = bridge.methods[methodName] ?: return
        val values =
            (call["args"] as? JsonArray)
                ?.map { element -> (element as? JsonPrimitive)?.content ?: element.toString() }
                ?.toTypedArray()
                ?: emptyArray()
        if (values.size > 16 || values.any { it.length > 262_144 }) return
        if (pendingCallbacks.incrementAndGet() > MAX_PENDING_CALLBACKS) {
            pendingCallbacks.decrementAndGet()
            return
        }
        if (!handler.post {
                try {
                    if (interfaces[bridge.interfaceName] === bridge.target) method.invoke(bridge.target, *values)
                } catch (e: Exception) {
                    logger.warn { "WebView JavaScript bridge callback failed (${e.javaClass.simpleName})" }
                } finally {
                    pendingCallbacks.decrementAndGet()
                }
            }
        ) {
            pendingCallbacks.decrementAndGet()
        }
    }

    /*
     * Some fingerprint browsers disable addBindings in new JS contexts. Then
     * Runtime.addBinding vanishes on navigation even with Runtime.enable; Playwright
     * restores its wrapper but not the native callback. Queue interface calls in
     * the page and poll them over CDP until the browser restores binding persistence.
     */
    private suspend fun installBridge(
        page: PageSession,
        name: String,
        obj: Any,
    ) {
        val methods =
            obj.javaClass.methods
                .filter { it.isAnnotationPresent(JavascriptInterface::class.java) }
                .associateBy { it.name }
        if (methods.isEmpty() || interfaces[name] !== obj) return
        val binding = "__suwayomiBridge${UUID.randomUUID().toString().replace("-", "")}"
        bindings.put(name, binding)?.let(bridgeHandlers::remove)
        bridgeHandlers[binding] = BridgeHandler(name, obj, methods)
        val quotedName = JsonPrimitive(name).toString()
        val quotedBinding = JsonPrimitive(binding).toString()
        val quotedMethods = JsonArray(methods.keys.map { JsonPrimitive(it) }).toString()
        val script =
            """
            window.__suwayomiBridgeInit = (window.__suwayomiBridgeInit || 0) + 1;
            window[$quotedName] = Object.fromEntries(
              $quotedMethods.map(function (method) {
                return [method, function () {
                  var args = Array.prototype.slice.call(arguments);
                  try {
                    var outbox = window.__suwayomiOutbox || (window.__suwayomiOutbox = []);
                    var message = JSON.stringify({ binding: $quotedBinding, method: method, args: args });
                    var chars = window.__suwayomiOutboxChars || 0;
                    if (outbox.length >= $MAX_OUTBOX_MESSAGES || chars + message.length > $MAX_OUTBOX_CHARS) {
                      window.__suwayomiOutboxDropped = (window.__suwayomiOutboxDropped || 0) + 1;
                      return;
                    }
                    outbox.push(message);
                    window.__suwayomiOutboxChars = chars + message.length;
                  } catch (e) { console.error('suwayomi bridge failed: ' + e); }
                }];
              }));
            """.trimIndent()
        page.page.addScriptToEvaluateOnNewDocument(AddScriptToEvaluateOnNewDocumentRequest(source = script))
        evaluate(page, script)
    }

    private suspend fun tick(page: PageSession) {
        if (bridgeHandlers.isEmpty()) return
        val capacity = (MAX_PENDING_CALLBACKS - pendingCallbacks.get()).coerceAtLeast(0)
        if (capacity == 0) return
        val result = evaluate(page, "($OUTBOX_SCRIPT)($capacity)") as? JsonObject ?: return
        val dropped = (result["dropped"] as? JsonPrimitive)?.intOrNull ?: 0
        if (dropped > 0) logger.warn { "CDP bridge outbox dropped $dropped messages" }
        (result["messages"] as? JsonArray)?.forEach { element ->
            dispatchBridge((element as? JsonPrimitive)?.content.orEmpty())
        }
    }

    private suspend fun flushBridge(page: PageSession) {
        try {
            tick(page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug { "Could not flush WebView bridge before navigation (${e.javaClass.simpleName})" }
        }
    }

    private fun receivedError(
        url: String,
        description: String,
    ) {
        val request =
            object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse(url)

                override fun isForMainFrame() = true

                override fun isRedirect() = false

                override fun hasGesture() = false

                override fun getMethod() = "GET"

                override fun getRequestHeaders(): Map<String, String> = emptyMap()
            }
        handler.post {
            viewClient.onReceivedError(view, request, WebResourceErrorImpl(WebViewClient.ERROR_UNKNOWN, description))
        }
    }

    private suspend fun seed(
        page: PageSession,
        url: String,
    ) {
        try {
            val origin = url.toHttpUrlOrNull() ?: return
            val cookies = cookieStore.get(origin)
            if (cookies.isEmpty()) return
            page.network.setCookies(
                cookies.map { cookie ->
                    CookieParam(
                        name = cookie.name,
                        value = cookie.value,
                        domain = cookie.domain,
                        path = cookie.path,
                        secure = cookie.secure,
                        httpOnly = cookie.httpOnly,
                        expires = if (cookie.persistent) cookie.expiresAt / 1000.0 else null,
                    )
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "Could not seed extension WebView cookies (${e.javaClass.simpleName})" }
        }
    }

    private suspend fun harvest(page: PageSession) {
        val url = currentUrl ?: return
        if (url.toHttpUrlOrNull() == null) return
        val cookies =
            page.network.getCookies(GetCookiesRequest(urls = listOf(url))).cookies.map { cookie ->
                BrowserCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path,
                    expires = cookie.expires ?: 0.0,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                )
            }
        cookieStore.addAll(WebViewCookies.fromService(cookies))
    }

    private fun dispatchTouch(event: MotionEvent): Boolean {
        val type =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> TouchEventType.touchStart
                MotionEvent.ACTION_MOVE -> TouchEventType.touchMove
                MotionEvent.ACTION_UP -> TouchEventType.touchEnd
                MotionEvent.ACTION_CANCEL -> TouchEventType.touchCancel
                else -> return false
            }
        val density = view.resources.displayMetrics.density
        val point =
            TouchPoint(
                x = (event.x / density).toDouble(),
                y = (event.y / density).toDouble(),
                force = event.pressure.toDouble(),
            )
        if (type == TouchEventType.touchStart) {
            lastTouchMoveNanos = 0L
            lastTouchMove = null
        }
        if (type == TouchEventType.touchMove) {
            lastTouchMove = point
            val now = System.nanoTime()
            if (now - lastTouchMoveNanos < TOUCH_MOVE_INTERVAL_NANOS) return true
            lastTouchMoveNanos = now
        }
        val finalMove = if (type == TouchEventType.touchEnd) lastTouchMove else null
        if (type == TouchEventType.touchEnd || type == TouchEventType.touchCancel) lastTouchMove = null
        browser.submit { page ->
            finalMove?.let {
                page.input.dispatchTouchEvent(DispatchTouchEventRequest(TouchEventType.touchMove, listOf(it)))
            }
            page.input.dispatchTouchEvent(
                DispatchTouchEventRequest(
                    type = type,
                    touchPoints = if (type == TouchEventType.touchStart || type == TouchEventType.touchMove) listOf(point) else emptyList(),
                ),
            )
        }
        return true
    }

    private fun failed(error: Exception) {
        logger.warn { "Prowl CDP WebView failed (${error.javaClass.simpleName})" }
        receivedError(currentUrl.orEmpty(), "Browser connection failed")
    }

    private data class DocumentOverride(
        val url: String,
        val html: String,
        val mimeType: String,
    )

    companion object {
        private const val TOUCH_MOVE_INTERVAL_NANOS = 80_000_000L

        private const val MAX_PENDING_CALLBACKS = 64
        private const val MAX_OUTBOX_MESSAGES = 256
        private const val MAX_OUTBOX_CHARS = 8 * 1024 * 1024
        private val OUTBOX_SCRIPT =
            """
            function(limit) {
              var outbox = window.__suwayomiOutbox || [];
              var messages = outbox.splice(0, limit);
              window.__suwayomiOutboxChars = Math.max(0,
                (window.__suwayomiOutboxChars || 0) - messages.reduce(
                  function(sum, message) { return sum + message.length; }, 0));
              var dropped = window.__suwayomiOutboxDropped || 0;
              window.__suwayomiOutboxDropped = 0;
              return { messages: messages, dropped: dropped };
            }
            """.trimIndent()

        fun create(view: WebView): WebViewProvider =
            Proxy.newProxyInstance(
                WebViewProvider::class.java.classLoader,
                arrayOf(WebViewProvider::class.java, DocumentStartScriptProvider::class.java),
                CdpWebViewProvider(view),
            ) as WebViewProvider
    }
}
