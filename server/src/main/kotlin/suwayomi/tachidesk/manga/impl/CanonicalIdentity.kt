package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.util.lang.isDuplicateKeyViolation
import suwayomi.tachidesk.manga.model.dataclass.CANONICAL_IDENTITY_EXPORT_SCHEMA_VERSION
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingEligibility
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingRole
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingSnapshot
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingWriteResult
import suwayomi.tachidesk.manga.model.dataclass.CanonicalDuplicateStrategy
import suwayomi.tachidesk.manga.model.dataclass.CanonicalIdentityExportBinding
import suwayomi.tachidesk.manga.model.dataclass.CanonicalIdentityExportDocument
import suwayomi.tachidesk.manga.model.dataclass.CanonicalIdentityExportWork
import suwayomi.tachidesk.manga.model.dataclass.CanonicalIdentityImportResult
import suwayomi.tachidesk.manga.model.dataclass.CanonicalSourceBindingDataClass
import suwayomi.tachidesk.manga.model.dataclass.CanonicalWorkDataClass
import suwayomi.tachidesk.manga.model.dataclass.CanonicalWorkWriteResult
import suwayomi.tachidesk.manga.model.dataclass.CanonicalWriteOutcome
import suwayomi.tachidesk.manga.model.table.CanonicalSourceBindingTable
import suwayomi.tachidesk.manga.model.table.CanonicalWorkTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import java.time.Instant
import java.util.UUID

/** Canonical work and source binding context of one discovery. */
data class CanonicalDiscoveryScope(
    val eligibility: CanonicalBindingEligibility,
    val snapshot: CanonicalBindingSnapshot?,
) {
    val isAcquisitionEligible: Boolean get() = eligibility.isAcquisitionEligible
}

/** Raised when an import document cannot be applied at all; the message is static and redacted. */
class CanonicalIdentityImportException(
    message: String,
) : IllegalArgumentException(message)

/** Longest stored scanlator value; a longer input is refused rather than silently truncated. */
private const val MAX_SCANLATOR_LENGTH = 256

/** Longest accepted work key; the import is the only caller that supplies one. */
private const val MAX_WORK_KEY_LENGTH = 64

/**
 * The shape a work key may have.
 *
 * Generated keys are 32 hexadecimal characters of a UUID. The import accepts any bounded token-shaped
 * key so a hand-written document stays possible, but refuses anything that could carry markup,
 * whitespace or a dialect-specific character into the stored public identity.
 */
private val WORK_KEY_PATTERN = Regex("[0-9A-Za-z_-]{1,$MAX_WORK_KEY_LENGTH}")

/** Bounds of the stored audit columns, so an oversized document is refused rather than truncated. */
private const val MAX_WORK_TITLE_LENGTH = 512
private const val MAX_MANGA_TITLE_LENGTH = 512
private const val MAX_MANGA_URL_LENGTH = 2048
private const val MAX_SOURCE_NAME_LENGTH = 256

/** How many times a fresh work key is tried before giving up; a collision is cryptographic. */
private const val MAX_WORK_KEY_ATTEMPTS = 3

/**
 * Result of reading a scanlator for storage.
 *
 * An absent scanlator and an unusable one are different answers, so they are different values
 * instead of both being `null`: only one of them may be stored.
 */
private sealed interface ScanlatorRead {
    data class Value(
        val scanlator: String?,
    ) : ScanlatorRead

    data object Invalid : ScanlatorRead
}

private fun readScanlator(raw: String?): ScanlatorRead {
    if (raw == null) return ScanlatorRead.Value(null)
    val normalized = CanonicalIdentity.normalizeScanlator(raw) ?: return ScanlatorRead.Invalid
    if (normalized.length > MAX_SCANLATOR_LENGTH) return ScanlatorRead.Invalid
    return ScanlatorRead.Value(normalized)
}

/**
 * The canonical identity control plane: which source manga are considered the same archival series,
 * and which of them ordinary discovery is made from.
 *
 * Three properties are load bearing:
 *
 * 1. **It is additive.** A manga nobody has bound is `UNBOUND` and is discovered, acquired and
 *    archived exactly as it was before this control plane existed. No stock table is rewritten and no
 *    existing row is moved.
 * 2. **It never touches content.** Every operation here writes canonical works and bindings only. It
 *    never deletes, merges or suppresses an archived revision, and it never changes a manga, a
 *    chapter or a download. Detaching a binding stops *new* discovery for that source; it does not
 *    retract anything already recorded.
 * 3. **Every mutation is serialized per work.** The work row is locked first and then its bindings in
 *    ascending id order, so two concurrent operations on one work cannot interleave, and no two
 *    operations can deadlock. Attaching the same manga to two works at once is decided by the unique
 *    index on the manga, and the loser gets `CONFLICT` rather than a half-applied state.
 *
 * What this deliberately does **not** do is decide that two source chapters are the same chapter.
 * Candidate keys and the single-active-revision invariant stay source scoped, because Keiyoushi/Mihon
 * extensions expose no cross-source chapter identity; treating chapter 1 of two sources as equal is a
 * manual, reversible mapping decision that a later layer has to make explicitly. The duplicate policy
 * recorded here is therefore advisory and is reported as such.
 */
object CanonicalIdentity {
    // ---------------------------------------------------------------- normalization

