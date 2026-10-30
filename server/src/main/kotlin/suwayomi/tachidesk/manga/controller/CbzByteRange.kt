package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** One contiguous run of bytes of an object, inclusive of both ends. */
internal data class CbzByteRange(
    val start: Long,
    val endInclusive: Long,
) {
    val length: Long get() = endInclusive - start + 1
}

/** What a request's `Range` header asks for. */
internal sealed interface CbzRangeRequest {
    /** no byte range was asked for: the whole object is sent */
    data object Whole : CbzRangeRequest

    data class Partial(
        val range: CbzByteRange,
    ) : CbzRangeRequest

    /** the range is malformed, names several runs, or lies outside the object */
    data object Unsatisfiable : CbzRangeRequest
}

/**
 * Only one run of bytes, so a multi-range request cannot be mistaken for a single one.
 *
 * Both ends are optional (`a-b`, `a-`, `-b`), and the digits are captured separately so the three forms
 * stay distinguishable: `bytes=-` matches this pattern but means neither an offset nor a suffix.
 */
private val SINGLE_BYTE_RANGE = Regex("bytes=([0-9]*)-([0-9]*)", RegexOption.IGNORE_CASE)

/**
 * Parses an RFC 7233 byte range request for an object of [size] bytes.
 *
 * Only a single `bytes` range is answered. A range is a request for one contiguous run of bytes, so a
 * multi-range request is refused instead of answered with its first run - silently serving a subset
 * would hand the client something other than what it asked for while claiming the request was honoured.
 * A malformed or out-of-range request is unsatisfiable, which is what lets the caller answer 416 with
 * the real size of the object rather than a guess.
 *
 * A `Range` header naming a unit this server does not understand is ignored, as RFC 7233 requires: only
 * a `bytes` range it cannot satisfy is refused.
 */
internal fun parseCbzRange(
    header: String?,
    size: Long,
): CbzRangeRequest {
    val value = header?.trim().orEmpty()
    if (value.isEmpty()) return CbzRangeRequest.Whole
    if (!value.startsWith("bytes=", ignoreCase = true)) return CbzRangeRequest.Whole

    val match = SINGLE_BYTE_RANGE.matchEntire(value) ?: return CbzRangeRequest.Unsatisfiable
    val first = match.groupValues[1]
    val last = match.groupValues[2]
    if (first.isEmpty() && last.isEmpty()) return CbzRangeRequest.Unsatisfiable

    if (first.isEmpty()) {
        // a suffix range: the last `last` bytes, or the whole object when it asks for more than there is
        val suffix = last.toLongOrNull() ?: return CbzRangeRequest.Unsatisfiable
        if (suffix <= 0 || size <= 0) return CbzRangeRequest.Unsatisfiable

        return CbzRangeRequest.Partial(CbzByteRange(size - suffix.coerceAtMost(size), size - 1))
    }

    val start = first.toLongOrNull() ?: return CbzRangeRequest.Unsatisfiable
    if (size <= 0 || start >= size) return CbzRangeRequest.Unsatisfiable

    if (last.isEmpty()) {
        return CbzRangeRequest.Partial(CbzByteRange(start, size - 1))
    }

    val requestedEnd = last.toLongOrNull() ?: return CbzRangeRequest.Unsatisfiable
    if (requestedEnd < start) return CbzRangeRequest.Unsatisfiable

    // an end past the object is clamped to it: that is a satisfied request, not an unsatisfiable one
    return CbzRangeRequest.Partial(CbzByteRange(start, requestedEnd.coerceAtMost(size - 1)))
}

/**
 * A file stream bounded to one run of bytes.
 *
 * The response must carry exactly the range it announced, so the stream stops at the end of the range
 * and closes the file as soon as it is reached: a copy of the artifact that is longer than the range
 * can never leak past the announced length, and no descriptor stays open once the response body is
 * complete. Closing is idempotent, so an early client disconnect and an end of stream cannot conflict.
 */
internal class BoundedFileStream(
    file: File,
    start: Long,
    length: Long,
) : InputStream() {
    private val source = FileInputStream(file)
    private var remaining = length
    private var closed = false

    init {
        source.channel.position(start)
    }

    override fun read(): Int {
        if (remaining <= 0) return -1

        val value = source.read()
        if (value < 0) {
            close()
            return -1
        }

        consume(1)
        return value
    }

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (remaining <= 0) return -1
        if (length == 0) return 0

        val read = source.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
        if (read < 0) {
            close()
            return -1
        }

        consume(read)
        return read
    }

    override fun available(): Int = minOf(remaining, source.available().toLong()).toInt()

    override fun close() {
        if (closed) return
        closed = true
        runCatching { source.close() }
    }

    private fun consume(count: Int) {
        remaining -= count
        if (remaining <= 0) {
            close()
        }
    }
}
