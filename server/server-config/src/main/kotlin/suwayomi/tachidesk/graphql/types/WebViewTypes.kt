package suwayomi.tachidesk.graphql.types

/**
 * Which browser backs the WebView.
 */
enum class WebViewProvider {
    /**
     * The Chromium embedded in the server, streamed to the client as a rendered canvas.
     */
    CEF,

    /**
     * A browser running in the external browser service, shown through that service's web VNC
     * endpoint. It is the same browser the service solves challenges with, so a page reached
     * through it is browsable even where the embedded browser cannot get past a challenge.
     */
    PROWL_VNC,
    ;

    companion object {
        fun from(value: String): WebViewProvider = entries.find { it.name.equals(value, ignoreCase = true) } ?: CEF
    }
}
