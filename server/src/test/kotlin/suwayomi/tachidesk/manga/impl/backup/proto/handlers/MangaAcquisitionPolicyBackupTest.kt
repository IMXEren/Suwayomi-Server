package suwayomi.tachidesk.manga.impl.backup.proto.handlers

import kotlinx.serialization.protobuf.ProtoBuf
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupManga
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import java.util.Date

// The per-series acquisition policy is a Suwayomi extension field on BackupManga. It must travel
// through a .tachibk round trip while old Mihon/Yokai backups, which lack the field, keep the stored
// override instead of resetting it. A stored override is null for a series that inherits the global
// default, which is why the field carries its own presence marker: without it an explicit inherit
// could not be told apart from a legacy backup.
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

    private fun storedOverride(url: String): MangaAcquisitionPolicy? =
        transaction {
            MangaTable
                .selectAll()
                .where { MangaTable.url eq url }
                .first()[MangaTable.acquisitionPolicy]
                ?.let { MangaAcquisitionPolicy.valueOf(it) }
        }

    private fun effectivePolicy(url: String): MangaAcquisitionPolicy =
        transaction { MangaType(MangaTable.selectAll().where { MangaTable.url eq url }.first()) }.acquisitionPolicy

    private fun restore(manga: BackupManga) {
        BackupMangaHandler.restore(manga, emptyMap(), emptyMap(), errors, backupFlags)
        assertTrue(errors.isEmpty(), errors.joinToString())
    }

    private fun restorePolicy(
        url: String,
        policy: String? = null,
        present: Boolean = false,
    ) {
        restore(
            BackupManga(
                source = 42,
                url = url,
                title = "Manga",
                acquisitionPolicy = policy,
                acquisitionPolicyPresent = present,
            ),
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
        assertFalse(decoded.acquisitionPolicyPresent, "a field that was never set is not authoritative")
    }

    @Test
    fun `policy survives a protobuf wire round trip`() {
        val bytes =
            ProtoBuf.encodeToByteArray(
                Backup.serializer(),
                Backup(
                    backupManga =
                        listOf(
                            BackupManga(
                                source = 42,
                                url = "/m",
                                title = "Manga",
                                acquisitionPolicy = "PAUSED",
                                acquisitionPolicyPresent = true,
                            ),
                        ),
                ),
            )

        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes).backupManga.single()
        assertEquals("PAUSED", decoded.acquisitionPolicy)
        assertTrue(decoded.acquisitionPolicyPresent, "the presence marker must survive the wire")
    }

    @Test
    fun `new backup round-trips the acquisition override`() {
        restorePolicy("/m", policy = "AUTO", present = true)
        assertEquals(MangaAcquisitionPolicy.AUTO, storedOverride("/m"))

        val exported = BackupMangaHandler.backup(backupFlags).single { it.url == "/m" }
        assertEquals("AUTO", exported.acquisitionPolicy)
        assertTrue(exported.acquisitionPolicyPresent, "an export always marks the field as authoritative")
    }

    @Test
    fun `a backup restores an explicit inherit so the series follows the default`() {
        restorePolicy("/m", policy = "AUTO", present = true)
        assertEquals(MangaAcquisitionPolicy.AUTO, storedOverride("/m"))

        restorePolicy("/m", present = true)
        assertNull(storedOverride("/m"), "an authoritative absent policy must clear the override")
        assertEquals(serverConfig.archiveDefaultAcquisitionPolicy.value, effectivePolicy("/m").name)
    }

    @Test
    fun `a new series without an authoritative policy inherits the default`() {
        restore(BackupManga(source = 42, url = "/old", title = "Old"))

        assertNull(storedOverride("/old"), "a legacy backup must not store a fabricated default")
        assertEquals(MangaAcquisitionPolicy.MANUAL, effectivePolicy("/old"), "the effective policy is the global default")
    }

    @Test
    fun `a legacy or unknown policy field does not overwrite an existing override`() {
        restorePolicy("/m", policy = "AUTO", present = true)
        assertEquals(MangaAcquisitionPolicy.AUTO, storedOverride("/m"))

        // an old backup without the field must not reset it to the default
        restore(BackupManga(source = 42, url = "/m", title = "Manga"))
        assertEquals(MangaAcquisitionPolicy.AUTO, storedOverride("/m"))

        // an unknown value from a newer client is ignored defensively, even when marked authoritative
        restorePolicy("/m", policy = "SOMETHING_NEW", present = true)
        assertEquals(MangaAcquisitionPolicy.AUTO, storedOverride("/m"))
    }

    @Test
    fun `an unmarked legacy policy cannot overwrite an existing override`() {
        restorePolicy("/m", policy = "AUTO", present = true)
        assertEquals(MangaAcquisitionPolicy.AUTO, storedOverride("/m"))

        // an old backup can carry a value from before the presence marker existed; it is not an
        // authoritative change, so the stored override must survive
        restorePolicy("/m", policy = "PAUSED")
        assertEquals(MangaAcquisitionPolicy.AUTO, storedOverride("/m"))
    }

    @Test
    fun `a marked policy updates an existing override`() {
        restorePolicy("/m", policy = "AUTO", present = true)
        assertEquals(MangaAcquisitionPolicy.AUTO, storedOverride("/m"))

        restorePolicy("/m", policy = "PAUSED", present = true)
        assertEquals(MangaAcquisitionPolicy.PAUSED, storedOverride("/m"))
    }

    @Test
    fun `a legacy non-null policy is preserved for a new series`() {
        restorePolicy("/new", policy = "PAUSED")

        assertEquals(
            MangaAcquisitionPolicy.PAUSED,
            storedOverride("/new"),
            "an old explicit choice on a newly imported series is kept",
        )
    }
}
