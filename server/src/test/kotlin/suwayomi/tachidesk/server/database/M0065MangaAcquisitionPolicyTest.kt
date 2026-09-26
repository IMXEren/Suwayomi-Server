package suwayomi.tachidesk.server.database

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.server.database.migration.M0065_MangaAcquisitionPolicy
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class M0065MangaAcquisitionPolicyTest : ApplicationTest() {
    private fun JdbcTransaction.policyOf(id: Long): String =
        exec("SELECT acquisition_policy FROM manga WHERE id = $id") {
            it.next()
            it.getString("acquisition_policy")
        }!!

    @Test
    fun `adds the acquisition policy column with a compatibility-safe default`() {
        // Database.connect makes the connected database the global default; restore it so this
        // throwaway database does not leak into tests that rely on the implicit default connection.
        val defaultDatabase = TransactionManager.defaultDatabase
        val database = Database.connect("jdbc:h2:mem:acquisitionpolicy-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "org.h2.Driver")
        TransactionManager.defaultDatabase = defaultDatabase

        transaction(database) {
            exec("CREATE TABLE manga (id BIGINT PRIMARY KEY, title VARCHAR, url VARCHAR)")
            exec("INSERT INTO manga (id, title, url) VALUES (1, 'Existing', '/existing')")

            M0065_MangaAcquisitionPolicy().run()

            assertEquals(
                MangaAcquisitionPolicy.MANUAL.name,
                policyOf(1),
                "pre-existing rows adopt the compatibility-safe default",
            )

            exec("INSERT INTO manga (id, title, url) VALUES (2, 'New', '/new')")
            assertEquals(
                MangaAcquisitionPolicy.MANUAL.name,
                policyOf(2),
                "rows inserted after the migration adopt the default",
            )
        }
    }
}
