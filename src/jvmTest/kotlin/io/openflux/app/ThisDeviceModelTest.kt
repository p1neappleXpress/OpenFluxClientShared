package io.openflux.app

import io.openflux.app.servers.ThisDeviceModel
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals

class ThisDeviceModelTest {
    private val conn = ScriptedConnection()
    private val c = testContainer(connection = conn)
    private val m = ThisDeviceModel(c)

    private val own = Profile(id = "a", name = "Своя", transport = TransportType.DIRECT, value = "1.2.3.4:8445", secret = "s".repeat(64))
    private val sub = Profile(id = "b", name = "Подписка", source = ProfileSource.Subscription, secret = "x")
    private val hosting = Profile(id = "c", name = "Хостинг", stream = true)

    @Test
    fun onlyProfilesThisDeviceCanServeAreOffered() {
        listOf(own, sub, hosting).forEach(c.profiles::upsert)
        assertEquals(listOf("a"), m.choices.map { it.id })
    }

    @Test
    fun startingRunsTheChosenProfileAsAnExit() {
        c.profiles.upsert(own)
        m.start(own)
        assertEquals(ConnectionMode.Exit, c.settings.settings.value.mode)
        assertEquals("a", c.settings.settings.value.selectedProfileId)
        assertEquals(ConnectionMode.Exit, (conn.state.value as ConnectionState.Connected).mode)
        assertEquals(true, m.running)
    }

    @Test
    fun stoppingGoesBackToBeingAClient() {
        c.profiles.upsert(own)
        m.start(own)
        m.stop()
        assertEquals(ConnectionState.Idle, conn.state.value)
        assertEquals(ConnectionMode.Client, c.settings.settings.value.mode)
        assertEquals(false, m.running)
    }
}
