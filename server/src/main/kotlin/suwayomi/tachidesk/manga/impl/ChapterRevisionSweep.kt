package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.util.lang.isDuplicateKeyViolation
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDiscoveryReason
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepItemDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepItemState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepKind
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepProgress
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepScheduleDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepSessionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepSessionState
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ChapterRevisionSweepItemTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionSweepScheduleTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionSweepSessionTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.serverConfig
import java.time.Instant

/**
 * Durable periodic and manual revision sweeps.
 *
 * This is the source-independent half of revision detection. A Keiyoushi/Mihon extension exposes no
 * revision id, no updated time and no digest, so the only proof that a re-release actually changed
 * the content is to fetch it again and compare. A sweep is what decides *which* chapters are worth
 * re-fetching; the comparison itself happens when the recorded candidate finishes acquiring, in
 * [ChapterRevision.markCompleteAndClassify].
 *
 * A sweep never downloads anything itself and never touches a chapter list: for every selected
 * chapter it records one durable candidate from the snapshot the local database already holds, and
 * the ordinary acquisition path takes it from there. That is what keeps a 34,350 item run - the
 * newest ten chapters of 3,435 series - resumable, rate limited and independent of any source
 * refresh.
 *
 * The schedule is one persisted row instead of a computed instant, so a restart cannot invent a
 * sweep and a long outage cannot turn into a catch-up storm: only the worker that actually started a
 * run moves the due time forward.
 */
object ChapterRevisionSweep {
    /** the only value [ChapterRevisionSweepSessionTable.activeMarker] ever holds */
    const val ACTIVE_MARKER = "ACTIVE"

    /** how many unchanged revisions one cleanup pass removes at most */
    const val CLEANUP_BATCH_SIZE = 25

    private const val MAX_ERROR_LENGTH = 1024
    private const val ITEM_INSERT_BATCH = 500

    /**
     * Persisted reason for a chapter a shutdown interrupted mid-attempt.
     *
     * Static on purpose: it is stored in a column exposed through authenticated GraphQL, so it must
     * never carry anything derived from a source response.
     */
    private const val INTERRUPTED_ITEM_REASON = "the server stopped while this chapter was being swept"

    private val logger = KotlinLogging.logger {}

    private val claimableStates =
        listOf(
            ChapterRevisionSweepItemState.PENDING,
            ChapterRevisionSweepItemState.RETRY_WAIT,
        )

    /** Item states that can still change without human interaction. */
    private val unfinishedStates = claimableStates + ChapterRevisionSweepItemState.PROCESSING

    /** Item states that make a finished session report [ChapterRevisionSweepSessionState.COMPLETED_WITH_ERRORS]. */
    private val errorStates = listOf(ChapterRevisionSweepItemState.FAILED)

    /** How a sweep was asked to start. */
    data class StartRequest(
        val kind: ChapterRevisionSweepKind,
        /** optional subset of series; null sweeps every tracked series */
        val mangaIds: List<Int>? = null,
    )

    sealed interface StartOutcome {
        data class Started(
            val session: ChapterRevisionSweepSessionDataClass,
            val itemCount: Int,
        ) : StartOutcome

        /** no tracked series, or none of them has a chapter to sweep */
        data object NothingToSweep : StartOutcome

        data object ActiveSessionExists : StartOutcome

        /** the requested subset names series that are not tracked, which is a client error */
        data class InvalidSubset(
            val unknownMangaIds: List<Int>,
        ) : StartOutcome
    }

    sealed interface RetryOutcome {
        data class Retried(
            val session: ChapterRevisionSweepSessionDataClass,
            val itemCount: Int,
        ) : RetryOutcome

        data object NotFound : RetryOutcome

        data object AnotherSessionActive : RetryOutcome
    }

    /** What one automatic tick decided to do. */
    sealed interface TickOutcome {
        data class Started(
            val session: ChapterRevisionSweepSessionDataClass,
            val itemCount: Int,
        ) : TickOutcome

        /** the persisted due time has not been reached yet */
        data object NotDue : TickOutcome

        /** another sweep owns the library; the due time is deferred rather than retried in a loop */
        data object Deferred : TickOutcome

        data object NothingToSweep : TickOutcome
    }

    /** One claimed chapter together with the run settings it has to be processed under. */
    data class Claim(
        val item: ChapterRevisionSweepItemDataClass,
        val session: ChapterRevisionSweepSessionDataClass,
    )

    /** One tracked series a session will visit. */
    private data class Target(
        val mangaId: Int,
        val url: String,
        val title: String,
        val sourceId: Long,
        val policy: MangaAcquisitionPolicy,
    )

    /**
     * One selected chapter.
     *
     * Only the identity of the chapter is kept: the candidate is created from the row the sweep
     * re-reads when the item is processed, so a chapter whose title or scanlator changed in the
     * meantime produces the revision that the source actually serves now.
     */
    private data class SelectedChapter(
        val id: Int,
        val url: String,
        val name: String,
    )

    private data class PendingItem(
        val target: Target,
        val chapter: SelectedChapter,
    )

    /** The discovery reason a sweep of this kind records. */
    fun reasonFor(kind: ChapterRevisionSweepKind): ChapterRevisionDiscoveryReason =
        if (kind == ChapterRevisionSweepKind.SCHEDULED) {
            ChapterRevisionDiscoveryReason.PERIODIC_SWEEP
        } else {
            ChapterRevisionDiscoveryReason.MANUAL_SWEEP
        }

    /** The number of newest chapters per series a kind selects; null means the whole history. */
    fun newestPerSeriesFor(
        kind: ChapterRevisionSweepKind,
        configuredNewest: Int,
    ): Int? = if (kind.isFullHistory) null else configuredNewest.coerceAtLeast(1)

