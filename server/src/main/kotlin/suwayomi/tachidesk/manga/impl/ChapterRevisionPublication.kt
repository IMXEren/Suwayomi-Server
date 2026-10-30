package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Sibling temporary file of exactly one publication attempt.
 *
 * The name carries the immutable revision identity and the attempt counter, which the claim
 * increments for every attempt. A stale worker can therefore only ever delete or move a file it
 * wrote itself, never the temporary file a newer attempt of the same chapter identity is using.
 */
internal fun publicationTemporaryFile(
    destination: File,
    revision: ChapterRevisionDataClass,
): File =
    File(
        destination.parentFile,
        ".${destination.name}.${revision.candidateKey}.${revision.id}.${revision.publicationAttempts}.tmp",
    )

/** Raised when the bytes published into the active library view do not match what was accepted. */
class ChapterRevisionPublicationConflictException(
    message: String,
) : Exception(message)

/**
 * Makes the active accepted revision of a chapter visible in the archive's `library/` view.
 *
 * The copy source is the immutable, remotely-confirmed CBZ under `revisions/<candidateKey>`, never
 * the local staging copy, so only content whose durability was confirmed can become visible. The
 * copy is written beside its destination and verified before it atomically replaces it, so a reader
 * never observes a half-written chapter and a failed attempt leaves the previous active copy in
 * place.
 *
 * Publication is generic: it emits a durable post-publication event and knows nothing about who
 * consumes it.
 */
