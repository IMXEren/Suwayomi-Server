package suwayomi.tachidesk.graphql.queries

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Order
import suwayomi.tachidesk.graphql.server.primitives.OrderBy
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.graphql.server.primitives.QueryResults
import suwayomi.tachidesk.graphql.server.primitives.applyBeforeAfter
import suwayomi.tachidesk.graphql.server.primitives.applySortAndGetPaginationInfo
import suwayomi.tachidesk.graphql.server.primitives.greaterNotUnique
import suwayomi.tachidesk.graphql.server.primitives.lessNotUnique
import suwayomi.tachidesk.graphql.types.CanonicalIdentityStatusType
import suwayomi.tachidesk.graphql.types.CanonicalSourceBindingNodeList
import suwayomi.tachidesk.graphql.types.CanonicalSourceBindingType
import suwayomi.tachidesk.graphql.types.CanonicalWorkNodeList
import suwayomi.tachidesk.graphql.types.CanonicalWorkType
import suwayomi.tachidesk.manga.impl.CanonicalIdentity
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingRole
import suwayomi.tachidesk.manga.model.table.CanonicalSourceBindingTable
import suwayomi.tachidesk.manga.model.table.CanonicalWorkTable
import suwayomi.tachidesk.manga.model.table.toDataClass

/**
 * Read side of the canonical identity control plane.
 *
 * Every field is authenticated because the control plane describes the shape of the operator's
 * library: which sources are considered the same series and which one the archive is fed from.
 */
class CanonicalIdentityQuery {
    /**
     * Order options of a work listing.
     *
     * Every one of them is backed by a non-null numeric column, which is what makes a cursor correct:
     * a nullable or textual sort key would need a null region and a tie-break that the cursor encoding
     * here cannot express. Title is therefore a *filter* ([titleContains]) rather than an order.
     */
    enum class CanonicalWorkOrderBy(
        override val column: Column<*>,
    ) : OrderBy<CanonicalWorkType> {
        ID(CanonicalWorkTable.id),
        CREATED_AT(CanonicalWorkTable.createdAt),
        UPDATED_AT(CanonicalWorkTable.updatedAt),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> CanonicalWorkTable.id greater cursor.value.toInt()
                CREATED_AT -> greaterNotUnique(CanonicalWorkTable.createdAt, CanonicalWorkTable.id, cursor, String::toLong)
                UPDATED_AT -> greaterNotUnique(CanonicalWorkTable.updatedAt, CanonicalWorkTable.id, cursor, String::toLong)
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> CanonicalWorkTable.id less cursor.value.toInt()
                CREATED_AT -> lessNotUnique(CanonicalWorkTable.createdAt, CanonicalWorkTable.id, cursor, String::toLong)
                UPDATED_AT -> lessNotUnique(CanonicalWorkTable.updatedAt, CanonicalWorkTable.id, cursor, String::toLong)
            }

