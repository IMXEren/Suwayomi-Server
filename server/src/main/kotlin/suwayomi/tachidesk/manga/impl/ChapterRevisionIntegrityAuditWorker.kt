package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditItemDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditItemState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionIntegrityState
import suwayomi.tachidesk.server.serverConfig
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** What checking one archived revision established. */
sealed interface ChapterIntegrityAuditOutcome {
    /** both artifacts are exactly the ones the archive recorded */
    data object Verified : ChapterIntegrityAuditOutcome

    /** the archived payload is confirmed absent from remote storage */
    data class Missing(
        val reason: String,
    ) : ChapterIntegrityAuditOutcome

    /** the archived payload is there but is not what the archive recorded */
    data class Corrupt(
        val reason: String,
    ) : ChapterIntegrityAuditOutcome

    /** nothing could be concluded about the archived payload, so it is worth another attempt */
    data class Inconclusive(
        val reason: String,
    ) : ChapterIntegrityAuditOutcome

    /** nothing can be checked at all right now, so the revision stays exactly as it was */
    data object Blocked : ChapterIntegrityAuditOutcome

    /** the revision stopped being something an audit may check, so no integrity state is written */
    data class Skipped(
        val reason: String,
    ) : ChapterIntegrityAuditOutcome
}

/**
 * Checks one revision of an audit run against remote storage.
 *
 * What is checked is the artifact identity the run recorded, never the revision row's current values:
 * an artifact location that was edited after the run started must not silently change what that run
 * verifies. The live row is only consulted for one thing, and deliberately so: whether the archive
 * still claims that payload at all. Retention may have pruned an artifact while the item waited in a
 * queue, and reporting a payload the archive itself deleted as "missing" would not be an audit finding
 * but a false alarm.
 *
 * The verifier is injectable, so the whole orchestration is testable without a live remote or a live
 * rclone installation.
 */
