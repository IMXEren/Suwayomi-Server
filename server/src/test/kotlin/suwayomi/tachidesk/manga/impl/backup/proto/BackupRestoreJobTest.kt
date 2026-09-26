package suwayomi.tachidesk.manga.impl.backup.proto

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.BufferedSource
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.ArchiveBootstrap
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupValidator.ValidationResult
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupManga
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapSessionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapState
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreAuditLevel
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreHandoffState
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobDataClass
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobState
import suwayomi.tachidesk.manga.model.dataclass.BackupRestorePhase
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.BackupRestoreJobAuditTable
import suwayomi.tachidesk.manga.model.table.BackupRestoreJobTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

// A durable restore has to survive a shutdown, isolate a single broken series and never expose the
// staged payload - none of which the legacy in-memory import could do.
class BackupRestoreJobTest : ApplicationTest() {
    private lateinit var stagingRoot: File

    @BeforeEach
    fun setUp() {
        stagingRoot = Files.createTempDirectory("backup-restore-test").toFile()
    }

    @AfterEach
    fun tearDown() {
        clearTables(BackupRestoreJobAuditTable, BackupRestoreJobTable, MangaTable)
        stagingRoot.deleteRecursively()
    }

    // ------------------------------------------------------------------ helpers

    private fun series(
        source: Long,
        url: String,
        title: String,
    ) = BackupManga(source = source, url = url, title = title)

    private fun backupOf(vararg series: BackupManga) = Backup(backupManga = series.toList())

    private fun createJob(handoffPolicy: MangaAcquisitionPolicy? = null): BackupRestoreJobDataClass =
        BackupRestoreJob.create(
            source = ByteArrayInputStream("uploaded-backup".toByteArray()),
            request = BackupRestoreJob.Request(BackupFlags.DEFAULT, handoffDefaultPolicy = handoffPolicy),
            stagingRoot = stagingRoot,
        )

    /** A restore only runs while it is claimed, exactly as the worker claims it. */
    private fun claimedJob(handoffPolicy: MangaAcquisitionPolicy? = null): BackupRestoreJobDataClass {
        createJob(handoffPolicy)
        return BackupRestoreJob.claimNextQueued()!!
    }

    private fun processor(
        backup: Backup,
        seriesPerChunk: Int = 25,
        missingSources: List<Pair<Long, String>> = emptyList(),
        applied: MutableList<String> = mutableListOf(),
        onSeries: (BackupManga) -> Unit = {},
        decode: (BufferedSource) -> Backup = { backup },
        startOutcome: () -> ArchiveBootstrap.StartOutcome = { ArchiveBootstrap.StartOutcome.NothingToBootstrap },
        captured: (ArchiveBootstrap.StartRequest) -> Unit = {},
    ) = BackupRestoreProcessor(
        stagingRoot = { stagingRoot },
        seriesPerChunk = seriesPerChunk,
        decode = decode,
        validate = { ValidationResult(emptyList(), emptyList(), emptyList(), missingSources) },
        restoreSettings = {},
        restoreCategories = { emptyMap() },
        restoreMeta = {},
        restoreSeries = { manga, _, _, _ ->
            applied += manga.title
            onSeries(manga)
            true
        },
        startBootstrap = { request ->
            captured(request)
            startOutcome()
        },
    )

    private fun stagedFileOf(job: BackupRestoreJobDataClass) = File(stagingRoot, job.stagedRelativePath)

    private fun runToCompletion(
        processor: BackupRestoreProcessor,
        job: BackupRestoreJobDataClass,
    ): BackupRestoreJobDataClass {
        var guard = 0
        while (processor.processChunk(job.id) && guard++ < 50) {
            // the loop keeps working the same restore until it has no work left
        }
        return BackupRestoreJob.get(job.restoreId)!!
    }

    // ------------------------------------------------------------------ staging

