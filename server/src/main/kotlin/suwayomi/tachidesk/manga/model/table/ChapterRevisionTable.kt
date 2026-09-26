package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.impl.util.lang.EMPTY
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingRole
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingSnapshot
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRetentionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDiscoveryReason
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionIntegrityState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionMetadataField
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSignalConfidence
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisFailure
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisState
import suwayomi.tachidesk.manga.model.table.columns.jsonObjectText
import suwayomi.tachidesk.manga.model.table.columns.truncatingVarchar

/**
 * Durable candidate revision/edition of a source chapter.
 *
 * [chapter] and [manga] are `SET NULL` references so deleting source rows never deletes an already
 * recorded revision; the source snapshot columns keep the row intelligible afterwards.
 */
object ChapterRevisionTable : IntIdTable() {
    /** immutable globally unique discovery key, derived from the source binding and chapter snapshot */
    val candidateKey = varchar("candidate_key", 64).uniqueIndex()

    /**
     * Immutable identity of the source chapter, shared by every revision of that chapter.
     *
     * Mutable display metadata (name, upload date, memo) and the content snapshot are deliberately
     * excluded, so a re-release of the same source chapter groups under the same identity while a
     * different chapter never does.
     */
    val chapterKey = varchar("chapter_key", 64)

    val chapter = optReference("chapter", ChapterTable, ReferenceOption.SET_NULL)
    val manga = optReference("manga", MangaTable, ReferenceOption.SET_NULL)

    val sourceId = long("source_id").nullable()
    val sourceMangaUrl = varchar("source_manga_url", 2048).nullable()
    val sourceChapterUrl = varchar("source_chapter_url", 2048)

    // immutable discovery snapshot
    val name = truncatingVarchar("name", 512)
    val scanlator = truncatingVarchar("scanlator", 256).nullable()
    val uploadDate = long("upload_date").default(0)
    val chapterNumber = float("chapter_number").default(-1f)
    val memo = jsonObjectText("memo").clientDefault { JsonObject.EMPTY }

    // immutable discovery audit: why this snapshot was recorded and what evidence supports it
    val discoveryReason = varchar("discovery_reason", 64).default(ChapterRevisionDiscoveryReason.NEW_CHAPTER.name)
    val signalConfidence = varchar("signal_confidence", 64).default(ChapterRevisionSignalConfidence.METADATA_HINT.name)
    val changedMetadataFields = varchar("changed_metadata_fields", 256).default("")

    val disposition = varchar("disposition", 256).default(ChapterRevisionDisposition.CANDIDATE.name)

    // acquisition, archive durability and publication are independent dimensions
    val acquisitionState = varchar("acquisition_state", 256).default(ChapterAcquisitionState.DISCOVERED.name)
    val archiveState = varchar("archive_state", 256).default(ChapterArchiveState.NOT_COMMITTED.name)
    val publicationState = varchar("publication_state", 256).default(ChapterPublicationState.NOT_PUBLISHED.name)

    // populated by later acquisition/archival phases
    val pageCount = integer("page_count").nullable()
    val contentHash = varchar("content_hash", 64).nullable()
    val candidatePath = varchar("candidate_path", 2048).nullable()

    val attempts = integer("attempts").default(0)
    val lastError = varchar("last_error", 4096).nullable()
    val lastAttemptAt = long("last_attempt_at").nullable()

    // archive commit audit trail, independent from the acquisition attempt counters above
    val archiveAttempts = integer("archive_attempts").default(0)
    val archiveLastError = varchar("archive_last_error", 4096).nullable()
    val archiveLastAttemptAt = long("archive_last_attempt_at").nullable()

    // relative, portable locations of the immutable archived artifacts
    val archiveCbzPath = varchar("archive_cbz_path", 2048).nullable()
    val archiveManifestPath = varchar("archive_manifest_path", 2048).nullable()
    val archiveCbzHash = varchar("archive_cbz_hash", 64).nullable()
    val archiveCbzSize = long("archive_cbz_size").nullable()
    val archiveManifestHash = varchar("archive_manifest_hash", 64).nullable()
    val archiveManifestSize = long("archive_manifest_size").nullable()
    val archivedAt = long("archived_at").nullable()

