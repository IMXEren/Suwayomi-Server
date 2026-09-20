package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.mutations.ArchiveBootstrapMutation
import suwayomi.tachidesk.graphql.queries.ArchiveBootstrapQuery
import suwayomi.tachidesk.graphql.server.GraphQLSchemaProvider
import suwayomi.tachidesk.manga.impl.Category.createCategory
import suwayomi.tachidesk.manga.impl.CategoryManga.addMangaToCategories
import suwayomi.tachidesk.manga.impl.util.lang.isDuplicateKeyViolation
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapCategoryPolicies
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapCategoryPolicy
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapItemState
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDiscoveryReason
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ArchiveBootstrapItemTable
import suwayomi.tachidesk.manga.model.table.ArchiveBootstrapSessionTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.database.migration.M0074_ArchiveBootstrap
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Migration-level guarantees of the bootstrap schema.
 *
 * Uses a plain throwaway H2 database and the migration's own SQL, so the assertions describe exactly
 * what the migration creates instead of what the Exposed table objects would create.
 */
class ArchiveBootstrapMigrationTest {
    private fun freshDatabase(): java.sql.Connection =
        DriverManager
            .getConnection("jdbc:h2:mem:archive_bootstrap_${System.nanoTime()};DB_CLOSE_DELAY=-1", "sa", "")
            .also { connection -> connection.createStatement().execute(M0074_ArchiveBootstrap().sql) }

    @Test
    fun `creates both bootstrap tables`() {
        freshDatabase().use { connection ->
            val names = mutableSetOf<String>()
            connection
                .createStatement()
                .executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")
                .use { rs ->
                    while (rs.next()) {
                        names += rs.getString(1).uppercase()
                    }
                }

            assertTrue(names.contains("ARCHIVEBOOTSTRAPSESSION"), "session table missing, got $names")
            assertTrue(names.contains("ARCHIVEBOOTSTRAPITEM"), "item table missing, got $names")
        }
    }

    @Test
    fun `enforces a single active session and allows unlimited finished ones`() {
        freshDatabase().use { connection ->
            connection.createStatement().execute(sessionInsert("ACTIVE"))
            // a second non-null marker is refused by the database, not by application code
            assertThrows(Exception::class.java) {
                connection.createStatement().execute(sessionInsert("ACTIVE"))
            }

            // a finished session keeps a null marker, and nulls are distinct in a unique index
            connection.createStatement().execute(sessionInsert(null))
            connection.createStatement().execute(sessionInsert(null))
        }
    }

    @Test
    fun `items cascade with their session and reject unknown sessions`() {
        freshDatabase().use { connection ->
            connection.createStatement().execute(sessionInsert(null))
            val sessionId =
                connection.createStatement().executeQuery("SELECT MAX(ID) FROM ARCHIVEBOOTSTRAPSESSION").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }

            connection.createStatement().execute(itemInsert(sessionId))
            connection.createStatement().execute("DELETE FROM ARCHIVEBOOTSTRAPSESSION WHERE ID = $sessionId")

            val remaining =
                connection
                    .createStatement()
                    .executeQuery("SELECT COUNT(*) FROM ARCHIVEBOOTSTRAPITEM WHERE SESSION = $sessionId")
                    .use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
            assertEquals(0, remaining, "deleting a session must cascade to its items")

            assertThrows(Exception::class.java) {
                connection.createStatement().execute(itemInsert(999_999))
            }
        }
    }

    @Test
    fun `accepts a category snapshot that does not fit a bounded varchar`() {
        freshDatabase().use { connection ->
            connection.createStatement().execute(sessionInsert(null))
            val sessionId =
                connection.createStatement().executeQuery("SELECT MAX(ID) FROM ARCHIVEBOOTSTRAPSESSION").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }

            // a series can belong to more categories than 512 characters can hold, so the snapshot
            // must not be length limited
            val snapshot = (1..200).joinToString(",")
            assertTrue(snapshot.length > 512, "the fixture has to exceed a bounded varchar, was ${snapshot.length}")

            connection.createStatement().execute(
                "INSERT INTO ARCHIVEBOOTSTRAPITEM (session, manga_id, source_id, title, policy, state, attempts, " +
                    "category_ids, updated_at) VALUES ($sessionId, 1, 1, 'series', 'AUTO', 'PENDING', 0, '$snapshot', 1)",
            )

            val stored =
                connection
                    .createStatement()
                    .executeQuery("SELECT category_ids FROM ARCHIVEBOOTSTRAPITEM WHERE SESSION = $sessionId")
                    .use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
            assertEquals(snapshot, stored)
        }
    }

    private fun sessionInsert(marker: String?): String =
        "INSERT INTO ARCHIVEBOOTSTRAPSESSION (state, active_marker, default_policy, category_policy_overrides, " +
            "inter_item_delay_seconds, retry_seconds, max_attempts, started_at, updated_at) " +
            "VALUES ('RUNNING', ${marker?.let { "'$it'" } ?: "NULL"}, 'AUTO', '', 2, 300, 3, 1, 1)"

    private fun itemInsert(sessionId: Int): String =
        "INSERT INTO ARCHIVEBOOTSTRAPITEM (session, manga_id, source_id, title, policy, state, attempts, updated_at) " +
            "VALUES ($sessionId, 1, 1, 'series', 'AUTO', 'PENDING', 0, 1)"
}

