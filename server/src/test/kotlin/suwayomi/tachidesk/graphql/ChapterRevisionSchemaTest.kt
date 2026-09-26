package suwayomi.tachidesk.graphql

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.server.GraphQLSchemaProvider
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRetentionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.test.ApplicationTest

class ChapterRevisionSchemaTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }
    }

    @Test
    fun `exposes the revision lifecycle and independent state enums`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        val disposition = schema.getType("ChapterRevisionDisposition") as GraphQLEnumType
        assertEquals(
            ChapterRevisionDisposition.entries.map { it.name }.toSet(),
            disposition.values.map { it.name }.toSet(),
        )

        val acquisition = schema.getType("ChapterAcquisitionState") as GraphQLEnumType
        assertEquals(
            ChapterAcquisitionState.entries.map { it.name }.toSet(),
            acquisition.values.map { it.name }.toSet(),
        )
        assertTrue(acquisition.values.map { it.name }.containsAll(listOf("VALIDATING", "VALIDATION_FAILED")))

        val archive = schema.getType("ChapterArchiveState") as GraphQLEnumType
        assertEquals(
            ChapterArchiveState.entries.map { it.name }.toSet(),
            archive.values.map { it.name }.toSet(),
        )

        val publication = schema.getType("ChapterPublicationState") as GraphQLEnumType
        assertEquals(
            ChapterPublicationState.entries.map { it.name }.toSet(),
            publication.values.map { it.name }.toSet(),
        )

        val retention = schema.getType("ChapterRetentionState") as GraphQLEnumType
        assertEquals(
            ChapterRetentionState.entries.map { it.name }.toSet(),
            retention.values.map { it.name }.toSet(),
        )
        assertTrue(retention.values.map { it.name }.containsAll(listOf("PRUNE_QUEUED", "REMOTE_DELETE_PENDING", "PRUNED")))

        val revisionType = schema.getType("ChapterRevisionType") as GraphQLObjectType
        val expectedEnumFields =
            mapOf(
                "disposition" to "ChapterRevisionDisposition",
                "acquisitionState" to "ChapterAcquisitionState",
                "archiveState" to "ChapterArchiveState",
                "publicationState" to "ChapterPublicationState",
                "retentionState" to "ChapterRetentionState",
            )
        expectedEnumFields.forEach { (field, enumName) ->
            assertEquals(
                enumName,
                GraphQLTypeUtil.unwrapAll(revisionType.getFieldDefinition(field).type).name,
            )
        }

        // nullable source-row relationships must be represented honestly
        assertTrue(GraphQLTypeUtil.isNullable(revisionType.getFieldDefinition("chapter").type))
        assertTrue(GraphQLTypeUtil.isNullable(revisionType.getFieldDefinition("manga").type))
        assertTrue(GraphQLTypeUtil.isNullable(revisionType.getFieldDefinition("chapterId").type))
        assertTrue(GraphQLTypeUtil.isNullable(revisionType.getFieldDefinition("mangaId").type))

        // publication and retention audit fields are nullable until their transition ran, and the
        // active marker is exposed as a boolean instead of as the raw nullable identity
        listOf(
            "archiveLastError",
            "archiveLastAttemptAt",
            "archiveCbzPath",
            "archiveManifestPath",
            "archiveCbzHash",
            "archiveCbzSize",
            "archivedAt",
            "acceptedAt",
            "activatedAt",
            "supersededAt",
            "publicationLastError",
            "publicationLastAttemptAt",
            "activeCbzPath",
            "activeCbzHash",
            "activeCbzSize",
            "publishedAt",
            "retentionLastError",
            "retentionLastAttemptAt",
            "retentionQueuedAt",
            "retentionNextVerificationAt",
            "deletedAt",
            "prunedAt",
        ).forEach { field ->
            assertTrue(
                GraphQLTypeUtil.isNullable(revisionType.getFieldDefinition(field).type),
                "$field must be nullable until its transition ran",
            )
        }

        assertNotNull(revisionType.getFieldDefinition("retentionAttempts"), "the retention attempt count is audited")
        assertEquals(
            "Boolean",
            GraphQLTypeUtil.unwrapAll(revisionType.getFieldDefinition("isActiveRevision").type).name,
            "the active marker is exposed as a boolean rather than as a nullable identity",
        )
        assertNull(
            revisionType.getFieldDefinition("activeChapterKey"),
            "the raw active identity is an implementation detail of the unique index",
        )
    }

    @Test
    fun `exposes the revision query and mutation surface`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        assertEquals(
            "ChapterRevisionType",
            GraphQLTypeUtil.unwrapAll(schema.queryType.getFieldDefinition("chapterRevision").type).name,
        )
        listOf("chapterRevisions", "approvalBacklog", "queuedBacklog").forEach { field ->
            assertEquals(
                "ChapterRevisionNodeList",
                GraphQLTypeUtil.unwrapAll(schema.queryType.getFieldDefinition(field).type).name,
            )
        }

        assertNotNull(schema.mutationType.getFieldDefinition("approveChapterRevisions"))
        assertNotNull(schema.mutationType.getFieldDefinition("rejectChapterRevisions"))
        assertNotNull(schema.mutationType.getFieldDefinition("retryChapterRevisions"))
        assertNotNull(schema.mutationType.getFieldDefinition("retryChapterRevisionArchives"))
        assertNotNull(schema.mutationType.getFieldDefinition("retryChapterRevisionPublications"))
        assertNotNull(schema.mutationType.getFieldDefinition("retryChapterRevisionPrunings"))

        // the review decisions are a semantically separate surface from the acquisition approval above
        listOf(
            "acceptChapterRevisionCandidates",
            "keepCurrentChapterRevisions",
            "keepBothChapterRevisions",
            "rejectChapterRevisionCandidates",
        ).forEach { field ->
            assertNotNull(schema.mutationType.getFieldDefinition(field), "$field must be registered")
        }

        // the accepted-lifecycle and publication read surface is registered too
        assertEquals(
            "ChapterRevisionType",
            GraphQLTypeUtil.unwrapAll(schema.queryType.getFieldDefinition("activeChapterRevision").type).name,
        )
        listOf("chapterRevisionHistory", "publicationBacklog").forEach { field ->
            assertEquals(
                "ChapterRevisionNodeList",
                GraphQLTypeUtil.unwrapAll(schema.queryType.getFieldDefinition(field).type).name,
            )
        }

        val pruningBacklog = schema.queryType.getFieldDefinition("pruningBacklog")
        assertEquals("ChapterRevisionNodeList", GraphQLTypeUtil.unwrapAll(pruningBacklog.type).name)
        assertTrue(
            pruningBacklog.arguments.map { it.name }.containsAll(listOf("order", "before", "after", "first", "last", "offset")),
            "the pruning backlog must use the shared cursor pagination machinery",
        )
    }

    @Test
    fun `the revision listing exposes the independent state dimensions as arguments`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        val field = schema.queryType.getFieldDefinition("chapterRevisions")
        val arguments = field.arguments.associateBy { it.name }

        // the three state dimensions of the table are separately filterable, so a queue inspector can
        // select e.g. a pending archive while its publication is still outstanding; all of them are
        // optional and therefore nullable
        mapOf(
            "archiveState" to "ChapterArchiveState",
            "publicationState" to "ChapterPublicationState",
            "retentionState" to "ChapterRetentionState",
        ).forEach { (argument, enumName) ->
            val definition = requireNotNull(arguments[argument]) { "$argument must be filterable" }
            assertTrue(GraphQLTypeUtil.isNullable(definition.type), "$argument must be optional")
            assertEquals(enumName, GraphQLTypeUtil.unwrapAll(definition.type).name)
        }

        // the pre-existing filters and the pagination machinery are unchanged
        assertTrue(
            arguments.keys.containsAll(
                listOf(
                    "chapterId",
                    "chapterKey",
                    "disposition",
                    "acquisitionState",
                    "discoveryReason",
                    "signalConfidence",
                    "order",
                    "before",
                    "after",
                    "first",
                    "last",
                    "offset",
                ),
            ),
        )
    }

    @Test
    fun `exposes the per-series retention override on the manga type`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        val mangaType = schema.getType("MangaType") as GraphQLObjectType
        assertTrue(
            GraphQLTypeUtil.isNullable(mangaType.getFieldDefinition("acceptedRevisionRetention").type),
            "the per-series override is nullable because null inherits the global default",
        )
        assertEquals(
            "Int",
            GraphQLTypeUtil.unwrapAll(mangaType.getFieldDefinition("effectiveAcceptedRevisionRetention").type).name,
            "the effective value is always concrete",
        )
    }

    @Test
    fun `exposes the chapter identity, the plural rejection mutation and paginated history`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        val revisionType = schema.getType("ChapterRevisionType") as GraphQLObjectType
        val chapterKeyField = revisionType.getFieldDefinition("chapterKey")
        assertNotNull(chapterKeyField, "the immutable chapter identity must be readable")
        assertFalse(GraphQLTypeUtil.isNullable(chapterKeyField.type), "the chapter identity is never null")
        assertEquals("String", GraphQLTypeUtil.unwrapAll(chapterKeyField.type).name)

        // the singular name was unused and misleading: the mutation takes a list of candidates
        assertNotNull(schema.mutationType.getFieldDefinition("rejectChapterRevisionCandidates"))
        assertNull(schema.mutationType.getFieldDefinition("rejectChapterRevisionCandidate"))

        val history = schema.queryType.getFieldDefinition("chapterRevisionHistory")
        assertEquals("ChapterRevisionNodeList", GraphQLTypeUtil.unwrapAll(history.type).name)
        val historyArguments = history.arguments.map { it.name }
        assertTrue(
            historyArguments.containsAll(listOf("chapterKey", "order", "before", "after", "first", "last", "offset")),
            "the identity history must use the shared cursor pagination machinery",
        )
    }

    @Test
    fun `exposes the visual comparison surface without any server side location`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        listOf(
            "chapterRevisionComparison",
            "chapterRevisionComparisonPages",
            "chapterRevisionVisualAnalysisStatus",
            "visualAnalysisBacklog",
        ).forEach { field ->
            assertNotNull(schema.queryType.getFieldDefinition(field), "$field must be registered")
        }

        val paging = schema.queryType.getFieldDefinition("chapterRevisionComparisonPages")
        assertEquals(
            "ChapterRevisionComparisonPageNodeList",
            GraphQLTypeUtil.unwrapAll(paging.type).name,
        )
        assertTrue(
            paging.arguments.map { it.name }.containsAll(listOf("revisionId", "after", "first")),
            "the alignment must page with the shared cursor machinery",
        )

        assertEquals(
            "ChapterRevisionComparisonType",
            GraphQLTypeUtil.unwrapAll(schema.queryType.getFieldDefinition("chapterRevisionComparison").type).name,
        )

        assertNotNull(schema.mutationType.getFieldDefinition("retryChapterRevisionVisualAnalyses"))
        assertNotNull(schema.getType("ChapterVisualAnalysisState"), "the analysis state enum must be registered")

        // the row is what a review UI renders, and it reports only whether each side has a preview: the
        // thumbnail location and its digest name a path inside the server's staging root, and the
        // content digests let a client decide for itself whether two pages are the same page
        val pageType = schema.getType("ChapterRevisionComparisonPageType") as GraphQLObjectType
        val exposed = pageType.fieldDefinitions.map { it.name }
        assertFalse(
            exposed.any { it.contains("RelativePath") || it.endsWith("Path") },
            "no field may expose a stored preview location, got $exposed",
        )
        assertFalse(
            exposed.any { it.contains("Hash") || it.contains("Digest") },
            "the page fingerprints stay internal, got $exposed",
        )
        // the only page and thumbnail fields are the aligned indices and the opaque review addresses, so
        // a client is told where to ask without being told where anything lives
        assertEquals(
            setOf(
                "baselinePageIndex",
                "candidatePageIndex",
                "baselinePageUrl",
                "candidatePageUrl",
                "baselineThumbnailUrl",
                "candidateThumbnailUrl",
            ),
            exposed.filter { it.contains("Thumbnail") || it.contains("Page") }.toSet(),
            "got $exposed",
        )
        listOf(
            "baselineThumbnailUrl",
            "candidateThumbnailUrl",
            "baselinePageUrl",
            "candidatePageUrl",
        ).forEach { field ->
            val definition = requireNotNull(pageType.getFieldDefinition(field)) { "$field must be exposed" }
            assertTrue(GraphQLTypeUtil.isNullable(definition.type), "$field is null when that side has nothing to show")
            assertEquals("String", GraphQLTypeUtil.unwrapAll(definition.type).name)
        }
        listOf("baselinePreviewAvailable", "candidatePreviewAvailable").forEach { field ->
            val definition = requireNotNull(pageType.getFieldDefinition(field)) { "$field must be exposed" }
            assertEquals("Boolean", GraphQLTypeUtil.unwrapAll(definition.type).name)
            assertFalse(GraphQLTypeUtil.isNullable(definition.type), "whether a preview exists is always known")
        }

        // what a reviewer does compare stays readable: the alignment, the indices and the measured distance
        listOf(
            "ordinal",
            "baselinePageIndex",
            "candidatePageIndex",
            "state",
            "hammingDistance",
        ).forEach { field ->
            assertNotNull(pageType.getFieldDefinition(field), "$field must stay readable")
        }
    }

    @Test
    fun `the order-by surface excludes the nullable approvedAt`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        val orderBy = schema.getType("ChapterRevisionOrderBy") as GraphQLEnumType
        val names = orderBy.values.map { it.name }

        assertTrue(
            names.containsAll(listOf("ID", "DISCOVERED_AT", "UPDATED_AT")),
            "the non-null order keys remain available",
        )
        assertFalse(
            names.contains("APPROVED_AT"),
            "nullable approvedAt must not be an order/cursor key with a null region and id tie-break",
        )
    }

    @Test
    fun `exposes the sweep surface, so the schema builds with the sweep types`() {
        // building the schema is itself the assertion: a sweep type that could not be wired would
        // have made this call throw instead of returning a schema
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        listOf(
            "chapterRevisionSweepSessions",
            "chapterRevisionSweepSession",
            "chapterRevisionSweepActiveSession",
            "chapterRevisionSweepLatestSession",
            "chapterRevisionSweepSchedule",
            "chapterRevisionSweepItems",
            "chapterRevisionSweepProgress",
        ).forEach { field ->
            assertNotNull(schema.queryType.getFieldDefinition(field), "$field must be registered")
        }

        listOf(
            "chapterRevisionSweepSession",
            "chapterRevisionSweepActiveSession",
            "chapterRevisionSweepLatestSession",
        ).forEach { field ->
            assertEquals(
                "ChapterRevisionSweepSessionType",
                GraphQLTypeUtil.unwrapAll(schema.queryType.getFieldDefinition(field).type).name,
            )
        }

        assertNotNull(schema.getType("ChapterRevisionSweepSessionType"), "the session type must be registered")
        assertNotNull(schema.getType("ChapterRevisionSweepProgressType"), "the progress type must be registered")
        assertNotNull(schema.getType("ChapterRevisionSweepScheduleType"), "the schedule type must be registered")

        // the control surface is registered under the sweep prefix; the per-method RequireAuth
        // annotations are asserted where they live, on the Kotlin entry points
        assertTrue(
            schema.mutationType.fieldDefinitions.count { it.name.contains("ChapterRevisionSweep") } >= 5,
            "expected the full sweep mutation surface, got ${schema.mutationType.fieldDefinitions.map { it.name }}",
        )
    }
}
