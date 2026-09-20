package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionReviewAction
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Accepted-revision lifecycle: one active revision per chapter identity, first-confirmed
// auto-activation, explicit review decisions and their replay/invalid guards.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterRevisionAcceptedLifecycleTest : ApplicationTest() {
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

    /** Marks a revision acquired and remotely confirmed, the only state an accepting action allows. */
    private fun confirm(
        id: Int,
        hash: String = "hash-$id",
        size: Long = id.toLong(),
    ) = transaction {
        ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
            it[acquisitionState] = ChapterAcquisitionState.COMPLETE.name
            it[archiveState] = ChapterArchiveState.REMOTE_CONFIRMED.name
            it[archiveCbzPath] = "revisions/$id/$id.cbz"
            it[archiveCbzHash] = hash
            it[archiveCbzSize] = size
        }
    }

    private fun createCandidateIds(
        mangaId: Int,
        chapter: ChapterDataClass,
        snapshot: ChapterDataClass = chapter,
        now: Long = 1000,
    ): Int =
        transaction {
            val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
            ChapterRevision.createCandidatesForNewChapters(mangaEntry, listOf(snapshot), now).single()
        }

    /** Two revisions of one chapter identity (a re-upload), both remotely confirmed. */
    private fun twoConfirmedRevisionsOfOneChapter(title: String): Pair<String, List<Int>> {
        val mangaId = createLibraryManga(title).also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        val chapter = chaptersOf(mangaId).single()

        val firstId = createCandidateIds(mangaId, chapter, now = 1000)
        val secondId =
            createCandidateIds(mangaId, chapter, snapshot = chapter.copy(name = "Chapter (reupload)", uploadDate = 5000), now = 2000)

        confirm(firstId)
        confirm(secondId)

        val chapterKey = ChapterRevision.getRevision(firstId)!!.chapterKey
        assertEquals(chapterKey, ChapterRevision.getRevision(secondId)!!.chapterKey, "a re-upload stays the same identity")
        return chapterKey to listOf(firstId, secondId)
    }

    private fun activeIds(chapterKey: String): List<Int> =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.activeChapterKey eq chapterKey }
                .map { it[ChapterRevisionTable.id].value }
        }

    @Test
    fun `chapter key is deterministic and groups exactly one chapter`() {
        val key = ChapterRevision.chapterKey(1, "https://source/manga", "/chapter/1")
        assertEquals(key, ChapterRevision.chapterKey(1, "https://source/manga", "/chapter/1"))
        assertNotEquals(key, ChapterRevision.chapterKey(1, "https://source/manga", "/chapter/2"))
        assertNotEquals(key, ChapterRevision.chapterKey(2, "https://source/manga", "/chapter/1"))
        assertNotEquals(key, ChapterRevision.chapterKey(null, "https://source/manga", "/chapter/1"))
    }

    @Test
    fun `revisions of one chapter share an identity while a different chapter does not`() {
        val mangaId = createLibraryManga("LIFECYCLE_GROUPING").also { createChapters(it, 2, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        val chapters = chaptersOf(mangaId)

        val firstId = createCandidateIds(mangaId, chapters[0], now = 1000)
        val reuploadId = createCandidateIds(mangaId, chapters[0], snapshot = chapters[0].copy(name = "reupload"), now = 2000)
        val otherId = createCandidateIds(mangaId, chapters[1], now = 3000)

        val first = ChapterRevision.getRevision(firstId)!!
        val reupload = ChapterRevision.getRevision(reuploadId)!!
        val other = ChapterRevision.getRevision(otherId)!!

        assertEquals(first.chapterKey, reupload.chapterKey, "a different snapshot of the same chapter is the same identity")
        assertNotEquals(first.chapterKey, other.chapterKey, "a different chapter must never share an identity")
        assertNotEquals(first.candidateKey, reupload.candidateKey, "different snapshots are different discoveries")
    }

    @Test
    fun `the first confirmed revision is auto activated exactly once and a later one stays a candidate`() {
        val (chapterKey, ids) = twoConfirmedRevisionsOfOneChapter("LIFECYCLE_FIRST_CONFIRMED")

        assertTrue(ChapterRevision.activateIfFirstConfirmed(ids[0]), "the first confirmed revision becomes active")
        assertFalse(ChapterRevision.activateIfFirstConfirmed(ids[0]), "an already active revision is not re-activated")
        assertFalse(
            ChapterRevision.activateIfFirstConfirmed(ids[1]),
            "a revision confirmed later must not silently replace the active one",
        )

        assertEquals(listOf(ids[0]), activeIds(chapterKey))
        val later = ChapterRevision.getRevision(ids[1])!!
        assertEquals(ChapterRevisionDisposition.CANDIDATE, later.disposition)
        assertNull(later.acceptedAt)
    }

    @Test
    fun `accept candidate activates the newest revision and supersedes the previous one`() {
        val (chapterKey, ids) = twoConfirmedRevisionsOfOneChapter("LIFECYCLE_ACCEPT")

        ChapterRevision.activateIfFirstConfirmed(ids[0])
        val accepted = ChapterRevision.review(listOf(ids[1]), ChapterRevisionReviewAction.ACCEPT_CANDIDATE, now = 5000)

        assertEquals(listOf(ids[1]), accepted.map { it.id })
        assertEquals(listOf(ids[1]), activeIds(chapterKey), "exactly one active revision remains")
        assertEquals(ChapterRevisionDisposition.ACCEPTED, accepted.single().disposition)
        assertEquals(5000L, accepted.single().activatedAt)

        val previous = ChapterRevision.getRevision(ids[0])!!
        assertEquals(ChapterRevisionDisposition.SUPERSEDED, previous.disposition)
        assertNull(previous.activeChapterKey)
        assertEquals(5000L, previous.supersededAt)
    }

    @Test
    fun `keep current and reject candidate dismiss the candidate without touching the active revision`() {
        val (chapterKey, ids) = twoConfirmedRevisionsOfOneChapter("LIFECYCLE_DISMISS")
        ChapterRevision.activateIfFirstConfirmed(ids[0])

        val kept = ChapterRevision.review(listOf(ids[1]), ChapterRevisionReviewAction.KEEP_CURRENT, now = 5000)
        assertEquals(ChapterRevisionDisposition.REJECTED, kept.single().disposition)
        assertEquals(listOf(ids[0]), activeIds(chapterKey))

        // the dismissal is a state change, so replaying it is a no-op
        assertTrue(ChapterRevision.review(listOf(ids[1]), ChapterRevisionReviewAction.REJECT_CANDIDATE, now = 6000).isEmpty())
        assertEquals(ChapterRevisionDisposition.REJECTED, ChapterRevision.getRevision(ids[1])!!.disposition)
    }

    @Test
    fun `keep both accepts the candidate as history while the active revision keeps its place`() {
        val (chapterKey, ids) = twoConfirmedRevisionsOfOneChapter("LIFECYCLE_KEEP_BOTH")
        ChapterRevision.activateIfFirstConfirmed(ids[0])

        val kept = ChapterRevision.review(listOf(ids[1]), ChapterRevisionReviewAction.KEEP_BOTH, now = 5000)

        assertEquals(listOf(ids[1]), kept.map { it.id })
        assertEquals(ChapterRevisionDisposition.ACCEPTED, kept.single().disposition)
        assertEquals(5000L, kept.single().acceptedAt)
        assertNull(kept.single().activeChapterKey, "a kept historical revision is accepted but not active")
        assertEquals(listOf(ids[0]), activeIds(chapterKey))
    }

    @Test
    fun `accepting an unconfirmed or already decided revision is a no-op`() {
        val mangaId = createLibraryManga("LIFECYCLE_GUARDS").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        val chapter = chaptersOf(mangaId).single()
        val unconfirmed = createCandidateIds(mangaId, chapter, now = 1000)

        assertTrue(
            ChapterRevision.review(listOf(unconfirmed), ChapterRevisionReviewAction.ACCEPT_CANDIDATE, now = 5000).isEmpty(),
            "content that is not remotely confirmed must never be activated",
        )
        assertTrue(
            ChapterRevision.review(listOf(unconfirmed), ChapterRevisionReviewAction.KEEP_BOTH, now = 5000).isEmpty(),
            "KEEP_BOTH also requires remote confirmation because it accepts the revision",
        )
        assertNull(ChapterRevision.getActiveRevision(ChapterRevision.getRevision(unconfirmed)!!.chapterKey))

        // dismissal is always safe, even without confirmation
        assertEquals(
            listOf(unconfirmed),
            ChapterRevision.review(listOf(unconfirmed), ChapterRevisionReviewAction.REJECT_CANDIDATE, now = 6000).map { it.id },
        )
        assertTrue(
            ChapterRevision.review(listOf(unconfirmed), ChapterRevisionReviewAction.REJECT_CANDIDATE, now = 7000).isEmpty(),
            "a replayed decision must not change anything again",
        )
    }

    @Test
    fun `accepting several candidates of one chapter at once picks the newest deterministically`() {
        val (chapterKey, ids) = twoConfirmedRevisionsOfOneChapter("LIFECYCLE_BATCH")

        val accepted = ChapterRevision.review(ids, ChapterRevisionReviewAction.ACCEPT_CANDIDATE, now = 5000)

        assertEquals(listOf(ids.max()), accepted.map { it.id }, "the highest id wins, independent of input order")
        assertEquals(listOf(ids.max()), activeIds(chapterKey))
        assertEquals(ChapterRevisionDisposition.CANDIDATE, ChapterRevision.getRevision(ids.min())!!.disposition)
    }

    @Test
    fun `concurrent accepts of one chapter leave exactly one active revision`() {
        val (chapterKey, ids) = twoConfirmedRevisionsOfOneChapter("LIFECYCLE_CONCURRENT")

        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                ids.map { id ->
                    executor.submit {
                        start.await()
                        runCatching {
                            ChapterRevision.review(listOf(id), ChapterRevisionReviewAction.ACCEPT_CANDIDATE, now = 5000)
                        }
                    }
                }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, activeIds(chapterKey).size, "the database invariant is one active revision per identity")
            val active = ChapterRevision.getActiveRevision(chapterKey)!!
            assertEquals(ChapterRevisionDisposition.ACCEPTED, active.disposition)
            val other = ChapterRevision.getRevision(ids.first { it != active.id })!!
            assertTrue(
                other.disposition == ChapterRevisionDisposition.SUPERSEDED ||
                    other.disposition == ChapterRevisionDisposition.CANDIDATE,
                "the loser is either superseded or left as a candidate, never active",
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent reviews of overlapping subsets of two identities never deadlock`() {
        val mangaId = createLibraryManga("LIFECYCLE_OVERLAP").also { createChapters(it, 2, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        val chapters = chaptersOf(mangaId)

        val firstIdentity =
            listOf(
                createCandidateIds(mangaId, chapters[0], now = 1000),
                createCandidateIds(mangaId, chapters[0], snapshot = chapters[0].copy(name = "A2"), now = 2000),
            )
        val secondIdentity =
            listOf(
                createCandidateIds(mangaId, chapters[1], now = 3000),
                createCandidateIds(mangaId, chapters[1], snapshot = chapters[1].copy(name = "B2"), now = 4000),
            )
        (firstIdentity + secondIdentity).forEach { confirm(it) }

        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            // each transaction reviews one revision of each identity, so the requested subsets overlap
            val subsets =
                listOf(
                    listOf(firstIdentity[0], secondIdentity[1]),
                    listOf(firstIdentity[1], secondIdentity[0]),
                )
            val futures =
                subsets.map { subset ->
                    executor.submit {
                        start.await()
                        ChapterRevision.review(subset, ChapterRevisionReviewAction.ACCEPT_CANDIDATE, now = 5000)
                    }
                }
            start.countDown()
            // locking the whole union in one ordered statement means every transaction simply waits its
            // turn; neither of them may end up as a deadlock victim
            futures.forEach { it.get(30, TimeUnit.SECONDS) }

            (firstIdentity + secondIdentity)
                .map { ChapterRevision.getRevision(it)!!.chapterKey }
                .distinct()
                .forEach { key -> assertEquals(1, activeIds(key).size, "exactly one active revision per identity") }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `deleting the source rows keeps the revision history and nulls the references`() {
        val mangaId = createLibraryManga("LIFECYCLE_SOURCE_DELETE").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        val chapter = chaptersOf(mangaId).single()
        val id = createCandidateIds(mangaId, chapter, now = 1000)

        transaction {
            ChapterTable.deleteWhere { ChapterTable.id eq chapter.id }
            MangaTable.deleteWhere { MangaTable.id eq mangaId }
        }

        val revision = ChapterRevision.getRevision(id)
        assertTrue(revision != null, "deleting the source rows must never delete the revision history")
        assertNull(revision!!.chapterId)
        assertNull(revision.mangaId)
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
