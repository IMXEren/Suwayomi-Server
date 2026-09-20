package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import suwayomi.tachidesk.manga.model.dataclass.KomgaScanIntentDataClass
import suwayomi.tachidesk.manga.model.dataclass.KomgaScanState

/**
 * The single coalesced Komga rescan intent.
 *
 * There is exactly one row - Komga is configured as one library - so the table is keyed by a fixed
 * [id] instead of an auto-increment. Publishing many chapters at once must not queue many scans, and
 * a coalesced intent must survive a restart, so the intent is durable state rather than an in-memory
 * signal.
 */
object KomgaScanIntentTable : Table("komgascanintent") {
    val id = integer("id")
    val state = varchar("state", 256)
    val generation = long("generation")
    val runningGeneration = long("running_generation").nullable()
    val requestedAt = long("requested_at")
    val notBeforeAt = long("not_before_at").nullable()
    val attempts = integer("attempts")
    val lastAttemptAt = long("last_attempt_at").nullable()
    val lastCompletedAt = long("last_completed_at").nullable()
    val lastError = varchar("last_error", 4096).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        // the worker only ever looks for a pending intent whose debounce window has elapsed
        index("komga_scan_intent_due_idx", false, state, notBeforeAt, id)
    }
}

fun KomgaScanIntentTable.toDataClass(intentEntry: ResultRow) =
    KomgaScanIntentDataClass(
        state = KomgaScanState.valueOf(intentEntry[state]),
        generation = intentEntry[generation],
        runningGeneration = intentEntry[runningGeneration],
        requestedAt = intentEntry[requestedAt],
        notBeforeAt = intentEntry[notBeforeAt],
        attempts = intentEntry[attempts],
        lastAttemptAt = intentEntry[lastAttemptAt],
        lastCompletedAt = intentEntry[lastCompletedAt],
        lastError = intentEntry[lastError],
    )
