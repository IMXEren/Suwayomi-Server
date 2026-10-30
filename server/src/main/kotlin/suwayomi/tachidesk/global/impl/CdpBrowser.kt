package suwayomi.tachidesk.global.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.hildan.chrome.devtools.ChromeDP
import org.hildan.chrome.devtools.sessions.BrowserSession
import org.hildan.chrome.devtools.sessions.PageSession
import org.hildan.chrome.devtools.sessions.asPageSession
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.util.asDirectClient
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** A single reserved Prowl page driven over the DevTools protocol on one serialized coroutine. */
internal class CdpBrowser(
    private val onReady: suspend CoroutineScope.(PageSession) -> Unit,
    private val onClosing: suspend (PageSession) -> Unit,
    private val onError: (Exception) -> Unit,
    private val onTick: suspend (PageSession) -> Unit,
    private val endpoint: String = serverConfig.flareSolverrUrl.value.removeSuffix("/"),
    private val token: String = Files.readString(Path.of(requireNotNull(System.getenv("WEB_VIEW_CDP_TOKEN_FILE")))).trim(),
    private val client: OkHttpClient =
        OkHttpClient()
            .asDirectClient()
            .newBuilder()
            .callTimeout(10.seconds.toJavaDuration())
            .build(),
) : AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private val tasks = Channel<suspend (PageSession) -> Unit>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private val dispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Prowl-CDP-WebView").apply { isDaemon = true } }
    private val scope = CoroutineScope(dispatcher.asCoroutineDispatcher() + SupervisorJob())
    private var loop: Job? = null

    @Volatile
    var egress: String? = null
        private set

    fun submit(
        initialUrl: String? = null,
        block: suspend (PageSession) -> Unit,
    ) {
        check(!closed.get()) { "WebView has been destroyed" }
        if (initialUrl != null) start(initialUrl)
        tasks.trySend(block).getOrThrow()
    }

    @Synchronized
    private fun start(url: String) {
        if (loop != null || closed.get()) return
        egress = WebViewEgress.forUrl(url)
        loop = scope.launch { run(egress) }
    }

    private suspend fun run(egress: String?) {
        var tab: String? = null
        var browser: BrowserSession? = null
        var page: PageSession? = null
        var stage = "reserve"
        try {
            val (reservedTab, target) = reserve(egress)
            tab = reservedTab
            if (closed.get()) return
            stage = "connect"
            browser =
                ChromeDP.connect(
                    endpoint.replaceFirst(Regex("^http"), "ws") + "/cdp/reservations/$tab/socket",
                    configureClient = {
                        defaultRequest { header("Authorization", "Bearer $token") }
                    },
                )
            stage = "attach"
            page = attach(browser, target)
            stage = "ready"
            scope.onReady(page)
            var lastTickNanos = System.nanoTime()
            while (!closed.get() && (loop?.isActive ?: false)) {
                val task = tasks.tryReceive().getOrNull()
                if (task != null) {
                    stage = "task"
                    task(page)
                } else {
                    delay(TICK_MILLIS.milliseconds)
                }
                if (System.nanoTime() - lastTickNanos >= TICK_MILLIS * 1_000_000) {
                    stage = "tick"
                    onTick(page)
                    lastTickNanos = System.nanoTime()
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                logger.warn { "CDP WebView stopped at $stage (${e.javaClass.simpleName})" }
                onError(e)
            }
        } finally {
            withContext(NonCancellable) {
                page?.let { cleanup("Browser cookie sync") { onClosing(it) } }
                browser?.let { cleanup("Closing the CDP session") { it.close() } }
                tab?.let {
                    cleanup("Releasing the CDP reservation") { release(it) }
                }
                closed.set(true)
                tasks.close()
                scope.cancel()
                dispatcher.shutdown()
            }
        }
    }

    private suspend fun cleanup(
        operation: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: Exception) {
            logger.warn { "$operation failed (${e.javaClass.simpleName})" }
        }
    }

    private suspend fun attach(
        browser: BrowserSession,
        target: String,
    ): PageSession = browser.attachToTarget(target).asPageSession()

    private fun reserve(egress: String?): Pair<String, String> {
        val body = buildJsonObject { if (egress != null) put("egress", egress) }.toString()
        val request =
            Request
                .Builder()
                .url("$endpoint/cdp/reservations")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Browser reservation failed with HTTP ${response.code}")
            val fields = Json.parseToJsonElement(response.body.string()).jsonObject
            return fields.getValue("tab").jsonPrimitive.content to fields.getValue("target").jsonPrimitive.content
        }
    }

    private fun release(tab: String) {
        val request =
            Request
                .Builder()
                .url("$endpoint/cdp/reservations/$tab")
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
        client.newCall(request).execute().close()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            tasks.close()
            scope.cancel()
            dispatcher.shutdown()
        }
    }

    private companion object {
        const val TICK_MILLIS = 50L
    }
}
