package suwayomi.tachidesk.manga.model.dataclass

import kotlinx.serialization.Serializable

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * How the archive intends to resolve two source bindings of the same canonical work that publish the
 * same chapter.
 *
 * This is **advisory** in this slice. The control plane records the intent, audits it and reports it,
 * but nothing reads it to delete, merge or suppress anything: an archived revision is only ever
 * removed by the retention policy, and publication is not gated on it. Applying the policy needs a
 * cross-source chapter mapping, and that mapping is only safe once it is reversible - which is
 * exactly why it is a separate, later layer.
 */
enum class CanonicalDuplicateStrategy {
    /** every binding's chapters are archived independently; nothing is treated as a duplicate */
    KEEP_ALL,

    /** the primary binding's copy is the preferred one when two bindings publish the same chapter */
    PREFER_PRIMARY_SOURCE,

    /** a configured scanlator's copy is the preferred one regardless of which binding published it */
    PREFER_SCANLATOR,
}

/**
 * What a bound source participates in.
 *
 * The role is what decides whether ordinary discovery records candidates for that manga at all:
 * exactly one role means "this is a source this work is archived from".
 */
enum class CanonicalBindingRole {
    /** the source is used for ordinary discovery and acquisition */
    ACTIVE,

    /** the source is bound and audited but records no candidate until it is promoted */
    FALLBACK,

    /** the source is deliberately excluded from discovery; it is kept for its audit and history */
    DISABLED,
    ;

    val isAcquisitionEligible: Boolean get() = this == ACTIVE
}

/**
 * How a manga's canonical binding affects ordinary candidate discovery.
 *
 * [UNBOUND] is not a degraded [ACTIVE]: a manga nobody has claimed is discovered exactly as it was
 * before canonical identity existed, byte for byte. Only an explicit binding can change that.
 */
enum class CanonicalBindingEligibility {
    UNBOUND,
    ACTIVE,
    NON_ACTIVE,
    ;

    val isAcquisitionEligible: Boolean get() = this != NON_ACTIVE
}

/**
 * Immutable snapshot of the canonical binding a revision candidate was discovered under.
 *
 * Recorded per candidate - never read back from the live binding - so detaching, re-binding or
 * deleting the work or the manga afterwards cannot rewrite what the discovery was made for. It is
 * also what the archive manifest carries (schema version 5), so the archive stays intelligible
 * without the database.
 *
 * There is deliberately no cross-source chapter identity here. Candidate keys and the single-active
 * -revision invariant remain source scoped, because two source chapters are not known to be
 * equivalent; establishing that is a manual, reversible mapping decision, not something discovery
 * may assume.
 */
data class CanonicalBindingSnapshot(
    val workKey: String,
    val bindingRole: CanonicalBindingRole,
    val bindingPriority: Int,
    val bindingPrimary: Boolean,
    val bindingSourceId: Long?,
    val bindingMangaUrl: String?,
)

/** One canonical work: the archive-level series several source bindings may belong to. */
data class CanonicalWorkDataClass(
    val id: Int,
    /** opaque, stable public identity; never derived from a title or a source URL */
    val workKey: String,
    val title: String,
    /** advisory until a reconciliation layer applies it; see [CanonicalDuplicateStrategy] */
    val duplicateStrategy: CanonicalDuplicateStrategy,
    /** kept exactly as the operator wrote it; comparison normalizes separately */
    val preferredScanlator: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One binding of an existing manga to a canonical work.
 *
 * [mangaId] is nullable and the reference is `SET NULL`: deleting a manga must not erase the audit of
 * what the work contained, so the snapshot columns beside it survive the source row.
 */
data class CanonicalSourceBindingDataClass(
    val id: Int,
    val workId: Int,
    val workKey: String,
    val mangaId: Int?,
    val role: CanonicalBindingRole,
    /** the ordering of a work's bindings; unique within the work */
    val priority: Int,
    /**
     * True while this binding is the work's preferred source.
     *
     * Enforced by a unique marker in the database rather than by convention, so "at most one primary
     * per work" cannot be violated by a concurrent write.
     */
    val isPrimary: Boolean,
    /** audit snapshot of the manga at bind time; never rewritten when the manga changes */
    val mangaTitle: String?,
    val mangaUrl: String?,
    val sourceId: Long?,
    val sourceName: String?,
    val boundAt: Long,
    val updatedAt: Long,
) {
    val eligibility: CanonicalBindingEligibility
        get() = if (role.isAcquisitionEligible) CanonicalBindingEligibility.ACTIVE else CanonicalBindingEligibility.NON_ACTIVE
}

/** What an explicit canonical write did; a conflict is an answer, not an exception. */
enum class CanonicalWriteOutcome {
    APPLIED,
    CONFLICT,
    NOT_FOUND,
}

/** Result of a write that targets one canonical work. */
data class CanonicalWorkWriteResult(
    val outcome: CanonicalWriteOutcome,
    val work: CanonicalWorkDataClass?,
)

/** Result of a write that targets one canonical source binding. */
data class CanonicalBindingWriteResult(
    val outcome: CanonicalWriteOutcome,
    val binding: CanonicalSourceBindingDataClass?,
)

/** Schema version of [CanonicalIdentityExportDocument]. */
const val CANONICAL_IDENTITY_EXPORT_SCHEMA_VERSION = 1

/**
 * Durable, self-describing export of the canonical identity control plane.
 *
 * It is a document rather than more protobuf backup fields: a work is a series-level object with many
 * bindings, so folding it into the per-manga backup record would have to reconstruct a work from
 * whichever manga happened to be restored first - and a chunked, resumable restore can restore
 * those in any order. Keeping the export separate leaves the existing backup wire untouched, which
 * is the property that matters for a backup format other servers also read.
 */
@Serializable
data class CanonicalIdentityExportDocument(
    val schemaVersion: Int = CANONICAL_IDENTITY_EXPORT_SCHEMA_VERSION,
    val works: List<CanonicalIdentityExportWork> = emptyList(),
)

@Serializable
data class CanonicalIdentityExportWork(
    val workKey: String,
    val title: String,
    val duplicateStrategy: CanonicalDuplicateStrategy = CanonicalDuplicateStrategy.KEEP_ALL,
    val preferredScanlator: String? = null,
    val bindings: List<CanonicalIdentityExportBinding> = emptyList(),
)

/**
 * One binding of an exported work.
 *
 * The manga is identified by its source coordinates rather than by its row id, because ids are not
 * stable across a restore. When no manga matches, the binding is imported detached with its snapshot
 * intact instead of being dropped: an unresolved binding is information, not an error.
 */
@Serializable
data class CanonicalIdentityExportBinding(
    val sourceId: Long? = null,
    val mangaUrl: String? = null,
    val role: CanonicalBindingRole = CanonicalBindingRole.FALLBACK,
    val priority: Int = 0,
    val isPrimary: Boolean = false,
    val mangaTitle: String? = null,
    val sourceName: String? = null,
)

/** What one import applied. */
data class CanonicalIdentityImportResult(
    val worksCreated: Int,
    val worksUpdated: Int,
    val bindingsBound: Int,
    /** bindings whose manga no longer exists; they are imported detached with their snapshot */
    val bindingsUnresolved: Int,
    /**
     * bindings that displaced a claim held by a work the document does not describe.
     *
     * A manga belongs to at most one work, so a binding the document owns has to release whatever
     * other binding claimed that manga. That release is a real change to a work outside the document,
     * so it is counted rather than applied silently.
     */
    val bindingsRebound: Int,
)
