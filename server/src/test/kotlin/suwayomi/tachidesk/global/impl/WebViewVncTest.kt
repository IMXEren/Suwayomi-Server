package suwayomi.tachidesk.global.impl

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import io.javalin.http.Header
import io.javalin.websocket.WsBinaryMessageHandler
import io.javalin.websocket.WsCloseContext
import io.javalin.websocket.WsCloseHandler
import io.javalin.websocket.WsConfig
import io.javalin.websocket.WsConnectContext
import io.javalin.websocket.WsConnectHandler
import io.javalin.websocket.WsErrorContext
import io.javalin.websocket.WsErrorHandler
import io.javalin.websocket.WsMessageHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import suwayomi.tachidesk.global.controller.WebViewVncController
import suwayomi.tachidesk.global.impl.util.Jwt
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.mutations.WebviewMutation
import suwayomi.tachidesk.graphql.queries.WebviewQuery
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.graphql.types.WebViewProvider
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.settings.SettingsRegistry
import suwayomi.tachidesk.server.user.SERVER_TOKEN_COOKIE
import suwayomi.tachidesk.server.user.UnauthorizedException
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.getUserFromWsContext
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.test.ApplicationTest
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A browser that answers the interactive commands without a service behind it. */
private class StubBrowser(
    private val tabs: List<WebViewTab> = listOf(WebViewTab(id = "tab-1", url = "https://example.com/", title = "Example", status = 200)),
) : WebViewBrowser() {
    val opened = mutableListOf<String>()
    val closed = mutableListOf<String?>()
    val listed = mutableListOf<String?>()

    override fun open(
        url: String,
        timeoutMillis: Int,
    ): WebViewTab {
        opened += url
        return tabs.first()
    }

    override fun close(tab: String?): List<String> {
        closed += tab
        return listOfNotNull(tab ?: "tab-1")
    }

    override fun list(tab: String?): List<WebViewTab> {
        listed += tab
        return tabs
    }
}

