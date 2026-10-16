package io.openflux.app

import io.openflux.app.servers.LoginForm
import io.openflux.app.servers.ServerLink
import io.openflux.desktop.model.KnownServer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LoginFormTest {
    private val fake = FakeNodeService(FakeServer("a.example"), FakeServer("2001:db8::1"))
    private val c = testContainer(node = fake)
    private val link = ServerLink(c)
    private val form = LoginForm(link)

    @Test
    fun theAddressMayCarryThePort() {
        assertEquals(Triple("a.example", 2222, true), LoginForm.parse("a.example:2222", "22"))
        assertEquals(Triple("a.example", 22, true), LoginForm.parse(" a.example ", "22"))
        assertEquals(Triple("2001:db8::1", 22, true), LoginForm.parse("2001:db8::1", "22"))
        assertEquals(Triple("2001:db8::1", 2222, true), LoginForm.parse("[2001:db8::1]:2222", "22"))
        assertEquals(false, LoginForm.parse("a.example:99999", "22").third)
    }

    @Test
    fun whatIsMissingIsSaid() = runTest {
        assertNull(form.connect())
        assertEquals("Укажите адрес сервера", form.error)
        form.host = "a.example"
        assertNull(form.connect())
        assertEquals("Введите пароль", form.error)
        form.useKey = true
        assertNull(form.connect())
        assertEquals("Вставьте закрытый ключ или выберите файл", form.error)
        form.host = "a.example:0"; form.useKey = false; form.password = "pw"
        assertNull(form.connect())
        assertEquals("Порт — число от 1 до 65535", form.error)
    }

    @Test
    fun aNewServerAsksToTrustItsKey() = runTest {
        form.host = "a.example"; form.password = "pw"
        assertNull(form.connect())
        assertNull(form.error, "the prompt speaks, not an error")
        assertNotNull(link.hostKeyPrompt)
        assertEquals("a.example:22:root", form.trust())
    }

    @Test
    fun aKnownServerFillsTheForm() {
        form.prefill(KnownServer("a.example", 2222, "admin"))
        assertEquals("a.example:2222", form.host)
        assertEquals("admin", form.user)
        form.prefill(KnownServer("b.example", 22, "root"))
        assertEquals("b.example", form.host)
    }

    @Test
    fun aRefusedLoginSaysSoAndKeepsTheForm() = runTest {
        c.settings.update { it.copy(knownHostKeys = mapOf("a.example:22" to "SHA256:a.example")) }
        form.host = "a.example"; form.useKey = true; form.privateKey = "  "
        form.useKey = false; form.password = ""
        assertNull(form.connect())
        form.password = "pw"
        assertEquals("a.example:22:root", form.connect())
        assertEquals("", form.password, "the form forgets the password once the link holds it")
    }
}
