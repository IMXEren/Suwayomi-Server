package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.graphql.mutations.MangaMutation
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createLibraryManga

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MangaAcquisitionPolicyTest : ApplicationTest() {
    private fun storedPolicy(mangaId: Int): MangaAcquisitionPolicy =
        transaction {
            MangaAcquisitionPolicy.valueOf(
                MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()[MangaTable.acquisitionPolicy],
            )
        }

    private fun readManga(mangaId: Int): MangaType =
        transaction { MangaType(MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()) }

    private fun updatePolicy(
        mangaId: Int,
        policy: MangaAcquisitionPolicy,
    ): MangaType =
        MangaMutation()
            .updateManga(
                MangaMutation.UpdateMangaInput(
                    id = mangaId,
                    patch = MangaMutation.UpdateMangaPatch(acquisitionPolicy = policy),
                ),
            ).get()!!
            .manga

    @Test
    fun `defaults to MANUAL for existing manga`() {
        val mangaId = createLibraryManga("ACQUISITION_POLICY_DEFAULT")

        assertEquals(MangaAcquisitionPolicy.MANUAL, storedPolicy(mangaId))
        assertEquals(MangaAcquisitionPolicy.MANUAL, readManga(mangaId).acquisitionPolicy)
    }

    @Test
    fun `updateManga round-trips the acquisition policy without touching other fields`() {
        val mangaId = createLibraryManga("ACQUISITION_POLICY_UPDATE")

        val auto = updatePolicy(mangaId, MangaAcquisitionPolicy.AUTO)
        assertEquals(MangaAcquisitionPolicy.AUTO, auto.acquisitionPolicy)
        assertEquals(MangaAcquisitionPolicy.AUTO, storedPolicy(mangaId))
        assertTrue(auto.inLibrary, "updating the acquisition policy must not clear inLibrary")

        val paused = updatePolicy(mangaId, MangaAcquisitionPolicy.PAUSED)
        assertEquals(MangaAcquisitionPolicy.PAUSED, paused.acquisitionPolicy)
        assertEquals(MangaAcquisitionPolicy.PAUSED, storedPolicy(mangaId))
    }

    @AfterEach
    internal fun tearDown() {
        clearTables(
            MangaMetaTable,
            MangaTable,
        )
    }
}
