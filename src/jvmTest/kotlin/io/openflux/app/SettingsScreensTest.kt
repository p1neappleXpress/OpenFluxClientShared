package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.LogLevel
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class SettingsScreensTest {
    @Test
    fun theJournalFiltersAndCopiesWhatItShows() = runComposeUiTest {
        val c = testContainer()
        (c.connection as TestConnection).logs.value = listOf(
            io.openflux.desktop.model.LogLine(1, 1, "соединение установлено", LogLevel.Info),
            io.openflux.desktop.model.LogLine(2, 2, "ошибка входа по SSH", LogLevel.Error),
        )
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.Logs) }.snapshot().encode()) }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("соединение установлено").assertIsDisplayed()
        onNodeWithText("Ошибки").performClick()
        assertEquals(0, onAllNodes(hasText("соединение установлено")).fetchSemanticsNodes().size)
        onNodeWithText("ошибка входа по SSH").assertIsDisplayed()
        onNodeWithContentDescription("Ещё").performClick()
        onNodeWithText("Копировать показанное").performClick()
        assertEquals("ошибка входа по SSH", (c.platform as TestPlatform).clipboard)
    }

    @Test
    fun tenTapsOnTheVersion() = runComposeUiTest {
        val c = testContainer()
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.SettingsPage("about")) }.snapshot().encode()) }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        repeat(10) { onNodeWithText("Версия приложения").performClick() }
        waitForIdle()
        assertEquals(true, c.settings.settings.value.developerMode)
    }

    @Test
    fun aReleaseCanBeSkipped() = runComposeUiTest {
        val p = TestPlatform().apply {
            appVersion = "2.0.0"
            releases = """[{"tag_name":"v2.1.0","draft":false,"prerelease":false,"body":"","html_url":"https://x","assets":[]}]"""
        }
        val c = testContainer(platform = p)
        kotlinx.coroutines.runBlocking { c.updates.check(manual = false) }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Вышла версия 2.1.0").assertIsDisplayed()
        onNodeWithContentDescription("Ещё").performClick()
        onNodeWithText("Пропустить эту версию").performClick()
        waitForIdle()
        assertEquals("v2.1.0", c.settings.settings.value.skippedUpdate)
        assertEquals(0, onAllNodes(hasText("Вышла версия 2.1.0")).fetchSemanticsNodes().size)
    }

    @Test
    fun shots() {
        val c = homeWorld()
        val pages = listOf(
            "settings" to Dest.Settings, "settings-connection" to Dest.SettingsPage("connection"), "settings-vpn" to Dest.SettingsPage("vpn"),
            "settings-services" to Dest.SettingsPage("services"), "settings-core" to Dest.SettingsPage("core"), "settings-look" to Dest.SettingsPage("look"),
            "settings-experimental" to Dest.SettingsPage("experimental"), "settings-about" to Dest.SettingsPage("about"),
            "journal" to Dest.Logs, "auth" to Dest.Auth, "account-none" to Dest.Account,
        )
        for ((name, dest) in pages) {
            c.settings.update { it.copy(navState = AppNavigator().apply { open(dest) }.snapshot().encode()) }
            Shots.render(name) { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        }
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.SettingsPage("services")) }.snapshot().encode()) }
        Shots.render("settings", Shots.WIDE) { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
    }
}
