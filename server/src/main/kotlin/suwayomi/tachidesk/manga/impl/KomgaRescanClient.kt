package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Header Komga expects for API-key authentication. */
internal const val KOMGA_API_KEY_HEADER = "X-API-Key"

/** Reads at most this much of a response body; a diagnostic must never pull an unbounded payload. */
internal const val KOMGA_MAX_DIAGNOSTIC_BYTES = 8_192L

/** Persisted and logged reasons are bounded so a misbehaving endpoint cannot bloat the database. */
internal const val KOMGA_MAX_REASON_LENGTH = 1_024

private const val REDACTED = "[redacted]"

/**
 * Values at least this long can be located and replaced inside a larger token.
 *
 * A shorter value can not be localized safely (replacing a one character value would mangle unrelated
 * text), so it is handled by dropping whole tokens instead - see [redactKomgaValues].
 */
private const val MIN_SUBSTRING_REDACTABLE_LENGTH = 3

private val WHITESPACE = Regex("\\s+")

/**
 * Raised by the built-in publication listener while Komga is not usable.
 *
 * Publication must not be rolled back by a listener, so the outbox records this failure verbatim and
 * retries later. The message therefore stays bounded and never contains the configured base URL,
 * library id or API key - only what is missing.
 */
internal class KomgaNotConfiguredException(
    message: String,
) : Exception(message)

/**
 * The configured Komga target, already validated.
 *
 * [request] carries everything a scan needs plus the values that must never be echoed back, so a
 * diagnostic produced anywhere below this boundary is redacted by construction.
 */
internal sealed interface KomgaRescanConfiguration {
    data class Invalid(
        val reason: String,
    ) : KomgaRescanConfiguration

    data class Valid(
        val request: KomgaRescanRequest,
    ) : KomgaRescanConfiguration
}

/** Everything one scan needs. Deliberately has no reference to the intent row or to any state. */
internal data class KomgaRescanRequest(
    val scanUrl: String,
    /** sent as the API-key header; null when Komga is intentionally configured without one */
    val apiKey: String?,
    /** configured values that must never be persisted verbatim in a diagnostic */
    val redactions: List<String>,
)

/**
 * The outcome of one scan attempt, classified so the caller never has to interpret HTTP itself.
 *
 * The split is what lets a transient outage retry on its own while a wrong library id or a rejected
 * API key stops and stays visible instead of hammering Komga forever.
 */
internal sealed interface KomgaRescanOutcome {
    data object Success : KomgaRescanOutcome

    data class RetryableFailure(
        val reason: String,
    ) : KomgaRescanOutcome

    data class HardFailure(
        val reason: String,
    ) : KomgaRescanOutcome
}

/** The seam that isolates the Komga HTTP contract, so it can be adjusted after live verification. */
internal fun interface KomgaRescanClient {
    suspend fun rescan(request: KomgaRescanRequest): KomgaRescanOutcome
}

/**
 * Validates the configured Komga target and builds the scan request.
 *
 * A blank base URL, a blank library id or a base URL that is not an absolute http(s) URL with a host
 * is not configured: the caller must then never open a connection at all.
 */
internal fun komgaRescanConfiguration(
    baseUrl: String,
    apiKey: String,
    libraryId: String,
): KomgaRescanConfiguration {
    val trimmedBase = baseUrl.trim()
    val trimmedLibrary = libraryId.trim()
    val trimmedKey = apiKey.trim()

    if (trimmedBase.isBlank()) {
        return KomgaRescanConfiguration.Invalid("no Komga base URL is configured")
    }
    if (trimmedLibrary.isBlank()) {
        return KomgaRescanConfiguration.Invalid("no Komga library id is configured")
    }

    val base =
        trimmedBase.toHttpUrlOrNull()
            ?: return KomgaRescanConfiguration.Invalid("the configured Komga base URL is not an absolute http(s) URL")
    val scanUrl =
        komgaScanUrl(base, trimmedLibrary)
            ?: return KomgaRescanConfiguration.Invalid(
                "the configured Komga base URL must be http or https and must not carry credentials, a query or a fragment",
            )

    val redactions =
        buildList {
            add(scanUrl)
            add(base.toString())
            add(trimmedBase)
            add(base.host)
            // only a non-default port is a configured detail; the default is a protocol constant
            if (base.port != defaultPort(base.scheme)) {
                add(base.port.toString())
            }
            add(trimmedLibrary)
            add(encodedPathSegment(trimmedLibrary))
            add(trimmedKey)
        }.filter { it.isNotEmpty() }.distinct()

    return KomgaRescanConfiguration.Valid(
        KomgaRescanRequest(
            scanUrl = scanUrl,
            apiKey = trimmedKey.ifBlank { null },
            redactions = redactions,
        ),
    )
}

private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

/**
 * The scan endpoint of a configured library.
 *
 * The library id is appended as a single path segment, so it is percent-encoded and can never escape
 * into a different route, while an optional reverse-proxy base path of the configured URL is kept.
 * Returns null when the URL cannot serve as a Komga base at all.
 */
