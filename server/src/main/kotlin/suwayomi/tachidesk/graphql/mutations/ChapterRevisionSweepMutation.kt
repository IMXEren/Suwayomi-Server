package suwayomi.tachidesk.graphql.mutations

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.ChapterRevisionSweepSessionType
import suwayomi.tachidesk.manga.impl.ChapterRevisionSweep
import suwayomi.tachidesk.manga.impl.ChapterRevisionSweepExecutor
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepKind
import suwayomi.tachidesk.server.serverConfig

/**
 * Control over revision sweeps.
 *
 * Starting a run is deliberately explicit about *how much* is swept: a full history sweep of a large
 * library re-downloads tens of thousands of chapters, so it is only ever requested, never implied.
 */
class ChapterRevisionSweepMutation {
    data class StartChapterRevisionSweepInput(
        val clientMutationId: String? = null,
        /** SCHEDULED is reserved for the scheduler, so a client asks for a recent-only or a full sweep */
        val kind: ChapterRevisionSweepKind = ChapterRevisionSweepKind.MANUAL_RECENT,
        /** optional subset of series; omitted sweeps every tracked series */
        val mangaIds: List<Int>? = null,
    )

    data class StartChapterRevisionSweepPayload(
        val clientMutationId: String?,
        val session: ChapterRevisionSweepSessionType?,
        val itemCount: Int?,
        val error: String?,
    )

    @RequireAuth
    fun startChapterRevisionSweep(input: StartChapterRevisionSweepInput): StartChapterRevisionSweepPayload {
        // A scheduled sweep is the scheduler's own business: letting a client claim that kind would
        // make the persisted schedule and the run disagree about who started what.
        if (input.kind == ChapterRevisionSweepKind.SCHEDULED) {
            return StartChapterRevisionSweepPayload(
                clientMutationId = input.clientMutationId,
                session = null,
                itemCount = null,
                error = "the scheduled sweep can only be started by the scheduler",
            )
        }

        val newestPerSeries =
            ChapterRevisionSweep.newestPerSeriesFor(
                input.kind,
                serverConfig.chapterRevisionSweepNewestChapters.value,
            )

        return when (
            val outcome =
                ChapterRevisionSweep.start(
                    request = ChapterRevisionSweep.StartRequest(input.kind, input.mangaIds),
                    newestPerSeries = newestPerSeries,
                )
        ) {
            is ChapterRevisionSweep.StartOutcome.Started -> {
                StartChapterRevisionSweepPayload(
                    clientMutationId = input.clientMutationId,
                    session = ChapterRevisionSweepSessionType(outcome.session),
                    itemCount = outcome.itemCount,
                    error = null,
                )
            }

            ChapterRevisionSweep.StartOutcome.NothingToSweep -> {
                StartChapterRevisionSweepPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = 0,
                    error = "no tracked series has a chapter that matches the request",
                )
            }

            ChapterRevisionSweep.StartOutcome.ActiveSessionExists -> {
                StartChapterRevisionSweepPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = null,
                    error = "another revision sweep is already active",
                )
            }

            is ChapterRevisionSweep.StartOutcome.InvalidSubset -> {
                StartChapterRevisionSweepPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = null,
                    // bounded: a caller that asks for thousands of unknown series must not turn the
                    // error into a payload of thousands of ids
                    error = "series are not tracked: ${outcome.unknownMangaIds.take(20).joinToString(", ")}",
                )
            }
        }
    }

    data class ChapterRevisionSweepSessionInput(
        val clientMutationId: String? = null,
        val sessionId: Int,
    )

    data class ChapterRevisionSweepSessionPayload(
        val clientMutationId: String?,
        val session: ChapterRevisionSweepSessionType?,
        val itemCount: Int?,
        val error: String?,
    )

    /** Stops claiming chapters. One already being processed is allowed to finish. */
    @RequireAuth
    fun pauseChapterRevisionSweep(input: ChapterRevisionSweepSessionInput): ChapterRevisionSweepSessionPayload {
        val session = ChapterRevisionSweep.pause(input.sessionId)
        return ChapterRevisionSweepSessionPayload(
            clientMutationId = input.clientMutationId,
            session = session?.let { ChapterRevisionSweepSessionType(it) },
            itemCount = null,
            error = if (session == null) "no running revision sweep with id ${input.sessionId}" else null,
        )
    }

    @RequireAuth
    fun resumeChapterRevisionSweep(input: ChapterRevisionSweepSessionInput): ChapterRevisionSweepSessionPayload {
        val session = ChapterRevisionSweep.resume(input.sessionId)
        if (session != null) {
            ChapterRevisionSweepExecutor.notifyWorkAvailable()
        }

        return ChapterRevisionSweepSessionPayload(
            clientMutationId = input.clientMutationId,
            session = session?.let { ChapterRevisionSweepSessionType(it) },
            itemCount = null,
            error = if (session == null) "no paused revision sweep with id ${input.sessionId}" else null,
        )
    }

    /** Abandons a run. Every unfinished chapter is recorded as cancelled. */
    @RequireAuth
    fun cancelChapterRevisionSweep(input: ChapterRevisionSweepSessionInput): ChapterRevisionSweepSessionPayload {
        val session = ChapterRevisionSweep.cancel(input.sessionId)
        return ChapterRevisionSweepSessionPayload(
            clientMutationId = input.clientMutationId,
            session = session?.let { ChapterRevisionSweepSessionType(it) },
            itemCount = null,
            error = if (session == null) "no active revision sweep with id ${input.sessionId}" else null,
        )
    }

    data class RetryChapterRevisionSweepItemsInput(
        val clientMutationId: String? = null,
        val sessionId: Int,
        /** omitted retries every failed chapter of the run */
        val itemIds: List<Int>? = null,
    )

    data class RetryChapterRevisionSweepItemsPayload(
        val clientMutationId: String?,
        val session: ChapterRevisionSweepSessionType?,
        val itemCount: Int?,
        val error: String?,
    )

    @RequireAuth
    fun retryChapterRevisionSweepItems(input: RetryChapterRevisionSweepItemsInput): RetryChapterRevisionSweepItemsPayload =
        when (val outcome = ChapterRevisionSweep.retry(input.sessionId, input.itemIds)) {
            is ChapterRevisionSweep.RetryOutcome.Retried -> {
                RetryChapterRevisionSweepItemsPayload(
                    clientMutationId = input.clientMutationId,
                    session = ChapterRevisionSweepSessionType(outcome.session),
                    itemCount = outcome.itemCount,
                    error = null,
                )
            }

            ChapterRevisionSweep.RetryOutcome.NotFound -> {
                RetryChapterRevisionSweepItemsPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = null,
                    error = "no revision sweep with id ${input.sessionId}",
                )
            }

            ChapterRevisionSweep.RetryOutcome.AnotherSessionActive -> {
                RetryChapterRevisionSweepItemsPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = null,
                    error = "another revision sweep is already active",
                )
            }
        }
}
