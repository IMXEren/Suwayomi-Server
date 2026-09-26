package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import graphql.schema.DataFetchingEnvironment
import org.jetbrains.exposed.v1.core.ResultRow
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Edge
import suwayomi.tachidesk.graphql.server.primitives.Node
import suwayomi.tachidesk.graphql.server.primitives.NodeList
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.manga.impl.ChapterRevisionDeliverability
import suwayomi.tachidesk.manga.impl.ChapterRevisionDeliveryRoutes
import suwayomi.tachidesk.manga.impl.chapterRevisionDeliverability
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRetentionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDiscoveryReason
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionIntegrityState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionMetadataField
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSignalConfidence
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisState
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import java.util.concurrent.CompletableFuture

/**
 * A chapter revision candidate, its acquisition/archive/publication states and its audit metadata.
 *
 * [chapterId] and [mangaId] are nullable because stored revisions outlive the source rows they came
 * from; the snapshot fields keep the revision readable afterwards.
 */
class ChapterRevisionType(
    val id: Int,
    /** immutable identity of the source chapter, shared by every revision of that chapter */
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
    val discoveredAt: Long,
    val updatedAt: Long,
    val approvedAt: Long?,
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
    val archiveVerificationAttempts: Int,
    val archiveLastVerificationAt: Long?,
    val archiveNextVerificationAt: Long?,
    /** when the revision was accepted, as the active revision or as a kept historical one */
    val acceptedAt: Long?,
    /** when this revision became the active revision of its chapter identity */
    val activatedAt: Long?,
    /** when this revision stopped being the active revision */
    val supersededAt: Long?,
    /** true only while this revision is the active revision of its chapter */
    val isActiveRevision: Boolean,
    /** publication attempt counter, independent from the acquisition and archive counters */
    val publicationAttempts: Int,
    val publicationLastError: String?,
    val publicationLastAttemptAt: Long?,
    /** relative path of the published copy in the archive's active `library/` view */
    val activeCbzPath: String?,
    val activeCbzHash: String?,
    val activeCbzSize: Long?,
    val publishedAt: Long?,
    /** retention/pruning state, independent from every other dimension */
    val retentionState: ChapterRetentionState,
    val retentionAttempts: Int,
    val retentionLastError: String?,
    val retentionLastAttemptAt: Long?,
    /** when the revision entered the pruning queue; the ordering key of that queue */
    val retentionQueuedAt: Long?,
    /** when the next remote absence check is due, null when none is scheduled */
    val retentionNextVerificationAt: Long?,
    /** when the archived payload stopped existing on the mounted archive */
    val deletedAt: Long?,
    /** when remote storage stopped listing the archived payload */
    val prunedAt: Long?,
    /** progress of the visual page comparison against the active baseline, independent of every other dimension */
    val visualAnalysisState: ChapterVisualAnalysisState,
    /** how often the visual analysis worker tried to analyse this revision */
    val visualAnalysisAttempts: Int,
    /** bounded diagnostic of an analysis that could not be produced */
    val visualAnalysisLastError: String?,
    val visualAnalysisLastAttemptAt: Long?,
    /** earliest instant the owed analysis may run, null while it is due immediately or already settled */
    val visualAnalysisNextAttemptAt: Long?,
    /** when the analysis reached a terminal state, null while it is still owed */
    val visualAnalysisCompletedAt: Long?,
    /**
     * Integrity of the archived payload, as the last completed audit found it.
     *
     * Independent from [archiveState]: a finding never downgrades durability and never removes
     * content, so a revision that was durably archived and is now reported missing keeps both facts.
     */
    val integrityState: ChapterRevisionIntegrityState,
    /** when the last completed integrity check ran, null before the first one */
    val integrityLastAuditedAt: Long?,
    /** the audit session that produced [integrityState], null while the revision was never audited */
    val integrityLastAuditSessionId: Int?,
    /** bounded diagnostic of the last finding or failed check, null when there is none */
    val integrityLastError: String?,
) : Node {
    /**
     * The API-absolute address that downloads this revision's archived CBZ, or null when it cannot be
     * downloaded at all.
     *
     * The address is this server's own route and never the location the bytes are kept at. A direct
     * remote location is a short-lived bearer credential for the payload, so it is only ever produced by
     * the request that follows this address and never becomes part of a URL a client may store or share.
     */
    val downloadUrl: String? =
        ChapterRevisionDeliveryRoutes
            .downloadPath(id)
            .takeIf {
                chapterRevisionDeliverability(
                    disposition = disposition,
                    archiveState = archiveState,
                    archiveCbzPath = archiveCbzPath,
                    archiveCbzHash = archiveCbzHash,
                    archiveCbzSize = archiveCbzSize,
                    retentionState = retentionState,
                    deletedAt = deletedAt,
                    integrityState = integrityState,
                ) == ChapterRevisionDeliverability.DELIVERABLE
            }

    constructor(row: ResultRow) : this(
        row[ChapterRevisionTable.id].value,
        row[ChapterRevisionTable.chapterKey],
        row[ChapterRevisionTable.candidateKey],
        row[ChapterRevisionTable.chapter]?.value,
        row[ChapterRevisionTable.manga]?.value,
        row[ChapterRevisionTable.sourceId],
        row[ChapterRevisionTable.sourceMangaUrl],
        row[ChapterRevisionTable.sourceChapterUrl],
        row[ChapterRevisionTable.name],
        row[ChapterRevisionTable.scanlator],
        row[ChapterRevisionTable.uploadDate],
        row[ChapterRevisionTable.chapterNumber],
        ChapterRevisionDiscoveryReason.valueOf(row[ChapterRevisionTable.discoveryReason]),
        ChapterRevisionSignalConfidence.valueOf(row[ChapterRevisionTable.signalConfidence]),
        ChapterRevisionMetadataField.decode(row[ChapterRevisionTable.changedMetadataFields]),
        ChapterRevisionDisposition.valueOf(row[ChapterRevisionTable.disposition]),
        ChapterAcquisitionState.valueOf(row[ChapterRevisionTable.acquisitionState]),
        ChapterArchiveState.valueOf(row[ChapterRevisionTable.archiveState]),
        ChapterPublicationState.valueOf(row[ChapterRevisionTable.publicationState]),
        row[ChapterRevisionTable.pageCount],
        row[ChapterRevisionTable.contentHash],
        row[ChapterRevisionTable.candidatePath],
        row[ChapterRevisionTable.attempts],
        row[ChapterRevisionTable.lastError],
        row[ChapterRevisionTable.lastAttemptAt],
        row[ChapterRevisionTable.discoveredAt],
        row[ChapterRevisionTable.updatedAt],
        row[ChapterRevisionTable.approvedAt],
        row[ChapterRevisionTable.archiveAttempts],
        row[ChapterRevisionTable.archiveLastError],
        row[ChapterRevisionTable.archiveLastAttemptAt],
        row[ChapterRevisionTable.archiveCbzPath],
        row[ChapterRevisionTable.archiveManifestPath],
        row[ChapterRevisionTable.archiveCbzHash],
        row[ChapterRevisionTable.archiveCbzSize],
        row[ChapterRevisionTable.archiveManifestHash],
        row[ChapterRevisionTable.archiveManifestSize],
        row[ChapterRevisionTable.archivedAt],
        row[ChapterRevisionTable.archiveVerificationAttempts],
        row[ChapterRevisionTable.archiveLastVerificationAt],
        row[ChapterRevisionTable.archiveNextVerificationAt],
        row[ChapterRevisionTable.acceptedAt],
        row[ChapterRevisionTable.activatedAt],
        row[ChapterRevisionTable.supersededAt],
        row[ChapterRevisionTable.activeChapterKey] != null,
        row[ChapterRevisionTable.publicationAttempts],
        row[ChapterRevisionTable.publicationLastError],
        row[ChapterRevisionTable.publicationLastAttemptAt],
        row[ChapterRevisionTable.activeCbzPath],
        row[ChapterRevisionTable.activeCbzHash],
        row[ChapterRevisionTable.activeCbzSize],
        row[ChapterRevisionTable.publishedAt],
        ChapterRetentionState.valueOf(row[ChapterRevisionTable.retentionState]),
        row[ChapterRevisionTable.retentionAttempts],
        row[ChapterRevisionTable.retentionLastError],
        row[ChapterRevisionTable.retentionLastAttemptAt],
        row[ChapterRevisionTable.retentionQueuedAt],
        row[ChapterRevisionTable.retentionNextVerificationAt],
        row[ChapterRevisionTable.deletedAt],
        row[ChapterRevisionTable.prunedAt],
        ChapterVisualAnalysisState.valueOf(row[ChapterRevisionTable.visualAnalysisState]),
        row[ChapterRevisionTable.visualAnalysisAttempts],
        row[ChapterRevisionTable.visualAnalysisLastError],
        row[ChapterRevisionTable.visualAnalysisLastAttemptAt],
        row[ChapterRevisionTable.visualAnalysisNextAttemptAt],
        row[ChapterRevisionTable.visualAnalysisCompletedAt],
        ChapterRevisionIntegrityState.valueOf(row[ChapterRevisionTable.integrityState]),
        row[ChapterRevisionTable.integrityLastAuditedAt],
        row[ChapterRevisionTable.integrityLastAuditSession],
        row[ChapterRevisionTable.integrityLastError],
    )

    constructor(dataClass: ChapterRevisionDataClass) : this(
        dataClass.id,
        dataClass.chapterKey,
        dataClass.candidateKey,
        dataClass.chapterId,
        dataClass.mangaId,
        dataClass.sourceId,
        dataClass.sourceMangaUrl,
        dataClass.sourceChapterUrl,
        dataClass.name,
        dataClass.scanlator,
        dataClass.uploadDate,
        dataClass.chapterNumber,
        dataClass.discoveryReason,
        dataClass.signalConfidence,
        dataClass.changedMetadataFields,
        dataClass.disposition,
        dataClass.acquisitionState,
        dataClass.archiveState,
        dataClass.publicationState,
        dataClass.pageCount,
        dataClass.contentHash,
        dataClass.candidatePath,
        dataClass.attempts,
        dataClass.lastError,
        dataClass.lastAttemptAt,
        dataClass.discoveredAt,
        dataClass.updatedAt,
        dataClass.approvedAt,
        dataClass.archiveAttempts,
        dataClass.archiveLastError,
        dataClass.archiveLastAttemptAt,
        dataClass.archiveCbzPath,
        dataClass.archiveManifestPath,
        dataClass.archiveCbzHash,
        dataClass.archiveCbzSize,
        dataClass.archiveManifestHash,
        dataClass.archiveManifestSize,
        dataClass.archivedAt,
        dataClass.archiveVerificationAttempts,
        dataClass.archiveLastVerificationAt,
        dataClass.archiveNextVerificationAt,
        dataClass.acceptedAt,
        dataClass.activatedAt,
        dataClass.supersededAt,
        dataClass.isActiveRevision,
        dataClass.publicationAttempts,
        dataClass.publicationLastError,
        dataClass.publicationLastAttemptAt,
        dataClass.activeCbzPath,
        dataClass.activeCbzHash,
        dataClass.activeCbzSize,
        dataClass.publishedAt,
        dataClass.retentionState,
        dataClass.retentionAttempts,
        dataClass.retentionLastError,
        dataClass.retentionLastAttemptAt,
        dataClass.retentionQueuedAt,
        dataClass.retentionNextVerificationAt,
        dataClass.deletedAt,
        dataClass.prunedAt,
        dataClass.visualAnalysisState,
        dataClass.visualAnalysisAttempts,
        dataClass.visualAnalysisLastError,
        dataClass.visualAnalysisLastAttemptAt,
        dataClass.visualAnalysisNextAttemptAt,
        dataClass.visualAnalysisCompletedAt,
        dataClass.integrityState,
        dataClass.integrityLastAuditedAt,
        dataClass.integrityLastAuditSessionId,
        dataClass.integrityLastError,
    )

    fun chapter(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<ChapterType?> =
        chapterId?.let { dataFetchingEnvironment.getValueFromDataLoader<Int, ChapterType?>("ChapterDataLoader", it) }
            ?: CompletableFuture.completedFuture(null)

    fun manga(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<MangaType?> =
        mangaId?.let { dataFetchingEnvironment.getValueFromDataLoader<Int, MangaType?>("MangaDataLoader", it) }
            ?: CompletableFuture.completedFuture(null)
}

data class ChapterRevisionNodeList(
    override val nodes: List<ChapterRevisionType>,
    override val edges: List<ChapterRevisionEdge>,
    override val pageInfo: PageInfo,
    override val totalCount: Int,
) : NodeList() {
    data class ChapterRevisionEdge(
        override val cursor: Cursor,
        override val node: ChapterRevisionType,
    ) : Edge()
}
