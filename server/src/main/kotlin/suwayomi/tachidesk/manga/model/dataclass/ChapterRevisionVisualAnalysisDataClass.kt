package suwayomi.tachidesk.manga.model.dataclass

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Progress of the visual page comparison of a revision against its baseline.
 *
 * This is an independent dimension on purpose. A revision can be fully acquired, archived and even
 * published while its visual analysis is still queued, and a visual analysis that failed never
 * invalidates the acquisition: the bytes were downloaded and validated either way, and the only
 * thing that is unknown is *how* they differ from what is already archived.
 *
 * [NOT_REQUIRED] is the state of a revision that has nothing to compare against - either it was the
 * first revision of its chapter, or its whole-chapter digest already matched - and of every revision
 * that existed before this dimension did. It is deliberately the column default, so an upgraded
 * database never wakes up believing it owes thousands of comparisons.
 */
enum class ChapterVisualAnalysisState {
    /** there is no baseline, or the content was already proven byte-identical: nothing to analyse */
    NOT_REQUIRED,

    /** the content changed against a baseline, so an analysis is owed */
    QUEUED,

    /** claimed by the analysis worker */
    ANALYZING,

    /** analysed: the summary describes the whole alignment */
    COMPLETE,

    /** analysed, but part of it could not be done (undecodable page, alignment budget exceeded) */
    COMPLETE_WITH_LIMITATIONS,

    /** the analysis could not be produced; the revision proceeds to archive/review with this audit */
    FAILED,
    ;

    /**
     * True once the revision no longer owes an analysis.
     *
     * The archive worker gates on this, so a revision whose comparison is still outstanding can
     * never be archived: archiving it would turn the missing analysis into a permanent mistake.
     */
    val isTerminal: Boolean
        get() = this == NOT_REQUIRED || this == COMPLETE || this == COMPLETE_WITH_LIMITATIONS || this == FAILED
}

/** How one row of an alignment relates the baseline and the candidate page. */
enum class ChapterRevisionPageAlignmentState {
    /** both pages exist and carry the same whole-page digest */
    EXACT,

    /** both pages exist, differ byte-wise, but their perceptual fingerprints are within the threshold */
    VISUALLY_EQUIVALENT,

    /** both pages exist and are visually different */
    MODIFIED,

    /** the page only exists in the candidate */
    ADDED,

    /** the page only exists in the baseline */
    REMOVED,
}

/**
 * Why a visual page comparison could not be produced.
 *
 * A category rather than a message: the analysis runs unattended over thousands of chapters, and the
 * one thing every consumer needs from a failure is something stable it can react to. The message
 * below it is a fixed, human readable sentence and never contains a path, a URL or a source error.
 */
enum class ChapterVisualAnalysisFailure(
    val message: String,
) {
    /** the staged pages the comparison would read are gone */
    STAGING_MISSING("the staged pages of the candidate are gone"),

    /** the archived baseline the comparison would read is not on the archive mount */
    BASELINE_MISSING("the archived baseline pages are not available"),

    /** the staged candidate pages could not be enumerated */
    CANDIDATE_UNREADABLE("the staged pages of the candidate could not be read"),

    /** the archived baseline could not be enumerated */
    BASELINE_UNREADABLE("the archived baseline pages could not be read"),

    /** the comparison itself raised something the worker did not anticipate */
    UNEXPECTED("the comparison could not be performed"),
}

/**
 * Durable summary of one visual comparison.
 *
 * The counts describe the whole alignment, not a sample, so a reader can tell a re-encoded chapter
 * apart from one with a single new page without paging through every row.
 */
data class ChapterRevisionComparisonDataClass(
    val id: Int,
    /** the candidate this comparison describes */
    val revisionId: Int,
    /** the revision that was actually compared against, or null when it was removed afterwards */
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
)

/**
 * One row of the page alignment behind a comparison.
 *
 * [baselinePerceptualHash]/[candidatePerceptualHash] are 128 bit perceptual fingerprints and are
 * null for a page that could not be decoded - an undecodable page is a limitation, not a failure,
 * so the exact digests of the row stay usable.
 */
data class ChapterRevisionComparisonPageDataClass(
    val id: Int,
    val comparisonId: Int,
    val revisionId: Int,
    /** position of this row in the alignment, which is also its display order */
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
    /**
     * Internal staging locations of the two review previews of this row.
     *
     * Never exposed through the API: they name a path inside the server's local staging root, and the
     * GraphQL layer reports only whether each side has a preview. A MODIFIED row carries both so the
     * two versions can be put side by side; an ADDED row only has a candidate and a REMOVED row only a
     * baseline.
     */
    val baselineThumbnailRelativePath: String?,
    val baselineThumbnailSha256: String?,
    val baselineThumbnailSize: Long?,
    val candidateThumbnailRelativePath: String?,
    val candidateThumbnailSha256: String?,
    val candidateThumbnailSize: Long?,
) {
    val hasBaselinePreview: Boolean get() = baselineThumbnailRelativePath != null

    val hasCandidatePreview: Boolean get() = candidateThumbnailRelativePath != null
}
