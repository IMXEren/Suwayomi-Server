package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionPageAlignmentState
import java.awt.image.BufferedImage
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.max

/**
 * What is known about one page of a chapter before it is aligned.
 *
 * [exactHash] is the whole-page digest. It is null when the page was never read to its end - a page
 * larger than the analysis limit, or a chapter whose cumulative bytes exceeded it - because a digest
 * of a prefix is not a digest of the page. A null digest is never equal to another null digest: two
 * unreadable pages are "not comparable", which is the honest answer and never a match.
 * [perceptualHash], [width] and [height] come from decoding the image, so they are null for a format
 * the runtime cannot decode - an unsupported page is a limitation of the comparison, never a reason
 * to lose the revision.
 */
data class ChapterVisualPageFingerprint(
    val exactHash: String?,
    val perceptualHash: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
)

/** One row of an alignment: what happened to one baseline page and one candidate page. */
data class ChapterVisualAlignmentPair(
    val ordinal: Int,
    val baselineIndex: Int?,
    val candidateIndex: Int?,
    val state: ChapterRevisionPageAlignmentState,
    val hammingDistance: Int?,
)

/** A complete alignment together with the reason a bounded algorithm had to be used. */
data class ChapterVisualAlignment(
    val pairs: List<ChapterVisualAlignmentPair>,
    val limitations: String?,
) {
    val hasLimitations: Boolean get() = limitations != null
}

/**
 * Deterministic perceptual fingerprinting and sequence alignment of two chapters.
 *
 * Pure and free of any storage or database access, so the alignment rules - which are the part that
 * decides whether a re-release is interesting - are testable on their own.
 */
object ChapterRevisionVisualComparison {
    /**
     * Version of the fingerprint and alignment rules.
     *
     * Persisted with every comparison so a later change to the grid, the cost model or the threshold
     * comparison can never silently reinterpret a summary that was already recorded.
     */
    const val ALGORITHM_VERSION = "dhash128-v1"

    /** A 128 bit fingerprint is exactly 32 hexadecimal characters. */
    const val HASH_HEX_LENGTH = 32

    /** side of the luminance grid: 9 columns give 8 horizontal comparisons per row and vice versa */
    const val HASH_GRID = 9

    /**
     * Upper bound on the dynamic-programming table.
     *
     * A chapter of 2,000 pages against another of 2,000 pages is four million cells, which is the
     * point where an int matrix stops being obviously cheap. Beyond it the alignment falls back to a
     * bounded, deterministic algorithm instead of risking an out-of-memory that would lose the
     * revision's analysis entirely.
     */
    const val MAX_ALIGNMENT_CELLS = 4_000_000

    /** cost of deleting or inserting one page */
    private const val GAP_COST = 3

    /** cost of pairing two pages that exist on both sides but look different */
    private const val SUBSTITUTION_COST = 4

    /** cost of pairing two pages that exist on both sides and look the same */
    private const val VISUALLY_EQUIVALENT_COST = 1

    /**
     * How far two aspect ratios may differ before a fingerprint match stops being credible.
     *
     * A perceptual hash ignores proportions, so a wide page and a tall page can collide. Requiring a
     * compatible shape before calling two pages equivalent is what keeps a collision from
     * auto-dismissing a genuinely different page.
     */
    private const val ASPECT_RATIO_TOLERANCE = 0.05

    /** Bits of a fingerprint that describe horizontal gradients. */
    private const val HORIZONTAL_BITS = 64

