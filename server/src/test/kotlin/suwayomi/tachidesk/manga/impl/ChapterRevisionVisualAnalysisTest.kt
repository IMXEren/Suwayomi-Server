package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDiscoveryReason
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionMetadataField
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionPageAlignmentState
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisFailure
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisState
import suwayomi.tachidesk.manga.model.table.ChapterRevisionComparisonPageTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionComparisonTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

/**
 * The durable visual comparison: its backlog rules, its archive gate, its baseline fence and the
 * artifacts it produces.
 *
 * The archive is a real directory tree and the pages are real PNGs, because the part that matters -
 * reading an archived CBZ, aligning it against staged pages and rendering previews - cannot be
 * exercised with placeholder bytes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterRevisionVisualAnalysisTest : ApplicationTest() {
    private val stagingRoot: File = File("build/tmp/chapter-revision-visual-staging-${UUID.randomUUID()}")
    private val archiveRoot: File = File("build/tmp/chapter-revision-visual-archive-${UUID.randomUUID()}")

    private val chapterKey = "a".repeat(64)

    @AfterEach
    fun cleanup() {
        // the alignment tables are deleted before the revisions they reference, so the cleanup cannot
        // trip over its own foreign keys and leave every later test in this class looking at old rows
        clearTables(ChapterRevisionComparisonPageTable, ChapterRevisionComparisonTable, ChapterRevisionTable)
        stagingRoot.deleteRecursively()
        archiveRoot.deleteRecursively()
    }

    // ---------------------------------------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------------------------------------

    /**
     * A deterministic gradient. [variant] changes the picture, [colorModel] changes only how it is
     * encoded, which is what a lossless re-encode of the same page looks like.
     */
    private fun image(
        variant: Int,
        colorModel: Int = BufferedImage.TYPE_INT_RGB,
    ): BufferedImage {
        val width = 64
        val height = 96
        val image = BufferedImage(width, height, colorModel)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val value = (x * 3 + y * 5 + variant * 71) % 256
                val rgb =
                    if (variant % 2 == 0) {
                        (value shl 16) or ((255 - value) shl 8) or (value / 2)
                    } else {
                        ((255 - value) shl 16) or (value shl 8) or (value / 3)
                    }
                image.setRGB(x, y, rgb)
            }
        }
        return image
    }

    private fun writePng(
        directory: File,
        name: String,
        image: BufferedImage,
    ): File {
        directory.mkdirs()
        val file = File(directory, name)
        ImageIO.write(image, "png", file)
        return file
    }

    private fun stagedPages(
        candidateKey: String,
        pages: List<File>,
    ): String {
        val directory = ChapterRevisionStaging.directory(stagingRoot, candidateKey)
        directory.mkdirs()
        pages.forEachIndexed { index, source ->
            source.copyTo(File(directory, ChapterRevisionStaging.pageFileName(index) + ".png"), overwrite = true)
        }
        return ChapterRevisionStaging.relativeDirectory(candidateKey)
    }

    /**
     * The archived CBZ of a baseline, written exactly the way production writes one.
     *
     * The archive commit builds its CBZ from the validated staging directory, so every entry is named
     * by its page order (`00001.png`). Archiving the raw source files instead would produce an artifact
     * the writer never creates, and would hide a regression in that naming.
     */
    private fun archivedBaseline(
        candidateKey: String,
        pages: List<File>,
    ): String {
        val target = ChapterRevisionArchiveArtifacts.cbzFile(archiveRoot, candidateKey)
        target.parentFile.mkdirs()

        val orderedDirectory = File(stagingRoot, "baseline-pages-$candidateKey").also { it.mkdirs() }
        val ordered =
            pages.mapIndexed { index, source ->
                File(orderedDirectory, ChapterRevisionStaging.pageFileName(index) + ".png").also { target ->
                    source.copyTo(target, overwrite = true)
                }
            }

        ChapterRevisionCbz.write(ordered, target)
        return ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey)
    }

    private fun revision(
        candidateKey: String,
        contentHash: String?,
        active: Boolean = false,
        acquisitionState: ChapterAcquisitionState = ChapterAcquisitionState.COMPLETE,
        comparisonState: ChapterRevisionComparisonState = ChapterRevisionComparisonState.NOT_EVALUATED,
        visualState: ChapterVisualAnalysisState = ChapterVisualAnalysisState.NOT_REQUIRED,
        baselineRevisionId: Int? = null,
        candidatePath: String? = null,
        archiveCbzPath: String? = null,
        archiveState: ChapterArchiveState = ChapterArchiveState.NOT_COMMITTED,
    ): Int =
        transaction {
            ChapterRevisionTable.insert {
                it[ChapterRevisionTable.candidateKey] = candidateKey
                it[ChapterRevisionTable.chapterKey] = this@ChapterRevisionVisualAnalysisTest.chapterKey
                it[sourceChapterUrl] = "https://example.invalid/$candidateKey"
                it[name] = "chapter $candidateKey"
                it[discoveredAt] = 1
                it[updatedAt] = 1
                it[ChapterRevisionTable.acquisitionState] = acquisitionState.name
                it[ChapterRevisionTable.archiveState] = archiveState.name
                it[ChapterRevisionTable.contentHash] = contentHash
                it[ChapterRevisionTable.pageCount] = 2
                it[ChapterRevisionTable.candidatePath] = candidatePath
                it[ChapterRevisionTable.archiveCbzPath] = archiveCbzPath
                it[ChapterRevisionTable.comparisonState] = comparisonState.name
                it[ChapterRevisionTable.visualAnalysisState] = visualState.name
                it[visualAnalysisAttempts] = 0
                if (baselineRevisionId != null) {
                    it[comparisonBaselineRevision] = baselineRevisionId
                }
                if (active) {
                    it[activeChapterKey] = this@ChapterRevisionVisualAnalysisTest.chapterKey
                    it[disposition] = ChapterRevisionDisposition.ACCEPTED.name
                }
            } get ChapterRevisionTable.id
        }.value

    private fun processor(
        baselineLookup: (Int) -> suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass? =
            { ChapterRevision.getRevision(it) },
        autoDismiss: Boolean = false,
        maxAttempts: Int = 3,
        onArchiveDue: () -> Unit = {},
        onCleanupDue: () -> Unit = {},
    ) = ChapterRevisionVisualAnalysisProcessor(
        stagingRoot = { stagingRoot },
        archiveRoot = { archiveRoot },
        hammingThreshold = { 2 },
        autoDismissVisuallyEquivalent = { autoDismiss },
        thumbnailMaxDimension = { 64 },
        retryIntervalSeconds = { 300 },
        maxAttempts = { maxAttempts },
        loadRevision = baselineLookup,
        onArchiveDue = onArchiveDue,
        onCleanupDue = onCleanupDue,
    )

    private fun loop(
        processor: ChapterRevisionVisualAnalysisProcessor,
        maxAttempts: Int = 3,
        onArchiveDue: () -> Unit = {},
    ) = ChapterRevisionVisualAnalysisLoop(
        processor = processor,
        stagingRoot = { stagingRoot },
        retryIntervalSeconds = { 300 },
        maxAttempts = { maxAttempts },
        onArchiveDue = onArchiveDue,
        now = { 5 },
    )

    private fun claimed(id: Int) =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.id eq id }
                .first()
                .let { ChapterRevisionTable.toDataClass(it) }
        }

    // ---------------------------------------------------------------------------------------------
    // backlog rules
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `only a due queued analysis is claimed and claiming counts the attempt`() {
        val due = revision("1".repeat(64), "hash", visualState = ChapterVisualAnalysisState.QUEUED)

        val claimed = ChapterRevisionComparisonStore.claimNext(now = 100)

        assertNotNull(claimed)
        assertEquals(due, claimed!!.id)
        assertEquals(ChapterVisualAnalysisState.ANALYZING, claimed.visualAnalysisState)
        assertEquals(1, claimed.visualAnalysisAttempts)
        assertNull(claimed.visualAnalysisNextAttemptAt)
        // the row is no longer claimable while it is being analysed
        assertNull(ChapterRevisionComparisonStore.claimNext(now = 100))
    }

    @Test
    fun `a deferred analysis is not claimed before its retry instant`() {
        val id = revision("2".repeat(64), "hash", visualState = ChapterVisualAnalysisState.QUEUED)
        ChapterRevisionComparisonStore.claimNext(now = 10)
        ChapterRevisionComparisonStore.requeue(id, retryIntervalSeconds = 300, now = 10)

        assertNull(ChapterRevisionComparisonStore.claimNext(now = 100))
        assertEquals(id, ChapterRevisionComparisonStore.claimNext(now = 400)!!.id)
    }

    @Test
    fun `a shutdown returns an interrupted analysis to the queue with its attempt count intact`() {
        val id = revision("3".repeat(64), "hash", visualState = ChapterVisualAnalysisState.QUEUED)
        ChapterRevisionComparisonStore.claimNext(now = 10)

        assertEquals(1, ChapterRevisionComparisonStore.recoverInterrupted(now = 20))

        val recovered = claimed(id)
        assertEquals(ChapterVisualAnalysisState.QUEUED, recovered.visualAnalysisState)
        assertEquals(1, recovered.visualAnalysisAttempts)
    }

    @Test
    fun `a settled analysis can be retried but a queued or committed one is left alone`() {
        val settled = revision("4".repeat(64), "hash", visualState = ChapterVisualAnalysisState.FAILED)
        val queued = revision("5".repeat(64), "hash", visualState = ChapterVisualAnalysisState.QUEUED)
        val committed =
            revision(
                "6".repeat(64),
                "hash",
                visualState = ChapterVisualAnalysisState.COMPLETE,
                archiveState = ChapterArchiveState.REMOTE_PENDING,
            )
        val confirmed =
            revision(
                "7".repeat(64),
                "hash",
                visualState = ChapterVisualAnalysisState.COMPLETE_WITH_LIMITATIONS,
                archiveState = ChapterArchiveState.REMOTE_CONFIRMED,
            )

        val retried = ChapterRevisionComparisonStore.retry(listOf(settled, queued, committed, confirmed), now = 5)

        assertEquals(listOf(settled), retried.map { it.id })
        assertEquals(ChapterVisualAnalysisState.QUEUED, claimed(settled).visualAnalysisState)
        // the comparison of a committed revision is part of its immutable manifest, so re-running it
        // would leave that manifest describing a comparison the archive no longer holds
        assertEquals(ChapterVisualAnalysisState.COMPLETE, claimed(committed).visualAnalysisState)
        assertEquals(ChapterVisualAnalysisState.COMPLETE_WITH_LIMITATIONS, claimed(confirmed).visualAnalysisState)
    }

    // ---------------------------------------------------------------------------------------------
    // archive gating
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `classification queues the comparison and keeps the revision out of the archive queue`() {
        val baseline = revision("6".repeat(64), "baseline-hash", active = true)
        val candidate =
            revision(
                "7".repeat(64),
                contentHash = null,
                acquisitionState = ChapterAcquisitionState.VALIDATING,
                candidatePath = stagedPages("7".repeat(64), listOf(writePng(stagingRoot, "a.png", image(0)))),
            )

        val completion = ChapterRevision.markCompleteAndClassify(candidate, 1, "candidate-hash", "path", now = 50)

        assertNotNull(completion)
        assertEquals(ChapterRevision.ChapterRevisionClassificationOutcome.CONTINUE, completion!!.outcome)
        assertEquals(ChapterRevisionComparisonState.CONTENT_CHANGED, completion.revision.comparisonState)
        assertEquals(ChapterVisualAnalysisState.QUEUED, completion.revision.visualAnalysisState)
        assertEquals(baseline, completion.revision.comparisonBaselineRevisionId)
        // the debt is owed immediately, and against exactly the revision the archive currently holds:
        // the active row itself, identified by the content hash the digest comparison used
        assertEquals(50L, completion.revision.visualAnalysisNextAttemptAt)
        assertEquals(baseline, ChapterRevision.getActiveRevision(chapterKey)?.id)
        assertEquals("baseline-hash", ChapterRevision.getRevision(baseline)!!.contentHash)

        // archiving it now would make the missing comparison permanent
        assertNull(ChapterRevision.claimNextArchiveCommit(now = 60))
    }

    @Test
    fun `an analysis that never produced a summary still releases the archive gate`() {
        // a real baseline row that simply has no archived artifact: the comparison cannot be produced,
        // and the analysis is reported as failed instead of being retried forever
        val baseline = revision("8".repeat(64).replace('8', '7'), "baseline-hash")
        val candidate =
            revision(
                "8".repeat(64),
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = baseline,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
                candidatePath = "revision-candidates/${"8".repeat(64)}",
            )

        runBlocking {
            processor(maxAttempts = 1).process(ChapterRevisionComparisonStore.claimNext(now = 5)!!)
        }

        val stored = claimed(candidate)
        assertEquals(ChapterVisualAnalysisState.FAILED, stored.visualAnalysisState)
        // the content is kept: the revision proceeds to archival with the failed comparison recorded
        assertEquals(ChapterAcquisitionState.COMPLETE, stored.acquisitionState)
        assertNotNull(stored.visualAnalysisCompletedAt)
    }

    // ---------------------------------------------------------------------------------------------
    // the worker
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a comparison is stored with its summary and its aligned pages`() {
        val baselineKey = "9".repeat(64)
        val baselinePages = listOf(writePng(stagingRoot, "b1.png", image(0)), writePng(stagingRoot, "b2.png", image(1)))
        val baseline =
            revision(
                baselineKey,
                "baseline-hash",
                active = true,
                archiveCbzPath = archivedBaseline(baselineKey, baselinePages),
            )

        val candidateKey = "a".repeat(64).replace('a', 'b')
        val candidatePages =
            listOf(
                // byte-identical to the first baseline page
                baselinePages[0].copyTo(File(stagingRoot, "c1.png"), overwrite = true),
                // a genuinely different picture
                writePng(stagingRoot, "c2.png", image(4)),
            )
        val candidate =
            revision(
                candidateKey,
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = baseline,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
                candidatePath = stagedPages(candidateKey, candidatePages),
            )

        var archiveWoken = false
        runBlocking {
            processor(onArchiveDue = { archiveWoken = true }).process(ChapterRevisionComparisonStore.claimNext(now = 5)!!)
        }

        val stored = claimed(candidate)
        assertEquals(ChapterVisualAnalysisState.COMPLETE, stored.visualAnalysisState)
        assertTrue(archiveWoken, "a terminal comparison is what opens the archive gate")

        val summary = ChapterRevisionComparisonStore.getComparison(candidate)
        assertNotNull(summary)
        assertEquals(2, summary!!.baselinePageCount)
        assertEquals(2, summary.candidatePageCount)
        assertEquals(1, summary.exactCount)
        assertEquals(1, summary.modifiedCount)
        assertEquals(0, summary.addedCount)
        assertEquals(0, summary.removedCount)
        assertFalse(summary.allPagesVisuallyEquivalent)
        assertEquals(
            ChapterRevisionVisualComparison.ALGORITHM_VERSION,
            summary.algorithmVersion,
        )

        val pages = ChapterRevisionComparisonStore.getPages(candidate)
        assertEquals(2, pages.size)
        assertEquals(ChapterRevisionPageAlignmentState.EXACT, pages[0].state)
        assertEquals(ChapterRevisionPageAlignmentState.MODIFIED, pages[1].state)
        assertEquals(0, pages[0].ordinal)
        assertEquals(1, pages[1].ordinal)
        assertNotNull(pages[0].baselineExactHash)
        assertEquals(pages[0].baselineExactHash, pages[0].candidateExactHash)

        // a rewritten page is reviewed with both of its versions side by side, so the MODIFIED row
        // carries a preview of each side while the untouched one carries none
        assertFalse(pages[0].hasBaselinePreview)
        assertFalse(pages[0].hasCandidatePreview)
        assertTrue(pages[1].hasBaselinePreview)
        assertTrue(pages[1].hasCandidatePreview)

        val baselinePreview = pages[1].baselineThumbnailRelativePath!!
        val candidatePreview = pages[1].candidateThumbnailRelativePath!!
        assertNotEquals(baselinePreview, candidatePreview)
        assertTrue(baselinePreview.startsWith(ChapterRevisionThumbnails.ROOT_DIR_NAME))
        assertTrue(File(stagingRoot, baselinePreview).isFile)
        assertTrue(File(stagingRoot, candidatePreview).isFile)
        assertNotNull(pages[1].baselineThumbnailSha256)
        assertNotNull(pages[1].candidateThumbnailSha256)

        // the alignment is what the archive manifest records, and it never names a staging path
        val audit = ChapterRevisionArchiveVisuals.auditOf(candidate)
        assertNotNull(audit)
        assertEquals(2, audit!!.pages.size)
        assertFalse(audit.pages.any { it.state == ChapterRevisionPageAlignmentState.ADDED })
    }

    @Test
    fun `a comparison against a closed baseline never dismisses content by default`() {
        val baselineKey = "c".repeat(64)
        val baselinePages = listOf(writePng(stagingRoot, "d1.png", image(0)))
        revision(
            baselineKey,
            "baseline-hash",
            active = true,
            archiveCbzPath = archivedBaseline(baselineKey, baselinePages),
        )

        val candidateKey = "d".repeat(64)
        val candidatePages =
            listOf(
                // the same picture, encoded differently: byte-wise different, visually identical
                writePng(stagingRoot, "e1.png", image(0, BufferedImage.TYPE_3BYTE_BGR)),
            )
        val candidate =
            revision(
                candidateKey,
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = ChapterRevision.getActiveRevision(chapterKey)!!.id,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
                candidatePath = stagedPages(candidateKey, candidatePages),
            )

        runBlocking {
            processor().process(ChapterRevisionComparisonStore.claimNext(now = 5)!!)
        }

        val stored = claimed(candidate)
        assertEquals(ChapterVisualAnalysisState.COMPLETE, stored.visualAnalysisState)
        val summary = ChapterRevisionComparisonStore.getComparison(candidate)!!
        assertTrue(summary.allPagesVisuallyEquivalent)
        // perceptually equivalent content is still reviewed: dismissing it by default would hide a
        // re-release that a human may well care about
        assertEquals(ChapterRevisionDisposition.CANDIDATE, stored.disposition)
        assertNull(stored.comparisonCleanupDueAt)
    }

    @Test
    fun `auto dismissal is opt in and schedules the staged cleanup`() {
        val baselineKey = "e".repeat(64)
        val baselinePages = listOf(writePng(stagingRoot, "f1.png", image(0)))
        revision(
            baselineKey,
            "baseline-hash",
            active = true,
            archiveCbzPath = archivedBaseline(baselineKey, baselinePages),
        )

        val candidateKey = "f".repeat(64)
        val candidate =
            revision(
                candidateKey,
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = ChapterRevision.getActiveRevision(chapterKey)!!.id,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
                candidatePath =
                    stagedPages(
                        candidateKey,
                        listOf(writePng(stagingRoot, "g1.png", image(0, BufferedImage.TYPE_3BYTE_BGR))),
                    ),
            )

        var cleanupWoken = false
        runBlocking {
            processor(autoDismiss = true, onCleanupDue = { cleanupWoken = true })
                .process(ChapterRevisionComparisonStore.claimNext(now = 5)!!, now = 5)
        }

        val stored = claimed(candidate)
        assertEquals(ChapterRevisionDisposition.UNCHANGED, stored.disposition)
        assertEquals(5L, stored.visualAnalysisCompletedAt)
        assertNotNull(stored.comparisonCleanupDueAt)
        assertTrue(cleanupWoken, "the removal of the dead staged pages is owned by the sweep worker")
    }

    @Test
    fun `a baseline that moved while the analysis ran is requeued instead of published`() {
        val oldBaseline = revision("0".repeat(64), "old-hash")
        val newerBaseline = revision("0".repeat(64).replace('0', '9'), "new-hash")

        // the comparison was started against the old baseline, but the newer one is active now
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq newerBaseline }) {
                it[activeChapterKey] = chapterKey
                it[disposition] = ChapterRevisionDisposition.ACCEPTED.name
            }
        }

        val candidateKey = "2".repeat(64)
        val candidate =
            revision(
                candidateKey,
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = oldBaseline,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
                candidatePath = stagedPages(candidateKey, listOf(writePng(stagingRoot, "h1.png", image(0)))),
            )

        val claimedRow = ChapterRevisionComparisonStore.claimNext(now = 5)!!
        val outcome =
            ChapterRevisionComparisonStore.commit(
                revisionId = claimedRow.id,
                summary =
                    ChapterRevisionComparisonSummary(
                        baselinePageCount = 1,
                        candidatePageCount = 1,
                        exactCount = 1,
                        visuallyEquivalentCount = 0,
                        modifiedCount = 0,
                        addedCount = 0,
                        removedCount = 0,
                        hammingThreshold = 2,
                        algorithmVersion = ChapterRevisionVisualComparison.ALGORITHM_VERSION,
                        allPagesVisuallyEquivalent = true,
                        hasLimitations = false,
                        limitations = null,
                    ),
                pages = emptyList(),
                autoDismissVisuallyEquivalent = true,
                retryIntervalSeconds = 60,
                now = 10,
            )

        assertEquals(ChapterRevisionVisualCommit.BaselineMoved, outcome)
        val stored = claimed(candidate)
        // the stale result is discarded, never published against the wrong baseline
        assertEquals(ChapterVisualAnalysisState.QUEUED, stored.visualAnalysisState)
        assertEquals(newerBaseline, stored.comparisonBaselineRevisionId)
        assertEquals(70L, stored.visualAnalysisNextAttemptAt)
        assertNull(ChapterRevisionComparisonStore.getComparison(candidate))
    }

    @Test
    fun `a baseline that moved to identical content is dismissed exactly instead of re-analysed`() {
        val oldBaseline = revision("3".repeat(64), "old-hash")
        val newerBaseline = revision("4".repeat(64), "candidate-hash")
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq newerBaseline }) {
                it[activeChapterKey] = chapterKey
                it[disposition] = ChapterRevisionDisposition.ACCEPTED.name
            }
        }

        val candidate =
            revision(
                "5".repeat(64),
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = oldBaseline,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
            )

        ChapterRevisionComparisonStore.claimNext(now = 5)
        val outcome =
            ChapterRevisionComparisonStore.commit(
                revisionId = candidate,
                summary =
                    ChapterRevisionComparisonSummary(
                        baselinePageCount = 0,
                        candidatePageCount = 0,
                        exactCount = 0,
                        visuallyEquivalentCount = 0,
                        modifiedCount = 0,
                        addedCount = 0,
                        removedCount = 0,
                        hammingThreshold = 2,
                        algorithmVersion = ChapterRevisionVisualComparison.ALGORITHM_VERSION,
                        allPagesVisuallyEquivalent = false,
                        hasLimitations = false,
                        limitations = null,
                    ),
                pages = emptyList(),
                autoDismissVisuallyEquivalent = false,
                retryIntervalSeconds = 60,
                now = 10,
            )

        assertTrue(outcome is ChapterRevisionVisualCommit.Applied)
        assertTrue((outcome as ChapterRevisionVisualCommit.Applied).unchanged)
        val stored = claimed(candidate)
        assertEquals(ChapterRevisionComparisonState.EXACT_MATCH, stored.comparisonState)
        assertEquals(ChapterRevisionDisposition.UNCHANGED, stored.disposition)
        assertEquals(ChapterVisualAnalysisState.NOT_REQUIRED, stored.visualAnalysisState)
    }

    // ---------------------------------------------------------------------------------------------
    // artifacts
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every attempt renders its own previews and never reuses another attempt's`() {
        val key = "6".repeat(64)
        val revisionId = 4_242
        val source = writePng(stagingRoot, "i1.png", image(0))
        var renders = 0
        val render = {
            renders++
            ImageIO.read(source)
        }

        val first =
            ChapterRevisionThumbnails.store(
                stagingRoot = stagingRoot,
                candidateKey = key,
                revisionId = revisionId,
                attempt = 1,
                ordinal = 0,
                side = ChapterRevisionThumbnailSide.CANDIDATE,
                maxDimension = 64,
                render = render,
            )

        assertNotNull(first)
        assertEquals(1, renders)
        assertTrue(first!!.relativePath.startsWith(ChapterRevisionThumbnails.ROOT_DIR_NAME))
        assertTrue(File(stagingRoot, first.relativePath).isFile)

        // a later attempt renders again into its own directory: a digest that happens to match a file
        // on disk says nothing about which baseline that file shows
        val second =
            ChapterRevisionThumbnails.store(
                stagingRoot = stagingRoot,
                candidateKey = key,
                revisionId = revisionId,
                attempt = 2,
                ordinal = 0,
                side = ChapterRevisionThumbnailSide.CANDIDATE,
                maxDimension = 64,
                render = render,
            )
        assertEquals(2, renders)
        assertEquals(first.sha256, second!!.sha256)
        assertNotEquals(first.relativePath, second.relativePath)

        // abandoning one attempt removes exactly what that attempt wrote
        ChapterRevisionThumbnails.deleteAttempt(stagingRoot, key, revisionId, 1)
        assertFalse(File(stagingRoot, first.relativePath).exists())
        assertTrue(File(stagingRoot, second.relativePath).isFile)

        ChapterRevisionThumbnails.deleteAll(stagingRoot, key)
        assertFalse(File(stagingRoot, second.relativePath).exists())
    }

    @Test
    fun `a committed replacement keeps only the attempt it committed`() {
        val baselineKey = "e".repeat(64)
        val baselinePages = listOf(writePng(stagingRoot, "k1.png", image(0)), writePng(stagingRoot, "k2.png", image(1)))
        val baseline =
            revision(
                baselineKey,
                "baseline-hash",
                active = true,
                archiveCbzPath = archivedBaseline(baselineKey, baselinePages),
            )

        val candidateKey = "f".repeat(64)
        val candidatePages =
            listOf(
                baselinePages[0].copyTo(File(stagingRoot, "l1.png"), overwrite = true),
                writePng(stagingRoot, "l2.png", image(4)),
            )
        val candidate =
            revision(
                candidateKey,
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = baseline,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
                candidatePath = stagedPages(candidateKey, candidatePages),
            )

        runBlocking {
            processor().process(ChapterRevisionComparisonStore.claimNext(now = 5)!!, now = 5)
        }

        val first = ChapterRevisionComparisonStore.getPages(candidate).single { it.hasBaselinePreview }
        val firstAttemptDirectory = ChapterRevisionThumbnails.directory(stagingRoot, candidateKey, candidate, 1)
        assertTrue(File(stagingRoot, first.baselineThumbnailRelativePath!!).isFile)
        assertTrue(firstAttemptDirectory.exists())

        // an operator retry replaces the comparison, so the rows now name the second attempt and
        // nothing references what the first one rendered any more
        ChapterRevisionComparisonStore.retry(listOf(candidate), now = 1)
        runBlocking {
            processor().process(ChapterRevisionComparisonStore.claimNext(now = 5)!!, now = 5)
        }

        val second = ChapterRevisionComparisonStore.getPages(candidate).single { it.hasBaselinePreview }
        assertEquals(2, claimed(candidate).visualAnalysisAttempts)
        assertNotEquals(first.baselineThumbnailRelativePath, second.baselineThumbnailRelativePath)
        assertFalse(firstAttemptDirectory.exists(), "the superseded attempt is unreferenced after the commit")
        assertTrue(File(stagingRoot, second.baselineThumbnailRelativePath!!).isFile)
        assertTrue(File(stagingRoot, second.candidateThumbnailRelativePath!!).isFile)
    }

    @Test
    fun `a replacement that fails keeps the previews the committed comparison points at`() {
        val baselineKey = "1".repeat(64)
        val baselinePages = listOf(writePng(stagingRoot, "p1.png", image(0)), writePng(stagingRoot, "p2.png", image(1)))
        val baseline =
            revision(
                baselineKey,
                "baseline-hash",
                active = true,
                archiveCbzPath = archivedBaseline(baselineKey, baselinePages),
            )

        val candidateKey = "2".repeat(64)
        val candidatePages =
            listOf(
                baselinePages[0].copyTo(File(stagingRoot, "q1.png"), overwrite = true),
                writePng(stagingRoot, "q2.png", image(4)),
            )
        val candidate =
            revision(
                candidateKey,
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = baseline,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
                candidatePath = stagedPages(candidateKey, candidatePages),
            )

        runBlocking {
            processor().process(ChapterRevisionComparisonStore.claimNext(now = 5)!!, now = 5)
        }

        val committed = ChapterRevisionComparisonStore.getPages(candidate).single { it.hasBaselinePreview }
        val committedBaselinePreview = committed.baselineThumbnailRelativePath!!
        val committedCandidatePreview = committed.candidateThumbnailRelativePath!!
        assertTrue(File(stagingRoot, committedBaselinePreview).isFile)

        ChapterRevisionComparisonStore.retry(listOf(candidate), now = 1)
        val second = ChapterRevisionComparisonStore.claimNext(now = 5)!!
        assertEquals(2, second.visualAnalysisAttempts)

        // the second attempt renders before it fails, so there is something of its own to discard
        val secondAttemptDirectory = ChapterRevisionThumbnails.directory(stagingRoot, candidateKey, candidate, 2)
        secondAttemptDirectory.mkdirs()
        File(secondAttemptDirectory, "000000-baseline.jpg").writeBytes(byteArrayOf(1, 2, 3))

        // the staged pages are gone, so this attempt cannot produce a comparison at all
        File(stagingRoot, ChapterRevisionStaging.relativeDirectory(candidateKey)).deleteRecursively()
        runBlocking {
            processor().process(second, now = 5)
        }

        assertEquals(ChapterVisualAnalysisState.QUEUED, claimed(candidate).visualAnalysisState)
        assertFalse(secondAttemptDirectory.exists(), "an abandoned attempt leaves nothing behind")

        // the committed comparison and the previews it names are untouched by the failed replacement
        val pages = ChapterRevisionComparisonStore.getPages(candidate).single { it.hasBaselinePreview }
        assertEquals(committedBaselinePreview, pages.baselineThumbnailRelativePath)
        assertNotNull(ChapterRevisionComparisonStore.getComparison(candidate))
        assertTrue(File(stagingRoot, committedBaselinePreview).isFile)
        assertTrue(File(stagingRoot, committedCandidatePreview).isFile)
    }

    @Test
    fun `an attempt that settles without a summary leaves no previews behind`() {
        val baselineKey = "3".repeat(64)
        val baselinePages = listOf(writePng(stagingRoot, "r1.png", image(0)))
        val baseline =
            revision(
                baselineKey,
                "baseline-hash",
                archiveCbzPath = archivedBaseline(baselineKey, baselinePages),
            )

        // the chapter moved on while the analysis ran, and the revision that is active now holds
        // exactly the candidate's bytes, so the comparison becomes moot
        revision("4".repeat(64), "candidate-hash", active = true)

        val candidateKey = "5".repeat(64)
        val candidatePages =
            listOf(
                baselinePages[0].copyTo(File(stagingRoot, "s1.png"), overwrite = true),
                writePng(stagingRoot, "s2.png", image(4)),
            )
        val candidate =
            revision(
                candidateKey,
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = baseline,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
                candidatePath = stagedPages(candidateKey, candidatePages),
            )
        val attemptDirectory = ChapterRevisionThumbnails.directory(stagingRoot, candidateKey, candidate, 1)

        runBlocking {
            processor().process(ChapterRevisionComparisonStore.claimNext(now = 5)!!, now = 5)
        }

        val stored = claimed(candidate)
        assertEquals(ChapterRevisionComparisonState.EXACT_MATCH, stored.comparisonState)
        assertEquals(ChapterVisualAnalysisState.NOT_REQUIRED, stored.visualAnalysisState)
        assertEquals(ChapterRevisionDisposition.UNCHANGED, stored.disposition)
        assertNull(ChapterRevisionComparisonStore.getComparison(candidate))
        // nothing was stored, so the previews this attempt rendered are unreferenced and must be gone
        assertFalse(attemptDirectory.exists())
    }

    @Test
    fun `a baseline that moves to the candidate's own bytes discards the committed comparison and its previews`() {
        val baselineKey = "7".repeat(64)
        val baselinePages = listOf(writePng(stagingRoot, "t1.png", image(0)), writePng(stagingRoot, "t2.png", image(1)))
        val baseline =
            revision(
                baselineKey,
                "baseline-hash",
                active = true,
                archiveCbzPath = archivedBaseline(baselineKey, baselinePages),
            )

        val candidateKey = "8".repeat(64)
        val candidatePages =
            listOf(
                baselinePages[0].copyTo(File(stagingRoot, "u1.png"), overwrite = true),
                writePng(stagingRoot, "u2.png", image(4)),
            )
        val candidate =
            revision(
                candidateKey,
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = baseline,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
                candidatePath = stagedPages(candidateKey, candidatePages),
            )

        var wakes = 0
        var cleanups = 0
        val processor = processor(onArchiveDue = { wakes++ }, onCleanupDue = { cleanups++ })

        runBlocking {
            processor.process(ChapterRevisionComparisonStore.claimNext(now = 5)!!, now = 5)
        }

        // a real committed comparison, with a preview on both sides of the rewritten page
        val committedRow = ChapterRevisionComparisonStore.getPages(candidate).single { it.hasBaselinePreview }
        val committedPreview = File(stagingRoot, committedRow.baselineThumbnailRelativePath!!)
        assertTrue(committedPreview.isFile)
        assertNotNull(ChapterRevisionComparisonStore.getComparison(candidate))
        assertEquals(1, wakes)

        // the chapter moved on: the revision that is active now holds exactly the candidate's bytes, so
        // the comparison against the previous baseline is moot
        val newer = revision("9".repeat(64), "candidate-hash")
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq baseline }) { it[activeChapterKey] = null }
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq newer }) {
                it[activeChapterKey] = chapterKey
                it[disposition] = ChapterRevisionDisposition.ACCEPTED.name
            }
        }

        ChapterRevisionComparisonStore.retry(listOf(candidate), now = 1)
        runBlocking {
            processor.process(ChapterRevisionComparisonStore.claimNext(now = 5)!!, now = 5)
        }

        val stored = claimed(candidate)
        assertEquals(ChapterRevisionComparisonState.EXACT_MATCH, stored.comparisonState)
        assertEquals(ChapterVisualAnalysisState.NOT_REQUIRED, stored.visualAnalysisState)
        assertEquals(ChapterRevisionDisposition.UNCHANGED, stored.disposition)
        // the summary described a baseline nobody compares to any more, so it is deleted rather than
        // left behind as this revision's current answer
        assertNull(ChapterRevisionComparisonStore.getComparison(candidate))
        assertTrue(ChapterRevisionComparisonStore.getPages(candidate).isEmpty())
        // and no attempt survives: neither the one that just rendered nor the committed one, whose
        // previews the deleted rows were the only description of
        assertFalse(ChapterRevisionThumbnails.directory(stagingRoot, candidateKey, candidate, 1).exists())
        assertFalse(ChapterRevisionThumbnails.directory(stagingRoot, candidateKey, candidate, 2).exists())
        assertFalse(committedPreview.exists())
        // the settle still wakes the sweep (the staged pages are dead weight) and the archive
        assertEquals(1, cleanups)
        assertEquals(2, wakes)
    }

    @Test
    fun `a chapter that loses its active baseline discards the committed comparison and its previews`() {
        val baselineKey = "a".repeat(64).replace('a', 'c')
        val baselinePages = listOf(writePng(stagingRoot, "v1.png", image(0)), writePng(stagingRoot, "v2.png", image(1)))
        val baseline =
            revision(
                baselineKey,
                "baseline-hash",
                active = true,
                archiveCbzPath = archivedBaseline(baselineKey, baselinePages),
            )

        val candidateKey = "b".repeat(64).replace('b', 'd')
        val candidatePages =
            listOf(
                baselinePages[0].copyTo(File(stagingRoot, "w1.png"), overwrite = true),
                writePng(stagingRoot, "w2.png", image(4)),
            )
        val candidate =
            revision(
                candidateKey,
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = baseline,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
                candidatePath = stagedPages(candidateKey, candidatePages),
            )

        runBlocking {
            processor().process(ChapterRevisionComparisonStore.claimNext(now = 5)!!, now = 5)
        }

        val committedRow = ChapterRevisionComparisonStore.getPages(candidate).single { it.hasBaselinePreview }
        val committedPreview = File(stagingRoot, committedRow.baselineThumbnailRelativePath!!)
        assertTrue(committedPreview.isFile)
        assertNotNull(ChapterRevisionComparisonStore.getComparison(candidate))

        // nothing is active for this chapter any more, so there is no baseline to compare to at all
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq baseline }) { it[activeChapterKey] = null }
        }

        ChapterRevisionComparisonStore.retry(listOf(candidate), now = 1)
        runBlocking {
            processor().process(ChapterRevisionComparisonStore.claimNext(now = 5)!!, now = 5)
        }

        val stored = claimed(candidate)
        assertEquals(ChapterRevisionComparisonState.NO_BASELINE, stored.comparisonState)
        assertEquals(ChapterVisualAnalysisState.NOT_REQUIRED, stored.visualAnalysisState)
        assertNull(ChapterRevisionComparisonStore.getComparison(candidate))
        assertTrue(ChapterRevisionComparisonStore.getPages(candidate).isEmpty())
        assertFalse(ChapterRevisionThumbnails.directory(stagingRoot, candidateKey, candidate, 1).exists())
        assertFalse(ChapterRevisionThumbnails.directory(stagingRoot, candidateKey, candidate, 2).exists())
        assertFalse(committedPreview.exists())
    }

    @Test
    fun `a cancelled analysis leaves no previews of an attempt that never committed`() {
        val baselineKey = "6".repeat(64).replace('6', 'a')
        val baselinePages = listOf(writePng(stagingRoot, "m1.png", image(0)))
        val baseline =
            revision(
                baselineKey,
                "baseline-hash",
                active = true,
                archiveCbzPath = archivedBaseline(baselineKey, baselinePages),
            )

        // enough differing pages that the attempt is still rendering when it is cancelled
        val candidateKey = "6".repeat(64)
        val candidatePages = (1..200).map { index -> writePng(stagingRoot, "n$index.png", image(index)) }
        val candidate =
            revision(
                candidateKey,
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = baseline,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
                candidatePath = stagedPages(candidateKey, candidatePages),
            )

        val claimedRevision = ChapterRevisionComparisonStore.claimNext(now = 5)!!
        val attemptDirectory = ChapterRevisionThumbnails.directory(stagingRoot, candidateKey, candidate, 1)

        var failure: Throwable? = null
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val job = scope.launch { processor().process(claimedRevision, now = 5) }
            job.invokeOnCompletion { cause -> failure = cause }

            runBlocking {
                // as soon as the attempt has rendered anything, stop it in the middle of its work
                withTimeout(30_000) {
                    while (attemptDirectory.listFiles().orEmpty().none { it.isFile }) {
                        delay(2)
                    }
                }
                job.cancelAndJoin()
            }
        } finally {
            scope.cancel()
        }

        assertTrue(failure is CancellationException, "the analysis was cancelled, not completed")
        assertFalse(attemptDirectory.exists(), "a cancelled attempt may not leave previews behind")
        // it never committed, so the row is still claimed and the worker's startup recovery owns it
        assertEquals(ChapterVisualAnalysisState.ANALYZING, claimed(candidate).visualAnalysisState)
        assertNull(ChapterRevisionComparisonStore.getComparison(candidate))
    }

    @Test
    fun `a page that cannot be rendered leaves the row without a preview`() {
        val stored =
            ChapterRevisionThumbnails.store(
                stagingRoot = stagingRoot,
                candidateKey = "6".repeat(64),
                revisionId = 1,
                attempt = 1,
                ordinal = 0,
                side = ChapterRevisionThumbnailSide.BASELINE,
                maxDimension = 64,
                render = { null },
            )

        assertNull(stored)
    }

    @Test
    fun `the manifest carries the visual audit but never a thumbnail location`() {
        val key = "7".repeat(64)
        val revisionId = revision(key, "hash", visualState = ChapterVisualAnalysisState.COMPLETE)

        transaction {
            ChapterRevisionComparisonTable.insert {
                it[revision] = revisionId
                it[baselinePageCount] = 1
                it[candidatePageCount] = 1
                it[exactCount] = 0
                it[visuallyEquivalentCount] = 0
                it[modifiedCount] = 1
                it[addedCount] = 0
                it[removedCount] = 0
                it[alignedCount] = 1
                it[hammingThreshold] = 2
                it[algorithmVersion] = ChapterRevisionVisualComparison.ALGORITHM_VERSION
                it[allPagesVisuallyEquivalent] = false
                it[hasLimitations] = false
                it[createdAt] = 1
                it[updatedAt] = 1
            }
        }

        val audit = ChapterRevisionArchiveVisuals.auditOf(revisionId)
        assertNotNull(audit)
        assertEquals(1, audit!!.modifiedCount)
        assertEquals(ChapterRevisionVisualComparison.ALGORITHM_VERSION, audit.algorithmVersion)

        // a version 3 manifest has no such field, and decoding one leaves it absent rather than failing
        val legacy =
            """
            {
              "schemaVersion": 3,
              "revisionId": 1,
              "candidateKey": "$key",
              "sourceChapterUrl": "https://example.invalid/legacy",
              "memo": {},
              "chapterNumber": 1.0,
              "chapterTitle": "chapter",
              "discoveredAt": 1,
              "archivedAt": 2,
              "pageCount": 1,
              "pages": [],
              "archiveContentHash": "hash",
              "archiveSize": 10
            }
            """.trimIndent()

        val decoded = ChapterRevisionArchiveManifestCodec.decode(legacy.toByteArray())
        assertEquals(3, decoded.schemaVersion)
        assertNull(decoded.visualComparison)
        assertEquals(ChapterRevisionArchiveManifestCodec.SCHEMA_VERSION, 5)
    }

    @Test
    fun `a failed analysis is recorded as a category even though no summary exists`() {
        val key = "b".repeat(64)
        val revisionId = revision(key, "hash", visualState = ChapterVisualAnalysisState.FAILED)

        // nothing was ever produced for this revision, so the manifest carries no comparison at all
        assertNull(ChapterRevisionArchiveVisuals.auditOf(revisionId))

        // ... but the terminal audit still says the analysis ran and how it ended, which is the only
        // way a re-read archive can tell "owed nothing" apart from "compared and failed"
        val audit =
            ChapterRevisionArchiveVisuals.analysisOf(
                state = ChapterVisualAnalysisState.FAILED,
                attempts = 2,
                completedAt = 9,
                failure = ChapterVisualAnalysisFailure.BASELINE_MISSING,
            )

        assertEquals(ChapterVisualAnalysisState.FAILED, audit.state)
        assertEquals(2, audit.attempts)
        assertEquals(9L, audit.completedAt)
        // a category name, never a message: nothing local - a path, a URL, a source error - can reach
        // the portable manifest
        assertEquals(ChapterVisualAnalysisFailure.BASELINE_MISSING.name, audit.failureCategory)
        assertFalse(audit.failureCategory!!.contains('/'))
        assertFalse(audit.failureCategory.contains('\\'))
    }

    @Test
    fun `an archived comparison catalogue is read back as the same ordered alignment`() {
        val key = "8".repeat(64)
        val pages = listOf(writePng(stagingRoot, "j1.png", image(0)), writePng(stagingRoot, "j2.png", image(1)))
        val baselineKey = "9".repeat(64)
        revision(baselineKey, "baseline-hash", active = true, archiveCbzPath = archivedBaseline(baselineKey, pages))

        val read = ChapterRevisionPageImages.openArchive(ChapterRevisionArchiveArtifacts.cbzFile(archiveRoot, baselineKey))

        try {
            assertEquals(2, read.pages.size)
            assertEquals(ChapterRevisionStaging.pageFileName(0) + ".png", read.pages[0].name)
            assertTrue(read.limitations.isEmpty())
            assertNotNull(read.pages[0].perceptualHash)
            assertEquals(64, read.pages[0].width)
            assertEquals(96, read.pages[0].height)
        } finally {
            read.close()
        }
    }

    @Test
    fun `a zip entry that is not a plain page name is refused`() {
        val key = "a".repeat(64).replace('a', 'c')
        val cbz = ChapterRevisionArchiveArtifacts.cbzFile(archiveRoot, key)
        cbz.parentFile.mkdirs()

        java.util.zip.ZipOutputStream(cbz.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("../../escape.png"))
            zip.write(42)
            zip.closeEntry()
        }

        assertThrows(ChapterRevisionPageAccessException::class.java) {
            ChapterRevisionPageImages.openArchive(cbz)
        }
    }

    @Test
    fun `an unsupported page degrades to a limitation instead of failing the comparison`() {
        val directory = File(stagingRoot, "unsupported")
        directory.mkdirs()
        File(directory, "00001.png").writeBytes(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32) { 7 },
        )
        File(directory, "00002.png").writeBytes(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32) { 9 },
        )

        val read = ChapterRevisionPageImages.openDirectory(directory)
        try {
            // the exact digest is what the comparison truly needs, and it is still there
            assertEquals(2, read.pages.size)
            assertNotNull(read.pages[0].exactHash)
            assertEquals(2, read.limitations.size)
            assertNull(read.pages[0].perceptualHash)
        } finally {
            read.close()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // bounded reads
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the digest read is bounded so a lying container cannot stream forever`() {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

        val abandoned = ChapterRevisionPageImages.digestOf({ ByteArrayInputStream(payload) }, limit = 1_024)

        // the stream is dropped after one byte more than the limit and never read to its end, and a
        // digest of a prefix is deliberately not reported
        assertNull(abandoned.first)
        assertEquals(1_025L, abandoned.second)

        val complete = ChapterRevisionPageImages.digestOf({ ByteArrayInputStream(payload) }, limit = payload.size.toLong())
        assertNotNull(complete.first)
        assertEquals(payload.size.toLong(), complete.second)
    }

    @Test
    fun `an entry that expands past the page limit becomes a terminal limitation without being read`() {
        val key = "e".repeat(64)
        val cbz = ChapterRevisionArchiveArtifacts.cbzFile(archiveRoot, key)
        cbz.parentFile.mkdirs()

        // zeroes compress to almost nothing, so the archive stays small while its declared page is
        // larger than the analysis limit
        java.util.zip.ZipOutputStream(cbz.outputStream()).use { zip ->
            zip.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)
            zip.putNextEntry(java.util.zip.ZipEntry("00001.png"))
            val chunk = ByteArray(1 shl 20)
            repeat((ChapterRevisionPageImages.MAX_PAGE_BYTES / chunk.size + 2).toInt()) { zip.write(chunk) }
            zip.closeEntry()
        }

        assertTrue(cbz.length() < ChapterRevisionPageImages.MAX_PAGE_BYTES, "the archive itself is small")

        val read = ChapterRevisionPageImages.openArchive(cbz)
        try {
            assertEquals(1, read.pages.size)
            assertNull(read.pages[0].exactHash)
            assertNull(read.pages[0].perceptualHash)
            assertTrue(read.pages[0].size > ChapterRevisionPageImages.MAX_PAGE_BYTES)
            assertEquals(1, read.limitations.size)
            assertTrue(read.limitations.single().contains("larger than the analysis limit"))
        } finally {
            read.close()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // unexpected failures
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an unexpected failure is requeued with a persisted backoff by the process that failed`() {
        val baseline = revision("f".repeat(64).replace('f', 'd'), "baseline-hash")
        val candidate =
            revision(
                "f".repeat(64),
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = baseline,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
                candidatePath = "revision-candidates/${"f".repeat(64)}",
            )

        var archiveWoken = false
        val loop =
            loop(
                processor(baselineLookup = { throw IllegalStateException("a path that must never be stored") }),
                onArchiveDue = { archiveWoken = true },
            )

        runBlocking { loop.drainOnce() }

        val stored = claimed(candidate)
        // the row is not left mid flight until the next start, where nothing could see it again
        assertEquals(ChapterVisualAnalysisState.QUEUED, stored.visualAnalysisState)
        assertEquals(1, stored.visualAnalysisAttempts)
        assertEquals(305L, stored.visualAnalysisNextAttemptAt)
        assertFalse(archiveWoken, "a retryable failure keeps the archive gate closed")
        // only the type is ever recorded, never the message a throwable happens to carry
        assertNull(stored.visualAnalysisLastFailure)

        // the requeue is a real wait: it is neither claimed early nor immediately re-drained
        assertNull(ChapterRevisionComparisonStore.claimNext(now = 100))
        assertEquals(candidate, ChapterRevisionComparisonStore.claimNext(now = 400)!!.id)
    }

    @Test
    fun `an unexpected failure is reported as failed once its attempt budget is spent`() {
        val baseline = revision("e".repeat(64).replace('e', 'c'), "baseline-hash")
        val candidate =
            revision(
                "e".repeat(64),
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = baseline,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
                candidatePath = "revision-candidates/${"e".repeat(64)}",
            )

        var archiveWoken = false
        val loop =
            loop(
                processor(baselineLookup = { throw IllegalStateException("boom") }, maxAttempts = 1),
                maxAttempts = 1,
                onArchiveDue = { archiveWoken = true },
            )

        runBlocking { loop.drainOnce() }

        val stored = claimed(candidate)
        assertEquals(ChapterVisualAnalysisState.FAILED, stored.visualAnalysisState)
        assertEquals(ChapterVisualAnalysisFailure.UNEXPECTED, stored.visualAnalysisLastFailure)
        // the stored audit is a fixed sentence, never the exception's own message
        assertEquals(ChapterVisualAnalysisFailure.UNEXPECTED.message, stored.visualAnalysisLastError)
        assertNotNull(stored.visualAnalysisCompletedAt)
        assertTrue(archiveWoken, "a terminal failure is what finally opens the archive gate")
    }

    // ---------------------------------------------------------------------------------------------
    // dismissal and manifest audit
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a comparison that was only partly possible is never auto dismissed`() {
        val baseline = revision("a".repeat(64).replace('a', 'd'), "baseline-hash", active = true)
        val candidate =
            revision(
                "a".repeat(64).replace('a', 'e'),
                "candidate-hash",
                visualState = ChapterVisualAnalysisState.QUEUED,
                baselineRevisionId = baseline,
                comparisonState = ChapterRevisionComparisonState.CONTENT_CHANGED,
            )

        ChapterRevisionComparisonStore.claimNext(now = 5)
        val outcome =
            ChapterRevisionComparisonStore.commit(
                revisionId = candidate,
                summary =
                    ChapterRevisionComparisonSummary(
                        baselinePageCount = 1,
                        candidatePageCount = 1,
                        exactCount = 1,
                        visuallyEquivalentCount = 0,
                        modifiedCount = 0,
                        addedCount = 0,
                        removedCount = 0,
                        hammingThreshold = 2,
                        algorithmVersion = ChapterRevisionVisualComparison.ALGORITHM_VERSION,
                        allPagesVisuallyEquivalent = true,
                        hasLimitations = true,
                        limitations = "the page could not be decoded",
                    ),
                pages = emptyList(),
                autoDismissVisuallyEquivalent = true,
                retryIntervalSeconds = 60,
                now = 10,
            )

        assertTrue(outcome is ChapterRevisionVisualCommit.Applied)
        val stored = claimed(candidate)
        // part of the chapter was never actually compared, so dismissing it would hide exactly the
        // re-release a human has to look at
        assertEquals(ChapterRevisionDisposition.CANDIDATE, stored.disposition)
        assertEquals(ChapterVisualAnalysisState.COMPLETE_WITH_LIMITATIONS, stored.visualAnalysisState)
        assertNull(stored.comparisonCleanupDueAt)
    }

    @Test
    fun `the manifest records how the analysis ended even when it produced no comparison`() {
        val failed = revision("f".repeat(64).replace('f', 'a'), "hash", visualState = ChapterVisualAnalysisState.QUEUED)
        val claimedRow = ChapterRevisionComparisonStore.claimNext(now = 5)!!
        ChapterRevisionComparisonStore.markFailed(claimedRow.id, ChapterVisualAnalysisFailure.BASELINE_MISSING, now = 9)

        val stored = claimed(failed)
        val analysis =
            ChapterRevisionArchiveVisuals.analysisOf(
                state = stored.visualAnalysisState,
                attempts = stored.visualAnalysisAttempts,
                completedAt = stored.visualAnalysisCompletedAt,
                failure = stored.visualAnalysisLastFailure,
            )

        assertEquals(ChapterVisualAnalysisState.FAILED, analysis.state)
        assertEquals(1, analysis.attempts)
        assertEquals(9L, analysis.completedAt)
        assertEquals(ChapterVisualAnalysisFailure.BASELINE_MISSING.name, analysis.failureCategory)
        assertNull(ChapterRevisionArchiveVisuals.auditOf(failed), "a failed analysis has no summary")

        // a not-required analysis is a different fact from a failed one, and stays legible
        val unnecessary =
            ChapterRevisionArchiveVisuals.analysisOf(
                state = ChapterVisualAnalysisState.NOT_REQUIRED,
                attempts = 0,
                completedAt = 12L,
                failure = null,
            )
        assertEquals(ChapterVisualAnalysisState.NOT_REQUIRED, unnecessary.state)
        assertEquals(0, unnecessary.attempts)
        assertNull(unnecessary.failureCategory)
    }

    @Test
    fun `the manifest visual audit round trips deterministically and names no local path`() {
        val key = "b".repeat(64)
        val revisionId = revision(key, "hash", visualState = ChapterVisualAnalysisState.FAILED)

        val manifest =
            ChapterRevisionArchiveManifest(
                schemaVersion = ChapterRevisionArchiveManifestCodec.SCHEMA_VERSION,
                revisionId = revisionId,
                candidateKey = key,
                seriesId = null,
                chapterId = null,
                sourceId = null,
                sourceMangaUrl = null,
                sourceChapterUrl = "https://example.invalid/analysis",
                chapterNumber = 1.0f,
                chapterTitle = "chapter",
                scanlator = null,
                memo = kotlinx.serialization.json.JsonObject(emptyMap()),
                visualAnalysis =
                    ChapterRevisionArchiveVisuals.analysisOf(
                        state = ChapterVisualAnalysisState.FAILED,
                        attempts = 3,
                        completedAt = 77L,
                        failure = ChapterVisualAnalysisFailure.BASELINE_UNREADABLE,
                    ),
                discoveredAt = 1,
                archivedAt = 2,
                pageCount = 0,
                pages = emptyList(),
                acquisitionContentHash = null,
                archiveContentHash = "hash",
                archiveSize = 10,
            )

        val encoded = ChapterRevisionArchiveManifestCodec.encode(manifest)
        val decoded = ChapterRevisionArchiveManifestCodec.decode(encoded)

        assertEquals(manifest.copy(visualAnalysis = decoded.visualAnalysis), decoded)
        assertEquals(
            ChapterRevisionArchiveManifestVisualAnalysis(
                ChapterVisualAnalysisState.FAILED,
                3,
                77L,
                "BASELINE_UNREADABLE",
            ),
            decoded.visualAnalysis,
        )
        // the archive is portable, so no part of it may name a location on the machine that wrote it
        val json = encoded.toString(Charsets.UTF_8)
        assertFalse(json.contains(ChapterRevisionThumbnails.ROOT_DIR_NAME))
        assertFalse(json.contains(stagingRoot.absolutePath))
        assertFalse(json.contains("thumbnail"))
        assertEquals(encoded.toString(Charsets.UTF_8), ChapterRevisionArchiveManifestCodec.encode(manifest).toString(Charsets.UTF_8))

        // a schema version 3 manifest has neither field and still decodes exactly as it was written
        val legacy =
            """
            {
              "schemaVersion": 3,
              "revisionId": 1,
              "candidateKey": "$key",
              "sourceChapterUrl": "https://example.invalid/legacy",
              "memo": {},
              "chapterNumber": 1.0,
              "chapterTitle": "chapter",
              "discoveredAt": 1,
              "archivedAt": 2,
              "pageCount": 0,
              "pages": [],
              "archiveContentHash": "hash",
              "archiveSize": 10
            }
            """.trimIndent()

        val legacyDecoded = ChapterRevisionArchiveManifestCodec.decode(legacy.toByteArray())
        assertNull(legacyDecoded.visualAnalysis)
        assertNull(legacyDecoded.visualComparison)

        // a version 2 manifest - the shape this archive wrote before the visual comparison existed -
        // is still read exactly as it was written
        val older =
            """
            {
              "schemaVersion": 2,
              "revisionId": 1,
              "candidateKey": "$key",
              "sourceChapterUrl": "https://example.invalid/older",
              "chapterNumber": 1.0,
              "chapterTitle": "chapter",
              "memo": {},
              "discoveryReason": "METADATA_CHANGE",
              "signalConfidence": "METADATA_HINT",
              "discoveredAt": 1,
              "archivedAt": 2,
              "pageCount": 0,
              "pages": [],
              "archiveContentHash": "hash",
              "archiveSize": 10
            }
            """.trimIndent()

        val olderDecoded = ChapterRevisionArchiveManifestCodec.decode(older.toByteArray())
        assertEquals(2, olderDecoded.schemaVersion)
        assertEquals(ChapterRevisionArchiveManifestCodec.SCHEMA_VERSION, 5)
        assertEquals(ChapterRevisionDiscoveryReason.METADATA_CHANGE, olderDecoded.discoveryReason)
        // a field v2 did not write keeps its default instead of failing the decode
        assertEquals(emptyList<ChapterRevisionMetadataField>(), olderDecoded.changedMetadataFields)
        assertNull(olderDecoded.seriesId)
        assertNull(olderDecoded.visualAnalysis)
        assertNull(olderDecoded.visualComparison)
    }
}
