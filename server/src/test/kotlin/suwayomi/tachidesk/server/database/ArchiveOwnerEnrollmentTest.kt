package suwayomi.tachidesk.server.database

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.global.model.table.UserAccountTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.createLibraryManga
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchiveOwnerEnrollmentTest : ApplicationTest() {
    @Test
    fun `only the built-in admin's library enrolls a manga into the shared archive`() {
        val otherId =
            transaction {
                val account =
                    UserAccountTable.insertAndGetId {
                        it[username] = "archive-owner-enrollment-test"
                        it[password] = "test"
                    }
                account.value
            }
        val adminManga = createLibraryManga("admin-archive-enrollment")
        val otherManga = createLibraryManga("other-archive-enrollment", otherId)
        try {
            fun enrolled(mangaId: Int) =
                transaction {
                    MangaTable.selectAll().where { MangaTable.id eq mangaId }.single()[MangaTable.inLibrary]
                }

            assertEquals(true, enrolled(adminManga))
            assertEquals(false, enrolled(otherManga))

            transaction {
                MangaUserTable.insertAndGetId {
                    it[manga] = otherManga
                    it[user] = 1
                    it[inLibrary] = true
                }
            }
            assertEquals(true, enrolled(otherManga))

            transaction {
                MangaUserTable.update({ (MangaUserTable.manga eq otherManga) and (MangaUserTable.user eq 1) }) {
                    it[inLibrary] = false
                }
            }
            assertEquals(false, enrolled(otherManga), "another user's library must not retain admin archive enrollment")
        } finally {
            transaction {
                MangaTable.deleteWhere { MangaTable.id eq adminManga }
                MangaTable.deleteWhere { MangaTable.id eq otherManga }
                UserAccountTable.deleteWhere { UserAccountTable.id eq otherId }
            }
        }
    }
}
