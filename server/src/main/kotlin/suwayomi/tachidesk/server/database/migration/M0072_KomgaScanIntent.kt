package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.manga.model.dataclass.KomgaScanState

// Adds the durable, coalesced Komga rescan intent. A published revision makes the configured Komga
// library stale, but a burst of publications must still cause a single library scan, and the intent
// has to survive a restart, so the intent is one persisted row instead of an in-memory signal.
//
// The row is intentionally created empty: with no intent recorded, nothing is pending and no scan is
// ever requested. No URL, library id or API key is stored - the intent only means "the configured
// library is stale" - so a database dump cannot leak credentials and changing the Komga
// configuration never invalidates a recorded intent.
@Suppress("ClassName", "unused")
class M0072_KomgaScanIntent : SQLMigration() {
    // language=sql
    override val sql: String =
        """
        CREATE TABLE komgascanintent (
            id INT NOT NULL,
            state VARCHAR(256) NOT NULL DEFAULT '${KomgaScanState.PENDING.name}',
            generation BIGINT NOT NULL DEFAULT 0,
            running_generation BIGINT NULL,
            requested_at BIGINT NOT NULL DEFAULT 0,
            not_before_at BIGINT NULL,
            attempts INT NOT NULL DEFAULT 0,
            last_attempt_at BIGINT NULL,
            last_completed_at BIGINT NULL,
            last_error VARCHAR(4096) NULL,
            PRIMARY KEY (id)
        );

        CREATE INDEX komga_scan_intent_due_idx
            ON komgascanintent (state, not_before_at, id);
        """.trimIndent()
}