    // remote durability verification audit, independent from the archive commit attempt counters
    val archiveVerificationAttempts = integer("archive_verification_attempts").default(0)
    val archiveLastVerificationAt = long("archive_last_verification_at").nullable()
    val archiveNextVerificationAt = long("archive_next_verification_at").nullable()

    val discoveredAt = long("discovered_at")
    val updatedAt = long("updated_at")
    val approvedAt = long("approved_at").nullable()

    // accepted-revision lifecycle, independent from the acquisition/archive/publication dimensions
    val acceptedAt = long("accepted_at").nullable()
    val activatedAt = long("activated_at").nullable()
    val supersededAt = long("superseded_at").nullable()

    /**
     * Holds [chapterKey] only while this row is the active revision of its chapter and is null
     * otherwise, so the unique index below enforces "at most one active revision per chapter" in
     * the database itself. Both H2 and PostgreSQL treat nulls as distinct in a unique index.
     */
    val activeChapterKey = varchar("active_chapter_key", 64).nullable()

    // publication audit trail and the published copy in the active `library/` view
    val publicationAttempts = integer("publication_attempts").default(0)
    val publicationLastError = varchar("publication_last_error", 4096).nullable()
    val publicationLastAttemptAt = long("publication_last_attempt_at").nullable()
    val activeCbzPath = varchar("active_cbz_path", 2048).nullable()
    val activeCbzHash = varchar("active_cbz_hash", 64).nullable()
    val activeCbzSize = long("active_cbz_size").nullable()
    val publishedAt = long("published_at").nullable()

    // retention/pruning audit, independent from the acquisition, archive and publication dimensions
    val retentionState = varchar("retention_state", 256).default(ChapterRetentionState.RETAINED.name)
    val retentionAttempts = integer("retention_attempts").default(0)
    val retentionLastError = varchar("retention_last_error", 4096).nullable()
    val retentionLastAttemptAt = long("retention_last_attempt_at").nullable()
    val retentionQueuedAt = long("retention_queued_at").nullable()
    val retentionNextVerificationAt = long("retention_next_verification_at").nullable()
    val deletedAt = long("deleted_at").nullable()
    val prunedAt = long("pruned_at").nullable()

    // content-comparison audit, independent from the acquisition, archive and publication dimensions
    //
    // The column default is NOT_EVALUATED rather than PENDING so that the runtime default agrees with
    // the migration, which hands that state to every row that already existed. Every creation path
    // writes PENDING explicitly, so a candidate is never silently labelled "never analysed".
    val comparisonState = varchar("comparison_state", 64).default(ChapterRevisionComparisonState.NOT_EVALUATED.name)
    val comparisonBaselineRevision = optReference("comparison_baseline_revision", ChapterRevisionTable, ReferenceOption.SET_NULL)
    val comparedAt = long("compared_at").nullable()
    val comparisonError = varchar("comparison_error", 1024).nullable()

    /** earliest instant the staged pages of an unchanged revision may be removed; null while unscheduled */
    val comparisonCleanupDueAt = long("comparison_cleanup_due_at").nullable()

    // visual page comparison audit, independent from the acquisition, archive, publication, retention
    // and whole-chapter comparison dimensions
    //
    // The column default is NOT_REQUIRED rather than QUEUED, exactly like the migration: a revision
    // that has nothing to compare against owes nothing, and only a classification that actually found
    // new content against a baseline queues an analysis explicitly.
    val visualAnalysisState = varchar("visual_analysis_state", 64).default(ChapterVisualAnalysisState.NOT_REQUIRED.name)
    val visualAnalysisAttempts = integer("visual_analysis_attempts").default(0)
    val visualAnalysisLastError = varchar("visual_analysis_last_error", 1024).nullable()

    /**
     * Machine readable category of the failure in [visualAnalysisLastError].
     *
     * Kept beside the message rather than derived from it: the message is prose written for a human,
     * and a manifest or a client that has to react to a failed comparison needs something it can
     * switch on. It is null whenever the analysis did not fail.
     */
    val visualAnalysisLastFailure = varchar("visual_analysis_last_failure", 64).nullable()
    val visualAnalysisLastAttemptAt = long("visual_analysis_last_attempt_at").nullable()

