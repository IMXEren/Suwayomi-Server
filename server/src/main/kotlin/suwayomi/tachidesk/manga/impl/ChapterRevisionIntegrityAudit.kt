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
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.util.lang.isDuplicateKeyViolation
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditItemDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditItemState
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditKind
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditProgress
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditScheduleDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditSessionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditSessionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionIntegrityState
import suwayomi.tachidesk.manga.model.table.ChapterIntegrityAuditItemTable
import suwayomi.tachidesk.manga.model.table.ChapterIntegrityAuditScheduleTable
import suwayomi.tachidesk.manga.model.table.ChapterIntegrityAuditSessionTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.serverConfig
import java.time.Instant

/**
 * Durable archive integrity audits.
 *
 * An audit answers the one question the archive cannot answer from its own state: the persisted
 * archive state says a revision *was* durably committed, it does not say the object is still there.
 * A run therefore walks the revisions the archive still claims - durably confirmed, payload not
 * scheduled for removal - and asks remote storage directly about each of them.
 *
 * The run only ever *records* what it found. It never deletes, repairs or re-uploads anything, and it
 * never rewrites the archive state: a finding lives in the revision's own integrity dimension, so
 * "was durably archived" and "is not there any more" stay readable at the same time.
 *
 * Everything the run needs is snapshotted into its items when it starts - the revision id, the
 * candidate key and the exact paths, sizes and digests of both archived objects - so a later edit of a
 * revision cannot change what an already recorded run audited.
 *
 * The schedule is one persisted row instead of a computed instant, so a restart cannot invent an audit
 * and a long outage cannot turn into a catch-up storm: only the worker that actually started a run
 * moves the due time forward.
 */
object ChapterRevisionIntegrityAudit {
    /** the only value [ChapterIntegrityAuditSessionTable.activeMarker] ever holds */
    const val ACTIVE_MARKER = "ACTIVE"

    private const val MAX_ERROR_LENGTH = 1024
    private const val ITEM_INSERT_BATCH = 500

    /**
     * Persisted reason for a revision a shutdown interrupted mid-check.
     *
     * Static on purpose: it is stored in a column exposed through authenticated GraphQL, so it must
     * never carry anything derived from a remote response.
     */
    private const val INTERRUPTED_ITEM_REASON = "the server stopped while this revision was being checked"

    private val logger = KotlinLogging.logger {}

    private val claimableStates =
        listOf(
            ChapterIntegrityAuditItemState.PENDING,
            ChapterIntegrityAuditItemState.RETRY_WAIT,
        )

    /** Item states that can still change without human interaction. */
    private val unfinishedStates = claimableStates + ChapterIntegrityAuditItemState.CHECKING

    /** Item states that make a finished run report [ChapterIntegrityAuditSessionState.COMPLETED_WITH_ERRORS]. */
    private val errorStates = listOf(ChapterIntegrityAuditItemState.FAILED)

    /** How an audit was asked to start. */
    data class StartRequest(
        val kind: ChapterIntegrityAuditKind,
        /** optional subset of series; null audits every series that has archived content */
        val mangaIds: List<Int>? = null,
    )

    sealed interface StartOutcome {
        data class Started(
            val session: ChapterIntegrityAuditSessionDataClass,
            val itemCount: Int,
        ) : StartOutcome

        /** no archived revision matches the request */
        data object NothingToAudit : StartOutcome

        data object ActiveSessionExists : StartOutcome

        /** the requested subset names series that are not in the database, which is a client error */
        data class InvalidSubset(
            val unknownMangaIds: List<Int>,
        ) : StartOutcome
    }

    sealed interface RetryOutcome {
        data class Retried(
            val session: ChapterIntegrityAuditSessionDataClass,
            val itemCount: Int,
        ) : RetryOutcome

        data object NotFound : RetryOutcome

        data object AnotherSessionActive : RetryOutcome
    }

    /** What one automatic tick decided to do. */
    sealed interface TickOutcome {
        data class Started(
            val session: ChapterIntegrityAuditSessionDataClass,
            val itemCount: Int,
        ) : TickOutcome

        /** the persisted due time has not been reached yet */
        data object NotDue : TickOutcome

        /** another audit owns the worker; the due time is deferred rather than retried in a loop */
        data object Deferred : TickOutcome

        data object NothingToAudit : TickOutcome
    }

    /** One claimed revision together with the run settings it has to be checked under. */
    data class Claim(
        val item: ChapterIntegrityAuditItemDataClass,
        val session: ChapterIntegrityAuditSessionDataClass,
    )

    /** The number of newest revisions per series a kind selects; null means every eligible revision. */
    fun newestPerMangaFor(
        kind: ChapterIntegrityAuditKind,
        configuredNewest: Int,
    ): Int? = if (kind.isFullHistory) null else configuredNewest.coerceAtLeast(1)

