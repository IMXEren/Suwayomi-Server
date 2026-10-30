package suwayomi.tachidesk.server.settings

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import suwayomi.tachidesk.graphql.types.Settings
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.user.ARCHIVE_OWNER_ID
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

object SettingsUpdater {
    private val logger = KotlinLogging.logger { }

    private fun updateSetting(
        name: String,
        value: Any,
    ) {
        try {
            @Suppress("UNCHECKED_CAST")
            val property =
                serverConfig::class
                    .memberProperties
                    .find { it.name == name } as? KProperty1<ServerConfig, MutableStateFlow<*>>

            if (property != null) {
                val stateFlow = property.get(serverConfig)

                val validationError = SettingsValidator.validate(name, value)
                val isValid = validationError == null

                if (!isValid) {
                    logger.warn { "Invalid value for setting $name: $validationError. Ignoring update." }

                    return
                }

                val maybeConvertedValue =
                    SettingsRegistry
                        .get(name)
                        ?.typeInfo
                        ?.convertToInternalType
                        ?.invoke(value) ?: value

                // Normal update - MigratedConfigValue handles deprecated mappings automatically
                @Suppress("UNCHECKED_CAST")
                (stateFlow as MutableStateFlow<Any>).value = maybeConvertedValue
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update setting $name due to" }
        }
    }

    fun requireArchiveOwner(
        userId: Int,
        settings: Settings,
    ) {
        if (userId == ARCHIVE_OWNER_ID) return
        require(settings.asMap().none { (name, value) -> value != null && isArchiveSetting(name) }) {
            "Only the built-in admin can change shared archive settings"
        }
    }

    private fun isArchiveSetting(name: String): Boolean = SettingsRegistry.get(name)?.group == SettingGroup.ARCHIVAL.value

    fun updateAll(
        settings: Settings,
        userId: Int,
        skipArchival: Boolean = false,
    ) {
        if (!skipArchival) requireArchiveOwner(userId, settings)
        settings.asMap().forEach { (name, value) ->
            if (value != null && (!skipArchival || !isArchiveSetting(name))) {
                updateSetting(name, value)
            }
        }
    }
}
