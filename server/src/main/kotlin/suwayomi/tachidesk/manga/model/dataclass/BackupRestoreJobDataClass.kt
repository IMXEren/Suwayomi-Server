package suwayomi.tachidesk.manga.model.dataclass

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import suwayomi.tachidesk.manga.impl.backup.BackupFlags

/**
 * Lifecycle of one durable protobuf backup restore.
 *
 * [QUEUED] and [RUNNING] are the only states the background worker still has to act on; everything
 * else is terminal and only an explicit retry moves a job back out of it.
 */
enum class BackupRestoreJobState {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILURE,
    CANCELLED,
    ;

    /** True while the worker still has to pick the job up. */
    val isClaimable: Boolean
        get() = this == QUEUED

    /** True once the job can no longer change on its own. */
    val isTerminal: Boolean
        get() = !isClaimable && this != RUNNING
}

/**
 * How far a restore has progressed.
 *
 * The phase is written *before* the side effects of that phase run, so a crash in the middle of a
 * phase resumes by redoing the phase: [SETTINGS], [CATEGORIES] and [META] are idempotent, and
 * [MANGA] resumes from [BackupRestoreJobDataClass.mangaIndex].
 */
enum class BackupRestorePhase {
    PENDING,
    SETTINGS,
    CATEGORIES,
    META,
    MANGA,
    COMPLETED,
}

/**
 * Outcome of the optional archive-bootstrap handoff that follows a successful restore.
 *
 * [PENDING] means a handoff was requested and not attempted yet. A [BLOCKED] restore is still a
 * successful restore - the library was imported - it only could not start its bootstrap because
 * another run was active, which an explicit retry resolves without importing anything again.
 */
enum class BackupRestoreHandoffState {
    NONE,
    PENDING,
    STARTED,
    BLOCKED,
    FAILED,
}

/** What an audit row records. */
enum class BackupRestoreAuditLevel {
    /** one series the existing backup handler could not restore; the import continues past it */
    MANGA_ERROR,

    /** a source id in the backup that is not installed; reported, never fatal */
    MISSING_SOURCE,
}

/**
 * One ordered per-category-name handoff override.
 *
 * The handoff is expressed by category *name* rather than id because the categories it refers to may
 * not exist yet when the restore is requested; the name is resolved against the restored categories
 * once the import succeeded.
 */
@Serializable
data class BackupRestoreCategoryPolicy(
    val categoryName: String,
    val policy: MangaAcquisitionPolicy,
)

/**
 * Canonical encoding of the ordered handoff overrides.
 *
 * Serialized as JSON rather than a delimited string because a category name may contain the
 * separators any hand-rolled format would need. The order is significant - the first override
 * matching a series wins - and is preserved.
 */
object BackupRestoreCategoryPolicies {
    private val json = Json { encodeDefaults = true }

    fun encode(policies: List<BackupRestoreCategoryPolicy>): String = json.encodeToString(policies.distinctBy { it.categoryName })

    fun decode(value: String): List<BackupRestoreCategoryPolicy> =
        value
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<BackupRestoreCategoryPolicy>>(it) }.getOrNull() }
            .orEmpty()
}

/**
 * Compact, version-independent encoding of the restore's [BackupFlags].
 *
 * A seven character bit string in declaration order. Deliberately not a serialized object: the flag
 * set is small, fixed and read back by a database dump as easily as by this process.
 */
object BackupRestoreFlagsCodec {
    fun encode(flags: BackupFlags): String =
        buildString {
            append(if (flags.includeManga) '1' else '0')
            append(if (flags.includeCategories) '1' else '0')
            append(if (flags.includeChapters) '1' else '0')
            append(if (flags.includeTracking) '1' else '0')
            append(if (flags.includeHistory) '1' else '0')
            append(if (flags.includeClientData) '1' else '0')
            append(if (flags.includeServerSettings) '1' else '0')
        }

    fun decode(value: String): BackupFlags =
        BackupFlags(
            includeManga = value.getOrNull(0) == '1',
            includeCategories = value.getOrNull(1) == '1',
            includeChapters = value.getOrNull(2) == '1',
            includeTracking = value.getOrNull(3) == '1',
            includeHistory = value.getOrNull(4) == '1',
            includeClientData = value.getOrNull(5) == '1',
            includeServerSettings = value.getOrNull(6) == '1',
        )
}

/**
 * Canonical encoding of the backup-category-order to database-category-id mapping.
 *
 * Persisted with the job so a resume at the MANGA phase does not have to re-run the category import
 * just to learn the mapping the series restore needs. The backup order is the key because that is
 * what the existing backup format uses to reference a category.
 */
object BackupRestoreCategoryMapping {
    fun encode(mapping: Map<Int, Int>): String = mapping.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    fun decode(value: String): Map<Int, Int> =
        value
            .takeIf { it.isNotBlank() }
            ?.split(',')
            ?.mapNotNull { entry ->
                val order = entry.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
                val categoryId = entry.substringAfter(':').toIntOrNull() ?: return@mapNotNull null
                order to categoryId
            }?.toMap()
            .orEmpty()
}

/**
 * One durable restore of a protobuf backup.
 *
 * [stagedRelativePath] is relative to the staging root and is the only pointer to the payload: the
 * absolute location, the file hash and the file size let a restart prove that the bytes it is about
 * to resume from are exactly the bytes the job was created from.
 */
data class BackupRestoreJobDataClass(
    val id: Int,
    val restoreId: String,
    val state: BackupRestoreJobState,
    val phase: BackupRestorePhase,
    val stagedRelativePath: String,
    val stagedSize: Long,
    val stagedSha256: String,
    val flags: BackupFlags,
    val categoryMapping: Map<Int, Int>,
    val mangaIndex: Int,
    val mangaCount: Int,
    val errorCount: Int,
    val lastError: String?,
    val handoffState: BackupRestoreHandoffState,
    val handoffDefaultPolicy: MangaAcquisitionPolicy?,
    val handoffCategoryOverrides: List<BackupRestoreCategoryPolicy>,
    val handoffSessionId: Int?,
    val handoffError: String?,
    val stagedDeletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val cancelledAt: Long?,
) {
    /** True while the staged payload is still on disk and a retry can therefore resume. */
    val stagedFileRetained: Boolean
        get() = stagedDeletedAt == null

    /** True when a bootstrap handoff was requested for this restore. */
    val handoffRequested: Boolean
        get() = handoffState != BackupRestoreHandoffState.NONE
}

/** One audit row of a restore: an isolated series failure or an unavailable source. */
data class BackupRestoreJobAuditDataClass(
    val id: Int,
    val jobId: Int,
    val level: BackupRestoreAuditLevel,
    val phase: BackupRestorePhase,
    val mangaIndex: Int?,
    val sourceId: Long?,
    val sourceName: String?,
    val message: String,
    /**
     * Deterministic identity of this report inside its job, e.g. `MANGA:42`.
     *
     * It is what lets an interrupted restore re-record the same failure without duplicating it, and
     * withdraw a failure whose series later succeeded.
     */
    val auditKey: String,
    val createdAt: Long,
)
