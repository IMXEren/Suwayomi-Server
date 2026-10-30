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

private val logger = KotlinLogging.logger {}

/**
 * Persisted claim lease for one remote durability check.
 *
 * The lease has to outlast the worst case runtime of a single verification - the configured command
 * timeout plus the process termination grace - and not merely the retry cadence. Otherwise a second
 * server instance could claim a row whose rclone command is still running, and a timeout could make
 * the row immediately due again.
 */
internal fun verificationLeaseSeconds(
    retryIntervalSeconds: Long,
    commandTimeoutSeconds: Long,
): Long =
    maxOf(
        retryIntervalSeconds,
        commandTimeoutSeconds + ProcessArchiveRemoteCommandRunner.TERMINATE_GRACE_SECONDS + LEASE_SAFETY_MARGIN_SECONDS,
    )

/** The unit persisted due times are compared in. */
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Milliseconds to wait until [dueAt], saturating instead of overflowing and never below [minMillis].
 *
 * A persisted due time that is corrupted or absurdly far in the future must never wrap into a tiny or
 * negative timeout, which would turn a sleep into a busy loop.
 */
internal fun waitMillisUntil(
    dueAt: Long,
    now: Long,
    minMillis: Long,
): Long {
    if (dueAt <= now) {
        return minMillis
    }

    val remainingSeconds = dueAt - now
    val maxSeconds = Long.MAX_VALUE / MILLIS_PER_SECOND
    return if (remainingSeconds >= maxSeconds) {
        Long.MAX_VALUE
    } else {
        (remainingSeconds * MILLIS_PER_SECOND).coerceAtLeast(minMillis)
    }
}

/** Extra margin so a command that dies just after the timeout can not be re-claimed too early. */
private const val LEASE_SAFETY_MARGIN_SECONDS = 10L

/** Deletes the downloaded pages and the locally built artifacts of one revision. */
internal fun deleteLocalRevisionArtifacts(
    stagingRoot: File,
    revision: ChapterRevisionDataClass,
) {
    ChapterRevisionStaging.deleteCandidate(stagingRoot, revision.candidateKey)
    ChapterRevisionArchiveArtifacts.deleteLocalRevision(stagingRoot, revision.candidateKey)
}

/**
 * Confirms one revision against remote storage and, only once that confirmation is durable, drops
 * the local copies that are no longer needed.
 *
 * A pending outcome leaves the revision at `REMOTE_PENDING` so it is simply checked again later;
 * only a genuine mismatch or an unusable rclone invocation is recorded as `ARCHIVE_UNCONFIRMED`.
 */
class ChapterRevisionArchiveVerificationProcessor(
    private val stagingRoot: () -> File,
    private val verifier: () -> ArchiveCommitVerifier,
    private val deleteLocalArtifacts: (File, ChapterRevisionDataClass) -> Unit = ::deleteLocalRevisionArtifacts,
    /** called after a revision became the active revision of its chapter, inside no transaction */
    private val onActivated: () -> Unit = {},
) {
    suspend fun process(claimed: ChapterRevisionDataClass) {
        val artifact = artifactOf(claimed)
        if (artifact == null) {
            // a pending revision without recorded artifact identity can never be confirmed remotely
            ChapterRevision.markArchiveUnconfirmed(claimed.id, "the archived artifacts are not recorded")
            return
        }

        val outcome =
            try {
                verifier().verify(artifact)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ArchiveVerification.Unconfirmed(e.message ?: e.javaClass.simpleName)
            }

        when (outcome) {
            is ArchiveVerification.Confirmed -> {
                if (ChapterRevision.markRemoteConfirmed(claimed.id)) {
                    cleanupRevision(claimed)

                    // The first confirmed revision of a chapter becomes its active revision, which is
                    // what makes the chapter appear in the library without a manual decision. A later
                    // confirmed revision deliberately stays a reviewable candidate, so an update can
                    // never silently replace what is already published.
                    if (ChapterRevision.activateIfFirstConfirmed(claimed.id)) {
                        onActivated()
                    }
                }
            }

            is ArchiveVerification.NotAttempted -> {
                // no verifier is configured, so nothing may be inferred about remote durability
                logger.debug { "No remote verifier is configured for chapter revision ${claimed.id}" }
            }

            is ArchiveVerification.Pending -> {
                ChapterRevision.markVerificationPending(claimed.id, outcome.reason)
            }

            is ArchiveVerification.Unconfirmed -> {
                logger.debug { "Chapter revision ${claimed.id} is unconfirmed: ${outcome.reason}" }
                ChapterRevision.markArchiveUnconfirmed(claimed.id, outcome.reason)
            }
        }
    }

    /**
     * Retries the local cleanup of revisions whose remote confirmation is already durable.
     *
     * The downloaded pages are deliberately kept until confirmation, so a cleanup that failed - for
     * example because a file was still mapped - is retried here instead of silently leaking.
     *
     * It returns true only when a batch really made progress, so the caller drains further batches
     * immediately instead of waiting a whole retry interval for each one, and stops as soon as a
     * batch cannot delete anything. A revision whose files cannot be removed yet is deferred to the
     * back of the queue so the confirmed revisions behind it are never starved.
     */
    fun cleanupPendingLocalArtifacts(): Boolean {
        val pending = ChapterRevision.revisionsAwaitingCleanup(CLEANUP_BATCH)
        if (pending.isEmpty()) {
            return false
        }

        var progressed = false
        pending.forEach { revision ->
            if (cleanupRevision(revision)) {
                progressed = true
            }
        }
        return progressed
    }

    private fun cleanupRevision(revision: ChapterRevisionDataClass): Boolean =
        try {
            deleteLocalArtifacts(stagingRoot(), revision)
            // the pending-cleanup marker is cleared only after the local files really are gone
            ChapterRevision.markCandidateCleanupComplete(revision.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to clean up the local artifacts of confirmed revision ${revision.id}" }
            ChapterRevision.deferCandidateCleanup(revision.id)
            false
        }

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
        /** Small batch so a long cleanup never blocks the single verification worker for long. */
        const val CLEANUP_BATCH = 8
    }
}

