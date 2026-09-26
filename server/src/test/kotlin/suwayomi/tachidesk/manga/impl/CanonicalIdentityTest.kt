package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingEligibility
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingRole
import suwayomi.tachidesk.manga.model.dataclass.CanonicalDuplicateStrategy
import suwayomi.tachidesk.manga.model.dataclass.CanonicalIdentityExportBinding
import suwayomi.tachidesk.manga.model.dataclass.CanonicalIdentityExportDocument
import suwayomi.tachidesk.manga.model.dataclass.CanonicalIdentityExportWork
import suwayomi.tachidesk.manga.model.dataclass.CanonicalSourceBindingDataClass
import suwayomi.tachidesk.manga.model.dataclass.CanonicalWriteOutcome
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.CanonicalSourceBindingTable
import suwayomi.tachidesk.manga.model.table.CanonicalWorkTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Behaviour of the canonical identity control plane.
 *
 * The three things this file exists to prove are that an unbound manga is untouched by the control
 * plane, that a non-active binding stops discovery without retracting anything, and that every
 * invariant survives a concurrent write.
 */
class CanonicalIdentityTest : ApplicationTest() {
    @BeforeEach
    fun reset() {
        // deleting a work cascades its bindings, so this clears the whole control plane; the manga and
        // chapter rows are left alone because other suites share this database
        clearTables(ChapterRevisionTable, CanonicalWorkTable)
    }

    private fun newManga(title: String = "series"): Int = createLibraryManga("$title-${UUID.randomUUID()}")

    private fun work(
        title: String = "Series",
        strategy: CanonicalDuplicateStrategy = CanonicalDuplicateStrategy.KEEP_ALL,
        scanlator: String? = null,
    ): String {
        val result = CanonicalIdentity.createWork(title, strategy, scanlator)
        assertEquals(CanonicalWriteOutcome.APPLIED, result.outcome, "creating a work must succeed: $title")
        return result.work!!.workKey
    }

    private fun binding(
        workKey: String,
        mangaId: Int,
        role: CanonicalBindingRole = CanonicalBindingRole.ACTIVE,
        priority: Int? = null,
        isPrimary: Boolean = false,
    ): CanonicalSourceBindingDataClass {
        val result = CanonicalIdentity.attachManga(workKey, mangaId, role, priority, isPrimary)
        assertEquals(CanonicalWriteOutcome.APPLIED, result.outcome, "attaching $mangaId must succeed")
        return result.binding!!
    }

    // ---------------------------------------------------------------- works

    @Test
    fun `a work gets a stable opaque key that is not derived from its title`() {
        val first = work(title = "Same Title")
        val second = work(title = "Same Title")

        assertTrue(first.length in 1..64, "the key has to fit its column: $first")
        assertTrue(first.matches(Regex("[0-9a-f]+")), "the key must be opaque rather than readable: $first")
        assertTrue(first != second, "two works with the same title are still two works")
        assertNotNull(CanonicalIdentity.getWorkByKey(first))
    }

    @Test
    fun `a work cannot be created without a usable title or scanlator preference`() {
        assertEquals(
            CanonicalWriteOutcome.CONFLICT,
            CanonicalIdentity.createWork("   ", CanonicalDuplicateStrategy.KEEP_ALL, null).outcome,
        )
        assertEquals(
            CanonicalWriteOutcome.CONFLICT,
            CanonicalIdentity.createWork("Series", CanonicalDuplicateStrategy.PREFER_SCANLATOR, null).outcome,
            "a scanlator preference without a scanlator could never be applied",
        )
        assertEquals(
            CanonicalWriteOutcome.CONFLICT,
            CanonicalIdentity.createWork("Series", CanonicalDuplicateStrategy.PREFER_SCANLATOR, "   ").outcome,
        )

        val applied = CanonicalIdentity.createWork("Series", CanonicalDuplicateStrategy.PREFER_SCANLATOR, "  Asura  Scans ")
        assertEquals(CanonicalWriteOutcome.APPLIED, applied.outcome)
        assertEquals("Asura Scans", applied.work!!.preferredScanlator, "the stored value keeps the operator's spelling")
        assertTrue(CanonicalIdentity.sameScanlator("Asura Scans", "asura  scans"), "comparison normalizes safely")
        assertTrue(!CanonicalIdentity.sameScanlator(null, null), "blank never matches blank")
    }

