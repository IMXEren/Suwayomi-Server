package suwayomi.tachidesk.graphql.directives

import com.expediagroup.graphql.generator.directives.KotlinFieldDirectiveEnvironment
import com.expediagroup.graphql.generator.directives.KotlinSchemaDirectiveWiring
import graphql.schema.DataFetcher
import graphql.schema.GraphQLFieldDefinition
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.requireArchiveOwner
import suwayomi.tachidesk.server.user.requireUser

class RequireArchiveOwnerDirectiveWiring : KotlinSchemaDirectiveWiring {
    override fun onField(environment: KotlinFieldDirectiveEnvironment): GraphQLFieldDefinition {
        val originalDataFetcher = environment.getDataFetcher()
        environment.setDataFetcher(
            DataFetcher { env ->
                val user = env.graphQlContext.getAttribute(Attribute.TachideskUser)
                requireArchiveOwner(user.requireUser())
                originalDataFetcher.get(env)
            },
        )
        return environment.element
    }
}
