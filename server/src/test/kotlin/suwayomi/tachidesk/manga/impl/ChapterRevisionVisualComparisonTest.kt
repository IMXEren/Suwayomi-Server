package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionPageAlignmentState
import java.awt.image.BufferedImage

/**
 * The alignment rules on their own.
 *
 * They are what decides whether a re-release is interesting, so they are tested without a database, a
 * source or an archive: a page is only ever described by its digests and dimensions here.
 */
class ChapterRevisionVisualComparisonTest {
    private val zero = "0".repeat(32)
    private val one = "1".repeat(32)
    private val two = "2".repeat(32)
    private val three = "3".repeat(32)

    private fun page(
        hash: String,
        perceptual: String? = hash,
        width: Int = 800,
        height: Int = 1200,
    ) = ChapterVisualPageFingerprint(
        exactHash = hash,
        perceptualHash = perceptual,
        width = width,
        height = height,
        size = 1024,
    )

    private fun states(alignment: ChapterVisualAlignment) = alignment.pairs.map { it.state }

    @Test
    fun `an inserted page becomes a single addition and never shifts the rest`() {
        val alignment =
            ChapterRevisionVisualComparison.align(
                baseline = listOf(page(zero), page(one)),
                candidate = listOf(page(zero), page(three), page(one)),
                threshold = 2,
            )

        // ordinal pairing would report the tail as rewritten; aligning by content reports one addition
        assertEquals(3, alignment.pairs.size)
        assertEquals(
            listOf(
                ChapterRevisionPageAlignmentState.EXACT,
                ChapterRevisionPageAlignmentState.ADDED,
                ChapterRevisionPageAlignmentState.EXACT,
            ),
            states(alignment),
        )
        assertNull(alignment.limitations)
        // the pairing is by content, not by position: the baseline keeps its own two indices while the
        // candidate carries the inserted page in the middle and so advances one further
        assertEquals(listOf(0, null, 1), alignment.pairs.map { it.baselineIndex })
        assertEquals(listOf(0, 1, 2), alignment.pairs.map { it.candidateIndex })
    }

    @Test
    fun `a deleted page becomes a single removal`() {
        val alignment =
            ChapterRevisionVisualComparison.align(
                baseline = listOf(page(zero), page(three), page(one)),
                candidate = listOf(page(zero), page(one)),
                threshold = 2,
            )

        assertEquals(
            listOf(
                ChapterRevisionPageAlignmentState.EXACT,
                ChapterRevisionPageAlignmentState.REMOVED,
                ChapterRevisionPageAlignmentState.EXACT,
            ),
            states(alignment),
        )
    }

    @Test
    fun `a rewritten page is modified rather than added and removed`() {
        val alignment =
            ChapterRevisionVisualComparison.align(
                baseline = listOf(page(zero), page("f".repeat(32))),
                candidate = listOf(page(zero), page(three)),
                threshold = 2,
            )

        assertEquals(
            listOf(ChapterRevisionPageAlignmentState.EXACT, ChapterRevisionPageAlignmentState.MODIFIED),
            states(alignment),
        )
    }

    @Test
    fun `reordered pages are reported as a move rather than two rewrites`() {
        val alignment =
            ChapterRevisionVisualComparison.align(
                baseline = listOf(page(zero), page(one)),
                candidate = listOf(page(one), page(zero)),
                threshold = 2,
            )

        val counts = states(alignment).groupingBy { it }.eachCount()
        // a swap cannot produce two non-crossing exact pairs: one of the two pages has to be read as a
        // removal and the other as an addition. That is the cheaper of the two honest readings a
        // sequence alignment can express, and it is never two substitutions
        assertEquals(1, counts[ChapterRevisionPageAlignmentState.EXACT])
        assertEquals(1, counts[ChapterRevisionPageAlignmentState.ADDED])
        assertEquals(1, counts[ChapterRevisionPageAlignmentState.REMOVED])
        assertNull(counts[ChapterRevisionPageAlignmentState.MODIFIED])
        assertEquals(3, alignment.pairs.size)
    }

    @Test
    fun `duplicated pages pair with the first available copy`() {
        val alignment =
            ChapterRevisionVisualComparison.align(
                baseline = listOf(page(zero), page(one), page(two)),
                candidate = listOf(page(zero), page(zero), page(one), page(two)),
                threshold = 2,
            )

        val counts = states(alignment).groupingBy { it }.eachCount()
        assertEquals(3, counts[ChapterRevisionPageAlignmentState.EXACT])
        assertEquals(1, counts[ChapterRevisionPageAlignmentState.ADDED])
    }

