package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Which side of a comparison a review thumbnail shows. */
enum class ChapterRevisionThumbnailSide {
    BASELINE,
    CANDIDATE,
}

/** Identity of a rendered review thumbnail, as persisted beside its alignment row. */
data class ChapterRevisionThumbnail(
    val relativePath: String,
    val sha256: String,
    val size: Long,
)

/**
 * Deterministic review thumbnails for the pages of a comparison.
 *
 * Thumbnails are derived data: they can always be rebuilt from the staged pages and the archived
 * CBZ, and they are never part of the archive. They are also the one thing a review UI cannot
 * recompute cheaply while a user waits, so they are written atomically and recorded with their digest.
 *
 * Each analysis attempt renders into its own directory. That is what makes a retry safe: a comparison
 * that is abandoned - because the baseline moved, because it was cancelled, because it failed - can
 * delete everything it rendered without touching the files a committed comparison still points at,
 * and the next attempt can never pick up a file it did not write.
 *
 * The location is derived only from the immutable 64-hex candidate key, the revision and the attempt
 * number, so it can never escape the comparison root however a chapter is named.
 */
object ChapterRevisionThumbnails {
    private val logger = KotlinLogging.logger {}

    /** sub-directory of the local staging root holding every comparison artifact */
    const val ROOT_DIR_NAME = "revision-comparisons"

    /** fixed encoder quality: the same page always produces the same bytes */
    private const val JPEG_QUALITY = 0.8f

    private const val FILE_SUFFIX = ".jpg"

    private val candidateKeyPattern = Regex("[0-9a-f]{64}")

    /** Portable, host independent location of one analysis attempt inside the staging root. */
    fun relativeDirectory(
        candidateKey: String,
        revisionId: Int,
        attempt: Int,
    ): String {
        require(candidateKeyPattern.matches(candidateKey)) { "Invalid chapter revision candidate key: $candidateKey" }
        require(attempt >= 1) { "An analysis attempt is numbered from one: $attempt" }
        return "$ROOT_DIR_NAME/$candidateKey/$revisionId-$attempt"
    }

    fun directory(
        stagingRoot: File,
        candidateKey: String,
        revisionId: Int,
        attempt: Int,
    ): File = File(stagingRoot, relativeDirectory(candidateKey, revisionId, attempt))

    /** Relative location of one thumbnail inside the staging root. */
    fun relativePath(
        candidateKey: String,
        revisionId: Int,
        attempt: Int,
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
    ): String = "${relativeDirectory(candidateKey, revisionId, attempt)}/${fileName(ordinal, side)}"

    fun file(
        stagingRoot: File,
        candidateKey: String,
        revisionId: Int,
        attempt: Int,
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
    ): File = File(directory(stagingRoot, candidateKey, revisionId, attempt), fileName(ordinal, side))

    private fun fileName(
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
    ): String = ordinal.toString().padStart(6, '0') + "-" + side.name.lowercase() + FILE_SUFFIX

