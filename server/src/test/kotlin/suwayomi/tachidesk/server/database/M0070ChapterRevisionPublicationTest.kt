package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationState
import suwayomi.tachidesk.manga.model.table.ChapterPublicationEventTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.server.database.migration.M0067_ChapterRevision
import suwayomi.tachidesk.server.database.migration.M0068_ChapterRevisionArchive
import suwayomi.tachidesk.server.database.migration.M0069_ChapterRevisionArchiveVerification
import suwayomi.tachidesk.server.database.migration.M0070_ChapterRevisionPublication
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class M0070ChapterRevisionPublicationTest : ApplicationTest() {
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        // Database.connect makes the connected database the global default; restore it so this
        // throwaway database does not leak into tests that rely on the implicit default connection.
        val defaultDatabase = TransactionManager.defaultDatabase
        database = Database.connect("jdbc:h2:mem:chapterrevisionpublication-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "org.h2.Driver")
        TransactionManager.defaultDatabase = defaultDatabase

        transaction(database) {
            exec(
                "CREATE TABLE manga (id INT PRIMARY KEY, url VARCHAR, title VARCHAR, description VARCHAR, in_library BOOLEAN, " +
                    "version BIGINT DEFAULT 0, last_modified_at BIGINT DEFAULT 0, is_syncing BOOLEAN DEFAULT FALSE, acquisition_policy VARCHAR)",
            )
            exec("CREATE TABLE chapter (id INT PRIMARY KEY)")
            M0067_ChapterRevision().run()
            M0068_ChapterRevisionArchive().run()
            M0069_ChapterRevisionArchiveVerification().run()
        }
    }

    /** Inserts a pre-M0070 row, which has no chapter_key column yet. */
    private fun JdbcTransaction.insertLegacyRevision(key: String) {
        // M0067 created publication_state with the NOT_PUBLISHED default and no lifecycle existed yet,
        // so this is the only value a pre-M0070 row can legitimately carry.
        exec(
            "INSERT INTO chapterrevision (candidate_key, source_chapter_url, \"name\", discovered_at, updated_at, " +
                "publication_state, memo) " +
                "VALUES ('$key', '/chapter/$key', 'Chapter', 100, 100, '${ChapterPublicationState.NOT_PUBLISHED.name}', '{}')",
        )
    }

    private fun JdbcTransaction.insertRevision(
        key: String,
        chapterKey: String = key,
        activeChapterKey: String? = null,
    ) {
        ChapterRevisionTable.insert {
            it[candidateKey] = key
            it[this.chapterKey] = chapterKey
            it[sourceChapterUrl] = "/chapter/$key"
            it[name] = "Chapter"
            it[discoveredAt] = 100
            it[updatedAt] = 100
            it[this.activeChapterKey] = activeChapterKey
        }
    }

    @Test
    fun `adds the accepted lifecycle, publication and outbox schema on a fresh install`() {
        transaction(database) {
            M0070_ChapterRevisionPublication().run()

            // the runtime table object is used so the inserts also prove the migrated schema fits it
            insertRevision("fresh-defaults")

            exec(
                "SELECT chapter_key, active_chapter_key, accepted_at, activated_at, superseded_at, " +
                    "publication_attempts, publication_last_error, publication_last_attempt_at, published_at " +
                    "FROM chapterrevision WHERE candidate_key = 'fresh-defaults'",
            ) {
                it.next()
                assertEquals("fresh-defaults", it.getString("chapter_key"))
                assertNull(it.getObject("active_chapter_key"))
                assertNull(it.getObject("accepted_at"))
                assertNull(it.getObject("activated_at"))
                assertNull(it.getObject("superseded_at"))
                assertEquals(0, it.getInt("publication_attempts"))
                assertNull(it.getObject("publication_last_error"))
                assertNull(it.getObject("publication_last_attempt_at"))
                assertNull(it.getObject("published_at"))
            }

            exec("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'CHAPTERPUBLICATIONEVENT'") {
                it.next()
                assertEquals(1, it.getInt(1), "the durable publication outbox table must be created")
            }

            ChapterPublicationEventTable.insert {
                it[revision] = null
                it[chapterKey] = "fresh-defaults"
                it[candidateKey] = "fresh-defaults"
                it[mangaId] = 1
                it[eventType] = "CHAPTER_REVISION_PUBLISHED"
                it[payload] = "{}"
                it[occurredAt] = 100
            }

            exec("SELECT delivered_at, attempts FROM chapterpublicationevent") {
                it.next()
                assertNull(it.getObject("delivered_at"), "a fresh event is undelivered so it stays deliverable")
                assertEquals(0, it.getInt("attempts"))
            }
        }
    }

    @Test
    fun `backfills chapter_key from candidate_key and makes it not null`() {
        transaction(database) {
            insertLegacyRevision("legacy-key")

            M0070_ChapterRevisionPublication().run()

            exec("SELECT chapter_key FROM chapterrevision WHERE candidate_key = 'legacy-key'") {
                it.next()
                assertEquals(
                    "legacy-key",
                    it.getString("chapter_key"),
                    "a row created before identities existed can only belong to one identity, so its own key is exact",
                )
            }

            assertThrows<Exception> {
                exec("UPDATE chapterrevision SET chapter_key = NULL WHERE candidate_key = 'legacy-key'")
            }
        }
    }

    @Test
    fun `preserves the publication state a pre-M0070 row actually had`() {
        transaction(database) {
            insertLegacyRevision("legacy-state")

            M0070_ChapterRevisionPublication().run()

            exec("SELECT publication_state FROM chapterrevision WHERE candidate_key = 'legacy-state'") {
                it.next()
                val stored = it.getString("publication_state")
                assertEquals(
                    ChapterPublicationState.NOT_PUBLISHED.name,
                    stored,
                    "M0070 must not rewrite the persisted publication state",
                )
                // the stored string still resolves to the current enum name, so nothing is translated
                assertEquals(ChapterPublicationState.NOT_PUBLISHED, ChapterPublicationState.valueOf(stored))
            }
        }
    }

    @Test
    fun `creates an outbox primary key that both H2 and PostgreSQL accept`() {
        val sql = M0070_ChapterRevisionPublication().sql

        assertTrue(
            sql.contains("INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY"),
            "the outbox id must use the identity syntax H2 and PostgreSQL both support",
        )
        assertFalse(
            sql.contains("AUTO_INCREMENT", ignoreCase = true),
            "AUTO_INCREMENT is invalid on PostgreSQL",
        )
    }

    @Test
    fun `enforces at most one active revision per chapter identity`() {
        transaction(database) {
            M0070_ChapterRevisionPublication().run()

            insertRevision("active-one", chapterKey = "identity-a", activeChapterKey = "identity-a")
            insertRevision("inactive", chapterKey = "identity-a")

            assertThrows<Exception> {
                insertRevision("active-two", chapterKey = "identity-a", activeChapterKey = "identity-a")
            }

            // a second identity may of course have its own active revision
            insertRevision("active-other", chapterKey = "identity-b", activeChapterKey = "identity-b")
        }
    }

    @Test
    fun `creates the identity, publication and outbox indexes`() {
        transaction(database) {
            M0070_ChapterRevisionPublication().run()

            val columnsByIndex = mutableMapOf<String, MutableList<String>>()
            exec(
                "SELECT INDEX_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS " +
                    "WHERE TABLE_NAME IN ('CHAPTERREVISION', 'CHAPTERPUBLICATIONEVENT') " +
                    "ORDER BY INDEX_NAME, ORDINAL_POSITION",
            ) {
                while (it.next()) {
                    columnsByIndex
                        .getOrPut(it.getString("INDEX_NAME")) { mutableListOf() }
                        .add(it.getString("COLUMN_NAME").uppercase())
                }
            }

            val indexedColumnSets = columnsByIndex.values.map { it.toSet() }.toSet()
            assertTrue(
                indexedColumnSets.contains(setOf("CHAPTER_KEY", "ID")),
                "the revision history of one identity is indexed",
            )
            assertTrue(
                indexedColumnSets.contains(setOf("ACTIVE_CHAPTER_KEY")),
                "the active-revision unique index must exist",
            )
            assertTrue(
                indexedColumnSets.contains(setOf("ACTIVE_CHAPTER_KEY", "PUBLICATION_STATE", "ID")),
                "the publication backlog filter and order are indexed",
            )
            assertTrue(
                indexedColumnSets.contains(setOf("DELIVERED_AT", "ID")),
                "the outbox delivery filter and order are indexed",
            )
        }
    }

    @Test
    fun `the H2 migration installs manga triggers that watch the retention override`() {
        transaction(database) {
            exec(
                "INSERT INTO manga (id, url, title, description, in_library, version, last_modified_at, is_syncing) " +
                    "VALUES (1, '/m', 'Manga', 'desc', TRUE, 0, 0, FALSE)",
            )

            M0070_ChapterRevisionPublication().run()

            exec("UPDATE manga SET accepted_revision_retention = 5 WHERE id = 1")

            exec("SELECT version, last_modified_at FROM manga WHERE id = 1") {
                it.next()
                assertEquals(1L, it.getLong("version"), "a retention-only change must bump the manga version")
                assertTrue(it.getLong("last_modified_at") > 0, "a retention-only change must stamp last_modified_at")
            }
        }
    }

    @Test
    fun `the postgresql migration watches the retention override in both manga row comparisons`() {
        val sql = M0070_ChapterRevisionPublication().postgresQuery()

        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION update_manga_version()"))
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION update_manga_last_modified_at()"))
        assertTrue(
            sql.contains("NEW.accepted_revision_retention)") && sql.contains("OLD.accepted_revision_retention)"),
            "update_manga_version must compare accepted_revision_retention for both rows",
        )
        assertTrue(
            sql.contains("NEW.version, NEW.acquisition_policy") && sql.contains("NEW.accepted_revision_retention)"),
            "update_manga_last_modified_at must watch the retention override too",
        )
    }
}