    fun start(
        request: StartRequest,
        newestPerManga: Int?,
        now: Long = Instant.now().epochSecond,
    ): StartOutcome {
        if (getActiveSession() != null) {
            return StartOutcome.ActiveSessionExists
        }

        val subset = request.mangaIds?.distinct()
        if (subset != null) {
            val known = knownMangaIds(subset)
            val unknown = subset.filter { it !in known }
            if (unknown.isNotEmpty()) {
                return StartOutcome.InvalidSubset(unknown)
            }
        }

        val items = auditTargets(subset, newestPerManga)
        if (items.isEmpty()) {
            return StartOutcome.NothingToAudit
        }

        return try {
            val (session, itemCount) = createSession(request.kind, newestPerManga, items, now)
            logger.info { "chapter integrity audit ${session.id} started with $itemCount revisions" }
            ChapterRevisionIntegrityAuditExecutor.notifyWorkAvailable()
            StartOutcome.Started(session, itemCount)
        } catch (e: Exception) {
            // The unique active marker is the real guard against two concurrent starts. Losing that race
            // is an expected outcome; anything else - a database error, a serialization failure - has to
            // propagate instead of being misreported as a conflict the caller cannot resolve.
            if (!e.isDuplicateKeyViolation()) {
                throw e
            }

            logger.info { "chapter integrity audit start rejected: another audit is active" }
            StartOutcome.ActiveSessionExists
        }
    }

    /**
     * Stops claiming revisions. One already being checked is allowed to finish.
     *
     * This is the operator's pause: the run keeps its items, its attempts and its due time, so a resume
     * continues exactly where it stopped instead of re-checking what it already checked.
     */
    fun pause(
        sessionId: Int,
        now: Long = Instant.now().epochSecond,
    ): ChapterIntegrityAuditSessionDataClass? =
        transaction {
            val session = readSession(sessionId) ?: return@transaction null
            if (session.state != ChapterIntegrityAuditSessionState.RUNNING) {
                return@transaction null
            }

            ChapterIntegrityAuditSessionTable.update({ ChapterIntegrityAuditSessionTable.id eq sessionId }) {
                it[state] = ChapterIntegrityAuditSessionState.PAUSED.name
                it[pausedAt] = now
                it[updatedAt] = now
            }

            readSession(sessionId)
        }

    fun resume(
        sessionId: Int,
        now: Long = Instant.now().epochSecond,
    ): ChapterIntegrityAuditSessionDataClass? =
        transaction {
            val session = readSession(sessionId) ?: return@transaction null
            if (session.state != ChapterIntegrityAuditSessionState.PAUSED) {
                return@transaction null
            }

            ChapterIntegrityAuditSessionTable.update({ ChapterIntegrityAuditSessionTable.id eq sessionId }) {
                it[state] = ChapterIntegrityAuditSessionState.RUNNING.name
                it[ChapterIntegrityAuditSessionTable.pausedAt] = null
                // a run that was paused for longer than its own pacing delay must not be held back by it
                it[nextItemAt] = null
                it[updatedAt] = now
            }

            readSession(sessionId)
        }

    /**
     * Abandons a run. Every unfinished revision is recorded as cancelled, never as checked.
     *
     * A revision that was already found to be missing stays missing: a cancel ends the run, it does not
     * undo a finding the run already made.
     */
    fun cancel(
        sessionId: Int,
        now: Long = Instant.now().epochSecond,
    ): ChapterIntegrityAuditSessionDataClass? =
        transaction {
            val session = readSession(sessionId) ?: return@transaction null
            if (!session.state.isActive) {
                return@transaction null
            }

            ChapterIntegrityAuditItemTable.update({
                (ChapterIntegrityAuditItemTable.session eq sessionId) and
                    (ChapterIntegrityAuditItemTable.state inList unfinishedStates.map { it.name })
            }) {
                it[state] = ChapterIntegrityAuditItemState.SKIPPED.name
                it[lastError] = null
                it[dueAt] = null
                it[finishedAt] = now
                it[updatedAt] = now
            }

            ChapterIntegrityAuditSessionTable.update({ ChapterIntegrityAuditSessionTable.id eq sessionId }) {
                it[state] = ChapterIntegrityAuditSessionState.CANCELLED.name
                // cleared here rather than by a reconcile pass, so the invariant is released by the same
                // commit that ends the run
                it[activeMarker] = null
                it[cancelledAt] = now
                it[finishedAt] = now
                it[updatedAt] = now
            }

            readSession(sessionId)
        }

    /**
     * Requeues the revisions of a run whose checks failed, preserving their attempt counts.
     *
     * This deliberately does not requeue findings: a revision recorded as missing or corrupt is not
     * something a retry can change, only a new run can - and a new run re-selects it anyway.
     */
    fun retry(
        sessionId: Int,
        itemIds: List<Int>? = null,
        now: Long = Instant.now().epochSecond,
    ): RetryOutcome {
        if (getActiveSession() != null) {
            return RetryOutcome.AnotherSessionActive
        }

        return transaction {
            val session = readSession(sessionId) ?: return@transaction RetryOutcome.NotFound

            val retried =
                ChapterIntegrityAuditItemTable.update({
                    (ChapterIntegrityAuditItemTable.session eq sessionId) and
                        (ChapterIntegrityAuditItemTable.state inList errorStates.map { it.name }) and
                        (itemIds?.let { ChapterIntegrityAuditItemTable.id inList it } ?: Op.TRUE)
                }) {
                    it[state] = ChapterIntegrityAuditItemState.PENDING.name
                    it[ChapterIntegrityAuditItemTable.lastError] = null
                    it[dueAt] = null
                    it[startedAt] = null
                    it[finishedAt] = null
                    it[updatedAt] = now
                }

            if (retried == 0) {
                return@transaction RetryOutcome.Retried(session, 0)
            }

            // a finished run that gets work back has to become active again, which the unique active
            // marker both permits and re-establishes
            ChapterIntegrityAuditSessionTable.update({ ChapterIntegrityAuditSessionTable.id eq sessionId }) {
                it[state] = ChapterIntegrityAuditSessionState.RUNNING.name
                it[activeMarker] = ACTIVE_MARKER
                it[finishedAt] = null
                it[updatedAt] = now
            }

            RetryOutcome.Retried(readSession(sessionId)!!, retried)
        }.also { outcome ->
            if (outcome is RetryOutcome.Retried && outcome.itemCount > 0) {
                ChapterRevisionIntegrityAuditExecutor.notifyWorkAvailable()
            }
        }
    }

