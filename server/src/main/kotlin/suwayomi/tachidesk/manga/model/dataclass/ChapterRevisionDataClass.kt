package suwayomi.tachidesk.manga.model.dataclass

import kotlinx.serialization.json.JsonObject

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Lifecycle of a discovered candidate revision/edition of a source chapter.
 *
 * [CANDIDATE] is created when reconciliation discovers a chapter. [ACCEPTED] marks a revision the
 * user accepted, either as the active revision of its chapter or as a kept historical one;
 * [SUPERSEDED] is the previously active revision that an accepted candidate replaced.
 *
 * [UNCHANGED] is terminal and means "acquired, proven byte-identical to the active revision". It is
 * a disposition rather than an acquisition state so that every worker which claims by disposition -
 * archiving, review and retention - excludes such a revision structurally instead of each of them
 * having to remember to filter it out.
 */
enum class ChapterRevisionDisposition {
    CANDIDATE,
    ACCEPTED,
    REJECTED,
    SUPERSEDED,
    UNCHANGED,
}

/** Why a durable revision candidate was discovered. */
enum class ChapterRevisionDiscoveryReason {
    NEW_CHAPTER,
    METADATA_CHANGE,

    /**
     * Recorded by an archive bootstrap when it initially visits an already imported series.
     *
     * It is a distinct reason on purpose: an operator can tell the initial backfill apart from later
     * discovery, and a candidate created here is exactly as idempotent as any other candidate.
     */
    BOOTSTRAP_IMPORT,

    /** Recorded by the automatic periodic sweep, which re-checks the newest chapters of a series. */
    PERIODIC_SWEEP,

    /** Recorded by a sweep an operator started explicitly, bounded or over the whole history. */
    MANUAL_SWEEP,
}

/** Strength of the evidence recorded for a candidate revision. */
enum class ChapterRevisionSignalConfidence {
    METADATA_HINT,
    MANIFEST_HINT,
    CONTENT_PROOF,
}

/** Acquisition-relevant source metadata which changed from the preceding chapter snapshot. */
enum class ChapterRevisionMetadataField {
    NAME,
    SCANLATOR,
    UPLOAD_DATE,
    CHAPTER_NUMBER,
    MEMO,
    ;

    companion object {
        fun encode(fields: Collection<ChapterRevisionMetadataField>): String =
            fields.distinct().sortedBy { it.ordinal }.joinToString(",") { it.name }

        fun decode(value: String): List<ChapterRevisionMetadataField> =
            value
                .takeIf { it.isNotBlank() }
                ?.split(',')
                ?.map(::valueOf)
                .orEmpty()
    }
}

/**
 * Explicit review decision for a remotely-confirmed candidate revision.
 *
 * These are deliberately separate from the acquisition `approve`/`reject` operations, which only
 * decide whether a candidate is going to be acquired in the first place.
 */
enum class ChapterRevisionReviewAction {
    /** the candidate becomes the sole active revision; the previously active one is superseded */
    ACCEPT_CANDIDATE,

    /** the candidate is rejected and the currently active revision stays active */
    KEEP_CURRENT,

    /** the candidate is accepted as a kept historical revision while the active one stays active */
    KEEP_BOTH,

    /** the candidate is rejected outright */
    REJECT_CANDIDATE,
}

/**
 * Acquisition progress of a revision. Acquisition is independent of archival durability and library
 * publication, so it is tracked as its own dimension.
 */
enum class ChapterAcquisitionState {
    DISCOVERED,
    PENDING_APPROVAL,
    APPROVED,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED_LOCAL,
    VALIDATING,
    COMPLETE,
    DOWNLOAD_FAILED,
    VALIDATION_FAILED,
}

/** Archival durability of a revision in the archive storage. */
enum class ChapterArchiveState {
    NOT_COMMITTED,
    COMMITTING,
    REMOTE_PENDING,
    REMOTE_CONFIRMED,
    COMMIT_FAILED,
    ARCHIVE_UNCONFIRMED,
}

/**
 * Library publication state of an accepted revision, independent of archival durability.
 *
 * Only a remotely-confirmed revision can be published, and only the active revision of a chapter is
 * published at all. Publication is generic: it makes the immutable CBZ visible in the archive's
 * active `library/` view and emits a post-publication event. A later library layer (for example a
 * Komga listener) consumes that event instead of being hard-coded here.
 */
enum class ChapterPublicationState {
    NOT_PUBLISHED,
    PUBLISHING,
    PUBLISHED,
    PUBLICATION_FAILED,
}

/**
 * Retention/pruning progress of a historical accepted revision, independent of every other
 * dimension.
 *
 * Pruning only ever removes the archived CBZ payload of a revision that is neither the active one
 * nor waiting to replace it, and it is only complete once remote storage itself no longer lists
 * that payload. Nothing here is reversible: a pruned revision can only come back by being acquired
 * again.
 */