class WebViewBrowserTest : ApplicationTest() {
    /**
     * The command name has to reach the service.
     *
     * This serializer omits fields that carry a default, so a command declared with one is left
     * out of the body and the service refuses the request as a malformed one, which is a silent
     * failure until something calls the service for real.
     */
    @Test
    fun `sends the command name in every request it makes`() {
        val bodies = mutableListOf<String>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val buffer = Buffer()
                    request.body?.writeTo(buffer)
                    bodies += buffer.readUtf8()
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"status":"ok","tab":{"id":"tab-1","url":"https://example.com/","title":"Example","status":200}}"""
                                .toResponseBody("application/json".toMediaType()),
                        ).build()
                }.build()

        val browser = WebViewBrowser(client)
        browser.open("https://example.com/")
        browser.close("tab-1")

        assertEquals(4, bodies.size, "opening, the cookie read that follows it, closing, and the profile read the close makes")
        assertTrue(bodies.any { it.contains("\"cmd\":\"browser.open\"") }, bodies.toString())
        assertTrue(bodies.any { it.contains("\"cmd\":\"browser.close\"") }, bodies.toString())
        assertTrue(bodies.any { it.contains("\"cmd\":\"cookies.list\"") }, bodies.toString())
        assertTrue(bodies.all { it.contains("\"cmd\":") }, "every request must name its command: $bodies")
    }

    @Test
    fun `accepts absolute http and https urls`() {
        assertEquals("https://example.com/a", WebViewBrowser.validateWebViewUrl("https://example.com/a"))
        assertEquals("http://example.com/", WebViewBrowser.validateWebViewUrl("  http://example.com/  "))
    }

    @Test
    fun `refuses anything that is not an absolute http or https url`() {
        val refused =
            listOf(
                "javascript:alert(1)",
                "file:///etc/passwd",
                "ftp://example.com/",
                "data:text/html,hi",
                "//example.com/",
                "/relative/path",
                "example.com",
                "http://",
                "",
                "   ",
            )
        refused.forEach { value ->
            assertThrows<IllegalArgumentException>("expected $value to be refused") {
                WebViewBrowser.validateWebViewUrl(value)
            }
        }
    }

    @Test
    fun `refuses a tab id that is not shaped like one the service issues`() {
        assertEquals("tab-1:A_2", WebViewBrowser.validateTabId("tab-1:A_2"))
        listOf("", " ", "../../etc", "tab id", "a".repeat(65)).forEach { value ->
            assertThrows<IllegalArgumentException>("expected $value to be refused") {
                WebViewBrowser.validateTabId(value)
            }
        }
    }

    @Test
    fun `opens a page and reports the tab the service returned`() {
        val browser = StubBrowser()
        val payload =
            WebviewMutation(browser)
                .openWebView(WebviewMutation.OpenWebViewInput(url = "https://example.com/"))
                .join()

        assertEquals(listOf("https://example.com/"), browser.opened)
        assertEquals("tab-1", payload.tab)
        assertEquals("Example", payload.title)
        assertEquals(200, payload.status)
    }

    @Test
    fun `a refused url never reaches the service`() {
        val browser = StubBrowser()
        val error =
            assertFailsWith<CompletionException> {
                WebviewMutation(browser)
                    .openWebView(WebviewMutation.OpenWebViewInput(url = "file:///etc/passwd"))
                    .join()
            }
        assertTrue(
            error.cause is IllegalArgumentException,
            "a refused url must be reported as invalid input, was ${error.cause}",
        )
        assertTrue(browser.opened.isEmpty(), "the service must not be called for a refused url")
    }

    @Test
    fun `closes the named tab and every tab when none is named`() {
        val browser = StubBrowser()
        val mutation = WebviewMutation(browser)

        val named =
            mutation
                .closeWebView(WebviewMutation.CloseWebViewInput(tab = "tab-1"))
                .join()
        assertEquals(listOf("tab-1"), named.closed)

        val every = mutation.closeWebView(null).join()
        assertEquals(listOf("tab-1"), every.closed)
        assertEquals(listOf("tab-1", null), browser.closed)
    }

    @Test
    fun `a visitor cannot use the interactive browser commands`() {
        assertThrows<UnauthorizedException> { UserType.Visitor.requireUser() }
    }

    @Test
    fun `an admin passes the same guard the interactive browser commands use`() {
        UserType.Admin(1).requireUser()
    }

    @Test
    fun `reports the tabs the external browser has open`() {
        val browser = StubBrowser()
        val tabs =
            WebviewQuery(browser)
                .webViewTabs()
                .join()

        assertEquals(1, tabs.size)
        assertEquals("tab-1", tabs.first().id)
        assertEquals("https://example.com/", tabs.first().url)
        assertEquals("Example", tabs.first().title)
        assertEquals(listOf<String?>(null), browser.listed, "listing without a tab must not name one")
    }

    @Test
    fun `the displayed tab is the one the browser is asked to keep alive`() {
        val browser = StubBrowser()

        WebviewQuery(browser)
            .webViewTabs("tab-7")
            .join()

        assertEquals(listOf<String?>("tab-7"), browser.listed)
    }

    @Test
    fun `a tab id the service would refuse never reaches the browser`() {
        val browser = StubBrowser()

        val error =
            assertFailsWith<CompletionException> {
                WebviewQuery(browser)
                    .webViewTabs("../../etc")
                    .join()
            }

        assertTrue(
            error.cause is IllegalArgumentException,
            "a refused tab id must be reported as invalid input, was ${error.cause}",
        )
        assertTrue(browser.listed.isEmpty(), "the browser must not be called for a refused tab id")
    }

    @Test
    fun `the tabs query carries the auth directive the other web view fields carry`() {
        assertTrue(
            WebviewQuery::class.java
                .getMethod("webViewTabs", String::class.java)
                .isAnnotationPresent(RequireAuth::class.java),
            "listing the browser's tabs must be authenticated like opening and closing one",
        )
    }

    @Test
    fun `a service that never answers fails instead of hanging`() {
        // A socket that accepts the connection and then says nothing is what a service which is
        // still working looks like from here, and what it must not become is a call that never
        // returns: the proxy in front of the server gives up on a stuck request first and reports
        // that as its own error, which the caller cannot do anything about.
        ServerSocket(0).use { silent ->
            val configured = serverConfig.flareSolverrUrl.value
            try {
                serverConfig.flareSolverrUrl.value = "http://127.0.0.1:${silent.localPort}"
                val client =
                    OkHttpClient
                        .Builder()
                        .callTimeout(500, TimeUnit.MILLISECONDS)
                        .readTimeout(500, TimeUnit.MILLISECONDS)
                        .build()

                val started = System.nanoTime()
                val error =
                    assertFailsWith<IOException> {
                        WebViewBrowser(client).open("https://example.com/")
                    }
                val elapsedMillis = (System.nanoTime() - started) / 1_000_000

                assertTrue(
                    error.message.orEmpty().contains("did not answer"),
                    "the caller must be told the service timed out, was: ${error.message}",
                )
                assertTrue(
                    error.message.orEmpty().contains("webViewOpenTimeout"),
                    "the failure must name the setting that bounds it, was: ${error.message}",
                )
                assertTrue(elapsedMillis < 10_000, "the call must fail rather than hang, took ${elapsedMillis}ms")
            } finally {
                serverConfig.flareSolverrUrl.value = configured
            }
        }
    }

    @Test
    fun `the interactive calls use the web view's timeout and not the solver's`() {
        val configuredOpen = serverConfig.webViewOpenTimeout.value
        val configuredSolver = serverConfig.flareSolverrTimeout.value
        try {
            serverConfig.webViewOpenTimeout.value = 180
            // The solver's budget is set to something that would change the answer if the
            // interactive calls still shared it, which is the mistake this pins.
            serverConfig.flareSolverrTimeout.value = 5

            val client = WebViewBrowser.timedClient(OkHttpClient.Builder().build())
            assertEquals(190_000, client.callTimeoutMillis)
            assertEquals(185_000, client.readTimeoutMillis)
            assertEquals(180_000, WebViewBrowser.interactiveTimeoutMillis())
            assertEquals(180, WebViewBrowser.interactiveTimeoutSeconds())
        } finally {
            serverConfig.webViewOpenTimeout.value = configuredOpen
            serverConfig.flareSolverrTimeout.value = configuredSolver
        }
    }

    @Test
    fun `the client page leaves its token in a cookie that only reaches the vnc routes`() {
        val cookie = WebViewVncController.assetCookie(token = "a.b.c", maxAgeSeconds = 300)

        assertTrue(cookie.startsWith("$SERVER_TOKEN_COOKIE=a.b.c;"), cookie)
        assertTrue(cookie.contains("Path=/api/v1/webview/vnc/"), cookie)
        assertTrue(cookie.contains("Max-Age=300"), cookie)
        assertTrue(cookie.contains("HttpOnly"), cookie)
        assertTrue(cookie.contains("SameSite=Strict"), cookie)
    }

    @Test
    fun `the asset cookie never outlives the token it carries`() {
        val lifetime = serverConfig.jwtTokenExpiry.value.inWholeSeconds
        val nearlyExpired =
            JWT
                .create()
                .withExpiresAt(Instant.now().plus(30, ChronoUnit.SECONDS))
                .sign(Algorithm.HMAC256("test"))

        val short = WebViewVncController.assetCookieMaxAgeSeconds(nearlyExpired)
        assertTrue(short in 1..30, "a token with 30 seconds left must cap the cookie, was $short")

        val noExpiry =
            JWT
                .create()
                .sign(Algorithm.HMAC256("test"))
        assertEquals(lifetime, WebViewVncController.assetCookieMaxAgeSeconds(noExpiry))
        assertEquals(lifetime, WebViewVncController.assetCookieMaxAgeSeconds("not-a-token"))
    }

    @Test
    fun `the provider defaults to the embedded browser and parses either value`() {
        assertEquals(WebViewProvider.CEF, WebViewProvider.from(""))
        assertEquals(WebViewProvider.CEF, WebViewProvider.from("cef"))
        assertEquals(WebViewProvider.PROWL_VNC, WebViewProvider.from("PROWL_VNC"))
        assertEquals(WebViewProvider.PROWL_VNC, WebViewProvider.from("prowl_vnc"))
    }
}

/**
 * The settings this feature adds, read through the registry the schema is generated from.
 */
class WebViewProviderSettingTest : ApplicationTest() {
    @Test
    fun `the web view settings carry their declared defaults`() {
        val provider = SettingsRegistry.get("webViewProvider")
        assertTrue(provider != null, "webViewProvider must be registered as a setting")
        assertEquals(WebViewProvider.CEF, provider.defaultValue)

        val vncUrl = SettingsRegistry.get("webViewVncUrl")
        assertTrue(vncUrl != null, "webViewVncUrl must be registered as a setting")
        assertEquals("http://vnc:6080", vncUrl.defaultValue)

        // An open is a browser start, a navigation and a solve, so it is bounded by its own setting
        // rather than by the challenge solver's budget.
        val openTimeout = SettingsRegistry.get("webViewOpenTimeout")
        assertTrue(openTimeout != null, "webViewOpenTimeout must be registered as a setting")
        assertEquals(180, openTimeout.defaultValue)
    }

    @Test
    fun `the embedded provider is what a default configuration selects`() {
        assertEquals(WebViewProvider.CEF, serverConfig.webViewProvider.value)
        assertEquals("http://vnc:6080", serverConfig.webViewVncUrl.value)
    }
}

/**
 * What a websocket handshake may authenticate with.
 *
 * The client page's own files are authenticated by a cookie confined to the VNC prefix, and a
 * browser attaches cookies to a websocket handshake made from any origin, so the socket must not
 * accept that cookie in place of the token itself.
 */
class WebViewVncSocketAuthTest : ApplicationTest() {
    private fun handshake(
        token: String?,
        cookie: String?,
    ): WsConnectContext {
        val ctx = mockk<WsConnectContext>()
        every { ctx.header(Header.AUTHORIZATION) } returns null
        every { ctx.header("Sec-WebSocket-Protocol") } returns null
        every { ctx.cookie(SERVER_TOKEN_COOKIE) } returns cookie
        every { ctx.queryParam("token") } returns token
        return ctx
    }

    @Test
    fun `a cookie alone cannot open the vnc socket`() {
        val configured = serverConfig.authMode.value
        try {
            serverConfig.authMode.value = AuthMode.UI_LOGIN
            val token = Jwt.generateJwt().accessToken

            assertEquals(
                UserType.Visitor,
                getUserFromWsContext(handshake(token = null, cookie = token), acceptCookie = false),
                "the asset cookie must not be enough to open the socket",
            )
            assertTrue(
                getUserFromWsContext(handshake(token = null, cookie = token)) is UserType.Admin,
                "the other sockets keep accepting the cookie",
            )
        } finally {
            serverConfig.authMode.value = configured
        }
    }

    @Test
    fun `the vnc socket route refuses a handshake that carries only the asset cookie`() {
        val configured = serverConfig.authMode.value
        try {
            serverConfig.authMode.value = AuthMode.UI_LOGIN
            val token = Jwt.generateJwt().accessToken
            val config = mockk<WsConfig>()
            val onConnect = slot<WsConnectHandler>()
            every { config.onConnect(capture(onConnect)) } returns Unit
            every { config.onMessage(any<WsMessageHandler>()) } returns Unit
            every { config.onBinaryMessage(any<WsBinaryMessageHandler>()) } returns Unit
            every { config.onClose(any<WsCloseHandler>()) } returns Unit
            every { config.onError(any<WsErrorHandler>()) } returns Unit

            WebViewVncController.vncSocket(config)

            assertThrows<UnauthorizedException>(
                "a handshake carrying only the cookie the page left must not open the socket",
            ) {
                onConnect.captured.handleConnect(handshake(token = null, cookie = token))
            }
        } finally {
            serverConfig.authMode.value = configured
        }
    }

    @Test
    fun `the vnc socket accepts the token the handshake presents`() {
        val configured = serverConfig.authMode.value
        try {
            serverConfig.authMode.value = AuthMode.UI_LOGIN
            val token = Jwt.generateJwt().accessToken

            assertTrue(
                getUserFromWsContext(handshake(token = token, cookie = null), acceptCookie = false) is UserType.Admin,
            )
            assertEquals(
                UserType.Visitor,
                getUserFromWsContext(handshake(token = null, cookie = null), acceptCookie = false),
                "an unauthenticated handshake is still refused",
            )
        } finally {
            serverConfig.authMode.value = configured
        }
    }
}

/**
 * What a socket's close and error paths do to the tab it was displaying: a socket takes its own tab
 * exactly once, whichever path ends it, and never another socket's.
 */
class WebViewVncTabCloseTest : ApplicationTest() {
    @Test
    fun `a socket close closes exactly the tab it was displaying, once`() {
        withTabCloseSocket { service, socket ->
            connect(socket, sessionId = "socket-1", tabId = "tab-1")

            socket.onClose.handleClose(closeContext("socket-1"))
            socket.onError.handleError(errorContext("socket-1"))
            socket.onClose.handleClose(closeContext("socket-1"))

            assertEquals(
                listOf("""{"cmd":"browser.close","tab":"tab-1"}"""),
                service.closeBodies,
                "the displayed tab must be closed once, by its own id: ${service.bodies}",
            )
        }
    }

    @Test
    fun `a socket error closes the displayed tab even when it ends the socket first`() {
        withTabCloseSocket { service, socket ->
            connect(socket, sessionId = "socket-1", tabId = "tab-1")

            socket.onError.handleError(errorContext("socket-1"))
            socket.onClose.handleClose(closeContext("socket-1"))

            assertEquals(
                listOf("""{"cmd":"browser.close","tab":"tab-1"}"""),
                service.closeBodies,
                "an error that ends the socket must close its tab, and the close that follows must not close it again",
            )
        }
    }

    @Test
    fun `closing one socket leaves another socket's tab open`() {
        withTabCloseSocket { service, socket ->
            connect(socket, sessionId = "socket-1", tabId = "tab-1")
            connect(socket, sessionId = "socket-2", tabId = "tab-2")

            socket.onClose.handleClose(closeContext("socket-1"))
            assertEquals(
                listOf("""{"cmd":"browser.close","tab":"tab-1"}"""),
                service.closeBodies,
                "closing the first socket must close only its own tab, not the one another socket is showing",
            )

            socket.onClose.handleClose(closeContext("socket-2"))
            assertEquals(
                listOf("""{"cmd":"browser.close","tab":"tab-1"}""", """{"cmd":"browser.close","tab":"tab-2"}"""),
                service.closeBodies,
                "the second socket's tab is closed when that socket closes, and not before",
            )
        }
    }

    /** Run [block] against a stub browser service, then restore the settings it changed. */
    private fun withTabCloseSocket(block: (StubBrowserService, SocketHandlers) -> Unit) {
        val service = StubBrowserService()
        val configuredSolver = serverConfig.flareSolverrUrl.value
        val configuredVnc = serverConfig.webViewVncUrl.value
        val configuredAuth = serverConfig.authMode.value
        try {
            serverConfig.flareSolverrUrl.value = "http://127.0.0.1:${service.port}"
            // The socket bridge dials this as soon as a handshake is accepted; a refused port keeps
            // it off the network the assertion depends on.
            serverConfig.webViewVncUrl.value = "http://127.0.0.1:1"
            serverConfig.authMode.value = AuthMode.UI_LOGIN
            block(service, registerSocket())
        } finally {
            service.stop()
            serverConfig.flareSolverrUrl.value = configuredSolver
            serverConfig.webViewVncUrl.value = configuredVnc
            serverConfig.authMode.value = configuredAuth
        }
    }

    private fun registerSocket(): SocketHandlers {
        val config = mockk<WsConfig>()
        val onConnect = slot<WsConnectHandler>()
        val onClose = slot<WsCloseHandler>()
        val onError = slot<WsErrorHandler>()
        every { config.onConnect(capture(onConnect)) } returns Unit
        every { config.onMessage(any<WsMessageHandler>()) } returns Unit
        every { config.onBinaryMessage(any<WsBinaryMessageHandler>()) } returns Unit
        every { config.onClose(capture(onClose)) } returns Unit
        every { config.onError(capture(onError)) } returns Unit

        WebViewVncController.vncSocket(config)
        return SocketHandlers(onConnect.captured, onClose.captured, onError.captured)
    }

    private fun connect(
        socket: SocketHandlers,
        sessionId: String,
        tabId: String,
    ) {
        val ctx = mockk<WsConnectContext>(relaxed = true)
        every { ctx.header(Header.AUTHORIZATION) } returns null
        every { ctx.header("Sec-WebSocket-Protocol") } returns null
        every { ctx.cookie(SERVER_TOKEN_COOKIE) } returns null
        every { ctx.queryParam("token") } returns Jwt.generateJwt().accessToken
        every { ctx.queryParam("tab") } returns tabId
        every { ctx.sessionId() } returns sessionId
        socket.onConnect.handleConnect(ctx)
    }

    private fun closeContext(sessionId: String): WsCloseContext {
        val ctx = mockk<WsCloseContext>(relaxed = true)
        every { ctx.sessionId() } returns sessionId
        return ctx
    }

    private fun errorContext(sessionId: String): WsErrorContext {
        val ctx = mockk<WsErrorContext>(relaxed = true)
        every { ctx.sessionId() } returns sessionId
        return ctx
    }

    /** The socket route's handlers, so a test can drive one close or error without a server. */
    private class SocketHandlers(
        val onConnect: WsConnectHandler,
        val onClose: WsCloseHandler,
        val onError: WsErrorHandler,
    )

    /** A browser service stub that records every command; only the close reply is read. */
    private class StubBrowserService {
        val bodies = mutableListOf<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val port: Int get() = server.address.port

        val closeBodies: List<String>
            get() = bodies.filter { it.contains("\"cmd\":\"browser.close\"") }

        init {
            server.createContext("/v1") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().decodeToString() }
                bodies += body
                val reply =
                    if (body.contains("\"cmd\":\"browser.close\"")) {
                        """{"status":"ok","closed":["tab-1"]}"""
                    } else {
                        """{"status":"ok","cookies":[]}"""
                    }
                val bytes = reply.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun stop() = server.stop(0)
    }
}
