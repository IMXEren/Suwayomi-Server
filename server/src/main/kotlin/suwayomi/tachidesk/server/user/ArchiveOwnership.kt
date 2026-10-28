package suwayomi.tachidesk.server.user

/** The original account owns the single shared archive after multi-user migration. */
const val ARCHIVE_OWNER_ID = 1

fun requireArchiveOwner(userId: Int) {
    require(userId == ARCHIVE_OWNER_ID) { "Only the built-in admin can manage the shared archive" }
}
