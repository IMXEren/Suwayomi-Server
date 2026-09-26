package suwayomi.tachidesk.global.controller

import com.auth0.jwt.JWT
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.websocket.WsConfig
import io.javalin.websocket.WsConnectContext
import io.javalin.websocket.WsContext
import okhttp3.OkHttpClient
import okhttp3.Request
import suwayomi.tachidesk.global.impl.WebViewBrowser
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.user.SERVER_TOKEN_COOKIE
import suwayomi.tachidesk.server.user.getTokenFromContext
import suwayomi.tachidesk.server.user.getUserFromWsContext
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.DirectProxySelector
import suwayomi.tachidesk.server.util.ServerSubpath
import suwayomi.tachidesk.server.util.asDirectClient
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Serves the external browser's web VNC endpoint on the server's own origin.
 *
 * The browser service runs a browser on a display that a VNC server exports, and the VNC server
 * ships a web client. Both the client's files and its socket are forwarded here so that nothing
 * has to be published to the host and the server's own authentication is the only way in: the
 * VNC endpoint itself has no authentication, and reaching it directly would hand out a desktop.
 *
 * The socket path is where the VNC server's web bridge listens, which is the convention that
 * noVNC's own client uses.
 *
 * The client page is opened with the token in its query string, which authenticates the navigation
 * but not the files the page then loads: those are sub-resource requests, which cannot carry an
 * authorization header and do not inherit the page's query string. The page therefore also leaves
 * its token in a cookie confined to this prefix, which is the only place that cookie is ever sent,
 * and the socket additionally requires the token itself rather than that cookie, because a browser
 * attaches cookies to a websocket handshake made from any origin.
 */
object WebViewVncController {
    private const val SOCKET_PATH = "/websockify"

    private val browser = WebViewBrowser()

    /** Which browser tab each socket is displaying, so a view closes its own tab on the way out. */
    private val tabBySession = ConcurrentHashMap<String, String>()

    /**
     * Path the asset cookie is scoped to, which is this surface and nothing else.
     */
    private val assetCookiePath = ServerSubpath.maybeAddAsPrefix("/api/v1/webview/vnc/")

    private val vncClient: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .build()
            // The endpoint is an internal service, which the server's own SOCKS proxy setting
            // would otherwise send to the upstream proxy instead.
            .asDirectClient()

    private val socketClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .proxy(DirectProxySelector)
            .build()

    private val bridges = ConcurrentHashMap<String, SocketBridge>()

    /**
     * The noVNC client page. Relative asset paths in it resolve against this route, so it is
     * served with a trailing slash.
     */
    fun vncIndex(ctx: Context) {
        ctx.getAttribute(Attribute.TachideskUser).requireUser()
        // The page's own files cannot present the token themselves, so it is also left in a cookie
        // that only ever travels under this prefix. There is no token to leave in the modes that
        // authenticate without one, and their sub-resource requests carry the session instead.
        getTokenFromContext(ctx)?.let { token ->
            ctx.header("Set-Cookie", assetCookie(token, assetCookieMaxAgeSeconds(token)))
        }
        proxy(ctx, "/vnc.html")
    }

    /**
     * The cookie that lets the client page's own files authenticate.
     */
    internal fun assetCookie(
        token: String,
        maxAgeSeconds: Long,
    ): String =
        "$SERVER_TOKEN_COOKIE=$token; Path=$assetCookiePath; Max-Age=$maxAgeSeconds; HttpOnly; " +
            "SameSite=Strict"

    /**
     * How long the asset cookie may live, in seconds.
     *
     * Bounded by what is left of the token it carries as well as by the configured token lifetime,
     * so it can never outlive the credential it holds, and it stays short because the page only
     * needs it while it is loading its own files.
     */
    internal fun assetCookieMaxAgeSeconds(token: String): Long {
        val configured = serverConfig.jwtTokenExpiry.value.inWholeSeconds
        val expiresAt = runCatching { JWT.decode(token).expiresAtAsInstant }.getOrNull() ?: return configured
        val remaining = Duration.between(Instant.now(), expiresAt).seconds
        return if (remaining in 1 until configured) remaining else configured
    }

    /**
     * The client's own files, forwarded from the endpoint unchanged apart from the headers that
     * describe a body as it was transported rather than as it is returned.
     */
    fun vncAsset(ctx: Context) {
        ctx.getAttribute(Attribute.TachideskUser).requireUser()
        val path = ctx.pathParam("path").trim()
        if (path.isEmpty() || path.contains("..") || path.startsWith("/")) {
            ctx.status(HttpStatus.BAD_REQUEST)
            return
        }
        proxy(ctx, "/$path")
    }

