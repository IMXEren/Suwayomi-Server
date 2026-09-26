@file:Suppress("RedundantNullableReturnType", "unused")

package suwayomi.tachidesk.graphql.mutations

import suwayomi.tachidesk.global.impl.WebViewBrowser
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.JavalinSetup.future
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.CookieHandler
import java.util.concurrent.CompletableFuture
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively

@OptIn(ExperimentalPathApi::class)
class WebviewMutation internal constructor(
    private val browser: WebViewBrowser,
) {
    constructor() : this(WebViewBrowser())

    private val applicationDirs by lazy { Injekt.get<ApplicationDirs>() }

    data class ClearCookiesAndCacheInput(
        val clientMutationId: String? = null,
    )

    data class ClearCookiesAndCachePayload(
        val clientMutationId: String?,
    )

    @RequireAuth
    fun clearCookiesAndCache(input: ClearCookiesAndCacheInput? = null): ClearCookiesAndCachePayload {
        val cookieHandler = CookieHandler.getDefault() as java.net.CookieManager
        cookieHandler.cookieStore.removeAll()
        Path(applicationDirs.cacheDir).deleteRecursively()

        return ClearCookiesAndCachePayload(
            clientMutationId = input?.clientMutationId,
        )
    }

    data class OpenWebViewInput(
        val clientMutationId: String? = null,
        val url: String,
    )

    data class OpenWebViewPayload(
        val clientMutationId: String?,
        val tab: String,
        val url: String,
        val title: String,
        val status: Int,
    )

    /**
     * Open a page in the external browser service and leave it open.
     *
     * The tab is shown through the VNC endpoint, so a person watches and drives it there. Only an
     * absolute http or https url is accepted, because the server hands the url to a browser it
     * controls.
     */
    @RequireAuth
    fun openWebView(input: OpenWebViewInput): CompletableFuture<OpenWebViewPayload> =
        future {
            // Validate at the boundary as well, so a refused url never reaches the browser even if
            // the client changes.
            val url = WebViewBrowser.validateWebViewUrl(input.url)
            val tab = browser.open(url)

            OpenWebViewPayload(
                clientMutationId = input.clientMutationId,
                tab = tab.id,
                url = tab.url,
                title = tab.title,
                status = tab.status,
            )
        }

    data class CloseWebViewInput(
        val clientMutationId: String? = null,
        val tab: String? = null,
    )

    data class CloseWebViewPayload(
        val clientMutationId: String?,
        val closed: List<String>,
    )

    /** Close one tab, or every open one when no tab is named. */
    @RequireAuth
    fun closeWebView(input: CloseWebViewInput? = null): CompletableFuture<CloseWebViewPayload> =
        future {
            CloseWebViewPayload(
                clientMutationId = input?.clientMutationId,
                closed = browser.close(input?.tab?.let { WebViewBrowser.validateTabId(it) }),
            )
        }
}