    @Test
    fun `stages the payload under a traversal safe name and proves it against tampering`() {
        val job = createJob()

        val staged = stagedFileOf(job)
        assertTrue(staged.isFile)
        assertEquals(staged.length(), job.stagedSize)
        assertEquals(BackupRestoreStaging.digestOf(staged), job.stagedSha256)
        assertTrue(BackupRestoreStaging.matches(staged, job.stagedSize, job.stagedSha256))

        // the name is generated, never accepted, so a stored path cannot be produced from input
        assertTrue(runCatching { BackupRestoreStaging.relativePath("../../etc/passwd") }.isFailure)
        assertTrue(runCatching { BackupRestoreStaging.stagedFile(stagingRoot, "../escape.tachibk") }.isFailure)

        // tampered bytes are refused instead of being resumed from
        staged.writeBytes("something else".toByteArray())
        assertFalse(BackupRestoreStaging.matches(staged, job.stagedSize, job.stagedSha256))
    }

    @Test
    fun `fails a resume whose staged payload was replaced`() {
        val job = claimedJob()
        stagedFileOf(job).writeBytes("something else".toByteArray())

        val processor = processor(backupOf(series(1, "u1", "one")))
        assertFalse(processor.processChunk(job.id))

        val failed = BackupRestoreJob.get(job.restoreId)!!
        assertEquals(BackupRestoreJobState.FAILURE, failed.state)
        assertTrue(failed.stagedFileRetained)
    }

    // ------------------------------------------------------------------ status

    @Test
    fun `reports the durable status without any in-memory entry`() {
        val job = createJob()

        // the database is authoritative: nothing was ever put into the legacy in-memory map
        val state = ProtoBackupImport.getRestoreState(job.restoreId)
        assertNotNull(state)
        assertTrue(state is ProtoBackupImport.BackupRestoreState.Idle)
        assertNull(ProtoBackupImport.getRestoreState("does-not-exist"))
    }

    @Test
    fun `keeps an ordinary restore out of the durable queue when it is a sync restore`() {
        ProtoBackupImport.restore(
            ByteArrayInputStream("not-a-backup".toByteArray()),
            BackupFlags.DEFAULT,
            syncMode = SyncRestoreMode.ADOPT,
        )

        // a sync restore is derived from the server, so it neither stages a payload nor creates a job
        assertEquals(0, transaction { BackupRestoreJobTable.selectAll().count() }.toInt())
        assertEquals(0, transaction { BackupRestoreJobAuditTable.selectAll().count() }.toInt())
    }

    // ------------------------------------------------------------------ phases

    @Test
    fun `applies every phase, records the progress and releases the payload`() {
        val job = claimedJob()
        val applied = mutableListOf<String>()
        val processor = processor(backupOf(series(1, "u1", "one"), series(1, "u2", "two")), applied = applied)

        val finished = runToCompletion(processor, job)

        assertEquals(listOf("one", "two"), applied)
        assertEquals(BackupRestoreJobState.SUCCESS, finished.state)
        assertEquals(BackupRestorePhase.COMPLETED, finished.phase)
        assertEquals(2, finished.mangaIndex)
        assertEquals(2, finished.mangaCount)
        assertEquals(0, finished.errorCount)

        // the payload is only released once the restore succeeded and its handoff is settled
        processor.releaseStagedPayload(finished)
        assertFalse(stagedFileOf(finished).isFile)
        assertTrue(BackupRestoreJob.get(job.restoreId)!!.stagedFileRetained.not())
    }

