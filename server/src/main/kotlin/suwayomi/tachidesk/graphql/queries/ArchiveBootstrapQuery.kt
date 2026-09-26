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
import org.jetbrains.exposed.v1.core.less
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
import suwayomi.tachidesk.graphql.types.ArchiveBootstrapItemNodeList
import suwayomi.tachidesk.graphql.types.ArchiveBootstrapItemType
import suwayomi.tachidesk.graphql.types.ArchiveBootstrapProgressType
import suwayomi.tachidesk.graphql.types.ArchiveBootstrapSessionNodeList
import suwayomi.tachidesk.graphql.types.ArchiveBootstrapSessionType
import suwayomi.tachidesk.graphql.types.ArchiveBootstrapUnresolvedSourceType
import suwayomi.tachidesk.manga.impl.ArchiveBootstrap
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapItemState
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapState
import suwayomi.tachidesk.manga.model.table.ArchiveBootstrapItemTable
import suwayomi.tachidesk.manga.model.table.ArchiveBootstrapSessionTable
import suwayomi.tachidesk.manga.model.table.toDataClass

/**
 * Read access to archive bootstrap runs.
 *
 * Everything here is authenticated: a run reveals which series an operator owns, so it belongs to the
 * same trust boundary as the library APIs themselves. No backup content is ever exposed - only what
 * the run recorded about an already imported, already readable library.
 */
class ArchiveBootstrapQuery {
    // items are ordered by id only, which is exactly the order the worker claims them in, so a cursor
    // on id is stable and matches what an operator sees progressing
    enum class ArchiveBootstrapItemOrderBy(
        override val column: Column<*>,
    ) : OrderBy<ArchiveBootstrapItemType> {
        ID(ArchiveBootstrapItemTable.id),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> = ArchiveBootstrapItemTable.id greater cursor.value.toInt()

        override fun less(cursor: Cursor): Op<Boolean> = ArchiveBootstrapItemTable.id less cursor.value.toInt()

        override fun asCursor(type: ArchiveBootstrapItemType): Cursor = Cursor(type.id.toString())
    }

    data class ArchiveBootstrapItemOrder(
        override val by: ArchiveBootstrapItemOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<ArchiveBootstrapItemOrderBy>

    enum class ArchiveBootstrapSessionOrderBy(
        override val column: Column<*>,
    ) : OrderBy<ArchiveBootstrapSessionType> {
        ID(ArchiveBootstrapSessionTable.id),
        STARTED_AT(ArchiveBootstrapSessionTable.startedAt),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> {
                    ArchiveBootstrapSessionTable.id greater cursor.value.toInt()
                }

                STARTED_AT -> {
                    greaterNotUnique(
                        ArchiveBootstrapSessionTable.startedAt,
                        ArchiveBootstrapSessionTable.id,
                        cursor,
                        String::toLong,
                    )
                }
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> {
                    ArchiveBootstrapSessionTable.id less cursor.value.toInt()
                }

                STARTED_AT -> {
                    lessNotUnique(
                        ArchiveBootstrapSessionTable.startedAt,
                        ArchiveBootstrapSessionTable.id,
                        cursor,
                        String::toLong,
                    )
                }
            }

        override fun asCursor(type: ArchiveBootstrapSessionType): Cursor =
            when (this) {
                ID -> Cursor(type.id.toString())
                STARTED_AT -> Cursor(type.id.toString() + "-" + type.startedAt)
            }
    }

