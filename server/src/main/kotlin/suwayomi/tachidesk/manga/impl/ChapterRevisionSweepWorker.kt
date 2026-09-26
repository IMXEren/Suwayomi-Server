package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDiscoveryReason
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepItemDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepItemState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepSessionDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ChapterRevisionSweepItemTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.time.Instant

/** What processing one chapter of a sweep produced. */
private val logger = KotlinLogging.logger {}

sealed interface ChapterRevisionSweepOutcome {
    /** the chapter was recorded as a revision candidate, which may be an already recorded one */
    data class Completed(
        val candidateCount: Int,
    ) : ChapterRevisionSweepOutcome

    /** the chapter does not qualify for a sweep, for a durable reason */
    data class Skipped(
        val reason: String,
    ) : ChapterRevisionSweepOutcome
}

/**
 * Processes one chapter of a sweep session.
 *
 * It never contacts a source and never refreshes a chapter list: it re-reads the row the local
 * database already holds and records exactly one revision candidate from it. That is what makes a
 * sweep independent of the extension being reachable, and what keeps a 34,350 item run from doing
 * 34,350 source refreshes.
 *
 * The seams are injectable so the orchestration can be tested without a live source or a live
 * extension registry.
 */
class ChapterRevisionSweepProcessor(
    private val loadManga: (Int) -> ResultRow? = { mangaId ->
        transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.firstOrNull() }
    },
    private val loadChapter: (Int) -> ChapterDataClass? = { chapterId ->
        transaction {
            ChapterTable
                .selectAll()
                .where { ChapterTable.id eq chapterId }
                .firstOrNull()
                ?.let { ChapterTable.toDataClass(it) }
        }
    },
    private val loadItemState: (Int) -> ChapterRevisionSweepItemState? = { itemId ->
        transaction {
            ChapterRevisionSweepItemTable
                .selectAll()
                .where { ChapterRevisionSweepItemTable.id eq itemId }
                .firstOrNull()
                ?.let { ChapterRevisionSweepItemState.valueOf(it[ChapterRevisionSweepItemTable.state]) }
        }
    },
    private val createCandidate: (
        mangaEntry: ResultRow,
        chapter: ChapterDataClass,
        policy: MangaAcquisitionPolicy,
        sweepItemId: Int,
        sweepSessionId: Int,
        reason: ChapterRevisionDiscoveryReason,
        now: Long,
    ) -> Int = { mangaEntry, chapter, policy, sweepItemId, sweepSessionId, reason, now ->
        transaction {
            ChapterRevision.createCandidatesForSweep(
                mangaEntry = mangaEntry,
                chapter = chapter,
                policy = policy,
                sweepItemId = sweepItemId,
                sweepSessionId = sweepSessionId,
                reason = reason,
                now = now,
            )
        }
    },
) {
    suspend fun process(
        item: ChapterRevisionSweepItemDataClass,
        session: ChapterRevisionSweepSessionDataClass,
        now: Long = Instant.now().epochSecond,
    ): ChapterRevisionSweepOutcome {
        // A cancel can land between the claim and this point. Every write below is fenced on
        // PROCESSING, so a cancelled chapter can neither record a candidate nor resurrect its run.
        if (loadItemState(item.id) != ChapterRevisionSweepItemState.PROCESSING) {
            return ChapterRevisionSweepOutcome.Skipped(ChapterRevisionSweep.chapterGoneReason(item.mangaId))
        }

        // A paused series is inert by definition: it records no revision and therefore fetches
        // nothing. The decision is taken from the policy the session snapshotted, so editing the
        // series mid-run cannot change what a running sweep does.
        if (item.policy == MangaAcquisitionPolicy.PAUSED) {
            return ChapterRevisionSweepOutcome.Skipped(ChapterRevisionSweep.pausedSeriesReason(item.mangaId))
        }

        val mangaId = item.mangaId ?: return ChapterRevisionSweepOutcome.Skipped(ChapterRevisionSweep.chapterGoneReason(null))
        val mangaEntry = loadManga(mangaId)
        if (mangaEntry == null || !mangaEntry[MangaTable.inLibrary]) {
            return ChapterRevisionSweepOutcome.Skipped(ChapterRevisionSweep.chapterGoneReason(mangaId))
        }

        val chapterId = item.chapterId ?: return ChapterRevisionSweepOutcome.Skipped(ChapterRevisionSweep.chapterGoneReason(mangaId))
        val chapter = loadChapter(chapterId) ?: return ChapterRevisionSweepOutcome.Skipped(ChapterRevisionSweep.chapterGoneReason(mangaId))
        // a chapter the database moved to another series is not this item's chapter any more
        if (chapter.mangaId != mangaId) {
            return ChapterRevisionSweepOutcome.Skipped(ChapterRevisionSweep.chapterGoneReason(mangaId))
        }

        val candidateCount =
            createCandidate(
                mangaEntry,
                chapter,
                item.policy,
                item.id,
                session.id,
                ChapterRevisionSweep.reasonFor(session.kind),
                now,
            )

        return ChapterRevisionSweepOutcome.Completed(candidateCount)
    }
}

