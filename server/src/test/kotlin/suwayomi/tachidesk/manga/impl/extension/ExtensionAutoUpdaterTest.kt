package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ExtensionDataClass
import kotlin.test.assertEquals

class ExtensionAutoUpdaterTest {
    @Test
    fun `only installed non-obsolete extensions with newer versions are eligible`() {
        val update =
            ExtensionDataClass(
                repo = "https://example.invalid/index.min.json",
                apkName = "extension.apk",
                iconUrl = "",
                name = "Example",
                pkgName = "example.extension",
                versionName = "2.0",
                versionCode = 2,
                lang = "en",
                isNsfw = false,
                installed = true,
                hasUpdate = true,
                obsolete = false,
            )
        val extensions =
            listOf(
                update,
                update.copy(pkgName = "not.installed", installed = false),
                update.copy(pkgName = "already.current", hasUpdate = false),
                update.copy(pkgName = "obsolete", obsolete = true),
            )

        assertEquals(listOf(update), ExtensionAutoUpdater.candidates(extensions))
    }
}