/**
 * Single-concurrency remote durability verification worker.
 *
 * Unlike the commit workers this one needs a timer: remote visibility can only be observed by asking
 * again, so while pending revisions exist the loop sleeps until the earliest persisted
 * next-verification time. With nothing pending it waits for a wake instead of querying the database,
 * and a wake is only ever sent after the interesting transition has committed.
 */
class ChapterRevisionArchiveVerificationLoop(
    private val processor: ChapterRevisionArchiveVerificationProcessor,
    private val enabled: () -> Boolean,
    private val retryIntervalSeconds: () -> Long,
    private val leaseSeconds: () -> Long = retryIntervalSeconds,
    private val now: () -> Long = { Instant.now().epochSecond },
) {
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

    /**
     * Records that work may exist. Safe to call from any thread and before [start]; the signal is
     * conflated so a wake up can never be lost.
     */
    fun notifyWorkAvailable() {
        wakeSignal.trySend(Unit)
    }

    /**
     * Claims and verifies one due revision; false when nothing is due or verification is disabled.
     *
     * The next attempt is already scheduled by the claim, so a pending outcome can never spin.
     */
    internal suspend fun drainOnceIfEnabled(): Boolean {
        if (!enabled()) {
            return false
        }

        val claimed = ChapterRevision.claimNextDueVerification(now(), leaseSeconds()) ?: return false
        try {
            processor.process(claimed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // one broken revision must never end the loop; its scheduled retry still stands
            logger.error(e) { "Failed to verify chapter revision ${claimed.id}" }
        }
        return true
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                while (drainOnceIfEnabled()) {
                    // keep draining whatever is already due
                }
                while (processor.cleanupPendingLocalArtifacts()) {
                    // keep draining successful cleanup batches instead of waiting a retry interval
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "The chapter revision verification worker failed" }
            }

            awaitNextDue()
        }
    }

    private suspend fun awaitNextDue() {
        if (!enabled()) {
            // nothing can be verified, so wait for a wake rather than querying the database
            wakeSignal.receive()
            return
        }

        val waitMillis =
            try {
                val dueAt = ChapterRevision.nextVerificationDueAt(now())
                when {
                    dueAt != null -> waitMillisUntil(dueAt, now(), MIN_WAIT_MILLIS)
                    ChapterRevision.revisionsAwaitingCleanup(1).isNotEmpty() -> retryMillis()
                    else -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to compute the next chapter revision verification time" }
                retryMillis()
            }

        if (waitMillis == null) {
            wakeSignal.receive()
        } else {
            withTimeoutOrNull(waitMillis) { wakeSignal.receive() }
        }
    }

    private fun retryMillis(): Long = retryIntervalSeconds().coerceAtLeast(1) * MILLIS_PER_SECOND

    private companion object {
        /** Bounded below so a due-but-unclaimable revision can never spin the loop. */
        const val MIN_WAIT_MILLIS = 1_000L
    }
}

/**
 * Single, global remote durability verification worker.
 *
 * It is only active when an rclone remote is configured. Without one, revisions legitimately stay
 * `REMOTE_PENDING` and no external process is ever spawned.
 */
object ChapterRevisionArchiveVerificationExecutor {
    private val applicationDirs: ApplicationDirs by injectLazy()

    private val loop by lazy {
        ChapterRevisionArchiveVerificationLoop(
            processor =
                ChapterRevisionArchiveVerificationProcessor(
                    stagingRoot = { File(applicationDirs.archiveStagingRoot) },
                    verifier = { configuredVerifier() },
                    onActivated = { ChapterRevisionPublicationExecutor.notifyWorkAvailable() },
                ),
            enabled = { serverConfig.archiveRcloneRemote.value.isNotBlank() },
            retryIntervalSeconds = { serverConfig.archiveVerificationRetrySeconds.value.toLong() },
            leaseSeconds = {
                verificationLeaseSeconds(
                    retryIntervalSeconds = serverConfig.archiveVerificationRetrySeconds.value.toLong(),
                    commandTimeoutSeconds = serverConfig.archiveVerificationTimeoutSeconds.value.toLong(),
                )
            },
        )
    }

    private fun configuredVerifier(): ArchiveCommitVerifier {
        val remote = serverConfig.archiveRcloneRemote.value
        if (remote.isBlank()) {
            return ArchiveCommitVerifier.NOT_CONFIGURED
        }

        return RcloneArchiveCommitVerifier(
            executable = serverConfig.archiveRcloneExecutable.value,
            remoteRoot = remote,
            timeout = serverConfig.archiveVerificationTimeoutSeconds.value.seconds,
        )
    }

    fun start() = loop.start()

    fun stop() = loop.stop()

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()

    /**
     * Called when the remote verification configuration changed.
     *
     * The verifier is inert while no remote is configured; waking it lets it claim the revisions that
     * were already due, without a restart.
     */
    fun configurationChanged() = loop.notifyWorkAvailable()
}
