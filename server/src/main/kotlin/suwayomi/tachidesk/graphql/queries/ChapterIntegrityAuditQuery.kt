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
import suwayomi.tachidesk.graphql.types.ChapterIntegrityAuditItemNodeList
import suwayomi.tachidesk.graphql.types.ChapterIntegrityAuditItemType
import suwayomi.tachidesk.graphql.types.ChapterIntegrityAuditProgressType
import suwayomi.tachidesk.graphql.types.ChapterIntegrityAuditScheduleType
import suwayomi.tachidesk.graphql.types.ChapterIntegrityAuditSessionNodeList
import suwayomi.tachidesk.graphql.types.ChapterIntegrityAuditSessionType
import suwayomi.tachidesk.manga.impl.ChapterRevisionIntegrityAudit
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditItemState
import suwayomi.tachidesk.manga.model.dataclass.ChapterIntegrityAuditSessionState
import suwayomi.tachidesk.manga.model.table.ChapterIntegrityAuditItemTable
import suwayomi.tachidesk.manga.model.table.ChapterIntegrityAuditSessionTable
import suwayomi.tachidesk.manga.model.table.toDataClass

/**
 * Read access to archive integrity audits.
 *
 * Everything here is authenticated: a run names which series an operator owns and which of their
 * archived payloads are missing, so it belongs to the same trust boundary as the library APIs
 * themselves. Nothing here exposes a location or a digest of an artifact - a client is told what a
 * check found, not where the file is.
 */
class ChapterIntegrityAuditQuery {
    // revisions are ordered by id only, which is exactly the order the worker claims them in, so a
    // cursor on id is stable and matches what an operator sees progressing
    enum class ChapterIntegrityAuditItemOrderBy(
        override val column: Column<*>,
    ) : OrderBy<ChapterIntegrityAuditItemType> {
        ID(ChapterIntegrityAuditItemTable.id),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> = ChapterIntegrityAuditItemTable.id greater cursor.value.toInt()

        override fun less(cursor: Cursor): Op<Boolean> = ChapterIntegrityAuditItemTable.id less cursor.value.toInt()

        override fun asCursor(type: ChapterIntegrityAuditItemType): Cursor = Cursor(type.id.toString())
    }

    data class ChapterIntegrityAuditItemOrder(
        override val by: ChapterIntegrityAuditItemOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<ChapterIntegrityAuditItemOrderBy>

    enum class ChapterIntegrityAuditSessionOrderBy(
        override val column: Column<*>,
    ) : OrderBy<ChapterIntegrityAuditSessionType> {
        ID(ChapterIntegrityAuditSessionTable.id),
        STARTED_AT(ChapterIntegrityAuditSessionTable.startedAt),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> {
                    ChapterIntegrityAuditSessionTable.id greater cursor.value.toInt()
                }

                STARTED_AT -> {
                    greaterNotUnique(
                        ChapterIntegrityAuditSessionTable.startedAt,
                        ChapterIntegrityAuditSessionTable.id,
                        cursor,
                        String::toLong,
                    )
                }
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> {
                    ChapterIntegrityAuditSessionTable.id less cursor.value.toInt()
                }

                STARTED_AT -> {
                    lessNotUnique(
                        ChapterIntegrityAuditSessionTable.startedAt,
                        ChapterIntegrityAuditSessionTable.id,
                        cursor,
                        String::toLong,
                    )
                }
            }

        override fun asCursor(type: ChapterIntegrityAuditSessionType): Cursor =
            when (this) {
                ID -> Cursor(type.id.toString())
                STARTED_AT -> Cursor(type.id.toString() + "-" + type.startedAt)
            }
    }

