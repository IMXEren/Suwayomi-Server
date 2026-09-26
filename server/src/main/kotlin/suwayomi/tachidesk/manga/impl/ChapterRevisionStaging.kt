package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse
import suwayomi.tachidesk.manga.impl.util.storage.ImageUtil
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Integrity outcome of a finalized candidate directory. */
sealed interface ChapterRevisionValidation {
    data class Valid(
        val pageCount: Int,
        val contentHash: String,
    ) : ChapterRevisionValidation

    data class Invalid(
        val reason: String,
    ) : ChapterRevisionValidation
}

/**
 * On-disk layout and integrity checks for staged chapter revision candidates.
 *
 * Pages are written into `<staging root>/revision-candidates/<candidateKey>.partial` and a finished
 * download is published by renaming that directory to `<staging root>/revision-candidates/<candidateKey>`.
 * Both paths share the same parent, so on one filesystem the rename is atomic and a candidate
 * directory is never observed half written.
 */
object ChapterRevisionStaging {
    /** sub-directory of the staging root holding every candidate download */
    const val ROOT_DIR_NAME = "revision-candidates"

    /** suffix of a candidate directory that is still being written */
    const val PARTIAL_SUFFIX = ".partial"

    /** suffix of an interrupted page write, written by [ImageResponse.saveImage] */
    private const val TEMP_FILE_SUFFIX = ".tmp"

    /**
     * Page files are zero padded so that their lexicographic order is the page order and so that a
     * single page can be located again after a restart without knowing the total page count.
     */
    private const val PAGE_NAME_WIDTH = 5

    /** attempts of the atomic directory publication before a transient failure is reported */
    private const val MOVE_ATTEMPTS = 5

    private const val MOVE_RETRY_MILLIS = 20L

    private val candidateKeyPattern = Regex("[0-9a-f]{64}")

    /** Portable, host independent location of a candidate inside the staging root. */
    fun relativeDirectory(candidateKey: String): String {
        require(candidateKeyPattern.matches(candidateKey)) { "Invalid chapter revision candidate key: $candidateKey" }
        return "$ROOT_DIR_NAME/$candidateKey"
    }

    fun directory(
        stagingRoot: File,
        candidateKey: String,
    ): File = File(stagingRoot, relativeDirectory(candidateKey))

    fun partialDirectory(
        stagingRoot: File,
        candidateKey: String,
    ): File = File(stagingRoot, relativeDirectory(candidateKey) + PARTIAL_SUFFIX)

    fun pageFileName(index: Int): String = (index + 1).toString().padStart(PAGE_NAME_WIDTH, '0')

    /** Finalized page file of [index], or null when that page has not been written yet. */
    fun findPageFile(
        directory: File,
        index: Int,
    ): File? {
        val prefix = pageFileName(index) + "."
        // an interrupted write is named "<page>.tmp", which also matches the prefix, so it must
        // never be mistaken for a downloaded page
        return directory
            .listFiles()
            .orEmpty()
            .firstOrNull { it.isFile && it.name.startsWith(prefix) && !it.name.endsWith(TEMP_FILE_SUFFIX) }
    }

