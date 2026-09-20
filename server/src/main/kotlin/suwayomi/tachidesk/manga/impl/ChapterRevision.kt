package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRetentionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDiscoveryReason
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionIntegrityState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionMetadataField
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionReviewAction
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionRollbackDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSignalConfidence
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepItemState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepSessionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisState
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ChapterRevisionRollbackTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionSweepItemTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionSweepSessionTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.serverConfig
import java.time.Instant

/** What a rollback decision did. */
sealed interface ChapterRevisionRollbackOutcome {
    /** The target became the active revision again; [replacedActiveRevisionId] is what it replaced. */
    data class RolledBack(
        val revision: ChapterRevisionDataClass,
        val replacedActiveRevisionId: Int?,
    ) : ChapterRevisionRollbackOutcome

    data object NotFound : ChapterRevisionRollbackOutcome

    /** The revision already is the active one, so replaying the decision changed nothing. */
    data object AlreadyActive : ChapterRevisionRollbackOutcome

    /** The target does not qualify to be made visible again, for a durable reason. */
    data class Refused(
        val reason: String,
    ) : ChapterRevisionRollbackOutcome
}

/**
 * Signals that the fenced activation of a rollback target matched no row.
 *
 * It is thrown inside the rollback transaction so the supersession that already happened is rolled
 * back with it, and it is translated into [ChapterRevisionRollbackOutcome.Refused] at the boundary of
 * the decision. Any other failure keeps propagating.
 */
private class RollbackFenceRaceException : RuntimeException("the rollback target is no longer activatable")

/** One archived revision of a chapter identity, as an integrity audit selects it. */
data class ChapterIntegrityAuditCandidate(
    val revisionId: Int,
    val chapterKey: String,
    val candidateKey: String,
    val mangaId: Int?,
    val chapterId: Int?,
    val chapterName: String,
    /**
     * The exact artifact identity the revision was archived under.
     *
     * It is the same value the durability verifier consumes, so an audit checks exactly the two
     * objects - CBZ and sidecar manifest - whose sizes and digests the archive recorded for it.
     */
    val artifact: ChapterRevisionArchiveArtifact,
)

/** One normalized source snapshot together with the signal which caused its discovery. */
data class ChapterRevisionDiscovery(
    val chapter: ChapterDataClass,
    val reason: ChapterRevisionDiscoveryReason,
    val changedMetadataFields: List<ChapterRevisionMetadataField> = emptyList(),
)

/**
 * Retention states whose archived payload is already gone or being deleted.
 *
 * Such a revision must not occupy a retention slot: it can no longer serve as a readable historical
 * copy, and counting it would silently keep fewer payloads than the policy asks for.
 */
internal val retentionPayloadGoneStates =
    listOf(
        ChapterRetentionState.DELETING,
        ChapterRetentionState.REMOTE_DELETE_PENDING,
        ChapterRetentionState.PRUNED,
    )

/**
 * True once the archived payload of a revision can no longer be read: its mounted copy was deleted or
 * a deletion is in flight.
 *
 * Such a revision can no longer serve as a historical copy of its chapter, so it must never occupy a
 * retention slot and must never be returned to RETAINED. The integrity audit asks the same question
 * before it checks an artifact: the archive having removed a payload on purpose is not the same fact
 * as a payload that went missing.
 */
internal fun ChapterRevisionDataClass.deletionStartedOrPayloadGone(): Boolean =
    deletedAt != null || retentionState in retentionPayloadGoneStates

/**
 * Durable backlog of chapter revision candidates.
 *
 * This is a separate persistent intent record: it does not dispatch downloads and does not replace
 * the existing download queue. Approving a candidate only records permission to acquire/archive it;
 * it does not mark any content as archived (that would be [ChapterRevisionDisposition.ACCEPTED]).
 */
object ChapterRevision {
    private val approvableStates =
        setOf(
            ChapterAcquisitionState.PENDING_APPROVAL,
            ChapterAcquisitionState.DISCOVERED,
        )

    /** States a shutdown may leave behind while a candidate is being acquired. */
    private val inFlightStates =
        listOf(
            ChapterAcquisitionState.DOWNLOADING,
            ChapterAcquisitionState.DOWNLOADED_LOCAL,
            ChapterAcquisitionState.VALIDATING,
        )

    /** States whose acquisition may be retried explicitly. */
    private val retryableStates =
        listOf(
            ChapterAcquisitionState.DOWNLOAD_FAILED,
            ChapterAcquisitionState.VALIDATION_FAILED,
        )

    /** Archive states a shutdown may leave behind while a revision is being committed. */
    private val archiveInFlightStates = listOf(ChapterArchiveState.COMMITTING)

    /** Archive states a verifier may report as unconfirmed. */
    private val archiveUnconfirmableStates =
        listOf(
            ChapterArchiveState.REMOTE_PENDING,
            ChapterArchiveState.ARCHIVE_UNCONFIRMED,
        )

    /** Archive states whose commit may be retried explicitly. */
    private val archiveRetryableStates =
        listOf(
            ChapterArchiveState.COMMIT_FAILED,
            ChapterArchiveState.ARCHIVE_UNCONFIRMED,
        )

    /** Publication states an explicit retry may requeue. */
    private val publicationRetryableStates = listOf(ChapterPublicationState.PUBLICATION_FAILED)

    /** Retention state a shutdown may leave behind while a revision's payload is being deleted. */
    private val retentionInFlightStates = listOf(ChapterRetentionState.DELETING)

    /**
     * Visual-analysis states that no longer block the archive commit.
     *
     * A revision whose visual analysis is still owed must not be archived: archiving its payload is
     * what makes the missing comparison permanent, because the archive then holds bytes nobody ever
     * looked at. Every state here - including [ChapterVisualAnalysisState.FAILED] - means the
     * question "how does this differ?" has been answered as far as it can be, so the revision may
     * proceed to archival and review with that audit attached.
     */
    private val visualAnalysisSettledStates =
        listOf(
            ChapterVisualAnalysisState.NOT_REQUIRED,
            ChapterVisualAnalysisState.COMPLETE,
            ChapterVisualAnalysisState.COMPLETE_WITH_LIMITATIONS,
            ChapterVisualAnalysisState.FAILED,
        )

    /** Retention states an explicit retry may requeue. */
    private val retentionRetryableStates = listOf(ChapterRetentionState.PRUNE_FAILED)

    /** Retention value meaning "keep every accepted revision". */
    const val UNLIMITED_ACCEPTED_REVISION_RETENTION = -1

    /**
     * True when [value] is a usable per-series retention override: null inherits the global default,
     * -1 is unlimited and a non-negative value is an explicit count. Anything below -1 is rejected.
     */
    fun isValidAcceptedRevisionRetentionOverride(value: Int?): Boolean = value == null || value >= UNLIMITED_ACCEPTED_REVISION_RETENTION

    /** Length of the `last_error` column. */
    private const val MAX_ERROR_LENGTH = 4096

    /**
     * Immutable, deterministic identity of a discovery. The same source binding and discovery
     * snapshot always produce the same key, so repeated reconciliation cannot create duplicates.
     * A different snapshot for the same chapter produces a different key and thus a new revision.
     */
    fun candidateKey(
        sourceId: Long?,
        sourceMangaUrl: String?,
        sourceChapterUrl: String,
        name: String,
        scanlator: String?,
        uploadDate: Long,
        chapterNumber: Float,
        memo: JsonObject,
        /**
         * Extra identity of the discovery, or null for a discovery that is identified by its
         * snapshot alone.
         *
         * A sweep sets it so that the *same* chapter snapshot recorded by two different sweeps
         * produces two distinct revisions - each sweep is its own re-check - while a retry of one
         * sweep item keeps producing the same one. It is appended instead of replacing a component,
         * so every key recorded before sweeps existed stays exactly what it was.
         */
        sweepDiscriminator: String? = null,
    ): String =
        Hash.sha256(
            // each component is length-prefixed so a value containing the delimiter (or any other
            // content) cannot shift the field boundaries and collide with a different snapshot;
            // Long/Float.toString are locale-independent, unlike format-based rendering
            (
                listOf(
                    sourceId?.toString(),
                    sourceMangaUrl,
                    sourceChapterUrl,
                    name,
                    scanlator,
                    uploadDate.toString(),
                    chapterNumber.toString(),
                    canonicalMemo(memo),
                ) + listOfNotNull(sweepDiscriminator)
            ).joinToString("") { component ->
                val value = component.orEmpty()
                "${value.length}:$value"
            },
        )

    /** Canonical source memo representation: object keys sorted recursively, array order retained. */
    fun canonicalMemo(memo: JsonObject): String = canonicalMemoObject(memo).toString()

    /**
     * Canonical form of a source memo: object keys sorted recursively, array order retained.
     *
     * The candidate key and the durable sidecar manifest both build on it, so two equivalent memos
     * which only differ in key order can neither produce two candidates nor two different manifest
     * byte sequences for the same revision.
     */
    fun canonicalMemoObject(memo: JsonObject): JsonObject = canonicalJsonElement(memo) as JsonObject

