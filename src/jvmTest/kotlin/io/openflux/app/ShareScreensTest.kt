package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.FakeShareLinkCodec
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareTransport
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ShareScreensTest {
    @Test
    fun aLinkFromOutsideOpensItsScreenAndIsAdded() = runComposeUiTest {
        val c = testContainer()
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        val link = runBlocking { FakeShareLinkCodec().encode(ShareConfig(name = "От друга", secret = "s".repeat(64), transports = listOf(ShareTransport(type = "direct", dial = "203.0.113.7:8445", priority = 50)))) }
        runOnIdle { c.incomingLink.value = link }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Добавить профиль")).fetchSemanticsNodes().isNotEmpty() && onAllNodes(hasText("От друга")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Добавить профиль").performClick()
        waitUntil(timeoutMillis = 5_000) { c.profiles.profiles.value.size == 1 }
        assertEquals("От друга", c.profiles.profiles.value.single().name)
    }

    @Test
    fun theShareSectionGivesAProfilesQr() = runComposeUiTest {
        val c = homeWorld()
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.Share) }.snapshot().encode()) }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Франкфурт").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Копировать ссылку")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Копировать ссылку").performClick()
        assertEquals(true, (c.platform as TestPlatform).clipboard?.startsWith("openflux://v1/"))
    }

    @Test
    fun shots() {
        val c = homeWorld()
        for ((name, dest) in listOf("share" to Dest.Share, "give" to Dest.Give("b"), "add-key" to Dest.AddKey)) {
            c.settings.update { it.copy(navState = AppNavigator().apply { open(dest) }.snapshot().encode()) }
            Shots.render(name) { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        }
    }
}
