package suwayomi.tachidesk.server.util

import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.types.WebViewProvider
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmbeddedBrowserStartupTest {
    @Test
    fun `Prowl CDP replaces extension CEF only when explicitly configured`() {
        assertNull(embeddedBrowserSkipReason(true, WebViewProvider.PROWL_VNC, false))
        assertEquals(
            "CEF is not initialized; extension WebViews use Prowl CDP",
            embeddedBrowserSkipReason(true, WebViewProvider.PROWL_VNC, true),
        )
        assertNull(embeddedBrowserSkipReason(true, WebViewProvider.CEF, true))
        assertEquals("CEF is disabled", embeddedBrowserSkipReason(false, WebViewProvider.PROWL_VNC, true))
    }
}
