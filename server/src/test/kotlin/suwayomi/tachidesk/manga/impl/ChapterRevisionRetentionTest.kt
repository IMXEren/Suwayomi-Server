package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRetentionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createLibraryManga
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ChapterRevisionRetentionTest : ApplicationTest() {
    private lateinit var archiveRoot: File
    private var clock = 10_000L

    @BeforeEach
    fun setUp() {
        clock = 10_000L
        archiveRoot = File("build/tmp/retention-${UUID.randomUUID()}").absoluteFile
        archiveRoot.mkdirs()
    }

    @AfterEach
    fun tearDown() {
        archiveRoot.deleteRecursively()
        clearTables(ChapterRevisionTable, ChapterTable, MangaTable)
    }

    private fun candidateKey(): String = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

    private data class Seeded(
        val mangaId: Int,
        val chapterKey: String,
        val activeId: Int,
        val historicalIds: List<Int>,
    )

    /**
     * Seeds one chapter identity: a published active revision plus [historicalCount] confirmed
     * historical revisions accepted in ascending order, newest last.
     */
    private fun seedIdentity(
        historicalCount: Int,
        retentionPolicy: Int? = null,
        historicalDisposition: ChapterRevisionDisposition = ChapterRevisionDisposition.SUPERSEDED,
    ): Seeded {
        val mangaId = createLibraryManga("Retention ${UUID.randomUUID()}")
        if (retentionPolicy != null) {
            transaction {
                MangaTable.update({ MangaTable.id eq mangaId }) { it[acceptedRevisionRetention] = retentionPolicy }
            }
        }

        val chapterKey = candidateKey()

        val activeId =
            insertRevision(
                mangaId = mangaId,
                chapterKey = chapterKey,
                disposition = ChapterRevisionDisposition.ACCEPTED,
                active = true,
                acceptedAt = 9_000,
                publicationState = ChapterPublicationState.PUBLISHED,
            )

        val historicalIds =
            (1..historicalCount).map { index ->
                insertRevision(
                    mangaId = mangaId,
                    chapterKey = chapterKey,
                    disposition = historicalDisposition,
                    active = false,
                    acceptedAt = (1_000 * index).toLong(),
                    publicationState = ChapterPublicationState.NOT_PUBLISHED,
                )
            }

        return Seeded(mangaId, chapterKey, activeId, historicalIds)
    }

    @Suppress("LongParameterList")
    private fun insertRevision(
        mangaId: Int,
        chapterKey: String,
        disposition: ChapterRevisionDisposition,
        active: Boolean,
        acceptedAt: Long,
        archiveState: ChapterArchiveState = ChapterArchiveState.REMOTE_CONFIRMED,
        publicationState: ChapterPublicationState = ChapterPublicationState.NOT_PUBLISHED,
        retentionState: ChapterRetentionState = ChapterRetentionState.RETAINED,
        retentionQueuedAt: Long? = null,
        key: String = candidateKey(),
    ): Int =
        transaction {
            ChapterRevisionTable.insert {
                it[candidateKey] = key
                it[this.chapterKey] = chapterKey
                it[this.manga] = mangaId
                it[sourceChapterUrl] = "/chapter/$key"
                it[name] = "Chapter"
                it[discoveredAt] = acceptedAt
                it[updatedAt] = acceptedAt
                it[this.disposition] = disposition.name
                it[this.archiveState] = archiveState.name
                it[this.publicationState] = publicationState.name
                it[this.retentionState] = retentionState.name
                it[this.acceptedAt] = acceptedAt
                it[this.activatedAt] = acceptedAt.takeIf { active }
                it[activeChapterKey] = chapterKey.takeIf { active }
                it[archiveCbzPath] = ChapterRevisionArchiveArtifacts.relativeCbzPath(key)
                it[archiveManifestPath] = ChapterRevisionArchiveArtifacts.relativeManifestPath(key)
                it[archiveCbzHash] = "a".repeat(64)
                it[archiveCbzSize] = 10
                it[archiveManifestHash] = "b".repeat(64)
                it[archiveManifestSize] = 20
                it[this.retentionQueuedAt] = retentionQueuedAt
            }

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.candidateKey eq key }
                .first()[ChapterRevisionTable.id]
                .value
        }

    private fun revision(id: Int): ChapterRevisionDataClass = ChapterRevision.getRevision(id)!!

    private fun retentionOf(id: Int): ChapterRetentionState = revision(id).retentionState

    // -----------------------------------------------------------------------------------------
    // selection
    // -----------------------------------------------------------------------------------------

    @Test
    fun `retains the active revision plus the newest N historical revisions`() {
        val seeded = seedIdentity(historicalCount = 3, retentionPolicy = 1)

        assertTrue(ChapterRevision.reconcileRetention(seeded.chapterKey))

        assertEquals(ChapterRetentionState.RETAINED, retentionOf(seeded.activeId))
        // historical newest last, so only the last one stays inside a window of one
        assertEquals(ChapterRetentionState.PRUNE_QUEUED, retentionOf(seeded.historicalIds[0]))
        assertEquals(ChapterRetentionState.PRUNE_QUEUED, retentionOf(seeded.historicalIds[1]))
        assertEquals(ChapterRetentionState.RETAINED, retentionOf(seeded.historicalIds[2]))
    }

    @Test
    fun `unlimited retention queues nothing at all`() {
        val seeded = seedIdentity(historicalCount = 3, retentionPolicy = -1)

        assertFalse(ChapterRevision.reconcileRetention(seeded.chapterKey), "nothing changed, so nothing is queued")

        seeded.historicalIds.forEach { assertEquals(ChapterRetentionState.RETAINED, retentionOf(it)) }
    }

    @Test
    fun `zero retention queues every historical revision but never the active one`() {
        val seeded = seedIdentity(historicalCount = 2, retentionPolicy = 0)

        ChapterRevision.reconcileRetention(seeded.chapterKey)

        assertEquals(ChapterRetentionState.RETAINED, retentionOf(seeded.activeId))
        seeded.historicalIds.forEach { assertEquals(ChapterRetentionState.PRUNE_QUEUED, retentionOf(it)) }
    }

    @Test
    fun `counts a revision kept alongside the active one as history`() {
        val seeded =
            seedIdentity(
                historicalCount = 1,
                retentionPolicy = 0,
                historicalDisposition = ChapterRevisionDisposition.ACCEPTED,
            )

        ChapterRevision.reconcileRetention(seeded.chapterKey)

        assertEquals(
            ChapterRetentionState.PRUNE_QUEUED,
            retentionOf(seeded.historicalIds.single()),
            "a KEEP_BOTH revision is history exactly like a superseded one",
        )
    }

    @Test
    fun `never queues an unconfirmed revision`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq seeded.historicalIds.single() }) {
                it[archiveState] = ChapterArchiveState.REMOTE_PENDING.name
            }
        }

        ChapterRevision.reconcileRetention(seeded.chapterKey)

        assertEquals(
            ChapterRetentionState.RETAINED,
            retentionOf(seeded.historicalIds.single()),
            "content that was never confirmed durable must never be deleted",
        )
    }

    @Test
    fun `never queues a revision that is still the published library copy`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq seeded.historicalIds.single() }) {
                it[publicationState] = ChapterPublicationState.PUBLISHED.name
            }
        }

        ChapterRevision.reconcileRetention(seeded.chapterKey)

        assertEquals(ChapterRetentionState.RETAINED, retentionOf(seeded.historicalIds.single()))
    }

    @Test
    fun `queues nothing before the replacement is published`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq seeded.activeId }) {
                it[publicationState] = ChapterPublicationState.NOT_PUBLISHED.name
            }
        }

        assertFalse(ChapterRevision.reconcileRetention(seeded.chapterKey))

        assertEquals(ChapterRetentionState.RETAINED, retentionOf(seeded.historicalIds.single()))
    }

    @Test
    fun `reconciling an identity without an active revision changes nothing`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq seeded.activeId }) {
                it[activeChapterKey] = null
            }
        }

        assertFalse(ChapterRevision.reconcileRetention(seeded.chapterKey))
    }

    // -----------------------------------------------------------------------------------------
    // policy changes, ordering and failure isolation
    // -----------------------------------------------------------------------------------------

    @Test
    fun `increasing retention unqueues a revision whose deletion has not started`() {
        val seeded = seedIdentity(historicalCount = 2, retentionPolicy = 0)
        ChapterRevision.reconcileRetention(seeded.chapterKey, clock)
        seeded.historicalIds.forEach { assertEquals(ChapterRetentionState.PRUNE_QUEUED, retentionOf(it)) }

        transaction {
            MangaTable.update({ MangaTable.id eq seeded.mangaId }) { it[acceptedRevisionRetention] = 2 }
        }

        ChapterRevision.reconcileRetention(seeded.chapterKey)

        seeded.historicalIds.forEach { assertEquals(ChapterRetentionState.RETAINED, retentionOf(it)) }
        assertNull(revision(seeded.historicalIds.first()).retentionQueuedAt)
    }

    @Test
    fun `never unqueues a revision once its deletion began`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        ChapterRevision.reconcileRetention(seeded.chapterKey, clock)
        val id = seeded.historicalIds.single()
        assertNotNull(ChapterRevision.claimNextPruneQueued(clock))

        transaction {
            MangaTable.update({ MangaTable.id eq seeded.mangaId }) { it[acceptedRevisionRetention] = 5 }
        }

        ChapterRevision.reconcileRetention(seeded.chapterKey)

        assertEquals(ChapterRetentionState.DELETING, retentionOf(id), "a started deletion is never silently abandoned")
    }

    @Test
    fun `claims the oldest queued revision first`() {
        val seeded = seedIdentity(historicalCount = 2, retentionPolicy = 0)
        ChapterRevision.reconcileRetention(seeded.chapterKey, clock)
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq seeded.historicalIds[0] }) {
                it[retentionQueuedAt] = 500
            }
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq seeded.historicalIds[1] }) {
                it[retentionQueuedAt] = 400
            }
        }

        assertEquals(seeded.historicalIds[1], ChapterRevision.claimNextPruneQueued(clock)?.id)
    }

    @Test
    fun `a failed prune is deferred behind the rest of the queue`() {
        val seeded = seedIdentity(historicalCount = 2, retentionPolicy = 0)
        ChapterRevision.reconcileRetention(seeded.chapterKey, clock)

        val first = ChapterRevision.claimNextPruneQueued(clock)!!
        assertTrue(ChapterRevision.deferPruneFailure(first.id, "the payload is still mapped", retryIntervalSeconds = 300, now = clock))

        assertEquals(ChapterRetentionState.PRUNE_QUEUED, retentionOf(first.id))
        assertEquals("the payload is still mapped", revision(first.id).retentionLastError)
        assertNotNull(revision(first.id).retentionQueuedAt)

        // the other queued revision is now the one that gets claimed, so one broken row cannot starve it
        assertEquals(seeded.historicalIds.first { it != first.id }, ChapterRevision.claimNextPruneQueued(clock)?.id)
    }

    @Test
    fun `recovers an interrupted prune without resetting the attempt count`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        ChapterRevision.reconcileRetention(seeded.chapterKey, clock)
        val claimed = ChapterRevision.claimNextPruneQueued(clock)!!
        assertEquals(1, claimed.retentionAttempts)

        assertEquals(1, ChapterRevision.recoverInterruptedPruning(clock))

        val recovered = revision(claimed.id)
        assertEquals(ChapterRetentionState.PRUNE_QUEUED, recovered.retentionState)
        assertEquals(1, recovered.retentionAttempts)
    }

    @Test
    fun `only a failed prune can be retried`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        val id = seeded.historicalIds.single()

        // still inside the window: not retryable, because nothing failed
        assertTrue(ChapterRevision.retryPruning(listOf(id)).isEmpty())

        ChapterRevision.reconcileRetention(seeded.chapterKey, clock)
        ChapterRevision.claimNextPruneQueued(clock)
        ChapterRevision.markPruneFailed(id, "rclone could not authenticate", clock)

        val retried = ChapterRevision.retryPruning(listOf(id), clock)
        assertEquals(listOf(id), retried.map { it.id })
        assertEquals(ChapterRetentionState.PRUNE_QUEUED, retried.single().retentionState)
        assertNull(retried.single().retentionLastError)
    }

    @Test
    fun `a pruned revision is never requeued`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        val id = seeded.historicalIds.single()
        ChapterRevision.reconcileRetention(seeded.chapterKey, clock)
        ChapterRevision.claimNextPruneQueued(clock)
        ChapterRevision.markRemoteDeletePending(id, clock)
        ChapterRevision.markPruned(id, clock)

        assertTrue(ChapterRevision.retryPruning(listOf(id), clock).isEmpty())
        assertEquals(ChapterRetentionState.PRUNED, retentionOf(id))
        assertNotNull(revision(id).prunedAt)
    }

    // -----------------------------------------------------------------------------------------
    // fencing
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a revision that became active before the deletion is returned to the retention window`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        ChapterRevision.reconcileRetention(seeded.chapterKey, clock)
        val claimed = ChapterRevision.claimNextPruneQueued(clock)!!
        assertEquals(ChapterRetentionState.DELETING, claimed.retentionState)

        // an explicit acceptance can promote a revision while a prune claim is in flight
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq seeded.activeId }) { it[activeChapterKey] = null }
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq claimed.id }) {
                it[activeChapterKey] = claimed.chapterKey
            }
        }

        assertFalse(ChapterRevision.authorizePruneDeletion(claimed.id, clock))
        assertEquals(
            ChapterRetentionState.RETAINED,
            retentionOf(claimed.id),
            "the payload of the active revision must never be deleted",
        )
    }

    @Test
    fun `authorizes a claimed revision that is still inactive`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        ChapterRevision.reconcileRetention(seeded.chapterKey, clock)
        val claimed = ChapterRevision.claimNextPruneQueued(clock)!!

        assertTrue(ChapterRevision.authorizePruneDeletion(claimed.id, clock))
    }

    @Test
    fun `leases a due absence check so a second claim cannot overtake it`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        val id = seeded.historicalIds.single()
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[retentionState] = ChapterRetentionState.REMOTE_DELETE_PENDING.name
                it[retentionNextVerificationAt] = clock
            }
        }

        val claimed = ChapterRevision.claimNextDueRetentionVerification(clock, leaseSeconds = 60)!!
        assertEquals(id, claimed.id)
        assertEquals(1, claimed.retentionAttempts)
        assertEquals(clock + 60, revision(id).retentionNextVerificationAt)

        assertNull(
            ChapterRevision.claimNextDueRetentionVerification(clock + 30, leaseSeconds = 60),
            "a leased check is not due again before its lease expired",
        )
        assertEquals(id, ChapterRevision.claimNextDueRetentionVerification(clock + 61, leaseSeconds = 60)?.id)
    }

    @Test
    fun `schedules an unscheduled pending deletion at startup`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        val id = seeded.historicalIds.single()
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[retentionState] = ChapterRetentionState.REMOTE_DELETE_PENDING.name
                it[retentionNextVerificationAt] = null
            }
        }

        assertEquals(1, ChapterRevision.schedulePendingRetentionVerifications(clock))
        assertEquals(clock, revision(id).retentionNextVerificationAt)
    }

    // -----------------------------------------------------------------------------------------
    // payload removal
    // -----------------------------------------------------------------------------------------

    private fun writeArchivedArtifacts(key: String) {
        val cbz = ChapterRevisionArchiveArtifacts.cbzFile(archiveRoot, key)
        cbz.parentFile.mkdirs()
        cbz.writeBytes(ByteArray(10))
        ChapterRevisionArchiveArtifacts.manifestFile(archiveRoot, key).writeBytes(ByteArray(20))
    }

    @Test
    fun `removes only the archived payload and keeps the sidecar manifest`() {
        val key = candidateKey()
        writeArchivedArtifacts(key)

        ChapterRevisionArchiveArtifacts.deleteArchivedCbz(archiveRoot, key)
        // idempotent: a retried deletion of an already removed payload is not an error
        ChapterRevisionArchiveArtifacts.deleteArchivedCbz(archiveRoot, key)

        assertFalse(ChapterRevisionArchiveArtifacts.archivedCbzExists(archiveRoot, key))
        assertTrue(
            ChapterRevisionArchiveArtifacts.manifestFile(archiveRoot, key).isFile,
            "the manifest is the immutable audit record of the pruned payload and must survive",
        )
    }

    @Test
    fun `prunes the queued payload and stops at remote delete pending`() =
        runBlocking {
            val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
            val id = seeded.historicalIds.single()
            val key = revision(id).candidateKey
            writeArchivedArtifacts(key)
            ChapterRevision.reconcileRetention(seeded.chapterKey, clock)

            val processor =
                ChapterRevisionRetentionProcessor(
                    archiveRoot = { archiveRoot },
                    verifier = { ArchiveDeletionVerifier.NOT_CONFIGURED },
                    retryIntervalSeconds = { 300 },
                    leaseSeconds = { 60 },
                    now = { clock },
                )

            assertTrue(processor.pruneOne())

            val pending = revision(id)
            assertEquals(ChapterRetentionState.REMOTE_DELETE_PENDING, pending.retentionState)
            assertEquals(clock, pending.deletedAt)
            assertFalse(ChapterRevisionArchiveArtifacts.archivedCbzExists(archiveRoot, key))
            assertTrue(ChapterRevisionArchiveArtifacts.manifestFile(archiveRoot, key).isFile)
        }

    @Test
    fun `a failed payload removal is deferred instead of failing the revision`() =
        runBlocking {
            val seeded = seedIdentity(historicalCount = 2, retentionPolicy = 0)
            ChapterRevision.reconcileRetention(seeded.chapterKey, clock)

            val processor =
                ChapterRevisionRetentionProcessor(
                    archiveRoot = { archiveRoot },
                    verifier = { ArchiveDeletionVerifier.NOT_CONFIGURED },
                    retryIntervalSeconds = { 300 },
                    leaseSeconds = { 60 },
                    now = { clock },
                    deleteArchivedCbz = { _, key -> throw java.io.IOException("the payload $key is still mapped") },
                )

            assertTrue(processor.pruneOne())

            // the attempt failed, so the row went back to the queue with its error recorded
            val failed = seedIdentityHistory(seeded).first { it.retentionState == ChapterRetentionState.PRUNE_QUEUED }
            assertTrue(failed.retentionLastError!!.contains("still mapped"))
        }

    private fun seedIdentityHistory(seeded: Seeded): List<ChapterRevisionDataClass> =
        transaction {
            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.chapterKey eq seeded.chapterKey }
                .map { ChapterRevisionTable.toDataClass(it) }
        }

    // -----------------------------------------------------------------------------------------
    // absence outcomes
    // -----------------------------------------------------------------------------------------

    private fun processorWith(verifier: ArchiveDeletionVerifier): ChapterRevisionRetentionProcessor =
        ChapterRevisionRetentionProcessor(
            archiveRoot = { archiveRoot },
            verifier = { verifier },
            retryIntervalSeconds = { 300 },
            leaseSeconds = { 60 },
            now = { clock },
        )

    private fun pendingDeletion(): Pair<Int, String> {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        val id = seeded.historicalIds.single()
        val key = revision(id).candidateKey
        writeArchivedArtifacts(key)
        ChapterRevision.reconcileRetention(seeded.chapterKey, clock)
        runBlocking { processorWith(ArchiveDeletionVerifier.NOT_CONFIGURED).pruneOne() }
        return id to key
    }

    @Test
    fun `confirms pruning only when the remote no longer lists the payload`() =
        runBlocking {
            val (id, _) = pendingDeletion()

            assertTrue(
                processorWith(ArchiveDeletionVerifier { ArchiveAbsenceVerification.ConfirmedAbsent }).verifyDueAbsence(),
            )

            assertEquals(ChapterRetentionState.PRUNED, retentionOf(id))
            assertEquals(clock, revision(id).prunedAt)
        }

    @Test
    fun `a payload the remote still lists stays pending with a scheduled re-check`() =
        runBlocking {
            val (id, key) = pendingDeletion()

            processorWith(ArchiveDeletionVerifier { ArchiveAbsenceVerification.StillPresent("$key.cbz is still visible") })
                .verifyDueAbsence()

            val pending = revision(id)
            assertEquals(ChapterRetentionState.REMOTE_DELETE_PENDING, pending.retentionState)
            assertEquals(clock + 300, pending.retentionNextVerificationAt)
            assertTrue(pending.retentionLastError!!.contains("still visible"))
        }

    @Test
    fun `a timed out check stays pending instead of failing the revision`() =
        runBlocking {
            val (id, _) = pendingDeletion()

            processorWith(ArchiveDeletionVerifier { ArchiveAbsenceVerification.Pending("rclone did not answer") })
                .verifyDueAbsence()

            assertEquals(ChapterRetentionState.REMOTE_DELETE_PENDING, retentionOf(id))
        }

    @Test
    fun `a fatal check marks the revision as failed`() =
        runBlocking {
            val (id, _) = pendingDeletion()

            processorWith(ArchiveDeletionVerifier { ArchiveAbsenceVerification.Unconfirmed("rclone could not authenticate") })
                .verifyDueAbsence()

            val failed = revision(id)
            assertEquals(ChapterRetentionState.PRUNE_FAILED, failed.retentionState)
            assertEquals("rclone could not authenticate", failed.retentionLastError)
            assertNull(failed.retentionNextVerificationAt)
        }

    @Test
    fun `without a configured remote the deletion stays pending and unscheduled`() =
        runBlocking {
            val (id, _) = pendingDeletion()

            processorWith(ArchiveDeletionVerifier.NOT_CONFIGURED).verifyDueAbsence()

            val pending = revision(id)
            assertEquals(
                ChapterRetentionState.REMOTE_DELETE_PENDING,
                pending.retentionState,
                "a mount deletion is never proof of remote durability",
            )
            assertNull(pending.retentionNextVerificationAt, "nothing may be scheduled when nothing can confirm it")
        }

    @Test
    fun `reconciliation is requested per identity and swept by page`() {
        val seeded = seedIdentity(historicalCount = 0, retentionPolicy = 0)
        val processor =
            ChapterRevisionRetentionProcessor(
                archiveRoot = { archiveRoot },
                verifier = { ArchiveDeletionVerifier.NOT_CONFIGURED },
                retryIntervalSeconds = { 300 },
                leaseSeconds = { 60 },
                now = { clock },
                reconciliationBatch = 1,
            )

        assertFalse(processor.hasPendingReconciliation())
        assertFalse(processor.reconcilePending())

        processor.requestIdentity(seeded.chapterKey)
        assertTrue(processor.hasPendingReconciliation())
        assertTrue(processor.reconcilePending())
        assertFalse(processor.hasPendingReconciliation())

        processor.requestSweep()
        // one identity per page, so the sweep needs a page per identity and one final empty page
        assertTrue(processor.reconcilePending())
        assertTrue(processor.reconcilePending())
        assertFalse(processor.hasPendingReconciliation())
    }

    // -----------------------------------------------------------------------------------------
    // scheduling, wake and loop lifecycle
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an unscheduled pending deletion is not claimable and is made due by scheduling`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        val id = seeded.historicalIds.single()
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[retentionState] = ChapterRetentionState.REMOTE_DELETE_PENDING.name
                it[retentionNextVerificationAt] = null
            }
        }

        assertNull(
            ChapterRevision.claimNextDueRetentionVerification(clock, leaseSeconds = 60),
            "NULL means unscheduled, never due",
        )
        assertNull(ChapterRevision.nextRetentionDueAt(clock), "an unscheduled row must not make the worker wake for it")

        assertEquals(1, ChapterRevision.schedulePendingRetentionVerifications(clock))
        assertEquals(id, ChapterRevision.claimNextDueRetentionVerification(clock, leaseSeconds = 60)?.id)
    }

    @Test
    fun `without a configured remote a pending deletion is attempted once and then the loop idles`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        val id = seeded.historicalIds.single()
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[retentionState] = ChapterRetentionState.REMOTE_DELETE_PENDING.name
                it[retentionNextVerificationAt] = null
            }
        }

        val loop =
            ChapterRevisionRetentionLoop(
                processor =
                    ChapterRevisionRetentionProcessor(
                        archiveRoot = { archiveRoot },
                        verifier = { ArchiveDeletionVerifier.NOT_CONFIGURED },
                        retryIntervalSeconds = { 300 },
                        leaseSeconds = { 60 },
                        now = { clock },
                    ),
                now = { clock },
            )

        loop.start()
        try {
            assertTrue(
                awaitUntil { revision(id).retentionAttempts >= 1 && revision(id).retentionNextVerificationAt == null },
                "the scheduled deletion is checked once",
            )
            assertEquals(
                ChapterRetentionState.REMOTE_DELETE_PENDING,
                retentionOf(id),
                "without a remote the deletion can never be confirmed",
            )
            assertNull(revision(id).retentionNextVerificationAt, "nothing may be scheduled while nothing can confirm it")

            runBlocking { delay(300) }
            assertEquals(1, revision(id).retentionAttempts, "the loop idles instead of re-claiming the same row")
        } finally {
            loop.stop()
        }
    }

    @Test
    fun `configuring the remote makes an unscheduled deletion due and the loop confirms it`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        val id = seeded.historicalIds.single()
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[retentionState] = ChapterRetentionState.REMOTE_DELETE_PENDING.name
                it[retentionNextVerificationAt] = null
            }
        }

        var remoteConfigured = false
        val loop =
            ChapterRevisionRetentionLoop(
                processor =
                    ChapterRevisionRetentionProcessor(
                        archiveRoot = { archiveRoot },
                        verifier = {
                            if (remoteConfigured) {
                                ArchiveDeletionVerifier { ArchiveAbsenceVerification.ConfirmedAbsent }
                            } else {
                                ArchiveDeletionVerifier.NOT_CONFIGURED
                            }
                        },
                        retryIntervalSeconds = { 300 },
                        leaseSeconds = { 60 },
                        now = { clock },
                    ),
                now = { clock },
            )

        loop.start()
        try {
            assertTrue(
                awaitUntil { revision(id).retentionAttempts >= 1 && revision(id).retentionNextVerificationAt == null },
                "the deletion is first checked while disabled",
            )
            assertEquals(ChapterRetentionState.REMOTE_DELETE_PENDING, retentionOf(id))

            remoteConfigured = true
            loop.configurationChanged()

            assertTrue(
                awaitUntil { retentionOf(id) == ChapterRetentionState.PRUNED },
                "a newly configured remote has to be picked up without a restart",
            )
        } finally {
            loop.stop()
        }
    }

    @Test
    fun `a lone undeletable payload is deferred and never reclaimed in a hot loop`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        val id = seeded.historicalIds.single()

        val loop =
            ChapterRevisionRetentionLoop(
                processor =
                    ChapterRevisionRetentionProcessor(
                        archiveRoot = { archiveRoot },
                        verifier = { ArchiveDeletionVerifier.NOT_CONFIGURED },
                        retryIntervalSeconds = { 300 },
                        leaseSeconds = { 60 },
                        now = { clock },
                        deleteArchivedCbz = { _, key -> throw java.io.IOException("the payload $key is still mapped") },
                    ),
                now = { clock },
            )

        loop.start()
        try {
            assertTrue(
                awaitUntil { revision(id).retentionAttempts >= 1 && retentionOf(id) == ChapterRetentionState.PRUNE_QUEUED },
                "the failed deletion is deferred back into the queue",
            )
            assertEquals(ChapterRetentionState.PRUNE_QUEUED, retentionOf(id), "a failed deletion stays queued")
            assertEquals(clock + 300, revision(id).retentionQueuedAt, "it is deferred at least one retry interval")
            assertEquals(clock + 300, ChapterRevision.nextRetentionDueAt(clock), "the worker sleeps until then")

            runBlocking { delay(300) }
            assertEquals(1, revision(id).retentionAttempts, "a lone undeletable row must never be reclaimed in a hot loop")
        } finally {
            loop.stop()
        }
    }

    @Test
    fun `the retention loop sweeps the published library once at startup so a lost wake is recovered`() {
        val seeded = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        val id = seeded.historicalIds.single()
        assertEquals(ChapterRetentionState.RETAINED, retentionOf(id), "unreconciled, exactly as after a crash")

        val loop =
            ChapterRevisionRetentionLoop(
                processor =
                    ChapterRevisionRetentionProcessor(
                        archiveRoot = { archiveRoot },
                        verifier = { ArchiveDeletionVerifier.NOT_CONFIGURED },
                        retryIntervalSeconds = { 300 },
                        leaseSeconds = { 60 },
                        now = { clock },
                    ),
                now = { clock },
            )

        loop.start()
        try {
            assertTrue(
                awaitUntil { retentionOf(id) != ChapterRetentionState.RETAINED },
                "the startup sweep has to reconcile an identity whose retention wake was lost",
            )
        } finally {
            loop.stop()
        }
    }

    @Test
    fun `a per-series override reconciles only the identities of the changed series`() {
        val changed = seedIdentity(historicalCount = 1, retentionPolicy = 0)
        val untouched = seedIdentity(historicalCount = 1, retentionPolicy = 0)

        val processor =
            ChapterRevisionRetentionProcessor(
                archiveRoot = { archiveRoot },
                verifier = { ArchiveDeletionVerifier.NOT_CONFIGURED },
                retryIntervalSeconds = { 300 },
                leaseSeconds = { 60 },
                now = { clock },
            )

        processor.requestMangas(listOf(changed.mangaId))
        assertTrue(processor.hasPendingReconciliation())
        while (processor.reconcilePending()) {
            // drain only the queued identities: no sweep was requested
        }
        assertFalse(processor.hasPendingReconciliation())

        assertEquals(ChapterRetentionState.PRUNE_QUEUED, retentionOf(changed.historicalIds.single()))
        assertEquals(
            ChapterRetentionState.RETAINED,
            retentionOf(untouched.historicalIds.single()),
            "an unrelated series is never reconciled by a per-series override",
        )
    }

    @Test
    fun `a failed deletion whose payload is gone does not consume a retention slot`() {
        val seeded = seedIdentity(historicalCount = 3, retentionPolicy = 1)
        ChapterRevision.reconcileRetention(seeded.chapterKey, clock)
        // newest is retained; the two older ones are queued
        assertEquals(ChapterRetentionState.RETAINED, retentionOf(seeded.historicalIds[2]))
        val goneId = seeded.historicalIds[0]
        assertEquals(ChapterRetentionState.PRUNE_QUEUED, retentionOf(goneId))

        // the mounted payload is deleted, then the remote absence check fails fatally
        ChapterRevision.claimNextPruneQueued(clock)
        ChapterRevision.markRemoteDeletePending(goneId, clock)
        ChapterRevision.markPruneFailed(goneId, "rclone could not authenticate", clock)

        transaction {
            MangaTable.update({ MangaTable.id eq seeded.mangaId }) { it[acceptedRevisionRetention] = 3 }
        }
        ChapterRevision.reconcileRetention(seeded.chapterKey)

        assertEquals(
            ChapterRetentionState.PRUNE_FAILED,
            retentionOf(goneId),
            "a revision whose deletion already completed is never returned to the window",
        )
        assertEquals(ChapterRetentionState.RETAINED, retentionOf(seeded.historicalIds[1]))
        assertEquals(ChapterRetentionState.RETAINED, retentionOf(seeded.historicalIds[2]))

        // retrying it must not silently return the gone row to the window either
        assertEquals(listOf(goneId), ChapterRevision.retryPruning(listOf(goneId), clock).map { it.id })
        ChapterRevision.reconcileRetention(seeded.chapterKey)
        assertEquals(
            ChapterRetentionState.PRUNE_QUEUED,
            retentionOf(goneId),
            "a retried deletion whose payload is already gone stays outside the window",
        )
    }

    private fun awaitUntil(
        timeoutMillis: Long = 30_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(10)
        }
        return condition()
    }
}

