package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Choosing a location on Home, as a person does. */
@OptIn(ExperimentalTestApi::class)
class HomePickTest {
    @Test
    fun clickingASubscriptionLocationChoosesIt() = runComposeUiTest {
        val c = homeWorld()
        c.profiles.upsert(Profile(id = "s1", name = "Берлин", source = ProfileSource.Subscription, transport = TransportType.CUPSONLINE, value = "rooms", secret = "s".repeat(64)))
        c.profiles.upsert(Profile(id = "s2", name = "Париж", source = ProfileSource.Subscription, transport = TransportType.CUPSONLINE, value = "rooms", secret = "s".repeat(64)))
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        if (onAllNodesWithText("Париж").fetchSemanticsNodes().isEmpty()) onAllNodesWithText("Амстердам")[0].performClick()
        waitForIdle()
        onAllNodesWithText("Париж")[0].performClick()
        waitForIdle()
        assertEquals("s2", c.settings.settings.value.selectedProfileId)
    }
}
