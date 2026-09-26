package suwayomi.tachidesk.graphql

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.graphql.queries.ChapterRevisionQuery
import suwayomi.tachidesk.manga.impl.ChapterRevision
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRetentionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga

// The per-identity revision history is cursor paginated with a stable (DISCOVERED_AT DESC, ID DESC)
// default order instead of returning one unbounded list.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterRevisionQueryTest : ApplicationTest() {
    private val query = ChapterRevisionQuery()

    private data class Seeded(
        val chapterKey: String,
        val ids: List<Int>,
    )

    /** Three revisions of one chapter identity, each discovered later than the previous one. */
    private fun seedIdentity(title: String): Seeded {
        val mangaId = createLibraryManga(title).also { createChapters(it, 1, read = false) }
        val chapter =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga eq mangaId }
                    .map { ChapterTable.toDataClass(it) }
                    .single()
            }

        val ids =
            listOf("first" to 1000L, "second" to 2000L, "third" to 3000L).map { (name, discoveredAt) ->
                createRevision(mangaId, chapter, name, discoveredAt)
            }

        val chapterKey = ChapterRevision.getRevision(ids.first())!!.chapterKey
        ids.forEach { assertEquals(chapterKey, ChapterRevision.getRevision(it)!!.chapterKey) }
        return Seeded(chapterKey, ids)
    }

    private fun createRevision(
        mangaId: Int,
        chapter: ChapterDataClass,
        name: String,
        discoveredAt: Long,
    ): Int =
        transaction {
            val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
            ChapterRevision
                .createCandidates(
                    sourceId = mangaEntry[MangaTable.sourceReference],
                    sourceMangaUrl = mangaEntry[MangaTable.url],
                    policy = MangaAcquisitionPolicy.MANUAL,
                    chapters = listOf(chapter.copy(name = name)),
                    now = discoveredAt,
                ).single()
        }

    @Test
    fun `history defaults to newest discovery first`() {
        val seeded = seedIdentity("REVISION_HISTORY_ORDER")

        val revisions = query.chapterRevisionHistory(seeded.chapterKey).nodes

        assertEquals(seeded.ids.reversed(), revisions.map { it.id }, "the default order is DISCOVERED_AT DESC, ID DESC")
        assertTrue(revisions.all { it.chapterKey == seeded.chapterKey })
    }

    @Test
    fun `history pages with the shared cursor machinery`() {
        val seeded = seedIdentity("REVISION_HISTORY_PAGINATION")

        val firstPage = query.chapterRevisionHistory(seeded.chapterKey, first = 2)
        assertEquals(seeded.ids.reversed().take(2), firstPage.nodes.map { it.id })
        assertEquals(3, firstPage.totalCount)
        assertTrue(firstPage.pageInfo.hasNextPage)
        val endCursor = firstPage.pageInfo.endCursor
        assertNotNull(endCursor)

        val secondPage = query.chapterRevisionHistory(seeded.chapterKey, after = endCursor, first = 2)
        assertEquals(seeded.ids.reversed().drop(2), secondPage.nodes.map { it.id })
        assertFalse(secondPage.pageInfo.hasNextPage)
    }

    /**
     * Four revisions of one identity, each put into a different combination of the three independent
     * state dimensions, so a filter can be told apart from its neighbours by its result set alone.
     */
    private fun seedStateIdentity(title: String): List<Int> {
        val mangaId = createLibraryManga(title).also { createChapters(it, 1, read = false) }
        val chapter =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga eq mangaId }
                    .map { ChapterTable.toDataClass(it) }
                    .single()
            }

        val ids =
            listOf("a" to 1000L, "b" to 2000L, "c" to 3000L, "d" to 4000L).map { (name, discoveredAt) ->
                createRevision(mangaId, chapter, name, discoveredAt)
            }

        setStates(ids[0], ChapterArchiveState.REMOTE_CONFIRMED, ChapterPublicationState.PUBLISHED, ChapterRetentionState.RETAINED)
        setStates(ids[1], ChapterArchiveState.REMOTE_PENDING, ChapterPublicationState.NOT_PUBLISHED, ChapterRetentionState.RETAINED)
        setStates(
            ids[2],
            ChapterArchiveState.REMOTE_CONFIRMED,
            ChapterPublicationState.PUBLICATION_FAILED,
            ChapterRetentionState.PRUNE_QUEUED,
        )
        setStates(ids[3], ChapterArchiveState.COMMIT_FAILED, ChapterPublicationState.NOT_PUBLISHED, ChapterRetentionState.PRUNE_FAILED)

        return ids
    }

    // the state transitions themselves are exercised by their own workers; these tests only need the
    // persisted state of a row, so the columns are written directly
    private fun setStates(
        id: Int,
        archive: ChapterArchiveState,
        publication: ChapterPublicationState,
        retention: ChapterRetentionState,
    ) {
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[archiveState] = archive.name
                it[publicationState] = publication.name
                it[retentionState] = retention.name
            }
        }
    }

    @Test
    fun `archive state filters on the archive dimension alone`() {
        val ids = seedStateIdentity("REVISION_FILTER_ARCHIVE")

        assertEquals(
            listOf(ids[0], ids[2]),
            query.chapterRevisions(archiveState = ChapterArchiveState.REMOTE_CONFIRMED).nodes.map { it.id },
        )
        assertEquals(listOf(ids[1]), query.chapterRevisions(archiveState = ChapterArchiveState.REMOTE_PENDING).nodes.map { it.id })
        assertEquals(listOf(ids[3]), query.chapterRevisions(archiveState = ChapterArchiveState.COMMIT_FAILED).nodes.map { it.id })
        assertTrue(query.chapterRevisions(archiveState = ChapterArchiveState.ARCHIVE_UNCONFIRMED).nodes.isEmpty())
    }

    @Test
    fun `publication and retention states filter on their own dimensions`() {
        val ids = seedStateIdentity("REVISION_FILTER_PUBLICATION_RETENTION")

        assertEquals(listOf(ids[0]), query.chapterRevisions(publicationState = ChapterPublicationState.PUBLISHED).nodes.map { it.id })
        assertEquals(
            listOf(ids[2]),
            query.chapterRevisions(publicationState = ChapterPublicationState.PUBLICATION_FAILED).nodes.map { it.id },
        )
        assertEquals(
            listOf(ids[1], ids[3]),
            query.chapterRevisions(publicationState = ChapterPublicationState.NOT_PUBLISHED).nodes.map { it.id },
        )

        assertEquals(
            listOf(ids[0], ids[1]),
            query.chapterRevisions(retentionState = ChapterRetentionState.RETAINED).nodes.map { it.id },
        )
        assertEquals(listOf(ids[2]), query.chapterRevisions(retentionState = ChapterRetentionState.PRUNE_QUEUED).nodes.map { it.id })
        assertEquals(listOf(ids[3]), query.chapterRevisions(retentionState = ChapterRetentionState.PRUNE_FAILED).nodes.map { it.id })
    }

    @Test
    fun `independent state filters are AND-ed together`() {
        val ids = seedStateIdentity("REVISION_FILTER_COMBINED")

        // the active published revision, narrowed by its archive durability
        assertEquals(
            listOf(ids[0]),
            query
                .chapterRevisions(
                    archiveState = ChapterArchiveState.REMOTE_CONFIRMED,
                    publicationState = ChapterPublicationState.PUBLISHED,
                    retentionState = ChapterRetentionState.RETAINED,
                ).nodes
                .map { it.id },
        )

        // the same archive state with a different publication state must select a different revision,
        // which is only possible if the two dimensions are genuinely independent predicates
        assertEquals(
            listOf(ids[2]),
            query
                .chapterRevisions(
                    archiveState = ChapterArchiveState.REMOTE_CONFIRMED,
                    publicationState = ChapterPublicationState.PUBLICATION_FAILED,
                ).nodes
                .map { it.id },
        )

        // a combination no revision satisfies proves the predicates are AND-ed rather than OR-ed
        assertTrue(
            query
                .chapterRevisions(
                    archiveState = ChapterArchiveState.REMOTE_CONFIRMED,
                    publicationState = ChapterPublicationState.PUBLISHED,
                    retentionState = ChapterRetentionState.PRUNE_QUEUED,
                ).nodes
                .isEmpty(),
        )
    }

    @Test
    fun `a state filter combines with the pre-existing filters`() {
        val ids = seedStateIdentity("REVISION_FILTER_MIXED")
        val chapterKey = ChapterRevision.getRevision(ids.first())!!.chapterKey

        assertEquals(
            listOf(ids[2]),
            query
                .chapterRevisions(
                    chapterKey = chapterKey,
                    disposition = ChapterRevisionDisposition.CANDIDATE,
                    archiveState = ChapterArchiveState.REMOTE_CONFIRMED,
                    publicationState = ChapterPublicationState.PUBLICATION_FAILED,
                ).nodes
                .map { it.id },
        )

        assertEquals(
            emptyList<Int>(),
            query
                .chapterRevisions(
                    chapterKey = "NO_SUCH_IDENTITY",
                    archiveState = ChapterArchiveState.REMOTE_CONFIRMED,
                ).nodes
                .map { it.id },
        )
    }

    @Test
    fun `a state filter keeps the filtered total count and a stable cursor`() {
        val ids = seedStateIdentity("REVISION_FILTER_PAGINATION")

        val firstPage = query.chapterRevisions(archiveState = ChapterArchiveState.REMOTE_CONFIRMED, first = 1)
        assertEquals(listOf(ids[0]), firstPage.nodes.map { it.id })
        assertEquals(2, firstPage.totalCount, "the total is the filtered count, not the table count")
        assertTrue(firstPage.pageInfo.hasNextPage)

        val secondPage =
            query.chapterRevisions(
                archiveState = ChapterArchiveState.REMOTE_CONFIRMED,
                after = firstPage.pageInfo.endCursor,
                first = 1,
            )
        assertEquals(listOf(ids[2]), secondPage.nodes.map { it.id })
        assertEquals(2, secondPage.totalCount, "the total stays stable across the pages of one filter")
        assertFalse(secondPage.pageInfo.hasNextPage)
    }

    @Test
    fun `omitting the state filters leaves the listing unchanged`() {
        val ids = seedStateIdentity("REVISION_FILTER_UNCHANGED")

        val unfiltered = query.chapterRevisions()
        assertEquals(ids, unfiltered.nodes.map { it.id }, "the default order and result set are untouched")
        assertEquals(4, unfiltered.totalCount)

        // passing an explicit null is the same as omitting the argument: the predicate is skipped
        assertEquals(
            ids,
            query
                .chapterRevisions(
                    archiveState = null,
                    publicationState = null,
                    retentionState = null,
                ).nodes
                .map { it.id },
        )

        val chapterKey = ChapterRevision.getRevision(ids.first())!!.chapterKey
        assertEquals(ids, query.chapterRevisions(chapterKey = chapterKey).nodes.map { it.id })
    }

    @Test
    fun `the dedicated backlogs keep their own conditions`() {
        val ids = seedStateIdentity("REVISION_FILTER_BACKLOGS")

        // the pruning backlog and the retention filter share a predicate, so they must agree
        assertEquals(
            ids[3],
            query
                .pruningBacklog()
                .nodes
                .single()
                .id,
        )
        assertEquals(listOf(ids[3]), query.chapterRevisions(retentionState = ChapterRetentionState.PRUNE_FAILED).nodes.map { it.id })

        // the publication backlog additionally requires an active published identity, so a failed
        // publication of a non-active candidate is only reachable through the independent filter
        assertTrue(query.publicationBacklog().nodes.isEmpty())
        assertEquals(
            listOf(ids[2]),
            query.chapterRevisions(publicationState = ChapterPublicationState.PUBLICATION_FAILED).nodes.map { it.id },
        )

        // every seeded candidate was created under MANUAL, so it is approvable: the approval backlog
        // lists all four while the queued backlog, which additionally requires QUEUED, lists none
        assertEquals(ids, query.approvalBacklog().nodes.map { it.id })
        assertTrue(query.queuedBacklog().nodes.isEmpty())
        assertEquals(4, query.chapterRevisions(disposition = ChapterRevisionDisposition.CANDIDATE).totalCount)
    }

    @AfterEach
    fun tearDown() {
        clearTables(ChapterRevisionTable, ChapterTable, MangaTable)
    }
}
