package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepItemDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepItemState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepKind
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepProgress
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepScheduleDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepSessionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepSessionState
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy

/**
 * One revision sweep run.
 *
 * Everything the run resolved when it started - the selection width and the pacing settings - is
 * exposed read-only: a client displays what the run will do, it does not rewrite it.
 */
class ChapterRevisionSweepSessionType(
    val id: Int,
    val kind: ChapterRevisionSweepKind,
    val state: ChapterRevisionSweepSessionState,
    /** how many newest chapters per series were selected; null when the whole history was selected */
    val newestPerSeries: Int?,
    val itemDelaySeconds: Long,
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
    constructor(session: ChapterRevisionSweepSessionDataClass) : this(
        id = session.id,
        kind = session.kind,
        state = session.state,
        newestPerSeries = session.newestPerSeries,
        itemDelaySeconds = session.itemDelaySeconds,
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

/**
 * One chapter of a run.
 *
 * The series, chapter and policy snapshots are returned instead of a live relationship: the row is an
 * audit record and deliberately outlives the series, chapter or source it was created from.
 */
class ChapterRevisionSweepItemType(
    val id: Int,
    val sessionId: Int,
    val mangaId: Int?,
    val chapterId: Int?,
    val sourceId: Long?,
    val chapterKey: String,
    val seriesTitle: String,
    val chapterName: String,
    val sourceChapterUrl: String,
    val policy: MangaAcquisitionPolicy,
    val state: ChapterRevisionSweepItemState,
    val attempts: Int,
    val lastError: String?,
    val candidateCount: Int?,
    val dueAt: Long?,
    val startedAt: Long?,
    val finishedAt: Long?,
    val updatedAt: Long,
) {
    constructor(item: ChapterRevisionSweepItemDataClass) : this(
        id = item.id,
        sessionId = item.sessionId,
        mangaId = item.mangaId,
        chapterId = item.chapterId,
        sourceId = item.sourceId,
        chapterKey = item.chapterKey,
        seriesTitle = item.seriesTitle,
        chapterName = item.chapterName,
        sourceChapterUrl = item.sourceChapterUrl,
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

/** Aggregate progress of one run, counted from its chapters. */
class ChapterRevisionSweepProgressType(
    val total: Int,
    val pending: Int,
    val processing: Int,
    val retryWait: Int,
    val complete: Int,
    val skipped: Int,
    val failed: Int,
    val cancelled: Int,
    val remaining: Int,
) {
    constructor(progress: ChapterRevisionSweepProgress) : this(
        total = progress.total,
        pending = progress.pending,
        processing = progress.processing,
        retryWait = progress.retryWait,
        complete = progress.complete,
        skipped = progress.skipped,
        failed = progress.failed,
        cancelled = progress.cancelled,
        remaining = progress.remaining,
    )
}

/** The single persisted schedule: when the next automatic sweep is due. */
class ChapterRevisionSweepScheduleType(
    val nextDueAt: Long,
    val lastRunAt: Long?,
    val lastSessionId: Int?,
    val updatedAt: Long,
) {
    constructor(schedule: ChapterRevisionSweepScheduleDataClass) : this(
        nextDueAt = schedule.nextDueAt,
        lastRunAt = schedule.lastRunAt,
        lastSessionId = schedule.lastSessionId,
        updatedAt = schedule.updatedAt,
    )
}

class ChapterRevisionSweepSessionNodeList(
    val nodes: List<ChapterRevisionSweepSessionType>,
    val edges: List<ChapterRevisionSweepSessionEdge>,
    val pageInfo: PageInfo,
    val totalCount: Int,
) {
    data class ChapterRevisionSweepSessionEdge(
        val cursor: Cursor,
        val node: ChapterRevisionSweepSessionType,
    )
}

class ChapterRevisionSweepItemNodeList(
    val nodes: List<ChapterRevisionSweepItemType>,
    val edges: List<ChapterRevisionSweepItemEdge>,
    val pageInfo: PageInfo,
    val totalCount: Int,
) {
    data class ChapterRevisionSweepItemEdge(
        val cursor: Cursor,
        val node: ChapterRevisionSweepItemType,
    )
}
