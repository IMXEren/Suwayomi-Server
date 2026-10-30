package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.server.database.migration.M0056_SyncYomi
import suwayomi.tachidesk.server.database.migration.M0063_FixSyncYomiTriggers
import suwayomi.tachidesk.server.database.migration.M0065_MangaAcquisitionPolicy
import suwayomi.tachidesk.server.database.migration.M0066_MangaAcquisitionPolicySyncYomi
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class M0066MangaAcquisitionPolicySyncYomiTest : ApplicationTest() {
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        // Database.connect makes the connected database the global default; restore it so this
        // throwaway database does not leak into tests that rely on the implicit default connection.
        val defaultDatabase = TransactionManager.defaultDatabase
        database = Database.connect("jdbc:h2:mem:acquisitionpolicysync-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "org.h2.Driver")
        TransactionManager.defaultDatabase = defaultDatabase

        transaction(database) {
            exec("CREATE TABLE manga (id BIGINT PRIMARY KEY, url VARCHAR, title VARCHAR, description VARCHAR, in_library BOOLEAN)")
            exec(
                "CREATE TABLE chapter (id BIGINT PRIMARY KEY, name VARCHAR, read BOOLEAN, bookmark BOOLEAN, last_page_read INT, manga BIGINT)",
            )
            exec("CREATE TABLE category (id BIGINT PRIMARY KEY, name VARCHAR, sort_order INT)")
            exec("CREATE TABLE categorymanga (id BIGINT PRIMARY KEY, manga BIGINT, category BIGINT)")
            exec("CREATE TABLE trackrecord (id BIGINT PRIMARY KEY, manga_id BIGINT, sync_id INT, status INT)")

            // replay the released order: M0056 installs the manga triggers while the column does not
            // exist yet, M0065 adds it, M0066 replaces the triggers so they watch it
            M0056_SyncYomi().run()
            M0063_FixSyncYomiTriggers().run()
            M0065_MangaAcquisitionPolicy().run()
            M0066_MangaAcquisitionPolicySyncYomi().run()

            exec("INSERT INTO manga (id, url, title, description, in_library) VALUES (1, '/m', 'Manga', 'd', TRUE)")
            exec("UPDATE manga SET version = 0, last_modified_at = 0")
        }
    }

    private fun JdbcTransaction.manga(): Pair<Long, Long> =
        exec("SELECT version, last_modified_at FROM manga WHERE id = 1") {
            it.next()
            it.getLong("version") to it.getLong("last_modified_at")
        }!!

    @Test
    fun `a policy-only change bumps the version and stamps the modification time`() {
        transaction(database) {
            exec("UPDATE manga SET acquisition_policy = 'AUTO' WHERE id = 1")

            val (version, stamp) = manga()
            assertEquals(1L, version, "watching acquisition_policy must bump the manga version")
            assertTrue(stamp > 0, "watching acquisition_policy must stamp last_modified_at")
        }
    }

    @Test
    fun `a syncing restore does not bump the version or stamp the modification time`() {
        transaction(database) {
            exec("UPDATE manga SET acquisition_policy = 'AUTO', version = 7, last_modified_at = 1234, is_syncing = TRUE WHERE id = 1")
            assertEquals(7L to 1234L, manga())

            exec("UPDATE manga SET is_syncing = FALSE WHERE is_syncing")
            assertEquals(7L to 1234L, manga())
        }
    }

    @Test
    fun `non-policy metadata updates do not bump the version or stamp`() {
        transaction(database) {
            exec("UPDATE manga SET title = 'renamed' WHERE id = 1")
            assertEquals(0L to 0L, manga())
        }
    }

    @Test
    fun `the postgresql migration watches acquisition_policy in both manga row comparisons`() {
        val sql = M0066_MangaAcquisitionPolicySyncYomi().postgresQuery()

        assertTrue(
            sql.contains("ROW(NEW.url, NEW.description, NEW.in_library, NEW.acquisition_policy)"),
            "update_manga_version must compare acquisition_policy",
        )
        assertTrue(
            sql.contains("ROW(OLD.url, OLD.description, OLD.in_library, OLD.acquisition_policy)"),
            "update_manga_version must compare acquisition_policy",
        )
        assertTrue(
            sql.contains("ROW(NEW.url, NEW.description, NEW.in_library, NEW.version, NEW.acquisition_policy)"),
            "update_manga_last_modified_at must compare acquisition_policy",
        )
        assertTrue(
            sql.contains("ROW(OLD.url, OLD.description, OLD.in_library, OLD.version, OLD.acquisition_policy)"),
            "update_manga_last_modified_at must compare acquisition_policy",
        )
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION update_manga_version()"))
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION update_manga_last_modified_at()"))
    }
}
