package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.AddTableMigration
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.impl.util.lang.EMPTY
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.columns.jsonObjectText
import suwayomi.tachidesk.manga.model.table.columns.truncatingVarchar

// The candidate revision table is populated lazily by reconciliation; existing chapters are not
// backfilled here. This migration only creates the durable table and must not be edited once
// released - later schema changes belong in new migrations.
@Suppress("ClassName", "unused")
class M0067_ChapterRevision : AddTableMigration() {
    private class ChapterRevisionTable : IntIdTable() {
        val candidateKey = varchar("candidate_key", 64).uniqueIndex()

        val chapter = optReference("chapter", ChapterTable, ReferenceOption.SET_NULL)
        val manga = optReference("manga", MangaTable, ReferenceOption.SET_NULL)

        val sourceId = long("source_id").nullable()
        val sourceMangaUrl = varchar("source_manga_url", 2048).nullable()
        val sourceChapterUrl = varchar("source_chapter_url", 2048)

        val name = truncatingVarchar("name", 512)
        val scanlator = truncatingVarchar("scanlator", 256).nullable()
        val uploadDate = long("upload_date").default(0)
        val chapterNumber = float("chapter_number").default(-1f)
        val memo = jsonObjectText("memo").clientDefault { JsonObject.EMPTY }

        val disposition = varchar("disposition", 256).default(ChapterRevisionDisposition.CANDIDATE.name)

        val acquisitionState = varchar("acquisition_state", 256).default(ChapterAcquisitionState.DISCOVERED.name)
        val archiveState = varchar("archive_state", 256).default(ChapterArchiveState.NOT_COMMITTED.name)
        val publicationState = varchar("publication_state", 256).default(ChapterPublicationState.NOT_PUBLISHED.name)

        val pageCount = integer("page_count").nullable()
        val contentHash = varchar("content_hash", 64).nullable()
        val candidatePath = varchar("candidate_path", 2048).nullable()

        val attempts = integer("attempts").default(0)
        val lastError = varchar("last_error", 4096).nullable()
        val lastAttemptAt = long("last_attempt_at").nullable()

        val discoveredAt = long("discovered_at")
        val updatedAt = long("updated_at")
        val approvedAt = long("approved_at").nullable()

        init {
            // keep identical to the runtime ChapterRevisionTable so the migrated schema matches it
            index("chapter_revision_chapter_idx", false, chapter)
            index("chapter_revision_manga_idx", false, manga)
            index("chapter_revision_approval_backlog_idx", false, disposition, acquisitionState, discoveredAt, id)
            index("chapter_revision_queued_backlog_idx", false, disposition, acquisitionState, updatedAt, id)
        }
    }

    override val tables: Array<Table>
        get() =
            arrayOf(
                ChapterRevisionTable(),
            )
}