        override fun asCursor(type: CanonicalWorkType): Cursor {
            val value =
                when (this) {
                    ID -> type.id.toString()
                    CREATED_AT -> type.id.toString() + "-" + type.createdAt
                    UPDATED_AT -> type.id.toString() + "-" + type.updatedAt
                }
            return Cursor(value)
        }
    }

    data class CanonicalWorkOrder(
        override val by: CanonicalWorkOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<CanonicalWorkOrderBy>

    /**
     * Every canonical work, ordered by the cursor's own column.
     *
     * The default order is `(ID ASC)`, which is the only order that cannot change under a concurrent
     * create and therefore the only one that keeps a cursor stable without a tie-break.
     */
    @RequireAuth
    fun canonicalWorks(
        titleContains: String? = null,
        order: List<CanonicalWorkOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): CanonicalWorkNodeList {
        val actualSort = order.orEmpty().ifEmpty { listOf(CanonicalWorkOrder(CanonicalWorkOrderBy.ID, SortOrder.ASC)) }

        val queryResults =
            transaction {
                val res = CanonicalWorkTable.selectAll()
                titleContains?.takeIf { it.isNotBlank() }?.let { needle ->
                    res.andWhere { CanonicalWorkTable.title.lowerCase() like "%${needle.lowercase()}%" }
                }

                val (total, firstResult, lastResult) = res.applySortAndGetPaginationInfo(actualSort, before, last, CanonicalWorkTable.id)

                res.applyBeforeAfter(
                    before = before,
                    after = after,
                    orderBy = actualSort.first().by,
                    orderByType = actualSort.first().byType,
                )

                if (first != null) {
                    res.limit(first).offset(offset?.toLong() ?: 0)
                } else if (last != null) {
                    res.limit(last)
                }

                QueryResults(total, firstResult, lastResult, res.toList())
            }

        val getAsCursor: (CanonicalWorkType) -> Cursor = actualSort.first().by::asCursor
        val resultsAsType = queryResults.results.map { CanonicalWorkType(CanonicalWorkTable.toDataClass(it)) }

        return CanonicalWorkNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let { CanonicalWorkNodeList.CanonicalWorkEdge(getAsCursor(it), it) },
                    resultsAsType.lastOrNull()?.let { CanonicalWorkNodeList.CanonicalWorkEdge(getAsCursor(it), it) },
                )
            },
            pageInfo =
                PageInfo(
                    hasNextPage = queryResults.lastKey != resultsAsType.lastOrNull()?.id,
                    hasPreviousPage = queryResults.firstKey != resultsAsType.firstOrNull()?.id,
                    startCursor = resultsAsType.firstOrNull()?.let { getAsCursor(it) },
                    endCursor = resultsAsType.lastOrNull()?.let { getAsCursor(it) },
                ),
            totalCount = queryResults.total.toInt(),
        )
    }

    @RequireAuth
    fun canonicalWork(workKey: String): CanonicalWorkType? = CanonicalIdentity.getWorkByKey(workKey)?.let { CanonicalWorkType(it) }

    /** The work a manga is bound to, or null while it is unbound. */
    @RequireAuth
    fun canonicalWorkForManga(mangaId: Int): CanonicalWorkType? =
        CanonicalIdentity.getBindingForManga(mangaId)?.let { CanonicalIdentity.getWorkById(it.workId) }?.let { CanonicalWorkType(it) }

    @RequireAuth
    fun canonicalBindingForManga(mangaId: Int): CanonicalSourceBindingType? =
        CanonicalIdentity.getBindingForManga(mangaId)?.let { CanonicalSourceBindingType(it) }

    /**
     * The bindings of one work, ordered by priority then id.
     *
     * A work has few bindings by construction - they are sources of one series - so this is a plain
     * list rather than a cursor page, and the ordering is the same one the mutation paths reason
     * about.
     */
    @RequireAuth
    fun canonicalBindingsForWork(workId: Int): CanonicalSourceBindingNodeList =
        CanonicalSourceBindingNodeList.of(CanonicalIdentity.getBindingsForWork(workId).map { CanonicalSourceBindingType(it) })

    /**
     * How much canonical identity exists, and what it is doing.
     *
     * Explicitly reports that the duplicate policy is advisory, so a client never has to infer it from
     * documentation.
     */
    @RequireAuth
    fun canonicalIdentityStatus(): CanonicalIdentityStatusType =
        transaction {
            fun countBindings(condition: Op<Boolean>): Int =
                CanonicalSourceBindingTable
                    .selectAll()
                    .where { condition }
                    .count()
                    .toInt()

            CanonicalIdentityStatusType(
                workCount = CanonicalWorkTable.selectAll().count().toInt(),
                bindingCount = CanonicalSourceBindingTable.selectAll().count().toInt(),
                primaryBindingCount =
                    countBindings(
                        (CanonicalSourceBindingTable.primaryMarker.isNotNull()) and
                            (CanonicalSourceBindingTable.role neq CanonicalBindingRole.DISABLED.name),
                    ),
                activeBindingCount = countBindings(CanonicalSourceBindingTable.role eq CanonicalBindingRole.ACTIVE.name),
                fallbackBindingCount = countBindings(CanonicalSourceBindingTable.role eq CanonicalBindingRole.FALLBACK.name),
                disabledBindingCount = countBindings(CanonicalSourceBindingTable.role eq CanonicalBindingRole.DISABLED.name),
                detachedBindingCount = countBindings(CanonicalSourceBindingTable.manga.isNull()),
                duplicatePolicyApplied = CanonicalWorkType.DUPLICATE_POLICY_APPLIED,
            )
        }
}
