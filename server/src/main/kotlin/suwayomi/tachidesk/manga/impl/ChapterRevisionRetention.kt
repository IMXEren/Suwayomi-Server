package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the retention window of a chapter identity in sync with the configured policy and removes
 * the archived payload of the revisions that fell outside it.
 *
 * Reconciliation and deletion deliberately live in one worker: whether a revision may be pruned can
 * only be decided from the history of its chapter identity, so splitting them would mean reading
 * that history in two places.
 *
 * Deletion through an object-storage mount only proves that the mount accepted the unlink, so a
 * deletion is complete only after remote storage itself stopped listing the payload. Until then the
 * revision stays in `REMOTE_DELETE_PENDING`.
 */
class ChapterRevisionRetentionProcessor(
    private val archiveRoot: () -> File,
    private val verifier: () -> ArchiveDeletionVerifier,
    private val retryIntervalSeconds: () -> Long,
    private val leaseSeconds: () -> Long,
    private val now: () -> Long = { Instant.now().epochSecond },
    private val deleteArchivedCbz: (File, String) -> Unit = { root, candidateKey ->
        ChapterRevisionArchiveArtifacts.deleteArchivedCbz(root, candidateKey)
    },
    private val reconciliationBatch: Int = RECONCILIATION_BATCH,
) {
    private val logger = KotlinLogging.logger {}

    private val lock = Any()
    private val pendingIdentities = LinkedHashSet<String>()
    private var sweepRequested = false
    private var sweepCursor: String? = null

    /** Queues one chapter identity whose revision history changed. */
    fun requestIdentity(chapterKey: String) {
        synchronized(lock) { pendingIdentities.add(chapterKey) }
    }

    /**
     * Queues a sweep of every published chapter identity.
     *
     * Used for a change that can affect an unknown set of identities - a global retention setting - or
     * to repair a lost retention wake at startup, because the sweep re-evaluates each identity against
     * its own effective policy anyway.
     */
    fun requestSweep() {
        synchronized(lock) {
            sweepRequested = true
            sweepCursor = null
        }
    }

    /**
     * Queues the published chapter identities of specific series.
     *
     * A per-series retention override only changes those series, so this is the targeted form of
     * [requestSweep] and never scans the rest of the library.
     */
    fun requestMangas(mangaIds: List<Int>) {
        if (mangaIds.isEmpty()) {
            return
        }

        val identities = ChapterRevision.publishedActiveRevisionIdentitiesForMangas(mangaIds)
        if (identities.isEmpty()) {
            return
        }

        synchronized(lock) { pendingIdentities.addAll(identities) }
    }

    fun hasPendingReconciliation(): Boolean = synchronized(lock) { sweepRequested || pendingIdentities.isNotEmpty() }

    /**
     * Reconciles one queued identity, or - when a sweep was requested - one page of the library.
     *
     * Returns true while reconciliation work remains, so the caller keeps draining instead of waiting
     * for a wake that no transition would send. One broken identity is logged and skipped rather than
     * ending the sweep.
     */
    fun reconcilePending(): Boolean {
        val identity = synchronized(lock) { pendingIdentities.firstOrNull()?.also { pendingIdentities.remove(it) } }
        if (identity != null) {
            reconcile(identity)
            return true
        }

        if (!synchronized(lock) { sweepRequested }) {
            return false
        }

        val page = ChapterRevision.publishedActiveRevisionIdentities(sweepCursor, reconciliationBatch)
        page.forEach { chapterKey -> reconcile(chapterKey) }

        synchronized(lock) {
            if (page.size < reconciliationBatch) {
                sweepRequested = false
                sweepCursor = null
            } else {
                sweepCursor = page.last()
            }
        }

        return true
    }

    /** Claims and prunes one revision; false when nothing is waiting to be pruned. */
    suspend fun pruneOne(): Boolean {
        val claimed = ChapterRevision.claimNextPruneQueued(now()) ?: return false

        try {
            // Re-check under the identity lock right before the filesystem is touched: a revision can
            // become the active one between the claim and here, and the payload of the active revision
            // must never be deleted. authorizePruneDeletion returns such a row to the retention window.
            if (ChapterRevision.authorizePruneDeletion(claimed.id, now())) {
                deleteArchivedCbz(archiveRoot(), claimed.candidateKey)
                ChapterRevision.markRemoteDeletePending(claimed.id, now())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to prune the archived payload of revision ${claimed.id}" }
            ChapterRevision.deferPruneFailure(
                id = claimed.id,
                error = e.message ?: e.javaClass.simpleName,
                retryIntervalSeconds = retryIntervalSeconds(),
                now = now(),
            )
        }

        return true
    }

    /** Claims and checks one due deletion; false when nothing is due. */
    suspend fun verifyDueAbsence(): Boolean {
        val claimed = ChapterRevision.claimNextDueRetentionVerification(now(), leaseSeconds()) ?: return false
        checkAbsence(claimed)
        return true
    }

    private fun reconcile(chapterKey: String) {
        runCatching { ChapterRevision.reconcileRetention(chapterKey, now()) }
            .onFailure { logger.error(it) { "Failed to reconcile the retention of chapter identity $chapterKey" } }
    }

    private suspend fun checkAbsence(claimed: ChapterRevisionDataClass) {
        val artifact = artifactOf(claimed)
        if (artifact == null) {
            ChapterRevision.markPruneFailed(claimed.id, "the pruned archived artifacts are not recorded", now())
            return
        }

        val outcome =
            try {
                verifier().verifyAbsent(artifact)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ArchiveAbsenceVerification.Unconfirmed(e.message ?: e.javaClass.simpleName)
            }

        when (outcome) {
            is ArchiveAbsenceVerification.ConfirmedAbsent -> {
                ChapterRevision.markPruned(claimed.id, now())
            }

            is ArchiveAbsenceVerification.NotAttempted -> {
                // no verifier is configured, so nothing may be inferred and nothing may be scheduled:
                // a later configuration turns this back into a due check on the next startup
                logger.debug { "No remote verifier is configured for pruned revision ${claimed.id}" }
                ChapterRevision.markRetentionVerificationPending(
                    id = claimed.id,
                    reason = "no remote verifier is configured",
                    nextVerificationAt = null,
                    now = now(),
                )
            }

            is ArchiveAbsenceVerification.StillPresent -> {
                ChapterRevision.markRetentionVerificationPending(
                    id = claimed.id,
                    reason = outcome.reason,
                    nextVerificationAt = nextCheckAt(),
                    now = now(),
                )
            }

            is ArchiveAbsenceVerification.Pending -> {
                ChapterRevision.markRetentionVerificationPending(
                    id = claimed.id,
                    reason = outcome.reason,
                    nextVerificationAt = nextCheckAt(),
                    now = now(),
                )
            }

            is ArchiveAbsenceVerification.Unconfirmed -> {
                logger.debug { "Pruned chapter revision ${claimed.id} could not be confirmed: ${outcome.reason}" }
                ChapterRevision.markPruneFailed(claimed.id, outcome.reason, now())
            }
        }
    }

    private fun nextCheckAt(): Long = saturatingEpochAdd(now(), retryIntervalSeconds().coerceAtLeast(1))

    private fun artifactOf(revision: ChapterRevisionDataClass): ChapterRevisionArchiveArtifact? {
        val cbzPath = revision.archiveCbzPath ?: return null
        val manifestPath = revision.archiveManifestPath ?: return null
        val cbzHash = revision.archiveCbzHash ?: return null
        val cbzSize = revision.archiveCbzSize ?: return null
        val manifestHash = revision.archiveManifestHash ?: return null
        val manifestSize = revision.archiveManifestSize ?: return null

        return ChapterRevisionArchiveArtifact(
            candidateKey = revision.candidateKey,
            relativeCbzPath = cbzPath,
            relativeManifestPath = manifestPath,
            cbzSha256 = cbzHash,
            cbzSize = cbzSize,
            manifestSha256 = manifestHash,
            manifestSize = manifestSize,
        )
    }

    private companion object {
        /** Identities reconciled per sweep page, so a large library is never processed in one step. */
        const val RECONCILIATION_BATCH = 32
    }
}

/**
 * Single-concurrency retention worker.
 *
 * It drains reconciliation, deletion and remote absence confirmation in that order, then waits. The
 * wait is a real sleep until the earliest persisted due time rather than a poll, and only a wake that
 * a committed transition sent can interrupt it.
 */
class ChapterRevisionRetentionLoop(
    private val processor: ChapterRevisionRetentionProcessor,
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    private val logger = KotlinLogging.logger {}
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var scope: CoroutineScope? = null
    private var loop: Job? = null

    /** Starts the loop. Repeated calls are ignored while it is already running. */
    fun start() {
        synchronized(lock) {
            if (loop?.isActive == true) {
                return
            }
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = newScope
            loop = newScope.launch { runLoop() }
        }
    }

    fun stop() {
        synchronized(lock) {
            scope?.cancel()
            scope = null
            loop = null
        }
    }

    /** Records that work may exist; safe to call from any thread and before [start]. */
    fun notifyWorkAvailable() {
        wakeSignal.trySend(Unit)
    }

    /** Queues one chapter identity and wakes the worker. */
    fun requestIdentity(chapterKey: String) {
        processor.requestIdentity(chapterKey)
        notifyWorkAvailable()
    }

    /** Queues a library-wide sweep and wakes the worker. */
    fun requestSweep() {
        processor.requestSweep()
        notifyWorkAvailable()
    }

    /** Queues the published identities of specific series and wakes the worker. */
    fun requestMangas(mangaIds: List<Int>) {
        processor.requestMangas(mangaIds)
        notifyWorkAvailable()
    }

    /**
     * Records that the remote verification configuration changed and wakes the worker.
     *
     * A deletion that could not be verified while no remote was configured is deliberately
     * unscheduled; configuring a remote has to make such rows due again without a restart.
     */
    fun configurationChanged() {
        runCatching { ChapterRevision.schedulePendingRetentionVerifications(now()) }
            .onFailure { logger.error(it) { "Failed to schedule pending chapter revision prunings" } }
        notifyWorkAvailable()
    }

    /** Reconciles, prunes or confirms one unit of work; false when there is nothing to do. */
    internal suspend fun drainOnce(): Boolean {
        if (processor.reconcilePending()) {
            return true
        }
        if (processor.pruneOne()) {
            return true
        }
        return processor.verifyDueAbsence()
    }

    private suspend fun runLoop() {
        runCatching { ChapterRevision.recoverInterruptedPruning() }
            .onFailure { logger.error(it) { "Failed to recover interrupted chapter revision prunings" } }
        runCatching { ChapterRevision.schedulePendingRetentionVerifications(now()) }
            .onFailure { logger.error(it) { "Failed to schedule pending chapter revision prunings" } }

        // A publication commit and the retention wake it triggers are two separate commits; a crash
        // between them would leave that identity unreconciled forever. The bounded startup sweep
        // repairs such a lost wake page by page.
        processor.requestSweep()

        while (currentCoroutineContext().isActive) {
            val processed =
                try {
                    drainOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "The chapter revision retention worker failed" }
                    false
                }

            if (!processed) {
                awaitNextDue()
            }
        }
    }

    private suspend fun awaitNextDue() {
        if (processor.hasPendingReconciliation()) {
            withTimeoutOrNull(MIN_WAIT_MILLIS) { wakeSignal.receive() }
            return
        }

        val dueAt =
            try {
                ChapterRevision.nextRetentionDueAt(now())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to compute the next chapter revision pruning time" }
                now() + 1
            }

        if (dueAt == null) {
            // nothing can be scheduled by a wake: only a transition or a sweep can add work
            wakeSignal.receive()
        } else {
            withTimeoutOrNull(waitMillisUntil(dueAt, now(), MIN_WAIT_MILLIS)) { wakeSignal.receive() }
        }
    }

    private companion object {
        /** Bounded below so a due-but-unclaimable revision can never spin the loop. */
        const val MIN_WAIT_MILLIS = 1_000L
    }
}

