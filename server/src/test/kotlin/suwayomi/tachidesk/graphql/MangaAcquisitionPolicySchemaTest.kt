package suwayomi.tachidesk.graphql

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.server.GraphQLSchemaProvider
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.test.ApplicationTest

class MangaAcquisitionPolicySchemaTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            ApplicationTest.testingSetup()
        }
    }

    @Test
    fun `exposes the acquisition policy as a readable enum and an updatable field`() {
        val schema = runBlocking { GraphQLSchemaProvider.getSchema() }

        val policyEnum = schema.getType("MangaAcquisitionPolicy") as GraphQLEnumType
        assertEquals(
            MangaAcquisitionPolicy.entries.map { it.name }.toSet(),
            policyEnum.values.map { it.name }.toSet(),
        )

        val mangaType = schema.getType("MangaType") as GraphQLObjectType
        assertEquals(
            "MangaAcquisitionPolicy",
            GraphQLTypeUtil.unwrapAll(mangaType.getFieldDefinition("acquisitionPolicy").type).name,
        )

        val updatePatch = schema.getType("UpdateMangaPatchInput") as GraphQLInputObjectType
        assertEquals(
            "MangaAcquisitionPolicy",
            GraphQLTypeUtil.unwrapAll(updatePatch.getFieldDefinition("acquisitionPolicy").type).name,
        )
    }
}
