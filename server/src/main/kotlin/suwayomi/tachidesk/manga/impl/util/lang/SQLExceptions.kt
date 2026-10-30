package suwayomi.tachidesk.manga.impl.util.lang

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.sql.SQLException

/** The duplicate-key state both H2 and PostgreSQL report, so a lost insert race is recognisable. */
internal const val DUPLICATE_KEY_SQLSTATE = "23505"

/**
 * True when this failure - or any failure that caused it - is a duplicate-key violation.
 *
 * A unique index is how this codebase expresses a single-owner invariant, so losing that race is an
 * expected outcome rather than an error: the caller has to tell it apart from a real write failure.
 * The cause chain is walked because the driver wraps the constraint violation, and the SQL state is
 * matched instead of the message because only the state is dialect independent.
 */
internal fun Throwable.isDuplicateKeyViolation(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is SQLException && cause.sqlState == DUPLICATE_KEY_SQLSTATE) {
            return true
        }
        cause = cause.cause
    }
    return false
}