    fun getSession(sessionId: Int): ChapterIntegrityAuditSessionDataClass? = transaction { readSession(sessionId) }

    fun getActiveSession(): ChapterIntegrityAuditSessionDataClass? =
        transaction {
            ChapterIntegrityAuditSessionTable
                .selectAll()
                .where { ChapterIntegrityAuditSessionTable.activeMarker eq ACTIVE_MARKER }
                .firstOrNull()
                ?.let { ChapterIntegrityAuditSessionTable.toDataClass(it) }
        }

    fun getLatestSession(): ChapterIntegrityAuditSessionDataClass? =
        transaction {
            ChapterIntegrityAuditSessionTable
                .selectAll()
                .orderBy(ChapterIntegrityAuditSessionTable.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.let { ChapterIntegrityAuditSessionTable.toDataClass(it) }
        }

    fun sessionCondition(state: ChapterIntegrityAuditSessionState? = null): Op<Boolean>? =
        state?.let { ChapterIntegrityAuditSessionTable.state eq it.name }

    fun itemCondition(
        sessionId: Int,
        state: ChapterIntegrityAuditItemState? = null,
        mangaId: Int? = null,
    ): Op<Boolean> =
        listOfNotNull(
            ChapterIntegrityAuditItemTable.session eq sessionId,
            state?.let { ChapterIntegrityAuditItemTable.state eq it.name },
            mangaId?.let { ChapterIntegrityAuditItemTable.mangaId eq it },
        ).reduce { acc, op -> acc and op }

    /** Counts one run's progress from its items, so a report can never disagree with the queue. */
    fun progress(sessionId: Int): ChapterIntegrityAuditProgress =
        transaction {
            ChapterIntegrityAuditItemTable
                .selectAll()
                .where { ChapterIntegrityAuditItemTable.session eq sessionId }
                .groupingBy { it[ChapterIntegrityAuditItemTable.state] }
                .eachCount()
                .let { counts ->
                    fun count(state: ChapterIntegrityAuditItemState) = counts[state.name] ?: 0

                    ChapterIntegrityAuditProgress(
                        total = counts.values.sum(),
                        pending = count(ChapterIntegrityAuditItemState.PENDING),
                        checking = count(ChapterIntegrityAuditItemState.CHECKING),
                        retryWait = count(ChapterIntegrityAuditItemState.RETRY_WAIT),
                        verified = count(ChapterIntegrityAuditItemState.VERIFIED),
                        missing = count(ChapterIntegrityAuditItemState.MISSING),
                        corrupt = count(ChapterIntegrityAuditItemState.CORRUPT),
                        failed = count(ChapterIntegrityAuditItemState.FAILED),
                        skipped = count(ChapterIntegrityAuditItemState.SKIPPED),
                    )
                }
        }

    // ------------------------------------------------------------------------------------------
    // the persisted schedule
    // ------------------------------------------------------------------------------------------

    fun getSchedule(): ChapterIntegrityAuditScheduleDataClass? =
        transaction {
            ChapterIntegrityAuditScheduleTable
                .selectAll()
                .where { ChapterIntegrityAuditScheduleTable.id eq ChapterIntegrityAuditScheduleTable.SINGLETON_ID }
                .firstOrNull()
                ?.let { ChapterIntegrityAuditScheduleTable.toDataClass(it) }
        }

    /**
     * Creates the schedule row if it does not exist yet.
     *
     * The first due time is one full interval in the future, never "now": installing or upgrading a
     * server must not immediately re-check the materialized artifacts of the whole library.
     */
    fun ensureSchedule(
        intervalSeconds: Long,
        now: Long = Instant.now().epochSecond,
    ): ChapterIntegrityAuditScheduleDataClass =
        transaction {
            readSchedule()?.let { return@transaction it }

            try {
                ChapterIntegrityAuditScheduleTable.insert {
                    it[id] = ChapterIntegrityAuditScheduleTable.SINGLETON_ID
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
     * [ChapterIntegrityAuditScheduleTable.nextDueAt] is deliberately left where it is: a start that never
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

            ChapterIntegrityAuditScheduleTable.update({
                ChapterIntegrityAuditScheduleTable.id eq ChapterIntegrityAuditScheduleTable.SINGLETON_ID
            }) {
                it[ChapterIntegrityAuditScheduleTable.retryNotBefore] = effective
                it[updatedAt] = now
            } > 0
        }

    /**
     * The instant the scheduler may run next, or null while no automatic audit is possible.
     *
     * The value is the *effective* due instant: an occurrence whose start failed stays due, but it may
     * not be retried before its persisted backoff. Returning null is what lets the worker wait for an
     * explicit wake instead of polling.
     */
    fun auditDueAt(
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
     * an audit happen sooner without editing the database. A *longer* one never postpones a run that is
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
                ChapterIntegrityAuditScheduleTable.update({
                    ChapterIntegrityAuditScheduleTable.id eq ChapterIntegrityAuditScheduleTable.SINGLETON_ID
                }) {
                    it[nextDueAt] = cap
                    it[updatedAt] = now
                }
            }
        }
    }

    /**
     * Runs one automatic occurrence.
     *
     * The whole occurrence is one transaction: the due instant is checked under the schedule row's own
     * lock, the run's session and its work list are written under that same lock, and the due time is
     * advanced in the same commit. A crash, a cancellation or a database error anywhere in between
     * therefore leaves the schedule exactly as it was, so the occurrence is retried instead of being
     * silently skipped - and a *completed* occurrence cannot be replayed either, because advancing the
     * due instant is part of the same commit that recorded it.
     *
     * @param beforeSessionCreated fault-injection seam: a test replaces it with a throw to prove that a
     * failure before the durable session exists leaves the due instant retryable. Production never
     * passes it.
     */
    fun runScheduledTick(
        intervalSeconds: Long,
        deferSeconds: Long,
        newestPerManga: Int,
        now: Long = Instant.now().epochSecond,
        beforeSessionCreated: () -> Unit = {},
    ): TickOutcome {
        val decision =
            try {
                transaction {
                    val schedule =
                        ChapterIntegrityAuditScheduleTable
                            .selectAll()
                            .where {
                                ChapterIntegrityAuditScheduleTable.id eq ChapterIntegrityAuditScheduleTable.SINGLETON_ID
                            }.forUpdate()
                            .firstOrNull()
                            ?: return@transaction TickDecision.NotDue

                    val effectiveDueAt =
                        maxOf(
                            schedule[ChapterIntegrityAuditScheduleTable.nextDueAt],
                            // an occurrence whose start failed is still due once its backoff has passed
                            schedule[ChapterIntegrityAuditScheduleTable.retryNotBefore]
                                ?: schedule[ChapterIntegrityAuditScheduleTable.nextDueAt],
                        )
                    if (effectiveDueAt > now) {
                        return@transaction TickDecision.NotDue
                    }

                    if (activeSessionId() != null) {
                        // Another audit owns the library. Deferring - instead of leaving the due time in the
                        // past - is what keeps the worker from spinning during a long manual run.
                        deferScheduleLocked(deferSeconds, now)
                        return@transaction TickDecision.Deferred
                    }

                    val items = auditTargets(null, newestPerManga)
                    if (items.isEmpty()) {
                        // a due occurrence with nothing to visit is a completed occurrence
                        advanceScheduleLocked(intervalSeconds, now, sessionId = null)
                        return@transaction TickDecision.NothingToAudit
                    }

                    beforeSessionCreated()
                    val sessionId = insertSession(ChapterIntegrityAuditKind.SCHEDULED, newestPerManga, now)
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

                logger.info { "scheduled chapter integrity audit deferred: another audit became active" }
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

            TickDecision.NothingToAudit -> {
                TickOutcome.NothingToAudit
            }

            is TickDecision.Started -> {
                logger.info { "chapter integrity audit ${decision.session.id} started with ${decision.itemCount} revisions" }
                ChapterRevisionIntegrityAuditExecutor.notifyWorkAvailable()
                TickOutcome.Started(decision.session, decision.itemCount)
            }
        }
    }

    private sealed interface TickDecision {
        data object NotDue : TickDecision

        data object Deferred : TickDecision

        /** the occurrence ran and found nothing to visit, which is still a completed occurrence */
        data object NothingToAudit : TickDecision

        data class Started(
            val session: ChapterIntegrityAuditSessionDataClass,
            val itemCount: Int,
        ) : TickDecision
    }

    /** Must be called inside a transaction. */
    private fun deferScheduleLocked(
        deferSeconds: Long,
        now: Long,
    ) {
        ChapterIntegrityAuditScheduleTable.update({
            ChapterIntegrityAuditScheduleTable.id eq ChapterIntegrityAuditScheduleTable.SINGLETON_ID
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
        ChapterIntegrityAuditScheduleTable.update({
            ChapterIntegrityAuditScheduleTable.id eq ChapterIntegrityAuditScheduleTable.SINGLETON_ID
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
     * Claims the oldest due revision of the oldest runnable run, or null when nothing is due.
     *
     * The session row is locked first, so the persisted [ChapterIntegrityAuditSessionTable.nextItemAt]
     * stays the single global rate limit even with two server instances, and a claim can only happen
     * against a session that is still RUNNING.
     */
    fun claimNextDueItem(now: Long = Instant.now().epochSecond): Claim? =
        transaction {
            val sessionRow =
                ChapterIntegrityAuditSessionTable
                    .selectAll()
                    .where { runnableSessionCondition(now) }
                    .orderBy(ChapterIntegrityAuditSessionTable.id to SortOrder.ASC)
                    .forUpdate()
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction null

            val session = ChapterIntegrityAuditSessionTable.toDataClass(sessionRow)

            val itemRow =
                ChapterIntegrityAuditItemTable
                    .selectAll()
                    .where { claimableItemCondition(session.id, now) }
                    // most overdue first, and an unset due time is due immediately: the pick agrees with
                    // the due time the worker sleeps until, which [nextDueAt] derives from the same order
                    .orderBy(
                        ChapterIntegrityAuditItemTable.dueAt to SortOrder.ASC_NULLS_FIRST,
                        ChapterIntegrityAuditItemTable.id to SortOrder.ASC,
                    ).forUpdate()
                    .limit(1)
                    .firstOrNull()

            if (itemRow == null) {
                // Nothing left to claim right now: either the run finished or every remaining revision
                // is waiting out its own retry delay, in which case it stays RUNNING.
                reconcileSessionLocked(session.id, now)
                return@transaction null
            }

            val itemId = itemRow[ChapterIntegrityAuditItemTable.id].value
            ChapterIntegrityAuditItemTable.update({ ChapterIntegrityAuditItemTable.id eq itemId }) {
                it[state] = ChapterIntegrityAuditItemState.CHECKING.name
                it[attempts] = itemRow[ChapterIntegrityAuditItemTable.attempts] + 1
                it[startedAt] = now
                it[dueAt] = null
                it[updatedAt] = now
            }

            ChapterIntegrityAuditSessionTable.update({ ChapterIntegrityAuditSessionTable.id eq session.id }) {
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
     * Returns interrupted revisions to the queue.
     *
     * An interrupted attempt consumed one attempt without proving anything about the archived payload,
     * so it is normally requeued - but not immediately: the run's own retry delay is persisted as the
     * new due time, which is what stops a crash-looping process from re-checking the same revisions at
     * every startup. Once that attempt was the last one the run allows, the revision is recorded as a
     * failed check instead. Attempts are never reset, so repeated crashes stay visible in the audit
     * trail.
     */
    fun recoverInterruptedItems(now: Long = Instant.now().epochSecond) {
        transaction {
            val activeSessions =
                ChapterIntegrityAuditSessionTable
                    .selectAll()
                    .where { ChapterIntegrityAuditSessionTable.activeMarker eq ACTIVE_MARKER }
                    .map { ChapterIntegrityAuditSessionTable.toDataClass(it) }

            val activeSessionIds = activeSessions.map { it.id }

            activeSessions.forEach { session ->
                ChapterIntegrityAuditItemTable.update({
                    (ChapterIntegrityAuditItemTable.session eq session.id) and
                        (ChapterIntegrityAuditItemTable.state eq ChapterIntegrityAuditItemState.CHECKING.name) and
                        (ChapterIntegrityAuditItemTable.attempts greaterEq session.maxAttempts)
                }) {
                    it[state] = ChapterIntegrityAuditItemState.FAILED.name
                    it[lastError] = INTERRUPTED_ITEM_REASON
                    it[dueAt] = null
                    it[startedAt] = null
                    it[finishedAt] = now
                    it[updatedAt] = now
                }

                ChapterIntegrityAuditItemTable.update({
                    (ChapterIntegrityAuditItemTable.session eq session.id) and
                        (ChapterIntegrityAuditItemTable.state eq ChapterIntegrityAuditItemState.CHECKING.name) and
                        (ChapterIntegrityAuditItemTable.attempts less session.maxAttempts)
                }) {
                    it[state] = ChapterIntegrityAuditItemState.RETRY_WAIT.name
                    it[dueAt] = saturatingEpochAdd(now, session.retrySeconds)
                    it[startedAt] = null
                    it[updatedAt] = now
                }
            }

            // A CHECKING revision of a cancelled or finished run can never be claimed again, so it must
            // not keep looking like work in progress.
            ChapterIntegrityAuditItemTable.update({
                (ChapterIntegrityAuditItemTable.state eq ChapterIntegrityAuditItemState.CHECKING.name) and
                    (ChapterIntegrityAuditItemTable.session notInList activeSessionIds)
            }) {
                it[state] = ChapterIntegrityAuditItemState.SKIPPED.name
                it[finishedAt] = now
                it[updatedAt] = now
            }

            activeSessionIds.forEach { reconcileSessionLocked(it, now) }
        }
    }

    /**
     * Earliest instant anything can still be checked, or null when no run is running.
     *
     * A running session that is only waiting out its own delay or a retry delay still counts, which is
     * what keeps the idle wait bounded instead of sleeping until an external wake up.
     */
    fun nextDueAt(now: Long = Instant.now().epochSecond): Long? =
        transaction {
            ChapterIntegrityAuditSessionTable
                .selectAll()
                .where { ChapterIntegrityAuditSessionTable.state eq ChapterIntegrityAuditSessionState.RUNNING.name }
                .orderBy(ChapterIntegrityAuditSessionTable.id to SortOrder.ASC)
                .map { ChapterIntegrityAuditSessionTable.toDataClass(it) }
                .mapNotNull { session ->
                    // The earliest *claimable* revision decides, not the oldest row: taking the oldest row
                    // would let a revision waiting out a retry hide a later one that is already due, and
                    // the worker would sleep through work it could have done right now.
                    val earliestClaimable =
                        ChapterIntegrityAuditItemTable
                            .selectAll()
                            .where {
                                (ChapterIntegrityAuditItemTable.session eq session.id) and
                                    (ChapterIntegrityAuditItemTable.state inList claimableStates.map { it.name })
                            }.orderBy(
                                ChapterIntegrityAuditItemTable.dueAt to SortOrder.ASC_NULLS_FIRST,
                                ChapterIntegrityAuditItemTable.id to SortOrder.ASC,
                            ).limit(1)
                            .firstOrNull()
                            // Nothing claimable at all: the run can only advance once a revision reports
                            // back, so it contributes no due time of its own.
                            ?: return@mapNotNull null

                    val itemDueAt = earliestClaimable[ChapterIntegrityAuditItemTable.dueAt] ?: now
                    // the persisted inter-item delay stays the global remote rate limit on top of that
                    session.nextItemAt?.let { maxOf(itemDueAt, it) } ?: itemDueAt
                }.minOrNull()
        }

    /**
     * Records a completed check.
     *
     * The item's terminal state and the revision's integrity dimension are written in one transaction,
     * so a report can never say a revision was verified while the revision still says it never was. A
     * finding is only ever recorded together with the item state that found it.
     */
    fun completeItem(
        claim: Claim,
        itemState: ChapterIntegrityAuditItemState,
        integrityState: ChapterRevisionIntegrityState,
        error: String?,
        now: Long = Instant.now().epochSecond,
    ) {
        require(itemState == ChapterIntegrityAuditItemState.VERIFIED || itemState.isFinding) {
            "a completed audit item has to be verified or a finding, not $itemState"
        }

        transaction {
            val updated =
                ChapterIntegrityAuditItemTable.update({
                    (ChapterIntegrityAuditItemTable.id eq claim.item.id) and
                        (ChapterIntegrityAuditItemTable.state eq ChapterIntegrityAuditItemState.CHECKING.name)
                }) {
                    it[state] = itemState.name
                    it[lastError] = error?.take(MAX_ERROR_LENGTH)
                    it[dueAt] = null
                    it[finishedAt] = now
                    it[updatedAt] = now
                } > 0

            if (updated) {
                claim.item.revisionId?.let { revisionId ->
                    ChapterRevision.recordIntegrityOutcome(
                        revisionId = revisionId,
                        state = integrityState,
                        sessionId = claim.session.id,
                        error = error,
                        now = now,
                    )
                }
            }

            reconcileSessionLocked(claim.session.id, now)
        }
    }

    /**
     * Records a check that did not conclude anything.
     *
     * A run only gives up on a revision once the attempts snapshot of its session is exhausted; before
     * that it waits out a persisted retry delay, which also keeps one unreachable object from spinning.
     * Giving up records the revision's integrity as a *failed* check rather than as a finding: nothing
     * was proven about the archived payload, only about the check.
     */
    fun failItem(
        claim: Claim,
        message: String,
        now: Long = Instant.now().epochSecond,
    ) {
        val bounded = message.take(MAX_ERROR_LENGTH)

        transaction {
            val exhausted = claim.item.attempts >= claim.session.maxAttempts
            val nextState =
                if (exhausted) ChapterIntegrityAuditItemState.FAILED else ChapterIntegrityAuditItemState.RETRY_WAIT

            val updated =
                ChapterIntegrityAuditItemTable.update({
                    (ChapterIntegrityAuditItemTable.id eq claim.item.id) and
                        (ChapterIntegrityAuditItemTable.state eq ChapterIntegrityAuditItemState.CHECKING.name)
                }) {
                    it[state] = nextState.name
                    it[lastError] = bounded
                    it[dueAt] = if (exhausted) null else saturatingEpochAdd(now, claim.session.retrySeconds)
                    if (exhausted) it[finishedAt] = now
                    it[updatedAt] = now
                } > 0

            if (updated && exhausted) {
                claim.item.revisionId?.let { revisionId ->
                    ChapterRevision.recordIntegrityOutcome(
                        revisionId = revisionId,
                        state = ChapterRevisionIntegrityState.AUDIT_FAILED,
                        sessionId = claim.session.id,
                        error = bounded,
                        now = now,
                    )
                }
            }

            reconcileSessionLocked(claim.session.id, now)
        }
    }

    /** A revision that cannot be checked at all, for a durable reason, without changing its integrity. */
    fun markItemSkipped(
        claim: Claim,
        reason: String,
        now: Long = Instant.now().epochSecond,
    ) {
        transaction {
            ChapterIntegrityAuditItemTable.update({
                (ChapterIntegrityAuditItemTable.id eq claim.item.id) and
                    (ChapterIntegrityAuditItemTable.state eq ChapterIntegrityAuditItemState.CHECKING.name)
            }) {
                it[state] = ChapterIntegrityAuditItemState.SKIPPED.name
                it[lastError] = reason.take(MAX_ERROR_LENGTH)
                it[dueAt] = null
                it[finishedAt] = now
                it[updatedAt] = now
            }

            reconcileSessionLocked(claim.session.id, now)
        }
    }

    /**
     * Puts a claimed revision back into the queue because nothing could be checked at all.
     *
     * This is the shape a run takes when no remote verifier is configured: the claim is undone - so the
     * attempt is not consumed and the revision's integrity is untouched - and the item becomes due
     * again. The worker never claims at all while no verifier is configured, and it waits for a wake
     * instead of polling, so this can never become a loop.
     */
    fun returnItemToPending(
        claim: Claim,
        reason: String,
        now: Long = Instant.now().epochSecond,
    ) {
        transaction {
            ChapterIntegrityAuditItemTable.update({
                (ChapterIntegrityAuditItemTable.id eq claim.item.id) and
                    (ChapterIntegrityAuditItemTable.state eq ChapterIntegrityAuditItemState.CHECKING.name)
            }) {
                it[state] = ChapterIntegrityAuditItemState.PENDING.name
                // the attempt was consumed by the claim, so it is given back: nothing was checked
                it[attempts] = maxOf(0, claim.item.attempts - 1)
                it[lastError] = reason.take(MAX_ERROR_LENGTH)
                it[startedAt] = null
                it[dueAt] = null
                it[updatedAt] = now
            }

            reconcileSessionLocked(claim.session.id, now)
        }
    }

    // ------------------------------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------------------------------

    private fun runnableSessionCondition(now: Long): Op<Boolean> =
        (ChapterIntegrityAuditSessionTable.state eq ChapterIntegrityAuditSessionState.RUNNING.name) and
            (
                (ChapterIntegrityAuditSessionTable.nextItemAt.isNull()) or
                    (ChapterIntegrityAuditSessionTable.nextItemAt lessEq now)
            )

    private fun claimableItemCondition(
        sessionId: Int,
        now: Long,
    ): Op<Boolean> =
        (ChapterIntegrityAuditItemTable.session eq sessionId) and
            (ChapterIntegrityAuditItemTable.state inList claimableStates.map { it.name }) and
            (
                (ChapterIntegrityAuditItemTable.dueAt.isNull()) or
                    (ChapterIntegrityAuditItemTable.dueAt lessEq now)
            )

    /** Must be called inside a transaction. */
    private fun activeSessionId(): Int? =
        ChapterIntegrityAuditSessionTable
            .selectAll()
            .where { ChapterIntegrityAuditSessionTable.activeMarker eq ACTIVE_MARKER }
            .firstOrNull()
            ?.get(ChapterIntegrityAuditSessionTable.id)
            ?.value

    /** Must be called inside a transaction. */
    private fun readSession(sessionId: Int): ChapterIntegrityAuditSessionDataClass? =
        ChapterIntegrityAuditSessionTable
            .selectAll()
            .where { ChapterIntegrityAuditSessionTable.id eq sessionId }
            .firstOrNull()
            ?.let { ChapterIntegrityAuditSessionTable.toDataClass(it) }

    /** Must be called inside a transaction. */
    private fun readItem(itemId: Int): ChapterIntegrityAuditItemDataClass? =
        ChapterIntegrityAuditItemTable
            .selectAll()
            .where { ChapterIntegrityAuditItemTable.id eq itemId }
            .firstOrNull()
            ?.let { ChapterIntegrityAuditItemTable.toDataClass(it) }

    /** Must be called inside a transaction. */
    private fun readSchedule(): ChapterIntegrityAuditScheduleDataClass? =
        ChapterIntegrityAuditScheduleTable
            .selectAll()
            .where { ChapterIntegrityAuditScheduleTable.id eq ChapterIntegrityAuditScheduleTable.SINGLETON_ID }
            .firstOrNull()
            ?.let { ChapterIntegrityAuditScheduleTable.toDataClass(it) }

    /**
     * Settles a run once nothing is left to claim.
     *
     * Only a session that is still running or paused is reconciled; a cancelled or already finished one
     * must not be resurrected by a straggling in-flight check completing.
     */
    private fun reconcileSessionLocked(
        sessionId: Int,
        now: Long,
    ) {
        val sessionRow =
            ChapterIntegrityAuditSessionTable
                .selectAll()
                .where { ChapterIntegrityAuditSessionTable.id eq sessionId }
                .forUpdate()
                .firstOrNull()
                ?: return
        if (!ChapterIntegrityAuditSessionState.valueOf(sessionRow[ChapterIntegrityAuditSessionTable.state]).isActive) {
            return
        }

        val unfinished =
            ChapterIntegrityAuditItemTable
                .selectAll()
                .where {
                    (ChapterIntegrityAuditItemTable.session eq sessionId) and
                        (ChapterIntegrityAuditItemTable.state inList unfinishedStates.map { it.name })
                }.count()
        if (unfinished > 0) {
            return
        }

        val errored =
            ChapterIntegrityAuditItemTable
                .selectAll()
                .where {
                    (ChapterIntegrityAuditItemTable.session eq sessionId) and
                        (ChapterIntegrityAuditItemTable.state inList errorStates.map { it.name })
                }.count()

        val terminal =
            if (errored > 0) {
                ChapterIntegrityAuditSessionState.COMPLETED_WITH_ERRORS
            } else {
                ChapterIntegrityAuditSessionState.COMPLETED
            }

        ChapterIntegrityAuditSessionTable.update({ ChapterIntegrityAuditSessionTable.id eq sessionId }) {
            it[state] = terminal.name
            it[activeMarker] = null
            it[finishedAt] = now
            it[updatedAt] = now
        }
    }

    /**
     * The revisions a run will check.
     *
     * The selection itself belongs to the revision store, which is the only place that knows which
     * archived revisions the archive still claims.
     */
    private fun auditTargets(
        mangaIds: List<Int>?,
        newestPerManga: Int?,
    ): List<ChapterIntegrityAuditCandidate> = ChapterRevision.integrityAuditCandidates(mangaIds, newestPerManga)

    /** The series a requested subset really names; an id that is not in the database is a client error. */
    private fun knownMangaIds(mangaIds: List<Int>): Set<Int> =
        transaction {
            MangaTable
                .selectAll()
                .where { MangaTable.id inList mangaIds }
                .map { it[MangaTable.id].value }
                .toSet()
        }

    private fun createSession(
        kind: ChapterIntegrityAuditKind,
        newestPerManga: Int?,
        items: List<ChapterIntegrityAuditCandidate>,
        now: Long,
    ): Pair<ChapterIntegrityAuditSessionDataClass, Int> =
        transaction {
            val sessionId = insertSession(kind, newestPerManga, now)
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
        kind: ChapterIntegrityAuditKind,
        newestPerManga: Int?,
        now: Long,
    ): Int =
        ChapterIntegrityAuditSessionTable
            .insertAndGetId {
                it[state] = ChapterIntegrityAuditSessionState.RUNNING.name
                it[activeMarker] = ACTIVE_MARKER
                it[ChapterIntegrityAuditSessionTable.kind] = kind.name
                it[ChapterIntegrityAuditSessionTable.newestPerManga] = newestPerManga
                it[itemDelaySeconds] = serverConfig.chapterIntegrityAuditItemDelaySeconds.value.toLong()
                it[retrySeconds] = serverConfig.chapterIntegrityAuditRetrySeconds.value.toLong()
                it[maxAttempts] = serverConfig.chapterIntegrityAuditMaxAttempts.value
                it[startedAt] = now
                it[updatedAt] = now
                it[nextItemAt] = now
            }.value

    /**
     * Writes the work list of a run.
     *
     * One small row per revision, carrying the exact artifact identity that revision was archived
     * under. Must be called inside a transaction.
     */
    private fun insertItems(
        sessionId: Int,
        candidates: List<ChapterIntegrityAuditCandidate>,
        now: Long,
    ) {
        val titles = mangaTitles(candidates.mapNotNull { it.mangaId }.distinct())

        candidates.chunked(ITEM_INSERT_BATCH).forEach { chunk ->
            ChapterIntegrityAuditItemTable.batchInsert(chunk) { candidate ->
                this[ChapterIntegrityAuditItemTable.session] = sessionId
                this[ChapterIntegrityAuditItemTable.revisionId] = candidate.revisionId
                this[ChapterIntegrityAuditItemTable.chapterKey] = candidate.chapterKey
                this[ChapterIntegrityAuditItemTable.candidateKey] = candidate.candidateKey
                this[ChapterIntegrityAuditItemTable.mangaId] = candidate.mangaId
                this[ChapterIntegrityAuditItemTable.chapterId] = candidate.chapterId
                this[ChapterIntegrityAuditItemTable.seriesTitle] = candidate.mangaId?.let { titles[it] }
                this[ChapterIntegrityAuditItemTable.chapterName] = candidate.chapterName
                this[ChapterIntegrityAuditItemTable.cbzPath] = candidate.artifact.relativeCbzPath
                this[ChapterIntegrityAuditItemTable.cbzSha256] = candidate.artifact.cbzSha256
                this[ChapterIntegrityAuditItemTable.cbzSize] = candidate.artifact.cbzSize
                this[ChapterIntegrityAuditItemTable.manifestPath] = candidate.artifact.relativeManifestPath
                this[ChapterIntegrityAuditItemTable.manifestSha256] = candidate.artifact.manifestSha256
                this[ChapterIntegrityAuditItemTable.manifestSize] = candidate.artifact.manifestSize
                this[ChapterIntegrityAuditItemTable.state] = ChapterIntegrityAuditItemState.PENDING.name
                this[ChapterIntegrityAuditItemTable.attempts] = 0
                this[ChapterIntegrityAuditItemTable.updatedAt] = now
            }
        }
    }

    /** Titles of the given series, in one query, or nothing for a series that no longer exists. */
    private fun mangaTitles(mangaIds: List<Int>): Map<Int, String> {
        if (mangaIds.isEmpty()) {
            return emptyMap()
        }

        return transaction {
            MangaTable
                .selectAll()
                .where { MangaTable.id inList mangaIds }
                .associate { it[MangaTable.id].value to it[MangaTable.title] }
        }
    }
}

/** The bounded diagnostic a revision records when an audit could not check it at all. */
internal const val INTEGRITY_AUDIT_BLOCKED_REASON = "no remote verifier is configured, so nothing was checked"
