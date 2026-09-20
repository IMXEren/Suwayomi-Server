package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonPageDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionPageAlignmentState
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisFailure
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisState
import suwayomi.tachidesk.manga.model.table.ChapterRevisionComparisonPageTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionComparisonTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.time.Instant

/**
 * Floor for the idle wait.
 *
 * A persisted due time in the past must not turn an idle wait into a busy loop.
 */
private const val MINIMUM_IDLE_MILLIS = 1_000L

/** Counts of one complete alignment, plus the reasons it had to be degraded. */
data class ChapterRevisionComparisonSummary(
    val baselinePageCount: Int,
    val candidatePageCount: Int,
    val exactCount: Int,
    val visuallyEquivalentCount: Int,
    val modifiedCount: Int,
    val addedCount: Int,
    val removedCount: Int,
    val hammingThreshold: Int,
    val algorithmVersion: String,
    val allPagesVisuallyEquivalent: Boolean,
    val hasLimitations: Boolean,
    val limitations: String?,
)

/** One aligned row as it is persisted. */
data class ChapterRevisionComparisonPageRecord(
    val ordinal: Int,
    val baselinePageIndex: Int?,
    val candidatePageIndex: Int?,
    val state: ChapterRevisionPageAlignmentState,
    val baselineExactHash: String?,
    val candidateExactHash: String?,
    val baselinePerceptualHash: String?,
    val candidatePerceptualHash: String?,
    val hammingDistance: Int?,
    val baselineWidth: Int?,
    val baselineHeight: Int?,
    val candidateWidth: Int?,
    val candidateHeight: Int?,
    val baselineSize: Long?,
    val candidateSize: Long?,
    /** the preview of the baseline side of this row, or null when it has none */
    val baselineThumbnailRelativePath: String?,
    val baselineThumbnailSha256: String?,
    val baselineThumbnailSize: Long?,
    /** the preview of the candidate side of this row, or null when it has none */
    val candidateThumbnailRelativePath: String?,
    val candidateThumbnailSha256: String?,
    val candidateThumbnailSize: Long?,
)

/** What committing one analysis did. */
sealed interface ChapterRevisionVisualCommit {
    /**
     * The summary was stored against the baseline that is still active.
     *
     * [unchanged] is true when the analysis - or the exact-digest re-check that replaced it - proved
     * the revision carries nothing the archive does not already hold.
     */
    data class Applied(
        val revision: ChapterRevisionDataClass,
        val unchanged: Boolean,
        /**
         * Whether this outcome stored a summary. The exact-digest and no-baseline paths settle the
         * revision without producing one, which is what tells the caller that the pages it just
         * rendered describe nothing anybody can read back.
         */
        val summaryStored: Boolean,
    ) : ChapterRevisionVisualCommit

    /**
     * The active revision of the chapter moved while the analysis ran.
     *
     * The result describes the wrong baseline, so it is discarded and the revision goes back to the
     * queue to be analysed against the revision that is active now. Publishing it anyway would put a
     * comparison of the old baseline into the review UI as if it were current.
     */
    data object BaselineMoved : ChapterRevisionVisualCommit

    /** The revision was no longer being analysed - a cancel or a retry won the race. */
    data object Stale : ChapterRevisionVisualCommit
}

/**
 * Durable backlog, results and audit of the visual page comparison.
 *
 * Kept apart from [ChapterRevision] because it is a dimension of its own with its own claim rules;
 * the only thing it shares with that object is the chapter-identity lock, which is what makes a
 * comparison safe against a concurrent acceptance.
 */
object ChapterRevisionComparisonStore {
    private const val MAX_LIMITATIONS_LENGTH = 1024

    private val retryableStates =
        listOf(
            ChapterVisualAnalysisState.COMPLETE,
            ChapterVisualAnalysisState.COMPLETE_WITH_LIMITATIONS,
            ChapterVisualAnalysisState.FAILED,
        )