    @Test
    fun `an empty side is fully added or fully removed`() {
        val added =
            ChapterRevisionVisualComparison.align(emptyList(), listOf(page(zero), page(one)), threshold = 2)
        assertEquals(listOf(ChapterRevisionPageAlignmentState.ADDED, ChapterRevisionPageAlignmentState.ADDED), states(added))

        val removed =
            ChapterRevisionVisualComparison.align(listOf(page(zero), page(one)), emptyList(), threshold = 2)
        assertEquals(listOf(ChapterRevisionPageAlignmentState.REMOVED, ChapterRevisionPageAlignmentState.REMOVED), states(removed))

        val empty = ChapterRevisionVisualComparison.align(emptyList(), emptyList(), threshold = 2)
        assertTrue(empty.pairs.isEmpty())
    }

    @Test
    fun `the same input always produces the same alignment`() {
        val baseline = listOf(page(zero), page(one), page(two), page(one))
        val candidate = listOf(page(one), page(zero), page(two), page(two))

        val first = ChapterRevisionVisualComparison.align(baseline, candidate, threshold = 2)
        val second = ChapterRevisionVisualComparison.align(baseline, candidate, threshold = 2)

        assertEquals(first.pairs, second.pairs)
    }

    @Test
    fun `perceptual equivalence is only claimed within the threshold`() {
        // one bit apart
        val close = "0".repeat(31) + "1"
        assertEquals(
            ChapterRevisionPageAlignmentState.VISUALLY_EQUIVALENT,
            ChapterRevisionVisualComparison.classify(page(zero), page(close), threshold = 2),
        )
        // three bits apart, which is over the conservative default
        val far = "0".repeat(29) + "111"
        assertEquals(
            ChapterRevisionPageAlignmentState.MODIFIED,
            ChapterRevisionVisualComparison.classify(page(zero), page(far), threshold = 2),
        )
    }

    @Test
    fun `an incompatible shape is never visually equivalent`() {
        // the exact digests differ, so only the shape guard can keep these apart: the perceptual
        // fingerprints are identical and the distance is zero
        assertEquals(
            ChapterRevisionPageAlignmentState.MODIFIED,
            ChapterRevisionVisualComparison.classify(
                page(zero, perceptual = two, width = 800, height = 1200),
                page(one, perceptual = two, width = 1200, height = 800),
                threshold = 2,
            ),
        )
    }

    @Test
    fun `the shape guard is symmetric and compares proportions rather than resolution`() {
        val tall = page(zero, perceptual = two, width = 800, height = 1200)
        val wide = page(one, perceptual = two, width = 1200, height = 800)

        // the answer may never depend on which side is passed first
        assertFalse(ChapterRevisionVisualComparison.dimensionsCompatible(tall, wide))
        assertFalse(ChapterRevisionVisualComparison.dimensionsCompatible(wide, tall))
        assertTrue(ChapterRevisionVisualComparison.dimensionsCompatible(tall, tall))

        // the same page at four times the resolution, and at a quarter of it
        assertTrue(
            ChapterRevisionVisualComparison.dimensionsCompatible(
                tall,
                page(one, perceptual = two, width = 3200, height = 4800),
            ),
        )
        assertTrue(
            ChapterRevisionVisualComparison.dimensionsCompatible(
                page(one, perceptual = two, width = 3200, height = 4800),
                tall,
            ),
        )
        assertTrue(
            ChapterRevisionVisualComparison.dimensionsCompatible(
                tall,
                page(one, perceptual = two, width = 200, height = 300),
            ),
        )

        // a slight rounding difference stays compatible, a re-proportioned page does not
        assertTrue(
            ChapterRevisionVisualComparison.dimensionsCompatible(
                tall,
                page(one, perceptual = two, width = 810, height = 1200),
            ),
        )
        assertFalse(
            ChapterRevisionVisualComparison.dimensionsCompatible(
                tall,
                page(one, perceptual = two, width = 880, height = 1200),
            ),
        )
    }

