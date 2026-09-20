package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

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
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.util.lang.isDuplicateKeyViolation
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapCategoryPolicies
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapCategoryPolicy
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapItemDataClass
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapItemState
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapProgress
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapSessionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapState
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapUnresolvedSource
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ArchiveBootstrapItemTable
import suwayomi.tachidesk.manga.model.table.ArchiveBootstrapSessionTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.encodeArchiveBootstrapCategoryIds
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.serverConfig
import java.time.Instant

/**
 * Durable archive bootstrap of an already imported library.
 *
 * This is the orchestration half of Phase 0: it does not import a backup - the existing proto backup
 * restore does that - and it does not fetch chapter images. It records *what* the backfill has to
 * visit, in what order, with which acquisition policy, and one series at a time it applies the
 * policy, refreshes the series through the normal source update path and records the resulting
 * initial archive candidates. Actual acquisition stays in [ChapterRevisionAcquisitionExecutor].
 *
 * The run is deliberately slow by design: 3,435 series must not be refreshed in one burst, so a
 * session persists its own inter-item delay and every item persists its own retry time.
 */
object ArchiveBootstrap {
    /** the only value [ArchiveBootstrapSessionTable.activeMarker] ever holds */
    const val ACTIVE_MARKER = "ACTIVE"

    private const val MAX_ERROR_LENGTH = 4096
    private const val ITEM_INSERT_BATCH = 200

    /**
     * Persisted reason for a series a shutdown interrupted mid-attempt.
     *
     * Static on purpose: it is stored in a column that is exposed through authenticated GraphQL, so it
     * must never carry anything derived from a source response.
     */
    private const val INTERRUPTED_ITEM_REASON = "the server stopped while this series was being bootstrapped"

    private val logger = KotlinLogging.logger {}

    /** Item states the worker still has to pick up. */
    private val claimableStates =
        listOf(
            ArchiveBootstrapItemState.PENDING,
            ArchiveBootstrapItemState.RETRY_WAIT,
        )

    /** Item states that can still change without human interaction. */
    private val unfinishedStates = claimableStates + ArchiveBootstrapItemState.PROCESSING

    /** Item states that make a finished session report [ArchiveBootstrapState.COMPLETED_WITH_ERRORS]. */
    private val errorStates =
        listOf(
            ArchiveBootstrapItemState.FAILED,
            ArchiveBootstrapItemState.UNRESOLVED_SOURCE,
        )

    /** Reasons a state transition was refused. */
    enum class Conflict {
        NOT_FOUND,
        ANOTHER_SESSION_ACTIVE,
    }

    /** How a bootstrap was requested to start. */
    data class StartRequest(
        val defaultPolicy: MangaAcquisitionPolicy,
        /** ordered: the first override matching a series wins */
        val categoryPolicies: List<ArchiveBootstrapCategoryPolicy> = emptyList(),
        /** optional subset of series; null bootstraps the whole library */
        val mangaIds: List<Int>? = null,
        /**
         * Idempotency key of the caller, or null for a run requested directly.
         *
         * A restore sets it to its own id so that starting the same bootstrap twice is the same
         * bootstrap: the second call returns the run the first one already created instead of a
         * conflict with itself.
         */
        val originKey: String? = null,
    )

    sealed interface StartOutcome {
        data class Started(
            val session: ArchiveBootstrapSessionDataClass,
            val itemCount: Int,
        ) : StartOutcome

        data object NothingToBootstrap : StartOutcome

        data object ActiveSessionExists : StartOutcome
    }

    sealed interface RetryOutcome {
        data class Retried(
            val session: ArchiveBootstrapSessionDataClass,
            val itemCount: Int,
        ) : RetryOutcome

        data object NotFound : RetryOutcome

        data object AnotherSessionActive : RetryOutcome
    }

    /** One claimed series together with the run settings it has to be processed under. */
    data class Claim(
        val item: ArchiveBootstrapItemDataClass,
        val session: ArchiveBootstrapSessionDataClass,
    )

    /** A snapshot of one series taken when a session starts. */
    private data class Target(
        val mangaId: Int,
        val url: String,
        val title: String,
        val sourceId: Long,
    )

