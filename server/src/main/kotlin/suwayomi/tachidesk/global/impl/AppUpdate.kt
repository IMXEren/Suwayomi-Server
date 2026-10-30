package suwayomi.tachidesk.global.impl

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import suwayomi.tachidesk.manga.impl.util.network.await
import uy.kohesive.injekt.injectLazy

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

data class UpdateDataClass(
    /** [channel] mirrors [suwayomi.tachidesk.server.BuildConfig.BUILD_TYPE] */
    val channel: String,
    val tag: String,
    val url: String,
)

object AppUpdate {
    // This fork publishes both channels from its own repository. The stable channel is the newest
    // non-prerelease release, which is what GitHub's /releases/latest returns; the preview channel
    // is the newest release of any kind, so it has to read the list endpoint because /releases/latest
    // skips prereleases.
    private const val LATEST_STABLE_CHANNEL_URL =
        "https://api.github.com/repos/IMXEren/Suwayomi-Server/releases/latest"
    private const val LATEST_PREVIEW_CHANNEL_URL =
        "https://api.github.com/repos/IMXEren/Suwayomi-Server/releases?per_page=1"

    private val logger = KotlinLogging.logger {}
    private val json: Json by injectLazy()
    private val network: NetworkHelper by injectLazy()

    suspend fun checkUpdate(): List<UpdateDataClass> =
        listOf(
            "Stable" to LATEST_STABLE_CHANNEL_URL,
            "Preview" to LATEST_PREVIEW_CHANNEL_URL,
        ).mapNotNull { (channel, url) -> fetchRelease(channel, url) }

    /**
     * A channel without a release yet is expected (the fork has no stable release until one is cut),
     * so a missing or unreadable release is skipped instead of failing the whole lookup.
     */
    private suspend fun fetchRelease(
        channel: String,
        url: String,
    ): UpdateDataClass? =
        try {
            val payload =
                json.parseToJsonElement(
                    network.client
                        .newCall(GET(url))
                        .await()
                        .body
                        .string(),
                )
            val release = if (payload is JsonArray) payload.firstOrNull()?.jsonObject else payload.jsonObject
            val tag = release?.get("tag_name")?.jsonPrimitive?.content
            val htmlUrl = release?.get("html_url")?.jsonPrimitive?.content

            if (tag == null || htmlUrl == null) null else UpdateDataClass(channel, tag, htmlUrl)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to look up the $channel release" }
            null
        }
}
