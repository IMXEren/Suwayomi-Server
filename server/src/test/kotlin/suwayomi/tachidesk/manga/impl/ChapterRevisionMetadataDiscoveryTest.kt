package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.util.lang.EMPTY
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDiscoveryReason
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionMetadataField
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSignalConfidence
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Metadata-driven revision discovery exercised through the production reconciliation path
 * (`Chapter.updateChapterListDatabase`) rather than through the candidate helper alone.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterRevisionMetadataDiscoveryTest : ApplicationTest() {
    private val stagingRoot: File = File("build/tmp/chapter-revision-metadata-staging-${UUID.randomUUID()}")
    private val archiveRoot: File = File("build/tmp/chapter-revision-metadata-archive-${UUID.randomUUID()}")

    private val now = 1_700_000_000L

    /** A minimal PNG so staged pages pass the image type check. */
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

    @AfterEach
    fun cleanup() {
        clearTables(ChapterRevisionTable, ChapterTable, MangaTable)
        stagingRoot.deleteRecursively()
        archiveRoot.deleteRecursively()
    }

    private fun mangaEntry(mangaId: Int): ResultRow = transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.first() }

    private fun chaptersOf(mangaId: Int): List<ChapterRevisionDataClass> =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.manga eq mangaId }
                .map { ChapterRevisionTable.toDataClass(it) }
        }

    private fun setPolicy(
        mangaId: Int,
        policy: MangaAcquisitionPolicy,
    ) {
        transaction {
            MangaTable.update({ MangaTable.id eq mangaId }) { it[acquisitionPolicy] = policy.name }
        }
    }

    /** Points the single existing chapter row of [mangaId] at a known, fully specified snapshot. */
    private fun seedChapter(
        mangaId: Int,
        name: String,
        memo: JsonObject,
    ) {
        transaction {
            ChapterTable.update({ ChapterTable.manga eq mangaId }) {
                it[ChapterTable.name] = name
                it[ChapterTable.chapter_number] = 1f
                it[ChapterTable.scanlator] = null
                it[ChapterTable.date_upload] = 0
                it[ChapterTable.memo] = memo
            }
        }
    }

    private fun fetchedChapter(
        name: String,
        memo: JsonObject,
    ): SChapter =
        SChapter.create().apply {
            url = "1"
            this.name = name
            chapter_number = 1f
            date_upload = 0
            scanlator = null
            this.memo = memo
        }

    private fun reconcile(
        mangaId: Int,
        chapter: SChapter,
    ) {
        // the legacy auto-download flow must not interfere with the archival candidates under test
        val autoDownloadNewChapters = serverConfig.autoDownloadNewChapters.value
        serverConfig.autoDownloadNewChapters.value = false
        try {
            runBlocking {
                Chapter.updateChapterListDatabase(mangaEntry(mangaId), listOf(chapter), StubSource(1))
            }
        } finally {
            serverConfig.autoDownloadNewChapters.value = autoDownloadNewChapters
        }
    }

    @Test
    fun `reconciliation records one metadata-change candidate and is idempotent`() {
        val mangaId = createLibraryManga("REVISION_METADATA_INTEGRATION").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)
        seedChapter(mangaId, "1", JsonObject.EMPTY)

        reconcile(mangaId, fetchedChapter("Changed name", JsonObject.EMPTY))

        val created = chaptersOf(mangaId).single()
        assertEquals(ChapterRevisionDiscoveryReason.METADATA_CHANGE, created.discoveryReason)
        assertEquals(ChapterRevisionSignalConfidence.METADATA_HINT, created.signalConfidence)
        assertEquals(listOf(ChapterRevisionMetadataField.NAME), created.changedMetadataFields)
        assertEquals(ChapterAcquisitionState.QUEUED, created.acquisitionState)
        assertNotNull(created.approvedAt)

        // the same reconciliation again must not record the change twice
        reconcile(mangaId, fetchedChapter("Changed name", JsonObject.EMPTY))

        assertEquals(1, chaptersOf(mangaId).size)
    }

    @Test
    fun `reconciliation ignores memo key order and non-library manga`() {
        val nested = { first: Boolean ->
            val inner =
                if (first) {
                    JsonObject(linkedMapOf("b" to JsonPrimitive(2), "a" to JsonPrimitive(1)))
                } else {
                    JsonObject(linkedMapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2)))
                }
            JsonObject(
                linkedMapOf(
                    "z" to JsonArray(listOf(inner)),
                    "a" to JsonPrimitive("value"),
                ),
            )
        }

        val reorderedManga = createLibraryManga("REVISION_METADATA_REORDERED").also { createChapters(it, 1, read = false) }
        setPolicy(reorderedManga, MangaAcquisitionPolicy.AUTO)
        seedChapter(reorderedManga, "1", nested(true))

        // only the nested key order of the memo differs, which is the same revision
        reconcile(reorderedManga, fetchedChapter("1", nested(false)))

        assertTrue(chaptersOf(reorderedManga).isEmpty())

        val privateManga = createLibraryManga("REVISION_METADATA_NOT_IN_LIBRARY").also { createChapters(it, 1, read = false) }
        setPolicy(privateManga, MangaAcquisitionPolicy.AUTO)
        seedChapter(privateManga, "1", JsonObject.EMPTY)
        transaction {
            MangaTable.update({ MangaTable.id eq privateManga }) { it[inLibrary] = false }
        }

        reconcile(privateManga, fetchedChapter("Changed name", JsonObject.EMPTY))

        assertTrue(chaptersOf(privateManga).isEmpty())
    }

    @Test
    fun `archived manifest is canonical and carries the schema-v2 discovery audit`() {
        val mangaId = createLibraryManga("REVISION_METADATA_MANIFEST").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)
        seedChapter(mangaId, "1", JsonObject.EMPTY)

        val nested = { first: Boolean ->
            val inner =
                if (first) {
                    JsonObject(linkedMapOf("b" to JsonPrimitive(2), "a" to JsonPrimitive(1)))
                } else {
                    JsonObject(linkedMapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2)))
                }
            JsonObject(linkedMapOf("z" to JsonArray(listOf(inner)), "a" to JsonPrimitive("value")))
        }

        reconcile(mangaId, fetchedChapter("Changed name", nested(true)))

        val candidate = chaptersOf(mangaId).single()
        val pages = listOf(pngBytes(1), pngBytes(2))

        val claimed = ChapterRevision.claimNextQueued(now = now)!!
        assertEquals(candidate.id, claimed.id)
        runBlocking {
            ChapterRevisionAcquisitionProcessor(
                sourceAccess = FakeSourceAccess(pages),
                stagingRoot = { stagingRoot },
            ).process(claimed)
        }
        assertEquals(ChapterAcquisitionState.COMPLETE, ChapterRevision.getRevision(candidate.id)!!.acquisitionState)

        // the row now carries the equivalent memo in a different key order; the archived manifest
        // must still be byte-identical, which is only true if the archive canonicalizes it
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq candidate.id }) {
                it[memo] = nested(false)
            }
        }

        val toArchive = ChapterRevision.claimNextArchiveCommit(now = now + 1)!!
        assertEquals(candidate.id, toArchive.id)
        runBlocking {
            ChapterRevisionArchiveProcessor(
                stagingRoot = { stagingRoot },
                archiveRoot = { archiveRoot },
            ).process(toArchive)
        }

        val archived = ChapterRevision.getRevision(candidate.id)!!
        assertEquals(ChapterArchiveState.REMOTE_PENDING, archived.archiveState)

        val manifestFile = File(archiveRoot, ChapterRevisionArchiveArtifacts.relativeManifestPath(archived.candidateKey))
        assertTrue(manifestFile.isFile)
        val bytes = manifestFile.readBytes()
        val manifest = ChapterRevisionArchiveManifestCodec.decode(bytes)

        assertEquals(ChapterRevisionArchiveManifestCodec.SCHEMA_VERSION, manifest.schemaVersion)
        assertEquals(ChapterRevisionDiscoveryReason.METADATA_CHANGE, manifest.discoveryReason)
        // a successfully acquired revision has validated its own bytes, so it is content proof by then
        assertEquals(ChapterRevisionSignalConfidence.CONTENT_PROOF, manifest.signalConfidence)
        // the seed memo was empty, so the name and the memo both changed in this discovery
        assertEquals(
            listOf(ChapterRevisionMetadataField.NAME, ChapterRevisionMetadataField.MEMO),
            manifest.changedMetadataFields,
        )

        // the archived memo is canonical, so an equivalent nested object with reordered keys
        // canonicalizes to the very same bytes and therefore the same manifest hash
        assertEquals(ChapterRevision.canonicalMemoObject(nested(true)).toString(), manifest.memo.toString())
        assertEquals(ChapterRevision.canonicalMemoObject(nested(false)).toString(), manifest.memo.toString())
        assertEquals(
            ChapterRevisionArchiveManifestCodec.encode(manifest.copy(memo = ChapterRevision.canonicalMemoObject(nested(true)))).toList(),
            bytes.toList(),
        )
    }
}