    @Test
    fun `resumes from the recorded series index instead of importing again`() {
        val job = claimedJob()
        val applied = mutableListOf<String>()
        val backup = backupOf(series(1, "u1", "one"), series(1, "u2", "two"), series(1, "u3", "three"))

        // one chunk of two series, then the process "stops"
        val first = processor(backup, seriesPerChunk = 2, applied = applied)
        assertTrue(first.processChunk(job.id))
        assertEquals(listOf("one", "two"), applied)
        assertEquals(2, BackupRestoreJob.get(job.restoreId)!!.mangaIndex)

        // a new process has no decoded backup, so it proves the staged payload and continues
        val afterRestart = processor(backup, seriesPerChunk = 2, applied = applied)
        assertFalse(afterRestart.processChunk(job.id))

        assertEquals(listOf("one", "two", "three"), applied)
        assertEquals(BackupRestoreJobState.SUCCESS, BackupRestoreJob.get(job.restoreId)!!.state)
    }

    @Test
    fun `records one isolated series failure and keeps importing`() {
        val job = claimedJob()
        val applied = mutableListOf<String>()
        val processor =
            processor(
                backupOf(series(1, "u1", "one"), series(1, "u2", "broken"), series(1, "u3", "three")),
                applied = applied,
                onSeries = { manga -> if (manga.title == "broken") throw IOException("this series is unreadable") },
            )

        val finished = runToCompletion(processor, job)

        assertEquals(listOf("one", "broken", "three"), applied)
        assertEquals(BackupRestoreJobState.SUCCESS, finished.state)
        assertEquals(1, finished.errorCount)

        val audits =
            transaction {
                BackupRestoreJobAuditTable
                    .selectAll()
                    .count { it[BackupRestoreJobAuditTable.level] == BackupRestoreAuditLevel.MANGA_ERROR.name }
            }
        assertEquals(1, audits)
    }

    @Test
    fun `records a missing source once even when the first phase runs again`() {
        val job = claimedJob()
        val backup = backupOf(series(42, "u1", "one"), series(42, "u2", "two"))
        val missing = listOf(42L to "Missing source")

        var guard = 0
        val first = processor(backup, seriesPerChunk = 1, missingSources = missing)
        while (first.processChunk(job.id) && guard++ < 10) { /* one chunk at a time */ }

        // a second process re-opens the payload, which re-runs the phase that records the report
        val second = processor(backup, seriesPerChunk = 1, missingSources = missing)
        assertFalse(second.processChunk(job.id))

        val recorded =
            transaction {
                BackupRestoreJobAuditTable
                    .selectAll()
                    .count { it[BackupRestoreJobAuditTable.level] == BackupRestoreAuditLevel.MISSING_SOURCE.name }
            }
        assertEquals(1, recorded)
    }

    @Test
    fun `cancels at a series boundary, retains the payload and resumes on retry`() {
        val job = claimedJob()
        val applied = mutableListOf<String>()
        val backup = backupOf(series(1, "u1", "one"), series(1, "u2", "two"), series(1, "u3", "three"))
        val processor = processor(backup, seriesPerChunk = 1, applied = applied)

        assertTrue(processor.processChunk(job.id))
        assertTrue(BackupRestoreJob.cancel(job.restoreId))

        assertFalse(processor.processChunk(job.id))
        val cancelled = BackupRestoreJob.get(job.restoreId)!!
        assertEquals(BackupRestoreJobState.CANCELLED, cancelled.state)
        assertEquals(listOf("one"), applied)
        // a cancelled import is exactly the case where the payload has to stay: it is the resume point
        assertTrue(cancelled.stagedFileRetained)

        assertTrue(BackupRestoreJob.retry(job.restoreId))
        val retried = BackupRestoreJob.claimNextQueued()!!
        val afterRetry = processor(backup, seriesPerChunk = 1, applied = applied)
        var retryGuard = 0
        while (afterRetry.processChunk(retried.id) && retryGuard++ < 10) {
            // one series per chunk, so the retry needs several calls to reach the end
        }

        // the retry continues at series two instead of importing the first series again
        assertEquals(listOf("one", "two", "three"), applied)
        assertEquals(BackupRestoreJobState.SUCCESS, BackupRestoreJob.get(job.restoreId)!!.state)
    }

    // ------------------------------------------------------------------ handoff