/**
 * Single, global accepted-revision retention worker.
 *
 * Pruning through the mounted archive happens even without a configured remote, but no revision is
 * ever reported as PRUNED until a verifier confirmed its absence directly against that remote.
 */
object ChapterRevisionRetentionExecutor {
    private val applicationDirs: ApplicationDirs by injectLazy()

    private val loop by lazy {
        ChapterRevisionRetentionLoop(
            processor =
                ChapterRevisionRetentionProcessor(
                    archiveRoot = { File(applicationDirs.archiveRoot) },
                    verifier = { configuredAbsenceVerifier() },
                    retryIntervalSeconds = { serverConfig.archiveVerificationRetrySeconds.value.toLong() },
                    leaseSeconds = {
                        verificationLeaseSeconds(
                            retryIntervalSeconds = serverConfig.archiveVerificationRetrySeconds.value.toLong(),
                            commandTimeoutSeconds = serverConfig.archiveVerificationTimeoutSeconds.value.toLong(),
                        )
                    },
                ),
        )
    }

    private fun configuredAbsenceVerifier(): ArchiveDeletionVerifier {
        val remote = serverConfig.archiveRcloneRemote.value
        if (remote.isBlank()) {
            return ArchiveDeletionVerifier.NOT_CONFIGURED
        }

        val verifier =
            RcloneArchiveCommitVerifier(
                executable = serverConfig.archiveRcloneExecutable.value,
                remoteRoot = remote,
                timeout = serverConfig.archiveVerificationTimeoutSeconds.value.seconds,
            )

        return ArchiveDeletionVerifier { artifact -> verifier.verifyAbsent(artifact) }
    }

    fun start() = loop.start()

    fun stop() = loop.stop()

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()

    /** Reconciles exactly one chapter identity, which is what a review decision changes. */
    fun requestIdentity(chapterKey: String) = loop.requestIdentity(chapterKey)

    /** Reconciles every published chapter identity, which is what a policy change needs. */
    fun requestSweep() = loop.requestSweep()

    /** Reconciles only the published identities of the given series, which is what a per-series override needs. */
    fun requestMangas(mangaIds: List<Int>) = loop.requestMangas(mangaIds)

    /**
     * Called when the remote verification configuration changed.
     *
     * It schedules the deletions that were left unschedulable while no remote was configured and
     * wakes the worker, so a newly configured remote is picked up without a restart.
     */
    fun configurationChanged() = loop.configurationChanged()

    /**
     * Requeues revisions whose pruning failed, preserving their attempt count.
     *
     * A pruned revision is never requeued: its payload is gone for good and can only come back by
     * being acquired again.
     */
    fun retry(ids: List<Int>): List<ChapterRevisionDataClass> {
        val retried = ChapterRevision.retryPruning(ids)
        if (retried.isEmpty()) {
            return emptyList()
        }

        notifyWorkAvailable()
        return retried
    }
}