    /** Drops interrupted page writes so validation never sees a truncated page. */
    fun clearTemporaryPages(directory: File) {
        directory
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(TEMP_FILE_SUFFIX) }
            .forEach { it.delete() }
    }

    /** Writes one page atomically and returns the written file. */
    fun writePage(
        directory: File,
        index: Int,
        content: InputStream,
    ): File {
        directory.mkdirs()
        val (path, _) = ImageResponse.saveImage(File(directory, pageFileName(index)).path, content, null)
        return File(path)
    }

    /**
     * Ordered page files of a finalized candidate directory.
     *
     * Only finalized pages are returned, so a leftover temporary write can never be packaged into
     * an archive CBZ.
     */
    fun pageFiles(directory: File): List<File> =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(TEMP_FILE_SUFFIX) }
            .sortedBy { it.name }

    /** Publishes a complete partial directory with one filesystem-atomic move. */
    fun publishCandidate(
        stagingRoot: File,
        candidateKey: String,
    ) {
        moveAtomicallyInto(
            source = partialDirectory(stagingRoot, candidateKey),
            target = directory(stagingRoot, candidateKey),
        )
    }

    /**
     * Publishes [source] as [target] with one filesystem-atomic move.
     *
     * A rename on Windows can transiently fail while another process (indexer, scanner, antivirus)
     * still holds a handle inside a directory tree. Retrying is safe because the move is atomic:
     * either the target appeared or it did not, never both partially.
     */
    fun moveAtomicallyInto(
        source: File,
        target: File,
    ) {
        var attempt = 1
        while (true) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (e: IOException) {
                if (attempt >= MOVE_ATTEMPTS || target.exists() || !source.exists()) {
                    throw e
                }
                Thread.sleep(MOVE_RETRY_MILLIS * attempt)
                attempt++
            }
        }
    }

    /**
     * Publishes [source] as [target] with one filesystem-atomic move, replacing [target] if it is
     * already there.
     *
     * The active library copy of a chapter is replaced whenever a newly accepted revision is
     * published, so unlike [moveAtomicallyInto] an existing target is expected rather than an error.
     * Both paths live on the same filesystem, which is what makes the replacement atomic.
     */
    fun moveAtomicallyReplacing(
        source: File,
        target: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // the temporary file is written as a sibling of the target, so this can only happen on
            // an unusual filesystem; a plain replace is still better than refusing to publish
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Removes both the finalized and the in-progress directory of a candidate. */
    fun deleteCandidate(
        stagingRoot: File,
        candidateKey: String,
    ) {
        listOf(directory(stagingRoot, candidateKey), partialDirectory(stagingRoot, candidateKey)).forEach { path ->
            if (path.exists() && !path.deleteRecursively() && path.exists()) {
                throw IOException("Failed to remove staged chapter revision path: $path")
            }
        }
    }

    /**
     * Validates a finalized candidate directory.
     *
     * [expectedPageCount] is the page count the source reported for this attempt. It is null when a
     * crash left a finalized directory behind and the candidate is re-validated without contacting
     * the source again; only the structural consistency of the directory can be checked then.
     */
    fun validate(
        directory: File,
        expectedPageCount: Int?,
    ): ChapterRevisionValidation {
        if (!directory.isDirectory) {
            return ChapterRevisionValidation.Invalid("staged chapter directory is missing")
        }

        val files = directory.listFiles().orEmpty()
        files.firstOrNull { it.name.endsWith(TEMP_FILE_SUFFIX) }?.let {
            return ChapterRevisionValidation.Invalid("incomplete page was left behind: ${it.name}")
        }
        files.firstOrNull { !it.isFile }?.let {
            return ChapterRevisionValidation.Invalid("unexpected entry in the staged chapter: ${it.name}")
        }
        if (files.isEmpty()) {
            return ChapterRevisionValidation.Invalid("chapter does not have any pages")
        }

        val pages = files.sortedBy { it.name }
        if (expectedPageCount != null && pages.size != expectedPageCount) {
            return ChapterRevisionValidation.Invalid("expected $expectedPageCount pages but staged ${pages.size}")
        }

        pages.forEachIndexed { index, page ->
            // the sorted names must be exactly 1..N; anything else is a missing, duplicated or
            // unexpected page
            if (!page.name.startsWith(pageFileName(index) + ".")) {
                return ChapterRevisionValidation.Invalid("unexpected page file: ${page.name}")
            }
            if (page.length() == 0L) {
                return ChapterRevisionValidation.Invalid("page ${page.name} is empty")
            }
            if (ImageUtil.findImageType { page.inputStream() } == null) {
                return ChapterRevisionValidation.Invalid("page ${page.name} is not a recognized image")
            }
        }

        return ChapterRevisionValidation.Valid(pages.size, contentHash(pages))
    }

    /**
     * SHA-256 over the ordered page content. Every page contributes its index and byte length before
     * its bytes, so the digest can not be reproduced by a different page division of the same
     * concatenated data.
     */
    private fun contentHash(pages: List<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteBuffer.allocate(PAGE_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)

        pages.forEachIndexed { index, page ->
            header.clear()
            header.putInt(index)
            header.putLong(page.length())
            digest.update(header.array())

            page.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private const val PAGE_HEADER_SIZE = 12
}