    private fun bootstrapSession() =
        ArchiveBootstrapSessionDataClass(
            id = 7,
            state = ArchiveBootstrapState.RUNNING,
            defaultPolicy = MangaAcquisitionPolicy.AUTO,
            categoryPolicies = emptyList(),
            interItemDelaySeconds = 1,
            retrySeconds = 1,
            maxAttempts = 1,
            startedAt = 1,
            updatedAt = 1,
            nextItemAt = null,
            lastItemAt = null,
            finishedAt = null,
            pausedAt = null,
            cancelledAt = null,
        )

    private fun insertLibrarySeries(
        source: Long,
        url: String,
        title: String,
    ): Int =
        transaction {
            MangaTable
                .insertAndGetId {
                    it[MangaTable.url] = url
                    it[MangaTable.title] = title
                    it[MangaTable.sourceReference] = source
                    it[MangaTable.inLibrary] = true
                }.value
        }

    @Test
    fun `hands only the imported series to the archive bootstrap and releases the payload`() {
        val libraryId = insertLibrarySeries(1, "u1", "one")
        val job = claimedJob(MangaAcquisitionPolicy.AUTO)
        val applied = mutableListOf<String>()

        var captured: ArchiveBootstrap.StartRequest? = null
        val processor =
            processor(
                // the second series is in the backup but not in this library, so it must not be handed over
                backupOf(series(1, "u1", "one"), series(1, "absent", "not-tracked")),
                applied = applied,
                startOutcome = { ArchiveBootstrap.StartOutcome.Started(bootstrapSession(), 1) },
                captured = { captured = it },
            )

        val finished = runToCompletion(processor, job)
        processor.releaseStagedPayload(finished)

        assertEquals(BackupRestoreHandoffState.STARTED, BackupRestoreJob.get(job.restoreId)!!.handoffState)
        assertEquals(7, BackupRestoreJob.get(job.restoreId)!!.handoffSessionId)
        assertEquals(listOf(libraryId), captured?.mangaIds)
        assertEquals(MangaAcquisitionPolicy.AUTO, captured?.defaultPolicy)
        assertFalse(stagedFileOf(finished).isFile)
    }

    @Test
    fun `blocks the handoff of an active bootstrap, retains the payload and retries without importing`() {
        val libraryId = insertLibrarySeries(1, "u1", "one")
        val job = claimedJob(MangaAcquisitionPolicy.MANUAL)
        val applied = mutableListOf<String>()

        var startAttempts = 0
        val processor =
            processor(
                backupOf(series(1, "u1", "one")),
                applied = applied,
                startOutcome = {
                    startAttempts++
                    if (startAttempts == 1) {
                        ArchiveBootstrap.StartOutcome.ActiveSessionExists
                    } else {
                        ArchiveBootstrap.StartOutcome.Started(bootstrapSession(), 1)
                    }
                },
            )

        val finished = runToCompletion(processor, job)
        processor.releaseStagedPayload(finished)

        val blocked = BackupRestoreJob.get(job.restoreId)!!
        assertEquals(BackupRestoreJobState.SUCCESS, blocked.state)
        assertEquals(BackupRestoreHandoffState.BLOCKED, blocked.handoffState)
        // the import succeeded; only the bootstrap could not start, so the payload is still needed
        assertTrue(stagedFileOf(blocked).isFile)
        assertTrue(blocked.stagedFileRetained)

        assertTrue(processor.retryHandoff(job.restoreId))
        val started = BackupRestoreJob.get(job.restoreId)!!
        assertEquals(BackupRestoreHandoffState.STARTED, started.handoffState)
        assertEquals(7, started.handoffSessionId)

        // a blocked handoff is resolved by starting the bootstrap, never by importing the library again
        assertEquals(2, startAttempts)
        assertEquals(1, applied.size)
        assertNotNull(libraryId)
    }

    // ------------------------------------------------------------------ diagnostics

