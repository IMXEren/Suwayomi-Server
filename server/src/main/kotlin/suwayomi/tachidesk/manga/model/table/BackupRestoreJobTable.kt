package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreAuditLevel
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreCategoryMapping
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreCategoryPolicies
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreFlagsCodec
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreHandoffState
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobAuditDataClass
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobDataClass
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobState
import suwayomi.tachidesk.manga.model.dataclass.BackupRestorePhase
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.columns.truncatingVarchar

/**
 * One durable protobuf backup restore.
 *
 * [stagedRelativePath] is deliberately relative: the staging root is configuration and may move
 * between restarts, and a stored absolute path would be both wrong afterwards and a way to point a
 * restore at an arbitrary file. [stagedSize] and [stagedSha256] are what make a resume honest - the
 * worker re-proves them before it continues.
 */
object BackupRestoreJobTable : IntIdTable("backuprestorejob") {
    /** the public identifier of the job; also the staged file name */
    val restoreId = varchar("restore_id", 64)

    val state = varchar("state", 64).default(BackupRestoreJobState.QUEUED.name)
    val phase = varchar("phase", 64).default(BackupRestorePhase.PENDING.name)

    val stagedRelativePath = varchar("staged_relative_path", 1024)
    val stagedSize = long("staged_size")
    val stagedSha256 = varchar("staged_sha256", 64)

    /** compact bit string; see [BackupRestoreFlagsCodec] */
    val flags = varchar("flags", 64)

    /** compact `backupOrder:categoryId` list; see [BackupRestoreCategoryMapping] */
    val categoryMapping = varchar("category_mapping", 4096).default("")

    val mangaIndex = integer("manga_index").default(0)
    val mangaCount = integer("manga_count").default(0)
    val errorCount = integer("error_count").default(0)
    val lastError = truncatingVarchar("last_error", 1024).nullable()

    val handoffState = varchar("handoff_state", 64).default(BackupRestoreHandoffState.NONE.name)
    val handoffDefaultPolicy = varchar("handoff_default_policy", 64).nullable()

    /** JSON array of name/policy pairs; see [BackupRestoreCategoryPolicies] */
    val handoffCategoryOverrides = text("handoff_category_overrides").default("")
    val handoffSessionId = integer("handoff_session_id").nullable()
    val handoffError = truncatingVarchar("handoff_error", 1024).nullable()

    /** set once the staged payload was deleted; null means a retry can still resume from it */
    val stagedDeletedAt = long("staged_deleted_at").nullable()

    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val startedAt = long("started_at").nullable()
    val finishedAt = long("finished_at").nullable()
    val cancelledAt = long("cancelled_at").nullable()

    init {
        index("backup_restore_job_restore_id_idx", true, restoreId)
        // the worker claims the oldest queued job
        index("backup_restore_job_claim_idx", false, state, id)
    }
}

/**
 * The audit trail of one restore.
 *
 * An isolated series failure and an uninstalled source are recorded here instead of aborting a
 * 3,435 series import. The row cascades with its job because an audit row has no meaning without the
 * restore it describes.
 */
object BackupRestoreJobAuditTable : IntIdTable("backuprestorejobaudit") {
    val job = reference("job", BackupRestoreJobTable, ReferenceOption.CASCADE)

    val level = varchar("level", 64).default(BackupRestoreAuditLevel.MANGA_ERROR.name)
    val phase = varchar("phase", 64)

    /** position of the series inside the backup, so an audit row is reproducible */
    val mangaIndex = integer("manga_index").nullable()
    val sourceId = long("source_id").nullable()
    val sourceName = truncatingVarchar("source_name", 256).nullable()

    /** bounded on purpose: it is exposed through authenticated GraphQL */
    val message = truncatingVarchar("message", 1024)

    /**
     * Deterministic identity of the report inside its job, e.g. `MANGA:42` or `SOURCE:17`.
     *
     * Together with the unique index below it is what makes re-recording a report idempotent: an
     * interrupted restore that reprocesses a series overwrites the same row instead of adding a
     * second one, so a report and the error count can never drift apart.
     */
    val auditKey = varchar("audit_key", 128).default("")

    val createdAt = long("created_at")

    init {
        index("backup_restore_job_audit_job_idx", false, job, id)
        index("backup_restore_job_audit_key_idx", true, job, auditKey)
    }
}

fun BackupRestoreJobTable.toDataClass(row: ResultRow) =
    BackupRestoreJobDataClass(
        id = row[id].value,
        restoreId = row[restoreId],
        state = BackupRestoreJobState.valueOf(row[state]),
        phase = BackupRestorePhase.valueOf(row[phase]),
        stagedRelativePath = row[stagedRelativePath],
        stagedSize = row[stagedSize],
        stagedSha256 = row[stagedSha256],
        flags = BackupRestoreFlagsCodec.decode(row[flags]),
        categoryMapping = BackupRestoreCategoryMapping.decode(row[categoryMapping]),
        mangaIndex = row[mangaIndex],
        mangaCount = row[mangaCount],
        errorCount = row[errorCount],
        lastError = row[lastError],
        handoffState = BackupRestoreHandoffState.valueOf(row[handoffState]),
        handoffDefaultPolicy = row[handoffDefaultPolicy]?.let { MangaAcquisitionPolicy.valueOf(it) },
        handoffCategoryOverrides = BackupRestoreCategoryPolicies.decode(row[handoffCategoryOverrides]),
        handoffSessionId = row[handoffSessionId],
        handoffError = row[handoffError],
        stagedDeletedAt = row[stagedDeletedAt],
        createdAt = row[createdAt],
        updatedAt = row[updatedAt],
        startedAt = row[startedAt],
        finishedAt = row[finishedAt],
        cancelledAt = row[cancelledAt],
    )

fun BackupRestoreJobAuditTable.toDataClass(row: ResultRow) =
    BackupRestoreJobAuditDataClass(
        id = row[id].value,
        jobId = row[job].value,
        level = BackupRestoreAuditLevel.valueOf(row[level]),
        phase = BackupRestorePhase.valueOf(row[phase]),
        mangaIndex = row[mangaIndex],
        sourceId = row[sourceId],
        sourceName = row[sourceName],
        message = row[message],
        auditKey = row[auditKey],
        createdAt = row[createdAt],
    )
