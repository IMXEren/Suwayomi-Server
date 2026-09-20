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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterRevisionArchiveTest : ApplicationTest() {
    private val stagingRoot: File = File("build/tmp/chapter-revision-archive-staging-${UUID.randomUUID()}")
    private val archiveRoot: File = File("build/tmp/chapter-revision-archive-root-${UUID.randomUUID()}")

    /** A minimal PNG so the staged files pass the image type check. */
    private fun pngBytes(marker: Int): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24) { marker.toByte() }

    private class FakeSourceAccess(
        private val content: List<ByteArray>,
    ) : ChapterRevisionSourceAccess {
        override suspend fun getPageList(revision: ChapterRevisionDataClass): List<Page> = content.indices.map { Page(it) }

        override suspend fun openPage(
            revision: ChapterRevisionDataClass,
            page: Page,
        ): InputStream = ByteArrayInputStream(content[page.index])
    }

    private fun archiveProcessor(onVerificationDue: () -> Unit = {}) =
        ChapterRevisionArchiveProcessor(
            stagingRoot = { stagingRoot },
            archiveRoot = { archiveRoot },
            onVerificationDue = onVerificationDue,
        )

    private fun archiveLoop(onVerificationDue: () -> Unit = {}) = ChapterRevisionArchiveLoop(archiveProcessor(onVerificationDue))

    /** Creates and fully acquires one revision with two staged pages. */
    private fun acquiredCandidate(
        title: String,
        pages: List<ByteArray> = listOf(pngBytes(1), pngBytes(2)),
    ): ChapterRevisionDataClass {
        val mangaId = createLibraryManga(title).also { createChapters(it, 1, read = false) }

        val candidate =
            transaction {
                MangaTable.update({ MangaTable.id eq mangaId }) { it[acquisitionPolicy] = MangaAcquisitionPolicy.AUTO.name }

                val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
                val chapters =
                    ChapterTable
                        .selectAll()
                        .where { ChapterTable.manga eq mangaId }
                        .map { ChapterTable.toDataClass(it) }

                ChapterRevision.createCandidatesForNewChapters(mangaEntry, chapters, DISCOVERED_AT)

                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.manga eq mangaId }
                    .first()
                    .let { ChapterRevisionTable.toDataClass(it) }
            }

        val claimed = ChapterRevision.claimNextQueued(now = 50)!!
        assertEquals(candidate.id, claimed.id)
        runBlocking {
            ChapterRevisionAcquisitionProcessor(
                sourceAccess = FakeSourceAccess(pages),
                stagingRoot = { stagingRoot },
            ).process(claimed)
        }

        val acquired = ChapterRevision.getRevision(candidate.id)!!
        assertEquals(ChapterAcquisitionState.COMPLETE, acquired.acquisitionState)
        return acquired
    }

    private fun archive(
        revision: ChapterRevisionDataClass,
        onVerificationDue: () -> Unit = {},
    ): ChapterRevisionDataClass {
        val claimed = ChapterRevision.claimNextArchiveCommit(now = 100)!!
        assertEquals(revision.id, claimed.id)
        runBlocking { archiveProcessor(onVerificationDue).process(claimed) }
        return ChapterRevision.getRevision(revision.id)!!
    }

    private fun readManifest(revision: ChapterRevisionDataClass): ChapterRevisionArchiveManifest {
        val file = File(archiveRoot, ChapterRevisionArchiveArtifacts.relativeManifestPath(revision.candidateKey))
        return ChapterRevisionArchiveManifestCodec.decode(file.readBytes())
    }

    /** A manifest that is deliberately not the one the processor would build for [revision]. */
    private fun manifestFor(
        revision: ChapterRevisionDataClass,
        chapterTitle: String,
    ) = ChapterRevisionArchiveManifest(
        schemaVersion = ChapterRevisionArchiveManifestCodec.SCHEMA_VERSION,
        revisionId = revision.id,
        candidateKey = revision.candidateKey,
        seriesId = revision.mangaId,
        chapterId = revision.chapterId,
        sourceId = revision.sourceId,
        sourceMangaUrl = revision.sourceMangaUrl,
        sourceChapterUrl = revision.sourceChapterUrl,
        chapterNumber = revision.chapterNumber,
        chapterTitle = chapterTitle,
        scanlator = revision.scanlator,
        memo = revision.memo,
        discoveredAt = revision.discoveredAt,
        archivedAt = DISCOVERED_AT,
        pageCount = 1,
        pages = emptyList(),
        acquisitionContentHash = revision.contentHash,
        archiveContentHash = "0".repeat(64),
        archiveSize = 1,
    )

    @Test
    fun `archiving publishes a cbz and a manifest and stops at remote pending`() {
        val revision = acquiredCandidate("ARCHIVE_PUBLISH")

        val archived = archive(revision)

        assertEquals(ChapterArchiveState.REMOTE_PENDING, archived.archiveState)
        assertEquals(1, archived.archiveAttempts)
        assertEquals(100L, archived.archiveLastAttemptAt)
        assertTrue(archived.archiveCbzPath!!.startsWith("revisions/${revision.candidateKey}/"))
        assertTrue(archived.archiveManifestPath!!.endsWith(".archive.json"))
        assertTrue(!File(archived.archiveCbzPath!!).isAbsolute, "persisted artifact paths must stay relative")
        assertEquals(64, archived.archiveCbzHash!!.length)
        assertTrue(archived.archiveCbzSize!! > 0)
        assertNull(archived.archiveLastError)

        val cbz = File(archiveRoot, archived.archiveCbzPath!!)
        val manifestFile = File(archiveRoot, archived.archiveManifestPath!!)
        assertTrue(cbz.isFile, "the immutable CBZ must be published")
        assertTrue(manifestFile.isFile, "the sidecar manifest must be published")
        assertEquals(ChapterRevisionArchiveArtifacts.digestOf(cbz).sha256, archived.archiveCbzHash)
        assertEquals(ChapterRevisionArchiveArtifacts.digestOf(manifestFile).sha256, archived.archiveManifestHash)
        assertEquals(manifestFile.length(), archived.archiveManifestSize)

        // both immutable objects are built locally first and retained through REMOTE_PENDING so a
        // configured verifier can compare the archived copies against them
        val localCbz = ChapterRevisionArchiveArtifacts.localCbzFile(stagingRoot, revision.candidateKey)
        val localManifest = ChapterRevisionArchiveArtifacts.localManifestFile(stagingRoot, revision.candidateKey)
        assertTrue(localCbz.isFile, "the local CBZ must be retained until remote durability is confirmed")
        assertTrue(localManifest.isFile, "the local manifest must be retained until remote durability is confirmed")
        assertEquals(
            ChapterRevisionArchiveArtifacts.digestOf(localCbz),
            ChapterRevisionArchiveArtifacts.digestOf(cbz),
            "the archived copy must be byte identical to the locally built artifact",
        )
        assertEquals(
            ChapterRevisionArchiveArtifacts.digestOf(localManifest),
            ChapterRevisionArchiveArtifacts.digestOf(manifestFile),
            "the archived manifest must be byte identical to the locally built manifest",
        )

        // the local artifact area must stay outside the validated page directory so page validation
        // keeps rejecting any extra entry
        assertTrue(
            ChapterRevisionArchiveArtifacts.relativeLocalDirectory(revision.candidateKey).startsWith("revision-artifacts/"),
        )
        assertTrue(
            !ChapterRevisionArchiveArtifacts
                .localDirectory(stagingRoot, revision.candidateKey)
                .path
                .startsWith(ChapterRevisionStaging.directory(stagingRoot, revision.candidateKey).path),
            "built artifacts must not pollute the validated page directory",
        )

        // the local staging pages must be retained for a future verifier and for retries
        val stagedPages = ChapterRevisionStaging.pageFiles(ChapterRevisionStaging.directory(stagingRoot, revision.candidateKey))
        assertEquals(2, stagedPages.size, "staging must not be consumed by the archive commit")
    }

    @Test
    fun `the manifest records durable identity, page fingerprints and the archive digest`() {
        val revision = acquiredCandidate("ARCHIVE_MANIFEST")

        val archived = archive(revision)

        val manifest = readManifest(revision)
        assertEquals(ChapterRevisionArchiveManifestCodec.SCHEMA_VERSION, manifest.schemaVersion)
        assertEquals(revision.id, manifest.revisionId)
        assertEquals(revision.candidateKey, manifest.candidateKey)
        assertEquals(revision.mangaId, manifest.seriesId)
        assertEquals(revision.chapterId, manifest.chapterId)
        assertEquals(revision.sourceId, manifest.sourceId)
        assertEquals(revision.sourceChapterUrl, manifest.sourceChapterUrl)
        assertEquals(revision.name, manifest.chapterTitle)
        assertEquals(revision.scanlator, manifest.scanlator)
        assertEquals(revision.discoveredAt, manifest.discoveredAt)
        assertEquals(revision.memo, manifest.memo)
        assertEquals(2, manifest.pageCount)
        assertEquals(archived.archiveCbzHash, manifest.archiveContentHash)
        assertEquals(archived.archiveCbzSize, manifest.archiveSize)
        assertEquals(revision.contentHash, manifest.acquisitionContentHash)

        val stagedPages = ChapterRevisionStaging.pageFiles(ChapterRevisionStaging.directory(stagingRoot, revision.candidateKey))
        assertEquals(
            stagedPages.map { ChapterRevisionCbz.fingerprintOf(it) },
            manifest.pages,
            "the manifest must record the ordered per-page fingerprints of the archived pages",
        )
    }

    @Test
    fun `a manifest written before sweep provenance still decodes and re-encodes canonically`() {
        val revision = acquiredCandidate("ARCHIVE_MANIFEST_V2")
        val archived = archive(revision)

        // the published file is the canonical encoding of its own model
        assertEquals(
            File(archiveRoot, archived.archiveManifestPath!!).readBytes().toList(),
            ChapterRevisionArchiveManifestCodec.encode(readManifest(revision)).toList(),
        )

        // a manifest written before a sweep and its comparison existed: it simply has no key for them
        val beforeSweeps =
            """
            {
              "schemaVersion": 2,
              "revisionId": 7,
              "candidateKey": "${revision.candidateKey}",
              "seriesId": null,
              "chapterId": null,
              "sourceId": null,
              "sourceMangaUrl": null,
              "sourceChapterUrl": "https://example.org/series/1/chapter/1",
              "chapterNumber": 1.0,
              "chapterTitle": "Chapter 1",
              "scanlator": null,
              "memo": {},
              "discoveredAt": 1700000000,
              "archivedAt": 1700000010,
              "pageCount": 2,
              "pages": [],
              "acquisitionContentHash": null,
              "archiveContentHash": "a",
              "archiveSize": 1
            }
            """.trimIndent()

        val decoded = ChapterRevisionArchiveManifestCodec.decode(beforeSweeps.toByteArray(Charsets.UTF_8))

        // the older manifest keeps its identity instead of being rejected as an unknown document
        assertEquals(2, decoded.schemaVersion)
        assertEquals(7, decoded.revisionId)
        assertEquals(revision.candidateKey, decoded.candidateKey)
        assertEquals("Chapter 1", decoded.chapterTitle)
        assertEquals(2, decoded.pageCount)
        assertEquals(0, decoded.pages.size)

        // and everything it could not carry comes back as the documented default
        assertEquals("PENDING", decoded.comparisonState.name)
        assertEquals(null, decoded.discoverySweepItemId)
        assertEquals(null, decoded.comparisonBaselineRevisionId)
        assertEquals(null, decoded.comparedAt)
        assertEquals("METADATA_HINT", decoded.signalConfidence.name)
        assertEquals("NEW_CHAPTER", decoded.discoveryReason.name)
        assertEquals(0, decoded.changedMetadataFields.size)

        // re-encoding is canonical, which is what the remote size and hash verification relies on
        val reencoded = ChapterRevisionArchiveManifestCodec.encode(decoded)
        assertEquals(decoded, ChapterRevisionArchiveManifestCodec.decode(reencoded))
        assertEquals(reencoded.toList(), ChapterRevisionArchiveManifestCodec.encode(decoded).toList())
    }

    @Test
    fun `the manifest round-trips and stays in archive order`() {
        val revision = acquiredCandidate("ARCHIVE_MANIFEST_ROUNDTRIP")

        val archived = archive(revision)

        val encoded = File(archiveRoot, archived.archiveManifestPath!!).readBytes()
        val decoded = ChapterRevisionArchiveManifestCodec.decode(encoded)

        assertEquals(
            decoded,
            readManifest(revision),
            "the manifest must round trip through its canonical encoding",
        )
        assertEquals(
            ChapterRevisionStaging
                .pageFiles(ChapterRevisionStaging.directory(stagingRoot, revision.candidateKey))
                .map { it.name },
            decoded.pages.map { it.name },
            "page fingerprints keep the staged page names in page order",
        )
        assertEquals(
            decoded.pages
                .map { it.sha256 }
                .distinct()
                .size,
            decoded.pages.size,
        )
    }

    @Test
    fun `the cbz is byte deterministic and orders pages by name`() {
        val revision = acquiredCandidate("ARCHIVE_DETERMINISTIC")
        val stagedPages = ChapterRevisionStaging.pageFiles(ChapterRevisionStaging.directory(stagingRoot, revision.candidateKey))

        val first = File(archiveRoot, "determinism/first.cbz").apply { parentFile.mkdirs() }
        val second = File(archiveRoot, "determinism/second.cbz").apply { parentFile.mkdirs() }

        val firstDigest = ChapterRevisionCbz.write(stagedPages, first)
        val secondDigest = ChapterRevisionCbz.write(stagedPages, second)

        assertEquals(firstDigest, secondDigest, "the same ordered pages must produce byte identical CBZ files")

        ZipFile(first).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(
                stagedPages.map { it.name },
                entries.map { it.name },
                "entries must follow the staged page order",
            )
            entries.forEach { entry ->
                assertNotEquals(-1, entry.time, "every entry must carry the fixed deterministic timestamp")
                assertEquals(
                    entries.first().time,
                    entry.time,
                    "entries must not carry wall clock timestamps",
                )
            }
        }
    }

    @Test
    fun `an artifact surviving a crash is reused instead of being rewritten`() {
        val revision = acquiredCandidate("ARCHIVE_IDEMPOTENT")

        val archived = archive(revision)
        val manifestBytes = File(archiveRoot, archived.archiveManifestPath!!).readBytes()
        val localManifest = ChapterRevisionArchiveArtifacts.localManifestFile(stagingRoot, revision.candidateKey)
        val localManifestBytes = localManifest.readBytes()

        // simulate a crash after publication but before the state was persisted
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq revision.id }) {
                it[archiveState] = ChapterArchiveState.NOT_COMMITTED.name
            }
        }

        val rearchived = archive(revision)

        assertEquals(ChapterArchiveState.REMOTE_PENDING, rearchived.archiveState)
        assertEquals(archived.archiveCbzHash, rearchived.archiveCbzHash)
        assertEquals(
            localManifestBytes.toList(),
            localManifest.readBytes().toList(),
            "the locally built manifest, including its original archivedAt, must be reused verbatim",
        )
        assertEquals(
            manifestBytes.toList(),
            File(archiveRoot, rearchived.archiveManifestPath!!).readBytes().toList(),
            "an existing manifest that already describes the published CBZ must be kept verbatim",
        )
        assertEquals(archived.archiveManifestHash, rearchived.archiveManifestHash)
    }

    @Test
    fun `a conflicting immutable artifact is never overwritten`() {
        val revision = acquiredCandidate("ARCHIVE_CONFLICT")
        val target = ChapterRevisionArchiveArtifacts.cbzFile(archiveRoot, revision.candidateKey)
        target.parentFile.mkdirs()
        val foreign = "not the archived chapter".toByteArray()
        target.writeBytes(foreign)

        val failed = archive(revision)

        assertEquals(ChapterArchiveState.COMMIT_FAILED, failed.archiveState)
        assertTrue(failed.archiveLastError!!.contains("different content"))
        assertEquals(
            foreign.toList(),
            target.readBytes().toList(),
            "a mismatching immutable artifact must be left untouched",
        )
        assertEquals(1, failed.archiveAttempts)
    }

    @Test
    fun `a failed commit keeps the acquisition state and staging`() {
        val revision = acquiredCandidate("ARCHIVE_FAILED")
        ChapterRevisionStaging.deleteCandidate(stagingRoot, revision.candidateKey)

        val failed = archive(revision)

        assertEquals(ChapterArchiveState.COMMIT_FAILED, failed.archiveState)
        assertEquals(
            ChapterAcquisitionState.COMPLETE,
            failed.acquisitionState,
            "a failed archive must not disturb how the chapter was acquired",
        )
        assertNull(failed.archiveCbzPath)
    }

    @Test
    fun `the commit schedules remote verification and never confirms on its own`() {
        val revision = acquiredCandidate("ARCHIVE_PENDING")
        var verificationNotified = false

        val archived = archive(revision) { verificationNotified = true }

        assertEquals(
            ChapterArchiveState.REMOTE_PENDING,
            archived.archiveState,
            "writing through the archive mount must never be treated as remote durability",
        )
        assertEquals(
            0L,
            archived.archiveNextVerificationAt,
            "a newly published revision must be due for verification immediately",
        )
        assertEquals(0, archived.archiveVerificationAttempts)
        assertNull(archived.archiveLastVerificationAt)
        assertTrue(verificationNotified, "the verification worker must be woken after the commit")
    }

    @Test
    fun `a locally staged manifest that describes something else is refused`() {
        val revision = acquiredCandidate("ARCHIVE_SIDECAR_CONFLICT")
        val localManifest = ChapterRevisionArchiveArtifacts.localManifestFile(stagingRoot, revision.candidateKey)
        localManifest.parentFile.mkdirs()
        val foreign =
            ChapterRevisionArchiveManifestCodec.encode(
                manifestFor(revision, chapterTitle = "a different chapter"),
            )
        localManifest.writeBytes(foreign)

        val failed = archive(revision)

        assertEquals(ChapterArchiveState.COMMIT_FAILED, failed.archiveState)
        assertTrue(failed.archiveLastError!!.contains("different content"), failed.archiveLastError!!)
        assertEquals(
            foreign.toList(),
            localManifest.readBytes().toList(),
            "a manifest with foreign metadata must never be overwritten",
        )
        assertNull(failed.archiveManifestHash, "a refused commit must not record a manifest digest")

        // nothing was copied into the archive root for this revision
        assertTrue(!ChapterRevisionArchiveArtifacts.directory(archiveRoot, revision.candidateKey).exists())
    }

    @Test
    fun `a committing revision recovers to uncommitted and preserves attempts`() {
        val revision = acquiredCandidate("ARCHIVE_RECOVERY")
        ChapterRevision.claimNextArchiveCommit(now = 100)

        assertEquals(1, ChapterRevision.recoverInterruptedArchives(now = 120))

        val recovered = ChapterRevision.getRevision(revision.id)!!
        assertEquals(ChapterArchiveState.NOT_COMMITTED, recovered.archiveState)
        assertEquals(1, recovered.archiveAttempts, "recovery must not reset the archive attempt count")
    }

    @Test
    fun `the archive loop keeps draining after a failure`() {
        val failing = acquiredCandidate("ARCHIVE_LOOP_FAIL")
        ChapterRevisionStaging.deleteCandidate(stagingRoot, failing.candidateKey)
        val succeeding = acquiredCandidate("ARCHIVE_LOOP_OK")

        val loop = archiveLoop()
        runBlocking {
            assertTrue(loop.drainOnce(), "the failing revision is claimed first")
            assertTrue(loop.drainOnce(), "the next revision is still processed")
            assertTrue(!loop.drainOnce(), "the queue is drained")
        }

        assertEquals(ChapterArchiveState.COMMIT_FAILED, ChapterRevision.getRevision(failing.id)!!.archiveState)
        assertEquals(ChapterArchiveState.REMOTE_PENDING, ChapterRevision.getRevision(succeeding.id)!!.archiveState)
    }

    @Test
    fun `retry requeues a failed archive and clears its artifacts before commit`() {
        val revision = acquiredCandidate("ARCHIVE_RETRY")
        ChapterRevisionStaging.deleteCandidate(stagingRoot, revision.candidateKey)
        assertEquals(ChapterArchiveState.COMMIT_FAILED, archive(revision).archiveState)

        // re-staging would be needed for a real retry; this checks the transition and cleanup only
        assertEquals(ChapterAcquisitionState.COMPLETE, ChapterRevision.getRevision(revision.id)!!.acquisitionState)

        // rebuild the downloaded pages so the retry cleanup can be shown to preserve them
        val stagedPages = ChapterRevisionStaging.directory(stagingRoot, revision.candidateKey)
        stagedPages.mkdirs()
        File(stagedPages, "00001.png").writeBytes(pngBytes(9))

        val localDirectory = ChapterRevisionArchiveArtifacts.localDirectory(stagingRoot, revision.candidateKey)
        localDirectory.mkdirs()
        File(localDirectory, "stale-local.tmp").writeText("stale")
        val archiveDirectory = ChapterRevisionArchiveArtifacts.directory(archiveRoot, revision.candidateKey)
        archiveDirectory.mkdirs()
        File(archiveDirectory, "stale.tmp").writeText("stale")

        var cleanedBeforeCommit = false
        val retried =
            ChapterRevision.retryArchive(listOf(revision.id), now = 200) { revisions ->
                assertEquals(1, revisions.size)
                revisions.forEach {
                    ChapterRevisionArchiveArtifacts.deleteArtifacts(stagingRoot, archiveRoot, it.candidateKey)
                }
                cleanedBeforeCommit = true
            }

        assertTrue(cleanedBeforeCommit, "artifact cleanup must run before the retry becomes visible")
        assertEquals(1, retried.size)
        assertEquals(ChapterArchiveState.NOT_COMMITTED, retried.single().archiveState)
        assertEquals(1, retried.single().archiveAttempts, "a retry must preserve the archive attempt count")
        assertTrue(!localDirectory.exists(), "stale local artifacts must be removed")
        assertTrue(!archiveDirectory.exists(), "stale archived artifacts must be removed")
        assertTrue(
            File(stagedPages, "00001.png").isFile,
            "a retry must not discard the downloaded pages, so it does not have to download again",
        )
        assertTrue(
            ChapterRevision.retryArchive(listOf(revision.id)).isEmpty(),
            "a revision that is no longer failed is not retryable",
        )
    }

    @Test
    fun `archive artifact paths are derived from the immutable candidate key only`() {
        val revision = acquiredCandidate("ARCHIVE_PATHS")

        assertTrue(ChapterRevisionArchiveArtifacts.relativeDirectory(revision.candidateKey).startsWith("revisions/"))
        assertTrue(ChapterRevisionArchiveArtifacts.relativeCbzPath(revision.candidateKey).startsWith("revisions/"))

        val rejected = runCatching { ChapterRevisionArchiveArtifacts.relativeDirectory("../../etc/passwd") }
        assertTrue(rejected.isFailure, "a traversal candidate key must be rejected")

        assertNull(
            ChapterRevision.getRevision(revision.id)!!.archiveCbzPath,
            "no artifact path exists before the archive commit ran",
        )
    }

    @AfterEach
    internal fun tearDown() {
        stagingRoot.deleteRecursively()
        archiveRoot.deleteRecursively()
        clearTables(ChapterRevisionTable, ChapterTable, MangaTable)
    }

    private companion object {
        const val DISCOVERED_AT = 1_000L
    }
}