    /** earliest instant this candidate's visual analysis may run; null while it is due immediately */
    val visualAnalysisNextAttemptAt = long("visual_analysis_next_attempt_at").nullable()
    val visualAnalysisCompletedAt = long("visual_analysis_completed_at").nullable()

    /**
     * The sweep item that owns this revision, or null for every other discovery.
     *
     * Unique, so an item can only ever produce one candidate: retrying it reuses that candidate. The
     * reference is `SET NULL` because a sweep is an audit and may be deleted while the content it
     * discovered is kept forever.
     */
    val sweepItem = optReference("sweep_item", ChapterRevisionSweepItemTable, ReferenceOption.SET_NULL)

    /**
     * Immutable snapshot of the canonical binding this revision was discovered under.
     *
     * Deliberately plain nullable columns rather than a reference: the snapshot has to stay readable
     * after the work it names is deleted, and a revision that predates canonical identity is simply
     * null. It is never read back from the live binding, so detaching or re-binding later cannot
     * rewrite what the discovery was made for.
     *
     * There is deliberately no cross-source chapter identity here. `chapterKey` and the single-active
     * -revision invariant remain source scoped; whether two source chapters are the same chapter is an
     * unresolved, manual decision, not something a binding may assume.
     */
    val canonicalWorkKey = varchar("canonical_work_key", 64).nullable()

    val canonicalBindingRole = varchar("canonical_binding_role", 64).nullable()
    val canonicalBindingPriority = integer("canonical_binding_priority").nullable()
    val canonicalBindingPrimary = bool("canonical_binding_primary").nullable()
    val canonicalBindingSourceId = long("canonical_binding_source_id").nullable()
    val canonicalBindingMangaUrl = varchar("canonical_binding_manga_url", 2048).nullable()

    // archive integrity audit dimension, independent from archive durability and from the retention
    // dimension: a finding here never downgrades REMOTE_CONFIRMED and never removes content
    //
    // The column default is NEVER_AUDITED rather than any finding, exactly like the migration: a
    // revision that was never checked decodes as never checked, and a run that cannot conclude never
    // records a finding on it.
    val integrityState = varchar("integrity_state", 64).default(ChapterRevisionIntegrityState.NEVER_AUDITED.name)
    val integrityLastAuditedAt = long("integrity_last_audited_at").nullable()
    val integrityLastAuditSession = integer("integrity_last_audit_session").nullable()
    val integrityLastError = varchar("integrity_last_error", 1024).nullable()

    init {
        // indexes for the real query paths: per-chapter and per-manga revision listing, and the two
        // ordered backlogs (filter on disposition/state, order on the non-null queue/discovery time)
        index("chapter_revision_chapter_idx", false, chapter)
        index("chapter_revision_manga_idx", false, manga)
        index("chapter_revision_discovery_reason_idx", false, discoveryReason, id)
        index("chapter_revision_signal_confidence_idx", false, signalConfidence, id)
        index("chapter_revision_approval_backlog_idx", false, disposition, acquisitionState, discoveredAt, id)
        index("chapter_revision_queued_backlog_idx", false, disposition, acquisitionState, updatedAt, id)
        index("chapter_revision_archive_backlog_idx", false, disposition, acquisitionState, archiveState, updatedAt, id)
        // the due-verification claim filters on disposition + archive state and orders by the
        // persisted next verification time
        index("chapter_revision_verification_backlog_idx", false, disposition, archiveState, archiveNextVerificationAt, id)
        // the pending-cleanup query filters on archive state only and orders by the update time
        index("chapter_revision_cleanup_idx", false, archiveState, updatedAt, id)
        // the revision history of one chapter identity, and the active revision within it
        index("chapter_revision_identity_idx", false, chapterKey, id)
        index("chapter_revision_active_identity_idx", true, activeChapterKey)
        // the publication backlog filters on the active marker + publication state
        index("chapter_revision_publication_backlog_idx", false, activeChapterKey, publicationState, id)
        // the pruning queue is claimed by state and ordered by queue entry
        index("chapter_revision_retention_backlog_idx", false, retentionState, retentionQueuedAt, id)
        // the remote absence re-check is claimed by state and ordered by its due time
        index("chapter_revision_retention_verify_idx", false, retentionState, retentionNextVerificationAt, id)
        // the comparison audit is browsed per state, which is what tells a sweep's duplicates apart
        index("chapter_revision_comparison_state_idx", false, comparisonState, id)
        // the unchanged-payload cleanup is claimed by its persisted due time, so the sweep worker can
        // sleep until the next retry instead of polling for work
        index("chapter_revision_comparison_cleanup_idx", false, comparisonCleanupDueAt, id)
        // the visual analysis backlog is claimed by state and ordered by its persisted due time
        index("chapter_revision_visual_analysis_backlog_idx", false, visualAnalysisState, visualAnalysisNextAttemptAt, id)
        // one candidate at most per sweep item
        index("chapter_revision_sweep_item_idx", true, sweepItem)
        // "which revisions were discovered under this canonical work" is what the snapshot is for
        index("chapter_revision_canonical_work_idx", false, canonicalWorkKey, id)
        // the integrity backlog is browsed per state, which is what a findings view pages through
        index("chapter_revision_integrity_state_idx", false, integrityState, id)
    }
}

