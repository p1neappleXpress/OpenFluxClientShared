package io.openflux.app

import io.openflux.app.servers.ServerLink
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.KnownServer
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.SshTarget
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerLinkTest {
    private val a = FakeServer("a.example")
    private val b = FakeServer("b.example")
    private val fake = FakeNodeService(a, b)
    private val platform = TestPlatform()
    private val c = testContainer(node = fake, platform = platform)
    private val link = ServerLink(c)

    private fun target(host: String, key: String = "SHA256:$host") = SshTarget(host, 22, "root", password = "pw", hostKey = key)

    @Test
    fun connectRemembersTheServerWithoutSecrets() = runTest {
        val id = link.connect(target("a.example"))
        assertEquals("a.example:22:root", id)
        assertEquals(listOf(KnownServer("a.example", 22, "root")), c.settings.settings.value.knownServers)
        assertFalse(c.settings.settings.value.toString().contains("pw"), "no password in the settings")
        assertTrue(link.hasAccess(id))
    }

    @Test
    fun unknownHostKeyAsksAndTrustConnects() = runTest {
        assertFailsWith<NodeWizardException> { link.connect(target("a.example", key = "")) }
        val prompt = assertNotNull(link.hostKeyPrompt)
        assertFalse(prompt.mismatch)
        assertEquals("SHA256:a.example", prompt.fingerprint)
        val id = link.trust()
        assertNull(link.hostKeyPrompt)
        assertEquals("SHA256:a.example", c.settings.settings.value.knownHostKeys["a.example:22"])
        assertEquals("a.example:22:root", id)
    }

    @Test
    fun changedHostKeyIsAMismatch() = runTest {
        assertFailsWith<NodeWizardException> { link.connect(target("a.example", key = "SHA256:old")) }
        assertTrue(assertNotNull(link.hostKeyPrompt).mismatch)
    }

    @Test
    fun aCallForAnotherServerConnectsThereFirst() = runTest {
        val ia = link.connect(target("a.example"))
        val ib = link.connect(target("b.example"))
        fake.log.clear()
        link.call(ia) { newChannel() }
        assertEquals(listOf("a.example:connect", "a.example:newChannel"), fake.log)
        fake.log.clear()
        link.call(ib) { newChannel() }
        assertEquals(listOf("b.example:connect", "b.example:newChannel"), fake.log)
    }

    @Test
    fun anIdleConnectionIsOpenedAgain() = runTest {
        val id = link.connect(target("a.example"))
        platform.clock += 61_000
        fake.log.clear()
        link.call(id) { newChannel() }
        assertEquals(listOf("a.example:connect", "a.example:newChannel"), fake.log)
    }

    @Test
    fun aReadRetriesOnceAfterADropAWriteDoesNot() = runTest {
        val id = link.connect(target("a.example"))
        fake.dropNext = true
        link.call(id, retry = true) { newChannel() }
        fake.dropNext = true
        assertFailsWith<NodeWizardException> { link.call(id) { newChannel() } }
    }

    @Test
    fun withoutAccessACallSaysSo() = runTest {
        val e = assertFailsWith<NodeWizardException> { link.call("a.example:22:root") { newChannel() } }
        assertEquals("Нет доступа к серверу: войдите заново", e.message)
    }

    @Test
    fun sudoDefaultsToTheLoginPassword() = runTest {
        a.sudo = "password"; a.sudoPassword = "pw"
        val id = link.connect(target("a.example"))
        assertTrue(link.needsSudo(id))
        assertEquals("pw", link.sudo(id))
        val byKey = link.connect(SshTarget("b.example", 22, "root", privateKey = "KEY", hostKey = "SHA256:b.example"))
        b.sudo = "password"
        link.connect(SshTarget("b.example", 22, "root", privateKey = "KEY", hostKey = "SHA256:b.example"))
        assertEquals("", link.sudo(byKey))
    }

    @Test
    fun channelsAreReadWithSudo() = runTest {
        a.channels["of-1"] = InstalledChannel("of-1", "active", 8446)
        a.channels["of-2"] = InstalledChannel("of-2", "failed", 8445)
        val id = link.connect(target("a.example"))
        assertEquals(listOf("of-2", "of-1"), link.channels(id).map { it.id }, "by port")
    }

    @Test
    fun closingAccessForgetsTheSecrets() = runTest {
        val id = link.connect(target("a.example"))
        link.closeAccess(id)
        assertFalse(link.hasAccess(id))
        assertNull(fake.connected)
        assertEquals(1, c.settings.settings.value.knownServers.size, "the server stays in the list")
    }

    @Test
    fun forgettingRemovesTheServer() = runTest {
        val id = link.connect(target("a.example"))
        link.forget(id)
        assertEquals(emptyList(), c.settings.settings.value.knownServers)
        assertFalse(link.hasAccess(id))
    }

    @Test
    fun sixServersAreAllKept() = runTest {
        val many = (1..6).map { FakeServer("s$it.example") }
        val f = FakeNodeService(*many.toTypedArray())
        val cc = testContainer(node = f)
        val l = ServerLink(cc)
        for (s in many) l.connect(target(s.host))
        assertEquals(6, cc.settings.settings.value.knownServers.size)
    }
}
