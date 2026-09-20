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
import suwayomi.tachidesk.graphql.types.ChapterRevisionRollbackNodeList
import suwayomi.tachidesk.graphql.types.ChapterRevisionRollbackType
import suwayomi.tachidesk.manga.model.table.ChapterRevisionRollbackTable
import suwayomi.tachidesk.manga.model.table.toDataClass

/**
 * The recorded returns to a historical revision.
 *
 * The history is append-only, so it pages by row id: the same revision can be a target more than once
 * and every decision keeps its own row. Nothing here exposes a path or a digest - a decision names
 * revisions, not content.
 */
class ChapterRevisionRollbackQuery {
    enum class ChapterRevisionRollbackOrderBy(
        override val column: Column<*>,
    ) : OrderBy<ChapterRevisionRollbackType> {
        ID(ChapterRevisionRollbackTable.id),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> = ChapterRevisionRollbackTable.id greater cursor.value.toInt()

        override fun less(cursor: Cursor): Op<Boolean> = ChapterRevisionRollbackTable.id less cursor.value.toInt()

        override fun asCursor(type: ChapterRevisionRollbackType): Cursor = Cursor(type.id.toString())
    }

    data class ChapterRevisionRollbackOrder(
        override val by: ChapterRevisionRollbackOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<ChapterRevisionRollbackOrderBy>

    /** Every decision of this server, newest first by default, optionally narrowed to one identity. */
    @RequireAuth
    fun chapterRevisionRollbacks(
        chapterKey: String? = null,
        order: List<ChapterRevisionRollbackOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterRevisionRollbackNodeList {
        val actualSort =
            order.orEmpty().ifEmpty {
                listOf(ChapterRevisionRollbackOrder(ChapterRevisionRollbackOrderBy.ID, SortOrder.DESC))
            }
        val condition = chapterKey?.let { ChapterRevisionRollbackTable.chapterKey eq it }

        val queryResults =
            transaction {
                val res = ChapterRevisionRollbackTable.selectAll()
                if (condition != null) {
                    res.andWhere { condition }
                }

                val (total, firstResult, lastResult) =
                    res.applySortAndGetPaginationInfo(actualSort, before, last, ChapterRevisionRollbackTable.id)

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

        val getAsCursor: (ChapterRevisionRollbackType) -> Cursor = actualSort.first().by::asCursor
        val resultsAsType =
            queryResults.results.map { ChapterRevisionRollbackType(ChapterRevisionRollbackTable.toDataClass(it)) }

        return ChapterRevisionRollbackNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        ChapterRevisionRollbackNodeList.ChapterRevisionRollbackEdge(getAsCursor(it), it)
                    },
                    resultsAsType.lastOrNull()?.let {
                        ChapterRevisionRollbackNodeList.ChapterRevisionRollbackEdge(getAsCursor(it), it)
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
}