fun ChapterRevisionTable.toDataClass(revisionEntry: ResultRow) =
    ChapterRevisionDataClass(
        id = revisionEntry[id].value,
        chapterKey = revisionEntry[chapterKey],
        candidateKey = revisionEntry[candidateKey],
        chapterId = revisionEntry[chapter]?.value,
        mangaId = revisionEntry[manga]?.value,
        sourceId = revisionEntry[sourceId],
        sourceMangaUrl = revisionEntry[sourceMangaUrl],
        sourceChapterUrl = revisionEntry[sourceChapterUrl],
        name = revisionEntry[name],
        scanlator = revisionEntry[scanlator],
        uploadDate = revisionEntry[uploadDate],
        chapterNumber = revisionEntry[chapterNumber],
        memo = revisionEntry[memo],
        discoveryReason = ChapterRevisionDiscoveryReason.valueOf(revisionEntry[discoveryReason]),
        signalConfidence = ChapterRevisionSignalConfidence.valueOf(revisionEntry[signalConfidence]),
        changedMetadataFields = ChapterRevisionMetadataField.decode(revisionEntry[changedMetadataFields]),
        disposition = ChapterRevisionDisposition.valueOf(revisionEntry[disposition]),
        acquisitionState = ChapterAcquisitionState.valueOf(revisionEntry[acquisitionState]),
        archiveState = ChapterArchiveState.valueOf(revisionEntry[archiveState]),
        publicationState = ChapterPublicationState.valueOf(revisionEntry[publicationState]),
        pageCount = revisionEntry[pageCount],
        contentHash = revisionEntry[contentHash],
        candidatePath = revisionEntry[candidatePath],
        attempts = revisionEntry[attempts],
        lastError = revisionEntry[lastError],
        lastAttemptAt = revisionEntry[lastAttemptAt],
        archiveAttempts = revisionEntry[archiveAttempts],
        archiveLastError = revisionEntry[archiveLastError],
        archiveLastAttemptAt = revisionEntry[archiveLastAttemptAt],
        archiveCbzPath = revisionEntry[archiveCbzPath],
        archiveManifestPath = revisionEntry[archiveManifestPath],
        archiveCbzHash = revisionEntry[archiveCbzHash],
        archiveCbzSize = revisionEntry[archiveCbzSize],
        archiveManifestHash = revisionEntry[archiveManifestHash],
        archiveManifestSize = revisionEntry[archiveManifestSize],
        archivedAt = revisionEntry[archivedAt],
        archiveVerificationAttempts = revisionEntry[archiveVerificationAttempts],
        archiveLastVerificationAt = revisionEntry[archiveLastVerificationAt],
        archiveNextVerificationAt = revisionEntry[archiveNextVerificationAt],
        discoveredAt = revisionEntry[discoveredAt],
        updatedAt = revisionEntry[updatedAt],
        approvedAt = revisionEntry[approvedAt],
        acceptedAt = revisionEntry[acceptedAt],
        activatedAt = revisionEntry[activatedAt],
        supersededAt = revisionEntry[supersededAt],
        activeChapterKey = revisionEntry[activeChapterKey],
        publicationAttempts = revisionEntry[publicationAttempts],
        publicationLastError = revisionEntry[publicationLastError],
        publicationLastAttemptAt = revisionEntry[publicationLastAttemptAt],
        activeCbzPath = revisionEntry[activeCbzPath],
        activeCbzHash = revisionEntry[activeCbzHash],
        activeCbzSize = revisionEntry[activeCbzSize],
        publishedAt = revisionEntry[publishedAt],
        retentionState = ChapterRetentionState.valueOf(revisionEntry[retentionState]),
        retentionAttempts = revisionEntry[retentionAttempts],
        retentionLastError = revisionEntry[retentionLastError],
        retentionLastAttemptAt = revisionEntry[retentionLastAttemptAt],
        retentionQueuedAt = revisionEntry[retentionQueuedAt],
        retentionNextVerificationAt = revisionEntry[retentionNextVerificationAt],
        deletedAt = revisionEntry[deletedAt],
        prunedAt = revisionEntry[prunedAt],
        comparisonState = ChapterRevisionComparisonState.valueOf(revisionEntry[comparisonState]),
        comparisonBaselineRevisionId = revisionEntry[comparisonBaselineRevision]?.value,
        comparedAt = revisionEntry[comparedAt],
        comparisonError = revisionEntry[comparisonError],
        comparisonCleanupDueAt = revisionEntry[comparisonCleanupDueAt],
        visualAnalysisState = ChapterVisualAnalysisState.valueOf(revisionEntry[visualAnalysisState]),
        visualAnalysisAttempts = revisionEntry[visualAnalysisAttempts],
        visualAnalysisLastError = revisionEntry[visualAnalysisLastError],
        visualAnalysisLastFailure =
            revisionEntry[visualAnalysisLastFailure]?.let { ChapterVisualAnalysisFailure.valueOf(it) },
        visualAnalysisLastAttemptAt = revisionEntry[visualAnalysisLastAttemptAt],
        visualAnalysisNextAttemptAt = revisionEntry[visualAnalysisNextAttemptAt],
        visualAnalysisCompletedAt = revisionEntry[visualAnalysisCompletedAt],
        sweepItemId = revisionEntry[sweepItem]?.value,
        integrityState = ChapterRevisionIntegrityState.valueOf(revisionEntry[integrityState]),
        integrityLastAuditedAt = revisionEntry[integrityLastAuditedAt],
        integrityLastAuditSessionId = revisionEntry[integrityLastAuditSession],
        integrityLastError = revisionEntry[integrityLastError],
        canonicalBinding = canonicalBindingSnapshotOf(revisionEntry),
    )

/**
 * Reads the canonical binding snapshot of a revision, or null while the revision is unbound.
 *
 * A row that predates canonical identity has no work key and therefore no snapshot at all, which is
 * what it was: discovered while no canonical work claimed its manga.
 */
private fun canonicalBindingSnapshotOf(revisionEntry: ResultRow): CanonicalBindingSnapshot? {
    val workKey = revisionEntry[ChapterRevisionTable.canonicalWorkKey] ?: return null
    return CanonicalBindingSnapshot(
        workKey = workKey,
        bindingRole = CanonicalBindingRole.valueOf(revisionEntry[ChapterRevisionTable.canonicalBindingRole]!!),
        bindingPriority = revisionEntry[ChapterRevisionTable.canonicalBindingPriority] ?: 0,
        bindingPrimary = revisionEntry[ChapterRevisionTable.canonicalBindingPrimary] ?: false,
        bindingSourceId = revisionEntry[ChapterRevisionTable.canonicalBindingSourceId],
        bindingMangaUrl = revisionEntry[ChapterRevisionTable.canonicalBindingMangaUrl],
    )
}
