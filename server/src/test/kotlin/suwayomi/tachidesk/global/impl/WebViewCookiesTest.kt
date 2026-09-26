package suwayomi.tachidesk.global.impl

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val OPEN_REPLY =
    """{"status":"ok","tab":{"id":"tab-1","url":"https://example.com/","title":"Example","status":200}}"""

/** The tab the open left open, which a poll that sees no movement reports unchanged. */
private const val TAB_REPLY =
    """{"status":"ok","tabs":[{"id":"tab-1","url":"https://example.com/","title":"Example","status":200}]}"""

private const val OK_REPLY = """{"status":"ok"}"""

/** A cookie the service reports for the browsed origin, with no expiry, which means a session. */
private const val SESSION_COOKIE =
    """{"name":"session","value":"from-browser","domain":".example.com","path":"/"}"""

/**
 * An expiry no session reaches, used to tell a session cookie from one that reads as expired.
 */
internal fun sessionFarFutureThreshold(): Long = System.currentTimeMillis() + 100L * 365 * 24 * 60 * 60 * 1000

/** A cookie store that answers from a list and records what the bridge wrote into it. */
private class RecordingCookieStore(
    private val stored: List<Cookie> = emptyList(),
) : WebViewCookieStore {
    val written = mutableListOf<Cookie>()

    override fun get(url: HttpUrl): List<Cookie> = stored.filter { url.host.endsWith(it.domain) }

    override fun addAll(cookies: List<Cookie>) {
        written += cookies
    }
}