    private fun canonicalJsonElement(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonicalJsonElement(it.value) })
            is JsonArray -> JsonArray(element.map(::canonicalJsonElement))
            else -> element
        }

    /** Acquisition-relevant immutable fields which differ between two normalized snapshots. */
    fun changedMetadataFields(
        previous: ChapterDataClass,
        current: ChapterDataClass,
    ): List<ChapterRevisionMetadataField> =
        buildList {
            if (previous.name != current.name) add(ChapterRevisionMetadataField.NAME)
            if (previous.scanlator != current.scanlator) add(ChapterRevisionMetadataField.SCANLATOR)
            if (previous.uploadDate != current.uploadDate) add(ChapterRevisionMetadataField.UPLOAD_DATE)
            if (previous.chapterNumber != current.chapterNumber) add(ChapterRevisionMetadataField.CHAPTER_NUMBER)
            if (canonicalMemo(previous.memo) != canonicalMemo(current.memo)) add(ChapterRevisionMetadataField.MEMO)
        }

    private fun initialAcquisitionState(policy: MangaAcquisitionPolicy): ChapterAcquisitionState =
        when (policy) {
            MangaAcquisitionPolicy.AUTO -> ChapterAcquisitionState.QUEUED
            MangaAcquisitionPolicy.MANUAL -> ChapterAcquisitionState.PENDING_APPROVAL
            MangaAcquisitionPolicy.PAUSED -> ChapterAcquisitionState.DISCOVERED
        }

    /**
     * Immutable identity of the source chapter, as opposed to [candidateKey], which identifies one
     * discovery snapshot of it.
     *
     * Only the source binding and the chapter URL take part. A changed name, upload date, scanlator
     * or memo describes another revision of the same chapter, so those must never split an identity;
     * a different chapter URL is a different chapter and must never share one.
     */
    fun chapterKey(
        sourceId: Long?,
        sourceMangaUrl: String?,
        sourceChapterUrl: String,
    ): String =
        Hash.sha256(
            listOf(sourceId?.toString(), sourceMangaUrl, sourceChapterUrl).joinToString("") { component ->
                val value = component.orEmpty()
                "${value.length}:$value"
            },
        )

    /**
     * Creates discovery candidates for newly reconciled chapters. Must be called inside the existing
     * reconciliation transaction. Idempotent: an already recorded [candidateKey] is never re-inserted.
     */
    fun createCandidatesForNewChapters(
        mangaEntry: ResultRow,
        chapters: List<ChapterDataClass>,
        now: Long,
    ): List<Int> {
        // only tracked series are archived; browsing or refreshing a source manga that is not in the
        // library must never populate the approval/queue backlog
        if (!mangaEntry[MangaTable.inLibrary]) {
            return emptyList()
        }

        return createCandidates(
            sourceId = mangaEntry[MangaTable.sourceReference],
            sourceMangaUrl = mangaEntry[MangaTable.url],
            policy = MangaAcquisitionPolicy.valueOf(mangaEntry[MangaTable.acquisitionPolicy]),
            chapters = chapters,
            now = now,
        )
    }

    /** Creates metadata-change discoveries, retaining the exact deterministic field audit. */
    fun createCandidatesForMetadataChanges(
        mangaEntry: ResultRow,
        discoveries: List<ChapterRevisionDiscovery>,
        now: Long,
    ): List<Int> {
        if (!mangaEntry[MangaTable.inLibrary] || discoveries.isEmpty()) {
            return emptyList()
        }
        require(discoveries.all { it.reason == ChapterRevisionDiscoveryReason.METADATA_CHANGE })
        require(discoveries.all { it.changedMetadataFields.isNotEmpty() })

        return createCandidateDiscoveries(
            sourceId = mangaEntry[MangaTable.sourceReference],
            sourceMangaUrl = mangaEntry[MangaTable.url],
            policy = MangaAcquisitionPolicy.valueOf(mangaEntry[MangaTable.acquisitionPolicy]),
            discoveries = discoveries,
            now = now,
        )
    }

    /**
     * Creates the initial archive candidates of one series visited by an archive bootstrap.
     *
     * It deliberately reuses the shared candidate upsert, so a chapter that normal reconciliation
     * already recorded collides idempotently by its canonical [candidateKey] instead of being
     * duplicated. It is called inside the bootstrap's own transaction, one series at a time.
     */
    fun createCandidatesForBootstrap(
        mangaEntry: ResultRow,
        chapters: List<ChapterDataClass>,
        now: Long,
    ): List<Int> {
        // only tracked series are archived; a series that left the library must not be populated
        if (!mangaEntry[MangaTable.inLibrary] || chapters.isEmpty()) {
            return emptyList()
        }

        return createCandidateDiscoveries(
            sourceId = mangaEntry[MangaTable.sourceReference],
            sourceMangaUrl = mangaEntry[MangaTable.url],
            policy = MangaAcquisitionPolicy.valueOf(mangaEntry[MangaTable.acquisitionPolicy]),
            discoveries =
                chapters.map {
                    ChapterRevisionDiscovery(it, ChapterRevisionDiscoveryReason.BOOTSTRAP_IMPORT)
                },
            now = now,
        )
    }

    /**
     * Creates the single candidate of one sweep item, from the chapter snapshot the sweep found.
     *
     * A sweep is the source-independent way to notice a re-release: the extension API exposes no
     * revision id, updated time or digest, so the content has to be fetched again and compared. The
     * candidate this records is therefore an ordinary acquisition candidate - it is the
     * content-comparison step after acquisition that decides whether it carries new content.
     *
     * It is idempotent per item: an item already bound to a candidate reuses it, which is what makes
     * a retried item continue where it stopped instead of recording a second revision of the same
     * chapter. Two different items always record two different candidates, because the sweep
     * discriminator is part of the candidate key.
     *
     * @return the number of candidates this item owns, which is 1 for a usable chapter and 0 for a
     * paused series, where nothing is fetched at all.
     */
    fun createCandidatesForSweep(
        mangaEntry: ResultRow,
        chapter: ChapterDataClass,
        policy: MangaAcquisitionPolicy,
        sweepItemId: Int,
        sweepSessionId: Int,
        reason: ChapterRevisionDiscoveryReason,
        now: Long,
    ): Int {
        require(reason == ChapterRevisionDiscoveryReason.PERIODIC_SWEEP || reason == ChapterRevisionDiscoveryReason.MANUAL_SWEEP)

        // a paused series is inert: it records no revision and therefore fetches nothing
        if (policy == MangaAcquisitionPolicy.PAUSED) {
            return 0
        }

        // only tracked series are swept; a series that left the library must not be populated
        if (!mangaEntry[MangaTable.inLibrary]) {
            return 0
        }

        transaction {
            // The candidate is recorded under the sweep bookkeeping's own lock, so a cancel that
            // committed before this transaction can never be raced into producing a candidate. Both
            // the claim and the cancel take the session row first and the item row second, and this
            // guard takes them in that same order, so no cycle between the two is possible.
            if (!sweepItemAcceptsCandidate(sweepItemId, sweepSessionId)) {
                return@transaction
            }

            // reusing the candidate of an earlier attempt is what makes a retry idempotent, and the
            // unique index on the binding is the database-level backstop for two concurrent attempts
            if (sweepItemCandidateId(sweepItemId) != null) {
                return@transaction
            }

            createCandidateDiscoveries(
                sourceId = mangaEntry[MangaTable.sourceReference],
                sourceMangaUrl = mangaEntry[MangaTable.url],
                policy = policy,
                discoveries = listOf(ChapterRevisionDiscovery(chapter, reason)),
                now = now,
                sweepItemId = sweepItemId,
                sweepDiscriminator = "sweep:$sweepSessionId:$sweepItemId",
            )
        }

        return sweepItemCandidateId(sweepItemId)?.let { 1 } ?: 0
    }

    /**
     * True when a sweep item may still record its candidate.
     *
     * Both rows are locked for the check, so the answer cannot be invalidated by a cancel that commits
     * while the candidate is being written: a cancel either committed first - and then this sees the
     * cancelled session - or it waits, and the candidate it cancels afterwards is one that was
     * legitimately recorded while the item was still processing.
     *
     * Must be called inside a transaction.
     */
    private fun sweepItemAcceptsCandidate(
        sweepItemId: Int,
        sweepSessionId: Int,
    ): Boolean {
        val sessionRow =
            ChapterRevisionSweepSessionTable
                .selectAll()
                .where { ChapterRevisionSweepSessionTable.id eq sweepSessionId }
                .forUpdate()
                .firstOrNull()
                ?: return false
        if (!ChapterRevisionSweepSessionState.valueOf(sessionRow[ChapterRevisionSweepSessionTable.state]).isActive) {
            return false
        }

        val itemRow =
            ChapterRevisionSweepItemTable
                .selectAll()
                .where { ChapterRevisionSweepItemTable.id eq sweepItemId }
                .forUpdate()
                .firstOrNull()
                ?: return false

        return ChapterRevisionSweepItemState.valueOf(itemRow[ChapterRevisionSweepItemTable.state]) ==
            ChapterRevisionSweepItemState.PROCESSING
    }

    /** The id of the candidate a sweep item owns, or null while it owns none. */
    private fun sweepItemCandidateId(sweepItemId: Int): Int? =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.sweepItem eq sweepItemId }
                .limit(1)
                .firstOrNull()
                ?.get(ChapterRevisionTable.id)
                ?.value
        }

    /**
     * Completes an acquisition and classifies its content against the active revision of its chapter
     * in one fenced transaction.
     *
     * Completion and comparison are deliberately one step: a revision that is complete but not yet
     * classified would be claimable by the archive worker, so a crash between the two would archive
     * a duplicate of content the archive already holds. Making it one transaction means the
     * comparison can never be missing for a completed revision.
     *
     * A revision whose whole-chapter digest differs from the active baseline leaves this transaction
     * with its page-by-page comparison owed ([ChapterVisualAnalysisState.QUEUED]), which is what keeps
     * it out of the archive queue until that comparison has settled.
     *
     * The identity lock is what makes the baseline trustworthy: an acceptance or a retention decision
     * on the same chapter serializes with this comparison, so the baseline that was read is still the
     * baseline that was written.
     *
     * @return the completed revision and what it was classified as, or null when the row was no
     * longer validating - a cancel or a retry that won the race.
     */
    fun markCompleteAndClassify(
        id: Int,
        pageCount: Int,
        contentHash: String,
        candidatePath: String,
        now: Long = Instant.now().epochSecond,
    ): ChapterRevisionCompletion? =
        transaction {
            val candidate =
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        (ChapterRevisionTable.id eq id) and
                            (ChapterRevisionTable.acquisitionState eq ChapterAcquisitionState.VALIDATING.name)
                    }.forUpdate()
                    .firstOrNull()
                    ?.let { ChapterRevisionTable.toDataClass(it) }
                    ?: return@transaction null

            lockChapterIdentity(candidate.chapterKey)

            // the active revision of the same identity at the moment the comparison commits; a
            // revision that is not active is never a baseline for itself
            val baseline =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.activeChapterKey eq candidate.chapterKey }
                    .limit(1)
                    .firstOrNull()
                    ?.let { ChapterRevisionTable.toDataClass(it) }
                    ?.takeIf { it.id != candidate.id && it.contentHash != null }

            val comparisonState =
                when {
                    baseline == null -> ChapterRevisionComparisonState.NO_BASELINE
                    baseline.contentHash == contentHash -> ChapterRevisionComparisonState.EXACT_MATCH
                    else -> ChapterRevisionComparisonState.CONTENT_CHANGED
                }
            val unchanged = comparisonState == ChapterRevisionComparisonState.EXACT_MATCH

            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.acquisitionState eq ChapterAcquisitionState.VALIDATING.name)
            }) {
                it[acquisitionState] = ChapterAcquisitionState.COMPLETE.name
                it[signalConfidence] = ChapterRevisionSignalConfidence.CONTENT_PROOF.name
                it[ChapterRevisionTable.pageCount] = pageCount
                it[ChapterRevisionTable.contentHash] = contentHash
                it[ChapterRevisionTable.candidatePath] = candidatePath
                it[lastError] = null
                it[ChapterRevisionTable.comparisonState] = comparisonState.name
                it[comparisonBaselineRevision] = baseline?.id
                it[comparedAt] = now
                it[comparisonError] = null
                if (unchanged) {
                    // terminal, so no worker which claims by disposition can ever pick it up again
                    it[disposition] = ChapterRevisionDisposition.UNCHANGED.name
                    // the staged copy is dead weight now, and this is the persisted instant its removal
                    // becomes due; recording it in the same transaction is what makes the cleanup
                    // recoverable instead of dependent on the process that happened to classify it
                    it[comparisonCleanupDueAt] = now
                } else if (comparisonState == ChapterRevisionComparisonState.CONTENT_CHANGED) {
                    // the whole-chapter digest only proved that the bytes differ, so the page-by-page
                    // comparison is owed before this revision may be archived. Recording it in the same
                    // transaction is what makes the debt recoverable: a crash here would otherwise leave
                    // a revision that the archive gate considers settled without a comparison ever
                    // having been requested.
                    it[visualAnalysisState] = ChapterVisualAnalysisState.QUEUED.name
                    it[visualAnalysisNextAttemptAt] = now
                }
                it[updatedAt] = now
            }

            val completed =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id eq id }
                    .first()
                    .let { ChapterRevisionTable.toDataClass(it) }

            ChapterRevisionCompletion(
                revision = completed,
                outcome =
                    if (unchanged) {
                        ChapterRevisionClassificationOutcome.UNCHANGED
                    } else {
                        ChapterRevisionClassificationOutcome.CONTINUE
                    },
            )
        }

    /**
     * Records that a comparison itself failed, without touching the acquisition outcome.
     *
     * The revision stays a normal candidate: it was acquired successfully, so the only thing that is
     * unknown is whether it duplicates the archive. Silently treating that as "changed" would archive
     * a duplicate, and treating it as "unchanged" would drop a potentially new revision, so the
     * failure is recorded and the normal archive path stays in charge.
     */
    fun markComparisonFailed(
        id: Int,
        error: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.comparisonState eq ChapterRevisionComparisonState.PENDING.name)
            }) {
                it[comparisonState] = ChapterRevisionComparisonState.ANALYSIS_FAILED.name
                it[comparisonError] = error.take(MAX_ERROR_LENGTH)
                it[comparedAt] = now
            } > 0
        }

    /**
     * Unchanged revisions whose staged pages still have to be removed, oldest first.
     *
     * The content of an unchanged revision is by definition already archived, so its local copy is
     * dead weight - but it may only be removed *after* the transaction that classified it committed,
     * and a crash in between must not leak it. The staged path is therefore the pending-cleanup
     * marker, exactly like a confirmed revision's.
     */
    fun unchangedRevisionsAwaitingCleanup(
        limit: Int,
        now: Long = Instant.now().epochSecond,
    ): List<ChapterRevisionDataClass> =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where { unchangedCleanupDueCondition(now) }
                .orderBy(
                    ChapterRevisionTable.comparisonCleanupDueAt to SortOrder.ASC_NULLS_FIRST,
                    ChapterRevisionTable.id to SortOrder.ASC,
                ).limit(limit)
                .map { ChapterRevisionTable.toDataClass(it) }
        }

    /**
     * The earliest instant any unchanged-payload cleanup is scheduled for, or null while none is.
     *
     * This deliberately looks at every marker, not only the ones that are due: a removal that failed has
     * a due time in the future, and that instant is exactly what the sweep worker has to sleep until.
     * A row that is already due reports [now] instead of its own past instant, so the worker waits the
     * minimum rather than trying to sleep a negative interval, and a marker whose due time is unset is
     * due immediately - which keeps the marker and the schedule from ever disagreeing.
     */
    fun nextUnchangedCleanupDueAt(now: Long = Instant.now().epochSecond): Long? =
        transaction {
            val scheduled =
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.UNCHANGED.name) and
                            (ChapterRevisionTable.candidatePath.isNotNull())
                    }.orderBy(
                        ChapterRevisionTable.comparisonCleanupDueAt to SortOrder.ASC_NULLS_FIRST,
                        ChapterRevisionTable.id to SortOrder.ASC,
                    ).limit(1)
                    .firstOrNull()
                    ?: return@transaction null

            val due = scheduled[ChapterRevisionTable.comparisonCleanupDueAt] ?: return@transaction now
            if (due <= now) now else due
        }

    /**
     * The cleanup backlog condition: a staged path that still has to go, and a due time that has passed.
     *
     * Only due rows are ever returned, so a removal that failed cannot spin, and ordering by the due
     * time is what lets a deferred row fall behind a row that is due now instead of starving it. An
     * unset due time counts as due, so a marker written without a schedule is still collected rather
     * than leaking its files forever.
     */
    private fun unchangedCleanupDueCondition(now: Long): Op<Boolean> =
        (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.UNCHANGED.name) and
            (ChapterRevisionTable.candidatePath.isNotNull()) and
            (
                (ChapterRevisionTable.comparisonCleanupDueAt.isNull()) or
                    (ChapterRevisionTable.comparisonCleanupDueAt lessEq now)
            )

    /**
     * Clears the pending-cleanup marker of an unchanged revision after its local files are gone.
     *
     * Clearing the due time together with the path is what stops a removed revision from being claimed
     * again: the row stops being due at all rather than staying due with nothing left to do.
     */
    fun markUnchangedStagingCleaned(
        id: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.UNCHANGED.name) and
                    (ChapterRevisionTable.candidatePath.isNotNull())
            }) {
                it[candidatePath] = null
                it[comparisonCleanupDueAt] = null
                it[updatedAt] = now
            } > 0
        }

    /**
     * Pushes a failed unchanged-payload cleanup forward to a persisted, retryable instant.
     *
     * The row keeps its staged path, so it is still waiting to be cleaned - but its due time moves on,
     * which is what keeps one directory that cannot be removed right now from spinning or from blocking
     * a row that is due immediately. The delay saturates, so a due time can never wrap into the past.
     */
    fun deferUnchangedStagingCleanup(
        id: Int,
        retryIntervalSeconds: Long,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.UNCHANGED.name) and
                    (ChapterRevisionTable.candidatePath.isNotNull())
            }) {
                it[comparisonCleanupDueAt] = saturatingEpochAdd(now, retryIntervalSeconds)
                it[updatedAt] = now
            } > 0
        }

    /** What a completed acquisition did to the revision queue. */
    enum class ChapterRevisionClassificationOutcome {
        /** the content differs from the active revision, or nothing was active: continue normally */
        CONTINUE,

        /** byte-identical to the already archived active revision: terminal, never archived */
        UNCHANGED,
    }

    /** One completed acquisition together with its classification. */
    data class ChapterRevisionCompletion(
        val revision: ChapterRevisionDataClass,
        val outcome: ChapterRevisionClassificationOutcome,
    )

    fun createCandidates(
        sourceId: Long?,
        sourceMangaUrl: String?,
        policy: MangaAcquisitionPolicy,
        chapters: List<ChapterDataClass>,
        now: Long,
    ): List<Int> =
        createCandidateDiscoveries(
            sourceId,
            sourceMangaUrl,
            policy,
            chapters.map { ChapterRevisionDiscovery(it, ChapterRevisionDiscoveryReason.NEW_CHAPTER) },
            now,
        )

    private fun createCandidateDiscoveries(
        sourceId: Long?,
        sourceMangaUrl: String?,
        policy: MangaAcquisitionPolicy,
        discoveries: List<ChapterRevisionDiscovery>,
        now: Long,
        /** binds every created candidate to exactly this sweep item; null for every other discovery */
        sweepItemId: Int? = null,
        /** see [candidateKey]; null keeps the snapshot-only identity of ordinary discoveries */
        sweepDiscriminator: String? = null,
    ): List<Int> {
        if (discoveries.isEmpty()) {
            return emptyList()
        }

        // Canonical source bindings are a control plane *over* ordinary discovery: a manga bound as
        // FALLBACK or DISABLED records no ordinary candidate until it is promoted, while a manga
        // nobody has bound is discovered exactly as it was before canonical identity existed. The
        // scope read here is also the immutable snapshot written into each candidate below, so a
        // later detach cannot rewrite what the discovery was made for. Work already created is
        // untouched by this: only creation is gated.
        val canonicalScopes = CanonicalIdentity.discoveryScopesFor(discoveries.map { it.chapter.mangaId })
        val eligible =
            discoveries.filter { discovery ->
                canonicalScopes[discovery.chapter.mangaId]?.isAcquisitionEligible != false
            }
        if (eligible.isEmpty()) {
            return emptyList()
        }

        val acquisitionState = initialAcquisitionState(policy)
        val approvedAt = now.takeIf { acquisitionState == ChapterAcquisitionState.QUEUED }

        val candidates =
            eligible
                .map { discovery ->
                    val chapter = discovery.chapter
                    candidateKey(
                        sourceId,
                        sourceMangaUrl,
                        chapter.url,
                        chapter.name,
                        chapter.scanlator,
                        chapter.uploadDate,
                        chapter.chapterNumber,
                        chapter.memo,
                        sweepDiscriminator,
                    ) to discovery
                }.distinctBy { it.first }

        val candidateKeys = candidates.map { it.first }

        // Concurrent reconciliation of the same series can compute overlapping candidate keys. The
        // upsert conflicts on the unique candidate_key and its conflict update only re-assigns
        // candidate_key to itself, so it inserts the missing rows without a unique-constraint
        // rollback and never overwrites an already recorded candidate. It is one statement and one
        // round trip regardless of batch size.
        ChapterRevisionTable.batchUpsert(
            candidates,
            ChapterRevisionTable.candidateKey,
            onUpdate = { it[ChapterRevisionTable.candidateKey] = insertValue(ChapterRevisionTable.candidateKey) },
            shouldReturnGeneratedValues = false,
        ) { (key, discovery) ->
            val chapter = discovery.chapter
            this[ChapterRevisionTable.chapterKey] = chapterKey(sourceId, sourceMangaUrl, chapter.url)
            this[ChapterRevisionTable.candidateKey] = key
            this[ChapterRevisionTable.chapter] = chapter.id
            this[ChapterRevisionTable.manga] = chapter.mangaId
            this[ChapterRevisionTable.sourceId] = sourceId
            this[ChapterRevisionTable.sourceMangaUrl] = sourceMangaUrl
            this[ChapterRevisionTable.sourceChapterUrl] = chapter.url
            this[ChapterRevisionTable.name] = chapter.name
            this[ChapterRevisionTable.scanlator] = chapter.scanlator
            this[ChapterRevisionTable.uploadDate] = chapter.uploadDate
            this[ChapterRevisionTable.chapterNumber] = chapter.chapterNumber
            this[ChapterRevisionTable.memo] = chapter.memo
            this[ChapterRevisionTable.discoveryReason] = discovery.reason.name
            this[ChapterRevisionTable.signalConfidence] = ChapterRevisionSignalConfidence.METADATA_HINT.name
            this[ChapterRevisionTable.changedMetadataFields] =
                ChapterRevisionMetadataField.encode(discovery.changedMetadataFields)
            this[ChapterRevisionTable.disposition] = ChapterRevisionDisposition.CANDIDATE.name
            this[ChapterRevisionTable.acquisitionState] = acquisitionState.name
            // every candidate recorded after the comparison dimension exists owes one, which is what
            // tells it apart from a row that predates the dimension and is NOT_EVALUATED
            this[ChapterRevisionTable.comparisonState] = ChapterRevisionComparisonState.PENDING.name
            this[ChapterRevisionTable.archiveState] = ChapterArchiveState.NOT_COMMITTED.name
            this[ChapterRevisionTable.publicationState] = ChapterPublicationState.NOT_PUBLISHED.name
            this[ChapterRevisionTable.attempts] = 0
            this[ChapterRevisionTable.sweepItem] = sweepItemId
            // the canonical binding is a snapshot, not a reference: it stays legible after the work
            // it names is gone, and it is null for a revision discovered under no work
            val canonicalSnapshot = canonicalScopes[chapter.mangaId]?.snapshot
            this[ChapterRevisionTable.canonicalWorkKey] = canonicalSnapshot?.workKey
            this[ChapterRevisionTable.canonicalBindingRole] = canonicalSnapshot?.bindingRole?.name
            this[ChapterRevisionTable.canonicalBindingPriority] = canonicalSnapshot?.bindingPriority
            this[ChapterRevisionTable.canonicalBindingPrimary] = canonicalSnapshot?.bindingPrimary
            this[ChapterRevisionTable.canonicalBindingSourceId] = canonicalSnapshot?.bindingSourceId
            this[ChapterRevisionTable.canonicalBindingMangaUrl] = canonicalSnapshot?.bindingMangaUrl
            this[ChapterRevisionTable.discoveredAt] = now
            this[ChapterRevisionTable.updatedAt] = now
            this[ChapterRevisionTable.approvedAt] = approvedAt
        }

        // The upsert's generated-key return is not reliable for rows whose insert was skipped, so
        // the ids are read back for the requested keys. Each returned id belongs to one of the
        // requested candidates, whether it was recorded by this reconciliation or by a concurrent
        // one; the id is returned in the order the candidates were requested.
        val idsByKey =
            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.candidateKey inList candidateKeys }
                .associate { it[ChapterRevisionTable.candidateKey] to it[ChapterRevisionTable.id].value }

        return candidateKeys.mapNotNull { idsByKey[it] }
    }

    fun getRevision(id: Int): ChapterRevisionDataClass? =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.id eq id }
                .firstOrNull()
                ?.let { ChapterRevisionTable.toDataClass(it) }
        }

    /** All revisions of a chapter, newest first, including rejected/superseded ones. */
    fun getRevisionsForChapter(chapterId: Int): List<ChapterRevisionDataClass> =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.chapter eq chapterId }
                .orderBy(ChapterRevisionTable.discoveredAt to SortOrder.DESC, ChapterRevisionTable.id to SortOrder.DESC)
                .map { ChapterRevisionTable.toDataClass(it) }
        }

    /** Candidates waiting for explicit approval, oldest discovery first. */
    fun getApprovalBacklog(): List<ChapterRevisionDataClass> =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where { candidateCondition(ChapterAcquisitionState.PENDING_APPROVAL) }
                .orderBy(
                    ChapterRevisionTable.discoveredAt to SortOrder.ASC,
                    ChapterRevisionTable.id to SortOrder.ASC,
                ).map { ChapterRevisionTable.toDataClass(it) }
        }

    /**
     * Approved candidates waiting to be acquired, ordered by when they entered the queue (the
     * non-null [ChapterRevisionTable.updatedAt]), then id. Approval sets `updatedAt == approvedAt`;
     * a future requeue moves the entry to the new queue-entry time.
     */
    fun getQueuedBacklog(): List<ChapterRevisionDataClass> =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where { candidateCondition(ChapterAcquisitionState.QUEUED) }
                .orderBy(
                    ChapterRevisionTable.updatedAt to SortOrder.ASC,
                    ChapterRevisionTable.id to SortOrder.ASC,
                ).map { ChapterRevisionTable.toDataClass(it) }
        }

    /**
     * Moves explicitly selected candidates to [ChapterAcquisitionState.QUEUED].
     *
     * Only [approvableStates] are transitioned; [ChapterAcquisitionState.DISCOVERED] candidates are
     * therefore promoted only when their id is explicitly selected. Any other state is left as-is.
     */
    fun approve(
        ids: List<Int>,
        now: Long = Instant.now().epochSecond,
    ): List<ChapterRevisionDataClass> =
        transition(ids, approvableStates.toList()) { update ->
            update[ChapterRevisionTable.acquisitionState] = ChapterAcquisitionState.QUEUED.name
            update[ChapterRevisionTable.approvedAt] = now
            update[ChapterRevisionTable.updatedAt] = now
        }

    /**
     * Re-queues candidates whose acquisition failed, preserving the attempt count so retries stay
     * auditable. Candidates that are still in flight or already complete are left untouched.
     */
    fun retry(
        ids: List<Int>,
        now: Long = Instant.now().epochSecond,
        beforeCommit: (List<ChapterRevisionDataClass>) -> Unit = {},
    ): List<ChapterRevisionDataClass> =
        transition(ids, retryableStates, beforeCommit) { update ->
            update[ChapterRevisionTable.acquisitionState] = ChapterAcquisitionState.QUEUED.name
            update[ChapterRevisionTable.updatedAt] = now
        }

    /**
     * Atomically claims the oldest queued candidate.
     *
     * The row is locked and re-checked under the lock, so a second claimant can never observe a
     * revision that is already being acquired.
     */
    fun claimNextQueued(now: Long = Instant.now().epochSecond): ChapterRevisionDataClass? =
        transaction {
            val queued =
                ChapterRevisionTable
                    .selectAll()
                    .where { candidateCondition(ChapterAcquisitionState.QUEUED) }
                    .orderBy(ChapterRevisionTable.updatedAt to SortOrder.ASC, ChapterRevisionTable.id to SortOrder.ASC)
                    .forUpdate()
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction null

            val id = queued[ChapterRevisionTable.id].value

            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[ChapterRevisionTable.acquisitionState] = ChapterAcquisitionState.DOWNLOADING.name
                it[ChapterRevisionTable.attempts] = queued[ChapterRevisionTable.attempts] + 1
                it[ChapterRevisionTable.lastAttemptAt] = now
                it[ChapterRevisionTable.updatedAt] = now
                it[ChapterRevisionTable.lastError] = null
            }

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.id eq id }
                .first()
                .let { ChapterRevisionTable.toDataClass(it) }
        }

    fun markDownloadedLocal(
        id: Int,
        candidatePath: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedUpdate(id, listOf(ChapterAcquisitionState.DOWNLOADING)) {
            it[ChapterRevisionTable.acquisitionState] = ChapterAcquisitionState.DOWNLOADED_LOCAL.name
            it[ChapterRevisionTable.candidatePath] = candidatePath
            it[ChapterRevisionTable.updatedAt] = now
        }

    fun markValidating(
        id: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedUpdate(id, listOf(ChapterAcquisitionState.DOWNLOADED_LOCAL)) {
            it[ChapterRevisionTable.acquisitionState] = ChapterAcquisitionState.VALIDATING.name
            it[ChapterRevisionTable.updatedAt] = now
        }

    fun markComplete(
        id: Int,
        pageCount: Int,
        contentHash: String,
        candidatePath: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedUpdate(id, listOf(ChapterAcquisitionState.VALIDATING)) {
            it[ChapterRevisionTable.acquisitionState] = ChapterAcquisitionState.COMPLETE.name
            it[ChapterRevisionTable.signalConfidence] = ChapterRevisionSignalConfidence.CONTENT_PROOF.name
            it[ChapterRevisionTable.pageCount] = pageCount
            it[ChapterRevisionTable.contentHash] = contentHash
            it[ChapterRevisionTable.candidatePath] = candidatePath
            it[ChapterRevisionTable.lastError] = null
            it[ChapterRevisionTable.updatedAt] = now
        }

    fun markDownloadFailed(
        id: Int,
        error: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean = markFailed(id, ChapterAcquisitionState.DOWNLOAD_FAILED, error, now)

    fun markValidationFailed(
        id: Int,
        error: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean = markFailed(id, ChapterAcquisitionState.VALIDATION_FAILED, error, now)

    /**
     * Returns revisions a shutdown left mid acquisition to the queue. Attempts are preserved and any
     * staged pages are kept, so recovery resumes instead of downloading the pages again.
     */
    fun recoverInterrupted(now: Long = Instant.now().epochSecond): Int =
        transaction {
            ChapterRevisionTable.update({
                ChapterRevisionTable.acquisitionState inList inFlightStates.map { it.name }
            }) {
                it[ChapterRevisionTable.acquisitionState] = ChapterAcquisitionState.QUEUED.name
                it[ChapterRevisionTable.updatedAt] = now
            }
        }

    private fun markFailed(
        id: Int,
        state: ChapterAcquisitionState,
        error: String,
        now: Long,
    ): Boolean =
        guardedUpdate(id, inFlightStates) {
            it[ChapterRevisionTable.acquisitionState] = state.name
            it[ChapterRevisionTable.lastError] = error.take(MAX_ERROR_LENGTH)
            it[ChapterRevisionTable.updatedAt] = now
        }

    /**
     * Atomically claims the oldest acquired revision whose archive commit has not started yet.
     *
     * Acquisition and archive state are independent: only an acquisition `COMPLETE` revision is
     * claimed, and nothing here changes how the chapter was acquired.
     */
    fun claimNextArchiveCommit(now: Long = Instant.now().epochSecond): ChapterRevisionDataClass? =
        transaction {
            val candidate =
                ChapterRevisionTable
                    .selectAll()
                    .where { archiveCandidateCondition(ChapterArchiveState.NOT_COMMITTED) }
                    .orderBy(ChapterRevisionTable.updatedAt to SortOrder.ASC, ChapterRevisionTable.id to SortOrder.ASC)
                    .forUpdate()
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction null

            val id = candidate[ChapterRevisionTable.id].value

            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[ChapterRevisionTable.archiveState] = ChapterArchiveState.COMMITTING.name
                it[ChapterRevisionTable.archiveAttempts] = candidate[ChapterRevisionTable.archiveAttempts] + 1
                it[ChapterRevisionTable.archiveLastAttemptAt] = now
                it[ChapterRevisionTable.archiveLastError] = null
                it[ChapterRevisionTable.updatedAt] = now
            }

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.id eq id }
                .first()
                .let { ChapterRevisionTable.toDataClass(it) }
        }

    /**
     * Returns revisions a shutdown left mid archive commit to the uncommitted state.
     *
     * Published artifacts are deliberately kept; the next attempt reuses them only when their digest
     * matches what it rebuilds.
     */
    fun recoverInterruptedArchives(now: Long = Instant.now().epochSecond): Int =
        transaction {
            ChapterRevisionTable.update({
                ChapterRevisionTable.archiveState inList archiveInFlightStates.map { it.name }
            }) {
                it[ChapterRevisionTable.archiveState] = ChapterArchiveState.NOT_COMMITTED.name
                it[ChapterRevisionTable.updatedAt] = now
            }
        }

    /**
     * Records that the revision's immutable artifacts were published into the archive root.
     *
     * This is intentionally not a durability claim: the artifact is only known to be written, not
     * confirmed on remote storage. A remote durability check is scheduled immediately.
     */
    fun markRemotePending(
        id: Int,
        cbzPath: String,
        manifestPath: String,
        cbzHash: String,
        cbzSize: Long,
        manifestHash: String,
        manifestSize: Long,
        archivedAt: Long,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedArchiveUpdate(id, archiveInFlightStates) {
            it[ChapterRevisionTable.archiveState] = ChapterArchiveState.REMOTE_PENDING.name
            it[ChapterRevisionTable.archiveCbzPath] = cbzPath
            it[ChapterRevisionTable.archiveManifestPath] = manifestPath
            it[ChapterRevisionTable.archiveCbzHash] = cbzHash
            it[ChapterRevisionTable.archiveCbzSize] = cbzSize
            it[ChapterRevisionTable.archiveManifestHash] = manifestHash
            it[ChapterRevisionTable.archiveManifestSize] = manifestSize
            it[ChapterRevisionTable.archivedAt] = archivedAt
            it[ChapterRevisionTable.archiveLastError] = null
            it[ChapterRevisionTable.archiveNextVerificationAt] = 0
            it[ChapterRevisionTable.updatedAt] = now
        }

    /**
     * Records that an archive verifier ran and could not confirm the artifacts on remote storage.
     *
     * The revision stays published and its artifacts and acquisition state are untouched; only the
     * archive dimension becomes retryable.
     */
    fun markArchiveUnconfirmed(
        id: Int,
        error: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedArchiveUpdate(id, archiveUnconfirmableStates) {
            it[ChapterRevisionTable.archiveState] = ChapterArchiveState.ARCHIVE_UNCONFIRMED.name
            it[ChapterRevisionTable.archiveLastError] = error.take(MAX_ERROR_LENGTH)
            it[ChapterRevisionTable.archiveNextVerificationAt] = null
            it[ChapterRevisionTable.updatedAt] = now
        }

    /**
     * Records that an archive verifier confirmed the artifact on remote storage.
     *
     * Only a still-pending revision may be confirmed. A stale or concurrent verifier must never
     * overwrite a hard [ChapterArchiveState.ARCHIVE_UNCONFIRMED] result; that state is cleared by an
     * explicit archive retry and republication instead.
     */
    fun markRemoteConfirmed(
        id: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedArchiveUpdate(id, listOf(ChapterArchiveState.REMOTE_PENDING)) {
            it[ChapterRevisionTable.archiveState] = ChapterArchiveState.REMOTE_CONFIRMED.name
            it[ChapterRevisionTable.archiveLastError] = null
            it[ChapterRevisionTable.archiveNextVerificationAt] = null
            it[ChapterRevisionTable.updatedAt] = now
        }

    fun markArchiveCommitFailed(
        id: Int,
        error: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedArchiveUpdate(id, archiveInFlightStates) {
            it[ChapterRevisionTable.archiveState] = ChapterArchiveState.COMMIT_FAILED.name
            it[ChapterRevisionTable.archiveLastError] = error.take(MAX_ERROR_LENGTH)
            it[ChapterRevisionTable.updatedAt] = now
        }

    /**
     * Requeues revisions whose archive commit failed, preserving the archive attempt count.
     *
     * Revisions that are still committing, already pending remote confirmation or confirmed are left
     * untouched, so a retry can never re-publish an artifact that is already durable.
     */
    fun retryArchive(
        ids: List<Int>,
        now: Long = Instant.now().epochSecond,
        beforeCommit: (List<ChapterRevisionDataClass>) -> Unit = {},
    ): List<ChapterRevisionDataClass> =
        transitionArchive(ids, archiveRetryableStates, beforeCommit) { update ->
            update[ChapterRevisionTable.archiveState] = ChapterArchiveState.NOT_COMMITTED.name
            update[ChapterRevisionTable.archiveLastError] = null
            update[ChapterRevisionTable.archiveNextVerificationAt] = null
            update[ChapterRevisionTable.updatedAt] = now
        }

    /**
     * Atomically claims the oldest revision whose remote durability check is due.
     *
     * The attempt is counted and the next check is scheduled before the caller runs the external
     * command, so a crash or a second server instance can never hot-loop on the same row and no
     * revision is verified twice concurrently.
     *
     * [leaseSeconds] is the persisted claim lease: it must cover the worst case runtime of a single
     * verification - the configured command timeout plus the process termination grace - and not
     * merely the retry cadence, otherwise another instance could claim a row whose command is still
     * running. Acquisition and archive commit state are untouched.
     */
    fun claimNextDueVerification(
        now: Long = Instant.now().epochSecond,
        leaseSeconds: Long,
    ): ChapterRevisionDataClass? =
        transaction {
            val candidate =
                ChapterRevisionTable
                    .selectAll()
                    .where { verificationDueCondition(now) }
                    .orderBy(
                        ChapterRevisionTable.archiveNextVerificationAt to SortOrder.ASC_NULLS_FIRST,
                        ChapterRevisionTable.id to SortOrder.ASC,
                    ).forUpdate()
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction null

            val id = candidate[ChapterRevisionTable.id].value

            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[ChapterRevisionTable.archiveVerificationAttempts] =
                    candidate[ChapterRevisionTable.archiveVerificationAttempts] + 1
                it[ChapterRevisionTable.archiveLastVerificationAt] = now
                it[ChapterRevisionTable.archiveNextVerificationAt] = saturatingEpochAdd(now, leaseSeconds)
                it[ChapterRevisionTable.updatedAt] = now
            }

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.id eq id }
                .first()
                .let { ChapterRevisionTable.toDataClass(it) }
        }

    /**
     * Earliest instant at which a remote durability check is due, or null when nothing is pending.
     *
     * A pending revision without a scheduled time - one that reached REMOTE_PENDING before
     * verification existed - counts as due now.
     */
    fun nextVerificationDueAt(now: Long = Instant.now().epochSecond): Long? =
        transaction {
            val dueNow =
                ChapterRevisionTable
                    .selectAll()
                    .where { verificationDueCondition(now) }
                    .limit(1)
                    .firstOrNull()
            if (dueNow != null) {
                return@transaction now
            }

            ChapterRevisionTable
                .selectAll()
                .where {
                    verificationCandidateCondition() and
                        (ChapterRevisionTable.archiveNextVerificationAt.isNotNull())
                }.orderBy(ChapterRevisionTable.archiveNextVerificationAt to SortOrder.ASC)
                .limit(1)
                .firstOrNull()
                ?.get(ChapterRevisionTable.archiveNextVerificationAt)
        }

    /**
     * Records a retryable verification outcome: the artifacts are still not visible on the remote.
     *
     * The revision deliberately stays REMOTE_PENDING; only the reason and timestamp are updated.
     */
    fun markVerificationPending(
        id: Int,
        reason: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedArchiveUpdate(id, listOf(ChapterArchiveState.REMOTE_PENDING)) {
            it[ChapterRevisionTable.archiveLastError] = reason.take(MAX_ERROR_LENGTH)
            it[ChapterRevisionTable.updatedAt] = now
        }

    /**
     * Confirmed revisions whose downloaded pages are still on local staging.
     *
     * [ChapterRevisionTable.candidatePath] is the durable marker of that pending cleanup and is
     * cleared only after the local files really are gone.
     *
     * The oldest pending cleanup comes first, so a batch that keeps failing would starve the rows
     * behind it; such a revision is moved to the back with [deferCandidateCleanup] instead.
     */
    fun revisionsAwaitingCleanup(limit: Int): List<ChapterRevisionDataClass> =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where {
                    (ChapterRevisionTable.archiveState eq ChapterArchiveState.REMOTE_CONFIRMED.name) and
                        (ChapterRevisionTable.candidatePath.isNotNull())
                }.orderBy(ChapterRevisionTable.updatedAt to SortOrder.ASC, ChapterRevisionTable.id to SortOrder.ASC)
                .limit(limit)
                .map { ChapterRevisionTable.toDataClass(it) }
        }

    /**
     * Moves a confirmed revision whose local cleanup failed to the back of the pending-cleanup queue.
     *
     * [ChapterRevisionTable.updatedAt] is the ordering key of [revisionsAwaitingCleanup], so bumping
     * it lets later confirmed revisions be cleaned up instead of being blocked behind a file that
     * cannot be deleted right now. The revision keeps its confirmation and its cleanup marker.
     */
    fun deferCandidateCleanup(
        id: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            val latestCleanupTimestamp =
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        (ChapterRevisionTable.archiveState eq ChapterArchiveState.REMOTE_CONFIRMED.name) and
                            (ChapterRevisionTable.candidatePath.isNotNull())
                    }.orderBy(ChapterRevisionTable.updatedAt to SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()
                    ?.get(ChapterRevisionTable.updatedAt)
                    ?: now
            val deferredUntil = maxOf(now, latestCleanupTimestamp + 1)

            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.archiveState eq ChapterArchiveState.REMOTE_CONFIRMED.name) and
                    (ChapterRevisionTable.candidatePath.isNotNull())
            }) {
                it[ChapterRevisionTable.updatedAt] = deferredUntil
            } > 0
        }

    /** Clears the pending-cleanup marker of a confirmed revision after its local files are gone. */
    fun markCandidateCleanupComplete(
        id: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.archiveState eq ChapterArchiveState.REMOTE_CONFIRMED.name) and
                    (ChapterRevisionTable.candidatePath.isNotNull())
            }) {
                it[ChapterRevisionTable.candidatePath] = null
                it[ChapterRevisionTable.updatedAt] = now
            } > 0
        }

    // -----------------------------------------------------------------------------------------
    // accepted-revision lifecycle (independent from acquisition, archive durability and publication)
    // -----------------------------------------------------------------------------------------

    /** The active accepted revision of a chapter identity, or null when the chapter has none. */
    fun getActiveRevision(chapterKey: String): ChapterRevisionDataClass? =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.activeChapterKey eq chapterKey }
                .firstOrNull()
                ?.let { ChapterRevisionTable.toDataClass(it) }
        }

    /**
     * How many superseded accepted revisions are kept for [mangaId] in addition to the active one.
     *
     * A per-series override wins over the global default and
     * [UNLIMITED_ACCEPTED_REVISION_RETENTION] means unlimited. Nothing is deleted here: pruning
     * archived content is only safe once its replacement is remotely confirmed, which the retention
     * sweep owns.
     */
    fun effectiveAcceptedRevisionRetention(mangaId: Int?): Int =
        acceptedRevisionRetentionOverride(mangaId) ?: serverConfig.acceptedRevisionRetention.value

    private fun acceptedRevisionRetentionOverride(mangaId: Int?): Int? {
        if (mangaId == null) {
            return null
        }

        return transaction {
            MangaTable
                .selectAll()
                .where { MangaTable.id eq mangaId }
                .firstOrNull()
                ?.get(MangaTable.acceptedRevisionRetention)
        }
    }

    /**
     * Applies an explicit review decision to candidate revisions.
     *
     * Only revisions that are still [ChapterRevisionDisposition.CANDIDATE] are considered, and the
     * two accepting actions additionally require [ChapterArchiveState.REMOTE_CONFIRMED]: content that
     * is not durably archived must never become the active revision. Every requested revision that
     * does not meet those preconditions is left exactly as it was and is not part of the result,
     * which is what makes a replayed decision a no-op instead of a second state change.
     *
     * The rows of every affected chapter identity are locked in one statement, in a global
     * ascending-id order, before anything is written, so two concurrent decisions on overlapping
     * subsets of the same chapter serialize instead of deadlocking on each other's identities; the
     * unique index on `active_chapter_key` is the database-level backstop for that invariant.
     *
     * When several candidates of one chapter are accepted at once, the one with the highest id wins
     * and the others stay candidates, so the outcome never depends on iteration order.
     */
    fun review(
        ids: List<Int>,
        action: ChapterRevisionReviewAction,
        now: Long = Instant.now().epochSecond,
    ): List<ChapterRevisionDataClass> {
        if (ids.isEmpty()) {
            return emptyList()
        }

        return transaction {
            // Two concurrent decisions may request overlapping subsets of the rows of the same
            // identities. Locking only the requested rows and then the identities would let each
            // transaction hold a part of what the other one is waiting for. The involved identities
            // are therefore determined first, then every one of their rows is locked in a single
            // ascending-id statement, which gives all transactions the same global lock order.
            val involvedChapterKeys =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id inList ids }
                    .map { it[ChapterRevisionTable.chapterKey] }
                    .distinct()

            lockChapterIdentities(involvedChapterKeys)

            val requested =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id inList ids }
                    .orderBy(ChapterRevisionTable.id to SortOrder.ASC)
                    .map { ChapterRevisionTable.toDataClass(it) }

            val decidedIds =
                when (action) {
                    ChapterRevisionReviewAction.ACCEPT_CANDIDATE -> acceptCandidates(requested, now)

                    ChapterRevisionReviewAction.KEEP_BOTH -> keepBothCandidates(requested, now)

                    ChapterRevisionReviewAction.KEEP_CURRENT,
                    ChapterRevisionReviewAction.REJECT_CANDIDATE,
                    -> rejectCandidates(requested, now)
                }

            if (decidedIds.isEmpty()) {
                return@transaction emptyList()
            }

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.id inList decidedIds }
                .orderBy(ChapterRevisionTable.id to SortOrder.ASC)
                .map { ChapterRevisionTable.toDataClass(it) }
        }
    }

    /**
     * Activates a remotely-confirmed revision when its chapter identity has no active revision yet.
     *
     * This is what makes a chapter become visible without a manual decision. A revision that is
     * confirmed later must not silently replace the active one, so it stays a reviewable candidate
     * until an explicit [ChapterRevisionReviewAction.ACCEPT_CANDIDATE].
     */
    fun activateIfFirstConfirmed(
        id: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            val candidate =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id eq id }
                    .firstOrNull()
                    ?.let { ChapterRevisionTable.toDataClass(it) }
                    ?: return@transaction false

            if (candidate.disposition != ChapterRevisionDisposition.CANDIDATE) return@transaction false
            if (candidate.archiveState != ChapterArchiveState.REMOTE_CONFIRMED) return@transaction false

            lockChapterIdentity(candidate.chapterKey)

            val alreadyActive =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.activeChapterKey eq candidate.chapterKey }
                    .limit(1)
                    .firstOrNull()
            if (alreadyActive != null) {
                return@transaction false
            }

            promoteToActive(candidate.id, candidate.chapterKey, now) > 0
        }

    /**
     * Activates a confirmed revision for every chapter identity that has none.
     *
     * Remote confirmation and the first activation are two separate commits: a crash between them
     * would leave a confirmed candidate invisible forever, because an already-confirmed revision is
     * never verified again. This deterministic recovery pass closes that gap and never replaces an
     * already active revision - an identity that has one is left alone and a confirmed candidate
     * confirmed later stays reviewable. When several confirmed candidates of one identity are
     * waiting, the newest one wins, which is the same rule an explicit multi-accept uses.
     */
    fun reconcileConfirmedActivations(now: Long = Instant.now().epochSecond): List<Int> =
        transaction {
            val confirmedCandidates =
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.CANDIDATE.name) and
                            (ChapterRevisionTable.archiveState eq ChapterArchiveState.REMOTE_CONFIRMED.name)
                    }.map { it[ChapterRevisionTable.chapterKey] to it[ChapterRevisionTable.id].value }

            if (confirmedCandidates.isEmpty()) {
                return@transaction emptyList()
            }

            val newestConfirmedByChapterKey =
                confirmedCandidates
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, ids) -> ids.max() }

            val involvedChapterKeys = newestConfirmedByChapterKey.keys.toList()
            lockChapterIdentities(involvedChapterKeys)

            // re-checked under the lock: an explicit acceptance that committed meanwhile wins
            val activeChapterKeys =
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        (ChapterRevisionTable.activeChapterKey.isNotNull()) and
                            (ChapterRevisionTable.chapterKey inList involvedChapterKeys)
                    }.map { it[ChapterRevisionTable.chapterKey] }
                    .toSet()

            newestConfirmedByChapterKey
                .filterKeys { it !in activeChapterKeys }
                .toSortedMap()
                .mapNotNull { (chapterKey, id) -> id.takeIf { promoteToActive(id, chapterKey, now) > 0 } }
        }

    /**
     * Publishes [id] as the active copy of its chapter.
     *
     * The chapter identity's revisions are locked and re-checked immediately before
     * [replaceActiveCopy] runs, so a publication that was started for a revision that has since been
     * superseded can not overwrite the target of the newer activation: that attempt is fenced out and
     * reported as false.
     *
     * [replaceActiveCopy] performs the atomic replacement of the active file and returns the digest
     * of the bytes that actually landed there. It runs inside the transaction, so a failure leaves
     * the revision claimed and the restart recovery simply retries it.
     */
    fun publishActiveCopy(
        id: Int,
        relativeActivePath: String,
        now: Long = Instant.now().epochSecond,
        replaceActiveCopy: () -> ChapterRevisionArtifactDigest,
        onPublished: (published: ChapterRevisionDataClass, replacedActiveCbzPath: String?) -> Unit = { _, _ -> },
    ): Boolean =
        transaction {
            val revision =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id eq id }
                    .firstOrNull()
                    ?.let { ChapterRevisionTable.toDataClass(it) }
                    ?: return@transaction false

            if (!revision.isActiveRevision) return@transaction false
            if (revision.archiveState != ChapterArchiveState.REMOTE_CONFIRMED) return@transaction false
            if (revision.publicationState != ChapterPublicationState.PUBLISHING) return@transaction false

            lockChapterIdentity(revision.chapterKey)

            // fencing: the row has to still be the active, claimed revision after the lock was taken
            ChapterRevisionTable
                .selectAll()
                .where {
                    (ChapterRevisionTable.id eq id) and
                        (ChapterRevisionTable.activeChapterKey eq revision.chapterKey) and
                        (ChapterRevisionTable.archiveState eq ChapterArchiveState.REMOTE_CONFIRMED.name) and
                        (ChapterRevisionTable.publicationState eq ChapterPublicationState.PUBLISHING.name)
                }.limit(1)
                .firstOrNull()
                ?: return@transaction false

            val replaced =
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        // every already published revision of the same chapter identity is replaced here,
                        // including the superseded one: the active-library path is shared per identity, so
                        // the bytes it described are exactly what this publication overwrites
                        (ChapterRevisionTable.chapterKey eq revision.chapterKey) and
                            (ChapterRevisionTable.publicationState eq ChapterPublicationState.PUBLISHED.name) and
                            (ChapterRevisionTable.id neq id)
                    }.map { it[ChapterRevisionTable.id].value to it[ChapterRevisionTable.activeCbzPath] }

            val digest = replaceActiveCopy()
            if (revision.archiveCbzHash != null && digest.sha256 != revision.archiveCbzHash) {
                throw ChapterRevisionPublicationConflictException(
                    "the published copy of revision $id does not match its recorded digest",
                )
            }

            if (replaced.isNotEmpty()) {
                ChapterRevisionTable.update({ ChapterRevisionTable.id inList replaced.map { it.first } }) {
                    it[publicationState] = ChapterPublicationState.NOT_PUBLISHED.name
                    it[updatedAt] = now
                }
            }

            val published =
                ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                    it[publicationState] = ChapterPublicationState.PUBLISHED.name
                    it[activeCbzPath] = relativeActivePath
                    it[activeCbzHash] = digest.sha256
                    it[activeCbzSize] = digest.size
                    it[publishedAt] = now
                    it[publicationLastError] = null
                    it[updatedAt] = now
                } > 0

            if (published) {
                val publishedRevision =
                    ChapterRevisionTable
                        .selectAll()
                        .where { ChapterRevisionTable.id eq id }
                        .first()
                        .let { ChapterRevisionTable.toDataClass(it) }

                onPublished(publishedRevision, replaced.firstOrNull()?.second)
            }

            published
        }

    /**
     * Atomically claims the next active revision that still has to be published.
     *
     * Only an active, remotely-confirmed revision can be claimed, so a candidate that was never
     * confirmed can never become visible in the library.
     */
    fun claimNextPublication(now: Long = Instant.now().epochSecond): ChapterRevisionDataClass? =
        transaction {
            val candidate =
                ChapterRevisionTable
                    .selectAll()
                    .where { publicationCandidateCondition(ChapterPublicationState.NOT_PUBLISHED) }
                    .orderBy(ChapterRevisionTable.updatedAt to SortOrder.ASC, ChapterRevisionTable.id to SortOrder.ASC)
                    .forUpdate()
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction null

            val id = candidate[ChapterRevisionTable.id].value

            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[publicationState] = ChapterPublicationState.PUBLISHING.name
                it[publicationAttempts] = candidate[ChapterRevisionTable.publicationAttempts] + 1
                it[publicationLastAttemptAt] = now
                it[publicationLastError] = null
                it[updatedAt] = now
            }

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.id eq id }
                .first()
                .let { ChapterRevisionTable.toDataClass(it) }
        }

    /** Returns a claim that turned out to be unreachable - a fenced-out attempt - to the queue. */
    fun releasePublicationClaim(
        id: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedPublicationUpdate(id, listOf(ChapterPublicationState.PUBLISHING)) {
            it[publicationState] = ChapterPublicationState.NOT_PUBLISHED.name
            it[updatedAt] = now
        }

    /** Recovers every publication a shutdown left in flight. */
    fun recoverInterruptedPublications(now: Long = Instant.now().epochSecond): Int =
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.publicationState eq ChapterPublicationState.PUBLISHING.name }) {
                it[publicationState] = ChapterPublicationState.NOT_PUBLISHED.name
                it[updatedAt] = now
            }
        }

    fun markPublicationFailed(
        id: Int,
        error: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedPublicationUpdate(id, listOf(ChapterPublicationState.PUBLISHING)) {
            it[publicationState] = ChapterPublicationState.PUBLICATION_FAILED.name
            it[publicationLastError] = error.take(MAX_ERROR_LENGTH)
            it[updatedAt] = now
        }

    /**
     * Requeues revisions whose publication failed, preserving the attempt count.
     *
     * A revision that is no longer the active one is left alone: publishing it would make old content
     * visible again after a newer revision was accepted.
     */
    fun retryPublications(
        ids: List<Int>,
        now: Long = Instant.now().epochSecond,
        beforeCommit: (List<ChapterRevisionDataClass>) -> Unit = {},
    ): List<ChapterRevisionDataClass> =
        transitionPublication(ids, publicationRetryableStates, beforeCommit) { update ->
            update[ChapterRevisionTable.publicationState] = ChapterPublicationState.NOT_PUBLISHED.name
            update[ChapterRevisionTable.publicationLastError] = null
            update[ChapterRevisionTable.updatedAt] = now
        }

    /** One candidate per chapter identity may be activated: the newest requested one. */
    private fun acceptCandidates(
        requested: List<ChapterRevisionDataClass>,
        now: Long,
    ): List<Int> {
        val activatable =
            requested
                .filter { it.disposition == ChapterRevisionDisposition.CANDIDATE }
                .filter { it.archiveState == ChapterArchiveState.REMOTE_CONFIRMED }
                .groupBy { it.chapterKey }
                .values
                .mapNotNull { candidates -> candidates.maxByOrNull { it.id } }

        val activated = mutableListOf<Int>()
        activatable.sortedBy { it.id }.forEach { candidate ->
            lockChapterIdentity(candidate.chapterKey)

            // the previously active revision of this chapter becomes history
            ChapterRevisionTable.update({
                (ChapterRevisionTable.activeChapterKey eq candidate.chapterKey) and (ChapterRevisionTable.id neq candidate.id)
            }) {
                it[disposition] = ChapterRevisionDisposition.SUPERSEDED.name
                it[activeChapterKey] = null
                it[supersededAt] = now
                it[updatedAt] = now
            }

            if (promoteToActive(candidate.id, candidate.chapterKey, now) > 0) {
                activated += candidate.id
            }
        }

        return activated
    }

    /** KEEP_BOTH: the candidate is accepted as history while the active revision keeps its place. */
    private fun keepBothCandidates(
        requested: List<ChapterRevisionDataClass>,
        now: Long,
    ): List<Int> =
        requested
            .filter { it.disposition == ChapterRevisionDisposition.CANDIDATE }
            .filter { it.archiveState == ChapterArchiveState.REMOTE_CONFIRMED }
            .mapNotNull { candidate ->
                val updated =
                    ChapterRevisionTable.update({
                        (ChapterRevisionTable.id eq candidate.id) and
                            (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.CANDIDATE.name)
                    }) {
                        it[disposition] = ChapterRevisionDisposition.ACCEPTED.name
                        it[acceptedAt] = now
                        it[updatedAt] = now
                    }
                candidate.id.takeIf { updated > 0 }
            }

    /**
     * KEEP_CURRENT and REJECT_CANDIDATE both end as [ChapterRevisionDisposition.REJECTED].
     *
     * Dismissing a candidate is always safe - it only removes it from review - so unlike the
     * accepting actions this does not require the candidate to be remotely confirmed yet.
     */
    private fun rejectCandidates(
        requested: List<ChapterRevisionDataClass>,
        now: Long,
    ): List<Int> =
        requested
            .filter { it.disposition == ChapterRevisionDisposition.CANDIDATE }
            .mapNotNull { candidate ->
                val updated =
                    ChapterRevisionTable.update({
                        (ChapterRevisionTable.id eq candidate.id) and
                            (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.CANDIDATE.name)
                    }) {
                        it[disposition] = ChapterRevisionDisposition.REJECTED.name
                        it[updatedAt] = now
                    }
                candidate.id.takeIf { updated > 0 }
            }

    /**
     * Locks every revision row of one chapter identity in a stable order.
     *
     * A single decision that only involves one identity uses this narrower form; a decision over
     * several identities must lock them together with [lockChapterIdentities] instead.
     */
    private fun lockChapterIdentity(chapterKey: String) = lockChapterIdentities(listOf(chapterKey))

    /**
     * Locks every revision row of one chapter identity for a caller outside this object.
     *
     * The visual comparison commits from its own store, but it has to take the same lock the review
     * and acceptance decisions take - otherwise an acceptance could promote a different baseline
     * while a comparison is being written against the old one.
     */
    internal fun lockChapterIdentityForComparison(chapterKey: String) = lockChapterIdentities(listOf(chapterKey))

    /**
     * Locks every revision row of the given chapter identities in one ascending-id statement.
     *
     * Taking the whole union in a single globally ordered read is what keeps concurrent decisions
     * that overlap on some identities from deadlocking: every caller acquires the same rows in the
     * same order, so one simply waits for the other to commit.
     */
    private fun lockChapterIdentities(chapterKeys: List<String>) {
        if (chapterKeys.isEmpty()) {
            return
        }

        ChapterRevisionTable
            .selectAll()
            .where { ChapterRevisionTable.chapterKey inList chapterKeys }
            .orderBy(ChapterRevisionTable.id to SortOrder.ASC)
            .forUpdate()
            .map { it[ChapterRevisionTable.id].value }
    }

    private fun promoteToActive(
        id: Int,
        chapterKey: String,
        now: Long,
    ): Int =
        ChapterRevisionTable.update({
            (ChapterRevisionTable.id eq id) and
                (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.CANDIDATE.name) and
                (ChapterRevisionTable.archiveState eq ChapterArchiveState.REMOTE_CONFIRMED.name)
        }) {
            it[disposition] = ChapterRevisionDisposition.ACCEPTED.name
            it[activeChapterKey] = chapterKey
            it[acceptedAt] = now
            it[activatedAt] = now
            it[updatedAt] = now
        }

    // -----------------------------------------------------------------------------------------
    // accepted-revision retention (independent from acquisition, archive durability and publication)
    // -----------------------------------------------------------------------------------------

    /**
     * Order of the accepted revisions of one chapter, newest first.
     *
     * `acceptedAt` is the decision time of a kept historical revision and `activatedAt` the time a
     * revision became the active one; the id breaks ties so the order never depends on a timestamp
     * collision.
     */
    private val revisionRecencyOrder =
        compareByDescending<ChapterRevisionDataClass> { it.acceptedAt ?: it.activatedAt ?: Long.MIN_VALUE }
            .thenByDescending { it.id }

    /**
     * Reconciles the retention window of one chapter identity.
     *
     * A revision may only be queued for pruning once its replacement is the active revision, that
     * replacement is durably archived and it is actually published: until then the older payload may
     * still be the only readable copy of the chapter. Returning a revision to the retention window
     * is always safe and therefore happens regardless of that gate, but only while nothing has been
     * deleted yet - a deletion that already started is never silently abandoned.
     *
     * @return true when the persisted retention state of this identity changed.
     */
    fun reconcileRetention(
        chapterKey: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            lockChapterIdentities(listOf(chapterKey))

            val revisions =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.chapterKey eq chapterKey }
                    .orderBy(ChapterRevisionTable.id to SortOrder.ASC)
                    .map { ChapterRevisionTable.toDataClass(it) }

            val active = revisions.firstOrNull { it.activeChapterKey == chapterKey } ?: return@transaction false

            val historical =
                revisions
                    .filter { it.id != active.id }
                    // both a superseded revision and one kept alongside the active one are history
                    .filter {
                        it.disposition == ChapterRevisionDisposition.SUPERSEDED ||
                            it.disposition == ChapterRevisionDisposition.ACCEPTED
                    }
                    // only durably archived content may ever be deleted, and a revision that is still
                    // the published library copy must never be touched
                    .filter { it.archiveState == ChapterArchiveState.REMOTE_CONFIRMED }
                    .filter { it.publicationState != ChapterPublicationState.PUBLISHED }
                    .filter { it.archiveCbzPath != null }
                    // a revision whose deletion already started is not a readable copy any more, so it
                    // neither fills a retention slot nor counts against the window
                    .filterNot { it.deletionStartedOrPayloadGone() }
                    .sortedWith(revisionRecencyOrder)

            val policy = effectiveAcceptedRevisionRetention(active.mangaId)
            val retainCount =
                if (policy == UNLIMITED_ACCEPTED_REVISION_RETENTION) {
                    Int.MAX_VALUE
                } else {
                    policy.coerceAtLeast(0)
                }
            val retained = historical.take(retainCount).map { it.id }.toSet()
            val prunable = historical.drop(retainCount).map { it.id }.toSet()

            var changed = false

            // Back inside the retention window again. Only a row whose deletion has not started may
            // be kept: DELETING and everything after it is past the point of no return.
            val toRetain =
                revisions
                    .filter {
                        it.id in retained &&
                            it.retentionState == ChapterRetentionState.PRUNE_QUEUED &&
                            !it.deletionStartedOrPayloadGone()
                    }.map { it.id }
            if (toRetain.isNotEmpty()) {
                ChapterRevisionTable.update({ ChapterRevisionTable.id inList toRetain }) {
                    it[retentionState] = ChapterRetentionState.RETAINED.name
                    it[retentionQueuedAt] = null
                    it[retentionNextVerificationAt] = null
                    it[retentionLastError] = null
                    it[updatedAt] = now
                }
                changed = true
            }

            // The replacement has to be durable and published before anything may be pruned. Rows that
            // are already queued stay queued: they were queued against an earlier, durable replacement
            // and removing their payload is still safe.
            val replacementDurable =
                active.archiveState == ChapterArchiveState.REMOTE_CONFIRMED &&
                    active.publicationState == ChapterPublicationState.PUBLISHED
            if (!replacementDurable) {
                return@transaction changed
            }

            val toQueue =
                revisions
                    .filter { it.id in prunable && it.retentionState == ChapterRetentionState.RETAINED }
                    .map { it.id }
            if (toQueue.isNotEmpty()) {
                ChapterRevisionTable.update({ ChapterRevisionTable.id inList toQueue }) {
                    it[retentionState] = ChapterRetentionState.PRUNE_QUEUED.name
                    it[retentionQueuedAt] = now
                    it[retentionNextVerificationAt] = null
                    it[retentionLastError] = null
                    it[updatedAt] = now
                }
                changed = true
            }

            changed
        }

    /**
     * One page of chapter identities whose active revision is published, ordered by identity.
     *
     * The retention sweep walks this page by page, so a library-wide policy change is bounded
     * instead of loading every revision of every chapter at once.
     */
    fun publishedActiveRevisionIdentities(
        afterChapterKey: String?,
        limit: Int,
    ): List<String> =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where {
                    val published =
                        (ChapterRevisionTable.activeChapterKey.isNotNull()) and
                            (ChapterRevisionTable.publicationState eq ChapterPublicationState.PUBLISHED.name)
                    if (afterChapterKey == null) {
                        published
                    } else {
                        published and (ChapterRevisionTable.chapterKey greater afterChapterKey)
                    }
                }.orderBy(ChapterRevisionTable.chapterKey to SortOrder.ASC)
                .limit(limit)
                .map { it[ChapterRevisionTable.chapterKey] }
        }

    /**
     * Published active revision identities of specific series, ordered and deduplicated.
     *
     * A per-series retention override only changes those series, so this is the targeted form of the
     * library-wide [publishedActiveRevisionIdentities] page and never scans the remaining series.
     */
    fun publishedActiveRevisionIdentitiesForMangas(mangaIds: List<Int>): List<String> =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where {
                    (ChapterRevisionTable.activeChapterKey.isNotNull()) and
                        (ChapterRevisionTable.publicationState eq ChapterPublicationState.PUBLISHED.name) and
                        (ChapterRevisionTable.manga inList mangaIds)
                }.orderBy(ChapterRevisionTable.chapterKey to SortOrder.ASC)
                .map { it[ChapterRevisionTable.chapterKey] }
                .distinct()
        }

    /**
     * Atomically claims the oldest revision waiting to be pruned.
     *
     * The state and the active marker are both part of the claim, so a row that returned to the
     * retention window - or that somehow became the active revision - can never be claimed.
     */
    fun claimNextPruneQueued(now: Long = Instant.now().epochSecond): ChapterRevisionDataClass? =
        transaction {
            val candidate =
                ChapterRevisionTable
                    .selectAll()
                    .where { pruneClaimableCondition(now) }
                    .orderBy(
                        ChapterRevisionTable.retentionQueuedAt to SortOrder.ASC_NULLS_FIRST,
                        ChapterRevisionTable.id to SortOrder.ASC,
                    ).forUpdate()
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction null

            val id = candidate[ChapterRevisionTable.id].value

            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.retentionState eq ChapterRetentionState.PRUNE_QUEUED.name)
            }) {
                it[retentionState] = ChapterRetentionState.DELETING.name
                it[retentionAttempts] = candidate[ChapterRevisionTable.retentionAttempts] + 1
                it[retentionLastAttemptAt] = now
                it[retentionLastError] = null
                it[updatedAt] = now
            }

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.id eq id }
                .first()
                .let { ChapterRevisionTable.toDataClass(it) }
        }

    /**
     * Re-checks, under the chapter identity lock, that a claimed revision may still be deleted.
     *
     * A revision can become the active one between its claim and the deletion - an explicit
     * acceptance is exactly that - and deleting the payload of the active revision would destroy the
     * chapter's own archive. The check is therefore repeated immediately before the filesystem is
     * touched and the row is fenced on both its state and its active marker. A revision that turned
     * out to be active is returned to the retention window instead of being deleted.
     */
    fun authorizePruneDeletion(
        id: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            val revision =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id eq id }
                    .firstOrNull()
                    ?.let { ChapterRevisionTable.toDataClass(it) }
                    ?: return@transaction false

            if (revision.retentionState != ChapterRetentionState.DELETING) return@transaction false

            if (revision.isActiveRevision) {
                ChapterRevisionTable.update({
                    (ChapterRevisionTable.id eq id) and
                        (ChapterRevisionTable.retentionState eq ChapterRetentionState.DELETING.name)
                }) {
                    it[retentionState] = ChapterRetentionState.RETAINED.name
                    it[retentionQueuedAt] = null
                    it[retentionNextVerificationAt] = null
                    it[updatedAt] = now
                }
                return@transaction false
            }

            lockChapterIdentities(listOf(revision.chapterKey))

            ChapterRevisionTable
                .selectAll()
                .where {
                    (ChapterRevisionTable.id eq id) and
                        (ChapterRevisionTable.retentionState eq ChapterRetentionState.DELETING.name) and
                        (ChapterRevisionTable.activeChapterKey.isNull())
                }.forUpdate()
                .limit(1)
                .firstOrNull() != null
        }

    /**
     * Records that the archived payload of a claimed revision is gone from the mounted archive.
     *
     * The remote absence check is made due immediately, so the worker claims it with a proper lease
     * instead of the deletion report being trusted as confirmation.
     */
    fun markRemoteDeletePending(
        id: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedRetentionUpdate(id, listOf(ChapterRetentionState.DELETING)) {
            it[retentionState] = ChapterRetentionState.REMOTE_DELETE_PENDING.name
            it[deletedAt] = now
            it[retentionNextVerificationAt] = now
            it[retentionLastError] = null
            it[updatedAt] = now
        }

    /**
     * Records that a prune attempt could not remove the payload and moves the row to the back of the
     * queue, so a payload that cannot be deleted right now never blocks the rows behind it.
     */
    fun deferPruneFailure(
        id: Int,
        error: String,
        retryIntervalSeconds: Long,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            val latestQueuedAt =
                ChapterRevisionTable
                    .selectAll()
                    .where { retentionCandidateCondition(ChapterRetentionState.PRUNE_QUEUED) }
                    .orderBy(ChapterRevisionTable.retentionQueuedAt to SortOrder.DESC_NULLS_LAST)
                    .limit(1)
                    .firstOrNull()
                    ?.get(ChapterRevisionTable.retentionQueuedAt)
                    ?: now
            // the deferred row waits at least one retry interval, and always behind the other queued
            // rows, so one undeletable payload never becomes a hot loop and never starves its peers.
            // Both additions saturate, so an already-saturated queue time stays in the future instead
            // of wrapping into an immediately claimable instant.
            val deferredUntil =
                maxOf(
                    saturatingEpochAdd(now, retryIntervalSeconds.coerceAtLeast(1)),
                    epochSecondAfter(latestQueuedAt),
                )

            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.retentionState eq ChapterRetentionState.DELETING.name)
            }) {
                it[retentionState] = ChapterRetentionState.PRUNE_QUEUED.name
                it[retentionQueuedAt] = deferredUntil
                it[retentionLastError] = error.take(MAX_ERROR_LENGTH)
                it[updatedAt] = now
            } > 0
        }

    /**
     * Records that remote storage confirmed the absence of the archived payload.
     *
     * This is irreversible: a pruned payload only comes back by acquiring the revision again. The
     * sidecar manifest and every recorded path/hash/size stay in place as the audit record.
     */
    fun markPruned(
        id: Int,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedRetentionUpdate(id, listOf(ChapterRetentionState.REMOTE_DELETE_PENDING)) {
            it[retentionState] = ChapterRetentionState.PRUNED.name
            it[prunedAt] = now
            it[retentionNextVerificationAt] = null
            it[retentionLastError] = null
            it[updatedAt] = now
        }

    /** Records a genuine pruning failure that only an explicit retry requeues. */
    fun markPruneFailed(
        id: Int,
        error: String,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedRetentionUpdate(
            id,
            listOf(ChapterRetentionState.DELETING, ChapterRetentionState.REMOTE_DELETE_PENDING),
        ) {
            it[retentionState] = ChapterRetentionState.PRUNE_FAILED.name
            it[retentionLastError] = error.take(MAX_ERROR_LENGTH)
            it[retentionNextVerificationAt] = null
            it[updatedAt] = now
        }

    /**
     * Records a retryable absence outcome: the payload is gone locally, but remote storage either
     * still lists it or could not be asked yet, so the revision stays REMOTE_DELETE_PENDING.
     */
    fun markRetentionVerificationPending(
        id: Int,
        reason: String,
        nextVerificationAt: Long?,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        guardedRetentionUpdate(id, listOf(ChapterRetentionState.REMOTE_DELETE_PENDING)) {
            it[retentionLastError] = reason.take(MAX_ERROR_LENGTH)
            it[retentionNextVerificationAt] = nextVerificationAt
            it[updatedAt] = now
        }

    /**
     * Atomically claims the next revision whose remote absence check is due.
     *
     * The next check is scheduled and the attempt counted before the external command runs, so a
     * crash or a second server instance can never hot-loop on the same row. [leaseSeconds] has to
     * cover the worst case runtime of one check - its command timeout plus the termination grace -
     * and not merely the retry cadence.
     */
    fun claimNextDueRetentionVerification(
        now: Long = Instant.now().epochSecond,
        leaseSeconds: Long,
    ): ChapterRevisionDataClass? =
        transaction {
            val candidate =
                ChapterRevisionTable
                    .selectAll()
                    .where { retentionVerificationDueCondition(now) }
                    .orderBy(
                        ChapterRevisionTable.retentionNextVerificationAt to SortOrder.ASC_NULLS_FIRST,
                        ChapterRevisionTable.id to SortOrder.ASC,
                    ).forUpdate()
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction null

            val id = candidate[ChapterRevisionTable.id].value

            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[retentionAttempts] = candidate[ChapterRevisionTable.retentionAttempts] + 1
                it[retentionLastAttemptAt] = now
                it[retentionNextVerificationAt] = saturatingEpochAdd(now, leaseSeconds)
                it[retentionLastError] = null
                it[updatedAt] = now
            }

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.id eq id }
                .first()
                .let { ChapterRevisionTable.toDataClass(it) }
        }

    /**
     * Earliest instant at which the retention worker has work it can not claim right now, or null
     * when nothing waits at all.
     *
     * It covers both queues the worker drains - revisions waiting to be pruned with a queue time in
     * the future, and deleted revisions whose remote absence check is scheduled - so the worker can
     * sleep until then instead of polling. A row that is claimable now makes this return `now`, and a
     * row without any persisted due time is deliberately not claimable and contributes nothing.
     */
    fun nextRetentionDueAt(now: Long = Instant.now().epochSecond): Long? =
        transaction {
            val claimableNow =
                ChapterRevisionTable
                    .selectAll()
                    .where { pruneClaimableCondition(now) }
                    .limit(1)
                    .firstOrNull()
            if (claimableNow != null) {
                return@transaction now
            }

            val verificationDueNow =
                ChapterRevisionTable
                    .selectAll()
                    .where { retentionVerificationDueCondition(now) }
                    .limit(1)
                    .firstOrNull()
            if (verificationDueNow != null) {
                return@transaction now
            }

            val nextPrune =
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        retentionCandidateCondition(ChapterRetentionState.PRUNE_QUEUED) and
                            (ChapterRevisionTable.activeChapterKey.isNull()) and
                            (ChapterRevisionTable.retentionQueuedAt.isNotNull())
                    }.orderBy(ChapterRevisionTable.retentionQueuedAt to SortOrder.ASC)
                    .limit(1)
                    .firstOrNull()
                    ?.get(ChapterRevisionTable.retentionQueuedAt)

            val nextVerification =
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        retentionPendingVerificationCondition() and
                            (ChapterRevisionTable.retentionNextVerificationAt.isNotNull())
                    }.orderBy(ChapterRevisionTable.retentionNextVerificationAt to SortOrder.ASC)
                    .limit(1)
                    .firstOrNull()
                    ?.get(ChapterRevisionTable.retentionNextVerificationAt)

            listOfNotNull(nextPrune, nextVerification).minOrNull()
        }

    /**
     * Makes every unscheduled deletion-waiting revision due now.
     *
     * A revision reaches REMOTE_DELETE_PENDING whenever its mounted payload is gone, but it can stay
     * unscheduled forever - because remote verification is not configured, or because the server
     * stopped before the first check. Startup has to make such rows due instead of leaving them
     * unverifiable, so configuring a remote later confirms them without any other intervention.
     */
    fun schedulePendingRetentionVerifications(now: Long = Instant.now().epochSecond): Int =
        transaction {
            ChapterRevisionTable.update({
                retentionPendingVerificationCondition() and
                    (ChapterRevisionTable.retentionNextVerificationAt.isNull())
            }) {
                it[retentionNextVerificationAt] = now
                it[updatedAt] = now
            }
        }

    /** Recovers every prune claim a shutdown left in flight, preserving the attempt count. */
    fun recoverInterruptedPruning(now: Long = Instant.now().epochSecond): Int =
        transaction {
            ChapterRevisionTable.update({
                ChapterRevisionTable.retentionState inList retentionInFlightStates.map { it.name }
            }) {
                it[retentionState] = ChapterRetentionState.PRUNE_QUEUED.name
                it[retentionQueuedAt] = now
                it[updatedAt] = now
            }
        }

    /**
     * Requeues revisions whose pruning failed, preserving the attempt count.
     *
     * Only an explicit failure is retryable: a pruned payload cannot be restored, and a revision that
     * is still queued, deleting or waiting for remote confirmation is already in flight.
     */
    fun retryPruning(
        ids: List<Int>,
        now: Long = Instant.now().epochSecond,
        beforeCommit: (List<ChapterRevisionDataClass>) -> Unit = {},
    ): List<ChapterRevisionDataClass> {
        if (ids.isEmpty()) {
            return emptyList()
        }

        return transaction {
            val retryableIds =
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        (ChapterRevisionTable.id inList ids) and
                            (ChapterRevisionTable.retentionState inList retentionRetryableStates.map { it.name })
                    }.orderBy(ChapterRevisionTable.id to SortOrder.ASC)
                    .forUpdate()
                    .map { it[ChapterRevisionTable.id].value }
            if (retryableIds.isEmpty()) {
                return@transaction emptyList()
            }

            ChapterRevisionTable.update({ ChapterRevisionTable.id inList retryableIds }) {
                it[retentionState] = ChapterRetentionState.PRUNE_QUEUED.name
                it[retentionQueuedAt] = now
                it[retentionNextVerificationAt] = null
                it[retentionLastError] = null
                it[updatedAt] = now
            }

            val retried =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id inList retryableIds }
                    .orderBy(ChapterRevisionTable.id to SortOrder.ASC)
                    .map { ChapterRevisionTable.toDataClass(it) }

            beforeCommit(retried)
            retried
        }
    }

    private fun retentionCandidateCondition(state: ChapterRetentionState): Op<Boolean> = ChapterRevisionTable.retentionState eq state.name

    private fun retentionPendingVerificationCondition(): Op<Boolean> =
        ChapterRevisionTable.retentionState eq ChapterRetentionState.REMOTE_DELETE_PENDING.name

    /**
     * A remote absence check is due only when it has a persisted due time that has passed.
     *
     * A null due time means "unscheduled" - a deletion that cannot be verified because no remote was
     * configured at the time - and must never be claimable, otherwise the worker would claim and
     * reschedule the very same row forever. [schedulePendingRetentionVerifications] makes such rows
     * due deliberately at startup and when the remote configuration changes.
     */
    private fun retentionVerificationDueCondition(now: Long): Op<Boolean> =
        retentionPendingVerificationCondition() and
            ChapterRevisionTable.retentionNextVerificationAt.isNotNull() and
            (ChapterRevisionTable.retentionNextVerificationAt lessEq now)

    /**
     * A queued revision is claimable once its not-before time has passed.
     *
     * A null queue time (a queued row from before pruning existed) is claimable immediately, while a
     * deferred row carries a queue time in the future and is skipped until then; that is what keeps a
     * payload that cannot be deleted right now from being reclaimed in a hot loop.
     */
    private fun pruneClaimableCondition(now: Long): Op<Boolean> =
        retentionCandidateCondition(ChapterRetentionState.PRUNE_QUEUED) and
            ChapterRevisionTable.activeChapterKey.isNull() and
            ((ChapterRevisionTable.retentionQueuedAt.isNull()) or (ChapterRevisionTable.retentionQueuedAt lessEq now))

    private fun guardedRetentionUpdate(
        id: Int,
        expectedStates: List<ChapterRetentionState>,
        applyTransition: ChapterRevisionTable.(UpdateStatement) -> Unit,
    ): Boolean =
        transaction {
            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.retentionState inList expectedStates.map { it.name })
            }) { applyTransition(it) } > 0
        }

    private fun publicationCandidateCondition(state: ChapterPublicationState): Op<Boolean> =
        (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.ACCEPTED.name) and
            (ChapterRevisionTable.activeChapterKey.isNotNull()) and
            (ChapterRevisionTable.archiveState eq ChapterArchiveState.REMOTE_CONFIRMED.name) and
            (ChapterRevisionTable.publicationState eq state.name)

    private fun guardedPublicationUpdate(
        id: Int,
        expectedStates: List<ChapterPublicationState>,
        applyTransition: ChapterRevisionTable.(UpdateStatement) -> Unit,
    ): Boolean =
        transaction {
            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.publicationState inList expectedStates.map { it.name })
            }) { applyTransition(it) } > 0
        }

    private fun transitionPublication(
        ids: List<Int>,
        states: List<ChapterPublicationState>,
        beforeCommit: (List<ChapterRevisionDataClass>) -> Unit = {},
        applyTransition: (UpdateStatement) -> Unit,
    ): List<ChapterRevisionDataClass> {
        if (ids.isEmpty()) {
            return emptyList()
        }

        return transaction {
            val transitionedIds =
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        (ChapterRevisionTable.id inList ids) and
                            (ChapterRevisionTable.publicationState inList states.map { it.name })
                    }.orderBy(ChapterRevisionTable.id to SortOrder.ASC)
                    .forUpdate()
                    .map { it[ChapterRevisionTable.id].value }
            if (transitionedIds.isEmpty()) {
                return@transaction emptyList()
            }

            ChapterRevisionTable.update({ ChapterRevisionTable.id inList transitionedIds }) { applyTransition(it) }

            val transitioned =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id inList transitionedIds }
                    .map { ChapterRevisionTable.toDataClass(it) }

            beforeCommit(transitioned)
            transitioned
        }
    }

    private fun verificationCandidateCondition(): Op<Boolean> =
        (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.CANDIDATE.name) and
            (ChapterRevisionTable.archiveState eq ChapterArchiveState.REMOTE_PENDING.name)

    private fun verificationDueCondition(now: Long): Op<Boolean> =
        verificationCandidateCondition() and
            ((ChapterRevisionTable.archiveNextVerificationAt.isNull()) or (ChapterRevisionTable.archiveNextVerificationAt lessEq now))

    private fun guardedUpdate(
        id: Int,
        expectedStates: List<ChapterAcquisitionState>,
        applyTransition: ChapterRevisionTable.(UpdateStatement) -> Unit,
    ): Boolean =
        transaction {
            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.acquisitionState inList expectedStates.map { it.name })
            }) { applyTransition(it) } > 0
        }

    private fun guardedArchiveUpdate(
        id: Int,
        expectedStates: List<ChapterArchiveState>,
        applyTransition: ChapterRevisionTable.(UpdateStatement) -> Unit,
    ): Boolean =
        transaction {
            ChapterRevisionTable.update({
                (ChapterRevisionTable.id eq id) and
                    (ChapterRevisionTable.archiveState inList expectedStates.map { it.name })
            }) { applyTransition(it) } > 0
        }

    private fun transitionArchive(
        ids: List<Int>,
        states: List<ChapterArchiveState>,
        beforeCommit: (List<ChapterRevisionDataClass>) -> Unit = {},
        applyTransition: (UpdateStatement) -> Unit,
    ): List<ChapterRevisionDataClass> {
        if (ids.isEmpty()) {
            return emptyList()
        }

        return transaction {
            val transitionedIds =
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        (ChapterRevisionTable.id inList ids) and
                            (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.CANDIDATE.name) and
                            (ChapterRevisionTable.archiveState inList states.map { it.name })
                    }.orderBy(ChapterRevisionTable.id to SortOrder.ASC)
                    .forUpdate()
                    .map { it[ChapterRevisionTable.id].value }
            if (transitionedIds.isEmpty()) {
                return@transaction emptyList()
            }

            ChapterRevisionTable.update({ ChapterRevisionTable.id inList transitionedIds }) { applyTransition(it) }

            val transitioned =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id inList transitionedIds }
                    .map { ChapterRevisionTable.toDataClass(it) }

            beforeCommit(transitioned)
            transitioned
        }
    }

    /**
     * Rejects explicitly selected candidates, preserving all audit data.
     *
     * Only [approvableStates] are transitioned; terminal or already approved candidates are left as-is.
     */
    fun reject(
        ids: List<Int>,
        now: Long = Instant.now().epochSecond,
    ): List<ChapterRevisionDataClass> =
        transition(ids, approvableStates.toList()) { update ->
            update[ChapterRevisionTable.disposition] = ChapterRevisionDisposition.REJECTED.name
            update[ChapterRevisionTable.updatedAt] = now
        }

    private fun transition(
        ids: List<Int>,
        states: List<ChapterAcquisitionState>,
        beforeCommit: (List<ChapterRevisionDataClass>) -> Unit = {},
        applyTransition: (UpdateStatement) -> Unit,
    ): List<ChapterRevisionDataClass> {
        if (ids.isEmpty()) {
            return emptyList()
        }

        return transaction {
            val condition = selectedCandidatesCondition(ids, states)

            // Lock the selected rows in a stable id order before transitioning so concurrent
            // approve/reject/retry requests serialize instead of overwriting each other. The
            // disposition/state guard is part of the locking read, so a waiter acquires the lock
            // only after the first transition commits and, seeing the row no longer matches, no-ops.
            val transitionedIds =
                ChapterRevisionTable
                    .selectAll()
                    .where { condition }
                    .orderBy(ChapterRevisionTable.id to SortOrder.ASC)
                    .forUpdate()
                    .map { it[ChapterRevisionTable.id].value }
            if (transitionedIds.isEmpty()) {
                return@transaction emptyList()
            }

            ChapterRevisionTable.update({ ChapterRevisionTable.id inList transitionedIds }) { applyTransition(it) }

            val transitioned =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id inList transitionedIds }
                    .map { ChapterRevisionTable.toDataClass(it) }

            beforeCommit(transitioned)
            transitioned
        }
    }

    private fun candidateCondition(state: ChapterAcquisitionState): Op<Boolean> =
        (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.CANDIDATE.name) and
            (ChapterRevisionTable.acquisitionState eq state.name)

    private fun archiveCandidateCondition(state: ChapterArchiveState): Op<Boolean> =
        (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.CANDIDATE.name) and
            (ChapterRevisionTable.acquisitionState eq ChapterAcquisitionState.COMPLETE.name) and
            (ChapterRevisionTable.archiveState eq state.name) and
            // a revision whose visual analysis is still owed may not be archived: committing it would
            // make the missing comparison permanent instead of answering it
            (ChapterRevisionTable.visualAnalysisState inList visualAnalysisSettledStates.map { it.name })

    private fun selectedCandidatesCondition(
        ids: List<Int>,
        states: List<ChapterAcquisitionState>,
    ): Op<Boolean> =
        (ChapterRevisionTable.id inList ids) and
            (ChapterRevisionTable.disposition eq ChapterRevisionDisposition.CANDIDATE.name) and
            (ChapterRevisionTable.acquisitionState inList states.map { it.name })

    // ------------------------------------------------------------------------------------------
    // archive integrity audit (independent from acquisition, archive, publication and retention)
    // ------------------------------------------------------------------------------------------

    /**
     * The archived revisions an integrity audit may check, newest first within each series.
     *
     * Only content the archive still claims is selected: a revision whose payload is confirmed on
     * remote storage and whose two artifacts the archive recorded. A revision whose deletion already
     * started - or that is already pruned - is deliberately excluded, because its payload is *meant* to
     * be gone and reporting that as a finding would be false evidence rather than an audit result.
     *
     * [newestPerManga] bounds the selection to that many newest revisions of every series; null means
     * every eligible revision, which is only ever reached by an explicitly requested full audit.
     */
    fun integrityAuditCandidates(
        mangaIds: List<Int>? = null,
        newestPerManga: Int? = null,
    ): List<ChapterIntegrityAuditCandidate> =
        transaction {
            val condition = integrityAuditCandidateCondition(mangaIds)

            // A full audit visits every eligible revision, so it is the one selection that really has to
            // read them all. A bounded one picks its window first and then reads back only what it
            // picked, so selecting the newest few revisions of a large library never materializes it.
            val selected =
                if (newestPerManga == null) {
                    ChapterRevisionTable
                        .selectAll()
                        .where { condition }
                        .orderBy(*newestFirstWithinSeries().toTypedArray())
                        .map { ChapterRevisionTable.toDataClass(it) }
                } else {
                    loadRevisionsById(newestRevisionIdsPerManga(condition, newestPerManga))
                }

            selected.mapNotNull { revision ->
                val artifact = auditArtifactOf(revision) ?: return@mapNotNull null
                ChapterIntegrityAuditCandidate(
                    revisionId = revision.id,
                    chapterKey = revision.chapterKey,
                    candidateKey = revision.candidateKey,
                    mangaId = revision.mangaId,
                    chapterId = revision.chapterId,
                    chapterName = revision.name,
                    artifact = artifact,
                )
            }
        }

    /**
     * The order every integrity selection uses: newest first within a series.
     *
     * The series is the leading column so the revisions of one series are contiguous, which is what
     * lets a bounded selection count per series in one pass instead of grouping the whole library. The
     * relative order of two different series is irrelevant to the window, and inside a series the
     * revision id decides, so the selection is deterministic in both dialects.
     */
    private fun newestFirstWithinSeries(): List<Pair<Expression<*>, SortOrder>> =
        listOf(
            ChapterRevisionTable.manga to SortOrder.ASC,
            ChapterRevisionTable.id to SortOrder.DESC,
        )

    /**
     * The newest [newestPerManga] eligible revisions of every series, as revision ids in that order.
     *
     * Only the two columns the window needs are read, one row at a time: the running count is reset
     * whenever the series changes, so nothing but the selected ids is ever held and a library of tens of
     * thousands of revisions is never materialized to choose a few per series.
     *
     * The window is per series deliberately. A single global limit would let a handful of large series
     * consume it and leave every other series unaudited for as long as those keep producing revisions.
     */
    private fun newestRevisionIdsPerManga(
        condition: Op<Boolean>,
        newestPerManga: Int,
    ): List<Int> {
        val width = newestPerManga.coerceAtLeast(1)
        val selected = ArrayList<Int>()
        var currentManga: Int? = null
        var hasCurrentManga = false
        var countedForCurrentManga = 0

        ChapterRevisionTable
            .select(ChapterRevisionTable.manga, ChapterRevisionTable.id)
            .where { condition }
            .orderBy(*newestFirstWithinSeries().toTypedArray())
            .forEach { row ->
                val mangaId = row[ChapterRevisionTable.manga]?.value
                if (!hasCurrentManga || mangaId != currentManga) {
                    hasCurrentManga = true
                    currentManga = mangaId
                    countedForCurrentManga = 0
                }

                if (countedForCurrentManga < width) {
                    countedForCurrentManga++
                    selected += row[ChapterRevisionTable.id].value
                }
            }

        return selected
    }

    /**
     * Reads the revisions of the given ids, in the given order.
     *
     * The window is already chosen, so this is a bounded, chunked read of exactly those rows - never a
     * query per series - and the order is restored from the ids instead of from a second sort.
     */
    private fun loadRevisionsById(ids: List<Int>): List<ChapterRevisionDataClass> {
        if (ids.isEmpty()) {
            return emptyList()
        }

        val byId =
            ids
                .chunked(REVISION_LOAD_BATCH)
                .flatMap { chunk ->
                    ChapterRevisionTable
                        .selectAll()
                        .where { ChapterRevisionTable.id inList chunk }
                        .map { ChapterRevisionTable.toDataClass(it) }
                }.associateBy { it.id }

        return ids.mapNotNull { byId[it] }
    }

    /** Must be called inside a transaction. */
    private fun integrityAuditCandidateCondition(mangaIds: List<Int>?): Op<Boolean> =
        listOfNotNull(
            ChapterRevisionTable.archiveState eq ChapterArchiveState.REMOTE_CONFIRMED.name,
            // a payload whose deletion started is meant to disappear, so it is not audited
            ChapterRevisionTable.retentionState notInList retentionPayloadGoneStates.map { it.name },
            ChapterRevisionTable.deletedAt.isNull(),
            ChapterRevisionTable.archiveCbzPath.isNotNull(),
            ChapterRevisionTable.archiveCbzHash.isNotNull(),
            ChapterRevisionTable.archiveCbzSize.isNotNull(),
            ChapterRevisionTable.archiveManifestPath.isNotNull(),
            ChapterRevisionTable.archiveManifestHash.isNotNull(),
            ChapterRevisionTable.archiveManifestSize.isNotNull(),
            mangaIds?.let { ChapterRevisionTable.manga inList it },
        ).reduce { acc, op -> acc and op }

    /** The artifact identity a revision was archived under, or null when it was not fully recorded. */
    private fun auditArtifactOf(revision: ChapterRevisionDataClass): ChapterRevisionArchiveArtifact? {
        val cbzPath = revision.archiveCbzPath ?: return null
        val manifestPath = revision.archiveManifestPath ?: return null
        val cbzHash = revision.archiveCbzHash ?: return null
        val cbzSize = revision.archiveCbzSize ?: return null
        val manifestHash = revision.archiveManifestHash ?: return null
        val manifestSize = revision.archiveManifestSize ?: return null

        return ChapterRevisionArchiveArtifact(
            candidateKey = revision.candidateKey,
            relativeCbzPath = cbzPath,
            relativeManifestPath = manifestPath,
            cbzSha256 = cbzHash,
            cbzSize = cbzSize,
            manifestSha256 = manifestHash,
            manifestSize = manifestSize,
        )
    }

    /**
     * Records the outcome of a completed integrity check on one revision.
     *
     * Only the integrity dimension is written, and deliberately not [ChapterRevisionTable.updatedAt]:
     * that column orders several unrelated backlogs, so touching it would let an audit move a row in
     * the publication, cleanup or archive queue. A finding therefore never changes what any other
     * worker does - it only says what the last check found.
     */
    fun recordIntegrityOutcome(
        revisionId: Int,
        state: ChapterRevisionIntegrityState,
        sessionId: Int,
        error: String?,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq revisionId }) {
                it[integrityState] = state.name
                it[integrityLastAuditedAt] = now
                it[integrityLastAuditSession] = sessionId
                it[integrityLastError] = error?.take(MAX_INTEGRITY_ERROR_LENGTH)
            } > 0
        }

    /**
     * Returns a chapter identity to the historical revision it should have kept.
     *
     * This is the only way an older revision becomes active again, and it is deliberately narrow. The
     * target has to be an accepted (or previously superseded) revision of its own identity, durably
     * archived, with its payload still there - and its last integrity check must not have found that
     * payload missing or wrong, because republishing it would need exactly the bytes that are known not
     * to be where they were recorded.
     *
     * Everything happens in one transaction under the identity's own lock, taken in the same global
     * order the review and retention decisions take, so a rollback and an acceptance that overlap on one
     * identity serialize instead of deadlocking: the current active revision is superseded, the target
     * becomes the accepted active one, and its publication is reset so the ordinary fenced publication
     * worker republishes its bytes. No file is touched inside the transaction - a reader therefore never
     * observes a half-replaced active copy, a failure leaves the previous one in place, and a target that
     * stopped qualifying after it was superseded takes that supersession back down with it.
     */
    fun rollbackChapterRevision(
        revisionId: Int,
        now: Long = Instant.now().epochSecond,
    ): ChapterRevisionRollbackOutcome = rollbackChapterRevision(revisionId, now, beforeActivation = {})

    /**
     * The decision itself, with the narrow seam a regression needs to reach its fence failure.
     *
     * The activation below is one fenced statement, so it can only affect no row when the target changed
     * while this transaction was holding the identity's lock - an interleaving the lock makes
     * unschedulable from a test. [beforeActivation] runs exactly between the supersession and that
     * statement, so that failure can be forced deterministically; production passes the no-op.
     *
     * Only the private fence signal is translated here. A real failure - a database error, an unexpected
     * throwable - still propagates, and the transaction is rolled back either way.
     */
    internal fun rollbackChapterRevision(
        revisionId: Int,
        now: Long,
        beforeActivation: () -> Unit,
    ): ChapterRevisionRollbackOutcome =
        try {
            performRollback(revisionId, now, beforeActivation)
        } catch (_: RollbackFenceRaceException) {
            ChapterRevisionRollbackOutcome.Refused(ROLLBACK_RACE_REASON)
        }

    private fun performRollback(
        revisionId: Int,
        now: Long,
        beforeActivation: () -> Unit,
    ): ChapterRevisionRollbackOutcome =
        transaction {
            val located =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id eq revisionId }
                    .firstOrNull()
                    ?.let { ChapterRevisionTable.toDataClass(it) }
                    ?: return@transaction ChapterRevisionRollbackOutcome.NotFound

            if (located.isActiveRevision) {
                // replaying the same decision must not write a second audit event or a second transition
                return@transaction ChapterRevisionRollbackOutcome.AlreadyActive
            }

            guardRollbackTarget(located)?.let { return@transaction ChapterRevisionRollbackOutcome.Refused(it) }

            lockChapterIdentity(located.chapterKey)

            // re-read under the lock: an acceptance, a rollback or a pruning that committed meanwhile
            // must be seen here rather than acted on from the pre-lock snapshot
            val target =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id eq revisionId }
                    .firstOrNull()
                    ?.let { ChapterRevisionTable.toDataClass(it) }
                    ?: return@transaction ChapterRevisionRollbackOutcome.NotFound
            if (target.isActiveRevision) {
                return@transaction ChapterRevisionRollbackOutcome.AlreadyActive
            }
            guardRollbackTarget(target)?.let { return@transaction ChapterRevisionRollbackOutcome.Refused(it) }

            val previousActive =
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        (ChapterRevisionTable.activeChapterKey eq target.chapterKey) and
                            (ChapterRevisionTable.id neq target.id)
                    }.orderBy(ChapterRevisionTable.id to SortOrder.ASC)
                    .map { it[ChapterRevisionTable.id].value }
                    .firstOrNull()

            if (previousActive != null) {
                ChapterRevisionTable.update({ ChapterRevisionTable.id eq previousActive }) {
                    it[disposition] = ChapterRevisionDisposition.SUPERSEDED.name
                    it[activeChapterKey] = null
                    it[supersededAt] = now
                    it[updatedAt] = now
                }
            }

            // the seam of rollbackChapterRevision: a regression forces the fence failure here, while
            // production runs it as the no-op it is
            beforeActivation()

            val activated =
                ChapterRevisionTable.update({
                    (ChapterRevisionTable.id eq target.id) and
                        (ChapterRevisionTable.activeChapterKey.isNull()) and
                        (ChapterRevisionTable.archiveState eq ChapterArchiveState.REMOTE_CONFIRMED.name)
                }) {
                    it[disposition] = ChapterRevisionDisposition.ACCEPTED.name
                    it[activeChapterKey] = target.chapterKey
                    it[acceptedAt] = target.acceptedAt ?: now
                    it[activatedAt] = now
                    it[supersededAt] = null
                    // the ordinary publication worker republishes the archived bytes of this revision;
                    // resetting the state is what makes it claim the row, and it is what keeps every
                    // file write outside this transaction
                    it[publicationState] = ChapterPublicationState.NOT_PUBLISHED.name
                    it[publicationLastError] = null
                    it[updatedAt] = now
                } > 0

            if (!activated) {
                // The target stopped qualifying between the guard and the write. The supersession above
                // is part of this transaction, so returning here would commit a chapter with no active
                // revision at all - the private signal rolls the whole decision back instead, and the
                // boundary reports it as the refusal it is.
                throw RollbackFenceRaceException()
            }

            ChapterRevisionRollbackTable.insert {
                it[chapterKey] = target.chapterKey
                it[fromRevisionId] = previousActive
                it[toRevisionId] = target.id
                it[rolledBackAt] = now
            }

            val rolledBack =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.id eq target.id }
                    .first()
                    .let { ChapterRevisionTable.toDataClass(it) }

            ChapterRevisionRollbackOutcome.RolledBack(rolledBack, previousActive)
        }

    /** The persisted reason a target that changed under the lock is refused with. */
    private fun guardRollbackTarget(target: ChapterRevisionDataClass): String? =
        when {
            target.disposition != ChapterRevisionDisposition.ACCEPTED &&
                target.disposition != ChapterRevisionDisposition.SUPERSEDED -> {
                "the revision is not an accepted revision of its chapter"
            }

            target.archiveState != ChapterArchiveState.REMOTE_CONFIRMED -> {
                "the revision is not durably archived"
            }

            target.deletionStartedOrPayloadGone() -> {
                "the archived payload of the revision has been removed"
            }

            target.integrityState.isFinding -> {
                "the last integrity check found the archived payload of the revision unusable"
            }

            else -> {
                null
            }
        }

    /** One page of the recorded rollbacks, newest first, ordered by the append-only row id. */
    fun rollbackHistory(
        chapterKey: String? = null,
        afterId: Int? = null,
        limit: Int,
    ): List<ChapterRevisionRollbackDataClass> =
        transaction {
            val condition =
                listOfNotNull(
                    chapterKey?.let { ChapterRevisionRollbackTable.chapterKey eq it },
                    afterId?.let { ChapterRevisionRollbackTable.id less it },
                ).reduceOrNull { acc, op -> acc and op }

            val query = ChapterRevisionRollbackTable.selectAll()
            if (condition != null) {
                query.andWhere { condition }
            }

            query
                .orderBy(ChapterRevisionRollbackTable.id to SortOrder.DESC)
                .limit(limit)
                .map { ChapterRevisionRollbackTable.toDataClass(it) }
        }

    /** The bounded length of the integrity diagnostic column. */
    private const val MAX_INTEGRITY_ERROR_LENGTH = 1024

    /** How many ids one revision read carries, so a window of tens of thousands stays one round trip per chunk. */
    private const val REVISION_LOAD_BATCH = 500

    /** The persisted reason a rollback whose target changed while it was locked is refused with. */
    private const val ROLLBACK_RACE_REASON =
        "the revision stopped being a durably archived accepted revision of its chapter"
}
