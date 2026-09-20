package suwayomi.tachidesk.manga.impl.backup.proto.handlers

import kotlinx.serialization.protobuf.ProtoBuf
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupManga
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import java.util.Date

// The per-series acquisition policy is a Suwayomi extension field on BackupManga. It must travel
// through a .tachibk round trip while old Mihon/Yokai backups, which lack the field, keep the
// stored or default policy instead of resetting it.
class MangaAcquisitionPolicyBackupTest : ApplicationTest() {
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

    private fun storedPolicy(url: String): MangaAcquisitionPolicy =
        transaction {
            MangaAcquisitionPolicy.valueOf(
                MangaTable.selectAll().where { MangaTable.url eq url }.first()[MangaTable.acquisitionPolicy],
            )
        }

    @AfterEach
    fun tearDown() {
        errors.clear()
        clearTables(MangaTable)
    }

    @Test
    fun `absent policy field decodes as null on the wire`() {
        val bytes =
            ProtoBuf.encodeToByteArray(
                Backup.serializer(),
                Backup(backupManga = listOf(BackupManga(source = 42, url = "/m", title = "Manga"))),
            )

        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes).backupManga.single()
        assertNull(decoded.acquisitionPolicy)
    }

    @Test
    fun `policy survives a protobuf wire round trip`() {
        val bytes =
            ProtoBuf.encodeToByteArray(
                Backup.serializer(),
                Backup(
                    backupManga =
                        listOf(
                            BackupManga(source = 42, url = "/m", title = "Manga", acquisitionPolicy = "PAUSED"),
                        ),
                ),
            )

        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes).backupManga.single()
        assertEquals("PAUSED", decoded.acquisitionPolicy)
    }

    @Test
    fun `new backup round-trips the acquisition policy`() {
        BackupMangaHandler.restore(
            BackupManga(source = 42, url = "/m", title = "Manga", acquisitionPolicy = "AUTO"),
            emptyMap(),
            emptyMap(),
            errors,
            backupFlags,
        )
        assertTrue(errors.isEmpty(), errors.joinToString())
        assertEquals(MangaAcquisitionPolicy.AUTO, storedPolicy("/m"))

        val exported = BackupMangaHandler.backup(backupFlags).single { it.url == "/m" }
        assertEquals("AUTO", exported.acquisitionPolicy)
    }

    @Test
    fun `present policy field updates an existing manga`() {
        BackupMangaHandler.restore(
            BackupManga(source = 42, url = "/m", title = "Manga", acquisitionPolicy = "MANUAL"),
            emptyMap(),
            emptyMap(),
            errors,
            backupFlags,
        )
        BackupMangaHandler.restore(
            BackupManga(source = 42, url = "/m", title = "Manga", acquisitionPolicy = "PAUSED"),
            emptyMap(),
            emptyMap(),
            errors,
            backupFlags,
        )
        assertTrue(errors.isEmpty(), errors.joinToString())
        assertEquals(MangaAcquisitionPolicy.PAUSED, storedPolicy("/m"))
    }

    @Test
    fun `absent policy field falls back to the default for a new manga`() {
        BackupMangaHandler.restore(
            BackupManga(source = 42, url = "/old", title = "Old"),
            emptyMap(),
            emptyMap(),
            errors,
            backupFlags,
        )
        assertTrue(errors.isEmpty(), errors.joinToString())
        assertEquals(MangaAcquisitionPolicy.MANUAL, storedPolicy("/old"))
    }

    @Test
    fun `absent or unknown policy field does not overwrite an existing policy`() {
        BackupMangaHandler.restore(
            BackupManga(source = 42, url = "/m", title = "Manga", acquisitionPolicy = "AUTO"),
            emptyMap(),
            emptyMap(),
            errors,
            backupFlags,
        )
        assertEquals(MangaAcquisitionPolicy.AUTO, storedPolicy("/m"))

        // an old backup without the field must not reset it to the default
        BackupMangaHandler.restore(
            BackupManga(source = 42, url = "/m", title = "Manga"),
            emptyMap(),
            emptyMap(),
            errors,
            backupFlags,
        )
        assertTrue(errors.isEmpty(), errors.joinToString())
        assertEquals(MangaAcquisitionPolicy.AUTO, storedPolicy("/m"))

        // an unknown value from a newer client is ignored defensively
        BackupMangaHandler.restore(
            BackupManga(source = 42, url = "/m", title = "Manga", acquisitionPolicy = "SOMETHING_NEW"),
            emptyMap(),
            emptyMap(),
            errors,
            backupFlags,
        )
        assertTrue(errors.isEmpty(), errors.joinToString())
        assertEquals(MangaAcquisitionPolicy.AUTO, storedPolicy("/m"))
    }
}