    fun start(
        request: StartRequest,
        newestPerSeries: Int?,
        now: Long = Instant.now().epochSecond,
    ): StartOutcome {
        if (getActiveSession() != null) {
            return StartOutcome.ActiveSessionExists
        }

        val subset = request.mangaIds?.distinct()
        val targets = loadTargets(subset)
        if (subset != null) {
            val tracked = targets.map { it.mangaId }.toSet()
            val unknown = subset.filter { it !in tracked }
            if (unknown.isNotEmpty()) {
                return StartOutcome.InvalidSubset(unknown)
            }
        }
        if (targets.isEmpty()) {
            return StartOutcome.NothingToSweep
        }

        val chaptersByManga = loadChaptersByManga(targets.map { it.mangaId }, newestPerSeries)
        val items = pendingItems(targets, chaptersByManga)
        if (items.isEmpty()) {
            return StartOutcome.NothingToSweep
        }

        return try {
            val (session, itemCount) = createSession(request.kind, newestPerSeries, items, now)
            logger.info { "chapter revision sweep ${session.id} started with $itemCount chapters" }
            ChapterRevisionSweepExecutor.notifyWorkAvailable()
            StartOutcome.Started(session, itemCount)
        } catch (e: Exception) {
            // The unique active marker is the real guard against two concurrent starts. Losing that
            // race is an expected outcome; anything else - a database error, a serialization failure -
            // has to propagate instead of being misreported as a conflict the caller cannot resolve.
            if (!e.isDuplicateKeyViolation()) {
                throw e
            }

            logger.info { "chapter revision sweep start rejected: another sweep is active" }
            StartOutcome.ActiveSessionExists
        }
    }

    /** Stops claiming chapters. One already being processed is allowed to finish. */
    fun pause(
        sessionId: Int,
        now: Long = Instant.now().epochSecond,
    ): ChapterRevisionSweepSessionDataClass? =
        transaction {
            val session =
                ChapterRevisionSweepSessionTable
                    .selectAll()
                    .where { ChapterRevisionSweepSessionTable.id eq sessionId }
                    .forUpdate()
                    .firstOrNull()
                    ?: return@transaction null
            if (ChapterRevisionSweepSessionState.valueOf(session[ChapterRevisionSweepSessionTable.state]) !=
                ChapterRevisionSweepSessionState.RUNNING
            ) {
                return@transaction null
            }

            ChapterRevisionSweepSessionTable.update({ ChapterRevisionSweepSessionTable.id eq sessionId }) {
                it[state] = ChapterRevisionSweepSessionState.PAUSED.name
                it[pausedAt] = now
                it[updatedAt] = now
            }

            readSession(sessionId)
        }

    fun resume(
        sessionId: Int,
        now: Long = Instant.now().epochSecond,
    ): ChapterRevisionSweepSessionDataClass? =
        transaction {
            val session =
                ChapterRevisionSweepSessionTable
                    .selectAll()
                    .where { ChapterRevisionSweepSessionTable.id eq sessionId }
                    .forUpdate()
                    .firstOrNull()
                    ?: return@transaction null
            if (ChapterRevisionSweepSessionState.valueOf(session[ChapterRevisionSweepSessionTable.state]) !=
                ChapterRevisionSweepSessionState.PAUSED
            ) {
                return@transaction null
            }

            ChapterRevisionSweepSessionTable.update({ ChapterRevisionSweepSessionTable.id eq sessionId }) {
                it[state] = ChapterRevisionSweepSessionState.RUNNING.name
                it[pausedAt] = null
                // resume immediately instead of waiting out a delay measured before the pause
                it[nextItemAt] = now
                it[updatedAt] = now
            }

            readSession(sessionId)
        }

    /**
     * Abandons a session.
     *
     * Every chapter that is not finished yet is cancelled, including one the worker already claimed: a
     * cancelled run must not leave a chapter looking like work in progress. The worker's own
     * transitions are all fenced on PROCESSING, so a late outcome can neither overwrite this state
     * nor reopen the run, and no later chapter is claimed because every claim re-checks that the
     * session is still RUNNING.
     */
    fun cancel(
        sessionId: Int,
        now: Long = Instant.now().epochSecond,
    ): ChapterRevisionSweepSessionDataClass? =
        transaction {
            val session =
                ChapterRevisionSweepSessionTable
                    .selectAll()
                    .where { ChapterRevisionSweepSessionTable.id eq sessionId }
                    .forUpdate()
                    .firstOrNull()
                    ?: return@transaction null
            if (!ChapterRevisionSweepSessionState.valueOf(session[ChapterRevisionSweepSessionTable.state]).isActive) {
                return@transaction null
            }

            ChapterRevisionSweepItemTable.update({
                (ChapterRevisionSweepItemTable.session eq sessionId) and
                    (ChapterRevisionSweepItemTable.state inList unfinishedStates.map { it.name })
            }) {
                it[state] = ChapterRevisionSweepItemState.CANCELLED.name
                it[dueAt] = null
                it[finishedAt] = now
                it[updatedAt] = now
            }

            ChapterRevisionSweepSessionTable.update({ ChapterRevisionSweepSessionTable.id eq sessionId }) {
                it[state] = ChapterRevisionSweepSessionState.CANCELLED.name
                it[activeMarker] = null
                it[cancelledAt] = now
                it[finishedAt] = now
                it[updatedAt] = now
            }

            readSession(sessionId)
        }

