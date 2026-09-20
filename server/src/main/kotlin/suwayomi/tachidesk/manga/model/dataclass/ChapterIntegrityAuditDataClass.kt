package suwayomi.tachidesk.manga.model.dataclass

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * How an integrity audit was requested.
 *
 * [SCHEDULED] and [MANUAL_RECENT] are the same bounded work - the newest archived revisions of every
 * series - and differ only in who asked for them. [MANUAL_FULL] is the only kind that visits every
 * eligible revision, and it is never reached by default: a full audit issues one remote check per
 * archived revision, so it has to be an explicit request.
 */
enum class ChapterIntegrityAuditKind {
    SCHEDULED,
    MANUAL_RECENT,
    MANUAL_FULL,
    ;

    /** True when the kind visits every eligible revision instead of only the newest ones per series. */
    val isFullHistory: Boolean
        get() = this == MANUAL_FULL
}

/**
 * Lifecycle of one integrity audit.
 *
 * [RUNNING] and [PAUSED] are the only non-terminal states, and at most one session may be in either
 * of them at a time; the unique active marker is what makes that a database invariant.
 */
enum class ChapterIntegrityAuditSessionState {
    RUNNING,
    PAUSED,
    CANCELLED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    ;

    /** True while the session can still check revisions. */
    val isActive: Boolean
        get() = this == RUNNING || this == PAUSED

    /** True once the session can no longer change on its own. */
    val isTerminal: Boolean
        get() = !isActive
}

/**
 * Progress of one archived revision inside an integrity audit.
 *
 * [MISSING] and [CORRUPT] are findings rather than failures: the check itself succeeded and what it
 * found is that the archived payload is not the payload the archive recorded. [FAILED] means the
 * check could not be completed within the attempts the session allows, which is a different fact and
 * is recorded as [ChapterRevisionIntegrityState.AUDIT_FAILED] rather than as a finding.
 */
enum class ChapterIntegrityAuditItemState {
    PENDING,
    CHECKING,
    RETRY_WAIT,
    VERIFIED,
    MISSING,
    CORRUPT,
    FAILED,
    SKIPPED,
    ;

    /** True while the item still has to be picked up by the worker. */
    val isClaimable: Boolean
        get() = this == PENDING || this == RETRY_WAIT

    /** True once the item can never be checked again. */
    val isTerminal: Boolean
        get() = !isClaimable && this != CHECKING

    /** True when the check concluded that the archived payload is not what the archive recorded. */
    val isFinding: Boolean
        get() = this == MISSING || this == CORRUPT
}

/**
 * Integrity of the archived payload of one revision, as established by an integrity audit.
 *
 * This is a dimension of its own, deliberately independent from [ChapterArchiveState]. An audit never
 * downgrades a revision's durability and never deletes anything: it only records what a later direct
 * check of the remote actually saw. A revision that is [REMOTE_CONFIRMED] and later reported [MISSING]
 * was durably archived once - the payload is simply not there any more - and both facts have to stay
 * readable at the same time.
 *
 * [NEVER_AUDITED] is the state every revision starts in, including every revision that existed before
 * audits did: nothing is backfilled, so "never audited" is a durable audit fact of its own and is
 * never confused with a check that is still owed.
 */
enum class ChapterRevisionIntegrityState {
    NEVER_AUDITED,
    VERIFIED,
    MISSING,
    CORRUPT,
    AUDIT_FAILED,
    ;

    /**
     * True when the last completed check found the archived payload unusable.
     *
     * A revision carrying a finding must not be offered as something that can be made visible again,
     * because the bytes such an action would need are known to be missing or wrong.
     */
    val isFinding: Boolean
        get() = this == MISSING || this == CORRUPT
}

/**
 * One integrity audit run.
 *
 * The pacing settings and the selection width are snapshotted here on purpose: editing a setting
 * afterwards must not silently change what an already recorded run is going to do.
 */
data class ChapterIntegrityAuditSessionDataClass(
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
)

/**
 * One archived revision inside an integrity audit.
 *
 * Every field describing the revision and its artifacts is an immutable snapshot rather than a
 * reference, so a later edit of the revision row cannot change what this run checked: the sizes and
 * digests here are the ones the archive recorded when the revision was committed. The revision id and
 * the candidate key are snapshots too - the audit record outlives the row it names.
 */
data class ChapterIntegrityAuditItemDataClass(
    val id: Int,
    val sessionId: Int,
    /** the audited revision at the time the run started, or null once that row is gone */
    val revisionId: Int?,
    val chapterKey: String,
    val candidateKey: String,
    val mangaId: Int?,
    val chapterId: Int?,
    val seriesTitle: String?,
    val chapterName: String,
    /** relative path of the immutable archived CBZ that was expected on remote storage */
    val cbzPath: String,
    val cbzSha256: String,
    val cbzSize: Long,
    /** relative path of the sidecar manifest that was expected beside it */
    val manifestPath: String,
    val manifestSha256: String,
    val manifestSize: Long,
    val state: ChapterIntegrityAuditItemState,
    val attempts: Int,
    val lastError: String?,
    /** earliest instant this revision may be checked again, null while it is due immediately */
    val dueAt: Long?,
    val startedAt: Long?,
    val finishedAt: Long?,
    val updatedAt: Long,
)

/** Aggregated progress of one audit, counted from the items themselves. */
data class ChapterIntegrityAuditProgress(
    val total: Int,
    val pending: Int,
    val checking: Int,
    val retryWait: Int,
    val verified: Int,
    val missing: Int,
    val corrupt: Int,
    val failed: Int,
    val skipped: Int,
) {
    /** Items that still have to be picked up or are being checked right now. */
    val remaining: Int
        get() = pending + checking + retryWait

    /** Items whose check found the archived payload missing or wrong. */
    val findings: Int
        get() = missing + corrupt
}

/**
 * The single persisted audit schedule.
 *
 * [nextDueAt] is stored rather than derived so a restart cannot invent an audit that was never due,
 * and so the worker that started a run is the only thing that can move it forward.
 *
 * [retryNotBefore] is the backoff of an occurrence that could not even be started. It is separate from
 * [nextDueAt] on purpose: a failed start defers the retry without consuming the occurrence, so the
 * audit it owed is still owed once the backoff has passed.
 */
data class ChapterIntegrityAuditScheduleDataClass(
    val nextDueAt: Long,
    val lastRunAt: Long?,
    val lastSessionId: Int?,
    val retryNotBefore: Long?,
    val updatedAt: Long,
)

/**
 * One recorded return to a historical revision.
 *
 * Kept as an append-only audit row rather than as a column on the revision, because the same revision
 * can be rolled back to more than once and each of those decisions has to stay readable.
 */
data class ChapterRevisionRollbackDataClass(
    val id: Int,
    /** immutable identity of the chapter the decision was about */
    val chapterKey: String,
    /** the revision that was active before the decision, or null when the identity had none */
    val fromRevisionId: Int?,
    /** the revision that became active again */
    val toRevisionId: Int?,
    val rolledBackAt: Long,
)
