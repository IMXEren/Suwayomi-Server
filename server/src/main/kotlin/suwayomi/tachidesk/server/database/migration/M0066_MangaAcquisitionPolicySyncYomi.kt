package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.server.serverConfig

// M0065 added Manga.acquisition_policy after the SyncYomi manga triggers were already installed, so
// without this migration the field is invisible to incremental sync.
//
// On PostgreSQL only the M0063 functions are replaced; their triggers (and the BEFORE-trigger
// ordering that lets update_manga_last_modified_at see the watched columns) are left untouched.
// On H2 the M0056 manga triggers are dropped and recreated against replacement classes because the
// M0056 classes must keep working while the column does not exist yet (M0057-M0064).
@Suppress("ClassName", "unused")
class M0066_MangaAcquisitionPolicySyncYomi : SQLMigration() {
    override val sql =
        when (serverConfig.databaseType.value) {
            DatabaseType.POSTGRESQL -> postgresQuery()
            DatabaseType.H2 -> h2Query()
        }

    // language=postgresql
    fun postgresQuery(): String =
        """
        CREATE OR REPLACE FUNCTION update_manga_version()
        RETURNS trigger AS $$
        BEGIN
            IF NOT NEW.is_syncing
               AND ROW(NEW.url, NEW.description, NEW.in_library, NEW.acquisition_policy)
                   IS DISTINCT FROM
                   ROW(OLD.url, OLD.description, OLD.in_library, OLD.acquisition_policy)
            THEN
                NEW.version := OLD.version + 1;
            END IF;

            RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;

        CREATE OR REPLACE FUNCTION update_manga_last_modified_at()
        RETURNS trigger AS $$
        BEGIN
            IF NEW.is_syncing THEN
                RETURN NEW;
            END IF;

            IF TG_OP = 'UPDATE'
               AND ROW(NEW.url, NEW.description, NEW.in_library, NEW.version, NEW.acquisition_policy)
                   IS NOT DISTINCT FROM
                   ROW(OLD.url, OLD.description, OLD.in_library, OLD.version, OLD.acquisition_policy)
            THEN
                RETURN NEW;
            END IF;

            NEW.last_modified_at := EXTRACT(EPOCH FROM NOW());
            RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;
        """.trimIndent()

    // language=h2
    fun h2Query(): String =
        """
        DROP TRIGGER IF EXISTS update_manga_version;
        CREATE TRIGGER update_manga_version
        BEFORE UPDATE ON manga
        FOR EACH ROW
        CALL "suwayomi.tachidesk.server.database.trigger.UpdateMangaVersionWithAcquisitionPolicyTrigger";

        DROP TRIGGER IF EXISTS update_manga_last_modified_at;
        CREATE TRIGGER update_manga_last_modified_at
        BEFORE UPDATE ON manga
        FOR EACH ROW
        CALL "suwayomi.tachidesk.server.database.trigger.UpdateMangaLastModifiedAtWithAcquisitionPolicyTrigger";
        """.trimIndent()
}