    /**
     * 128 bit perceptual fingerprint of a decoded page.
     *
     * The image is expected to be small - it is decoded with subsampling - and is reduced to a
     * [HASH_GRID] square luminance grid by area averaging before the gradients are read, so the
     * fingerprint is stable against re-encoding and resizing without ever needing the full page in
     * memory at full resolution.
     */
    fun fingerprint(image: BufferedImage): String? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) {
            return null
        }

        val luminance = luminanceGrid(image)
        var bits = StringBuilder(HASH_HEX_LENGTH * 4)

        // horizontal gradient: is the pixel to the right brighter than this one
        for (row in 0 until HASH_GRID - 1) {
            for (column in 0 until HASH_GRID - 1) {
                bits.append(if (luminance[row * HASH_GRID + column + 1] > luminance[row * HASH_GRID + column]) '1' else '0')
            }
        }

        // vertical gradient: is the pixel below brighter than this one
        for (column in 0 until HASH_GRID - 1) {
            for (row in 0 until HASH_GRID - 1) {
                bits.append(if (luminance[(row + 1) * HASH_GRID + column] > luminance[row * HASH_GRID + column]) '1' else '0')
            }
        }

        val binary = bits.toString()
        check(binary.length == HORIZONTAL_BITS * 2) { "unexpected fingerprint width: ${binary.length}" }

        return BigInteger(binary, 2).toString(16).padStart(HASH_HEX_LENGTH, '0')
    }

    /** Luminance of every cell of the [HASH_GRID] square, row major, area averaged. */
    private fun luminanceGrid(image: BufferedImage): IntArray {
        val grid = IntArray(HASH_GRID * HASH_GRID)
        val width = image.width
        val height = image.height

        for (row in 0 until HASH_GRID) {
            val top = row * height / HASH_GRID
            val bottom = max(top + 1, (row + 1) * height / HASH_GRID)
            for (column in 0 until HASH_GRID) {
                val left = column * width / HASH_GRID
                val right = max(left + 1, (column + 1) * width / HASH_GRID)

                var sum = 0L
                var count = 0
                for (y in top until bottom.coerceAtMost(height)) {
                    for (x in left until right.coerceAtMost(width)) {
                        val rgb = image.getRGB(x, y)
                        val red = (rgb shr 16) and 0xFF
                        val green = (rgb shr 8) and 0xFF
                        val blue = rgb and 0xFF
                        // integer luma, the same weights for every platform
                        sum += (red * 299 + green * 587 + blue * 114) / 1000
                        count++
                    }
                }
                grid[row * HASH_GRID + column] = if (count == 0) 0 else (sum / count).toInt()
            }
        }

        return grid
    }

    /**
     * Number of differing bits between two fingerprints, or null when they cannot be compared.
     *
     * A missing fingerprint (an undecodable page) and a fingerprint of an unexpected width are both
     * "not comparable" rather than "different", so the caller can report a limitation instead of
     * inventing a distance.
     */
    fun hammingDistance(
        first: String?,
        second: String?,
    ): Int? {
        if (first == null || second == null) {
            return null
        }
        if (first.length != second.length || first.isEmpty()) {
            return null
        }
        return try {
            BigInteger(first, 16).xor(BigInteger(second, 16)).bitCount()
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** True when two pages have a compatible shape, so a fingerprint match is credible. */
    fun dimensionsCompatible(
        first: ChapterVisualPageFingerprint,
        second: ChapterVisualPageFingerprint,
    ): Boolean {
        val firstWidth = first.width ?: return false
        val firstHeight = first.height ?: return false
        val secondWidth = second.width ?: return false
        val secondHeight = second.height ?: return false
        if (firstWidth <= 0 || firstHeight <= 0 || secondWidth <= 0 || secondHeight <= 0) {
            return false
        }

        // normalized cross product: symmetric in its arguments and independent of the absolute
        // resolution, so a resized page is compatible while a rotated or re-proportioned one is not
        val firstProduct = firstWidth.toLong() * secondHeight
        val secondProduct = secondWidth.toLong() * firstHeight
        val larger = maxOf(firstProduct, secondProduct)
        if (larger <= 0L) {
            return false
        }

        return abs(firstProduct - secondProduct).toDouble() <= ASPECT_RATIO_TOLERANCE * larger.toDouble()
    }

    /** How one candidate page relates to one baseline page. */
    fun classify(
        baseline: ChapterVisualPageFingerprint,
        candidate: ChapterVisualPageFingerprint,
        threshold: Int,
    ): ChapterRevisionPageAlignmentState {
        // two pages that were never read to their end are not known to be identical, so a null digest
        // can never be an exact match - not even against another null digest
        if (baseline.exactHash != null && baseline.exactHash == candidate.exactHash) {
            return ChapterRevisionPageAlignmentState.EXACT
        }

        val distance = hammingDistance(baseline.perceptualHash, candidate.perceptualHash)
        if (distance != null && distance <= threshold && dimensionsCompatible(baseline, candidate)) {
            return ChapterRevisionPageAlignmentState.VISUALLY_EQUIVALENT
        }

        return ChapterRevisionPageAlignmentState.MODIFIED
    }

    /**
     * Aligns two chapters by content, never by ordinal.
     *
     * Ordinal pairing is what makes a naive comparison useless: inserting a single credit page at the
     * front shifts every following page, so a naive comparison would report the whole chapter as
     * modified. Aligning the sequences instead means an insertion costs one [ADDED] row and a
     * deletion one [REMOVED] row, while genuinely rewritten pages become [MODIFIED].
     *
     * Within the cell budget this is an exact Needleman-Wunsch alignment; above it a bounded
     * deterministic anchor alignment is used and the reason is reported in [ChapterVisualAlignment.limitations].
     */
    fun align(
        baseline: List<ChapterVisualPageFingerprint>,
        candidate: List<ChapterVisualPageFingerprint>,
        threshold: Int,
    ): ChapterVisualAlignment {
        val rows = baseline.size + 1
        val columns = candidate.size + 1

        if (rows.toLong() * columns.toLong() > MAX_ALIGNMENT_CELLS) {
            return anchoredAlignment(
                baseline = baseline,
                candidate = candidate,
                threshold = threshold,
                limitation =
                    "chapters are too large for an exact alignment (${baseline.size} against " +
                        "${candidate.size} pages, budget $MAX_ALIGNMENT_CELLS cells): " +
                        "a bounded anchor alignment was used instead",
            )
        }

        return exactAlignment(baseline, candidate, threshold)
    }

    private fun exactAlignment(
        baseline: List<ChapterVisualPageFingerprint>,
        candidate: List<ChapterVisualPageFingerprint>,
        threshold: Int,
    ): ChapterVisualAlignment {
        val rows = baseline.size + 1
        val columns = candidate.size + 1
        val costs = IntArray(rows * columns)

        for (row in 1 until rows) {
            costs[row * columns] = row * GAP_COST
        }
        for (column in 1 until columns) {
            costs[column] = column * GAP_COST
        }

        for (row in 1 until rows) {
            val baselinePage = baseline[row - 1]
            for (column in 1 until columns) {
                val candidatePage = candidate[column - 1]
                val pairCost =
                    when (classify(baselinePage, candidatePage, threshold)) {
                        ChapterRevisionPageAlignmentState.EXACT -> 0
                        ChapterRevisionPageAlignmentState.VISUALLY_EQUIVALENT -> VISUALLY_EQUIVALENT_COST
                        else -> SUBSTITUTION_COST
                    }

                val diagonal = costs[(row - 1) * columns + column - 1] + pairCost
                val removal = costs[(row - 1) * columns + column] + GAP_COST
                val addition = costs[row * columns + column - 1] + GAP_COST

                costs[row * columns + column] = minOf(diagonal, removal, addition)
            }
        }

        // traceback into reverse order, then flip; the tie-break order is fixed (diagonal first, then
        // removal, then addition) so the same input always produces the same alignment
        val reversed = ArrayList<ChapterVisualAlignmentPair>(baseline.size + candidate.size)
        var row = baseline.size
        var column = candidate.size
        while (row > 0 || column > 0) {
            val baselinePage = if (row > 0) baseline[row - 1] else null
            val candidatePage = if (column > 0) candidate[column - 1] else null

            val diagonalCost =
                if (row > 0 && column > 0) {
                    val pairCost =
                        when (classify(baselinePage!!, candidatePage!!, threshold)) {
                            ChapterRevisionPageAlignmentState.EXACT -> 0
                            ChapterRevisionPageAlignmentState.VISUALLY_EQUIVALENT -> VISUALLY_EQUIVALENT_COST
                            else -> SUBSTITUTION_COST
                        }
                    costs[(row - 1) * columns + column - 1] + pairCost
                } else {
                    Int.MAX_VALUE
                }

            when {
                diagonalCost != Int.MAX_VALUE && costs[row * columns + column] == diagonalCost -> {
                    reversed +=
                        buildPair(
                            ordinal = 0,
                            baselineIndex = row - 1,
                            candidateIndex = column - 1,
                            baseline = baselinePage!!,
                            candidate = candidatePage!!,
                            threshold = threshold,
                        )
                    row--
                    column--
                }

                row > 0 && costs[row * columns + column] == costs[(row - 1) * columns + column] + GAP_COST -> {
                    reversed +=
                        buildPair(
                            ordinal = 0,
                            baselineIndex = row - 1,
                            candidateIndex = null,
                            baseline = baselinePage!!,
                            candidate = null,
                            threshold = threshold,
                        )
                    row--
                }

                else -> {
                    reversed +=
                        buildPair(
                            ordinal = 0,
                            baselineIndex = null,
                            candidateIndex = column - 1,
                            baseline = null,
                            candidate = candidatePage!!,
                            threshold = threshold,
                        )
                    column--
                }
            }
        }

        return ChapterVisualAlignment(
            pairs = reversed.reversed().mapIndexed { ordinal, pair -> pair.copy(ordinal = ordinal) },
            limitations = null,
        )
    }

    /**
     * Bounded fallback used only when the exact alignment would not fit the cell budget.
     *
     * It anchors on the whole-page digests that occur exactly once on both sides, pairs the pages
     * between consecutive anchors ordinally, and pairs the anchor pages with each other. That keeps the
     * two properties that matter here: the result is deterministic (there is no unordered iteration or
     * timing dependency), and the work is linear in the page count, so a pathological chapter produces
     * a degraded but honest alignment instead of an out-of-memory.
     */
    private fun anchoredAlignment(
        baseline: List<ChapterVisualPageFingerprint>,
        candidate: List<ChapterVisualPageFingerprint>,
        threshold: Int,
        limitation: String,
    ): ChapterVisualAlignment {
        // a page that was never read to its end has no digest to anchor on, and one that occurs more
        // than once cannot say where in the sequence it belongs
        val baselineOccurrences = baseline.mapNotNull { it.exactHash }.groupingBy { it }.eachCount()
        val candidateOccurrences = candidate.mapNotNull { it.exactHash }.groupingBy { it }.eachCount()

        val uniqueCandidatePositions = HashMap<String, Int>()
        candidate.forEachIndexed { index, page ->
            val hash = page.exactHash
            if (hash != null && candidateOccurrences[hash] == 1) {
                uniqueCandidatePositions[hash] = index
            }
        }

        val anchors = ArrayList<Pair<Int, Int>>()
        var cursor = -1
        baseline.forEachIndexed { index, page ->
            val hash = page.exactHash
            val position = if (hash != null && baselineOccurrences[hash] == 1) uniqueCandidatePositions[hash] else null
            if (position != null && position > cursor) {
                anchors += index to position
                cursor = position
            }
        }

        val pairs = ArrayList<ChapterVisualAlignmentPair>(max(baseline.size, candidate.size))
        var baselineStart = 0
        var candidateStart = 0

        // the anchor page itself is paired explicitly: treating it only as the end of the preceding gap
        // would skip every anchor, and the trailing "rest of the chapter" gap would overrun the sentinel
        anchors.forEach { (baselineAnchor, candidateAnchor) ->
            appendOrdinalGap(
                pairs = pairs,
                baseline = baseline,
                candidate = candidate,
                baselineStart = baselineStart,
                baselineEnd = baselineAnchor,
                candidateStart = candidateStart,
                candidateEnd = candidateAnchor,
                threshold = threshold,
            )
            pairs +=
                buildPair(
                    ordinal = pairs.size,
                    baselineIndex = baselineAnchor,
                    candidateIndex = candidateAnchor,
                    baseline = baseline[baselineAnchor],
                    candidate = candidate[candidateAnchor],
                    threshold = threshold,
                )

            baselineStart = baselineAnchor + 1
            candidateStart = candidateAnchor + 1
        }

        appendOrdinalGap(
            pairs = pairs,
            baseline = baseline,
            candidate = candidate,
            baselineStart = baselineStart,
            baselineEnd = baseline.size,
            candidateStart = candidateStart,
            candidateEnd = candidate.size,
            threshold = threshold,
        )

        return ChapterVisualAlignment(pairs, limitation)
    }

    /** Pairs the pages of one stretch between two anchors by position. */
    private fun appendOrdinalGap(
        pairs: MutableList<ChapterVisualAlignmentPair>,
        baseline: List<ChapterVisualPageFingerprint>,
        candidate: List<ChapterVisualPageFingerprint>,
        baselineStart: Int,
        baselineEnd: Int,
        candidateStart: Int,
        candidateEnd: Int,
        threshold: Int,
    ) {
        val baselineLength = baselineEnd - baselineStart
        val candidateLength = candidateEnd - candidateStart

        repeat(max(baselineLength, candidateLength)) { offset ->
            val baselinePage = if (offset < baselineLength) baseline[baselineStart + offset] else null
            val candidatePage = if (offset < candidateLength) candidate[candidateStart + offset] else null

            pairs +=
                buildPair(
                    ordinal = pairs.size,
                    baselineIndex = baselinePage?.let { baselineStart + offset },
                    candidateIndex = candidatePage?.let { candidateStart + offset },
                    baseline = baselinePage,
                    candidate = candidatePage,
                    threshold = threshold,
                )
        }
    }

    private fun buildPair(
        ordinal: Int,
        baselineIndex: Int?,
        candidateIndex: Int?,
        baseline: ChapterVisualPageFingerprint?,
        candidate: ChapterVisualPageFingerprint?,
        threshold: Int,
    ): ChapterVisualAlignmentPair =
        when {
            baseline != null && candidate != null -> {
                val state = classify(baseline, candidate, threshold)
                ChapterVisualAlignmentPair(
                    ordinal = ordinal,
                    baselineIndex = baselineIndex,
                    candidateIndex = candidateIndex,
                    state = state,
                    hammingDistance = hammingDistance(baseline.perceptualHash, candidate.perceptualHash),
                )
            }

            baseline != null -> {
                ChapterVisualAlignmentPair(
                    ordinal = ordinal,
                    baselineIndex = baselineIndex,
                    candidateIndex = null,
                    state = ChapterRevisionPageAlignmentState.REMOVED,
                    hammingDistance = null,
                )
            }

            else -> {
                ChapterVisualAlignmentPair(
                    ordinal = ordinal,
                    baselineIndex = null,
                    candidateIndex = candidateIndex,
                    state = ChapterRevisionPageAlignmentState.ADDED,
                    hammingDistance = null,
                )
            }
        }
}
