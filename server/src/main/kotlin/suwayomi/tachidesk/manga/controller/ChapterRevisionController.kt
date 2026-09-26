package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.http.BadRequestResponse
import io.javalin.http.ConflictResponse
import io.javalin.http.Context
import io.javalin.http.HandlerType
import io.javalin.http.HttpStatus
import io.javalin.http.NotFoundResponse
import suwayomi.tachidesk.manga.impl.ChapterRevisionComparisonMediaResolver
import suwayomi.tachidesk.manga.impl.ChapterRevisionDeliveryResolution
import suwayomi.tachidesk.manga.impl.ChapterRevisionDeliveryResolver
import suwayomi.tachidesk.manga.impl.ChapterRevisionMediaResolution
import suwayomi.tachidesk.manga.impl.ChapterRevisionThumbnailSide
import suwayomi.tachidesk.manga.impl.RcloneArchiveLinkGenerator
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.pathParam
import suwayomi.tachidesk.server.util.withOperation
import uy.kohesive.injekt.injectLazy
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Serves the review previews of a revision comparison.
 *
 * The routes are addressed only by a revision, an aligned row ordinal and a side. What is behind them
 * is derived data - a thumbnail the comparison rendered, or a page of the staged or archived content -
 * so the addresses are stable by identity rather than by content, and every response is uncacheable.
 */
object ChapterRevisionController {
    private val applicationDirs: ApplicationDirs by injectLazy()

    private val media by lazy {
        ChapterRevisionComparisonMediaResolver(
            stagingRoot = { File(applicationDirs.archiveStagingRoot) },
            archiveRoot = { File(applicationDirs.archiveRoot) },
        )
    }

    /**
     * Delivery is configured per request rather than per process, so enabling it or pointing it at
     * another remote takes effect on the next download instead of after a restart.
     */
    private val delivery by lazy {
        ChapterRevisionDeliveryResolver(
            archiveRoot = { File(applicationDirs.archiveRoot) },
            directDeliveryEnabled = { serverConfig.archiveDirectDeliveryEnabled.value },
            fallbackToLocal = { serverConfig.archiveDirectDeliveryFallbackToLocal.value },
            linker =
                RcloneArchiveLinkGenerator(
                    executable = { serverConfig.archiveRcloneExecutable.value },
                    remoteRoot = { serverConfig.archiveRcloneRemote.value },
                    expirySeconds = { serverConfig.archiveDirectDeliveryExpirySeconds.value.toLong() },
                    timeout = { serverConfig.archiveVerificationTimeoutSeconds.value.seconds },
                    requireExpiryEvidence = { serverConfig.archiveDirectDeliveryRequireExpiryEvidence.value },
                ),
        )
    }

    val comparisonThumbnail =
        handler(
            pathParam<String>("revisionId"),
            pathParam<String>("ordinal"),
            pathParam<String>("side"),
            documentWith = {
                withOperation {
                    summary("Get a revision comparison thumbnail")
                    description(
                        "Get the rendered review thumbnail of one side of one aligned page of a revision comparison.",
                    )
                }
            },
            behaviorOf = { ctx, revisionId, ordinal, side ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val address = comparisonMediaAddress(revisionId, ordinal, side)
                serve(ctx, address) { media.thumbnail(it.revisionId, it.ordinal, it.side) }
            },
            withResults = {
                image(HttpStatus.OK)
                httpCode(HttpStatus.BAD_REQUEST)
                httpCode(HttpStatus.NOT_FOUND)
                httpCode(HttpStatus.CONFLICT)
            },
        )

