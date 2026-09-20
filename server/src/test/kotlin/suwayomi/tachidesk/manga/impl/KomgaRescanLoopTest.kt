package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.KomgaScanState
import suwayomi.tachidesk.test.ApplicationTest

class KomgaRescanLoopTest : ApplicationTest() {
    private val validConfiguration =
        komgaRescanConfiguration("http://komga:25600", "s3cr3t-key", "lib-1") as KomgaRescanConfiguration.Valid

    private val event =
        ChapterPublicationEvent(
            id = 1,
            type = ChapterPublicationEventType.CHAPTER_REVISION_PUBLISHED,
            occurredAt = 100,
            payload =
                ChapterRevisionPublishedPayload(
                    schemaVersion = 1,
                    revisionId = 1,
                    chapterKey = "chapter-key",
                    candidateKey = "candidate",
                    mangaId = 1,
                    chapterId = 1,
                    sourceId = 1,
                    sourceMangaUrl = "/manga",
                    sourceChapterUrl = "/chapter",
                    chapterNumber = 1f,
                    chapterTitle = "Chapter",
                    scanlator = null,
                    activeCbzPath = "revisions/candidate/chapter.cbz",
                    activeCbzHash = "hash",
                    activeCbzSize = 1,
                    replacedActiveCbzPath = null,
                    publishedAt = 100,
                ),
        )

    @BeforeEach
    fun clearIntent() {
        transaction { exec("DELETE FROM komgascanintent") }
    }

    @AfterEach
    fun clearIntentAgain() {
        transaction { exec("DELETE FROM komgascanintent") }
    }

    /** A processor whose clock is fixed and whose debounce window is empty, so intents are due at once. */
    private fun processorThat(
        configuration: () -> KomgaRescanConfiguration,
        client: () -> KomgaRescanOutcome,
        retryIntervalSeconds: Long = 300,
    ): KomgaRescanProcessor =
        KomgaRescanProcessor(
            configuration = configuration,
            client = { KomgaRescanClient { client() } },
            debounceSeconds = { 0 },
            retryIntervalSeconds = { retryIntervalSeconds },
            now = { 100 },
        )

    @Test
    fun `the publication listener records one coalesced intent and performs no HTTP`() =
        runBlocking {
            var notified = 0
            val listener =
                KomgaRescanPublicationListener(
                    configuration = { validConfiguration },
                    debounceSeconds = { 15 },
                    now = { 100 },
                    onIntentRecorded = { notified++ },
                )

            listener.onPublished(event)

            val intent = KomgaScanIntent.current()!!
            assertEquals(KomgaScanState.PENDING, intent.state)
            assertEquals(115L, intent.notBeforeAt, "the intent carries the quiet period, not a request")
            assertEquals(1, notified, "the worker is woken so it does not wait for an external stimulus")
        }

    @Test
    fun `the publication listener fails delivery while Komga is unusable so the event stays pending`() {
        val listener =
            KomgaRescanPublicationListener(
                configuration = { KomgaRescanConfiguration.Invalid("no Komga base URL is configured") },
                debounceSeconds = { 15 },
                now = { 100 },
                onIntentRecorded = { error("a listener that cannot deliver must not signal work") },
            )

        assertThrows(KomgaNotConfiguredException::class.java) {
            runBlocking { listener.onPublished(event) }
        }
        assertNull(KomgaScanIntent.current(), "nothing may be recorded while Komga is unusable")
    }

    @Test
    fun `the processor never opens a connection while Komga is not configured`() =
        runBlocking {
            var calls = 0
            val processor =
                processorThat(
                    configuration = { KomgaRescanConfiguration.Invalid("blank") },
                    client = {
                        calls++
                        KomgaRescanOutcome.Success
                    },
                )

            // a pending intent exists, but it must not cause a connection while Komga is unusable
            KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)

