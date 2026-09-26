package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.graphql.mutations.ChapterIntegrityAuditMutation
import suwayomi.tachidesk.graphql.queries.ChapterIntegrityAuditQuery
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditItemState
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditKind
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditSessionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRetentionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionIntegrityState
import suwayomi.tachidesk.manga.model.table.ChapterIntegrityAuditItemTable
import suwayomi.tachidesk.manga.model.table.ChapterIntegrityAuditScheduleTable
import suwayomi.tachidesk.manga.model.table.ChapterIntegrityAuditSessionTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionRollbackTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createLibraryManga
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Behaviour of the durable archive integrity audit.
 *
 * No remote and no rclone installation is involved: the check is a boundary the tests replace, so what
 * is exercised is exactly the orchestration - which revisions are selected, what each possible answer
 * from the remote records, and how a run survives a restart, a cancel or an unconfigured verifier.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterRevisionIntegrityAuditTest : ApplicationTest() {
    private val query = ChapterIntegrityAuditQuery()

    private var clock = 10_000L

    @AfterEach
    fun clean() {
        clearTables(
            ChapterIntegrityAuditItemTable,
            ChapterIntegrityAuditSessionTable,
            ChapterRevisionRollbackTable,
            ChapterRevisionTable,
            ChapterTable,
            MangaTable,
        )
        transaction { exec("DELETE FROM chapterintegrityauditschedule") }
    }

    private fun newKey(): String = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

    /** Replaces the remote boundary, recording what a run asked about. */
    private class FakeIntegrityVerifier(
        var outcome: ArchiveIntegrityVerification = ArchiveIntegrityVerification.Verified,
        var absence: ArchiveAbsenceVerification = ArchiveAbsenceVerification.NotAttempted,
    ) : ArchiveIntegrityVerifier {
        val checked = CopyOnWriteArrayList<String>()

        override suspend fun check(artifact: ChapterRevisionArchiveArtifact): ArchiveIntegrityVerification {
            checked += artifact.candidateKey
            return outcome
        }

        override suspend fun confirmAbsent(artifact: ChapterRevisionArchiveArtifact): ArchiveAbsenceVerification = absence
    }

    @Suppress("LongParameterList")
    private fun archivedRevision(
        mangaId: Int? = null,
        chapterKey: String = newKey(),
        candidateKey: String = newKey(),
        archiveState: ChapterArchiveState = ChapterArchiveState.REMOTE_CONFIRMED,
        retentionState: ChapterRetentionState = ChapterRetentionState.RETAINED,
        deletedAt: Long? = null,
        integrityState: ChapterRevisionIntegrityState = ChapterRevisionIntegrityState.NEVER_AUDITED,
        disposition: ChapterRevisionDisposition = ChapterRevisionDisposition.ACCEPTED,
        active: Boolean = false,
        publicationState: ChapterPublicationState = ChapterPublicationState.PUBLISHED,
    ): Int =
        transaction {
            ChapterRevisionTable.insert {
                it[ChapterRevisionTable.candidateKey] = candidateKey
                it[ChapterRevisionTable.chapterKey] = chapterKey
                it[ChapterRevisionTable.manga] = mangaId
                it[sourceChapterUrl] = "https://example.invalid/$candidateKey"
                it[name] = "chapter $candidateKey"
                it[discoveredAt] = 1
                it[updatedAt] = 1
                it[ChapterRevisionTable.archiveState] = archiveState.name
                it[ChapterRevisionTable.retentionState] = retentionState.name
                it[ChapterRevisionTable.deletedAt] = deletedAt
                it[ChapterRevisionTable.integrityState] = integrityState.name
                it[ChapterRevisionTable.disposition] = disposition.name
                it[activeChapterKey] = chapterKey.takeIf { active }
                it[ChapterRevisionTable.publicationState] = publicationState.name
                it[archiveCbzPath] = ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey)
                it[archiveManifestPath] = ChapterRevisionArchiveArtifacts.relativeManifestPath(candidateKey)
                it[archiveCbzHash] = "a".repeat(64)
                it[archiveCbzSize] = 10
                it[archiveManifestHash] = "b".repeat(64)
                it[archiveManifestSize] = 20
            }

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.candidateKey eq candidateKey }
                .first()[ChapterRevisionTable.id]
                .value
        }

    private fun revision(id: Int) = ChapterRevision.getRevision(id)!!

    /** Starts a run and drives it to completion with [verifier], returning the run's id. */
    private suspend fun runAudit(
        verifier: FakeIntegrityVerifier,
        kind: ChapterIntegrityAuditKind = ChapterIntegrityAuditKind.MANUAL_RECENT,
        newestPerManga: Int? = 10,
        attempts: Int = 5,
    ): Pair<Int, Int> {
        val started =
            ChapterRevisionIntegrityAudit.start(
                request = ChapterRevisionIntegrityAudit.StartRequest(kind),
                newestPerManga = newestPerManga,
                now = clock,
            )
        val session = (started as ChapterRevisionIntegrityAudit.StartOutcome.Started).session
        val processor = ChapterRevisionIntegrityAuditProcessor(verifier = { verifier })

        repeat(attempts) {
            // The run paces itself and defers a failed check, so the driver does what the worker does:
            // it waits exactly as long as the persisted state says it has to instead of re-claiming a
            // revision that is not due yet. Advancing the clock is what lets a bounded number of
            // iterations really exhaust the attempts a run allows.
            clock = ChapterRevisionIntegrityAudit.nextDueAt(clock) ?: clock
            val claim = ChapterRevisionIntegrityAudit.claimNextDueItem(clock) ?: return@repeat
            recordAuditOutcome(claim, processor.process(claim), clock)
        }

        return session.id to (started).itemCount
    }

    // -----------------------------------------------------------------------------------------
    // selection
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an audit selects only durably archived payloads that are still there`() {
        archivedRevision()
        // not confirmed yet, so nothing durable can be checked
        archivedRevision(archiveState = ChapterArchiveState.REMOTE_PENDING)
        // the archive removed this payload on purpose; its absence is not a finding
        archivedRevision(retentionState = ChapterRetentionState.PRUNED)
        archivedRevision(retentionState = ChapterRetentionState.DELETING)
        archivedRevision(deletedAt = 5)

        val started =
            ChapterRevisionIntegrityAudit.start(
                request = ChapterRevisionIntegrityAudit.StartRequest(ChapterIntegrityAuditKind.MANUAL_RECENT),
                newestPerManga = 10,
                now = clock,
            )

        val session = (started as ChapterRevisionIntegrityAudit.StartOutcome.Started).session
        assertEquals(1, started.itemCount)

        val item = query.chapterIntegrityAuditItems(session.id, first = 10).nodes.single()
        // the item snapshots the exact artifacts of the revision, so a later edit cannot change them
        assertEquals(ChapterIntegrityAuditItemState.PENDING, item.state)
        assertEquals(0, item.attempts, "a queued revision has no attempt yet")
    }

    @Test
    fun `a recent audit keeps the newest revisions of every series`() {
        // every revision here carries the same discovered and updated timestamps, so nothing but the
        // revision id can order them: the window has to be decided by that identity, per series, and not
        // by a value that a re-release or a re-import could make equal
        val first = createLibraryManga("Series one")
        val second = createLibraryManga("Series two")

        val oldest = archivedRevision(mangaId = first)
        val middle = archivedRevision(mangaId = first)
        val newest = archivedRevision(mangaId = first)
        val otherSeriesOldest = archivedRevision(mangaId = second)
        val otherSeriesNewest = archivedRevision(mangaId = second)

        val (sessionId, itemCount) = runBlocking { runAudit(FakeIntegrityVerifier(), newestPerManga = 2) }

        assertEquals(4, itemCount)
        val audited =
            query
                .chapterIntegrityAuditItems(sessionId, first = 10)
                .nodes
                .mapNotNull { it.revisionId }
                .toSet()
        assertEquals(setOf(middle, newest, otherSeriesOldest, otherSeriesNewest), audited)
        assertFalse(audited.contains(oldest), "the oldest revision of a series is outside a window of two")
        assertEquals(4, query.chapterIntegrityAuditProgress(sessionId).verified)
    }

    @Test
    fun `a full audit visits every eligible revision`() {
        val mangaId = createLibraryManga("Series")
        repeat(4) { archivedRevision(mangaId = mangaId) }

        val (_, itemCount) =
            runBlocking { runAudit(FakeIntegrityVerifier(), kind = ChapterIntegrityAuditKind.MANUAL_FULL, newestPerManga = null) }

        assertEquals(4, itemCount)
    }

    @Test
    fun `an audit of a series that does not exist is a client error`() {
        archivedRevision(mangaId = createLibraryManga("Series"))

        val started =
            ChapterRevisionIntegrityAudit.start(
                request = ChapterRevisionIntegrityAudit.StartRequest(ChapterIntegrityAuditKind.MANUAL_RECENT, listOf(999_999)),
                newestPerManga = 10,
                now = clock,
            )

        assertEquals(
            listOf(999_999),
            (started as ChapterRevisionIntegrityAudit.StartOutcome.InvalidSubset).unknownMangaIds,
        )
    }

    // -----------------------------------------------------------------------------------------
    // findings
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a verified payload records the integrity dimension and completes the run`() {
        val revisionId = archivedRevision()

        val (sessionId, _) = runBlocking { runAudit(FakeIntegrityVerifier()) }

        val item = query.chapterIntegrityAuditItems(sessionId, first = 10).nodes.single()
        assertEquals(ChapterIntegrityAuditItemState.VERIFIED, item.state)
        assertEquals(1, item.attempts)

        val revision = revision(revisionId)
        assertEquals(ChapterRevisionIntegrityState.VERIFIED, revision.integrityState)
        assertEquals(clock, revision.integrityLastAuditedAt)
        assertEquals(sessionId, revision.integrityLastAuditSessionId)
        assertNull(revision.integrityLastError)
        // the run really finished, and the single-active invariant was released
        assertEquals(ChapterIntegrityAuditSessionState.COMPLETED, ChapterRevisionIntegrityAudit.getSession(sessionId)!!.state)
        assertNull(ChapterRevisionIntegrityAudit.getActiveSession())
    }

    @Test
    fun `a missing payload is a finding that never downgrades durability`() {
        val revisionId = archivedRevision()
        val verifier = FakeIntegrityVerifier(outcome = ArchiveIntegrityVerification.Missing("not there"))

        val (sessionId, _) = runBlocking { runAudit(verifier) }

        assertEquals(
            ChapterIntegrityAuditItemState.MISSING,
            query
                .chapterIntegrityAuditItems(sessionId, first = 10)
                .nodes
                .single()
                .state,
        )
        assertEquals(1, query.chapterIntegrityAuditProgress(sessionId).findings)

        val revision = revision(revisionId)
        assertEquals(ChapterRevisionIntegrityState.MISSING, revision.integrityState)
        // durability is a different fact and is not rewritten by an audit
        assertEquals(ChapterArchiveState.REMOTE_CONFIRMED, revision.archiveState)
        assertEquals(ChapterRetentionState.RETAINED, revision.retentionState)
        assertEquals("not there", revision.integrityLastError)
    }

    @Test
    fun `a corrupt payload is a finding that removes nothing`() {
        val revisionId = archivedRevision()
        val verifier =
            FakeIntegrityVerifier(outcome = ArchiveIntegrityVerification.Corrupt("remote cbz has size 5, expected 10"))

        val (sessionId, _) = runBlocking { runAudit(verifier) }

        assertEquals(
            ChapterIntegrityAuditItemState.CORRUPT,
            query
                .chapterIntegrityAuditItems(sessionId, first = 10)
                .nodes
                .single()
                .state,
        )

        val revision = revision(revisionId)
        assertEquals(ChapterRevisionIntegrityState.CORRUPT, revision.integrityState)
        // the payload the archive recorded is untouched: an audit only ever says what it found
        assertNotNull(revision.archiveCbzPath)
        assertNotNull(revision.archiveCbzHash)
        assertNull(revision.deletedAt)
    }

    @Test
    fun `an inconclusive check is retried with its attempt preserved`() {
        val revisionId = archivedRevision()
        val verifier = FakeIntegrityVerifier(outcome = ArchiveIntegrityVerification.Retryable("not visible yet"))
        val processor = ChapterRevisionIntegrityAuditProcessor(verifier = { verifier })

        val session =
            (
                ChapterRevisionIntegrityAudit.start(
                    request = ChapterRevisionIntegrityAudit.StartRequest(ChapterIntegrityAuditKind.MANUAL_RECENT),
                    newestPerManga = 10,
                    now = clock,
                ) as ChapterRevisionIntegrityAudit.StartOutcome.Started
            ).session

        val claim = ChapterRevisionIntegrityAudit.claimNextDueItem(clock)!!
        runBlocking { recordAuditOutcome(claim, processor.process(claim), clock) }

        val item = query.chapterIntegrityAuditItems(session.id, first = 10).nodes.single()
        assertEquals(ChapterIntegrityAuditItemState.RETRY_WAIT, item.state)
        assertEquals(1, item.attempts)
        assertEquals("not visible yet", item.lastError)
        assertEquals(clock + session.retrySeconds, item.dueAt)
        // an inconclusive check proved nothing about the payload, so the revision is still unaudited
        assertEquals(ChapterRevisionIntegrityState.NEVER_AUDITED, revision(revisionId).integrityState)
        // ... and it is not due yet, so the worker sleeps instead of re-checking immediately
        assertNull(ChapterRevisionIntegrityAudit.claimNextDueItem(clock))
    }

    @Test
    fun `an exhausted inconclusive check becomes a finding only when absence is confirmed`() {
        val revisionId = archivedRevision()
        val verifier =
            FakeIntegrityVerifier(
                outcome = ArchiveIntegrityVerification.Retryable("not visible yet"),
                absence = ArchiveAbsenceVerification.ConfirmedAbsent,
            )

        // three checks is what the run's own snapshot allows, so exhaustion is engine driven, not test driven
        val (sessionId, _) = runBlocking { runAudit(verifier, attempts = 3) }

        assertEquals(
            ChapterIntegrityAuditItemState.MISSING,
            query
                .chapterIntegrityAuditItems(sessionId, first = 10)
                .nodes
                .single()
                .state,
        )
        assertEquals(ChapterRevisionIntegrityState.MISSING, revision(revisionId).integrityState)
    }

    @Test
    fun `an absence that could not be confirmed stays a failed check rather than a finding`() {
        val revisionId = archivedRevision()
        // the remote answered every check inconclusively and could not confirm the payload is gone, so
        // nothing about the archived bytes was ever established
        val verifier = FakeIntegrityVerifier(outcome = ArchiveIntegrityVerification.Retryable("timed out"))

        val (sessionId, _) = runBlocking { runAudit(verifier, attempts = 3) }

        assertEquals(
            ChapterIntegrityAuditItemState.FAILED,
            query
                .chapterIntegrityAuditItems(sessionId, first = 10)
                .nodes
                .single()
                .state,
        )
        assertEquals(ChapterRevisionIntegrityState.AUDIT_FAILED, revision(revisionId).integrityState)
        assertEquals(
            ChapterIntegrityAuditSessionState.COMPLETED_WITH_ERRORS,
            ChapterRevisionIntegrityAudit.getSession(sessionId)!!.state,
        )
    }

    @Test
    fun `an unconfigured verifier leaves the revision untouched`() {
        val revisionId = archivedRevision()

        val (sessionId, _) = runBlocking { runAudit(FakeIntegrityVerifier(outcome = ArchiveIntegrityVerification.Unavailable)) }

        val item = query.chapterIntegrityAuditItems(sessionId, first = 10).nodes.single()
        assertEquals(ChapterIntegrityAuditItemState.PENDING, item.state)
        // nothing was checked, so the attempt is given back and the revision stays unaudited
        assertEquals(0, item.attempts)
        assertEquals(INTEGRITY_AUDIT_BLOCKED_REASON, item.lastError)
        assertEquals(ChapterRevisionIntegrityState.NEVER_AUDITED, revision(revisionId).integrityState)
    }

    @Test
    fun `the worker claims nothing at all while no remote is configured`() {
        archivedRevision()
        ChapterRevisionIntegrityAudit.start(
            request = ChapterRevisionIntegrityAudit.StartRequest(ChapterIntegrityAuditKind.MANUAL_RECENT),
            newestPerManga = 10,
            now = clock,
        )

        var claims = 0
        val unconfigured =
            ChapterRevisionIntegrityAuditLoop(
                verifierConfigured = { false },
                claim = {
                    claims++
                    ChapterRevisionIntegrityAudit.claimNextDueItem(it)
                },
                now = { clock },
            )

        assertFalse(runBlocking { unconfigured.drainAuditOnce() }, "an unconfigured worker has no work it could do")
        assertEquals(0, claims, "it must not even claim, which is what keeps it from polling")
        assertEquals(1, transaction { ChapterIntegrityAuditItemTable.selectAll().toList().size })

        val configured =
            ChapterRevisionIntegrityAuditLoop(
                verifierConfigured = { true },
                claim = {
                    claims++
                    ChapterRevisionIntegrityAudit.claimNextDueItem(it)
                },
                now = { clock },
            )

        assertTrue(runBlocking { configured.drainAuditOnce() }, "a configured worker claims the due revision")
        assertEquals(1, claims)
    }

    // -----------------------------------------------------------------------------------------
    // lifecycle
    // -----------------------------------------------------------------------------------------

    @Test
    fun `cancelling a run skips what it never checked and releases the invariant`() {
        archivedRevision()
        archivedRevision()
        archivedRevision()

        val session =
            (
                ChapterRevisionIntegrityAudit.start(
                    request = ChapterRevisionIntegrityAudit.StartRequest(ChapterIntegrityAuditKind.MANUAL_RECENT),
                    newestPerManga = 10,
                    now = clock,
                ) as ChapterRevisionIntegrityAudit.StartOutcome.Started
            ).session

        val cancelled = ChapterRevisionIntegrityAudit.cancel(session.id, clock)!!

        assertEquals(ChapterIntegrityAuditSessionState.CANCELLED, cancelled.state)
        assertNull(ChapterRevisionIntegrityAudit.getActiveSession())
        val progress = ChapterRevisionIntegrityAudit.progress(session.id)
        assertEquals(3, progress.skipped)
        assertEquals(0, progress.findings)
        assertEquals(0, progress.verified)

        // a cancelled run no longer owns the single-active invariant, so a new one may start
        assertTrue(
            ChapterRevisionIntegrityAudit.start(
                request = ChapterRevisionIntegrityAudit.StartRequest(ChapterIntegrityAuditKind.MANUAL_RECENT),
                newestPerManga = 10,
                now = clock,
            ) is ChapterRevisionIntegrityAudit.StartOutcome.Started,
        )
    }

    @Test
    fun `an interrupted check returns to the queue with its attempt intact`() {
        archivedRevision()
        val session =
            (
                ChapterRevisionIntegrityAudit.start(
                    request = ChapterRevisionIntegrityAudit.StartRequest(ChapterIntegrityAuditKind.MANUAL_RECENT),
                    newestPerManga = 10,
                    now = clock,
                ) as ChapterRevisionIntegrityAudit.StartOutcome.Started
            ).session

        // a shutdown between the claim and the check leaves the revision CHECKING
        ChapterRevisionIntegrityAudit.claimNextDueItem(clock)

        ChapterRevisionIntegrityAudit.recoverInterruptedItems(clock)

        val item = query.chapterIntegrityAuditItems(session.id, first = 10).nodes.single()
        assertEquals(ChapterIntegrityAuditItemState.RETRY_WAIT, item.state)
        assertEquals(1, item.attempts, "an interrupted attempt stays visible")
        assertEquals(clock + session.retrySeconds, item.dueAt, "and it is not retried at once")
    }

    @Test
    fun `a failed scheduled start defers its retry without consuming the occurrence`() {
        ChapterRevisionIntegrityAudit.ensureSchedule(intervalSeconds = 100, now = clock)
        archivedRevision()

        val loop =
            ChapterRevisionIntegrityAuditLoop(
                now = { clock },
                scheduledTick = { throw IllegalStateException("the database is unreachable") },
            )

        assertFalse(
            loop.drainScheduledTick(
                enabled = { true },
                intervalSeconds = { 100 },
                deferSeconds = { 30 },
                now = { clock },
            ),
            "a start that failed is not a started run",
        )

        val schedule = ChapterRevisionIntegrityAudit.getSchedule()!!
        // the occurrence is still owed: only *when* it may be retried moved
        assertEquals(clock + 100, schedule.nextDueAt)
        assertEquals(clock + 30, schedule.retryNotBefore)
        assertNull(schedule.lastSessionId)
    }

    @Test
    fun `the runtime writes the only schedule row the database accepts`() {
        ChapterRevisionIntegrityAudit.ensureSchedule(intervalSeconds = 100, now = clock)
        assertEquals(1L, transaction { ChapterIntegrityAuditScheduleTable.selectAll().count() })

        // The runtime always writes the single schedule, so the guarantee it relies on is the database's:
        // a different id has to be refused by the table rather than by the code that inserts it.
        assertThrows(Exception::class.java) {
            transaction {
                ChapterIntegrityAuditScheduleTable.insert {
                    it[id] = 2
                    it[nextDueAt] = clock
                    it[updatedAt] = clock
                }
            }
        }

        assertEquals(1L, transaction { ChapterIntegrityAuditScheduleTable.selectAll().count() })
        assertEquals(clock + 100, ChapterRevisionIntegrityAudit.getSchedule()!!.nextDueAt)
    }

    @Test
    fun `a due occurrence records its run and advances the schedule in one step`() {
        val mangaId = createLibraryManga("Series")
        archivedRevision(mangaId = mangaId)
        ChapterRevisionIntegrityAudit.ensureSchedule(intervalSeconds = 0, now = clock)

        // the loop's own configuration is what an occurrence is started and advanced with, so the test
        // builds the loop with the interval and backoff it asserts instead of relying on the defaults
        val loop =
            ChapterRevisionIntegrityAuditLoop(
                now = { clock },
                intervalSeconds = { 100 },
                deferSeconds = { 30 },
            )
        assertTrue(
            loop.drainScheduledTick(
                enabled = { true },
                intervalSeconds = { 100 },
                deferSeconds = { 30 },
                now = { clock },
            ),
            "a due occurrence with work runs",
        )

        val schedule = ChapterRevisionIntegrityAudit.getSchedule()!!
        assertEquals(clock + 100, schedule.nextDueAt)
        assertEquals(clock, schedule.lastRunAt)
        assertNotNull(schedule.lastSessionId)

        val session = ChapterRevisionIntegrityAudit.getSession(schedule.lastSessionId!!)!!
        assertEquals(ChapterIntegrityAuditKind.SCHEDULED, session.kind)
        // the occurrence was consumed by the same commit that recorded it, so it cannot run twice
        assertEquals(
            ChapterRevisionIntegrityAudit.TickOutcome.NotDue,
            ChapterRevisionIntegrityAudit.runScheduledTick(intervalSeconds = 100, deferSeconds = 30, newestPerManga = 10, now = clock),
        )
    }

    @Test
    fun `a scheduled audit never runs while the scheduler is disabled`() {
        val loop = ChapterRevisionIntegrityAuditLoop(now = { clock })

        assertFalse(
            loop.drainScheduledTick(
                enabled = { false },
                intervalSeconds = { 100 },
                deferSeconds = { 30 },
                now = { clock },
            ),
        )
        assertNull(ChapterRevisionIntegrityAudit.getSchedule(), "a disabled scheduler creates nothing")
    }

    @Test
    fun `a later run re-checks a revision that was a finding`() {
        val revisionId = archivedRevision()
        runBlocking { runAudit(FakeIntegrityVerifier(outcome = ArchiveIntegrityVerification.Missing("gone"))) }
        assertEquals(ChapterRevisionIntegrityState.MISSING, revision(revisionId).integrityState)

        // the revision is still durably archived, so a new run selects it again and can clear the finding
        val (sessionId, _) = runBlocking { runAudit(FakeIntegrityVerifier()) }

        assertEquals(
            ChapterIntegrityAuditItemState.VERIFIED,
            query
                .chapterIntegrityAuditItems(sessionId, first = 10)
                .nodes
                .single()
                .state,
        )
        assertEquals(ChapterRevisionIntegrityState.VERIFIED, revision(revisionId).integrityState)
        assertNull(revision(revisionId).integrityLastError)
    }

    // -----------------------------------------------------------------------------------------
    // the read surface
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the read surface pages a run's revisions and reports its findings`() {
        val mangaId = createLibraryManga("Series")
        repeat(3) { archivedRevision(mangaId = mangaId) }

        val (sessionId, _) = runBlocking { runAudit(FakeIntegrityVerifier(outcome = ArchiveIntegrityVerification.Corrupt("size"))) }

        val firstPage = query.chapterIntegrityAuditItems(sessionId, first = 2)
        assertEquals(2, firstPage.nodes.size)
        assertTrue(firstPage.pageInfo.hasNextPage)
        assertEquals(3, firstPage.totalCount, "the total describes the whole run, not the page returned")

        val secondPage = query.chapterIntegrityAuditItems(sessionId, after = firstPage.pageInfo.endCursor, first = 2)
        assertEquals(1, secondPage.nodes.size)
        assertFalse(secondPage.pageInfo.hasNextPage)
        assertEquals(3, secondPage.totalCount)

        val findings = query.chapterIntegrityAuditItems(sessionId, state = ChapterIntegrityAuditItemState.CORRUPT, first = 10)
        assertEquals(3, findings.nodes.size)

        val progress = query.chapterIntegrityAuditProgress(sessionId)
        assertEquals(3, progress.total)
        assertEquals(3, progress.corrupt)
        assertEquals(3, progress.findings)
        assertEquals(0, progress.remaining)

        val sessions = query.chapterIntegrityAuditSessions(first = 10)
        assertEquals(1, sessions.totalCount)
        assertEquals(sessionId, sessions.nodes.single().id)
    }

    @Test
    fun `a client cannot claim the scheduled kind or start without a remote`() {
        archivedRevision()
        val mutation = ChapterIntegrityAuditMutation()

        val scheduled =
            mutation.startChapterIntegrityAudit(
                ChapterIntegrityAuditMutation.StartChapterIntegrityAuditInput(kind = ChapterIntegrityAuditKind.SCHEDULED),
            )
        assertNull(scheduled.session)
        assertNotNull(scheduled.error)

        // no rclone remote is configured in this test environment, so nothing could ever be checked
        val manual =
            mutation.startChapterIntegrityAudit(
                ChapterIntegrityAuditMutation.StartChapterIntegrityAuditInput(kind = ChapterIntegrityAuditKind.MANUAL_RECENT),
            )
        assertNull(manual.session)
        assertNotNull(manual.error)
        assertNull(ChapterRevisionIntegrityAudit.getActiveSession())
    }
}
