package io.openflux.app

import io.openflux.app.nav.Dest
import io.openflux.app.servers.NodeEditorModel
import io.openflux.app.servers.NodeVerifier
import io.openflux.app.servers.ServerLink
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.ScriptParam
import io.openflux.desktop.model.ScriptSettingsCodec
import io.openflux.desktop.model.TransportType
import io.openflux.app.servers.NodeScript
import io.openflux.desktop.service.InMemoryScriptRepository
import io.openflux.desktop.service.ScriptRepository
import kotlin.io.encoding.Base64
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeEditorModelTest {
    private val doc = "https://docs.yandex.ru/edit/d/" + "a".repeat(20)
    private val server = FakeServer("a.example")
    private val fake = FakeNodeService(server)
    private val conn = ScriptedConnection().apply { address = { ExitAddress.Known("a.example") } }
    /** Installed scripts with their package bytes, as the platform repositories keep them. */
    private class Scripts(private val inner: InMemoryScriptRepository = InMemoryScriptRepository()) : ScriptRepository by inner {
        val bytes = mutableMapOf<String, ByteArray>()
        override fun packageBytes(id: String) = bytes[id]?.let { it to ByteArray(0) }
    }
    private val scripts = Scripts()
    private val c: AppContainer = testContainer(node = fake, connection = conn, scripts = scripts)
    private val authorKey = "ab".repeat(32)

    private fun installScript(id: String, file: String = "$id.flux") {
        scripts.upsert(
            InstalledScript(
                id = id, name = "MTS Link", pubkeyHex = authorKey, fingerprint = "f", fileName = file,
                params = listOf(ScriptParam("room", "Комната", scope = "profile"), ScriptParam("speed", scope = "settings")),
            ),
        )
        scripts.bytes[id] = "flux-$id".encodeToByteArray()
    }
    private val link = ServerLink(c)
    private val id = "a.example:22:root"

    private var yandexCookies = ""

    private suspend fun TestScope.editor(channel: String? = null): NodeEditorModel {
        link.connect(SshTarget("a.example", 22, "root", password = "pw", hostKey = "SHA256:a.example"))
        return NodeEditorModel(c, link, id, channel, this, NodeVerifier(c, now = { testScheduler.currentTime }), yandexCookies = { yandexCookies })
    }

    private fun installedNode(): InstalledChannel {
        val ch = InstalledChannel("of-x", "active", 8445, "ctx", listOf(NodeTransport("vyandex", doc)), "l4", "k".repeat(64))
        server.channels[ch.id] = ch
        return ch
    }

    @Test
    fun quickInstallMakesADirectNodeSavesItsProfileAndProvesIt() = runTest {
        val m = editor()
        m.install(); advanceUntilIdle()
        val made = server.channels.values.single()
        assertEquals(emptyList(), made.transports, "direct only")
        val p = c.profiles.profiles.value.single()
        assertEquals(ProfileSource.Node, p.source)
        assertEquals("Нода a.example", p.name)
        assertEquals(p.id, c.settings.settings.value.selectedProfileId)
        assertEquals(Dest.Node(id, made.id), m.done)
        assertNull(m.error)
        assertTrue(conn.state.value is ConnectionState.Connected, "the check connected through it")
    }

    /** Telemost and Avito go as they are (Avito's rooms made first); a script goes with this device's package. */
    @Test
    fun aNodeWithEveryKindOfTransport() = runTest {
        installScript("mtslink")
        val m = editor()
        m.edit {
            it.copy(
                telemost = true, telemostLink = "https://telemost.yandex.ru/j/88936358186517?x=1", avito = true,
                scripts = listOf(NodeScript("mtslink", "https://my.mts-link.ru/j/1", mapOf("speed" to "fast"))),
            )
        }
        m.install(); advanceUntilIdle()
        assertNull(m.error)
        val made = server.channels.values.single()
        assertEquals(listOf("telemost", "avito", "script"), made.transports.map { it.type })
        assertEquals("https://telemost.yandex.ru/j/88936358186517", made.transports[0].url)
        assertEquals("avito-rooms", made.transports[1].url, "rooms made before the install")
        val s = made.transports[2]
        assertEquals("mtslink", s.script)
        assertEquals(authorKey, s.pubkey)
        assertEquals("flux-mtslink", Base64.decode(s.pkg).decodeToString(), "this device's package")
        assertEquals(mapOf("speed" to "fast", "room" to "https://my.mts-link.ru/j/1"), ScriptSettingsCodec.decode(s.params), "the primary value rides in the settings, as the app runs it")

        val p = c.profiles.profiles.value.single()
        val extra = p.extras.single { it.type == TransportType.SCRIPT }
        assertEquals("mtslink", extra.scriptId)
        assertEquals("https://my.mts-link.ru/j/1", extra.value)
        assertEquals(mapOf("speed" to "fast"), extra.settings)
        assertTrue(p.session)
        assertTrue(p.carriers.any { it.type == TransportType.TELEMOST } && p.carriers.any { it.type == TransportType.AVITO })
    }

    @Test
    fun aScriptThisDeviceCannotSendIsSaidBeforeInstalling() = runTest {
        installScript("bare", file = "bare.js")
        val m = editor()
        m.edit { it.copy(scripts = listOf(NodeScript("bare"))) }
        m.install(); advanceUntilIdle()
        assertTrue(m.error.orEmpty().contains("bare"), m.error)
        assertTrue(server.channels.isEmpty())

        m.edit { it.copy(scripts = listOf(NodeScript("gone"))) }
        m.install(); advanceUntilIdle()
        assertTrue(m.error.orEmpty().contains("не установлен"), m.error)
    }

    @Test
    fun anInstalledNodesScriptsAreReadBackAndKeptInItsProfile() = runTest {
        installScript("mtslink")
        val ch = InstalledChannel(
            "of-s", "active", 8445, "ctx",
            listOf(NodeTransport("mailru", "https://cloud.mail.ru/public/aa/bb"), NodeTransport("script", "v1", script = "mtslink", params = ScriptSettingsCodec.encode(mapOf("room" to "v1", "speed" to "slow")))),
            "l4", "k".repeat(64),
        )
        server.channels[ch.id] = ch
        val m = editor(ch.id)
        m.load(); advanceUntilIdle()
        assertEquals(listOf(NodeScript("mtslink", "v1", mapOf("room" to "v1", "speed" to "slow"))), m.draft.scripts)
        m.edit { it.copy(scripts = it.scripts.map { s -> s.copy(value = "v2") }) }
        assertEquals(1, m.changes)
        m.apply(); advanceUntilIdle()
        assertNull(m.error)
        val sent = server.channels.getValue("of-s").transports.single { it.type == "script" }
        assertEquals("v2", ScriptSettingsCodec.decode(sent.params)["room"], "the new value replaces the old one in the settings")
        m.connectHere(); advanceUntilIdle()
        val p = c.profiles.profiles.value.single()
        assertEquals(listOf("mtslink"), p.extras.filter { it.type == TransportType.SCRIPT }.map { it.scriptId })
    }

    @Test
    fun aPlanProblemIsShownAndNothingIsInstalled() = runTest {
        val m = editor()
        fake.failPlan = "на сервере нет systemd"
        m.install(); advanceUntilIdle()
        assertEquals("на сервере нет systemd", m.error)
        assertTrue(server.channels.isEmpty())
        assertTrue(c.profiles.profiles.value.isEmpty())
        assertNull(m.done)
    }

    @Test
    fun aWrongSudoPasswordIsSaidAtTheButton() = runTest {
        server.sudo = "password"; server.sudoPassword = "right"
        val m = editor()
        assertTrue(m.needsSudo)
        m.sudoInput = "wrong"
        m.install(); advanceUntilIdle()
        assertEquals("sudo не принял пароль", m.error)
        assertTrue(server.channels.isEmpty())
    }

    @Test
    fun aFailedCheckKeepsTheNodeAndOffersRemoval() = runTest {
        conn.address = { ExitAddress.Known("198.51.100.1") }
        val m = editor()
        m.install(); advanceUntilIdle()
        assertEquals("Запрос вышел с адреса 198.51.100.1, а не с адреса сервера", m.verifyFailed)
        assertEquals(1, server.channels.size)
        m.removeFailed(); advanceUntilIdle()
        assertTrue(server.channels.isEmpty())
        assertTrue(c.profiles.profiles.value.isEmpty())
    }

    @Test
    fun editingLoadsCountsAndDiscards() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        assertEquals(true, m.draft.volga)
        m.edit { it.copy(mode = NodeMode.L3) }
        assertEquals(1, m.changes)
        m.discard()
        assertEquals(0, m.changes)
    }

    @Test
    fun applyChangesTheServerAndTheProfileHere() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        val before = m.adopt()
        m.edit { it.copy(mode = NodeMode.L3, cups = true) }
        m.apply(); advanceUntilIdle()
        assertNull(m.error)
        val now = server.channels.getValue("of-x")
        assertEquals("l3", now.mode)
        assertEquals(listOf("vyandex", "cupsonline"), now.transports.map { it.type })
        val p = c.profiles.profiles.value.single()
        assertEquals(before.id, p.id, "the same profile, updated")
        assertTrue(p.carriers.any { it.type == TransportType.CUPSONLINE })
        assertEquals(0, m.changes)
    }

    @Test
    fun aFailedApplyKeepsTheDraft() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        m.edit { it.copy(mode = NodeMode.L3) }
        fake.dropNext = true
        m.apply(); advanceUntilIdle()
        assertNotNull(m.error)
        assertEquals(1, m.changes)
        assertEquals("l4", server.channels.getValue("of-x").mode)
    }

    @Test
    fun aNewNameStaysOnThisDevice() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        m.adopt()
        fake.log.clear()
        m.edit { it.copy(name = "Амстердам") }
        m.apply(); advanceUntilIdle()
        assertFalse(fake.log.any { it.endsWith("setTransports") })
        assertEquals("Амстердам", c.profiles.profiles.value.single().name)
        assertEquals(0, m.changes)
    }

    @Test
    fun removingDeletesTheNodeAndItsProfile() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        m.adopt()
        var gone = false
        m.remove { gone = true }; advanceUntilIdle()
        assertTrue(gone)
        assertTrue(server.channels.isEmpty())
        assertTrue(c.profiles.profiles.value.isEmpty())
    }

    @Test
    fun theYandexSignInGoesToTheNode() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        m.pushYandexSignIn(); advanceUntilIdle()
        assertEquals("Сначала войдите в Яндекс: Настройки → Сервисы", m.error)
        yandexCookies = "Session_id=s; yandex_login=ivan"
        m.pushYandexSignIn(); advanceUntilIdle()
        assertEquals(listOf("Session_id=s; yandex_login=ivan"), fake.cookiesSent)
        assertNull(m.error)
    }

    @Test
    fun aDocumentMadeHereTakesItsSignInToANewNode() = runTest {
        val m = editor()
        m.createYandexDocument(); advanceUntilIdle()
        m.install(); advanceUntilIdle()
        assertNull(m.error)
        assertEquals(listOf("Session_id=s; yandex_login=ivan"), fake.cookiesSent)
    }

    @Test
    fun theSignInGoesOnlyWithTheDocumentItMade() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        m.createYandexDocument(); advanceUntilIdle()
        m.edit { it.copy(yandexDoc = "https://docs.yandex.ru/edit/d/" + "b".repeat(20)) }
        m.checkDocument(); advanceUntilIdle()
        m.apply(); advanceUntilIdle()
        assertNull(m.error)
        assertEquals(emptyList(), fake.cookiesSent, "another document goes without the sign-in made for the first")
    }

    @Test
    fun aNodeWithoutAProfileHereGetsOneFromTheServersKey() = runTest {
        installedNode()
        val m = editor("of-x")
        m.load(); advanceUntilIdle()
        assertNull(m.localProfile)
        val p = m.adopt()
        assertEquals(ProfileSource.Node, p.source)
        assertEquals(p, m.localProfile)
        assertEquals(p.id, m.adopt().id, "found again, not made twice")
    }
}
