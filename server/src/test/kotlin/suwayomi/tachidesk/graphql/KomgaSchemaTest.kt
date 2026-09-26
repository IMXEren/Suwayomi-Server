package suwayomi.tachidesk.graphql

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.server.GraphQLSchemaProvider
import suwayomi.tachidesk.graphql.types.SettingsType
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.settings.SettingsRegistry
import suwayomi.tachidesk.server.settings.SettingsValidator
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class KomgaSchemaTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }
    }

    @Test
    fun `exposes the Komga status query and the rescan mutations`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        assertEquals(
            "KomgaRescanStatusType",
            GraphQLTypeUtil.unwrapAll(schema.queryType.getFieldDefinition("komgaRescanStatus").type).name,
        )
        assertNotNull(schema.mutationType.getFieldDefinition("requestKomgaRescan"))
        assertNotNull(schema.mutationType.getFieldDefinition("retryKomgaRescan"))
    }

    @Test
    fun `the status type never exposes the configured target`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }
        val status = schema.getType("KomgaRescanStatusType") as GraphQLObjectType
        val fields = status.fieldDefinitions.map { it.name }.toSet()

        assertTrue(
            fields.containsAll(
                listOf(
                    "configured",
                    "configurationError",
                    "state",
                    "generation",
                    "attempts",
                    "requestedAt",
                    "notBeforeAt",
                    "lastAttemptAt",
                    "lastCompletedAt",
                    "lastError",
                ),
            ),
            "the status fields are the audit surface: $fields",
        )
        val leaked =
            fields.filter { field ->
                field.contains("url", ignoreCase = true) ||
                    field.contains("key", ignoreCase = true) ||
                    field.contains("library", ignoreCase = true)
            }
        assertTrue(leaked.isEmpty(), "the status must not expose the base URL, library id or API key: $leaked")
    }

    @Test
    fun `only the Komga api key is a write-only secret`() {
        val secrets = SettingsRegistry.getAll().filterValues { it.secret }.keys
        assertEquals(setOf("komgaApiKey"), secrets, "a setting must not become write-only silently")
    }

    @Test
    fun `the settings output type makes the secret nullable so it can never carry a value`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }
        val settingsType = schema.getType("SettingsType") as GraphQLObjectType
        assertTrue(
            GraphQLTypeUtil.isNullable(settingsType.getFieldDefinition("komgaApiKey").type),
            "the secret is absent from every serialized settings object",
        )
    }

    @Test
    fun `setSettings can still accept a new api key while the secret stays write-only`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }
        val setSettings = schema.mutationType.getFieldDefinition("setSettings")
        val setSettingsInput =
            GraphQLTypeUtil.unwrapAll(setSettings.getArgument("input").type)
                as GraphQLInputObjectType
        val settingsField = setSettingsInput.fields.first { it.name == "settings" }
        val partialInput = GraphQLTypeUtil.unwrapAll(settingsField.type) as GraphQLInputObjectType
        val keyField = partialInput.fields.firstOrNull { it.name == "komgaApiKey" }

        assertNotNull(keyField, "the partial settings input must accept a new api key")
        assertTrue(
            GraphQLTypeUtil.isNullable(keyField!!.type),
            "the write-only input is optional like every other partial field",
        )
    }

    @Test
    fun `a configured api key is stored but never read back into a serializable settings object`() {
        val original = serverConfig.komgaApiKey.value
        val key = "unique-komga-key-${UUID.randomUUID()}"
        try {
            assertNull(SettingsValidator.validate("komgaApiKey", key), "a unique key is a valid setting value")

            serverConfig.komgaApiKey.value = key
            assertEquals(key, serverConfig.komgaApiKey.value, "the runtime still reads the true value")
            assertNull(SettingsType(serverConfig).komgaApiKey, "SettingsType(config) must not echo the key")
            assertNull(SettingsType().komgaApiKey, "the query and setSettings payload build the same masked object")
        } finally {
            serverConfig.komgaApiKey.value = original
        }
    }
}
