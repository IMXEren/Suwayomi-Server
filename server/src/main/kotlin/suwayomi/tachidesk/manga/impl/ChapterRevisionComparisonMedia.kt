package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import suwayomi.tachidesk.manga.impl.util.storage.ImageUtil
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * One servable object of a comparison: what it is, exactly how long it is when that is known, and its bytes.
 *
 * [contentLength] is the exact number of bytes the stream will produce, and is null when that number is
 * not known. A stored file is measured before it is served, so a thumbnail and a staged page are exact;
 * an archive entry only declares a size, which is why a page read out of a CBZ reports no length at all
 * rather than a number the archive itself could have invented.
 */
class ChapterRevisionComparisonMedia internal constructor(
    val contentType: String,
    val contentLength: Long?,
    private val stream: InputStream,
) : Closeable {
    /** The bytes to send. The caller owns the stream and must close it. */
    fun stream(): InputStream = stream

    override fun close() = stream.close()
}

/**
 * The outcome of addressing one object of a comparison.
 *
 * Failure is a value rather than an exception on purpose: the difference between "there is nothing at
 * that address" and "what is stored there is not what was recorded" is all a client is told, and
 * neither carries a reason, a path or a message that could describe the server's filesystem.
 */
sealed interface ChapterRevisionMediaResolution {
    data class Served(
        val media: ChapterRevisionComparisonMedia,
    ) : ChapterRevisionMediaResolution

    /** nothing is stored at that address, or it may not be addressed at all */
    data object NotFound : ChapterRevisionMediaResolution

    /** something is stored, but it is not the artifact the database recorded */
    data object Corrupt : ChapterRevisionMediaResolution
}

/**
 * The API addresses of a comparison preview.
 *
 * The controller that serves them and the GraphQL layer that hands them out both build them here, so a
 * URL a client is given is always a URL that is actually routed. Nothing else about an object is part
 * of its address: not a file name, not a candidate key, not a digest.
 *
 * Every address is an API-absolute path - it begins with the `/api/v1` prefix and names a route on this
 * server - and never a full URL, because only the client knows the host and scheme it reached the server
 * through.
 */
object ChapterRevisionComparisonMediaRoutes {
    private const val BASE = "/api/v1/archive/revisions"

    fun thumbnailUrl(
        revisionId: Int,
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
    ): String = "$BASE/$revisionId/comparison/$ordinal/${side.name.lowercase()}/thumbnail"

    fun pageUrl(
        revisionId: Int,
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
    ): String = "$BASE/$revisionId/comparison/$ordinal/${side.name.lowercase()}/page"
}

/**
 * Serves the previews of one aligned row: the thumbnails the comparison rendered, and the full pages
 * either side of it.
 *
 * Nothing a client sends is ever used as a location. A request names a revision, an ordinal and a
 * side; the comparison the database holds for that revision is what decides which stored location may
 * be read, and that location is then required to be one this server could have written - the fixed
 * shape the thumbnail writer produces, rebuilt from its parsed parts and compared with the stored
 * value. The result is that a stored path can only ever address a file of this comparison, however the
 * column was filled in.
 *
 * A thumbnail is re-hashed before it is served. A page is not: hashing a whole chapter on every request
 * would cost more than reading it, so the archived artifact is checked against the size recorded for it
 * and the page is addressed by the index the alignment recorded, which is the only index that was ever
 * derived from those exact bytes.
 */
