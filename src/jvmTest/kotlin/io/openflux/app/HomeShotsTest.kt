package io.openflux.app

import io.openflux.app.home.ConnectionList
import io.openflux.app.home.HomeModel
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.TrafficStats
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.test.Test
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/** Home's states, for review by eye (SHOTS=<dir>). */
class HomeShotsTest {
    private fun shot(name: String, frame: Shots.Frame = Shots.PHONE, prepare: (ScriptedConnection) -> Unit = {}) {
        val conn = ScriptedConnection()
        val c = homeWorld(conn)
        prepare(conn)
        Shots.render(name, frame) { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
    }

    @Test fun empty() = Shots.render("home-empty") { OpenFluxApp(testContainer(), NoScrollbars, Shortcuts()) }
    @Test fun idle() = shot("home")
    @Test fun idleSmall() = shot("home", Shots.SMALL)
    @Test fun wide() = shot("home", Shots.WIDE)

    private fun connected(conn: ScriptedConnection) {
        val c = conn
        c.state.value = ConnectionState.Connected(io.openflux.desktop.model.Profile(id = "a", name = "Амстердам"), ConnectionMode.Client, 0)
        c.traffic.value = TrafficStats(downBytesPerSec = 1_250_000, upBytesPerSec = 42_000, totalDown = 52_000_000, activeTransport = "vyandex", activeTransports = listOf("vyandex"), live = true)
    }

    @Test fun on() = shot("home-on") { connected(it) }
    @Test fun failed() = shot("home-failed") { it.state.value = ConnectionState.Failed(io.openflux.desktop.model.Profile(id = "a", name = "Амстердам"), "Нода не ответила за 20 секунд") }
    @Test fun busy() = shot("home-busy") { it.state.value = ConnectionState.Connecting(io.openflux.desktop.model.Profile(id = "a", name = "Амстердам"), ConnectionMode.Client, 0) }

    @Test fun drawer() {
        val c = homeWorld()
        Shots.render("home-list") {
            CompositionLocalProvider(LocalAppContainer provides c, io.openflux.app.shell.LocalNavigator provides io.openflux.app.nav.AppNavigator()) {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    ConnectionList(HomeModel(c), c.profiles.profiles.value, c.profiles.profiles.value.first(), onChosen = {})
                }
            }
        }
    }
}
