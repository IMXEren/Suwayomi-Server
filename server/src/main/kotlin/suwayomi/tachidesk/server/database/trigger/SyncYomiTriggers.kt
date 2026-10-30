package suwayomi.tachidesk.server.database.trigger

import org.h2.tools.TriggerAdapter
import java.sql.Connection
import java.sql.ResultSet
import kotlin.random.Random
import kotlin.time.Clock

// M0056, M0066 and M0070 triggers must remain loadable until M0082 replaces the single-user schema.
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

@Suppress("unused")
class UpdateMangaUserVersionTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet,
        newRow: ResultSet,
    ) {
        val isSyncing = newRow.getBoolean("is_syncing")
        val hasChanged =
            oldRow.getBoolean("in_library") != newRow.getBoolean("in_library") ||
                oldRow.getLong("in_library_at") != newRow.getLong("in_library_at")
        if (!isSyncing && hasChanged) {
            newRow.updateLong("version", newRow.getLong("version") + 1)
        }
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

@Deprecated("Removed, but needs to be kept due to migrations")
class UpdateChapterAndMangaVersionTrigger : DeprecatedTrigger()

@Suppress("unused")
class UpdateChapterUserVersionTrigger : TriggerAdapter() {
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

@Suppress("unused")
class UpdateMangaUserLastModifiedAtTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet?,
        newRow: ResultSet,
    ) {
        newRow.stampLastModifiedAt(oldRow, listOf("in_library", "in_library_at", "version"))
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

@Deprecated("Removed, but needs to be kept due to migrations")
class UpdateChapterLastModifiedAtTrigger : DeprecatedTrigger()

@Suppress("unused")
class UpdateChapterUserLastModifiedAtTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet?,
        newRow: ResultSet,
    ) {
        newRow.stampLastModifiedAt(oldRow, listOf("read", "bookmark", "last_page_read", "version"))
    }
}

@Suppress("unused")
class UpdateMangaBumpUserVersionsTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet,
        newRow: ResultSet,
    ) {
        val sharedChanged =
            oldRow.getString("url") != newRow.getString("url") ||
                oldRow.getString("description") != newRow.getString("description")
        val archiveChanged =
            oldRow.getString("acquisition_policy") != newRow.getString("acquisition_policy") ||
                oldRow.getObject("accepted_revision_retention") != newRow.getObject("accepted_revision_retention")
        if (!sharedChanged && !archiveChanged) return

        val sql =
            "UPDATE MANGAUSER SET version = version + 1, last_modified_at = ? " +
                "WHERE manga = ? AND NOT is_syncing" + if (sharedChanged) "" else " AND user_id = 1"
        conn.prepareStatement(sql).use {
            it.setLong(1, Clock.System.now().epochSeconds)
            it.setInt(2, newRow.getInt("id"))
            it.executeUpdate()
        }
    }
}

private fun Connection.bumpMangaUserVersion(
    mangaId: Int,
    userId: Int,
) {
    // language=h2
    prepareStatement(
        "UPDATE MANGAUSER SET version = version + 1 WHERE manga = ? AND user_id = ? AND NOT is_syncing",
    ).use {
        it.setInt(1, mangaId)
        it.setInt(2, userId)
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
        conn.bumpMangaUserVersion(newRow.getInt("manga"), newRow.getInt("user_id"))
    }
}

@Suppress("unused")
class DeleteMangaCategoryUpdateVersionTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet,
        newRow: ResultSet?,
    ) {
        conn.bumpMangaUserVersion(oldRow.getInt("manga"), oldRow.getInt("user_id"))
    }
}

@Suppress("unused")
class TrackRecordUpdateMangaVersionTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet?,
        newRow: ResultSet?,
    ) {
        val row = (newRow ?: oldRow) ?: return
        conn.bumpMangaUserVersion(row.getInt("manga_id"), row.getInt("user_id"))
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