class ChapterRevisionComparisonMediaResolver(
    private val stagingRoot: () -> File,
    private val archiveRoot: () -> File,
) {
    private val logger = KotlinLogging.logger {}

    fun thumbnail(
        revisionId: Int,
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
    ): ChapterRevisionMediaResolution {
        // the comparison has to exist and be this revision's own, so a row of another revision can never
        // be addressed through this one
        if (ChapterRevisionComparisonStore.getComparison(revisionId) == null) {
            return missing(revisionId, ordinal, side)
        }
        // whichever side a thumbnail shows, it was rendered inside the analysis attempt of the revision
        // the comparison describes - both sides of a row are rendered from that one attempt - so that is
        // the revision its location is keyed by, and not the revision the other side of the row belongs to
        val revision = ChapterRevision.getRevision(revisionId) ?: return missing(revisionId, ordinal, side)
        val row = ChapterRevisionComparisonStore.getPage(revisionId, ordinal) ?: return missing(revisionId, ordinal, side)

        val stored =
            when (side) {
                ChapterRevisionThumbnailSide.BASELINE -> {
                    StoredThumbnail(row.baselineThumbnailRelativePath, row.baselineThumbnailSha256, row.baselineThumbnailSize)
                }

                ChapterRevisionThumbnailSide.CANDIDATE -> {
                    StoredThumbnail(row.candidateThumbnailRelativePath, row.candidateThumbnailSha256, row.candidateThumbnailSize)
                }
            }

        val relativePath = stored.relativePath ?: return missing(revisionId, ordinal, side)
        val sha256 = stored.sha256
        val size = stored.size
        if (sha256 == null || size == null) {
            // a row that records a location without the digest and size it was written with cannot be
            // verified, and an unverifiable preview is not served
            return corrupt(revisionId, ordinal, side, null)
        }

        val expected = expectedThumbnailPath(relativePath, revision, ordinal, side) ?: return missing(revisionId, ordinal, side)
        val file = containedFile(stagingRoot(), expected) ?: return missing(revisionId, ordinal, side)
        if (!file.isFile || file.length() != size) {
            return corrupt(revisionId, ordinal, side, null)
        }

        val digest = runCatching { ChapterRevisionArchiveArtifacts.digestOf(file) }.getOrNull()
        if (digest == null || digest.size != size || !digest.sha256.equals(sha256, ignoreCase = true)) {
            return corrupt(revisionId, ordinal, side, null)
        }

        val contentType = detectImageType { file.inputStream() } ?: return corrupt(revisionId, ordinal, side, null)
        if (contentType != ImageUtil.ImageType.JPEG) {
            // the thumbnail writer only ever produces JPEG, so anything else under that name is not the
            // preview that was recorded
            return corrupt(revisionId, ordinal, side, null)
        }

        return ChapterRevisionMediaResolution.Served(
            ChapterRevisionComparisonMedia("image/jpeg", size, file.inputStream()),
        )
    }

    fun page(
        revisionId: Int,
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
    ): ChapterRevisionMediaResolution {
        val comparison = ChapterRevisionComparisonStore.getComparison(revisionId) ?: return missing(revisionId, ordinal, side)
        val revision = sideRevision(comparison, revisionId, side) ?: return missing(revisionId, ordinal, side)
        val row = ChapterRevisionComparisonStore.getPage(revisionId, ordinal) ?: return missing(revisionId, ordinal, side)

        val pageIndex =
            when (side) {
                ChapterRevisionThumbnailSide.BASELINE -> row.baselinePageIndex
                ChapterRevisionThumbnailSide.CANDIDATE -> row.candidatePageIndex
            } ?: return missing(revisionId, ordinal, side)
        if (pageIndex < 0) {
            return missing(revisionId, ordinal, side)
        }

        return when (side) {
            // the staged pages are the bytes the comparison actually read, so they are preferred while
            // they exist; they are removed once the revision is archived and confirmed to be durable
            ChapterRevisionThumbnailSide.CANDIDATE -> candidatePage(revision, pageIndex, revisionId, ordinal, side)

            ChapterRevisionThumbnailSide.BASELINE -> archivedPage(revision, pageIndex, revisionId, ordinal, side)
        }
    }

    /**
     * The candidate side, from its staged pages when they are still there and from its immutable archive
     * otherwise.
     *
     * Both hold the same page files in the same order, so the stored page index addresses the same page
     * either way - which is what lets a page stay reviewable after the staging directory is cleaned up.
     */
    private fun candidatePage(
        revision: ChapterRevisionDataClass,
        pageIndex: Int,
        revisionId: Int,
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
    ): ChapterRevisionMediaResolution {
        revision.candidatePath?.let { path ->
            val directory = containedFile(stagingRoot(), path)
            if (directory != null && directory.isDirectory) {
                val page = ChapterRevisionStaging.pageFiles(directory).getOrNull(pageIndex)
                if (page != null) {
                    if (page.length() > ChapterRevisionPageImages.MAX_PAGE_BYTES) {
                        return corrupt(revisionId, ordinal, side, null)
                    }
                    val contentType =
                        detectImageType { page.inputStream() }
                            ?: return corrupt(revisionId, ordinal, side, null)
                    return ChapterRevisionMediaResolution.Served(
                        ChapterRevisionComparisonMedia(contentType.mime, page.length(), page.inputStream()),
                    )
                }
            }
        }

        return archivedPage(revision, pageIndex, revisionId, ordinal, side)
    }

    /** The baseline side, and the candidate fallback: always an immutable artifact of that revision. */
    private fun archivedPage(
        revision: ChapterRevisionDataClass,
        pageIndex: Int,
        revisionId: Int,
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
    ): ChapterRevisionMediaResolution {
        val artifact =
            when (val resolved = resolveArchivedArtifact(revision)) {
                is ArchivedArtifact.Resolved -> resolved.file
                ArchivedArtifact.Missing -> return missing(revisionId, ordinal, side)
                ArchivedArtifact.Corrupt -> return corrupt(revisionId, ordinal, side, null)
            }

        val stream =
            try {
                ChapterRevisionPageImages.openArchivedPage(artifact, pageIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChapterRevisionPageAccessException) {
                // the index is out of range, an entry name is unsafe, or a container limit was hit;
                // none of that is described to the client
                logFailure(revisionId, ordinal, side, e)
                return missing(revisionId, ordinal, side)
            } catch (e: IOException) {
                logFailure(revisionId, ordinal, side, e)
                return missing(revisionId, ordinal, side)
            }

        val contentType = detectImageType(stream)
        if (contentType == null) {
            runCatching { stream.close() }
            return corrupt(revisionId, ordinal, side, null)
        }

        // no length is claimed for a page read out of an archive: the entry's declared size is what the
        // archive says about itself, and reporting it as the length of the content would be reporting a
        // number this server never measured. The bound on how much may stream is enforced while it streams.
        return ChapterRevisionMediaResolution.Served(
            ChapterRevisionComparisonMedia(contentType.mime, null, stream),
        )
    }

    /**
     * The archived artifact of one side, preferring its immutable copy over the active library one.
     *
     * An artifact that is absent is skipped, which is what lets a pruned immutable payload fall back to
     * the active publication copy that holds the same bytes. An artifact that is present but is not the
     * size that was recorded for it is reported as corrupt rather than skipped: serving a different file
     * under the recorded page indices could show the wrong page, which is worse than showing none.
     */
    private fun resolveArchivedArtifact(revision: ChapterRevisionDataClass): ArchivedArtifact {
        val root = archiveRoot()
        val recorded =
            listOfNotNull(
                revision.archiveCbzPath?.let { it to revision.archiveCbzSize },
                revision.activeCbzPath?.let { it to revision.activeCbzSize },
            )

        var sawMismatch = false
        recorded.forEach { (path, size) ->
            val file = containedFile(root, path) ?: return@forEach
            if (!file.isFile) return@forEach
            if (size == null || file.length() != size) {
                sawMismatch = true
                return@forEach
            }
            return ArchivedArtifact.Resolved(file)
        }

        return if (sawMismatch) ArchivedArtifact.Corrupt else ArchivedArtifact.Missing
    }

    /**
     * The revision whose content one side of a row shows.
     *
     * A page of the baseline side comes from the baseline revision - the one the comparison was decided
     * against - while every other address belongs to the revision the comparison describes. A baseline
     * that was removed afterwards leaves the comparison readable but has no pages to show.
     */
    private fun sideRevision(
        comparison: ChapterRevisionComparisonDataClass,
        revisionId: Int,
        side: ChapterRevisionThumbnailSide,
    ): ChapterRevisionDataClass? =
        when (side) {
            ChapterRevisionThumbnailSide.CANDIDATE -> ChapterRevision.getRevision(revisionId)

            // a baseline that was removed afterwards leaves the comparison readable but has no pages
            ChapterRevisionThumbnailSide.BASELINE -> comparison.baselineRevisionId?.let { ChapterRevision.getRevision(it) }
        }

    /**
     * The canonical location of a stored thumbnail, or null when the stored value is not one this
     * server could have written for exactly this (revision, ordinal, side).
     *
     * The stored value is parsed, the parse is required to agree with the row it was stored for, and the
     * location is then rebuilt from the parsed parts and required to equal the stored string. Extra path
     * segments therefore cannot survive: anything that is not exactly the fixed shape is refused.
     */
    private fun expectedThumbnailPath(
        storedRelativePath: String,
        revision: ChapterRevisionDataClass,
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
    ): String? {
        val match = thumbnailPathPattern.matchEntire(storedRelativePath) ?: return null

        val candidateKey = match.groupValues[1]
        val pathRevisionId = match.groupValues[2].toIntOrNull() ?: return null
        val attempt = match.groupValues[3].toIntOrNull() ?: return null
        val pathOrdinal = match.groupValues[4].toIntOrNull() ?: return null
        val pathSide = match.groupValues[5]

        if (pathRevisionId != revision.id || pathOrdinal != ordinal) return null
        if (!pathSide.equals(side.name, ignoreCase = true)) return null
        // the directory is keyed by the candidate key of the revision the comparison was run for
        if (candidateKey != revision.candidateKey) return null
        if (attempt < 1) return null

        val canonical =
            ChapterRevisionThumbnails.relativePath(
                candidateKey = candidateKey,
                revisionId = pathRevisionId,
                attempt = attempt,
                ordinal = pathOrdinal,
                side = side,
            )

        return canonical.takeIf { it == storedRelativePath }
    }

    /** The media type of an image, or null when the leading bytes are not an allowed image type. */
    private fun detectImageType(openStream: () -> InputStream): ImageUtil.ImageType? = ImageUtil.findImageType(openStream)

    /**
     * The media type of an already open stream, rewound afterwards.
     *
     * The caller passes a buffered stream, so the inspection is a peek rather than a read: nothing is
     * consumed and the response still starts at the first byte of the page.
     */
    private fun detectImageType(stream: InputStream): ImageUtil.ImageType? =
        try {
            ImageUtil.findImageType(stream)
        } catch (e: Exception) {
            logger.debug { "A comparison page could not be inspected (${e::class.java.simpleName})" }
            null
        }

    private fun missing(
        revisionId: Int,
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
    ): ChapterRevisionMediaResolution.NotFound = ChapterRevisionMediaResolution.NotFound

    private fun corrupt(
        revisionId: Int,
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
        cause: Exception?,
    ): ChapterRevisionMediaResolution.Corrupt {
        logFailure(revisionId, ordinal, side, cause)
        return ChapterRevisionMediaResolution.Corrupt
    }

    /** Records an address and why it failed, never a path, a stored value or a raw message. */
    private fun logFailure(
        revisionId: Int,
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
        cause: Exception?,
    ) {
        logger.warn {
            "A comparison preview could not be served: revision=$revisionId ordinal=$ordinal " +
                "side=${side.name} cause=${cause?.let { it::class.java.simpleName } ?: "stored artifact mismatch"}"
        }
    }

    private data class StoredThumbnail(
        val relativePath: String?,
        val sha256: String?,
        val size: Long?,
    )

    private sealed interface ArchivedArtifact {
        data class Resolved(
            val file: File,
        ) : ArchivedArtifact

        data object Missing : ArchivedArtifact

        data object Corrupt : ArchivedArtifact
    }

    private companion object {
        /**
         * The only shape a stored thumbnail location may have.
         *
         * It is anchored and enumerates every part, so a location that differs from what the thumbnail
         * writer produces - by a segment, by a case, by an extension - simply does not match.
         */
        val thumbnailPathPattern =
            Regex(
                "^${Regex.escape(ChapterRevisionThumbnails.ROOT_DIR_NAME)}/" +
                    "([0-9a-f]{64})/(\\d{1,9})-(\\d{1,9})/(\\d+)-(baseline|candidate)\\.jpg$",
            )
    }
}