    data class ChapterIntegrityAuditSessionOrder(
        override val by: ChapterIntegrityAuditSessionOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<ChapterIntegrityAuditSessionOrderBy>

    /** All runs of this server, newest first by default. */
    @RequireAuth
    fun chapterIntegrityAuditSessions(
        state: ChapterIntegrityAuditSessionState? = null,
        order: List<ChapterIntegrityAuditSessionOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterIntegrityAuditSessionNodeList {
        val actualSort =
            order.orEmpty().ifEmpty {
                listOf(ChapterIntegrityAuditSessionOrder(ChapterIntegrityAuditSessionOrderBy.ID, SortOrder.DESC))
            }
        val condition = ChapterRevisionIntegrityAudit.sessionCondition(state)

        val queryResults =
            transaction {
                val res = ChapterIntegrityAuditSessionTable.selectAll()
                if (condition != null) {
                    res.andWhere { condition }
                }

                val (total, firstResult, lastResult) =
                    res.applySortAndGetPaginationInfo(
                        actualSort,
                        before,
                        last,
                        ChapterIntegrityAuditSessionTable.id,
                    )

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

        val getAsCursor: (ChapterIntegrityAuditSessionType) -> Cursor = actualSort.first().by::asCursor
        val resultsAsType =
            queryResults.results.map {
                ChapterIntegrityAuditSessionType(ChapterIntegrityAuditSessionTable.toDataClass(it))
            }

        return ChapterIntegrityAuditSessionNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        ChapterIntegrityAuditSessionNodeList.ChapterIntegrityAuditSessionEdge(getAsCursor(it), it)
                    },
                    resultsAsType.lastOrNull()?.let {
                        ChapterIntegrityAuditSessionNodeList.ChapterIntegrityAuditSessionEdge(getAsCursor(it), it)
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
    fun chapterIntegrityAuditSession(id: Int): ChapterIntegrityAuditSessionType? =
        ChapterRevisionIntegrityAudit.getSession(id)?.let { ChapterIntegrityAuditSessionType(it) }

    /** The run that is still running or paused; there is at most one. */
    @RequireAuth
    fun chapterIntegrityAuditActiveSession(): ChapterIntegrityAuditSessionType? =
        ChapterRevisionIntegrityAudit.getActiveSession()?.let { ChapterIntegrityAuditSessionType(it) }

    @RequireAuth
    fun chapterIntegrityAuditLatestSession(): ChapterIntegrityAuditSessionType? =
        ChapterRevisionIntegrityAudit.getLatestSession()?.let { ChapterIntegrityAuditSessionType(it) }

    /** When the next automatic audit is due, or null while no schedule exists yet. */
    @RequireAuth
    fun chapterIntegrityAuditSchedule(): ChapterIntegrityAuditScheduleType? =
        ChapterRevisionIntegrityAudit.getSchedule()?.let { ChapterIntegrityAuditScheduleType(it) }

    /** The revisions of one run, in the order the worker will visit them. */
    @RequireAuth
    fun chapterIntegrityAuditItems(
        sessionId: Int,
        state: ChapterIntegrityAuditItemState? = null,
        mangaId: Int? = null,
        order: List<ChapterIntegrityAuditItemOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterIntegrityAuditItemNodeList {
        val actualSort =
            order.orEmpty().ifEmpty {
                listOf(ChapterIntegrityAuditItemOrder(ChapterIntegrityAuditItemOrderBy.ID, SortOrder.ASC))
            }
        val condition = ChapterRevisionIntegrityAudit.itemCondition(sessionId, state, mangaId)

        val queryResults =
            transaction {
                val res = ChapterIntegrityAuditItemTable.selectAll().andWhere { condition }

                val (total, firstResult, lastResult) =
                    res.applySortAndGetPaginationInfo(actualSort, before, last, ChapterIntegrityAuditItemTable.id)

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

        val getAsCursor: (ChapterIntegrityAuditItemType) -> Cursor = actualSort.first().by::asCursor
        val resultsAsType =
            queryResults.results.map { ChapterIntegrityAuditItemType(ChapterIntegrityAuditItemTable.toDataClass(it)) }

        return ChapterIntegrityAuditItemNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        ChapterIntegrityAuditItemNodeList.ChapterIntegrityAuditItemEdge(getAsCursor(it), it)
                    },
                    resultsAsType.lastOrNull()?.let {
                        ChapterIntegrityAuditItemNodeList.ChapterIntegrityAuditItemEdge(getAsCursor(it), it)
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
    fun chapterIntegrityAuditProgress(sessionId: Int): ChapterIntegrityAuditProgressType =
        ChapterIntegrityAuditProgressType(ChapterRevisionIntegrityAudit.progress(sessionId))
}