    @Test
    fun `never persists the staging location or a raw failure message`() {
        val job = claimedJob()
        val processor =
            processor(
                backupOf(series(1, "u1", "one")),
                decode = { throw IOException("cannot read ${stagingRoot.absolutePath}/backup-imports/staged.tachibk") },
            )

        assertFalse(processor.processChunk(job.id))

        val failed = BackupRestoreJob.get(job.restoreId)!!
        assertEquals(BackupRestoreJobState.FAILURE, failed.state)
        // only the shape of the failure is persisted, never its text: the text can name the staging root,
        // the upload or a credential
        assertEquals("the operation failed (IOException)", failed.lastError)
        assertFalse(failed.lastError.orEmpty().contains(stagingRoot.absolutePath))

        val messages =
            transaction {
                BackupRestoreJobAuditTable
                    .selectAll()
                    .map { it[BackupRestoreJobAuditTable.message] }
            }
        assertTrue(messages.none { it.contains(stagingRoot.absolutePath) })
    }

    @Test
    fun `reports a series failure as a bounded, identity free audit`() {
        val job = claimedJob()
        val processor =
            processor(
                backupOf(series(1, "u1", "one")),
                onSeries = { throw IOException("line one\nline two for series one on source 1") },
            )

        val finished = runToCompletion(processor, job)

        val audit =
            transaction {
                BackupRestoreJobAuditTable.selectAll().first()
            }
        assertEquals(BackupRestoreJobState.SUCCESS, finished.state)
        assertEquals(BackupRestoreAuditLevel.MANGA_ERROR.name, audit[BackupRestoreJobAuditTable.level])
        assertEquals(0, audit[BackupRestoreJobAuditTable.mangaIndex])
        assertEquals(1L, audit[BackupRestoreJobAuditTable.sourceId])
        assertEquals("MANGA:0", audit[BackupRestoreJobAuditTable.auditKey])
        // the series position and its source are the report; the title and the exception text are not
        assertEquals(BackupRestoreJob.MANGA_FAILURE_MESSAGE, audit[BackupRestoreJobAuditTable.message])
        assertFalse(audit[BackupRestoreJobAuditTable.message].contains("line one"))
        assertFalse(audit[BackupRestoreJobAuditTable.message].contains("one"))
    }

    // ------------------------------------------------------------------ audit identity

    private fun auditRows(jobId: Int) =
        transaction {
            BackupRestoreJobAuditTable
                .selectAll()
                .where { BackupRestoreJobAuditTable.job eq jobId }
                .map { BackupRestoreJobAuditTable.toDataClass(it) }
        }

    @Test
    fun `re-recording the failure of one series neither duplicates it nor inflates the count`() {
        val job = claimedJob()

        assertTrue(BackupRestoreJob.recordMangaFailure(job.id, 0, 1, "src"))
        assertTrue(BackupRestoreJob.recordMangaFailure(job.id, 0, 1, "src"))

        assertEquals(1, auditRows(job.id).size)
        assertEquals(1, BackupRestoreJob.getById(job.id)!!.errorCount)
        assertEquals(listOf(0), BackupRestoreJob.failedMangaIndexes(job.id))
    }

    @Test
    fun `withdraws the failure of a series a later attempt restored`() {
        val job = claimedJob()

        assertTrue(BackupRestoreJob.recordMangaFailure(job.id, 0, 1, "src"))
        assertTrue(BackupRestoreJob.clearMangaFailure(job.id, 0))

        assertTrue(auditRows(job.id).isEmpty())
        assertEquals(0, BackupRestoreJob.getById(job.id)!!.errorCount)
        assertTrue(BackupRestoreJob.failedMangaIndexes(job.id).isEmpty())
        // withdrawing a report that is not there is a no-op rather than a second withdrawal
        assertFalse(BackupRestoreJob.clearMangaFailure(job.id, 0))
    }

