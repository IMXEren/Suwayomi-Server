package suwayomi.tachidesk.manga.impl.backup.proto

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreAuditLevel
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreCategoryMapping
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreCategoryPolicies
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreCategoryPolicy
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreFlagsCodec
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreHandoffState
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobDataClass
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobState
import suwayomi.tachidesk.manga.model.dataclass.BackupRestorePhase
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.BackupRestoreJobAuditTable
import suwayomi.tachidesk.manga.model.table.BackupRestoreJobTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.time.Instant

/**
 * Durable state of an ordinary `.tachibk` restore.
 *
 * The job, not the process, owns the progress: the uploaded bytes are staged on local disk once and
 * the row records only how far the import got, so a restart resumes instead of re-uploading. This is
 * what makes a 3,435 series baseline import survivable.
 *
 * Nothing here parses or applies a backup - the existing parser, validator and handlers do that - and
 * nothing here exposes the payload: the staged file name is the only pointer, and it is derived from a
 * random id rather than from anything a client sent.
 */
object BackupRestoreJob {
    private val logger = KotlinLogging.logger {}

    /** Bounded because the file name is all that is kept, never the payload. */
    private const val MAX_MESSAGE_LENGTH = 512

    /**
     * The reason recorded for a series the existing handler could not restore.
     *
     * Static on purpose: the message the handler composes names the series, the source and the raw
     * exception, and this value is persisted in a column that is exposed through authenticated GraphQL.
     * The series position and its source are recorded in their own bounded columns instead.
     */
    internal const val MANGA_FAILURE_MESSAGE = "this series could not be restored"

    /** The reason recorded for a source the backup refers to that is not installed. */
    internal const val MISSING_SOURCE_MESSAGE = "the source of these series is not installed, so they cannot be refreshed"

    private const val RESTORE_ID_BYTES = 16

    private val random = SecureRandom()

    /**
     * What a restore was asked to do.
     *
     * The bootstrap handoff is optional and expressed before the import runs, because the category
     * overrides it carries are matched by name against the categories the import itself restores.
     */
    data class Request(
        val flags: BackupFlags,
        /** null means no handoff was requested */
        val handoffDefaultPolicy: MangaAcquisitionPolicy? = null,
        /** ordered: the first override matching a series' category wins */
        val handoffCategoryOverrides: List<BackupRestoreCategoryPolicy> = emptyList(),
    ) {
        val handoffRequested: Boolean
            get() = handoffDefaultPolicy != null
    }

    /**
     * Stages an uploaded backup and records the durable job that will restore it.
     *
     * The staged payload is removed again when the row cannot be written, so a failed request never
     * leaks a file into staging.
     */
    fun create(
        source: InputStream,
        request: Request,
        stagingRoot: File,
        now: Long = Instant.now().epochSecond,
    ): BackupRestoreJobDataClass {
        val restoreId = newRestoreId()
        val staged = BackupRestoreStaging.stage(source, stagingRoot, restoreId)

        return try {
            transaction {
                val jobId =
                    BackupRestoreJobTable
                        .insertAndGetId {
                            it[BackupRestoreJobTable.restoreId] = restoreId
                            it[state] = BackupRestoreJobState.QUEUED.name
                            it[phase] = BackupRestorePhase.PENDING.name
                            it[stagedRelativePath] = staged.relativePath
                            it[stagedSize] = staged.size
                            it[stagedSha256] = staged.sha256
                            it[flags] = BackupRestoreFlagsCodec.encode(request.flags)
                            it[categoryMapping] = ""
                            it[mangaIndex] = 0
                            it[mangaCount] = 0
                            it[errorCount] = 0
                            it[handoffState] =
                                if (request.handoffRequested) {
                                    BackupRestoreHandoffState.PENDING.name
                                } else {
                                    BackupRestoreHandoffState.NONE.name
                                }
                            it[handoffDefaultPolicy] = request.handoffDefaultPolicy?.name
                            it[handoffCategoryOverrides] = BackupRestoreCategoryPolicies.encode(request.handoffCategoryOverrides)
                            it[createdAt] = now
                            it[updatedAt] = now
                        }.value

                readById(jobId) ?: error("the backup restore job disappeared while it was being created")
            }
        } catch (e: Throwable) {
            runCatching { BackupRestoreStaging.delete(BackupRestoreStaging.stagedFile(stagingRoot, staged.relativePath)) }
                .onFailure { logger.warn { "Failed to remove the staged backup of a refused restore: ${failureKind(it)}" } }
            throw e
        }
    }