    fun start(
        request: StartRequest,
        now: Long = Instant.now().epochSecond,
    ): StartOutcome {
        // An origin is resolved before anything else and regardless of what is active: a run this very
        // caller already created is its own run, not a conflict, so a caller that was interrupted
        // between starting a bootstrap and recording that it did can resume by asking again.
        request.originKey?.let { origin ->
            existingSessionForOrigin(origin)?.let { return StartOutcome.Started(it, itemCountOf(it.id)) }
        }

        if (getActiveSession() != null) {
            return StartOutcome.ActiveSessionExists
        }

        val targets = loadTargets(request.mangaIds)
        if (targets.isEmpty()) {
            return StartOutcome.NothingToBootstrap
        }

        val categoryIdsByManga = loadCategoryIds(targets.map { it.mangaId })

        return try {
            val (session, itemCount) = createSession(request, targets, categoryIdsByManga, now)
            logger.info { "archive bootstrap ${session.id} started with $itemCount series" }
            ArchiveBootstrapExecutor.notifyWorkAvailable()
            StartOutcome.Started(session, itemCount)
        } catch (e: Exception) {
            // A unique index is the real guard against two concurrent starts. The duplicate is either
            // the active marker - somebody else's run won - or this request's own origin, which a
            // racing identical start inserted first. Only the second one is this caller's own run.
            // Anything else - a database error, a serialization failure - must propagate instead of
            // being misreported as a conflict the caller can do nothing about.
            if (!e.isDuplicateKeyViolation()) {
                throw e
            }

            request.originKey?.let { origin ->
                existingSessionForOrigin(origin)?.let { return StartOutcome.Started(it, itemCountOf(it.id)) }
            }

            logger.info { "archive bootstrap start rejected: another session is active" }
            StartOutcome.ActiveSessionExists
        }
    }

    /** The run a given caller already started, if it exists; null for an unknown origin. */
    private fun existingSessionForOrigin(originKey: String): ArchiveBootstrapSessionDataClass? =
        transaction {
            ArchiveBootstrapSessionTable
                .selectAll()
                .where { ArchiveBootstrapSessionTable.originKey eq originKey }
                .firstOrNull()
                ?.let { ArchiveBootstrapSessionTable.toDataClass(it) }
        }

    /**
     * How many series a run already records.
     *
     * An idempotent start has to report the real size of the run it is returning, and the items are the
     * only place that size lives.
     */
    private fun itemCountOf(sessionId: Int): Int =
        transaction {
            ArchiveBootstrapItemTable
                .selectAll()
                .where { ArchiveBootstrapItemTable.session eq sessionId }
                .count()
                .toInt()
        }

    /** Stops claiming series. One already being processed is allowed to finish. */
    fun pause(
        sessionId: Int,
        now: Long = Instant.now().epochSecond,
    ): ArchiveBootstrapSessionDataClass? =
        transaction {
            val session =
                ArchiveBootstrapSessionTable
                    .selectAll()
                    .where { ArchiveBootstrapSessionTable.id eq sessionId }
                    .forUpdate()
                    .firstOrNull()
                    ?: return@transaction null
            if (ArchiveBootstrapState.valueOf(session[ArchiveBootstrapSessionTable.state]) != ArchiveBootstrapState.RUNNING) {
                return@transaction null
            }

            ArchiveBootstrapSessionTable.update({ ArchiveBootstrapSessionTable.id eq sessionId }) {
                it[state] = ArchiveBootstrapState.PAUSED.name
                it[pausedAt] = now
                it[updatedAt] = now
            }

            readSession(sessionId)
        }