    @Test
    fun `includes a series whose earlier failure a later attempt cleared in the handoff`() {
        val libraryId = insertLibrarySeries(1, "u1", "one")
        val job = claimedJob(MangaAcquisitionPolicy.AUTO)

        // the crash window: the previous attempt recorded the failure of series 0 but stopped before it
        // recorded its progress
        assertTrue(BackupRestoreJob.recordMangaFailure(job.id, 0, 1, "src"))

        var captured: ArchiveBootstrap.StartRequest? = null
        val processor =
            processor(
                backupOf(series(1, "u1", "one")),
                startOutcome = { ArchiveBootstrap.StartOutcome.Started(bootstrapSession(), 1) },
                captured = { captured = it },
            )

        val finished = runToCompletion(processor, job)

        assertEquals(0, finished.errorCount)
        assertTrue(auditRows(job.id).isEmpty())
        assertEquals(listOf(libraryId), captured?.mangaIds)
    }

    // ------------------------------------------------------------------ staged payload lifecycle

    @Test
    fun `releases the staged payload of a restore that spans several chunks`() =
        runBlocking {
            // queued rather than claimed: the loop is the thing that claims it, and a multi chunk
            // restore finishes inside the loop's active branch - the path that used to leak
            val job = createJob()
            val processor = processor(backupOf(series(1, "u1", "one"), series(1, "u2", "two")), seriesPerChunk = 1)
            val loop = BackupRestoreLoop(processor, now = { 1L })

            var guard = 0
            while (loop.drainOnce() && guard++ < 10) {
                // the loop works the same restore until it has no work left
            }

            // the restore finished inside the active branch, which is exactly the path that used to leak:
            // the payload is released once the last chunk settled the job
            assertFalse(stagedFileOf(job).isFile)
            assertFalse(BackupRestoreJob.get(job.restoreId)!!.stagedFileRetained)
        }

    @Test
    fun `releases the staged payload of a handoff a restart resumes`() =
        runBlocking {
            val libraryId = insertLibrarySeries(1, "u1", "one")
            val job = claimedJob(MangaAcquisitionPolicy.AUTO)

            // the crash window: the import succeeded and its handoff was still pending when the process died
            assertTrue(BackupRestoreJob.completeJob(job.id))
            assertTrue(BackupRestoreJob.setHandoffState(job.id, BackupRestoreHandoffState.PENDING))
            assertTrue(stagedFileOf(job).isFile)

            val processor =
                processor(
                    backupOf(series(1, "u1", "one")),
                    startOutcome = { ArchiveBootstrap.StartOutcome.Started(bootstrapSession(), 1) },
                )

            processor.resumePendingHandoffs()

            val resumed = BackupRestoreJob.get(job.restoreId)!!
            assertEquals(BackupRestoreHandoffState.STARTED, resumed.handoffState)
            assertEquals(7, resumed.handoffSessionId)
            assertFalse(stagedFileOf(job).isFile)
            assertNotNull(libraryId)
        }

    @Test
    fun `releases the staged payload once a blocked handoff finally started`() {
        val libraryId = insertLibrarySeries(1, "u1", "one")
        val job = claimedJob(MangaAcquisitionPolicy.MANUAL)
        var startAttempts = 0

        val processor =
            processor(
                backupOf(series(1, "u1", "one")),
                startOutcome = {
                    startAttempts++
                    if (startAttempts == 1) {
                        ArchiveBootstrap.StartOutcome.ActiveSessionExists
                    } else {
                        ArchiveBootstrap.StartOutcome.Started(bootstrapSession(), 1)
                    }
                },
            )

        val blocked = runToCompletion(processor, job)
        assertEquals(BackupRestoreHandoffState.BLOCKED, blocked.handoffState)
        assertTrue(stagedFileOf(blocked).isFile)

        // the retry resolves the handoff, so the payload it was retained for is no longer needed
        assertTrue(processor.retryHandoff(job.restoreId))
        assertEquals(BackupRestoreHandoffState.STARTED, BackupRestoreJob.get(job.restoreId)!!.handoffState)
        assertFalse(stagedFileOf(job).isFile)
        assertNotNull(libraryId)
    }