    /**
     * Atomically claims the oldest due candidate whose visual analysis is owed.
     *
     * Only `QUEUED` rows whose persisted due time has passed are claimed, so a requeued row cannot be
     * picked up again before its retry instant and a failed analysis cannot spin.
     */
    fun claimNext(now: Long = Instant.now().epochSecond): ChapterRevisionDataClass? =
        transaction {
            val candidate =
                ChapterRevisionTable
                    .selectAll()
                    .where { visualAnalysisDueCondition(now) }
                    .orderBy(
                        ChapterRevisionTable.visualAnalysisNextAttemptAt to SortOrder.ASC_NULLS_FIRST,
                        ChapterRevisionTable.id to SortOrder.ASC,
                    ).forUpdate()
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction null

            val id = candidate[ChapterRevisionTable.id].value

            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.visualAnalysisState eq ChapterVisualAnalysisState.QUEUED.name)
            }) {
                it[ChapterRevisionTable.visualAnalysisState] = ChapterVisualAnalysisState.ANALYZING.name
                it[visualAnalysisAttempts] = candidate[ChapterRevisionTable.visualAnalysisAttempts] + 1
                it[visualAnalysisLastAttemptAt] = now
                it[visualAnalysisNextAttemptAt] = null
                it[visualAnalysisLastError] = null
                it[visualAnalysisLastFailure] = null
                it[updatedAt] = now
            }

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.id eq id }
                .first()
                .let { ChapterRevisionTable.toDataClass(it) }
        }

    /** Returns analyses a shutdown interrupted to the queue, preserving their attempt count. */
    fun recoverInterrupted(now: Long = Instant.now().epochSecond): Int =
        transaction {
            ChapterRevisionTable.update({
                ChapterRevisionTable.visualAnalysisState eq ChapterVisualAnalysisState.ANALYZING.name
            }) {
                it[visualAnalysisState] = ChapterVisualAnalysisState.QUEUED.name
                it[visualAnalysisNextAttemptAt] = now
                it[updatedAt] = now
            }
        }

    /** Earliest instant an owed analysis may run, or null when nothing is queued. */
    fun nextDueAt(now: Long = Instant.now().epochSecond): Long? =
        transaction {
            val row =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.visualAnalysisState eq ChapterVisualAnalysisState.QUEUED.name }
                    .orderBy(
                        ChapterRevisionTable.visualAnalysisNextAttemptAt to SortOrder.ASC_NULLS_FIRST,
                        ChapterRevisionTable.id to SortOrder.ASC,
                    ).limit(1)
                    .firstOrNull()
                    ?: return@transaction null

            val due = row[ChapterRevisionTable.visualAnalysisNextAttemptAt] ?: return@transaction now
            if (due <= now) now else due
        }

    /**
     * Pushes a failed analysis attempt forward to a persisted, retryable instant.
     *
     * The delay saturates, so a due time can never wrap into the past and turn the retry into a hot
     * loop.
     */
    fun requeue(
        id: Int,
        retryIntervalSeconds: Long,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.visualAnalysisState eq ChapterVisualAnalysisState.ANALYZING.name)
            }) {
                it[visualAnalysisState] = ChapterVisualAnalysisState.QUEUED.name
                it[visualAnalysisNextAttemptAt] = saturatingEpochAdd(now, retryIntervalSeconds)
                it[updatedAt] = now
            } > 0
        }

    /** Records that the revision never owed an analysis after all and opens the archive gate. */
    fun markNotRequired(
        id: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.visualAnalysisState eq ChapterVisualAnalysisState.ANALYZING.name)
            }) {
                it[visualAnalysisState] = ChapterVisualAnalysisState.NOT_REQUIRED.name
                it[visualAnalysisNextAttemptAt] = null
                it[visualAnalysisCompletedAt] = now
                it[visualAnalysisLastError] = null
                it[visualAnalysisLastFailure] = null
                it[updatedAt] = now
            } > 0
        }

    /**
     * Records that the analysis is over without a summary.
     *
     * The revision keeps its acquired bytes and proceeds to archival and review with this audit
     * attached: a comparison that could not be produced must never cost the content it describes.
     *
     * [failure] is a category rather than a message, so nothing that happened on disk - a path, a URL,
     * a source error - can ever reach the stored audit or the archive manifest.
     */
    fun markFailed(
        id: Int,
        failure: ChapterVisualAnalysisFailure,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.visualAnalysisState eq ChapterVisualAnalysisState.ANALYZING.name)
            }) {
                it[visualAnalysisState] = ChapterVisualAnalysisState.FAILED.name
                it[visualAnalysisLastError] = failure.message
                it[visualAnalysisLastFailure] = failure.name
                it[visualAnalysisNextAttemptAt] = null
                it[visualAnalysisCompletedAt] = now
                it[updatedAt] = now
            } > 0
        }

    /**
     * Re-queues analyses an operator asked for again.
     *
     * Only a revision that has not been archived yet may be re-analysed. The comparison is part of the
     * immutable archive manifest of a committed revision, so replacing it would leave that manifest
     * describing a comparison the archive no longer holds; re-comparing a committed revision would
     * need a new, versioned derived artifact rather than an overwrite of this one.
     *
     * Every settled state may still be retried, because the analysis is derived data: re-running it
     * replaces the stored summary and never touches the revision's content or acquisition. The
     * previous summary is left in place until the new one commits, so a retry that fails does not
     * destroy the audit it was meant to improve.
     */
    fun retry(
        ids: List<Int>,
        now: Long = Instant.now().epochSecond,
    ): List<ChapterRevisionDataClass> {
        if (ids.isEmpty()) {
            return emptyList()
        }

        return transaction {
            // the ids are selected before the update, under their own lock, so the result is exactly
            // what this call re-queued. Selecting after the update would also return rows that were
            // already queued when the call arrived, and report them as freshly retried.
            val retriedIds =
                ChapterRevisionTable
                    .selectAll()
                    .where { retryableCondition(ids) }
                    .forUpdate()
                    .orderBy(ChapterRevisionTable.id to SortOrder.ASC)
                    .map { it[ChapterRevisionTable.id].value }

            if (retriedIds.isEmpty()) {
                return@transaction emptyList()
            }

            ChapterRevisionTable.update({ ChapterRevisionTable.id inList retriedIds }) {
                it[visualAnalysisState] = ChapterVisualAnalysisState.QUEUED.name
                it[visualAnalysisNextAttemptAt] = now
                it[visualAnalysisLastError] = null
                it[visualAnalysisLastFailure] = null
                it[updatedAt] = now
            }

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.id inList retriedIds }
                .orderBy(ChapterRevisionTable.id to SortOrder.ASC)
                .map { ChapterRevisionTable.toDataClass(it) }
        }
    }

    /** The revisions an explicit retry may re-queue: settled, and not yet archived. */
    private fun retryableCondition(ids: List<Int>): Op<Boolean> =
        (ChapterRevisionTable.id inList ids) and
            (ChapterRevisionTable.visualAnalysisState inList retryableStates.map { it.name }) and
            (ChapterRevisionTable.archiveState eq ChapterArchiveState.NOT_COMMITTED.name)

    /** The stored summary of one revision, or null when it was never analysed. */
    fun getComparison(revisionId: Int): ChapterRevisionComparisonDataClass? =
        transaction {
            ChapterRevisionComparisonTable
                .selectAll()
                .where { ChapterRevisionComparisonTable.revision eq revisionId }
                .firstOrNull()
                ?.let { ChapterRevisionComparisonTable.toDataClass(it) }
        }

    /** The ordered alignment of one revision. */
    fun getPages(revisionId: Int): List<ChapterRevisionComparisonPageDataClass> =
        transaction {
            ChapterRevisionComparisonPageTable
                .selectAll()
                .where { ChapterRevisionComparisonPageTable.revision eq revisionId }
                .orderBy(ChapterRevisionComparisonPageTable.ordinal to SortOrder.ASC)
                .map { ChapterRevisionComparisonPageTable.toDataClass(it) }
        }

    /**
     * One aligned row of one revision, or null when that revision has no such ordinal.
     *
     * Addressing a row by its revision is what authorises it: a row belongs to the candidate it was
     * stored under, so asking with a different revision can never reach another one's alignment.
     */
    fun getPage(
        revisionId: Int,
        ordinal: Int,
    ): ChapterRevisionComparisonPageDataClass? =
        transaction {
            ChapterRevisionComparisonPageTable
                .selectAll()
                .where {
                    (ChapterRevisionComparisonPageTable.revision eq revisionId) and
                        (ChapterRevisionComparisonPageTable.ordinal eq ordinal)
                }.firstOrNull()
                ?.let { ChapterRevisionComparisonPageTable.toDataClass(it) }
        }

    /**
     * How many aligned rows one revision has.
     *
     * A client that pages through an alignment has to know how many rows exist in total, and counting
     * them here is one indexed count instead of loading every row the connection is about to page past.
     */
    fun countPages(revisionId: Int): Int =
        transaction {
            ChapterRevisionComparisonPageTable
                .selectAll()
                .where { ChapterRevisionComparisonPageTable.revision eq revisionId }
                .count()
                .toInt()
        }

    /**
     * Stores a finished analysis and opens the archive gate for the revision.
     *
     * The active baseline is re-read under the chapter-identity lock, so a comparison can only ever be
     * committed against the revision that is active at that instant. A revision whose baseline moved is
     * requeued instead of being published with a summary of a baseline nobody is comparing to.
     */
    fun commit(
        revisionId: Int,
        summary: ChapterRevisionComparisonSummary,
        pages: List<ChapterRevisionComparisonPageRecord>,
        autoDismissVisuallyEquivalent: Boolean,
        retryIntervalSeconds: Long,
        now: Long = Instant.now().epochSecond,
    ): ChapterRevisionVisualCommit =
        transaction {
            val claimed =
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        (ChapterRevisionTable.id eq revisionId) and
                            (ChapterRevisionTable.visualAnalysisState eq ChapterVisualAnalysisState.ANALYZING.name)
                    }.forUpdate()
                    .firstOrNull()
                    ?.let { ChapterRevisionTable.toDataClass(it) }
                    ?: return@transaction ChapterRevisionVisualCommit.Stale

            // the same lock an acceptance decision takes, so the baseline cannot move between the read
            // below and the write that follows it
            ChapterRevision.lockChapterIdentityForComparison(claimed.chapterKey)

            val active =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.activeChapterKey eq claimed.chapterKey }
                    .limit(1)
                    .firstOrNull()
                    ?.let { ChapterRevisionTable.toDataClass(it) }

            if (active == null) {
                // nothing is active any more, so there is no baseline to compare against at all
                return@transaction applyWithoutComparison(
                    revisionId = claimed.id,
                    comparisonState = ChapterRevisionComparisonState.NO_BASELINE,
                    baselineId = null,
                    now = now,
                )
            }

            if (active.id != claimed.comparisonBaselineRevisionId) {
                // the exact digest is re-checked first: the move may have made the analysis moot
                if (active.contentHash != null && active.contentHash == claimed.contentHash) {
                    return@transaction applyWithoutComparison(
                        revisionId = claimed.id,
                        comparisonState = ChapterRevisionComparisonState.EXACT_MATCH,
                        baselineId = active.id,
                        now = now,
                    )
                }

                ChapterRevisionTable.update({
                    (ChapterRevisionTable.id eq revisionId) and
                        (ChapterRevisionTable.visualAnalysisState eq ChapterVisualAnalysisState.ANALYZING.name)
                }) {
                    it[visualAnalysisState] = ChapterVisualAnalysisState.QUEUED.name
                    it[comparisonBaselineRevision] = active.id
                    it[visualAnalysisNextAttemptAt] = saturatingEpochAdd(now, retryIntervalSeconds)
                    it[updatedAt] = now
                }

                return@transaction ChapterRevisionVisualCommit.BaselineMoved
            }

            // auto-dismissal is only honest when the whole alignment proved equivalence *and* nothing
            // about it was degraded: a bounded anchor alignment or an undecodable page means part of
            // the chapter was never actually compared, and that is exactly what a human must look at
            val autoDismiss =
                autoDismissVisuallyEquivalent && summary.allPagesVisuallyEquivalent && !summary.hasLimitations

            ChapterRevisionComparisonTable.deleteWhere { ChapterRevisionComparisonTable.revision eq revisionId }
            ChapterRevisionComparisonPageTable.deleteWhere { ChapterRevisionComparisonPageTable.revision eq revisionId }

            val comparisonId =
                ChapterRevisionComparisonTable
                    .insertAndGetId {
                        it[revision] = revisionId
                        it[baselineRevision] = active.id
                        it[baselinePageCount] = summary.baselinePageCount
                        it[candidatePageCount] = summary.candidatePageCount
                        it[exactCount] = summary.exactCount
                        it[visuallyEquivalentCount] = summary.visuallyEquivalentCount
                        it[modifiedCount] = summary.modifiedCount
                        it[addedCount] = summary.addedCount
                        it[removedCount] = summary.removedCount
                        it[alignedCount] = pages.size
                        it[hammingThreshold] = summary.hammingThreshold
                        it[algorithmVersion] = summary.algorithmVersion
                        it[allPagesVisuallyEquivalent] = summary.allPagesVisuallyEquivalent
                        it[hasLimitations] = summary.hasLimitations
                        it[limitations] = summary.limitations?.take(MAX_LIMITATIONS_LENGTH)
                        it[createdAt] = now
                        it[updatedAt] = now
                    }

            pages.forEach { page ->
                ChapterRevisionComparisonPageTable.insert {
                    it[comparison] = comparisonId.value
                    it[revision] = revisionId
                    it[ordinal] = page.ordinal
                    it[baselinePageIndex] = page.baselinePageIndex
                    it[candidatePageIndex] = page.candidatePageIndex
                    it[state] = page.state.name
                    it[baselineExactHash] = page.baselineExactHash
                    it[candidateExactHash] = page.candidateExactHash
                    it[baselinePerceptualHash] = page.baselinePerceptualHash
                    it[candidatePerceptualHash] = page.candidatePerceptualHash
                    it[hammingDistance] = page.hammingDistance
                    it[baselineWidth] = page.baselineWidth
                    it[baselineHeight] = page.baselineHeight
                    it[candidateWidth] = page.candidateWidth
                    it[candidateHeight] = page.candidateHeight
                    it[baselineSize] = page.baselineSize
                    it[candidateSize] = page.candidateSize
                    it[baselineThumbnailRelativePath] = page.baselineThumbnailRelativePath
                    it[baselineThumbnailSha256] = page.baselineThumbnailSha256
                    it[baselineThumbnailSize] = page.baselineThumbnailSize
                    it[candidateThumbnailRelativePath] = page.candidateThumbnailRelativePath
                    it[candidateThumbnailSha256] = page.candidateThumbnailSha256
                    it[candidateThumbnailSize] = page.candidateThumbnailSize
                    it[createdAt] = now
                }
            }

            ChapterRevisionTable.update({ ChapterRevisionTable.id eq revisionId }) {
                it[visualAnalysisState] =
                    if (summary.hasLimitations) {
                        ChapterVisualAnalysisState.COMPLETE_WITH_LIMITATIONS.name
                    } else {
                        ChapterVisualAnalysisState.COMPLETE.name
                    }
                it[visualAnalysisNextAttemptAt] = null
                it[visualAnalysisCompletedAt] = now
                it[visualAnalysisLastError] = null
                it[visualAnalysisLastFailure] = null
                it[visualAnalysisLastAttemptAt] = now
                it[comparisonBaselineRevision] = active.id
                it[comparedAt] = now
                if (autoDismiss) {
                    // the whole alignment proved the candidate carries nothing new, so it is terminal
                    // exactly like a byte-identical duplicate - but the schedule of its staged removal
                    // is written here so a crash cannot leak the pages
                    it[disposition] = ChapterRevisionDisposition.UNCHANGED.name
                    it[comparisonCleanupDueAt] = now
                }
                it[updatedAt] = now
            }

            val stored =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id eq revisionId }
                    .first()
                    .let { ChapterRevisionTable.toDataClass(it) }

            ChapterRevisionVisualCommit.Applied(stored, unchanged = autoDismiss, summaryStored = true)
        }

    /**
     * Records that there is no comparison to make after all.
     *
     * This is the path for "nothing is active" and for "the exact digest already matched": both mean
     * the revision owes no analysis, so the archive gate opens and the staged pages become cleanable
     * in the byte-identical case.
     *
     * A comparison that was already committed is deleted in the same transaction. The schema holds at
     * most one summary per revision and both the manifest writer and the review query read it as the
     * *current* comparison, so leaving a summary of the baseline this analysis just refused to compare
     * against would present a comparison nobody can reproduce as the revision's answer.
     */
    private fun applyWithoutComparison(
        revisionId: Int,
        comparisonState: ChapterRevisionComparisonState,
        baselineId: Int?,
        now: Long,
    ): ChapterRevisionVisualCommit {
        // pages first, then the parent: rows before the summary they belong to, so the deletion is
        // correct whether or not the parent's cascade runs
        ChapterRevisionComparisonPageTable.deleteWhere { ChapterRevisionComparisonPageTable.revision eq revisionId }
        ChapterRevisionComparisonTable.deleteWhere { ChapterRevisionComparisonTable.revision eq revisionId }

        ChapterRevisionTable.update({
            (ChapterRevisionTable.id eq revisionId) and
                (ChapterRevisionTable.visualAnalysisState eq ChapterVisualAnalysisState.ANALYZING.name)
        }) {
            it[ChapterRevisionTable.comparisonState] = comparisonState.name
            it[comparisonBaselineRevision] = baselineId
            it[comparedAt] = now
            it[ChapterRevisionTable.visualAnalysisState] = ChapterVisualAnalysisState.NOT_REQUIRED.name
            it[visualAnalysisNextAttemptAt] = null
            it[visualAnalysisCompletedAt] = now
            it[visualAnalysisLastError] = null
            it[visualAnalysisLastFailure] = null
            if (comparisonState == ChapterRevisionComparisonState.EXACT_MATCH) {
                it[disposition] = ChapterRevisionDisposition.UNCHANGED.name
                it[comparisonCleanupDueAt] = now
            }
            it[updatedAt] = now
        }

        val stored =
            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.id eq revisionId }
                .first()
                .let { ChapterRevisionTable.toDataClass(it) }

        return ChapterRevisionVisualCommit.Applied(
            stored,
            unchanged = comparisonState == ChapterRevisionComparisonState.EXACT_MATCH,
            summaryStored = false,
        )
    }

    private fun visualAnalysisDueCondition(now: Long): Op<Boolean> =
        (ChapterRevisionTable.visualAnalysisState eq ChapterVisualAnalysisState.QUEUED.name) and
            (
                (ChapterRevisionTable.visualAnalysisNextAttemptAt.isNull()) or
                    (ChapterRevisionTable.visualAnalysisNextAttemptAt lessEq now)
            )
}

