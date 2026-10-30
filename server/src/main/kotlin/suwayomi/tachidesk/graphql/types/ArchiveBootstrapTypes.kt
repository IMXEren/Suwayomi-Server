package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapCategoryPolicy
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapItemDataClass
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapItemState
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapProgress
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapSessionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapState
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapUnresolvedSource
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy

/**
 * One archive bootstrap run.
 *
 * Everything the run resolved when it started - the acquisition policies and the pacing settings - is
 * exposed read-only: a client displays what the run will do, it does not rewrite it.
 */
class ArchiveBootstrapSessionType(
    val id: Int,
    val state: ArchiveBootstrapState,
    val defaultPolicy: MangaAcquisitionPolicy,
    val categoryPolicies: List<ArchiveBootstrapCategoryPolicyType>,
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
) {
    constructor(session: ArchiveBootstrapSessionDataClass) : this(
        id = session.id,
        state = session.state,
        defaultPolicy = session.defaultPolicy,
        categoryPolicies = session.categoryPolicies.map { ArchiveBootstrapCategoryPolicyType(it) },
        interItemDelaySeconds = session.interItemDelaySeconds,
        retrySeconds = session.retrySeconds,
        maxAttempts = session.maxAttempts,
        startedAt = session.startedAt,
        updatedAt = session.updatedAt,
        nextItemAt = session.nextItemAt,
        lastItemAt = session.lastItemAt,
        finishedAt = session.finishedAt,
        pausedAt = session.pausedAt,
        cancelledAt = session.cancelledAt,
    )
}

/** One ordered per-category override, effective at the position it is listed in. */
class ArchiveBootstrapCategoryPolicyType(
    val categoryId: Int,
    val policy: MangaAcquisitionPolicy,
) {
    constructor(policy: ArchiveBootstrapCategoryPolicy) : this(policy.categoryId, policy.policy)
}

/**
 * One series of a run.
 *
 * The series, source, title and category snapshots are returned instead of a live relationship: the
 * row is an audit record and deliberately outlives the series or source it was created from.
 */
class ArchiveBootstrapItemType(
    val id: Int,
    val sessionId: Int,
    val mangaId: Int?,
    val sourceId: Long?,
    val title: String,
    val mangaUrl: String?,
    val categoryIds: List<Int>,
    val policy: MangaAcquisitionPolicy,
    val state: ArchiveBootstrapItemState,
    val attempts: Int,
    val lastError: String?,
    val candidateCount: Int?,
    val dueAt: Long?,
    val startedAt: Long?,
    val finishedAt: Long?,
    val updatedAt: Long,
) {
    constructor(item: ArchiveBootstrapItemDataClass) : this(
        id = item.id,
        sessionId = item.sessionId,
        mangaId = item.mangaId,
        sourceId = item.sourceId,
        title = item.title,
        mangaUrl = item.mangaUrl,
        categoryIds = item.categoryIds,
        policy = item.policy,
        state = item.state,
        attempts = item.attempts,
        lastError = item.lastError,
        candidateCount = item.candidateCount,
        dueAt = item.dueAt,
        startedAt = item.startedAt,
        finishedAt = item.finishedAt,
        updatedAt = item.updatedAt,
    )
}

/** Aggregate progress of one run, counted from its series. */
class ArchiveBootstrapProgressType(
    val total: Int,
    val pending: Int,
    val processing: Int,
    val retryWait: Int,
    val complete: Int,
    val failed: Int,
    val unresolvedSource: Int,
    val skipped: Int,
    val cancelled: Int,
    val remaining: Int,
) {
    constructor(progress: ArchiveBootstrapProgress) : this(
        total = progress.total,
        pending = progress.pending,
        processing = progress.processing,
        retryWait = progress.retryWait,
        complete = progress.complete,
        failed = progress.failed,
        unresolvedSource = progress.unresolvedSource,
        skipped = progress.skipped,
        cancelled = progress.cancelled,
        remaining = progress.remaining,
    )
}

/** Series grouped by the source that could not be resolved. */
class ArchiveBootstrapUnresolvedSourceType(
    val sourceId: Long?,
    val mangaCount: Int,
    val sampleTitles: List<String>,
) {
    constructor(source: ArchiveBootstrapUnresolvedSource) : this(
        sourceId = source.sourceId,
        mangaCount = source.mangaCount,
        sampleTitles = source.sampleTitles,
    )
}

class ArchiveBootstrapSessionNodeList(
    val nodes: List<ArchiveBootstrapSessionType>,
    val edges: List<ArchiveBootstrapSessionEdge>,
    val pageInfo: PageInfo,
    val totalCount: Int,
) {
    data class ArchiveBootstrapSessionEdge(
        val cursor: Cursor,
        val node: ArchiveBootstrapSessionType,
    )
}

class ArchiveBootstrapItemNodeList(
    val nodes: List<ArchiveBootstrapItemType>,
    val edges: List<ArchiveBootstrapItemEdge>,
    val pageInfo: PageInfo,
    val totalCount: Int,
) {
    data class ArchiveBootstrapItemEdge(
        val cursor: Cursor,
        val node: ArchiveBootstrapItemType,
    )
}
