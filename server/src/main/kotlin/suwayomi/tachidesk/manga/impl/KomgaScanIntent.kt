package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.util.lang.isDuplicateKeyViolation
import suwayomi.tachidesk.manga.model.dataclass.KomgaScanIntentDataClass
import suwayomi.tachidesk.manga.model.dataclass.KomgaScanState
import suwayomi.tachidesk.manga.model.table.KomgaScanIntentTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import java.time.Instant

/**
 * Durable, coalesced Komga rescan intent.
 *
 * A published revision only means "the configured Komga library is stale", so every request updates
 * the same row instead of appending work: N publications produce one scan. The intent deliberately
 * stores no URL, library id or API key - it refers to "the configured library" - so changing the
 * Komga configuration never invalidates it and the row can never leak credentials.
 *
 * Every state transition is a guarded, transactional update, so a second actor (a stale worker, a
 * restart recovery) can never resurrect or overwrite a newer decision.
 */
object KomgaScanIntent {
    /** The intent is a singleton row: one configured Komga library, one coalesced rescan. */
    private const val SINGLETON_ID = 1

    private const val MAX_ERROR_LENGTH = 4096

    /** Bounded so a persistent duplicate-key error can never spin the caller forever. */
    private const val MAX_CREATE_ATTEMPTS = 5

    /** The stored intent, or null when no scan was ever requested. */
    fun current(): KomgaScanIntentDataClass? = transaction { selectRow() }

    /**
     * Records that the configured Komga library is stale.
     *
     * The debounce window is restarted by every request, so a burst of chapter publications still
     * results in a single scan after the library went quiet. The generation is always bumped: a scan
     * that is already running belongs to an older request by definition, and its completion has to be
     * recognised as stale instead of clearing the newer request.
     */
    fun requestScan(
        now: Long = Instant.now().epochSecond,
        debounceSeconds: Long,
    ): KomgaScanIntentDataClass {
        var attempt = 1
        while (true) {
            try {
                return transaction { recordRequest(now, debounceSeconds) }
            } catch (e: ExposedSQLException) {
                // Two creators can both find the singleton absent and both try to seed it, so the
                // loser sees a duplicate-key violation. That lost race is expected, not a failure:
                // the whole request is retried with a fresh transaction, which now finds the row and
                // updates it. Retrying on the shared duplicate-key state is dialect independent
                // (H2 and PostgreSQL both report 23505), which is what a dialect specific upsert could
                // not give us while still counting every request exactly once.
                if (attempt >= MAX_CREATE_ATTEMPTS || !e.isDuplicateKeyViolation()) {
                    throw e
                }
                attempt++
            }
        }
    }

    /**
     * Records one request on the singleton, creating it first when this is the very first request.
     *
     * A request never cancels a scan that is already in flight: the state stays [KomgaScanState.RUNNING]
     * and its claim is preserved, while the generation is bumped so the running outcome is fenced and
     * requeues the newer request instead of completing it. Only a non-running intent returns to
     * [KomgaScanState.PENDING], which is what makes a second concurrent claim impossible.
     */
    private fun JdbcTransaction.recordRequest(
        now: Long,
        debounceSeconds: Long,
    ): KomgaScanIntentDataClass {
        val notBefore = saturatingEpochAdd(now, debounceSeconds.coerceAtLeast(0))
        val existing = selectRowForUpdate()
        if (existing == null) {
            KomgaScanIntentTable.insert {
                it[id] = SINGLETON_ID
                it[state] = KomgaScanState.PENDING.name
                it[generation] = 1
                it[KomgaScanIntentTable.runningGeneration] = null
                it[requestedAt] = now
                it[notBeforeAt] = notBefore
                it[attempts] = 0
                it[lastAttemptAt] = null
                it[lastCompletedAt] = null
                it[lastError] = null
            }
            return selectRow() ?: error("the Komga scan intent disappeared while it was being recorded")
        }

        val running = existing.runningGeneration != null
        KomgaScanIntentTable.update({ KomgaScanIntentTable.id eq SINGLETON_ID }) {
            it[generation] = existing.generation + 1
            it[requestedAt] = now
            it[notBeforeAt] = notBefore
            if (!running) {
                it[state] = KomgaScanState.PENDING.name
            }
            it[lastError] = null
        }

        return selectRow() ?: error("the Komga scan intent disappeared while it was being recorded")
    }

    /**
     * Records an explicit request for a scan that runs as soon as possible.
     *
     * Only an operator asks for this, so it is not debounced: the point of the request is to scan
     * now, not to wait out a quiet period.
     */
    fun requestImmediateScan(now: Long = Instant.now().epochSecond): KomgaScanIntentDataClass = requestScan(now = now, debounceSeconds = 0)