    /**
     * Normalizes a scanlator for comparison.
     *
     * Case is not folded here - the stored value keeps the operator's own spelling - but control
     * characters are removed and runs of whitespace collapse, so "Asura Scans" and "asura  scans"
     * compare equal. A blank value is no scanlator at all.
     */
    fun normalizeScanlator(raw: String?): String? =
        raw
            ?.filterNot { it.isISOControl() }
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotEmpty() }

    /** True when two scanlators name the same group; a blank value never matches a blank value. */
    fun sameScanlator(
        first: String?,
        second: String?,
    ): Boolean {
        val a = normalizeScanlator(first)?.lowercase() ?: return false
        val b = normalizeScanlator(second)?.lowercase() ?: return false
        return a == b
    }

    // ---------------------------------------------------------------- reads

    fun getWorkByKey(workKey: String): CanonicalWorkDataClass? = transaction { readWorkByKey(workKey) }

    fun getWorkById(id: Int): CanonicalWorkDataClass? =
        transaction {
            readWorkById(id)?.let { CanonicalWorkTable.toDataClass(it) }
        }

    /** Every binding of one work, ordered by the priority that makes the ordering unambiguous. */
    fun getBindingsForWork(workId: Int): List<CanonicalSourceBindingDataClass> =
        transaction {
            val workKey =
                CanonicalWorkTable
                    .selectAll()
                    .where { CanonicalWorkTable.id eq workId }
                    .firstOrNull()
                    ?.get(CanonicalWorkTable.workKey)
                    ?: return@transaction emptyList()

            CanonicalSourceBindingTable
                .selectAll()
                .where { CanonicalSourceBindingTable.work eq workId }
                .orderBy(CanonicalSourceBindingTable.priority to SortOrder.ASC, CanonicalSourceBindingTable.id to SortOrder.ASC)
                .map { CanonicalSourceBindingTable.toDataClass(workKey, it) }
        }

    fun getBinding(id: Int): CanonicalSourceBindingDataClass? = transaction { bindingAt(id) }

    /** The binding that claims a manga, or null while it is unbound. */
    fun getBindingForManga(mangaId: Int): CanonicalSourceBindingDataClass? = transaction { bindingForMangaRow(mangaId) }

    /** The binding rows that claim any of the given manga, keyed by manga id. */
    fun getBindingsForManga(mangaIds: Collection<Int>): Map<Int, CanonicalSourceBindingDataClass> {
        val distinct = mangaIds.filterNotNull().distinct()
        if (distinct.isEmpty()) return emptyMap()

        return transaction {
            val rows =
                CanonicalSourceBindingTable
                    .selectAll()
                    .where { CanonicalSourceBindingTable.manga inList distinct }
                    .toList()
            val workKeys = workKeysByIds(rows.map { it[CanonicalSourceBindingTable.work].value })

            rows.associate { row ->
                val workKey = workKeys[row[CanonicalSourceBindingTable.work].value].orEmpty()
                row[CanonicalSourceBindingTable.manga]!!.value to CanonicalSourceBindingTable.toDataClass(workKey, row)
            }
        }
    }

    /**
     * Every binding of every given work, keyed by work id and ordered by priority then id.
     *
     * The batched form exists because a page of works resolves all of their bindings at once: the
     * whole page costs one binding query and one work-key query, not one query per work.
     */
    fun getBindingsForWorks(workIds: Collection<Int>): Map<Int, List<CanonicalSourceBindingDataClass>> {
        val distinct = workIds.distinct()
        if (distinct.isEmpty()) return emptyMap()

        return transaction {
            val workKeys = workKeysByIds(distinct)
            val grouped = linkedMapOf<Int, MutableList<CanonicalSourceBindingDataClass>>()

            CanonicalSourceBindingTable
                .selectAll()
                .where { CanonicalSourceBindingTable.work inList distinct }
                .orderBy(CanonicalSourceBindingTable.priority to SortOrder.ASC, CanonicalSourceBindingTable.id to SortOrder.ASC)
                .toList()
                .forEach { row ->
                    val workId = row[CanonicalSourceBindingTable.work].value
                    grouped.getOrPut(workId) { mutableListOf() } +=
                        CanonicalSourceBindingTable.toDataClass(workKeys[workId].orEmpty(), row)
                }

            grouped
        }
    }

    // ---------------------------------------------------------------- eligibility

    /**
     * The canonical scope of every given manga, keyed by manga id.
     *
     * This is the single place ordinary discovery asks "may this manga record a candidate". It is read
     * only - it never locks, never writes and never mutates a candidate - so it is safe to call from
     * inside a reconciliation transaction.
     */
    fun discoveryScopesFor(mangaIds: Collection<Int?>): Map<Int?, CanonicalDiscoveryScope> {
        val distinct = mangaIds.filterNotNull().distinct()
        if (distinct.isEmpty()) return emptyMap()

        val bound =
            transaction {
                val rows =
                    CanonicalSourceBindingTable
                        .selectAll()
                        .where { CanonicalSourceBindingTable.manga inList distinct }
                        .toList()
                val workKeys = workKeysByIds(rows.map { it[CanonicalSourceBindingTable.work].value })

                rows.associate { row ->
                    val workKey = workKeys[row[CanonicalSourceBindingTable.work].value].orEmpty()
                    val role = CanonicalBindingRole.valueOf(row[CanonicalSourceBindingTable.role])
                    row[CanonicalSourceBindingTable.manga]!!.value to
                        CanonicalDiscoveryScope(
                            eligibility =
                                if (role.isAcquisitionEligible) {
                                    CanonicalBindingEligibility.ACTIVE
                                } else {
                                    CanonicalBindingEligibility.NON_ACTIVE
                                },
                            snapshot =
                                CanonicalBindingSnapshot(
                                    workKey = workKey,
                                    bindingRole = role,
                                    bindingPriority = row[CanonicalSourceBindingTable.priority],
                                    bindingPrimary = row[CanonicalSourceBindingTable.primaryMarker] != null,
                                    bindingSourceId = row[CanonicalSourceBindingTable.sourceId],
                                    bindingMangaUrl = row[CanonicalSourceBindingTable.mangaUrl],
                                ),
                        )
                }
            }

        return distinct.associateWith { bound[it] ?: UNBOUND_SCOPE }
    }

    /**
     * True when ordinary discovery may record candidates for this manga.
     *
     * A null id - a discovery with no manga row - is unbound and therefore eligible, exactly like a
     * manga nobody has claimed.
     */
    fun isAcquisitionEligible(mangaId: Int?): Boolean =
        mangaId == null || discoveryScopesFor(listOf(mangaId))[mangaId]!!.isAcquisitionEligible

    private val UNBOUND_SCOPE = CanonicalDiscoveryScope(CanonicalBindingEligibility.UNBOUND, null)

    // ---------------------------------------------------------------- work operations

    /**
     * Creates an empty canonical work.
     *
     * `PREFER_SCANLATOR` without a scanlator is refused rather than stored: a policy that names no
     * scanlator cannot be applied by anything that later reads it, so accepting it would only create a
     * configuration that silently means "no preference".
     */
    fun createWork(
        title: String,
        duplicateStrategy: CanonicalDuplicateStrategy,
        preferredScanlator: String?,
        now: Long = Instant.now().epochSecond,
    ): CanonicalWorkWriteResult =
        transaction {
            val normalizedTitle = title.trim()
            if (normalizedTitle.isEmpty()) {
                return@transaction CanonicalWorkWriteResult(CanonicalWriteOutcome.CONFLICT, null)
            }

            val scanlator =
                when (val read = readScanlator(preferredScanlator)) {
                    ScanlatorRead.Invalid -> return@transaction CanonicalWorkWriteResult(CanonicalWriteOutcome.CONFLICT, null)
                    is ScanlatorRead.Value -> read.scanlator
                }

            if (duplicateStrategy == CanonicalDuplicateStrategy.PREFER_SCANLATOR && scanlator == null) {
                return@transaction CanonicalWorkWriteResult(CanonicalWriteOutcome.CONFLICT, null)
            }

            val workKey = generateUnusedWorkKey()
            CanonicalWorkTable.insert {
                it[CanonicalWorkTable.workKey] = workKey
                it[CanonicalWorkTable.title] = normalizedTitle
                it[CanonicalWorkTable.duplicateStrategy] = duplicateStrategy.name
                it[CanonicalWorkTable.preferredScanlator] = scanlator
                it[CanonicalWorkTable.createdAt] = now
                it[CanonicalWorkTable.updatedAt] = now
            }

            CanonicalWorkWriteResult(CanonicalWriteOutcome.APPLIED, readWorkByKey(workKey))
        }

    /**
     * Updates the mutable fields of a work.
     *
     * The title and the duplicate policy are advisory metadata, never identity, so changing them moves
     * nothing. [clearPreferredScanlator] exists because "leave it alone" and "remove it" are different
     * requests and one nullable argument cannot express both.
     */
    fun updateWork(
        workKey: String,
        title: String? = null,
        duplicateStrategy: CanonicalDuplicateStrategy? = null,
        preferredScanlator: String? = null,
        clearPreferredScanlator: Boolean = false,
        now: Long = Instant.now().epochSecond,
    ): CanonicalWorkWriteResult =
        transaction {
            val workRow = lockWorkByKey(workKey) ?: return@transaction CanonicalWorkWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)

            val normalizedTitle = title?.trim()
            if (normalizedTitle != null && normalizedTitle.isEmpty()) {
                return@transaction CanonicalWorkWriteResult(CanonicalWriteOutcome.CONFLICT, null)
            }

            val resolvedStrategy =
                duplicateStrategy ?: CanonicalDuplicateStrategy.valueOf(workRow[CanonicalWorkTable.duplicateStrategy])

            val resolvedScanlator =
                when {
                    clearPreferredScanlator -> {
                        null
                    }

                    preferredScanlator != null -> {
                        when (val read = readScanlator(preferredScanlator)) {
                            ScanlatorRead.Invalid -> return@transaction CanonicalWorkWriteResult(CanonicalWriteOutcome.CONFLICT, null)
                            is ScanlatorRead.Value -> read.scanlator
                        }
                    }

                    else -> {
                        workRow[CanonicalWorkTable.preferredScanlator]
                    }
                }

            if (resolvedStrategy == CanonicalDuplicateStrategy.PREFER_SCANLATOR && resolvedScanlator == null) {
                return@transaction CanonicalWorkWriteResult(CanonicalWriteOutcome.CONFLICT, null)
            }

            val workId = workRow[CanonicalWorkTable.id].value
            CanonicalWorkTable.update({ CanonicalWorkTable.id eq workId }) {
                normalizedTitle?.let { value -> it[CanonicalWorkTable.title] = value }
                it[CanonicalWorkTable.duplicateStrategy] = resolvedStrategy.name
                it[CanonicalWorkTable.preferredScanlator] = resolvedScanlator
                it[CanonicalWorkTable.updatedAt] = now
            }

            CanonicalWorkWriteResult(CanonicalWriteOutcome.APPLIED, readWorkByKey(workKey))
        }

    /**
     * Deletes a work that has no bindings.
     *
     * Refusing a non-empty work is the point: deleting a work that still claims sources would erase
     * the record of which sources belong together. Detaching them first is an explicit, auditable
     * step, and no archive data is involved either way.
     */
    fun deleteEmptyWork(workKey: String): CanonicalWriteOutcome =
        transaction {
            val workRow = lockWorkByKey(workKey) ?: return@transaction CanonicalWriteOutcome.NOT_FOUND
            val workId = workRow[CanonicalWorkTable.id].value
            lockBindings(workId)

            val hasBindings =
                CanonicalSourceBindingTable
                    .selectAll()
                    .where { CanonicalSourceBindingTable.work eq workId }
                    .limit(1)
                    .any()
            if (hasBindings) {
                return@transaction CanonicalWriteOutcome.CONFLICT
            }

            CanonicalWorkTable.deleteWhere { CanonicalWorkTable.id eq workId }
            CanonicalWriteOutcome.APPLIED
        }

    // ---------------------------------------------------------------- binding operations

    /**
     * Binds an existing manga to a work.
     *
     * The binding starts as the work's primary only when the caller says so and no other binding
     * already is; a second primary is refused rather than silently resolved, because preferring one
     * source over another is a decision only the operator can make.
     *
     * A manga belongs to exactly one work, so binding it twice - onto the same work or onto a second
     * one - is reported as `CONFLICT` instead of as a failure. Two attaches onto two different works
     * each lock only their own work, so neither can see the other's uncommitted row and both find the
     * manga unbound; the unique index on the manga is what decides that race, and its loser leaves with
     * the same `CONFLICT` as a caller that was simply too late. Every other failure propagates: a
     * database that is unavailable or a write that breaks a different invariant is not something a
     * caller can resolve by re-reading the work.
     */
    fun attachManga(
        workKey: String,
        mangaId: Int,
        role: CanonicalBindingRole,
        priority: Int? = null,
        isPrimary: Boolean = false,
        now: Long = Instant.now().epochSecond,
    ): CanonicalBindingWriteResult =
        try {
            attachMangaInTransaction(workKey, mangaId, role, priority, isPrimary, now)
        } catch (e: Exception) {
            // A duplicate key here is the losing side of that race, which is the outcome the caller
            // already handles; walking the cause chain tells the driver's wrapper apart from the genuine
            // failures below it, and anything else keeps its own meaning instead of being flattened.
            if (!e.isDuplicateKeyViolation()) {
                throw e
            }

            CanonicalBindingWriteResult(CanonicalWriteOutcome.CONFLICT, null)
        }

    /** The attach itself, in a transaction of its own so the boundary above can classify a lost race. */
    private fun attachMangaInTransaction(
        workKey: String,
        mangaId: Int,
        role: CanonicalBindingRole,
        priority: Int?,
        isPrimary: Boolean,
        now: Long,
    ): CanonicalBindingWriteResult =
        transaction {
            val workRow = lockWorkByKey(workKey) ?: return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)
            val workId = workRow[CanonicalWorkTable.id].value
            val bindings = lockBindings(workId)

            val mangaRow =
                MangaTable
                    .selectAll()
                    .where { MangaTable.id eq mangaId }
                    .firstOrNull()
                    ?: return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)

            if (isPrimary && role == CanonicalBindingRole.DISABLED) {
                return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.CONFLICT, null)
            }

            val alreadyBound =
                CanonicalSourceBindingTable
                    .selectAll()
                    .where { CanonicalSourceBindingTable.manga eq mangaId }
                    .limit(1)
                    .any()
            if (alreadyBound) {
                return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.CONFLICT, null)
            }

            val resolvedPriority = priority ?: (bindings.maxOfOrNull { it[CanonicalSourceBindingTable.priority] } ?: -1) + 1
            if (bindings.any { it[CanonicalSourceBindingTable.priority] == resolvedPriority }) {
                return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.CONFLICT, null)
            }

            if (isPrimary && bindings.any { it[CanonicalSourceBindingTable.primaryMarker] != null }) {
                return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.CONFLICT, null)
            }

            val id =
                insertBinding(
                    workId = workId,
                    mangaId = mangaId,
                    role = role,
                    priority = resolvedPriority,
                    isPrimary = isPrimary,
                    mangaTitle = mangaRow[MangaTable.title],
                    mangaUrl = mangaRow[MangaTable.url],
                    sourceId = mangaRow[MangaTable.sourceReference],
                    now = now,
                )

            CanonicalBindingWriteResult(CanonicalWriteOutcome.APPLIED, bindingAt(id))
        }

    /**
     * Removes a binding.
     *
     * This is the operator's explicit "stop treating this source as part of the work" action. It
     * removes the binding row only: the work, the manga, every recorded revision and everything
     * archived stay exactly where they are, and the revision snapshots taken at discovery time keep
     * the historical record of the binding that produced them.
     */
    fun detachBinding(bindingId: Int): CanonicalBindingWriteResult =
        transaction {
            val located = bindingAt(bindingId) ?: return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)
            lockWorkById(located.workId) ?: return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)
            val bindings = lockBindings(located.workId)
            if (bindings.none { it[CanonicalSourceBindingTable.id].value == bindingId }) {
                return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)
            }

            CanonicalSourceBindingTable.deleteWhere { CanonicalSourceBindingTable.id eq bindingId }
            CanonicalBindingWriteResult(CanonicalWriteOutcome.APPLIED, located)
        }

    /**
     * Changes the role and/or the ordering place of one binding.
     *
     * A role of `DISABLED` clears the primary marker, because a source that is excluded from discovery
     * cannot also be the preferred one; leaving the marker set would make the work's "preferred
     * source" unreadable.
     */
    fun changeBinding(
        bindingId: Int,
        role: CanonicalBindingRole? = null,
        priority: Int? = null,
        now: Long = Instant.now().epochSecond,
    ): CanonicalBindingWriteResult =
        transaction {
            val located = bindingAt(bindingId) ?: return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)
            lockWorkById(located.workId) ?: return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)
            val bindings = lockBindings(located.workId)
            val target =
                bindings.firstOrNull { it[CanonicalSourceBindingTable.id].value == bindingId }
                    ?: return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)

            if (priority != null &&
                bindings.any {
                    it[CanonicalSourceBindingTable.id].value != bindingId && it[CanonicalSourceBindingTable.priority] == priority
                }
            ) {
                return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.CONFLICT, null)
            }

            val resolvedRole = role ?: CanonicalBindingRole.valueOf(target[CanonicalSourceBindingTable.role])

            CanonicalSourceBindingTable.update({ CanonicalSourceBindingTable.id eq bindingId }) {
                it[CanonicalSourceBindingTable.role] = resolvedRole.name
                priority?.let { value -> it[CanonicalSourceBindingTable.priority] = value }
                if (resolvedRole == CanonicalBindingRole.DISABLED) {
                    it[CanonicalSourceBindingTable.primaryMarker] = null
                }
                it[CanonicalSourceBindingTable.updatedAt] = now
            }

            CanonicalBindingWriteResult(CanonicalWriteOutcome.APPLIED, bindingAt(bindingId))
        }

    /**
     * Makes one binding the work's primary.
     *
     * Promotion decides *which* bound source is preferred; it does not disable the others, because
     * "prefer this one" and "stop using the others" are separate decisions. The previous primary only
     * loses the marker and keeps its role. Idempotent: promoting the current primary changes nothing.
     */
    fun promoteBinding(
        bindingId: Int,
        now: Long = Instant.now().epochSecond,
    ): CanonicalBindingWriteResult =
        transaction {
            val located = bindingAt(bindingId) ?: return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)
            lockWorkById(located.workId) ?: return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)
            val bindings = lockBindings(located.workId)
            val target =
                bindings.firstOrNull { it[CanonicalSourceBindingTable.id].value == bindingId }
                    ?: return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)

            val previousPrimary = bindings.firstOrNull { it[CanonicalSourceBindingTable.primaryMarker] != null }
            if (previousPrimary != null && previousPrimary[CanonicalSourceBindingTable.id].value == bindingId) {
                // already the primary: only make sure its role agrees, then report it unchanged
                if (target[CanonicalSourceBindingTable.role] != CanonicalBindingRole.ACTIVE.name) {
                    CanonicalSourceBindingTable.update({ CanonicalSourceBindingTable.id eq bindingId }) {
                        it[CanonicalSourceBindingTable.role] = CanonicalBindingRole.ACTIVE.name
                        it[CanonicalSourceBindingTable.updatedAt] = now
                    }
                }
                return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.APPLIED, bindingAt(bindingId))
            }

            if (previousPrimary != null) {
                CanonicalSourceBindingTable.update(
                    { CanonicalSourceBindingTable.id eq previousPrimary[CanonicalSourceBindingTable.id].value },
                ) {
                    it[CanonicalSourceBindingTable.primaryMarker] = null
                    it[CanonicalSourceBindingTable.updatedAt] = now
                }
            }

            CanonicalSourceBindingTable.update({ CanonicalSourceBindingTable.id eq bindingId }) {
                it[CanonicalSourceBindingTable.primaryMarker] = primaryMarkerFor(located.workId)
                it[CanonicalSourceBindingTable.role] = CanonicalBindingRole.ACTIVE.name
                it[CanonicalSourceBindingTable.updatedAt] = now
            }

            CanonicalBindingWriteResult(CanonicalWriteOutcome.APPLIED, bindingAt(bindingId))
        }

    /**
     * Moves the work onto another bound source: the chosen binding becomes primary and `ACTIVE`, and
     * the abandoned primary is demoted to `FALLBACK`.
     *
     * This is the "source A is gone, continue on source B" operation, so the old primary must stop
     * being a discovery source - but it must not be thrown away. A `FALLBACK` binding is still bound,
     * still audited and archivable again by promoting it, which is what makes failover reversible. A
     * `DISABLED` binding is refused: reviving an explicitly excluded source is [changeBinding]'s job,
     * not a silent side effect of failing over.
     *
     * Nothing here changes or deletes a manga, a chapter, a revision or an archived artifact.
     */
    fun failover(
        workKey: String,
        bindingId: Int,
        now: Long = Instant.now().epochSecond,
    ): CanonicalBindingWriteResult =
        transaction {
            val workRow = lockWorkByKey(workKey) ?: return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)
            val workId = workRow[CanonicalWorkTable.id].value
            val bindings = lockBindings(workId)

            val target =
                bindings.firstOrNull { it[CanonicalSourceBindingTable.id].value == bindingId }
                    ?: return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.NOT_FOUND, null)

            if (target[CanonicalSourceBindingTable.role] == CanonicalBindingRole.DISABLED.name) {
                return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.CONFLICT, null)
            }

            val previousPrimary = bindings.firstOrNull { it[CanonicalSourceBindingTable.primaryMarker] != null }
            if (previousPrimary != null && previousPrimary[CanonicalSourceBindingTable.id].value == bindingId) {
                return@transaction CanonicalBindingWriteResult(CanonicalWriteOutcome.APPLIED, bindingAt(bindingId))
            }

            if (previousPrimary != null) {
                CanonicalSourceBindingTable.update(
                    { CanonicalSourceBindingTable.id eq previousPrimary[CanonicalSourceBindingTable.id].value },
                ) {
                    it[CanonicalSourceBindingTable.primaryMarker] = null
                    it[CanonicalSourceBindingTable.role] = CanonicalBindingRole.FALLBACK.name
                    it[CanonicalSourceBindingTable.updatedAt] = now
                }
            }

            CanonicalSourceBindingTable.update({ CanonicalSourceBindingTable.id eq bindingId }) {
                it[CanonicalSourceBindingTable.primaryMarker] = primaryMarkerFor(workId)
                it[CanonicalSourceBindingTable.role] = CanonicalBindingRole.ACTIVE.name
                it[CanonicalSourceBindingTable.updatedAt] = now
            }

            CanonicalBindingWriteResult(CanonicalWriteOutcome.APPLIED, bindingAt(bindingId))
        }

    // ---------------------------------------------------------------- export / import

    /**
     * Exports the whole control plane as a durable, self-describing document.
     *
     * Manga are addressed by source coordinates and their audit snapshot, never by row id, so the
     * document stays meaningful on a database whose ids are different.
     */
    fun exportDocument(): CanonicalIdentityExportDocument =
        transaction {
            val bindingRows =
                CanonicalSourceBindingTable
                    .selectAll()
                    .orderBy(CanonicalSourceBindingTable.id to SortOrder.ASC)
                    .toList()
            val byWork = bindingRows.groupBy { it[CanonicalSourceBindingTable.work].value }

            CanonicalIdentityExportDocument(
                works =
                    CanonicalWorkTable
                        .selectAll()
                        .orderBy(CanonicalWorkTable.id to SortOrder.ASC)
                        .map { workRow ->
                            CanonicalIdentityExportWork(
                                workKey = workRow[CanonicalWorkTable.workKey],
                                title = workRow[CanonicalWorkTable.title],
                                duplicateStrategy = CanonicalDuplicateStrategy.valueOf(workRow[CanonicalWorkTable.duplicateStrategy]),
                                preferredScanlator = workRow[CanonicalWorkTable.preferredScanlator],
                                bindings =
                                    (byWork[workRow[CanonicalWorkTable.id].value] ?: emptyList()).map { row ->
                                        CanonicalIdentityExportBinding(
                                            sourceId = row[CanonicalSourceBindingTable.sourceId],
                                            mangaUrl = row[CanonicalSourceBindingTable.mangaUrl],
                                            role = CanonicalBindingRole.valueOf(row[CanonicalSourceBindingTable.role]),
                                            priority = row[CanonicalSourceBindingTable.priority],
                                            isPrimary = row[CanonicalSourceBindingTable.primaryMarker] != null,
                                            mangaTitle = row[CanonicalSourceBindingTable.mangaTitle],
                                            sourceName = row[CanonicalSourceBindingTable.sourceName],
                                        )
                                    },
                            )
                        },
            )
        }

    /**
     * Applies an export document.
     *
     * Merge, not replace: a work in the document is created or updated, and a work that is not in the
     * document is left alone. The *binding set* of a work in the document is authoritative, because a
     * binding is what the document is about - and because a manga can only be bound once, importing a
     * binding releases whatever binding claimed that manga.
     *
     * Every lock the import takes is taken in ascending id order across all works and then all
     * bindings, so an import can never deadlock against another import or against a single-work
     * mutation, which only ever locks one work and its own bindings.
     *
     * A binding whose manga is no longer present is imported detached with its snapshot rather than
     * dropped: an unresolved source is information the operator needs, not an error to hide. A binding
     * that releases a claim held by a work the document does not describe is reported in
     * [CanonicalIdentityImportResult.bindingsRebound], because that is a change to a work the caller
     * did not name.
     */
    fun importDocument(
        document: CanonicalIdentityExportDocument,
        now: Long = Instant.now().epochSecond,
    ): CanonicalIdentityImportResult {
        validateDocument(document)

        return transaction {
            // all works and all bindings, each set ascending by id: the global lock order shared by
            // every canonical operation in this process
            val lockedWorks =
                CanonicalWorkTable
                    .selectAll()
                    .orderBy(CanonicalWorkTable.id to SortOrder.ASC)
                    .forUpdate()
                    .toList()
            val lockedBindings =
                CanonicalSourceBindingTable
                    .selectAll()
                    .orderBy(CanonicalSourceBindingTable.id to SortOrder.ASC)
                    .forUpdate()
                    .toList()

            // which work currently claims which manga, kept in memory as the document is applied: the
            // unique index makes it a function, and updating it here keeps the released-claim count
            // exact without a query per binding
            val workOfManga =
                lockedBindings
                    .mapNotNull { row ->
                        row[CanonicalSourceBindingTable.manga]?.value?.let { it to row[CanonicalSourceBindingTable.work].value }
                    }.toMap()
                    .toMutableMap()

            var worksCreated = 0
            var worksUpdated = 0
            var bindingsBound = 0
            var bindingsUnresolved = 0
            var bindingsRebound = 0

            document.works.sortedBy { it.workKey }.forEach { entry ->
                val scanlator = normalizeScanlator(entry.preferredScanlator)
                val existing = lockedWorks.firstOrNull { it[CanonicalWorkTable.workKey] == entry.workKey }

                val workId =
                    if (existing == null) {
                        worksCreated++
                        CanonicalWorkTable
                            .insertAndGetId {
                                it[CanonicalWorkTable.workKey] = entry.workKey
                                it[CanonicalWorkTable.title] = entry.title.trim()
                                it[CanonicalWorkTable.duplicateStrategy] = entry.duplicateStrategy.name
                                it[CanonicalWorkTable.preferredScanlator] = scanlator
                                it[CanonicalWorkTable.createdAt] = now
                                it[CanonicalWorkTable.updatedAt] = now
                            }.value
                    } else {
                        worksUpdated++
                        val id = existing[CanonicalWorkTable.id].value
                        CanonicalWorkTable.update({ CanonicalWorkTable.id eq id }) {
                            it[CanonicalWorkTable.title] = entry.title.trim()
                            it[CanonicalWorkTable.duplicateStrategy] = entry.duplicateStrategy.name
                            it[CanonicalWorkTable.preferredScanlator] = scanlator
                            it[CanonicalWorkTable.updatedAt] = now
                        }
                        // the document owns this work's bindings, so the previous set is replaced
                        CanonicalSourceBindingTable.deleteWhere { CanonicalSourceBindingTable.work eq id }
                        workOfManga.entries.removeAll { it.value == id }
                        id
                    }

                entry.bindings.forEach { binding ->
                    val mangaId = resolveManga(binding)
                    if (mangaId == null) {
                        bindingsUnresolved++
                    } else {
                        bindingsBound++
                        // a manga belongs to at most one work: importing this binding releases any
                        // other binding that claims the same manga
                        val previousHolder = workOfManga[mangaId]
                        if (previousHolder != null && previousHolder != workId) {
                            bindingsRebound++
                        }
                        CanonicalSourceBindingTable.deleteWhere { CanonicalSourceBindingTable.manga eq mangaId }
                        workOfManga[mangaId] = workId
                    }

                    insertBinding(
                        workId = workId,
                        mangaId = mangaId,
                        role = binding.role,
                        priority = binding.priority,
                        isPrimary = binding.isPrimary && binding.role != CanonicalBindingRole.DISABLED,
                        mangaTitle = binding.mangaTitle,
                        mangaUrl = binding.mangaUrl,
                        sourceId = binding.sourceId,
                        sourceName = binding.sourceName,
                        now = now,
                    )
                }
            }

            CanonicalIdentityImportResult(worksCreated, worksUpdated, bindingsBound, bindingsUnresolved, bindingsRebound)
        }
    }

    /** Rejects a document that cannot be applied, before anything is written. Static reasons only. */
    private fun validateDocument(document: CanonicalIdentityExportDocument) {
        if (document.schemaVersion != CANONICAL_IDENTITY_EXPORT_SCHEMA_VERSION) {
            throw CanonicalIdentityImportException("Unsupported canonical identity export schema version")
        }

        val keys = document.works.map { it.workKey }
        if (keys.any { !WORK_KEY_PATTERN.matches(it) }) {
            throw CanonicalIdentityImportException("Invalid canonical work key")
        }
        if (keys.size != keys.distinct().size) {
            throw CanonicalIdentityImportException("Duplicate canonical work key")
        }
        if (document.works.any { it.title.isBlank() || it.title.trim().length > MAX_WORK_TITLE_LENGTH }) {
            throw CanonicalIdentityImportException("Invalid canonical work title")
        }
        if (document.works.any { (normalizeScanlator(it.preferredScanlator)?.length ?: 0) > MAX_SCANLATOR_LENGTH }) {
            throw CanonicalIdentityImportException("Invalid canonical scanlator preference")
        }
        if (document.works.any { work -> work.bindings.count { it.isPrimary } > 1 }) {
            throw CanonicalIdentityImportException("A canonical work may have at most one primary binding")
        }
        if (document.works.any { work ->
                work.bindings.map { it.priority }.size !=
                    work.bindings
                        .map { it.priority }
                        .distinct()
                        .size
            }
        ) {
            throw CanonicalIdentityImportException("A canonical work may not repeat a binding priority")
        }
        if (document.works.any {
                it.duplicateStrategy == CanonicalDuplicateStrategy.PREFER_SCANLATOR &&
                    normalizeScanlator(it.preferredScanlator) == null
            }
        ) {
            throw CanonicalIdentityImportException("A scanlator preference requires a scanlator")
        }

        // every audit value the document carries has to fit the column it is written to, otherwise an
        // oversized document would either be truncated silently or fail the write with a dialect error.
        // None of it is ever a reason to apply part of the document.
        val bindings = document.works.flatMap { it.bindings }
        if (bindings.any { (it.mangaUrl?.length ?: 0) > MAX_MANGA_URL_LENGTH }) {
            throw CanonicalIdentityImportException("Invalid canonical binding source url")
        }
        if (bindings.any { (it.mangaTitle?.length ?: 0) > MAX_MANGA_TITLE_LENGTH }) {
            throw CanonicalIdentityImportException("Invalid canonical binding source title")
        }
        if (bindings.any { (it.sourceName?.length ?: 0) > MAX_SOURCE_NAME_LENGTH }) {
            throw CanonicalIdentityImportException("Invalid canonical binding source name")
        }

        // a source coordinate identifies one manga, and a manga belongs to one work, so the same
        // coordinates may not appear under two works of one document. Rejecting it here is what keeps
        // the import faithful: the alternative is a last-writer-wins release the caller never asked for.
        val coordinates =
            bindings.mapNotNull { binding ->
                val sourceId = binding.sourceId
                val url = binding.mangaUrl
                if (sourceId == null || url == null) null else sourceId to url
            }
        if (coordinates.size != coordinates.distinct().size) {
            throw CanonicalIdentityImportException("A source may be bound to at most one canonical work")
        }
    }

    /** The manga a binding names, by source coordinates; null when it no longer exists. */
    private fun resolveManga(binding: CanonicalIdentityExportBinding): Int? {
        val sourceId = binding.sourceId ?: return null
        val url = binding.mangaUrl ?: return null

        return MangaTable
            .selectAll()
            .where { (MangaTable.sourceReference eq sourceId) and (MangaTable.url eq url) }
            .limit(1)
            .firstOrNull()
            ?.get(MangaTable.id)
            ?.value
    }

    // ---------------------------------------------------------------- internals

    /**
     * work id -> public key, for the works a caller actually resolved a binding for.
     *
     * Scoped by id on purpose: the control plane is read on hot discovery paths, so resolving a few
     * bindings must not read the whole work table.
     */
    private fun workKeysByIds(ids: Collection<Int>): Map<Int, String> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return emptyMap()

        return CanonicalWorkTable
            .selectAll()
            .where { CanonicalWorkTable.id inList distinct }
            .associate { it[CanonicalWorkTable.id].value to it[CanonicalWorkTable.workKey] }
    }

    private fun workKeyOf(bindingRow: ResultRow): String =
        readWorkById(bindingRow[CanonicalSourceBindingTable.work].value)
            ?.get(CanonicalWorkTable.workKey)
            .orEmpty()

    private fun bindingAt(id: Int): CanonicalSourceBindingDataClass? =
        CanonicalSourceBindingTable
            .selectAll()
            .where { CanonicalSourceBindingTable.id eq id }
            .firstOrNull()
            ?.let { row -> CanonicalSourceBindingTable.toDataClass(workKeyOf(row), row) }

    private fun bindingForMangaRow(mangaId: Int): CanonicalSourceBindingDataClass? =
        CanonicalSourceBindingTable
            .selectAll()
            .where { CanonicalSourceBindingTable.manga eq mangaId }
            .limit(1)
            .firstOrNull()
            ?.let { row -> CanonicalSourceBindingTable.toDataClass(workKeyOf(row), row) }

    private fun readWorkByKey(workKey: String): CanonicalWorkDataClass? =
        CanonicalWorkTable
            .selectAll()
            .where { CanonicalWorkTable.workKey eq workKey }
            .firstOrNull()
            ?.let { CanonicalWorkTable.toDataClass(it) }

    private fun readWorkById(id: Int): ResultRow? =
        CanonicalWorkTable
            .selectAll()
            .where { CanonicalWorkTable.id eq id }
            .firstOrNull()

    /** Locks the work row of a work, so every later step of the operation is serialized. */
    private fun lockWorkByKey(workKey: String): ResultRow? =
        CanonicalWorkTable
            .selectAll()
            .where { CanonicalWorkTable.workKey eq workKey }
            .forUpdate()
            .firstOrNull()

    private fun lockWorkById(id: Int): ResultRow? =
        CanonicalWorkTable
            .selectAll()
            .where { CanonicalWorkTable.id eq id }
            .forUpdate()
            .firstOrNull()

    /**
     * Locks every binding of a work, ascending by id.
     *
     * The whole set is locked - not just the row being changed - because every operation here decides
     * on the set: "is there already a primary", "is this priority taken", "which priority is next". A
     * partial lock would let a concurrent attach invalidate exactly those answers.
     */
    private fun lockBindings(workId: Int): List<ResultRow> =
        CanonicalSourceBindingTable
            .selectAll()
            .where { CanonicalSourceBindingTable.work eq workId }
            .orderBy(CanonicalSourceBindingTable.id to SortOrder.ASC)
            .forUpdate()
            .toList()

    private fun primaryMarkerFor(workId: Int): String = "work:$workId"

    /** A fresh random work key that is not in use; see [MAX_WORK_KEY_ATTEMPTS]. */
    private fun generateUnusedWorkKey(): String {
        repeat(MAX_WORK_KEY_ATTEMPTS) {
            val key = UUID.randomUUID().toString().replace("-", "")
            val taken =
                CanonicalWorkTable
                    .selectAll()
                    .where { CanonicalWorkTable.workKey eq key }
                    .limit(1)
                    .any()
            if (!taken) {
                return key
            }
        }
        throw IllegalStateException("Failed to generate a unique canonical work key")
    }

    /**
     * Inserts one binding row.
     *
     * The snapshot columns are supplied by the caller so an import can preserve the audit of a manga
     * that no longer exists, and so attaching resolves them from the live manga exactly once. The
     * source name is recorded because it is the only human readable identity a source has once the
     * manga row is gone.
     */
    private fun insertBinding(
        workId: Int,
        mangaId: Int?,
        role: CanonicalBindingRole,
        priority: Int,
        isPrimary: Boolean,
        mangaTitle: String?,
        mangaUrl: String?,
        sourceId: Long?,
        sourceName: String? = null,
        now: Long,
    ): Int =
        CanonicalSourceBindingTable
            .insertAndGetId {
                it[CanonicalSourceBindingTable.work] = workId
                it[CanonicalSourceBindingTable.manga] = mangaId
                it[CanonicalSourceBindingTable.role] = role.name
                it[CanonicalSourceBindingTable.priority] = priority
                it[CanonicalSourceBindingTable.primaryMarker] = if (isPrimary) primaryMarkerFor(workId) else null
                it[CanonicalSourceBindingTable.mangaTitle] = mangaTitle
                it[CanonicalSourceBindingTable.mangaUrl] = mangaUrl
                it[CanonicalSourceBindingTable.sourceId] = sourceId
                it[CanonicalSourceBindingTable.sourceName] = sourceName ?: sourceNameOf(sourceId)
                it[CanonicalSourceBindingTable.boundAt] = now
                it[CanonicalSourceBindingTable.updatedAt] = now
            }.value

    private fun sourceNameOf(sourceId: Long?): String? =
        sourceId?.let {
            SourceTable
                .selectAll()
                .where { SourceTable.id eq it }
                .limit(1)
                .firstOrNull()
                ?.get(SourceTable.name)
        }
}
