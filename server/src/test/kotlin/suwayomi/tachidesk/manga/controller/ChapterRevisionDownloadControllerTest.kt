package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.http.NotFoundResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.impl.ChapterRevisionDeliveryResolution
import suwayomi.tachidesk.server.user.UnauthorizedException
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.test.ApplicationTest
import java.io.File
import java.io.InputStream

/**
 * The request surface of the archived-CBZ download.
 *
 * What is asserted here is everything that happens before and after a location is resolved: who may ask,
 * which addresses name nothing, which byte runs a response may carry, and what each outcome turns into.
 * No remote is involved, so a direct location is asserted as the value it is and the mounted copy is the
 * only thing actually read.
 */
class ChapterRevisionDownloadControllerTest : ApplicationTest() {
    private fun context(
        revisionId: String = "1",
        range: String? = null,
        user: UserType = UserType.Admin(1),
    ): Context {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.attribute<UserType>("user") } returns user
        every { ctx.pathParam("revisionId") } returns revisionId
        every { ctx.header("range") } returns range
        return ctx
    }

    @Test
    fun `a request without an authenticated user is refused before the address is parsed`() {
        assertThrows(UnauthorizedException::class.java) {
            ChapterRevisionController.download.handle(context(user = UserType.Visitor))
        }
        // the check comes before the address is read, so an address that would otherwise be refused is
        // still answered as unauthorized for a caller who may not download anything at all
        assertThrows(UnauthorizedException::class.java) {
            ChapterRevisionController.download.handle(context(revisionId = "not-a-number", user = UserType.Visitor))
        }
    }

    @Test
    fun `an address that names no revision is not found rather than a bad request`() {
        listOf("not-a-number", "0", "-1", "").forEach { address ->
            assertThrows(NotFoundResponse::class.java) {
                ChapterRevisionController.download.handle(context(revisionId = address))
            }
        }
    }

    @Test
    fun `every outcome becomes the status the contract describes`() {
        val ctx = context()
        ChapterRevisionController.respond(ctx, 7, ChapterRevisionDeliveryResolution.NotFound, head = false)
        verify { ctx.status(HttpStatus.NOT_FOUND) }

        val unusable = context()
        ChapterRevisionController.respond(unusable, 7, ChapterRevisionDeliveryResolution.Unusable, head = false)
        verify { unusable.status(HttpStatus.CONFLICT) }

        val unavailable = context()
        ChapterRevisionController.respond(unavailable, 7, ChapterRevisionDeliveryResolution.Unavailable, head = false)
        verify { unavailable.status(HttpStatus.SERVICE_UNAVAILABLE) }

        val headOnly = context()
        ChapterRevisionController.respond(headOnly, 7, ChapterRevisionDeliveryResolution.MethodNotAllowed, head = true)
        verify { headOnly.status(HttpStatus.METHOD_NOT_ALLOWED) }
        verify { headOnly.header("allow", "GET") }
    }

    @Test
    fun `a direct location is only ever handed over as an uncacheable redirect`() {
        val location = "https://bucket.example/object?X-Amz-Signature=abc"
        val ctx = context()

        ChapterRevisionController.respond(ctx, 7, ChapterRevisionDeliveryResolution.Direct(location), head = false)

        verify { ctx.redirect(location, HttpStatus.TEMPORARY_REDIRECT) }
        verify { ctx.header("cache-control", "private, no-store") }
        verify { ctx.header("referrer-policy", "no-referrer") }
        // the location is a credential, so it may not also be written anywhere a response can keep
        verify(exactly = 0) { ctx.result(any<InputStream>()) }
        verify(exactly = 0) { ctx.status(any<HttpStatus>()) }
    }

    @Test
    fun `the whole mounted artifact is sent with its real length and a range header`(
        @TempDir directory: File,
    ) {
        val content = ByteArray(64) { it.toByte() }
        val file = File(directory, "revision.cbz").apply { writeBytes(content) }
        val ctx = context()

        ChapterRevisionController.respond(ctx, 7, ChapterRevisionDeliveryResolution.Local(file, 64L), false)

        verify { ctx.status(HttpStatus.OK) }
        verify { ctx.header("content-length", "64") }
        verify { ctx.header("accept-ranges", "bytes") }
        verify { ctx.header("content-disposition", "attachment; filename=\"revision-7.cbz\"") }
        verify { ctx.header("cache-control", "private, no-store") }

        val stream = slot<InputStream>()
        verify { ctx.result(capture(stream)) }
        assertArrayEquals(content, stream.captured.readBytes())
    }

    @Test
    fun `a byte range is answered with exactly that run of bytes`(
        @TempDir directory: File,
    ) {
        val content = ByteArray(64) { it.toByte() }
        val file = File(directory, "revision.cbz").apply { writeBytes(content) }
        val ctx = context(range = "bytes=10-19")

        ChapterRevisionController.respond(ctx, 7, ChapterRevisionDeliveryResolution.Local(file, 64L), false)

        verify { ctx.status(HttpStatus.PARTIAL_CONTENT) }
        verify { ctx.header("content-range", "bytes 10-19/64") }
        verify { ctx.header("content-length", "10") }
        verify { ctx.header("accept-ranges", "bytes") }

        val stream = slot<InputStream>()
        verify { ctx.result(capture(stream)) }
        assertArrayEquals(content.copyOfRange(10, 20), stream.captured.readBytes())
    }

    @Test
    fun `a range that cannot be satisfied is refused with the real length`(
        @TempDir directory: File,
    ) {
        val file = File(directory, "revision.cbz").apply { writeBytes(ByteArray(64)) }

        listOf("bytes=64-", "bytes=90-100", "bytes=10-9", "bytes=-0", "bytes=0-1,5-6", "bytes=abc", "bytes=-").forEach { range ->
            val ctx = context(range = range)

            ChapterRevisionController.respond(ctx, 7, ChapterRevisionDeliveryResolution.Local(file, 64L), false)

            verify { ctx.status(HttpStatus.RANGE_NOT_SATISFIABLE) }
            verify { ctx.header("content-range", "bytes */64") }
            verify(exactly = 0) { ctx.result(any<InputStream>()) }
        }
    }

    @Test
    fun `a HEAD reports the same metadata and sends no bytes`(
        @TempDir directory: File,
    ) {
        val file = File(directory, "revision.cbz").apply { writeBytes(ByteArray(64)) }
        val ctx = context(range = "bytes=0-9")

        ChapterRevisionController.respond(ctx, 7, ChapterRevisionDeliveryResolution.Local(file, 64L), head = true)

        verify { ctx.status(HttpStatus.PARTIAL_CONTENT) }
        verify { ctx.header("content-length", "10") }
        verify { ctx.header("content-range", "bytes 0-9/64") }
        verify(exactly = 0) { ctx.result(any<InputStream>()) }

        val whole = context()
        ChapterRevisionController.respond(whole, 7, ChapterRevisionDeliveryResolution.Local(file, 64L), head = true)
        verify { whole.header("content-length", "64") }
        verify(exactly = 0) { whole.result(any<InputStream>()) }
    }

    @Test
    fun `only a single bytes range is understood and anything else is served whole`() {
        assertEquals(CbzRangeRequest.Whole, parseCbzRange(null, 100L))
        assertEquals(CbzRangeRequest.Whole, parseCbzRange("   ", 100L))
        // a unit this server does not understand has to be ignored, so the whole object is served
        assertEquals(CbzRangeRequest.Whole, parseCbzRange("items=0-1", 100L))

        val closed = parseCbzRange("bytes=0-9", 100L) as CbzRangeRequest.Partial
        assertEquals(CbzByteRange(0L, 9L), closed.range)
        assertEquals(10L, closed.range.length)

        assertEquals(CbzByteRange(90L, 99L), partialOf("bytes=90-").range)
        assertEquals(CbzByteRange(1L, 2L), partialOf("bytes=0001-0002").range)
        // an end past the object is a satisfied request, a start past it is not
        assertEquals(CbzByteRange(0L, 99L), partialOf("bytes=0-1000").range)
        assertEquals(CbzRangeRequest.Unsatisfiable, parseCbzRange("bytes=100-", 100L))
        // a suffix range asks for the last bytes, or for everything when it asks for more than there is
        assertEquals(CbzByteRange(90L, 99L), partialOf("bytes=-10").range)
        assertEquals(CbzByteRange(0L, 99L), partialOf("bytes=-1000").range)
        // a number too large to be a byte offset is malformed rather than clamped
        assertEquals(CbzRangeRequest.Unsatisfiable, parseCbzRange("bytes=99999999999999999999999-", 100L))
        // an empty object has no byte to serve at all
        assertEquals(CbzRangeRequest.Unsatisfiable, parseCbzRange("bytes=0-", 0L))
        assertEquals(CbzRangeRequest.Unsatisfiable, parseCbzRange("bytes=-1", 0L))
    }

    private fun partialOf(header: String): CbzRangeRequest.Partial = parseCbzRange(header, 100L) as CbzRangeRequest.Partial

    @Test
    fun `a bounded stream stops at its range and closes the file it read`(
        @TempDir directory: File,
    ) {
        val content = ByteArray(16) { it.toByte() }
        val file = File(directory, "revision.cbz").apply { writeBytes(content) }

        val stream = BoundedFileStream(file, start = 4L, length = 5L)
        val read = ByteArray(32)
        var total = 0
        while (true) {
            val count = stream.read(read, total, read.size - total)
            if (count < 0) break
            total += count
        }

        assertArrayEquals(content.copyOfRange(4, 9), read.copyOfRange(0, total))
        // the range is exhausted, so the stream is at its end and reading again changes nothing
        assertEquals(-1, stream.read())
        // closing is idempotent, and the file is not held open once the range has been served: on
        // Windows an open handle would make this delete fail
        stream.close()
        stream.close()
        assertTrue(file.delete(), "the bounded stream kept the artifact open")
    }

    @Test
    fun `a bounded stream that is closed early releases the file`(
        @TempDir directory: File,
    ) {
        val file = File(directory, "revision.cbz").apply { writeBytes(ByteArray(16)) }

        val stream = BoundedFileStream(file, start = 0L, length = 16L)
        assertNotNull(stream.read())
        stream.close()

        assertTrue(file.delete())
    }
}