    @Test
    fun `a work with bindings is not deleted, and an empty one is`() {
        val workKey = work()
        val mangaId = newManga()
        binding(workKey, mangaId)

        assertEquals(CanonicalWriteOutcome.CONFLICT, CanonicalIdentity.deleteEmptyWork(workKey))
        assertNotNull(CanonicalIdentity.getWorkByKey(workKey))

        val detached = CanonicalIdentity.getBindingForManga(mangaId)!!
        assertEquals(CanonicalWriteOutcome.APPLIED, CanonicalIdentity.detachBinding(detached.id).outcome)
        assertEquals(CanonicalWriteOutcome.APPLIED, CanonicalIdentity.deleteEmptyWork(workKey))
        assertNull(CanonicalIdentity.getWorkByKey(workKey))
    }

    @Test
    fun `updating a work keeps it usable and reports an unknown one`() {
        val workKey = work(strategy = CanonicalDuplicateStrategy.PREFER_SCANLATOR, scanlator = "Asura Scans")

        val updated = CanonicalIdentity.updateWork(workKey, title = " Renamed ", duplicateStrategy = CanonicalDuplicateStrategy.KEEP_ALL)
        assertEquals(CanonicalWriteOutcome.APPLIED, updated.outcome)
        assertEquals("Renamed", updated.work!!.title)
        assertEquals("Asura Scans", updated.work!!.preferredScanlator, "changing the policy must not drop the scanlator")

        assertEquals(
            CanonicalWriteOutcome.CONFLICT,
            CanonicalIdentity
                .updateWork(
                    workKey,
                    duplicateStrategy = CanonicalDuplicateStrategy.PREFER_SCANLATOR,
                    clearPreferredScanlator = true,
                ).outcome,
            "clearing the scanlator while preferring one would leave an unapplicable policy",
        )
        assertEquals(
            CanonicalWriteOutcome.NOT_FOUND,
            CanonicalIdentity.updateWork("missing", title = "x").outcome,
        )
    }

    // ---------------------------------------------------------------- bindings

    @Test
    fun `one manga belongs to one work and one place in it`() {
        val first = work("A")
        val second = work("B")
        val mangaId = newManga()
        val otherManga = newManga()

        val attached = binding(first, mangaId, priority = null)
        assertEquals(0, attached.priority, "the first binding takes the first place")
        assertEquals(CanonicalBindingRole.ACTIVE, attached.role)
        assertTrue(!attached.isPrimary, "attaching must not silently choose a preferred source")
        assertNotNull(attached.mangaTitle)
        assertNotNull(attached.mangaUrl)

        assertEquals(1, binding(first, otherManga).priority, "the next place follows the existing ones")

        assertEquals(
            CanonicalWriteOutcome.CONFLICT,
            CanonicalIdentity.attachManga(second, mangaId, CanonicalBindingRole.ACTIVE).outcome,
            "a manga can only belong to one work",
        )
        assertEquals(
            CanonicalWriteOutcome.CONFLICT,
            CanonicalIdentity.attachManga(first, mangaId, CanonicalBindingRole.ACTIVE).outcome,
            "attaching the same manga twice is a conflict, not a duplicate row",
        )
        assertEquals(
            CanonicalWriteOutcome.CONFLICT,
            CanonicalIdentity.attachManga(first, newManga(), CanonicalBindingRole.ACTIVE, priority = 0).outcome,
            "the ordering of a work's bindings is a permutation",
        )
        assertEquals(
            CanonicalWriteOutcome.NOT_FOUND,
            CanonicalIdentity.attachManga("missing", mangaId, CanonicalBindingRole.ACTIVE).outcome,
        )
        assertEquals(
            CanonicalWriteOutcome.NOT_FOUND,
            CanonicalIdentity.attachManga(first, 987_654, CanonicalBindingRole.ACTIVE).outcome,
        )
    }

