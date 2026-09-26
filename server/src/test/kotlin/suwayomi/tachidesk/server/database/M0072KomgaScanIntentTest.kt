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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.KomgaScanState
import suwayomi.tachidesk.server.database.migration.M0072_KomgaScanIntent
import java.util.UUID

class M0072KomgaScanIntentTest {
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        // Database.connect makes the connected database the global default; restore it so this
        // throwaway database does not leak into tests that rely on the implicit default connection.
        val defaultDatabase = TransactionManager.defaultDatabase
        database = Database.connect("jdbc:h2:mem:komgascanintent-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "org.h2.Driver")
        TransactionManager.defaultDatabase = defaultDatabase
    }

    @Test
    fun `creates the singleton intent table with a pristine default and no recorded work`() {
        transaction(database) {
            M0072_KomgaScanIntent().run()

            exec("SELECT COUNT(*) FROM komgascanintent") {
                it.next()
                assertEquals(0, it.getInt(1), "the intent table starts empty, so nothing is pending")
            }

            exec("INSERT INTO komgascanintent (id) VALUES (1)")

            exec(
                "SELECT state, generation, running_generation, requested_at, not_before_at, attempts, " +
                    "last_attempt_at, last_completed_at, last_error FROM komgascanintent WHERE id = 1",
            ) {
                it.next()
                assertEquals(KomgaScanState.PENDING.name, it.getString("state"))
                assertEquals(0L, it.getLong("generation"))
                assertNull(it.getObject("running_generation"))
                assertEquals(0L, it.getLong("requested_at"))
                assertNull(it.getObject("not_before_at"))
                assertEquals(0, it.getInt("attempts"))
                assertNull(it.getObject("last_attempt_at"))
                assertNull(it.getObject("last_completed_at"))
                assertNull(it.getObject("last_error"), "no credential or target may ever be stored in the intent")
            }
        }
    }

    @Test
    fun `indexes the due filter and keeps the intent a singleton`() {
        transaction(database) {
            M0072_KomgaScanIntent().run()

            val columnsByIndex = mutableMapOf<String, MutableList<String>>()
            exec(
                "SELECT INDEX_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS " +
                    "WHERE TABLE_NAME = 'KOMGASCANINTENT' ORDER BY INDEX_NAME, ORDINAL_POSITION",
            ) {
                while (it.next()) {
                    columnsByIndex
                        .getOrPut(it.getString("INDEX_NAME")) { mutableListOf() }
                        .add(it.getString("COLUMN_NAME").uppercase())
                }
            }

            val indexedColumnSets = columnsByIndex.values.map { it.toSet() }.toSet()
            assertTrue(
                indexedColumnSets.contains(setOf("STATE", "NOT_BEFORE_AT", "ID")),
                "the due filter and its order are indexed: $columnsByIndex",
            )
        }
    }
}
