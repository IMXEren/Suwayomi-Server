package suwayomi.tachidesk.graphql

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.graphql.queries.ChapterRevisionQuery
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionPageAlignmentState
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisState
import suwayomi.tachidesk.manga.model.table.ChapterRevisionComparisonPageTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionComparisonTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables

/**
 * The read surface a review UI pages through.
 *
 * The alignment is the one part of the archive that is unbounded in size - a chapter can have
 * thousands of pages - so the properties that matter here are the ones a pager depends on: a stable
 * cursor, a total that describes the whole alignment rather than the page that was returned, and the
 * absence of any server-side location in what is exposed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterRevisionVisualAnalysisQueryTest : ApplicationTest() {
    private val query = ChapterRevisionQuery()

    private val chapterKey = "a".repeat(64)
    private val candidateKey = "b".repeat(64)

    @AfterEach
    fun cleanup() {
        clearTables(ChapterRevisionComparisonPageTable, ChapterRevisionComparisonTable, ChapterRevisionTable)
    }

    private fun revision(
        candidateKey: String,
        active: Boolean = false,
        visualState: ChapterVisualAnalysisState = ChapterVisualAnalysisState.NOT_REQUIRED,
        activeCbzPath: String? = null,
    ): Int =
        transaction {
            ChapterRevisionTable.insert {
                it[ChapterRevisionTable.candidateKey] = candidateKey
                it[ChapterRevisionTable.chapterKey] = this@ChapterRevisionVisualAnalysisQueryTest.chapterKey
                it[sourceChapterUrl] = "https://example.invalid/$candidateKey"
                it[name] = "chapter $candidateKey"
                it[discoveredAt] = 1
                it[updatedAt] = 1
                it[ChapterRevisionTable.acquisitionState] = ChapterAcquisitionState.COMPLETE.name
                it[ChapterRevisionTable.archiveState] = ChapterArchiveState.NOT_COMMITTED.name
                it[contentHash] = candidateKey
                it[pageCount] = 3
                it[ChapterRevisionTable.comparisonState] = ChapterRevisionComparisonState.CONTENT_CHANGED.name
                it[ChapterRevisionTable.visualAnalysisState] = visualState.name
                it[visualAnalysisAttempts] = 0
                it[ChapterRevisionTable.activeCbzPath] = activeCbzPath
                if (active) {
                    it[activeChapterKey] = this@ChapterRevisionVisualAnalysisQueryTest.chapterKey
                    it[disposition] = ChapterRevisionDisposition.ACCEPTED.name
                }
            } get ChapterRevisionTable.id
        }.value

    /**
     * Three aligned rows, of which one MODIFIED row carries a preview of each side.
     *
     * The stored preview location lives in the staging root, so it exists to prove what the API does
     * *not* say about it.
     */
    private fun seedComparison(baselineId: Int?): Int {
        val revisionId = revision(candidateKey, visualState = ChapterVisualAnalysisState.COMPLETE)

        val comparisonId =
            transaction {
                ChapterRevisionComparisonTable.insert {
                    it[revision] = revisionId
                    it[baselineRevision] = baselineId
                    it[baselinePageCount] = 3
                    it[candidatePageCount] = 3
                    it[exactCount] = 1
                    it[visuallyEquivalentCount] = 0
                    it[modifiedCount] = 1
                    it[addedCount] = 0
                    it[removedCount] = 0
                    it[alignedCount] = 3
                    it[hammingThreshold] = 2
                    it[algorithmVersion] = "dhash128-v1"
                    it[allPagesVisuallyEquivalent] = false
                    it[hasLimitations] = false
                    it[limitations] = null
                    it[createdAt] = 10
                    it[updatedAt] = 10
                } get ChapterRevisionComparisonTable.id
            }.value

        transaction {
            ChapterRevisionComparisonPageTable.insert {
                it[comparison] = comparisonId
                it[revision] = revisionId
                it[ordinal] = 0
                it[baselinePageIndex] = 0
                it[candidatePageIndex] = 0
                it[state] = ChapterRevisionPageAlignmentState.EXACT.name
                it[createdAt] = 10
            }

            ChapterRevisionComparisonPageTable.insert {
                it[comparison] = comparisonId
                it[revision] = revisionId
                it[ordinal] = 1
                it[baselinePageIndex] = 1
                it[candidatePageIndex] = 1
                it[state] = ChapterRevisionPageAlignmentState.MODIFIED.name
                it[baselineThumbnailRelativePath] = "revision-comparisons/x/1-1/000001-baseline.jpg"
                it[baselineThumbnailSha256] = "aaaa"
                it[baselineThumbnailSize] = 10
                it[candidateThumbnailRelativePath] = "revision-comparisons/x/1-1/000001-candidate.jpg"
                it[candidateThumbnailSha256] = "bbbb"
                it[candidateThumbnailSize] = 20
                it[createdAt] = 10
            }

            ChapterRevisionComparisonPageTable.insert {
                it[comparison] = comparisonId
                it[revision] = revisionId
                it[ordinal] = 2
                it[baselinePageIndex] = 2
                it[candidatePageIndex] = null
                it[state] = ChapterRevisionPageAlignmentState.REMOVED.name
                it[createdAt] = 10
            }
        }

        return revisionId
    }

    @Test
    fun `a comparison is read back with its summary counts`() {
        val baselineId = revision("c".repeat(64), active = true, activeCbzPath = "library/manga-1/c.cbz")
        val revisionId = seedComparison(baselineId)

        val comparison = query.chapterRevisionComparison(revisionId)
        assertNotNull(comparison)
        assertEquals(baselineId, comparison!!.baselineRevisionId)
        assertEquals(3, comparison.alignedCount)
        assertEquals(1, comparison.exactCount)
        assertEquals(1, comparison.modifiedCount)
        assertEquals("dhash128-v1", comparison.algorithmVersion)
    }

    @Test
    fun `a revision without a comparison reports none`() {
        val revisionId = revision(candidateKey, visualState = ChapterVisualAnalysisState.QUEUED)

        assertNull(query.chapterRevisionComparison(revisionId))
        assertTrue(query.chapterRevisionComparisonPages(revisionId, first = 10).nodes.isEmpty())
    }

    @Test
    fun `the alignment pages with a stable cursor and a total that describes the whole alignment`() {
        val revisionId = seedComparison(null)

        val firstPage = query.chapterRevisionComparisonPages(revisionId, first = 2)
        assertEquals(listOf(0, 1), firstPage.nodes.map { it.ordinal })
        assertTrue(firstPage.pageInfo.hasNextPage)
        // the total is the whole alignment, not the page that was returned: a client showing "row 2 of
        // 3" must not see it fall to 2 as soon as it pages
        assertEquals(3, firstPage.totalCount)

        val endCursor = firstPage.pageInfo.endCursor
        assertNotNull(endCursor)
        assertEquals("1", endCursor!!.value)

        val secondPage = query.chapterRevisionComparisonPages(revisionId, after = endCursor, first = 2)
        assertEquals(listOf(2), secondPage.nodes.map { it.ordinal })
        assertFalse(secondPage.pageInfo.hasNextPage)
        assertTrue(secondPage.pageInfo.hasPreviousPage)
        // ... and it stays the whole alignment on the second page too
        assertEquals(3, secondPage.totalCount)

        // paging past the end is empty rather than an error, so a client that polls keeps working
        val beyond = query.chapterRevisionComparisonPages(revisionId, after = secondPage.pageInfo.endCursor, first = 2)
        assertTrue(beyond.nodes.isEmpty())
        assertEquals(3, beyond.totalCount)
    }

    @Test
    fun `a malformed cursor is rejected instead of silently ignored`() {
        val revisionId = seedComparison(null)

        // the cursor is an opaque scalar, so a hand-written value reaches the ordinal comparison; the
        // shared pagination machinery of every other connection rejects it the same way
        assertThrows(Exception::class.java) {
            query.chapterRevisionComparisonPages(revisionId, after = Cursor("not-an-ordinal"), first = 2)
        }
    }

    @Test
    fun `a row only reports whether a preview exists, never where it lives`() {
        // a baseline that is still there, so a baseline page is genuinely addressable
        val revisionId = seedComparison(revision("c".repeat(64)))

        val rows = query.chapterRevisionComparisonPages(revisionId, first = 10).nodes
        assertEquals(3, rows.size)

        val exact = rows[0]
        val modified = rows[1]
        val removed = rows[2]

        assertEquals(ChapterRevisionPageAlignmentState.EXACT, exact.state)
        assertFalse(exact.baselinePreviewAvailable, "a row that needs no review carries no preview")
        assertFalse(exact.candidatePreviewAvailable, "a row that needs no review carries no preview")
        assertEquals(0, exact.baselinePageIndex)

        // the rewritten page is reviewed by putting both of its versions side by side
        assertEquals(ChapterRevisionPageAlignmentState.MODIFIED, modified.state)
        assertTrue(modified.baselinePreviewAvailable)
        assertTrue(modified.candidatePreviewAvailable)
        assertEquals(1, modified.baselinePageIndex)
        assertEquals(1, modified.candidatePageIndex)

        assertEquals(ChapterRevisionPageAlignmentState.REMOVED, removed.state)
        assertNull(removed.candidatePageIndex, "a removed page does not exist in the candidate")

        // a side without a preview has no address at all, so a client never probes for one
        assertNull(exact.baselineThumbnailUrl)
        assertNull(exact.candidateThumbnailUrl)

        // a row that does have previews is addressed only by this row's identity: the address carries no
        // location, no candidate key and no digest, and it is an API-absolute path on this server - it
        // begins with /api/v1 - never a full URL, which only the client could form
        assertEquals(
            "/api/v1/archive/revisions/$revisionId/comparison/1/baseline/thumbnail",
            modified.baselineThumbnailUrl,
        )
        assertEquals(
            "/api/v1/archive/revisions/$revisionId/comparison/1/candidate/thumbnail",
            modified.candidateThumbnailUrl,
        )
        assertEquals("/api/v1/archive/revisions/$revisionId/comparison/1/baseline/page", modified.baselinePageUrl)
        assertEquals("/api/v1/archive/revisions/$revisionId/comparison/1/candidate/page", modified.candidatePageUrl)
        assertFalse(modified.baselineThumbnailUrl!!.contains("revision-comparisons"))
        assertFalse(modified.baselineThumbnailUrl!!.contains(candidateKey))

        // the removed page has no candidate side, so only the baseline can be opened
        assertEquals("/api/v1/archive/revisions/$revisionId/comparison/2/baseline/page", removed.baselinePageUrl)
        assertNull(removed.candidatePageUrl)
        assertNull(removed.candidateThumbnailUrl)

        // a page that needs no review is still addressable, because both of its sides exist
        assertEquals("/api/v1/archive/revisions/$revisionId/comparison/0/baseline/page", exact.baselinePageUrl)
        assertEquals("/api/v1/archive/revisions/$revisionId/comparison/0/candidate/page", exact.candidatePageUrl)
    }

    @Test
    fun `a comparison whose baseline is gone keeps its audit but offers no baseline page address`() {
        val revisionId = seedComparison(null)

        val rows = query.chapterRevisionComparisonPages(revisionId, first = 10).nodes

        // the audit outlives the content it describes: the rows are still there, with their indices
        assertEquals(3, rows.size)
        assertEquals(1, rows[1].baselinePageIndex)
        assertEquals(ChapterRevisionPageAlignmentState.MODIFIED, rows[1].state)

        // nothing can serve a baseline page once that revision is gone, so no address is offered for one: a
        // client is never handed a link that is already known to be dead
        rows.forEach { assertNull(it.baselinePageUrl) }

        // the baseline thumbnail is stored with the comparison rather than with the baseline revision, so it
        // stays addressable, and the candidate side is untouched
        assertEquals(
            "/api/v1/archive/revisions/$revisionId/comparison/1/baseline/thumbnail",
            rows[1].baselineThumbnailUrl,
        )
        assertEquals("/api/v1/archive/revisions/$revisionId/comparison/1/candidate/page", rows[1].candidatePageUrl)
    }

    @Test
    fun `the status reports the backlog per state`() {
        revision("d".repeat(64), visualState = ChapterVisualAnalysisState.QUEUED)
        revision("e".repeat(64), visualState = ChapterVisualAnalysisState.QUEUED)
        revision("f".repeat(64), visualState = ChapterVisualAnalysisState.ANALYZING)
        revision("1".repeat(64), visualState = ChapterVisualAnalysisState.COMPLETE)
        revision("2".repeat(64), visualState = ChapterVisualAnalysisState.COMPLETE_WITH_LIMITATIONS)
        revision("3".repeat(64), visualState = ChapterVisualAnalysisState.FAILED)
        revision("4".repeat(64), visualState = ChapterVisualAnalysisState.NOT_REQUIRED)

        val status = query.chapterRevisionVisualAnalysisStatus()

        assertEquals(2, status.queued)
        assertEquals(1, status.analyzing)
        assertEquals(1, status.complete)
        assertEquals(1, status.completeWithLimitations)
        assertEquals(1, status.failed)
        assertEquals(1, status.notRequired)
    }
}
