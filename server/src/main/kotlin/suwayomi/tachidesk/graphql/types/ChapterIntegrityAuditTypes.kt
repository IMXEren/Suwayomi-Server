package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditItemDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditItemState
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditKind
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditProgress
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditScheduleDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditSessionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditSessionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionRollbackDataClass

/**
 * One archive integrity audit run.
 *
 * Everything the run resolved when it started - the selection width and the pacing settings - is
 * exposed read-only: a client displays what the run will do, it does not rewrite it.
 */
class ChapterIntegrityAuditSessionType(
    val id: Int,
    val kind: ChapterIntegrityAuditKind,
    val state: ChapterIntegrityAuditSessionState,
    /** how many newest revisions per series were selected; null when every revision was selected */
    val newestPerManga: Int?,
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
    constructor(session: ChapterIntegrityAuditSessionDataClass) : this(
        id = session.id,
        kind = session.kind,
        state = session.state,
        newestPerManga = session.newestPerManga,
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
 * One archived revision inside a run.
 *
 * The series, chapter and artifact snapshots are returned instead of a live relationship: the row is an
 * audit record and deliberately outlives the revision, series or chapter it was created from. The
 * artifact paths and digests themselves are never exposed - a client is told whether the payload was
 * where the archive recorded it, not where that is.
 */
class ChapterIntegrityAuditItemType(
    val id: Int,
    val sessionId: Int,
    val revisionId: Int?,
    val chapterKey: String,
    val candidateKey: String,
    val mangaId: Int?,
    val chapterId: Int?,
    val seriesTitle: String?,
    val chapterName: String,
    val state: ChapterIntegrityAuditItemState,
    val attempts: Int,
    val lastError: String?,
    val dueAt: Long?,
    val startedAt: Long?,
    val finishedAt: Long?,
    val updatedAt: Long,
) {
    constructor(item: ChapterIntegrityAuditItemDataClass) : this(
        id = item.id,
        sessionId = item.sessionId,
        revisionId = item.revisionId,
        chapterKey = item.chapterKey,
        candidateKey = item.candidateKey,
        mangaId = item.mangaId,
        chapterId = item.chapterId,
        seriesTitle = item.seriesTitle,
        chapterName = item.chapterName,
        state = item.state,
        attempts = item.attempts,
        lastError = item.lastError,
        dueAt = item.dueAt,
        startedAt = item.startedAt,
        finishedAt = item.finishedAt,
        updatedAt = item.updatedAt,
    )
}

/** Aggregate progress of one run, counted from its revisions. */
class ChapterIntegrityAuditProgressType(
    val total: Int,
    val pending: Int,
    val checking: Int,
    val retryWait: Int,
    val verified: Int,
    val missing: Int,
    val corrupt: Int,
    val failed: Int,
    val skipped: Int,
    val remaining: Int,
    /** revisions the run found to be missing or wrong */
    val findings: Int,
) {
    constructor(progress: ChapterIntegrityAuditProgress) : this(
        total = progress.total,
        pending = progress.pending,
        checking = progress.checking,
        retryWait = progress.retryWait,
        verified = progress.verified,
        missing = progress.missing,
        corrupt = progress.corrupt,
        failed = progress.failed,
        skipped = progress.skipped,
        remaining = progress.remaining,
        findings = progress.findings,
    )
}

/** The single persisted schedule: when the next automatic audit is due. */
class ChapterIntegrityAuditScheduleType(
    val nextDueAt: Long,
    val lastRunAt: Long?,
    val lastSessionId: Int?,
    val updatedAt: Long,
) {
    constructor(schedule: ChapterIntegrityAuditScheduleDataClass) : this(
        nextDueAt = schedule.nextDueAt,
        lastRunAt = schedule.lastRunAt,
        lastSessionId = schedule.lastSessionId,
        updatedAt = schedule.updatedAt,
    )
}

/**
 * One recorded return to a historical revision.
 *
 * The decisions are append-only, so the same revision can appear as a target more than once and every
 * one of those decisions keeps its own row and instant.
 */
class ChapterRevisionRollbackType(
    val id: Int,
    val chapterKey: String,
    /** the revision that was active before the decision, null when the identity had none */
    val fromRevisionId: Int?,
    /** the revision that became active again */
    val toRevisionId: Int?,
    val rolledBackAt: Long,
) {
    constructor(event: ChapterRevisionRollbackDataClass) : this(
        id = event.id,
        chapterKey = event.chapterKey,
        fromRevisionId = event.fromRevisionId,
        toRevisionId = event.toRevisionId,
        rolledBackAt = event.rolledBackAt,
    )
}

class ChapterIntegrityAuditSessionNodeList(
    val nodes: List<ChapterIntegrityAuditSessionType>,
    val edges: List<ChapterIntegrityAuditSessionEdge>,
    val pageInfo: PageInfo,
    val totalCount: Int,
) {
    data class ChapterIntegrityAuditSessionEdge(
        val cursor: Cursor,
        val node: ChapterIntegrityAuditSessionType,
    )
}

class ChapterIntegrityAuditItemNodeList(
    val nodes: List<ChapterIntegrityAuditItemType>,
    val edges: List<ChapterIntegrityAuditItemEdge>,
    val pageInfo: PageInfo,
    val totalCount: Int,
) {
    data class ChapterIntegrityAuditItemEdge(
        val cursor: Cursor,
        val node: ChapterIntegrityAuditItemType,
    )
}

class ChapterRevisionRollbackNodeList(
    val nodes: List<ChapterRevisionRollbackType>,
    val edges: List<ChapterRevisionRollbackEdge>,
    val pageInfo: PageInfo,
    val totalCount: Int,
) {
    data class ChapterRevisionRollbackEdge(
        val cursor: Cursor,
        val node: ChapterRevisionRollbackType,
    )
}
