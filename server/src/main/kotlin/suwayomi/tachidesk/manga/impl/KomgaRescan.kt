package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import suwayomi.tachidesk.manga.model.dataclass.KomgaScanIntentDataClass
import suwayomi.tachidesk.server.serverConfig
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * The built-in publication listener that makes the configured Komga library stale.
 *
 * It deliberately performs no HTTP: a publication only records a coalesced rescan intent and wakes
 * the Komga worker, so publication latency never depends on Komga being reachable. While Komga is not
 * usable the listener fails the delivery, which keeps the publication event durable and pending
 * instead of losing it - the event is retried once Komga is configured.
 */
internal class KomgaRescanPublicationListener(
    private val configuration: () -> KomgaRescanConfiguration,
    private val debounceSeconds: () -> Long,
    private val now: () -> Long,
    private val onIntentRecorded: () -> Unit,
) : ChapterRevisionPublicationListener {
    override suspend fun onPublished(event: ChapterPublicationEvent) {
        when (val target = configuration()) {
            is KomgaRescanConfiguration.Invalid -> {
                throw KomgaNotConfiguredException(target.reason)
            }

            is KomgaRescanConfiguration.Valid -> {
                KomgaScanIntent.requestScan(now = now(), debounceSeconds = debounceSeconds())
                logger.debug { "Komga rescan requested after publishing chapter ${event.payload.chapterKey}" }
                onIntentRecorded()
            }
        }
    }
}

/**
 * One Komga scan attempt.
 *
 * The processor never interprets HTTP itself: it asks the isolated client and maps the three possible
 * outcomes onto the durable intent. A scan is only attempted while Komga is usable, and the claim is
 * released on cancellation so a shutdown can never leave the intent claimed by nobody.
 */
internal class KomgaRescanProcessor(
    private val configuration: () -> KomgaRescanConfiguration,
    private val client: () -> KomgaRescanClient,
    private val debounceSeconds: () -> Long,
    private val retryIntervalSeconds: () -> Long,
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    /**
     * Scans once when an intent is due.
     *
     * Returns true only when a scan was actually attempted, which is what lets the worker sleep
     * instead of spinning while there is nothing to do.
     */
    suspend fun scanDue(): Boolean {
        val target = configuration()
        if (target !is KomgaRescanConfiguration.Valid) {
            // not configured: no connection may ever be opened, and the intent stays pending
            return false
        }

        val claimed = KomgaScanIntent.claimDueScan(now()) ?: return false
        val runningGeneration = claimed.runningGeneration ?: return false

        val outcome =
            try {
                client().rescan(target.request)
            } catch (e: CancellationException) {
                KomgaScanIntent.releaseClaim(runningGeneration, now())
                throw e
            } catch (e: Exception) {
                // A raw throwable must never be logged or persisted here: its message can carry the
                // request URL or the resolved target. Only a bounded, redacted, static diagnostic may
                // escape this boundary, so the exception type is all that is reported.
                val reason =
                    komgaReason(
                        "the Komga scan could not be performed (${e.javaClass.simpleName})",
                        target.request.redactions,
                    )
                logger.warn { "The Komga scan attempt could not be performed: $reason" }
                KomgaRescanOutcome.RetryableFailure(reason)
            }

        apply(outcome, runningGeneration)
        return true
    }

    private fun apply(
        outcome: KomgaRescanOutcome,
        runningGeneration: Long,
    ) {
        when (outcome) {
            is KomgaRescanOutcome.Success -> {
                val completed = KomgaScanIntent.completeScan(runningGeneration, now(), debounceSeconds())
                if (!completed) {
                    logger.info { "The Komga library changed again while it was being scanned; another scan is queued" }
                }
            }

            is KomgaRescanOutcome.RetryableFailure -> {
                logger.warn { "The Komga scan failed and will be retried: ${outcome.reason}" }
                KomgaScanIntent.failScan(
                    runningGeneration = runningGeneration,
                    error = outcome.reason,
                    retryable = true,
                    retryAt = saturatingEpochAdd(now(), retryIntervalSeconds().coerceAtLeast(1)),
                    now = now(),
                    debounceSeconds = debounceSeconds(),
                )
            }

            is KomgaRescanOutcome.HardFailure -> {
                logger.error { "The Komga scan failed until it is retried explicitly: ${outcome.reason}" }
                KomgaScanIntent.failScan(
                    runningGeneration = runningGeneration,
                    error = outcome.reason,
                    retryable = false,
                    retryAt = now(),
                    now = now(),
                    debounceSeconds = debounceSeconds(),
                )
            }
        }
    }
}

/**
 * Records an immediate scan intent when the configured target is usable.
 *
 * Every transition to a usable target has to scan the library that was already published, even when
 * no publication event is undelivered, so a usable target always records a fresh request. When a scan
 * of the previous target is still in flight the request keeps its claim and only bumps the generation,
 * which fences the old outcome and makes a second concurrent claim impossible. An unusable target
 * records nothing at all, so it can never open a connection.
 *
 * Returns true when a scan was requested.
 */
internal fun applyKomgaConfigurationChange(configuration: () -> KomgaRescanConfiguration): Boolean {
    if (configuration() !is KomgaRescanConfiguration.Valid) {
        return false
    }

    // requestImmediateScan is not debounced and revives a FAILED intent, so a configuration change
    // both scans now and is the way out of a failure that no retry would otherwise clear.
    KomgaScanIntent.requestImmediateScan()
    return true
}

/**
 * The single-concurrency Komga rescan worker.
 *
 * It needs a timer because a debounced intent is only claimable after its quiet period, and it must
 * sleep instead of polling when nothing is pending: a pending intent is either already due or carries
 * the persisted instant it becomes due.
 */
internal class KomgaRescanLoop(
    private val processor: KomgaRescanProcessor,
    private val configuration: () -> KomgaRescanConfiguration,
    private val debounceSeconds: () -> Long,
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    private val worker =
        ChapterRevisionWorkerLoop(
            workerName = "Komga rescan",
            beforeFirstDrain = { KomgaScanIntent.recoverInterruptedScan(now(), debounceSeconds()) },
            idleTimeoutMillis = { idleTimeoutMillis() },
            drainOnce = { processor.scanDue() },
        )

    fun start() = worker.start()

    fun stop() = worker.stop()

    fun notifyWorkAvailable() = worker.notifyWorkAvailable()

    private fun idleTimeoutMillis(): Long? {
        if (configuration() !is KomgaRescanConfiguration.Valid) {
            // nothing can be scanned while Komga is not usable; a configuration change wakes the loop
            return null
        }

        val dueAt = KomgaScanIntent.nextDueAt(now()) ?: return null
        return waitMillisUntil(dueAt, now(), MIN_WAIT_MILLIS)
    }

    private companion object {
        /** Bounded below so a due-but-unclaimable intent can never spin the loop. */
        const val MIN_WAIT_MILLIS = 1_000L
    }
}

/**
 * The Komga integration's lifecycle and configuration boundary.
 *
 * The publication listener is registered before the publication worker starts, so no publication can
 * ever be delivered without its Komga intent being recorded. Registration is idempotent, and stopping
 * removes the listener again, which is what lets tests cycle the integration without leaking a
 * listener or a coroutine into the next test.
 */
object KomgaRescanExecutor {
    private val httpClient by lazy { OkHttpClient.Builder().build() }

    private val loop by lazy {
        KomgaRescanLoop(
            processor =
                KomgaRescanProcessor(
                    configuration = { configuration() },
                    client = { rescanClient() },
                    debounceSeconds = { debounceSeconds() },
                    retryIntervalSeconds = { serverConfig.komgaRescanRetrySeconds.value.toLong() },
                ),
            configuration = { configuration() },
            debounceSeconds = { debounceSeconds() },
        )
    }

    private fun rescanClient(): KomgaRescanClient =
        DefaultKomgaRescanClient(
            client = { httpClient },
            timeoutSeconds = { serverConfig.komgaRequestTimeoutSeconds.value.toLong() },
        )

    private val publicationListener by lazy {
        KomgaRescanPublicationListener(
            configuration = { configuration() },
            debounceSeconds = { debounceSeconds() },
            now = { Instant.now().epochSecond },
            onIntentRecorded = { notifyWorkAvailable() },
        )
    }

    fun start() {
        ChapterRevisionPublicationListenerRegistry.register(publicationListener)
        loop.start()
    }

    fun stop() {
        loop.stop()
        ChapterRevisionPublicationListenerRegistry.unregister(publicationListener)
    }

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()

    /** The current, already validated Komga target. */
    internal fun configuration(): KomgaRescanConfiguration =
        komgaRescanConfiguration(
            baseUrl = serverConfig.komgaBaseUrl.value,
            apiKey = serverConfig.komgaApiKey.value,
            libraryId = serverConfig.komgaLibraryId.value,
        )

    /** The quiet period a rescan waits for after the last publication. */
    private fun debounceSeconds(): Long = serverConfig.komgaRescanDebounceSeconds.value.toLong()

    /** Records an explicit scan request that runs as soon as possible. */
    fun requestImmediateScan(): KomgaScanIntentDataClass {
        val intent = KomgaScanIntent.requestImmediateScan()
        notifyWorkAvailable()
        return intent
    }

    /** Requeues a failed scan. Returns false when there was nothing to retry. */
    fun retryFailedScan(): Boolean {
        val retried = KomgaScanIntent.retryFailed()
        if (retried) {
            notifyWorkAvailable()
        }
        return retried
    }

    /**
     * Called when the Komga configuration changed.
     *
     * A usable configuration records an immediate scan, so enabling Komga or pointing it somewhere new
     * scans the already-published library even when no publication event is undelivered; a scan of the
     * previous target that is still in flight is fenced by that request. A blank configuration records
     * nothing and can never cause HTTP, but both workers are still woken so the status surface reflects
     * reality and the undelivered publication events are retried as soon as Komga becomes usable.
     */
    fun configurationChanged() {
        applyKomgaConfigurationChange { configuration() }
        notifyWorkAvailable()
        ChapterRevisionPublicationExecutor.notifyWorkAvailable()
    }
}
