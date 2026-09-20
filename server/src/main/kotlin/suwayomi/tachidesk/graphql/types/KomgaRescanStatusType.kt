package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.manga.impl.KomgaRescanConfiguration
import suwayomi.tachidesk.manga.model.dataclass.KomgaScanIntentDataClass
import suwayomi.tachidesk.manga.model.dataclass.KomgaScanState

/**
 * The Komga rescan status of the configured library.
 *
 * It is deliberately free of the configured base URL, library id and API key: an operator needs to
 * know whether scans reach Komga, not where the credentials point, and this type is what a client and
 * the logs see. [configurationError] and [lastError] are already bounded and redacted by the Komga
 * client boundary.
 */
class KomgaRescanStatusType(
    /** whether Komga is usable; a scan is never attempted while it is false */
    val configured: Boolean,
    /** why Komga is unusable, with no configured value echoed back */
    val configurationError: String?,
    val state: KomgaScanState?,
    /** request counter; every published chapter or manual request increments it */
    val generation: Long?,
    val attempts: Int?,
    val requestedAt: Long?,
    /** earliest instant the pending scan may run; the end of its quiet period */
    val notBeforeAt: Long?,
    val lastAttemptAt: Long?,
    val lastCompletedAt: Long?,
    val lastError: String?,
) {
    companion object {
        internal fun of(
            configuration: KomgaRescanConfiguration,
            intent: KomgaScanIntentDataClass?,
        ): KomgaRescanStatusType =
            KomgaRescanStatusType(
                configured = configuration is KomgaRescanConfiguration.Valid,
                configurationError = (configuration as? KomgaRescanConfiguration.Invalid)?.reason,
                state = intent?.state,
                generation = intent?.generation,
                attempts = intent?.attempts,
                requestedAt = intent?.requestedAt,
                notBeforeAt = intent?.notBeforeAt,
                lastAttemptAt = intent?.lastAttemptAt,
                lastCompletedAt = intent?.lastCompletedAt,
                lastError = intent?.lastError,
            )
    }
}
