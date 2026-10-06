package io.openflux.desktop

import io.openflux.desktop.core.ExitL3
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.ExitBackend
import io.openflux.desktop.model.describe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ExitL3Test {
    @Test
    fun windowsHasNothingToCheckBeforeStartWinDivertIsFetched() {
        assertNull(ExitL3.problem("Windows 11", root = false))
    }

    @Test
    fun linuxNeedsRootAndMacHasNoL3() {
        assertNotNull(ExitL3.problem("Linux", root = false))
        assertNull(ExitL3.problem("Linux", root = true))
        assertTrue("не реализована" in ExitL3.problem("Mac OS X", root = true).orEmpty())
    }

    @Test
    fun onlyWindowsAsksUacForTheCoreAndOnlyWhenTheAppIsNotElevated() {
        assertTrue(ExitL3.needsUac("Windows 11", appElevated = false))
        assertFalse(ExitL3.needsUac("Windows 11", appElevated = true))
        assertFalse(ExitL3.needsUac("Linux", appElevated = false))
    }

    @Test
    fun settingsSavedBeforeTheChoiceExistedReadAsL4() {
        val old = """{"theme":"System","mode":"Exit","exitDirectPort":8445}"""
        assertEquals(ExitBackend.L4, Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), old).exitBackend)
        val chosen = Json.encodeToString(AppSettings.serializer(), AppSettings(exitBackend = ExitBackend.L3))
        assertEquals(ExitBackend.L3, Json.decodeFromString(AppSettings.serializer(), chosen).exitBackend)
    }

    @Test
    fun theWordsSayWhatEachBackendCosts() {
        assertTrue("права не нужны" in ExitBackend.L4.describe(null))
        assertTrue("администратора" in ExitBackend.L3.describe("при запуске Windows попросит разрешение администратора"))
        assertFalse(":" in ExitBackend.L3.describe(null).substringAfter("нужны права"))
    }
}
