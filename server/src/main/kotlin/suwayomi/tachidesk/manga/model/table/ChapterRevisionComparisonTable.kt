package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonPageDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionPageAlignmentState

/**
 * Durable summary of one visual page comparison.
 *
 * [baselineRevision] is a self reference with `SET NULL`: the comparison happened and remains a
 * historic fact even after the revision it was decided against is removed.
 */
object ChapterRevisionComparisonTable : IntIdTable() {
    /** the candidate this summary describes; unique, so re-analysing replaces instead of appending */
    val revision = reference("revision", ChapterRevisionTable, ReferenceOption.CASCADE)

    val baselineRevision = optReference("baseline_revision", ChapterRevisionTable, ReferenceOption.SET_NULL)

    val baselinePageCount = integer("baseline_page_count")
    val candidatePageCount = integer("candidate_page_count")

    val exactCount = integer("exact_count")
    val visuallyEquivalentCount = integer("visually_equivalent_count")
    val modifiedCount = integer("modified_count")
    val addedCount = integer("added_count")
    val removedCount = integer("removed_count")

    /** how many rows [ChapterRevisionComparisonPageTable] holds for this summary */
    val alignedCount = integer("aligned_count")

    /**
     * The threshold the summary was produced with.
     *
     * Persisted rather than read from the current setting, so raising or lowering the threshold later
     * can never silently reinterpret a comparison that was already recorded.
     */
    val hammingThreshold = integer("hamming_threshold")

    val algorithmVersion = varchar("algorithm_version", 64)
    val allPagesVisuallyEquivalent = bool("all_pages_visually_equivalent")
    val hasLimitations = bool("has_limitations")
    val limitations = varchar("limitations", 1024).nullable()

    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    init {
        index("chapter_revision_comparison_revision_idx", true, revision)
        index("chapter_revision_comparison_created_idx", false, createdAt, id)
    }
}

/**
 * One row of the page alignment behind a comparison.
 *
 * [revision] duplicates the owning summary's candidate on purpose: the review UI pages the alignment
 * of a candidate and filters it by state, which is one index scan here instead of a join.
 */
object ChapterRevisionComparisonPageTable : IntIdTable() {
    val comparison = reference("comparison", ChapterRevisionComparisonTable, ReferenceOption.CASCADE)
    val revision = reference("revision", ChapterRevisionTable, ReferenceOption.CASCADE)

    /** position of this row inside the alignment, which is also its display order */
    val ordinal = integer("ordinal")

    val baselinePageIndex = integer("baseline_page_index").nullable()
    val candidatePageIndex = integer("candidate_page_index").nullable()

    val state = varchar("state", 32).default(ChapterRevisionPageAlignmentState.MODIFIED.name)

    /** whole-page digests, so an EXACT row stays provable without the images */
    val baselineExactHash = varchar("baseline_exact_hash", 64).nullable()
    val candidateExactHash = varchar("candidate_exact_hash", 64).nullable()

    /** 128 bit perceptual fingerprints; null when that page could not be decoded */
    val baselinePerceptualHash = varchar("baseline_perceptual_hash", 64).nullable()
    val candidatePerceptualHash = varchar("candidate_perceptual_hash", 64).nullable()

    val hammingDistance = integer("hamming_distance").nullable()

    val baselineWidth = integer("baseline_width").nullable()
    val baselineHeight = integer("baseline_height").nullable()
    val candidateWidth = integer("candidate_width").nullable()
    val candidateHeight = integer("candidate_height").nullable()

    val baselineSize = long("baseline_size").nullable()
    val candidateSize = long("candidate_size").nullable()

    /**
     * Internal staging locations of the two review previews of this row.
     *
     * Never exposed through the API: they are an implementation detail of the local staging root, and
     * the point of recording them is only that a client can be told whether a side has a preview at
     * all. Both sides are separate because a MODIFIED row is reviewed by comparing the two versions of
     * the page.
     */
    val baselineThumbnailRelativePath = varchar("baseline_thumbnail_relative_path", 1024).nullable()
    val baselineThumbnailSha256 = varchar("baseline_thumbnail_sha256", 64).nullable()
    val baselineThumbnailSize = long("baseline_thumbnail_size").nullable()

    val candidateThumbnailRelativePath = varchar("candidate_thumbnail_relative_path", 1024).nullable()
    val candidateThumbnailSha256 = varchar("candidate_thumbnail_sha256", 64).nullable()
    val candidateThumbnailSize = long("candidate_thumbnail_size").nullable()

    val createdAt = long("created_at")

    init {
        index("chapter_revision_comparison_page_order_idx", true, comparison, ordinal)
        index("chapter_revision_comparison_page_revision_idx", false, revision, state, ordinal)
    }
}

fun ChapterRevisionComparisonTable.toDataClass(row: ResultRow) =
    ChapterRevisionComparisonDataClass(
        id = row[id].value,
        revisionId = row[revision].value,
        baselineRevisionId = row[baselineRevision]?.value,
        baselinePageCount = row[baselinePageCount],
        candidatePageCount = row[candidatePageCount],
        exactCount = row[exactCount],
        visuallyEquivalentCount = row[visuallyEquivalentCount],
        modifiedCount = row[modifiedCount],
        addedCount = row[addedCount],
        removedCount = row[removedCount],
        alignedCount = row[alignedCount],
        hammingThreshold = row[hammingThreshold],
        algorithmVersion = row[algorithmVersion],
        allPagesVisuallyEquivalent = row[allPagesVisuallyEquivalent],
        hasLimitations = row[hasLimitations],
        limitations = row[limitations],
        createdAt = row[createdAt],
        updatedAt = row[updatedAt],
    )

fun ChapterRevisionComparisonPageTable.toDataClass(row: ResultRow) =
    ChapterRevisionComparisonPageDataClass(
        id = row[id].value,
        comparisonId = row[comparison].value,
        revisionId = row[revision].value,
        ordinal = row[ordinal],
        baselinePageIndex = row[baselinePageIndex],
        candidatePageIndex = row[candidatePageIndex],
        state = ChapterRevisionPageAlignmentState.valueOf(row[state]),
        baselineExactHash = row[baselineExactHash],
        candidateExactHash = row[candidateExactHash],
        baselinePerceptualHash = row[baselinePerceptualHash],
        candidatePerceptualHash = row[candidatePerceptualHash],
        hammingDistance = row[hammingDistance],
        baselineWidth = row[baselineWidth],
        baselineHeight = row[baselineHeight],
        candidateWidth = row[candidateWidth],
        candidateHeight = row[candidateHeight],
        baselineSize = row[baselineSize],
        candidateSize = row[candidateSize],
        baselineThumbnailRelativePath = row[baselineThumbnailRelativePath],
        baselineThumbnailSha256 = row[baselineThumbnailSha256],
        baselineThumbnailSize = row[baselineThumbnailSize],
        candidateThumbnailRelativePath = row[candidateThumbnailRelativePath],
        candidateThumbnailSha256 = row[candidateThumbnailSha256],
        candidateThumbnailSize = row[candidateThumbnailSize],
    )
