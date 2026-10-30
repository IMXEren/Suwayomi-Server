package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingEligibility
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingRole
import suwayomi.tachidesk.manga.model.dataclass.CanonicalDuplicateStrategy
import suwayomi.tachidesk.manga.model.dataclass.CanonicalSourceBindingDataClass
import suwayomi.tachidesk.manga.model.dataclass.CanonicalWorkDataClass
import suwayomi.tachidesk.manga.model.table.columns.truncatingVarchar

/**
 * A canonical work: the archive-level series that several source manga may belong to.
 *
 * [workKey] is the public identity and is opaque - a random value, never derived from a title or a
 * source URL. Mutable display metadata ([title]) and the advisory duplicate policy are deliberately
 * not part of it, so renaming a work or changing its policy never moves the archive.
 */
object CanonicalWorkTable : IntIdTable("canonicalwork") {
    val workKey = varchar("work_key", 64).uniqueIndex()

    val title = truncatingVarchar("title", 512)

    /** advisory only; see [CanonicalDuplicateStrategy] */
    val duplicateStrategy = varchar("duplicate_strategy", 64).default(CanonicalDuplicateStrategy.KEEP_ALL.name)

    /** kept as written; comparison normalizes separately via CanonicalIdentity.normalizeScanlator */
    val preferredScanlator = truncatingVarchar("preferred_scanlator", 256).nullable()

    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    init {
        index("canonical_work_title_idx", false, title, id)
        index("canonical_work_updated_idx", false, updatedAt, id)
    }
}

/**
 * Binding of an existing manga to a canonical work.
 *
 * [manga] is `SET NULL`, and the columns beside it are an immutable audit snapshot of the manga at
 * bind time: deleting a source manga must not erase what the work contained, and it must never touch
 * the canonical work or the archive.
 *
 * Two invariants live in the database rather than in the operations:
 *
 * - `unique (manga)` - one manga belongs to at most one work. Nulls are distinct in both H2 and
 *   PostgreSQL, so a detached binding keeps its audit without blocking a later re-bind.
 * - `unique (primary_marker)` - at most one primary per work. [primaryMarker] carries the work id
 *   while the row is primary and is null otherwise, which makes the marker work-scoped, so no
 *   operation can leave a work with two preferred sources even if two of them race.
 *
 * `unique (work, priority)` makes the ordering of a work's bindings a permutation: no two bindings
 * may claim the same place, so "the next fallback" is always unambiguous.
 */
object CanonicalSourceBindingTable : IntIdTable("canonicalsourcebinding") {
    val work = reference("work", CanonicalWorkTable, ReferenceOption.CASCADE)

    /** the bound manga, or null once the source row is gone; the snapshot below survives it */
    val manga = optReference("manga", MangaTable, ReferenceOption.SET_NULL)

    val role = varchar("role", 64).default(CanonicalBindingRole.ACTIVE.name)

    /** the place of this binding among the work's bindings; unique within the work */
    val priority = integer("priority").default(0)

    /** carries the work id while this row is the work's primary, null otherwise */
    val primaryMarker = varchar("primary_marker", 64).nullable()

    // immutable audit snapshot of the manga at bind time
    val mangaTitle = truncatingVarchar("manga_title", 512).nullable()
    val mangaUrl = varchar("manga_url", 2048).nullable()
    val sourceId = long("source_id").nullable()
    val sourceName = truncatingVarchar("source_name", 256).nullable()

    val boundAt = long("bound_at")
    val updatedAt = long("updated_at")

    init {
        index("canonical_binding_manga_idx", true, manga)
        index("canonical_binding_primary_idx", true, primaryMarker)
        index("canonical_binding_priority_idx", true, work, priority)
        index("canonical_binding_work_idx", false, work, priority, id)
    }
}

fun CanonicalWorkTable.toDataClass(workEntry: ResultRow) =
    CanonicalWorkDataClass(
        id = workEntry[id].value,
        workKey = workEntry[workKey],
        title = workEntry[title],
        duplicateStrategy = CanonicalDuplicateStrategy.valueOf(workEntry[duplicateStrategy]),
        preferredScanlator = workEntry[preferredScanlator],
        createdAt = workEntry[createdAt],
        updatedAt = workEntry[updatedAt],
    )

/**
 * Reads a binding together with the work key it belongs to.
 *
 * The key is joined in rather than looked up again by the caller so a binding is never reported
 * without the identity that makes it meaningful.
 */
fun CanonicalSourceBindingTable.toDataClass(
    workKey: String,
    bindingEntry: ResultRow,
) = CanonicalSourceBindingDataClass(
    id = bindingEntry[id].value,
    workId = bindingEntry[work].value,
    workKey = workKey,
    mangaId = bindingEntry[manga]?.value,
    role = CanonicalBindingRole.valueOf(bindingEntry[role]),
    priority = bindingEntry[priority],
    isPrimary = bindingEntry[primaryMarker] != null,
    mangaTitle = bindingEntry[mangaTitle],
    mangaUrl = bindingEntry[mangaUrl],
    sourceId = bindingEntry[sourceId],
    sourceName = bindingEntry[sourceName],
    boundAt = bindingEntry[boundAt],
    updatedAt = bindingEntry[updatedAt],
)

/** Convenience for the eligibility of a binding row without materializing its data class. */
fun CanonicalSourceBindingTable.eligibilityOf(bindingEntry: ResultRow): CanonicalBindingEligibility =
    if (CanonicalBindingRole.valueOf(bindingEntry[role]).isAcquisitionEligible) {
        CanonicalBindingEligibility.ACTIVE
    } else {
        CanonicalBindingEligibility.NON_ACTIVE
    }
