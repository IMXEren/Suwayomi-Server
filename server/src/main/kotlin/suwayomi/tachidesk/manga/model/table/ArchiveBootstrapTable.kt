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
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapCategoryPolicies
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapItemDataClass
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapItemState
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapSessionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapState
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.columns.truncatingVarchar

/**
 * Durable archive bootstrap session.
 *
 * [activeMarker] holds a constant only while the session is [ArchiveBootstrapState.RUNNING] or
 * [ArchiveBootstrapState.PAUSED], so its unique index is what enforces "at most one active bootstrap"
 * in the database rather than in application code. Both H2 and PostgreSQL treat nulls as distinct in
 * a unique index, so every terminal session keeps a null marker and never conflicts.
 */
object ArchiveBootstrapSessionTable : IntIdTable("archivebootstrapsession") {
    val state = varchar("state", 64).default(ArchiveBootstrapState.RUNNING.name)
    val activeMarker = varchar("active_marker", 64).nullable().uniqueIndex()

    /**
     * The restore this run was started for, or null for a run an operator started directly.
     *
     * A restore can be interrupted between starting its bootstrap and recording that it did, so the
     * origin is what lets the retry recognise its own run instead of mistaking it for somebody else's
     * active session. The unique index makes "one bootstrap per restore" a database invariant, and
     * both dialects treat nulls as distinct, so every direct run keeps a null origin.
     */
    val originKey = varchar("origin_key", 128).nullable().uniqueIndex()

    /** the policy applied to every series without a matching category override */
    val defaultPolicy = varchar("default_policy", 64)

    /** ordered `categoryId:POLICY` snapshot; the first matching entry wins */
    val categoryPolicyOverrides = text("category_policy_overrides").default("")

    // scheduling settings snapshotted at start, so a later settings change cannot alter a live run
    val interItemDelaySeconds = long("inter_item_delay_seconds").default(2)
    val retrySeconds = long("retry_seconds").default(300)
    val maxAttempts = integer("max_attempts").default(3)

    /** earliest instant the next series of this session may be processed; the global rate limit */
    val nextItemAt = long("next_item_at").nullable()
    val lastItemAt = long("last_item_at").nullable()

    val startedAt = long("started_at")
    val updatedAt = long("updated_at")
    val finishedAt = long("finished_at").nullable()
    val pausedAt = long("paused_at").nullable()
    val cancelledAt = long("cancelled_at").nullable()

    init {
        // the worker claims the oldest session that is currently allowed to run
        index("archive_bootstrap_session_claim_idx", false, state, nextItemAt, id)
    }
}

/**
 * One series of a bootstrap session.
 *
 * [mangaId] and [sourceId] are immutable snapshots, not references, so deleting a series or a source
 * row leaves the audit record intact. Only the owning session is a real reference, and it cascades
 * because an item has no meaning without its run.
 */
object ArchiveBootstrapItemTable : IntIdTable("archivebootstrapitem") {
    val session = reference("session", ArchiveBootstrapSessionTable, ReferenceOption.CASCADE)

    val mangaId = integer("manga_id").nullable()
    val sourceId = long("source_id").nullable()

    val title = truncatingVarchar("title", 512)
    val mangaUrl = varchar("manga_url", 2048).nullable()

    /** comma separated category ids the series belonged to when the session started */
    val categoryIds = text("category_ids").default("")

    /** the acquisition policy resolved for this series when the session started */
    val policy = varchar("policy", 64)

    val state = varchar("state", 64).default(ArchiveBootstrapItemState.PENDING.name)
    val attempts = integer("attempts").default(0)
    val lastError = varchar("last_error", 4096).nullable()
    val candidateCount = integer("candidate_count").nullable()

    /** earliest instant this series may be retried, null while it is due immediately */
    val dueAt = long("due_at").nullable()

    val startedAt = long("started_at").nullable()
    val finishedAt = long("finished_at").nullable()
    val updatedAt = long("updated_at")

    init {
        // the worker claims the earliest claimable item of one session, ordered by its own due time
        index("archive_bootstrap_item_backlog_idx", false, session, state, dueAt, id)
        // the per-session item listing is ordered by id and filtered only by session
        index("archive_bootstrap_item_session_idx", false, session, id)
    }
}

private fun encodeCategoryIds(categoryIds: List<Int>): String = categoryIds.distinct().sorted().joinToString(",")

private fun decodeCategoryIds(value: String): List<Int> =
    value
        .takeIf { it.isNotBlank() }
        ?.split(',')
        ?.mapNotNull { it.toIntOrNull() }
        .orEmpty()

fun ArchiveBootstrapSessionTable.toDataClass(row: ResultRow) =
    ArchiveBootstrapSessionDataClass(
        id = row[id].value,
        state = ArchiveBootstrapState.valueOf(row[state]),
        originKey = row[originKey],
        defaultPolicy = MangaAcquisitionPolicy.valueOf(row[defaultPolicy]),
        categoryPolicies = ArchiveBootstrapCategoryPolicies.decode(row[categoryPolicyOverrides]),
        interItemDelaySeconds = row[interItemDelaySeconds],
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

fun ArchiveBootstrapItemTable.toDataClass(row: ResultRow) =
    ArchiveBootstrapItemDataClass(
        id = row[id].value,
        sessionId = row[session].value,
        mangaId = row[mangaId],
        sourceId = row[sourceId],
        title = row[title],
        mangaUrl = row[mangaUrl],
        categoryIds = decodeCategoryIds(row[categoryIds]),
        policy = MangaAcquisitionPolicy.valueOf(row[policy]),
        state = ArchiveBootstrapItemState.valueOf(row[state]),
        attempts = row[attempts],
        lastError = row[lastError],
        candidateCount = row[candidateCount],
        dueAt = row[dueAt],
        startedAt = row[startedAt],
        finishedAt = row[finishedAt],
        updatedAt = row[updatedAt],
    )

/** Snapshot helper kept beside the table so both write paths encode category ids identically. */
fun encodeArchiveBootstrapCategoryIds(categoryIds: List<Int>): String = encodeCategoryIds(categoryIds)
