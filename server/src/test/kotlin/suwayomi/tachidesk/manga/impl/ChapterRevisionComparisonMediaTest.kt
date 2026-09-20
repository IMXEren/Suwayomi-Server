package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonState
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisState
import suwayomi.tachidesk.manga.model.table.ChapterRevisionComparisonPageTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionComparisonTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory

/**
 * The review previews of a comparison.
 *
 * Everything here is about what one request can reach: which revision and row it names, which stored
 * location that row is allowed to point at, and what is served when the stored bytes are not the ones
 * that were recorded. A page and a thumbnail are checked differently on purpose - a thumbnail is
 * re-hashed, a page is addressed by the index the alignment recorded and guarded by the size of the
 * artifact it came from.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterRevisionComparisonMediaTest : ApplicationTest() {
    private val chapterKey = "a".repeat(64)
    private val candidateKey = "b".repeat(64)
    private val baselineKey = "c".repeat(64)

    private lateinit var stagingRoot: File
    private lateinit var archiveRoot: File

    @BeforeEach
    fun setup() {
        stagingRoot = createTempDirectory("comparison-media-staging").toFile()
        archiveRoot = createTempDirectory("comparison-media-archive").toFile()
    }

    @AfterEach
    fun cleanup() {
        clearTables(ChapterRevisionComparisonPageTable, ChapterRevisionComparisonTable, ChapterRevisionTable)
        stagingRoot.deleteRecursively()
        archiveRoot.deleteRecursively()
    }

    private fun resolver() = ChapterRevisionComparisonMediaResolver(stagingRoot = { stagingRoot }, archiveRoot = { archiveRoot })

    // ---------------------------------------------------------------- fixtures

    private fun revision(
        candidateKey: String,
        candidatePath: String? = null,
        archiveCbzPath: String? = null,
        archiveCbzSize: Long? = null,
        activeCbzPath: String? = null,
        activeCbzSize: Long? = null,
    ): Int =
        transaction {
            ChapterRevisionTable.insert {
                it[ChapterRevisionTable.candidateKey] = candidateKey
                it[ChapterRevisionTable.chapterKey] = this@ChapterRevisionComparisonMediaTest.chapterKey
                it[sourceChapterUrl] = "https://example.invalid/$candidateKey"
                it[name] = "chapter $candidateKey"
                it[discoveredAt] = 1
                it[updatedAt] = 1
                it[acquisitionState] = ChapterAcquisitionState.COMPLETE.name
                it[archiveState] = ChapterArchiveState.NOT_COMMITTED.name
                it[contentHash] = candidateKey
                it[pageCount] = 3
                it[comparisonState] = ChapterRevisionComparisonState.CONTENT_CHANGED.name
                it[visualAnalysisState] = ChapterVisualAnalysisState.COMPLETE.name
                it[visualAnalysisAttempts] = 0
                it[ChapterRevisionTable.candidatePath] = candidatePath
                it[ChapterRevisionTable.archiveCbzPath] = archiveCbzPath
                it[ChapterRevisionTable.archiveCbzSize] = archiveCbzSize
                it[ChapterRevisionTable.activeCbzPath] = activeCbzPath
                it[ChapterRevisionTable.activeCbzSize] = activeCbzSize
            } get ChapterRevisionTable.id
        }.value

    private fun comparison(
        revisionId: Int,
        baselineRevisionId: Int?,
    ): Int =
        transaction {
            ChapterRevisionComparisonTable.insert {
                it[revision] = revisionId
                it[baselineRevision] = baselineRevisionId
                it[baselinePageCount] = 3
                it[candidatePageCount] = 3
                it[exactCount] = 0
                it[visuallyEquivalentCount] = 0
                it[modifiedCount] = 1
                it[addedCount] = 0
                it[removedCount] = 0
                it[alignedCount] = 1
                it[hammingThreshold] = 2
                it[algorithmVersion] = "dhash128-v1"
                it[allPagesVisuallyEquivalent] = false
                it[hasLimitations] = false
                it[limitations] = null
                it[createdAt] = 10
                it[updatedAt] = 10
            } get ChapterRevisionComparisonTable.id
        }.value

    private fun alignedRow(
        comparisonId: Int,
        revisionId: Int,
        ordinal: Int = 0,
        baselinePageIndex: Int? = null,
        candidatePageIndex: Int? = null,
        baselineThumbnail: StoredThumbnail? = null,
        candidateThumbnail: StoredThumbnail? = null,
    ) {
        transaction {
            ChapterRevisionComparisonPageTable.insert {
                it[comparison] = comparisonId
                it[revision] = revisionId
                it[ChapterRevisionComparisonPageTable.ordinal] = ordinal
                it[ChapterRevisionComparisonPageTable.baselinePageIndex] = baselinePageIndex
                it[ChapterRevisionComparisonPageTable.candidatePageIndex] = candidatePageIndex
                it[createdAt] = 10
                it[baselineThumbnailRelativePath] = baselineThumbnail?.relativePath
                it[baselineThumbnailSha256] = baselineThumbnail?.sha256
                it[baselineThumbnailSize] = baselineThumbnail?.size
                it[candidateThumbnailRelativePath] = candidateThumbnail?.relativePath
                it[candidateThumbnailSha256] = candidateThumbnail?.sha256
                it[candidateThumbnailSize] = candidateThumbnail?.size
            }
        }
    }

    private data class StoredThumbnail(
        val relativePath: String,
        val sha256: String,
        val size: Long,
    )

    private fun storedThumbnail(relativePath: String): StoredThumbnail {
        val file = File(stagingRoot, relativePath)
        val digest = ChapterRevisionArchiveArtifacts.digestOf(file)
        return StoredThumbnail(relativePath, digest.sha256, digest.size)
    }

    private fun writeImage(
        file: File,
        format: String,
        color: Int,
    ) {
        file.parentFile.mkdirs()
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 4) {
            for (y in 0 until 4) {
                image.setRGB(x, y, color)
            }
        }
        ImageIO.write(image, format, file)
    }

    private fun writeCbz(
        file: File,
        entries: List<Pair<String, ByteArray>>,
    ) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun imageBytes(
        format: String,
        color: Int,
    ): ByteArray {
        val file = File(stagingRoot, "fixture-$format-$color.img")
        writeImage(file, format, color)
        return file.readBytes()
    }

    private fun bytesOf(media: ChapterRevisionComparisonMedia): ByteArray = media.stream().use { it.readBytes() }

    // ---------------------------------------------------------------- thumbnails

    @Test
    fun `a thumbnail is served when its bytes still match what was recorded`() {
        val revisionId = revision(candidateKey)
        val comparisonId = comparison(revisionId, null)
        val relativePath = ChapterRevisionThumbnails.relativePath(candidateKey, revisionId, 1, 1, ChapterRevisionThumbnailSide.BASELINE)
        writeImage(File(stagingRoot, relativePath), "jpg", 0x112233)
        val stored = storedThumbnail(relativePath)
        alignedRow(comparisonId, revisionId, ordinal = 1, baselineThumbnail = stored)

        val resolution = resolver().thumbnail(revisionId, 1, ChapterRevisionThumbnailSide.BASELINE)

        val served = resolution as ChapterRevisionMediaResolution.Served
        assertEquals("image/jpeg", served.media.contentType)
        assertEquals(stored.size, served.media.contentLength)
        assertArrayEquals(File(stagingRoot, relativePath).readBytes(), bytesOf(served.media))
    }

    @Test
    fun `a thumbnail whose bytes no longer hash to what was recorded is refused`() {
        val revisionId = revision(candidateKey)
        val comparisonId = comparison(revisionId, null)
        val relativePath = ChapterRevisionThumbnails.relativePath(candidateKey, revisionId, 1, 1, ChapterRevisionThumbnailSide.BASELINE)
        writeImage(File(stagingRoot, relativePath), "jpg", 0x112233)
        val digest = ChapterRevisionArchiveArtifacts.digestOf(File(stagingRoot, relativePath))
        // the size still agrees, so only the digest can tell that the file is not the preview that was stored
        alignedRow(
            comparisonId,
            revisionId,
            ordinal = 1,
            baselineThumbnail = StoredThumbnail(relativePath, "0".repeat(64), digest.size),
        )

        assertEquals(
            ChapterRevisionMediaResolution.Corrupt,
            resolver().thumbnail(revisionId, 1, ChapterRevisionThumbnailSide.BASELINE),
        )
    }

    @Test
    fun `a thumbnail whose size no longer agrees is refused`() {
        val revisionId = revision(candidateKey)
        val comparisonId = comparison(revisionId, null)
        val relativePath = ChapterRevisionThumbnails.relativePath(candidateKey, revisionId, 1, 1, ChapterRevisionThumbnailSide.CANDIDATE)
        writeImage(File(stagingRoot, relativePath), "jpg", 0x445566)
        val digest = ChapterRevisionArchiveArtifacts.digestOf(File(stagingRoot, relativePath))
        alignedRow(
            comparisonId,
            revisionId,
            ordinal = 1,
            candidateThumbnail = StoredThumbnail(relativePath, digest.sha256, digest.size + 1),
        )

        assertEquals(
            ChapterRevisionMediaResolution.Corrupt,
            resolver().thumbnail(revisionId, 1, ChapterRevisionThumbnailSide.CANDIDATE),
        )
    }

    @Test
    fun `a stored location that is not the shape the thumbnail writer produces is refused`() {
        val revisionId = revision(candidateKey)
        val comparisonId = comparison(revisionId, null)
        val relativePath = ChapterRevisionThumbnails.relativePath(candidateKey, revisionId, 1, 1, ChapterRevisionThumbnailSide.BASELINE)
        writeImage(File(stagingRoot, relativePath), "jpg", 0x112233)
        val digest = ChapterRevisionArchiveArtifacts.digestOf(File(stagingRoot, relativePath))

        // a traversal, a foreign candidate key, a foreign revision and a foreign extension are all
        // simply not the shape this server writes, so none of them can be addressed
        val refused =
            listOf(
                "../../../../etc/passwd",
                "revision-comparisons/${"e".repeat(64)}/$revisionId-1/000001-baseline.jpg",
                "revision-comparisons/$candidateKey/${revisionId + 1}-1/000001-baseline.jpg",
                "revision-comparisons/$candidateKey/$revisionId-1/000001-candidate.jpg",
                "revision-comparisons/$candidateKey/$revisionId-1/000002-baseline.jpg",
                "revision-comparisons/$candidateKey/$revisionId-0/000001-baseline.jpg",
                "$relativePath/../../$candidateKey",
            )

        refused.forEach { stored ->
            clearTables(ChapterRevisionComparisonPageTable)
            alignedRow(
                comparisonId,
                revisionId,
                ordinal = 1,
                baselineThumbnail = StoredThumbnail(stored, digest.sha256, digest.size),
            )
            assertEquals(
                ChapterRevisionMediaResolution.NotFound,
                resolver().thumbnail(revisionId, 1, ChapterRevisionThumbnailSide.BASELINE),
                "a stored location must be the exact shape the thumbnail writer produces: $stored",
            )
        }
    }

    @Test
    fun `a row that records a location without its digest is never served`() {
        val revisionId = revision(candidateKey)
        val comparisonId = comparison(revisionId, null)
        val relativePath = ChapterRevisionThumbnails.relativePath(candidateKey, revisionId, 1, 1, ChapterRevisionThumbnailSide.BASELINE)
        writeImage(File(stagingRoot, relativePath), "jpg", 0x112233)
        alignedRow(
            comparisonId,
            revisionId,
            ordinal = 1,
            baselineThumbnail = StoredThumbnail(relativePath, "0".repeat(64), 0),
        )

        // a zero size can never match a real file, so the row is refused rather than served unverified
        assertEquals(
            ChapterRevisionMediaResolution.Corrupt,
            resolver().thumbnail(revisionId, 1, ChapterRevisionThumbnailSide.BASELINE),
        )
    }

    @Test
    fun `a row of another revision is never reachable through this revision's address`() {
        val otherRevisionId = revision(candidateKey)
        val otherComparisonId = comparison(otherRevisionId, null)
        val otherPath = ChapterRevisionThumbnails.relativePath(candidateKey, otherRevisionId, 1, 1, ChapterRevisionThumbnailSide.BASELINE)
        writeImage(File(stagingRoot, otherPath), "jpg", 0x112233)
        alignedRow(otherComparisonId, otherRevisionId, ordinal = 1, baselineThumbnail = storedThumbnail(otherPath))

        val revisionId = revision(baselineKey)
        val comparisonId = comparison(revisionId, null)
        alignedRow(comparisonId, revisionId, ordinal = 1)

        // the comparison of this revision exists, the row exists, but it has no preview of its own
        assertEquals(
            ChapterRevisionMediaResolution.NotFound,
            resolver().thumbnail(revisionId, 1, ChapterRevisionThumbnailSide.BASELINE),
        )
        // and an ordinal the comparison does not have is not addressable either
        assertEquals(
            ChapterRevisionMediaResolution.NotFound,
            resolver().thumbnail(otherRevisionId, 7, ChapterRevisionThumbnailSide.BASELINE),
        )
        // a revision with no comparison at all has no rows to address
        assertEquals(
            ChapterRevisionMediaResolution.NotFound,
            resolver().thumbnail(Int.MAX_VALUE, 1, ChapterRevisionThumbnailSide.BASELINE),
        )
    }

    // ---------------------------------------------------------------- candidate pages

    @Test
    fun `a candidate page is served from the staged files while they exist`() {
        val directory = File(stagingRoot, ChapterRevisionStaging.relativeDirectory(candidateKey))
        writeImage(File(directory, "00001.png"), "png", 0x00FF00)
        writeImage(File(directory, "00002.png"), "png", 0x0000FF)

        val revisionId = revision(candidateKey, candidatePath = ChapterRevisionStaging.relativeDirectory(candidateKey))
        val comparisonId = comparison(revisionId, null)
        alignedRow(comparisonId, revisionId, candidatePageIndex = 1)

        val served = resolver().page(revisionId, 0, ChapterRevisionThumbnailSide.CANDIDATE) as ChapterRevisionMediaResolution.Served

        assertEquals("image/png", served.media.contentType)
        assertEquals(File(directory, "00002.png").length(), served.media.contentLength)
        assertArrayEquals(File(directory, "00002.png").readBytes(), bytesOf(served.media))
    }

    @Test
    fun `a candidate page falls back to its immutable archive once the staged files are gone`() {
        val relativeCbzPath = ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey)
        val cbz = File(archiveRoot, relativeCbzPath)
        val first = imageBytes("png", 0x111111)
        val second = imageBytes("png", 0x222222)
        writeCbz(cbz, listOf("00001.png" to first, "00002.png" to second))

        val revisionId =
            revision(
                candidateKey,
                // the staging directory was cleaned up after the archive was confirmed
                candidatePath = ChapterRevisionStaging.relativeDirectory(candidateKey),
                archiveCbzPath = relativeCbzPath,
                archiveCbzSize = cbz.length(),
            )
        val comparisonId = comparison(revisionId, null)
        alignedRow(comparisonId, revisionId, candidatePageIndex = 1)

        val served = resolver().page(revisionId, 0, ChapterRevisionThumbnailSide.CANDIDATE) as ChapterRevisionMediaResolution.Served

        assertEquals("image/png", served.media.contentType)
        // an archive entry only declares its size, so a page read out of one claims no length at all
        assertNull(served.media.contentLength)
        assertArrayEquals(second, bytesOf(served.media))
    }

    @Test
    fun `a baseline page is served from the immutable archive of the baseline revision`() {
        val baselineCbzPath = ChapterRevisionArchiveArtifacts.relativeCbzPath(baselineKey)
        val cbz = File(archiveRoot, baselineCbzPath)
        val pageBytes = imageBytes("jpg", 0x334455)
        writeCbz(cbz, listOf("00001.jpg" to pageBytes))

        val baselineRevisionId = revision(baselineKey, archiveCbzPath = baselineCbzPath, archiveCbzSize = cbz.length())
        val revisionId = revision(candidateKey)
        val comparisonId = comparison(revisionId, baselineRevisionId)
        alignedRow(comparisonId, revisionId, baselinePageIndex = 0)

        val served = resolver().page(revisionId, 0, ChapterRevisionThumbnailSide.BASELINE) as ChapterRevisionMediaResolution.Served

        assertEquals("image/jpeg", served.media.contentType)
        assertArrayEquals(pageBytes, bytesOf(served.media))
    }

    @Test
    fun `a baseline with no immutable copy is served from the active publication copy`() {
        val activeCbzPath = ChapterRevisionLibrary.relativeCbzPath(mangaId = 1, chapterKey = chapterKey)
        val cbz = File(archiveRoot, activeCbzPath)
        val pageBytes = imageBytes("png", 0x556677)
        writeCbz(cbz, listOf("00001.png" to pageBytes))

        val baselineRevisionId = revision(baselineKey, activeCbzPath = activeCbzPath, activeCbzSize = cbz.length())
        val revisionId = revision(candidateKey)
        val comparisonId = comparison(revisionId, baselineRevisionId)
        alignedRow(comparisonId, revisionId, baselinePageIndex = 0)

        val served = resolver().page(revisionId, 0, ChapterRevisionThumbnailSide.BASELINE) as ChapterRevisionMediaResolution.Served

        assertArrayEquals(pageBytes, bytesOf(served.media))
    }

    @Test
    fun `a page of a side the row does not have is not addressable`() {
        val baselineCbzPath = ChapterRevisionArchiveArtifacts.relativeCbzPath(baselineKey)
        val cbz = File(archiveRoot, baselineCbzPath)
        val pageBytes = imageBytes("png", 0x334455)
        writeCbz(cbz, listOf("00001.png" to pageBytes))

        val baselineRevisionId = revision(baselineKey, archiveCbzPath = baselineCbzPath, archiveCbzSize = cbz.length())
        val revisionId = revision(candidateKey)
        val comparisonId = comparison(revisionId, baselineRevisionId)
        // a removed page exists only on the baseline
        alignedRow(comparisonId, revisionId, baselinePageIndex = 0)

        assertEquals(
            ChapterRevisionMediaResolution.NotFound,
            resolver().page(revisionId, 0, ChapterRevisionThumbnailSide.CANDIDATE),
        )
        // the ordinal itself has to be one of this comparison's rows
        assertEquals(
            ChapterRevisionMediaResolution.NotFound,
            resolver().page(revisionId, 4, ChapterRevisionThumbnailSide.BASELINE),
        )
        // an index beyond the artifact's pages is not addressable
        clearTables(ChapterRevisionComparisonPageTable)
        alignedRow(comparisonId, revisionId, baselinePageIndex = 9)
        assertEquals(
            ChapterRevisionMediaResolution.NotFound,
            resolver().page(revisionId, 0, ChapterRevisionThumbnailSide.BASELINE),
        )
    }

    // ---------------------------------------------------------------- integrity

    @Test
    fun `an archive entry that is not a plain root file name is refused`() {
        val relativeCbzPath = ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey)
        val cbz = File(archiveRoot, relativeCbzPath)
        val pageBytes = imageBytes("png", 0x778899)
        writeCbz(cbz, listOf("../escape.png" to pageBytes, "00001.png" to pageBytes))

        val revisionId = revision(candidateKey, archiveCbzPath = relativeCbzPath, archiveCbzSize = cbz.length())
        val comparisonId = comparison(revisionId, null)
        alignedRow(comparisonId, revisionId, candidatePageIndex = 1)

        // the whole archive is refused rather than the unsafe entry being skipped or extracted
        assertEquals(
            ChapterRevisionMediaResolution.NotFound,
            resolver().page(revisionId, 0, ChapterRevisionThumbnailSide.CANDIDATE),
        )
    }

    @Test
    fun `an archive that is not the size that was recorded is refused`() {
        val relativeCbzPath = ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey)
        val cbz = File(archiveRoot, relativeCbzPath)
        writeCbz(cbz, listOf("00001.png" to imageBytes("png", 0x101010)))

        val revisionId = revision(candidateKey, archiveCbzPath = relativeCbzPath, archiveCbzSize = cbz.length() + 5)
        val comparisonId = comparison(revisionId, null)
        alignedRow(comparisonId, revisionId, candidatePageIndex = 0)

        assertEquals(
            ChapterRevisionMediaResolution.Corrupt,
            resolver().page(revisionId, 0, ChapterRevisionThumbnailSide.CANDIDATE),
        )
    }

    @Test
    fun `an absent archive is missing rather than corrupt`() {
        val relativeCbzPath = ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey)
        val revisionId = revision(candidateKey, archiveCbzPath = relativeCbzPath, archiveCbzSize = 1234)
        val comparisonId = comparison(revisionId, null)
        alignedRow(comparisonId, revisionId, candidatePageIndex = 0)

        assertEquals(
            ChapterRevisionMediaResolution.NotFound,
            resolver().page(revisionId, 0, ChapterRevisionThumbnailSide.CANDIDATE),
        )
    }

    @Test
    fun `a staged page that is not an image is refused rather than sent`() {
        val directory = File(stagingRoot, ChapterRevisionStaging.relativeDirectory(candidateKey))
        directory.mkdirs()
        File(directory, "00001.png").writeBytes(ByteArray(64) { 0x41 })

        val revisionId = revision(candidateKey, candidatePath = ChapterRevisionStaging.relativeDirectory(candidateKey))
        val comparisonId = comparison(revisionId, null)
        alignedRow(comparisonId, revisionId, candidatePageIndex = 0)

        assertEquals(
            ChapterRevisionMediaResolution.Corrupt,
            resolver().page(revisionId, 0, ChapterRevisionThumbnailSide.CANDIDATE),
        )
    }

    @Test
    fun `reading a page to its end releases the archive it owns`() {
        val relativeCbzPath = ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey)
        val cbz = File(archiveRoot, relativeCbzPath)
        val pageBytes = imageBytes("png", 0x202020)
        writeCbz(cbz, listOf("00001.png" to pageBytes))

        val revisionId = revision(candidateKey, archiveCbzPath = relativeCbzPath, archiveCbzSize = cbz.length())
        val comparisonId = comparison(revisionId, null)
        alignedRow(comparisonId, revisionId, candidatePageIndex = 0)

        val served = resolver().page(revisionId, 0, ChapterRevisionThumbnailSide.CANDIDATE) as ChapterRevisionMediaResolution.Served
        val stream = served.media.stream()

        assertArrayEquals(pageBytes, stream.readBytes())
        // a container may close a stream the reader already closed, so closing twice must be harmless
        served.media.close()
        assertThrows(IOException::class.java) { stream.read() }

        if (File.separatorChar == '\\') {
            // Windows refuses to delete a file that is still open, so this is a real proof that reading
            // the page to its end released the archive
            assertTrue(cbz.delete(), "the archive must be released once the page has been read")
        }
    }

    @Test
    fun `the addresses of a comparison are opaque and built from its identity`() {
        assertEquals(
            "/api/v1/archive/revisions/7/comparison/3/baseline/thumbnail",
            ChapterRevisionComparisonMediaRoutes.thumbnailUrl(7, 3, ChapterRevisionThumbnailSide.BASELINE),
        )
        assertEquals(
            "/api/v1/archive/revisions/7/comparison/3/candidate/page",
            ChapterRevisionComparisonMediaRoutes.pageUrl(7, 3, ChapterRevisionThumbnailSide.CANDIDATE),
        )
        val url = ChapterRevisionComparisonMediaRoutes.thumbnailUrl(7, 3, ChapterRevisionThumbnailSide.BASELINE)
        // the address a client is handed names an identity and nothing else: neither the comparison root
        // nor the candidate staging root appears in it
        listOf(ChapterRevisionThumbnails.ROOT_DIR_NAME, ChapterRevisionStaging.ROOT_DIR_NAME).forEach { root ->
            assertTrue(!url.contains(root), "the address must not name a storage location: $root")
        }
        assertTrue(
            Regex("^/api/v1/archive/revisions/\\d+/comparison/\\d+/(baseline|candidate)/(thumbnail|page)$")
                .matches(url),
            "the address must be nothing but a revision, a row and a side: $url",
        )
    }

    // ---------------------------------------------------------------- bounds

    @Test
    fun `a page that streams more bytes than the serving limit is refused and releases its archive`() {
        val relativeCbzPath = ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey)
        val cbz = File(archiveRoot, relativeCbzPath)
        writeCbz(cbz, listOf("00001.png" to imageBytes("png", 0x010203)))

        // the entry is a real one, but what it streams is not: how many bytes a page has is declared by the
        // archive itself, so the bound has to be counted on the bytes that are actually read
        val zip = ZipFile(cbz)
        val stream = ChapterArchivePageStream(zip, EndlessStream(0x41), limit = 8)

        assertEquals(8, stream.read(ByteArray(8), 0, 8))
        assertThrows(IOException::class.java) { stream.read() }
        assertThrows(IOException::class.java) { stream.read(ByteArray(4), 0, 4) }

        if (File.separatorChar == '\\') {
            // Windows refuses to delete a file that is still open, so this is what proves the archive was
            // released at the moment the bound was crossed
            assertTrue(cbz.delete(), "the archive must be released when the bound is crossed")
        }
    }

    @Test
    fun `a served page begins at its own first byte even though its type is detected first`() {
        val pageBytes = imageBytes("png", 0x0A0B0C)
        val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        // an archived page: the type is detected on the buffered stream the response is written from, so the
        // peek must leave the response starting at the page's own first byte
        val relativeCbzPath = ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey)
        val cbz = File(archiveRoot, relativeCbzPath)
        writeCbz(cbz, listOf("00001.png" to pageBytes))

        val archivedRevision = revision(candidateKey, archiveCbzPath = relativeCbzPath, archiveCbzSize = cbz.length())
        alignedRow(comparison(archivedRevision, null), archivedRevision, candidatePageIndex = 0)

        val archived = resolver().page(archivedRevision, 0, ChapterRevisionThumbnailSide.CANDIDATE) as ChapterRevisionMediaResolution.Served
        val archivedBytes = bytesOf(archived.media)
        assertArrayEquals(pageBytes, archivedBytes)
        assertArrayEquals(pngSignature, archivedBytes.copyOf(8))

        // a staged page: its type is detected on a stream of its own, so the served stream is untouched
        val relativeDirectory = ChapterRevisionStaging.relativeDirectory(baselineKey)
        val stagedPage = File(File(stagingRoot, relativeDirectory), "00001.png")
        writeImage(stagedPage, "png", 0x0A0B0C)
        val stagedBytes = stagedPage.readBytes()

        val stagedRevision = revision(baselineKey, candidatePath = relativeDirectory)
        alignedRow(comparison(stagedRevision, null), stagedRevision, candidatePageIndex = 0)

        val staged = resolver().page(stagedRevision, 0, ChapterRevisionThumbnailSide.CANDIDATE) as ChapterRevisionMediaResolution.Served
        val servedBytes = bytesOf(staged.media)
        assertArrayEquals(stagedBytes, servedBytes)
        assertArrayEquals(pngSignature, servedBytes.copyOf(8))
    }

    @Test
    fun `a baseline page is not addressable once the baseline revision is gone`() {
        val relativeCbzPath = ChapterRevisionArchiveArtifacts.relativeCbzPath(baselineKey)
        val cbz = File(archiveRoot, relativeCbzPath)
        writeCbz(cbz, listOf("00001.png" to imageBytes("png", 0x445566)))

        val revisionId = revision(candidateKey)
        // the summary still records the baseline's page index, but the baseline revision itself is gone
        val comparisonId = comparison(revisionId, null)
        alignedRow(comparisonId, revisionId, baselinePageIndex = 0)

        assertEquals(
            ChapterRevisionMediaResolution.NotFound,
            resolver().page(revisionId, 0, ChapterRevisionThumbnailSide.BASELINE),
        )
    }

    /** A stream that never ends and never allocates, which is the most a forged entry can do. */
    private class EndlessStream(
        private val byte: Int,
    ) : InputStream() {
        override fun read(): Int = byte

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) {
                return 0
            }
            bytes.fill(byte.toByte(), offset, offset + length)
            return length
        }
    }
}
