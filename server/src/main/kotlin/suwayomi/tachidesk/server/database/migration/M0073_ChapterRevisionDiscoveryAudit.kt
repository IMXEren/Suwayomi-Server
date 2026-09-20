package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDiscoveryReason
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSignalConfidence

// Records why a candidate was created and how strong its revision evidence is. Existing candidates
// were created by new-chapter reconciliation; completed ones already have validated downloaded bytes
// and therefore qualify as content proof without any content re-download or broad backfill.
@Suppress("ClassName", "unused")
class M0073_ChapterRevisionDiscoveryAudit : SQLMigration() {
    // language=sql
    override val sql: String =
        """
        ALTER TABLE chapterrevision
            ADD COLUMN discovery_reason VARCHAR(64) NOT NULL DEFAULT '${ChapterRevisionDiscoveryReason.NEW_CHAPTER.name}';
        ALTER TABLE chapterrevision
            ADD COLUMN signal_confidence VARCHAR(64) NOT NULL DEFAULT '${ChapterRevisionSignalConfidence.METADATA_HINT.name}';
        ALTER TABLE chapterrevision
            ADD COLUMN changed_metadata_fields VARCHAR(256) NOT NULL DEFAULT '';

        UPDATE chapterrevision
        SET signal_confidence = '${ChapterRevisionSignalConfidence.CONTENT_PROOF.name}'
        WHERE acquisition_state = '${ChapterAcquisitionState.COMPLETE.name}';

        CREATE INDEX chapter_revision_discovery_reason_idx
            ON chapterrevision (discovery_reason, id);
        CREATE INDEX chapter_revision_signal_confidence_idx
            ON chapterrevision (signal_confidence, id);
        """.trimIndent()
}
