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
import org.junit.jupiter.api.assertThrows
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.server.database.migration.M0067_ChapterRevision
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class M0067ChapterRevisionTest : ApplicationTest() {
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        // Database.connect makes the connected database the global default; restore it so this
        // throwaway database does not leak into tests that rely on the implicit default connection.
        val defaultDatabase = TransactionManager.defaultDatabase
        database = Database.connect("jdbc:h2:mem:chapterrevision-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "org.h2.Driver")
        TransactionManager.defaultDatabase = defaultDatabase

        transaction(database) {
            exec("CREATE TABLE manga (id INT PRIMARY KEY)")
            exec("CREATE TABLE chapter (id INT PRIMARY KEY)")
            M0067_ChapterRevision().run()
        }
    }

    // uses the runtime table object so the insert also proves the migrated schema is usable by it
    private fun insertRevision(
        key: String,
        chapterId: Int?,
        mangaId: Int?,
    ) {
        ChapterRevisionTable.insert {
            it[candidateKey] = key
            it[chapter] = chapterId
            it[manga] = mangaId
            it[sourceChapterUrl] = "/chapter/$key"
            it[name] = "Chapter"
            it[discoveredAt] = 100
            it[updatedAt] = 100
        }
    }

    @Test
    fun `creates the revision table with compatibility defaults`() {
        transaction(database) {
            exec("INSERT INTO manga (id) VALUES (1)")
            exec("INSERT INTO chapter (id) VALUES (10)")
            insertRevision("key-1", 10, 1)

            exec(
                "SELECT disposition, acquisition_state, archive_state, publication_state, attempts, " +
                    "upload_date, chapter_number, page_count, content_hash, candidate_path, last_error, " +
                    "last_attempt_at, approved_at FROM chapterrevision WHERE candidate_key = 'key-1'",
            ) {
                it.next()
                assertEquals("CANDIDATE", it.getString("disposition"))
                assertEquals("DISCOVERED", it.getString("acquisition_state"))
                assertEquals("NOT_COMMITTED", it.getString("archive_state"))
                assertEquals("NOT_PUBLISHED", it.getString("publication_state"))
                assertEquals(0, it.getInt("attempts"))
                assertEquals(0L, it.getLong("upload_date"))
                assertEquals(-1f, it.getFloat("chapter_number"))
                assertNull(it.getObject("page_count"))
                assertNull(it.getObject("content_hash"))
                assertNull(it.getObject("candidate_path"))
                assertNull(it.getObject("last_error"))
                assertNull(it.getObject("last_attempt_at"))
                assertNull(it.getObject("approved_at"))
            }
        }
    }

    @Test
    fun `keeps revisions when the referenced source rows are deleted`() {
        transaction(database) {
            exec("INSERT INTO manga (id) VALUES (1)")
            exec("INSERT INTO chapter (id) VALUES (10)")
            insertRevision("key-1", 10, 1)

            exec("DELETE FROM chapter WHERE id = 10")
            selectRefs {
                assertNull(it.getObject("chapter"), "deleting the chapter must not delete its revisions")
                assertEquals(1, it.getInt("manga"))
            }

            exec("DELETE FROM manga WHERE id = 1")
            selectRefs {
                assertNull(it.getObject("chapter"))
                assertNull(it.getObject("manga"), "deleting the manga must not delete its revisions")
            }

            exec("SELECT COUNT(*) FROM chapterrevision") {
                it.next()
                assertEquals(1, it.getInt(1), "the revision row survives the source deletion")
            }
        }
    }

    @Test
    fun `enforces a unique candidate key`() {
        transaction(database) {
            insertRevision("duplicate", null, null)

            assertThrows<Exception> {
                insertRevision("duplicate", null, null)
            }
        }
    }

    private fun JdbcTransaction.selectRefs(assertion: (java.sql.ResultSet) -> Unit) {
        exec("SELECT chapter, manga FROM chapterrevision WHERE candidate_key = 'key-1'") {
            it.next()
            assertion(it)
        }
    }

    @Test
    fun `creates the indexes used by the revision query paths`() {
        transaction(database) {
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
            assertTrue(indexedColumnSets.contains(setOf("CANDIDATE_KEY")), "the unique candidate key is indexed")
            assertTrue(indexedColumnSets.contains(setOf("CHAPTER")), "chapter lookups are indexed")
            assertTrue(indexedColumnSets.contains(setOf("MANGA")), "manga lookups are indexed")
            assertTrue(
                indexedColumnSets.contains(setOf("DISPOSITION", "ACQUISITION_STATE", "DISCOVERED_AT", "ID")),
                "the approval backlog filter and order are indexed",
            )
            assertTrue(
                indexedColumnSets.contains(setOf("DISPOSITION", "ACQUISITION_STATE", "UPDATED_AT", "ID")),
                "the queued backlog filter and order are indexed",
            )
        }
    }
}
