package io.openflux.desktop

import io.openflux.desktop.data.FileSettingsRepository
import java.nio.file.Files
import io.openflux.desktop.model.CoreSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsMigrationTest {
    @Test
    fun systemProxyTurnsOnOnceForOldSettings() {
        val dir = Files.createTempDirectory("openflux-settings").toFile()
        try {
            // Saved by a version where the system proxy was off by default.
            dir.resolve("settings.json").writeText("""{"systemProxy": false, "socksPort": 1090}""")
            val first = FileSettingsRepository(dir)
            assertTrue(first.settings.value.systemProxy)
            assertTrue(first.settings.value.socksPort == 1090)

            // Turned off by hand afterwards: that choice stays.
            first.update { it.copy(systemProxy = false) }
            assertFalse(FileSettingsRepository(dir).settings.value.systemProxy)

            // A fresh install starts with it on.
            val fresh = Files.createTempDirectory("openflux-settings").toFile()
            assertTrue(FileSettingsRepository(fresh).settings.value.systemProxy)
            fresh.deleteRecursively()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theForkAndOriginalCoreChoicesBecomeOne() {
        for (old in listOf("Fork", "Official")) {
            val dir = Files.createTempDirectory("openflux-settings").toFile()
            try {
                dir.resolve("settings.json").writeText("""{"coreSource": "$old", "systemProxyDefaultOn": true}""")
                assertEquals(CoreSource.Release, FileSettingsRepository(dir).settings.value.coreSource)
            } finally {
                dir.deleteRecursively()
            }
        }
        val kept = Files.createTempDirectory("openflux-settings").toFile()
        try {
            kept.resolve("settings.json").writeText("""{"coreSource": "Custom", "customCorePath": "x", "systemProxyDefaultOn": true}""")
            assertEquals(CoreSource.Custom, FileSettingsRepository(kept).settings.value.coreSource)
        } finally {
            kept.deleteRecursively()
        }
    }
}
