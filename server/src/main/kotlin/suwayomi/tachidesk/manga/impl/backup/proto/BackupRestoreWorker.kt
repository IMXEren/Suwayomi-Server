package suwayomi.tachidesk.manga.impl.backup.proto

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import okio.BufferedSource
import okio.buffer
import okio.gzip
import okio.source
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.ArchiveBootstrap
import suwayomi.tachidesk.manga.impl.ChapterRevisionWorkerLoop
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupValidator.ValidationResult
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupCategoryHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupGlobalMetaHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupMangaHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupSettingsHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupSourceHandler
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupManga
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapCategoryPolicy
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreHandoffState
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobDataClass
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobState
import suwayomi.tachidesk.manga.model.dataclass.BackupRestorePhase
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies one durable `.tachibk` restore, one bounded chunk at a time.
 *
 * It deliberately contains no backup parsing, no validation and no restore logic of its own: it calls
 * the existing parser, validator and handlers, and only adds what a long import was missing - durable
 * progress, isolated per-series failures, and a place to resume from.
 *
 * The decoded backup is held in memory for the duration of the import, exactly as the legacy path did,
 * because decoding it per chunk would re-read tens of megabytes for every series. A restart simply
 * decodes it again and continues from the persisted series index.
 */
class BackupRestoreProcessor(
    /** where staged backups live; required because the worker must not guess a data location */
    private val stagingRoot: () -> File,
    /** how many series one chunk applies before it yields, so progress and cancels stay responsive */
    private val seriesPerChunk: Int = SERIES_PER_CHUNK,
    private val decode: (BufferedSource) -> Backup = { source ->
        ProtoBackupImport.parser.decodeFromByteArray(Backup.serializer(), source.readByteArray())
    },
    private val validate: (Backup) -> ValidationResult = { ProtoBackupValidator.validate(it) },
    private val restoreSettings: (Backup) -> Unit = { BackupSettingsHandler.restore(it.serverSettings) },
    private val restoreCategories: (Backup) -> Map<Int, Int> = { BackupCategoryHandler.restore(it.backupCategories) },
    private val restoreMeta: (Backup) -> Unit = { backup ->
        BackupGlobalMetaHandler.restore(backup.meta)
        BackupSourceHandler.restore(backup.backupSources)
    },
    private val restoreSeries: (
        BackupManga,
        Map<Int, Int>,
        Map<Long, String>,
        BackupFlags,
    ) -> Boolean = { manga, categoryMapping, sourceMapping, flags ->
        BackupMangaHandler.restore(manga, categoryMapping, sourceMapping, flags)
    },
    private val startBootstrap: (ArchiveBootstrap.StartRequest) -> ArchiveBootstrap.StartOutcome = { request ->
        ArchiveBootstrap.start(request)
    },
) {
    private val logger = KotlinLogging.logger {}

    /** One restore being applied; the decoded backup is kept until the import finishes or stops. */
    private class Session(
        val jobId: Int,
        val backup: Backup,
        val flags: BackupFlags,
        val categoryMapping: MutableMap<Int, Int>,
        val sourceMapping: Map<Long, String>,
        var mangaIndex: Int,
        var phase: BackupRestorePhase,
    )

    /** What applying one chunk of a restore found. */
    private enum class ChunkResult {
        /** the import still has series left */
        MORE_SERIES,

        /** every series of the import was applied */
        IMPORT_FINISHED,

        /** the job stopped being RUNNING while its series was being applied */
        FENCED_OUT,
    }

    private val sessions = ConcurrentHashMap<Int, Session>()

    /**
     * Applies one chunk of a running restore.
     *
     * Returns true while the import still has work left, which is how the loop keeps the worker on
     * this restore instead of sleeping between chunks.
     */
    fun processChunk(
        jobId: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean {
        val job = BackupRestoreJob.getById(jobId) ?: return discard(jobId)

        // a cancel or a terminal state can land between two chunks, so every chunk re-reads the row
        // instead of trusting the claim it was started from
        if (job.state != BackupRestoreJobState.RUNNING) {
            return discard(jobId)
        }

        return try {
            val session = sessions[jobId] ?: (openSession(job, now) ?: return discard(jobId))
            if (!isRunning(jobId)) {
                return discard(jobId)
            }

            when (applySeriesChunk(session, now)) {
                ChunkResult.MORE_SERIES -> {
                    true
                }

                ChunkResult.IMPORT_FINISHED -> {
                    finish(session, now)
                    false
                }

                ChunkResult.FENCED_OUT -> {
                    discard(jobId)
                }
            }
        } catch (e: CancellationException) {
            // the job stays RUNNING and is returned to the queue on the next start, so a shutdown is
            // never recorded as a failure and the series it was applying is simply redone
            sessions.remove(jobId)
            throw e
        } catch (e: Throwable) {
            sessions.remove(jobId)
            BackupRestoreJob.failJob(jobId, backupRestoreDiagnostic(e), now)
            logger.error { "Backup restore $jobId failed: ${failureKind(e)}" }
            false
        }
    }

    /**
     * Completes handoffs a shutdown left pending.
     *
     * A crash between recording the successful import and running the optional bootstrap handoff must
     * not silently leave a library that was imported but never handed over. Cancellation is propagated
     * rather than swallowed, because a shutdown must stop the loop instead of being recorded as a failed
     * handoff; every settleable payload is released, because a handoff that is no longer pending is
     * exactly when the staged bytes stop being needed.
     */
    suspend fun resumePendingHandoffs(now: Long = Instant.now().epochSecond) {
        BackupRestoreJob.jobsAwaitingHandoff().forEach { job ->
            try {
                runHandoff(job, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn { "Failed to resume the archive bootstrap handoff of restore ${job.id}: ${failureKind(e)}" }
            }

            settleStagedPayload(job.id, now)
            ProtoBackupImport.notifyRestoreStateChanged()
        }
    }

    // ------------------------------------------------------------------------------------------
    // phases
    // ------------------------------------------------------------------------------------------

    /**
     * Verifies the staged payload, decodes it once and runs every phase before the series import.
     *
     * The phase is persisted before its side effects, so a crash inside a phase re-runs that phase;
     * every one of them is idempotent, which is exactly why the persisted phase can be the resume
     * point of the next process.
     */
    private fun openSession(
        job: BackupRestoreJobDataClass,
        now: Long,
    ): Session? {
        val staged = stagedFileOf(job)
        if (!job.stagedFileRetained || !staged.isFile) {
            BackupRestoreJob.failJob(job.id, "the staged backup is no longer available", now)
            return null
        }

        // the bytes are re-proved before they are used: a resume has to continue from exactly the
        // payload the job was created from
        if (!BackupRestoreStaging.matches(staged, job.stagedSize, job.stagedSha256)) {
            BackupRestoreJob.failJob(job.id, "the staged backup no longer matches the uploaded content", now)
            return null
        }

        val backup = decodeStaged(staged)
        val validation = validate(backup)
        recordMissingSources(job, validation, now)

        val session =
            Session(
                jobId = job.id,
                backup = backup,
                flags = job.flags,
                categoryMapping = job.categoryMapping.toMutableMap(),
                sourceMapping = backup.getSourceMap(),
                mangaIndex = job.mangaIndex,
                phase = job.phase,
            )

        runPreSeriesPhases(session, now)

        sessions[job.id] = session
        return session
    }

    private fun runPreSeriesPhases(
        session: Session,
        now: Long,
    ) {
        val backup = session.backup
        val flags = session.flags

        if (session.phase.ordinal <= BackupRestorePhase.SETTINGS.ordinal) {
            BackupRestoreJob.advancePhase(session.jobId, BackupRestorePhase.SETTINGS, now = now)
            if (flags.includeServerSettings) {
                restoreSettings(backup)
            }
            session.phase = BackupRestorePhase.SETTINGS
        }

        if (session.phase.ordinal <= BackupRestorePhase.CATEGORIES.ordinal) {
            BackupRestoreJob.advancePhase(session.jobId, BackupRestorePhase.CATEGORIES, now = now)
            if (flags.includeCategories) {
                session.categoryMapping.putAll(restoreCategories(backup))
                // persisted so a resume at the series phase does not have to re-import categories just
                // to learn the mapping the series restore needs
                BackupRestoreJob.recordCategoryMapping(session.jobId, session.categoryMapping, now)
            }
            session.phase = BackupRestorePhase.CATEGORIES
        }

        if (session.phase.ordinal <= BackupRestorePhase.META.ordinal) {
            BackupRestoreJob.advancePhase(session.jobId, BackupRestorePhase.META, now = now)
            if (flags.includeClientData) {
                restoreMeta(backup)
            }
            session.phase = BackupRestorePhase.META
        }

        if (session.phase.ordinal <= BackupRestorePhase.MANGA.ordinal) {
            val total = if (flags.includeManga) backup.backupManga.size else 0
            BackupRestoreJob.advancePhase(
                session.jobId,
                BackupRestorePhase.MANGA,
                mangaIndex = if (flags.includeManga) session.mangaIndex else total,
                mangaCount = total,
                now = now,
            )
            session.phase = BackupRestorePhase.MANGA
        }
    }

    /**
     * Applies at most [seriesPerChunk] series, and reports what the chunk found.
     *
     * Every series re-reads the job row before anything is written: a cancel can land while the series
     * is being applied, and a restore that no longer owns its job must not record progress for a series
     * it did not take credit for, nor report a failure that the state transition has already superseded.
     */
    private fun applySeriesChunk(
        session: Session,
        now: Long,
    ): ChunkResult {
        if (!session.flags.includeManga) {
            return ChunkResult.IMPORT_FINISHED
        }

        val series = session.backup.backupManga
        var applied = 0

        // read once per chunk rather than once per series: withdrawing the report of a series that later
        // succeeded is the only reason to know, and a failed series is the exception, not the rule
        val failedIndexes = BackupRestoreJob.failedMangaIndexes(session.jobId).toMutableSet()

        while (session.mangaIndex < series.size && applied < seriesPerChunk) {
            val index = session.mangaIndex
            val manga = series[index]

            val appliedSeries =
                try {
                    restoreSeries(manga, session.categoryMapping, session.sourceMapping, session.flags)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // the handler reports its own failures as a result, so this is one it could not:
                    // it is recorded as a failure, and its message is never carried anywhere
                    false
                }

            if (!isRunning(session.jobId)) {
                return ChunkResult.FENCED_OUT
            }

            if (appliedSeries) {
                if (index in failedIndexes) {
                    // an earlier attempt of this very series failed and this one did not, so the report is
                    // withdrawn: it is no longer true, and leaving it would both inflate the error count
                    // and exclude a restored series from the archive handoff
                    BackupRestoreJob.clearMangaFailure(session.jobId, index, now)
                    failedIndexes.remove(index)
                }
            } else {
                BackupRestoreJob.recordMangaFailure(
                    id = session.jobId,
                    mangaIndex = index,
                    sourceId = manga.source,
                    sourceName = session.sourceMapping[manga.source],
                    now = now,
                )
                failedIndexes.add(index)
            }

            session.mangaIndex = index + 1
            // recorded after the series was applied, so a resume never skips a series
            BackupRestoreJob.recordMangaIndex(session.jobId, session.mangaIndex, now)
            applied++
        }

        return if (session.mangaIndex < series.size) ChunkResult.MORE_SERIES else ChunkResult.IMPORT_FINISHED
    }

    /** True while the job may still be advanced; a cancel or a terminal state fences the worker out. */
    private fun isRunning(jobId: Int): Boolean = BackupRestoreJob.getById(jobId)?.state == BackupRestoreJobState.RUNNING

    private fun finish(
        session: Session,
        now: Long,
    ) {
        sessions.remove(session.jobId)

        BackupRestoreJob.advancePhase(session.jobId, BackupRestorePhase.COMPLETED, now = now)
        if (!BackupRestoreJob.completeJob(session.jobId, now)) {
            return
        }

        val job = BackupRestoreJob.getById(session.jobId) ?: return
        runHandoff(job, now)
    }

    // ------------------------------------------------------------------------------------------
    // archive bootstrap handoff
    // ------------------------------------------------------------------------------------------

    /**
     * Starts the optional archive bootstrap for the series this restore imported.
     *
     * Only the imported series are handed over, matched by source and url rather than by position, so a
     * backup that also contains series this server does not track cannot bootstrap them. A series
     * whose own restore failed is left out for the same reason.
     *
     * The staged payload is where the series list comes from, which is why it is retained until the
     * handoff is either not requested or has started.
     */
    private fun runHandoff(
        job: BackupRestoreJobDataClass,
        now: Long,
    ) {
        if (!job.handoffRequested) {
            return
        }

        val policy = job.handoffDefaultPolicy
        if (policy == null) {
            BackupRestoreJob.setHandoffState(job.id, BackupRestoreHandoffState.NONE, now = now)
            return
        }

        val backup =
            try {
                decodeStaged(stagedFileOf(job))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn { "Could not read the staged backup of restore ${job.id} for its handoff: ${failureKind(e)}" }
                null
            }

        if (backup == null) {
            BackupRestoreJob.setHandoffState(
                job.id,
                BackupRestoreHandoffState.FAILED,
                error = "the staged backup is no longer available",
                now = now,
            )
            return
        }

        val failedIndexes = BackupRestoreJob.failedMangaIndexes(job.id).toSet()
        val pairs =
            backup.backupManga
                .withIndex()
                .filter { (index, _) -> index !in failedIndexes }
                .map { (_, manga) -> manga.source to manga.url }

        val mangaIds = resolveMangaIds(pairs)
        if (mangaIds.isEmpty()) {
            // nothing of this backup is in the library, so the handoff is complete by definition
            BackupRestoreJob.setHandoffState(job.id, BackupRestoreHandoffState.STARTED, now = now)
            return
        }

        val resolvedNames = resolveCategoryIds(job.handoffCategoryOverrides.map { it.categoryName })
        val overrides =
            job.handoffCategoryOverrides.mapNotNull { override ->
                resolvedNames[override.categoryName]
                    ?.let { categoryId -> ArchiveBootstrapCategoryPolicy(categoryId, override.policy) }
            }

        try {
            when (
                val outcome =
                    startBootstrap(
                        ArchiveBootstrap.StartRequest(
                            defaultPolicy = policy,
                            categoryPolicies = overrides,
                            mangaIds = mangaIds,
                            // this restore's own id: starting the handoff again after a crash between the
                            // bootstrap insert and the row that records it must find the run it created
                            originKey = job.restoreId,
                        ),
                    )
            ) {
                is ArchiveBootstrap.StartOutcome.Started -> {
                    BackupRestoreJob.setHandoffState(
                        job.id,
                        BackupRestoreHandoffState.STARTED,
                        sessionId = outcome.session.id,
                        now = now,
                    )
                }

                ArchiveBootstrap.StartOutcome.ActiveSessionExists -> {
                    // the import succeeded; only the bootstrap could not start, and an explicit retry
                    // resolves that without importing anything again
                    BackupRestoreJob.setHandoffState(
                        job.id,
                        BackupRestoreHandoffState.BLOCKED,
                        error = "another archive bootstrap is already active",
                        now = now,
                    )
                }

                ArchiveBootstrap.StartOutcome.NothingToBootstrap -> {
                    BackupRestoreJob.setHandoffState(job.id, BackupRestoreHandoffState.STARTED, now = now)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            BackupRestoreJob.setHandoffState(
                job.id,
                BackupRestoreHandoffState.FAILED,
                error = backupRestoreDiagnostic(e),
                now = now,
            )
        }
    }

    /**
     * Retries the handoff of an import that succeeded but could not start its bootstrap.
     *
     * The import is never repeated: a blocked handoff is resolved by starting the bootstrap only.
     */
    fun retryHandoff(
        restoreId: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean {
        val job = BackupRestoreJob.get(restoreId) ?: return false
        if (job.state != BackupRestoreJobState.SUCCESS) {
            return false
        }
        if (job.handoffState != BackupRestoreHandoffState.BLOCKED && job.handoffState != BackupRestoreHandoffState.FAILED) {
            return false
        }

        BackupRestoreJob.setHandoffState(job.id, BackupRestoreHandoffState.PENDING, now = now)
        val pending = BackupRestoreJob.getById(job.id) ?: return false
        runHandoff(pending, now)
        // the retry has now decided the handoff, so a payload that is no longer needed is released
        settleStagedPayload(pending.id, now)
        ProtoBackupImport.notifyRestoreStateChanged()
        return true
    }

    /**
     * Deletes the staged payload of an import that succeeded and whose handoff is settled.
     *
     * The payload is retained on failure, on cancel and while a handoff is blocked or failed: in every
     * one of those cases it is what makes the restore resumable or the handoff retryable.
     */
    fun releaseStagedPayload(
        job: BackupRestoreJobDataClass,
        now: Long = Instant.now().epochSecond,
    ) {
        val settled =
            job.handoffState == BackupRestoreHandoffState.NONE ||
                job.handoffState == BackupRestoreHandoffState.STARTED
        if (job.state != BackupRestoreJobState.SUCCESS || !job.stagedFileRetained || !settled) {
            return
        }

        runCatching { BackupRestoreJob.cleanupStaged(job.restoreId, stagingRoot(), now) }
            .onFailure { logger.warn { "Failed to remove the staged backup of restore ${job.id}: ${failureKind(it)}" } }
    }

    /**
     * Releases the staged payload of a restore whose outcome and handoff are both settled.
     *
     * Reading the row first is what makes this safe to call after every chunk: a payload is only ever
     * released once the restore succeeded and its handoff is no longer pending.
     */
    private fun settleStagedPayload(
        jobId: Int,
        now: Long,
    ) {
        BackupRestoreJob.getById(jobId)?.also { job -> releaseStagedPayload(job, now) }
    }

    private fun recordMissingSources(
        job: BackupRestoreJobDataClass,
        validation: ValidationResult,
        now: Long,
    ) {
        if (validation.missingSourceIds.isEmpty()) {
            return
        }

        // re-running the first phase after a crash must not duplicate the report
        val recorded = BackupRestoreJob.missingSourceAuditIds(job.id)

        validation.missingSourceIds
            .filter { (sourceId, _) -> sourceId !in recorded }
            .forEach { (sourceId, sourceName) ->
                BackupRestoreJob.recordMissingSource(
                    id = job.id,
                    phase = job.phase,
                    sourceId = sourceId,
                    sourceName = sourceName,
                    now = now,
                )
            }
    }

    private fun discard(jobId: Int): Boolean {
        sessions.remove(jobId)
        return false
    }

    private fun stagedFileOf(job: BackupRestoreJobDataClass): File = BackupRestoreJob.stagedFileOf(job, stagingRoot())

    private fun decodeStaged(staged: File): Backup =
        staged
            .source()
            .gzip()
            .buffer()
            .use { source -> decode(source) }

    /** Category names are matched the way the existing backup handlers match them. */
    private fun resolveCategoryIds(names: List<String>): Map<String, Int> =
        transaction {
            val rows = CategoryTable.selectAll().map { it[CategoryTable.id].value to it[CategoryTable.name] }

            names
                .distinct()
                .mapNotNull { name ->
                    rows.firstOrNull { it.second.equals(name, ignoreCase = true) }?.let { name to it.first }
                }.toMap()
        }

    /**
     * Maps the source/url pairs of an imported backup to library series.
     *
     * The mapping is by pair rather than by url alone, because the same url can exist on two sources,
     * and it is batched because the baseline library has thousands of series.
     */
    private fun resolveMangaIds(pairs: List<Pair<Long, String>>): List<Int> {
        if (pairs.isEmpty()) {
            return emptyList()
        }

        val wanted = pairs.toSet()
        val found = mutableListOf<Int>()

        pairs
            .map { it.second }
            .distinct()
            .chunked(RESOLVE_URL_BATCH)
            .forEach { urlBatch ->
                val urlSet = urlBatch.toSet()
                pairs
                    .filter { it.second in urlSet }
                    .map { it.first }
                    .distinct()
                    .chunked(RESOLVE_SOURCE_BATCH)
                    .forEach { sourceBatch ->
                        transaction {
                            MangaTable
                                .selectAll()
                                .where {
                                    (MangaTable.url inList urlBatch) and
                                        (MangaTable.sourceReference inList sourceBatch)
                                }.forEach { row ->
                                    val key = row[MangaTable.sourceReference] to row[MangaTable.url]
                                    if (key in wanted) {
                                        found += row[MangaTable.id].value
                                    }
                                }
                        }
                    }
            }

        return found.distinct()
    }
}

/** Series applied per chunk: often enough to persist progress, large enough to stay efficient. */
private const val SERIES_PER_CHUNK = 25

/** Resolution batches, so the `IN` lists stay bounded on a library with thousands of series. */
private const val RESOLVE_URL_BATCH = 200
private const val RESOLVE_SOURCE_BATCH = 50

/**
 * The single, global durable restore worker.
 *
 * Concurrency is one: a restore is the one operation that legitimately touches the whole library, and
 * two of them would fight over the same categories and series. The claim is fenced in the database, so
 * a second server instance cannot continue the same import.
 *
 * There is no idle timeout: a queued restore only ever appears because a request, a retry or the
 * restart recovery created one, and all three wake the worker directly.
 */
class BackupRestoreLoop(
    private val processor: BackupRestoreProcessor,
    private val claim: (Long) -> BackupRestoreJobDataClass? = { now -> BackupRestoreJob.claimNextQueued(now) },
    private val recover: (Long) -> Int = { now -> BackupRestoreJob.recoverRunning(now) },
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    private var activeJobId: Int? = null

    private val loop =
        ChapterRevisionWorkerLoop(
            workerName = "BackupRestoreWorker",
            beforeFirstDrain = {
                recover(now())
                processor.resumePendingHandoffs(now())
            },
            idleTimeoutMillis = { null },
            drainOnce = { drainOnce() },
        )

    fun start() = loop.start()

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()

    fun stop() = loop.stop()

    /**
     * Applies one chunk of work and publishes what it changed.
     *
     * Internal rather than private so a test can drive the loop deterministically instead of waiting on
     * its scheduler.
     */
    internal suspend fun drainOnce(): Boolean {
        val jobId = activeJobId
        if (jobId != null) {
            val more = processor.processChunk(jobId, now())
            settle(jobId)
            if (more) {
                return true
            }
            activeJobId = null
        }

        val claimed = claim(now()) ?: return false
        val more = processor.processChunk(claimed.id, now())

        settle(claimed.id)
        activeJobId = claimed.id.takeIf { more }
        return true
    }

    /**
     * Publishes what one chunk of a restore changed.
     *
     * This runs after every chunk and not only after the last one: a client watching a restore has to
     * see it advance, and the staged payload becomes releasable the moment a restore finishes and its
     * handoff settles - which is a transition that happens inside a chunk, not after the loop stops.
     */
    private fun settle(jobId: Int) {
        val current = now()
        BackupRestoreJob.getById(jobId)?.also { processor.releaseStagedPayload(it, current) }
        ProtoBackupImport.notifyRestoreStateChanged()
    }
}

object BackupRestoreExecutor {
    private val applicationDirs: ApplicationDirs by injectLazy()

    private val processor by lazy { BackupRestoreProcessor(stagingRoot = { File(applicationDirs.archiveStagingRoot) }) }

    private val loop by lazy { BackupRestoreLoop(processor) }

    fun start() = loop.start()

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()

    /** Retries the optional bootstrap handoff of a restore that already imported its library. */
    fun retryHandoff(restoreId: String): Boolean = processor.retryHandoff(restoreId)

    /** Removes the staged payload of a restore the operator no longer wants to keep on disk. */
    fun cleanupStaged(restoreId: String): Boolean = BackupRestoreJob.cleanupStaged(restoreId, File(applicationDirs.archiveStagingRoot))
}