    // ------------------------------------------------------------------------------------------
    // worker support
    // ------------------------------------------------------------------------------------------

    /**
     * Atomically claims the oldest queued restore.
     *
     * The state is part of the claim, so two workers - or two server instances - can never continue
     * the same import.
     */
    fun claimNextQueued(now: Long = Instant.now().epochSecond): BackupRestoreJobDataClass? =
        transaction {
            val row =
                BackupRestoreJobTable
                    .selectAll()
                    .where { BackupRestoreJobTable.state eq BackupRestoreJobState.QUEUED.name }
                    .orderBy(BackupRestoreJobTable.id to SortOrder.ASC)
                    .forUpdate()
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction null

            val id = row[BackupRestoreJobTable.id].value

            BackupRestoreJobTable.update({ BackupRestoreJobTable.id eq id }) {
                it[state] = BackupRestoreJobState.RUNNING.name
                it[startedAt] = row[BackupRestoreJobTable.startedAt] ?: now
                it[updatedAt] = now
            }

            readById(id)
        }

    /**
     * Returns restores a shutdown left running to the queue.
     *
     * The phase and the series index are kept: the import resumes exactly where it stopped instead of
     * importing everything again.
     */
    fun recoverRunning(now: Long = Instant.now().epochSecond): Int =
        transaction {
            BackupRestoreJobTable.update({ BackupRestoreJobTable.state eq BackupRestoreJobState.RUNNING.name }) {
                it[state] = BackupRestoreJobState.QUEUED.name
                it[updatedAt] = now
            }
        }

    /**
     * Records the phase that is about to run.
     *
     * Writing the phase *before* its side effects is what makes a resume correct: a crash inside a
     * phase re-runs that phase, and every phase is idempotent, so redoing one is always safe.
     */
    fun advancePhase(
        id: Int,
        phase: BackupRestorePhase,
        mangaIndex: Int? = null,
        mangaCount: Int? = null,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            BackupRestoreJobTable.update({
                (BackupRestoreJobTable.id eq id) and (BackupRestoreJobTable.state eq BackupRestoreJobState.RUNNING.name)
            }) {
                it[BackupRestoreJobTable.phase] = phase.name
                mangaIndex?.also { index -> it[BackupRestoreJobTable.mangaIndex] = index }
                mangaCount?.also { count -> it[BackupRestoreJobTable.mangaCount] = count }
                it[updatedAt] = now
            } > 0
        }

    /** Records how many series of the backup were applied; the resume point of the MANGA phase. */
    fun recordMangaIndex(
        id: Int,
        index: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            BackupRestoreJobTable.update({
                (BackupRestoreJobTable.id eq id) and (BackupRestoreJobTable.state eq BackupRestoreJobState.RUNNING.name)
            }) {
                it[mangaIndex] = index
                it[updatedAt] = now
            } > 0
        }

    /** Persists the category mapping the series restore needs, so a resume does not rebuild it. */
    fun recordCategoryMapping(
        id: Int,
        mapping: Map<Int, Int>,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            BackupRestoreJobTable.update({
                (BackupRestoreJobTable.id eq id) and (BackupRestoreJobTable.state eq BackupRestoreJobState.RUNNING.name)
            }) {
                it[categoryMapping] = BackupRestoreCategoryMapping.encode(mapping)
                it[updatedAt] = now
            } > 0
        }

    /**
     * Records, or refreshes, the failure of one series.
     *
     * The report is keyed by the position of the series in the backup, so an interrupted restore that
     * reprocesses that series replaces its own report instead of adding a second one, and the recorded
     * error count cannot be inflated by a resume.
     */
    fun recordMangaFailure(
        id: Int,
        mangaIndex: Int,
        sourceId: Long? = null,
        sourceName: String? = null,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            if (readById(id) == null) {
                return@transaction false
            }

            upsertAudit(
                jobId = id,
                auditKey = mangaFailureAuditKey(mangaIndex),
                level = BackupRestoreAuditLevel.MANGA_ERROR,
                phase = BackupRestorePhase.MANGA,
                mangaIndex = mangaIndex,
                sourceId = sourceId,
                sourceName = sourceName,
                message = MANGA_FAILURE_MESSAGE,
                now = now,
            )
            recountMangaErrors(id, now)
            true
        }

