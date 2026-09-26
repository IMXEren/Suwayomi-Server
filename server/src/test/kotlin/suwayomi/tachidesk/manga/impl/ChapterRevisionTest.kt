package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDiscoveryReason
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterRevisionTest : ApplicationTest() {
    private fun setPolicy(
        mangaId: Int,
        policy: MangaAcquisitionPolicy,
    ) = transaction {
        MangaTable.update({ MangaTable.id eq mangaId }) { it[acquisitionPolicy] = policy.name }
    }

    private fun chaptersOf(mangaId: Int): List<ChapterDataClass> =
        transaction {
            ChapterTable
                .selectAll()
                .where { ChapterTable.manga eq mangaId }
                .orderBy(ChapterTable.sourceOrder)
                .map { ChapterTable.toDataClass(it) }
        }

    private fun createCandidates(
        mangaId: Int,
        now: Long,
    ): List<Int> =
        transaction {
            val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
            ChapterRevision.createCandidatesForNewChapters(mangaEntry, chaptersOf(mangaId), now)
        }

    private fun candidateWithPolicy(
        mangaId: Int,
        policy: MangaAcquisitionPolicy,
        now: Long,
    ): ChapterRevisionDataClass {
        setPolicy(mangaId, policy)
        val ids = createCandidates(mangaId, now)
        return ChapterRevision.getRevision(ids.single())!!
    }

    private fun countRevisions(mangaId: Int): Long =
        transaction { ChapterRevisionTable.selectAll().where { ChapterRevisionTable.manga eq mangaId }.count() }

    @Test
    fun `maps the acquisition policy to the initial candidate state`() {
        val autoManga = createLibraryManga("REVISION_AUTO").also { createChapters(it, 1, read = false) }
        val auto = candidateWithPolicy(autoManga, MangaAcquisitionPolicy.AUTO, 1000)
        assertEquals(ChapterAcquisitionState.QUEUED, auto.acquisitionState)
        assertEquals(1000L, auto.approvedAt)
        assertEquals(ChapterRevisionDisposition.CANDIDATE, auto.disposition)

        val manualManga = createLibraryManga("REVISION_MANUAL").also { createChapters(it, 1, read = false) }
        val manual = candidateWithPolicy(manualManga, MangaAcquisitionPolicy.MANUAL, 1000)
        assertEquals(ChapterAcquisitionState.PENDING_APPROVAL, manual.acquisitionState)
        assertNull(manual.approvedAt)
        assertEquals(ChapterRevisionDisposition.CANDIDATE, manual.disposition)

        val pausedManga = createLibraryManga("REVISION_PAUSED").also { createChapters(it, 1, read = false) }
        val paused = candidateWithPolicy(pausedManga, MangaAcquisitionPolicy.PAUSED, 1000)
        assertEquals(ChapterAcquisitionState.DISCOVERED, paused.acquisitionState)
        assertNull(paused.approvedAt)
        assertEquals(ChapterRevisionDisposition.CANDIDATE, paused.disposition)
    }

    @Test
    fun `candidate creation is deterministic and idempotent`() {
        val mangaId = createLibraryManga("REVISION_IDEMPOTENT").also { createChapters(it, 2, read = false) }
        val memo = JsonObject(mapOf("source-token" to JsonPrimitive("snapshot")))
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        transaction {
            ChapterTable.update({ ChapterTable.manga eq mangaId }) { it[ChapterTable.memo] = memo }
        }

        val firstIds = createCandidates(mangaId, 1000)
        assertEquals(2, firstIds.size)
        assertEquals(2L, countRevisions(mangaId))

        val secondIds = createCandidates(mangaId, 2000)
        assertEquals(
            firstIds.toSet(),
            secondIds.toSet(),
            "repeated reconciliation returns the already recorded candidates and creates no duplicates",
        )
        assertEquals(2L, countRevisions(mangaId))

        val stored = ChapterRevision.getRevision(firstIds.first())!!
        assertEquals(memo, stored.memo, "source memo must survive as part of the immutable retrieval snapshot")
        assertEquals(
            ChapterRevision.candidateKey(
                stored.sourceId,
                stored.sourceMangaUrl,
                stored.sourceChapterUrl,
                stored.name,
                stored.scanlator,
                stored.uploadDate,
                stored.chapterNumber,
                stored.memo,
            ),
            stored.candidateKey,
            "the key must be reproducible from the stored discovery snapshot",
        )
    }

    @Test
    fun `reconciliation of an already recorded candidate does not overwrite it`() {
        val mangaId = createLibraryManga("REVISION_NO_OVERWRITE").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        val id = createCandidates(mangaId, 1000).single()

        // put the candidate in a non-initial state, as an explicit approval would
        ChapterRevision.approve(listOf(id), now = 5000)

        // re-discovering the same chapter must conflict on candidate_key and leave the row untouched
        assertEquals(listOf(id), createCandidates(mangaId, 9000))

        val stored = ChapterRevision.getRevision(id)!!
        assertEquals(ChapterAcquisitionState.QUEUED, stored.acquisitionState)
        assertEquals(5000L, stored.approvedAt)
        assertEquals(1000L, stored.discoveredAt, "the original discovery snapshot is preserved")
        assertEquals(5000L, stored.updatedAt, "the queue-entry time is not reset by reconciliation")
        assertEquals(1L, countRevisions(mangaId))
    }

    @Test
    fun `a changed discovery snapshot creates another revision for the same chapter`() {
        val mangaId = createLibraryManga("REVISION_RESNAPSHOT").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        val chapter = chaptersOf(mangaId).single()

        transaction {
            val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
            ChapterRevision.createCandidatesForNewChapters(mangaEntry, listOf(chapter), 1000)
            ChapterRevision.createCandidates(
                sourceId = mangaEntry[MangaTable.sourceReference],
                sourceMangaUrl = mangaEntry[MangaTable.url],
                policy = MangaAcquisitionPolicy.MANUAL,
                chapters = listOf(chapter.copy(name = "Chapter 1 (reupload)", uploadDate = 5000)),
                now = 2000,
            )
        }

        val revisions = ChapterRevision.getRevisionsForChapter(chapter.id)
        assertEquals(2, revisions.size)
        assertEquals(2, revisions.map { it.candidateKey }.distinct().size)
    }

    @Test
    fun `memo canonicalization is recursive and metadata audit excludes operational fields`() {
        val mangaId = createLibraryManga("REVISION_CANONICAL").also { createChapters(it, 1, read = false) }
        val chapter = chaptersOf(mangaId).single()
        val firstMemo =
            JsonObject(
                linkedMapOf(
                    "z" to JsonArray(listOf(JsonObject(linkedMapOf("b" to JsonPrimitive(2), "a" to JsonPrimitive(1))))),
                    "a" to JsonPrimitive("value"),
                ),
            )
        val reorderedMemo =
            JsonObject(
                linkedMapOf(
                    "a" to JsonPrimitive("value"),
                    "z" to JsonArray(listOf(JsonObject(linkedMapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2))))),
                ),
            )
        assertEquals(ChapterRevision.canonicalMemo(firstMemo), ChapterRevision.canonicalMemo(reorderedMemo))

        val previous = chapter.copy(memo = firstMemo)
        val excludedChanges =
            ChapterRevision.changedMetadataFields(
                previous,
                previous.copy(
                    index = previous.index + 1,
                    fetchedAt = previous.fetchedAt + 1,
                    realUrl = "changed",
                ),
            )
        assertTrue(excludedChanges.isEmpty())
        assertEquals(
            ChapterRevisionMetadataField.entries,
            ChapterRevision.changedMetadataFields(
                previous,
                previous.copy(
                    name = "changed",
                    scanlator = "other",
                    uploadDate = previous.uploadDate + 1,
                    chapterNumber = previous.chapterNumber + 1,
                    memo = JsonObject(mapOf("different" to JsonPrimitive(true))),
                ),
            ),
        )
    }

    @Test
    fun `metadata discovery persists audit and validated content upgrades confidence`() {
        val mangaId = createLibraryManga("REVISION_METADATA_AUDIT").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        val chapter = chaptersOf(mangaId).single().copy(name = "changed")
        val id =
            transaction {
                val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
                ChapterRevision
                    .createCandidatesForMetadataChanges(
                        mangaEntry,
                        listOf(
                            ChapterRevisionDiscovery(
                                chapter,
                                ChapterRevisionDiscoveryReason.METADATA_CHANGE,
                                listOf(ChapterRevisionMetadataField.NAME),
                            ),
                        ),
                        1000,
                    ).single()
            }
        val discovered = ChapterRevision.getRevision(id)!!
        assertEquals(ChapterRevisionDiscoveryReason.METADATA_CHANGE, discovered.discoveryReason)
        assertEquals(ChapterRevisionSignalConfidence.METADATA_HINT, discovered.signalConfidence)
        assertEquals(listOf(ChapterRevisionMetadataField.NAME), discovered.changedMetadataFields)

        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[acquisitionState] = ChapterAcquisitionState.VALIDATING.name
            }
        }
        assertTrue(ChapterRevision.markComplete(id, 2, "a".repeat(64), "revision-candidates/key", now = 2000))
        assertEquals(ChapterRevisionSignalConfidence.CONTENT_PROOF, ChapterRevision.getRevision(id)!!.signalConfidence)
    }

    @Test
    fun `approve and reject only transition explicitly selected pending candidates`() {
        val mangaId = createLibraryManga("REVISION_TRANSITIONS").also { createChapters(it, 3, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        val ids = createCandidates(mangaId, 1000)

        val approved = ChapterRevision.approve(listOf(ids[0]), now = 5000)
        assertEquals(listOf(ids[0]), approved.map { it.id })
        assertEquals(ChapterAcquisitionState.QUEUED, approved.single().acquisitionState)
        assertEquals(5000L, approved.single().approvedAt)
        assertEquals(ChapterRevisionDisposition.CANDIDATE, approved.single().disposition)

        assertTrue(ChapterRevision.approve(listOf(ids[0])).isEmpty(), "an already queued candidate is a no-op")

        val rejected = ChapterRevision.reject(listOf(ids[1]), now = 6000)
        assertEquals(listOf(ids[1]), rejected.map { it.id })
        assertEquals(ChapterRevisionDisposition.REJECTED, rejected.single().disposition)
        assertEquals(ChapterAcquisitionState.PENDING_APPROVAL, rejected.single().acquisitionState)

        assertTrue(ChapterRevision.approve(listOf(ids[1])).isEmpty(), "a rejected candidate cannot be approved")
        assertTrue(ChapterRevision.reject(listOf(ids[1])).isEmpty(), "an already rejected candidate is a no-op")
    }

    @Test
    fun `a discovered candidate is promoted only when explicitly selected`() {
        val mangaId = createLibraryManga("REVISION_DISCOVERED").also { createChapters(it, 2, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.PAUSED)
        val ids = createCandidates(mangaId, 1000)

        assertTrue(ChapterRevision.getApprovalBacklog().isEmpty(), "paused candidates are not part of the approval backlog")

        val approved = ChapterRevision.approve(listOf(ids[1]), now = 4000)
        assertEquals(listOf(ids[1]), approved.map { it.id })
        assertEquals(ChapterAcquisitionState.QUEUED, approved.single().acquisitionState)

        assertEquals(
            listOf(ids[1]),
            ChapterRevision.getQueuedBacklog().map { it.id },
            "only the explicitly promoted candidate is queued",
        )
        assertEquals(ChapterAcquisitionState.DISCOVERED, ChapterRevision.getRevision(ids[0])!!.acquisitionState)
    }

    @Test
    fun `the queued backlog is ordered by queue entry then id`() {
        val mangaId = createLibraryManga("REVISION_QUEUE_ORDER").also { createChapters(it, 3, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        val ids = createCandidates(mangaId, 1000)

        ChapterRevision.approve(listOf(ids[2]), now = 3000)
        ChapterRevision.approve(listOf(ids[1], ids[0]), now = 1000)

        assertEquals(listOf(ids[0], ids[1], ids[2]), ChapterRevision.getQueuedBacklog().map { it.id })
    }

    @Test
    fun `the approval backlog is ordered by discovery then id`() {
        val mangaId = createLibraryManga("REVISION_APPROVAL_ORDER").also { createChapters(it, 3, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        val chapters = chaptersOf(mangaId)

        transaction {
            val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
            ChapterRevision.createCandidatesForNewChapters(mangaEntry, listOf(chapters[2]), 3000)
            ChapterRevision.createCandidatesForNewChapters(mangaEntry, listOf(chapters[0]), 1000)
            ChapterRevision.createCandidatesForNewChapters(mangaEntry, listOf(chapters[1]), 2000)
        }

        assertEquals(
            listOf(chapters[0].id, chapters[1].id, chapters[2].id),
            ChapterRevision.getApprovalBacklog().map { it.chapterId },
        )
    }

    @Test
    fun `candidate creation is independent of the auto download setting`() {
        val previous = serverConfig.autoDownloadNewChapters.value
        try {
            serverConfig.autoDownloadNewChapters.value = false

            val mangaId = createLibraryManga("REVISION_AUTODOWNLOAD").also { createChapters(it, 1, read = false) }
            setPolicy(mangaId, MangaAcquisitionPolicy.AUTO)

            val ids = createCandidates(mangaId, 1000)
            assertEquals(1, ids.size, "candidates are created regardless of the legacy auto download setting")

            val chapters = chaptersOf(mangaId)
            assertTrue(chapters.none { it.downloaded }, "no download was dispatched for the candidate")
            assertEquals(-1, chapters.single().pageCount)
        } finally {
            serverConfig.autoDownloadNewChapters.value = previous
        }
    }

    @Test
    fun `candidate creation is gated to tracked library manga`() {
        val mangaId = createLibraryManga("REVISION_OUTSIDE_LIBRARY").also { createChapters(it, 2, read = false) }
        transaction {
            MangaTable.update({ MangaTable.id eq mangaId }) { it[inLibrary] = false }
        }
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)

        val outsideLibrary = createCandidates(mangaId, 1000)
        assertTrue(outsideLibrary.isEmpty(), "browsing a source manga outside the library must not create archival candidates")
        assertEquals(0L, countRevisions(mangaId))

        transaction {
            MangaTable.update({ MangaTable.id eq mangaId }) { it[inLibrary] = true }
        }
        assertEquals(2, createCandidates(mangaId, 2000).size, "the same manga is tracked once it is added to the library")
    }

    @AfterEach
    internal fun tearDown() {
        clearTables(
            ChapterRevisionTable,
            ChapterTable,
            MangaTable,
        )
    }
}