    /**
     * Two works can be told to adopt one manga at the very same moment.
     *
     * Each attach locks only its own work, so neither can see the other's uncommitted row and both find
     * the manga unbound; the unique index on the manga is what decides that race. However it is decided,
     * the published contract has to hold: both calls finish without an exception, exactly one of them
     * applied, and one binding is left behind. Repeated because the window between the check and the
     * insert is small, so a single round is not evidence that the losing path never throws.
     */
    @Test
    fun `a manga raced onto two works at once ends as one binding and one conflict`() {
        repeat(8) {
            val first = work("A")
            val second = work("B")
            val mangaId = newManga()

            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val outcomes = Collections.synchronizedList(mutableListOf<CanonicalWriteOutcome>())

            val racers =
                listOf(first, second).map { workKey ->
                    thread {
                        ready.countDown()
                        start.await()
                        val outcome =
                            CanonicalIdentity
                                .attachManga(workKey, mangaId, CanonicalBindingRole.ACTIVE)
                                .outcome
                        outcomes.add(outcome)
                    }
                }

            ready.await()
            start.countDown()
            racers.forEach { it.join() }

            assertEquals(
                listOf(CanonicalWriteOutcome.APPLIED, CanonicalWriteOutcome.CONFLICT),
                outcomes.sortedBy { it.name },
                "exactly one racing attach may win and the other has to report a conflict",
            )
            assertEquals(
                1,
                transaction {
                    CanonicalSourceBindingTable
                        .selectAll()
                        .where { CanonicalSourceBindingTable.manga eq mangaId }
                        .toList()
                        .size
                },
                "only one binding may exist for the manga afterwards",
            )
        }
    }

    @Test
    fun `a work never ends up with two preferred sources`() {
        val workKey = work()
        val primary = binding(workKey, newManga(), isPrimary = true)
        val other = binding(workKey, newManga())

        assertTrue(primary.isPrimary)
        assertTrue(!other.isPrimary)

        val promoted = CanonicalIdentity.promoteBinding(other.id)
        assertEquals(CanonicalWriteOutcome.APPLIED, promoted.outcome)
        assertTrue(CanonicalIdentity.getBinding(other.id)!!.isPrimary)
        assertTrue(!CanonicalIdentity.getBinding(primary.id)!!.isPrimary, "the previous primary loses the marker")
        assertEquals(
            CanonicalBindingRole.ACTIVE,
            CanonicalIdentity.getBinding(primary.id)!!.role,
            "promotion chooses a preference, it does not disable the others",
        )

        // promoting the current primary again changes nothing and still succeeds
        val again = CanonicalIdentity.promoteBinding(other.id)
        assertEquals(CanonicalWriteOutcome.APPLIED, again.outcome)
        assertTrue(CanonicalIdentity.getBinding(other.id)!!.isPrimary)

        // a second primary can never be attached while one exists
        assertEquals(
            CanonicalWriteOutcome.CONFLICT,
            CanonicalIdentity.attachManga(workKey, newManga(), CanonicalBindingRole.ACTIVE, isPrimary = true).outcome,
        )
    }

    @Test
    fun `a disabled binding is not the preferred one`() {
        val workKey = work()
        val primary = binding(workKey, newManga(), isPrimary = true)

        assertEquals(
            CanonicalWriteOutcome.APPLIED,
            CanonicalIdentity.changeBinding(primary.id, role = CanonicalBindingRole.DISABLED).outcome,
        )

        val disabled = CanonicalIdentity.getBinding(primary.id)!!
        assertEquals(CanonicalBindingRole.DISABLED, disabled.role)
        assertTrue(!disabled.isPrimary, "a source excluded from discovery cannot also be the preferred one")

        assertEquals(
            CanonicalWriteOutcome.CONFLICT,
            CanonicalIdentity.attachManga(workKey, newManga(), CanonicalBindingRole.DISABLED, isPrimary = true).outcome,
        )
    }

