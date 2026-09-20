package suwayomi.tachidesk.server.database.trigger

import org.h2.tools.TriggerAdapter
import java.sql.Connection
import java.sql.ResultSet
import kotlin.random.Random
import kotlin.time.Clock

// The watched column set is passed in so the M0056 trigger (which was created before
// acquisition_policy existed) and its M0066 replacement can share the bump logic without the older
// one ever reading a column that does not exist yet during the ordered migration sequence.
private fun ResultSet.bumpVersionOnWatchedColumnChange(
    oldRow: ResultSet,
    watchedColumns: List<String>,
) {
    if (getBoolean("is_syncing")) return
    if (watchedColumns.all { oldRow.getObject(it) == getObject(it) }) return

    updateLong("version", getLong("version") + 1)
}

private val MANGA_VERSION_WATCHED_COLUMNS = listOf("url", "description", "in_library")
private val MANGA_VERSION_WATCHED_COLUMNS_WITH_ACQUISITION_POLICY =
    MANGA_VERSION_WATCHED_COLUMNS + "acquisition_policy"

// M0070 adds accepted_revision_retention, so its replacement watches that column too.
private val MANGA_VERSION_WATCHED_COLUMNS_WITH_ARCHIVAL_RETENTION =
    MANGA_VERSION_WATCHED_COLUMNS_WITH_ACQUISITION_POLICY + "accepted_revision_retention"

@Suppress("unused")
class UpdateMangaVersionTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet,
        newRow: ResultSet,
    ) {
        newRow.bumpVersionOnWatchedColumnChange(oldRow, MANGA_VERSION_WATCHED_COLUMNS)
    }
}

// M0066 replacement for UpdateMangaVersionTrigger; additionally watches acquisition_policy.
@Suppress("unused")
class UpdateMangaVersionWithAcquisitionPolicyTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet,
        newRow: ResultSet,
    ) {
        newRow.bumpVersionOnWatchedColumnChange(oldRow, MANGA_VERSION_WATCHED_COLUMNS_WITH_ACQUISITION_POLICY)
    }
}

// M0070 replacement for UpdateMangaVersionWithAcquisitionPolicyTrigger; additionally watches
// accepted_revision_retention.
@Suppress("unused")
class UpdateMangaVersionWithArchivalRetentionTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet,
        newRow: ResultSet,
    ) {
        newRow.bumpVersionOnWatchedColumnChange(oldRow, MANGA_VERSION_WATCHED_COLUMNS_WITH_ARCHIVAL_RETENTION)
    }
}

@Suppress("unused")
class UpdateChapterAndMangaVersionTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet,
        newRow: ResultSet,
    ) {
        val isSyncing = newRow.getBoolean("is_syncing")
        val hasChanged =
            oldRow.getBoolean("read") != newRow.getBoolean("read") ||
                oldRow.getBoolean("bookmark") != newRow.getBoolean("bookmark") ||
                oldRow.getInt("last_page_read") != newRow.getInt("last_page_read")

        if (!isSyncing && hasChanged) {
            val currentVersion = newRow.getLong("version")
            newRow.updateLong("version", currentVersion + 1)
        }
    }
}

// Sync restores keep the timestamp they write; an update only counts when a synced column changed.
private fun ResultSet.stampLastModifiedAt(
    oldRow: ResultSet?,
    watchedColumns: List<String>,
) {
    if (getBoolean("is_syncing")) return

    if (oldRow != null && watchedColumns.all { oldRow.getObject(it) == getObject(it) }) return

    updateLong("last_modified_at", Clock.System.now().epochSeconds)
}

private val MANGA_LAST_MODIFIED_WATCHED_COLUMNS = listOf("url", "description", "in_library", "version")
private val MANGA_LAST_MODIFIED_WATCHED_COLUMNS_WITH_ACQUISITION_POLICY =
    MANGA_LAST_MODIFIED_WATCHED_COLUMNS + "acquisition_policy"
private val MANGA_LAST_MODIFIED_WATCHED_COLUMNS_WITH_ARCHIVAL_RETENTION =
    MANGA_LAST_MODIFIED_WATCHED_COLUMNS_WITH_ACQUISITION_POLICY + "accepted_revision_retention"

@Suppress("unused")
class UpdateMangaLastModifiedAtTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet?,
        newRow: ResultSet,
    ) {
        newRow.stampLastModifiedAt(oldRow, MANGA_LAST_MODIFIED_WATCHED_COLUMNS)
    }
}

// M0066 replacement for UpdateMangaLastModifiedAtTrigger; additionally watches acquisition_policy.
@Suppress("unused")
class UpdateMangaLastModifiedAtWithAcquisitionPolicyTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet?,
        newRow: ResultSet,
    ) {
        newRow.stampLastModifiedAt(oldRow, MANGA_LAST_MODIFIED_WATCHED_COLUMNS_WITH_ACQUISITION_POLICY)
    }
}

// M0070 replacement for UpdateMangaLastModifiedAtWithAcquisitionPolicyTrigger; additionally watches
// accepted_revision_retention.
@Suppress("unused")
class UpdateMangaLastModifiedAtWithArchivalRetentionTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet?,
        newRow: ResultSet,
    ) {
        newRow.stampLastModifiedAt(oldRow, MANGA_LAST_MODIFIED_WATCHED_COLUMNS_WITH_ARCHIVAL_RETENTION)
    }
}

@Suppress("unused")
class UpdateChapterLastModifiedAtTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet?,
        newRow: ResultSet,
    ) {
        newRow.stampLastModifiedAt(oldRow, listOf("read", "bookmark", "last_page_read", "version"))
    }
}

private fun Connection.bumpMangaVersion(mangaId: Int) {
    prepareStatement(
        "UPDATE MANGA SET version = version + 1 WHERE id = ? AND NOT is_syncing",
    ).use {
        it.setInt(1, mangaId)
        it.executeUpdate()
    }
}

@Suppress("unused")
class InsertMangaCategoryUpdateVersionTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet?,
        newRow: ResultSet,
    ) {
        conn.bumpMangaVersion(newRow.getInt("manga"))
    }
}

@Suppress("unused")
class DeleteMangaCategoryUpdateVersionTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet,
        newRow: ResultSet?,
    ) {
        conn.bumpMangaVersion(oldRow.getInt("manga"))
    }
}

@Suppress("unused")
class TrackRecordUpdateMangaVersionTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet?,
        newRow: ResultSet?,
    ) {
        val mangaId = (newRow ?: oldRow)?.getInt("manga_id") ?: return
        conn.bumpMangaVersion(mangaId)
    }
}

@Suppress("unused")
class InsertCategoryUidTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet?,
        newRow: ResultSet,
    ) {
        if (newRow.getLong("uid") == 0L) {
            newRow.updateLong("uid", Random.nextLong(1, Long.MAX_VALUE))
        }

        if (newRow.getLong("last_modified_at") == 0L) {
            newRow.updateLong(
                "last_modified_at",
                Clock.System.now().epochSeconds,
            )
        }
    }
}

@Suppress("unused")
class UpdateCategoryVersionTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet,
        newRow: ResultSet,
    ) {
        val isSyncing = newRow.getBoolean("is_syncing")
        val hasChanged =
            oldRow.getString("name") != newRow.getString("name") ||
                oldRow.getInt("sort_order") != newRow.getInt("sort_order")

        if (!isSyncing && hasChanged) {
            val currentVersion = newRow.getLong("version")
            newRow.updateLong("version", currentVersion + 1)

            newRow.updateLong(
                "last_modified_at",
                Clock.System.now().epochSeconds,
            )
        }
    }
}
