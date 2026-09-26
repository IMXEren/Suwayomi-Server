package suwayomi.tachidesk.manga.model.dataclass

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Lifecycle of one archive bootstrap run.
 *
 * A bootstrap is a one-shot, resumable backfill of an already imported library: it applies the
 * chosen acquisition policy, refreshes each series once and records its initial archive candidates.
 * [RUNNING] and [PAUSED] are the only non-terminal states, and at most one session may be in either
 * of them at a time.
 */
enum class ArchiveBootstrapState {
    RUNNING,
    PAUSED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    CANCELLED,
    ;

    /** True while the session can still process items. */
    val isActive: Boolean
        get() = this == RUNNING || this == PAUSED

    /** True once the session can no longer change on its own. */
    val isTerminal: Boolean
        get() = !isActive
}

/**
 * Progress of one series inside a bootstrap session.
 *
 * [UNRESOLVED_SOURCE] is deliberately its own outcome rather than a failure: a source that is not
 * installed must not stop the rest of a 3,435 series backfill, but it also must not look like a
 * series that was archived successfully.
 */
enum class ArchiveBootstrapItemState {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    COMPLETE,
    FAILED,
    UNRESOLVED_SOURCE,
    SKIPPED,
    CANCELLED,
    ;

    /** True while the item still has to be picked up by the worker. */
    val isClaimable: Boolean
        get() = this == PENDING || this == RETRY_WAIT

    /** True once the item can never be processed again. */
    val isTerminal: Boolean
        get() = !isClaimable && this != PROCESSING
}

/** One ordered per-category acquisition policy override, snapshotted when the session started. */
data class ArchiveBootstrapCategoryPolicy(
    val categoryId: Int,
    val policy: MangaAcquisitionPolicy,
)

/**
 * Canonical encoding of the ordered category overrides.
 *
 * The order is significant - the first override that matches a series wins, so a series in several
 * categories gets the policy of the earliest listed one - and the encoding therefore preserves it
 * instead of sorting. The format is a compact comma separated `id:POLICY` list, which keeps the
 * persisted snapshot readable in a database dump and never depends on a serializer version.
 */
object ArchiveBootstrapCategoryPolicies {
    fun encode(policies: List<ArchiveBootstrapCategoryPolicy>): String =
        policies.distinctBy { it.categoryId }.joinToString(",") { "${it.categoryId}:${it.policy.name}" }

    fun decode(value: String): List<ArchiveBootstrapCategoryPolicy> =
        value
            .takeIf { it.isNotBlank() }
            ?.split(',')
            ?.mapNotNull { entry ->
                val categoryId = entry.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
                val policy =
                    runCatching { MangaAcquisitionPolicy.valueOf(entry.substringAfter(':')) }.getOrNull()
                        ?: return@mapNotNull null
                ArchiveBootstrapCategoryPolicy(categoryId, policy)
            }.orEmpty()
}

/**
 * One bootstrap run over the library.
 *
 * The policy settings and the category overrides are snapshotted here on purpose: editing a
 * category or a global setting afterwards must not silently change what an already recorded run is
 * going to do.
 */
data class ArchiveBootstrapSessionDataClass(
    val id: Int,
    val state: ArchiveBootstrapState,
    /**
     * The restore this run was started for, or null for a run an operator started directly.
     *
     * Deliberately internal: it is how a resumed restore recognises its own run, and it is never
     * exposed through GraphQL.
     */
    val originKey: String? = null,
    val defaultPolicy: MangaAcquisitionPolicy,
    val categoryPolicies: List<ArchiveBootstrapCategoryPolicy>,
    val interItemDelaySeconds: Long,
    val retrySeconds: Long,
    val maxAttempts: Int,
    val startedAt: Long,
    val updatedAt: Long,
    val nextItemAt: Long?,
    val lastItemAt: Long?,
    val finishedAt: Long?,
    val pausedAt: Long?,
    val cancelledAt: Long?,
)

/**
 * One series inside a bootstrap session.
 *
 * [mangaId] and [sourceId] are plain snapshots rather than foreign keys so that deleting a series
 * or a source row can never erase the audit record of what the run did.
 */
data class ArchiveBootstrapItemDataClass(
    val id: Int,
    val sessionId: Int,
    val mangaId: Int?,
    val sourceId: Long?,
    val title: String,
    val mangaUrl: String?,
    val categoryIds: List<Int>,
    /** the acquisition policy resolved for this series when the session started */
    val policy: MangaAcquisitionPolicy,
    val state: ArchiveBootstrapItemState,
    val attempts: Int,
    val lastError: String?,
    val candidateCount: Int?,
    val dueAt: Long?,
    val startedAt: Long?,
    val finishedAt: Long?,
    val updatedAt: Long,
)

/** Aggregated progress of one session, counted from the items themselves. */
data class ArchiveBootstrapProgress(
    val total: Int,
    val pending: Int,
    val processing: Int,
    val retryWait: Int,
    val complete: Int,
    val failed: Int,
    val unresolvedSource: Int,
    val skipped: Int,
    val cancelled: Int,
) {
    /** Items that still have to be picked up. */
    val remaining: Int
        get() = pending + processing + retryWait
}

/**
 * Series that a bootstrap could not process because their source is not available.
 *
 * Reported per source so an operator sees which extension has to be installed instead of scrolling
 * through thousands of per-series failures.
 */
data class ArchiveBootstrapUnresolvedSource(
    val sourceId: Long?,
    val mangaCount: Int,
    val sampleTitles: List<String>,
)