            assertFalse(processor.scanDue())
            assertEquals(0, calls)
            assertEquals(KomgaScanState.PENDING, KomgaScanIntent.current()!!.state)
        }

    @Test
    fun `a configured processor scans a due intent once and completes it`() =
        runBlocking {
            KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
            var calls = 0
            val processor =
                processorThat(
                    configuration = { validConfiguration },
                    client = {
                        calls++
                        KomgaRescanOutcome.Success
                    },
                )

            assertTrue(processor.scanDue())
            assertEquals(1, calls)
            assertEquals(KomgaScanState.COMPLETE, KomgaScanIntent.current()!!.state)
            assertFalse(processor.scanDue(), "a completed intent leaves the loop nothing to do")
        }

    @Test
    fun `a retryable failure is persisted with a retry time instead of hot-looping`() =
        runBlocking {
            KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
            val processor =
                processorThat(
                    configuration = { validConfiguration },
                    client = { KomgaRescanOutcome.RetryableFailure("Komga answered 503: unavailable") },
                    retryIntervalSeconds = 300,
                )

            assertTrue(processor.scanDue())

            val current = KomgaScanIntent.current()!!
            assertEquals(KomgaScanState.PENDING, current.state)
            assertEquals(400L, current.notBeforeAt, "the retry is deferred by the configured interval")
            assertNull(current.runningGeneration)
        }

    @Test
    fun `a hard failure is recorded and not retried on its own`() =
        runBlocking {
            KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
            val processor =
                processorThat(
                    configuration = { validConfiguration },
                    client = { KomgaRescanOutcome.HardFailure("Komga answered 401: invalid key") },
                )

            assertTrue(processor.scanDue())
            assertEquals(KomgaScanState.FAILED, KomgaScanIntent.current()!!.state)
            assertFalse(processor.scanDue(), "a hard failure only changes through an explicit retry")
        }

    @Test
    fun `cancelling a scan releases the claim so it is not stuck claimed`() {
        KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
        val processor =
            processorThat(
                configuration = { validConfiguration },
                client = { throw CancellationException("worker stopped") },
            )

        assertThrows(CancellationException::class.java) {
            runBlocking { processor.scanDue() }
        }

        val current = KomgaScanIntent.current()!!
        assertEquals(KomgaScanState.PENDING, current.state)
        assertNull(current.runningGeneration, "a cancelled attempt must not leave the intent claimed")
    }

    @Test
    fun `an unexpected failure is persisted without the target, the library or the key`() =
        runBlocking {
            val target =
                komgaRescanConfiguration("http://komga.internal:25600", "s3cr3t-key", "lib-1") as
                    KomgaRescanConfiguration.Valid
            KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
            val processor =
                processorThat(
                    configuration = { target },
                    client = {
                        throw IllegalStateException(
                            "boom ${target.request.scanUrl} key ${target.request.apiKey} library lib-1",
                        )
                    },
                )

            assertTrue(processor.scanDue())

            val error = KomgaScanIntent.current()!!.lastError!!
            assertFalse(error.contains("s3cr3t-key"), "the api key must not be persisted: $error")
            assertFalse(error.contains("lib-1"), "the library id must not be persisted: $error")
            assertFalse(error.contains("komga.internal"), "the target host must not be persisted: $error")
            assertFalse(error.contains("25600"), "the target port must not be persisted: $error")
            assertTrue(error.length <= KOMGA_MAX_REASON_LENGTH, "the persisted reason is bounded: $error")
        }

    @Test
    fun `a configuration change with no intent records an immediate scan`() {
        assertNull(KomgaScanIntent.current())

        assertTrue(applyKomgaConfigurationChange { validConfiguration })

        val intent = KomgaScanIntent.current()!!
        assertEquals(KomgaScanState.PENDING, intent.state)
        assertEquals(1L, intent.generation)
        assertNotNull(intent.notBeforeAt)
    }

    @Test
    fun `a configuration change over a COMPLETE intent records a fresh scan`() {
        KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
        KomgaScanIntent.claimDueScan(now = 100)
        assertTrue(KomgaScanIntent.completeScan(runningGeneration = 1, now = 100, debounceSeconds = 0))
        assertEquals(KomgaScanState.COMPLETE, KomgaScanIntent.current()!!.state)

        assertTrue(applyKomgaConfigurationChange { validConfiguration })

        val intent = KomgaScanIntent.current()!!
        assertEquals(KomgaScanState.PENDING, intent.state, "a completed scan must be requested again")
        assertEquals(2L, intent.generation)
    }

    @Test
    fun `a configuration change over a RUNNING intent fences it without a second scan`() {
        KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
        KomgaScanIntent.claimDueScan(now = 100)

        assertTrue(applyKomgaConfigurationChange { validConfiguration })

        val intent = KomgaScanIntent.current()!!
        assertEquals(KomgaScanState.RUNNING, intent.state, "the in-flight scan is not cancelled")
        assertEquals(1L, intent.runningGeneration, "its claim is preserved")
        assertEquals(2L, intent.generation, "the in-flight old-target scan is fenced")
        assertNull(KomgaScanIntent.claimDueScan(now = 200), "no second scan may run concurrently")
    }

    @Test
    fun `a configuration change to an unusable target records nothing`() {
        assertFalse(applyKomgaConfigurationChange { KomgaRescanConfiguration.Invalid("no Komga base URL is configured") })
        assertNull(KomgaScanIntent.current(), "an unusable target must never record a scan")
    }
}