    @Test
    fun `failover moves the work onto a fallback and is reversible`() {
        val workKey = work()
        val abandoned = binding(workKey, newManga(), role = CanonicalBindingRole.ACTIVE, isPrimary = true)
        val fallback = binding(workKey, newManga(), role = CanonicalBindingRole.FALLBACK)

        val failed = CanonicalIdentity.failover(workKey, fallback.id)
        assertEquals(CanonicalWriteOutcome.APPLIED, failed.outcome)

        assertEquals(CanonicalBindingRole.ACTIVE, CanonicalIdentity.getBinding(fallback.id)!!.role)
        assertTrue(CanonicalIdentity.getBinding(fallback.id)!!.isPrimary)

        val demoted = CanonicalIdentity.getBinding(abandoned.id)!!
        assertEquals(CanonicalBindingRole.FALLBACK, demoted.role, "the abandoned source stops feeding discovery")
        assertTrue(!demoted.isPrimary)
        assertNotNull(CanonicalIdentity.getBinding(abandoned.id), "failover never throws a binding away")

        // and back
        assertEquals(CanonicalWriteOutcome.APPLIED, CanonicalIdentity.failover(workKey, abandoned.id).outcome)
        assertEquals(CanonicalBindingRole.ACTIVE, CanonicalIdentity.getBinding(abandoned.id)!!.role)
        assertEquals(CanonicalBindingRole.FALLBACK, CanonicalIdentity.getBinding(fallback.id)!!.role)
    }

    @Test
    fun `failover refuses a binding that is not the work's or is excluded`() {
        val workKey = work()
        val otherWork = work("Other")
        binding(workKey, newManga(), isPrimary = true)
        val disabled = binding(workKey, newManga(), role = CanonicalBindingRole.DISABLED)
        val stranger = binding(otherWork, newManga())

        assertEquals(
            CanonicalWriteOutcome.CONFLICT,
            CanonicalIdentity.failover(workKey, disabled.id).outcome,
            "reviving an explicitly excluded source is an explicit change, not a side effect",
        )
        assertEquals(
            CanonicalWriteOutcome.NOT_FOUND,
            CanonicalIdentity.failover(workKey, stranger.id).outcome,
            "a binding of another work is not found for this work",
        )
        assertEquals(CanonicalWriteOutcome.NOT_FOUND, CanonicalIdentity.failover("missing", stranger.id).outcome)
    }

    @Test
    fun `detaching removes the binding and keeps the work and the manga`() {
        val workKey = work()
        val mangaId = newManga()
        val attached = binding(workKey, mangaId)

        val detached = CanonicalIdentity.detachBinding(attached.id)
        assertEquals(CanonicalWriteOutcome.APPLIED, detached.outcome)
        assertNull(CanonicalIdentity.getBinding(attached.id))
        assertNull(CanonicalIdentity.getBindingForManga(mangaId), "the manga is unbound again")
        assertNotNull(CanonicalIdentity.getWorkByKey(workKey), "the work is untouched")
        assertNotNull(transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.firstOrNull() })