/**
 * Single, global revision sweep worker.
 *
 * Concurrency is one on purpose: the sweep re-downloads content, so it is the heaviest thing this
 * server does and the persisted item delay - not this process - is what paces it. The same loop also
 * advances the persisted schedule, so there is exactly one place that decides what a sweep does next
 * and one wake signal that can never be lost.
 */
class ChapterRevisionSweepLoop(
    processor: ChapterRevisionSweepProcessor = ChapterRevisionSweepProcessor(),
    enabled: () -> Boolean = { serverConfig.chapterRevisionSweepEnabled.value },
    intervalSeconds: () -> Long = { serverConfig.chapterRevisionSweepIntervalDays.value.toLong() * SECONDS_PER_DAY },
    newestPerSeries: () -> Int = { serverConfig.chapterRevisionSweepNewestChapters.value },
    deferSeconds: () -> Long = { serverConfig.chapterRevisionSweepRetrySeconds.value.toLong() },
    claim: (Long) -> ChapterRevisionSweep.Claim? = { now -> ChapterRevisionSweep.claimNextDueItem(now) },
    complete: (ChapterRevisionSweep.Claim, Int, Long) -> Unit = { claim, count, now ->
        ChapterRevisionSweep.completeItem(claim, count, now)
    },
    fail: (ChapterRevisionSweep.Claim, Throwable, Long) -> Unit = { claim, error, now ->
        ChapterRevisionSweep.failItem(claim, error, now)
    },
    skip: (ChapterRevisionSweep.Claim, String, Long) -> Unit = { claim, reason, now ->
        ChapterRevisionSweep.markItemSkipped(claim, reason, now)
    },
    recoverInterrupted: (Long) -> Unit = { now -> ChapterRevisionSweep.recoverInterruptedItems(now) },
    nextItemDueAt: (Long) -> Long? = { now -> ChapterRevisionSweep.nextDueAt(now) },
    scheduleDueAt: () -> Long? = { ChapterRevisionSweep.sweepDueAt(serverConfig.chapterRevisionSweepEnabled.value) },
    cleanupUnchanged: () -> CleanupOutcome = { sweepCleanupUnchangedStaging() },
    cleanupDueAt: (Long) -> Long? = { now -> ChapterRevision.nextUnchangedCleanupDueAt(now) },
    now: () -> Long = { Instant.now().epochSecond },
    /**
     * Runs one automatic occurrence of the persisted schedule.
     *
     * Injectable so a test can prove how a failing start is retried without staging a database failure
     * of its own; production always uses the persisted schedule.
     */
    private val scheduledTick: (Long) -> ChapterRevisionSweep.TickOutcome = { current ->
        ChapterRevisionSweep.runScheduledTick(intervalSeconds(), deferSeconds(), newestPerSeries(), current)
    },
) {
    private val loop =
        ChapterRevisionWorkerLoop(
            workerName = "ChapterRevisionSweepWorker",
            beforeFirstDrain = {
                // A shutdown can leave a chapter claimed but unprocessed, and can leave an unchanged
                // revision whose staged pages were never removed.
                recoverInterrupted(now())
                cleanupUnchanged()
                // The schedule is created here rather than by the migration: its first due time is one
                // full interval in the future, which only the running server can express.
                ChapterRevisionSweep.ensureSchedule(intervalSeconds())
            },
            // A running session that is only waiting out its own delay still has a due time, a pending
            // schedule has one too, and a staged payload whose removal failed has a retry due time of
            // its own; taking the earliest of the three keeps the wait bounded without polling. With
            // none of them the worker sleeps until an explicit wake, because that is the only thing
            // that could produce new work.
            idleTimeoutMillis = {
                val current = now()
                val dueAt =
                    listOfNotNull(nextItemDueAt(current), scheduleDueAt(), cleanupDueAt(current))
                        .minOrNull()
                dueAt?.let { sweepWaitMillisUntil(it, current, MINIMUM_IDLE_MILLIS) }
            },
            drainOnce = {
                val current = now()
                val started = drainScheduledTick(enabled, intervalSeconds, deferSeconds, now)

                val claimed = claim(current)
                if (claimed == null) {
                    if (!started) {
                        // nothing else to do: this is the natural place to remove the staged pages of
                        // revisions that turned out to be unchanged
                        cleanupUnchanged()
                    }
                    started
                } else {
                    processClaimed(processor, claimed, complete, fail, skip, now)
                    true
                }
            },
        )

    fun start() = loop.start()

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()

    fun stop() = loop.stop()

    private suspend fun processClaimed(
        processor: ChapterRevisionSweepProcessor,
        claimed: ChapterRevisionSweep.Claim,
        complete: (ChapterRevisionSweep.Claim, Int, Long) -> Unit,
        fail: (ChapterRevisionSweep.Claim, Throwable, Long) -> Unit,
        skip: (ChapterRevisionSweep.Claim, String, Long) -> Unit,
        now: () -> Long,
    ) {
        try {
            when (val outcome = processor.process(claimed.item, claimed.session, now())) {
                is ChapterRevisionSweepOutcome.Completed -> complete(claimed, outcome.candidateCount, now())
                is ChapterRevisionSweepOutcome.Skipped -> skip(claimed, outcome.reason, now())
            }
        } catch (e: CancellationException) {
            // the claimed chapter stays PROCESSING and is returned to the queue on the next start,
            // which keeps its attempts intact instead of recording a shutdown as a failure
            throw e
        } catch (e: Throwable) {
            // one broken chapter must never stop the run
            fail(claimed, e, now())
        }
    }

    /**
     * Runs one automatic tick, persisting a backoff when the occurrence could not even be started.
     *
     * A failure leaves the occurrence's own due instant untouched, so the sweep it owes is retried
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

        ChapterRevisionSweep.ensureSchedule(intervalSeconds(), current)

        return try {
            scheduledTick(current) is ChapterRevisionSweep.TickOutcome.Started
        } catch (e: CancellationException) {
            // a shutdown is not a failure of the occurrence: nothing is deferred and nothing is lost,
            // because an interrupted attempt never advanced the due instant
            throw e
        } catch (e: Throwable) {
            // The exception *type* is logged, never its message, which can carry database or filesystem
            // details. A crash before this commits costs one immediate retry, never the occurrence.
            ChapterRevisionSweep.recordScheduleStartFailure(deferSeconds(), current)
            logger.warn {
                "The scheduled chapter revision sweep could not be started and is retried after its backoff: " +
                    e.javaClass.simpleName
            }
            false
        }
    }
}

/**
 * Floor for the idle wait.
 *
 * A persisted due time in the past must not turn an idle wait into a busy loop.
 */
private const val MINIMUM_IDLE_MILLIS = 1_000L

private const val SECONDS_PER_DAY = 86_400L

private fun sweepWaitMillisUntil(
    dueAt: Long,
    now: Long,
    floor: Long,
): Long = maxOf(floor, (dueAt - now) * 1_000L)

/**
 * Global entry point of the sweep worker and of its persisted schedule.
 *
 * The executor is what a settings change and a GraphQL mutation talk to, so nothing outside this file
 * has to know how the schedule is stored.
 */
object ChapterRevisionSweepExecutor {
    private val applicationDirs: ApplicationDirs by injectLazy()

    private val loop by lazy { ChapterRevisionSweepLoop() }

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
        val intervalSeconds = serverConfig.chapterRevisionSweepIntervalDays.value.toLong() * SECONDS_PER_DAY
        ChapterRevisionSweep.ensureSchedule(intervalSeconds)
        ChapterRevisionSweep.reschedule(intervalSeconds)
        notifyWorkAvailable()
    }

    /**
     * Removes the staged pages of revisions that turned out to be byte-identical to the active one.
     *
     * The content of such a revision is already archived, so its local copy is dead weight - but it may
     * only be removed *after* the transaction that classified it committed. The staged path is the
     * pending-cleanup marker, so a crash in between is resumed here instead of leaking the files.
     */
    fun cleanupUnchangedStaging(limit: Int = ChapterRevisionSweep.CLEANUP_BATCH_SIZE): CleanupOutcome =
        sweepCleanupUnchangedStaging(limit, File(applicationDirs.archiveStagingRoot))

    fun cleanupStagingRoot(): File = File(applicationDirs.archiveStagingRoot)
}

