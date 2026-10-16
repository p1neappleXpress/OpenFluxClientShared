package io.openflux.desktop

import io.openflux.desktop.model.KnownServer
import io.openflux.desktop.model.NodeDocuments
import io.openflux.desktop.model.NodeServers
import io.openflux.desktop.model.NodeTransports
import io.openflux.desktop.model.PhpHosts
import io.openflux.desktop.model.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The node helpers of the model: names, documents, known servers, a hosting node's page address. */
class NodeHelpersTest {
    @Test
    fun transportNames() {
        assertEquals("Direct", NodeTransports.describe(emptyList()))
        assertEquals("Volga, Mail.ru и Direct", NodeTransports.describe(listOf(TransportType.VYANDEX, TransportType.MAILRU)))
        assertEquals("https://cloud.mail.ru/public/a1/b2", NodeTransports.cleanMailru(" https://cloud.mail.ru/public/a1/b2/?x#y "))
        assertNull(NodeTransports.cleanMailru("https://cloud.mail.ru.evil/public/a1/b2"))
    }
    @Test
    fun documentNames() {
        val at = 1790477460000L // 2026-09-27 02:51 UTC
        val named = NodeDocuments.fileName("Моя нода №1", "203.0.113.10", at, kotlin.random.Random(1))
        assertTrue(Regex("^moya-noda-1-20260927-0251-[a-z0-9]{4}$").matches(named), named)
        val default = NodeDocuments.fileName("Нода 203.0.113.10", "203.0.113.10", at, kotlin.random.Random(1))
        assertTrue(Regex("^20260927-0251-[a-z0-9]{4}$").matches(default), default)
        assertTrue(Regex("^20260927-0251-[a-z0-9]{4}$").matches(NodeDocuments.fileName("  ", "h", at, kotlin.random.Random(2))))
        val long = NodeDocuments.fileName("x".repeat(100), "h", at, kotlin.random.Random(3))
        assertTrue(Regex("^[a-z0-9-]{1,64}$").matches(long) && long.startsWith("x".repeat(24) + "-"), long)
        assertFalse("openflux" in NodeDocuments.fileName("OpenFlux", "h", at, kotlin.random.Random(4)))
    }
    @Test
    fun servers() {
        val a = KnownServer("a", 22, "root")
        val b = KnownServer("b", 22, "root")
        assertEquals(listOf(b, a), NodeServers.remember(listOf(a), b))
        assertEquals(listOf(KnownServer("A", 22, "admin"), b), NodeServers.remember(listOf(a, b), KnownServer("A", 22, "admin")))
        assertEquals(NodeServers.MAX_KNOWN, NodeServers.remember((1..60).map { KnownServer("h$it", 22, "u") }, a).size)
        assertNull(NodeServers.port("0"))
        assertNull(NodeServers.port("70000"))
        assertEquals(2222, NodeServers.port(" 2222 "))
        assertTrue(NodeDocuments.signedIn("yandexuid=1; Session_id=abc; L=2"))
        assertFalse(NodeDocuments.signedIn("yandexuid=1; sessionid2=abc"))
    }
    @Test
    fun aNodePageAddressIsReadApart() {
        val m = PhpHosts.nodeAddress("https://site.example.org/sub/mailruexit.php?k=K1&url=https%3A%2F%2Fcloud.mail.ru%2Fpublic%2Fa%2Fb")!!
        assertEquals("https://site.example.org", m.site)
        assertEquals("K1", m.token)
        assertEquals(TransportType.MAILRU, m.carrier)
        assertEquals("https://cloud.mail.ru/public/a/b", m.target)
        val plain = PhpHosts.nodeAddress("site.example.org")!!
        assertEquals("https://site.example.org", plain.site)
        assertEquals("", plain.token)
        assertNull(plain.carrier)
        assertNull(PhpHosts.nodeAddress("not a site"))
        assertEquals("••••", PhpHosts.maskToken("abcd"))
        assertEquals("Ключ доступа — одна строка без пробелов", PhpHosts.tokenProblem("a b"))
    }
}