    /**
     * Withdraws the failure of one series that later succeeded.
     *
     * An interrupted restore reprocesses the series it was applying, and that attempt can succeed. The
     * earlier report is then no longer true: leaving it would inflate the error count and exclude a
     * series that is in fact restored from the archive handoff.
     */
    fun clearMangaFailure(
        id: Int,
        mangaIndex: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            val removed =
                BackupRestoreJobAuditTable.deleteWhere {
                    (BackupRestoreJobAuditTable.job eq id) and
                        (BackupRestoreJobAuditTable.auditKey eq mangaFailureAuditKey(mangaIndex))
                } > 0

            if (removed) {
                recountMangaErrors(id, now)
            }
            removed
        }

    /**
     * Records, or refreshes, the report of a source the backup refers to that is not installed.
     *
     * Keyed by source id, so re-running the phase that produces these reports cannot duplicate them.
     */
    fun recordMissingSource(
        id: Int,
        phase: BackupRestorePhase,
        sourceId: Long,
        sourceName: String?,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            if (readById(id) == null) {
                return@transaction false
            }

            upsertAudit(
                jobId = id,
                auditKey = missingSourceAuditKey(sourceId),
                level = BackupRestoreAuditLevel.MISSING_SOURCE,
                phase = phase,
                mangaIndex = null,
                sourceId = sourceId,
                sourceName = sourceName,
                message = MISSING_SOURCE_MESSAGE,
                now = now,
            )
            true
        }

    /**
     * Writes the report identified by [auditKey], replacing an earlier one with the same key.
     *
     * The lookup and the write share this transaction and the key is unique per job, so a re-recorded
     * report updates its own row and a first-time report inserts exactly one.
     */
    private fun upsertAudit(
        jobId: Int,
        auditKey: String,
        level: BackupRestoreAuditLevel,
        phase: BackupRestorePhase,
        mangaIndex: Int?,
        sourceId: Long?,
        sourceName: String?,
        message: String,
        now: Long,
    ) {
        val existing =
            BackupRestoreJobAuditTable
                .selectAll()
                .where {
                    (BackupRestoreJobAuditTable.job eq jobId) and
                        (BackupRestoreJobAuditTable.auditKey eq auditKey)
                }.limit(1)
                .firstOrNull()

        if (existing == null) {
            BackupRestoreJobAuditTable.insert {
                it[job] = jobId
                it[BackupRestoreJobAuditTable.auditKey] = auditKey
                it[BackupRestoreJobAuditTable.level] = level.name
                it[BackupRestoreJobAuditTable.phase] = phase.name
                it[BackupRestoreJobAuditTable.mangaIndex] = mangaIndex
                it[BackupRestoreJobAuditTable.sourceId] = sourceId
                it[BackupRestoreJobAuditTable.sourceName] = sourceName
                it[BackupRestoreJobAuditTable.message] = message.take(MAX_MESSAGE_LENGTH)
                it[createdAt] = now
            }
            return
        }

        val rowId = existing[BackupRestoreJobAuditTable.id].value
        BackupRestoreJobAuditTable.update({ BackupRestoreJobAuditTable.id eq rowId }) {
            it[BackupRestoreJobAuditTable.level] = level.name
            it[BackupRestoreJobAuditTable.phase] = phase.name
            it[BackupRestoreJobAuditTable.mangaIndex] = mangaIndex
            it[BackupRestoreJobAuditTable.sourceId] = sourceId
            it[BackupRestoreJobAuditTable.sourceName] = sourceName
            it[BackupRestoreJobAuditTable.message] = message.take(MAX_MESSAGE_LENGTH)
            it[createdAt] = now
        }
    }

    /**
     * Recomputes the recorded failure count from the reports themselves.
     *
     * The count is denormalized for the status query, and because a report can be withdrawn as well as
     * added, a counter that is only ever incremented would drift. Recounting is one indexed count, on an
     * operation that happens only for a series that failed or recovered.
     */
    private fun recountMangaErrors(
        id: Int,
        now: Long,
    ) {
        val errors =
            BackupRestoreJobAuditTable
                .selectAll()
                .where {
                    (BackupRestoreJobAuditTable.job eq id) and
                        (BackupRestoreJobAuditTable.level eq BackupRestoreAuditLevel.MANGA_ERROR.name)
                }.count()
                .toInt()

        BackupRestoreJobTable.update({ BackupRestoreJobTable.id eq id }) {
            it[errorCount] = errors
            it[updatedAt] = now
        }
    }

