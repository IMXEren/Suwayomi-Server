package suwayomi.tachidesk.graphql.queries

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import graphql.schema.DataFetchingEnvironment
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
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
import suwayomi.tachidesk.graphql.types.ChapterRevisionComparisonPageNodeList
import suwayomi.tachidesk.graphql.types.ChapterRevisionComparisonPageType
import suwayomi.tachidesk.graphql.types.ChapterRevisionComparisonType
import suwayomi.tachidesk.graphql.types.ChapterRevisionNodeList
import suwayomi.tachidesk.graphql.types.ChapterRevisionType
import suwayomi.tachidesk.graphql.types.ChapterRevisionVisualAnalysisStatus
import suwayomi.tachidesk.manga.impl.ChapterRevision
import suwayomi.tachidesk.manga.impl.ChapterRevisionComparisonStore
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRetentionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDiscoveryReason
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSignalConfidence
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisState
import suwayomi.tachidesk.manga.model.table.ChapterRevisionComparisonPageTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import java.util.concurrent.CompletableFuture

class ChapterRevisionQuery {
    @RequireAuth
    fun chapterRevision(
        dataFetchingEnvironment: DataFetchingEnvironment,
        id: Int,
    ): CompletableFuture<ChapterRevisionType?> = dataFetchingEnvironment.getValueFromDataLoader("ChapterRevisionDataLoader", id)

    // approvedAt is deliberately not an order option: it is nullable audit metadata, so it cannot
    // back a cursor without a null region and an incorrect id tie-break. The queued backlog orders
    // by the non-null updatedAt (which approval sets equal to approvedAt) instead.
    enum class ChapterRevisionOrderBy(
        override val column: Column<*>,
    ) : OrderBy<ChapterRevisionType> {
        ID(ChapterRevisionTable.id),
        DISCOVERED_AT(ChapterRevisionTable.discoveredAt),
        UPDATED_AT(ChapterRevisionTable.updatedAt),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> ChapterRevisionTable.id greater cursor.value.toInt()
                DISCOVERED_AT -> greaterNotUnique(ChapterRevisionTable.discoveredAt, ChapterRevisionTable.id, cursor, String::toLong)
                UPDATED_AT -> greaterNotUnique(ChapterRevisionTable.updatedAt, ChapterRevisionTable.id, cursor, String::toLong)
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> ChapterRevisionTable.id less cursor.value.toInt()
                DISCOVERED_AT -> lessNotUnique(ChapterRevisionTable.discoveredAt, ChapterRevisionTable.id, cursor, String::toLong)
                UPDATED_AT -> lessNotUnique(ChapterRevisionTable.updatedAt, ChapterRevisionTable.id, cursor, String::toLong)
            }

