package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.server.serverConfig

// Turns the per-series acquisition policy into an optional override.
//
// M0065 added the column NOT NULL DEFAULT 'MANUAL', so every series carried a value and the default
// was indistinguishable from an explicit choice. A series without an override now inherits the
// configurable global default instead, so the column must accept null and must not supply its own
// default. Existing rows are deliberately not rewritten: a stored value, MANUAL included, stays an
// explicit per-series override.
@Suppress("ClassName", "unused")
class M0080_ArchiveDefaultAcquisitionPolicy : SQLMigration() {
    override val sql: String =
        when (serverConfig.databaseType.value) {
            DatabaseType.POSTGRESQL -> postgresQuery()
            DatabaseType.H2 -> h2Query()
        }

    // language=postgresql
    fun postgresQuery(): String =
        """
        ALTER TABLE manga ALTER COLUMN acquisition_policy DROP NOT NULL;
        ALTER TABLE manga ALTER COLUMN acquisition_policy DROP DEFAULT;
        """.trimIndent()

    // language=h2
    fun h2Query(): String =
        """
        ALTER TABLE manga ALTER COLUMN acquisition_policy SET NULL;
        ALTER TABLE manga ALTER COLUMN acquisition_policy DROP DEFAULT;
        """.trimIndent()
}
