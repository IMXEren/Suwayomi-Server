package suwayomi.tachidesk.graphql.server.primitives

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Query

/** Existing archive connections always return total count and page bounds. */
fun <T : OrderBy<*>, Id : Any> Query.applySortAndGetPaginationInfo(
    sort: List<Order<T>>,
    before: Cursor?,
    last: Int?,
    idColumn: Column<EntityID<Id>>,
): PaginationInfo<Id> = applySortAndGetPaginationInfo(sort, before, last, idColumn, PaginationNeeds(total = true, bounds = true))