/**
 * Produces one comparison: reads both sides, aligns them, renders the review thumbnails and commits
 * the result against the active baseline.
 *
 * The seams are injectable so the orchestration - which is the part that has to survive a restart and
 * a moving baseline - can be tested without a real archive on disk.
 */
class ChapterRevisionVisualAnalysisProcessor(
    private val stagingRoot: () -> File,
    private val archiveRoot: () -> File,
    private val hammingThreshold: () -> Int = { 2 },
    private val autoDismissVisuallyEquivalent: () -> Boolean = { false },
    private val thumbnailMaxDimension: () -> Int = { 480 },
    private val retryIntervalSeconds: () -> Long = { 300 },
    private val maxAttempts: () -> Int = { 3 },
    private val loadRevision: (Int) -> ChapterRevisionDataClass? = { ChapterRevision.getRevision(it) },
    private val onArchiveDue: () -> Unit = {},
    private val onCleanupDue: () -> Unit = {},
) {
    /**
     * Produces one comparison for a claimed revision.
     *
     * [now] is the instant of the orchestration that owns this attempt, so every state transition the
     * processing makes - the commit, a retry's due time, a failure's completion - is stamped with the
     * same clock instead of reading the wall clock several times in one run.
     */
    suspend fun process(
        claimed: ChapterRevisionDataClass,
        now: Long = Instant.now().epochSecond,
    ) {
        val baselineId = claimed.comparisonBaselineRevisionId
        if (baselineId == null || claimed.comparisonState != ChapterRevisionComparisonState.CONTENT_CHANGED) {
            // nothing to compare against, so the revision never owed an analysis
            finish(claimed, onArchiveDue, now)
            return
        }

        val baseline = loadRevision(baselineId)
        if (baseline == null) {
            // the row is gone; the database nulls the reference when that happens, so this is only
            // reachable through a race and the honest answer is "nothing to compare to"
            finish(claimed, onArchiveDue, now)
            return
        }

        val attempt = claimed.visualAnalysisAttempts
        val candidatePath = claimed.candidatePath
        if (candidatePath == null) {
            abandon(claimed, attempt, ChapterVisualAnalysisFailure.STAGING_MISSING, now)
            return
        }

        val baselineCbz = resolveBaselineArtifact(baseline)
        if (baselineCbz == null) {
            abandon(claimed, attempt, ChapterVisualAnalysisFailure.BASELINE_MISSING, now)
            return
        }

        val directory = File(stagingRoot(), candidatePath)

        try {
            val candidatePages =
                try {
                    ChapterRevisionPageImages.openDirectory(directory)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ChapterRevisionPageAccessException) {
                    abandon(claimed, attempt, ChapterVisualAnalysisFailure.CANDIDATE_UNREADABLE, now)
                    return
                }

            try {
                val baselinePages =
                    try {
                        ChapterRevisionPageImages.openArchive(baselineCbz)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ChapterRevisionPageAccessException) {
                        abandon(claimed, attempt, ChapterVisualAnalysisFailure.BASELINE_UNREADABLE, now)
                        return
                    }

                try {
                    analyze(claimed, attempt, baselinePages, candidatePages, now)
                } finally {
                    baselinePages.close()
                }
            } finally {
                candidatePages.close()
            }
        } catch (e: CancellationException) {
            // a shutdown in the middle of an attempt leaves previews that no reader will ever consult.
            // They are derived data keyed by this attempt, so they are removed here rather than left to
            // accumulate as the only trace of an analysis that never committed.
            discardRenderedArtifacts(claimed, attempt)
            throw e
        }
    }

    private suspend fun analyze(
        claimed: ChapterRevisionDataClass,
        attempt: Int,
        baselinePages: ChapterRevisionChapterPages,
        candidatePages: ChapterRevisionChapterPages,
        now: Long,
    ) {
        val threshold = hammingThreshold().coerceAtLeast(0)
        val alignment =
            ChapterRevisionVisualComparison.align(
                baseline = baselinePages.fingerprints(),
                candidate = candidatePages.fingerprints(),
                threshold = threshold,
            )

        val limitations =
            buildList {
                alignment.limitations?.let { add(it) }
                baselinePages.limitations.forEach { add("baseline $it") }
                candidatePages.limitations.forEach { add("candidate $it") }
            }

        val summary =
            summarize(
                alignment = alignment,
                baselinePageCount = baselinePages.pages.size,
                candidatePageCount = candidatePages.pages.size,
                threshold = threshold,
                limitations = limitations,
            )

        val records =
            renderRows(
                candidateKey = claimed.candidateKey,
                revisionId = claimed.id,
                attempt = attempt,
                alignment = alignment,
                baselinePages = baselinePages,
                candidatePages = candidatePages,
            )

        val outcome =
            ChapterRevisionComparisonStore.commit(
                revisionId = claimed.id,
                summary = summary,
                pages = records,
                autoDismissVisuallyEquivalent = autoDismissVisuallyEquivalent(),
                retryIntervalSeconds = retryIntervalSeconds(),
                now = now,
            )

        when (outcome) {
            is ChapterRevisionVisualCommit.Applied -> {
                // the commit is durable, so what this attempt rendered is decided now: a stored summary
                // is described by the rows just written and keeps only its own attempt, while a
                // revision that settled without one has no rows at all and so no preview may survive.
                // This is derived-data hygiene, and a failure or a shutdown during it must never take
                // the wake below with it.
                val interrupted = discardSettledPreviewArtifacts(claimed, attempt, keepAttempt = outcome.summaryStored)
                if (outcome.unchanged) {
                    // the staged pages are dead weight now; the sweep worker owns their removal
                    onCleanupDue()
                }
                // a terminal analysis is what opens the archive gate, so the archive is woken only here
                onArchiveDue()
                // the cancellation still propagates, but only after the settled revision has been handed
                // to the archive; if the process dies before that wake is consumed, the archive worker's
                // own startup drain claims the row on the next start
                interrupted?.let { throw it }
            }

            ChapterRevisionVisualCommit.BaselineMoved -> {
                // the previews describe the baseline that just stopped being active, so they are
                // removed rather than left behind for a later reader to mistake for the current ones
                discardComparisonAttemptThumbnails(stagingRoot(), claimed, attempt)
                // the requeue already carries a persisted due time, so the wake is what makes it
                // observable without waiting for the next unrelated event
                ChapterRevisionVisualAnalysisExecutor.notifyWorkAvailable()
            }

            ChapterRevisionVisualCommit.Stale -> {
                // the candidate was cancelled or retried while it was being analysed
                discardComparisonAttemptThumbnails(stagingRoot(), claimed, attempt)
            }
        }
    }

    /**
     * Renders the review thumbnails of the interesting rows and turns every row into a persisted
     * record.
     *
     * Only pages that actually need a preview are decoded again, and cancellation is checked between
     * pages so a shutdown does not keep a whole chapter of JPEGs being written.
     *
     * Every preview is rendered from the page the alignment just paired, into this attempt's own
     * directory. Nothing recorded by an earlier analysis is read back or trusted, because a digest
     * agreeing with a file on disk says nothing about whether that file shows the right baseline.
     */
    private suspend fun renderRows(
        candidateKey: String,
        revisionId: Int,
        attempt: Int,
        alignment: ChapterVisualAlignment,
        baselinePages: ChapterRevisionChapterPages,
        candidatePages: ChapterRevisionChapterPages,
    ): List<ChapterRevisionComparisonPageRecord> {
        val root = stagingRoot()
        val maxDimension = thumbnailMaxDimension()
        val rows = ArrayList<ChapterRevisionComparisonPageRecord>(alignment.pairs.size)

        alignment.pairs.forEach { pair ->
            currentCoroutineContext().ensureActive()

            val baselinePage = pair.baselineIndex?.let { baselinePages.pages.getOrNull(it) }
            val candidatePage = pair.candidateIndex?.let { candidatePages.pages.getOrNull(it) }

            // a rewritten page is reviewed by putting the two versions of it side by side, so a
            // MODIFIED row carries both previews; an ADDED page only exists in the candidate and a
            // REMOVED one only in the baseline
            val baselineIndex = pair.baselineIndex
            val candidateIndex = pair.candidateIndex
            val needsBothSides = pair.state == ChapterRevisionPageAlignmentState.MODIFIED
            val renderBaseline =
                baselineIndex != null &&
                    (needsBothSides || pair.state == ChapterRevisionPageAlignmentState.REMOVED)
            val renderCandidate =
                candidateIndex != null &&
                    (needsBothSides || pair.state == ChapterRevisionPageAlignmentState.ADDED)

            val baselineThumbnail =
                baselineIndex
                    ?.takeIf { renderBaseline }
                    ?.let { index ->
                        ChapterRevisionThumbnails.store(
                            stagingRoot = root,
                            candidateKey = candidateKey,
                            revisionId = revisionId,
                            attempt = attempt,
                            ordinal = pair.ordinal,
                            side = ChapterRevisionThumbnailSide.BASELINE,
                            maxDimension = maxDimension,
                            render = { baselinePages.decode(index, maxDimension) },
                        )
                    }

            val candidateThumbnail =
                candidateIndex
                    ?.takeIf { renderCandidate }
                    ?.let { index ->
                        ChapterRevisionThumbnails.store(
                            stagingRoot = root,
                            candidateKey = candidateKey,
                            revisionId = revisionId,
                            attempt = attempt,
                            ordinal = pair.ordinal,
                            side = ChapterRevisionThumbnailSide.CANDIDATE,
                            maxDimension = maxDimension,
                            render = { candidatePages.decode(index, maxDimension) },
                        )
                    }

            rows +=
                ChapterRevisionComparisonPageRecord(
                    ordinal = pair.ordinal,
                    baselinePageIndex = pair.baselineIndex,
                    candidatePageIndex = pair.candidateIndex,
                    state = pair.state,
                    baselineExactHash = baselinePage?.exactHash,
                    candidateExactHash = candidatePage?.exactHash,
                    baselinePerceptualHash = baselinePage?.perceptualHash,
                    candidatePerceptualHash = candidatePage?.perceptualHash,
                    hammingDistance = pair.hammingDistance,
                    baselineWidth = baselinePage?.width,
                    baselineHeight = baselinePage?.height,
                    candidateWidth = candidatePage?.width,
                    candidateHeight = candidatePage?.height,
                    baselineSize = baselinePage?.size,
                    candidateSize = candidatePage?.size,
                    baselineThumbnailRelativePath = baselineThumbnail?.relativePath,
                    baselineThumbnailSha256 = baselineThumbnail?.sha256,
                    baselineThumbnailSize = baselineThumbnail?.size,
                    candidateThumbnailRelativePath = candidateThumbnail?.relativePath,
                    candidateThumbnailSha256 = candidateThumbnail?.sha256,
                    candidateThumbnailSize = candidateThumbnail?.size,
                )
        }

        return rows
    }

    private fun summarize(
        alignment: ChapterVisualAlignment,
        baselinePageCount: Int,
        candidatePageCount: Int,
        threshold: Int,
        limitations: List<String>,
    ): ChapterRevisionComparisonSummary {
        var exact = 0
        var equivalent = 0
        var modified = 0
        var added = 0
        var removed = 0

        alignment.pairs.forEach { pair ->
            when (pair.state) {
                ChapterRevisionPageAlignmentState.EXACT -> exact++
                ChapterRevisionPageAlignmentState.VISUALLY_EQUIVALENT -> equivalent++
                ChapterRevisionPageAlignmentState.MODIFIED -> modified++
                ChapterRevisionPageAlignmentState.ADDED -> added++
                ChapterRevisionPageAlignmentState.REMOVED -> removed++
            }
        }

        return ChapterRevisionComparisonSummary(
            baselinePageCount = baselinePageCount,
            candidatePageCount = candidatePageCount,
            exactCount = exact,
            visuallyEquivalentCount = equivalent,
            modifiedCount = modified,
            addedCount = added,
            removedCount = removed,
            hammingThreshold = threshold,
            algorithmVersion = ChapterRevisionVisualComparison.ALGORITHM_VERSION,
            // every row has to be a pairing of two pages that look the same; anything added, removed or
            // modified means the candidate really carries something the baseline does not
            allPagesVisuallyEquivalent =
                alignment.pairs.isNotEmpty() &&
                    modified == 0 &&
                    added == 0 &&
                    removed == 0,
            hasLimitations = limitations.isNotEmpty(),
            limitations = limitations.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(1024),
        )
    }

    /** The immutable archived artifact of the baseline, preferring it over the active library copy. */
    private fun resolveBaselineArtifact(baseline: ChapterRevisionDataClass): File? {
        val root = archiveRoot()
        baseline.archiveCbzPath?.let { path -> File(root, path).takeIf { it.isFile }?.let { return it } }
        baseline.activeCbzPath?.let { path -> File(root, path).takeIf { it.isFile }?.let { return it } }
        return null
    }

    /** Terminal without a summary: the revision never owed one. */
    private fun finish(
        claimed: ChapterRevisionDataClass,
        onArchiveDue: () -> Unit,
        now: Long,
    ) {
        ChapterRevisionComparisonStore.markNotRequired(claimed.id, now = now)
        onArchiveDue()
    }

    /**
     * Records an attempt that could not produce a comparison.
     *
     * The revision is retried until its attempt budget is exhausted and only then reported as failed,
     * because the usual reason - an object-storage mount that is briefly unavailable - is transient.
     * Either way the content is kept; a failed comparison never discards the bytes it describes.
     *
     * Whatever the abandoned attempt rendered is removed first, so a preview of the wrong baseline
     * cannot be left behind for a later reader to mistake for the current one.
     */
    private fun abandon(
        claimed: ChapterRevisionDataClass,
        attempt: Int,
        failure: ChapterVisualAnalysisFailure,
        now: Long,
    ) {
        discardRenderedArtifacts(claimed, attempt)

        if (claimed.visualAnalysisAttempts >= maxAttempts().coerceAtLeast(1)) {
            ChapterRevisionComparisonStore.markFailed(claimed.id, failure, now = now)
            onArchiveDue()
            return
        }

        // a retry is never immediate: a zero interval would turn the requeue into a busy loop
        ChapterRevisionComparisonStore.requeue(claimed.id, retryIntervalSeconds().coerceAtLeast(1), now = now)
        ChapterRevisionVisualAnalysisExecutor.notifyWorkAvailable()
    }

    /** Removes what one attempt rendered; see [discardComparisonAttemptThumbnails]. */
    private fun discardRenderedArtifacts(
        claimed: ChapterRevisionDataClass,
        attempt: Int,
    ) = discardComparisonAttemptThumbnails(stagingRoot(), claimed, attempt)

    /**
     * Removes the previews a committed analysis left behind, and reports a cancellation instead of raising it.
     *
     * With [keepAttempt] the rows just written name [attempt], so only the other attempts of this
     * revision are unreferenced; without it the revision settled with no summary at all and every
     * attempt it ever rendered is unreferenced, including the one that just ran.
     *
     * @return the cancellation that interrupted the cleanup, so the caller can rethrow it once the
     * settled revision has been handed to the archive. Every other failure is ignored: an attempt
     * directory is derived data that nothing reads once the comparison it showed is gone.
     */
    private fun discardSettledPreviewArtifacts(
        claimed: ChapterRevisionDataClass,
        attempt: Int,
        keepAttempt: Boolean,
    ): CancellationException? =
        try {
            if (keepAttempt) {
                discardSupersededComparisonThumbnails(stagingRoot(), claimed, attempt)
            } else {
                ChapterRevisionThumbnails.deleteRevisionAttempts(stagingRoot(), claimed.candidateKey, claimed.id)
            }
            null
        } catch (e: CancellationException) {
            e
        } catch (e: Exception) {
            null
        }
}

