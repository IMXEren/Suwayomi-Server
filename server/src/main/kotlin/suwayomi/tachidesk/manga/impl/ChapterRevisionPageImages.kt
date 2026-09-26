package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream

/** One page of a chapter as the comparison sees it: a digest plus whatever could be decoded. */
data class ChapterRevisionPageAnalysis(
    val name: String,
    /**
     * The whole-page digest, or null when the page was never read to its end.
     *
     * A page larger than the analysis limit - or one met after the chapter's cumulative limit was
     * already reached - is never hashed to the end, and a digest of a prefix would describe a
     * different file. It stays null so nothing can compare it against a real digest.
     */
    val exactHash: String?,
    val size: Long,
    val width: Int?,
    val height: Int?,
    val perceptualHash: String?,
    /** why this page could not be analysed visually, or null when it could */
    val limitation: String?,
) {
    fun toFingerprint(): ChapterVisualPageFingerprint =
        ChapterVisualPageFingerprint(
            exactHash = exactHash,
            perceptualHash = perceptualHash,
            width = width,
            height = height,
            size = size,
        )
}

/** Raised when a chapter cannot even be enumerated, as opposed to one page being undecodable. */
class ChapterRevisionPageAccessException(
    message: String,
) : IOException(message)

/**
 * One readable side of a comparison.
 *
 * Pages are enumerated once - which computes every whole-page digest and fingerprints whatever
 * decodes - and a single page can be decoded again on demand at a larger size. Nothing is kept at
 * full resolution, so a review thumbnail is rendered from a fresh, bounded decode instead of holding
 * a whole chapter of images in memory.
 */
class ChapterRevisionChapterPages internal constructor(
    val pages: List<ChapterRevisionPageAnalysis>,
    /** per-page reasons the visual comparison had to be degraded, in page order */
    val limitations: List<String>,
    private val decodePage: (Int, Int) -> BufferedImage?,
    private val onClose: () -> Unit,
) : Closeable {
    fun fingerprints(): List<ChapterVisualPageFingerprint> = pages.map { it.toFingerprint() }

    /** Decodes [index] again, bounded to roughly [targetEdge] pixels on its longer side. */
    fun decode(
        index: Int,
        targetEdge: Int,
    ): BufferedImage? = decodePage(index, targetEdge)

    override fun close() = onClose()
}

/**
 * Reads the pages of either side of a comparison.
 *
 * Both sides are read the same way on purpose: a staged candidate directory and an archived CBZ hold
 * the same page files, so a digest computed here is directly comparable with the one the archive
 * manifest recorded at commit time.
 *
 * Every failure mode that concerns a single page - an unsupported format, a decode error, a
 * pathological dimension - is reduced to a limitation on that page. The exact digest is computed
 * before any decoding is attempted, so a page that cannot be decoded still participates in the
 * comparison; only a chapter that cannot be enumerated at all fails.
 */
object ChapterRevisionPageImages {
    private val logger = KotlinLogging.logger {}

    /** Pages larger than this are hashed but never decoded, so a hostile file cannot exhaust memory. */
    const val MAX_PAGE_BYTES = 64L * 1024 * 1024

    /** Total uncompressed size a single chapter may declare before it is refused outright. */
    const val MAX_CHAPTER_BYTES = 512L * 1024 * 1024

    /** A page is never decoded at more than this many pixels per side. */
    const val MAX_IMAGE_DIMENSION = 20_000

    /**
     * A page is never decoded at more than this many pixels in total.
     *
     * Subsampling bounds what a decoder allocates for the formats that honour it, but not every
     * reader does, so the size a file declares is bounded independently as well.
     */
    const val MAX_IMAGE_PIXELS = 40_000_000L

    /** Upper bound on the number of pages one chapter may declare. */
    const val MAX_PAGE_COUNT = 20_000

    /** Static reason a page was not read to its end; never carries a path or a container detail. */
    private const val PAGE_LIMIT_REASON = "the page is larger than the analysis limit"

    /** Static reason the rest of a chapter was not read; never carries a path or a container detail. */
    private const val CHAPTER_LIMIT_REASON = "the chapter is larger than the analysis limit"

    /** The image is subsampled to roughly this edge length before it is fingerprinted. */
    private const val FINGERPRINT_TARGET_EDGE = 64

    init {
        // a review of a large library must not fill the JVM's image cache directory
        ImageIO.setUseCache(false)
    }

