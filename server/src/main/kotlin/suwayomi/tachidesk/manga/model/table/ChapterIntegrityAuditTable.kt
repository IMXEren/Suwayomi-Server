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
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditItemDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditItemState
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditKind
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditScheduleDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditSessionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditSessionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionRollbackDataClass
import suwayomi.tachidesk.manga.model.table.columns.truncatingVarchar

/**
 * Durable archive integrity audit session.
 *
 * [activeMarker] holds a constant only while the session is [ChapterIntegrityAuditSessionState.RUNNING]
 * or [ChapterIntegrityAuditSessionState.PAUSED], so its unique index is what enforces "at most one
 * active audit" in the database rather than in application code. Both H2 and PostgreSQL treat nulls
 * as distinct in a unique index, so every terminal session keeps a null marker and never conflicts.
 */
object ChapterIntegrityAuditSessionTable : IntIdTable("chapterintegrityauditsession") {
    val kind = varchar("kind", 64).default(ChapterIntegrityAuditKind.MANUAL_RECENT.name)
    val state = varchar("state", 64).default(ChapterIntegrityAuditSessionState.RUNNING.name)
    val activeMarker = varchar("active_marker", 64).nullable().uniqueIndex()

    /** how many newest revisions per series were selected; null when every revision was selected */
    val newestPerManga = integer("newest_per_manga").nullable()

    // pacing snapshotted at start, so a later settings change cannot alter a live run
    val itemDelaySeconds = long("item_delay_seconds").default(2)
    val retrySeconds = long("retry_seconds").default(300)
    val maxAttempts = integer("max_attempts").default(3)

    /** earliest instant the next revision of this session may be checked; the global remote rate limit */
    val nextItemAt = long("next_item_at").nullable()
    val lastItemAt = long("last_item_at").nullable()

    val startedAt = long("started_at")
    val updatedAt = long("updated_at")
    val finishedAt = long("finished_at").nullable()
    val pausedAt = long("paused_at").nullable()
    val cancelledAt = long("cancelled_at").nullable()

    init {
        // the worker claims the oldest session that is currently allowed to run
        index("chapter_integrity_audit_session_claim_idx", false, state, nextItemAt, id)
    }
}

/**
 * One archived revision inside an audit session.
 *
 * The revision id, its candidate key and the exact artifact identity of both archived objects are
 * plain snapshots rather than references: auditing has to be able to say what it checked even after
 * the revision row was edited or removed, and a payload location that a later edit changed must not
 * silently change what this run verified. Only the owning session is a real reference, and it
 * cascades because an item has no meaning without its run.
 */
object ChapterIntegrityAuditItemTable : IntIdTable("chapterintegrityaudititem") {
    val session = reference("session", ChapterIntegrityAuditSessionTable, ReferenceOption.CASCADE)

    /** the audited revision when the run started; null once that row is gone */
    val revisionId = integer("revision").nullable()
    val chapterKey = varchar("chapter_key", 64)
    val candidateKey = varchar("candidate_key", 64)
    val mangaId = integer("manga_id").nullable()
    val chapterId = integer("chapter_id").nullable()
    val seriesTitle = truncatingVarchar("series_title", 512).nullable()
    val chapterName = truncatingVarchar("chapter_name", 512)

    /** the immutable archived CBZ this run expected on remote storage */
    val cbzPath = varchar("cbz_path", 2048)
    val cbzSha256 = varchar("cbz_sha256", 64)
    val cbzSize = long("cbz_size")

    /** the sidecar manifest that has to be there beside it */
    val manifestPath = varchar("manifest_path", 2048)
    val manifestSha256 = varchar("manifest_sha256", 64)
    val manifestSize = long("manifest_size")

    val state = varchar("state", 64).default(ChapterIntegrityAuditItemState.PENDING.name)
    val attempts = integer("attempts").default(0)
    val lastError = varchar("last_error", 1024).nullable()

