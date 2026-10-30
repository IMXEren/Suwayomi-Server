package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

// Remote durability verification keeps its own attempt counter, audit timestamps and persisted
// schedule, so a restart or a second server instance can neither hot-loop on the same revision nor
// verify it twice concurrently.
//
// M0067/M0068 stay untouched: they already created and extended the table in released form. Rows
// that are already pending are simply made due immediately instead of being backfilled by hand.
@Suppress("ClassName", "unused")
class M0069_ChapterRevisionArchiveVerification : SQLMigration() {
    // language=sql
    override val sql: String =
        """
        ALTER TABLE chapterrevision ADD COLUMN archive_verification_attempts INT NOT NULL DEFAULT 0;
        ALTER TABLE chapterrevision ADD COLUMN archive_last_verification_at BIGINT NULL;
        ALTER TABLE chapterrevision ADD COLUMN archive_next_verification_at BIGINT NULL;

        UPDATE chapterrevision SET archive_next_verification_at = 0 WHERE archive_state = 'REMOTE_PENDING';

        CREATE INDEX chapter_revision_verification_backlog_idx
            ON chapterrevision (disposition, archive_state, archive_next_verification_at, id);

        CREATE INDEX chapter_revision_cleanup_idx
            ON chapterrevision (archive_state, updated_at, id);
        """.trimIndent()
}
