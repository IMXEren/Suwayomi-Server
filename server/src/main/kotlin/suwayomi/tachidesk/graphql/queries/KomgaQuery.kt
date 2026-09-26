package suwayomi.tachidesk.graphql.queries

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.KomgaRescanStatusType
import suwayomi.tachidesk.manga.impl.KomgaRescanExecutor
import suwayomi.tachidesk.manga.impl.KomgaScanIntent

class KomgaQuery {
    /**
     * The current Komga rescan status, without the configured base URL, library id or API key.
     *
     * The status is returned even while Komga is not configured - with [KomgaRescanStatusType.configured]
     * false and the reason - because that is exactly when an operator needs to know why nothing is
     * being scanned.
     */
    @RequireAuth
    fun komgaRescanStatus(): KomgaRescanStatusType =
        KomgaRescanStatusType.of(
            configuration = KomgaRescanExecutor.configuration(),
            intent = KomgaScanIntent.current(),
        )
}
