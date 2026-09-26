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

/**
 * A single concurrency-1 background worker.
 *
 * Used by both the chapter revision acquisition and archive workers, which have identical
 * lifecycle, wake and failure-isolation requirements: one global worker per queue, a conflated wake
 * signal so a wake up can never be lost, and a failed item that must never end the loop.
 */
open class ChapterRevisionWorkerLoop(
    private val workerName: String,
    private val beforeFirstDrain: suspend () -> Unit = {},
    /**
     * How long to wait for a wake when the queue is empty, or null to wait indefinitely.
     *
     * A worker whose queue can only be refilled by an external event (a listener that failed, a
     * remote object that became visible) needs a bounded wait so the retry can happen without an
     * outside stimulus; a worker that is woken by every transition does not.
     */
    private val idleTimeoutMillis: () -> Long? = { null },
    private val drainOnce: suspend () -> Boolean,
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

    /**
     * Records that work may exist. Safe to call from any thread and before [start]; the signal is
     * conflated so a wake up can never be lost.
     */
    fun notifyWorkAvailable() {
        wakeSignal.trySend(Unit)
    }

    private suspend fun runLoop() {
        runCatching { beforeFirstDrain() }
            .onFailure { logger.error(it) { "Failed to prepare the $workerName worker" } }

        while (currentCoroutineContext().isActive) {
            val processed =
                try {
                    drainOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "The $workerName worker failed" }
                    false
                }

            if (!processed) {
                // The conflated signal makes the wait race free: a wake that arrived while the
                // queue was being drained is still queued, so it is never lost. The optional
                // timeout only exists for work that no transition can wake us for.
                val timeout = idleTimeoutMillis()
                if (timeout == null) {
                    wakeSignal.receive()
                } else {
                    withTimeoutOrNull(timeout) { wakeSignal.receive() }
                }
            }
        }
    }
}