class ChapterRevisionAbsenceVerificationTest {
    private val key = "c".repeat(64)

    private val artifact =
        ChapterRevisionArchiveArtifact(
            candidateKey = key,
            relativeCbzPath = ChapterRevisionArchiveArtifacts.relativeCbzPath(key),
            relativeManifestPath = ChapterRevisionArchiveArtifacts.relativeManifestPath(key),
            cbzSha256 = "d".repeat(64),
            cbzSize = 10,
            manifestSha256 = "e".repeat(64),
            manifestSize = 20,
        )

    private class RecordingRunner(
        private val result: ArchiveRemoteCommandResult,
    ) : ArchiveRemoteCommandRunner {
        val commands = CopyOnWriteArrayList<List<String>>()

        override suspend fun run(
            command: List<String>,
            timeout: Duration,
        ): ArchiveRemoteCommandResult {
            commands.add(command)
            return result
        }
    }

    private fun verifier(runner: ArchiveRemoteCommandRunner) =
        RcloneArchiveCommitVerifier(
            executable = "rclone",
            remoteRoot = "secret-remote:bucket/library",
            timeout = 30.seconds,
            runner = runner,
        )

    private fun verify(result: ArchiveRemoteCommandResult): ArchiveAbsenceVerification =
        runBlocking { verifier(RecordingRunner(result)).verifyAbsent(artifact) }

