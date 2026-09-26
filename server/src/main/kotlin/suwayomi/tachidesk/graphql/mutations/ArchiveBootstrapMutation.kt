package suwayomi.tachidesk.graphql.mutations

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.ArchiveBootstrapSessionType
import suwayomi.tachidesk.manga.impl.ArchiveBootstrap
import suwayomi.tachidesk.manga.impl.ArchiveBootstrapExecutor
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapCategoryPolicy
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy

/**
 * Control over archive bootstrap runs.
 *
 * Starting a run requires an explicit acquisition policy, because the policy is what the bootstrap
 * will persist per series: it is the user's archival intent, not a default this API may pick.
 */
class ArchiveBootstrapMutation {
    data class ArchiveBootstrapCategoryPolicyInput(
        val categoryId: Int,
        val policy: MangaAcquisitionPolicy,
    )

    data class StartArchiveBootstrapInput(
        val clientMutationId: String? = null,
        /** the policy applied to every series without a matching category override */
        val defaultPolicy: MangaAcquisitionPolicy,
        /** ordered: the first override matching a series wins */
        val categoryPolicies: List<ArchiveBootstrapCategoryPolicyInput>? = null,
        /** optional subset of series; omitted bootstraps the whole library */
        val mangaIds: List<Int>? = null,
    )

    data class StartArchiveBootstrapPayload(
        val clientMutationId: String?,
        val session: ArchiveBootstrapSessionType?,
        val itemCount: Int?,
        val error: String?,
    )

    @RequireAuth
    fun startArchiveBootstrap(input: StartArchiveBootstrapInput): StartArchiveBootstrapPayload {
        val request =
            ArchiveBootstrap.StartRequest(
                defaultPolicy = input.defaultPolicy,
                categoryPolicies =
                    input.categoryPolicies.orEmpty().map {
                        ArchiveBootstrapCategoryPolicy(it.categoryId, it.policy)
                    },
                mangaIds = input.mangaIds,
            )

        return when (val outcome = ArchiveBootstrap.start(request)) {
            is ArchiveBootstrap.StartOutcome.Started -> {
                StartArchiveBootstrapPayload(
                    clientMutationId = input.clientMutationId,
                    session = ArchiveBootstrapSessionType(outcome.session),
                    itemCount = outcome.itemCount,
                    error = null,
                )
            }

            ArchiveBootstrap.StartOutcome.NothingToBootstrap -> {
                StartArchiveBootstrapPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = 0,
                    error = "no in-library series match the request",
                )
            }

            ArchiveBootstrap.StartOutcome.ActiveSessionExists -> {
                StartArchiveBootstrapPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = null,
                    error = "another archive bootstrap is already active",
                )
            }
        }
    }

    data class ArchiveBootstrapSessionInput(
        val clientMutationId: String? = null,
        val sessionId: Int,
    )

    data class ArchiveBootstrapSessionPayload(
        val clientMutationId: String?,
        val session: ArchiveBootstrapSessionType?,
        val itemCount: Int?,
        val error: String?,
    )

    /** Stops claiming new series; one already being processed is allowed to finish. */
    @RequireAuth
    fun pauseArchiveBootstrap(input: ArchiveBootstrapSessionInput): ArchiveBootstrapSessionPayload {
        val session = ArchiveBootstrap.pause(input.sessionId)
        return ArchiveBootstrapSessionPayload(
            clientMutationId = input.clientMutationId,
            session = session?.let { ArchiveBootstrapSessionType(it) },
            itemCount = null,
            error = if (session == null) "no running archive bootstrap with id ${input.sessionId}" else null,
        )
    }

    @RequireAuth
    fun resumeArchiveBootstrap(input: ArchiveBootstrapSessionInput): ArchiveBootstrapSessionPayload {
        val session = ArchiveBootstrap.resume(input.sessionId)
        if (session != null) {
            ArchiveBootstrapExecutor.notifyWorkAvailable()
        }

        return ArchiveBootstrapSessionPayload(
            clientMutationId = input.clientMutationId,
            session = session?.let { ArchiveBootstrapSessionType(it) },
            itemCount = null,
            error = if (session == null) "no paused archive bootstrap with id ${input.sessionId}" else null,
        )
    }

    /** Abandons a run. Every unfinished series is recorded as cancelled. */
    @RequireAuth
    fun cancelArchiveBootstrap(input: ArchiveBootstrapSessionInput): ArchiveBootstrapSessionPayload {
        val session = ArchiveBootstrap.cancel(input.sessionId)
        return ArchiveBootstrapSessionPayload(
            clientMutationId = input.clientMutationId,
            session = session?.let { ArchiveBootstrapSessionType(it) },
            itemCount = null,
            error = if (session == null) "no active archive bootstrap with id ${input.sessionId}" else null,
        )
    }

    data class RetryArchiveBootstrapItemsInput(
        val clientMutationId: String? = null,
        val sessionId: Int,
        /** omitted retries every failed or source-unresolved series of the run */
        val itemIds: List<Int>? = null,
    )

    data class RetryArchiveBootstrapItemsPayload(
        val clientMutationId: String?,
        val session: ArchiveBootstrapSessionType?,
        val itemCount: Int?,
        val error: String?,
    )

    @RequireAuth
    fun retryArchiveBootstrapItems(input: RetryArchiveBootstrapItemsInput): RetryArchiveBootstrapItemsPayload =
        when (val outcome = ArchiveBootstrap.retry(input.sessionId, input.itemIds)) {
            is ArchiveBootstrap.RetryOutcome.Retried -> {
                RetryArchiveBootstrapItemsPayload(
                    clientMutationId = input.clientMutationId,
                    session = ArchiveBootstrapSessionType(outcome.session),
                    itemCount = outcome.itemCount,
                    error = null,
                )
            }

            ArchiveBootstrap.RetryOutcome.NotFound -> {
                RetryArchiveBootstrapItemsPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = null,
                    error = "no archive bootstrap with id ${input.sessionId}",
                )
            }

            ArchiveBootstrap.RetryOutcome.AnotherSessionActive -> {
                RetryArchiveBootstrapItemsPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = null,
                    error = "another archive bootstrap is already active",
                )
            }
        }
}
