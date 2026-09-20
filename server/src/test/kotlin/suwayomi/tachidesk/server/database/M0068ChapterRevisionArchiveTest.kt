package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.server.database.migration.M0067_ChapterRevision
import suwayomi.tachidesk.server.database.migration.M0068_ChapterRevisionArchive
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class M0068ChapterRevisionArchiveTest : ApplicationTest() {
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        // Database.connect makes the connected database the global default; restore it so this
        // throwaway database does not leak into tests that rely on the implicit default connection.
        val defaultDatabase = TransactionManager.defaultDatabase
        database = Database.connect("jdbc:h2:mem:chapterrevisionarchive-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "org.h2.Driver")
        TransactionManager.defaultDatabase = defaultDatabase

        transaction(database) {
            exec("CREATE TABLE manga (id INT PRIMARY KEY)")
            exec("CREATE TABLE chapter (id INT PRIMARY KEY)")
            M0067_ChapterRevision().run()
            M0068_ChapterRevisionArchive().run()
        }
    }

    // uses the runtime table object so the insert also proves the migrated schema is usable by it
    private fun insertRevision(key: String) {
        ChapterRevisionTable.insert {
            it[candidateKey] = key
            it[sourceChapterUrl] = "/chapter/$key"
            it[name] = "Chapter"
            it[discoveredAt] = 100
            it[updatedAt] = 100
        }
    }

    @Test
    fun `adds the archive columns with compatibility defaults`() {
        transaction(database) {
            insertRevision("archive-defaults")

            exec(
                "SELECT archive_attempts, archive_last_error, archive_last_attempt_at, archive_cbz_path, " +
                    "archive_manifest_path, archive_cbz_hash, archive_cbz_size, archive_manifest_hash, " +
                    "archive_manifest_size, archived_at " +
                    "FROM chapterrevision WHERE candidate_key = 'archive-defaults'",
            ) {
                it.next()
                assertEquals(0, it.getInt("archive_attempts"))
                assertNull(it.getObject("archive_last_error"))
                assertNull(it.getObject("archive_last_attempt_at"))
                assertNull(it.getObject("archive_cbz_path"))
                assertNull(it.getObject("archive_manifest_path"))
                assertNull(it.getObject("archive_cbz_hash"))
                assertNull(it.getObject("archive_cbz_size"))
                assertNull(it.getObject("archive_manifest_hash"))
                assertNull(it.getObject("archive_manifest_size"))
                assertNull(it.getObject("archived_at"))
            }
        }
    }

    @Test
    fun `indexes the archive backlog`() {
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
            assertTrue(
                indexedColumnSets.contains(
                    setOf("DISPOSITION", "ACQUISITION_STATE", "ARCHIVE_STATE", "UPDATED_AT", "ID"),
                ),
                "the archive backlog filter and order are indexed",
            )
        }
    }
}