    @Test
    fun `a page that was never read is not an exact match of anything`() {
        // no digest at all: a page over the analysis limit, or one met after the chapter's budget ran
        // out. A digest of a prefix is not a digest of the page, so it must never pair as exact
        val unread = ChapterVisualPageFingerprint(exactHash = null, perceptualHash = null, width = null, height = null, size = null)

        assertEquals(
            ChapterRevisionPageAlignmentState.MODIFIED,
            ChapterRevisionVisualComparison.classify(unread, unread, threshold = 2),
        )
        assertEquals(
            ChapterRevisionPageAlignmentState.MODIFIED,
            ChapterRevisionVisualComparison.classify(unread, page(zero), threshold = 2),
        )
    }

    @Test
    fun `a missing fingerprint is a distance of null rather than zero`() {
        assertNull(ChapterRevisionVisualComparison.hammingDistance(null, zero))
        assertNull(ChapterRevisionVisualComparison.hammingDistance(zero, null))
        assertNull(ChapterRevisionVisualComparison.hammingDistance(zero, "not-hex"))
        assertEquals(1, ChapterRevisionVisualComparison.hammingDistance(zero, "0".repeat(31) + "1"))
        assertEquals(0, ChapterRevisionVisualComparison.hammingDistance(zero, zero))
    }

    @Test
    fun `undecodable pages still align by their exact digest`() {
        val noFingerprint = page(one, perceptual = null)

        assertEquals(
            ChapterRevisionPageAlignmentState.EXACT,
            ChapterRevisionVisualComparison.classify(page(one, perceptual = null), noFingerprint, threshold = 2),
        )
    }

    @Test
    fun `a chapter too large for an exact alignment degrades with a recorded limitation`() {
        // just over the documented cell budget
        val size = 2_100
        val baseline = List(size) { page(indexHash(it)) }
        val candidate = List(size) { page(indexHash(it)) }

        val alignment = ChapterRevisionVisualComparison.align(baseline, candidate, threshold = 2)

        assertNotNull(alignment.limitations)
        assertTrue(alignment.limitations!!.contains(ChapterRevisionVisualComparison.MAX_ALIGNMENT_CELLS.toString()))
        // the bounded fallback still pairs every single page, so the comparison is degraded, not lost
        assertEquals(size, alignment.pairs.size)
        assertTrue(alignment.pairs.all { it.state == ChapterRevisionPageAlignmentState.EXACT })
        assertEquals(List(size) { it }, alignment.pairs.map { it.baselineIndex })
        assertEquals(List(size) { it }, alignment.pairs.map { it.candidateIndex })

        // the sentinel that ends the alignment is never consumed as an anchor, so nothing is skipped
        // and nothing is read past the end of either side
        assertEquals(alignment.pairs, ChapterRevisionVisualComparison.align(baseline, candidate, threshold = 2).pairs)
    }

    @Test
    fun `the bounded fallback has no digest to anchor on for a page that was never read`() {
        val size = 2_100
        val unread = ChapterVisualPageFingerprint(exactHash = null, perceptualHash = null, width = null, height = null, size = null)
        val baseline = List(size) { if (it == 5) unread else page(indexHash(it)) }
        val candidate = List(size) { if (it == 5) unread else page(indexHash(it)) }

        val alignment = ChapterRevisionVisualComparison.align(baseline, candidate, threshold = 2)

        assertEquals(size, alignment.pairs.size)
        // the unread row is paired by position inside the gap it falls into, and is honestly modified
        assertEquals(1, alignment.pairs.count { it.state == ChapterRevisionPageAlignmentState.MODIFIED })
        assertEquals(size - 1, alignment.pairs.count { it.state == ChapterRevisionPageAlignmentState.EXACT })
        assertNull(alignment.pairs[5].hammingDistance)
    }

    /**
     * A digest no other page in the list shares, so every page is a candidate anchor.
     */
    private fun indexHash(index: Int): String = index.toString(16).padStart(32, '0')

    @Test
    fun `a fingerprint is deterministic and 128 bits wide`() {
        val image = BufferedImage(9, 9, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 9) {
            for (y in 0 until 9) {
                image.setRGB(x, y, if (x > y) 0xFFFFFF else 0x000000)
            }
        }

        val first = ChapterRevisionVisualComparison.fingerprint(image)
        val second = ChapterRevisionVisualComparison.fingerprint(image)

        assertNotNull(first)
        assertEquals(ChapterRevisionVisualComparison.HASH_HEX_LENGTH, first!!.length)
        assertEquals(first, second)
        // the fingerprint of the same pixels cannot depend on how the image was decoded
        assertEquals(0, ChapterRevisionVisualComparison.hammingDistance(first, second))
    }
}