/** A browser service that answers each command and records every request body it was sent. */
private class StubService(
    private val respond: (command: String) -> String,
) {
    val bodies = mutableListOf<String>()

    val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                val body = buffer.readUtf8()
                bodies += body
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(respond(commandOf(body)).toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

    fun bodyFor(command: String): String = bodies.first { it.contains("\"cmd\":\"$command\"") }

    private fun commandOf(body: String): String =
        Regex("\"cmd\"\\s*:\\s*\"([^\"]+)\"")
            .find(body)
            ?.groupValues
            ?.get(1)
            .orEmpty()
}

internal fun appCookie(
    name: String,
    value: String,
    domain: String,
    hostOnly: Boolean = true,
    path: String = "/",
    expiresAt: Long = Long.MAX_VALUE,
    secure: Boolean = false,
    httpOnly: Boolean = false,
): Cookie {
    val builder =
        Cookie
            .Builder()
            .name(name)
            .value(value)
            .path(path)
    if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
    builder.expiresAt(expiresAt)
    if (secure) builder.secure()
    if (httpOnly) builder.httpOnly()
    return builder.build()
}

/**
 * Session state moving between the app's own requests and the external browser.
 *
 * The embedded WebView has always shared its cookies with the app, so these are what make the
 * external one its equal rather than a browser the app cannot see.
 */
class WebViewCookieBridgeTest : ApplicationTest() {
    @Test
    fun `the app's cookies are sent with the open so the browser starts in the same session`() {
        val store = RecordingCookieStore(listOf(appCookie("session", "from-app", "example.com")))
        val service = StubService { command -> if (command == "browser.open") OPEN_REPLY else OK_REPLY }

        WebViewBrowser(service.client, store).open("https://example.com/")

        val body = service.bodyFor("browser.open")
        assertTrue(body.contains("\"name\":\"session\""), body)
        assertTrue(body.contains("\"value\":\"from-app\""), body)
        assertTrue(body.contains("\"domain\":\"example.com\""), body)
        // Only the fields the service installs are sent, so nothing implies the others are honoured.
        assertFalse(body.contains("expires"), body)
        assertFalse(body.contains("httpOnly"), body)
    }

    @Test
    fun `the browser's cookies are stored once the open succeeds`() {
        val store = RecordingCookieStore()
        val service =
            StubService { command ->
                if (command == "cookies.list") """{"status":"ok","cookies":[$SESSION_COOKIE]}""" else OPEN_REPLY
            }

        WebViewBrowser(service.client, store).open("https://example.com/")

        assertEquals(1, store.written.size)
        val session = store.written.single()
        assertEquals("session", session.name)
        assertEquals("example.com", session.domain)
        // A cookie the browser reported without an expiry must not read as expired: it is kept the
        // way the embedded browser's flush keeps a session cookie, with an expiry beyond any date.
        assertTrue(session.expiresAt > sessionFarFutureThreshold(), "a session cookie must not read as expired")
        assertTrue(service.bodyFor("cookies.list").contains("\"url\":\"https://example.com/\""))
    }

    @Test
    fun `a close reads the whole profile so a login that moved domains is not lost`() {
        val store = RecordingCookieStore()
        // The tab's own page is example.com, and the login it went through left its cookie on the
        // domain that authenticated it, which a read scoped to the page would not report.
        val crossDomain =
            """{"name":"auth","value":"from-browser","domain":".auth.example.net","path":"/"}"""
        val service =
            StubService { command ->
                when (command) {
                    "cookies.list" -> """{"status":"ok","cookies":[$crossDomain]}"""
                    "browser.close" -> """{"status":"ok","closed":["tab-1"]}"""
                    else -> OPEN_REPLY
                }
            }

        val browser = WebViewBrowser(service.client, store)
        browser.open("https://example.com/")
        store.written.clear()

        browser.close("tab-1")

        assertEquals(listOf("auth"), store.written.map { it.name }, "closing a tab must bring its session back with it")
        assertEquals("auth.example.net", store.written.single().domain)
        val read = service.bodies.last { it.contains("\"cmd\":\"cookies.list\"") }
        assertFalse(read.contains("\"url\""), "the whole profile is read, not the page the tab ended on: $read")
    }

    @Test
    fun `a login in the displayed tab is read back while the page stays put`() {
        val store = RecordingCookieStore()
        // The page never moves, so the only signal a login gives is the cookie itself.
        var cookieReply = OK_REPLY
        val service =
            StubService { command ->
                when (command) {
                    "browser.list" -> TAB_REPLY
                    "cookies.list" -> cookieReply
                    else -> OPEN_REPLY
                }
            }

        val browser = WebViewBrowser(service.client, store)
        browser.open("https://example.com/")
        store.written.clear()
        service.bodies.clear()

        cookieReply = """{"status":"ok","cookies":[$SESSION_COOKIE]}"""
        browser.list("tab-1")

        assertEquals(1, store.written.size, "a login at the same url must still reach the app")
        assertEquals("session", store.written.single().name)
        val read = service.bodyFor("cookies.list")
        assertFalse(read.contains("\"url\""), "the displayed tab is read as the whole profile: $read")
    }

    @Test
    fun `a poll that sees the same profile does not write it again`() {
        val store = RecordingCookieStore()
        val service =
            StubService { command ->
                when (command) {
                    "browser.list" -> TAB_REPLY
                    "cookies.list" -> """{"status":"ok","cookies":[$SESSION_COOKIE]}"""
                    else -> OPEN_REPLY
                }
            }

        val browser = WebViewBrowser(service.client, store)
        browser.open("https://example.com/")
        store.written.clear()

        browser.list("tab-1")
        assertEquals(1, store.written.size, "the first listing of the tab takes its session")
        store.written.clear()

        browser.list("tab-1")
        assertTrue(store.written.isEmpty(), "a five second poll must not rewrite an unchanged profile")
    }

    @Test
    fun `a failed store write is retried on the next poll`() {
        var failWrite = true
        val stored = mutableListOf<Cookie>()
        val store =
            object : WebViewCookieStore {
                override fun get(url: HttpUrl): List<Cookie> = emptyList()

                override fun addAll(cookies: List<Cookie>) {
                    if (failWrite) throw IllegalStateException("store unavailable")
                    stored += cookies
                }
            }
        val service =
            StubService { command ->
                when (command) {
                    "browser.list" -> TAB_REPLY
                    "cookies.list" -> """{"status":"ok","cookies":[$SESSION_COOKIE]}"""
                    else -> OPEN_REPLY
                }
            }
        val browser = WebViewBrowser(service.client, store)

        browser.list("tab-1")
        assertTrue(stored.isEmpty())
        failWrite = false
        browser.list("tab-1")

        assertEquals(listOf("session"), stored.map { it.name })
    }

    @Test
    fun `listing without a tab is a read-only enumeration`() {
        val store = RecordingCookieStore()
        val service =
            StubService { command ->
                when (command) {
                    "browser.list" -> TAB_REPLY
                    "cookies.list" -> """{"status":"ok","cookies":[$SESSION_COOKIE]}"""
                    else -> OPEN_REPLY
                }
            }

        val browser = WebViewBrowser(service.client, store)
        browser.open("https://example.com/")
        store.written.clear()
        service.bodies.clear()

        browser.list()

        assertTrue(
            service.bodies.none { it.contains("\"cmd\":\"cookies.list\"") },
            "a bare listing must not read the profile: ${service.bodies}",
        )
        assertTrue(store.written.isEmpty(), "a bare listing must not write the store")
    }

    @Test
    fun `listing a tab the browser no longer has does not sync the profile`() {
        val store = RecordingCookieStore()
        val service =
            StubService { command ->
                when (command) {
                    "browser.list" -> """{"status":"ok","tabs":[]}"""
                    "cookies.list" -> """{"status":"ok","cookies":[$SESSION_COOKIE]}"""
                    else -> OPEN_REPLY
                }
            }

        val browser = WebViewBrowser(service.client, store)
        browser.open("https://example.com/")
        store.written.clear()
        service.bodies.clear()

        browser.list("tab-1")

        assertTrue(
            service.bodies.none { it.contains("\"cmd\":\"cookies.list\"") },
            "an unknown tab has no login to take: ${service.bodies}",
        )
        assertTrue(store.written.isEmpty())
    }

    @Test
    fun `closing a tab the app never opened reads the whole profile`() {
        val store = RecordingCookieStore()
        val service =
            StubService { command ->
                when (command) {
                    "cookies.list" -> """{"status":"ok","cookies":[$SESSION_COOKIE]}"""
                    "browser.close" -> """{"status":"ok","closed":["tab-9"]}"""
                    else -> OPEN_REPLY
                }
            }

        WebViewBrowser(service.client, store).close("tab-9")

        assertEquals(1, store.written.size, "a tab the app did not open still has a session worth taking")
        val listBody = service.bodyFor("cookies.list")
        assertFalse(listBody.contains("\"url\""), "an unknown tab is not a url to scope the read to: $listBody")
    }

    @Test
    fun `a cookie read the service refuses does not fail the open`() {
        val store = RecordingCookieStore()
        val service =
            StubService { command ->
                if (command == "cookies.list") """{"status":"error","message":"nope"}""" else OPEN_REPLY
            }

        val tab = WebViewBrowser(service.client, store).open("https://example.com/")

        assertEquals("tab-1", tab.id)
        assertTrue(store.written.isEmpty())
    }

    @Test
    fun `a cookie read the service refuses does not fail the close`() {
        val store = RecordingCookieStore()
        val service =
            StubService { command ->
                when (command) {
                    "cookies.list" -> """{"status":"error","message":"nope"}"""
                    "browser.close" -> """{"status":"ok","closed":["tab-1"]}"""
                    else -> OPEN_REPLY
                }
            }

        val browser = WebViewBrowser(service.client, store)
        browser.open("https://example.com/")

        assertEquals(listOf("tab-1"), browser.close("tab-1"))
    }

    @Test
    fun `a store that cannot be read still opens the page`() {
        val failing =
            object : WebViewCookieStore {
                override fun get(url: HttpUrl): List<Cookie> = throw IllegalStateException("no store")

                override fun addAll(cookies: List<Cookie>) = Unit
            }
        val service = StubService { command -> if (command == "browser.open") OPEN_REPLY else OK_REPLY }

        // A caller that cannot read the app's cookies gets a browser that starts signed out, which
        // is what it would have had before the bridge existed, rather than a refused open.
        val tab = WebViewBrowser(service.client, failing).open("https://example.com/")

        assertEquals("tab-1", tab.id)
    }

    @Test
    fun `a harvested cookie is readable through the store the app's own requests use`() {
        // The bridge writes through an interface so it can be exercised without a running app, but
        // the implementation behind it is what the app's requests read, so it is checked once here.
        val store = AppCookieStore()
        val mapped =
            WebViewCookies.fromService(
                listOf(BrowserCookie(name = "session", value = "from-browser", domain = ".example.com")),
            )
        store.addAll(mapped)

        val sent = store.get("https://example.com/".toHttpUrl())
        assertTrue(
            sent.any { it.name == "session" && it.value == "from-browser" },
            "the app's store must return what the browser reported, and held ${sent.size} cookie(s)",
        )
    }

    @Test
    fun `the tabs the browser reports are read back with their titles`() {
        val store = RecordingCookieStore()
        val service =
            StubService { command ->
                if (command == "browser.list") {
                    """{"status":"ok","tabs":[{"id":"tab-7","url":"https://example.org/","title":"Org","status":200}]}"""
                } else {
                    OK_REPLY
                }
            }

        val tabs = WebViewBrowser(service.client, store).list()

        assertEquals(1, tabs.size)
        assertEquals("tab-7", tabs.single().id)
        assertEquals("https://example.org/", tabs.single().url)
        assertEquals("Org", tabs.single().title)
        assertEquals("browser.list", Regex("\"cmd\"\\s*:\\s*\"([^\"]+)\"").find(service.bodyFor("browser.list"))?.groupValues?.get(1))
    }
}

/** The cookie mapping itself, in both directions. */
class WebViewCookieMappingTest {
    @Test
    fun `a host only cookie is sent with a bare domain and a domain cookie keeps its dot`() {
        val hostOnly = WebViewCookies.toService(listOf(appCookie("a", "1", "example.com"))).single()
        assertEquals("example.com", hostOnly.domain)
        assertEquals("a", hostOnly.name)
        assertEquals("/", hostOnly.path)

        val coversSubdomains = WebViewCookies.toService(listOf(appCookie("b", "2", "example.com", hostOnly = false))).single()
        assertEquals(".example.com", coversSubdomains.domain)
    }

    @Test
    fun `a session cookie is stored as lasting rather than as already expired`() {
        // The service reports a session cookie with no expiry, and the app's model has to keep it:
        // reading that as expired would drop exactly the login cookies this bridge exists for. The
        // embedded browser's flush keeps one the same way, with an expiry beyond any date.
        val absent = WebViewCookies.fromService(listOf(BrowserCookie(name = "s", value = "v", domain = ".example.com"))).single()
        assertTrue(absent.expiresAt > sessionFarFutureThreshold(), "a session cookie must not read as expired")

        val nonPositive =
            WebViewCookies
                .fromService(listOf(BrowserCookie(name = "s", value = "v", domain = ".example.com", expires = -1.0)))
                .single()
        assertTrue(
            nonPositive.expiresAt > sessionFarFutureThreshold(),
            "a non-positive expiry is the service's session cookie, not a date in the past",
        )
    }

    @Test
    fun `a reported expiry is kept as its date and an absent one is not`() {
        val dated =
            WebViewCookies
                .fromService(
                    listOf(BrowserCookie(name = "s", value = "v", domain = "example.com", expires = 1_700_000_000.0)),
                ).single()
        assertEquals(1_700_000_000_000L, dated.expiresAt)

        val session = WebViewCookies.fromService(listOf(BrowserCookie(name = "s", value = "v", domain = "example.com"))).single()
        assertTrue(session.expiresAt > dated.expiresAt, "a session cookie must outlive any reported date")
    }

    @Test
    fun `a reported expiry is stored in milliseconds`() {
        val cookie =
            WebViewCookies
                .fromService(listOf(BrowserCookie(name = "s", value = "v", domain = "example.com", expires = 1_700_000_000.0)))
                .single()
        assertEquals(1_700_000_000_000L, cookie.expiresAt)
    }

    @Test
    fun `a leading dot marks a cookie that covers subdomains`() {
        val domainCookie =
            WebViewCookies.fromService(listOf(BrowserCookie(name = "s", value = "v", domain = ".example.com"))).single()
        assertFalse(domainCookie.hostOnly, "a dot in the reported domain means subdomains are covered")
        assertEquals("example.com", domainCookie.domain)

        val hostOnly =
            WebViewCookies.fromService(listOf(BrowserCookie(name = "s", value = "v", domain = "example.com"))).single()
        assertTrue(hostOnly.hostOnly, "a bare domain means that host alone")
    }

    @Test
    fun `a path without a leading slash is given one and an absent one becomes the root`() {
        val relative =
            WebViewCookies
                .fromService(listOf(BrowserCookie(name = "s", value = "v", domain = "example.com", path = "sub")))
                .single()
        assertEquals("/sub", relative.path)

        val absent =
            WebViewCookies.fromService(listOf(BrowserCookie(name = "s", value = "v", domain = "example.com"))).single()
        assertEquals("/", absent.path)
    }

    @Test
    fun `the flags the app's model keeps are kept`() {
        val cookie =
            WebViewCookies
                .fromService(
                    listOf(
                        BrowserCookie(
                            name = "s",
                            value = "v",
                            domain = "example.com",
                            secure = true,
                            httpOnly = true,
                        ),
                    ),
                ).single()
        assertTrue(cookie.secure)
        assertTrue(cookie.httpOnly)
    }

    @Test
    fun `a cookie without a usable domain is skipped`() {
        val mapped =
            WebViewCookies.fromService(
                listOf(
                    BrowserCookie(name = "a", value = "1"),
                    BrowserCookie(name = "b", value = "2", domain = "."),
                    BrowserCookie(name = "c", value = "3", domain = "example.com"),
                ),
            )
        assertEquals(listOf("c"), mapped.map { it.name })
    }

    @Test
    fun `a cookie the model refuses is skipped without costing the others`() {
        val mapped =
            WebViewCookies.fromService(
                listOf(
                    // A domain the app's cookie model refuses, which must not lose the cookie next
                    // to it: this runs on the path of an open that has already succeeded.
                    BrowserCookie(name = "broken", value = "v", domain = "not a domain"),
                    BrowserCookie(name = "good", value = "v", domain = "example.com"),
                ),
            )
        assertEquals(listOf("good"), mapped.map { it.name })
    }
}
