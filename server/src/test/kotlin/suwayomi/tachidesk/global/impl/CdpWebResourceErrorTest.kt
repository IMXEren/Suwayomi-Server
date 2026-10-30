package suwayomi.tachidesk.global.impl

import android.webkit.WebResourceErrorImpl
import android.webkit.WebResourceResponse
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CdpWebResourceErrorTest {
    @Test
    fun `modern WebView errors use a concrete Android-compatible resource error`() {
        val error = WebResourceErrorImpl(-1, "Navigation failed")
        assertEquals(-1, error.errorCode)
        assertEquals("Navigation failed", error.description)
    }

    @Test
    fun `three-argument resource responses allow empty data with HTTP 200`() {
        val response = WebResourceResponse(null, null, null)
        assertEquals(200, response.statusCode)
        assertEquals("OK", response.reasonPhrase)
        assertNull(response.data)
    }
}