/**
 * Behaviour of the durable bootstrap orchestration.
 *
 * These are the phase-0 guarantees: bounded, restart-safe, one series at a time, with the policy and
 * category snapshot frozen at start and a missing extension isolated instead of fatal.
 */
class ArchiveBootstrapTest : ApplicationTest() {
    @AfterEach
    fun cleanUp() {
        transaction {
            ArchiveBootstrap.getActiveSession()?.let { ArchiveBootstrap.cancel(it.id) }
        }
        clearTables(ArchiveBootstrapItemTable, ArchiveBootstrapSessionTable)
        // the settings are process wide, so a test that narrows the retry budget has to hand the
        // declared defaults back or it silently decides for every test that runs after it
        serverConfig.archiveBootstrapRetrySeconds.value = DEFAULT_RETRY_SECONDS
        serverConfig.archiveBootstrapMaxAttempts.value = DEFAULT_MAX_ATTEMPTS
    }

    /**
     * Declared defaults of the process-wide settings these tests narrow.
     *
     * Mirrored here on purpose: the settings are global, so a test that shrinks the retry budget has
     * to hand the declared values back or it silently decides for every later test.
     */
    private companion object {
        const val DEFAULT_RETRY_SECONDS = 300
        const val DEFAULT_MAX_ATTEMPTS = 3
    }

    private fun libraryManga(title: String): Int = createLibraryManga(title)

    private fun start(
        defaultPolicy: MangaAcquisitionPolicy = MangaAcquisitionPolicy.AUTO,
        categoryPolicies: List<ArchiveBootstrapCategoryPolicy> = emptyList(),
        mangaIds: List<Int>? = null,
        originKey: String? = null,
    ): ArchiveBootstrap.StartOutcome =
        ArchiveBootstrap.start(
            ArchiveBootstrap.StartRequest(defaultPolicy, categoryPolicies, mangaIds, originKey),
        )

    /** Every session with its origin, so a test can prove a run was created exactly once. */
    private fun sessionOrigins(): List<Pair<Int, String?>> =
        transaction {
            ArchiveBootstrapSessionTable
                .selectAll()
                .map { it[ArchiveBootstrapSessionTable.id].value to it[ArchiveBootstrapSessionTable.originKey] }
        }

    @Test
    fun `returns the run an origin already started instead of a conflict with itself`() {
        val mangaId = libraryManga("bootstrap-origin-idempotent")
        val origin = "restore-origin-idempotent"

        val first = start(mangaIds = listOf(mangaId), originKey = origin)
        assertTrue(first is ArchiveBootstrap.StartOutcome.Started)
        first as ArchiveBootstrap.StartOutcome.Started

        // the crash window: the run was created and the restore died before it recorded that. Asking
        // again has to return that same run - not a second one, and not a conflict with itself.
        val again = start(mangaIds = listOf(mangaId), originKey = origin)
        assertTrue(again is ArchiveBootstrap.StartOutcome.Started)
        again as ArchiveBootstrap.StartOutcome.Started

        assertEquals(first.session.id, again.session.id)
        assertEquals(first.itemCount, again.itemCount)
        assertEquals(listOf(first.session.id to origin), sessionOrigins())

        // and a run nobody owns is still refused while that one is active
        assertEquals(ArchiveBootstrap.StartOutcome.ActiveSessionExists, start(mangaIds = listOf(mangaId)))

        transaction { ArchiveBootstrap.getActiveSession()?.let { ArchiveBootstrap.cancel(it.id) } }
    }

    @Test
    fun `returns one run when the same origin is started concurrently`() {
        val mangaId = libraryManga("bootstrap-origin-concurrent")
        val origin = "restore-origin-concurrent"

        // The race this covers is a restore whose bootstrap insert and whose job update are separated:
        // the loser of the insert must recognise the winner as its own run. Both threads are released
        // together so the two starts really do overlap.
        val gate = java.util.concurrent.CountDownLatch(1)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<ArchiveBootstrap.StartOutcome>())
        val workers =
            List(2) {
                Thread {
                    gate.await()
                    outcomes += start(mangaIds = listOf(mangaId), originKey = origin)
                }
            }