        assertEquals(CanonicalWriteOutcome.NOT_FOUND, CanonicalIdentity.detachBinding(attached.id).outcome)
    }

    @Test
    fun `deleting a manga keeps the binding audit and does not touch the work`() {
        val workKey = work()
        val mangaId = newManga("audited")
        val attached = binding(workKey, mangaId)

        transaction { MangaTable.deleteWhere { MangaTable.id eq mangaId } }

        val survivor = CanonicalIdentity.getBinding(attached.id)
        assertNotNull(survivor, "deleting a source must not erase the record of what the work contained")
        assertNull(survivor!!.mangaId)
        assertEquals(attached.mangaTitle, survivor.mangaTitle, "the audit snapshot is never rewritten")
        assertEquals(attached.mangaUrl, survivor.mangaUrl)
        assertNotNull(CanonicalIdentity.getWorkByKey(workKey))
        assertEquals(1, CanonicalIdentity.getBindingsForWork(survivor.workId).size)
    }

    // ---------------------------------------------------------------- eligibility

    @Test
    fun `eligibility follows the binding role and unbound is untouched`() {
        val unbound = newManga("unbound")
        assertEquals(CanonicalBindingEligibility.UNBOUND, CanonicalIdentity.discoveryScopesFor(listOf(unbound))[unbound]!!.eligibility)
        assertTrue(CanonicalIdentity.isAcquisitionEligible(unbound), "an unbound manga is discovered as before")
        assertTrue(CanonicalIdentity.isAcquisitionEligible(null), "a discovery with no manga row is unbound too")

        val workKey = work()
        val active = binding(workKey, newManga("active"), role = CanonicalBindingRole.ACTIVE)
        val fallback = binding(workKey, newManga("fallback"), role = CanonicalBindingRole.FALLBACK)
        val disabled = binding(workKey, newManga("disabled"), role = CanonicalBindingRole.DISABLED)

        assertTrue(active.eligibility.isAcquisitionEligible)
        assertTrue(!fallback.eligibility.isAcquisitionEligible)
        assertTrue(!disabled.eligibility.isAcquisitionEligible)

        assertTrue(CanonicalIdentity.isAcquisitionEligible(active.mangaId))
        assertTrue(!CanonicalIdentity.isAcquisitionEligible(fallback.mangaId))
        assertTrue(!CanonicalIdentity.isAcquisitionEligible(disabled.mangaId))
    }

    // ---------------------------------------------------------------- discovery integration

    private fun chaptersOf(mangaId: Int): List<ChapterDataClass> =
        transaction {
            ChapterTable
                .selectAll()
                .where { ChapterTable.manga eq mangaId }
                .orderBy(ChapterTable.sourceOrder)
                .map { ChapterTable.toDataClass(it) }
        }

    private fun createCandidates(
        mangaId: Int,
        now: Long = 1_000,
    ): List<Int> =
        transaction {
            val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
            ChapterRevision.createCandidatesForNewChapters(mangaEntry, chaptersOf(mangaId), now)
        }

    private fun setPolicy(
        mangaId: Int,
        policy: MangaAcquisitionPolicy,
    ) {
        transaction {
            MangaTable.update({ MangaTable.id eq mangaId }) { it[acquisitionPolicy] = policy.name }
        }
    }

    @Test
    fun `an unbound manga records exactly the candidates it always did`() {
        val mangaId = newManga("ordinary").also { createChapters(it, 2, read = false) }

        val ids = createCandidates(mangaId)

        assertEquals(2, ids.size, "an unbound manga must behave byte for byte as before")
        assertNull(ChapterRevision.getRevision(ids.first())!!.canonicalBinding, "an unbound discovery carries no canonical snapshot")
    }

    @Test
    fun `a non-active binding records no ordinary candidate until it is promoted`() {
        val mangaId = newManga("fallback-source").also { createChapters(it, 2, read = false) }
        val workKey = work()
        val source = binding(workKey, mangaId, role = CanonicalBindingRole.FALLBACK)
        val primaryManga = newManga("primary-source").also { createChapters(it, 2, read = false) }
        val primary = binding(workKey, primaryManga, isPrimary = true)

        assertTrue(createCandidates(mangaId).isEmpty(), "a fallback binding must not feed ordinary discovery")
        assertEquals(2, createCandidates(primaryManga).size, "the active binding keeps being discovered")

        // promoting the fallback flips eligibility for the *next* discovery without touching anything
        // that was already recorded
        assertEquals(CanonicalWriteOutcome.APPLIED, CanonicalIdentity.failover(workKey, source.id).outcome)
        assertEquals(2, createCandidates(mangaId).size, "a promoted source is discovered again")
        assertEquals(CanonicalBindingRole.FALLBACK, CanonicalIdentity.getBinding(primary.id)!!.role)
        assertTrue(createCandidates(primaryManga).isEmpty(), "the abandoned source stops being discovered")
    }

    @Test
    fun `a candidate records the exact binding it was discovered under`() {
        val mangaId = newManga("bound").also { createChapters(it, 1, read = false) }
        val workKey = work()
        val source = binding(workKey, mangaId, role = CanonicalBindingRole.ACTIVE, isPrimary = true)

        val revisionId = createCandidates(mangaId).single()
        val snapshot = ChapterRevision.getRevision(revisionId)!!.canonicalBinding
        assertNotNull(snapshot)
        assertEquals(workKey, snapshot!!.workKey, "the archive addresses the work by its public key")
        assertEquals(CanonicalBindingRole.ACTIVE, snapshot.bindingRole)
        assertEquals(source.priority, snapshot.bindingPriority)
        assertTrue(snapshot.bindingPrimary)
        assertEquals(source.sourceId, snapshot.bindingSourceId)
        assertEquals(source.mangaUrl, snapshot.bindingMangaUrl)

        // the snapshot is a copy, so detaching later cannot rewrite what the discovery was made for
        assertEquals(CanonicalWriteOutcome.APPLIED, CanonicalIdentity.detachBinding(source.id).outcome)
        val afterDetach = ChapterRevision.getRevision(revisionId)!!.canonicalBinding
        assertEquals(snapshot, afterDetach, "the recorded snapshot must survive a detach")
        assertTrue(CanonicalIdentity.isAcquisitionEligible(mangaId), "detaching makes the manga unbound and eligible")
    }

    @Test
    fun `acquisition policy still decides the initial state of a bound discovery`() {
        val mangaId = newManga("manual").also { createChapters(it, 1, read = false) }
        setPolicy(mangaId, MangaAcquisitionPolicy.MANUAL)
        val workKey = work()
        binding(workKey, mangaId)

        val revisionId = createCandidates(mangaId).single()
        val revision = ChapterRevision.getRevision(revisionId)!!
        assertEquals(ChapterAcquisitionState.PENDING_APPROVAL, revision.acquisitionState)
        assertNotNull(revision.canonicalBinding, "the binding is recorded whatever the policy is")
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    fun `concurrent attaches leave exactly one binding for a manga`() {
        val workKey = work()
        val mangaId = newManga("contended")
        val start = CountDownLatch(1)
        val outcomes = Collections.synchronizedList(mutableListOf<CanonicalWriteOutcome?>())

        val threads =
            (1..2).map {
                thread(start = true) {
                    start.await()
                    outcomes +=
                        runCatching { CanonicalIdentity.attachManga(workKey, mangaId, CanonicalBindingRole.ACTIVE).outcome }.getOrNull()
                }
            }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(1, outcomes.count { it == CanonicalWriteOutcome.APPLIED }, "exactly one write may win: $outcomes")
        val bindings =
            transaction {
                CanonicalSourceBindingTable
                    .selectAll()
                    .where { CanonicalSourceBindingTable.manga eq mangaId }
                    .count()
            }
        assertEquals(1, bindings, "the unique manga index is what makes the loser's write impossible")
    }

    // ---------------------------------------------------------------- export / import

    @Test
    fun `an export round trips through an import`() {
        val workKey = work("Round Trip", CanonicalDuplicateStrategy.PREFER_SCANLATOR, "Asura Scans")
        val primaryManga = newManga("primary")
        val fallbackManga = newManga("fallback")
        val primary = binding(workKey, primaryManga, isPrimary = true)
        binding(workKey, fallbackManga, role = CanonicalBindingRole.FALLBACK, priority = 1)

        val document = CanonicalIdentity.exportDocument()
        assertEquals(1, document.works.size)
        assertEquals(
            2,
            document.works
                .single()
                .bindings.size,
        )

        // the document addresses manga by source coordinates rather than by row id
        assertTrue(
            document.works
                .single()
                .bindings
                .any { it.mangaUrl == primary.mangaUrl },
        )

        // detaching the bindings and importing the document restores the same shape
        document.works.single().bindings.forEach { entry ->
            val binding = CanonicalIdentity.getBindingForManga(if (entry.mangaUrl == primary.mangaUrl) primaryManga else fallbackManga)!!
            CanonicalIdentity.detachBinding(binding.id)
        }
        assertNull(CanonicalIdentity.getBindingForManga(primaryManga))

        val result = CanonicalIdentity.importDocument(document)
        assertEquals(0, result.worksCreated, "the work key already exists, so the work is updated")
        assertEquals(1, result.worksUpdated)
        assertEquals(2, result.bindingsBound)
        assertEquals(0, result.bindingsUnresolved)

        val imported = CanonicalIdentity.getBindingForManga(primaryManga)
        assertNotNull(imported)
        assertTrue(imported!!.isPrimary, "the import restores the preferred source")
        assertEquals(2, CanonicalIdentity.getBindingsForWork(imported.workId).size)
    }

    @Test
    fun `an import keeps an unresolved source visible instead of dropping it`() {
        val mangaId = newManga("gone")
        transaction { MangaTable.deleteWhere { MangaTable.id eq mangaId } }

        val result =
            CanonicalIdentity.importDocument(
                CanonicalIdentityExportDocument(
                    works =
                        listOf(
                            CanonicalIdentityExportWork(
                                workKey = "orphan-work",
                                title = "Orphan",
                                bindings =
                                    listOf(
                                        CanonicalIdentityExportBinding(
                                            sourceId = 1,
                                            mangaUrl = "missing-url",
                                            role = CanonicalBindingRole.FALLBACK,
                                            priority = 0,
                                            isPrimary = false,
                                            mangaTitle = "the source as it was",
                                        ),
                                    ),
                            ),
                        ),
                ),
            )

        assertEquals(1, result.worksCreated)
        assertEquals(0, result.bindingsBound)
        assertEquals(1, result.bindingsUnresolved)

        val work = CanonicalIdentity.getWorkByKey("orphan-work")!!
        val binding = CanonicalIdentity.getBindingsForWork(work.id).single()
        assertNull(binding.mangaId)
        assertEquals("the source as it was", binding.mangaTitle, "the snapshot is imported with the binding")
    }

    @Test
    fun `an import rejects a document it cannot apply`() {
        val duplicateKeys =
            CanonicalIdentityExportDocument(
                works = listOf(CanonicalIdentityExportWork("same", "A"), CanonicalIdentityExportWork("same", "B")),
            )
        assertThrows(CanonicalIdentityImportException::class.java) {
            CanonicalIdentity.importDocument(duplicateKeys)
        }

        val twoPrimaries =
            CanonicalIdentityExportDocument(
                works =
                    listOf(
                        CanonicalIdentityExportWork(
                            workKey = "w",
                            title = "W",
                            bindings =
                                listOf(
                                    CanonicalIdentityExportBinding(priority = 0, isPrimary = true),
                                    CanonicalIdentityExportBinding(priority = 1, isPrimary = true),
                                ),
                        ),
                    ),
            )
        assertThrows(CanonicalIdentityImportException::class.java) {
            CanonicalIdentity.importDocument(twoPrimaries)
        }

        assertNull(CanonicalIdentity.getWorkByKey("same"), "a rejected document writes nothing")
    }

    /**
     * The canonical columns of a revision are a snapshot, not a reference, so the audit outlives the
     * control plane row it names. A work may only be deleted while it is empty, and doing so must not
     * touch the revisions that were discovered under it.
     */
    @Test
    fun `deleting an empty work leaves the canonical audit of its revisions intact`() {
        val mangaId = newManga("archived-under-a-work").also { createChapters(it, 1, read = false) }
        val workKey = work()
        val source = binding(workKey, mangaId, isPrimary = true)

        val revisionId = createCandidates(mangaId).single()
        assertEquals(workKey, ChapterRevision.getRevision(revisionId)!!.canonicalBinding?.workKey)

        // a work that still claims a source is not deletable, and detaching is what makes it empty
        assertEquals(CanonicalWriteOutcome.CONFLICT, CanonicalIdentity.deleteEmptyWork(workKey))
        assertEquals(CanonicalWriteOutcome.APPLIED, CanonicalIdentity.detachBinding(source.id).outcome)
        assertEquals(CanonicalWriteOutcome.APPLIED, CanonicalIdentity.deleteEmptyWork(workKey))
        assertNull(CanonicalIdentity.getWorkByKey(workKey))

        val revision = ChapterRevision.getRevision(revisionId)!!
        assertEquals(workKey, revision.canonicalBinding?.workKey, "the revision keeps the identity it was discovered under")
        assertEquals(CanonicalBindingRole.ACTIVE, revision.canonicalBinding?.bindingRole)
        assertNull(CanonicalIdentity.getBindingForManga(mangaId), "detaching leaves the manga unbound")
    }

    /**
     * A document owns the bindings of the works it describes, and a manga belongs to one work. Both
     * halves of that are observable: the release of another work's claim is counted, and re-applying
     * the same document changes nothing.
     */
    @Test
    fun `an import reports the claim it released and applies the same document once`() {
        val claimed = newManga("claimed")
        val holderKey = work("Holder")
        binding(holderKey, claimed)

        val row = transaction { MangaTable.selectAll().where { MangaTable.id eq claimed }.single() }
        val document =
            CanonicalIdentityExportDocument(
                works =
                    listOf(
                        CanonicalIdentityExportWork(
                            workKey = "migrated-work",
                            title = "Migrated",
                            bindings =
                                listOf(
                                    CanonicalIdentityExportBinding(
                                        sourceId = row[MangaTable.sourceReference],
                                        mangaUrl = row[MangaTable.url],
                                        role = CanonicalBindingRole.ACTIVE,
                                        priority = 0,
                                        isPrimary = true,
                                    ),
                                ),
                        ),
                    ),
            )

        val first = CanonicalIdentity.importDocument(document)
        assertEquals(1, first.worksCreated)
        assertEquals(1, first.bindingsBound)
        assertEquals(0, first.bindingsUnresolved)
        assertEquals(1, first.bindingsRebound, "the claim of the work outside the document is released and counted")

        val migrated = CanonicalIdentity.getBindingForManga(claimed)!!
        assertEquals("migrated-work", migrated.workKey)
        assertTrue(migrated.isPrimary)
        assertEquals(
            0,
            CanonicalIdentity.getBindingsForWork(CanonicalIdentity.getWorkByKey(holderKey)!!.id).size,
            "a manga belongs to at most one work, so the other work lost the binding",
        )

        val second = CanonicalIdentity.importDocument(document)
        assertEquals(0, second.worksCreated, "the work is already there")
        assertEquals(1, second.worksUpdated)
        assertEquals(1, second.bindingsBound)
        assertEquals(0, second.bindingsRebound, "re-applying the same document releases nothing")
        assertEquals(1, CanonicalIdentity.getBindingsForWork(migrated.workId).size)
    }

    /**
     * An import is only safe if a document it cannot apply entirely is applied not at all, so every
     * bound is checked before the transaction opens rather than when a column is written.
     */
    @Test
    fun `an import refuses a document whose values do not fit or repeat a source`() {
        val oversizedUrl =
            CanonicalIdentityExportDocument(
                works =
                    listOf(
                        CanonicalIdentityExportWork(
                            workKey = "w1",
                            title = "W",
                            bindings =
                                listOf(
                                    CanonicalIdentityExportBinding(sourceId = 1, mangaUrl = "u".repeat(2049)),
                                ),
                        ),
                    ),
            )
        assertThrows(CanonicalIdentityImportException::class.java) {
            CanonicalIdentity.importDocument(oversizedUrl)
        }

        val oversizedTitle =
            CanonicalIdentityExportDocument(works = listOf(CanonicalIdentityExportWork("w1", "t".repeat(513))))
        assertThrows(CanonicalIdentityImportException::class.java) {
            CanonicalIdentity.importDocument(oversizedTitle)
        }

        // the same source coordinates identify one manga, and a manga belongs to one work
        val repeatedSource =
            CanonicalIdentityExportDocument(
                works =
                    listOf(
                        CanonicalIdentityExportWork(
                            "w1",
                            "W",
                            bindings = listOf(CanonicalIdentityExportBinding(sourceId = 1, mangaUrl = "https://example.invalid/a")),
                        ),
                        CanonicalIdentityExportWork(
                            "w2",
                            "W2",
                            bindings = listOf(CanonicalIdentityExportBinding(sourceId = 1, mangaUrl = "https://example.invalid/a")),
                        ),
                    ),
            )
        assertThrows(CanonicalIdentityImportException::class.java) {
            CanonicalIdentity.importDocument(repeatedSource)
        }

        // a work key is the public identity, so it stays a bounded token rather than free text
        val markupKey = CanonicalIdentityExportDocument(works = listOf(CanonicalIdentityExportWork("not a key", "W")))
        assertThrows(CanonicalIdentityImportException::class.java) {
            CanonicalIdentity.importDocument(markupKey)
        }

        assertNull(CanonicalIdentity.getWorkByKey("w1"), "a refused document writes nothing")
    }
}