        override fun asCursor(type: ChapterRevisionType): Cursor {
            val value =
                when (this) {
                    ID -> type.id.toString()
                    DISCOVERED_AT -> type.id.toString() + "-" + type.discoveredAt
                    UPDATED_AT -> type.id.toString() + "-" + type.updatedAt
                }
            return Cursor(value)
        }
    }

    data class ChapterRevisionOrder(
        override val by: ChapterRevisionOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<ChapterRevisionOrderBy>

    /**
     * The revision listing, filtered by any combination of its independent dimensions.
     *
     * `acquisitionState`, `archiveState`, `publicationState` and `retentionState` are separate
     * arguments exactly because the table models them as separate dimensions: filtering by an
     * archive state while a publication is still pending is a legitimate query, and combining them
     * would make the archive state of a still-downloading candidate unexpressible. They are
     * AND-ed together with every other argument.
     */
    @RequireAuth
    fun chapterRevisions(
        chapterId: Int? = null,
        chapterKey: String? = null,
        disposition: ChapterRevisionDisposition? = null,
        acquisitionState: ChapterAcquisitionState? = null,
        archiveState: ChapterArchiveState? = null,
        publicationState: ChapterPublicationState? = null,
        retentionState: ChapterRetentionState? = null,
        discoveryReason: ChapterRevisionDiscoveryReason? = null,
        signalConfidence: ChapterRevisionSignalConfidence? = null,
        order: List<ChapterRevisionOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterRevisionNodeList =
        queryRevisions(
            condition =
                buildCondition(
                    chapterId = chapterId,
                    chapterKey = chapterKey,
                    disposition = disposition,
                    acquisitionState = acquisitionState,
                    archiveState = archiveState,
                    publicationState = publicationState,
                    retentionState = retentionState,
                    discoveryReason = discoveryReason,
                    signalConfidence = signalConfidence,
                ),
            order = order,
            before = before,
            after = after,
            first = first,
            last = last,
            offset = offset,
            defaultOrder = listOf(ChapterRevisionOrder(ChapterRevisionOrderBy.ID, SortOrder.ASC)),
        )

    /** Candidates waiting for explicit approval. */
    @RequireAuth
    fun approvalBacklog(
        order: List<ChapterRevisionOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterRevisionNodeList =
        queryRevisions(
            condition =
                buildCondition(
                    disposition = ChapterRevisionDisposition.CANDIDATE,
                    acquisitionState = ChapterAcquisitionState.PENDING_APPROVAL,
                ),
            order = order,
            before = before,
            after = after,
            first = first,
            last = last,
            offset = offset,
            defaultOrder =
                listOf(
                    ChapterRevisionOrder(ChapterRevisionOrderBy.DISCOVERED_AT, SortOrder.ASC),
                    ChapterRevisionOrder(ChapterRevisionOrderBy.ID, SortOrder.ASC),
                ),
        )

    /** Approved candidates waiting to be acquired, ordered by queue entry then id. */
    @RequireAuth
    fun queuedBacklog(
        order: List<ChapterRevisionOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterRevisionNodeList =
        queryRevisions(
            condition =
                buildCondition(
                    disposition = ChapterRevisionDisposition.CANDIDATE,
                    acquisitionState = ChapterAcquisitionState.QUEUED,
                ),
            order = order,
            before = before,
            after = after,
            first = first,
            last = last,
            offset = offset,
            defaultOrder =
                listOf(
                    ChapterRevisionOrder(ChapterRevisionOrderBy.UPDATED_AT, SortOrder.ASC),
                    ChapterRevisionOrder(ChapterRevisionOrderBy.ID, SortOrder.ASC),
                ),
        )

    /** The active accepted revision of a chapter identity, or null when it has none. */
    @RequireAuth
    fun activeChapterRevision(chapterKey: String): ChapterRevisionType? =
        ChapterRevision.getActiveRevision(chapterKey)?.let { ChapterRevisionType(it) }

    /**
     * The revision history of one chapter identity, newest discovery first.
     *
     * The history is unbounded by construction - a long-lived series can accumulate many revisions -
     * so it uses the same cursor pagination machinery as the other revision listings. The default
     * order is the non-null `(DISCOVERED_AT DESC, ID DESC)`, which is what makes the cursor stable.
     */
    @RequireAuth
    fun chapterRevisionHistory(
        chapterKey: String,
        order: List<ChapterRevisionOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterRevisionNodeList =
        queryRevisions(
            condition = buildCondition(chapterKey = chapterKey),
            order = order,
            before = before,
            after = after,
            first = first,
            last = last,
            offset = offset,
            defaultOrder =
                listOf(
                    ChapterRevisionOrder(ChapterRevisionOrderBy.DISCOVERED_AT, SortOrder.DESC),
                    ChapterRevisionOrder(ChapterRevisionOrderBy.ID, SortOrder.DESC),
                ),
        )

    /** Active revisions whose publication has failed and can be retried. */
    @RequireAuth
    fun publicationBacklog(
        order: List<ChapterRevisionOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterRevisionNodeList =
        queryRevisions(
            condition =
                (ChapterRevisionTable.activeChapterKey.isNotNull()) and
                    (ChapterRevisionTable.publicationState eq ChapterPublicationState.PUBLICATION_FAILED.name),
            order = order,
            before = before,
            after = after,
            first = first,
            last = last,
            offset = offset,
            defaultOrder = listOf(ChapterRevisionOrder(ChapterRevisionOrderBy.ID, SortOrder.ASC)),
        )

    /**
     * Revisions whose pruning failed and can be retried.
     *
     * A revision that is already PRUNED is deliberately absent: its payload is gone for good, so only
     * re-acquiring the revision can bring it back.
     */
    @RequireAuth
    fun pruningBacklog(
        order: List<ChapterRevisionOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterRevisionNodeList =
        queryRevisions(
            condition = ChapterRevisionTable.retentionState eq ChapterRetentionState.PRUNE_FAILED.name,
            order = order,
            before = before,
            after = after,
            first = first,
            last = last,
            offset = offset,
            defaultOrder = listOf(ChapterRevisionOrder(ChapterRevisionOrderBy.ID, SortOrder.ASC)),
        )

    /**
     * Candidates whose visual page comparison is still owed or running.
     *
     * A separate listing rather than a filter argument, because this is the operator's "what is the
     * analysis doing" view: it is exactly the set the worker will pick up next.
     */
    @RequireAuth
    fun visualAnalysisBacklog(
        order: List<ChapterRevisionOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterRevisionNodeList =
        queryRevisions(
            condition =
                ChapterRevisionTable.visualAnalysisState inList
                    listOf(ChapterVisualAnalysisState.QUEUED.name, ChapterVisualAnalysisState.ANALYZING.name),
            order = order,
            before = before,
            after = after,
            first = first,
            last = last,
            offset = offset,
            defaultOrder = listOf(ChapterRevisionOrder(ChapterRevisionOrderBy.ID, SortOrder.ASC)),
        )

    /** How much visual-analysis work is outstanding, and how the finished ones turned out. */
    @RequireAuth
    fun chapterRevisionVisualAnalysisStatus(): ChapterRevisionVisualAnalysisStatus =
        transaction {
            fun countOf(state: ChapterVisualAnalysisState): Int =
                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.visualAnalysisState eq state.name }
                    .count()
                    .toInt()

            ChapterRevisionVisualAnalysisStatus(
                queued = countOf(ChapterVisualAnalysisState.QUEUED),
                analyzing = countOf(ChapterVisualAnalysisState.ANALYZING),
                complete = countOf(ChapterVisualAnalysisState.COMPLETE),
                completeWithLimitations = countOf(ChapterVisualAnalysisState.COMPLETE_WITH_LIMITATIONS),
                failed = countOf(ChapterVisualAnalysisState.FAILED),
                notRequired = countOf(ChapterVisualAnalysisState.NOT_REQUIRED),
            )
        }

    /** The stored page-by-page comparison of one revision, or null when none was produced. */
    @RequireAuth
    fun chapterRevisionComparison(revisionId: Int): ChapterRevisionComparisonType? =
        ChapterRevisionComparisonStore.getComparison(revisionId)?.let { ChapterRevisionComparisonType(it) }

    /**
     * The ordered alignment behind a comparison.
     *
     * `ordinal` is unique within a comparison and is the page order, so the cursor is simply the last
     * ordinal that was returned. That keeps paging stable without a nullable timestamp to break ties
     * on, which is what a cursor needs to stay correct.
     */
    @RequireAuth
    fun chapterRevisionComparisonPages(
        revisionId: Int,
        after: Cursor? = null,
        first: Int? = null,
    ): ChapterRevisionComparisonPageNodeList {
        val limit = (first ?: DEFAULT_COMPARISON_PAGE_SIZE).coerceIn(1, MAX_COMPARISON_PAGE_SIZE)

        val rows =
            transaction {
                val query =
                    ChapterRevisionComparisonPageTable
                        .selectAll()
                        .where { ChapterRevisionComparisonPageTable.revision eq revisionId }
                if (after != null) {
                    query.andWhere { ChapterRevisionComparisonPageTable.ordinal greater after.value.toInt() }
                }
                query
                    .orderBy(ChapterRevisionComparisonPageTable.ordinal to SortOrder.ASC)
                    .limit(limit + 1)
                    .toList()
            }

        val hasNextPage = rows.size > limit

        // the summary is what says whether the baseline this alignment was decided against still exists,
        // so it is read once for the whole page rather than looked up per row
        val baselineAvailable = ChapterRevisionComparisonStore.getComparison(revisionId)?.baselineRevisionId != null

        val page =
            rows.take(limit).map { row ->
                ChapterRevisionComparisonPageType(ChapterRevisionComparisonPageTable.toDataClass(row), baselineAvailable)
            }

        return ChapterRevisionComparisonPageNodeList(
            nodes = page,
            edges =
                page.map {
                    ChapterRevisionComparisonPageNodeList.ChapterRevisionComparisonPageEdge(
                        Cursor(it.ordinal.toString()),
                        it,
                    )
                },
            pageInfo =
                PageInfo(
                    hasNextPage = hasNextPage,
                    hasPreviousPage = after != null,
                    startCursor = page.firstOrNull()?.let { Cursor(it.ordinal.toString()) },
                    endCursor = page.lastOrNull()?.let { Cursor(it.ordinal.toString()) },
                ),
            // the whole alignment, not just the page that was returned: a client needs to know how many
            // rows exist to show progress through them
            totalCount = ChapterRevisionComparisonStore.countPages(revisionId),
        )
    }

    private fun queryRevisions(
        condition: Op<Boolean>?,
        order: List<ChapterRevisionOrder>?,
        before: Cursor?,
        after: Cursor?,
        first: Int?,
        last: Int?,
        offset: Int?,
        defaultOrder: List<ChapterRevisionOrder>,
    ): ChapterRevisionNodeList {
        val actualSort = order.orEmpty().ifEmpty { defaultOrder }

        val queryResults =
            transaction {
                val res = ChapterRevisionTable.selectAll()
                if (condition != null) {
                    res.andWhere { condition }
                }

                val (total, firstResult, lastResult) = res.applySortAndGetPaginationInfo(actualSort, before, last, ChapterRevisionTable.id)

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

        val getAsCursor: (ChapterRevisionType) -> Cursor = actualSort.first().by::asCursor
        val resultsAsType = queryResults.results.map { ChapterRevisionType(it) }

        return ChapterRevisionNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        ChapterRevisionNodeList.ChapterRevisionEdge(getAsCursor(it), it)
                    },
                    resultsAsType.lastOrNull()?.let {
                        ChapterRevisionNodeList.ChapterRevisionEdge(getAsCursor(it), it)
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

    private fun buildCondition(
        chapterId: Int? = null,
        chapterKey: String? = null,
        disposition: ChapterRevisionDisposition? = null,
        acquisitionState: ChapterAcquisitionState? = null,
        archiveState: ChapterArchiveState? = null,
        publicationState: ChapterPublicationState? = null,
        retentionState: ChapterRetentionState? = null,
        discoveryReason: ChapterRevisionDiscoveryReason? = null,
        signalConfidence: ChapterRevisionSignalConfidence? = null,
    ): Op<Boolean>? =
        listOfNotNull(
            chapterId?.let { ChapterRevisionTable.chapter eq it },
            chapterKey?.let { ChapterRevisionTable.chapterKey eq it },
            disposition?.let { ChapterRevisionTable.disposition eq it.name },
            acquisitionState?.let { ChapterRevisionTable.acquisitionState eq it.name },
            archiveState?.let { ChapterRevisionTable.archiveState eq it.name },
            publicationState?.let { ChapterRevisionTable.publicationState eq it.name },
            retentionState?.let { ChapterRevisionTable.retentionState eq it.name },
            discoveryReason?.let { ChapterRevisionTable.discoveryReason eq it.name },
            signalConfidence?.let { ChapterRevisionTable.signalConfidence eq it.name },
        ).reduceOrNull { acc, op -> acc and op }
}

/** How many aligned rows a comparison listing returns when the caller does not say. */
private const val DEFAULT_COMPARISON_PAGE_SIZE = 100

/** Upper bound on one page of an alignment, so a single query cannot read a whole chapter by accident. */
private const val MAX_COMPARISON_PAGE_SIZE = 1_000
