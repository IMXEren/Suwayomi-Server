package suwayomi.tachidesk.graphql

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import graphql.schema.GraphQLObjectType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.mutations.CanonicalIdentityMutation
import suwayomi.tachidesk.graphql.queries.CanonicalIdentityQuery
import suwayomi.tachidesk.graphql.server.GraphQLSchemaProvider
import suwayomi.tachidesk.test.ApplicationTest

/**
 * The API contract of the canonical identity control plane.
 *
 * Three things are asserted rather than described: every field is authenticated, no cursor is backed
 * by a column that cannot carry one, and the surface never exposes a path, a hash or a credential.
 */
class CanonicalIdentitySchemaTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }

        private val queryFields =
            listOf(
                "canonicalWorks",
                "canonicalWork",
                "canonicalWorkForManga",
                "canonicalBindingForManga",
                "canonicalBindingsForWork",
                "canonicalIdentityStatus",
            )

        private val mutationFields =
            listOf(
                "createCanonicalWork",
                "updateCanonicalWork",
                "deleteCanonicalWork",
                "attachMangaToCanonicalWork",
                "detachCanonicalBinding",
                "changeCanonicalBinding",
                "promoteCanonicalBinding",
                "failoverCanonicalWork",
                "exportCanonicalIdentity",
                "importCanonicalIdentity",
            )
    }

    @Test
    fun `exposes the canonical identity queries and mutations`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        queryFields.forEach { field ->
            assertNotNull(schema.queryType.getFieldDefinition(field), "missing query field $field")
        }
        mutationFields.forEach { field ->
            assertNotNull(schema.mutationType.getFieldDefinition(field), "missing mutation field $field")
        }
    }

    /**
     * The directive wiring is driven by the annotation itself, so the annotation is what is asserted -
     * the same way the other surfaces in this repository assert it, on the Kotlin entry points.
     */
    @Test
    fun `every canonical identity field requires authentication`() {
        val authenticatedQueryMethods =
            CanonicalIdentityQuery::class.java.declaredMethods
                .filter { it.isAnnotationPresent(RequireAuth::class.java) }
                .map { it.name }
                .toSet()
        val authenticatedMutationMethods =
            CanonicalIdentityMutation::class.java.declaredMethods
                .filter { it.isAnnotationPresent(RequireAuth::class.java) }
                .map { it.name }
                .toSet()

        queryFields.forEach { method ->
            assertTrue(method in authenticatedQueryMethods, "$method must require authentication")
        }
        mutationFields.forEach { method ->
            assertTrue(method in authenticatedMutationMethods, "$method must require authentication")
        }
    }

    /**
     * A cursor has to be backed by a non-null, numeric, stable sort key.
     *
     * A textual key needs a tie-break the cursor encoding here cannot express, so the work listing
     * deliberately filters by title instead of ordering by it. This pins that decision down.
     */
    @Test
    fun `the work listing only orders by keys a cursor can carry`() {
        assertEquals(
            listOf("ID", "CREATED_AT", "UPDATED_AT"),
            CanonicalIdentityQuery.CanonicalWorkOrderBy.entries.map { it.name },
            "a nullable or textual sort key would make the cursor incorrect",
        )
    }

    @Test
    fun `a manga exposes its canonical binding and its discovery eligibility`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }
        val mangaType = schema.getType("MangaType") as GraphQLObjectType
        val fields = mangaType.fieldDefinitions.map { it.name }.toSet()

        assertTrue(fields.contains("canonicalBinding"), "the binding is the whole point of the control plane: $fields")
        assertTrue(
            fields.contains("canonicalAcquisitionEligible"),
            "a client must be able to see whether a source is still fed to discovery: $fields",
        )
    }

    /**
     * The duplicate policy is advisory, and the API says so without the client reading documentation.
     */
    @Test
    fun `the duplicate policy is reported as unapplied`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        listOf("CanonicalWorkType", "CanonicalIdentityStatusType").forEach { typeName ->
            val type = schema.getType(typeName) as GraphQLObjectType
            assertNotNull(
                type.fieldDefinitions.firstOrNull { it.name == "duplicatePolicyApplied" },
                "$typeName must state that the recorded policy is not applied",
            )
        }
    }

    /**
     * The control plane describes series and sources, never storage. A path or a digest in this surface
     * would be an internal location leaking through an authenticated API.
     *
     * `workKey` is the one field name allowed to contain a forbidden substring: it is the public,
     * opaque identity the API addresses a work by, not a credential.
     */
    @Test
    fun `the canonical types expose no paths hashes or credentials`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        val publicIdentityField = "workKey"

        val leaked =
            listOf("CanonicalWorkType", "CanonicalSourceBindingType", "CanonicalIdentityStatusType").flatMap { typeName ->
                val type = schema.getType(typeName) as GraphQLObjectType
                type.fieldDefinitions
                    .map { it.name }
                    .filterNot { it == publicIdentityField }
                    .filter { field ->
                        listOf("path", "hash", "sha", "secret", "key", "token").any { field.contains(it, ignoreCase = true) }
                    }.map { "$typeName.$it" }
            }

        assertTrue(
            leaked.isEmpty(),
            "the surface must not carry a location, a digest or a credential; workKey is an identity, not a secret: $leaked",
        )

        // the exclusion above must not be able to hide a rename of that identity away
        val workType = schema.getType("CanonicalWorkType") as GraphQLObjectType
        assertTrue(
            workType.fieldDefinitions.any { it.name == publicIdentityField },
            "the work key is the public identity the API addresses a work by",
        )
    }

    @Test
    fun `the import payload is a document and the import result reports unresolved sources`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        val importInput = schema.getType("ImportCanonicalIdentityInput") as graphql.schema.GraphQLInputObjectType
        assertTrue(
            importInput.fieldDefinitions.map { it.name }.contains("payload"),
            "the export is round-tripped as a document",
        )

        val importType = schema.getType("CanonicalIdentityImportType") as GraphQLObjectType
        val importFields = importType.fieldDefinitions.map { it.name }.toSet()
        assertTrue(
            importFields.contains("bindingsUnresolved"),
            "an unresolved source is imported detached, so the result has to report it: $importFields",
        )
    }
}