    fun resume(
        sessionId: Int,
        now: Long = Instant.now().epochSecond,
    ): ArchiveBootstrapSessionDataClass? =
        transaction {
            val session =
                ArchiveBootstrapSessionTable
                    .selectAll()
                    .where { ArchiveBootstrapSessionTable.id eq sessionId }
                    .forUpdate()
                    .firstOrNull()
                    ?: return@transaction null
            if (ArchiveBootstrapState.valueOf(session[ArchiveBootstrapSessionTable.state]) != ArchiveBootstrapState.PAUSED) {
                return@transaction null
            }

            ArchiveBootstrapSessionTable.update({ ArchiveBootstrapSessionTable.id eq sessionId }) {
                it[state] = ArchiveBootstrapState.RUNNING.name
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
     * Every series that is not finished yet is cancelled, including one the worker already claimed: a
     * cancelled run must not leave a series looking like work in progress. A worker that is still
     * processing such a series is fenced out by its own PROCESSING condition, so it can neither record
     * a late outcome nor resurrect the run. Nothing claims a later series either, because every claim
     * re-checks that the session is still RUNNING.
     */
    fun cancel(
        sessionId: Int,
        now: Long = Instant.now().epochSecond,
    ): ArchiveBootstrapSessionDataClass? =
        transaction {
            val session =
                ArchiveBootstrapSessionTable
                    .selectAll()
                    .where { ArchiveBootstrapSessionTable.id eq sessionId }
                    .forUpdate()
                    .firstOrNull()
                    ?: return@transaction null
            if (!ArchiveBootstrapState.valueOf(session[ArchiveBootstrapSessionTable.state]).isActive) {
                return@transaction null
            }

            cancelUnfinishedItems(sessionId, now)

            ArchiveBootstrapSessionTable.update({ ArchiveBootstrapSessionTable.id eq sessionId }) {
                it[state] = ArchiveBootstrapState.CANCELLED.name
                it[activeMarker] = null
                it[cancelledAt] = now
                it[finishedAt] = now
                it[updatedAt] = now
            }

            readSession(sessionId)
        }

    /**
     * Returns failed and source-unresolved series to the queue.
     *
     * A retry reopens a finished run, so it is refused while another session is still active.
     */
    fun retry(
        sessionId: Int,
        itemIds: List<Int>? = null,
        now: Long = Instant.now().epochSecond,
    ): RetryOutcome =
        transaction {
            val session =
                ArchiveBootstrapSessionTable
                    .selectAll()
                    .where { ArchiveBootstrapSessionTable.id eq sessionId }
                    .forUpdate()
                    .firstOrNull()
                    ?: return@transaction RetryOutcome.NotFound

            if (!ArchiveBootstrapState.valueOf(session[ArchiveBootstrapSessionTable.state]).isActive) {
                val activeId = activeSessionId()
                if (activeId != null && activeId != sessionId) {
                    return@transaction RetryOutcome.AnotherSessionActive
                }
            }

            val ids =
                ArchiveBootstrapItemTable
                    .selectAll()
                    .where { retryableItemCondition(sessionId, itemIds) }
                    .orderBy(ArchiveBootstrapItemTable.id to SortOrder.ASC)
                    .forUpdate()
                    .map { it[ArchiveBootstrapItemTable.id].value }

            val reopened = ids.isNotEmpty()
            if (reopened) {
                ArchiveBootstrapItemTable.update({ ArchiveBootstrapItemTable.id inList ids }) {
                    it[state] = ArchiveBootstrapItemState.PENDING.name
                    it[attempts] = 0
                    it[dueAt] = null
                    it[lastError] = null
                    it[startedAt] = null
                    it[finishedAt] = null
                    it[updatedAt] = now
                }

                ArchiveBootstrapSessionTable.update({ ArchiveBootstrapSessionTable.id eq sessionId }) {
                    it[state] = ArchiveBootstrapState.RUNNING.name
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
                ArchiveBootstrapExecutor.notifyWorkAvailable()
            }
        }

    // ------------------------------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------------------------------

    fun getSession(sessionId: Int): ArchiveBootstrapSessionDataClass? = transaction { readSession(sessionId) }

    /** The session that is still running or paused, of which there can be at most one. */
    fun getActiveSession(): ArchiveBootstrapSessionDataClass? =
        transaction {
            ArchiveBootstrapSessionTable
                .selectAll()
                .where { ArchiveBootstrapSessionTable.activeMarker eq ACTIVE_MARKER }
                .firstOrNull()
                ?.let { ArchiveBootstrapSessionTable.toDataClass(it) }
        }

    fun getLatestSession(): ArchiveBootstrapSessionDataClass? =
        transaction {
            ArchiveBootstrapSessionTable
                .selectAll()
                .orderBy(ArchiveBootstrapSessionTable.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.let { ArchiveBootstrapSessionTable.toDataClass(it) }
        }

    /** Condition for the paged session listing, or null to list every session. */
    fun sessionCondition(state: ArchiveBootstrapState? = null): Op<Boolean>? = state?.let { ArchiveBootstrapSessionTable.state eq it.name }

    /** Condition for the paged item listing of one session. */
    fun itemCondition(
        sessionId: Int,
        state: ArchiveBootstrapItemState? = null,
        mangaId: Int? = null,
    ): Op<Boolean> =
        listOfNotNull(
            ArchiveBootstrapItemTable.session eq sessionId,
            state?.let { ArchiveBootstrapItemTable.state eq it.name },
            mangaId?.let { ArchiveBootstrapItemTable.mangaId eq it },
        ).reduce { acc, op -> acc and op }

    /** Counted from the items themselves so progress can never drift from the stored rows. */
    fun progress(sessionId: Int): ArchiveBootstrapProgress =
        transaction {
            val counts =
                ArchiveBootstrapItemTable
                    .select(ArchiveBootstrapItemTable.state, ArchiveBootstrapItemTable.id.count())
                    .where { ArchiveBootstrapItemTable.session eq sessionId }
                    .groupBy(ArchiveBootstrapItemTable.state)
                    .associate { it[ArchiveBootstrapItemTable.state] to it[ArchiveBootstrapItemTable.id.count()].toInt() }

            fun countOf(state: ArchiveBootstrapItemState): Int = counts[state.name] ?: 0

            ArchiveBootstrapProgress(
                total = counts.values.sum(),
                pending = countOf(ArchiveBootstrapItemState.PENDING),
                processing = countOf(ArchiveBootstrapItemState.PROCESSING),
                retryWait = countOf(ArchiveBootstrapItemState.RETRY_WAIT),
                complete = countOf(ArchiveBootstrapItemState.COMPLETE),
                failed = countOf(ArchiveBootstrapItemState.FAILED),
                unresolvedSource = countOf(ArchiveBootstrapItemState.UNRESOLVED_SOURCE),
                skipped = countOf(ArchiveBootstrapItemState.SKIPPED),
                cancelled = countOf(ArchiveBootstrapItemState.CANCELLED),
            )
        }

    /**
     * Series whose source is missing, grouped per source.
     *
     * A 3,435 series backfill can hit dozens of uninstalled extensions; reporting them per source is
     * what makes the run actionable instead of a wall of per-series failures.
     */
    fun unresolvedSources(
        sessionId: Int,
        sampleSize: Int = 5,
    ): List<ArchiveBootstrapUnresolvedSource> =
        transaction {
            ArchiveBootstrapItemTable
                .selectAll()
                .where {
                    (ArchiveBootstrapItemTable.session eq sessionId) and
                        (ArchiveBootstrapItemTable.state eq ArchiveBootstrapItemState.UNRESOLVED_SOURCE.name)
                }.orderBy(ArchiveBootstrapItemTable.id to SortOrder.ASC)
                .groupBy { it[ArchiveBootstrapItemTable.sourceId] }
                .map { (sourceId, rows) ->
                    ArchiveBootstrapUnresolvedSource(
                        sourceId = sourceId,
                        mangaCount = rows.size,
                        sampleTitles = rows.take(sampleSize).map { it[ArchiveBootstrapItemTable.title] },
                    )
                }
        }

    // ------------------------------------------------------------------------------------------
    // worker support
    // ------------------------------------------------------------------------------------------

    /**
     * Claims the oldest due series of the oldest runnable session, or null when nothing is due.
     *
     * The session row is locked first, so the persisted [ArchiveBootstrapSessionTable.nextItemAt]
     * stays the single global rate limit even with two server instances, and a claim can only happen
     * against a session that is still RUNNING.
     */
    fun claimNextDueItem(now: Long = Instant.now().epochSecond): Claim? =
        transaction {
            val sessionRow =
                ArchiveBootstrapSessionTable
                    .selectAll()
                    .where { runnableSessionCondition(now) }
                    .orderBy(ArchiveBootstrapSessionTable.id to SortOrder.ASC)
                    .forUpdate()
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction null

            val session = ArchiveBootstrapSessionTable.toDataClass(sessionRow)

            val itemRow =
                ArchiveBootstrapItemTable
                    .selectAll()
                    .where { claimableItemCondition(session.id, now) }
                    // most overdue first, and an unset due time is due immediately: the pick has to
                    // agree with the due time the worker sleeps until, which [nextDueAt] derives from
                    // the same ordering
                    .orderBy(
                        ArchiveBootstrapItemTable.dueAt to SortOrder.ASC_NULLS_FIRST,
                        ArchiveBootstrapItemTable.id to SortOrder.ASC,
                    ).forUpdate()
                    .limit(1)
                    .firstOrNull()

            if (itemRow == null) {
                // Nothing left to claim right now: either the session finished or every remaining
                // series is waiting out its own retry delay, in which case it stays RUNNING.
                reconcileSessionLocked(session.id, now)
                return@transaction null
            }

            val itemId = itemRow[ArchiveBootstrapItemTable.id].value
            ArchiveBootstrapItemTable.update({ ArchiveBootstrapItemTable.id eq itemId }) {
                it[state] = ArchiveBootstrapItemState.PROCESSING.name
                it[attempts] = itemRow[ArchiveBootstrapItemTable.attempts] + 1
                it[startedAt] = now
                it[dueAt] = null
                it[updatedAt] = now
            }

            ArchiveBootstrapSessionTable.update({ ArchiveBootstrapSessionTable.id eq session.id }) {
                it[nextItemAt] = saturatingEpochAdd(now, session.interItemDelaySeconds)
                it[lastItemAt] = now
                it[updatedAt] = now
            }

            Claim(
                item = readItem(itemId)!!,
                session = readSession(session.id)!!,
            )
        }

    /**
     * Returns interrupted series to the queue.
     *
     * An interrupted attempt consumed one attempt without proving anything about the series, so it is
     * normally requeued - but not immediately: the session's own retry delay is persisted as the new
     * due time, which is what stops a crash-looping process from re-refreshing the same series at
     * every startup. Once that attempt was the last one the session allows, the series is failed
     * instead, because a series that takes the server down three times must not be retried forever.
     *
     * Attempts are never reset, so repeated crashes stay visible in the audit trail.
     */
    fun recoverInterruptedItems(now: Long = Instant.now().epochSecond) {
        transaction {
            val activeSessions =
                ArchiveBootstrapSessionTable
                    .selectAll()
                    .where { ArchiveBootstrapSessionTable.activeMarker eq ACTIVE_MARKER }
                    .map { ArchiveBootstrapSessionTable.toDataClass(it) }

            val activeSessionIds = activeSessions.map { it.id }

            activeSessions.forEach { session ->
                // the claim already counted the attempt, so the budget is exhausted when the stored
                // count reached what this session allows
                ArchiveBootstrapItemTable.update({
                    (ArchiveBootstrapItemTable.session eq session.id) and
                        (ArchiveBootstrapItemTable.state eq ArchiveBootstrapItemState.PROCESSING.name) and
                        (ArchiveBootstrapItemTable.attempts greaterEq session.maxAttempts)
                }) {
                    it[state] = ArchiveBootstrapItemState.FAILED.name
                    it[lastError] = INTERRUPTED_ITEM_REASON
                    it[dueAt] = null
                    it[startedAt] = null
                    it[finishedAt] = now
                    it[updatedAt] = now
                }

                ArchiveBootstrapItemTable.update({
                    (ArchiveBootstrapItemTable.session eq session.id) and
                        (ArchiveBootstrapItemTable.state eq ArchiveBootstrapItemState.PROCESSING.name) and
                        (ArchiveBootstrapItemTable.attempts less session.maxAttempts)
                }) {
                    it[state] = ArchiveBootstrapItemState.RETRY_WAIT.name
                    it[dueAt] = saturatingEpochAdd(now, session.retrySeconds)
                    it[startedAt] = null
                    it[updatedAt] = now
                }
            }

            // A PROCESSING item of a cancelled or finished session can never be claimed again, so it
            // must not keep looking like work in progress.
            ArchiveBootstrapItemTable.update({
                (ArchiveBootstrapItemTable.state eq ArchiveBootstrapItemState.PROCESSING.name) and
                    (ArchiveBootstrapItemTable.session notInList activeSessionIds)
            }) {
                it[state] = ArchiveBootstrapItemState.CANCELLED.name
                it[finishedAt] = now
                it[updatedAt] = now
            }

            // A series that exhausted its attempts may have been the last unfinished one, so the run
            // has to be settled here instead of waiting for a claim that can no longer happen.
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
            ArchiveBootstrapSessionTable
                .selectAll()
                .where { ArchiveBootstrapSessionTable.state eq ArchiveBootstrapState.RUNNING.name }
                .orderBy(ArchiveBootstrapSessionTable.id to SortOrder.ASC)
                .map { ArchiveBootstrapSessionTable.toDataClass(it) }
                .mapNotNull { session ->
                    // The earliest *claimable* series decides, not the oldest row. Taking the oldest row
                    // would let a series waiting out a retry hide a later series that is already due,
                    // and the worker would then sleep through work it could have done right now.
                    // Ordering by due time picks that row in the database: an unset due time sorts
                    // first because it means "due now".
                    val earliestClaimable =
                        ArchiveBootstrapItemTable
                            .selectAll()
                            .where {
                                (ArchiveBootstrapItemTable.session eq session.id) and
                                    (ArchiveBootstrapItemTable.state inList claimableStates.map { it.name })
                            }.orderBy(
                                ArchiveBootstrapItemTable.dueAt to SortOrder.ASC_NULLS_FIRST,
                                ArchiveBootstrapItemTable.id to SortOrder.ASC,
                            ).limit(1)
                            .firstOrNull()
                            // Nothing claimable at all: the session can only advance once an in-flight
                            // series reports back, so it contributes no due time of its own and the
                            // worker waits for that wake instead of re-querying.
                            ?: return@mapNotNull null

                    val itemDueAt = earliestClaimable[ArchiveBootstrapItemTable.dueAt] ?: now
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
            ArchiveBootstrapItemTable.update({
                (ArchiveBootstrapItemTable.id eq claim.item.id) and
                    (ArchiveBootstrapItemTable.state eq ArchiveBootstrapItemState.PROCESSING.name)
            }) {
                it[state] = ArchiveBootstrapItemState.COMPLETE.name
                it[ArchiveBootstrapItemTable.candidateCount] = candidateCount
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
     * A series is only given up on once the attempts snapshot of its session is exhausted; before that
     * it waits out a persisted retry delay, which also keeps one broken source from spinning.
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

        logger.debug {
            "archive bootstrap series ${claim.item.mangaId} of run ${claim.session.id} failed: $message"
        }

        transaction {
            val exhausted = claim.item.attempts >= claim.session.maxAttempts
            val nextState = if (exhausted) ArchiveBootstrapItemState.FAILED else ArchiveBootstrapItemState.RETRY_WAIT

            ArchiveBootstrapItemTable.update({
                (ArchiveBootstrapItemTable.id eq claim.item.id) and
                    (ArchiveBootstrapItemTable.state eq ArchiveBootstrapItemState.PROCESSING.name)
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
     * A bounded, source-independent diagnostic for a series whose attempt failed.
     *
     * The exception *type* is kept because it is the only part that is safe to persist: it tells an
     * operator whether the source timed out, was unreachable or answered unexpectedly, while the
     * message - which is where URLs and credentials leak into logs - is deliberately dropped.
     */
    private fun failureReason(error: Throwable?): String =
        (
            error?.let { "the series could not be bootstrapped (${it.javaClass.simpleName})" }
                ?: "the series could not be bootstrapped"
        ).take(MAX_ERROR_LENGTH)

    /** A series whose source is not installed is isolated, never fatal to the run. */
    fun markItemUnresolvedSource(
        claim: Claim,
        now: Long = Instant.now().epochSecond,
    ) {
        finishItem(
            claim = claim,
            targetState = ArchiveBootstrapItemState.UNRESOLVED_SOURCE,
            error = "source ${claim.item.sourceId} is not available",
            now = now,
        )
    }

    /** A series that cannot be bootstrapped for a durable reason, such as leaving the library. */
    fun markItemSkipped(
        claim: Claim,
        reason: String,
        now: Long = Instant.now().epochSecond,
    ) {
        finishItem(claim, ArchiveBootstrapItemState.SKIPPED, reason, now)
    }

    private fun finishItem(
        claim: Claim,
        targetState: ArchiveBootstrapItemState,
        error: String,
        now: Long,
    ) {
        transaction {
            ArchiveBootstrapItemTable.update({
                (ArchiveBootstrapItemTable.id eq claim.item.id) and
                    (ArchiveBootstrapItemTable.state eq ArchiveBootstrapItemState.PROCESSING.name)
            }) {
                it[state] = targetState.name
                it[lastError] = error.take(MAX_ERROR_LENGTH)
                it[finishedAt] = now
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
        (ArchiveBootstrapSessionTable.state eq ArchiveBootstrapState.RUNNING.name) and
            (
                (ArchiveBootstrapSessionTable.nextItemAt.isNull()) or
                    (ArchiveBootstrapSessionTable.nextItemAt lessEq now)
            )

    private fun claimableItemCondition(
        sessionId: Int,
        now: Long,
    ): Op<Boolean> =
        (ArchiveBootstrapItemTable.session eq sessionId) and
            (ArchiveBootstrapItemTable.state inList claimableStates.map { it.name }) and
            (
                (ArchiveBootstrapItemTable.dueAt.isNull()) or
                    (ArchiveBootstrapItemTable.dueAt lessEq now)
            )

    private fun retryableItemCondition(
        sessionId: Int,
        itemIds: List<Int>?,
    ): Op<Boolean> =
        listOfNotNull(
            ArchiveBootstrapItemTable.session eq sessionId,
            ArchiveBootstrapItemTable.state inList errorStates.map { it.name },
            itemIds?.let { ArchiveBootstrapItemTable.id inList it },
        ).reduce { acc, op -> acc and op }

    /** Must be called inside a transaction. */
    private fun activeSessionId(): Int? =
        ArchiveBootstrapSessionTable
            .selectAll()
            .where { ArchiveBootstrapSessionTable.activeMarker eq ACTIVE_MARKER }
            .firstOrNull()
            ?.get(ArchiveBootstrapSessionTable.id)
            ?.value

    /** Must be called inside a transaction. */
    private fun readSession(sessionId: Int): ArchiveBootstrapSessionDataClass? =
        ArchiveBootstrapSessionTable
            .selectAll()
            .where { ArchiveBootstrapSessionTable.id eq sessionId }
            .firstOrNull()
            ?.let { ArchiveBootstrapSessionTable.toDataClass(it) }

    /** Must be called inside a transaction. */
    private fun readItem(itemId: Int): ArchiveBootstrapItemDataClass? =
        ArchiveBootstrapItemTable
            .selectAll()
            .where { ArchiveBootstrapItemTable.id eq itemId }
            .firstOrNull()
            ?.let { ArchiveBootstrapItemTable.toDataClass(it) }

    /**
     * Cancels every series that is not finished yet, including one the worker already claimed.
     *
     * A claimed series has to be recorded as cancelled here instead of being left in PROCESSING,
     * because the worker's own transitions are all fenced on PROCESSING: once the row is cancelled a
     * late completion can no longer overwrite this state or reopen the run.
     */
    private fun cancelUnfinishedItems(
        sessionId: Int,
        now: Long,
    ) {
        ArchiveBootstrapItemTable.update({
            (ArchiveBootstrapItemTable.session eq sessionId) and
                (ArchiveBootstrapItemTable.state inList unfinishedStates.map { it.name })
        }) {
            it[state] = ArchiveBootstrapItemState.CANCELLED.name
            it[dueAt] = null
            it[finishedAt] = now
            it[updatedAt] = now
        }
    }

    /**
     * Settles a session once nothing is left to claim.
     *
     * Only a session that is still running or paused is reconciled; a cancelled or already finished
     * one must not be resurrected by a straggling in-flight series completing.
     */
    private fun reconcileSessionLocked(
        sessionId: Int,
        now: Long,
    ) {
        val sessionRow =
            ArchiveBootstrapSessionTable
                .selectAll()
                .where { ArchiveBootstrapSessionTable.id eq sessionId }
                .forUpdate()
                .firstOrNull()
                ?: return
        if (!ArchiveBootstrapState.valueOf(sessionRow[ArchiveBootstrapSessionTable.state]).isActive) {
            return
        }

        val unfinished =
            ArchiveBootstrapItemTable
                .selectAll()
                .where {
                    (ArchiveBootstrapItemTable.session eq sessionId) and
                        (ArchiveBootstrapItemTable.state inList unfinishedStates.map { it.name })
                }.count()
        if (unfinished > 0) {
            return
        }

        val errored =
            ArchiveBootstrapItemTable
                .selectAll()
                .where {
                    (ArchiveBootstrapItemTable.session eq sessionId) and
                        (ArchiveBootstrapItemTable.state inList errorStates.map { it.name })
                }.count()

        val terminal =
            if (errored > 0) {
                ArchiveBootstrapState.COMPLETED_WITH_ERRORS
            } else {
                ArchiveBootstrapState.COMPLETED
            }

        ArchiveBootstrapSessionTable.update({ ArchiveBootstrapSessionTable.id eq sessionId }) {
            it[state] = terminal.name
            it[activeMarker] = null
            it[finishedAt] = now
            it[updatedAt] = now
        }
    }

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
                    )
                }
        }

    /** Batched category lookup, so starting a session never becomes a per-series query. */
    private fun loadCategoryIds(mangaIds: List<Int>): Map<Int, List<Int>> =
        mangaIds
            .chunked(1000)
            .flatMap { chunk ->
                CategoryManga
                    .getMangasCategories(chunk)
                    .map { (mangaId, categories) -> mangaId to categories.map { it.id } }
            }.toMap()

    private fun createSession(
        request: StartRequest,
        targets: List<Target>,
        categoryIdsByManga: Map<Int, List<Int>>,
        now: Long,
    ): Pair<ArchiveBootstrapSessionDataClass, Int> {
        val categoryPolicies = request.categoryPolicies.distinctBy { it.categoryId }

        return transaction {
            val sessionId =
                ArchiveBootstrapSessionTable
                    .insertAndGetId {
                        it[state] = ArchiveBootstrapState.RUNNING.name
                        it[activeMarker] = ACTIVE_MARKER
                        it[originKey] = request.originKey
                        it[defaultPolicy] = request.defaultPolicy.name
                        it[categoryPolicyOverrides] = ArchiveBootstrapCategoryPolicies.encode(categoryPolicies)
                        it[interItemDelaySeconds] = serverConfig.archiveBootstrapInterItemDelaySeconds.value.toLong()
                        it[retrySeconds] = serverConfig.archiveBootstrapRetrySeconds.value.toLong()
                        it[maxAttempts] = serverConfig.archiveBootstrapMaxAttempts.value
                        it[startedAt] = now
                        it[updatedAt] = now
                        it[nextItemAt] = now
                    }.value

            // Only the work list is written here: one small row per series with its snapshotted policy.
            // No chapter is reconciled and no revision candidate is created, so this never queues 46k
            // chapters at once - the worker hands each series to the refresh path on its own.
            targets.chunked(ITEM_INSERT_BATCH).forEach { chunk ->
                ArchiveBootstrapItemTable.batchInsert(chunk) { target ->
                    val policy =
                        categoryPolicies
                            .firstOrNull { it.categoryId in categoryIdsByManga[target.mangaId].orEmpty() }
                            ?.policy
                            ?: request.defaultPolicy

                    this[ArchiveBootstrapItemTable.session] = sessionId
                    this[ArchiveBootstrapItemTable.mangaId] = target.mangaId
                    this[ArchiveBootstrapItemTable.sourceId] = target.sourceId
                    this[ArchiveBootstrapItemTable.title] = target.title
                    this[ArchiveBootstrapItemTable.mangaUrl] = target.url
                    this[ArchiveBootstrapItemTable.categoryIds] =
                        encodeArchiveBootstrapCategoryIds(categoryIdsByManga[target.mangaId].orEmpty())
                    this[ArchiveBootstrapItemTable.policy] = policy.name
                    this[ArchiveBootstrapItemTable.state] = ArchiveBootstrapItemState.PENDING.name
                    this[ArchiveBootstrapItemTable.attempts] = 0
                    this[ArchiveBootstrapItemTable.updatedAt] = now
                }
            }

            readSession(sessionId)!! to targets.size
        }
    }
}
