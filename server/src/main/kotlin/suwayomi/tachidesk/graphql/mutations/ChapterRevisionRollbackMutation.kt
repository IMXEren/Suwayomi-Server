package suwayomi.tachidesk.graphql.mutations

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.ChapterRevisionType
import suwayomi.tachidesk.manga.impl.ChapterRevision
import suwayomi.tachidesk.manga.impl.ChapterRevisionPublicationExecutor
import suwayomi.tachidesk.manga.impl.ChapterRevisionRetentionExecutor
import suwayomi.tachidesk.manga.impl.ChapterRevisionRollbackOutcome

/**
 * Returns to a historical revision of a chapter.
 *
 * The decision is durable and reversible by the same operation: rolling back to the revision that is
 * already active is a no-op, so replaying a request is safe. Content itself is only ever replaced by
 * the ordinary publication worker, strictly after the decision committed - the mutation therefore
 * wakes that worker and the retention worker instead of writing any file itself.
 */
class ChapterRevisionRollbackMutation {
    data class RollbackChapterRevisionInput(
        val clientMutationId: String? = null,
        /** the historical revision that should become the active one again */
        val revisionId: Int,
    )

    data class RollbackChapterRevisionPayload(
        val clientMutationId: String?,
        /** the revision that is active after this decision */
        val revision: ChapterRevisionType?,
        /** the revision this decision replaced, null when the chapter had no active revision */
        val replacedRevisionId: Int?,
        val error: String?,
    )

    @RequireAuth
    fun rollbackChapterRevision(input: RollbackChapterRevisionInput): RollbackChapterRevisionPayload {
        val outcome = ChapterRevision.rollbackChapterRevision(input.revisionId)

        return when (outcome) {
            is ChapterRevisionRollbackOutcome.RolledBack -> {
                // the decision is committed, so the workers are woken strictly afterwards: publication
                // has to replace the active copy, and the identity's retention window changed because a
                // different revision is active now
                ChapterRevisionPublicationExecutor.notifyWorkAvailable()
                ChapterRevisionRetentionExecutor.requestIdentity(outcome.revision.chapterKey)

                RollbackChapterRevisionPayload(
                    clientMutationId = input.clientMutationId,
                    revision = ChapterRevisionType(outcome.revision),
                    replacedRevisionId = outcome.replacedActiveRevisionId,
                    error = null,
                )
            }

            ChapterRevisionRollbackOutcome.NotFound -> {
                RollbackChapterRevisionPayload(
                    clientMutationId = input.clientMutationId,
                    revision = null,
                    replacedRevisionId = null,
                    error = "no revision with id ${input.revisionId}",
                )
            }

            ChapterRevisionRollbackOutcome.AlreadyActive -> {
                // a replay is reported as the no-op it is, rather than as an error a client would retry
                RollbackChapterRevisionPayload(
                    clientMutationId = input.clientMutationId,
                    revision = ChapterRevision.getRevision(input.revisionId)?.let { ChapterRevisionType(it) },
                    replacedRevisionId = null,
                    error = null,
                )
            }

            is ChapterRevisionRollbackOutcome.Refused -> {
                RollbackChapterRevisionPayload(
                    clientMutationId = input.clientMutationId,
                    revision = null,
                    replacedRevisionId = null,
                    error = outcome.reason,
                )
            }
        }
    }
}
