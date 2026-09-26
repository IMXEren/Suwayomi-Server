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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import suwayomi.tachidesk.graphql.mutations.MangaMutation
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.util.concurrent.ExecutionException

// The per-series acquisition policy is an optional override: a series without one inherits the
// configurable global default, and an override - MANUAL included - always wins over it.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MangaAcquisitionPolicyTest : ApplicationTest() {
    private val defaultPolicy = serverConfig.archiveDefaultAcquisitionPolicy.value

    private fun storedOverride(mangaId: Int): MangaAcquisitionPolicy? =
        transaction {
            MangaTable
                .selectAll()
                .where { MangaTable.id eq mangaId }
                .first()[MangaTable.acquisitionPolicy]
                ?.let { MangaAcquisitionPolicy.valueOf(it) }
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

    private fun clearPolicy(mangaId: Int): MangaType =
        MangaMutation()
            .updateManga(
                MangaMutation.UpdateMangaInput(
                    id = mangaId,
                    patch = MangaMutation.UpdateMangaPatch(inheritAcquisitionPolicy = true),
                ),
            ).get()!!
            .manga

    private fun createCandidateState(mangaId: Int): String =
        transaction {
            val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
            val chapters =
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga eq mangaId }
                    .orderBy(ChapterTable.sourceOrder)
                    .map { ChapterTable.toDataClass(it) }

            ChapterRevision.createCandidatesForNewChapters(mangaEntry, chapters, 100)

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.manga eq mangaId }
                .first()
                .let { ChapterRevisionTable.toDataClass(it) }
                .acquisitionState
                .name
        }

    @Test
    fun `a new series inherits the global default instead of storing one`() {
        val mangaId = createLibraryManga("ACQUISITION_POLICY_DEFAULT")

        assertNull(storedOverride(mangaId), "a series without an override must store null")
        val manga = readManga(mangaId)
        assertNull(manga.acquisitionPolicyOverride)
        assertEquals(MangaAcquisitionPolicy.MANUAL, manga.acquisitionPolicy, "the effective policy is the global default")
    }

    @Test
    fun `an explicit override wins over the default and clearing restores inheritance`() {
        val mangaId = createLibraryManga("ACQUISITION_POLICY_OVERRIDE")

        // an explicit MANUAL is an override, not the default: it must survive a changed default
        serverConfig.archiveDefaultAcquisitionPolicy.value = "PAUSED"

        val manual = updatePolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        assertEquals(MangaAcquisitionPolicy.MANUAL, manual.acquisitionPolicyOverride)
        assertEquals(MangaAcquisitionPolicy.MANUAL, manual.acquisitionPolicy)
        assertEquals(MangaAcquisitionPolicy.MANUAL, storedOverride(mangaId))

        val cleared = clearPolicy(mangaId)
        assertNull(storedOverride(mangaId), "clearing must remove the stored override")
        assertNull(cleared.acquisitionPolicyOverride)
        assertEquals(MangaAcquisitionPolicy.PAUSED, cleared.acquisitionPolicy, "the effective policy follows the default again")
    }

    @Test
    fun `the effective policy follows a default change while the override is unset`() {
        val mangaId = createLibraryManga("ACQUISITION_POLICY_DYNAMIC")
        assertEquals(MangaAcquisitionPolicy.MANUAL, readManga(mangaId).acquisitionPolicy)

        serverConfig.archiveDefaultAcquisitionPolicy.value = "AUTO"
        assertEquals(MangaAcquisitionPolicy.AUTO, readManga(mangaId).acquisitionPolicy)
        assertNull(storedOverride(mangaId), "changing the default must not write an override")

        serverConfig.archiveDefaultAcquisitionPolicy.value = "PAUSED"
        assertEquals(MangaAcquisitionPolicy.PAUSED, readManga(mangaId).acquisitionPolicy)
    }

    @Test
    fun `the override and the clear flag are mutually exclusive`() {
        val mangaId = createLibraryManga("ACQUISITION_POLICY_CONFLICT")

        val failure =
            assertThrows<ExecutionException> {
                MangaMutation()
                    .updateManga(
                        MangaMutation.UpdateMangaInput(
                            id = mangaId,
                            patch =
                                MangaMutation.UpdateMangaPatch(
                                    acquisitionPolicy = MangaAcquisitionPolicy.AUTO,
                                    inheritAcquisitionPolicy = true,
                                ),
                        ),
                    ).get()
            }
        assertTrue(failure.cause is IllegalArgumentException)
        assertNull(storedOverride(mangaId), "a rejected patch must not be stored")
    }

    @Test
    fun `candidate creation uses the inherited policy`() {
        val autoId = createLibraryManga("ACQUISITION_POLICY_INHERIT_AUTO").also { createChapters(it, 1, read = false) }
        serverConfig.archiveDefaultAcquisitionPolicy.value = "AUTO"
        assertEquals(ChapterAcquisitionState.QUEUED.name, createCandidateState(autoId), "an inherited AUTO must queue")

        val pausedId = createLibraryManga("ACQUISITION_POLICY_INHERIT_PAUSED").also { createChapters(it, 1, read = false) }
        serverConfig.archiveDefaultAcquisitionPolicy.value = "PAUSED"
        assertEquals(ChapterAcquisitionState.DISCOVERED.name, createCandidateState(pausedId), "an inherited PAUSED stays inert")
    }

    @AfterEach
    internal fun tearDown() {
        serverConfig.archiveDefaultAcquisitionPolicy.value = defaultPolicy
        clearTables(
            ChapterRevisionTable,
            ChapterTable,
            MangaMetaTable,
            MangaTable,
        )
    }
}
