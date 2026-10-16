package io.openflux.desktop

import io.openflux.desktop.core.CoreConnectionService
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionNarrative
import io.openflux.desktop.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionNarrativeTest {
    @Test
    fun aLinkThatIsUpIsNotYetAProvenNode() {
        val up = ConnectionNarrative.linkUp(ConnectionMode.Client, listOf("yandex", "mailru"))
        assertEquals(LogLevel.Info, up.level)
        assertTrue(up.text.contains("yandex, mailru"), up.text)
        assertTrue(up.text.contains("Проверяю"), "it says the proof is still to come: ${up.text}")
        assertTrue(!up.text.contains("Подключено"), up.text)
    }

    @Test
    fun anExitNodeIsSimplyRunning() {
        assertEquals(LogLevel.Success, ConnectionNarrative.linkUp(ConnectionMode.Exit, emptyList()).level)
    }

    @Test
    fun aNodeIsConfirmedByTheAddressTheTrafficLeavesFrom() {
        val ok = ConnectionNarrative.nodeConfirmed("203.0.113.7", 1_340)
        assertEquals(LogLevel.Success, ok.level)
        assertTrue(ok.text.contains("203.0.113.7") && ok.text.contains("1.3 с"), ok.text)
    }

    @Test
    fun aFailedCheckSaysWhatItMeansAndWhereToLook() {
        val lines = ConnectionNarrative.nodeNotConfirmed("HTTP connect timed out")
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.level == LogLevel.Warning })
        assertTrue(lines[0].text.contains("не проходит") && lines[0].text.contains("Нода не отвечает"), lines[0].text)
        assertTrue(lines[1].text.contains("Что проверить"), lines[1].text)
    }

    @Test
    fun theCommonFailuresHavePlainReasons() {
        fun why(raw: String?) = ConnectionNarrative.explainCheckFailure(raw)
        assertTrue(why("Connection refused").contains("прокси ядра"))
        assertTrue(why("java.net.UnknownHostException: api.ipify.org").contains("DNS"))
        assertTrue(why("PKIX path building failed").contains("TLS"))
        assertTrue(why("Network is unreachable").contains("маршрута"))
        assertTrue(why("неожиданный ответ").contains("не так"))
        assertEquals("нет ответа", why(null))
        assertEquals("something odd", why("something odd"))
    }

    @Test
    fun theCoresUsefulLinesAreSaidPlainlyAndTheRestAreLeftAlone() {
        val peer = ConnectionNarrative.readCoreLine("2026/10/14 20:00:00 Authenticated peer: IPv4 TCP; UDP=true; ICMP errors=true; maximum packet=1280")
        assertNotNull(peer)
        assertEquals(LogLevel.Success, peer.level)
        assertTrue(peer.text.contains("ключ шифрования"))
        assertEquals(LogLevel.Warning, ConnectionNarrative.readCoreLine("Mode: Session, falling back to classic while the exit does not answer the handshake")!!.level)
        assertTrue(ConnectionNarrative.readCoreLine("Running as CLIENT (stream mux over yandex, SOCKS5 on 127.0.0.1:1080)")!!.text.contains("клиент"))
        assertEquals(LogLevel.Warning, ConnectionNarrative.readCoreLine("captcha required for yandex")!!.level)
        assertNull(ConnectionNarrative.readCoreLine("23:33:27.443294 [VOLGA] ping"))
    }

    @Test
    fun aFatalLineGetsAPlainCauseOnlyWhenItIsFatal() {
        assertTrue(CoreConnectionService.friendlyProblem("FATAL: dial tcp 1.2.3.4:443: connect: connection refused")!!.contains("Нода отказала"))
        assertTrue(CoreConnectionService.friendlyProblem("[ERROR] read: i/o timeout")!!.contains("Нода не отвечает"))
        // a mere debug line with the same words must not become "the reason the core died"
        assertNull(CoreConnectionService.friendlyProblem("dial tcp 1.2.3.4:443: i/o timeout, retrying"))
        // the rules that were there keep working
        assertTrue(CoreConnectionService.friendlyProblem("listen tcp 127.0.0.1:1080: address already in use")!!.contains("Порт уже занят"))
    }
}