        workers.forEach { it.start() }
        gate.countDown()
        workers.forEach { it.join(30_000) }

        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it is ArchiveBootstrap.StartOutcome.Started })
        assertEquals(1, outcomes.map { (it as ArchiveBootstrap.StartOutcome.Started).session.id }.toSet().size)
        assertEquals(origin, sessionOrigins().single().second)

        transaction { ArchiveBootstrap.getActiveSession()?.let { ArchiveBootstrap.cancel(it.id) } }
    }

    private fun items(sessionId: Int) =
        transaction {
            ArchiveBootstrapItemTable
                .selectAll()
                .where { ArchiveBootstrapItemTable.session eq sessionId }
                .orderBy(ArchiveBootstrapItemTable.id)
                .map { ArchiveBootstrapItemTable.toDataClass(it) }
        }

    @Test
    fun `starts a session for library series only and applies the default policy`() {
        val inLibrary = libraryManga("bootstrap-library-a")
        val alsoInLibrary = libraryManga("bootstrap-library-b")
        val notInLibraryTitle = "bootstrap-source-only"
        val notInLibrary =
            transaction {
                MangaTable
                    .insertAndGetId {
                        it[title] = notInLibraryTitle
                        it[url] = notInLibraryTitle
                        it[sourceReference] = 1
                        // qualify the column: the local `inLibrary` id would shadow it inside the insert
                        it[MangaTable.inLibrary] = false
                    }.value
            }

        val outcome = start(mangaIds = listOf(inLibrary, alsoInLibrary, notInLibrary))
        assertTrue(outcome is ArchiveBootstrap.StartOutcome.Started)
        val session = (outcome as ArchiveBootstrap.StartOutcome.Started).session

        val started = items(session.id)
        assertEquals(listOf(inLibrary, alsoInLibrary), started.map { it.mangaId })
        assertTrue(started.all { it.policy == MangaAcquisitionPolicy.AUTO })
        assertTrue(started.all { it.state == ArchiveBootstrapItemState.PENDING })
    }

    @Test
    fun `refuses a second active session and reports nothing to bootstrap`() {
        val mangaId = libraryManga("bootstrap-single-active")
        assertTrue(start(mangaIds = listOf(mangaId)) is ArchiveBootstrap.StartOutcome.Started)
        assertEquals(ArchiveBootstrap.StartOutcome.ActiveSessionExists, start(mangaIds = listOf(mangaId)))
        assertNotNull(ArchiveBootstrap.getActiveSession())

        transaction { ArchiveBootstrap.getActiveSession()?.let { ArchiveBootstrap.cancel(it.id) } }
        assertEquals(
            ArchiveBootstrap.StartOutcome.NothingToBootstrap,
            start(mangaIds = emptyList()),
        )
    }

    @Test
    fun `resolves the first matching category override`() {
        val mangaId = libraryManga("bootstrap-category-override")
        val first = createCategory("bootstrap-category-first")
        val second = createCategory("bootstrap-category-second")
        addMangaToCategories(mangaId, listOf(first, second))

        val outcome =
            start(
                defaultPolicy = MangaAcquisitionPolicy.MANUAL,
                categoryPolicies =
                    listOf(
                        ArchiveBootstrapCategoryPolicy(second, MangaAcquisitionPolicy.PAUSED),
                        ArchiveBootstrapCategoryPolicy(first, MangaAcquisitionPolicy.AUTO),
                    ),
                mangaIds = listOf(mangaId),
            )

        val session = (outcome as ArchiveBootstrap.StartOutcome.Started).session
        // the earliest listed matching override wins, not the category order or the default
        assertEquals(MangaAcquisitionPolicy.PAUSED, items(session.id).single().policy)
        assertEquals(listOf(second, first), session.categoryPolicies.map { it.categoryId })
        assertEquals(
            session.categoryPolicies,
            ArchiveBootstrapCategoryPolicies.decode(ArchiveBootstrapCategoryPolicies.encode(session.categoryPolicies)),
        )
    }

    @Test
    fun `claims one series per persisted delay and recovers it after a restart`() {
        val mangaId = libraryManga("bootstrap-rate-limit")
        val session = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session

        val claimed = ArchiveBootstrap.claimNextDueItem()
        assertNotNull(claimed)
        assertEquals(mangaId, claimed!!.item.mangaId)
        assertEquals(1, claimed.item.attempts)
        assertEquals(ArchiveBootstrapItemState.PROCESSING, claimed.item.state)

        // the persisted session delay is the global rate limit
        assertNull(ArchiveBootstrap.claimNextDueItem())

        // an interrupted claim is requeued without losing its attempts, but not immediately: the
        // session's own retry delay is what stops a crash loop from re-refreshing the same series
        ArchiveBootstrap.recoverInterruptedItems()
        val recovered = items(session.id).single()
        assertEquals(ArchiveBootstrapItemState.RETRY_WAIT, recovered.state)
        assertEquals(1, recovered.attempts)
        val interruptedDueAt = requireNotNull(recovered.dueAt)
        assertNull(ArchiveBootstrap.claimNextDueItem(now = interruptedDueAt - 1))
        assertNotNull(ArchiveBootstrap.claimNextDueItem(now = interruptedDueAt + 1))
    }

    @Test
    fun `waits out a retry delay and gives up once attempts are exhausted`() {
        val mangaId = libraryManga("bootstrap-retry")
        serverConfig.archiveBootstrapMaxAttempts.value = 2
        serverConfig.archiveBootstrapRetrySeconds.value = 60

        val session = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session

        val first = ArchiveBootstrap.claimNextDueItem()!!
        // the synthetic clock is anchored on the persisted rate limit, so the test never has to guess
        // what the real clock was when the series was claimed
        val base = first.session.nextItemAt!!
        ArchiveBootstrap.failItem(first, IllegalStateException("source refused"), now = base)
        val afterFirst = items(session.id).single()
        assertEquals(ArchiveBootstrapItemState.RETRY_WAIT, afterFirst.state)
        assertEquals(base + 60, afterFirst.dueAt)
        assertEquals(
            "the series could not be bootstrapped (IllegalStateException)",
            afterFirst.lastError,
            "only a static phrase plus the exception type may be persisted",
        )

        // not yet due, while the session keeps reporting when it will be
        assertNull(ArchiveBootstrap.claimNextDueItem(now = base + 30))
        assertEquals(ArchiveBootstrapState.RUNNING, ArchiveBootstrap.getSession(session.id)!!.state)
        assertEquals(base + 60, ArchiveBootstrap.nextDueAt(now = base + 30))

        val second = ArchiveBootstrap.claimNextDueItem(now = base + 60)!!
        assertEquals(2, second.item.attempts)
        ArchiveBootstrap.failItem(second, IllegalStateException("still refused"), now = base + 60)

        val failed = items(session.id).single()
        assertEquals(ArchiveBootstrapItemState.FAILED, failed.state)
        assertNull(failed.dueAt)

        // an exhausted series still keeps the session open until nothing is left to claim
        assertEquals(ArchiveBootstrapState.COMPLETED_WITH_ERRORS, ArchiveBootstrap.getSession(session.id)!!.state)
    }

    @Test
    fun `isolates a series whose source is missing`() {
        val mangaId = libraryManga("bootstrap-unresolved")
        val otherMangaId = libraryManga("bootstrap-resolved")
        val session =
            (
                start(mangaIds = listOf(mangaId, otherMangaId)) as ArchiveBootstrap.StartOutcome.Started
            ).session

        val first = ArchiveBootstrap.claimNextDueItem()!!
        ArchiveBootstrap.markItemUnresolvedSource(first)

        val progress = ArchiveBootstrap.progress(session.id)
        assertEquals(1, progress.unresolvedSource)
        assertEquals(1, progress.remaining)
        assertEquals(ArchiveBootstrapState.RUNNING, ArchiveBootstrap.getSession(session.id)!!.state)

        val unresolved = ArchiveBootstrap.unresolvedSources(session.id)
        assertEquals(1, unresolved.size)
        assertEquals(first.item.sourceId, unresolved.single().sourceId)
        assertEquals(1, unresolved.single().mangaCount)

        val second = ArchiveBootstrap.claimNextDueItem(now = first.session.nextItemAt!!)!!
        ArchiveBootstrap.completeItem(second, candidateCount = 0)
        assertEquals(ArchiveBootstrapState.COMPLETED_WITH_ERRORS, ArchiveBootstrap.getSession(session.id)!!.state)
        assertNull(ArchiveBootstrap.getActiveSession())
    }

    @Test
    fun `pause resume and cancel stop and reopen claiming race free`() {
        val mangaId = libraryManga("bootstrap-pause-resume")
        val session = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session

        assertNotNull(ArchiveBootstrap.pause(session.id))
        assertNull(ArchiveBootstrap.claimNextDueItem())
        assertEquals(ArchiveBootstrapState.PAUSED, ArchiveBootstrap.getSession(session.id)!!.state)
        // a paused session still owns the single active slot
        assertEquals(ArchiveBootstrap.StartOutcome.ActiveSessionExists, start(mangaIds = listOf(mangaId)))

        assertNotNull(ArchiveBootstrap.resume(session.id))
        assertNotNull(ArchiveBootstrap.claimNextDueItem())

        val cancelled = ArchiveBootstrap.getActiveSession()
        assertNotNull(cancelled)
        assertNotNull(ArchiveBootstrap.cancel(session.id))
        assertNull(ArchiveBootstrap.claimNextDueItem(now = 9_999_999))

        val cancelledItem = items(session.id).single()
        assertEquals(ArchiveBootstrapItemState.CANCELLED, cancelledItem.state)
        assertEquals(ArchiveBootstrapState.CANCELLED, ArchiveBootstrap.getSession(session.id)!!.state)
        assertNull(ArchiveBootstrap.getActiveSession())
    }

    @Test
    fun `reconciles a fully processed session as completed`() {
        val mangaId = libraryManga("bootstrap-complete")
        val session = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session

        val claimed = ArchiveBootstrap.claimNextDueItem()!!
        ArchiveBootstrap.completeItem(claimed, candidateCount = 7)

        val completed = items(session.id).single()
        assertEquals(ArchiveBootstrapItemState.COMPLETE, completed.state)
        assertEquals(7, completed.candidateCount)
        assertEquals(ArchiveBootstrapState.COMPLETED, ArchiveBootstrap.getSession(session.id)!!.state)
        assertNull(ArchiveBootstrap.getActiveSession())
    }

    @Test
    fun `retry reopens failed series of a finished session`() {
        val mangaId = libraryManga("bootstrap-retry-reopen")
        serverConfig.archiveBootstrapMaxAttempts.value = 1
        val session = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session

        val claimed = ArchiveBootstrap.claimNextDueItem()!!
        ArchiveBootstrap.failItem(claimed, IllegalStateException("boom"))
        assertEquals(ArchiveBootstrapState.COMPLETED_WITH_ERRORS, ArchiveBootstrap.getSession(session.id)!!.state)

        val retried = ArchiveBootstrap.retry(session.id)
        assertTrue(retried is ArchiveBootstrap.RetryOutcome.Retried)
        assertEquals(1, (retried as ArchiveBootstrap.RetryOutcome.Retried).itemCount)
        assertEquals(ArchiveBootstrapState.RUNNING, ArchiveBootstrap.getSession(session.id)!!.state)

        val reopened = items(session.id).single()
        assertEquals(ArchiveBootstrapItemState.PENDING, reopened.state)
        assertEquals(0, reopened.attempts)
        assertNull(reopened.lastError)
    }

    @Test
    fun `processes a series once and records idempotent initial candidates`() {
        val mangaId = libraryManga("bootstrap-process")
        createChapters(mangaId, amount = 3, read = false)

        val session = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session
        val claimed = ArchiveBootstrap.claimNextDueItem()!!

        val refreshes = AtomicInteger()
        val processor =
            ArchiveBootstrapProcessor(
                resolveSource = { null.also { refreshes.incrementAndGet() } },
                refreshManga = { refreshes.incrementAndGet() },
            )

        // a source that cannot be resolved is reported as unresolved, never as a failure
        val unresolved = runBlocking { processor.process(claimed.item) }
        assertEquals(ArchiveBootstrapProcessOutcome.UnresolvedSource, unresolved)

        val resolving =
            ArchiveBootstrapProcessor(
                resolveSource = { stubSourceForBootstrap },
                refreshManga = { refreshes.incrementAndGet() },
            )
        val completed = runBlocking { resolving.process(claimed.item) }
        assertTrue(completed is ArchiveBootstrapProcessOutcome.Completed)
        assertEquals(3, (completed as ArchiveBootstrapProcessOutcome.Completed).candidateCount)
        assertEquals(1, refreshes.get() - 1)

        // the policy snapshot was applied to the series itself, which is what later scheduling reads
        val storedPolicy =
            transaction {
                MangaTable
                    .selectAll()
                    .where { MangaTable.id eq mangaId }
                    .single()[MangaTable.acquisitionPolicy]
            }
        assertEquals(MangaAcquisitionPolicy.AUTO.name, storedPolicy)

        val candidatesBefore =
            transaction {
                ChapterRevisionTable.selectAll().where { ChapterRevisionTable.manga eq mangaId }.count()
            }
        assertEquals(3, candidatesBefore)
        val reasons =
            transaction {
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.manga eq mangaId }
                    .map { it[ChapterRevisionTable.discoveryReason] }
            }
        assertTrue(reasons.all { it == ChapterRevisionDiscoveryReason.BOOTSTRAP_IMPORT.name })

        // processing the same series again must not duplicate its candidates
        runBlocking { resolving.process(claimed.item) }
        val candidatesAfter =
            transaction {
                ChapterRevisionTable.selectAll().where { ChapterRevisionTable.manga eq mangaId }.count()
            }
        assertEquals(candidatesBefore, candidatesAfter)

        // and a series outside the library is never populated by a bootstrap
        val notInLibrary =
            transaction {
                MangaTable
                    .insertAndGetId {
                        it[title] = "bootstrap-not-in-library"
                        it[url] = "bootstrap-not-in-library"
                        it[sourceReference] = 1
                        it[inLibrary] = false
                    }.value
            }
        val skipped =
            transaction {
                val row = MangaTable.selectAll().where { MangaTable.id eq notInLibrary }.single()
                ChapterRevision.createCandidatesForBootstrap(row, emptyList(), 1)
            }
        assertTrue(skipped.isEmpty())

        ArchiveBootstrap.completeItem(claimed, candidateCount = 3)
        assertEquals(ArchiveBootstrapState.COMPLETED, ArchiveBootstrap.getSession(session.id)!!.state)
    }

    @Test
    fun `skips a series that left the library while the run was active`() {
        val mangaId = libraryManga("bootstrap-left-library")
        val session = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session
        val claimed = ArchiveBootstrap.claimNextDueItem()!!

        transaction {
            MangaTable.update({ MangaTable.id eq mangaId }) { it[inLibrary] = false }
        }

        val refreshed = AtomicInteger()
        val processor = ArchiveBootstrapProcessor(refreshManga = { refreshed.incrementAndGet() })
        val outcome = runBlocking { processor.process(claimed.item) }
        assertTrue(outcome is ArchiveBootstrapProcessOutcome.Skipped)
        assertEquals(0, refreshed.get())

        ArchiveBootstrap.markItemSkipped(claimed, "no longer in the library")
        assertEquals(ArchiveBootstrapItemState.SKIPPED, items(session.id).single().state)
        assertEquals(ArchiveBootstrapState.COMPLETED, ArchiveBootstrap.getSession(session.id)!!.state)
    }

    @Test
    fun `cancels a claimed series instead of leaving it in progress`() {
        val mangaId = libraryManga("bootstrap-cancel-claimed")
        val session = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session
        val claimed = ArchiveBootstrap.claimNextDueItem()!!
        assertEquals(ArchiveBootstrapItemState.PROCESSING, claimed.item.state)

        ArchiveBootstrap.cancel(session.id)

        assertEquals(ArchiveBootstrapItemState.CANCELLED, items(session.id).single().state)
        assertEquals(ArchiveBootstrapState.CANCELLED, ArchiveBootstrap.getSession(session.id)!!.state)
        assertNull(ArchiveBootstrap.getActiveSession())

        // the worker still holding the claim is fenced out: a late outcome neither overwrites the
        // cancelled series nor resurrects the run
        ArchiveBootstrap.completeItem(claimed, candidateCount = 5)
        assertEquals(ArchiveBootstrapItemState.CANCELLED, items(session.id).single().state)
        assertEquals(ArchiveBootstrapState.CANCELLED, ArchiveBootstrap.getSession(session.id)!!.state)
    }

    @Test
    fun `does not refresh a series whose run was cancelled after the claim`() {
        val mangaId = libraryManga("bootstrap-cancel-before-refresh")
        val session = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session
        val claimed = ArchiveBootstrap.claimNextDueItem()!!
        ArchiveBootstrap.cancel(session.id)

        val refreshed = AtomicInteger()
        val processor = ArchiveBootstrapProcessor(refreshManga = { refreshed.incrementAndGet() })
        val outcome = runBlocking { processor.process(claimed.item) }
        assertTrue(outcome is ArchiveBootstrapProcessOutcome.Skipped)
        assertEquals(0, refreshed.get())
        assertEquals(
            0,
            transaction {
                ChapterRevisionTable.selectAll().where { ChapterRevisionTable.manga eq mangaId }.count()
            },
        )
        assertEquals(ArchiveBootstrapState.CANCELLED, ArchiveBootstrap.getSession(session.id)!!.state)
    }

    @Test
    fun `lets only one of two concurrent starts win`() {
        val first = libraryManga("bootstrap-concurrent-a")
        val second = libraryManga("bootstrap-concurrent-b")
        val barrier = CyclicBarrier(2)
        val outcomes = CopyOnWriteArrayList<ArchiveBootstrap.StartOutcome>()

        listOf(first, second)
            .map { mangaId ->
                Thread {
                    barrier.await()
                    outcomes += start(mangaIds = listOf(mangaId))
                }.also { it.start() }
            }.forEach { it.join() }

        assertEquals(1, outcomes.count { it is ArchiveBootstrap.StartOutcome.Started })
        assertEquals(1, outcomes.count { it == ArchiveBootstrap.StartOutcome.ActiveSessionExists })
        // the single-active rule is the database's, so exactly one row carries the marker
        assertEquals(
            1,
            transaction {
                ArchiveBootstrapSessionTable
                    .selectAll()
                    .where { ArchiveBootstrapSessionTable.activeMarker eq ArchiveBootstrap.ACTIVE_MARKER }
                    .count()
            },
        )
    }

    @Test
    fun `pages the series of a run by a stable cursor`() {
        val mangaIds = (1..3).map { libraryManga("bootstrap-page-$it") }
        val session = (start(mangaIds = mangaIds) as ArchiveBootstrap.StartOutcome.Started).session
        val query = ArchiveBootstrapQuery()

        val firstPage = query.archiveBootstrapItems(sessionId = session.id, first = 2)
        assertEquals(2, firstPage.nodes.size)
        assertEquals(3, firstPage.totalCount)
        assertTrue(firstPage.pageInfo.hasNextPage)

        val secondPage =
            query.archiveBootstrapItems(
                sessionId = session.id,
                first = 2,
                after = firstPage.pageInfo.endCursor,
            )
        assertEquals(1, secondPage.nodes.size)
        assertFalse(secondPage.pageInfo.hasNextPage)
        // the cursor neither repeats nor skips a series, which is what makes the listing usable
        assertEquals(mangaIds, (firstPage.nodes + secondPage.nodes).map { it.mangaId })
    }

    @Test
    fun `exposes a bounded next due time while a session is running`() {
        val mangaId = libraryManga("bootstrap-next-due")
        val session = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session
        assertNotNull(ArchiveBootstrap.nextDueAt())

        ArchiveBootstrap.cancel(session.id)
        assertNull(ArchiveBootstrap.nextDueAt())
    }

    @Test
    fun `reports progress counted from the series`() {
        val first = libraryManga("bootstrap-progress-a")
        val second = libraryManga("bootstrap-progress-b")
        val session = (start(mangaIds = listOf(first, second)) as ArchiveBootstrap.StartOutcome.Started).session

        val claimed = ArchiveBootstrap.claimNextDueItem()!!
        ArchiveBootstrap.completeItem(claimed, candidateCount = 0)

        val progress = ArchiveBootstrap.progress(session.id)
        assertEquals(2, progress.total)
        assertEquals(1, progress.complete)
        assertEquals(1, progress.remaining)
        assertFalse(progress.pending == progress.total)
    }

    @Test
    fun `does not let a waiting retry hide a series that is already due`() {
        val waiting = libraryManga("bootstrap-due-waiting")
        val ready = libraryManga("bootstrap-due-ready")
        serverConfig.archiveBootstrapRetrySeconds.value = 600

        val session =
            (
                start(mangaIds = listOf(waiting, ready)) as ArchiveBootstrap.StartOutcome.Started
            ).session

        val first = ArchiveBootstrap.claimNextDueItem()!!
        assertEquals(waiting, first.item.mangaId, "the lowest id is claimed first while both are due")
        val base = first.session.nextItemAt!!
        ArchiveBootstrap.failItem(first, IllegalStateException("source refused"), now = base)
        assertEquals(base + 600, items(session.id).single { it.mangaId == waiting }.dueAt)

        // The waiting series has the lower id, so reading only the oldest claimable row would report
        // its retry time and the worker would sleep through the other series. The earliest *claimable*
        // due time is what the worker has to wake up for.
        assertEquals(base, ArchiveBootstrap.nextDueAt(now = base))
        assertEquals(
            ready,
            ArchiveBootstrap.claimNextDueItem(now = base)?.item?.mangaId,
            "a series waiting out its retry must not block one that is already due",
        )
    }

    @Test
    fun `recovery gives up on an interrupted series whose attempts are exhausted`() {
        val mangaId = libraryManga("bootstrap-recovery-exhausted")
        serverConfig.archiveBootstrapMaxAttempts.value = 1

        val session = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session
        val claimed = ArchiveBootstrap.claimNextDueItem()!!
        assertEquals(1, claimed.item.attempts)

        ArchiveBootstrap.recoverInterruptedItems(now = claimed.session.nextItemAt!!)

        val failed = items(session.id).single()
        assertEquals(ArchiveBootstrapItemState.FAILED, failed.state)
        assertEquals("the server stopped while this series was being bootstrapped", failed.lastError)
        assertNull(failed.dueAt)
        assertEquals(1, failed.attempts)
        // the run is settled here instead of waiting for a claim that can no longer happen
        assertEquals(ArchiveBootstrapState.COMPLETED_WITH_ERRORS, ArchiveBootstrap.getSession(session.id)!!.state)
        assertNull(ArchiveBootstrap.getActiveSession())
    }

    @Test
    fun `recovery of a paused run defers the series instead of settling the run`() {
        val mangaId = libraryManga("bootstrap-recovery-paused")
        serverConfig.archiveBootstrapMaxAttempts.value = 3
        serverConfig.archiveBootstrapRetrySeconds.value = 90

        val session = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session
        val claimed = ArchiveBootstrap.claimNextDueItem()!!
        ArchiveBootstrap.pause(session.id)

        ArchiveBootstrap.recoverInterruptedItems(now = claimed.session.nextItemAt!!)

        val recovered = items(session.id).single()
        assertEquals(ArchiveBootstrapItemState.RETRY_WAIT, recovered.state)
        assertEquals(1, recovered.attempts, "recovery must not reset the attempt count")
        assertEquals(claimed.session.nextItemAt!! + 90, recovered.dueAt)
        assertEquals(ArchiveBootstrapState.PAUSED, ArchiveBootstrap.getSession(session.id)!!.state)
        val deferredDueAt = requireNotNull(recovered.dueAt)
        assertNull(ArchiveBootstrap.claimNextDueItem(now = deferredDueAt + 3600))
    }

    @Test
    fun `persists a source independent failure reason only`() {
        val mangaId = libraryManga("bootstrap-failure-reason")
        val session = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session
        val claimed = ArchiveBootstrap.claimNextDueItem()!!

        val secret = "https://reader:hunter2@source.example/chapters?token=abcdef"
        ArchiveBootstrap.failItem(claimed, IllegalStateException("Request failed for $secret"))

        val stored = items(session.id).single().lastError!!
        assertEquals("the series could not be bootstrapped (IllegalStateException)", stored)
        assertFalse(stored.contains("hunter2"), stored)
        assertFalse(stored.contains("source.example"), stored)
        assertFalse(stored.contains("token"), stored)

        // a failure without a throwable keeps the same shape instead of recording "null"
        ArchiveBootstrap.cancel(session.id)
        val secondSession = (start(mangaIds = listOf(mangaId)) as ArchiveBootstrap.StartOutcome.Started).session
        ArchiveBootstrap.failItem(ArchiveBootstrap.claimNextDueItem()!!, null)
        assertEquals("the series could not be bootstrapped", items(secondSession.id).single().lastError)
    }

    @Test
    fun `recognises only a duplicate key violation as another active session`() {
        assertFalse(IllegalStateException("boom").isDuplicateKeyViolation())
        assertFalse(SQLException("connection reset", "08006").isDuplicateKeyViolation())
        assertTrue(SQLException("duplicate key", "23505").isDuplicateKeyViolation())
        // the driver wraps the constraint violation, so the cause chain has to be walked
        assertTrue(IllegalStateException("outer", SQLException("duplicate key", "23505")).isDuplicateKeyViolation())
    }

    /** source id 1 is the one [createLibraryManga] binds; the stub only has to resolve. */
    private val stubSourceForBootstrap = StubSource(1)
}