class ChapterRevisionPublicationProcessor(
    private val archiveRoot: () -> File,
    /**
     * Called after a publication committed, outside any transaction.
     *
     * Publication is what makes an older revision leave the active slot, so it is also what can move
     * that revision outside its retention window; the retention worker owns what happens next.
     */
    private val onPublished: (ChapterRevisionDataClass) -> Unit = {},
) {
    suspend fun process(
        claimed: ChapterRevisionDataClass,
        now: Long = Instant.now().epochSecond,
    ) {
        try {
            if (publish(claimed, now)) {
                onPublished(claimed)
                // delivery happens strictly after the publication transaction committed
                ChapterRevisionPublicationEvents.deliverPending(now)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to publish chapter revision ${claimed.id}" }
            ChapterRevision.markPublicationFailed(claimed.id, e.message ?: e.javaClass.simpleName, now)
        }
    }

    private fun publish(
        claimed: ChapterRevisionDataClass,
        now: Long,
    ): Boolean {
        if (!claimed.isActiveRevision) {
            // a revision that stopped being the active one must never become visible
            return ChapterRevision.releasePublicationClaim(claimed.id, now)
        }

        val mangaId = claimed.mangaId
        val expectedHash = claimed.archiveCbzHash
        val expectedSize = claimed.archiveCbzSize
        if (mangaId == null || expectedHash == null || expectedSize == null) {
            ChapterRevision.markPublicationFailed(
                claimed.id,
                "the archived artifacts of the active revision are not recorded",
                now,
            )
            return false
        }

        val root = archiveRoot()
        val source = ChapterRevisionArchiveArtifacts.cbzFile(root, claimed.candidateKey)
        val relativeActivePath = ChapterRevisionLibrary.relativeCbzPath(mangaId, claimed.chapterKey)
        val destination = ChapterRevisionLibrary.cbzFile(root, mangaId, claimed.chapterKey)
        val temporary = publicationTemporaryFile(destination, claimed)

        try {
            temporary.parentFile?.mkdirs()
            temporary.delete()
            copy(source, temporary)

            val copied = ChapterRevisionArchiveArtifacts.digestOf(temporary)
            if (copied.sha256 != expectedHash || copied.size != expectedSize) {
                throw ChapterRevisionPublicationConflictException(
                    "the archived copy of revision ${claimed.id} does not match its recorded digest",
                )
            }

            val published =
                ChapterRevision.publishActiveCopy(
                    id = claimed.id,
                    relativeActivePath = relativeActivePath,
                    now = now,
                    replaceActiveCopy = {
                        ChapterRevisionStaging.moveAtomicallyReplacing(temporary, destination)
                        ChapterRevisionArchiveArtifacts.digestOf(destination)
                    },
                    onPublished = { revision, replacedActiveCbzPath ->
                        ChapterRevisionPublicationEvents.recordPublished(revision, replacedActiveCbzPath, now)
                    },
                )

            if (!published) {
                // fenced out: this revision is no longer the active one, so the claim is released
                return ChapterRevision.releasePublicationClaim(claimed.id, now)
            }

            return true
        } finally {
            // no-op once the temporary file became the destination
            temporary.delete()
        }
    }

    private fun copy(
        source: File,
        target: File,
    ) {
        if (!source.isFile) {
            throw IOException("the archived copy of the revision is missing: ${source.path}")
        }

        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }
}

/**
 * Single-concurrency publication worker.
 *
 * It only ever claims active, remotely-confirmed revisions, so an accepted-but-unconfirmed candidate
 * and a superseded revision both stay invisible. Undelivered post-publication events are retried on a
 * bounded interval, because a listener that was down has no other way to make its retry happen.
 */
class ChapterRevisionPublicationLoop(
    private val processor: ChapterRevisionPublicationProcessor,
) {
    private val worker =
        ChapterRevisionWorkerLoop(
            workerName = "chapter revision publication",
            beforeFirstDrain = {
                ChapterRevision.recoverInterruptedPublications()
                // remote confirmation and activation are two commits, so a crash between them would
                // otherwise leave a confirmed candidate invisible forever; repair that here, before
                // the first drain, so the loop immediately publishes whatever it activates
                ChapterRevision.reconcileConfirmedActivations()
            },
            idleTimeoutMillis = { if (ChapterRevisionPublicationEvents.hasPending()) EVENT_RETRY_MILLIS else null },
            drainOnce = { drainOnce() },
        )

    fun start() = worker.start()

    fun stop() = worker.stop()

    fun notifyWorkAvailable() = worker.notifyWorkAvailable()

    /** Publishes one revision, or delivers one pending event; false when there is nothing to do. */
    internal suspend fun drainOnce(): Boolean {
        val claimed = ChapterRevision.claimNextPublication()
        if (claimed != null) {
            try {
                processor.process(claimed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // one broken revision must never end the loop; the startup recovery re-queues it
                logger.error(e) { "Failed to publish chapter revision ${claimed.id}" }
            }
            return true
        }

        return ChapterRevisionPublicationEvents.deliverPending()
    }

    private companion object {
        /** Bounded so a listener that keeps failing is retried without spinning the worker. */
        const val EVENT_RETRY_MILLIS = 60_000L
    }
}

/**
 * Single, global active-library publication worker.
 *
 * It is deliberately separate from the archive commit and the remote verifier: publication only
 * reacts to an already confirmed revision, and a failure here never changes how a chapter was
 * acquired or archived.
 */
object ChapterRevisionPublicationExecutor {
    private val applicationDirs: ApplicationDirs by injectLazy()

    private val loop by lazy {
        ChapterRevisionPublicationLoop(
            ChapterRevisionPublicationProcessor(
                archiveRoot = { File(applicationDirs.archiveRoot) },
                onPublished = { revision -> ChapterRevisionRetentionExecutor.requestIdentity(revision.chapterKey) },
            ),
        )
    }

    fun start() = loop.start()

    fun stop() = loop.stop()

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()

    /**
     * Requeues revisions whose publication failed, preserving their attempt count.
     *
     * The published copy is left alone on purpose: it is still the active content of that chapter, and
     * the retry replaces it atomically once the new revision can be written.
     */
    fun retry(ids: List<Int>): List<ChapterRevisionDataClass> {
        val retried = ChapterRevision.retryPublications(ids)
        if (retried.isEmpty()) {
            return emptyList()
        }

        notifyWorkAvailable()
        return retried
    }
}