/**
 * Removes what one abandoned analysis attempt rendered.
 *
 * Each attempt writes into its own directory, so this can never touch the previews a committed
 * comparison still points at. A failure is ignored on purpose: the artifacts are derived data that
 * the next attempt rewrites into a new directory, and no reader ever consults a directory it did not
 * write.
 */
internal fun discardComparisonAttemptThumbnails(
    stagingRoot: File,
    claimed: ChapterRevisionDataClass,
    attempt: Int,
) {
    try {
        ChapterRevisionThumbnails.deleteAttempt(stagingRoot, claimed.candidateKey, claimed.id, attempt)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // derived data: a leftover attempt directory is never read again
    }
}

/**
 * Removes the attempt directories a superseded comparison left behind.
 *
 * Called only after a commit has stored the new rows, so [keepAttempt] is the attempt those rows point
 * at and every other attempt of the same revision is unreferenced. A failure is ignored for the same
 * reason discarding one attempt is: the previews are derived data, and a reader only ever consults the
 * attempt the committed comparison names.
 */
internal fun discardSupersededComparisonThumbnails(
    stagingRoot: File,
    claimed: ChapterRevisionDataClass,
    keepAttempt: Int,
) {
    try {
        ChapterRevisionThumbnails.deleteSupersededAttempts(
            stagingRoot = stagingRoot,
            candidateKey = claimed.candidateKey,
            revisionId = claimed.id,
            keepAttempt = keepAttempt,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // derived data: the replacement's own previews are the ones the archive records
    }
}

/**
 * Single-concurrency loop over the visual analysis backlog.
 *
 * Concurrency is one for the same reason acquisition is: comparing a chapter decodes every page of
 * both sides, and running several at once would multiply that against the same disk and the same
 * source-independent archive mount.
 */
class ChapterRevisionVisualAnalysisLoop(
    private val processor: ChapterRevisionVisualAnalysisProcessor,
    private val stagingRoot: () -> File,
    private val retryIntervalSeconds: () -> Long = { 300 },
    private val maxAttempts: () -> Int = { 3 },
    private val onArchiveDue: () -> Unit = {},
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    private val logger = KotlinLogging.logger {}

    private val worker =
        ChapterRevisionWorkerLoop(
            workerName = "chapter revision visual analysis",
            beforeFirstDrain = {
                // a shutdown can leave a candidate mid analysis; it returns to the queue with its
                // attempt count intact so its budget is never silently reset
                ChapterRevisionComparisonStore.recoverInterrupted(now())
            },
            // a requeued attempt and a baseline that moved both have a persisted due time, so the wait
            // is bounded without polling; with nothing queued the worker sleeps until an explicit wake
            idleTimeoutMillis = {
                val current = now()
                ChapterRevisionComparisonStore
                    .nextDueAt(current)
                    ?.let { dueAt -> waitMillisUntil(dueAt, current, MINIMUM_IDLE_MILLIS) }
            },
            drainOnce = { drainOnce() },
        )

    fun start() = worker.start()

    fun stop() = worker.stop()

    fun notifyWorkAvailable() = worker.notifyWorkAvailable()

    /** Claims and analyses one candidate; returns false when the backlog is empty. */
    internal suspend fun drainOnce(): Boolean {
        val claimed = ChapterRevisionComparisonStore.claimNext(now()) ?: return false
        try {
            processor.process(claimed, now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onUnexpectedFailure(claimed, e)
        }
        return true
    }

    /**
     * Recovers from an exception the processor did not turn into a state transition of its own.
     *
     * Without this a single unexpected failure would leave the row `ANALYZING` until the next start,
     * where no worker can see it, while the loop happily drained unrelated work in the meantime. The
     * attempt is therefore requeued with its own persisted due time inside the process that failed -
     * never immediately, so a deterministically failing candidate cannot spin - and reported as
     * failed once its budget is spent, which is what finally opens the archive gate.
     *
     * Only the *type* of the exception is reported: a message can name a path, a URL or a source
     * error, and none of that belongs in a stored audit or an archive manifest.
     */
    private fun onUnexpectedFailure(
        claimed: ChapterRevisionDataClass,
        error: Throwable,
    ) {
        logger.warn { "Chapter revision ${claimed.id} failed to analyse (${error.javaClass.simpleName})" }
        discardComparisonAttemptThumbnails(stagingRoot(), claimed, claimed.visualAnalysisAttempts)

        if (claimed.visualAnalysisAttempts >= maxAttempts().coerceAtLeast(1)) {
            if (ChapterRevisionComparisonStore.markFailed(claimed.id, ChapterVisualAnalysisFailure.UNEXPECTED)) {
                // the comparison is over, so the archive gate is open again
                onArchiveDue()
            }
            return
        }

        ChapterRevisionComparisonStore.requeue(claimed.id, retryIntervalSeconds().coerceAtLeast(1), now = now())
    }
}

/** Single, global chapter revision visual analysis worker. */
object ChapterRevisionVisualAnalysisExecutor {
    private val applicationDirs: ApplicationDirs by injectLazy()

    private val loop by lazy {
        ChapterRevisionVisualAnalysisLoop(
            processor =
                ChapterRevisionVisualAnalysisProcessor(
                    stagingRoot = { File(applicationDirs.archiveStagingRoot) },
                    archiveRoot = { File(applicationDirs.archiveRoot) },
                    hammingThreshold = { serverConfig.chapterRevisionVisualHashThreshold.value },
                    autoDismissVisuallyEquivalent = { serverConfig.chapterRevisionAutoDismissVisuallyEquivalent.value },
                    thumbnailMaxDimension = { serverConfig.chapterRevisionThumbnailMaxDimension.value },
                    retryIntervalSeconds = { serverConfig.chapterRevisionVisualAnalysisRetrySeconds.value.toLong() },
                    maxAttempts = { serverConfig.chapterRevisionVisualAnalysisMaxAttempts.value },
                    onArchiveDue = { ChapterRevisionArchiveExecutor.notifyWorkAvailable() },
                    onCleanupDue = { ChapterRevisionSweepExecutor.notifyWorkAvailable() },
                ),
            stagingRoot = { File(applicationDirs.archiveStagingRoot) },
            retryIntervalSeconds = { serverConfig.chapterRevisionVisualAnalysisRetrySeconds.value.toLong() },
            maxAttempts = { serverConfig.chapterRevisionVisualAnalysisMaxAttempts.value },
            onArchiveDue = { ChapterRevisionArchiveExecutor.notifyWorkAvailable() },
        )
    }

    fun start() = loop.start()

    fun stop() = loop.stop()

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()

    /** Re-queues analyses an operator asked for again, then wakes the worker. */
    fun retry(ids: List<Int>): List<ChapterRevisionDataClass> {
        val retried = ChapterRevisionComparisonStore.retry(ids)
        if (retried.isNotEmpty()) {
            notifyWorkAvailable()
        }
        return retried
    }
}
