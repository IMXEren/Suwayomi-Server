package suwayomi.tachidesk.graphql.mutations

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

class KomgaMutation {
    data class RequestKomgaRescanInput(
        val clientMutationId: String? = null,
    )

    data class RequestKomgaRescanPayload(
        val clientMutationId: String?,
        val rescan: KomgaRescanStatusType,
    )

    /**
     * Requests a rescan of the configured Komga library as soon as possible.
     *
     * Coalescing still applies: a rescan that is already pending is only made due sooner, so this can
     * never cause two scans where one would do.
     */
    @RequireAuth
    fun requestKomgaRescan(input: RequestKomgaRescanInput): RequestKomgaRescanPayload {
        val (clientMutationId) = input
        KomgaRescanExecutor.requestImmediateScan()

        return RequestKomgaRescanPayload(clientMutationId, KomgaRescanExecutor.status())
    }

    data class RetryKomgaRescanInput(
        val clientMutationId: String? = null,
    )

    data class RetryKomgaRescanPayload(
        val clientMutationId: String?,
        /** false when there was nothing to retry, which is not an error */
        val retried: Boolean,
        val rescan: KomgaRescanStatusType,
    )

    /**
     * Re-drives a scan that hard-failed.
     *
     * The failure mode this exists for is a wrong base URL, a wrong library id or a rejected API key:
     * none of those retry on their own, so an operator fixes the configuration and then retries.
     */
    @RequireAuth
    fun retryKomgaRescan(input: RetryKomgaRescanInput): RetryKomgaRescanPayload {
        val (clientMutationId) = input
        val retried = KomgaRescanExecutor.retryFailedScan()

        return RetryKomgaRescanPayload(clientMutationId, retried, KomgaRescanExecutor.status())
    }

    /** The status after a mutation, built the same way the query builds it. */
    private fun KomgaRescanExecutor.status(): KomgaRescanStatusType =
        KomgaRescanStatusType.of(configuration = configuration(), intent = KomgaScanIntent.current())
}