    val comparisonPage =
        handler(
            pathParam<String>("revisionId"),
            pathParam<String>("ordinal"),
            pathParam<String>("side"),
            documentWith = {
                withOperation {
                    summary("Get a revision comparison page")
                    description(
                        "Get the full page of one side of one aligned page of a revision comparison, from the " +
                            "candidate's staged or archived content and from the baseline's archived content.",
                    )
                }
            },
            behaviorOf = { ctx, revisionId, ordinal, side ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val address = comparisonMediaAddress(revisionId, ordinal, side)
                serve(ctx, address) { media.page(it.revisionId, it.ordinal, it.side) }
            },
            withResults = {
                image(HttpStatus.OK)
                httpCode(HttpStatus.BAD_REQUEST)
                httpCode(HttpStatus.NOT_FOUND)
                httpCode(HttpStatus.CONFLICT)
            },
        )

    val download =
        handler(
            pathParam<String>("revisionId"),
            documentWith = {
                withOperation {
                    summary("Download an archived chapter revision")
                    description(
                        "Download the immutable archived CBZ of one accepted revision. The response is either a " +
                            "short-lived redirect to a location on the configured remote storage, or the archived " +
                            "bytes themselves; a HEAD request reports the same metadata without a body, and a byte " +
                            "range is answered with exactly that run of bytes.",
                    )
                }
            },
            behaviorOf = { ctx, revisionId ->
                // authentication comes before the address is parsed and before anything is resolved: an
                // address that would be refused is still refused as unauthorized for a caller who may not
                // ask for this at all, and nothing about a revision is read until that answer is known
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val id = revisionId.toIntOrNull()?.takeIf { it > 0 } ?: throw NotFoundResponse()
                val head = ctx.method() == HandlerType.HEAD

                // the bytes are already an archive, so re-encoding the response would both waste work
                // and contradict the range and length this contract announces
                ctx.disableCompression()
                ctx.future {
                    future { delivery.resolve(id, directRequested = !head) }
                        .thenAccept { resolution -> respond(ctx, id, resolution, head) }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.PARTIAL_CONTENT)
                httpCode(HttpStatus.TEMPORARY_REDIRECT)
                httpCode(HttpStatus.NOT_FOUND)
                httpCode(HttpStatus.METHOD_NOT_ALLOWED)
                httpCode(HttpStatus.CONFLICT)
                httpCode(HttpStatus.RANGE_NOT_SATISFIABLE)
                httpCode(HttpStatus.SERVICE_UNAVAILABLE)
            },
        )

