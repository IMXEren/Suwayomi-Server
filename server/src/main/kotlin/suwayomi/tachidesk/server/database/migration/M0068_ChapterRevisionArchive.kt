package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

// The archive commit keeps its own attempt counter and audit trail so a failed archive never
// disturbs how a chapter was acquired, and stores the relative locations and digest of the
// immutable artifacts.
//
// M0067 stays untouched: it already created the table in released form, so these columns are added
// here instead.
@Suppress("ClassName", "unused")
class M0068_ChapterRevisionArchive : SQLMigration() {
    // language=sql
    override val sql: String =
        """
        ALTER TABLE chapterrevision ADD COLUMN archive_attempts INT NOT NULL DEFAULT 0;
        ALTER TABLE chapterrevision ADD COLUMN archive_last_error VARCHAR(4096) NULL;
        ALTER TABLE chapterrevision ADD COLUMN archive_last_attempt_at BIGINT NULL;
        ALTER TABLE chapterrevision ADD COLUMN archive_cbz_path VARCHAR(2048) NULL;
        ALTER TABLE chapterrevision ADD COLUMN archive_manifest_path VARCHAR(2048) NULL;
        ALTER TABLE chapterrevision ADD COLUMN archive_cbz_hash VARCHAR(64) NULL;
        ALTER TABLE chapterrevision ADD COLUMN archive_cbz_size BIGINT NULL;
        ALTER TABLE chapterrevision ADD COLUMN archive_manifest_hash VARCHAR(64) NULL;
        ALTER TABLE chapterrevision ADD COLUMN archive_manifest_size BIGINT NULL;
        ALTER TABLE chapterrevision ADD COLUMN archived_at BIGINT NULL;

        CREATE INDEX chapter_revision_archive_backlog_idx
            ON chapterrevision (disposition, acquisition_state, archive_state, updated_at, id);
        """.trimIndent()
}