    /**
     * Requeues a failed intent, which is what an explicit retry or a configuration change does.
     *
     * Returns false when there is nothing to retry: a pending, running or completed intent is already
     * where it should be, and re-driving it would only cause a duplicate scan.
     */
    fun retryFailed(now: Long = Instant.now().epochSecond): Boolean =
        transaction {
            KomgaScanIntentTable.update({
                (KomgaScanIntentTable.id eq SINGLETON_ID) and
                    (KomgaScanIntentTable.state eq KomgaScanState.FAILED.name)
            }) {
                it[state] = KomgaScanState.PENDING.name
                it[notBeforeAt] = now
                it[lastError] = null
            } > 0
        }

    /**
     * Atomically claims the pending intent once its debounce window elapsed.
     *
     * The claim copies the current generation into the row, which is the fencing token of this
     * attempt: every later outcome is applied only while that token still matches.
     */
    fun claimDueScan(now: Long = Instant.now().epochSecond): KomgaScanIntentDataClass? =
        transaction {
            val candidate =
                KomgaScanIntentTable
                    .selectAll()
                    .where {
                        (KomgaScanIntentTable.state eq KomgaScanState.PENDING.name) and
                            (
                                (KomgaScanIntentTable.notBeforeAt.isNull()) or
                                    (KomgaScanIntentTable.notBeforeAt lessEq now)
                            )
                    }.forUpdate()
                    .firstOrNull()
                    ?: return@transaction null

            KomgaScanIntentTable.update({ KomgaScanIntentTable.id eq SINGLETON_ID }) {
                it[state] = KomgaScanState.RUNNING.name
                it[KomgaScanIntentTable.runningGeneration] = candidate[KomgaScanIntentTable.generation]
                it[attempts] = candidate[KomgaScanIntentTable.attempts] + 1
                it[lastAttemptAt] = now
                it[notBeforeAt] = null
                it[lastError] = null
            }

            selectRow()
        }

    /**
     * Records a successful scan.
     *
     * Returns true when the intent is done. A request that arrived while the scan ran is not done: the
     * library changed again after the scan started, so the intent returns to PENDING instead of being
     * marked complete by an outcome that no longer describes it.
     */
    fun completeScan(
        runningGeneration: Long,
        now: Long = Instant.now().epochSecond,
        debounceSeconds: Long,
    ): Boolean =
        transaction {
            val row = selectRowForUpdate() ?: return@transaction false
            if (row.runningGeneration != runningGeneration) {
                // the claim was already resolved by a recovery or a newer attempt
                return@transaction false
            }

            if (row.generation == runningGeneration) {
                KomgaScanIntentTable.update({ KomgaScanIntentTable.id eq SINGLETON_ID }) {
                    it[state] = KomgaScanState.COMPLETE.name
                    it[KomgaScanIntentTable.runningGeneration] = null
                    it[notBeforeAt] = null
                    it[lastCompletedAt] = now
                    it[lastError] = null
                }
                true
            } else {
                requeue(now = now, debounceSeconds = debounceSeconds)
                false
            }
        }

    /**
     * Records a failed scan attempt.
     *
     * A retryable failure stays PENDING with a persisted retry time instead of becoming FAILED, so the
     * worker retries on its own without ever hot-looping. A hard failure (an authentication or
     * endpoint error) becomes FAILED and is only re-driven by an explicit retry or a configuration
     * change. An attempt whose request was superseded only returns the intent to PENDING: the newer
     * request has not been attempted yet, so its outcome is still unknown.
     */
    fun failScan(
        runningGeneration: Long,
        error: String,
        retryable: Boolean,
        retryAt: Long,
        now: Long = Instant.now().epochSecond,
        debounceSeconds: Long,
    ): Boolean =
        transaction {
            val row = selectRowForUpdate() ?: return@transaction false
            if (row.runningGeneration != runningGeneration) {
                return@transaction false
            }

            val superseded = row.generation != runningGeneration
            if (superseded) {
                requeue(now = now, debounceSeconds = debounceSeconds, error = error)
            } else if (retryable) {
                KomgaScanIntentTable.update({ KomgaScanIntentTable.id eq SINGLETON_ID }) {
                    it[state] = KomgaScanState.PENDING.name
                    it[KomgaScanIntentTable.runningGeneration] = null
                    it[notBeforeAt] = retryAt
                    it[lastError] = error.take(MAX_ERROR_LENGTH)
                }
            } else {
                KomgaScanIntentTable.update({ KomgaScanIntentTable.id eq SINGLETON_ID }) {
                    it[state] = KomgaScanState.FAILED.name
                    it[KomgaScanIntentTable.runningGeneration] = null
                    it[notBeforeAt] = null
                    it[lastError] = error.take(MAX_ERROR_LENGTH)
                }
            }
            true
        }

