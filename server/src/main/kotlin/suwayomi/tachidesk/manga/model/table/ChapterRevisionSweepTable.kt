package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepItemDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepItemState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepKind
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepScheduleDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepSessionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepSessionState
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.columns.truncatingVarchar

/**
 * Durable revision sweep session.
 *
 * [activeMarker] holds a constant only while the session is [ChapterRevisionSweepSessionState.RUNNING]
 * or [ChapterRevisionSweepSessionState.PAUSED], so its unique index is what enforces "at most one
 * active sweep" in the database rather than in application code. Both H2 and PostgreSQL treat nulls
 * as distinct in a unique index, so every terminal session keeps a null marker and never conflicts.
 */
object ChapterRevisionSweepSessionTable : IntIdTable("chapterrevisionsweepsession") {
    val kind = varchar("kind", 64).default(ChapterRevisionSweepKind.MANUAL_RECENT.name)
    val state = varchar("state", 64).default(ChapterRevisionSweepSessionState.RUNNING.name)
    val activeMarker = varchar("active_marker", 64).nullable().uniqueIndex()

    /** how many newest chapters per series were selected; null when the whole history was selected */
    val newestPerSeries = integer("newest_per_series").nullable()

    // pacing snapshotted at start, so a later settings change cannot alter a live run
    val itemDelaySeconds = long("item_delay_seconds").default(2)
    val retrySeconds = long("retry_seconds").default(300)
    val maxAttempts = integer("max_attempts").default(3)

    /** earliest instant the next chapter of this session may be processed; the global rate limit */
    val nextItemAt = long("next_item_at").nullable()
    val lastItemAt = long("last_item_at").nullable()

    val startedAt = long("started_at")
    val updatedAt = long("updated_at")
    val finishedAt = long("finished_at").nullable()
    val pausedAt = long("paused_at").nullable()
    val cancelledAt = long("cancelled_at").nullable()

    init {
        // the worker claims the oldest session that is currently allowed to run
        index("chapter_revision_sweep_session_claim_idx", false, state, nextItemAt, id)
    }
}

/**
 * One chapter of a sweep session.
 *
 * Every field describing the series, the chapter and the policy is an immutable snapshot rather than
 * a reference, so deleting a series, a chapter or a source row leaves the audit record intact. Only
 * the owning session is a real reference, and it cascades because an item has no meaning without its
 * run.
 */
object ChapterRevisionSweepItemTable : IntIdTable("chapterrevisionsweepitem") {
    val session = reference("session", ChapterRevisionSweepSessionTable, ReferenceOption.CASCADE)

    val mangaId = integer("manga_id").nullable()
    val chapterId = integer("chapter_id").nullable()
    val sourceId = long("source_id").nullable()

    /** the chapter identity the candidate has to be created under */
    val chapterKey = varchar("chapter_key", 64)

    val seriesTitle = truncatingVarchar("series_title", 512)
    val chapterName = truncatingVarchar("chapter_name", 512)
    val sourceChapterUrl = varchar("source_chapter_url", 2048)

    /** the acquisition policy resolved for this chapter when the session started */
    val policy = varchar("policy", 64)

    val state = varchar("state", 64).default(ChapterRevisionSweepItemState.PENDING.name)
    val attempts = integer("attempts").default(0)
    val lastError = varchar("last_error", 1024).nullable()
    val candidateCount = integer("candidate_count").nullable()

    /** earliest instant this chapter may be retried, null while it is due immediately */
    val dueAt = long("due_at").nullable()

    val startedAt = long("started_at").nullable()
    val finishedAt = long("finished_at").nullable()
    val updatedAt = long("updated_at")

    init {
        // the worker claims the earliest claimable chapter of one session, ordered by its own due time
        index("chapter_revision_sweep_item_backlog_idx", false, session, state, dueAt, id)
        // the per-session listing is ordered by id and filtered only by session
        index("chapter_revision_sweep_item_session_idx", false, session, id)
    }
}

/**
 * The single persisted sweep schedule.
 *
 * It is a row rather than a computed instant so that "when is the next sweep due" survives a restart,
 * and so that only the worker which actually started a run can move it forward. The primary key plus
 * the migration's `id = 1` check make a second schedule impossible in the database.
 */
object ChapterRevisionSweepScheduleTable : Table("chapterrevisionsweepschedule") {
    val id = integer("id")
    val nextDueAt = long("next_due_at")
    val lastRunAt = long("last_run_at").nullable()
    val lastSessionId = integer("last_session_id").nullable()

    /**
     * Earliest instant a scheduled occurrence that could not be started may be retried, or null while
     * no retry is pending.
     *
     * It is deliberately a separate column from [nextDueAt]: a failed start must not consume the
     * occurrence, so the due instant stays where it was and only *when* the retry happens is deferred.
     */
    val retryNotBefore = long("retry_not_before").nullable()

    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)

    /** The singleton key of the one schedule row. */
    const val SINGLETON_ID = 1
}

fun ChapterRevisionSweepSessionTable.toDataClass(row: ResultRow) =
    ChapterRevisionSweepSessionDataClass(
        id = row[id].value,
        kind = ChapterRevisionSweepKind.valueOf(row[kind]),
        state = ChapterRevisionSweepSessionState.valueOf(row[state]),
        newestPerSeries = row[newestPerSeries],
        itemDelaySeconds = row[itemDelaySeconds],
        retrySeconds = row[retrySeconds],
        maxAttempts = row[maxAttempts],
        startedAt = row[startedAt],
        updatedAt = row[updatedAt],
        nextItemAt = row[nextItemAt],
        lastItemAt = row[lastItemAt],
        finishedAt = row[finishedAt],
        pausedAt = row[pausedAt],
        cancelledAt = row[cancelledAt],
    )

fun ChapterRevisionSweepItemTable.toDataClass(row: ResultRow) =
    ChapterRevisionSweepItemDataClass(
        id = row[id].value,
        sessionId = row[session].value,
        mangaId = row[mangaId],
        chapterId = row[chapterId],
        sourceId = row[sourceId],
        chapterKey = row[chapterKey],
        seriesTitle = row[seriesTitle],
        chapterName = row[chapterName],
        sourceChapterUrl = row[sourceChapterUrl],
        policy = MangaAcquisitionPolicy.valueOf(row[policy]),
        state = ChapterRevisionSweepItemState.valueOf(row[state]),
        attempts = row[attempts],
        lastError = row[lastError],
        candidateCount = row[candidateCount],
        dueAt = row[dueAt],
        startedAt = row[startedAt],
        finishedAt = row[finishedAt],
        updatedAt = row[updatedAt],
    )

fun ChapterRevisionSweepScheduleTable.toDataClass(row: ResultRow) =
    ChapterRevisionSweepScheduleDataClass(
        nextDueAt = row[nextDueAt],
        lastRunAt = row[lastRunAt],
        lastSessionId = row[lastSessionId],
        retryNotBefore = row[retryNotBefore],
        updatedAt = row[updatedAt],
    )