    /** Positions in the backup whose series could not be restored, in ascending order. */
    fun failedMangaIndexes(id: Int): List<Int> =
        transaction {
            BackupRestoreJobAuditTable
                .selectAll()
                .where {
                    (BackupRestoreJobAuditTable.job eq id) and
                        (BackupRestoreJobAuditTable.level eq BackupRestoreAuditLevel.MANGA_ERROR.name)
                }.mapNotNull { it[BackupRestoreJobAuditTable.mangaIndex] }
                .distinct()
                .sorted()
        }

    /** Source ids already reported as missing for this restore, so a re-run cannot duplicate them. */
    fun missingSourceAuditIds(id: Int): Set<Long> =
        transaction {
            BackupRestoreJobAuditTable
                .selectAll()
                .where {
                    (BackupRestoreJobAuditTable.job eq id) and
                        (BackupRestoreJobAuditTable.level eq BackupRestoreAuditLevel.MISSING_SOURCE.name)
                }.mapNotNull { it[BackupRestoreJobAuditTable.sourceId] }
                .toSet()
        }

    /** Records that the import itself succeeded; the handoff is a separate step after this. */
    fun completeJob(
        id: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            BackupRestoreJobTable.update({
                (BackupRestoreJobTable.id eq id) and (BackupRestoreJobTable.state eq BackupRestoreJobState.RUNNING.name)
            }) {
                it[state] = BackupRestoreJobState.SUCCESS.name
                it[BackupRestoreJobTable.phase] = BackupRestorePhase.COMPLETED.name
                it[finishedAt] = now
                it[updatedAt] = now
            } > 0
        }

    fun failJob(
        id: Int,
        error: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            BackupRestoreJobTable.update({
                (BackupRestoreJobTable.id eq id) and (BackupRestoreJobTable.state eq BackupRestoreJobState.RUNNING.name)
            }) {
                it[state] = BackupRestoreJobState.FAILURE.name
                it[lastError] = error.take(MAX_MESSAGE_LENGTH)
                it[finishedAt] = now
                it[updatedAt] = now
            } > 0
        }

    /** Records the outcome of the optional archive-bootstrap handoff. */
    fun setHandoffState(
        id: Int,
        state: BackupRestoreHandoffState,
        sessionId: Int? = null,
        error: String? = null,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            BackupRestoreJobTable.update({ BackupRestoreJobTable.id eq id }) {
                it[handoffState] = state.name
                it[handoffSessionId] = sessionId
                it[handoffError] = error?.take(MAX_MESSAGE_LENGTH)
                it[updatedAt] = now
            } > 0
        }

    fun markStagedDeleted(
        id: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            BackupRestoreJobTable.update({
                (BackupRestoreJobTable.id eq id) and (BackupRestoreJobTable.stagedDeletedAt.isNull())
            }) {
                it[stagedDeletedAt] = now
                it[updatedAt] = now
            } > 0
        }

    /** Restores of a finished import whose optional handoff was requested but never completed. */
    fun jobsAwaitingHandoff(): List<BackupRestoreJobDataClass> =
        transaction {
            BackupRestoreJobTable
                .selectAll()
                .where {
                    (BackupRestoreJobTable.state eq BackupRestoreJobState.SUCCESS.name) and
                        (BackupRestoreJobTable.handoffState eq BackupRestoreHandoffState.PENDING.name)
                }.orderBy(BackupRestoreJobTable.id to SortOrder.ASC)
                .map { BackupRestoreJobTable.toDataClass(it) }
        }

    // ------------------------------------------------------------------------------------------
    // explicit operations
    // ------------------------------------------------------------------------------------------

    /** Abandons a restore. A running one stops at its next series boundary. */
    fun cancel(
        restoreId: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            BackupRestoreJobTable.update({
                (BackupRestoreJobTable.restoreId eq restoreId) and
                    (
                        BackupRestoreJobTable.state inList
                            listOf(
                                BackupRestoreJobState.QUEUED.name,
                                BackupRestoreJobState.RUNNING.name,
                            )
                    )
            }) {
                it[state] = BackupRestoreJobState.CANCELLED.name
                it[cancelledAt] = now
                it[finishedAt] = now
                it[updatedAt] = now
            } > 0
        }

