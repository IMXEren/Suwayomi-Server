package suwayomi.tachidesk.graphql.mutations

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.ChapterRevisionType
import suwayomi.tachidesk.manga.impl.ChapterRevision
import suwayomi.tachidesk.manga.impl.ChapterRevisionAcquisitionExecutor
import suwayomi.tachidesk.manga.impl.ChapterRevisionArchiveExecutor
import suwayomi.tachidesk.manga.impl.ChapterRevisionPublicationExecutor
import suwayomi.tachidesk.manga.impl.ChapterRevisionRetentionExecutor
import suwayomi.tachidesk.manga.impl.ChapterRevisionVisualAnalysisExecutor
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionReviewAction

class ChapterRevisionMutation {
    data class RetryChapterRevisionVisualAnalysesInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class RetryChapterRevisionVisualAnalysesPayload(
        val clientMutationId: String?,
        val revisions: List<ChapterRevisionType>,
    )

    /**
     * Re-queues the page comparison of already analysed revisions.
     *
     * The comparison is derived data, so re-running it replaces the stored summary and never touches
     * the revision's content, archive or publication. A retry that fails leaves the previous summary
     * in place.
     */
    @RequireAuth
    fun retryChapterRevisionVisualAnalyses(input: RetryChapterRevisionVisualAnalysesInput): RetryChapterRevisionVisualAnalysesPayload {
        val (clientMutationId, ids) = input

        val revisions = ChapterRevisionVisualAnalysisExecutor.retry(ids).map { ChapterRevisionType(it) }

        return RetryChapterRevisionVisualAnalysesPayload(clientMutationId, revisions)
    }

    data class ApproveChapterRevisionsInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class ApproveChapterRevisionsPayload(
        val clientMutationId: String?,
        val revisions: List<ChapterRevisionType>,
    )

    @RequireAuth
    fun approveChapterRevisions(input: ApproveChapterRevisionsInput): ApproveChapterRevisionsPayload {
        val (clientMutationId, ids) = input

        val revisions = ChapterRevisionAcquisitionExecutor.approve(ids).map { ChapterRevisionType(it) }

        return ApproveChapterRevisionsPayload(clientMutationId, revisions)
    }

    data class RetryChapterRevisionsInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class RetryChapterRevisionsPayload(
        val clientMutationId: String?,
        val revisions: List<ChapterRevisionType>,
    )

    /** Re-queues candidates whose acquisition failed, preserving their attempt count. */
    @RequireAuth
    fun retryChapterRevisions(input: RetryChapterRevisionsInput): RetryChapterRevisionsPayload {
        val (clientMutationId, ids) = input

        val revisions = ChapterRevisionAcquisitionExecutor.retry(ids).map { ChapterRevisionType(it) }

        return RetryChapterRevisionsPayload(clientMutationId, revisions)
    }

    data class RetryChapterRevisionArchivesInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class RetryChapterRevisionArchivesPayload(
        val clientMutationId: String?,
        val revisions: List<ChapterRevisionType>,
    )

    /**
     * Re-queues candidates whose archive commit failed, preserving their archive attempt count.
     *
     * Published artifacts of the failed attempt are removed before the retry becomes visible.
     */
    @RequireAuth
    fun retryChapterRevisionArchives(input: RetryChapterRevisionArchivesInput): RetryChapterRevisionArchivesPayload {
        val (clientMutationId, ids) = input

        val revisions = ChapterRevisionArchiveExecutor.retry(ids).map { ChapterRevisionType(it) }

        return RetryChapterRevisionArchivesPayload(clientMutationId, revisions)
    }

    data class RejectChapterRevisionsInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class RejectChapterRevisionsPayload(
        val clientMutationId: String?,
        val revisions: List<ChapterRevisionType>,
    )

    @RequireAuth
    fun rejectChapterRevisions(input: RejectChapterRevisionsInput): RejectChapterRevisionsPayload {
        val (clientMutationId, ids) = input

        val revisions = ChapterRevision.reject(ids).map { ChapterRevisionType(it) }

        return RejectChapterRevisionsPayload(clientMutationId, revisions)
    }

    data class AcceptChapterRevisionCandidatesInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class AcceptChapterRevisionCandidatesPayload(
        val clientMutationId: String?,
        val revisions: List<ChapterRevisionType>,
    )

    /**
     * ACCEPT_CANDIDATE: the candidate becomes the sole active revision and the previously active one
     * becomes superseded. Only a remotely-confirmed candidate can be accepted.
     */
    @RequireAuth
    fun acceptChapterRevisionCandidates(input: AcceptChapterRevisionCandidatesInput): AcceptChapterRevisionCandidatesPayload {
        val (clientMutationId, ids) = input

        val revisions = review(ids, ChapterRevisionReviewAction.ACCEPT_CANDIDATE)

        return AcceptChapterRevisionCandidatesPayload(clientMutationId, revisions)
    }

    data class KeepCurrentChapterRevisionsInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class KeepCurrentChapterRevisionsPayload(
        val clientMutationId: String?,
        val revisions: List<ChapterRevisionType>,
    )

    /** KEEP_CURRENT: the candidate is rejected so the currently active revision keeps its place. */
    @RequireAuth
    fun keepCurrentChapterRevisions(input: KeepCurrentChapterRevisionsInput): KeepCurrentChapterRevisionsPayload {
        val (clientMutationId, ids) = input

        val revisions = review(ids, ChapterRevisionReviewAction.KEEP_CURRENT)

        return KeepCurrentChapterRevisionsPayload(clientMutationId, revisions)
    }

    data class KeepBothChapterRevisionsInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class KeepBothChapterRevisionsPayload(
        val clientMutationId: String?,
        val revisions: List<ChapterRevisionType>,
    )

    /**
     * KEEP_BOTH: the candidate is accepted as historical, archived content while the active revision
     * keeps its place. Only a remotely-confirmed candidate can be accepted.
     */
    @RequireAuth
    fun keepBothChapterRevisions(input: KeepBothChapterRevisionsInput): KeepBothChapterRevisionsPayload {
        val (clientMutationId, ids) = input

        val revisions = review(ids, ChapterRevisionReviewAction.KEEP_BOTH)

        return KeepBothChapterRevisionsPayload(clientMutationId, revisions)
    }

    /** REJECT_CANDIDATE: the candidate is rejected outright. */
    @RequireAuth
    fun rejectChapterRevisionCandidates(input: RejectChapterRevisionCandidatesInput): RejectChapterRevisionCandidatesPayload {
        val (clientMutationId, ids) = input

        val revisions = review(ids, ChapterRevisionReviewAction.REJECT_CANDIDATE)

        return RejectChapterRevisionCandidatesPayload(clientMutationId, revisions)
    }

    data class RejectChapterRevisionCandidatesInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class RejectChapterRevisionCandidatesPayload(
        val clientMutationId: String?,
        val revisions: List<ChapterRevisionType>,
    )

    data class RetryChapterRevisionPublicationsInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class RetryChapterRevisionPublicationsPayload(
        val clientMutationId: String?,
        val revisions: List<ChapterRevisionType>,
    )

    /**
     * Re-queues revisions whose publication failed, preserving their publication attempt count.
     *
     * A revision that is no longer the active one is left alone.
     */
    @RequireAuth
    fun retryChapterRevisionPublications(input: RetryChapterRevisionPublicationsInput): RetryChapterRevisionPublicationsPayload {
        val (clientMutationId, ids) = input

        val revisions = ChapterRevisionPublicationExecutor.retry(ids).map { ChapterRevisionType(it) }

        return RetryChapterRevisionPublicationsPayload(clientMutationId, revisions)
    }

    data class RetryChapterRevisionPruningsInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class RetryChapterRevisionPruningsPayload(
        val clientMutationId: String?,
        val revisions: List<ChapterRevisionType>,
    )

    /**
     * Re-queues revisions whose pruning failed, preserving their attempt count.
     *
     * An already pruned revision is left alone: its payload is gone for good, so it can only come back
     * by being acquired again.
     */
    @RequireAuth
    fun retryChapterRevisionPrunings(input: RetryChapterRevisionPruningsInput): RetryChapterRevisionPruningsPayload {
        val (clientMutationId, ids) = input

        val revisions = ChapterRevisionRetentionExecutor.retry(ids).map { ChapterRevisionType(it) }

        return RetryChapterRevisionPruningsPayload(clientMutationId, revisions)
    }

    /**
     * Applies one review decision and wakes the dependent workers only after the transaction
     * committed, so no worker can observe a half-applied decision.
     */
    private fun review(
        ids: List<Int>,
        action: ChapterRevisionReviewAction,
    ): List<ChapterRevisionType> {
        val revisions = ChapterRevision.review(ids, action)
        if (revisions.isEmpty()) {
            return emptyList()
        }

        when (action) {
            ChapterRevisionReviewAction.ACCEPT_CANDIDATE -> ChapterRevisionPublicationExecutor.notifyWorkAvailable()
            else -> Unit
        }

        // accepting a revision is what moves the previous one out of the active slot, so it is also
        // what can put it outside the retention window
        if (action == ChapterRevisionReviewAction.ACCEPT_CANDIDATE || action == ChapterRevisionReviewAction.KEEP_BOTH) {
            revisions.map { it.chapterKey }.distinct().forEach { ChapterRevisionRetentionExecutor.requestIdentity(it) }
        }

        return revisions.map { ChapterRevisionType(it) }
    }
}
