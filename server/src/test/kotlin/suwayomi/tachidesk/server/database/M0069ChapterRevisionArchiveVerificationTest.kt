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
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.server.database.migration.M0067_ChapterRevision
import suwayomi.tachidesk.server.database.migration.M0068_ChapterRevisionArchive
import suwayomi.tachidesk.server.database.migration.M0069_ChapterRevisionArchiveVerification
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class M0069ChapterRevisionArchiveVerificationTest : ApplicationTest() {
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        // Database.connect makes the connected database the global default; restore it so this
        // throwaway database does not leak into tests that rely on the implicit default connection.
        val defaultDatabase = TransactionManager.defaultDatabase
        database = Database.connect("jdbc:h2:mem:chapterrevisionverification-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "org.h2.Driver")
        TransactionManager.defaultDatabase = defaultDatabase

        transaction(database) {
            exec("CREATE TABLE manga (id INT PRIMARY KEY)")
            exec("CREATE TABLE chapter (id INT PRIMARY KEY)")
            M0067_ChapterRevision().run()
            M0068_ChapterRevisionArchive().run()
        }
    }

    private fun JdbcTransaction.insertRawRevision(
        key: String,
        archiveState: String,
    ) {
        exec(
            "INSERT INTO chapterrevision (candidate_key, source_chapter_url, \"name\", discovered_at, updated_at, archive_state, memo) " +
                "VALUES ('$key', '/chapter/$key', 'Chapter', 100, 100, '$archiveState', '{}')",
        )
    }

    @Test
    fun `adds the verification columns with compatibility defaults`() {
        transaction(database) {
            M0069_ChapterRevisionArchiveVerification().run()

            // the runtime table object is used so the insert also proves the migrated schema fits it
            ChapterRevisionTable.insert {
                it[candidateKey] = "verification-defaults"
                it[sourceChapterUrl] = "/chapter/verification-defaults"
                it[name] = "Chapter"
                it[discoveredAt] = 100
                it[updatedAt] = 100
            }

            exec(
                "SELECT archive_verification_attempts, archive_last_verification_at, archive_next_verification_at " +
                    "FROM chapterrevision WHERE candidate_key = 'verification-defaults'",
            ) {
                it.next()
                assertEquals(0, it.getInt("archive_verification_attempts"))
                assertNull(it.getObject("archive_last_verification_at"))
                assertNull(it.getObject("archive_next_verification_at"))
            }
        }
    }

    @Test
    fun `rows that are already pending become due immediately`() {
        transaction(database) {
            // inserted before the migration so the backfill is actually exercised
            insertRawRevision("confirmed-key", "REMOTE_CONFIRMED")
            insertRawRevision("pending-key", "REMOTE_PENDING")

            M0069_ChapterRevisionArchiveVerification().run()

            exec("SELECT candidate_key, archive_next_verification_at FROM chapterrevision ORDER BY candidate_key") {
                it.next()
                assertEquals("confirmed-key", it.getString("candidate_key"))
                assertNull(it.getObject("archive_next_verification_at"), "a confirmed revision is not scheduled")

                it.next()
                assertEquals("pending-key", it.getString("candidate_key"))
                assertEquals(
                    0L,
                    it.getLong("archive_next_verification_at"),
                    "a revision that was pending before verification existed is due immediately",
                )
            }
        }
    }

    @Test
    fun `indexes the verification backlog`() {
        transaction(database) {
            M0069_ChapterRevisionArchiveVerification().run()

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
                    setOf("DISPOSITION", "ARCHIVE_STATE", "ARCHIVE_NEXT_VERIFICATION_AT", "ID"),
                ),
                "the due-verification filter and order are indexed",
            )
            assertTrue(
                indexedColumnSets.contains(setOf("ARCHIVE_STATE", "UPDATED_AT", "ID")),
                "the pending-cleanup filter and order are indexed",
            )
        }
    }
}
