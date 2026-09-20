package suwayomi.tachidesk.graphql.dataLoaders

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import graphql.GraphQLContext
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.jetbrains.exposed.v1.core.Slf4jSqlDebugLogger
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.types.ChapterRevisionType
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.server.JavalinSetup.future

class ChapterRevisionDataLoader : KotlinDataLoader<Int, ChapterRevisionType> {
    override val dataLoaderName = "ChapterRevisionDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterRevisionType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val revisions =
                        ChapterRevisionTable
                            .selectAll()
                            .where { ChapterRevisionTable.id inList ids }
                            .map { ChapterRevisionType(it) }
                            .associateBy { it.id }
                    ids.map { revisions[it] }
                }
            }
        }
}
