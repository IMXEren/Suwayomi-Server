package suwayomi.tachidesk.server.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.network.interceptor.CFClearance
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The answer the solver gives when it did what it was asked. */
private val SOLVED =
    """
    {
      "solution": {
        "url": "https://example.com/",
        "status": 200,
        "cookies": [{"name": "cf_clearance", "value": "cleared", "domain": ".example.com"}],
        "userAgent": "test"
      },
      "status": "ok",
      "message": "",
      "startTimestamp": 0,
      "endTimestamp": 0,
      "version": "prowl/test"
    }
    """.trimIndent()

/** The refusal the solver gives for a session the egress it names is not the one it was bound to. */
private val SESSION_BOUND_ELSEWHERE =
    """
    {
      "status": "error",
      "message": "session suwayomi is bound to a different egress",
      "startTimestamp": 0,
      "endTimestamp": 0,
      "version": "prowl/test"
    }
    """.trimIndent()

/** The per-request proxy the app's own SOCKS settings produce. */
private const val PROXIED = "\"proxy\":{\"url\":\"socks5://127.0.0.1:1080\"}"

/**
 * What a solve sends when the app's own SOCKS proxy is the egress.
 *
 * The proxy and the session are separate settings that travel on one request, so only an answer
 * shaped like the solver's own shows what was asked.
 */
class SolverProxyHandoffTest : ApplicationTest() {
    @Test
    fun `a proxied solve carries the session and the proxy together`() =
        solveAgainst(SOLVED) { bodies ->
            val response =
                CFClearance.resolveWithFlareSolver(Request.Builder().url("https://example.com/").build(), true)

            assertTrue(response.solution.cookies.any { it.name == "cf_clearance" })
            assertEquals(1, bodies.size)
            assertTrue("\"session\":\"suwayomi\"" in bodies.single(), bodies.single())
            assertTrue("\"session_ttl_minutes\":30" in bodies.single(), bodies.single())
            assertTrue(PROXIED in bodies.single(), bodies.single())
        }

    @Test
    fun `a solver that refuses the session fails loudly instead of retrying without it`() =
        solveAgainst(SESSION_BOUND_ELSEWHERE) { bodies ->
            val error =
                assertFailsWith<IOException> {
                    CFClearance.resolveWithFlareSolver(Request.Builder().url("https://example.com/").build(), true)
                }

            assertEquals(1, bodies.size, "a refused session must fail rather than be retried without it")
            // The session and the proxy still travel together, so the refusal names a genuine
            // mismatch rather than a session the app dropped of its own accord.
            assertTrue("\"session\":\"suwayomi\"" in bodies.single(), bodies.single())
            assertTrue(PROXIED in bodies.single(), bodies.single())
            val message = error.message.orEmpty()
            assertTrue("bound to a different egress" in message, message)
            assertTrue("server.socksProxyHost/server.socksProxyPort" in message, message)
            assertTrue("server.flareSolverrSessionName" in message, message)
        }

    /**
     * Run one solve against a stub that always answers [reply], with the SOCKS proxy and the session
     * configured, and hand the request bodies it was sent to [block].
     */
    private fun solveAgainst(
        reply: String,
        block: suspend (List<String>) -> Unit,
    ) {
        val bodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1") { exchange ->
            val body = exchange.requestBody.use { it.readBytes().decodeToString() }
            bodies += body
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        val addressed = serverConfig.flareSolverrUrl.value
        val enabled = serverConfig.socksProxyEnabled.value
        val version = serverConfig.socksProxyVersion.value
        val host = serverConfig.socksProxyHost.value
        val port = serverConfig.socksProxyPort.value
        val session = serverConfig.flareSolverrSessionName.value
        val ttl = serverConfig.flareSolverrSessionTtl.value
        try {
            serverConfig.flareSolverrUrl.value = "http://127.0.0.1:${server.address.port}"
            serverConfig.socksProxyEnabled.value = true
            serverConfig.socksProxyVersion.value = 5
            serverConfig.socksProxyHost.value = "127.0.0.1"
            serverConfig.socksProxyPort.value = "1080"
            serverConfig.flareSolverrSessionName.value = "suwayomi"
            serverConfig.flareSolverrSessionTtl.value = 30

            runBlocking { block(bodies) }
        } finally {
            server.stop(0)
            serverConfig.flareSolverrUrl.value = addressed
            serverConfig.socksProxyEnabled.value = enabled
            serverConfig.socksProxyVersion.value = version
            serverConfig.socksProxyHost.value = host
            serverConfig.socksProxyPort.value = port
            serverConfig.flareSolverrSessionName.value = session
            serverConfig.flareSolverrSessionTtl.value = ttl
        }
    }
}
