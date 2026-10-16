package io.openflux.app

import io.openflux.app.servers.NodeVerifier
import io.openflux.desktop.model.CaptchaPrompt
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.LogLine
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.TrafficStats
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.service.ConnectionService
import io.openflux.app.platform.BrowserPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A connection the test steers: what connect() leads to, where traffic leaves, which carrier is live. */
class ScriptedConnection : ConnectionService {
    var onConnect: (Profile, ConnectionMode?) -> ConnectionState = { p, mode -> ConnectionState.Connected(p, mode ?: ConnectionMode.Client, 0) }
    var address: () -> ExitAddress = { ExitAddress.Known("203.0.113.7") }
    var refreshes = 0
    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val traffic = MutableStateFlow(TrafficStats())
    override val exitAddress = MutableStateFlow<ExitAddress>(ExitAddress.Unknown)
    override val logs: StateFlow<List<LogLine>> = MutableStateFlow(emptyList())
    override val captcha = MutableStateFlow<CaptchaPrompt?>(null)
    override val exitShareLink: StateFlow<String?> = MutableStateFlow(null)
    override val socksAddress: StateFlow<String?> = MutableStateFlow(null)
    override val captchaPage: StateFlow<BrowserPage?> = MutableStateFlow(null)
    override fun connect(profile: Profile, mode: ConnectionMode?) {
        state.value = onConnect(profile, mode)
        if (state.value is ConnectionState.Connected) exitAddress.value = address()
    }
    override fun disconnect() { state.value = ConnectionState.Idle }
    override fun refreshExitAddress() { refreshes++; exitAddress.value = address() }
    override fun clearLogs() = Unit
    override fun openCaptcha() = Unit
    override fun submitCaptcha() = Unit
    override fun dismissCaptcha() = Unit
    override fun shutdown() = Unit
}

class NodeVerifierTest {
    private val profile = Profile(id = "p1", name = "Нода")
    private val conn = ScriptedConnection()

    private fun TestScope.verifier() = NodeVerifier(testContainer(connection = conn), now = { testScheduler.currentTime })

    @Test
    fun trafficLeavingTheServerProvesIt() = runTest {
        val r = verifier().verify(profile, setOf("203.0.113.7"), primary = null)
        assertEquals("203.0.113.7", r.ip)
        assertTrue(r.primaryUp)
    }

    @Test
    fun anotherAddressIsAFailureAndDisconnects() = runTest {
        conn.address = { ExitAddress.Known("198.51.100.1") }
        val e = assertFailsWith<NodeWizardException> { verifier().verify(profile, setOf("203.0.113.7"), null) }
        assertEquals("Запрос вышел с адреса 198.51.100.1, а не с адреса сервера", e.message)
        assertEquals(ConnectionState.Idle, conn.state.value)
    }

    @Test
    fun androidVpnCannotAskTheAddressButTheSessionIsProof() = runTest {
        conn.address = { ExitAddress.NotCheckable("в режиме VPN видно в браузере") }
        val r = verifier().verify(profile, setOf("203.0.113.7"), null)
        assertEquals(null, r.ip)
    }

    @Test
    fun anUnavailableAddressIsAskedAgainUntilItWorks() = runTest {
        var n = 0
        conn.address = { if (n++ < 2) ExitAddress.Unavailable("Tunnel failed, got: 502") else ExitAddress.Known("203.0.113.7") }
        val r = verifier().verify(profile, setOf("203.0.113.7"), null)
        assertEquals("203.0.113.7", r.ip)
        assertTrue(conn.refreshes >= 2)
    }

    @Test
    fun aFailedConnectionSaysWhy() = runTest {
        conn.onConnect = { p, _ -> ConnectionState.Failed(p, "нода не ответила") }
        val e = assertFailsWith<NodeWizardException> { verifier().verify(profile, setOf("203.0.113.7"), null) }
        assertEquals("нода не ответила", e.message)
    }

    @Test
    fun noAnswerUntilTheDeadlineNamesTheLastProblem() = runTest {
        conn.address = { ExitAddress.Unavailable("Tunnel failed, got: 502") }
        val e = assertFailsWith<NodeWizardException> { verifier().verify(profile, setOf("203.0.113.7"), null) }
        assertEquals("Канал поднялся, но запрос через него не прошёл: Tunnel failed, got: 502", e.message)
    }

    @Test
    fun itWaitsForTheMainTransportAndCanStopWaiting() = runTest {
        val v = verifier()
        conn.traffic.value = TrafficStats(activeTransport = "direct", live = true)
        var result: NodeVerifier.Result? = null
        val job = launch { result = v.verify(profile, setOf("203.0.113.7"), TransportType.VYANDEX) }
        testScheduler.advanceTimeBy(5_000)
        assertEquals("Жду канал через ${TransportType.VYANDEX.shortLabel}…", v.stage)
        v.stopWaiting()
        job.join()
        assertFalse(result!!.primaryUp)
    }

    @Test
    fun theMainTransportComingUpIsNoticed() = runTest {
        val v = verifier()
        conn.traffic.value = TrafficStats(activeTransport = "direct", live = true)
        var result: NodeVerifier.Result? = null
        val job = launch { result = v.verify(profile, setOf("203.0.113.7"), TransportType.VYANDEX) }
        testScheduler.advanceTimeBy(5_000)
        conn.traffic.value = TrafficStats(activeTransport = "vyandex", live = true)
        job.join()
        assertTrue(result!!.primaryUp)
    }
}
