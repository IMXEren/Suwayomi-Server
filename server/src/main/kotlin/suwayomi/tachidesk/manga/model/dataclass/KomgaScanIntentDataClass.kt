package suwayomi.tachidesk.manga.model.dataclass

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Lifecycle of the coalesced Komga rescan intent.
 *
 * The intent is a single durable row rather than a queue: every publication only means "the
 * configured Komga library is stale", so N publications must produce one scan, not N scans.
 * [RUNNING] is the claimed state; a request that arrives while a scan runs only bumps the
 * generation, so the stale completion is recognisable and becomes [PENDING] again.
 */
enum class KomgaScanState {
    PENDING,
    RUNNING,
    COMPLETE,
    FAILED,
}

/**
 * The persisted Komga rescan intent.
 *
 * It never stores the base URL, the library id or the API key: the intent only says "the configured
 * library is stale", so changing the Komga configuration never invalidates a recorded intent, and a
 * database dump can never leak credentials. [generation] is bumped by every request and copied into
 * [runningGeneration] when a scan is claimed, which is what fences a completion that belongs to an
 * older request.
 */
data class KomgaScanIntentDataClass(
    val state: KomgaScanState,
    /** request counter; every publication or manual request increments it */
    val generation: Long,
    /** the generation the in-flight scan was claimed for, null while no scan is running */
    val runningGeneration: Long?,
    /** when the intent was first or last requested */
    val requestedAt: Long,
    /** earliest instant the pending scan may run; the debounce window of the newest request */
    val notBeforeAt: Long?,
    val attempts: Int,
    val lastAttemptAt: Long?,
    val lastCompletedAt: Long?,
    /** bounded, redacted reason of the last failed attempt */
    val lastError: String?,
)
