package suwayomi.tachidesk.graphql.queries

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
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
import suwayomi.tachidesk.graphql.types.ChapterRevisionSweepItemNodeList
import suwayomi.tachidesk.graphql.types.ChapterRevisionSweepItemType
import suwayomi.tachidesk.graphql.types.ChapterRevisionSweepProgressType
import suwayomi.tachidesk.graphql.types.ChapterRevisionSweepScheduleType
import suwayomi.tachidesk.graphql.types.ChapterRevisionSweepSessionNodeList
import suwayomi.tachidesk.graphql.types.ChapterRevisionSweepSessionType
import suwayomi.tachidesk.manga.impl.ChapterRevisionSweep
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepItemState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSweepSessionState
import suwayomi.tachidesk.manga.model.table.ChapterRevisionSweepItemTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionSweepSessionTable
import suwayomi.tachidesk.manga.model.table.toDataClass

/**
 * Read access to revision sweeps.
 *
 * Everything here is authenticated: a run reveals which series an operator owns, so it belongs to the
 * same trust boundary as the library APIs themselves.
 */
class ChapterRevisionSweepQuery {
    // chapters are ordered by id only, which is exactly the order the worker claims them in, so a
    // cursor on id is stable and matches what an operator sees progressing
    enum class ChapterRevisionSweepItemOrderBy(
        override val column: Column<*>,
    ) : OrderBy<ChapterRevisionSweepItemType> {
        ID(ChapterRevisionSweepItemTable.id),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> = ChapterRevisionSweepItemTable.id greater cursor.value.toInt()

        override fun less(cursor: Cursor): Op<Boolean> = ChapterRevisionSweepItemTable.id less cursor.value.toInt()

        override fun asCursor(type: ChapterRevisionSweepItemType): Cursor = Cursor(type.id.toString())
    }

    data class ChapterRevisionSweepItemOrder(
        override val by: ChapterRevisionSweepItemOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<ChapterRevisionSweepItemOrderBy>

    enum class ChapterRevisionSweepSessionOrderBy(
        override val column: Column<*>,
    ) : OrderBy<ChapterRevisionSweepSessionType> {
        ID(ChapterRevisionSweepSessionTable.id),
        STARTED_AT(ChapterRevisionSweepSessionTable.startedAt),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> {
                    ChapterRevisionSweepSessionTable.id greater cursor.value.toInt()
                }

                STARTED_AT -> {
                    greaterNotUnique(
                        ChapterRevisionSweepSessionTable.startedAt,
                        ChapterRevisionSweepSessionTable.id,
                        cursor,
                        String::toLong,
                    )
                }
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> {
                    ChapterRevisionSweepSessionTable.id less cursor.value.toInt()
                }

                STARTED_AT -> {
                    lessNotUnique(
                        ChapterRevisionSweepSessionTable.startedAt,
                        ChapterRevisionSweepSessionTable.id,
                        cursor,
                        String::toLong,
                    )
                }
            }

        override fun asCursor(type: ChapterRevisionSweepSessionType): Cursor =
            when (this) {
                ID -> Cursor(type.id.toString())
                STARTED_AT -> Cursor(type.id.toString() + "-" + type.startedAt)
            }
    }

    data class ChapterRevisionSweepSessionOrder(
        override val by: ChapterRevisionSweepSessionOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<ChapterRevisionSweepSessionOrderBy>

    /** All runs of this server, newest first by default. */
    @RequireAuth
    fun chapterRevisionSweepSessions(
        state: ChapterRevisionSweepSessionState? = null,
        order: List<ChapterRevisionSweepSessionOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterRevisionSweepSessionNodeList {
        val actualSort =
            order.orEmpty().ifEmpty {
                listOf(ChapterRevisionSweepSessionOrder(ChapterRevisionSweepSessionOrderBy.ID, SortOrder.DESC))
            }
        val condition = ChapterRevisionSweep.sessionCondition(state)

        val queryResults =
            transaction {
                val res = ChapterRevisionSweepSessionTable.selectAll()
                if (condition != null) {
                    res.andWhere { condition }
                }

                val (total, firstResult, lastResult) =
                    res.applySortAndGetPaginationInfo(actualSort, before, last, ChapterRevisionSweepSessionTable.id)

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

        val getAsCursor: (ChapterRevisionSweepSessionType) -> Cursor = actualSort.first().by::asCursor
        val resultsAsType =
            queryResults.results.map { ChapterRevisionSweepSessionType(ChapterRevisionSweepSessionTable.toDataClass(it)) }

        return ChapterRevisionSweepSessionNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        ChapterRevisionSweepSessionNodeList.ChapterRevisionSweepSessionEdge(getAsCursor(it), it)
                    },
                    resultsAsType.lastOrNull()?.let {
                        ChapterRevisionSweepSessionNodeList.ChapterRevisionSweepSessionEdge(getAsCursor(it), it)
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
    fun chapterRevisionSweepSession(id: Int): ChapterRevisionSweepSessionType? =
        ChapterRevisionSweep.getSession(id)?.let { ChapterRevisionSweepSessionType(it) }

    /** The run that is still running or paused; there is at most one. */
    @RequireAuth
    fun chapterRevisionSweepActiveSession(): ChapterRevisionSweepSessionType? =
        ChapterRevisionSweep.getActiveSession()?.let { ChapterRevisionSweepSessionType(it) }

    @RequireAuth
    fun chapterRevisionSweepLatestSession(): ChapterRevisionSweepSessionType? =
        ChapterRevisionSweep.getLatestSession()?.let { ChapterRevisionSweepSessionType(it) }

    /** When the next automatic sweep is due, or null while no schedule exists yet. */
    @RequireAuth
    fun chapterRevisionSweepSchedule(): ChapterRevisionSweepScheduleType? =
        ChapterRevisionSweep.getSchedule()?.let { ChapterRevisionSweepScheduleType(it) }

    /** The chapters of one run, in the order the worker will visit them. */
    @RequireAuth
    fun chapterRevisionSweepItems(
        sessionId: Int,
        state: ChapterRevisionSweepItemState? = null,
        mangaId: Int? = null,
        order: List<ChapterRevisionSweepItemOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterRevisionSweepItemNodeList {
        val actualSort =
            order.orEmpty().ifEmpty { listOf(ChapterRevisionSweepItemOrder(ChapterRevisionSweepItemOrderBy.ID, SortOrder.ASC)) }
        val condition = ChapterRevisionSweep.itemCondition(sessionId, state, mangaId)

        val queryResults =
            transaction {
                val res = ChapterRevisionSweepItemTable.selectAll().andWhere { condition }

                val (total, firstResult, lastResult) =
                    res.applySortAndGetPaginationInfo(actualSort, before, last, ChapterRevisionSweepItemTable.id)

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

        val getAsCursor: (ChapterRevisionSweepItemType) -> Cursor = actualSort.first().by::asCursor
        val resultsAsType = queryResults.results.map { ChapterRevisionSweepItemType(ChapterRevisionSweepItemTable.toDataClass(it)) }

        return ChapterRevisionSweepItemNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        ChapterRevisionSweepItemNodeList.ChapterRevisionSweepItemEdge(getAsCursor(it), it)
                    },
                    resultsAsType.lastOrNull()?.let {
                        ChapterRevisionSweepItemNodeList.ChapterRevisionSweepItemEdge(getAsCursor(it), it)
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
    fun chapterRevisionSweepProgress(sessionId: Int): ChapterRevisionSweepProgressType =
        ChapterRevisionSweepProgressType(ChapterRevisionSweep.progress(sessionId))
}