    /** Returns failed chapters to the queue. A retry reopens a finished run. */
    fun retry(
        sessionId: Int,
        itemIds: List<Int>? = null,
        now: Long = Instant.now().epochSecond,
    ): RetryOutcome =
        transaction {
            val session =
                ChapterRevisionSweepSessionTable
                    .selectAll()
                    .where { ChapterRevisionSweepSessionTable.id eq sessionId }
                    .forUpdate()
                    .firstOrNull()
                    ?: return@transaction RetryOutcome.NotFound

            if (!ChapterRevisionSweepSessionState.valueOf(session[ChapterRevisionSweepSessionTable.state]).isActive) {
                val activeId = activeSessionId()
                if (activeId != null && activeId != sessionId) {
                    return@transaction RetryOutcome.AnotherSessionActive
                }
            }

            val ids =
                ChapterRevisionSweepItemTable
                    .selectAll()
                    .where { retryableItemCondition(sessionId, itemIds) }
                    .orderBy(ChapterRevisionSweepItemTable.id to SortOrder.ASC)
                    .forUpdate()
                    .map { it[ChapterRevisionSweepItemTable.id].value }

            if (ids.isNotEmpty()) {
                ChapterRevisionSweepItemTable.update({ ChapterRevisionSweepItemTable.id inList ids }) {
                    it[state] = ChapterRevisionSweepItemState.PENDING.name
                    it[attempts] = 0
                    it[dueAt] = null
                    it[lastError] = null
                    it[startedAt] = null
                    it[finishedAt] = null
                    it[updatedAt] = now
                }

                ChapterRevisionSweepSessionTable.update({ ChapterRevisionSweepSessionTable.id eq sessionId }) {
                    it[state] = ChapterRevisionSweepSessionState.RUNNING.name
                    it[activeMarker] = ACTIVE_MARKER
                    it[finishedAt] = null
                    it[cancelledAt] = null
                    it[nextItemAt] = now
                    it[updatedAt] = now
                }
            }

            RetryOutcome.Retried(readSession(sessionId)!!, ids.size)
        }.also { outcome ->
            if (outcome is RetryOutcome.Retried && outcome.itemCount > 0) {
                ChapterRevisionSweepExecutor.notifyWorkAvailable()
            }
        }

    // ------------------------------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------------------------------

    fun getSession(sessionId: Int): ChapterRevisionSweepSessionDataClass? = transaction { readSession(sessionId) }

    /** The session that is still running or paused, of which there can be at most one. */
    fun getActiveSession(): ChapterRevisionSweepSessionDataClass? =
        transaction {
            ChapterRevisionSweepSessionTable
                .selectAll()
                .where { ChapterRevisionSweepSessionTable.activeMarker eq ACTIVE_MARKER }
                .firstOrNull()
                ?.let { ChapterRevisionSweepSessionTable.toDataClass(it) }
        }

