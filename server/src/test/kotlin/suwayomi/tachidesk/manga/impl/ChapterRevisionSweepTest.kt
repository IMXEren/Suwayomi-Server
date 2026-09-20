package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.mutations.ChapterRevisionSweepMutation
import suwayomi.tachidesk.graphql.queries.ChapterRevisionSweepQuery
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepItemDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepItemState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepKind
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepSessionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepSessionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisState
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ChapterRevisionSweepItemTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionSweepSessionTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Behaviour of the durable revision sweep and of the content comparison it drives.
 *
 * No source and no download is involved: the sweep never fetches anything, it records a candidate
 * from the snapshot the database already holds, and the comparison is a pure function of two
 * content hashes. The tests therefore drive exactly the code that runs in production, without
 * needing a live extension.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterRevisionSweepTest : ApplicationTest() {
    private val stagingRoot: File = File("build/tmp/chapter-revision-sweep-staging-${UUID.randomUUID()}")

    @BeforeEach
    fun clean() {
        // FK order: revisions reference chapters and mangas, items reference sessions
        clearTables(
            ChapterRevisionTable,
            ChapterRevisionSweepItemTable,
            ChapterRevisionSweepSessionTable,
            ChapterTable,
            MangaTable,
        )
        transaction { exec("DELETE FROM chapterrevisionsweepschedule") }
    }

    private fun setPolicy(
        mangaId: Int,
        policy: MangaAcquisitionPolicy,
    ) = transaction {
        MangaTable.update({ MangaTable.id eq mangaId }) { it[acquisitionPolicy] = policy.name }
    }

    private fun revisionCount(mangaId: Int): Int =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.manga eq mangaId }
                .count()
                .toInt()
        }

    private fun sessionCount(): Int =
        transaction {
            ChapterRevisionSweepSessionTable
                .selectAll()
                .count()
                .toInt()
        }

    private fun itemOf(itemId: Int): ChapterRevisionSweepItemDataClass =
        transaction {
            ChapterRevisionSweepItemTable
                .selectAll()
                .where { ChapterRevisionSweepItemTable.id eq itemId }
                .first()
                .let { ChapterRevisionSweepItemTable.toDataClass(it) }
        }

    private fun revisionsOf(mangaId: Int) =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.manga eq mangaId }
                .orderBy(ChapterRevisionTable.id)
                .map { ChapterRevisionTable.toDataClass(it) }
        }

    private fun startSweep(
        kind: ChapterRevisionSweepKind = ChapterRevisionSweepKind.MANUAL_RECENT,
        newestPerSeries: Int? = 5,
    ): ChapterRevisionSweepSessionDataClass {
        val outcome = ChapterRevisionSweep.start(ChapterRevisionSweep.StartRequest(kind), newestPerSeries, now = BASE_NOW)
        assertTrue(outcome is ChapterRevisionSweep.StartOutcome.Started, "expected a session but got $outcome")
        return (outcome as ChapterRevisionSweep.StartOutcome.Started).session
    }

    private fun claim(offsetSeconds: Long = 0): ChapterRevisionSweep.Claim {
        val claimed = ChapterRevisionSweep.claimNextDueItem(now = BASE_NOW + offsetSeconds)
        assertNotNull(claimed, "expected a claimable chapter")
        return claimed!!
    }

    private fun process(claim: ChapterRevisionSweep.Claim): ChapterRevisionSweepOutcome =
        runBlocking { ChapterRevisionSweepProcessor().process(claim.item, claim.session, now = BASE_NOW) }

    /** Drains one claimed chapter the way the worker does. */
    private fun processAndSettle(claim: ChapterRevisionSweep.Claim): ChapterRevisionSweepOutcome =
        process(claim).also { outcome ->
            when (outcome) {
                is ChapterRevisionSweepOutcome.Completed -> ChapterRevisionSweep.completeItem(claim, outcome.candidateCount)
                is ChapterRevisionSweepOutcome.Skipped -> ChapterRevisionSweep.markItemSkipped(claim, outcome.reason)
            }
        }

    private fun sweepOnce(
        mangaId: Int,
        session: ChapterRevisionSweepSessionDataClass,
    ): Int {
        val claim = claim()
        val outcome = processAndSettle(claim)
        assertTrue(outcome is ChapterRevisionSweepOutcome.Completed, "expected a completed chapter but got $outcome")
        return (outcome as ChapterRevisionSweepOutcome.Completed).candidateCount
    }

    // ------------------------------------------------------------------------------------------
    // the persisted schedule
    // ------------------------------------------------------------------------------------------

    @Test
    fun `the first automatic due time is one interval away instead of immediate`() {
        val schedule = ChapterRevisionSweep.ensureSchedule(intervalSeconds = HALF_HOUR, now = BASE_NOW)

        assertEquals(BASE_NOW + HALF_HOUR, schedule.nextDueAt)
        // nothing is due yet, and the tick says so without starting or deferring anything
        assertEquals(
            ChapterRevisionSweep.TickOutcome.NotDue,
            ChapterRevisionSweep.runScheduledTick(HALF_HOUR, 300, 10, now = BASE_NOW),
        )
        assertNull(ChapterRevisionSweep.getActiveSession())
        assertEquals(BASE_NOW + HALF_HOUR, ChapterRevisionSweep.getSchedule()!!.nextDueAt)
    }

    @Test
    fun `a due tick starts one scheduled run and advances the due time by one interval`() {
        createLibraryManga("SWEEP_TICK").also { createChapters(it, 12, read = false) }
        ChapterRevisionSweep.ensureSchedule(HALF_HOUR, now = BASE_NOW)

        val outcome = ChapterRevisionSweep.runScheduledTick(HALF_HOUR, 300, 10, now = BASE_NOW + HALF_HOUR)
        assertTrue(outcome is ChapterRevisionSweep.TickOutcome.Started, "expected a started run but got $outcome")

        val session = (outcome as ChapterRevisionSweep.TickOutcome.Started).session
        assertEquals(ChapterRevisionSweepKind.SCHEDULED, session.kind)
        assertEquals(10, session.newestPerSeries)
        // the newest ten chapters of the one series, not all twelve
        assertEquals(10, ChapterRevisionSweep.progress(session.id).total)

        val schedule = ChapterRevisionSweep.getSchedule()!!
        // no catch-up storm: the due time moves one interval on, not one per missed interval
        assertEquals(BASE_NOW + 2 * HALF_HOUR, schedule.nextDueAt)
        assertEquals(session.id, schedule.lastSessionId)
        assertEquals(BASE_NOW + HALF_HOUR, schedule.lastRunAt)

        // a sweep is already running, so the next tick defers instead of starting a second one
        assertEquals(
            ChapterRevisionSweep.TickOutcome.Deferred,
            ChapterRevisionSweep.runScheduledTick(HALF_HOUR, 300, 10, now = BASE_NOW + 2 * HALF_HOUR),
        )
        assertEquals(BASE_NOW + 2 * HALF_HOUR + 300, ChapterRevisionSweep.getSchedule()!!.nextDueAt)
    }

    @Test
    fun `rescheduling pulls a shortened interval in and never postpones a pending run`() {
        ChapterRevisionSweep.ensureSchedule(HALF_HOUR, now = BASE_NOW)

        // a much longer interval must not defer the run that is already scheduled
        ChapterRevisionSweep.reschedule(HALF_HOUR * 100, now = BASE_NOW)
        assertEquals(BASE_NOW + HALF_HOUR, ChapterRevisionSweep.getSchedule()!!.nextDueAt)

        // a shorter one is what an operator uses to make a sweep happen sooner
        ChapterRevisionSweep.reschedule(60, now = BASE_NOW)
        assertEquals(BASE_NOW + 60, ChapterRevisionSweep.getSchedule()!!.nextDueAt)

        // and a disabled scheduler has nothing to wait for, so the worker sleeps instead of polling
        assertNull(ChapterRevisionSweep.sweepDueAt(enabled = false))
        assertEquals(BASE_NOW + 60, ChapterRevisionSweep.sweepDueAt(enabled = true))
    }

    @Test
    fun `a failed scheduled occurrence leaves the due instant retryable and no run behind`() {
        createLibraryManga("SWEEP_TICK_FAULT").also { createChapters(it, 3, read = false) }
        ChapterRevisionSweep.ensureSchedule(HALF_HOUR, now = BASE_NOW)

        // the fault is injected exactly between claiming the occurrence and recording it, which is the
        // window a crash would hit in production
        val failure =
            assertThrows(IllegalStateException::class.java) {
                ChapterRevisionSweep.runScheduledTick(HALF_HOUR, 300, 10, now = BASE_NOW + HALF_HOUR) {
                    throw IllegalStateException("injected failure before the session was created")
                }
            }
        assertEquals("injected failure before the session was created", failure.message)

        // the whole occurrence rolled back: the due instant is untouched, so it is retried, not skipped
        assertEquals(BASE_NOW + HALF_HOUR, ChapterRevisionSweep.getSchedule()!!.nextDueAt)
        assertNull(ChapterRevisionSweep.getLatestSession(), "a failed occurrence must leave no run behind")
        assertEquals(
            ChapterRevisionSweep.TickOutcome.NotDue,
            ChapterRevisionSweep.runScheduledTick(HALF_HOUR, 300, 10, now = BASE_NOW),
        )

        // and the very same occurrence, retried, records the run and only then moves the schedule on
        val retried = ChapterRevisionSweep.runScheduledTick(HALF_HOUR, 300, 10, now = BASE_NOW + HALF_HOUR)
        assertTrue(retried is ChapterRevisionSweep.TickOutcome.Started, "expected a started run but got $retried")
        assertEquals(1, sessionCount())
        assertEquals(BASE_NOW + 2 * HALF_HOUR, ChapterRevisionSweep.getSchedule()!!.nextDueAt)
    }

    @Test
    fun `a failed scheduled start is retried at its persisted backoff instead of every second`() {
        createLibraryManga("SWEEP_TICK_BACKOFF").also { createChapters(it, 3, read = false) }
        ChapterRevisionSweep.ensureSchedule(HALF_HOUR, now = BASE_NOW)
        val dueAt = BASE_NOW + HALF_HOUR
        val backoff = 300L

        var clock = dueAt
        var tickCalls = 0
        var startAttempts = 0
        var failStarts = true
        val loop =
            ChapterRevisionSweepLoop(
                now = { clock },
                scheduledTick = { current ->
                    tickCalls++
                    ChapterRevisionSweep.runScheduledTick(HALF_HOUR, backoff, 10, now = current) {
                        startAttempts++
                        if (failStarts) {
                            throw IllegalStateException("injected persistent start failure")
                        }
                    }
                },
            )

        // the due occurrence is attempted once and fails
        assertFalse(loop.drainScheduledTick({ true }, { HALF_HOUR }, { backoff }, { clock }))
        assertEquals(1, startAttempts)
        assertNull(ChapterRevisionSweep.getLatestSession(), "a failed start must leave no run behind")

        // the occurrence itself stays due, so it can never be skipped, and only the retry is deferred
        val failed = ChapterRevisionSweep.getSchedule()!!
        assertEquals(dueAt, failed.nextDueAt)
        assertEquals(dueAt + backoff, failed.retryNotBefore)
        // the worker sleeps until that persisted instant instead of waking on the past due instant
        assertEquals(dueAt + backoff, ChapterRevisionSweep.sweepDueAt(enabled = true, now = dueAt))
        // and a disabled scheduler still has nothing to wait for at all
        assertNull(ChapterRevisionSweep.sweepDueAt(enabled = false, now = dueAt))

        // inside the backoff the worker may look, but the occurrence is not attempted a second time
        clock = dueAt + backoff - 1
        assertFalse(loop.drainScheduledTick({ true }, { HALF_HOUR }, { backoff }, { clock }))
        assertEquals(2, tickCalls)
        assertEquals(1, startAttempts)
        assertEquals(0, sessionCount())

        // exactly when the backoff has passed the very same occurrence runs and advances the schedule once
        failStarts = false
        clock = dueAt + backoff
        assertTrue(loop.drainScheduledTick({ true }, { HALF_HOUR }, { backoff }, { clock }))
        assertEquals(2, startAttempts)
        val started = ChapterRevisionSweep.getSchedule()!!
        assertNull(started.retryNotBefore, "a completed occurrence settles its backoff")
        assertEquals(dueAt + backoff + HALF_HOUR, started.nextDueAt)
        assertEquals(1, sessionCount())
    }

    @Test
    fun `concurrent scheduled ticks claim the occurrence at most once`() {
        createLibraryManga("SWEEP_TICK_RACE").also { createChapters(it, 3, read = false) }
        ChapterRevisionSweep.ensureSchedule(HALF_HOUR, now = BASE_NOW)

        val gate = CountDownLatch(1)
        val outcomes = arrayOfNulls<ChapterRevisionSweep.TickOutcome>(2)
        val failures = arrayOfNulls<Throwable>(2)
        val threads =
            (0 until 2).map { index ->
                Thread {
                    gate.await()
                    try {
                        outcomes[index] =
                            ChapterRevisionSweep.runScheduledTick(HALF_HOUR, 300, 10, now = BASE_NOW + HALF_HOUR)
                    } catch (e: Throwable) {
                        failures[index] = e
                    }
                }
            }
        threads.forEach { it.start() }
        gate.countDown()
        threads.forEach { it.join(30_000) }

        failures.forEachIndexed { index, failure -> assertNull(failure, "tick $index failed: $failure") }

        // two instances racing the same due instant produce exactly one run, and the loser sees either an
        // already advanced due time or an already active run - never a second session
        val started = outcomes.count { it is ChapterRevisionSweep.TickOutcome.Started }
        assertEquals(1, started, "exactly one occurrence must be claimed, got ${outcomes.toList()}")
        assertEquals(1, sessionCount())
        assertEquals(BASE_NOW + 2 * HALF_HOUR, ChapterRevisionSweep.getSchedule()!!.nextDueAt)
    }

    @Test
    fun `a due tick with nothing to sweep still completes the occurrence`() {
        // a tracked series with no chapters for the sweep to select
        createLibraryManga("SWEEP_TICK_EMPTY")
        ChapterRevisionSweep.ensureSchedule(HALF_HOUR, now = BASE_NOW)

        assertEquals(
            ChapterRevisionSweep.TickOutcome.NothingToSweep,
            ChapterRevisionSweep.runScheduledTick(HALF_HOUR, 300, 10, now = BASE_NOW + HALF_HOUR),
        )

        // the occurrence really ran and found nothing, so it is completed rather than left due forever
        assertNull(ChapterRevisionSweep.getLatestSession())
        assertEquals(BASE_NOW + 2 * HALF_HOUR, ChapterRevisionSweep.getSchedule()!!.nextDueAt)
        assertEquals(BASE_NOW + HALF_HOUR, ChapterRevisionSweep.getSchedule()!!.lastRunAt)
    }

    // ------------------------------------------------------------------------------------------
    // selection and sessions
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a full sweep selects the whole history while a recent sweep selects the newest chapters`() {
        createLibraryManga("SWEEP_SELECTION").also { createChapters(it, 12, read = false) }

        val recent = startSweep(ChapterRevisionSweepKind.MANUAL_RECENT, newestPerSeries = 10)
        assertEquals(10, ChapterRevisionSweep.progress(recent.id).total)
        ChapterRevisionSweep.cancel(recent.id)

        val full = startSweep(ChapterRevisionSweepKind.MANUAL_FULL, newestPerSeries = null)
        assertEquals(12, ChapterRevisionSweep.progress(full.id).total)
        // the selection width is snapshotted, so the run itself records what it will visit
        assertNull(ChapterRevisionSweep.getSession(full.id)!!.newestPerSeries)
    }

    @Test
    fun `a second active sweep is refused and an unknown subset is reported`() {
        createLibraryManga("SWEEP_CONFLICT").also { createChapters(it, 1, read = false) }
        val emptyMangaId = createLibraryManga("SWEEP_CONFLICT_EMPTY")
        val session = startSweep()

        assertEquals(
            ChapterRevisionSweep.StartOutcome.ActiveSessionExists,
            ChapterRevisionSweep.start(ChapterRevisionSweep.StartRequest(ChapterRevisionSweepKind.MANUAL_RECENT), 5),
        )

        ChapterRevisionSweep.cancel(session.id)

        // only a tracked series may be requested, and the offending ids are named
        val invalid =
            ChapterRevisionSweep.start(
                ChapterRevisionSweep.StartRequest(ChapterRevisionSweepKind.MANUAL_RECENT, listOf(999_999)),
                5,
            )
        assertTrue(invalid is ChapterRevisionSweep.StartOutcome.InvalidSubset, "expected an invalid subset, got $invalid")

        // a tracked series that simply has nothing to visit is not an error
        assertEquals(
            ChapterRevisionSweep.StartOutcome.NothingToSweep,
            ChapterRevisionSweep.start(
                ChapterRevisionSweep.StartRequest(ChapterRevisionSweepKind.MANUAL_RECENT, listOf(emptyMangaId)),
                5,
            ),
        )
    }

    @Test
    fun `a paused series is skipped and records no candidate at all`() {
        val mangaId = createLibraryManga("SWEEP_PAUSED").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.PAUSED)

        startSweep()
        val claim = claim()
        val outcome = processAndSettle(claim)

        assertTrue(outcome is ChapterRevisionSweepOutcome.Skipped, "expected a skip but got $outcome")
        // nothing was recorded, so nothing can ever be fetched for this series
        assertEquals(0, revisionCount(mangaId))
        assertEquals(ChapterRevisionSweepItemState.SKIPPED, itemOf(claim.item.id).state)
        // the run settles itself once nothing is left to visit
        assertNull(ChapterRevisionSweep.getActiveSession())
        assertEquals(ChapterRevisionSweepSessionState.COMPLETED, ChapterRevisionSweep.getLatestSession()!!.state)
    }

    @Test
    fun `a cancelled run neither records a candidate nor lets a claimed chapter write one`() {
        val mangaId = createLibraryManga("SWEEP_CANCEL").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)

        val session = startSweep()
        val claim = claim()

        ChapterRevisionSweep.cancel(session.id)
        assertEquals(ChapterRevisionSweepItemState.CANCELLED, itemOf(claim.item.id).state)

        // the claim is fenced: a late outcome can neither record a candidate nor reopen the run
        val outcome = process(claim)
        assertTrue(outcome is ChapterRevisionSweepOutcome.Skipped, "expected a fenced skip but got $outcome")
        ChapterRevisionSweep.markItemSkipped(claim, "late outcome")

        assertEquals(0, revisionCount(mangaId))
        assertEquals(ChapterRevisionSweepSessionState.CANCELLED, ChapterRevisionSweep.getLatestSession()!!.state)
    }

    @Test
    fun `a cancel that commits between the claim and the candidate insert records nothing`() {
        val mangaId = createLibraryManga("SWEEP_CANCEL_RACE").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)

        val session = startSweep()
        val claim = claim()

        val candidateReached = CountDownLatch(1)
        val cancelCommitted = CountDownLatch(1)
        val candidateCount = arrayOfNulls<Int>(1)
        val failures = arrayOfNulls<Throwable>(1)

        // The processor's own state check runs first and passes. The cancel then commits before the
        // insertion below, which is exactly the window that check alone cannot close: the two database
        // transactions have to serialize on the same rows.
        val processor =
            ChapterRevisionSweepProcessor(
                createCandidate = { mangaEntry, chapter, policy, itemId, sessionId, reason, now ->
                    candidateReached.countDown()
                    assertTrue(cancelCommitted.await(30, TimeUnit.SECONDS), "the cancel never committed")
                    transaction {
                        ChapterRevision.createCandidatesForSweep(mangaEntry, chapter, policy, itemId, sessionId, reason, now)
                    }
                },
            )

        val thread =
            Thread {
                try {
                    val outcome = runBlocking { processor.process(claim.item, claim.session, now = BASE_NOW) }
                    candidateCount[0] = (outcome as ChapterRevisionSweepOutcome.Completed).candidateCount
                } catch (e: Throwable) {
                    failures[0] = e
                }
            }
        thread.start()

        assertTrue(candidateReached.await(30, TimeUnit.SECONDS), "the processor never reached the candidate step")
        ChapterRevisionSweep.cancel(session.id)
        cancelCommitted.countDown()
        thread.join(30_000)

        assertFalse(thread.isAlive, "recording a candidate must not hang behind a cancel")
        assertNull(failures[0], "the raced candidate creation failed: ${failures[0]}")
        assertEquals(0, candidateCount[0] ?: -1, "a cancelled run must not record a candidate")
        assertEquals(0, revisionCount(mangaId))
        // and the late chapter cannot resurrect the run it belonged to
        assertEquals(ChapterRevisionSweepSessionState.CANCELLED, ChapterRevisionSweep.getLatestSession()!!.state)
    }

    @Test
    fun `a newly recorded candidate owes a comparison that a legacy row never does`() {
        val mangaId = createLibraryManga("SWEEP_COMPARISON_STATE").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)

        assertEquals(ChapterRevisionComparisonState.PENDING, ChapterRevision.getRevision(seedCandidate(mangaId))!!.comparisonState)

        // a sweep records an ordinary candidate, so it owes its comparison too
        val session = startSweep()
        sweepOnce(mangaId, session)
        assertEquals(
            ChapterRevisionComparisonState.PENDING,
            revisionsOf(mangaId).maxByOrNull { it.id }!!.comparisonState,
        )
    }

    // ------------------------------------------------------------------------------------------
    // recovery and retries
    // ------------------------------------------------------------------------------------------

    @Test
    fun `recovery requeues an interrupted chapter after its retry delay without resetting attempts`() {
        createLibraryManga("SWEEP_RECOVERY").also { createChapters(it, 1, read = false) }
        startSweep()

        val claim = claim()
        ChapterRevisionSweep.recoverInterruptedItems(now = BASE_NOW + 10)

        val recovered = itemOf(claim.item.id)
        assertEquals(ChapterRevisionSweepItemState.RETRY_WAIT, recovered.state)
        // an interrupted attempt proved nothing, so it is not silently forgotten
        assertEquals(1, recovered.attempts)
        assertEquals(BASE_NOW + 10 + claim.session.retrySeconds, recovered.dueAt)
        // and the session stays runnable so the chapter is picked up again
        assertEquals(ChapterRevisionSweepSessionState.RUNNING, ChapterRevisionSweep.getActiveSession()!!.state)
    }

    @Test
    fun `exhausting the attempts fails the chapter instead of retrying it forever`() {
        createLibraryManga("SWEEP_EXHAUSTED").also { createChapters(it, 1, read = false) }

        val previous = serverConfig.chapterRevisionSweepMaxAttempts.value
        serverConfig.chapterRevisionSweepMaxAttempts.value = 1
        try {
            startSweep()
            val claim = claim()
            assertEquals(1, claim.item.attempts)

            ChapterRevisionSweep.recoverInterruptedItems(now = BASE_NOW + 10)

            val failed = itemOf(claim.item.id)
            assertEquals(ChapterRevisionSweepItemState.FAILED, failed.state)
            assertNotNull(failed.lastError)
            assertNull(failed.dueAt)
            // nothing is left, so the run settles as completed with errors
            assertEquals(ChapterRevisionSweepSessionState.COMPLETED_WITH_ERRORS, ChapterRevisionSweep.getLatestSession()!!.state)

            // a failed chapter is only retried when it is asked for
            val retried = ChapterRevisionSweep.retry(claim.session.id, listOf(claim.item.id))
            assertTrue(retried is ChapterRevisionSweep.RetryOutcome.Retried, "expected a retry but got $retried")
            assertEquals(1, (retried as ChapterRevisionSweep.RetryOutcome.Retried).itemCount)
            assertEquals(ChapterRevisionSweepItemState.PENDING, itemOf(claim.item.id).state)
        } finally {
            serverConfig.chapterRevisionSweepMaxAttempts.value = previous
        }
    }

    // ------------------------------------------------------------------------------------------
    // candidate identity
    // ------------------------------------------------------------------------------------------

    @Test
    fun `retrying one item reuses its candidate while another sweep records its own`() {
        val mangaId = createLibraryManga("SWEEP_IDENTITY").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)

        val first = startSweep()

        // the item records its candidate, then fails the way a source outage would leave it
        val claimed = claim()
        val outcome = process(claimed)
        assertTrue(outcome is ChapterRevisionSweepOutcome.Completed, "expected a completed chapter but got $outcome")
        assertEquals(1, (outcome as ChapterRevisionSweepOutcome.Completed).candidateCount)
        ChapterRevisionSweep.failItem(claimed, RuntimeException("source outage"), now = BASE_NOW)

        val itemId = claimed.item.id
        val firstCandidate = revisionsOf(mangaId).single()
        assertEquals(itemId, firstCandidate.sweepItemId)
        assertEquals(ChapterAcquisitionState.QUEUED, firstCandidate.acquisitionState)

        // retrying that same item must reuse the candidate it already owns, not record a second one
        val retried = claim(offsetSeconds = 400)
        assertEquals(itemId, retried.item.id)
        val again = processAndSettle(retried)
        assertEquals(1, (again as ChapterRevisionSweepOutcome.Completed).candidateCount)
        assertEquals(1, revisionCount(mangaId), "a retried item must reuse the candidate it already owns")
        assertEquals(firstCandidate.id, revisionsOf(mangaId).single().id)

        // a later sweep of the same unchanged chapter is its own re-check, so it records its own revision
        ChapterRevisionSweep.cancel(first.id)
        val second = startSweep()
        assertEquals(1, sweepOnce(mangaId, second))
        assertEquals(2, revisionCount(mangaId), "a different sweep must record a distinct candidate")
    }

    // ------------------------------------------------------------------------------------------
    // the content comparison
    // ------------------------------------------------------------------------------------------

    /** Records one candidate the way ordinary reconciliation does, and returns its id. */
    private fun seedCandidate(mangaId: Int): Int =
        transaction {
            val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
            val chapters =
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga eq mangaId }
                    .map { ChapterTable.toDataClass(it) }

            ChapterRevision.createCandidatesForNewChapters(mangaEntry, chapters, BASE_NOW)

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.manga eq mangaId }
                .first()
                .get(ChapterRevisionTable.id)
                .value
        }

    /** Turns a recorded candidate into the active, durably archived revision of its chapter. */
    private fun makeActiveBaseline(
        revisionId: Int,
        contentHash: String,
    ) = transaction {
        ChapterRevisionTable
            .selectAll()
            .where { ChapterRevisionTable.id eq revisionId }
            .first()
            .let { ChapterRevisionTable.toDataClass(it) }
            .let { revision ->
                ChapterRevisionTable.update({ ChapterRevisionTable.id eq revisionId }) {
                    it[disposition] = ChapterRevisionDisposition.ACCEPTED.name
                    it[archiveState] = ChapterArchiveState.REMOTE_CONFIRMED.name
                    it[activeChapterKey] = revision.chapterKey
                    it[ChapterRevisionTable.contentHash] = contentHash
                }
            }
    }

    /** Brings a queued candidate up to the state a completed validation leaves it in. */
    private fun acquireToValidating(revisionId: Int) {
        val claimed = ChapterRevision.claimNextQueued(now = BASE_NOW)!!
        assertEquals(revisionId, claimed.id)
        assertTrue(ChapterRevision.markDownloadedLocal(revisionId, "candidates/$revisionId"))
        assertTrue(ChapterRevision.markValidating(revisionId))
    }

    @Test
    fun `an exact match becomes terminal and is never archiveable`() {
        val mangaId = createLibraryManga("SWEEP_EXACT").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)

        val contentHash = "a".repeat(64)
        makeActiveBaseline(seedCandidate(mangaId), contentHash)

        val session = startSweep()
        assertEquals(1, sweepOnce(mangaId, session))
        val candidate = revisionsOf(mangaId).maxByOrNull { it.id }!!
        acquireToValidating(candidate.id)

        // the staged pages exist, so the cleanup has something real to remove
        val staged = File(stagingRoot, ChapterRevisionStaging.relativeDirectory(candidate.candidateKey))
        staged.mkdirs()
        assertTrue(staged.isDirectory)

        val completion = ChapterRevision.markCompleteAndClassify(candidate.id, 2, contentHash, "candidates/${candidate.id}", now = BASE_NOW)
        assertNotNull(completion)
        assertEquals(ChapterRevision.ChapterRevisionClassificationOutcome.UNCHANGED, completion!!.outcome)
        assertEquals(ChapterRevisionComparisonState.EXACT_MATCH, completion.revision.comparisonState)
        assertEquals(ChapterRevisionDisposition.UNCHANGED, completion.revision.disposition)
        assertEquals(ChapterAcquisitionState.COMPLETE, completion.revision.acquisitionState)

        // it is terminal, so no worker that claims by disposition can reach it
        assertNull(ChapterRevision.claimNextArchiveCommit(now = BASE_NOW), "an unchanged revision must never be archived")
        assertNull(ChapterRevision.claimNextQueued(now = BASE_NOW))
        assertTrue(ChapterRevision.unchangedRevisionsAwaitingCleanup(10).any { it.id == candidate.id })

        // the staged pages are removed and the pending-cleanup marker is cleared
        val cleanup = sweepCleanupUnchangedStaging(stagingRoot = stagingRoot)
        assertEquals(1, cleanup.removed)
        assertFalse(staged.exists())
        assertNull(ChapterRevision.getRevision(candidate.id)!!.candidatePath)
    }

    /**
     * Stands in for the visual analysis worker having reached a terminal state.
     *
     * A revision whose whole-chapter digest differs from the active baseline owes a page-by-page
     * comparison before it may be archived, so the tests that used to read the archive queue
     * directly have to settle that debt first. The comparison itself is covered by
     * ChapterRevisionVisualAnalysisTest.
     */
    private fun settleVisualAnalysis(id: Int) {
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[visualAnalysisState] = ChapterVisualAnalysisState.COMPLETE.name
                it[visualAnalysisNextAttemptAt] = null
                it[visualAnalysisCompletedAt] = BASE_NOW
            }
        }
    }

    @Test
    fun `different content keeps the revision and it continues into the archive`() {
        val mangaId = createLibraryManga("SWEEP_CHANGED").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)

        makeActiveBaseline(seedCandidate(mangaId), "a".repeat(64))

        val session = startSweep()
        assertEquals(1, sweepOnce(mangaId, session))
        val candidate = revisionsOf(mangaId).maxByOrNull { it.id }!!
        acquireToValidating(candidate.id)

        val completion =
            ChapterRevision.markCompleteAndClassify(
                candidate.id,
                3,
                "b".repeat(64),
                "candidates/${candidate.id}",
                now = BASE_NOW,
            )
        assertNotNull(completion)
        assertEquals(ChapterRevision.ChapterRevisionClassificationOutcome.CONTINUE, completion!!.outcome)
        assertEquals(ChapterRevisionComparisonState.CONTENT_CHANGED, completion.revision.comparisonState)
        assertEquals(ChapterRevisionDisposition.CANDIDATE, completion.revision.disposition)

        // the bytes differ, so the page-by-page comparison is owed first and the archive gate stays shut
        assertEquals(ChapterVisualAnalysisState.QUEUED, completion.revision.visualAnalysisState)
        assertNull(ChapterRevision.claimNextArchiveCommit(now = BASE_NOW))

        // once that comparison has settled the revision continues exactly as before
        settleVisualAnalysis(candidate.id)
        assertEquals(candidate.id, ChapterRevision.claimNextArchiveCommit(now = BASE_NOW)?.id)
    }

    @Test
    fun `a chapter without an active revision simply proceeds`() {
        val mangaId = createLibraryManga("SWEEP_NO_BASELINE").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)

        val candidate = seedCandidate(mangaId)
        acquireToValidating(candidate)

        val completion = ChapterRevision.markCompleteAndClassify(candidate, 1, "c".repeat(64), "candidates/$candidate", now = BASE_NOW)
        assertNotNull(completion)
        assertEquals(ChapterRevisionComparisonState.NO_BASELINE, completion!!.revision.comparisonState)
        assertNull(completion.revision.comparisonBaselineRevisionId)
        assertEquals(ChapterRevision.ChapterRevisionClassificationOutcome.CONTINUE, completion.outcome)
        // there was nothing to compare against, so no page-by-page comparison is owed
        assertEquals(ChapterVisualAnalysisState.NOT_REQUIRED, completion.revision.visualAnalysisState)
        assertEquals(candidate, ChapterRevision.claimNextArchiveCommit(now = BASE_NOW)?.id)
    }

    @Test
    fun `a revision that is no longer validating cannot be classified`() {
        val mangaId = createLibraryManga("SWEEP_FENCE").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)

        // the row is still queued, so a classification racing a cancel or a retry is fenced out
        val candidate = seedCandidate(mangaId)
        assertNull(ChapterRevision.markCompleteAndClassify(candidate, 1, "d".repeat(64), "candidates/$candidate"))
        assertEquals(ChapterAcquisitionState.QUEUED, ChapterRevision.getRevision(candidate)!!.acquisitionState)
        assertEquals(ChapterRevisionComparisonState.PENDING, ChapterRevision.getRevision(candidate)!!.comparisonState)
    }

    @Test
    fun `two classifications of one chapter identity serialize on the correct baseline`() {
        val mangaId = createLibraryManga("SWEEP_CONCURRENT").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)

        // the archived revision both candidates have to be compared against
        val baseline = seedCandidate(mangaId)
        val baselineHash = "a".repeat(64)
        makeActiveBaseline(baseline, baselineHash)

        // a second sweep of the same chapter records its own candidate instead of reusing the first
        val first = startSweep()
        assertEquals(1, sweepOnce(mangaId, first))
        ChapterRevisionSweep.cancel(first.id)
        val second = startSweep()
        assertEquals(1, sweepOnce(mangaId, second))

        val candidates = revisionsOf(mangaId).filter { it.id != baseline }
        assertEquals(2, candidates.size)

        // both are made acquirable, in whatever order the queue hands them out
        repeat(candidates.size) {
            val claimed = ChapterRevision.claimNextQueued(now = BASE_NOW)!!
            assertTrue(candidates.any { it.id == claimed.id }, "unexpected claim ${claimed.id}")
            assertTrue(ChapterRevision.markDownloadedLocal(claimed.id, "candidates/${claimed.id}"))
            assertTrue(ChapterRevision.markValidating(claimed.id))
        }

        // one candidate duplicates the archive and the other one does not
        val contentHashes = listOf(baselineHash, "b".repeat(64))
        val completed = BooleanArray(candidates.size)
        val baselineIds = arrayOfNulls<Int>(candidates.size)
        val comparisonStates = arrayOfNulls<ChapterRevisionComparisonState>(candidates.size)
        val dispositions = arrayOfNulls<ChapterRevisionDisposition>(candidates.size)
        val failures = arrayOfNulls<Throwable>(candidates.size)
        val gate = CountDownLatch(1)

        // both comparisons run at once: the identity lock serializes them, and taking it as one
        // globally ordered statement is what keeps two overlapping identities from deadlocking
        val threads =
            candidates.mapIndexed { index, candidate ->
                Thread {
                    gate.await()
                    try {
                        val completion =
                            ChapterRevision.markCompleteAndClassify(
                                candidate.id,
                                1,
                                contentHashes[index],
                                "candidates/${candidate.id}",
                                now = BASE_NOW,
                            )
                        completed[index] = completion != null
                        baselineIds[index] = completion?.revision?.comparisonBaselineRevisionId
                        comparisonStates[index] = completion?.revision?.comparisonState
                        dispositions[index] = completion?.revision?.disposition
                    } catch (e: Throwable) {
                        failures[index] = e
                    }
                }
            }
        threads.forEach { it.start() }
        gate.countDown()
        threads.forEach { it.join(30_000) }

        assertTrue(threads.none { it.isAlive }, "two comparisons of one identity must not deadlock")
        failures.forEachIndexed { index, failure -> assertNull(failure, "comparison $index failed: $failure") }
        assertTrue(completed.all { it }, "every comparison must complete")

        // each comparison saw the archived revision as its baseline, never the other candidate
        baselineIds.forEachIndexed { index, baselineId ->
            assertEquals(baseline, baselineId, "comparison $index compared against the wrong baseline")
        }

        assertEquals(ChapterRevisionComparisonState.EXACT_MATCH, comparisonStates[0])
        assertEquals(ChapterRevisionDisposition.UNCHANGED, dispositions[0])
        assertEquals(ChapterRevisionComparisonState.CONTENT_CHANGED, comparisonStates[1])
        assertEquals(ChapterRevisionDisposition.CANDIDATE, dispositions[1])

        // only the changed candidate continues, and the archived baseline was left untouched - but it
        // still owes its comparison, so it is not archivable yet
        assertEquals(
            ChapterVisualAnalysisState.QUEUED,
            ChapterRevision.getRevision(candidates[1].id)!!.visualAnalysisState,
        )
        assertNull(ChapterRevision.claimNextArchiveCommit(now = BASE_NOW))

        settleVisualAnalysis(candidates[1].id)
        assertEquals(candidates[1].id, ChapterRevision.claimNextArchiveCommit(now = BASE_NOW)?.id)
        assertEquals(ChapterRevisionDisposition.ACCEPTED, ChapterRevision.getRevision(baseline)!!.disposition)
        assertEquals(baselineHash, ChapterRevision.getRevision(baseline)!!.contentHash)
    }

    @Test
    fun `a deferred staging cleanup never spins on itself and never starves the next revision`() {
        val mangaId = createLibraryManga("SWEEP_CLEANUP_DEFER").also { createChapters(it, 2, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)

        // every chapter already has an archived revision, so the sweep's copies turn out unchanged
        val contentHash = "a".repeat(64)
        seedCandidate(mangaId)
        val baselines = revisionsOf(mangaId)
        assertEquals(2, baselines.size)
        baselines.forEach { makeActiveBaseline(it.id, contentHash) }

        val session = startSweep()
        val firstClaim = claim()
        assertEquals(1, (processAndSettle(firstClaim) as ChapterRevisionSweepOutcome.Completed).candidateCount)
        // the persisted inter-item delay is the global rate limit, so the second claim waits it out
        val secondClaim = ChapterRevisionSweep.claimNextDueItem(now = firstClaim.session.nextItemAt!!)!!
        assertEquals(1, (processAndSettle(secondClaim) as ChapterRevisionSweepOutcome.Completed).candidateCount)

        val unchanged = revisionsOf(mangaId).filter { it.disposition == ChapterRevisionDisposition.CANDIDATE }
        assertEquals(2, unchanged.size)
        unchanged.forEach { candidate ->
            // the staged pages exist, so the cleanup has something real to remove
            File(stagingRoot, ChapterRevisionStaging.relativeDirectory(candidate.candidateKey)).mkdirs()
        }

        repeat(unchanged.size) {
            val claimed = ChapterRevision.claimNextQueued(now = BASE_NOW)!!
            assertTrue(unchanged.any { it.id == claimed.id }, "unexpected claim ${claimed.id}")
            assertTrue(ChapterRevision.markDownloadedLocal(claimed.id, "candidates/${claimed.id}"))
            assertTrue(ChapterRevision.markValidating(claimed.id))
            assertNotNull(
                ChapterRevision.markCompleteAndClassify(
                    claimed.id,
                    2,
                    contentHash,
                    "candidates/${claimed.id}",
                    now = BASE_NOW,
                ),
            )
        }

        val pending = ChapterRevision.unchangedRevisionsAwaitingCleanup(10, BASE_NOW)
        assertEquals(2, pending.size)
        val deferred = pending.first()
        val next = pending.last()

        // one revision cannot be removed right now: its due instant is pushed forward rather than
        // dropped, so it is retried later instead of leaking its staged pages
        assertTrue(
            ChapterRevision.deferUnchangedStagingCleanup(deferred.id, retryIntervalSeconds = 300, now = BASE_NOW + 60),
        )
        assertNotNull(
            ChapterRevision.getRevision(deferred.id)!!.candidatePath,
            "a deferred cleanup must stay marked so it is retried",
        )
        assertEquals(
            BASE_NOW + 60 + 300,
            ChapterRevision.getRevision(deferred.id)!!.comparisonCleanupDueAt,
            "the retry is persisted, so it outlives the process that deferred it",
        )
        // it is not due before that instant, so the row behind it is what a pass sees now
        assertEquals(next.id, ChapterRevision.unchangedRevisionsAwaitingCleanup(1, BASE_NOW + 60).single().id)

        // so the next pass cleans the revision behind it instead of spinning on the deferred one
        val cleanup = sweepCleanupUnchangedStaging(limit = 1, stagingRoot = stagingRoot, retrySeconds = 300, now = BASE_NOW + 60)
        assertEquals(1, cleanup.removed)
        assertEquals(0, cleanup.deferred)
        assertNull(ChapterRevision.getRevision(next.id)!!.candidatePath)
        assertNotNull(
            ChapterRevision.getRevision(deferred.id)!!.candidatePath,
            "the deferred revision is still marked for cleanup",
        )
    }

    @Test
    fun `an unchanged revision schedules its cleanup and a successful removal clears the schedule`() {
        val mangaId = createLibraryManga("SWEEP_CLEANUP_SCHEDULE").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)

        val contentHash = "a".repeat(64)
        makeActiveBaseline(seedCandidate(mangaId), contentHash)

        val session = startSweep()
        assertEquals(1, sweepOnce(mangaId, session))
        val candidate = revisionsOf(mangaId).maxByOrNull { it.id }!!
        acquireToValidating(candidate.id)
        File(stagingRoot, ChapterRevisionStaging.relativeDirectory(candidate.candidateKey)).mkdirs()

        assertNotNull(
            ChapterRevision.markCompleteAndClassify(candidate.id, 2, contentHash, "candidates/${candidate.id}", now = BASE_NOW),
        )

        // the removal is scheduled by the same transaction that classified the revision, so a crash in
        // between can never lose it, and the worker has a real instant to sleep until
        assertEquals(BASE_NOW, ChapterRevision.getRevision(candidate.id)!!.comparisonCleanupDueAt)
        assertEquals(BASE_NOW, ChapterRevision.nextUnchangedCleanupDueAt(BASE_NOW))

        assertEquals(1, sweepCleanupUnchangedStaging(stagingRoot = stagingRoot, now = BASE_NOW).removed)
        assertNull(ChapterRevision.getRevision(candidate.id)!!.candidatePath)
        assertNull(ChapterRevision.getRevision(candidate.id)!!.comparisonCleanupDueAt)
        // nothing is left to remove, so the worker sleeps instead of polling
        assertNull(ChapterRevision.nextUnchangedCleanupDueAt(BASE_NOW + 1))
    }

    @Test
    fun `a failed cleanup retries at its persisted instant instead of hot-looping`() {
        val mangaId = createLibraryManga("SWEEP_CLEANUP_RETRY").also { createChapters(it, 2, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)

        // every chapter already has an archived revision, so the sweep's copies turn out unchanged
        val contentHash = "a".repeat(64)
        seedCandidate(mangaId)
        revisionsOf(mangaId).forEach { makeActiveBaseline(it.id, contentHash) }

        val session = startSweep()
        val firstClaim = claim()
        assertTrue(processAndSettle(firstClaim) is ChapterRevisionSweepOutcome.Completed)
        val secondClaim = ChapterRevisionSweep.claimNextDueItem(now = firstClaim.session.nextItemAt!!)!!
        assertTrue(processAndSettle(secondClaim) is ChapterRevisionSweepOutcome.Completed)

        val unchanged = revisionsOf(mangaId).filter { it.disposition == ChapterRevisionDisposition.CANDIDATE }
        assertEquals(2, unchanged.size)
        unchanged.forEach { candidate ->
            File(stagingRoot, ChapterRevisionStaging.relativeDirectory(candidate.candidateKey)).mkdirs()
            val claimed = ChapterRevision.claimNextQueued(now = BASE_NOW)!!
            assertTrue(unchanged.any { it.id == claimed.id }, "unexpected claim ${claimed.id}")
            assertTrue(ChapterRevision.markDownloadedLocal(claimed.id, "candidates/${claimed.id}"))
            assertTrue(ChapterRevision.markValidating(claimed.id))
            assertNotNull(
                ChapterRevision.markCompleteAndClassify(claimed.id, 2, contentHash, "candidates/${claimed.id}", now = BASE_NOW),
            )
        }

        val failing = unchanged.first()
        val behind = unchanged.last()

        // the removal failed, so its due instant is pushed to the persisted retry
        assertTrue(ChapterRevision.deferUnchangedStagingCleanup(failing.id, retryIntervalSeconds = 300, now = BASE_NOW))
        assertFalse(ChapterRevision.unchangedRevisionsAwaitingCleanup(10, BASE_NOW).any { it.id == failing.id })

        // the row behind it is still due right now, and it alone decides the worker's next wait
        assertEquals(behind.id, ChapterRevision.unchangedRevisionsAwaitingCleanup(10, BASE_NOW).single().id)
        assertEquals(BASE_NOW, ChapterRevision.nextUnchangedCleanupDueAt(BASE_NOW))

        assertEquals(1, sweepCleanupUnchangedStaging(limit = 1, stagingRoot = stagingRoot, now = BASE_NOW).removed)
        assertNull(ChapterRevision.getRevision(behind.id)!!.candidatePath)

        // once that row is gone the next wait is the deferred instant, so the worker sleeps instead of
        // re-checking, and an immediate pass has nothing left to attempt at all - no hot loop
        assertEquals(BASE_NOW + 300, ChapterRevision.nextUnchangedCleanupDueAt(BASE_NOW))
        val empty = sweepCleanupUnchangedStaging(stagingRoot = stagingRoot, retrySeconds = 300, now = BASE_NOW)
        assertEquals(0, empty.removed)
        assertEquals(0, empty.deferred)
        assertNotNull(
            ChapterRevision.getRevision(failing.id)!!.candidatePath,
            "a not-yet-due retry keeps its staged path, so its pages are never leaked",
        )

        // a restarted process reads the same persisted instant, and once it passes the row is due again
        assertTrue(ChapterRevision.unchangedRevisionsAwaitingCleanup(10, BASE_NOW + 300).any { it.id == failing.id })
        assertEquals(
            1,
            sweepCleanupUnchangedStaging(stagingRoot = stagingRoot, retrySeconds = 300, now = BASE_NOW + 300).removed,
        )
        assertNull(ChapterRevision.getRevision(failing.id)!!.candidatePath)
        assertNull(ChapterRevision.nextUnchangedCleanupDueAt(BASE_NOW + 300))
    }

    // ------------------------------------------------------------------------------------------
    // GraphQL surface
    // ------------------------------------------------------------------------------------------

    @Test
    fun `every sweep query and mutation method requires authentication`() {
        // Kotlin generates extra members in the same class for default arguments and for lambda
        // bodies (`name$default`, `name$lambda$1$0`). They are not GraphQL entry points and can never
        // carry the annotation, so only the declared API methods are checked; `$` is how Kotlin names
        // every generated member.
        val queryMethods =
            ChapterRevisionSweepQuery::class.java.declaredMethods.filter {
                it.name.startsWith("chapterRevisionSweep") && !it.isSynthetic && '$' !in it.name
            }
        val mutationMethods =
            ChapterRevisionSweepMutation::class.java.declaredMethods.filter {
                it.name.contains("ChapterRevisionSweep") && !it.isSynthetic && '$' !in it.name
            }

        assertTrue(queryMethods.size >= 7, "expected the full sweep query surface, got ${queryMethods.map { it.name }}")
        assertTrue(mutationMethods.size >= 5, "expected the full sweep mutation surface, got ${mutationMethods.map { it.name }}")

        // a run reveals which series an operator owns, so every entry point belongs to the same trust
        // boundary as the library APIs themselves
        (queryMethods + mutationMethods).forEach { method ->
            assertNotNull(method.getAnnotation(RequireAuth::class.java), "${method.name} must require auth")
        }
    }

    @Test
    fun `the session listing paginates and the scheduler cannot be driven by a client`() {
        createLibraryManga("SWEEP_GRAPHQL").also { createChapters(it, 1, read = false) }

        startSweep().also { ChapterRevisionSweep.cancel(it.id) }
        startSweep()

        val query = ChapterRevisionSweepQuery()
        val page = query.chapterRevisionSweepSessions(first = 1)
        assertEquals(1, page.nodes.size)
        assertEquals(2, page.totalCount)
        assertTrue(page.pageInfo.hasNextPage)
        assertFalse(page.pageInfo.hasPreviousPage)

        val items = query.chapterRevisionSweepItems(sessionId = page.nodes.first().id, first = 1)
        assertEquals(1, items.nodes.size)
        assertEquals(1, items.totalCount)

        val progress = query.chapterRevisionSweepProgress(page.nodes.first().id)
        // nothing has been claimed yet, so the one chapter of the run is still pending
        assertEquals(1, progress.total)
        assertEquals(1, progress.pending)
        assertEquals(1, progress.remaining)

        // starting the scheduler's own kind through the API would let the persisted schedule and the
        // run disagree about who started what, so it is refused
        val refused =
            ChapterRevisionSweepMutation()
                .startChapterRevisionSweep(
                    ChapterRevisionSweepMutation.StartChapterRevisionSweepInput(kind = ChapterRevisionSweepKind.SCHEDULED),
                )
        assertNull(refused.session)
        assertNotNull(refused.error)
    }

    private companion object {
        /** one fixed instant, so a persisted due time is never compared against the wall clock */
        const val BASE_NOW = 1_700_000_000L

        const val HALF_HOUR = 1_800L
    }
}