    @Test
    fun `keeps the payload when a handoff is still blocked`() {
        insertLibrarySeries(1, "u1", "one")
        val job = claimedJob(MangaAcquisitionPolicy.MANUAL)

        val processor =
            processor(
                backupOf(series(1, "u1", "one")),
                startOutcome = { ArchiveBootstrap.StartOutcome.ActiveSessionExists },
            )

        val blocked = runToCompletion(processor, job)
        processor.releaseStagedPayload(blocked)

        // a blocked handoff is retryable only from the staged bytes, so releasing them would strand it
        assertTrue(stagedFileOf(blocked).isFile)
        assertTrue(BackupRestoreJob.get(job.restoreId)!!.stagedFileRetained)
    }

    // ------------------------------------------------------------------ cancellation fencing

    @Test
    fun `stops a chunk that was cancelled while its series was being applied`() {
        val job = claimedJob()
        val backup = backupOf(series(1, "u1", "one"), series(1, "u2", "two"))
        val applied = mutableListOf<String>()
        val processor =
            processor(
                backup,
                seriesPerChunk = 1,
                applied = applied,
                // the cancel lands while the handler is applying the series, which is exactly the window
                // the worker has to re-read the row for
                onSeries = { BackupRestoreJob.cancel(job.restoreId) },
            )

        assertFalse(processor.processChunk(job.id))

        val cancelled = BackupRestoreJob.get(job.restoreId)!!
        assertEquals(BackupRestoreJobState.CANCELLED, cancelled.state)
        assertEquals(listOf("one"), applied)
        // nothing is recorded for a series the restore no longer owns
        assertEquals(0, cancelled.mangaIndex)
        assertTrue(auditRows(job.id).isEmpty())

        // and a cancelled restore is never resurrected into a success by a later chunk
        assertFalse(processor.processChunk(job.id))
        assertEquals(BackupRestoreJobState.CANCELLED, BackupRestoreJob.get(job.restoreId)!!.state)
    }

    @Test
    fun `propagates cancellation from a resumed handoff instead of recording it as a failure`() =
        runBlocking {
            insertLibrarySeries(1, "u1", "one")
            val job = claimedJob(MangaAcquisitionPolicy.AUTO)
            assertTrue(BackupRestoreJob.completeJob(job.id))
            assertTrue(BackupRestoreJob.setHandoffState(job.id, BackupRestoreHandoffState.PENDING))

            val processor =
                processor(
                    backupOf(series(1, "u1", "one")),
                    startOutcome = { throw CancellationException("shutting down") },
                )

            val failure = runCatching { processor.resumePendingHandoffs() }.exceptionOrNull()
            assertTrue(failure is CancellationException)

            // a shutdown is not a failed handoff, and the payload is still there to be resumed from
            assertEquals(BackupRestoreHandoffState.PENDING, BackupRestoreJob.get(job.restoreId)!!.handoffState)
            assertTrue(stagedFileOf(job).isFile)
        }

    @Test
    fun `propagates cancellation from reading the staged backup of a resumed handoff`() =
        runBlocking {
            insertLibrarySeries(1, "u1", "one")
            val job = claimedJob(MangaAcquisitionPolicy.AUTO)
            assertTrue(BackupRestoreJob.completeJob(job.id))
            assertTrue(BackupRestoreJob.setHandoffState(job.id, BackupRestoreHandoffState.PENDING))

            val processor =
                processor(
                    backupOf(series(1, "u1", "one")),
                    decode = { throw CancellationException("shutting down") },
                )

            val failure = runCatching { processor.resumePendingHandoffs() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(BackupRestoreHandoffState.PENDING, BackupRestoreJob.get(job.restoreId)!!.handoffState)
        }
}