    /**
     * Answers one resolved request.
     *
     * The outcomes are written as statuses rather than raised as exceptions: a resolution completes on
     * another thread than the handler, so an exception raised there would end as a failed future instead
     * of the response this contract describes. No outcome carries a stored location, a path or a reason.
     */
    internal fun respond(
        ctx: Context,
        revisionId: Int,
        resolution: ChapterRevisionDeliveryResolution,
        head: Boolean,
    ) {
        when (resolution) {
            is ChapterRevisionDeliveryResolution.Direct -> {
                // 307 keeps the method, so a client that asked with GET still asks with GET; the location
                // is a bearer credential for the payload, so the response may never be stored or handed on
                ctx.header("cache-control", "private, no-store")
                ctx.header("referrer-policy", "no-referrer")
                ctx.redirect(resolution.location, HttpStatus.TEMPORARY_REDIRECT)
            }

            is ChapterRevisionDeliveryResolution.Local -> {
                serveLocal(ctx, revisionId, resolution, head)
            }

            ChapterRevisionDeliveryResolution.NotFound -> {
                ctx.status(HttpStatus.NOT_FOUND)
            }

            ChapterRevisionDeliveryResolution.Unusable -> {
                ctx.status(HttpStatus.CONFLICT)
            }

            ChapterRevisionDeliveryResolution.Unavailable -> {
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE)
            }

            ChapterRevisionDeliveryResolution.MethodNotAllowed -> {
                // a generated location is a credential for a GET, so this resource has no HEAD at all
                ctx.header("allow", "GET")
                ctx.status(HttpStatus.METHOD_NOT_ALLOWED)
            }
        }
    }

    /**
     * Sends the verified mounted copy, or exactly the byte run that was asked for.
     *
     * The response describes the artifact as a whole - what it is, how long it is, and that it accepts
     * ranges - because a client that knows nothing about this server can still resume an interrupted
     * fetch. The name in the disposition is built from the revision id alone, so neither a stored column
     * nor a client's request can reach a response header, and the type is the configured CBZ type so the
     * artifact is delivered as the archive format it is rather than as sniffed bytes.
     */
    internal fun serveLocal(
        ctx: Context,
        revisionId: Int,
        local: ChapterRevisionDeliveryResolution.Local,
        head: Boolean,
    ) {
        ctx.header("cache-control", "private, no-store")
        ctx.header("x-content-type-options", "nosniff")
        ctx.header("content-type", serverConfig.opdsCbzMimetype.value.mediaType)
        ctx.header("content-disposition", "attachment; filename=\"revision-$revisionId.cbz\"")
        ctx.header("accept-ranges", "bytes")

        when (val range = parseCbzRange(ctx.header("range"), local.size)) {
            CbzRangeRequest.Unsatisfiable -> {
                // 416 still reports the real length, so a client can retry against the true size
                ctx.header("content-range", "bytes */${local.size}")
                ctx.status(HttpStatus.RANGE_NOT_SATISFIABLE)
            }

            is CbzRangeRequest.Partial -> {
                val start = range.range.start
                val end = range.range.endInclusive
                ctx.header("content-range", "bytes $start-$end/${local.size}")
                ctx.header("content-length", range.range.length.toString())
                ctx.status(HttpStatus.PARTIAL_CONTENT)
                if (!head) ctx.result(BoundedFileStream(local.file, start, range.range.length))
            }

            CbzRangeRequest.Whole -> {
                ctx.header("content-length", local.size.toString())
                ctx.status(HttpStatus.OK)
                if (!head) ctx.result(BoundedFileStream(local.file, 0, local.size))
            }
        }
    }

    /**
     * Parses an address, or refuses it.
     *
     * A malformed address is a client error rather than a missing resource: only a well formed one can
     * name something, and telling the two apart is what keeps a typo from looking like a deletion.
     */
    private fun comparisonMediaAddress(
        revisionId: String,
        ordinal: String,
        side: String,
    ): ComparisonMediaAddress {
        val parsedRevisionId = revisionId.toIntOrNull()?.takeIf { it > 0 } ?: throw BadRequestResponse()
        val parsedOrdinal = ordinal.toIntOrNull()?.takeIf { it >= 0 } ?: throw BadRequestResponse()
        val parsedSide =
            ChapterRevisionThumbnailSide.entries.firstOrNull { it.name.equals(side, ignoreCase = true) }
                ?: throw BadRequestResponse()

        return ComparisonMediaAddress(parsedRevisionId, parsedOrdinal, parsedSide)
    }

    /**
     * Sends one resolved object, or refuses it.
     *
     * A missing object and an object that is not what was recorded are both generic: neither the
     * address nor the reason behind it is disclosed, and no stored location reaches the client.
     */
    private fun serve(
        ctx: Context,
        address: ComparisonMediaAddress,
        resolve: (ComparisonMediaAddress) -> ChapterRevisionMediaResolution,
    ) {
        when (val resolution = resolve(address)) {
            is ChapterRevisionMediaResolution.Served -> {
                // the address is stable but what it points at is replaced by a later comparison, so a
                // response may never be reused; the bytes are sent inline and never sniffed as another type
                ctx.header("cache-control", "private, no-store")
                ctx.header("content-disposition", "inline")
                ctx.header("x-content-type-options", "nosniff")
                ctx.header("content-type", resolution.media.contentType)
                ctx.result(resolution.media.stream())
            }

            ChapterRevisionMediaResolution.NotFound -> {
                throw NotFoundResponse()
            }

            ChapterRevisionMediaResolution.Corrupt -> {
                throw ConflictResponse()
            }
        }
    }

    private data class ComparisonMediaAddress(
        val revisionId: Int,
        val ordinal: Int,
        val side: ChapterRevisionThumbnailSide,
    )
}
