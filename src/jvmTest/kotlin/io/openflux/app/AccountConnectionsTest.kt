package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.account.connectionStateText
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.BotLink
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotState
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The account's connections in the window: their state, connecting this device, giving one to another device. */
@OptIn(ExperimentalTestApi::class)
class AccountConnectionsTest {
    private val token = "AAAAAAAAAAAAAAAAAAAAAA"

    @Test
    fun eachStateIsSaidAndOnlyTelegramIsSentToTheBot() {
        fun s(status: String, tg: Boolean = true, connected: Boolean = false, gone: Boolean = false) =
            connectionStateText(BotLink(token, "p1", status = status, account = true, gone = gone), tg, connected)
        assertEquals("работает", s("running"))
        assertEquals("работает, подключено на этом устройстве", s("running", connected = true))
        assertTrue(s("paused").contains("@openflux_bot"))
        assertEquals("приостановлено", s("paused", tg = false), "an email account has no bot")
        assertEquals("готовится", s("creating"))
        assertEquals("готовится", s(""))
        assertEquals("закончилось или заменено", s("running", gone = true))
    }

    @Test
    fun aConnectionOfTheAccountIsConnectedFromItsSheetAndSharedByTheBotsLink() = runComposeUiTest {
        val c = testContainer()
        c.profiles.upsert(Profile("p1", "OpenFlux #4", source = ProfileSource.Bot))
        c.botRepo.save(
            BotState(
                session = BotSession("ofs_x", 7, "ivan"),
                subscription = BotSubscription(planId = "own30", planName = "Start", status = "active", expiresAt = 1_900_000_000, maxSessions = 1, isActive = true),
                links = listOf(BotLink(token, "p1", "OpenFlux #4", account = true, status = "running")),
            ),
        )
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.Account) }.snapshot().encode()) }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("OpenFlux #4").performScrollTo().assertIsDisplayed()
        onNodeWithText("работает").assertIsDisplayed()
        onNodeWithText("OpenFlux #4").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Подключиться")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("https://link.openflux.dev/$token").assertExists()
        onAllNodes(hasText("openflux://", substring = true)).fetchSemanticsNodes().let { assertTrue(it.isEmpty(), "the key is never shown, only the bot's link") }
        onNodeWithText("Подключиться").performClick()
        waitForIdle()
        val state = c.connection.state.value
        assertIs<ConnectionState.Connected>(state)
        assertEquals("p1", state.profile.id)
        assertFalse(onAllNodes(hasText("Подключиться")).fetchSemanticsNodes().isNotEmpty(), "the sheet closed")
    }
}
