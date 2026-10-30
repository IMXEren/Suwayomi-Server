package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingRole

/**
 * Compatibility of the sidecar manifest across schema versions.
 *
 * The archive is meant to outlive the server that wrote it, so adding the canonical identity audit in
 * schema version 5 must be a pure addition: a version 2, 3 or 4 manifest has to decode exactly as it
 * was written, with no canonical identity at all.
 */
class CanonicalIdentityManifestTest {
    private val candidateKey = "a".repeat(64)

    /** The fields every version of the manifest has always carried. */
    private fun manifestJson(
        version: Int,
        canonicalIdentity: String = "",
    ): String =
        """
        {
          "schemaVersion": $version,
          "revisionId": 7,
          "candidateKey": "$candidateKey",
          "sourceChapterUrl": "https://example.invalid/chapter/1",
          "chapterNumber": 1.0,
          "chapterTitle": "chapter",
          "memo": {},
          "discoveredAt": 10,
          "archivedAt": 20,
          "pageCount": 0,
          "pages": [],
          "archiveContentHash": "deadbeef",
          "archiveSize": 3$canonicalIdentity
        }
        """.trimIndent()

    @Test
    fun `the schema version advances to five for the canonical identity audit`() {
        assertEquals(5, ChapterRevisionArchiveManifestCodec.SCHEMA_VERSION)
    }

    @Test
    fun `manifests written before schema version five decode with no canonical identity`() {
        listOf(2, 3, 4).forEach { version ->
            val decoded = ChapterRevisionArchiveManifestCodec.decode(manifestJson(version).toByteArray())

            assertEquals(version, decoded.schemaVersion, "an older manifest keeps its own version")
            assertNull(decoded.canonicalIdentity, "a version $version manifest has no canonical identity to lose")
            assertEquals(candidateKey, decoded.candidateKey)
        }
    }

    @Test
    fun `a schema version five manifest round trips the binding snapshot`() {
        val encoded =
            ChapterRevisionArchiveManifestCodec.encode(
                ChapterRevisionArchiveManifestCodec
                    .decode(
                        manifestJson(
                            version = 5,
                            canonicalIdentity =
                                ""","canonicalIdentity": {"workKey": "work-key", "bindingRole": "FALLBACK", "bindingPriority": 2, "bindingPrimary": false, "bindingSourceId": 7, "bindingMangaUrl": "https://example.invalid/manga/1"}""",
                        ).toByteArray(),
                    ),
            )

        val decoded = ChapterRevisionArchiveManifestCodec.decode(encoded)
        val identity = decoded.canonicalIdentity
        assertNotNull(identity, "the snapshot survives a re-encode")
        assertEquals("work-key", identity!!.workKey)
        assertEquals(CanonicalBindingRole.FALLBACK, identity.bindingRole)
        assertEquals(2, identity.bindingPriority, "the priority is what makes the fallback order readable")
        assertEquals(false, identity.bindingPrimary)
        assertEquals(7L, identity.bindingSourceId)
        assertEquals("https://example.invalid/manga/1", identity.bindingMangaUrl)

        // determinism: the same manifest always serializes to the same bytes
        assertEquals(
            encoded.toString(Charsets.UTF_8),
            ChapterRevisionArchiveManifestCodec.encode(decoded).toString(Charsets.UTF_8),
        )
    }

    /**
     * A canonical identity block without a role is not a legacy manifest, it is a broken one: the role
     * is what says whether the source fed discovery. Only the whole block is optional.
     */
    @Test
    fun `an incomplete canonical identity block is refused rather than defaulted`() {
        assertThrows(Exception::class.java) {
            ChapterRevisionArchiveManifestCodec.decode(
                manifestJson(
                    version = 5,
                    canonicalIdentity = ""","canonicalIdentity": {"workKey": "work-key", "bindingPriority": 2, "bindingPrimary": false}""",
                ).toByteArray(),
            )
        }
    }

    @Test
    fun `an unbound revision still encodes an explicit null rather than omitting the field`() {
        val manifest =
            ChapterRevisionArchiveManifestCodec
                .decode(manifestJson(version = 5).toByteArray())
                .copy(candidateKey = candidateKey, memo = JsonObject(emptyMap()))

        val encoded = ChapterRevisionArchiveManifestCodec.encode(manifest).toString(Charsets.UTF_8)

        assertNull(ChapterRevisionArchiveManifestCodec.decode(encoded.toByteArray()).canonicalIdentity)
        assertTrue(
            encoded.contains("canonicalIdentity"),
            "canonicalIdentity is written explicitly so the field is part of the schema, not an omission",
        )
        assertEquals(5, ChapterRevisionArchiveManifestCodec.decode(encoded.toByteArray()).schemaVersion)
    }
}
