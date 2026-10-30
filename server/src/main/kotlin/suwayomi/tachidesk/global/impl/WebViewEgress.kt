package suwayomi.tachidesk.global.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Select the named browser identity from the deployment's live destination rules. */
internal object WebViewEgress {
    private val ipBoundCookieNames = setOf("cf_clearance", "__cf_bm")
    private val rulesDir = System.getenv("WEB_VIEW_EGRESS_RULES_DIR")?.takeIf(String::isNotBlank)?.let(Path::of)

    val isConfigured: Boolean get() = rulesDir != null

    fun forUrl(url: String): String? = rulesDir?.let { resolve(url, it) }

    fun isIpBoundCookie(name: String): Boolean = name.lowercase() in ipBoundCookieNames

    internal fun resolve(
        url: String,
        rules: Path,
    ): String? {
        val host = url.toHttpUrlOrNull()?.host ?: return null
        if (!Files.isDirectory(rules)) throw IOException("WebView egress rules directory is unavailable")
        val files =
            Files.list(rules).use { entries ->
                entries.filter { it.fileName.toString().endsWith(".txt") }.toList()
            }
        val fallback =
            files
                .singleOrNull { it.fileName.toString().endsWith("-exclude.txt") }
                ?.fileName
                ?.toString()
                ?.removeSuffix("-exclude.txt")
                ?: throw IOException("WebView egress fallback rule is missing or ambiguous")
        val direct = rules.resolve("direct-hosts.txt")
        if (Files.exists(direct) && Files.readAllLines(direct).any { matches(it, host) }) return null
        val assigned =
            files
                .filterNot {
                    it.fileName.toString() in setOf("egress-watch.txt", "$fallback-exclude.txt") ||
                        it.fileName.toString().startsWith("direct-")
                }.filter { file ->
                    Files.readAllLines(file).any { matches(it, host) }
                }
        if (assigned.size > 1) throw IOException("WebView egress assignment is ambiguous")
        val ruleName =
            assigned
                .singleOrNull()
                ?.fileName
                ?.toString()
                ?.removeSuffix(".txt") ?: fallback
        val aliases = Json.parseToJsonElement(Files.readString(rules.resolve("webview-egress-names.json"))).jsonObject
        return aliases[ruleName]?.jsonPrimitive?.content
            ?: throw IOException("WebView egress assignment is not configured")
    }

    private fun matches(
        entry: String,
        host: String,
    ): Boolean {
        val pattern = entry.substringBefore('#').trim().lowercase()
        return pattern == host || (pattern.startsWith("*.") && host.endsWith(pattern.removePrefix("*")))
    }
}
