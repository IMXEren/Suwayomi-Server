package suwayomi.tachidesk.graphql.types

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import graphql.schema.DataFetchingEnvironment
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Edge
import suwayomi.tachidesk.graphql.server.primitives.Node
import suwayomi.tachidesk.graphql.server.primitives.NodeList
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingRole
import suwayomi.tachidesk.manga.model.dataclass.CanonicalDuplicateStrategy
import suwayomi.tachidesk.manga.model.dataclass.CanonicalSourceBindingDataClass
import suwayomi.tachidesk.manga.model.dataclass.CanonicalWorkDataClass
import java.util.concurrent.CompletableFuture

/**
 * One canonical work: the archive-level series several source bindings may belong to.
 *
 * [duplicatePolicyApplied] is deliberately part of the type rather than a note in the documentation.
 * The duplicate strategy recorded on a work is **advisory**: nothing in this server deletes, merges or
 * suppresses an archived revision because of it, and publication is not gated on it. A client has to
 * be able to tell that from the API itself, so the answer ships with the object.
 */
class CanonicalWorkType(
    val id: Int,
    /** opaque, stable public identity; the archive addresses a work by this, never by its title */
    val workKey: String,
    val title: String,
    /** advisory until a reconciliation layer applies it; see [duplicatePolicyApplied] */
    val duplicateStrategy: CanonicalDuplicateStrategy,
    /** the scanlator the strategy prefers, kept as written; null means no preference is recorded */
    val preferredScanlator: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * Always false in this slice.
     *
     * It becomes true only when a reconciliation layer that actually applies [duplicateStrategy]
     * exists, and applying it has to be reversible - which means every accepted revision must still
     * be there when it runs. Until then the policy is recorded intent, not behaviour.
     */
    val duplicatePolicyApplied: Boolean,
) : Node {
    constructor(dataClass: CanonicalWorkDataClass) : this(
        dataClass.id,
        dataClass.workKey,
        dataClass.title,
        dataClass.duplicateStrategy,
        dataClass.preferredScanlator,
        dataClass.createdAt,
        dataClass.updatedAt,
        DUPLICATE_POLICY_APPLIED,
    )

    /** The work's bindings, ordered by priority then id. */
    fun bindings(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<CanonicalSourceBindingNodeList> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, CanonicalSourceBindingNodeList>("CanonicalBindingsForWorkDataLoader", id)

    /**
     * The work's preferred source, or null when none is chosen.
     *
     * Derived from [bindings] rather than loaded separately, so a client can never observe a primary
     * that is not in the list it was given.
     */
    fun primaryBinding(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<CanonicalSourceBindingType?> =
        bindings(dataFetchingEnvironment).thenApply { list -> list.nodes.firstOrNull { it.isPrimary } }

    fun bindingCount(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<Int> =
        bindings(dataFetchingEnvironment).thenApply { it.totalCount }

    companion object {
        /** See [duplicatePolicyApplied]. */
        const val DUPLICATE_POLICY_APPLIED = false
    }
}

/**
 * One binding of an existing manga to a canonical work.
 *
 * [mangaTitle], [mangaUrl], [sourceId] and [sourceName] are the audit snapshot taken at bind time and
 * are never rewritten, so a binding stays readable after its manga is deleted. [mangaAvailable] is how
 * a client tells such a detached binding apart from a live one.
 */
class CanonicalSourceBindingType(
    val id: Int,
    val workId: Int,
    val workKey: String,
    val mangaId: Int?,
    val role: CanonicalBindingRole,
    /** the place of this binding among the work's bindings; unique within the work */
    val priority: Int,
    val isPrimary: Boolean,
    val mangaTitle: String?,
    val mangaUrl: String?,
    val sourceId: Long?,
    val sourceName: String?,
    val boundAt: Long,
    val updatedAt: Long,
    /** true while this binding is the source ordinary discovery records candidates from */
    val acquisitionEligible: Boolean,
    /** false once the source manga is gone; the binding and its archive are kept either way */
    val mangaAvailable: Boolean,
) : Node {
    constructor(dataClass: CanonicalSourceBindingDataClass) : this(
        dataClass.id,
        dataClass.workId,
        dataClass.workKey,
        dataClass.mangaId,
        dataClass.role,
        dataClass.priority,
        dataClass.isPrimary,
        dataClass.mangaTitle,
        dataClass.mangaUrl,
        dataClass.sourceId,
        dataClass.sourceName,
        dataClass.boundAt,
        dataClass.updatedAt,
        dataClass.role.isAcquisitionEligible,
        dataClass.mangaId != null,
    )

    fun work(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<CanonicalWorkType?> =
        dataFetchingEnvironment.getValueFromDataLoader("CanonicalWorkDataLoader", workId)

    fun manga(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<MangaType?> =
        mangaId?.let { dataFetchingEnvironment.getValueFromDataLoader<Int, MangaType?>("MangaDataLoader", it) }
            ?: CompletableFuture.completedFuture(null)
}

data class CanonicalWorkNodeList(
    override val nodes: List<CanonicalWorkType>,
    override val edges: List<CanonicalWorkEdge>,
    override val pageInfo: PageInfo,
    override val totalCount: Int,
) : NodeList() {
    data class CanonicalWorkEdge(
        override val cursor: Cursor,
        override val node: CanonicalWorkType,
    ) : Edge()
}

data class CanonicalSourceBindingNodeList(
    override val nodes: List<CanonicalSourceBindingType>,
    override val edges: List<CanonicalSourceBindingEdge>,
    override val pageInfo: PageInfo,
    override val totalCount: Int,
) : NodeList() {
    data class CanonicalSourceBindingEdge(
        override val cursor: Cursor,
        override val node: CanonicalSourceBindingType,
    ) : Edge()

    companion object {
        /** The shape the per-work and per-manga data loaders return: no paging, everything loaded. */
        fun of(bindings: List<CanonicalSourceBindingType>): CanonicalSourceBindingNodeList =
            CanonicalSourceBindingNodeList(
                nodes = bindings,
                edges = bindings.mapIndexed { index, node -> CanonicalSourceBindingEdge(Cursor(index.toString()), node) },
                pageInfo =
                    PageInfo(
                        hasNextPage = false,
                        hasPreviousPage = false,
                        startCursor = bindings.indices.firstOrNull()?.let { Cursor(it.toString()) },
                        endCursor = bindings.indices.lastOrNull()?.let { Cursor(it.toString()) },
                    ),
                totalCount = bindings.size,
            )
    }
}

/**
 * How much canonical identity exists, and what it is currently doing.
 *
 * [duplicatePolicyApplied] is here as well as on each work so a client that only wants "is this
 * advisory" does not have to walk the works.
 */
class CanonicalIdentityStatusType(
    val workCount: Int,
    val bindingCount: Int,
    val primaryBindingCount: Int,
    val activeBindingCount: Int,
    val fallbackBindingCount: Int,
    val disabledBindingCount: Int,
    /** bindings whose source manga no longer exists; they are kept for their audit and history */
    val detachedBindingCount: Int,
    val duplicatePolicyApplied: Boolean,
)

/** A durable, self-describing export of the control plane, as JSON. */
class CanonicalIdentityExportType(
    val payload: String,
    val schemaVersion: Int,
    val workCount: Int,
    val bindingCount: Int,
)

/** What one import applied. */
class CanonicalIdentityImportType(
    val worksCreated: Int,
    val worksUpdated: Int,
    val bindingsBound: Int,
    /** bindings whose manga is not present; imported detached with their snapshot intact */
    val bindingsUnresolved: Int,
    /**
     * bindings that released a claim held by a work the document does not describe.
     *
     * A manga belongs to at most one work, so this is the count of works outside the document that
     * lost a binding to it - a change the caller has to be able to see.
     */
    val bindingsRebound: Int,
)
