package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.manga.model.dataclass.ChapterRetentionState

// Adds retention pruning as its own persisted state dimension. Pruning is deliberately not folded
// into the acquisition, archive or publication states: those describe what a revision is, while
// retention describes whether a historical revision's archived payload is still kept.
//
// M0067-M0070 stay untouched: they already created and extended the revision table in released form.
// Every existing row is RETAINED with no attempt recorded, which is exactly the pre-pruning default,
// so no backfill beyond the column default is needed.
@Suppress("ClassName", "unused")
class M0071_ChapterRevisionRetention : SQLMigration() {
    // language=sql
    override val sql: String =
        """
        ALTER TABLE chapterrevision ADD COLUMN retention_state VARCHAR(256) NOT NULL DEFAULT '${ChapterRetentionState.RETAINED.name}';
        ALTER TABLE chapterrevision ADD COLUMN retention_attempts INT NOT NULL DEFAULT 0;
        ALTER TABLE chapterrevision ADD COLUMN retention_last_error VARCHAR(4096) NULL;
        ALTER TABLE chapterrevision ADD COLUMN retention_last_attempt_at BIGINT NULL;
        ALTER TABLE chapterrevision ADD COLUMN retention_queued_at BIGINT NULL;
        ALTER TABLE chapterrevision ADD COLUMN retention_next_verification_at BIGINT NULL;
        ALTER TABLE chapterrevision ADD COLUMN deleted_at BIGINT NULL;
        ALTER TABLE chapterrevision ADD COLUMN pruned_at BIGINT NULL;

        CREATE INDEX chapter_revision_retention_backlog_idx
            ON chapterrevision (retention_state, retention_queued_at, id);

        CREATE INDEX chapter_revision_retention_verify_idx
            ON chapterrevision (retention_state, retention_next_verification_at, id);
        """.trimIndent()
}