enum class ChapterRetentionState {
    /** inside the retention window: nothing is scheduled and nothing may be deleted */
    RETAINED,

    /** outside the retention window and waiting for the pruning worker */
    PRUNE_QUEUED,

    /** claimed by the pruning worker: the archived CBZ payload is being removed */
    DELETING,

    /** the archived CBZ is gone locally, but remote absence is not confirmed yet */
    REMOTE_DELETE_PENDING,

    /** remote absence confirmed; the sidecar manifest is kept as the immutable audit record */
    PRUNED,

    /** a genuine failure that only an explicit retry requeues */
    PRUNE_FAILED,
}

/**
 * A revision candidate/edition of a source chapter.
 *
 * The [chapterId]/[mangaId] relationships are nullable on purpose: stored revisions must survive
 * the disappearance of the source rows so that already archived content keeps its identity.
 * The `source*` fields and the immutable discovery snapshot describe the source state at discovery
 * time and remain readable even after the source rows are gone.
 */
data class ChapterRevisionDataClass(
    val id: Int,
    /**
     * Immutable identity of the source chapter, as opposed to [candidateKey] which identifies one
     * discovery snapshot. Every revision of the same chapter shares it, so it is what groups a
     * chapter's revision history and what the active revision is unique within.
     */
    val chapterKey: String,
    val candidateKey: String,
    val chapterId: Int?,
    val mangaId: Int?,
    val sourceId: Long?,
    val sourceMangaUrl: String?,
    val sourceChapterUrl: String,
    val name: String,
    val scanlator: String?,
    val uploadDate: Long,
    val chapterNumber: Float,
    val memo: JsonObject,
    val discoveryReason: ChapterRevisionDiscoveryReason,
    val signalConfidence: ChapterRevisionSignalConfidence,
    val changedMetadataFields: List<ChapterRevisionMetadataField>,
    val disposition: ChapterRevisionDisposition,
    val acquisitionState: ChapterAcquisitionState,
    val archiveState: ChapterArchiveState,
    val publicationState: ChapterPublicationState,
    val pageCount: Int?,
    val contentHash: String?,
    val candidatePath: String?,
    val attempts: Int,
    val lastError: String?,
    val lastAttemptAt: Long?,
    val archiveAttempts: Int,
    val archiveLastError: String?,
    val archiveLastAttemptAt: Long?,
    val archiveCbzPath: String?,
    val archiveManifestPath: String?,
    val archiveCbzHash: String?,
    val archiveCbzSize: Long?,
    val archiveManifestHash: String?,
    val archiveManifestSize: Long?,
    val archivedAt: Long?,
    /** how often remote durability was checked for this revision */
    val archiveVerificationAttempts: Int,
    /** when the last remote durability check ran, null before the first one */
    val archiveLastVerificationAt: Long?,
    /** when the next remote durability check is due, null when no check is scheduled */
    val archiveNextVerificationAt: Long?,
    val discoveredAt: Long,
    val updatedAt: Long,
    val approvedAt: Long?,
    /** when the revision was accepted, as the active revision or as a kept historical one */
    val acceptedAt: Long?,
    /** when this revision became the active revision of its chapter identity */
    val activatedAt: Long?,
    /** when this revision stopped being the active revision */
    val supersededAt: Long?,
    /**
     * Equals [chapterKey] only while this revision is the active revision of its chapter and is
     * null otherwise. The unique index on it is what makes "at most one active revision per
     * chapter" a database invariant rather than a convention.
     */
    val activeChapterKey: String?,
    /** publication attempt counter, independent from the acquisition and archive attempt counters */
    val publicationAttempts: Int,
    val publicationLastError: String?,
    val publicationLastAttemptAt: Long?,
    /** relative path of the published copy in the archive's active `library/` view */
    val activeCbzPath: String?,
    val activeCbzHash: String?,
    val activeCbzSize: Long?,
    val publishedAt: Long?,
    /** retention/pruning progress of this revision, independent from every other dimension */
    val retentionState: ChapterRetentionState,
    /** how often the pruning worker tried to remove this revision's archived payload */
    val retentionAttempts: Int,
    val retentionLastError: String?,
    val retentionLastAttemptAt: Long?,
    /** when the revision entered the pruning queue; the ordering key of that queue */
    val retentionQueuedAt: Long?,
    /** when the next remote absence check is due, null when none is scheduled */
    val retentionNextVerificationAt: Long?,
    /** when the archived CBZ payload stopped existing on the mounted archive */
    val deletedAt: Long?,
    /** when remote storage stopped listing the archived CBZ payload */
    val prunedAt: Long?,
    /**
     * Result of comparing this revision's content against the active revision of its chapter.
     *
     * Independent from every lifecycle dimension: it never replaces an acquisition, archive or
     * publication state. It only records whether the newly acquired bytes were already archived.
     */
    val comparisonState: ChapterRevisionComparisonState,
    /**
     * The revision that [comparisonState] was decided against, or null when nothing was active yet.
     * Nulled by the database if that revision row is ever removed, so the audit survives it.
     */
    val comparisonBaselineRevisionId: Int?,
    /** when the comparison ran, null before it did */
    val comparedAt: Long?,
    /** bounded, source-independent diagnostic of a comparison that could not be completed */
    val comparisonError: String?,
    /**
     * Earliest instant the staged pages of an [ChapterRevisionComparisonState.EXACT_MATCH] revision may
     * be removed, or null while nothing is scheduled.
     *
     * A revision whose bytes are already archived cannot be archived again, so its local copy is dead
     * weight - but only after the transaction that classified it committed. This column is that
     * schedule: the classification writes it, a successful cleanup clears it, and a failed cleanup
     * pushes it forward. Persisting it is what lets the worker sleep until the right instant instead
     * of polling, and what makes the retry survive a restart.
     */
    val comparisonCleanupDueAt: Long?,
    /**
     * Progress of the visual page comparison against [comparisonBaselineRevisionId].
     *
     * Independent from every lifecycle dimension as well as from [comparisonState]: the whole-chapter
     * digest says *that* the content differs, this dimension says *how* - and it can be queued,
     * running, limited or failed while the revision is archived normally.
     */
    val visualAnalysisState: ChapterVisualAnalysisState,
    /** how often the visual analysis worker tried to analyse this revision */
    val visualAnalysisAttempts: Int,
    /** bounded, source-independent diagnostic of an analysis that failed */
    val visualAnalysisLastError: String?,
    /**
     * Machine readable category of [visualAnalysisLastError], or null when the analysis did not fail.
     *
     * Persisted separately so the archive manifest can record *why* a comparison is missing without
     * copying prose, which is also what keeps a message that mentions a local path out of the archive.
     */
    val visualAnalysisLastFailure: ChapterVisualAnalysisFailure?,
    val visualAnalysisLastAttemptAt: Long?,
    /** earliest instant this candidate's analysis may run, null while it is due immediately */
    val visualAnalysisNextAttemptAt: Long?,
    /** when the analysis reached a terminal state, null while it is still owed */
    val visualAnalysisCompletedAt: Long?,
    /**
     * Integrity of this revision's archived payload, as the last completed audit found it.
     *
     * Independent from [archiveState] on purpose: an audit never downgrades durability and never
     * removes content, so a revision that was durably confirmed and is now reported missing keeps both
     * facts - it was archived, and its payload is not where it was recorded to be.
     */
    val integrityState: ChapterRevisionIntegrityState = ChapterRevisionIntegrityState.NEVER_AUDITED,
    /** when the last completed integrity check ran, null before the first one */
    val integrityLastAuditedAt: Long? = null,
    /** the audit session that produced [integrityState], null while the revision was never audited */
    val integrityLastAuditSessionId: Int? = null,
    /** bounded, source-independent diagnostic of the last finding or failed check */
    val integrityLastError: String? = null,
    /**
     * The sweep item that produced this revision, or null for every other discovery.
     *
     * Unique, so an item owns at most one candidate: retrying the item reuses that candidate while a
     * different item - of the same run or of a later one - always records its own.
     */
    val sweepItemId: Int?,
    /**
     * Immutable snapshot of the canonical binding this revision was discovered under, or null while
     * its manga was unbound.
     *
     * Captured at discovery and never read back from the live binding, so detaching, re-binding or
     * deleting the work afterwards cannot rewrite what the discovery was made for. It is also what the
     * archive manifest records, so the audit survives without the database.
     *
     * There is no cross-source chapter identity in it: [chapterKey] and the active-revision invariant
     * remain source scoped, because two source chapters are not known to be equivalent.
     */
    val canonicalBinding: CanonicalBindingSnapshot? = null,
) {
    /** True while this revision is the active revision of its chapter. */
    val isActiveRevision: Boolean get() = activeChapterKey != null

    /** True while the revision is waiting to be pruned, i.e. outside the retention window. */
    val isAwaitingPruning: Boolean
        get() =
            retentionState == ChapterRetentionState.PRUNE_QUEUED ||
                retentionState == ChapterRetentionState.DELETING ||
                retentionState == ChapterRetentionState.REMOTE_DELETE_PENDING

    /**
     * True once the archived payload of this revision is known not to be usable.
     *
     * A revision whose last check found its payload missing or wrong must not be offered as something
     * that can be made visible again: the bytes such an action would need are exactly the ones the
     * audit could not find.
     */
    val hasIntegrityFinding: Boolean get() = integrityState.isFinding
}
