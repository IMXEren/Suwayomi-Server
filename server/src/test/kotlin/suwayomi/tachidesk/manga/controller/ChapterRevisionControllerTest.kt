package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.ChapterRevisionStaging
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonState
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisState
import suwayomi.tachidesk.manga.model.table.ChapterRevisionComparisonPageTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionComparisonTable
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.user.UnauthorizedException
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The request surface of the comparison previews.
 *
 * What is asserted here is only what happens before any media is resolved: who may ask at all, which
 * addresses are refused as malformed rather than as missing, and that a well formed address for a
 * revision that does not exist stays a missing resource.
 */
class ChapterRevisionControllerTest : ApplicationTest() {
    /** a revision id that is valid and, by construction, not one any test creates */
    private val absentRevisionId = Int.MAX_VALUE.toString()

    private fun context(
        revisionId: String = absentRevisionId,
        ordinal: String = "0",
        side: String = "baseline",
        user: UserType = UserType.Admin(1),
    ): Context {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.attribute<UserType>("user") } returns user
        every { ctx.pathParam("revisionId") } returns revisionId
        every { ctx.pathParam("ordinal") } returns ordinal
        every { ctx.pathParam("side") } returns side
        return ctx
    }

    @Test
    fun `a request without an authenticated user is refused`() {
        val ctx = context(user = UserType.Visitor)

        assertThrows(UnauthorizedException::class.java) {
            ChapterRevisionController.comparisonThumbnail.handle(ctx)
        }
        assertThrows(UnauthorizedException::class.java) {
            ChapterRevisionController.comparisonPage.handle(ctx)
        }
        // the check comes before the address is parsed and before anything is resolved: an address that
        // would otherwise be refused is still answered as unauthorized for a caller who may not ask at all
        assertThrows(UnauthorizedException::class.java) {
            ChapterRevisionController.comparisonPage.handle(context(revisionId = "not-a-number", user = UserType.Visitor))
        }
    }

    @Test
    fun `a malformed address is a bad request rather than a missing resource`() {
        val malformed =
            listOf(
                context(revisionId = "not-a-number"),
                context(revisionId = "0"),
                context(revisionId = "-1"),
                context(ordinal = "not-a-number"),
                context(ordinal = "-1"),
                context(side = ""),
                context(side = "sideways"),
            )

        malformed.forEach { ctx ->
            assertThrows(BadRequestResponse::class.java) {
                ChapterRevisionController.comparisonThumbnail.handle(ctx)
            }
            assertThrows(BadRequestResponse::class.java) {
                ChapterRevisionController.comparisonPage.handle(ctx)
            }
        }
    }

    @Test
    fun `a missing resource and a malformed address are never confused with each other`() {
        // the two are told apart by the address alone, so a typo cannot look like a deletion
        assertThrows(NotFoundResponse::class.java) {
            ChapterRevisionController.comparisonThumbnail.handle(context(side = "CANDIDATE"))
        }
        assertThrows(BadRequestResponse::class.java) {
            ChapterRevisionController.comparisonThumbnail.handle(context(side = "CANDIDATE "))
        }
    }

    @Test
    fun `a served page is sent inline as an uncacheable image of a detected type`() {
        val chapterKey = "a".repeat(64)
        val candidateKey = "b".repeat(64)
        val stagingRoot = File(Injekt.get<ApplicationDirs>().archiveStagingRoot)
        val relativeDirectory = ChapterRevisionStaging.relativeDirectory(candidateKey)

        // the page a comparison recorded as the candidate's first one
        val directory = File(stagingRoot, relativeDirectory)
        directory.mkdirs()
        val page = File(directory, "00001.png")
        BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB).also { image ->
            for (x in 0 until 4) {
                for (y in 0 until 4) {
                    image.setRGB(x, y, 0x00FF00)
                }
            }
            ImageIO.write(image, "png", page)
        }

        val revisionId =
            transaction {
                ChapterRevisionTable.insert {
                    it[ChapterRevisionTable.candidateKey] = candidateKey
                    it[ChapterRevisionTable.chapterKey] = chapterKey
                    it[sourceChapterUrl] = "https://example.invalid/$candidateKey"
                    it[name] = "chapter"
                    it[discoveredAt] = 1
                    it[updatedAt] = 1
                    it[acquisitionState] = ChapterAcquisitionState.COMPLETE.name
                    it[archiveState] = ChapterArchiveState.NOT_COMMITTED.name
                    it[comparisonState] = ChapterRevisionComparisonState.CONTENT_CHANGED.name
                    it[visualAnalysisState] = ChapterVisualAnalysisState.COMPLETE.name
                    it[visualAnalysisAttempts] = 0
                    it[ChapterRevisionTable.candidatePath] = relativeDirectory
                } get ChapterRevisionTable.id
            }.value

        val comparisonId =
            transaction {
                ChapterRevisionComparisonTable.insert {
                    it[revision] = revisionId
                    it[baselineRevision] = null
                    it[baselinePageCount] = 1
                    it[candidatePageCount] = 1
                    it[exactCount] = 0
                    it[visuallyEquivalentCount] = 0
                    it[modifiedCount] = 0
                    it[addedCount] = 1
                    it[removedCount] = 0
                    it[alignedCount] = 1
                    it[hammingThreshold] = 2
                    it[algorithmVersion] = "dhash128-v1"
                    it[allPagesVisuallyEquivalent] = false
                    it[hasLimitations] = false
                    it[limitations] = null
                    it[createdAt] = 10
                    it[updatedAt] = 10
                } get ChapterRevisionComparisonTable.id
            }.value

        transaction {
            ChapterRevisionComparisonPageTable.insert {
                it[comparison] = comparisonId
                it[revision] = revisionId
                it[ordinal] = 0
                it[candidatePageIndex] = 0
                it[createdAt] = 10
            }
        }

        val ctx = context(revisionId = revisionId.toString(), side = "candidate")

        ChapterRevisionController.comparisonPage.handle(ctx)

        verify { ctx.header("content-type", "image/png") }
        verify { ctx.header("cache-control", "private, no-store") }
        verify { ctx.header("content-disposition", "inline") }
        verify { ctx.header("x-content-type-options", "nosniff") }
        verify { ctx.result(any<java.io.InputStream>()) }
        // a page read out of an archive has no content length this server measured, and a staged one is
        // served without one too, so the response never claims a length at all
        verify(exactly = 0) { ctx.header("content-length", any<String>()) }
    }

    @AfterEach
    fun cleanup() {
        clearTables(ChapterRevisionComparisonPageTable, ChapterRevisionComparisonTable, ChapterRevisionTable)
    }
}