    private fun listing(vararg names: String): String =
        names.joinToString(",", "[", "]") { """{"Path":"${artifact.candidateKey}/$it","Name":"$it","Size":10}""" }

    @Test
    fun `a missing directory proves the payload is gone`() {
        assertEquals(ArchiveAbsenceVerification.ConfirmedAbsent, verify(ArchiveRemoteCommandResult(exitCode = 3)))
        assertEquals(ArchiveAbsenceVerification.ConfirmedAbsent, verify(ArchiveRemoteCommandResult(exitCode = 4)))
    }

    @Test
    fun `a listing without the payload proves the payload is gone while the manifest is kept`() {
        val result =
            ArchiveRemoteCommandResult(
                exitCode = 0,
                standardOutput = listing("$key.archive.json"),
            )

        assertEquals(
            ArchiveAbsenceVerification.ConfirmedAbsent,
            verify(result),
            "the retained manifest must never be mistaken for the payload",
        )
    }

    @Test
    fun `a listing that still contains the payload is retryable`() {
        val result =
            ArchiveRemoteCommandResult(
                exitCode = 0,
                standardOutput = listing("$key.cbz", "$key.archive.json"),
            )

        val outcome = verify(result)
        assertTrue(outcome is ArchiveAbsenceVerification.StillPresent, "a still listed payload is not a failure")
    }