class ChapterRevisionIntegrityAuditProcessor(
    private val verifier: () -> ArchiveIntegrityVerifier = { ArchiveIntegrityVerifier.NOT_CONFIGURED },
    private val loadRevision: (Int) -> ChapterRevisionDataClass? = { revisionId -> ChapterRevision.getRevision(revisionId) },
) {
    suspend fun process(claim: ChapterRevisionIntegrityAudit.Claim): ChapterIntegrityAuditOutcome {
        val item = claim.item

        val revisionId =
            item.revisionId
                ?: return ChapterIntegrityAuditOutcome.Skipped(SKIPPED_REVISION_GONE)

        val revision =
            loadRevision(revisionId)
                ?: return ChapterIntegrityAuditOutcome.Skipped(SKIPPED_REVISION_GONE)

        if (revision.candidateKey != item.candidateKey) {
            // the row now holds a different discovery snapshot, so the payload this item described is no
            // longer the payload that revision owns
            return ChapterIntegrityAuditOutcome.Skipped(SKIPPED_REVISION_REPLACED)
        }

        if (revision.deletionStartedOrPayloadGone()) {
            // retention removed the payload on purpose, so its absence is expected rather than a finding
            return ChapterIntegrityAuditOutcome.Skipped(SKIPPED_PAYLOAD_PRUNED)
        }

        val artifact = artifactOf(item)
        val configured = verifier()

        val outcome =
            try {
                configured.check(artifact)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the exception *type* is kept, because it is the only part that is safe to persist; the
                // message can carry remote details and never crosses this boundary
                ArchiveIntegrityVerification.Failed(
                    "the check failed (${e.javaClass.simpleName})",
                )
            }

        return when (outcome) {
            ArchiveIntegrityVerification.Verified -> {
                ChapterIntegrityAuditOutcome.Verified
            }

            is ArchiveIntegrityVerification.Missing -> {
                ChapterIntegrityAuditOutcome.Missing(outcome.reason)
            }

            is ArchiveIntegrityVerification.Corrupt -> {
                ChapterIntegrityAuditOutcome.Corrupt(outcome.reason)
            }

            is ArchiveIntegrityVerification.Failed -> {
                ChapterIntegrityAuditOutcome.Inconclusive(outcome.reason)
            }

            ArchiveIntegrityVerification.Unavailable -> {
                ChapterIntegrityAuditOutcome.Blocked
            }

            is ArchiveIntegrityVerification.Retryable -> {
                if (claim.item.attempts < claim.session.maxAttempts) {
                    ChapterIntegrityAuditOutcome.Inconclusive(outcome.reason)
                } else {
                    // The attempts this run allows are spent on an inconclusive check, so the one question
                    // left is whether the payload is simply gone. Only a positive absence answer - the same
                    // evidence the verifier uses to decide that a pruned payload really left the remote -
                    // justifies recording a missing payload; anything else stays a failed check.
                    exhaustedOutcome(configured, artifact, outcome.reason)
                }
            }
        }
    }

    private suspend fun exhaustedOutcome(
        verifier: ArchiveIntegrityVerifier,
        artifact: ChapterRevisionArchiveArtifact,
        reason: String,
    ): ChapterIntegrityAuditOutcome =
        try {
            when (verifier.confirmAbsent(artifact)) {
                ArchiveAbsenceVerification.ConfirmedAbsent -> {
                    ChapterIntegrityAuditOutcome.Missing("the payload is not listed on the remote after $reason")
                }

                else -> {
                    ChapterIntegrityAuditOutcome.Inconclusive(reason)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChapterIntegrityAuditOutcome.Inconclusive(
                "$reason; the absence of the payload could not be confirmed (${e.javaClass.simpleName})",
            )
        }

    /** The artifact identity this run recorded, which is what the remote is asked about. */
    private fun artifactOf(item: ChapterIntegrityAuditItemDataClass): ChapterRevisionArchiveArtifact =
        ChapterRevisionArchiveArtifact(
            candidateKey = item.candidateKey,
            relativeCbzPath = item.cbzPath,
            relativeManifestPath = item.manifestPath,
            cbzSha256 = item.cbzSha256,
            cbzSize = item.cbzSize,
            manifestSha256 = item.manifestSha256,
            manifestSize = item.manifestSize,
        )

    private companion object {
        /** Static on purpose: the reason of a skipped revision is exposed through authenticated GraphQL. */
        const val SKIPPED_REVISION_GONE = "the revision this check was recorded for no longer exists"

        const val SKIPPED_REVISION_REPLACED =
            "the revision no longer identifies the archived payload this run recorded"

        const val SKIPPED_PAYLOAD_PRUNED = "the archived payload of the revision has been removed"
    }
}

/**
 * Single, global archive integrity audit worker.
 *
 * Concurrency is one on purpose: every check is one remote listing, so the run is the thing that must
 * not become a burst of thousands of commands - the persisted item delay, not this process, paces it.
 * The same loop also advances the persisted schedule, so exactly one place decides what an audit does
 * next and one wake signal can never be lost.
 *
 * It never claims anything while no remote verifier is configured, and it then waits for a wake
 * instead of polling: a deployment without a remote leaves every revision at NEVER_AUDITED, which is
 * the honest answer, rather than recording findings nobody verified.
 */
class ChapterRevisionIntegrityAuditLoop(
    private val processor: ChapterRevisionIntegrityAuditProcessor = ChapterRevisionIntegrityAuditProcessor(),
    private val enabled: () -> Boolean = { serverConfig.chapterIntegrityAuditEnabled.value },
    private val verifierConfigured: () -> Boolean = { serverConfig.archiveRcloneRemote.value.isNotBlank() },
    private val intervalSeconds: () -> Long = { serverConfig.chapterIntegrityAuditIntervalDays.value.toLong() * SECONDS_PER_DAY },
    newestPerManga: () -> Int = { serverConfig.chapterIntegrityAuditRecentRevisions.value },
    private val deferSeconds: () -> Long = { serverConfig.chapterIntegrityAuditRetrySeconds.value.toLong() },
    private val claim: (Long) -> ChapterRevisionIntegrityAudit.Claim? = { now ->
        ChapterRevisionIntegrityAudit.claimNextDueItem(now)
    },
    private val complete: (ChapterRevisionIntegrityAudit.Claim, ChapterIntegrityAuditOutcome, Long) -> Unit = { claim, outcome, now ->
        recordAuditOutcome(claim, outcome, now)
    },
    recoverInterrupted: (Long) -> Unit = { now -> ChapterRevisionIntegrityAudit.recoverInterruptedItems(now) },
    nextItemDueAt: (Long) -> Long? = { now -> ChapterRevisionIntegrityAudit.nextDueAt(now) },
    scheduleDueAt: () -> Long? = { ChapterRevisionIntegrityAudit.auditDueAt(enabled()) },
    private val now: () -> Long = { Instant.now().epochSecond },
    /**
     * Runs one automatic occurrence of the persisted schedule.
     *
     * Injectable so a test can prove how a failing start is retried without staging a database failure
     * of its own; production always uses the persisted schedule.
     */
    private val scheduledTick: (Long) -> ChapterRevisionIntegrityAudit.TickOutcome = { current ->
        ChapterRevisionIntegrityAudit.runScheduledTick(intervalSeconds(), deferSeconds(), newestPerManga(), current)
    },
) {
    private val loop =
        ChapterRevisionWorkerLoop(
            workerName = "ChapterRevisionIntegrityAuditWorker",
            beforeFirstDrain = {
                // A shutdown can leave a revision claimed but unchecked, and the schedule has to exist
                // before the worker can decide anything about it.
                recoverInterrupted(now())
                ChapterRevisionIntegrityAudit.ensureSchedule(intervalSeconds())
            },
            // Without a configured remote nothing can be checked at all, so the worker sleeps until an
            // explicit wake instead of waking up once per second only to find that out again. With one,
            // the earliest of the next due revision and the next schedule occurrence bounds the wait.
            idleTimeoutMillis = {
                if (!verifierConfigured()) {
                    null
                } else {
                    val current = now()
                    val dueAt = listOfNotNull(nextItemDueAt(current), scheduleDueAt()).minOrNull()
                    dueAt?.let { waitMillisUntil(it, current, MINIMUM_IDLE_MILLIS) }
                }
            },
            drainOnce = { drainAuditOnce() },
        )

    /**
     * Runs one iteration: one automatic occurrence if one is due, then one revision of a run.
     *
     * Without a configured verifier nothing is claimed at all and the caller is told there was no
     * work, which is what makes the idle wait a real sleep instead of a poll for work that cannot be
     * done: the check that decides it is the same one the claim would need.
     *
     * @return true when this iteration did something
     */
    internal suspend fun drainAuditOnce(): Boolean {
        if (!verifierConfigured()) {
            return false
        }

        val current = now()
        val started = drainScheduledTick(enabled, intervalSeconds, deferSeconds, now)
        val claimed = claim(current)
        if (claimed == null) {
            return started
        }

        processClaimed(processor, claimed, complete, now)
        return true
    }

    fun start() = loop.start()

    fun stop() = loop.stop()

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()

    /** Checks one claim and records its outcome; internal so a test can drive a single iteration. */
    internal suspend fun processClaimed(
        processor: ChapterRevisionIntegrityAuditProcessor,
        claimed: ChapterRevisionIntegrityAudit.Claim,
        complete: (ChapterRevisionIntegrityAudit.Claim, ChapterIntegrityAuditOutcome, Long) -> Unit,
        now: () -> Long,
    ) {
        try {
            complete(claimed, processor.process(claimed), now())
        } catch (e: CancellationException) {
            // the claimed revision stays CHECKING and is returned to the queue on the next start, which
            // keeps its attempts intact instead of recording a shutdown as a failed check
            throw e
        } catch (e: Throwable) {
            // one broken revision must never stop the run; its scheduled retry still stands
            logger.warn { "chapter integrity audit item ${claimed.item.id} could not be checked: ${e.javaClass.simpleName}" }
            ChapterRevisionIntegrityAudit.failItem(claimed, "the check could not be run (${e.javaClass.simpleName})")
        }
    }

    /**
     * Runs one automatic tick, persisting a backoff when the occurrence could not even be started.
     *
     * A failure leaves the occurrence's own due instant untouched, so the audit it owes is retried
     * instead of being skipped; the persisted backoff only decides *when*. That is what keeps a
     * persistent start failure from turning the worker's idle floor into a one-second retry loop.
     *
     * @return true when a scheduled run was really started
     */
    fun drainScheduledTick(
        enabled: () -> Boolean,
        intervalSeconds: () -> Long,
        deferSeconds: () -> Long,
        now: () -> Long,
    ): Boolean {
        val current = now()

        if (!enabled()) {
            return false
        }

        ChapterRevisionIntegrityAudit.ensureSchedule(intervalSeconds(), current)

        return try {
            scheduledTick(current) is ChapterRevisionIntegrityAudit.TickOutcome.Started
        } catch (e: CancellationException) {
            // a shutdown is not a failure of the occurrence: nothing is deferred and nothing is lost,
            // because an interrupted attempt never advanced the due instant
            throw e
        } catch (e: Throwable) {
            // The exception *type* is logged, never its message, which can carry database details. A
            // crash before this commits costs one immediate retry, never the occurrence.
            ChapterRevisionIntegrityAudit.recordScheduleStartFailure(deferSeconds(), current)
            logger.warn {
                "The scheduled chapter integrity audit could not be started and is retried after its backoff: " +
                    e.javaClass.simpleName
            }
            false
        }
    }
}

/**
 * Records one outcome as the durable state of its revision.
 *
 * A finding is recorded together with the item state that found it, in one transaction, so the two can
 * never disagree; a check that concluded nothing leaves the revision's integrity state alone until the
 * run either succeeds or gives up on it.
 */
internal fun recordAuditOutcome(
    claim: ChapterRevisionIntegrityAudit.Claim,
    outcome: ChapterIntegrityAuditOutcome,
    now: Long,
) {
    when (outcome) {
        ChapterIntegrityAuditOutcome.Verified -> {
            ChapterRevisionIntegrityAudit.completeItem(
                claim,
                ChapterIntegrityAuditItemState.VERIFIED,
                ChapterRevisionIntegrityState.VERIFIED,
                null,
                now,
            )
        }

        is ChapterIntegrityAuditOutcome.Missing -> {
            ChapterRevisionIntegrityAudit.completeItem(
                claim,
                ChapterIntegrityAuditItemState.MISSING,
                ChapterRevisionIntegrityState.MISSING,
                outcome.reason,
                now,
            )
        }

        is ChapterIntegrityAuditOutcome.Corrupt -> {
            ChapterRevisionIntegrityAudit.completeItem(
                claim,
                ChapterIntegrityAuditItemState.CORRUPT,
                ChapterRevisionIntegrityState.CORRUPT,
                outcome.reason,
                now,
            )
        }

        is ChapterIntegrityAuditOutcome.Inconclusive -> {
            ChapterRevisionIntegrityAudit.failItem(claim, outcome.reason, now)
        }

        ChapterIntegrityAuditOutcome.Blocked -> {
            ChapterRevisionIntegrityAudit.returnItemToPending(claim, INTEGRITY_AUDIT_BLOCKED_REASON, now)
        }

        is ChapterIntegrityAuditOutcome.Skipped -> {
            ChapterRevisionIntegrityAudit.markItemSkipped(claim, outcome.reason, now)
        }
    }
}

/** Floor for the idle wait: a persisted due time in the past must not turn an idle wait into a busy loop. */
private const val MINIMUM_IDLE_MILLIS = 1_000L

private const val SECONDS_PER_DAY = 86_400L

/**
 * Global entry point of the integrity audit worker and of its persisted schedule.
 *
 * The executor is what a settings change and a GraphQL mutation talk to, so nothing outside this file
 * has to know how the schedule is stored or how a revision is checked.
 */
object ChapterRevisionIntegrityAuditExecutor {
    private val loop by lazy { ChapterRevisionIntegrityAuditLoop() }

    fun start() = loop.start()

    fun stop() = loop.stop()

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()

    /**
     * Applies a settings change without a restart.
     *
     * A schedule that does not exist yet is created with the new interval, and an interval that got
     * shorter is pulled in immediately; the worker is then woken so the change is observable at once
     * instead of at the next due time.
     */
    fun configurationChanged() {
        val intervalSeconds = serverConfig.chapterIntegrityAuditIntervalDays.value.toLong() * SECONDS_PER_DAY
        ChapterRevisionIntegrityAudit.ensureSchedule(intervalSeconds)
        ChapterRevisionIntegrityAudit.reschedule(intervalSeconds)
        notifyWorkAvailable()
    }
}