    /**
     * Renders and stores the thumbnail of one side of one aligned row.
     *
     * Nothing already on disk is trusted: the file is written fresh, hashed after it is complete and
     * moved into place atomically. A page that cannot be rendered leaves the row without a preview
     * rather than with a wrong one.
     *
     * @return the stored thumbnail, or null when nothing could be rendered.
     */
    fun store(
        stagingRoot: File,
        candidateKey: String,
        revisionId: Int,
        attempt: Int,
        ordinal: Int,
        side: ChapterRevisionThumbnailSide,
        maxDimension: Int,
        render: () -> BufferedImage?,
    ): ChapterRevisionThumbnail? {
        val target = file(stagingRoot, candidateKey, revisionId, attempt, ordinal, side)

        val source =
            try {
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug { "A page could not be decoded for a review thumbnail" }
                null
            } ?: return null
        if (source.width <= 0 || source.height <= 0) {
            return null
        }

        return try {
            target.parentFile.mkdirs()
            val temporary = File(target.parentFile, ".${target.name}.tmp")
            temporary.delete()
            writeJpeg(scaleToFit(source, maxDimension), temporary)

            val digest = ChapterRevisionArchiveArtifacts.digestOf(temporary)
            ChapterRevisionStaging.moveAtomicallyReplacing(temporary, target)

            ChapterRevisionThumbnail(
                relativePath(candidateKey, revisionId, attempt, ordinal, side),
                digest.sha256,
                digest.size,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug { "A review thumbnail could not be stored" }
            null
        }
    }

    /**
     * Removes everything one attempt rendered.
     *
     * Called when an attempt is abandoned, so a comparison of the wrong baseline - or one that never
     * committed - can never leave a preview behind that a later reader might take for the current one.
     */
    fun deleteAttempt(
        stagingRoot: File,
        candidateKey: String,
        revisionId: Int,
        attempt: Int,
    ) {
        deleteDirectory(directory(stagingRoot, candidateKey, revisionId, attempt))
    }

    /**
     * Removes every attempt directory of one revision except [keepAttempt].
     *
     * Called only after a comparison has been committed, so the kept attempt is the one whose files
     * the rows that were just written point at, and nothing references the others any more. It is
     * deliberately keyed on the revision as well as the candidate: two revisions of one chapter are
     * analysed independently, so a replacement of one must never delete the other's previews.
     */
    fun deleteSupersededAttempts(
        stagingRoot: File,
        candidateKey: String,
        revisionId: Int,
        keepAttempt: Int,
    ) = deleteAttemptDirectories(stagingRoot, candidateKey, revisionId, keepAttempt)

    /**
     * Removes every attempt directory of one revision.
     *
     * Called when a revision settles without a comparison: a previously committed summary is deleted
     * with the rows it owned, so nothing describes what an earlier attempt rendered and no preview of
     * it may survive to be taken for the current one.
     */
    fun deleteRevisionAttempts(
        stagingRoot: File,
        candidateKey: String,
        revisionId: Int,
    ) = deleteAttemptDirectories(stagingRoot, candidateKey, revisionId, keepAttempt = null)

    /**
     * Removes the attempt directories of one revision except [keepAttempt], when one is named.
     *
     * The candidate key is pattern-checked and the revision is an [Int], so the directory that is
     * listed is always a direct child of the staging root's comparison root, and an entry is deleted
     * only when its name is exactly `"<revision>-<attempt>"`.
     */
    private fun deleteAttemptDirectories(
        stagingRoot: File,
        candidateKey: String,
        revisionId: Int,
        keepAttempt: Int?,
    ) {
        if (!candidateKeyPattern.matches(candidateKey)) {
            return
        }

        val prefix = "$revisionId-"
        File(stagingRoot, "$ROOT_DIR_NAME/$candidateKey").listFiles()?.forEach { entry ->
            if (!entry.isDirectory || !entry.name.startsWith(prefix)) {
                return@forEach
            }
            val attempt = entry.name.removePrefix(prefix).toIntOrNull()
            if (attempt != null && attempt != keepAttempt) {
                deleteDirectory(entry)
            }
        }
    }

    /** Removes every thumbnail of one candidate; derived data, so it is always safe to rebuild. */
    fun deleteAll(
        stagingRoot: File,
        candidateKey: String,
    ) {
        if (!candidateKeyPattern.matches(candidateKey)) {
            return
        }
        deleteDirectory(File(stagingRoot, "$ROOT_DIR_NAME/$candidateKey"))
    }

    private fun deleteDirectory(directory: File) {
        if (directory.exists() && !directory.deleteRecursively() && directory.exists()) {
            throw IOException("Failed to remove the comparison thumbnails: $directory")
        }
    }

    /** Bounded rescale that preserves the aspect ratio and never enlarges. */
    fun scaleToFit(
        source: BufferedImage,
        maxDimension: Int,
    ): BufferedImage {
        val limit = maxDimension.coerceAtLeast(1)
        val longest = maxOf(source.width, source.height)
        val scale = if (longest <= limit) 1.0 else limit.toDouble() / longest

        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)

        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, Color.WHITE, null)
        } finally {
            graphics.dispose()
        }

        return target
    }

    private fun writeJpeg(
        image: BufferedImage,
        target: File,
    ) {
        val writer =
            ImageIO.getImageWritersByFormatName("jpeg").let {
                if (!it.hasNext()) {
                    throw IOException("no JPEG writer is available")
                }
                it.next()
            }

        try {
            val parameters = writer.defaultWriteParam
            parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
            parameters.compressionQuality = JPEG_QUALITY

            val output = ImageIO.createImageOutputStream(target)
            if (output == null) {
                throw IOException("failed to open a thumbnail output stream")
            }

            output.use {
                writer.output = output
                writer.write(null, IIOImage(image, null, null), parameters)
            }
        } finally {
            writer.dispose()
        }
    }
}
