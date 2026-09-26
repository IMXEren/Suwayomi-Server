package suwayomi.tachidesk.global.impl

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.Serializable
import okhttp3.Cookie
import okhttp3.HttpUrl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A cookie in the shape the browser service reports and accepts.
 *
 * The field names are the ones the service's fetch replies already use, so a caller reads one
 * shape whether it fetched a page or asked for the profile's cookies. Only [name], [value],
 * [domain] and [path] have an effect when a cookie is sent back with an open, which is the
 * direction that seeds the browser from the app's own store.
 */
@Serializable
internal data class BrowserCookie(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null,
    val expires: Double? = null,
    val secure: Boolean? = null,
    val httpOnly: Boolean? = null,
    val sameSite: String? = null,
)

/**
 * The app's own cookie store, narrowed to what the browser bridge needs.
 *
 * An interface rather than the store itself, because the store belongs to the network layer and
 * the bridge has to be exercisable without a running app.
 */
internal interface WebViewCookieStore {
    /** The cookies the app would send to [url]. */
    fun get(url: HttpUrl): List<Cookie>

    /** Store cookies the browser reported, as if a response had carried them. */
    fun addAll(cookies: List<Cookie>)
}

/** The store the app's own source requests read. */
internal class AppCookieStore : WebViewCookieStore {
    private val store by lazy { Injekt.get<NetworkHelper>().cookieStore }

    override fun get(url: HttpUrl): List<Cookie> = store.get(url)

    override fun addAll(cookies: List<Cookie>) {
        // The store keys cookies by their own domain and takes a url only for the CookieJar
        // contract, so each domain is given the url that domain is addressed at, which is what
        // the embedded browser's flush does.
        cookies.groupBy { it.domain }.forEach { (domain, group) ->
            val url =
                try {
                    HttpUrl
                        .Builder()
                        .scheme("http")
                        .host(domain)
                        .build()
                } catch (_: IllegalArgumentException) {
                    // A domain the app's model accepted but a url cannot be built from: the
                    // cookies of that group are dropped, which the caller reports.
                    return@forEach
                }
            store.addAll(url, group)
        }
    }
}

/**
 * Translation between the app's cookies and the shape the browser service speaks.
 *
 * The embedded WebView has the same bridge in its own terms: it pushes the app's cookies in when
 * it starts and reads the browser's cookies back as they change. These mappings are what the
 * external browser needs instead, and the reading side follows the same rules so that a cookie
 * means the same thing whichever provider is selected.
 */
internal object WebViewCookies {
    /**
     * Map the app's cookies into what the service accepts.
     *
     * A cookie that only applies to its own host is sent with its bare domain, and one that covers
     * subdomains keeps the leading dot that expresses that, which is how a browser reads a domain
     * cookie. Expiry and the flags are not sent: the service installs name, value, domain and path
     * and ignores the rest, so sending them would suggest they are honoured.
     */
    fun toService(cookies: List<Cookie>): List<BrowserCookie> =
        cookies.map { cookie ->
            BrowserCookie(
                name = cookie.name,
                value = cookie.value,
                domain = if (cookie.hostOnly) cookie.domain else "." + cookie.domain,
                path = cookie.path,
            )
        }

    /**
     * Map cookies the service reported into the app's cookie model.
     *
     * The rules mirror the embedded browser's flush: the domain is stored without its leading dot
     * and a cookie that arrived without one is host-only; a session cookie, which the service
     * reports as a non-positive expiry or omits, becomes [Long.MAX_VALUE] so it is not dropped as
     * already expired, which would discard exactly the login cookies this bridge exists for; and a
     * path without a leading slash is given one. SameSite has no equivalent in the app's model and
     * is dropped.
     *
     * A cookie the model refuses is skipped rather than failing the caller. This runs on the path
     * of an open and a close, and one unusable cookie from a site must not cost the rest of the
     * session or the browser command itself.
     */
    fun fromService(cookies: List<BrowserCookie>): List<Cookie> =
        cookies.mapNotNull { cookie ->
            val reportedDomain = cookie.domain.orEmpty()
            val domain = reportedDomain.removePrefix(".")
            if (domain.isBlank()) return@mapNotNull null
            val reportedPath = cookie.path
            val path = if (reportedPath.isNullOrEmpty()) "/" else "/" + reportedPath.removePrefix("/")
            try {
                val builder =
                    Cookie
                        .Builder()
                        .name(cookie.name)
                        .value(cookie.value)
                        .path(path)
                        .domain(domain)
                val expires = cookie.expires
                if (expires != null && expires > 0) {
                    builder.expiresAt((expires * MILLIS_PER_SECOND).toLong())
                } else {
                    // A session cookie, which OkHttp keeps as one by saturating this to its own far
                    // future maximum, exactly as the embedded browser's flush behaves. Reading a
                    // session cookie as one that has already expired would drop it.
                    builder.expiresAt(Long.MAX_VALUE)
                }
                if (cookie.httpOnly == true) {
                    builder.httpOnly()
                }
                if (cookie.secure == true) {
                    builder.secure()
                }
                if (!reportedDomain.startsWith('.')) {
                    // A cookie reported without a leading dot belongs to that host alone, which is
                    // the distinction the app's model keeps and the embedded browser's flush keeps.
                    builder.hostOnlyDomain(domain)
                }
                builder.build()
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    private const val MILLIS_PER_SECOND = 1000.0
}