    /** Ordered pages of a staged candidate directory. */
    fun openDirectory(directory: File): ChapterRevisionChapterPages {
        if (!directory.isDirectory) {
            throw ChapterRevisionPageAccessException("the staged chapter directory is missing")
        }

        val files = ChapterRevisionStaging.pageFiles(directory)
        if (files.isEmpty()) {
            throw ChapterRevisionPageAccessException("the staged chapter does not have any pages")
        }
        if (files.size > MAX_PAGE_COUNT) {
            throw ChapterRevisionPageAccessException("the staged chapter declares ${files.size} pages")
        }

        val (pages, limitations) =
            readPages(
                names = files.map { it.name },
                declaredSizes = files.map { it.length() },
                openStream = { index -> files[index].inputStream() },
            )

        return ChapterRevisionChapterPages(
            pages = pages,
            limitations = limitations,
            decodePage = { index, targetEdge ->
                val file = files.getOrNull(index) ?: return@ChapterRevisionChapterPages null
                decode(targetEdge) { file.inputStream() }?.image
            },
            onClose = {},
        )
    }

    /**
     * Ordered pages of an archived chapter CBZ.
     *
     * Zip entry names are never trusted as paths: an entry is addressed through the [ZipFile] API, and
     * any name that is not a plain file name in the archive root is refused before its content is
     * read. That is what makes a crafted archive unable to reach outside the archive root.
     */
    fun openArchive(cbz: File): ChapterRevisionChapterPages {
        if (!cbz.isFile) {
            throw ChapterRevisionPageAccessException("the archived chapter is missing")
        }

        val zip = ZipFile(cbz)
        try {
            val ordered = orderedEntries(zip)
            val safeNames = ordered.map { requireSafeEntryName(it) }
            val (pages, limitations) =
                readPages(
                    names = safeNames,
                    declaredSizes = ordered.map { it.size },
                    openStream = { index -> zip.getInputStream(ordered[index]) },
                )

            return ChapterRevisionChapterPages(
                pages = pages,
                limitations = limitations,
                decodePage = { index, targetEdge ->
                    val entry = ordered.getOrNull(index) ?: return@ChapterRevisionChapterPages null
                    decode(targetEdge) { zip.getInputStream(entry) }?.image
                },
                onClose = { runCatching { zip.close() } },
            )
        } catch (e: Exception) {
            runCatching { zip.close() }
            throw e
        }
    }

    /**
     * Opens exactly one page of an archived chapter.
     *
     * The entry is selected with the same ordering and the same root-entry safety rule that
     * [openArchive] applies, so page index n here is page index n there. That is what makes the page
     * index a comparison recorded usable on its own: the same index addresses the same page whether a
     * whole chapter or a single page is read. The returned stream owns the archive, and closes it when
     * it is closed or when it is read to its end.
     */
    fun openArchivedPage(
        cbz: File,
        index: Int,
    ): InputStream {
        if (!cbz.isFile) {
            throw ChapterRevisionPageAccessException("the archived chapter is missing")
        }
        if (index < 0) {
            throw ChapterRevisionPageAccessException("the archived chapter has no page at that index")
        }

        val zip = ZipFile(cbz)
        try {
            val entry =
                orderedEntries(zip).getOrNull(index)
                    ?: throw ChapterRevisionPageAccessException("the archived chapter has no page at that index")
            if (entry.size > MAX_PAGE_BYTES) {
                throw ChapterRevisionPageAccessException("the archived page is larger than the serving limit")
            }
            // buffered so the leading bytes can be inspected and rewound without a second read of the
            // archive or a copy of the page, and bounded so the size the entry declares is never what
            // decides how much a page may stream
            return BufferedInputStream(ChapterArchivePageStream(zip, zip.getInputStream(entry), MAX_PAGE_BYTES))
        } catch (e: Exception) {
            runCatching { zip.close() }
            throw e
        }
    }

    /**
     * The ordered, safety-checked entries of an archive.
     *
     * Every container level limit and the root-entry safety rule are enforced here, so the whole-chapter
     * reader and the single-page reader refuse exactly the same archives for exactly the same reasons.
     */
    private fun orderedEntries(zip: ZipFile): List<ZipEntry> {
        val entries = zip.entries().toList().filter { !it.isDirectory }
        if (entries.isEmpty()) {
            throw ChapterRevisionPageAccessException("the archived chapter does not have any pages")
        }
        if (entries.size > MAX_PAGE_COUNT) {
            throw ChapterRevisionPageAccessException("the archived chapter declares ${entries.size} entries")
        }

        val declaredTotal = entries.sumOf { it.size.coerceAtLeast(0) }
        if (declaredTotal > MAX_CHAPTER_BYTES) {
            throw ChapterRevisionPageAccessException("the archived chapter declares $declaredTotal uncompressed bytes")
        }

        val ordered = entries.sortedBy { it.name }
        ordered.forEach { requireSafeEntryName(it) }
        return ordered
    }

