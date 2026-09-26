package suwayomi.tachidesk.graphql.mutations

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.ChapterIntegrityAuditSessionType
import suwayomi.tachidesk.manga.impl.ChapterRevisionIntegrityAudit
import suwayomi.tachidesk.manga.impl.ChapterRevisionIntegrityAuditExecutor
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditKind
import suwayomi.tachidesk.server.serverConfig

/**
 * Control over archive integrity audits.
 *
 * Starting a run is deliberately explicit about *how much* is checked: a full-history audit issues one
 * remote command per archived revision, so it is only ever requested, never implied. A run is also
 * refused while no remote verifier is configured - a session whose revisions could never be checked
 * would hold the single-active-run invariant forever, so it is better to say so than to record a run
 * that can only sit there.
 */
class ChapterIntegrityAuditMutation {
    data class StartChapterIntegrityAuditInput(
        val clientMutationId: String? = null,
        /** SCHEDULED is reserved for the scheduler, so a client asks for a recent-only or a full audit */
        val kind: ChapterIntegrityAuditKind = ChapterIntegrityAuditKind.MANUAL_RECENT,
        /** optional subset of series; omitted audits every series that has archived content */
        val mangaIds: List<Int>? = null,
    )

    data class StartChapterIntegrityAuditPayload(
        val clientMutationId: String?,
        val session: ChapterIntegrityAuditSessionType?,
        val itemCount: Int?,
        val error: String?,
    )

    @RequireAuth
    fun startChapterIntegrityAudit(input: StartChapterIntegrityAuditInput): StartChapterIntegrityAuditPayload {
        // A scheduled audit is the scheduler's own business: letting a client claim that kind would make
        // the persisted schedule and the run disagree about who started what.
        if (input.kind == ChapterIntegrityAuditKind.SCHEDULED) {
            return StartChapterIntegrityAuditPayload(
                clientMutationId = input.clientMutationId,
                session = null,
                itemCount = null,
                error = "the scheduled audit can only be started by the scheduler",
            )
        }

        if (serverConfig.archiveRcloneRemote.value.isBlank()) {
            return StartChapterIntegrityAuditPayload(
                clientMutationId = input.clientMutationId,
                session = null,
                itemCount = null,
                error = "no rclone remote is configured, so no archived artifact could be checked",
            )
        }

        val newestPerManga =
            ChapterRevisionIntegrityAudit.newestPerMangaFor(
                input.kind,
                serverConfig.chapterIntegrityAuditRecentRevisions.value,
            )

        return when (
            val outcome =
                ChapterRevisionIntegrityAudit.start(
                    request = ChapterRevisionIntegrityAudit.StartRequest(input.kind, input.mangaIds),
                    newestPerManga = newestPerManga,
                )
        ) {
            is ChapterRevisionIntegrityAudit.StartOutcome.Started -> {
                StartChapterIntegrityAuditPayload(
                    clientMutationId = input.clientMutationId,
                    session = ChapterIntegrityAuditSessionType(outcome.session),
                    itemCount = outcome.itemCount,
                    error = null,
                )
            }

            ChapterRevisionIntegrityAudit.StartOutcome.NothingToAudit -> {
                StartChapterIntegrityAuditPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = 0,
                    error = "no archived revision matches the request",
                )
            }

            ChapterRevisionIntegrityAudit.StartOutcome.ActiveSessionExists -> {
                StartChapterIntegrityAuditPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = null,
                    error = "another integrity audit is already active",
                )
            }

            is ChapterRevisionIntegrityAudit.StartOutcome.InvalidSubset -> {
                StartChapterIntegrityAuditPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = null,
                    // bounded: a caller that asks for thousands of unknown series must not turn the error
                    // into a payload of thousands of ids
                    error = "series do not exist: ${outcome.unknownMangaIds.take(20).joinToString(", ")}",
                )
            }
        }
    }

    data class ChapterIntegrityAuditSessionInput(
        val clientMutationId: String? = null,
        val sessionId: Int,
    )

    data class ChapterIntegrityAuditSessionPayload(
        val clientMutationId: String?,
        val session: ChapterIntegrityAuditSessionType?,
        val error: String?,
    )

    /** Stops claiming revisions. One already being checked is allowed to finish. */
    @RequireAuth
    fun pauseChapterIntegrityAudit(input: ChapterIntegrityAuditSessionInput): ChapterIntegrityAuditSessionPayload {
        val session = ChapterRevisionIntegrityAudit.pause(input.sessionId)
        return ChapterIntegrityAuditSessionPayload(
            clientMutationId = input.clientMutationId,
            session = session?.let { ChapterIntegrityAuditSessionType(it) },
            error = if (session == null) "no running integrity audit with id ${input.sessionId}" else null,
        )
    }

    @RequireAuth
    fun resumeChapterIntegrityAudit(input: ChapterIntegrityAuditSessionInput): ChapterIntegrityAuditSessionPayload {
        val session = ChapterRevisionIntegrityAudit.resume(input.sessionId)
        if (session != null) {
            ChapterRevisionIntegrityAuditExecutor.notifyWorkAvailable()
        }

        return ChapterIntegrityAuditSessionPayload(
            clientMutationId = input.clientMutationId,
            session = session?.let { ChapterIntegrityAuditSessionType(it) },
            error = if (session == null) "no paused integrity audit with id ${input.sessionId}" else null,
        )
    }

    /** Abandons a run. Every unchecked revision is recorded as skipped, never as checked. */
    @RequireAuth
    fun cancelChapterIntegrityAudit(input: ChapterIntegrityAuditSessionInput): ChapterIntegrityAuditSessionPayload {
        val session = ChapterRevisionIntegrityAudit.cancel(input.sessionId)
        return ChapterIntegrityAuditSessionPayload(
            clientMutationId = input.clientMutationId,
            session = session?.let { ChapterIntegrityAuditSessionType(it) },
            error = if (session == null) "no active integrity audit with id ${input.sessionId}" else null,
        )
    }

    data class RetryChapterIntegrityAuditItemsInput(
        val clientMutationId: String? = null,
        val sessionId: Int,
        /** omitted retries every failed revision of the run */
        val itemIds: List<Int>? = null,
    )

    data class RetryChapterIntegrityAuditItemsPayload(
        val clientMutationId: String?,
        val session: ChapterIntegrityAuditSessionType?,
        val itemCount: Int?,
        val error: String?,
    )

    @RequireAuth
    fun retryChapterIntegrityAuditItems(input: RetryChapterIntegrityAuditItemsInput): RetryChapterIntegrityAuditItemsPayload =
        when (val outcome = ChapterRevisionIntegrityAudit.retry(input.sessionId, input.itemIds)) {
            is ChapterRevisionIntegrityAudit.RetryOutcome.Retried -> {
                RetryChapterIntegrityAuditItemsPayload(
                    clientMutationId = input.clientMutationId,
                    session = ChapterIntegrityAuditSessionType(outcome.session),
                    itemCount = outcome.itemCount,
                    error = null,
                )
            }

            ChapterRevisionIntegrityAudit.RetryOutcome.NotFound -> {
                RetryChapterIntegrityAuditItemsPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = null,
                    error = "no integrity audit with id ${input.sessionId}",
                )
            }

            ChapterRevisionIntegrityAudit.RetryOutcome.AnotherSessionActive -> {
                RetryChapterIntegrityAuditItemsPayload(
                    clientMutationId = input.clientMutationId,
                    session = null,
                    itemCount = null,
                    error = "another integrity audit is already active",
                )
            }
        }
}
