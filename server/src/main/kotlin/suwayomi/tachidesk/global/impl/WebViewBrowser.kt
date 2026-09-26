package suwayomi.tachidesk.global.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.util.asDirectClient
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * A tab open in the external browser service, as reported back to a caller.
 */
data class WebViewTab(
    val id: String,
    val url: String,
    val title: String,
    val status: Int,
)

/**
 * Client for the browser service's interactive commands.
 *
 * A tab stays open on the display the VNC endpoint serves and shares a profile with the service's
 * ordinary fetches, so a clearance or a login earned in the browser applies to the app's own
 * requests. Session state is shared in both directions, as the embedded WebView has always done.
 *
 * Every call is bounded by the web view's own timeout, and a call that outlives it is reported
 * rather than left to hang.
 */
internal open class WebViewBrowser(
    private val client: OkHttpClient = defaultClient(),
    private val cookies: WebViewCookieStore = AppCookieStore(),
) {
    private val logger = KotlinLogging.logger {}

    /** The whole profile the browser last reported, so an unchanged one is not written again. */
    @Volatile
    private var lastProfileCookies: Set<BrowserCookie>? = null

    open fun open(
        url: String,
        timeoutMillis: Int = interactiveTimeoutMillis(),
    ): WebViewTab {
        val accepted = validateWebViewUrl(url)
        val seeded = seedCookies(accepted)
        val body =
            json.encodeToString(
                BrowserOpenRequest(
                    cmd = OPEN_COMMAND,
                    url = accepted,
                    maxTimeout = timeoutMillis,
                    cookies = seeded.takeIf { it.isNotEmpty() },
                ),
            )
        val response = post(body)
        if (response.status != "ok") {
            throw IOException(response.message.ifBlank { "The browser service refused to open the page" })
        }
        val tab = response.tab ?: throw IOException("The browser service did not report a tab")
        // Carry back what the browser holds for the page it just opened, so a session established
        // there is the one the app's own requests use.
        harvest(tab.url.ifBlank { accepted })
        return WebViewTab(id = tab.id, url = tab.url, title = tab.title, status = tab.status)
    }

    open fun close(tab: String?): List<String> {
        val named = tab?.let { validateTabId(it) }
        val body = json.encodeToString(BrowserCloseRequest(cmd = CLOSE_COMMAND, tab = named))
        val response = post(body)
        if (response.status != "ok") {
            throw IOException(response.message.ifBlank { "The browser service refused to close the tab" })
        }
        val closed = response.closed.orEmpty()
        // The whole profile is read on the way out, matching the embedded browser's flush: a login
        // that navigated across domains sets cookies outside the page the tab ended on.
        harvest(null)
        return closed
    }

    /** List tabs; only a named [tab] is kept alive and has its cookies synced. */
    open fun list(tab: String? = null): List<WebViewTab> {
        val named = tab?.let { validateTabId(it) }
        val response = post(json.encodeToString(BrowserListRequest(cmd = LIST_COMMAND, tab = named)))
        if (response.status != "ok") {
            throw IOException(response.message.ifBlank { "The browser service refused to list its tabs" })
        }
        val tabs = response.tabs.orEmpty()
        if (named != null && tabs.any { it.id == named }) harvest(null, skipUnchanged = true)
        return tabs.map { WebViewTab(id = it.id, url = it.url, title = it.title, status = it.status) }
    }

    /**
     * The app's cookies for [url], in the shape the service installs before it navigates.
     *
     * A store that cannot be read is not a reason to refuse the open: the browser works without a
     * seeded session, it just starts out signed out.
     */
    private fun seedCookies(url: String): List<BrowserCookie> =
        try {
            val origin = url.toHttpUrlOrNull() ?: return emptyList()
            WebViewCookies.toService(cookies.get(origin))
        } catch (e: Exception) {
            logger.warn(e) { "Reading the app's cookies failed, opening without a seeded session" }
            emptyList()
        }

    /** Read browser cookies into the app store; avoid repeated writes during tab polling. */
    private fun harvest(
        url: String?,
        skipUnchanged: Boolean = false,
    ) {
        try {
            val reported = requestCookies(url)
            if (reported.isEmpty()) {
                if (skipUnchanged) lastProfileCookies = emptySet()
                return
            }
            val profile = if (skipUnchanged) reported.toSet() else null
            if (profile != null && profile == lastProfileCookies) return
            val mapped = WebViewCookies.fromService(reported)
            cookies.addAll(mapped)
            if (profile != null) lastProfileCookies = profile
            if (mapped.size < reported.size) {
                logger.warn { "Stored ${mapped.size} of ${reported.size} cookies the browser reported" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Reading the browser's cookies failed, the browser command is unaffected" }
        }
    }

    private fun requestCookies(url: String?): List<BrowserCookie> {
        val response = post(json.encodeToString(CookiesListRequest(cmd = COOKIES_LIST_COMMAND, url = url)))
        if (response.status != "ok") {
            throw IOException(response.message.ifBlank { "The browser service refused to report its cookies" })
        }
        return response.cookies.orEmpty()
    }

    private fun post(body: String): BrowserResponse {
        val endpoint = serverConfig.flareSolverrUrl.value.removeSuffix("/") + "/v1"
        val request =
            Request
                .Builder()
                .url(endpoint)
                .post(body.toRequestBody(jsonMediaType))
                .build()

        val response =
            try {
                client.newCall(request).execute()
            } catch (_: InterruptedIOException) {
                throw IOException(
                    "The browser service did not answer within ${interactiveTimeoutSeconds()} seconds " +
                        "(server.webViewOpenTimeout)",
                )
            }

        response.use { answered ->
            if (!answered.isSuccessful) {
                throw IOException("The browser service answered with HTTP ${answered.code}")
            }
            val raw = answered.body?.string().orEmpty()
            return try {
                json.decodeFromString<BrowserResponse>(raw)
            } catch (_: Exception) {
                throw IOException("The browser service answered with an unreadable body")
            }
        }
    }

    companion object {
        private const val OPEN_COMMAND = "browser.open"
        private const val CLOSE_COMMAND = "browser.close"
        private const val LIST_COMMAND = "browser.list"
        private const val COOKIES_LIST_COMMAND = "cookies.list"

        private val json = Json { ignoreUnknownKeys = true }
        private val jsonMediaType = "application/json".toMediaType()
        private val tabIdPattern = Regex("^[A-Za-z0-9._:-]{1,64}$")

        /**
         * How long an interactive call may take, in seconds.
         *
         * The web view's own budget, not the solver's: an open is a browser start, a navigation and
         * a solve in one call, which fits a wider margin than a fetch does.
         */
        internal fun interactiveTimeoutSeconds(): Int = serverConfig.webViewOpenTimeout.value

        /** The same timeout as the request is told to honour, in milliseconds. */
        internal fun interactiveTimeoutMillis(): Int = interactiveTimeoutSeconds().seconds.inWholeMilliseconds.toInt()

        /** Bound a client to the interactive timeout, with the margin the solver calls use. */
        internal fun timedClient(base: OkHttpClient): OkHttpClient {
            val timeout = interactiveTimeoutSeconds().seconds
            return base
                .newBuilder()
                .callTimeout(timeout.plus(10.seconds).toJavaDuration())
                .readTimeout(timeout.plus(5.seconds).toJavaDuration())
                .build()
        }

        /**
         * Accept only an absolute http or https url, because the server forwards it to a browser it
         * controls.
         *
         * @throws IllegalArgumentException when the url is unusable.
         */
        fun validateWebViewUrl(url: String): String {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) {
                throw IllegalArgumentException("A url is required")
            }
            val parsed =
                try {
                    URI(trimmed)
                } catch (_: Exception) {
                    throw IllegalArgumentException("The url is not valid")
                }
            val scheme = parsed.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                throw IllegalArgumentException("Only http and https urls can be opened")
            }
            if (parsed.host.isNullOrBlank()) {
                throw IllegalArgumentException("The url must name a host")
            }
            return trimmed
        }

        /**
         * Accept only a tab id shaped like the ones the browser service issues.
         *
         * @throws IllegalArgumentException when the id is unusable.
         */
        fun validateTabId(tab: String): String {
            val trimmed = tab.trim()
            if (!tabIdPattern.matches(trimmed)) {
                throw IllegalArgumentException("The tab id is not valid")
            }
            return trimmed
        }

        private fun defaultClient(): OkHttpClient =
            timedClient(
                OkHttpClient
                    .Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build()
                    // The service is reached on the internal network, which the server's own SOCKS
                    // proxy setting would otherwise send to the upstream proxy instead.
                    .asDirectClient(),
            )
    }
}

@Serializable
private data class BrowserOpenRequest(
    // The command name carries no default on purpose: this serializer omits fields that have one,
    // and a request without its command is refused by the service as a malformed one.
    val cmd: String,
    val url: String,
    val maxTimeout: Int,
    val cookies: List<BrowserCookie>? = null,
)

@Serializable
private data class BrowserCloseRequest(
    val cmd: String,
    val tab: String? = null,
)

@Serializable
private data class BrowserListRequest(
    val cmd: String,
    val tab: String? = null,
)

@Serializable
private data class CookiesListRequest(
    val cmd: String,
    val url: String? = null,
)

@Serializable
private data class BrowserResponse(
    val status: String = "",
    val message: String = "",
    val tab: BrowserTab? = null,
    val tabs: List<BrowserTab>? = null,
    val closed: List<String>? = null,
    val cookies: List<BrowserCookie>? = null,
)

@Serializable
private data class BrowserTab(
    @SerialName("id") val id: String = "",
    val url: String = "",
    val title: String = "",
    val status: Int = 0,
)
