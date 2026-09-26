package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Saturating epoch-second arithmetic for persisted due times.
 *
 * A persisted due time is compared against the wall clock, so it must never wrap: adding to a value
 * that is already at [Long.MAX_VALUE] would produce a negative instant that reads as "due long ago"
 * and turn a deferred retry into a hot loop. Saturating keeps such a value "far in the future"
 * instead, which is what the callers of this function intend.
 */
internal fun saturatingEpochAdd(
    now: Long,
    deltaSeconds: Long,
): Long {
    if (deltaSeconds <= 0) {
        return now
    }

    return if (now > Long.MAX_VALUE - deltaSeconds) Long.MAX_VALUE else now + deltaSeconds
}

/** One epoch second after [value], saturating instead of wrapping. */
internal fun epochSecondAfter(value: Long): Long = saturatingEpochAdd(value, 1)
