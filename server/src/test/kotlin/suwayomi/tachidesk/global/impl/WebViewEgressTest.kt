package suwayomi.tachidesk.global.impl

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WebViewEgressTest {
    @TempDir
    lateinit var rules: Path

    @Test
    fun `apex and subdomain follow the current rule assignment`() {
        put("fallback-exclude.txt", "sample.example.test\n*.sample.example.test\n")
        put("upstream-1.txt", "other.example.test\n")
        put("upstream-2.txt", "sample.example.test\n*.sample.example.test\n")
        names()
        assertEquals("exit-02", WebViewEgress.resolve("https://sample.example.test/series/title", rules))
        assertEquals("exit-02", WebViewEgress.resolve("https://reader.sample.example.test/chapter", rules))
        assertEquals("exit-03", WebViewEgress.resolve("https://elsewhere.example.test/", rules))
        assertEquals("exit-01", WebViewEgress.resolve("https://other.example.test/", rules))
        assertEquals("exit-03", WebViewEgress.resolve("https://not-sample.example.test/", rules))
    }

    @Test
    fun `internationalized hostname selects its punycode rule`() {
        put("fallback-exclude.txt", "xn--bcher-kva.example.test\n")
        put("upstream-2.txt", "xn--bcher-kva.example.test\n")
        names()
        assertEquals("exit-02", WebViewEgress.resolve("https://bücher.example.test/", rules))
    }

    @Test
    fun `direct route remains on the routing hop`() {
        put("fallback-exclude.txt", "direct.example.test\n")
        put("direct-hosts.txt", "direct.example.test\n")
        names()
        assertNull(WebViewEgress.resolve("https://direct.example.test/", rules))
        assertNull(WebViewEgress.resolve("about:blank", rules))
    }

    @Test
    fun `missing or ambiguous assignment fails closed`() {
        put("fallback-exclude.txt", "site.example.test\n")
        put("upstream-1.txt", "site.example.test\n")
        put("upstream-2.txt", "site.example.test\n")
        names()
        assertFailsWith<IOException> { WebViewEgress.resolve("https://site.example.test/", rules) }
        put("upstream-2.txt", "")
        put("webview-egress-names.json", "{}")
        assertFailsWith<IOException> { WebViewEgress.resolve("https://site.example.test/", rules) }
    }

    private fun names() {
        put("webview-egress-names.json", """{"upstream-1":"exit-01","upstream-2":"exit-02","fallback":"exit-03"}""")
    }

    private fun put(
        name: String,
        entries: String,
    ) {
        Files.writeString(rules.resolve(name), entries)
    }
}
