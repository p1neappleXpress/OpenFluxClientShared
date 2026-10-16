package io.openflux.app

import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.nav.NavState
import io.openflux.app.nav.Section
import io.openflux.app.nav.aliveIn
import io.openflux.app.nav.rememberAppNavigator
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.desktop.data.FileSettingsRepository
import io.openflux.desktop.model.KnownServer
import io.openflux.desktop.model.Profile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavStateTest {
    @Test
    fun aliveFollowsTheContainer() {
        val c = testContainer()
        c.settings.update { it.copy(knownServers = listOf(KnownServer("h", 22, "root"))) }
        c.profiles.upsert(Profile(id = "p1", name = "Амстердам"))
        val alive = aliveIn(c)
        assertTrue(alive(Dest.Server("h:22:root")))
        assertTrue(alive(Dest.Node("h:22:root", "of-a")))
        assertFalse(alive(Dest.Server("x:22:root")))
        assertTrue(alive(Dest.Profile("p1")))
        assertFalse(alive(Dest.Profile("no-such-profile")))
        assertTrue(alive(Dest.SettingsPage("about")))
    }

    @Test
    fun settingsOfAnOlderVersionLoadAndKeepTheState() {
        val dir = Files.createTempDirectory("openflux-nav").toFile()
        try {
            // Written by 2.14.x: no navState.
            dir.resolve("settings.json").writeText("""{"socksPort": 1090}""")
            val first = FileSettingsRepository(dir)
            assertEquals("", first.settings.value.navState)
            val saved = AppNavigator().apply { open(Dest.Server("h:22:root")) }.snapshot().encode()
            first.update { it.copy(navState = saved) }
            assertEquals(saved, FileSettingsRepository(dir).settings.value.navState)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun desktopRestartRestoresTheScreen() {
        val c = testContainer()
        c.settings.update { it.copy(knownServers = listOf(KnownServer("h", 22, "root"))) }
        val saved = AppNavigator().apply { open(Dest.Server("h:22:root")) }.snapshot().encode()
        val n = AppNavigator.restore(NavState.decode(saved), aliveIn(c))
        assertEquals(Section.Servers, n.section)
        assertEquals(Dest.Server("h:22:root"), n.top)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun deletedProfileClosesItsScreens() = runComposeUiTest {
        val c = testContainer()
        c.profiles.upsert(Profile(id = "p1", name = "Амстердам"))
        lateinit var n: AppNavigator
        setContent { n = rememberAppNavigator(c) }
        runOnIdle { n.open(Dest.Profile("p1")); n.open(Dest.Give("p1")) }
        runOnIdle { c.profiles.delete("p1") }
        waitForIdle()
        assertEquals(listOf(Dest.Home), n.stack(Section.Home))
        assertEquals(listOf(Dest.Share), n.stack(Section.Share))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun desktopSavesWhereThePersonIs() = runComposeUiTest {
        val c = testContainer()
        c.settings.update { it.copy(knownServers = listOf(KnownServer("h", 22, "root"))) }
        lateinit var n: AppNavigator
        setContent { n = rememberAppNavigator(c) }
        runOnIdle { n.open(Dest.Server("h:22:root")) }
        // The save waits for the person to settle (debounce), on the test's virtual clock.
        waitUntil(timeoutMillis = 5_000) { NavState.decode(c.settings.settings.value.navState)?.section == Section.Servers }
    }
}
