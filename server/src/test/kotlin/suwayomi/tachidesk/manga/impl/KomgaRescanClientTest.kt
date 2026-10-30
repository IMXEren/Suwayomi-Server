package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaRescanClientTest {
    private fun configure(
        baseUrl: String = "http://komga:25600",
        apiKey: String = "",
        libraryId: String = "lib-1",
    ): KomgaRescanConfiguration = komgaRescanConfiguration(baseUrl, apiKey, libraryId)

    private fun valid(
        baseUrl: String = "http://komga:25600",
        apiKey: String = "",
        libraryId: String = "lib-1",
    ): KomgaRescanConfiguration.Valid = configure(baseUrl, apiKey, libraryId) as KomgaRescanConfiguration.Valid

    @Test
    fun `a blank configuration is invalid and never builds a URL`() {
        listOf(
            Triple("", "key", "lib"),
            Triple("http://komga:25600", "key", ""),
            Triple("", "key", ""),
            Triple("   ", "key", "lib"),
        ).forEach { (base, key, library) ->
            assertTrue(
                komgaRescanConfiguration(base, key, library) is KomgaRescanConfiguration.Invalid,
                "($base, $library) must not be usable",
            )
        }
    }

    @Test
    fun `a base url that is not an absolute http url without credentials, query or fragment is rejected`() {
        listOf(
            "komga:25600",
            "ftp://komga/x",
            "http://user:pass@komga:25600",
            "http://komga:25600?a=b",
            "http://komga:25600#frag",
        ).forEach { base ->
            assertTrue(
                komgaRescanConfiguration(base, "", "lib") is KomgaRescanConfiguration.Invalid,
                "$base must be rejected",
            )
        }
    }

    @Test
    fun `the library id is one encoded path segment and a reverse proxy base path is kept`() {
        assertEquals(
            "https://example.com/komga/api/v1/libraries/lib-1/scan",
            valid(baseUrl = "https://example.com/komga").request.scanUrl,
        )

        // a library id can never escape into another route: the slash is encoded as one segment
        assertEquals(
            "http://komga:25600/api/v1/libraries/a%2Fb%20c/scan",
            valid(libraryId = "a/b c").request.scanUrl,
        )
    }

    @Test
    fun `the api key is only sent when it is nonblank and never appears in the URL`() {
        val noKey = valid(apiKey = "   ")
        assertNull(noKey.request.apiKey, "a blank key means Komga is deliberately configured without one")
        assertFalse(noKey.request.scanUrl.contains("key"))

        val keyed = valid(apiKey = "  s3cr3t-key  ")
        assertEquals("s3cr3t-key", keyed.request.apiKey)
        assertFalse(keyed.request.scanUrl.contains("s3cr3t-key"))
        assertTrue(keyed.request.redactions.contains("s3cr3t-key"))
    }

    @Test
    fun `the base, library and key are all redacted from a diagnostic`() {
        val request = valid(apiKey = "s3cr3t-key")
        val reason =
            komgaReason(
                "request to ${request.request.scanUrl} with key s3cr3t-key for library lib-1 failed",
                request.request.redactions,
            )
        assertFalse(reason.contains("s3cr3t-key"))
        assertFalse(reason.contains("lib-1"))
        assertFalse(reason.contains("komga:25600"))
        assertTrue(reason.contains("[redacted]"))
    }

    @Test
    fun `a short configured value of any length is redacted`() {
        val request = valid(apiKey = "zz", libraryId = "qq")
        val reason =
            komgaReason(
                "Komga answered 401: the key zz for library qq at ${request.request.scanUrl} was rejected",
                request.request.redactions,
            )
        assertFalse(reason.contains("zz"), "a one or two character key must not survive redaction: $reason")
        assertFalse(reason.contains("qq"), "a short library id must not survive redaction: $reason")
        assertFalse(reason.contains("komga:25600"))
    }

    @Test
    fun `a long configured value is redacted even inside a larger token`() {
        val request = valid(apiKey = "s3cr3t-key", libraryId = "lib-1")
        val reason = redactKomgaValues("Authorization: key=s3cr3t-key&library=lib-1&x=1", request.request.redactions)
        assertFalse(reason.contains("s3cr3t-key"))
        assertFalse(reason.contains("lib-1"))
    }

    @Test
    fun `the host and a non-default port of the target are redacted`() {
        val request = valid(baseUrl = "http://komga.internal:25600", apiKey = "", libraryId = "lib-1")
        val reason = komgaReason("Failed to connect to komga.internal:25600", request.request.redactions)
        assertFalse(reason.contains("komga.internal"), "the configured host must not survive: $reason")
        assertFalse(reason.contains("25600"), "a non-default port must not survive: $reason")
    }

    @Test
    fun `classification treats 2xx as success and only transient statuses as retryable`() {
        listOf(200, 202, 204).forEach {
            assertEquals(KomgaRescanOutcome.Success, classifyKomgaScanResponse(it, null, emptyList()))
        }
        listOf(408, 425, 429, 500, 503).forEach { status ->
            assertTrue(
                classifyKomgaScanResponse(status, "boom", emptyList()) is KomgaRescanOutcome.RetryableFailure,
                "$status must retry",
            )
        }
        listOf(400, 401, 403, 404, 422).forEach { status ->
            assertTrue(
                classifyKomgaScanResponse(status, "boom", emptyList()) is KomgaRescanOutcome.HardFailure,
                "$status must stop",
            )
        }
    }

    @Test
    fun `a diagnostic is bounded, single line and free of the configured values`() {
        val request = valid(apiKey = "s3cr3t-key")
        val outcome =
            classifyKomgaScanResponse(
                statusCode = 500,
                body = "error\n\twith s3cr3t-key " + "x".repeat(5_000),
                redactions = request.request.redactions,
            )
        val reason = (outcome as KomgaRescanOutcome.RetryableFailure).reason
        assertTrue(reason.length <= KOMGA_MAX_REASON_LENGTH, "the persisted reason is bounded")
        assertFalse(reason.contains("\n"))
        assertFalse(reason.contains("s3cr3t-key"))
    }

    @Test
    fun `saturating epoch arithmetic never wraps`() {
        assertEquals(Long.MAX_VALUE, saturatingEpochAdd(Long.MAX_VALUE, 1))
        assertEquals(Long.MAX_VALUE, saturatingEpochAdd(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(150L, saturatingEpochAdd(100, 50))
        assertEquals(100L, saturatingEpochAdd(100, 0))
        assertEquals(100L, saturatingEpochAdd(100, -5))
        assertEquals(Long.MAX_VALUE, epochSecondAfter(Long.MAX_VALUE))
    }

    @Test
    fun `waitMillisUntil saturates and never turns a far future due time into a tiny timeout`() {
        assertEquals(1_000L, waitMillisUntil(dueAt = 100, now = 100, minMillis = 1_000))
        assertEquals(1_000L, waitMillisUntil(dueAt = 101, now = 100, minMillis = 1_000))
        assertEquals(5_000L, waitMillisUntil(dueAt = 105, now = 100, minMillis = 1_000))
        assertEquals(Long.MAX_VALUE, waitMillisUntil(dueAt = Long.MAX_VALUE, now = 0, minMillis = 1_000))
    }
}
