@file:Suppress("RedundantNullableReturnType", "unused")

package suwayomi.tachidesk.graphql.mutations

import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.PartialSettingsType
import suwayomi.tachidesk.graphql.types.Settings
import suwayomi.tachidesk.graphql.types.SettingsType
import suwayomi.tachidesk.server.settings.SettingsCompat
import suwayomi.tachidesk.server.settings.SettingsUpdater
import suwayomi.tachidesk.server.settings.SettingsValidator
import suwayomi.tachidesk.server.user.ARCHIVE_OWNER_ID
import suwayomi.tachidesk.server.user.UserPermission
import suwayomi.tachidesk.server.user.hasPermission
import xyz.nulldev.ts.config.GlobalConfigManager

class SettingsMutation {
    data class SetSettingsInput(
        val clientMutationId: String? = null,
        val settings: PartialSettingsType,
    )

    data class SetSettingsPayload(
        val clientMutationId: String?,
        val settings: SettingsType,
    )

    @GraphQLIgnore
    fun updateSettings(
        settings: Settings,
        userId: Int,
        skipArchival: Boolean = false,
    ) {
        val validationErrors = SettingsValidator.validate(settings, true)
        if (validationErrors.isNotEmpty()) {
            throw Exception("Validation errors: ${validationErrors.joinToString("; ")}")
        }

        SettingsUpdater.updateAll(settings, userId, skipArchival)
    }

    @RequireAuth
    fun setSettings(
        @GraphQLIgnore
        userId: Int,
        @GraphQLIgnore
        permissions: List<UserPermission>,
        input: SetSettingsInput,
    ): SetSettingsPayload {
        val (clientMutationId, settings) = input

        SettingsUpdater.requireArchiveOwner(userId, settings)
        SettingsCompat.applyMovedSettingsToUser(userId, settings)

        if (!permissions.hasPermission(UserPermission.MANAGE_SETTINGS)) {
            return SetSettingsPayload(clientMutationId, SettingsType.masked(userId))
        }

        updateSettings(settings, userId)

        return SetSettingsPayload(clientMutationId, SettingsType(userId))
    }

    data class ResetSettingsInput(
        val clientMutationId: String? = null,
    )

    data class ResetSettingsPayload(
        val clientMutationId: String?,
        val settings: SettingsType,
    )

    @RequireAuth
    fun resetSettings(
        @GraphQLIgnore
        userId: Int,
        @GraphQLIgnore
        permissions: List<UserPermission>,
        input: ResetSettingsInput,
    ): ResetSettingsPayload {
        val (clientMutationId) = input

        SettingsCompat.resetMovedUserSettings(userId)

        if (!permissions.hasPermission(UserPermission.MANAGE_SETTINGS)) {
            return ResetSettingsPayload(clientMutationId, SettingsType.masked(userId))
        }

        if (userId == ARCHIVE_OWNER_ID) GlobalConfigManager.resetUserConfig()
        val settings = SettingsType.defaults()
        updateSettings(settings, userId, skipArchival = userId != ARCHIVE_OWNER_ID)

        return ResetSettingsPayload(clientMutationId, settings)
    }
}