    /**
     * Returns a failed or cancelled restore to the queue.
     *
     * The phase and the series index are preserved, so a retry continues instead of importing
     * everything again; the caller refuses the retry when the staged payload is already gone.
     */
    fun retry(
        restoreId: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            BackupRestoreJobTable.update({
                (BackupRestoreJobTable.restoreId eq restoreId) and
                    (
                        BackupRestoreJobTable.state inList
                            listOf(
                                BackupRestoreJobState.FAILURE.name,
                                BackupRestoreJobState.CANCELLED.name,
                            )
                    )
            }) {
                it[state] = BackupRestoreJobState.QUEUED.name
                it[lastError] = null
                it[finishedAt] = null
                it[cancelledAt] = null
                it[updatedAt] = now
            } > 0
        }

    /**
     * Deletes the staged payload of a finished restore.
     *
     * Refused while the restore is still running or queued, because the payload is exactly what it
     * would resume from.
     */
    fun cleanupStaged(
        restoreId: String,
        stagingRoot: File,
        now: Long = Instant.now().epochSecond,
    ): Boolean {
        val job = get(restoreId) ?: return false
        if (!job.state.isTerminal || !job.stagedFileRetained) {
            return false
        }

        BackupRestoreStaging.delete(BackupRestoreStaging.stagedFile(stagingRoot, job.stagedRelativePath))
        return markStagedDeleted(job.id, now)
    }

    // ------------------------------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------------------------------

    fun get(restoreId: String): BackupRestoreJobDataClass? =
        transaction {
            BackupRestoreJobTable
                .selectAll()
                .where { BackupRestoreJobTable.restoreId eq restoreId }
                .firstOrNull()
                ?.let { BackupRestoreJobTable.toDataClass(it) }
        }

    fun getById(id: Int): BackupRestoreJobDataClass? = transaction { readById(id) }

    /** The newest restore, which is what a client shows without knowing the id. */
    fun getLatest(): BackupRestoreJobDataClass? =
        transaction {
            BackupRestoreJobTable
                .selectAll()
                .orderBy(BackupRestoreJobTable.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.let { BackupRestoreJobTable.toDataClass(it) }
        }

    /** Resolves the staged payload of [job], refusing a relative path that escapes the staging root. */
    fun stagedFileOf(
        job: BackupRestoreJobDataClass,
        stagingRoot: File,
    ): File = BackupRestoreStaging.stagedFile(stagingRoot, job.stagedRelativePath)

    private fun readById(id: Int): BackupRestoreJobDataClass? =
        BackupRestoreJobTable
            .selectAll()
            .where { BackupRestoreJobTable.id eq id }
            .firstOrNull()
            ?.let { BackupRestoreJobTable.toDataClass(it) }

    private fun newRestoreId(): String {
        val bytes = ByteArray(RESTORE_ID_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/** Audit key of the failure report of one series, deterministic so re-recording is idempotent. */
internal fun mangaFailureAuditKey(mangaIndex: Int): String = "MANGA:$mangaIndex"

/** Audit key of the report of one unavailable source, deterministic so re-recording is idempotent. */
internal fun missingSourceAuditKey(sourceId: Long): String = "SOURCE:$sourceId"

/**
 * A bounded, sanitized reason for a failure this server wants to remember.
 *
 * Neither the message of a failure nor the message of an exception may be persisted or logged: both can
 * carry a series title, a source url, the staging location or a credential, and this value reaches an
 * authenticated GraphQL reader. Only the exception *type* is kept, so the value is derived from the
 * failure's shape rather than from its text.
 */
internal fun backupRestoreDiagnostic(error: Throwable?): String = backupRestoreReason("the operation failed", error)

/** A sanitized reason prefixed with a static [context] the caller supplies. */
internal fun backupRestoreReason(
    context: String,
    error: Throwable?,
): String = "$context (${failureKind(error)})"

/**
 * The type of a failure, never its message.
 *
 * A type name is structural: it says what kind of failure this was without repeating any of the data
 * that produced it.
 */
internal fun failureKind(error: Throwable?): String = error?.javaClass?.simpleName ?: "unknown error"