    /**
     * A zip entry name is accepted only when it is a plain file name.
     *
     * The archive writer stores pages as `00001.png` and never creates a directory, so anything that
     * looks like a path - a separator, a parent reference, an absolute name - is a sign the archive
     * was produced by something else and is refused.
     */
    private fun requireSafeEntryName(entry: ZipEntry): String {
        val name = entry.name
        if (name.isEmpty() ||
            name.contains('/') ||
            name.contains('\\') ||
            name.contains("..") ||
            name.startsWith(".")
        ) {
            throw ChapterRevisionPageAccessException("the archived chapter contains an unsafe entry name")
        }
        return name
    }

    /** Reads one page: digest first, decode second, every decode problem reduced to a limitation. */
    private fun readPages(
        names: List<String>,
        declaredSizes: List<Long>,
        openStream: (Int) -> InputStream,
    ): Pair<List<ChapterRevisionPageAnalysis>, List<String>> {
        val budget = ChapterByteBudget()
        val pages = ArrayList<ChapterRevisionPageAnalysis>(names.size)
        val limitations = ArrayList<String>()

        names.forEachIndexed { index, name ->
            val declaredSize = declaredSizes[index].coerceAtLeast(0)
            val page =
                if (budget.isExhausted) {
                    // the chapter has already yielded more than it may, so no further page is read at
                    // all: a hostile archive must not be able to stream forever just by lying about
                    // its per-entry sizes
                    ChapterRevisionPageAnalysis(name, null, declaredSize, null, null, null, CHAPTER_LIMIT_REASON)
                } else {
                    read(name, declaredSize) { openStream(index) }
                }

            budget.consume(page.size)
            pages += page
            page.limitation?.let { limitations += "$name: $it" }
        }

        return pages to limitations
    }

    /**
     * Reads one page: digest first, decode second, every decode problem reduced to a limitation.
     *
     * The digest read is bounded, so a page whose declared size lies - a few compressed kilobytes that
     * expand to gigabytes - cannot be decompressed without end: the stream is abandoned as soon as it
     * yields more than [MAX_PAGE_BYTES], and the page is recorded as a limitation with no digest at
     * all rather than with the digest of a prefix.
     */
    private fun read(
        name: String,
        declaredSize: Long,
        openStream: () -> InputStream,
    ): ChapterRevisionPageAnalysis {
        if (declaredSize > MAX_PAGE_BYTES) {
            // the container already says this page is over the limit, so it is never read
            return ChapterRevisionPageAnalysis(name, null, declaredSize, null, null, null, PAGE_LIMIT_REASON)
        }

        val (exactHash, size) =
            try {
                digestOf(openStream, MAX_PAGE_BYTES)
            } catch (e: Exception) {
                logger.debug { "A chapter page could not be read" }
                throw ChapterRevisionPageAccessException("a chapter page could not be read")
            }

        if (exactHash == null) {
            return ChapterRevisionPageAnalysis(name, null, size, null, null, null, PAGE_LIMIT_REASON)
        }

        if (size == 0L) {
            return ChapterRevisionPageAnalysis(name, exactHash, size, null, null, null, "the page is empty")
        }

        val decoded =
            try {
                decode(FINGERPRINT_TARGET_EDGE, openStream)
            } catch (e: Exception) {
                logger.debug { "A chapter page could not be decoded" }
                null
            }

        if (decoded == null || decoded.image == null) {
            val reason = decoded?.limitation ?: "the page could not be decoded"
            return ChapterRevisionPageAnalysis(name, exactHash, size, decoded?.width, decoded?.height, null, reason)
        }

        val perceptualHash =
            try {
                ChapterRevisionVisualComparison.fingerprint(decoded.image)
            } catch (e: Exception) {
                logger.debug { "A chapter page could not be fingerprinted" }
                null
            }

        return ChapterRevisionPageAnalysis(
            name = name,
            exactHash = exactHash,
            size = size,
            width = decoded.width,
            height = decoded.height,
            perceptualHash = perceptualHash,
            limitation = if (perceptualHash == null) "the page could not be fingerprinted" else null,
        )
    }

    /**
     * Decodes one page bounded to roughly [targetEdge] pixels on its longer side.
     *
     * The reported [DecodedImage.width]/[DecodedImage.height] are the dimensions of the *original*
     * image, not of the subsampled buffer: they are what the aspect-ratio guard compares, and using
     * the subsampled size would make the guard depend on the decode step.
     */
    private fun decode(
        targetEdge: Int,
        openStream: () -> InputStream,
    ): DecodedImage? =
        ImageInputStreamContainer(openStream).use { container ->
            val reader = container.reader
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)

            if (width <= 0 || height <= 0) {
                return@use DecodedImage(width, height, null, "the page reports an invalid size")
            }
            if (width > MAX_IMAGE_DIMENSION ||
                height > MAX_IMAGE_DIMENSION ||
                width.toLong() * height > MAX_IMAGE_PIXELS
            ) {
                return@use DecodedImage(width, height, null, "the page is $width x $height pixels")
            }

