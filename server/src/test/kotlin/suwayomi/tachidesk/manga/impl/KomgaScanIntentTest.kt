package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.KomgaScanState
import suwayomi.tachidesk.test.ApplicationTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KomgaScanIntentTest : ApplicationTest() {
    @BeforeEach
    fun clearIntent() {
        transaction { exec("DELETE FROM komgascanintent") }
    }

    @AfterEach
    fun clearIntentAgain() {
        transaction { exec("DELETE FROM komgascanintent") }
    }

    @Test
    fun `a burst of publications coalesces into a single intent that restarts its quiet window`() {
        assertNull(KomgaScanIntent.current(), "no scan is pending before the first publication")

        val first = KomgaScanIntent.requestScan(now = 100, debounceSeconds = 15)
        assertEquals(KomgaScanState.PENDING, first.state)
        assertEquals(1L, first.generation)
        assertEquals(115L, first.notBeforeAt)

        val second = KomgaScanIntent.requestScan(now = 110, debounceSeconds = 15)
        assertEquals(KomgaScanState.PENDING, second.state)
        assertEquals(2L, second.generation, "a second publication is the same pending scan, not a second one")
        assertEquals(125L, second.notBeforeAt, "the debounce window restarts from the newest request")
        assertEquals(2L, KomgaScanIntent.current()!!.generation)
    }

    @Test
    fun `a request that arrives while a scan runs is fenced without allowing a second claim`() {
        KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
        val claimed = KomgaScanIntent.claimDueScan(now = 100)!!
        assertEquals(KomgaScanState.RUNNING, claimed.state)
        assertEquals(1L, claimed.runningGeneration)
        assertEquals(1, claimed.attempts)

        // the library changed again while the scan is still in flight
        val reseeded = KomgaScanIntent.requestScan(now = 101, debounceSeconds = 0)

        assertEquals(2L, reseeded.generation, "the newer request is a new generation")
        assertEquals(KomgaScanState.RUNNING, reseeded.state, "a request must not cancel the in-flight scan")
        assertEquals(1L, reseeded.runningGeneration, "the in-flight claim is preserved")
        assertNull(
            KomgaScanIntent.claimDueScan(now = 102),
            "no second scan may be claimed while the first is still running",
        )

        val completed = KomgaScanIntent.completeScan(runningGeneration = 1, now = 103, debounceSeconds = 0)
        assertFalse(completed, "the stale outcome must not complete the newer request")
        val current = KomgaScanIntent.current()!!
        assertEquals(KomgaScanState.PENDING, current.state, "the stale outcome requeues the newer request")
        assertEquals(2L, current.generation)
        assertNull(current.runningGeneration)
    }

    @Test
    fun `seeding the singleton never overwrites an existing intent`() {
        transaction {
            exec(
                "INSERT INTO komgascanintent (id, state, generation, running_generation, requested_at, not_before_at, attempts) " +
                    "VALUES (1, 'RUNNING', 7, 7, 100, NULL, 3)",
            )
        }

        val reseeded = KomgaScanIntent.requestScan(now = 200, debounceSeconds = 0)

        assertEquals(KomgaScanState.RUNNING, reseeded.state, "the seed must not clobber the running state")
        assertEquals(7L, reseeded.runningGeneration, "the seed must not clobber the claim")
        assertEquals(8L, reseeded.generation, "the request is counted exactly once")
        assertEquals(3, reseeded.attempts, "the seed must not reset the attempt count")
    }

    @Test
    fun `concurrent first requests never unique-fail and each counts exactly once`() {
        val creators = 8
        val start = CountDownLatch(1)
        val ready = CountDownLatch(creators)
        val failures = CopyOnWriteArrayList<Throwable>()
        val pool = Executors.newFixedThreadPool(creators)
        try {
            val tasks =
                (1..creators).map {
                    pool.submit {
                        ready.countDown()
                        start.await()
                        try {
                            KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
                        } catch (t: Throwable) {
                            failures.add(t)
                        }
                    }
                }
            ready.await()
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertTrue(failures.isEmpty(), "no concurrent creator may unique-fail: $failures")
        assertEquals(
            creators.toLong(),
            KomgaScanIntent.current()!!.generation,
            "every request increments the generation exactly once",
        )
    }

    @Test
    fun `a matching claim completes the intent and records when`() {
        KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
        assertNotNull(KomgaScanIntent.claimDueScan(now = 100))

        assertTrue(KomgaScanIntent.completeScan(runningGeneration = 1, now = 150, debounceSeconds = 0))
        val current = KomgaScanIntent.current()!!
        assertEquals(KomgaScanState.COMPLETE, current.state)
        assertNull(current.runningGeneration)
        assertEquals(150L, current.lastCompletedAt)
    }

    @Test
    fun `a stale completion cannot resolve a claim it does not own`() {
        KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
        KomgaScanIntent.claimDueScan(now = 100)

        assertFalse(
            KomgaScanIntent.completeScan(runningGeneration = 7, now = 101, debounceSeconds = 0),
            "a generation that never claimed the intent cannot complete it",
        )
        assertEquals(KomgaScanState.RUNNING, KomgaScanIntent.current()!!.state)
    }

    @Test
    fun `a retryable failure stays pending with a persisted retry time`() {
        KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
        KomgaScanIntent.claimDueScan(now = 100)

        assertTrue(
            KomgaScanIntent.failScan(
                runningGeneration = 1,
                error = "Komga answered 503: unavailable",
                retryable = true,
                retryAt = 400,
                now = 100,
                debounceSeconds = 0,
            ),
        )
        val current = KomgaScanIntent.current()!!
        assertEquals(KomgaScanState.PENDING, current.state)
        assertEquals(400L, current.notBeforeAt, "the retry is deferred instead of hot-looping")
        assertEquals("Komga answered 503: unavailable", current.lastError)
        assertNull(current.runningGeneration)
    }

    @Test
    fun `a hard failure stops until it is retried explicitly`() {
        KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
        KomgaScanIntent.claimDueScan(now = 100)

        assertTrue(
            KomgaScanIntent.failScan(
                runningGeneration = 1,
                error = "Komga answered 401: invalid key",
                retryable = false,
                retryAt = 100,
                now = 100,
                debounceSeconds = 0,
            ),
        )
        assertEquals(KomgaScanState.FAILED, KomgaScanIntent.current()!!.state)

        assertTrue(KomgaScanIntent.retryFailed(now = 200))
        val retried = KomgaScanIntent.current()!!
        assertEquals(KomgaScanState.PENDING, retried.state)
        assertEquals(200L, retried.notBeforeAt)
        assertNull(retried.lastError)
        assertFalse(KomgaScanIntent.retryFailed(now = 200), "there is nothing left to retry")
    }

    @Test
    fun `recovery returns an interrupted claim to pending and keeps its generation and attempts`() {
        KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
        KomgaScanIntent.claimDueScan(now = 100)

        assertEquals(1, KomgaScanIntent.recoverInterruptedScan(now = 200, debounceSeconds = 30))
        val current = KomgaScanIntent.current()!!
        assertEquals(KomgaScanState.PENDING, current.state)
        assertEquals(1L, current.generation, "recovery describes the same request")
        assertEquals(1, current.attempts, "an interrupted attempt is not refunded")
        assertEquals(230L, current.notBeforeAt, "a crash loop cannot hammer Komga at every startup")
    }

    @Test
    fun `a cancelled claim is released and made due immediately`() {
        KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
        KomgaScanIntent.claimDueScan(now = 100)

        assertTrue(KomgaScanIntent.releaseClaim(runningGeneration = 1, now = 150))
        val current = KomgaScanIntent.current()!!
        assertEquals(KomgaScanState.PENDING, current.state)
        assertNull(current.runningGeneration)
        assertEquals(150L, current.notBeforeAt)
    }

    @Test
    fun `nextDueAt reports now while due and the persisted instant otherwise`() {
        assertNull(KomgaScanIntent.nextDueAt(now = 100), "nothing is scheduled while no intent exists")

        KomgaScanIntent.requestScan(now = 100, debounceSeconds = 100)
        assertEquals(200L, KomgaScanIntent.nextDueAt(now = 100))
        assertEquals(250L, KomgaScanIntent.nextDueAt(now = 250), "a due intent wakes the loop now")
    }

    @Test
    fun `a saturating debounce never wraps a due time into the past`() {
        val saturated = KomgaScanIntent.requestScan(now = 100, debounceSeconds = Long.MAX_VALUE)
        assertTrue(saturated.notBeforeAt!! > 0, "a saturating add must never produce a negative instant")
        assertEquals(Long.MAX_VALUE, saturated.notBeforeAt)
        assertEquals(Long.MAX_VALUE, KomgaScanIntent.nextDueAt(now = 100))
    }

    @Test
    fun `a corrupted far future due time is saturating and never reads as due`() {
        KomgaScanIntent.requestScan(now = 100, debounceSeconds = 0)
        transaction {
            exec("UPDATE komgascanintent SET not_before_at = ${Long.MAX_VALUE}, state = '${KomgaScanState.PENDING.name}'")
        }
        assertEquals(Long.MAX_VALUE, KomgaScanIntent.nextDueAt(now = 100))
        assertNull(KomgaScanIntent.claimDueScan(now = 100), "a saturated due time is not claimable")
    }
}