private fun komgaScanUrl(
    base: HttpUrl,
    libraryId: String,
): String? {
    if (base.scheme != "http" && base.scheme != "https") {
        return null
    }
    if (base.username.isNotEmpty() || base.password.isNotEmpty()) {
        return null
    }
    if (base.query != null || base.fragment != null) {
        return null
    }

    return base
        .newBuilder()
        .addPathSegments("api/v1/libraries")
        .addPathSegment(libraryId)
        .addPathSegment("scan")
        .build()
        .toString()
}

private fun encodedPathSegment(segment: String): String =
    URLEncoder
        .encode(segment, StandardCharsets.UTF_8)
        .replace("+", "%20")

/**
 * Replaces every configured value with a placeholder so no diagnostic can echo it back.
 *
 * A value long enough to be located is replaced wherever it appears, even inside a larger token. A
 * value that is too short to locate safely is still a credential or an identifier, so any token that
 * contains one is replaced whole. The guarantee is that no configured value of any nonblank length
 * survives redaction, including a one character API key or library id.
 */
internal fun redactKomgaValues(
    message: String,
    redactions: List<String>,
): String {
    val values = redactions.filter { it.isNotEmpty() }.distinct()
    if (values.isEmpty()) {
        return message
    }

    var result = message
    values
        .filter { it.length >= MIN_SUBSTRING_REDACTABLE_LENGTH }
        .forEach { value -> result = result.replace(value, REDACTED) }

    val shortValues = values.filter { it.length < MIN_SUBSTRING_REDACTABLE_LENGTH }
    if (shortValues.isEmpty()) {
        return result
    }

    return result
        .split(WHITESPACE)
        .filter { it.isNotEmpty() }
        .joinToString(" ") { token -> if (shortValues.any { token.contains(it) }) REDACTED else token }
}

/**
 * A single-line, bounded and redacted diagnostic.
 *
 * Control characters are flattened because the reason is persisted, logged and later exposed through
 * GraphQL; a value that silently contains line breaks would corrupt all three.
 */
internal fun komgaReason(
    message: String,
    redactions: List<String>,
): String =
    redactKomgaValues(message, redactions)
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(KOMGA_MAX_REASON_LENGTH)

/**
 * Maps an HTTP status to the reaction it deserves.
 *
 * Any 2xx means Komga accepted the scan request. A timeout, a rate limit and a server-side failure are
 * transient by nature, so they retry; everything else (a rejected API key, an unknown library) only
 * changes when the configuration or Komga itself changes, so it stops as a hard failure.
 */
internal fun classifyKomgaScanResponse(
    statusCode: Int,
    body: String?,
    redactions: List<String>,
): KomgaRescanOutcome {
    if (statusCode in 200..299) {
        return KomgaRescanOutcome.Success
    }

    val diagnostic =
        body
            ?.takeIf { it.isNotBlank() }
            ?.let { komgaReason(it, redactions) }
            ?.takeIf { it.isNotEmpty() }
            ?: "no response body"

    val reason = komgaReason("Komga answered $statusCode: $diagnostic", redactions)
    return if (statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599) {
        KomgaRescanOutcome.RetryableFailure(reason)
    } else {
        KomgaRescanOutcome.HardFailure(reason)
    }
}

/**
 * The real HTTP implementation.
 *
 * It reads at most [KOMGA_MAX_DIAGNOSTIC_BYTES] of the response, so a diagnostic can never pull an
 * unbounded body, and it is fully cancellation-aware: cancelling the worker cancels the in-flight
 * request instead of leaking it.
 */
internal class DefaultKomgaRescanClient(
    private val client: () -> OkHttpClient,
    private val timeoutSeconds: () -> Long,
) : KomgaRescanClient {
    override suspend fun rescan(request: KomgaRescanRequest): KomgaRescanOutcome {
        val timeout = timeoutSeconds().coerceAtLeast(1)
        val builder =
            Request
                .Builder()
                .url(request.scanUrl)
                .method("POST", ByteArray(0).toRequestBody(null, 0, 0))
        request.apiKey?.let { builder.header(KOMGA_API_KEY_HEADER, it) }

        val call = client().newCall(builder.build())

        return try {
            withTimeout(timeout * MILLIS_PER_SECOND) {
                call.await().use { response ->
                    classifyKomgaScanResponse(
                        statusCode = response.code,
                        body = response.peekBody(KOMGA_MAX_DIAGNOSTIC_BYTES).string(),
                        redactions = request.redactions,
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            call.cancel()
            KomgaRescanOutcome.RetryableFailure(
                komgaReason("Komga did not answer within ${timeout}s", request.redactions),
            )
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: IOException) {
            KomgaRescanOutcome.RetryableFailure(
                komgaReason("the Komga request failed: ${e.message ?: e.javaClass.simpleName}", request.redactions),
            )
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