            val step = (minOf(width, height) / targetEdge.coerceAtLeast(1)).coerceAtLeast(1)
            val image =
                try {
                    val parameters = reader.defaultReadParam
                    parameters.setSourceSubsampling(step, step, 0, 0)
                    reader.read(0, parameters)
                } catch (e: Exception) {
                    logger.debug { "A subsampled chapter page could not be decoded" }
                    null
                }

            DecodedImage(width, height, image, if (image == null) "the page could not be decoded" else null)
        }

    /**
     * Digest of at most `limit` bytes.
     *
     * Internal rather than private so the bound itself can be tested without having to materialise a
     * file larger than the analysis limit.
     *
     * @return the digest and the exact byte count, or a null digest and the count of bytes that were
     * read when the stream still had more to give after `limit` bytes. The stream is never read past
     * `limit + 1` bytes, so a small compressed entry that expands to gigabytes costs one buffer more
     * than the limit and nothing else.
     */
    internal fun digestOf(
        openStream: () -> InputStream,
        limit: Long,
    ): Pair<String?, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val cap = limit + 1
        var total = 0L

        openStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (total < cap) {
                val room = minOf(buffer.size.toLong(), cap - total).toInt()
                val read = input.read(buffer, 0, room)
                if (read < 0) break
                total += read
                digest.update(buffer, 0, read)
            }
        }

        if (total > limit) {
            // the page is larger than the limit, so there is no digest of it to report
            return null to total
        }

        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) } to total
    }

    /** Running count of the bytes a chapter has actually yielded. */
    private class ChapterByteBudget {
        private var consumed = 0L

        /** True once the chapter has already produced more bytes than it is allowed to. */
        val isExhausted: Boolean get() = consumed > MAX_CHAPTER_BYTES

        fun consume(bytes: Long) {
            consumed = (consumed + bytes.coerceAtLeast(0)).coerceAtMost(MAX_CHAPTER_BYTES + 1)
        }
    }

    private data class DecodedImage(
        val width: Int,
        val height: Int,
        val image: BufferedImage?,
        val limitation: String?,
    )

    /** Owns the reader and both streams together, so neither can outlive the other. */
    private class ImageInputStreamContainer(
        openStream: () -> InputStream,
    ) : AutoCloseable {
        /** the stream the caller opened; an [ImageInputStream] does not take ownership of it */
        private val raw: InputStream = openStream()
        private val stream: ImageInputStream
        val reader: ImageReader

        init {
            val created =
                try {
                    ImageIO.createImageInputStream(raw)
                } catch (e: Exception) {
                    runCatching { raw.close() }
                    throw e
                }

            if (created == null) {
                runCatching { raw.close() }
                throw ChapterRevisionPageAccessException("the page is not a readable image stream")
            }
            stream = created

            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) {
                runCatching { stream.close() }
                runCatching { raw.close() }
                throw ChapterRevisionPageAccessException("the page format is not supported")
            }
            reader = readers.next()
            reader.input = stream
        }

        override fun close() {
            runCatching { reader.dispose() }
            runCatching { stream.close() }
            // closed explicitly rather than assumed: a leaked file handle keeps a staged page open
            runCatching { raw.close() }
        }
    }
}

/**
 * One open page of an archive, bounded to [limit] bytes.
 *
 * Reading it to its end closes the archive, so a response that is written completely cannot leave a
 * [ZipFile] open, and an abandoned request is covered by the close the response already owes. Closing
 * twice is harmless, because a servlet container may close a stream the writer already closed.
 *
 * The bound counts the bytes actually handed out rather than the size the archive declares. A zip entry
 * may declare any uncompressed size it likes - or omit it - so a declared size is only a hint that is
 * checked early, while the count is what a hostile or corrupt archive is actually held to. The archive
 * is released at the moment the bound is crossed, so an entry that streams without end cannot hold a
 * file handle open while it is refused.
 */
internal class ChapterArchivePageStream(
    private val zip: ZipFile,
    private val entry: InputStream,
    private val limit: Long,
) : InputStream() {
    private var closed = false
    private var served = 0L

    override fun read(): Int {
        val value = entry.read()
        if (value < 0) {
            close()
        } else {
            charged(1)
        }
        return value
    }

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val read = entry.read(bytes, offset, length)
        if (read < 0) {
            close()
        } else {
            charged(read)
        }
        return read
    }

    override fun available(): Int = entry.available()

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        runCatching { entry.close() }
        zip.close()
    }

    /** Charges read bytes to the bound, refusing the read that crosses it and releasing the archive. */
    private fun charged(amount: Int) {
        served += amount
        if (served > limit) {
            close()
            throw ChapterRevisionPageAccessException("the archived page is larger than the serving limit")
        }
    }
}
