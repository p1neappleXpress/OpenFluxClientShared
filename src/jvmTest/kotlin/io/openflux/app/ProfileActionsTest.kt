package io.openflux.app

import io.openflux.app.profiles.ProfileActions
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TransportType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileActionsTest {
    private val conn = ScriptedConnection()
    private val c = testContainer(connection = conn)
    private val a = ProfileActions(c)
    private val p = Profile(id = "p", name = "Друг", source = ProfileSource.Link, transport = TransportType.DIRECT, value = "1.2.3.4:8445", secret = "s".repeat(64))
    private val sub = Profile(id = "s", name = "Амстердам", source = ProfileSource.Subscription)

    @Test
    fun connectingAProfileIsAlwaysAsAClient() {
        c.profiles.upsert(p)
        val modes = mutableListOf<io.openflux.desktop.model.ConnectionMode?>()
        conn.onConnect = { pr, mode -> modes += mode; ConnectionState.Connected(pr, mode ?: c.settings.settings.value.mode, 0) }
        c.settings.update { it.copy(mode = io.openflux.desktop.model.ConnectionMode.Exit) }
        a.connect(p)
        assertEquals(listOf<io.openflux.desktop.model.ConnectionMode?>(io.openflux.desktop.model.ConnectionMode.Client), modes)
        assertEquals(io.openflux.desktop.model.ConnectionMode.Client, c.settings.settings.value.mode)
        assertEquals("p", c.settings.settings.value.selectedProfileId)
    }

    @Test
    fun duplicateMakesAManualCopy() {
        c.profiles.upsert(p)
        val copy = a.duplicate(p)!!
        assertEquals("Друг (копия)", copy.name)
        assertEquals(ProfileSource.Manual, copy.source)
        assertEquals(2, c.profiles.profiles.value.size)
        assertNull(a.duplicate(sub))
    }

    @Test
    fun deletingTheConnectedOneDisconnectsAndForgetsTheChoice() {
        c.profiles.upsert(p)
        a.connect(p)
        a.delete(p)
        assertEquals(ConnectionState.Idle, conn.state.value)
        assertTrue(c.profiles.profiles.value.isEmpty())
        assertNull(c.settings.settings.value.selectedProfileId)
    }

    @Test
    fun aSubscriptionsLocationIsNeitherDeletedNorShared() = runTest {
        c.profiles.upsert(sub)
        a.delete(sub)
        assertEquals(1, c.profiles.profiles.value.size)
        assertFalse(a.canShare(sub))
        assertTrue(a.link(sub).isFailure)
    }

    @Test
    fun aMaxTokenCannotBeShared() {
        assertFalse(a.canShare(p.copy(transport = TransportType.ONEME)))
        assertTrue(a.canShare(p))
    }

    @Test
    fun markOwnMovesItToMyServers() {
        c.profiles.upsert(p)
        a.markOwn(p)
        assertEquals(ProfileSource.Node, c.profiles.profiles.value.single().source)
    }
}
