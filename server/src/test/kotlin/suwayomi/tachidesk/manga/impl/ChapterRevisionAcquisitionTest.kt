package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterRevisionAcquisitionTest : ApplicationTest() {
    private val stagingRoot: File = File("build/tmp/chapter-revision-staging-${UUID.randomUUID()}")

    private fun processor(access: ChapterRevisionSourceAccess) =
        ChapterRevisionAcquisitionProcessor(sourceAccess = access, stagingRoot = { stagingRoot })

    /** A minimal PNG so the staged files pass the image type check. */
    private fun pngBytes(marker: Int): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24) { marker.toByte() }

    /** Returns null to simulate a source that can not serve the revision. */
    private class FakeSourceAccess(
        private val content: (ChapterRevisionDataClass) -> List<ByteArray>?,
    ) : ChapterRevisionSourceAccess {
        var pageListRequests = 0
            private set
        var pageRequests = 0
            private set

        override suspend fun getPageList(revision: ChapterRevisionDataClass): List<Page> {
            pageListRequests++
            val pages = content(revision) ?: throw ChapterRevisionSourceException("source unavailable")
            return pages.indices.map { Page(it) }
        }

        override suspend fun openPage(
            revision: ChapterRevisionDataClass,
            page: Page,
        ): InputStream {
            pageRequests++
            val pages = content(revision) ?: throw ChapterRevisionSourceException("source unavailable")
            return ByteArrayInputStream(pages[page.index])
        }
    }

    private fun createCandidate(
        title: String,
        policy: MangaAcquisitionPolicy,
        chapterCount: Int = 1,
    ): ChapterRevisionDataClass {
        val mangaId = createLibraryManga(title).also { createChapters(it, chapterCount, read = false) }

        return transaction {
            MangaTable.update({ MangaTable.id eq mangaId }) { it[acquisitionPolicy] = policy.name }

            val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
            val chapters =
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga eq mangaId }
                    .orderBy(ChapterTable.sourceOrder)
                    .map { ChapterTable.toDataClass(it) }

            ChapterRevision.createCandidatesForNewChapters(mangaEntry, chapters, DISCOVERED_AT)

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.manga eq mangaId }
                .first()
                .let { ChapterRevisionTable.toDataClass(it) }
        }
    }

    private fun acquire(
        access: ChapterRevisionSourceAccess,
        now: Long = 50,
    ): ChapterRevisionDataClass {
        val claimed = ChapterRevision.claimNextQueued(now = now)!!
        runBlocking { processor(access).process(claimed) }
        return ChapterRevision.getRevision(claimed.id)!!
    }

    @Test
    fun `claim marks the candidate as downloading exactly once`() {
        val candidate = createCandidate("ACQ_CLAIM", MangaAcquisitionPolicy.AUTO)

        val claimed = ChapterRevision.claimNextQueued(now = 50)

        assertNotNull(claimed)
        assertEquals(candidate.id, claimed!!.id)
        assertEquals(ChapterAcquisitionState.DOWNLOADING, claimed.acquisitionState)
        assertEquals(1, claimed.attempts)
        assertEquals(50L, claimed.lastAttemptAt)
        assertNull(claimed.lastError)

        assertNull(ChapterRevision.claimNextQueued(now = 60), "an in-flight candidate must not be claimed twice")
    }

    @Test
    fun `recovery requeues interrupted revisions and preserves attempts`() {
        val candidate = createCandidate("ACQ_RECOVERY", MangaAcquisitionPolicy.AUTO)
        ChapterRevision.claimNextQueued(now = 50)

        assertEquals(1, ChapterRevision.recoverInterrupted(now = 60))

        val recovered = ChapterRevision.getRevision(candidate.id)!!
        assertEquals(ChapterAcquisitionState.QUEUED, recovered.acquisitionState)
        assertEquals(1, recovered.attempts, "recovery must not reset the attempt count")
    }

    @Test
    fun `retry requeues failed revisions and preserves attempts`() {
        val candidate = createCandidate("ACQ_RETRY", MangaAcquisitionPolicy.AUTO)
        ChapterRevision.claimNextQueued(now = 50)
        ChapterRevision.markDownloadFailed(candidate.id, "boom", now = 60)

        var resetBeforeCommit = false
        val retried =
            ChapterRevision.retry(listOf(candidate.id), now = 70) { revisions ->
                assertEquals(ChapterAcquisitionState.QUEUED, revisions.single().acquisitionState)
                resetBeforeCommit = true
            }

        assertTrue(resetBeforeCommit, "staging reset must run before the retry transaction commits")
        assertEquals(listOf(candidate.id), retried.map { it.id })
        assertEquals(ChapterAcquisitionState.QUEUED, retried.single().acquisitionState)
        assertEquals(1, retried.single().attempts)
        assertTrue(
            ChapterRevision.retry(listOf(candidate.id), now = 80).isEmpty(),
            "a revision that is already queued is not retryable",
        )
    }

    @Test
    fun `acquisition stores a relative path and the ordered page count`() {
        val candidate = createCandidate("ACQ_COMPLETE", MangaAcquisitionPolicy.AUTO)
        val access = FakeSourceAccess { listOf(pngBytes(1), pngBytes(2)) }

        val completed = acquire(access)

        assertEquals(ChapterAcquisitionState.COMPLETE, completed.acquisitionState)
        assertEquals(2, completed.pageCount)
        assertNotNull(completed.contentHash)
        assertNull(completed.lastError)
        assertEquals(ChapterRevisionStaging.relativeDirectory(candidate.candidateKey), completed.candidatePath)
        assertFalse(completed.candidatePath!!.startsWith("/"), "the staged path must stay relative")
        assertTrue(File(stagingRoot, completed.candidatePath).isDirectory)
        assertFalse(
            File(stagingRoot, "${completed.candidatePath}.partial").exists(),
            "publishing renames the partial directory instead of leaving it behind",
        )
    }

    @Test
    fun `the content hash only depends on the ordered page content`() {
        val first = createCandidate("ACQ_HASH_A", MangaAcquisitionPolicy.AUTO)
        val second = createCandidate("ACQ_HASH_B", MangaAcquisitionPolicy.AUTO)
        val pages = listOf(pngBytes(3), pngBytes(4))
        val access = FakeSourceAccess { pages }

        val firstCompleted = acquire(access, now = 50)
        val secondCompleted = acquire(access, now = 51)

        assertEquals(first.id, firstCompleted.id)
        assertEquals(ChapterAcquisitionState.COMPLETE, firstCompleted.acquisitionState)
        assertEquals(ChapterAcquisitionState.COMPLETE, secondCompleted.acquisitionState)
        assertEquals(firstCompleted.contentHash, secondCompleted.contentHash, "identical pages hash identically")
        assertNotEquals(firstCompleted.candidatePath, secondCompleted.candidatePath, "each candidate stages separately")
        assertEquals(4, access.pageRequests, "both pages of both candidates are fetched")
    }

    @Test
    fun `a finalized directory is completed without downloading the pages again`() {
        val candidate = createCandidate("ACQ_RESUME", MangaAcquisitionPolicy.AUTO)
        val directory = ChapterRevisionStaging.directory(stagingRoot, candidate.candidateKey).apply { mkdirs() }
        ChapterRevisionStaging.writePage(directory, 0, ByteArrayInputStream(pngBytes(7)))
        ChapterRevisionStaging.writePage(directory, 1, ByteArrayInputStream(pngBytes(8)))

        val access = FakeSourceAccess { fail("a finalized directory must not be downloaded again") }

        val completed = acquire(access)

        assertEquals(ChapterAcquisitionState.COMPLETE, completed.acquisitionState)
        assertEquals(2, completed.pageCount)
        assertEquals(0, access.pageListRequests)
        assertEquals(0, access.pageRequests)
    }

    @Test
    fun `an interrupted attempt resumes with the pages it already downloaded`() {
        val candidate = createCandidate("ACQ_PARTIAL", MangaAcquisitionPolicy.AUTO)
        val partialDirectory = ChapterRevisionStaging.partialDirectory(stagingRoot, candidate.candidateKey).apply { mkdirs() }
        ChapterRevisionStaging.writePage(partialDirectory, 0, ByteArrayInputStream(pngBytes(11)))

        val access = FakeSourceAccess { listOf(pngBytes(11), pngBytes(12)) }

        val completed = acquire(access)

        assertEquals(ChapterAcquisitionState.COMPLETE, completed.acquisitionState)
        assertEquals(2, completed.pageCount)
        assertEquals(1, access.pageRequests, "only the missing page is fetched again")
    }

    @Test
    fun `an interrupted page write is downloaded again instead of being reused`() {
        val candidate = createCandidate("ACQ_TMP", MangaAcquisitionPolicy.AUTO)
        val partialDirectory =
            ChapterRevisionStaging.partialDirectory(stagingRoot, candidate.candidateKey).apply { mkdirs() }
        File(partialDirectory, "${ChapterRevisionStaging.pageFileName(0)}.tmp").writeText("truncated")

        val access = FakeSourceAccess { listOf(pngBytes(21)) }

        val completed = acquire(access)

        assertEquals(ChapterAcquisitionState.COMPLETE, completed.acquisitionState)
        assertEquals(1, completed.pageCount)
        assertEquals(1, access.pageRequests, "a truncated page must be fetched again")
    }

    @Test
    fun `a failing candidate does not stop the queue`() {
        val failing = createCandidate("ACQ_FAIL", MangaAcquisitionPolicy.AUTO)
        val succeeding = createCandidate("ACQ_OK", MangaAcquisitionPolicy.AUTO)
        val access =
            FakeSourceAccess { revision ->
                if (revision.id == failing.id) null else listOf(pngBytes(9))
            }
        val loop = ChapterRevisionAcquisitionLoop(processor(access))

        runBlocking {
            assertTrue(loop.drainOnce())
            assertTrue(loop.drainOnce())
            assertFalse(loop.drainOnce())
        }

        assertEquals(ChapterAcquisitionState.DOWNLOAD_FAILED, ChapterRevision.getRevision(failing.id)!!.acquisitionState)
        assertEquals(ChapterAcquisitionState.COMPLETE, ChapterRevision.getRevision(succeeding.id)!!.acquisitionState)
    }

    @Test
    fun `an empty chapter and an unusable page fail validation`() {
        val empty = createCandidate("ACQ_EMPTY", MangaAcquisitionPolicy.AUTO)
        val broken = createCandidate("ACQ_BROKEN", MangaAcquisitionPolicy.AUTO)

        val emptyCompleted = acquire(FakeSourceAccess { emptyList() }, now = 50)
        val brokenCompleted = acquire(FakeSourceAccess { listOf(pngBytes(1), ByteArray(0)) }, now = 51)

        assertEquals(empty.id, emptyCompleted.id)
        assertEquals(ChapterAcquisitionState.VALIDATION_FAILED, emptyCompleted.acquisitionState)
        assertEquals(broken.id, brokenCompleted.id)
        assertEquals(ChapterAcquisitionState.VALIDATION_FAILED, brokenCompleted.acquisitionState)
        assertNotNull(brokenCompleted.lastError)
    }

    @Test
    fun `validation rejects missing, leftover and unexpected pages`() {
        val directory = File(stagingRoot, "validation-${UUID.randomUUID()}").apply { mkdirs() }

        assertTrue(
            ChapterRevisionStaging.validate(directory, null) is ChapterRevisionValidation.Invalid,
            "a directory without pages is not a chapter",
        )

        ChapterRevisionStaging.writePage(directory, 0, ByteArrayInputStream(pngBytes(1)))
        ChapterRevisionStaging.writePage(directory, 1, ByteArrayInputStream(pngBytes(2)))
        val valid = ChapterRevisionStaging.validate(directory, 2)

        assertTrue(valid is ChapterRevisionValidation.Valid)
        assertEquals(2, (valid as ChapterRevisionValidation.Valid).pageCount)
        assertTrue(ChapterRevisionStaging.validate(directory, 3) is ChapterRevisionValidation.Invalid)

        File(directory, "00003.tmp").writeText("leftover")
        assertTrue(ChapterRevisionStaging.validate(directory, 2) is ChapterRevisionValidation.Invalid)
    }

    @Test
    fun `an invalid candidate key can not escape the staging root`() {
        val escaped = runCatching { ChapterRevisionStaging.relativeDirectory("../../etc/passwd") }

        assertTrue(escaped.isFailure, "a path traversal candidate key must be rejected")
    }

    @Test
    fun `the staging root defaults to the data root and honours a configured path`() {
        val applicationDirs = ApplicationDirs()
        val configured = File("build/tmp/staging-config-${UUID.randomUUID()}").apply { mkdirs() }.absolutePath

        try {
            serverConfig.archiveStagingPath.value = ""
            assertEquals("${applicationDirs.dataRoot}/staging", applicationDirs.archiveStagingRoot)

            serverConfig.archiveStagingPath.value = configured
            assertEquals(configured, applicationDirs.archiveStagingRoot)
        } finally {
            serverConfig.archiveStagingPath.value = ""
        }
    }

    @AfterEach
    internal fun tearDown() {
        stagingRoot.deleteRecursively()
        clearTables(ChapterRevisionTable, ChapterTable, MangaTable)
    }

    private companion object {
        const val DISCOVERED_AT = 1_000L
    }
}
