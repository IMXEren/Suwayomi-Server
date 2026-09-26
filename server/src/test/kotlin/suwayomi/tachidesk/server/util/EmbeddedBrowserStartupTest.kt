package suwayomi.tachidesk.server.util

import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.types.WebViewProvider
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether the embedded browser is initialized at all.
 *
 * CEF is a browser process of its own, so starting it while the external provider serves the WebView
 * spends that memory for nothing. The decision reads the two settings, and takes them as parameters
 * so that it can be checked without loading the very thing it is deciding about.
 */
class EmbeddedBrowserStartupTest : ApplicationTest() {
    @Test
    fun `the embedded browser is initialized only when it is enabled and is the provider`() {
        assertNull(
            embeddedBrowserSkipReason(kcefEnabled = true, provider = WebViewProvider.CEF),
            "the embedded provider with CEF enabled is the case that must initialize",
        )
        assertTrue(
            embeddedBrowserSkipReason(kcefEnabled = true, provider = WebViewProvider.PROWL_VNC) != null,
            "the external provider must not initialize the embedded browser",
        )
        assertEquals("CEF is disabled", embeddedBrowserSkipReason(kcefEnabled = false, provider = WebViewProvider.CEF))
        assertEquals(
            "CEF is disabled",
            embeddedBrowserSkipReason(kcefEnabled = false, provider = WebViewProvider.PROWL_VNC),
            "a disabled embedded browser is reported the way it always was",
        )
    }

    @Test
    fun `the reason names the provider and says that a restart is what changes it`() {
        val reason =
            embeddedBrowserSkipReason(kcefEnabled = true, provider = WebViewProvider.PROWL_VNC)
                .orEmpty()
        assertTrue(reason.contains("PROWL_VNC"), reason)
        assertTrue(reason.contains("restart"), reason)
    }

    @Test
    fun `the decision is what the running configuration selects`() {
        val configured = serverConfig.webViewProvider.value
        try {
            // Reading the settings is the part that cannot be exercised by passing values in, and it
            // is the part a future change could break silently.
            serverConfig.webViewProvider.value = WebViewProvider.PROWL_VNC
            val reason = embeddedBrowserSkipReason()
            assertTrue(reason != null && reason.contains("PROWL_VNC"), reason.orEmpty())

            serverConfig.webViewProvider.value = WebViewProvider.CEF
            assertEquals(
                if (serverConfig.kcefEnabled.value) null else "CEF is disabled",
                embeddedBrowserSkipReason(),
            )
        } finally {
            serverConfig.webViewProvider.value = configured
        }
    }
}
