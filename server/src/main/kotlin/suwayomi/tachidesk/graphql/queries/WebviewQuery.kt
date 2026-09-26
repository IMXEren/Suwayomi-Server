package suwayomi.tachidesk.graphql.queries

import suwayomi.tachidesk.global.impl.WebViewBrowser
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

class WebviewQuery internal constructor(
    private val browser: WebViewBrowser,
) {
    constructor() : this(WebViewBrowser())

    /** A page open in the external browser. */
    data class WebViewTabType(
        val id: String,
        val url: String,
        val title: String,
    )

    /**
     * The pages the external browser has open.
     *
     * [tab] names the tab the caller is displaying: only that tab's idle countdown is renewed and
     * only that tab's profile is read back, so a forgotten tab still times out. Without [tab] the
     * listing is read-only.
     */
    @RequireAuth
    fun webViewTabs(tab: String? = null): CompletableFuture<List<WebViewTabType>> =
        future {
            browser
                .list(tab?.let { WebViewBrowser.validateTabId(it) })
                .map { WebViewTabType(id = it.id, url = it.url, title = it.title) }
        }
}
