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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ChapterRetentionState
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.server.database.migration.M0067_ChapterRevision
import suwayomi.tachidesk.server.database.migration.M0068_ChapterRevisionArchive
import suwayomi.tachidesk.server.database.migration.M0069_ChapterRevisionArchiveVerification
import suwayomi.tachidesk.server.database.migration.M0070_ChapterRevisionPublication
import suwayomi.tachidesk.server.database.migration.M0071_ChapterRevisionRetention
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class M0071ChapterRevisionRetentionTest : ApplicationTest() {
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        // Database.connect makes the connected database the global default; restore it so this
        // throwaway database does not leak into tests that rely on the implicit default connection.
        val defaultDatabase = TransactionManager.defaultDatabase
        database = Database.connect("jdbc:h2:mem:chapterrevisionretention-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "org.h2.Driver")
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
            M0070_ChapterRevisionPublication().run()
        }
    }

    private fun JdbcTransaction.insertRevision(key: String) {
        ChapterRevisionTable.insert {
            it[candidateKey] = key
            it[chapterKey] = key
            it[sourceChapterUrl] = "/chapter/$key"
            it[name] = "Chapter"
            it[discoveredAt] = 100
            it[updatedAt] = 100
        }
    }

    @Test
    fun `adds the retention dimension with a pristine default on a fresh install`() {
        transaction(database) {
            M0071_ChapterRevisionRetention().run()

            insertRevision("fresh-retention")

            exec(
                "SELECT retention_state, retention_attempts, retention_last_error, retention_last_attempt_at, " +
                    "retention_queued_at, retention_next_verification_at, deleted_at, pruned_at " +
                    "FROM chapterrevision WHERE candidate_key = 'fresh-retention'",
            ) {
                it.next()
                assertEquals(ChapterRetentionState.RETAINED.name, it.getString("retention_state"))
                assertEquals(0, it.getInt("retention_attempts"))
                assertNull(it.getObject("retention_last_error"))
                assertNull(it.getObject("retention_last_attempt_at"))
                assertNull(it.getObject("retention_queued_at"))
                assertNull(it.getObject("retention_next_verification_at"))
                assertNull(it.getObject("deleted_at"))
                assertNull(it.getObject("pruned_at"))
            }
        }
    }

    @Test
    fun `leaves revisions that existed before the migration retained`() {
        transaction(database) {
            // a pre-M0071 row has no retention columns at all until the migration runs
            exec(
                "INSERT INTO chapterrevision (candidate_key, chapter_key, source_chapter_url, \"name\", discovered_at, updated_at, memo) " +
                    "VALUES ('legacy-retention', 'legacy-retention', '/chapter/legacy', 'Chapter', 100, 100, '{}')",
            )

            M0071_ChapterRevisionRetention().run()

            exec("SELECT retention_state FROM chapterrevision WHERE candidate_key = 'legacy-retention'") {
                it.next()
                assertEquals(
                    ChapterRetentionState.RETAINED.name,
                    it.getString("retention_state"),
                    "nothing may be pruned until the retention worker decided to",
                )
            }
        }
    }

    @Test
    fun `creates the pruning queue and absence verification indexes`() {
        transaction(database) {
            M0071_ChapterRevisionRetention().run()

            val columnsByIndex = mutableMapOf<String, MutableList<String>>()
            exec(
                "SELECT INDEX_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS " +
                    "WHERE TABLE_NAME = 'CHAPTERREVISION' ORDER BY INDEX_NAME, ORDINAL_POSITION",
            ) {
                while (it.next()) {
                    columnsByIndex
                        .getOrPut(it.getString("INDEX_NAME")) { mutableListOf() }
                        .add(it.getString("COLUMN_NAME").uppercase())
                }
            }

            val indexedColumnSets = columnsByIndex.values.map { it.toSet() }.toSet()
            assertTrue(
                indexedColumnSets.contains(setOf("RETENTION_STATE", "RETENTION_QUEUED_AT", "ID")),
                "the pruning queue filter and order are indexed",
            )
            assertTrue(
                indexedColumnSets.contains(setOf("RETENTION_STATE", "RETENTION_NEXT_VERIFICATION_AT", "ID")),
                "the remote absence re-check filter and order are indexed",
            )
        }
    }
}