/** The bootstrap GraphQL surface must be present and authenticated. */
class ArchiveBootstrapSchemaTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }
    }

    @Test
    fun `exposes bootstrap states, item states and the run fields`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        val sessionState = schema.getType("ArchiveBootstrapState") as GraphQLEnumType
        assertEquals(
            ArchiveBootstrapState.entries.map { it.name }.toSet(),
            sessionState.values.map { it.name }.toSet(),
        )
        assertTrue(sessionState.values.map { it.name }.containsAll(listOf("RUNNING", "COMPLETED_WITH_ERRORS", "CANCELLED")))

        val itemState = schema.getType("ArchiveBootstrapItemState") as GraphQLEnumType
        assertEquals(
            ArchiveBootstrapItemState.entries.map { it.name }.toSet(),
            itemState.values.map { it.name }.toSet(),
        )
        assertTrue(itemState.values.map { it.name }.contains("UNRESOLVED_SOURCE"))

        val sessionType = schema.getType("ArchiveBootstrapSessionType") as GraphQLObjectType
        assertEquals(
            "ArchiveBootstrapState",
            GraphQLTypeUtil.unwrapAll(sessionType.getFieldDefinition("state").type).name,
        )
        assertEquals(
            "MangaAcquisitionPolicy",
            GraphQLTypeUtil.unwrapAll(sessionType.getFieldDefinition("defaultPolicy").type).name,
        )
        // the category snapshot is exposed read-only and ordered by position
        assertNotNull(sessionType.getFieldDefinition("categoryPolicies"))

        val itemType = schema.getType("ArchiveBootstrapItemType") as GraphQLObjectType
        assertEquals(
            "ArchiveBootstrapItemState",
            GraphQLTypeUtil.unwrapAll(itemType.getFieldDefinition("state").type).name,
        )
        // the audit snapshots stay nullable, because the underlying series or source row may be gone
        assertTrue(GraphQLTypeUtil.isNullable(itemType.getFieldDefinition("mangaId").type))
        assertTrue(GraphQLTypeUtil.isNullable(itemType.getFieldDefinition("sourceId").type))

        val queryType = schema.getType("Query") as GraphQLObjectType
        listOf(
            "archiveBootstrapSessions",
            "archiveBootstrapSession",
            "archiveBootstrapActiveSession",
            "archiveBootstrapItems",
            "archiveBootstrapProgress",
            "archiveBootstrapUnresolvedSources",
        ).forEach { field ->
            assertNotNull(queryType.getFieldDefinition(field), "missing query field $field")
        }

        val mutationType = schema.getType("Mutation") as GraphQLObjectType
        listOf(
            "startArchiveBootstrap",
            "pauseArchiveBootstrap",
            "resumeArchiveBootstrap",
            "cancelArchiveBootstrap",
            "retryArchiveBootstrapItems",
        ).forEach { field ->
            assertNotNull(mutationType.getFieldDefinition(field), "missing mutation field $field")
        }

        // auth is per field: a run names the series an operator owns, so it belongs to the same
        // trust boundary as the library APIs. The directive wiring is driven by the annotation
        // itself, so the annotation is what is asserted here.
        val authenticatedQueryMethods =
            ArchiveBootstrapQuery::class.java.declaredMethods
                .filter { it.isAnnotationPresent(RequireAuth::class.java) }
                .map { it.name }
                .toSet()
        val authenticatedMutationMethods =
            ArchiveBootstrapMutation::class.java.declaredMethods
                .filter { it.isAnnotationPresent(RequireAuth::class.java) }
                .map { it.name }
                .toSet()

        listOf(
            "archiveBootstrapSessions",
            "archiveBootstrapSession",
            "archiveBootstrapActiveSession",
            "archiveBootstrapLatestSession",
            "archiveBootstrapItems",
            "archiveBootstrapProgress",
            "archiveBootstrapUnresolvedSources",
            "archiveBootstrapEffectivePolicy",
        ).forEach { method ->
            assertTrue(method in authenticatedQueryMethods, "$method must require authentication")
        }
        listOf(
            "startArchiveBootstrap",
            "pauseArchiveBootstrap",
            "resumeArchiveBootstrap",
            "cancelArchiveBootstrap",
            "retryArchiveBootstrapItems",
        ).forEach { method ->
            assertTrue(method in authenticatedMutationMethods, "$method must require authentication")
        }

        // the single-active invariant is never exposed as a column
        assertNull(sessionType.getFieldDefinition("activeMarker"))
    }
}