/** How many unchanged revisions one cleanup pass removed or deferred. */
data class CleanupOutcome(
    val removed: Int,
    val deferred: Int,
)

/**
 * Removes the staged pages of unchanged revisions, earliest due time first.
 *
 * Only rows whose persisted due time has passed are attempted, so one directory that cannot be removed
 * right now cannot spin: its failure pushes the due time forward and lets the rows behind it be cleaned.
 * A cancellation is rethrown instead of being recorded, because a shutdown is not a failed removal.
 */
fun sweepCleanupUnchangedStaging(
    limit: Int = ChapterRevisionSweep.CLEANUP_BATCH_SIZE,
    stagingRoot: File = ChapterRevisionSweepExecutor.cleanupStagingRoot(),
    retrySeconds: Long = serverConfig.chapterRevisionSweepRetrySeconds.value.toLong(),
    now: Long = Instant.now().epochSecond,
): CleanupOutcome {
    var removed = 0
    var deferred = 0

    ChapterRevision.unchangedRevisionsAwaitingCleanup(limit, now).forEach { revision ->
        val cleaned =
            try {
                ChapterRevisionStaging.deleteCandidate(stagingRoot, revision.candidateKey)
                ChapterRevision.markUnchangedStagingCleaned(revision.id, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The staging path and the filesystem message are deliberately dropped: this root is
                // where the operator's own archive lives, so neither belongs in a shared log line.
                logger.debug { "staged pages of unchanged revision ${revision.id} could not be removed" }
                false
            }

        if (cleaned) {
            removed++
        } else {
            ChapterRevision.deferUnchangedStagingCleanup(revision.id, retrySeconds, now)
            deferred++
        }
    }

    return CleanupOutcome(removed, deferred)
}
