package suwayomi.tachidesk.manga.impl.extension

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import suwayomi.tachidesk.manga.model.dataclass.ExtensionDataClass
import suwayomi.tachidesk.server.serverConfig
import kotlin.time.Duration.Companion.hours

object ExtensionAutoUpdater {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    internal fun candidates(extensions: List<ExtensionDataClass>): List<ExtensionDataClass> =
        extensions.filter { it.installed && it.hasUpdate && !it.obsolete }

    fun start() {
        scope.launch {
            serverConfig.autoUpdateExtensions.collectLatest { enabled ->
                if (!enabled) return@collectLatest
                while (true) {
                    try {
                        ExtensionsList.fetchExtensions()
                        val outdated = candidates(ExtensionsList.extensionTableAsDataClass())
                        for (extension in outdated) {
                            if (!serverConfig.autoUpdateExtensions.value) break
                            try {
                                Extension.updateExtension(extension.pkgName)
                                logger.info { "Updated extension ${extension.pkgName}" }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn(e) { "Could not update extension ${extension.pkgName}" }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Could not check for extension updates" }
                    }
                    delay(24.hours)
                }
            }
        }
    }
}