    /**
     * The VNC socket, bridged to the endpoint's web socket.
     *
     * Messages are forwarded in both directions as they arrive, in whichever form they arrive:
     * the protocol carries the framebuffer as binary and control messages as text.
     */
    fun vncSocket(ws: WsConfig) {
        ws.onConnect { ctx ->
            // The page's own files are authenticated by a cookie scoped to this prefix, and a
            // browser attaches cookies to a websocket handshake made from any origin, so a
            // handshake has to present the token itself and the cookie alone is not enough.
            getUserFromWsContext(ctx, acceptCookie = false).requireUser()
            ctx.enableAutomaticPings()
            // Remember which browser tab this socket is displaying, so closing the socket closes
            // that tab and no other. A view that goes away must not leave a browser running: an
            // abandoned tab holds the browser service's egress and starves the fetch path the
            // app's own challenge solving depends on.
            ctx
                .queryParam("tab")
                ?.takeIf { it.isNotBlank() }
                ?.let { tabBySession[ctx.sessionId()] = it }
            bridges[ctx.sessionId()] = SocketBridge(ctx)
        }
        ws.onMessage { ctx ->
            bridges[ctx.sessionId()]?.sendText(ctx.message())
        }
        ws.onBinaryMessage { ctx ->
            bridges[ctx.sessionId()]?.sendBinary(ctx.data())
        }
        ws.onClose { ctx ->
            bridges.remove(ctx.sessionId())?.close()
            closeViewedTab(ctx)
        }
        ws.onError { ctx ->
            bridges.remove(ctx.sessionId())?.close()
            closeViewedTab(ctx)
        }
    }

    /**
     * Close the tab this socket was displaying, at most once.
     *
     * Both the close and the error path call this, and removing the entry is what makes it
     * idempotent: after the first call there is nothing left to close, so a second call is a no-op.
     * A close that fails here is not a leak either, because the service reaps idle interactive tabs
     * on its own timer.
     */
    private fun closeViewedTab(ctx: WsContext) {
        val tab = tabBySession.remove(ctx.sessionId()) ?: return
        runCatching { browser.close(tab) }
    }

    private fun proxy(
        ctx: Context,
        upstreamPath: String,
    ) {
        val upstream = serverConfig.webViewVncUrl.value.removeSuffix("/")
        val request =
            Request
                .Builder()
                .url(upstream + upstreamPath)
                .get()
                .build()

        val response =
            try {
                vncClient.newCall(request).execute()
            } catch (e: IOException) {
                ctx.status(HttpStatus.BAD_GATEWAY)
                ctx.result("The external browser service is not reachable")
                return
            }

        // The body is read and the response closed before anything is handed to the context:
        // Javalin writes what it was given once the handler returns, so a stream that the closing
        // of the response had already closed fails the response with "closed".
        val code = response.code
        val contentType = response.header("Content-Type")
        val cacheControl = response.header("Cache-Control")
        val bytes = response.use { it.body?.bytes() ?: ByteArray(0) }

        ctx.status(code)
        contentType?.let { type -> ctx.contentType(type) }
        cacheControl?.let { cache -> ctx.header("Cache-Control", cache) }
        ctx.result(bytes)
    }

    /**
     * One client connection, paired with the endpoint connection it forwards to.
     */
    private class SocketBridge(
        private val client: WsConnectContext,
    ) {
        private val upstream: CompletableFuture<WebSocket>

        init {
            val target = socketUri()
            upstream =
                socketClient
                    .newWebSocketBuilder()
                    .buildAsync(
                        target,
                        object : WebSocket.Listener {
                            override fun onOpen(webSocket: WebSocket) {
                                webSocket.request(1)
                            }

                            override fun onText(
                                webSocket: WebSocket,
                                data: CharSequence,
                                last: Boolean,
                            ): CompletableFuture<*> {
                                client.send(data.toString())
                                webSocket.request(1)
                                return CompletableFuture.completedFuture(null)
                            }

                            override fun onBinary(
                                webSocket: WebSocket,
                                data: ByteBuffer,
                                last: Boolean,
                            ): CompletableFuture<*> {
                                client.send(data)
                                webSocket.request(1)
                                return CompletableFuture.completedFuture(null)
                            }

                            override fun onClose(
                                webSocket: WebSocket,
                                statusCode: Int,
                                reason: String,
                            ): CompletableFuture<*> {
                                client.closeSession(statusCode, reason)
                                return CompletableFuture.completedFuture(null)
                            }

                            override fun onError(
                                webSocket: WebSocket,
                                error: Throwable,
                            ) {
                                client.closeSession()
                            }
                        },
                    )
        }

        fun sendText(message: String) {
            upstream.thenAccept { it.sendText(message, true) }
        }

        fun sendBinary(data: ByteBuffer) {
            upstream.thenAccept { it.sendBinary(data, true) }
        }

        fun close() {
            upstream.thenAccept { it.sendClose(WebSocket.NORMAL_CLOSURE, "closed") }
        }

        private fun socketUri(): URI {
            val configured = URI(serverConfig.webViewVncUrl.value.removeSuffix("/"))
            val secure = configured.scheme == "https"
            val scheme = if (secure) "wss" else "ws"
            val port =
                if (configured.port > 0) {
                    configured.port
                } else {
                    if (secure) 443 else 80
                }
            return URI("$scheme://${configured.host}:$port$SOCKET_PATH")
        }
    }
}
