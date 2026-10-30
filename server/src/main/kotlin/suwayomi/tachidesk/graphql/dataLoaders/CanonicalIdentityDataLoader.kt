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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.types.CanonicalSourceBindingNodeList
import suwayomi.tachidesk.graphql.types.CanonicalSourceBindingType
import suwayomi.tachidesk.graphql.types.CanonicalWorkType
import suwayomi.tachidesk.manga.impl.CanonicalIdentity
import suwayomi.tachidesk.manga.model.table.CanonicalWorkTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.JavalinSetup.future

/**
 * Batches canonical works by id.
 *
 * Exists so a binding's `work` field is one query for a whole page of bindings instead of one per
 * binding, exactly like the existing manga loader.
 */
class CanonicalWorkDataLoader : KotlinDataLoader<Int, CanonicalWorkType> {
    override val dataLoaderName = "CanonicalWorkDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, CanonicalWorkType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    val works =
                        CanonicalWorkTable
                            .selectAll()
                            .where { CanonicalWorkTable.id inList ids }
                            .map { CanonicalWorkType(CanonicalWorkTable.toDataClass(it)) }
                            .associateBy { it.id }
                    ids.map { works[it] }
                }
            }
        }
}

/**
 * Batches the bindings of a work, ordered by priority then id.
 *
 * The ordering is the same one the mutation paths reason about, so a client that renders the list sees
 * the fallback order the server would use. Every work of one request is resolved by a single binding
 * query, so a page of works costs two queries rather than two per work.
 */
class CanonicalBindingsForWorkDataLoader : KotlinDataLoader<Int, CanonicalSourceBindingNodeList> {
    override val dataLoaderName = "CanonicalBindingsForWorkDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, CanonicalSourceBindingNodeList> =
        DataLoaderFactory.newDataLoader<Int, CanonicalSourceBindingNodeList> { ids ->
            future {
                val bindings = CanonicalIdentity.getBindingsForWorks(ids)
                ids.map { workId ->
                    CanonicalSourceBindingNodeList.of(
                        bindings[workId].orEmpty().map { CanonicalSourceBindingType(it) },
                    )
                }
            }
        }
}

/**
 * Batches the binding that claims a manga.
 *
 * This is what [suwayomi.tachidesk.graphql.types.MangaType.canonicalBinding] resolves through, so the
 * canonical status of a whole manga page costs one query.
 */
class CanonicalBindingForMangaDataLoader : KotlinDataLoader<Int, CanonicalSourceBindingType> {
    override val dataLoaderName = "CanonicalBindingForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, CanonicalSourceBindingType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                val bindings = CanonicalIdentity.getBindingsForManga(ids)
                ids.map { mangaId -> bindings[mangaId]?.let { CanonicalSourceBindingType(it) } }
            }
        }
}
