package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDiscoveryReason
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSignalConfidence
import suwayomi.tachidesk.server.database.migration.M0067_ChapterRevision
import suwayomi.tachidesk.server.database.migration.M0068_ChapterRevisionArchive
import suwayomi.tachidesk.server.database.migration.M0069_ChapterRevisionArchiveVerification
import suwayomi.tachidesk.server.database.migration.M0070_ChapterRevisionPublication
import suwayomi.tachidesk.server.database.migration.M0071_ChapterRevisionRetention
import suwayomi.tachidesk.server.database.migration.M0073_ChapterRevisionDiscoveryAudit
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class M0073ChapterRevisionDiscoveryAuditTest : ApplicationTest() {
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        val defaultDatabase = TransactionManager.defaultDatabase
        database = Database.connect("jdbc:h2:mem:chapterrevisionaudit-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "org.h2.Driver")
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
            M0071_ChapterRevisionRetention().run()
        }
    }

    @Test
    fun `adds defaults and backfills completed revisions as content proof`() {
        transaction(database) {
            exec(
                "INSERT INTO chapterrevision (candidate_key, chapter_key, source_chapter_url, \"name\", acquisition_state, " +
                    "discovered_at, updated_at, memo) VALUES " +
                    "('pending-audit', 'pending-audit', '/pending', 'Pending', '${ChapterAcquisitionState.QUEUED.name}', 1, 1, '{}'), " +
                    "('complete-audit', 'complete-audit', '/complete', 'Complete', '${ChapterAcquisitionState.COMPLETE.name}', 1, 1, '{}')",
            )
            M0073_ChapterRevisionDiscoveryAudit().run()

            exec(
                "SELECT candidate_key, discovery_reason, signal_confidence, changed_metadata_fields " +
                    "FROM chapterrevision ORDER BY candidate_key",
            ) {
                it.next()
                assertEquals("complete-audit", it.getString("candidate_key"))
                assertEquals(ChapterRevisionDiscoveryReason.NEW_CHAPTER.name, it.getString("discovery_reason"))
                assertEquals(ChapterRevisionSignalConfidence.CONTENT_PROOF.name, it.getString("signal_confidence"))
                assertEquals("", it.getString("changed_metadata_fields"))
                it.next()
                assertEquals("pending-audit", it.getString("candidate_key"))
                assertEquals(ChapterRevisionSignalConfidence.METADATA_HINT.name, it.getString("signal_confidence"))
            }
        }
    }

    @Test
    fun `creates indexes for discovery reason and confidence filters`() {
        transaction(database) {
            M0073_ChapterRevisionDiscoveryAudit().run()
            val indexedColumnSets = mutableListOf<Set<String>>()
            val columnsByIndex = mutableMapOf<String, MutableSet<String>>()
            exec(
                "SELECT INDEX_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS " +
                    "WHERE TABLE_NAME = 'CHAPTERREVISION' ORDER BY INDEX_NAME, ORDINAL_POSITION",
            ) {
                while (it.next()) {
                    columnsByIndex.getOrPut(it.getString("INDEX_NAME")) { linkedSetOf() }.add(it.getString("COLUMN_NAME").uppercase())
                }
            }
            indexedColumnSets += columnsByIndex.values
            assertTrue(indexedColumnSets.contains(setOf("DISCOVERY_REASON", "ID")))
            assertTrue(indexedColumnSets.contains(setOf("SIGNAL_CONFIDENCE", "ID")))
        }
    }
}