    @Test
    fun `a timeout stays pending`() {
        val outcome = verify(ArchiveRemoteCommandResult(timedOut = true))
        assertTrue(outcome is ArchiveAbsenceVerification.Pending)
    }

    @Test
    fun `a temporary remote error stays pending`() {
        val outcome = verify(ArchiveRemoteCommandResult(exitCode = 5, standardError = "temporary failure"))
        assertTrue(outcome is ArchiveAbsenceVerification.Pending)
    }

    @Test
    fun `an authentication failure is unconfirmed and never leaks the configured remote`() {
        val outcome =
            verify(
                ArchiveRemoteCommandResult(
                    exitCode = 8,
                    standardError = "Failed to authenticate with secret-remote:bucket/library",
                ),
            )

        assertTrue(outcome is ArchiveAbsenceVerification.Unconfirmed)
        val reason = (outcome as ArchiveAbsenceVerification.Unconfirmed).reason
        assertFalse(reason.contains("secret-remote"), "the configured remote must be redacted from a persisted reason")
        assertTrue(reason.contains("<remote>"))
    }

    @Test
    fun `an unusable listing is unconfirmed`() {
        val outcome = verify(ArchiveRemoteCommandResult(exitCode = 0, standardOutput = "not json"))
        assertTrue(outcome is ArchiveAbsenceVerification.Unconfirmed)
    }

    @Test
    fun `an unstartable command is unconfirmed`() {
        val outcome = verify(ArchiveRemoteCommandResult(startFailure = "rclone is not installed"))
        assertTrue(outcome is ArchiveAbsenceVerification.Unconfirmed)
    }

    @Test
    fun `checks the payload through the configured remote and never through a local path`() {
        val runner = RecordingRunner(ArchiveRemoteCommandResult(exitCode = 3))
        runBlocking { verifier(runner).verifyAbsent(artifact) }

        val command = runner.commands.single()
        assertEquals("rclone", command.first())
        assertEquals("lsjson", command[1])
        assertEquals(
            "secret-remote:bucket/library/revisions/$key",
            command[2],
            "an absence check has to ask the remote directly, never the mounted copy",
        )
    }
}
