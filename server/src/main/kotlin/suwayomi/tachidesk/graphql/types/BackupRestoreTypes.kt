package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreAuditLevel
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreHandoffState
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobAuditDataClass
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobDataClass
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobState
import suwayomi.tachidesk.manga.model.dataclass.BackupRestorePhase

/**
 * One durable restore.
 *
 * The staged payload location, its size and its digest are deliberately absent: a client needs to know
 * where a restore is, not where its bytes are. [progress] and [total] are the series counters the
 * import has actually reached, which is exactly what a resume continues from.
 */
class BackupRestoreJobType(
    val id: Int,
    val restoreId: String,
    val state: BackupRestoreJobState,
    val phase: BackupRestorePhase,
    val progress: Int,
    val total: Int,
    val errorCount: Int,
    val lastError: String?,
    val handoffState: BackupRestoreHandoffState,
    val handoffSessionId: Int?,
    val handoffError: String?,
    /** whether the staged payload is still on disk, which is what makes a retry resumable */
    val stagedPayloadRetained: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val cancelledAt: Long?,
) {
    constructor(job: BackupRestoreJobDataClass) : this(
        id = job.id,
        restoreId = job.restoreId,
        state = job.state,
        phase = job.phase,
        progress = job.mangaIndex,
        total = job.mangaCount,
        errorCount = job.errorCount,
        lastError = job.lastError,
        handoffState = job.handoffState,
        handoffSessionId = job.handoffSessionId,
        handoffError = job.handoffError,
        stagedPayloadRetained = job.stagedFileRetained,
        createdAt = job.createdAt,
        updatedAt = job.updatedAt,
        startedAt = job.startedAt,
        finishedAt = job.finishedAt,
        cancelledAt = job.cancelledAt,
    )
}

/**
 * One isolated problem of a restore.
 *
 * These are the series the existing backup handler could not restore and the sources the backup refers
 * to that are not installed; neither aborts an import of thousands of series, and both have to stay
 * readable afterwards.
 */
class BackupRestoreAuditType(
    val id: Int,
    val level: BackupRestoreAuditLevel,
    val phase: BackupRestorePhase,
    /** position of the series inside the backup, which makes a failure reproducible */
    val mangaIndex: Int?,
    val sourceId: Long?,
    val sourceName: String?,
    val message: String,
    val createdAt: Long,
) {
    constructor(audit: BackupRestoreJobAuditDataClass) : this(
        id = audit.id,
        level = audit.level,
        phase = audit.phase,
        mangaIndex = audit.mangaIndex,
        sourceId = audit.sourceId,
        sourceName = audit.sourceName,
        message = audit.message,
        createdAt = audit.createdAt,
    )
}
