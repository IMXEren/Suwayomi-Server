package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.graphql.mutations.ChapterRevisionRollbackMutation
import suwayomi.tachidesk.graphql.queries.ChapterRevisionRollbackQuery
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRetentionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionIntegrityState
import suwayomi.tachidesk.manga.model.table.ChapterRevisionRollbackTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createLibraryManga
import java.util.UUID
import java.util.concurrent.CountDownLatch

/**
 * Behaviour of rolling a chapter identity back to a historical revision.
 *
 * The decision is a database transition plus a wake: the bytes that become visible again are written by
 * the ordinary publication worker, so what these tests assert is the state the decision leaves behind,
 * the guards that refuse it, and that a replay or a concurrent decision cannot corrupt the
 * single-active-revision invariant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterRevisionRollbackTest : ApplicationTest() {
    private val query = ChapterRevisionRollbackQuery()
    private val mutation = ChapterRevisionRollbackMutation()
    private var clock = 10_000L

    @AfterEach
    fun clean() {
        clearTables(ChapterRevisionRollbackTable, ChapterRevisionTable)
    }

    private fun newKey(): String = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

    @Suppress("LongParameterList")
    private fun revision(
        mangaId: Int? = null,
        chapterKey: String = newKey(),
        candidateKey: String = newKey(),
        disposition: ChapterRevisionDisposition,
        active: Boolean = false,
        archiveState: ChapterArchiveState = ChapterArchiveState.REMOTE_CONFIRMED,
        publicationState: ChapterPublicationState = ChapterPublicationState.NOT_PUBLISHED,
        retentionState: ChapterRetentionState = ChapterRetentionState.RETAINED,
        integrityState: ChapterRevisionIntegrityState = ChapterRevisionIntegrityState.NEVER_AUDITED,
        deletedAt: Long? = null,
        acceptedAt: Long = 1_000,
    ): Int =
        transaction {
            ChapterRevisionTable.insert {
                it[ChapterRevisionTable.candidateKey] = candidateKey
                it[ChapterRevisionTable.chapterKey] = chapterKey
                it[ChapterRevisionTable.manga] = mangaId
                it[sourceChapterUrl] = "https://example.invalid/$candidateKey"
                it[name] = "chapter"
                it[discoveredAt] = 1
                it[updatedAt] = 1
                it[ChapterRevisionTable.disposition] = disposition.name
                it[ChapterRevisionTable.archiveState] = archiveState.name
                it[ChapterRevisionTable.publicationState] = publicationState.name
                it[ChapterRevisionTable.retentionState] = retentionState.name
                it[ChapterRevisionTable.integrityState] = integrityState.name
                it[ChapterRevisionTable.deletedAt] = deletedAt
                it[this.acceptedAt] = acceptedAt
                it[activatedAt] = acceptedAt.takeIf { active }
                it[activeChapterKey] = chapterKey.takeIf { active }
                it[archiveCbzPath] = ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey)
                it[archiveManifestPath] = ChapterRevisionArchiveArtifacts.relativeManifestPath(candidateKey)
                it[archiveCbzHash] = "a".repeat(64)
                it[archiveCbzSize] = 10
                it[archiveManifestHash] = "b".repeat(64)
                it[archiveManifestSize] = 20
            }

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.candidateKey eq candidateKey }
                .first()[ChapterRevisionTable.id]
                .value
        }

    private fun revision(id: Int) = ChapterRevision.getRevision(id)!!

    private fun rollbacks(): List<Int> =
        transaction { ChapterRevisionRollbackTable.selectAll().map { it[ChapterRevisionRollbackTable.id].value } }

    /** One identity: a published active revision plus one confirmed historical revision. */
    private data class Seeded(
        val chapterKey: String,
        val activeId: Int,
        val historicalId: Int,
    )

    private fun seedIdentity(): Seeded {
        val mangaId = createLibraryManga("Rollback ${UUID.randomUUID()}")
        val chapterKey = newKey()
        val activeId =
            revision(
                mangaId = mangaId,
                chapterKey = chapterKey,
                disposition = ChapterRevisionDisposition.ACCEPTED,
                active = true,
                publicationState = ChapterPublicationState.PUBLISHED,
                acceptedAt = 2_000,
            )
        val historicalId =
            revision(
                mangaId = mangaId,
                chapterKey = chapterKey,
                disposition = ChapterRevisionDisposition.SUPERSEDED,
                acceptedAt = 1_000,
            )

        return Seeded(chapterKey, activeId, historicalId)
    }

    @Test
    fun `rolling back activates the historical revision and supersedes the current one`() {
        val seeded = seedIdentity()

        val outcome = ChapterRevision.rollbackChapterRevision(seeded.historicalId, clock)

        val rolledBack = (outcome as ChapterRevisionRollbackOutcome.RolledBack)
        assertEquals(seeded.historicalId, rolledBack.revision.id)
        assertEquals(seeded.activeId, rolledBack.replacedActiveRevisionId)

        val target = revision(seeded.historicalId)
        assertEquals(ChapterRevisionDisposition.ACCEPTED, target.disposition)
        assertTrue(target.isActiveRevision)
        assertEquals(clock, target.activatedAt)
        assertNull(target.supersededAt)
        // the bytes are republished by the ordinary worker, so the decision only re-queues them
        assertEquals(ChapterPublicationState.NOT_PUBLISHED, target.publicationState)
        // the archived payload a historical revision was kept for is what gets published again
        assertNotNull(target.archiveCbzPath)
        assertEquals(ChapterArchiveState.REMOTE_CONFIRMED, target.archiveState)

        val previous = revision(seeded.activeId)
        assertEquals(ChapterRevisionDisposition.SUPERSEDED, previous.disposition)
        assertNull(previous.activeChapterKey)
        assertEquals(clock, previous.supersededAt)

        // the identity still holds both revisions, and exactly one of them is the active one, which is
        // the single-active invariant the database itself enforces
        assertEquals(
            2,
            transaction {
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.chapterKey eq seeded.chapterKey }
                    .toList()
                    .size
            },
        )
        assertEquals(
            1,
            transaction {
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        (ChapterRevisionTable.chapterKey eq seeded.chapterKey) and
                            (ChapterRevisionTable.activeChapterKey eq seeded.chapterKey)
                    }.toList()
                    .size
            },
        )
        assertEquals(seeded.historicalId, ChapterRevision.getActiveRevision(seeded.chapterKey)!!.id)

        // and the decision is recorded once, naming both revisions
        val events = query.chapterRevisionRollbacks(chapterKey = seeded.chapterKey, first = 10)
        val event = events.nodes.single()
        assertEquals(seeded.activeId, event.fromRevisionId)
        assertEquals(seeded.historicalId, event.toRevisionId)
        assertEquals(clock, event.rolledBackAt)
    }

    @Test
    fun `replaying the same decision is a no-op`() {
        val seeded = seedIdentity()
        ChapterRevision.rollbackChapterRevision(seeded.historicalId, clock)
        val afterFirst = revision(seeded.historicalId)

        val replay = ChapterRevision.rollbackChapterRevision(seeded.historicalId, clock + 100)

        assertEquals(ChapterRevisionRollbackOutcome.AlreadyActive, replay)
        // nothing moved, and no second decision was recorded
        assertEquals(afterFirst, revision(seeded.historicalId))
        assertEquals(1, rollbacks().size)
    }

    @Test
    fun `a target that stops being activatable after it was reached rolls the supersession back`() {
        val seeded = seedIdentity()
        val beforeActive = revision(seeded.activeId)

        val outcome =
            ChapterRevision.rollbackChapterRevision(
                revisionId = seeded.historicalId,
                now = clock,
                beforeActivation = {
                    // exactly the fence failure it guards against: the target stops being durably
                    // archived between the supersession of the current revision and its own activation
                    transaction {
                        ChapterRevisionTable.update({ ChapterRevisionTable.id eq seeded.historicalId }) {
                            it[ChapterRevisionTable.archiveState] = ChapterArchiveState.REMOTE_PENDING.name
                        }
                    }
                },
            )

        // the decision is the refusal the fence exists for, and it recorded no event
        assertTrue((outcome as ChapterRevisionRollbackOutcome.Refused).reason.contains("stopped being"))
        assertTrue(rollbacks().isEmpty())

        // the supersession is rolled back together with the activation it belonged to: the identity
        // keeps the revision it had, untouched, instead of being left with no active revision at all
        val active = revision(seeded.activeId)
        assertEquals(beforeActive, active)
        assertEquals(ChapterRevisionDisposition.ACCEPTED, active.disposition)
        assertEquals(seeded.chapterKey, active.activeChapterKey)
        assertNull(active.supersededAt)
        assertEquals(ChapterPublicationState.PUBLISHED, active.publicationState)
        assertEquals(ChapterRetentionState.RETAINED, active.retentionState)

        // and the target is still the historical revision it was
        val target = revision(seeded.historicalId)
        assertEquals(ChapterRevisionDisposition.SUPERSEDED, target.disposition)
        assertNull(target.activeChapterKey)
        assertNull(target.activatedAt)
        assertEquals(ChapterArchiveState.REMOTE_CONFIRMED, target.archiveState)
        assertEquals(ChapterPublicationState.NOT_PUBLISHED, target.publicationState)

        assertEquals(seeded.activeId, ChapterRevision.getActiveRevision(seeded.chapterKey)!!.id)
    }

    @Test
    fun `an unexpected failure inside the decision propagates instead of being reported as a refusal`() {
        val seeded = seedIdentity()
        val beforeActive = revision(seeded.activeId)

        val failure =
            assertThrows(IllegalStateException::class.java) {
                ChapterRevision.rollbackChapterRevision(
                    revisionId = seeded.historicalId,
                    now = clock,
                    beforeActivation = { throw IllegalStateException("the storage went away") },
                )
            }

        // a real failure is not a refusal: it reaches the caller, and it left nothing behind either
        assertEquals("the storage went away", failure.message)
        assertEquals(beforeActive, revision(seeded.activeId))
        assertEquals(ChapterRevisionDisposition.SUPERSEDED, revision(seeded.historicalId).disposition)
        assertNull(revision(seeded.historicalId).activeChapterKey)
        assertTrue(rollbacks().isEmpty())
    }

    @Test
    fun `an unknown revision is not found`() {
        assertEquals(ChapterRevisionRollbackOutcome.NotFound, ChapterRevision.rollbackChapterRevision(999_999))
    }

    @Test
    fun `a candidate is refused`() {
        val mangaId = createLibraryManga("Series")
        val chapterKey = newKey()
        revision(mangaId = mangaId, chapterKey = chapterKey, disposition = ChapterRevisionDisposition.ACCEPTED, active = true)
        val candidate = revision(mangaId = mangaId, chapterKey = chapterKey, disposition = ChapterRevisionDisposition.CANDIDATE)

        val outcome = ChapterRevision.rollbackChapterRevision(candidate)

        assertTrue((outcome as ChapterRevisionRollbackOutcome.Refused).reason.contains("not an accepted revision"))
        assertEquals(ChapterRevisionDisposition.CANDIDATE, revision(candidate).disposition)
        assertTrue(rollbacks().isEmpty())
    }

    @Test
    fun `a revision that was never durably archived is refused`() {
        val mangaId = createLibraryManga("Series")
        val chapterKey = newKey()
        revision(mangaId = mangaId, chapterKey = chapterKey, disposition = ChapterRevisionDisposition.ACCEPTED, active = true)
        val pending =
            revision(
                mangaId = mangaId,
                chapterKey = chapterKey,
                disposition = ChapterRevisionDisposition.SUPERSEDED,
                archiveState = ChapterArchiveState.REMOTE_PENDING,
            )

        val outcome = ChapterRevision.rollbackChapterRevision(pending)

        assertTrue((outcome as ChapterRevisionRollbackOutcome.Refused).reason.contains("not durably archived"))
        assertNull(revision(pending).activeChapterKey)
    }

    @Test
    fun `a revision whose payload is gone is refused`() {
        val mangaId = createLibraryManga("Series")
        val chapterKey = newKey()
        revision(mangaId = mangaId, chapterKey = chapterKey, disposition = ChapterRevisionDisposition.ACCEPTED, active = true)
        val pruned =
            revision(
                mangaId = mangaId,
                chapterKey = chapterKey,
                disposition = ChapterRevisionDisposition.SUPERSEDED,
                retentionState = ChapterRetentionState.PRUNED,
            )

        val outcome = ChapterRevision.rollbackChapterRevision(pruned)

        assertTrue((outcome as ChapterRevisionRollbackOutcome.Refused).reason.contains("has been removed"))

        // the same is true of a deletion that already started, and of a payload already unlinked
        val deleting =
            revision(
                mangaId = mangaId,
                chapterKey = chapterKey,
                disposition = ChapterRevisionDisposition.SUPERSEDED,
                retentionState = ChapterRetentionState.DELETING,
            )
        assertTrue(
            (ChapterRevision.rollbackChapterRevision(deleting) as ChapterRevisionRollbackOutcome.Refused)
                .reason
                .contains("has been removed"),
        )

        val unlinked =
            revision(
                mangaId = mangaId,
                chapterKey = chapterKey,
                disposition = ChapterRevisionDisposition.SUPERSEDED,
                deletedAt = 5,
            )
        assertTrue(
            (ChapterRevision.rollbackChapterRevision(unlinked) as ChapterRevisionRollbackOutcome.Refused)
                .reason
                .contains("has been removed"),
        )
        assertNull(revision(pruned).activeChapterKey)
    }

    @Test
    fun `a revision the last integrity check found unusable is refused`() {
        val mangaId = createLibraryManga("Series")
        val chapterKey = newKey()
        revision(mangaId = mangaId, chapterKey = chapterKey, disposition = ChapterRevisionDisposition.ACCEPTED, active = true)

        listOf(ChapterRevisionIntegrityState.MISSING, ChapterRevisionIntegrityState.CORRUPT).forEach { finding ->
            val damaged =
                revision(
                    mangaId = mangaId,
                    chapterKey = chapterKey,
                    disposition = ChapterRevisionDisposition.SUPERSEDED,
                    integrityState = finding,
                )

            val outcome = ChapterRevision.rollbackChapterRevision(damaged)

            // the bytes such a rollback would publish are exactly the ones that are known to be unusable
            assertTrue(
                (outcome as ChapterRevisionRollbackOutcome.Refused).reason.contains("integrity check found"),
            )
            assertNull(revision(damaged).activeChapterKey)
        }

        // a failed *check* is not a finding about the payload, so it does not refuse the decision
        val unknown =
            revision(
                mangaId = mangaId,
                chapterKey = chapterKey,
                disposition = ChapterRevisionDisposition.SUPERSEDED,
                integrityState = ChapterRevisionIntegrityState.AUDIT_FAILED,
            )
        assertTrue(ChapterRevision.rollbackChapterRevision(unknown) is ChapterRevisionRollbackOutcome.RolledBack)
    }

    @Test
    fun `a concurrent acceptance and rollback serialize into one active revision`() {
        val seeded = seedIdentity()
        val other = createLibraryManga("Series")
        // a second candidate of the same identity, so the two decisions really compete
        val candidate =
            revision(
                mangaId = other,
                chapterKey = seeded.chapterKey,
                disposition = ChapterRevisionDisposition.CANDIDATE,
            )

        val start = CountDownLatch(1)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<String>())

        val acceptThread =
            Thread {
                start.await()
                ChapterRevision.review(
                    listOf(candidate),
                    suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionReviewAction.ACCEPT_CANDIDATE,
                )
                outcomes += "accept"
            }
        val rollbackThread =
            Thread {
                start.await()
                ChapterRevision.rollbackChapterRevision(seeded.historicalId)
                outcomes += "rollback"
            }

        acceptThread.start()
        rollbackThread.start()
        start.countDown()
        acceptThread.join(30_000)
        rollbackThread.join(30_000)

        assertEquals(2, outcomes.size, "both decisions have to conclude")

        // whichever committed second owns the identity, and exactly one revision is active
        val active =
            transaction {
                ChapterRevisionTable
                    .selectAll()
                    .where {
                        (ChapterRevisionTable.chapterKey eq seeded.chapterKey) and
                            (ChapterRevisionTable.activeChapterKey eq seeded.chapterKey)
                    }.toList()
            }
        assertEquals(1, active.size)
        assertTrue(
            active.single()[ChapterRevisionTable.id].value in listOf(seeded.historicalId, candidate),
            "the active revision is one of the two the threads asked for",
        )
    }

    @Test
    fun `a rollback does not prune the revision it made active and the replaced one waits for publication`() {
        val seeded = seedIdentity()

        assertTrue(ChapterRevision.rollbackChapterRevision(seeded.historicalId, clock) is ChapterRevisionRollbackOutcome.RolledBack)

        // the target is active now, so reconciliation may never queue it for pruning
        ChapterRevision.reconcileRetention(seeded.chapterKey, clock)
        assertEquals(ChapterRetentionState.RETAINED, revision(seeded.historicalId).retentionState)

        // the revision it replaced is history, but its payload may only be pruned once the replacement
        // is really published - the rollback reset that, so it is still retained
        assertEquals(ChapterRetentionState.RETAINED, revision(seeded.activeId).retentionState)
        assertEquals(ChapterArchiveState.REMOTE_CONFIRMED, revision(seeded.activeId).archiveState)
    }

    @Test
    fun `the mutation reports a refusal as an error and a success as the active revision`() {
        val seeded = seedIdentity()

        val accepted =
            mutation.rollbackChapterRevision(
                ChapterRevisionRollbackMutation.RollbackChapterRevisionInput(revisionId = seeded.historicalId),
            )
        assertNull(accepted.error)
        assertEquals(seeded.historicalId, accepted.revision!!.id)
        assertEquals(seeded.activeId, accepted.replacedRevisionId)

        val replay =
            mutation.rollbackChapterRevision(
                ChapterRevisionRollbackMutation.RollbackChapterRevisionInput(revisionId = seeded.historicalId),
            )
        assertNull(replay.error, "a replay is the no-op it is, not an error a client would retry")

        val refused =
            mutation.rollbackChapterRevision(
                ChapterRevisionRollbackMutation.RollbackChapterRevisionInput(revisionId = 999_999),
            )
        assertNotNull(refused.error)
        assertNull(refused.revision)
    }

    @Test
    fun `the rollback history pages by decision`() {
        val seeded = seedIdentity()
        val other = seedIdentity()

        ChapterRevision.rollbackChapterRevision(seeded.historicalId, clock)
        ChapterRevision.rollbackChapterRevision(other.historicalId, clock + 1)

        val all = query.chapterRevisionRollbacks(first = 10)
        assertEquals(2, all.totalCount)
        // newest first by default
        assertEquals(clock + 1, all.nodes.first().rolledBackAt)

        val firstPage = query.chapterRevisionRollbacks(first = 1)
        assertTrue(firstPage.pageInfo.hasNextPage)
        assertEquals(2, firstPage.totalCount)
        val secondPage = query.chapterRevisionRollbacks(after = firstPage.pageInfo.endCursor, first = 1)
        assertEquals(1, secondPage.nodes.size)
        assertEquals(clock, secondPage.nodes.single().rolledBackAt)

        // a client can ask about one identity, which is what a chapter's own history is
        val own = query.chapterRevisionRollbacks(chapterKey = seeded.chapterKey, first = 10)
        assertEquals(1, own.totalCount)
        assertEquals(seeded.chapterKey, own.nodes.single().chapterKey)
    }
}