    /** earliest instant this revision may be checked again, null while it is due immediately */
    val dueAt = long("due_at").nullable()

    val startedAt = long("started_at").nullable()
    val finishedAt = long("finished_at").nullable()
    val updatedAt = long("updated_at")

    init {
        // the worker claims the earliest claimable revision of one session, ordered by its own due time
        index("chapter_integrity_audit_item_backlog_idx", false, session, state, dueAt, id)
        // the per-session listing is ordered by id and filtered only by session
        index("chapter_integrity_audit_item_session_idx", false, session, id)
    }
}

/**
 * The single persisted audit schedule.
 *
 * It is a row rather than a computed instant so that "when is the next audit due" survives a restart,
 * and so that only the worker which actually started a run can move it forward. The primary key plus
 * the migration's `id = 1` check make a second schedule impossible in the database.
 */
object ChapterIntegrityAuditScheduleTable : Table("chapterintegrityauditschedule") {
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

/**
 * Append-only record of a return to a historical revision.
 *
 * The revision references are plain nullable snapshots, not foreign keys: the decision has to stay
 * readable after the revision it names is removed, and it is a statement about what was active at a
 * point in time rather than a live relationship.
 */
object ChapterRevisionRollbackTable : IntIdTable("chapterrevisionrollback") {
    val chapterKey = varchar("chapter_key", 64)
    val fromRevisionId = integer("from_revision").nullable()
    val toRevisionId = integer("to_revision").nullable()
    val rolledBackAt = long("rolled_back_at")

    init {
        // the history of one chapter identity, newest first
        index("chapter_revision_rollback_identity_idx", false, chapterKey, rolledBackAt, id)
        // the global history, newest first, which is what the paginated audit query walks
        index("chapter_revision_rollback_history_idx", false, rolledBackAt, id)
    }
}

fun ChapterIntegrityAuditSessionTable.toDataClass(row: ResultRow) =
    ChapterIntegrityAuditSessionDataClass(
        id = row[id].value,
        kind = ChapterIntegrityAuditKind.valueOf(row[kind]),
        state = ChapterIntegrityAuditSessionState.valueOf(row[state]),
        newestPerManga = row[newestPerManga],
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

fun ChapterIntegrityAuditItemTable.toDataClass(row: ResultRow) =
    ChapterIntegrityAuditItemDataClass(
        id = row[id].value,
        sessionId = row[session].value,
        revisionId = row[revisionId],
        chapterKey = row[chapterKey],
        candidateKey = row[candidateKey],
        mangaId = row[mangaId],
        chapterId = row[chapterId],
        seriesTitle = row[seriesTitle],
        chapterName = row[chapterName],
        cbzPath = row[cbzPath],
        cbzSha256 = row[cbzSha256],
        cbzSize = row[cbzSize],
        manifestPath = row[manifestPath],
        manifestSha256 = row[manifestSha256],
        manifestSize = row[manifestSize],
        state = ChapterIntegrityAuditItemState.valueOf(row[state]),
        attempts = row[attempts],
        lastError = row[lastError],
        dueAt = row[dueAt],
        startedAt = row[startedAt],
        finishedAt = row[finishedAt],
        updatedAt = row[updatedAt],
    )

fun ChapterIntegrityAuditScheduleTable.toDataClass(row: ResultRow) =
    ChapterIntegrityAuditScheduleDataClass(
        nextDueAt = row[nextDueAt],
        lastRunAt = row[lastRunAt],
        lastSessionId = row[lastSessionId],
        retryNotBefore = row[retryNotBefore],
        updatedAt = row[updatedAt],
    )

fun ChapterRevisionRollbackTable.toDataClass(row: ResultRow) =
    ChapterRevisionRollbackDataClass(
        id = row[id].value,
        chapterKey = row[chapterKey],
        fromRevisionId = row[fromRevisionId],
        toRevisionId = row[toRevisionId],
        rolledBackAt = row[rolledBackAt],
    )