    fun getLatestSession(): ChapterRevisionSweepSessionDataClass? =
        transaction {
            ChapterRevisionSweepSessionTable
                .selectAll()
                .orderBy(ChapterRevisionSweepSessionTable.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.let { ChapterRevisionSweepSessionTable.toDataClass(it) }
        }

    /** Condition for the paged session listing, or null to list every session. */
    fun sessionCondition(state: ChapterRevisionSweepSessionState? = null): Op<Boolean>? =
        state?.let { ChapterRevisionSweepSessionTable.state eq it.name }

    /** Condition for the paged item listing of one session. */
    fun itemCondition(
        sessionId: Int,
        state: ChapterRevisionSweepItemState? = null,
        mangaId: Int? = null,
    ): Op<Boolean> =
        listOfNotNull(
            ChapterRevisionSweepItemTable.session eq sessionId,
            state?.let { ChapterRevisionSweepItemTable.state eq it.name },
            mangaId?.let { ChapterRevisionSweepItemTable.mangaId eq it },
        ).reduce { acc, op -> acc and op }

    /** Counted from the items themselves so progress can never drift from the stored rows. */
    fun progress(sessionId: Int): ChapterRevisionSweepProgress =
        transaction {
            val counts =
                ChapterRevisionSweepItemTable
                    .select(ChapterRevisionSweepItemTable.state, ChapterRevisionSweepItemTable.id.count())
                    .where { ChapterRevisionSweepItemTable.session eq sessionId }
                    .groupBy(ChapterRevisionSweepItemTable.state)
                    .associate { it[ChapterRevisionSweepItemTable.state] to it[ChapterRevisionSweepItemTable.id.count()].toInt() }

            fun countOf(state: ChapterRevisionSweepItemState): Int = counts[state.name] ?: 0

            ChapterRevisionSweepProgress(
                total = counts.values.sum(),
                pending = countOf(ChapterRevisionSweepItemState.PENDING),
                processing = countOf(ChapterRevisionSweepItemState.PROCESSING),
                retryWait = countOf(ChapterRevisionSweepItemState.RETRY_WAIT),
                complete = countOf(ChapterRevisionSweepItemState.COMPLETE),
                skipped = countOf(ChapterRevisionSweepItemState.SKIPPED),
                failed = countOf(ChapterRevisionSweepItemState.FAILED),
                cancelled = countOf(ChapterRevisionSweepItemState.CANCELLED),
            )
        }

    // ------------------------------------------------------------------------------------------
    // the persisted schedule
    // ------------------------------------------------------------------------------------------

    fun getSchedule(): ChapterRevisionSweepScheduleDataClass? =
        transaction {
            ChapterRevisionSweepScheduleTable
                .selectAll()
                .where { ChapterRevisionSweepScheduleTable.id eq ChapterRevisionSweepScheduleTable.SINGLETON_ID }
                .firstOrNull()
                ?.let { ChapterRevisionSweepScheduleTable.toDataClass(it) }
        }

    /**
     * Creates the schedule row if it does not exist yet.
     *
     * The first due time is one full interval in the future, never "now": installing or upgrading a
     * server must not immediately re-download the newest chapters of every series.
     */
    fun ensureSchedule(
        intervalSeconds: Long,
        now: Long = Instant.now().epochSecond,
    ): ChapterRevisionSweepScheduleDataClass =
        transaction {
            readSchedule()?.let { return@transaction it }

            try {
                ChapterRevisionSweepScheduleTable.insert {
                    it[id] = ChapterRevisionSweepScheduleTable.SINGLETON_ID
                    it[nextDueAt] = saturatingEpochAdd(now, intervalSeconds)
                    it[lastRunAt] = null
                    it[lastSessionId] = null
                    it[retryNotBefore] = null
                    it[updatedAt] = now
                }
            } catch (e: Exception) {
                // a second instance may have inserted the singleton first, which is not an error
                if (!e.isDuplicateKeyViolation()) {
                    throw e
                }
            }

            readSchedule()!!
        }

    /**
     * Persists the backoff of a scheduled occurrence that could not even be started.
     *
     * [ChapterRevisionSweepScheduleTable.nextDueAt] is deliberately left where it is: a start that never
     * happened has not consumed the occurrence, so it is still owed. Persisting *when* the retry may
     * happen is what keeps a persistent failure from turning the worker's idle floor into a retry loop,
     * and returning false when there is no schedule row means an unscheduled server has nothing to defer.
     */
    fun recordScheduleStartFailure(
        deferSeconds: Long,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            val schedule = readSchedule() ?: return@transaction false
            val retryNotBefore = saturatingEpochAdd(now, deferSeconds.coerceAtLeast(1))
            // never shorten a pending wait: a clock that jumped backwards must not pull the retry in
            val effective = maxOf(schedule.retryNotBefore ?: retryNotBefore, retryNotBefore)

            ChapterRevisionSweepScheduleTable.update({
                ChapterRevisionSweepScheduleTable.id eq ChapterRevisionSweepScheduleTable.SINGLETON_ID
            }) {
                it[ChapterRevisionSweepScheduleTable.retryNotBefore] = effective
                it[updatedAt] = now
            } > 0
        }

    /**
     * The instant the scheduler may run next, or null while no automatic sweep is possible.
     *
     * The value is the *effective* due instant: an occurrence whose start failed stays due, but it may
     * not be retried before its persisted backoff. The worker sleeps on this value, which is what keeps
     * a failing start from waking the worker once per second.
     *
     * Returning null is what lets the worker wait for an explicit wake instead of polling: a disabled
     * scheduler and a missing schedule row both mean "nothing to wait for".
     */
    fun sweepDueAt(
        enabled: Boolean,
        now: Long = Instant.now().epochSecond,
    ): Long? {
        if (!enabled) {
            return null
        }

        return getSchedule()?.let { schedule ->
            maxOf(schedule.nextDueAt, schedule.retryNotBefore ?: schedule.nextDueAt)
        }
    }

    /**
     * Applies a configuration change to the persisted schedule.
     *
     * A *shorter* interval takes effect immediately, because that is the only way an operator can make
     * a sweep happen sooner without editing the database. A *longer* one never postpones a run that is
     * already scheduled: the instance which started that run is the only thing allowed to move it.
     */
    fun reschedule(
        intervalSeconds: Long,
        now: Long = Instant.now().epochSecond,
    ) {
        transaction {
            val schedule = readSchedule() ?: return@transaction
            val cap = saturatingEpochAdd(now, intervalSeconds)
            if (schedule.nextDueAt > cap) {
                ChapterRevisionSweepScheduleTable.update({
                    ChapterRevisionSweepScheduleTable.id eq ChapterRevisionSweepScheduleTable.SINGLETON_ID
                }) {
                    it[nextDueAt] = cap
                    it[updatedAt] = now
                }
            }
        }
    }

    /**
     * Runs one automatic tick.
     *
     * The whole occurrence is one transaction: the due instant is checked under the schedule row's own
     * lock, the run's session and its work list are written under that same lock, and the due time is
     * advanced in the same commit. A crash, a cancellation or a database error anywhere in between
     * therefore leaves the schedule exactly as it was, so the occurrence is retried instead of being
     * silently skipped - and a *completed* occurrence cannot be replayed either, because advancing the
     * due instant is part of the same commit that recorded it. Advancing one interval rather than one
     * per missed interval is what keeps a long outage from turning into a catch-up storm.
     *
     * Holding the lock while the work list is written is deliberate: the selection and the insert are
     * one decision, so a second instance cannot claim the same occurrence in between. It also means a
     * due occurrence with nothing to sweep still advances atomically - that occurrence really was run.
     *
     * @param beforeSessionCreated fault-injection seam: a test replaces it with a throw to prove that a
     * failure before the durable session exists leaves the due instant retryable. Production never
     * passes it.
     */
    fun runScheduledTick(
        intervalSeconds: Long,
        deferSeconds: Long,
        newestPerSeries: Int,
        now: Long = Instant.now().epochSecond,
        beforeSessionCreated: () -> Unit = {},
    ): TickOutcome {
        val decision =
            try {
                transaction {
                    val schedule =
                        ChapterRevisionSweepScheduleTable
                            .selectAll()
                            .where { ChapterRevisionSweepScheduleTable.id eq ChapterRevisionSweepScheduleTable.SINGLETON_ID }
                            .forUpdate()
                            .firstOrNull()
                            ?: return@transaction TickDecision.NotDue

                    val effectiveDueAt =
                        maxOf(
                            schedule[ChapterRevisionSweepScheduleTable.nextDueAt],
                            // an occurrence whose start failed is still due once its backoff has passed
                            schedule[ChapterRevisionSweepScheduleTable.retryNotBefore]
                                ?: schedule[ChapterRevisionSweepScheduleTable.nextDueAt],
                        )
                    if (effectiveDueAt > now) {
                        return@transaction TickDecision.NotDue
                    }

                    if (activeSessionId() != null) {
                        // Another sweep owns the library. Deferring - instead of leaving the due time in
                        // the past - is what keeps the worker from spinning during a long manual run.
                        deferScheduleLocked(deferSeconds, now)
                        return@transaction TickDecision.Deferred
                    }

                    val targets = loadTargets(null)
                    val items =
                        if (targets.isEmpty()) {
                            emptyList()
                        } else {
                            pendingItems(targets, loadChaptersByManga(targets.map { it.mangaId }, newestPerSeries))
                        }
                    if (items.isEmpty()) {
                        // a due occurrence with nothing to visit is a completed occurrence
                        advanceScheduleLocked(intervalSeconds, now, sessionId = null)
                        return@transaction TickDecision.NothingToSweep
                    }

                    beforeSessionCreated()
                    val sessionId = insertSession(ChapterRevisionSweepKind.SCHEDULED, newestPerSeries, now)
                    insertItems(sessionId, items, now)
                    advanceScheduleLocked(intervalSeconds, now, sessionId)

                    TickDecision.Started(readSession(sessionId)!!, items.size)
                }
            } catch (e: Exception) {
                // Losing the active-marker race is an expected outcome, not a failure of the occurrence:
                // deferring keeps the worker from retrying a start that cannot succeed right now. Every
                // other failure propagates with the schedule untouched, so the due instant stays
                // retryable rather than being consumed by an attempt that never happened.
                if (!e.isDuplicateKeyViolation()) {
                    throw e
                }

                logger.info { "chapter revision sweep scheduled run deferred: another sweep became active" }
                deferSchedule(deferSeconds, now)
                return TickOutcome.Deferred
            }

        return when (decision) {
            TickDecision.NotDue -> {
                TickOutcome.NotDue
            }

            TickDecision.Deferred -> {
                TickOutcome.Deferred
            }

            TickDecision.NothingToSweep -> {
                TickOutcome.NothingToSweep
            }

            is TickDecision.Started -> {
                logger.info { "chapter revision sweep ${decision.session.id} started with ${decision.itemCount} chapters" }
                ChapterRevisionSweepExecutor.notifyWorkAvailable()
                TickOutcome.Started(decision.session, decision.itemCount)
            }
        }
    }

    private sealed interface TickDecision {
        data object NotDue : TickDecision

        data object Deferred : TickDecision

        /** the occurrence ran and found nothing to visit, which is still a completed occurrence */
        data object NothingToSweep : TickDecision

        data class Started(
            val session: ChapterRevisionSweepSessionDataClass,
            val itemCount: Int,
        ) : TickDecision
    }

    /** Must be called inside a transaction. */
    private fun deferScheduleLocked(
        deferSeconds: Long,
        now: Long,
    ) {
        ChapterRevisionSweepScheduleTable.update({
            ChapterRevisionSweepScheduleTable.id eq ChapterRevisionSweepScheduleTable.SINGLETON_ID
        }) {
            it[nextDueAt] = saturatingEpochAdd(now, deferSeconds)
            // the occurrence was moved as a whole, so no separate retry is owed any more
            it[retryNotBefore] = null
            it[updatedAt] = now
        }
    }

    /** Moves the due instant forward in its own transaction, for a schedule a failed attempt left alone. */
    private fun deferSchedule(
        deferSeconds: Long,
        now: Long,
    ) {
        transaction { deferScheduleLocked(deferSeconds, now) }
    }

    /**
     * Advances the schedule past a completed occurrence; must be called inside the transaction that
     * records the occurrence, so no crash can separate the two.
     */
    private fun advanceScheduleLocked(
        intervalSeconds: Long,
        now: Long,
        sessionId: Int?,
    ) {
        ChapterRevisionSweepScheduleTable.update({
            ChapterRevisionSweepScheduleTable.id eq ChapterRevisionSweepScheduleTable.SINGLETON_ID
        }) {
            it[nextDueAt] = saturatingEpochAdd(now, intervalSeconds)
            it[lastRunAt] = now
            // the occurrence really ran, so any backoff of an earlier failed attempt is settled
            it[retryNotBefore] = null
            // an occurrence that found nothing has no session to link, so the previous run stays named
            if (sessionId != null) {
                it[lastSessionId] = sessionId
            }
            it[updatedAt] = now
        }
    }

    // ------------------------------------------------------------------------------------------
    // worker support
    // ------------------------------------------------------------------------------------------

    /**
     * Claims the oldest due chapter of the oldest runnable session, or null when nothing is due.
     *
     * The session row is locked first, so the persisted [ChapterRevisionSweepSessionTable.nextItemAt]
     * stays the single global rate limit even with two server instances, and a claim can only happen
     * against a session that is still RUNNING.
     */
    fun claimNextDueItem(now: Long = Instant.now().epochSecond): Claim? =
        transaction {
            val sessionRow =
                ChapterRevisionSweepSessionTable
                    .selectAll()
                    .where { runnableSessionCondition(now) }
                    .orderBy(ChapterRevisionSweepSessionTable.id to SortOrder.ASC)
                    .forUpdate()
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction null

            val session = ChapterRevisionSweepSessionTable.toDataClass(sessionRow)

            val itemRow =
                ChapterRevisionSweepItemTable
                    .selectAll()
                    .where { claimableItemCondition(session.id, now) }
                    // most overdue first, and an unset due time is due immediately: the pick agrees
                    // with the due time the worker sleeps until, which [nextDueAt] derives from the
                    // same ordering
                    .orderBy(
                        ChapterRevisionSweepItemTable.dueAt to SortOrder.ASC_NULLS_FIRST,
                        ChapterRevisionSweepItemTable.id to SortOrder.ASC,
                    ).forUpdate()
                    .limit(1)
                    .firstOrNull()

            if (itemRow == null) {
                // Nothing left to claim right now: either the session finished or every remaining
                // chapter is waiting out its own retry delay, in which case it stays RUNNING.
                reconcileSessionLocked(session.id, now)
                return@transaction null
            }

            val itemId = itemRow[ChapterRevisionSweepItemTable.id].value
            ChapterRevisionSweepItemTable.update({ ChapterRevisionSweepItemTable.id eq itemId }) {
                it[state] = ChapterRevisionSweepItemState.PROCESSING.name
                it[attempts] = itemRow[ChapterRevisionSweepItemTable.attempts] + 1
                it[startedAt] = now
                it[dueAt] = null
                it[updatedAt] = now
            }

            ChapterRevisionSweepSessionTable.update({ ChapterRevisionSweepSessionTable.id eq session.id }) {
                it[nextItemAt] = saturatingEpochAdd(now, session.itemDelaySeconds)
                it[lastItemAt] = now
                it[updatedAt] = now
            }

            Claim(
                item = readItem(itemId)!!,
                session = readSession(session.id)!!,
            )
        }

    /**
     * Returns interrupted chapters to the queue.
     *
     * An interrupted attempt consumed one attempt without proving anything about the chapter, so it is
     * normally requeued - but not immediately: the session's own retry delay is persisted as the new
     * due time, which is what stops a crash-looping process from re-sweeping the same chapters at every
     * startup. Once that attempt was the last one the session allows, the chapter is failed instead.
     * Attempts are never reset, so repeated crashes stay visible in the audit trail.
     */
    fun recoverInterruptedItems(now: Long = Instant.now().epochSecond) {
        transaction {
            val activeSessions =
                ChapterRevisionSweepSessionTable
                    .selectAll()
                    .where { ChapterRevisionSweepSessionTable.activeMarker eq ACTIVE_MARKER }
                    .map { ChapterRevisionSweepSessionTable.toDataClass(it) }

            val activeSessionIds = activeSessions.map { it.id }

            activeSessions.forEach { session ->
                ChapterRevisionSweepItemTable.update({
                    (ChapterRevisionSweepItemTable.session eq session.id) and
                        (ChapterRevisionSweepItemTable.state eq ChapterRevisionSweepItemState.PROCESSING.name) and
                        (ChapterRevisionSweepItemTable.attempts greaterEq session.maxAttempts)
                }) {
                    it[state] = ChapterRevisionSweepItemState.FAILED.name
                    it[lastError] = INTERRUPTED_ITEM_REASON
                    it[dueAt] = null
                    it[startedAt] = null
                    it[finishedAt] = now
                    it[updatedAt] = now
                }

                ChapterRevisionSweepItemTable.update({
                    (ChapterRevisionSweepItemTable.session eq session.id) and
                        (ChapterRevisionSweepItemTable.state eq ChapterRevisionSweepItemState.PROCESSING.name) and
                        (ChapterRevisionSweepItemTable.attempts less session.maxAttempts)
                }) {
                    it[state] = ChapterRevisionSweepItemState.RETRY_WAIT.name
                    it[dueAt] = saturatingEpochAdd(now, session.retrySeconds)
                    it[startedAt] = null
                    it[updatedAt] = now
                }
            }

            // A PROCESSING chapter of a cancelled or finished session can never be claimed again, so
            // it must not keep looking like work in progress.
            ChapterRevisionSweepItemTable.update({
                (ChapterRevisionSweepItemTable.state eq ChapterRevisionSweepItemState.PROCESSING.name) and
                    (ChapterRevisionSweepItemTable.session notInList activeSessionIds)
            }) {
                it[state] = ChapterRevisionSweepItemState.CANCELLED.name
                it[finishedAt] = now
                it[updatedAt] = now
            }

            activeSessionIds.forEach { reconcileSessionLocked(it, now) }
        }
    }

    /**
     * Earliest instant anything can still be processed, or null when no session is running.
     *
     * A running session that is only waiting out its own delay or a retry delay still counts, which is
     * what keeps the idle wait bounded instead of sleeping until an external wake up.
     */
    fun nextDueAt(now: Long = Instant.now().epochSecond): Long? =
        transaction {
            ChapterRevisionSweepSessionTable
                .selectAll()
                .where { ChapterRevisionSweepSessionTable.state eq ChapterRevisionSweepSessionState.RUNNING.name }
                .orderBy(ChapterRevisionSweepSessionTable.id to SortOrder.ASC)
                .map { ChapterRevisionSweepSessionTable.toDataClass(it) }
                .mapNotNull { session ->
                    // The earliest *claimable* chapter decides, not the oldest row: taking the oldest
                    // row would let a chapter waiting out a retry hide a later chapter that is already
                    // due, and the worker would sleep through work it could have done right now.
                    val earliestClaimable =
                        ChapterRevisionSweepItemTable
                            .selectAll()
                            .where {
                                (ChapterRevisionSweepItemTable.session eq session.id) and
                                    (ChapterRevisionSweepItemTable.state inList claimableStates.map { it.name })
                            }.orderBy(
                                ChapterRevisionSweepItemTable.dueAt to SortOrder.ASC_NULLS_FIRST,
                                ChapterRevisionSweepItemTable.id to SortOrder.ASC,
                            ).limit(1)
                            .firstOrNull()
                            // Nothing claimable at all: the session can only advance once a chapter
                            // reports back, so it contributes no due time of its own.
                            ?: return@mapNotNull null

                    val itemDueAt = earliestClaimable[ChapterRevisionSweepItemTable.dueAt] ?: now
                    // the persisted inter-item delay stays the global rate limit on top of that
                    session.nextItemAt?.let { maxOf(itemDueAt, it) } ?: itemDueAt
                }.minOrNull()
        }

    fun completeItem(
        claim: Claim,
        candidateCount: Int,
        now: Long = Instant.now().epochSecond,
    ) {
        transaction {
            ChapterRevisionSweepItemTable.update({
                (ChapterRevisionSweepItemTable.id eq claim.item.id) and
                    (ChapterRevisionSweepItemTable.state eq ChapterRevisionSweepItemState.PROCESSING.name)
            }) {
                it[state] = ChapterRevisionSweepItemState.COMPLETE.name
                it[ChapterRevisionSweepItemTable.candidateCount] = candidateCount
                it[lastError] = null
                it[finishedAt] = now
                it[updatedAt] = now
            }

            reconcileSessionLocked(claim.session.id, now)
        }

        // candidates only became visible when their transaction committed, so the acquisition worker
        // is woken strictly afterwards
        if (candidateCount > 0) {
            ChapterRevisionAcquisitionExecutor.notifyWorkAvailable()
        }
    }

    /**
     * Records a failed attempt.
     *
     * A chapter is only given up on once the attempts snapshot of its session is exhausted; before that
     * it waits out a persisted retry delay, which also keeps one broken item from spinning.
     */
    fun failItem(
        claim: Claim,
        error: Throwable?,
        now: Long = Instant.now().epochSecond,
    ) {
        // The throwable message routinely embeds source URLs, cookies or tokens, and this column is
        // persisted, logged and served to authenticated clients, so only a static phrase plus the
        // exception type is recorded. The raw throwable never crosses this orchestration boundary.
        val message = failureReason(error)

        logger.debug { "chapter revision sweep item ${claim.item.id} of run ${claim.session.id} failed: $message" }

        transaction {
            val exhausted = claim.item.attempts >= claim.session.maxAttempts
            val nextState =
                if (exhausted) ChapterRevisionSweepItemState.FAILED else ChapterRevisionSweepItemState.RETRY_WAIT

            ChapterRevisionSweepItemTable.update({
                (ChapterRevisionSweepItemTable.id eq claim.item.id) and
                    (ChapterRevisionSweepItemTable.state eq ChapterRevisionSweepItemState.PROCESSING.name)
            }) {
                it[state] = nextState.name
                it[lastError] = message
                it[dueAt] = if (exhausted) null else saturatingEpochAdd(now, claim.session.retrySeconds)
                if (exhausted) it[finishedAt] = now
                it[updatedAt] = now
            }

            reconcileSessionLocked(claim.session.id, now)
        }
    }

    /**
     * A bounded, source-independent diagnostic for a chapter whose attempt failed.
     *
     * The exception *type* is kept because it is the only part that is safe to persist: it tells an
     * operator whether the source timed out, was unreachable or answered unexpectedly, while the
     * message - which is where URLs and credentials leak into logs - is deliberately dropped.
     */
    private fun failureReason(error: Throwable?): String =
        (
            error?.let { "the chapter could not be swept (${it.javaClass.simpleName})" }
                ?: "the chapter could not be swept"
        ).take(MAX_ERROR_LENGTH)

    /** A chapter that cannot be swept for a durable reason, such as a paused series. */
    fun markItemSkipped(
        claim: Claim,
        reason: String,
        now: Long = Instant.now().epochSecond,
    ) {
        transaction {
            ChapterRevisionSweepItemTable.update({
                (ChapterRevisionSweepItemTable.id eq claim.item.id) and
                    (ChapterRevisionSweepItemTable.state eq ChapterRevisionSweepItemState.PROCESSING.name)
            }) {
                it[state] = ChapterRevisionSweepItemState.SKIPPED.name
                it[lastError] = reason.take(MAX_ERROR_LENGTH)
                it[dueAt] = null
                it[finishedAt] = now
                it[updatedAt] = now
            }

            reconcileSessionLocked(claim.session.id, now)
        }
    }

    /** The persisted reason a paused series records. */
    fun pausedSeriesReason(mangaId: Int?): String = "series $mangaId is paused, so nothing is fetched for it"

    /** The persisted reason a chapter that vanished from the library records. */
    fun chapterGoneReason(mangaId: Int?): String = "series $mangaId no longer has this chapter in the library"

    // ------------------------------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------------------------------

    private fun runnableSessionCondition(now: Long): Op<Boolean> =
        (ChapterRevisionSweepSessionTable.state eq ChapterRevisionSweepSessionState.RUNNING.name) and
            (
                (ChapterRevisionSweepSessionTable.nextItemAt.isNull()) or
                    (ChapterRevisionSweepSessionTable.nextItemAt lessEq now)
            )

    private fun claimableItemCondition(
        sessionId: Int,
        now: Long,
    ): Op<Boolean> =
        (ChapterRevisionSweepItemTable.session eq sessionId) and
            (ChapterRevisionSweepItemTable.state inList claimableStates.map { it.name }) and
            (
                (ChapterRevisionSweepItemTable.dueAt.isNull()) or
                    (ChapterRevisionSweepItemTable.dueAt lessEq now)
            )

    private fun retryableItemCondition(
        sessionId: Int,
        itemIds: List<Int>?,
    ): Op<Boolean> =
        listOfNotNull(
            ChapterRevisionSweepItemTable.session eq sessionId,
            ChapterRevisionSweepItemTable.state inList errorStates.map { it.name },
            itemIds?.let { ChapterRevisionSweepItemTable.id inList it },
        ).reduce { acc, op -> acc and op }

    /** Must be called inside a transaction. */
    private fun activeSessionId(): Int? =
        ChapterRevisionSweepSessionTable
            .selectAll()
            .where { ChapterRevisionSweepSessionTable.activeMarker eq ACTIVE_MARKER }
            .firstOrNull()
            ?.get(ChapterRevisionSweepSessionTable.id)
            ?.value

    /** Must be called inside a transaction. */
    private fun readSession(sessionId: Int): ChapterRevisionSweepSessionDataClass? =
        ChapterRevisionSweepSessionTable
            .selectAll()
            .where { ChapterRevisionSweepSessionTable.id eq sessionId }
            .firstOrNull()
            ?.let { ChapterRevisionSweepSessionTable.toDataClass(it) }

    /** Must be called inside a transaction. */
    private fun readItem(itemId: Int): ChapterRevisionSweepItemDataClass? =
        ChapterRevisionSweepItemTable
            .selectAll()
            .where { ChapterRevisionSweepItemTable.id eq itemId }
            .firstOrNull()
            ?.let { ChapterRevisionSweepItemTable.toDataClass(it) }

    /** Must be called inside a transaction. */
    private fun readSchedule(): ChapterRevisionSweepScheduleDataClass? =
        ChapterRevisionSweepScheduleTable
            .selectAll()
            .where { ChapterRevisionSweepScheduleTable.id eq ChapterRevisionSweepScheduleTable.SINGLETON_ID }
            .firstOrNull()
            ?.let { ChapterRevisionSweepScheduleTable.toDataClass(it) }

    /**
     * Settles a session once nothing is left to claim.
     *
     * Only a session that is still running or paused is reconciled; a cancelled or already finished one
     * must not be resurrected by a straggling in-flight chapter completing.
     */
    private fun reconcileSessionLocked(
        sessionId: Int,
        now: Long,
    ) {
        val sessionRow =
            ChapterRevisionSweepSessionTable
                .selectAll()
                .where { ChapterRevisionSweepSessionTable.id eq sessionId }
                .forUpdate()
                .firstOrNull()
                ?: return
        if (!ChapterRevisionSweepSessionState.valueOf(sessionRow[ChapterRevisionSweepSessionTable.state]).isActive) {
            return
        }

        val unfinished =
            ChapterRevisionSweepItemTable
                .selectAll()
                .where {
                    (ChapterRevisionSweepItemTable.session eq sessionId) and
                        (ChapterRevisionSweepItemTable.state inList unfinishedStates.map { it.name })
                }.count()
        if (unfinished > 0) {
            return
        }

        val errored =
            ChapterRevisionSweepItemTable
                .selectAll()
                .where {
                    (ChapterRevisionSweepItemTable.session eq sessionId) and
                        (ChapterRevisionSweepItemTable.state inList errorStates.map { it.name })
                }.count()

        val terminal =
            if (errored > 0) {
                ChapterRevisionSweepSessionState.COMPLETED_WITH_ERRORS
            } else {
                ChapterRevisionSweepSessionState.COMPLETED
            }

        ChapterRevisionSweepSessionTable.update({ ChapterRevisionSweepSessionTable.id eq sessionId }) {
            it[state] = terminal.name
            it[activeMarker] = null
            it[finishedAt] = now
            it[updatedAt] = now
        }
    }

    /**
     * The tracked series a sweep will visit.
     *
     * One query for the whole selection, so a subset of a large library is never resolved series by
     * series.
     */
    private fun loadTargets(mangaIds: List<Int>?): List<Target> =
        transaction {
            val condition =
                if (mangaIds == null) {
                    MangaTable.inLibrary eq true
                } else {
                    (MangaTable.inLibrary eq true) and (MangaTable.id inList mangaIds)
                }

            MangaTable
                .selectAll()
                .where { condition }
                .orderBy(MangaTable.id to SortOrder.ASC)
                .map {
                    Target(
                        mangaId = it[MangaTable.id].value,
                        url = it[MangaTable.url],
                        title = it[MangaTable.title],
                        sourceId = it[MangaTable.sourceReference],
                        policy = MangaAcquisitionPolicy.valueOf(it[MangaTable.acquisitionPolicy]),
                    )
                }
        }

    /**
     * Selects the newest chapters of every target series in one query.
     *
     * "Newest" is the ordering the rest of the server already uses for a manga's latest chapter - the
     * most recent upload date first, the source's own position as the tiebreaker - so a sweep visits
     * exactly the chapters an operator sees at the top of a series. Only the identity of each chapter
     * is kept: the candidate is created from the row that is re-read when the item is processed.
     */
    private fun loadChaptersByManga(
        mangaIds: List<Int>,
        newestPerSeries: Int?,
    ): Map<Int, List<SelectedChapter>> =
        transaction {
            ChapterTable
                .selectAll()
                .where { ChapterTable.manga inList mangaIds }
                .orderBy(
                    ChapterTable.manga to SortOrder.ASC,
                    ChapterTable.date_upload to SortOrder.DESC,
                    ChapterTable.sourceOrder to SortOrder.DESC,
                    ChapterTable.id to SortOrder.DESC,
                ).map {
                    it[ChapterTable.manga].value to
                        SelectedChapter(
                            id = it[ChapterTable.id].value,
                            url = it[ChapterTable.url],
                            name = it[ChapterTable.name],
                        )
                }.groupBy({ it.first }, { it.second })
                .mapValues { (_, chapters) ->
                    // groupBy keeps the encounter order of each group, so the limit keeps the newest
                    if (newestPerSeries == null) chapters else chapters.take(newestPerSeries)
                }
        }

    private fun pendingItems(
        targets: List<Target>,
        chaptersByManga: Map<Int, List<SelectedChapter>>,
    ): List<PendingItem> =
        targets.flatMap { target ->
            chaptersByManga[target.mangaId].orEmpty().map { chapter -> PendingItem(target, chapter) }
        }

    private fun createSession(
        kind: ChapterRevisionSweepKind,
        newestPerSeries: Int?,
        items: List<PendingItem>,
        now: Long,
    ): Pair<ChapterRevisionSweepSessionDataClass, Int> =
        transaction {
            val sessionId = insertSession(kind, newestPerSeries, now)
            insertItems(sessionId, items, now)
            readSession(sessionId)!! to items.size
        }

    /**
     * Inserts the session row itself.
     *
     * Must be called inside a transaction: an automatic occurrence has to create this row and advance
     * the schedule in one commit, so the caller owns the transaction boundary.
     */
    private fun insertSession(
        kind: ChapterRevisionSweepKind,
        newestPerSeries: Int?,
        now: Long,
    ): Int =
        ChapterRevisionSweepSessionTable
            .insertAndGetId {
                it[state] = ChapterRevisionSweepSessionState.RUNNING.name
                it[activeMarker] = ACTIVE_MARKER
                it[ChapterRevisionSweepSessionTable.kind] = kind.name
                it[ChapterRevisionSweepSessionTable.newestPerSeries] = newestPerSeries
                it[itemDelaySeconds] = serverConfig.chapterRevisionSweepItemDelaySeconds.value.toLong()
                it[retrySeconds] = serverConfig.chapterRevisionSweepRetrySeconds.value.toLong()
                it[maxAttempts] = serverConfig.chapterRevisionSweepMaxAttempts.value
                it[startedAt] = now
                it[updatedAt] = now
                it[nextItemAt] = now
            }.value

    /**
     * Writes the work list of a session.
     *
     * Only one small row per chapter with its snapshotted policy: no chapter is refreshed and no
     * candidate is created, so starting a full sweep never queues tens of thousands of downloads at
     * once. Must be called inside a transaction.
     */
    private fun insertItems(
        sessionId: Int,
        items: List<PendingItem>,
        now: Long,
    ) {
        items.chunked(ITEM_INSERT_BATCH).forEach { chunk ->
            ChapterRevisionSweepItemTable.batchInsert(chunk) { pendingItem ->
                val target = pendingItem.target
                val chapter = pendingItem.chapter

                this[ChapterRevisionSweepItemTable.session] = sessionId
                this[ChapterRevisionSweepItemTable.mangaId] = target.mangaId
                this[ChapterRevisionSweepItemTable.chapterId] = chapter.id
                this[ChapterRevisionSweepItemTable.sourceId] = target.sourceId
                this[ChapterRevisionSweepItemTable.chapterKey] =
                    ChapterRevision.chapterKey(target.sourceId, target.url, chapter.url)
                this[ChapterRevisionSweepItemTable.seriesTitle] = target.title
                this[ChapterRevisionSweepItemTable.chapterName] = chapter.name
                this[ChapterRevisionSweepItemTable.sourceChapterUrl] = chapter.url
                this[ChapterRevisionSweepItemTable.policy] = target.policy.name
                this[ChapterRevisionSweepItemTable.state] = ChapterRevisionSweepItemState.PENDING.name
                this[ChapterRevisionSweepItemTable.attempts] = 0
                this[ChapterRevisionSweepItemTable.updatedAt] = now
            }
        }
    }
}