    data class ArchiveBootstrapSessionOrder(
        override val by: ArchiveBootstrapSessionOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<ArchiveBootstrapSessionOrderBy>

    /** All runs of this server, newest first by default. */
    @RequireAuth
    fun archiveBootstrapSessions(
        state: ArchiveBootstrapState? = null,
        order: List<ArchiveBootstrapSessionOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ArchiveBootstrapSessionNodeList {
        val actualSort =
            order.orEmpty().ifEmpty {
                listOf(ArchiveBootstrapSessionOrder(ArchiveBootstrapSessionOrderBy.ID, SortOrder.DESC))
            }
        val condition = ArchiveBootstrap.sessionCondition(state)

        val queryResults =
            transaction {
                val res = ArchiveBootstrapSessionTable.selectAll()
                if (condition != null) {
                    res.andWhere { condition }
                }

                val (total, firstResult, lastResult) =
                    res.applySortAndGetPaginationInfo(actualSort, before, last, ArchiveBootstrapSessionTable.id)

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

        val getAsCursor: (ArchiveBootstrapSessionType) -> Cursor = actualSort.first().by::asCursor
        val resultsAsType = queryResults.results.map { ArchiveBootstrapSessionType(ArchiveBootstrapSessionTable.toDataClass(it)) }

        return ArchiveBootstrapSessionNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        ArchiveBootstrapSessionNodeList.ArchiveBootstrapSessionEdge(getAsCursor(it), it)
                    },
                    resultsAsType.lastOrNull()?.let {
                        ArchiveBootstrapSessionNodeList.ArchiveBootstrapSessionEdge(getAsCursor(it), it)
                    },
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
    fun archiveBootstrapSession(id: Int): ArchiveBootstrapSessionType? =
        ArchiveBootstrap.getSession(id)?.let { ArchiveBootstrapSessionType(it) }

    /** The run that is still running or paused; there is at most one. */
    @RequireAuth
    fun archiveBootstrapActiveSession(): ArchiveBootstrapSessionType? =
        ArchiveBootstrap.getActiveSession()?.let { ArchiveBootstrapSessionType(it) }

    @RequireAuth
    fun archiveBootstrapLatestSession(): ArchiveBootstrapSessionType? =
        ArchiveBootstrap.getLatestSession()?.let { ArchiveBootstrapSessionType(it) }

    /** The series of one run, in the order the worker will visit them. */
    @RequireAuth
    fun archiveBootstrapItems(
        sessionId: Int,
        state: ArchiveBootstrapItemState? = null,
        mangaId: Int? = null,
        order: List<ArchiveBootstrapItemOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ArchiveBootstrapItemNodeList {
        val actualSort = order.orEmpty().ifEmpty { listOf(ArchiveBootstrapItemOrder(ArchiveBootstrapItemOrderBy.ID, SortOrder.ASC)) }
        val condition = ArchiveBootstrap.itemCondition(sessionId, state, mangaId)

        val queryResults =
            transaction {
                val res = ArchiveBootstrapItemTable.selectAll().andWhere { condition }

                val (total, firstResult, lastResult) =
                    res.applySortAndGetPaginationInfo(actualSort, before, last, ArchiveBootstrapItemTable.id)

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

        val getAsCursor: (ArchiveBootstrapItemType) -> Cursor = actualSort.first().by::asCursor
        val resultsAsType = queryResults.results.map { ArchiveBootstrapItemType(ArchiveBootstrapItemTable.toDataClass(it)) }

        return ArchiveBootstrapItemNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        ArchiveBootstrapItemNodeList.ArchiveBootstrapItemEdge(getAsCursor(it), it)
                    },
                    resultsAsType.lastOrNull()?.let {
                        ArchiveBootstrapItemNodeList.ArchiveBootstrapItemEdge(getAsCursor(it), it)
                    },
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
    fun archiveBootstrapProgress(sessionId: Int): ArchiveBootstrapProgressType =
        ArchiveBootstrapProgressType(ArchiveBootstrap.progress(sessionId))

    /**
     * Series a run could not process because their source is missing.
     *
     * Reported per source so a missing extension is visible as one actionable item rather than as
     * hundreds of individual series.
     */
    @RequireAuth
    fun archiveBootstrapUnresolvedSources(
        sessionId: Int,
        sampleSize: Int = 5,
    ): List<ArchiveBootstrapUnresolvedSourceType> =
        ArchiveBootstrap
            .unresolvedSources(sessionId, sampleSize)
            .map { ArchiveBootstrapUnresolvedSourceType(it) }

    /** The policy that applies to a series, given its categories. */
    @RequireAuth
    fun archiveBootstrapEffectivePolicy(
        sessionId: Int,
        mangaId: Int,
    ): ArchiveBootstrapItemType? =
        transaction {
            ArchiveBootstrapItemTable
                .selectAll()
                .where {
                    (ArchiveBootstrapItemTable.session eq sessionId) and
                        (ArchiveBootstrapItemTable.mangaId eq mangaId)
                }.firstOrNull()
                ?.let { ArchiveBootstrapItemType(ArchiveBootstrapItemTable.toDataClass(it)) }
        }
}
