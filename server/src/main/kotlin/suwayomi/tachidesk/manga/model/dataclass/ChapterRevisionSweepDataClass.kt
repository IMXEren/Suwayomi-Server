package suwayomi.tachidesk.manga.model.dataclass

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * How a revision sweep was requested.
 *
 * [SCHEDULED] and [MANUAL_RECENT] are the same bounded work - the newest chapters of every tracked
 * series - and differ only in who asked for them. [MANUAL_FULL] is the only kind that selects the
 * whole chapter history, and it is never reached by default: a full sweep of a large library
 * re-downloads tens of thousands of chapters, so it has to be an explicit request.
 */
enum class ChapterRevisionSweepKind {
    SCHEDULED,
    MANUAL_RECENT,
    MANUAL_FULL,
    ;

    /** True when the kind selects every chapter of a series instead of only its newest ones. */
    val isFullHistory: Boolean
        get() = this == MANUAL_FULL
}

/**
 * Lifecycle of one revision sweep.
 *
 * [RUNNING] and [PAUSED] are the only non-terminal states, and at most one session may be in either
 * of them at a time; the unique active marker is what makes that a database invariant.
 */
enum class ChapterRevisionSweepSessionState {
    RUNNING,
    PAUSED,
    CANCELLED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    ;

    /** True while the session can still process items. */
    val isActive: Boolean
        get() = this == RUNNING || this == PAUSED

    /** True once the session can no longer change on its own. */
    val isTerminal: Boolean
        get() = !isActive
}

/**
 * Progress of one chapter inside a sweep session.
 *
 * [SKIPPED] is its own outcome rather than a success: a paused series and a chapter that vanished
 * from the library are deliberately not the same as a chapter that was actually swept.
 */
enum class ChapterRevisionSweepItemState {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    COMPLETE,
    SKIPPED,
    FAILED,
    CANCELLED,
    ;

    /** True while the item still has to be picked up by the worker. */
    val isClaimable: Boolean
        get() = this == PENDING || this == RETRY_WAIT

    /** True once the item can never be processed again. */
    val isTerminal: Boolean
        get() = !isClaimable && this != PROCESSING
}

/**
 * Result of comparing an acquired revision against the currently active revision of its chapter.
 *
 * This is an independent dimension on purpose. It records *why* a revision is or is not interesting
 * without replacing any lifecycle state: an [EXACT_MATCH] is still a fully acquired revision whose
 * bytes were validated, it simply does not need to be archived because the archive already holds
 * exactly those bytes.
 */
enum class ChapterRevisionComparisonState {
    /**
     * The revision predates content comparison, so none was ever attempted for it.
     *
     * This is what a row that already existed when the comparison dimension was introduced carries.
     * It is deliberately distinct from [PENDING]: "never analysed" is a durable audit fact an operator
     * must not confuse with a comparison that is still owed, and only [PENDING] means the analysis is
     * outstanding. Every candidate recorded after the dimension exists starts at [PENDING].
     */
    NOT_EVALUATED,

    /** no classification was attempted yet */
    PENDING,

    /** nothing was active for this chapter, so there was nothing to compare against */
    NO_BASELINE,

    /** byte-identical content to the active revision: the re-release carries no new content */
    EXACT_MATCH,

    /** the content differs from the active revision */
    CONTENT_CHANGED,

    /** the comparison itself could not be completed */
    ANALYSIS_FAILED,
}

/**
 * One sweep run over the library.
 *
 * The pacing settings and the selection width are snapshotted here on purpose: editing a setting
 * afterwards must not silently change what an already recorded run is going to do.
 */
data class ChapterRevisionSweepSessionDataClass(
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
)

/**
 * One chapter inside a sweep session.
 *
 * `mangaId`, `chapterId`, `sourceId`, `chapterKey`, the titles and the source URL are plain
 * snapshots rather than foreign keys, so deleting a series, a chapter or a source row can never
 * erase the audit record of what the run visited.
 */
data class ChapterRevisionSweepItemDataClass(
    val id: Int,
    val sessionId: Int,
    val mangaId: Int?,
    val chapterId: Int?,
    val sourceId: Long?,
    val chapterKey: String,
    val seriesTitle: String,
    val chapterName: String,
    val sourceChapterUrl: String,
    /** the acquisition policy resolved for this chapter when the session started */
    val policy: MangaAcquisitionPolicy,
    val state: ChapterRevisionSweepItemState,
    val attempts: Int,
    val lastError: String?,
    val candidateCount: Int?,
    val dueAt: Long?,
    val startedAt: Long?,
    val finishedAt: Long?,
    val updatedAt: Long,
)

/** Aggregated progress of one session, counted from the items themselves. */
data class ChapterRevisionSweepProgress(
    val total: Int,
    val pending: Int,
    val processing: Int,
    val retryWait: Int,
    val complete: Int,
    val skipped: Int,
    val failed: Int,
    val cancelled: Int,
) {
    /** Items that still have to be picked up. */
    val remaining: Int
        get() = pending + processing + retryWait
}

/**
 * The single persisted sweep schedule.
 *
 * [nextDueAt] is stored rather than derived so a restart cannot invent a sweep that was never due,
 * and so the worker that started a run is the only thing that can move it forward.
 *
 * [retryNotBefore] is the backoff of an occurrence that could not even be started. It is separate from
 * [nextDueAt] on purpose: a failed start defers the retry without consuming the occurrence, so the
 * sweep it owed is still owed once the backoff has passed.
 */
data class ChapterRevisionSweepScheduleDataClass(
    val nextDueAt: Long,
    val lastRunAt: Long?,
    val lastSessionId: Int?,
    val retryNotBefore: Long?,
    val updatedAt: Long,
)
