package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Edge
import suwayomi.tachidesk.graphql.server.primitives.Node
import suwayomi.tachidesk.graphql.server.primitives.NodeList
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.manga.impl.ChapterRevisionComparisonMediaRoutes
import suwayomi.tachidesk.manga.impl.ChapterRevisionThumbnailSide
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonPageDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionPageAlignmentState

/**
 * The stored visual comparison of one revision against the baseline it replaced.
 *
 * The counts describe the whole alignment, so a client can show "1 page modified, 1 page added"
 * without paging through every row first. The per-page audit is a separate, cursor-paged connection
 * because a chapter can have thousands of pages.
 */
class ChapterRevisionComparisonType(
    val revisionId: Int,
    val baselineRevisionId: Int?,
    val baselinePageCount: Int,
    val candidatePageCount: Int,
    val exactCount: Int,
    val visuallyEquivalentCount: Int,
    val modifiedCount: Int,
    val addedCount: Int,
    val removedCount: Int,
    val alignedCount: Int,
    val hammingThreshold: Int,
    val algorithmVersion: String,
    val allPagesVisuallyEquivalent: Boolean,
    val hasLimitations: Boolean,
    val limitations: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    constructor(dataClass: ChapterRevisionComparisonDataClass) : this(
        dataClass.revisionId,
        dataClass.baselineRevisionId,
        dataClass.baselinePageCount,
        dataClass.candidatePageCount,
        dataClass.exactCount,
        dataClass.visuallyEquivalentCount,
        dataClass.modifiedCount,
        dataClass.addedCount,
        dataClass.removedCount,
        dataClass.alignedCount,
        dataClass.hammingThreshold,
        dataClass.algorithmVersion,
        dataClass.allPagesVisuallyEquivalent,
        dataClass.hasLimitations,
        dataClass.limitations,
        dataClass.createdAt,
        dataClass.updatedAt,
    )
}

/**
 * One aligned row of a comparison.
 *
 * The whole-page and perceptual digests stay internal: they are 128 bit fingerprints of a page's content,
 * and a client that can read them can decide whether two pages of a library are the same page - which is
 * not what reviewing a re-release needs. The local thumbnail locations stay internal for the same reason:
 * they name paths inside the server's staging root, so a side is described by whether it has a preview and
 * by an opaque address that is built from this row's identity alone.
 */
class ChapterRevisionComparisonPageType(
    val ordinal: Int,
    val baselinePageIndex: Int?,
    val candidatePageIndex: Int?,
    val state: ChapterRevisionPageAlignmentState,
    val hammingDistance: Int?,
    val baselineWidth: Int?,
    val baselineHeight: Int?,
    val candidateWidth: Int?,
    val candidateHeight: Int?,
    val baselineSize: Long?,
    val candidateSize: Long?,
    /** true when the baseline side of this row has a rendered preview; never a path or a digest */
    val baselinePreviewAvailable: Boolean,
    /** true when the candidate side of this row has a rendered preview; never a path or a digest */
    val candidatePreviewAvailable: Boolean,
    /**
     * Review address of the baseline thumbnail, or null when that side has no preview.
     *
     * Every address is an API-absolute path - it starts with `/api/v1` and names a route on this server -
     * and never a full URL, because only the client knows which host and scheme it reached the server through.
     */
    val baselineThumbnailUrl: String?,
    val candidateThumbnailUrl: String?,
    /** review address of the baseline page itself, or null when the row has no addressable baseline page */
    val baselinePageUrl: String?,
    val candidatePageUrl: String?,
) : Node {
    /**
     * [baselineAvailable] says whether the revision this row was compared against still exists.
     *
     * A row keeps its baseline indices and its recorded previews after that revision is pruned - the audit
     * is written to outlive the content it describes - but no baseline page can be served once the revision
     * is gone, so its address is withheld instead of being offered and then refused. The baseline thumbnail
     * is unaffected: it is stored with the comparison itself, not with the baseline revision.
     */
    constructor(
        dataClass: ChapterRevisionComparisonPageDataClass,
        baselineAvailable: Boolean,
    ) : this(
        dataClass.ordinal,
        dataClass.baselinePageIndex,
        dataClass.candidatePageIndex,
        dataClass.state,
        dataClass.hammingDistance,
        dataClass.baselineWidth,
        dataClass.baselineHeight,
        dataClass.candidateWidth,
        dataClass.candidateHeight,
        dataClass.baselineSize,
        dataClass.candidateSize,
        // read straight off the row: a preview exists exactly when the attempt that stored the row
        // rendered that side, so nothing has to be looked up or guessed here
        dataClass.hasBaselinePreview,
        dataClass.hasCandidatePreview,
        // an address is offered exactly when the row says something is there, so a client never has to
        // probe for 404s; what the address yields is decided server side on every request
        thumbnailUrlOrNull(dataClass, ChapterRevisionThumbnailSide.BASELINE, dataClass.hasBaselinePreview),
        thumbnailUrlOrNull(dataClass, ChapterRevisionThumbnailSide.CANDIDATE, dataClass.hasCandidatePreview),
        pageUrlOrNull(dataClass, ChapterRevisionThumbnailSide.BASELINE, dataClass.baselinePageIndex.takeIf { baselineAvailable }),
        pageUrlOrNull(dataClass, ChapterRevisionThumbnailSide.CANDIDATE, dataClass.candidatePageIndex),
    )
}

/**
 * The review address of one side's thumbnail, or null when that side has no preview.
 *
 * The address is built from this row's identity, never from anything stored: it carries no location, no
 * candidate key and no digest, so it discloses nothing about the server's filesystem beyond the fact that
 * this row has a preview.
 */
private fun thumbnailUrlOrNull(
    dataClass: ChapterRevisionComparisonPageDataClass,
    side: ChapterRevisionThumbnailSide,
    hasPreview: Boolean,
): String? =
    if (hasPreview) {
        ChapterRevisionComparisonMediaRoutes.thumbnailUrl(dataClass.revisionId, dataClass.ordinal, side)
    } else {
        null
    }

/** The review address of one side's full page, or null when the row has no page on that side. */
private fun pageUrlOrNull(
    dataClass: ChapterRevisionComparisonPageDataClass,
    side: ChapterRevisionThumbnailSide,
    pageIndex: Int?,
): String? =
    if (pageIndex != null) {
        ChapterRevisionComparisonMediaRoutes.pageUrl(dataClass.revisionId, dataClass.ordinal, side)
    } else {
        null
    }

data class ChapterRevisionComparisonPageNodeList(
    override val nodes: List<ChapterRevisionComparisonPageType>,
    override val edges: List<ChapterRevisionComparisonPageEdge>,
    override val pageInfo: PageInfo,
    override val totalCount: Int,
) : NodeList() {
    data class ChapterRevisionComparisonPageEdge(
        override val cursor: Cursor,
        override val node: ChapterRevisionComparisonPageType,
    ) : Edge()
}

/** How much visual-analysis work is outstanding, and how the finished ones turned out. */
data class ChapterRevisionVisualAnalysisStatus(
    val queued: Int,
    val analyzing: Int,
    val complete: Int,
    val completeWithLimitations: Int,
    val failed: Int,
    val notRequired: Int,
)
