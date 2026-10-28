package suwayomi.tachidesk.server.database.trigger

import org.h2.tools.TriggerAdapter
import java.sql.Connection
import java.sql.ResultSet

/** Keep the shared archive enrolled only for the built-in admin's library. */
@Suppress("unused")
class MirrorAdminArchiveEnrollmentTrigger : TriggerAdapter() {
    override fun fire(
        conn: Connection,
        oldRow: ResultSet?,
        newRow: ResultSet?,
    ) {
        val row = newRow ?: oldRow ?: return
        if (row.getInt("user_id") != 1) return

        val enrolled = newRow?.getBoolean("in_library") ?: false
        val enrolledAt = row.getLong("in_library_at")
        conn.prepareStatement(
            "UPDATE MANGA SET in_library = ?, in_library_at = ? " +
                "WHERE id = ? AND (in_library <> ? OR in_library_at <> ?)",
        ).use {
            it.setBoolean(1, enrolled)
            it.setLong(2, enrolledAt)
            it.setInt(3, row.getInt("manga"))
            it.setBoolean(4, enrolled)
            it.setLong(5, enrolledAt)
            it.executeUpdate()
        }
    }
}
