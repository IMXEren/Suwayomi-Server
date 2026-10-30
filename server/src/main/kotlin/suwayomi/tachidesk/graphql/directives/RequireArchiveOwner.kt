package suwayomi.tachidesk.graphql.directives

import com.expediagroup.graphql.generator.annotations.GraphQLDirective
import graphql.introspection.Introspection.DirectiveLocation

@GraphQLDirective(name = "requireArchiveOwner", locations = [DirectiveLocation.FIELD_DEFINITION])
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequireArchiveOwner
