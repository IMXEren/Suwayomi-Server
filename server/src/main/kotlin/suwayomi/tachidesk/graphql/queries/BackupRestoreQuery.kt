package suwayomi.tachidesk.graphql.queries

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.directives.RequireArchiveOwner
import suwayomi.tachidesk.graphql.types.BackupRestoreAuditType
import suwayomi.tachidesk.graphql.types.BackupRestoreJobType
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreAuditLevel
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobState
import suwayomi.tachidesk.manga.model.table.BackupRestoreJobAuditTable
import suwayomi.tachidesk.manga.model.table.BackupRestoreJobTable
import suwayomi.tachidesk.manga.model.table.toDataClass

/**
 * Read access to durable `.tachibk` restores.
 *
     * Durable jobs belong to the built-in admin's shared archive; other users' restores use upstream's
     * per-user in-memory status instead.
 */
class BackupRestoreQuery {
    /** Bounded because a job reveals the shape of a library and no client needs thousands at once. */
    private val maxPageSize = 200

    /** Restores of this server, newest first by default. */
    @RequireArchiveOwner
    fun backupRestoreJobs(
        state: BackupRestoreJobState? = null,
        limit: Int = 20,
    ): List<BackupRestoreJobType> {
        return transaction {
            val query = BackupRestoreJobTable.selectAll()
            if (state != null) {
                query.andWhere { BackupRestoreJobTable.state eq state.name }
            }

            query
                .orderBy(BackupRestoreJobTable.id to SortOrder.DESC)
                .limit(limit.coerceIn(1, maxPageSize))
                .map { BackupRestoreJobType(BackupRestoreJobTable.toDataClass(it)) }
        }
    }

    /**
     * One restore by the id its upload returned.
     *
     * The same id the legacy status query accepts, so a client can move from the status poll to the
     * durable view without discovering a second identifier.
     */
    @RequireArchiveOwner
    fun backupRestoreJob(
        restoreId: String,
    ): BackupRestoreJobType? {
        return transaction {
            BackupRestoreJobTable
                .selectAll()
                .where { BackupRestoreJobTable.restoreId eq restoreId }
                .firstOrNull()
                ?.let { BackupRestoreJobType(BackupRestoreJobTable.toDataClass(it)) }
        }
    }

    /**
     * What a restore could not apply, oldest first.
     *
     * A missing source and a series the existing handler refused are both reported here instead of
     * aborting the import, so an operator sees exactly which extension or series to look at.
     */
    @RequireArchiveOwner
    fun backupRestoreAudits(
        restoreId: String,
        level: BackupRestoreAuditLevel? = null,
        limit: Int = 100,
    ): List<BackupRestoreAuditType> {
        return transaction {
            val job =
                BackupRestoreJobTable
                    .selectAll()
                    .where { BackupRestoreJobTable.restoreId eq restoreId }
                    .firstOrNull()
                    ?: return@transaction emptyList()

            val jobId = job[BackupRestoreJobTable.id].value
            val query = BackupRestoreJobAuditTable.selectAll().where { BackupRestoreJobAuditTable.job eq jobId }
            if (level != null) {
                query.andWhere { BackupRestoreJobAuditTable.level eq level.name }
            }

            query
                .orderBy(BackupRestoreJobAuditTable.id to SortOrder.ASC)
                .limit(limit.coerceIn(1, maxPageSize))
                .map { BackupRestoreAuditType(BackupRestoreJobAuditTable.toDataClass(it)) }
        }
    }

    /** Counts of one restore's problems, split by level, without loading the rows. */
    @RequireArchiveOwner
    fun backupRestoreErrorCounts(
        restoreId: String,
    ): BackupRestoreErrorCountsType? {
        return transaction {
            val job =
                BackupRestoreJobTable
                    .selectAll()
                    .where { BackupRestoreJobTable.restoreId eq restoreId }
                    .firstOrNull()
                    ?: return@transaction null

            val jobId = job[BackupRestoreJobTable.id].value

            val audits =
                BackupRestoreJobAuditTable
                    .selectAll()
                    .where { BackupRestoreJobAuditTable.job eq jobId }
                    .map { it[BackupRestoreJobAuditTable.level] }

            BackupRestoreErrorCountsType(
                mangaErrors = audits.count { it == BackupRestoreAuditLevel.MANGA_ERROR.name },
                missingSources = audits.count { it == BackupRestoreAuditLevel.MISSING_SOURCE.name },
            )
        }
    }
}

/** How many of each kind of problem a restore recorded. */
data class BackupRestoreErrorCountsType(
    val mangaErrors: Int,
    val missingSources: Int,
)
