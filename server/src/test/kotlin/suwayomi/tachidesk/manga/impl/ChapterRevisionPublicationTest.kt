package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionReviewAction
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ChapterPublicationEventTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ChapterRevisionPublicationTest : ApplicationTest() {
    private lateinit var archiveRoot: File
    private val processor get() = ChapterRevisionPublicationProcessor(archiveRoot = { archiveRoot })

    @BeforeEach
    fun setUp() {
        archiveRoot = File("build/tmp/publication-${UUID.randomUUID()}").absoluteFile
        archiveRoot.mkdirs()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private data class Seeded(
        val mangaId: Int,
        val chapter: ChapterDataClass,
        val chapterKey: String,
        val revisionId: Int,
        val candidateKey: String,
        val bytes: ByteArray,
    )

    /**
     * Builds a remotely-confirmed revision with a real archived CBZ, the exact state the
     * publication worker is allowed to claim. With [active] false it stays a candidate, which is
     * the state a crash between remote confirmation and activation leaves behind.
     */
    private fun seedConfirmedActiveRevision(
        title: String,
        bytes: ByteArray,
        active: Boolean = true,
    ): Seeded {
        val mangaId = createLibraryManga(title).also { createChapters(it, 1, read = false) }
        transaction { MangaTable.update({ MangaTable.id eq mangaId }) { it[acquisitionPolicy] = MangaAcquisitionPolicy.MANUAL.name } }
        val chapter =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga eq mangaId }
                    .map { ChapterTable.toDataClass(it) }
                    .single()
            }
        val id =
            transaction {
                val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
                ChapterRevision.createCandidatesForNewChapters(mangaEntry, listOf(chapter), 1000).single()
            }
        val revision = ChapterRevision.getRevision(id)!!

        val cbz = ChapterRevisionArchiveArtifacts.cbzFile(archiveRoot, revision.candidateKey)
        cbz.parentFile.mkdirs()
        cbz.writeBytes(bytes)

        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[acquisitionState] = ChapterAcquisitionState.COMPLETE.name
                it[archiveState] = ChapterArchiveState.REMOTE_CONFIRMED.name
                it[archiveCbzPath] = ChapterRevisionArchiveArtifacts.relativeCbzPath(revision.candidateKey)
                it[archiveCbzHash] = sha256(bytes)
                it[archiveCbzSize] = bytes.size.toLong()
                it[disposition] = if (active) ChapterRevisionDisposition.ACCEPTED.name else ChapterRevisionDisposition.CANDIDATE.name
                it[activeChapterKey] = revision.chapterKey.takeIf { active }
                it[acceptedAt] = 1000L.takeIf { active }
                it[activatedAt] = 1000L.takeIf { active }
            }
        }

        return Seeded(mangaId, chapter, revision.chapterKey, id, revision.candidateKey, bytes)
    }

    /** Adds a second, remotely-confirmed candidate revision to an already seeded chapter identity. */
    private fun addConfirmedCandidateOf(
        seeded: Seeded,
        name: String,
        bytes: ByteArray,
        now: Long,
    ): Int {
        val id =
            transaction {
                val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq seeded.mangaId }.first()
                ChapterRevision
                    .createCandidates(
                        sourceId = mangaEntry[MangaTable.sourceReference],
                        sourceMangaUrl = mangaEntry[MangaTable.url],
                        policy = MangaAcquisitionPolicy.MANUAL,
                        chapters = listOf(seeded.chapter.copy(name = name)),
                        now = now,
                    ).single()
            }
        val candidateKey = ChapterRevision.getRevision(id)!!.candidateKey
        assertEquals(seeded.chapterKey, ChapterRevision.getRevision(id)!!.chapterKey, "the snapshot is a revision of the same identity")
        val cbz = ChapterRevisionArchiveArtifacts.cbzFile(archiveRoot, candidateKey)
        cbz.parentFile.mkdirs()
        cbz.writeBytes(bytes)
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                it[acquisitionState] = ChapterAcquisitionState.COMPLETE.name
                it[archiveState] = ChapterArchiveState.REMOTE_CONFIRMED.name
                it[archiveCbzPath] = ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey)
                it[archiveCbzHash] = sha256(bytes)
                it[archiveCbzSize] = bytes.size.toLong()
            }
        }
        return id
    }

    private fun activeCopyFile(seeded: Seeded): File = ChapterRevisionLibrary.cbzFile(archiveRoot, seeded.mangaId, seeded.chapterKey)

    private fun publicationState(id: Int): ChapterPublicationState = ChapterRevision.getRevision(id)!!.publicationState

    @Test
    fun `publishes the exact confirmed bytes into the library view and records an event`() {
        val bytes = "confirmed chapter bytes".toByteArray()
        val seeded = seedConfirmedActiveRevision("PUBLISH_OK", bytes)

        val claimed = ChapterRevision.claimNextPublication(now = 2000)!!
        assertEquals(seeded.revisionId, claimed.id)
        assertEquals(ChapterPublicationState.PUBLISHING, claimed.publicationState)
        assertEquals(1, claimed.publicationAttempts)

        runBlocking { processor.process(claimed, now = 3000) }

        val published = ChapterRevision.getRevision(seeded.revisionId)!!
        assertEquals(ChapterPublicationState.PUBLISHED, published.publicationState)
        assertNotNull(published.publishedAt)
        assertEquals(sha256(bytes), published.activeCbzHash)
        assertEquals(bytes.size.toLong(), published.activeCbzSize)

        assertArrayEquals(bytes, activeCopyFile(seeded).readBytes(), "the library copy must be the exact archived bytes")

        // the published event is durable and, with no listener, is delivered immediately in-process
        val event =
            transaction {
                ChapterPublicationEventTable
                    .selectAll()
                    .where { ChapterPublicationEventTable.revision eq seeded.revisionId }
                    .single()
            }
        assertNotNull(event[ChapterPublicationEventTable.deliveredAt])
    }

    @Test
    fun `refuses to publish a copy whose bytes do not match the recorded digest`() {
        val bytes = "real bytes".toByteArray()
        val seeded = seedConfirmedActiveRevision("PUBLISH_HASH_MISMATCH", bytes)
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq seeded.revisionId }) {
                it[archiveCbzHash] =
                    sha256("tampered".toByteArray())
            }
        }

        val claimed = ChapterRevision.claimNextPublication()!!
        runBlocking { processor.process(claimed, now = 3000) }

        val failed = ChapterRevision.getRevision(seeded.revisionId)!!
        assertEquals(ChapterPublicationState.PUBLICATION_FAILED, failed.publicationState)
        assertNotNull(failed.publicationLastError)
        assertFalse(activeCopyFile(seeded).exists(), "a mismatching copy must never become the active chapter")
    }

    @Test
    fun `refuses to publish a copy whose size does not match`() {
        val bytes = "sized bytes".toByteArray()
        val seeded = seedConfirmedActiveRevision("PUBLISH_SIZE_MISMATCH", bytes)
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq seeded.revisionId }) { it[archiveCbzSize] = bytes.size.toLong() + 1 }
        }

        runBlocking { processor.process(ChapterRevision.claimNextPublication()!!, now = 3000) }

        assertEquals(ChapterPublicationState.PUBLICATION_FAILED, publicationState(seeded.revisionId))
        assertFalse(activeCopyFile(seeded).exists())
    }

    @Test
    fun `a missing archived copy fails the publication instead of writing a partial file`() {
        val seeded = seedConfirmedActiveRevision("PUBLISH_MISSING", "bytes".toByteArray())
        assertTrue(ChapterRevisionArchiveArtifacts.cbzFile(archiveRoot, seeded.candidateKey).delete())

        runBlocking { processor.process(ChapterRevision.claimNextPublication()!!, now = 3000) }

        assertEquals(ChapterPublicationState.PUBLICATION_FAILED, publicationState(seeded.revisionId))
        assertFalse(activeCopyFile(seeded).exists())
    }

    @Test
    fun `publishing a newer revision supersedes the file and demotes the previous publication`() {
        val mangaId = createLibraryManga("PUBLISH_REPLACE").also { createChapters(it, 1, read = false) }
        transaction { MangaTable.update({ MangaTable.id eq mangaId }) { it[acquisitionPolicy] = MangaAcquisitionPolicy.MANUAL.name } }
        val chapter =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga eq mangaId }
                    .map { ChapterTable.toDataClass(it) }
                    .single()
            }

        // builds a remotely-confirmed revision that is still a candidate; activation happens via review
        fun addConfirmedRevision(
            name: String,
            bytes: ByteArray,
        ): Int {
            val id =
                transaction {
                    val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
                    ChapterRevision
                        .createCandidates(
                            sourceId = mangaEntry[MangaTable.sourceReference],
                            sourceMangaUrl = mangaEntry[MangaTable.url],
                            policy = MangaAcquisitionPolicy.MANUAL,
                            chapters = listOf(chapter.copy(name = name, uploadDate = if (name == "a") 1 else 2)),
                            now = if (name == "a") 1000 else 2000,
                        ).single()
                }
            val candidateKey = ChapterRevision.getRevision(id)!!.candidateKey
            val cbz = ChapterRevisionArchiveArtifacts.cbzFile(archiveRoot, candidateKey)
            cbz.parentFile.mkdirs()
            cbz.writeBytes(bytes)
            transaction {
                ChapterRevisionTable.update({ ChapterRevisionTable.id eq id }) {
                    it[acquisitionState] = ChapterAcquisitionState.COMPLETE.name
                    it[archiveState] = ChapterArchiveState.REMOTE_CONFIRMED.name
                    it[archiveCbzPath] = ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey)
                    it[archiveCbzHash] = sha256(bytes)
                    it[archiveCbzSize] = bytes.size.toLong()
                }
            }
            return id
        }

        val firstBytes = "first revision".toByteArray()
        val firstId = addConfirmedRevision("a", firstBytes)
        val chapterKey = ChapterRevision.getRevision(firstId)!!.chapterKey
        assertTrue(ChapterRevision.review(listOf(firstId), ChapterRevisionReviewAction.ACCEPT_CANDIDATE, now = 2500).isNotEmpty())
        runBlocking { processor.process(ChapterRevision.claimNextPublication()!!, now = 3000) }
        assertEquals(ChapterPublicationState.PUBLISHED, publicationState(firstId))

        // accept a newer revision: the first one becomes superseded but stays published for now
        val secondBytes = "second revision".toByteArray()
        val secondId = addConfirmedRevision("b", secondBytes)
        ChapterRevision.review(listOf(secondId), ChapterRevisionReviewAction.ACCEPT_CANDIDATE, now = 4000)
        assertEquals(ChapterRevisionDisposition.SUPERSEDED, ChapterRevision.getRevision(firstId)!!.disposition)

        runBlocking { processor.process(ChapterRevision.claimNextPublication()!!, now = 5000) }

        assertEquals(ChapterPublicationState.PUBLISHED, publicationState(secondId))
        assertEquals(
            ChapterPublicationState.NOT_PUBLISHED,
            publicationState(firstId),
            "the replaced revision must be demoted once its bytes are overwritten",
        )
        assertArrayEquals(secondBytes, ChapterRevisionLibrary.cbzFile(archiveRoot, mangaId, chapterKey).readBytes())
        assertNull(ChapterRevision.getRevision(firstId)!!.activeChapterKey)
    }

    @Test
    fun `a stale worker can never overwrite the active copy of a newer revision`() {
        val bytes = "active bytes".toByteArray()
        val seeded = seedConfirmedActiveRevision("PUBLISH_FENCE", bytes)
        runBlocking { processor.process(ChapterRevision.claimNextPublication()!!, now = 3000) }
        val publishedFile = activeCopyFile(seeded)
        val publishedDigest = sha256(publishedFile.readBytes())

        // the revision stopped being active (as a newer acceptance would do) but a claim is still out
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq seeded.revisionId }) {
                it[activeChapterKey] = null
                it[disposition] = ChapterRevisionDisposition.SUPERSEDED.name
                it[publicationState] = ChapterPublicationState.PUBLISHING.name
            }
        }

        val replaced = AtomicBoolean(false)
        val result =
            ChapterRevision.publishActiveCopy(
                id = seeded.revisionId,
                relativeActivePath = ChapterRevisionLibrary.relativeCbzPath(seeded.mangaId, seeded.chapterKey),
                now = 6000,
                replaceActiveCopy = {
                    replaced.set(true)
                    ChapterRevisionArtifactDigest(bytes.size.toLong(), sha256(bytes))
                },
            )

        assertFalse(result, "a fenced-out attempt must report failure")
        assertFalse(replaced.get(), "the stale attempt must never touch the active file")
        assertEquals(publishedDigest, sha256(publishedFile.readBytes()), "the newer active bytes survive")
    }

    @Test
    fun `recovers a publication a shutdown left in flight and republishes it`() {
        val bytes = "recovered".toByteArray()
        val seeded = seedConfirmedActiveRevision("PUBLISH_RECOVER", bytes)

        val claimed = ChapterRevision.claimNextPublication(now = 3000)!!
        assertEquals(seeded.revisionId, claimed.id)
        assertEquals(1, claimed.publicationAttempts)
        assertNull(ChapterRevision.claimNextPublication(now = 3500), "an in-flight revision is not claimable")
        assertEquals(1, ChapterRevision.recoverInterruptedPublications(now = 4000))

        val reclaimed = ChapterRevision.claimNextPublication(now = 5000)!!
        assertEquals(seeded.revisionId, reclaimed.id)
        assertEquals(2, reclaimed.publicationAttempts, "recovery does not reset the attempt count")

        runBlocking { processor.process(reclaimed, now = 6000) }
        assertEquals(ChapterPublicationState.PUBLISHED, publicationState(seeded.revisionId))
    }

    @Test
    fun `an already published revision is not published twice`() {
        val seeded = seedConfirmedActiveRevision("PUBLISH_IDEMPOTENT", "once".toByteArray())

        runBlocking { processor.process(ChapterRevision.claimNextPublication()!!, now = 3000) }
        assertEquals(ChapterPublicationState.PUBLISHED, publicationState(seeded.revisionId))
        assertEquals(1, ChapterRevision.getRevision(seeded.revisionId)!!.publicationAttempts)

        assertNull(ChapterRevision.claimNextPublication(now = 4000), "a published revision is never claimed again")
        assertEquals(0, ChapterRevision.recoverInterruptedPublications(now = 4000))
        assertEquals(ChapterPublicationState.PUBLISHED, publicationState(seeded.revisionId))
    }

    @Test
    fun `a failed publication can be retried end to end`() {
        val bytes = "retry bytes".toByteArray()
        val seeded = seedConfirmedActiveRevision("PUBLISH_RETRY", bytes)
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq seeded.revisionId }) {
                it[archiveCbzHash] =
                    sha256("wrong".toByteArray())
            }
        }

        runBlocking { processor.process(ChapterRevision.claimNextPublication()!!, now = 3000) }
        assertEquals(ChapterPublicationState.PUBLICATION_FAILED, publicationState(seeded.revisionId))

        assertTrue(ChapterRevision.retryPublications(listOf(seeded.revisionId), now = 4000).isNotEmpty())
        assertEquals(ChapterPublicationState.NOT_PUBLISHED, publicationState(seeded.revisionId))

        // fix the recorded digest and publish again
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq seeded.revisionId }) { it[archiveCbzHash] = sha256(bytes) }
        }
        val reclaimed = ChapterRevision.claimNextPublication(now = 5000)!!
        assertEquals(2, reclaimed.publicationAttempts)
        runBlocking { processor.process(reclaimed, now = 6000) }
        assertEquals(ChapterPublicationState.PUBLISHED, publicationState(seeded.revisionId))
    }

    @Test
    fun `only an active, remotely confirmed revision is eligible for publication`() {
        // a confirmed candidate that was never activated
        val candidateBytes = "candidate".toByteArray()
        val candidate = seedConfirmedActiveRevision("PUBLISH_ELIGIBLE", candidateBytes)
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq candidate.revisionId }) {
                it[activeChapterKey] = null
                it[disposition] = ChapterRevisionDisposition.CANDIDATE.name
            }
        }
        assertNull(ChapterRevision.claimNextPublication(), "a candidate that was never activated is not publishable")

        // an accepted revision whose durability was not confirmed
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq candidate.revisionId }) {
                it[activeChapterKey] = candidate.chapterKey
                it[disposition] = ChapterRevisionDisposition.ACCEPTED.name
                it[archiveState] = ChapterArchiveState.REMOTE_PENDING.name
            }
        }
        assertNull(ChapterRevision.claimNextPublication(), "an unconfirmed revision must never become visible")
    }

    @Test
    fun `a failing listener never rolls back the publication and the event stays retryable`() {
        val bytes = "with listener".toByteArray()
        val seeded = seedConfirmedActiveRevision("PUBLISH_LISTENER", bytes)

        val attempts = AtomicInteger()
        val failing =
            ChapterRevisionPublicationListener { _ ->
                attempts.incrementAndGet()
                throw IllegalStateException("listener down")
            }
        ChapterRevisionPublicationListenerRegistry.register(failing)
        try {
            runBlocking { processor.process(ChapterRevision.claimNextPublication()!!, now = 3000) }

            assertEquals(ChapterPublicationState.PUBLISHED, publicationState(seeded.revisionId), "delivery failure must not roll back")
            assertEquals(1, attempts.get())

            val undelivered =
                transaction {
                    ChapterPublicationEventTable
                        .selectAll()
                        .where { ChapterPublicationEventTable.revision eq seeded.revisionId }
                        .single()
                }
            assertNull(undelivered[ChapterPublicationEventTable.deliveredAt])
            assertNotNull(undelivered[ChapterPublicationEventTable.lastError])
            assertTrue(ChapterRevisionPublicationEvents.hasPending())
        } finally {
            ChapterRevisionPublicationListenerRegistry.unregister(failing)
        }

        // at-least-once: a healthy listener receives the same event on the retry
        val received = CopyOnWriteArrayList<Int>()
        val healthy = ChapterRevisionPublicationListener { event -> received.add(event.payload.revisionId) }
        ChapterRevisionPublicationListenerRegistry.register(healthy)
        try {
            val delivered = runBlocking { ChapterRevisionPublicationEvents.deliverPending(now = 5000) }
            assertTrue(delivered)
            assertEquals(listOf(seeded.revisionId), received.toList())
            assertFalse(ChapterRevisionPublicationEvents.hasPending())
        } finally {
            ChapterRevisionPublicationListenerRegistry.unregister(healthy)
        }
    }

    @Test
    fun `the outbox keeps its events durable across a restart`() {
        val seeded = seedConfirmedActiveRevision("PUBLISH_OUTBOX_RESTART", "durable".toByteArray())
        // stop before delivery by leaving a listener that fails, which is the only way an event stays pending
        val failing = ChapterRevisionPublicationListener { throw IllegalStateException("down") }
        ChapterRevisionPublicationListenerRegistry.register(failing)
        try {
            runBlocking { processor.process(ChapterRevision.claimNextPublication()!!, now = 3000) }
        } finally {
            ChapterRevisionPublicationListenerRegistry.unregister(failing)
        }

        // a fresh read (as a restarted process would do) still finds the event
        val pending =
            transaction {
                ChapterPublicationEventTable
                    .selectAll()
                    .where { ChapterPublicationEventTable.deliveredAt.isNull() }
                    .single()
            }
        assertEquals(seeded.revisionId, pending[ChapterPublicationEventTable.revision]?.value)
        assertTrue(ChapterRevisionPublicationEvents.hasPending())
    }

    @Test
    fun `a stale attempt and a new attempt never share a temporary file`() {
        val bytes = "new bytes".toByteArray()
        val seeded = seedConfirmedActiveRevision("PUBLISH_TEMP_ISOLATION", bytes)
        val claimed = ChapterRevision.claimNextPublication(now = 2000)!!
        val destination = activeCopyFile(seeded)
        val currentTemp = publicationTemporaryFile(destination, claimed)

        val staleTemp =
            publicationTemporaryFile(destination, claimed.copy(id = claimed.id - 1000, candidateKey = "0".repeat(64)))
        assertNotEquals(currentTemp, staleTemp, "the temporary name must carry the revision identity")
        assertNotEquals(
            currentTemp,
            publicationTemporaryFile(destination, claimed.copy(publicationAttempts = claimed.publicationAttempts + 1)),
            "the temporary name must carry the attempt counter",
        )

        // an older worker's file sits beside the destination while the newer attempt publishes
        staleTemp.parentFile.mkdirs()
        staleTemp.writeBytes("stale bytes".toByteArray())
        runBlocking { processor.process(claimed, now = 3000) }

        assertTrue(staleTemp.exists(), "the new attempt must never delete the stale worker's temporary file")
        assertArrayEquals(bytes, destination.readBytes())
        assertFalse(currentTemp.exists(), "the new attempt cleans up only its own temporary file")

        // the stale worker finishing only removes its own file, never the newly published copy
        assertTrue(staleTemp.delete())
        assertArrayEquals(bytes, destination.readBytes(), "a stale cleanup never touches the published copy")
    }

    @Test
    fun `a confirmed candidate left behind by a crash before activation is activated on restart`() {
        val seeded = seedConfirmedActiveRevision("PUBLISH_RECONCILE", "reconciled".toByteArray(), active = false)

        assertEquals(ChapterRevisionDisposition.CANDIDATE, ChapterRevision.getRevision(seeded.revisionId)!!.disposition)
        assertNull(ChapterRevision.getActiveRevision(seeded.chapterKey), "the crash left the identity without an active revision")

        assertEquals(listOf(seeded.revisionId), ChapterRevision.reconcileConfirmedActivations(now = 2500))
        assertEquals(seeded.revisionId, ChapterRevision.getActiveRevision(seeded.chapterKey)!!.id)

        // idempotent: a second restart finds the identity already active and changes nothing
        assertTrue(ChapterRevision.reconcileConfirmedActivations(now = 2600).isEmpty())
        assertEquals(seeded.revisionId, ChapterRevision.getActiveRevision(seeded.chapterKey)!!.id)
    }

    @Test
    fun `reconciliation never replaces an already active revision`() {
        val seeded = seedConfirmedActiveRevision("PUBLISH_RECONCILE_ACTIVE", "active".toByteArray())
        val candidateId = addConfirmedCandidateOf(seeded, "newer", "newer".toByteArray(), now = 2000)

        assertTrue(
            ChapterRevision.reconcileConfirmedActivations(now = 2500).isEmpty(),
            "a confirmed candidate must never replace the active revision without a decision",
        )
        assertEquals(seeded.revisionId, ChapterRevision.getActiveRevision(seeded.chapterKey)!!.id)
        assertEquals(ChapterRevisionDisposition.CANDIDATE, ChapterRevision.getRevision(candidateId)!!.disposition)
    }

    @Test
    fun `the publication loop reconciles and publishes a confirmed candidate after a restart`() {
        val seeded = seedConfirmedActiveRevision("PUBLISH_LOOP_RECONCILE", "loop".toByteArray(), active = false)
        val loop = ChapterRevisionPublicationLoop(processor)
        try {
            loop.start()
            assertTrue(
                awaitUntil(30_000) {
                    ChapterRevision.getRevision(seeded.revisionId)!!.publicationState == ChapterPublicationState.PUBLISHED
                },
                "the loop's startup reconciliation must activate and publish the confirmed candidate",
            )
            assertArrayEquals(seeded.bytes, activeCopyFile(seeded).readBytes())
        } finally {
            loop.stop()
        }
    }

    private fun awaitUntil(
        timeoutMillis: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(25)
        }
        return condition()
    }

    @AfterEach
    fun tearDown() {
        ChapterRevisionPublicationListenerRegistry.snapshot().forEach { ChapterRevisionPublicationListenerRegistry.unregister(it) }
        archiveRoot.deleteRecursively()
        clearTables(
            ChapterPublicationEventTable,
            ChapterRevisionTable,
            ChapterTable,
            MangaTable,
        )
    }
}
