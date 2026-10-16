package io.openflux.app

import io.openflux.app.home.HomeBanner
import io.openflux.app.home.HomeModel
import io.openflux.app.home.SubLook
import io.openflux.app.home.look
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.PhpNodeRef
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ServiceEndpoint
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionKind
import io.openflux.desktop.model.SubscriptionState
import io.openflux.desktop.model.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class HomeModelTest {
    private val conn = ScriptedConnection()
    private val c = testContainer(connection = conn)
    private val m = HomeModel(c)
    private val a = Profile(id = "a", name = "Амстердам", source = ProfileSource.Subscription)
    private val b = Profile(id = "b", name = "Нода 203.0.113.7", source = ProfileSource.Node)
    private val h = Profile(id = "h", name = "Хостинг", phpNode = PhpNodeRef("https://x", "t"), stream = true)
    private val d = Profile(id = "d", name = "Друг", source = ProfileSource.Link, transport = TransportType.VYANDEX)

    @Test
    fun theChosenProfileOrTheFirst() {
        listOf(a, b).forEach(c.profiles::upsert)
        assertEquals("a", m.selected()?.id)
        c.settings.update { it.copy(selectedProfileId = "b") }
        assertEquals("b", m.selected()?.id)
        c.settings.update { it.copy(selectedProfileId = "gone") }
        assertEquals("a", m.selected()?.id)
    }

    /** Stopping the exit node from anywhere leaves the device a client; the shortcut, the tray and the tile never start the node. */
    @Test
    fun theShortcutAndTheButtonAgreeAfterTheExitNode() {
        listOf(a, b).forEach(c.profiles::upsert)
        val modes = mutableListOf<ConnectionMode?>()
        conn.onConnect = { p, mode -> modes += mode; ConnectionState.Connected(p, mode ?: c.settings.settings.value.mode, 0) }
        c.settings.update { it.copy(mode = ConnectionMode.Exit, selectedProfileId = "b") }
        conn.state.value = ConnectionState.Connected(b, ConnectionMode.Exit, 0)

        io.openflux.app.shell.toggleConnection(c)
        assertEquals(ConnectionState.Idle, conn.state.value)
        assertEquals(ConnectionMode.Client, c.settings.settings.value.mode, "stopping the node returns to the client mode")

        io.openflux.app.shell.toggleConnection(c)
        assertEquals(listOf<ConnectionMode?>(ConnectionMode.Client), modes, "the shortcut connects as a client")

        m.toggle()
        c.settings.update { it.copy(mode = ConnectionMode.Exit) }
        conn.state.value = ConnectionState.Connected(b, ConnectionMode.Exit, 0)
        m.toggle()
        assertEquals(ConnectionState.Idle, conn.state.value, "the Home button disconnects, as its label says")
        assertEquals(ConnectionMode.Client, c.settings.settings.value.mode)
    }

    /** The words of the state, the same on Home and in the Android notification. */
    @Test
    fun theStateInWords() {
        assertEquals("Отключено", io.openflux.app.home.statusLine(ConnectionState.Idle))
        assertEquals("Подключено", io.openflux.app.home.statusLine(ConnectionState.Connected(a, ConnectionMode.Client, 0)))
        assertEquals("Нода работает", io.openflux.app.home.statusLine(ConnectionState.Connected(b, ConnectionMode.Exit, 0)))
    }

    @Test
    fun theDotIsGreenOnlyWhenConnected() {
        val dot = { st: ConnectionState, id: String -> io.openflux.app.home.profileDot(st, id) }
        assertEquals(io.openflux.app.components.Status.Busy, dot(ConnectionState.Connecting(a, ConnectionMode.Client, 0), "a"))
        assertEquals(io.openflux.app.components.Status.On, dot(ConnectionState.Connected(a, ConnectionMode.Client, 0), "a"))
        assertEquals(io.openflux.app.components.Status.Off, dot(ConnectionState.Connected(a, ConnectionMode.Client, 0), "b"))
        assertEquals(io.openflux.app.components.Status.Off, dot(ConnectionState.Idle, "a"))
    }

    @Test
    fun choosingDoesNotTouchARunningExitNode() {
        listOf(a, b).forEach(c.profiles::upsert)
        c.settings.update { it.copy(mode = ConnectionMode.Exit, selectedProfileId = "b") }
        conn.state.value = ConnectionState.Connected(b, ConnectionMode.Exit, 0)
        m.choose(a)
        assertEquals("b", (conn.state.value as ConnectionState.Connected).profile.id, "the node keeps running as itself")
        assertEquals("a", c.settings.settings.value.selectedProfileId)
    }

    @Test
    fun choosingWhileConnectedOnlyChoosesUntilSwitched() {
        listOf(a, b).forEach(c.profiles::upsert)
        m.toggle()
        assertEquals("a", (conn.state.value as ConnectionState.Connected).profile.id)
        m.choose(b)
        assertEquals("a", (conn.state.value as ConnectionState.Connected).profile.id)
        m.switchTo(b)
        assertEquals("b", (conn.state.value as ConnectionState.Connected).profile.id)
        m.toggle()
        assertEquals(ConnectionState.Idle, conn.state.value)
        m.choose(a)
        assertEquals(ConnectionState.Idle, conn.state.value, "choosing while off only chooses")
        assertEquals("a", c.settings.settings.value.selectedProfileId)
    }

    @Test
    fun groupsForTheDrawer() {
        listOf(a, b, h, d).forEach(c.profiles::upsert)
        val g = m.groups(c.profiles.profiles.value)
        assertEquals(listOf("Подписка", "Мои серверы", "Остальные"), g.map { it.first })
        assertEquals(listOf("a"), g[0].second.map { it.id })
        assertEquals(listOf("b", "h"), g[1].second.map { it.id })
        assertEquals(listOf("d"), g[2].second.map { it.id })
    }

    @Test
    fun oneBannerByPriority() {
        listOf(d).forEach(c.profiles::upsert)
        val settings = AppSettings()
        val yandexExpired = mapOf(AccountKind.Yandex to AuthStatus.Expired("ivan"))
        val sub = SubscriptionState(ServiceEndpoint("https://s"), SubscriptionKind.Account, status = SubStatus.REVOKED)
        val failed = ConnectionState.Failed(d, "нода не ответила")
        assertIs<HomeBanner.Failed>(m.banner(failed, settings, d, yandexExpired, sub, 0))
        assertIs<HomeBanner.Account>(m.banner(ConnectionState.Idle, settings, d, yandexExpired, sub, 0))
        assertIs<HomeBanner.Subscription>(m.banner(ConnectionState.Idle, settings, d, emptyMap(), sub, 0))
        assertNull(m.banner(ConnectionState.Idle, settings, d, emptyMap(), null, 0))
        val exit = ConnectionState.Connected(d, ConnectionMode.Exit, 0)
        assertIs<HomeBanner.ThisDevice>(m.banner(exit, settings.copy(mode = ConnectionMode.Exit), d, emptyMap(), null, 0))
    }

    @Test
    fun subscriptionLooks() {
        val day = 86_400L
        val now = 1_791_763_200L
        fun s(status: String, expires: Long) = SubscriptionState(ServiceEndpoint("https://s"), SubscriptionKind.Account, status = status, expires = expires)
        assertEquals(SubLook("Отключена", "Доступ отключён. Обратитесь в поддержку.", true), s(SubStatus.REVOKED, 0).look(now * 1000, 0))
        assertEquals(SubLook("Нет подписки", "Подписка не оформлена.", true), s(SubStatus.EXPIRED, 0).look(now * 1000, 0))
        assertEquals(true, s(SubStatus.ACTIVE, now + 2 * day).look(now * 1000, 0).attention)
        assertEquals(false, s(SubStatus.ACTIVE, now + 10 * day).look(now * 1000, 0).attention)
        assertEquals("До 22 октября 2026: осталось 10 дней.", s(SubStatus.ACTIVE, now + 10 * day).look(now * 1000, 0).line)
    }
}
