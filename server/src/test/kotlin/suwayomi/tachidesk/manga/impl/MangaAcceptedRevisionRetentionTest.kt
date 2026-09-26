package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.protobuf.ProtoBuf
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
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
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupMangaHandler
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupManga
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createLibraryManga
import java.util.Date
import java.util.concurrent.ExecutionException

// Per-series accepted-revision retention: the nullable override, its effective value, the GraphQL
// surface, validation and the backup field.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MangaAcceptedRevisionRetentionTest : ApplicationTest() {
    private val backupFlags =
        BackupFlags(
            includeManga = true,
            includeCategories = false,
            includeChapters = false,
            includeTracking = false,
            includeHistory = false,
            includeClientData = false,
            includeServerSettings = false,
        )

    private val errors = mutableListOf<Pair<Date, String>>()

    private fun storedOverride(mangaId: Int): Int? =
        transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()[MangaTable.acceptedRevisionRetention] }

    private fun readManga(mangaId: Int): MangaType =
        transaction { MangaType(MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()) }

    private fun updateRetention(
        mangaId: Int,
        retention: Int?,
        inherit: Boolean = false,
    ): MangaType =
        MangaMutation()
            .updateManga(
                MangaMutation.UpdateMangaInput(
                    id = mangaId,
                    patch =
                        MangaMutation.UpdateMangaPatch(
                            acceptedRevisionRetention = retention,
                            inheritAcceptedRevisionRetention = inherit,
                        ),
                ),
            ).get()!!
            .manga

    @Test
    fun `an unset override inherits the global default`() {
        val mangaId = createLibraryManga("RETENTION_DEFAULT")

        assertNull(storedOverride(mangaId), "an unset per-series override must stay null")
        val manga = readManga(mangaId)
        assertNull(manga.acceptedRevisionRetention)
        assertEquals(serverConfig.acceptedRevisionRetention.value, manga.effectiveAcceptedRevisionRetention)
        assertEquals(3, serverConfig.acceptedRevisionRetention.value, "the global default stays 3")
        assertEquals(serverConfig.acceptedRevisionRetention.value, ChapterRevision.effectiveAcceptedRevisionRetention(mangaId))
    }

    @Test
    fun `an explicit override round-trips and drives the effective value`() {
        val mangaId = createLibraryManga("RETENTION_OVERRIDE")

        val zero = updateRetention(mangaId, 0)
        assertEquals(0, zero.acceptedRevisionRetention)
        assertEquals(0, zero.effectiveAcceptedRevisionRetention, "0 keeps only the active revision")
        assertEquals(0, storedOverride(mangaId))

        val unlimited = updateRetention(mangaId, ChapterRevision.UNLIMITED_ACCEPTED_REVISION_RETENTION)
        assertEquals(-1, unlimited.acceptedRevisionRetention)
        assertEquals(-1, unlimited.effectiveAcceptedRevisionRetention)
        assertEquals(-1, ChapterRevision.effectiveAcceptedRevisionRetention(mangaId))

        val inherited = updateRetention(mangaId, null, inherit = true)
        assertNull(inherited.acceptedRevisionRetention, "clearing the override goes back to inheriting")
        assertNull(storedOverride(mangaId))
        assertEquals(serverConfig.acceptedRevisionRetention.value, inherited.effectiveAcceptedRevisionRetention)
    }

    @Test
    fun `an out-of-range override is rejected`() {
        val mangaId = createLibraryManga("RETENTION_INVALID")

        val failure = assertThrows<ExecutionException> { updateRetention(mangaId, -2) }
        assertTrue(failure.cause is IllegalArgumentException)
        assertNull(storedOverride(mangaId), "a rejected value must never be stored")
    }

    @Test
    fun `a bulk update applies the override to every id in one statement`() {
        val first = createLibraryManga("RETENTION_BULK_A")
        val second = createLibraryManga("RETENTION_BULK_B")

        MangaMutation()
            .updateMangas(
                MangaMutation.UpdateMangasInput(
                    ids = listOf(first, second),
                    patch = MangaMutation.UpdateMangaPatch(acceptedRevisionRetention = 5),
                ),
            ).get()

        assertEquals(5, storedOverride(first))
        assertEquals(5, storedOverride(second))
        assertEquals(
            5,
            transaction {
                MangaTable.selectAll().where { MangaTable.id inList listOf(first, second) }.first()[MangaTable.acceptedRevisionRetention]
            },
        )
    }

    private fun seedRetention(
        url: String,
        retention: Int,
    ): Int {
        BackupMangaHandler.restore(
            BackupManga(
                source = 42,
                url = url,
                title = "Manga",
                acceptedRevisionRetention = retention,
                acceptedRevisionRetentionPresent = true,
            ),
            emptyMap(),
            emptyMap(),
            errors,
            backupFlags,
        )
        assertTrue(errors.isEmpty(), errors.joinToString())
        return transaction {
            MangaTable
                .selectAll()
                .where { MangaTable.url eq url }
                .first()[MangaTable.id]
                .value
        }
    }

    @Test
    fun `backup carries the override on a dedicated field and restores it`() {
        val bytes =
            ProtoBuf.encodeToByteArray(
                Backup.serializer(),
                Backup(
                    backupManga =
                        listOf(
                            BackupManga(
                                source = 42,
                                url = "/r",
                                title = "Manga",
                                acceptedRevisionRetention = -1,
                                acceptedRevisionRetentionPresent = true,
                            ),
                        ),
                ),
            )

        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes).backupManga.single()
        assertEquals(-1, decoded.acceptedRevisionRetention)
        assertTrue(decoded.acceptedRevisionRetentionPresent, "the presence marker must survive the wire")

        val mangaId = seedRetention("/r", 0)

        val exported = BackupMangaHandler.backup(backupFlags).single { it.url == "/r" }
        assertEquals(0, exported.acceptedRevisionRetention)
        assertTrue(exported.acceptedRevisionRetentionPresent, "an export always marks the field as authoritative")
        assertEquals(0, storedOverride(mangaId))
    }

    @Test
    fun `an old backup without the presence marker keeps the stored override`() {
        val mangaId = seedRetention("/r", 7)

        // an old backup that carries a value but not the marker is still a legacy backup
        BackupMangaHandler.restore(
            BackupManga(source = 42, url = "/r", title = "Manga", acceptedRevisionRetention = 2),
            emptyMap(),
            emptyMap(),
            errors,
            backupFlags,
        )
        assertTrue(errors.isEmpty(), errors.joinToString())
        assertEquals(7, storedOverride(mangaId), "a legacy backup must not overwrite the stored override")

        // an old backup without the field at all is the same case
        BackupMangaHandler.restore(
            BackupManga(source = 42, url = "/r", title = "Manga"),
            emptyMap(),
            emptyMap(),
            errors,
            backupFlags,
        )
        assertEquals(7, storedOverride(mangaId), "an absent field must not wipe the stored override")

        // an out-of-range value marked as authoritative is still ignored defensively
        BackupMangaHandler.restore(
            BackupManga(
                source = 42,
                url = "/r",
                title = "Manga",
                acceptedRevisionRetention = -5,
                acceptedRevisionRetentionPresent = true,
            ),
            emptyMap(),
            emptyMap(),
            errors,
            backupFlags,
        )
        assertEquals(7, storedOverride(mangaId), "an out-of-range value must be ignored")
    }

    @Test
    fun `an authoritative inherit marker clears the stored override`() {
        val mangaId = seedRetention("/r", 5)

        BackupMangaHandler.restore(
            BackupManga(source = 42, url = "/r", title = "Manga", acceptedRevisionRetentionPresent = true),
            emptyMap(),
            emptyMap(),
            errors,
            backupFlags,
        )
        assertTrue(errors.isEmpty(), errors.joinToString())
        assertNull(storedOverride(mangaId), "an explicit inherit must clear the override")
        assertEquals(serverConfig.acceptedRevisionRetention.value, readManga(mangaId).effectiveAcceptedRevisionRetention)
    }

    @AfterEach
    fun tearDown() {
        errors.clear()
        clearTables(MangaMetaTable, MangaTable)
    }
}