    /**
     * Returns a scan a shutdown left claimed to PENDING.
     *
     * The claim is not rebuilt and the attempt is not refunded: the request is still the same one, so
     * the generation is preserved and the attempt count keeps counting. The debounce is applied again
     * so a crash-looping process cannot hammer Komga at every startup.
     */
    fun recoverInterruptedScan(
        now: Long = Instant.now().epochSecond,
        debounceSeconds: Long,
    ): Int =
        transaction {
            KomgaScanIntentTable.update({ KomgaScanIntentTable.runningGeneration.isNotNull() }) {
                it[state] = KomgaScanState.PENDING.name
                it[KomgaScanIntentTable.runningGeneration] = null
                it[notBeforeAt] = saturatingEpochAdd(now, debounceSeconds.coerceAtLeast(0))
                it[lastError] = "the server stopped while the scan was running"
            }
        }

    /**
     * Returns a claim whose attempt will never finish - a cancelled scan - to PENDING.
     *
     * Without this a cancelled attempt would leave the intent claimed forever, because no worker
     * would still be running to resolve it. The scan is due immediately: the shutdown interrupted
     * real work that is still outstanding.
     */
    fun releaseClaim(
        runningGeneration: Long,
        now: Long = Instant.now().epochSecond,
    ): Boolean =
        transaction {
            val row = selectRowForUpdate() ?: return@transaction false
            if (row.runningGeneration != runningGeneration) {
                return@transaction false
            }

            KomgaScanIntentTable.update({ KomgaScanIntentTable.id eq SINGLETON_ID }) {
                it[state] = KomgaScanState.PENDING.name
                it[KomgaScanIntentTable.runningGeneration] = null
                it[notBeforeAt] = now
            }
            true
        }

    /** Earliest instant a pending scan is due, or null when nothing is scheduled. */
    fun nextDueAt(now: Long = Instant.now().epochSecond): Long? =
        transaction {
            val dueNow =
                KomgaScanIntentTable
                    .selectAll()
                    .where {
                        (KomgaScanIntentTable.state eq KomgaScanState.PENDING.name) and
                            (
                                (KomgaScanIntentTable.notBeforeAt.isNull()) or
                                    (KomgaScanIntentTable.notBeforeAt lessEq now)
                            )
                    }.limit(1)
                    .firstOrNull()
            if (dueNow != null) {
                return@transaction now
            }

            KomgaScanIntentTable
                .selectAll()
                .where {
                    (KomgaScanIntentTable.state eq KomgaScanState.PENDING.name) and
                        (KomgaScanIntentTable.notBeforeAt.isNotNull())
                }.orderBy(KomgaScanIntentTable.notBeforeAt to SortOrder.ASC)
                .limit(1)
                .firstOrNull()
                ?.get(KomgaScanIntentTable.notBeforeAt)
        }

    private fun JdbcTransaction.selectRow(): KomgaScanIntentDataClass? =
        KomgaScanIntentTable
            .selectAll()
            .where { KomgaScanIntentTable.id eq SINGLETON_ID }
            .firstOrNull()
            ?.let { KomgaScanIntentTable.toDataClass(it) }

    private fun JdbcTransaction.selectRowForUpdate(): KomgaScanIntentDataClass? =
        KomgaScanIntentTable
            .selectAll()
            .where { KomgaScanIntentTable.id eq SINGLETON_ID }
            .forUpdate()
            .firstOrNull()
            ?.let { KomgaScanIntentTable.toDataClass(it) }

    /** Returns a claimed intent to PENDING, restarting its debounce window, and clears the claim. */
    private fun JdbcTransaction.requeue(
        now: Long,
        debounceSeconds: Long,
        error: String? = null,
    ) {
        KomgaScanIntentTable.update({ KomgaScanIntentTable.id eq SINGLETON_ID }) {
            it[state] = KomgaScanState.PENDING.name
            it[KomgaScanIntentTable.runningGeneration] = null
            it[notBeforeAt] = saturatingEpochAdd(now, debounceSeconds.coerceAtLeast(0))
            it[lastError] = error?.take(MAX_ERROR_LENGTH)
        }
    }
}
