package io.openflux.app

import io.openflux.app.nav.Dest
import io.openflux.app.servers.HostingNodeModel
import io.openflux.app.servers.HostingSetupModel
import io.openflux.app.servers.NodeVerifier
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.FtpTarget
import io.openflux.desktop.model.PhpHostingException
import io.openflux.desktop.model.PhpInstalled
import io.openflux.desktop.model.PhpNodeState
import io.openflux.desktop.model.PhpProbe
import io.openflux.desktop.model.PhpProgress
import io.openflux.desktop.model.PhpRoom
import io.openflux.desktop.model.PhpStatus
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.service.PhpHostingService
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A hosting as the fake sees it: the files, the node, what fails. */
class FakeHosting : PhpHostingService() {
    var webroot = ""
    var uploaded = false
    var running = false
    var token = "tok12345"
    var siteDownFor = 0
    /** A final reason the site gives on every check ("php_missing"); null when it works. */
    var checkFails: String? = null
    val calls = mutableListOf<String>()
    override suspend fun probe(ftp: FtpTarget): PhpProbe {
        calls += "probe"
        if (ftp.password != "ftp-pw") throw PhpHostingException("ftp_login")
        if (ftp.dir.isEmpty() && webroot.isEmpty()) throw PhpHostingException("ftp_no_webroot", "htdocs,public_html")
        return PhpProbe(security = "tls", dir = ftp.dir.ifEmpty { webroot }, writable = true)
    }
    override suspend fun deploy(ftp: FtpTarget, token: String, onProgress: (PhpProgress) -> Unit): PhpInstalled {
        calls += "deploy"
        onProgress(PhpProgress(phase = "upload", n = 1, of = 3))
        uploaded = true
        if (token.isNotEmpty()) this.token = token
        return PhpInstalled(dir = ftp.dir, token = this.token, security = "none")
    }
    override suspend fun remove(ftp: FtpTarget) { calls += "remove"; uploaded = false }
    override suspend fun check(site: String, token: String, carrier: String): PhpStatus {
        calls += "check"
        if (siteDownFor-- > 0) throw PhpHostingException("site_unreachable", site)
        checkFails?.let { throw PhpHostingException(it, "usleep,openssl") }
        if (token != this.token) throw PhpHostingException("site_token")
        return PhpStatus()
    }
    override suspend fun start(site: String, token: String, carrier: String, target: String, chain: Boolean, quiet: Boolean): PhpNodeState {
        calls += "start:$carrier:$target"; running = true; return PhpNodeState(running = true)
    }
    override suspend fun stop(site: String, token: String, carrier: String, target: String) { calls += "stop"; running = false }
    override suspend fun node(site: String, token: String, carrier: String, target: String) = PhpNodeState(running = running)
    override suspend fun page(site: String, token: String, carrier: String, target: String) = "$site/mailruexit.php?k=$token"
    override suspend fun newRoom(): PhpRoom { calls += "newRoom"; return PhpRoom("r1", "https://cups.online/r1") }
    override suspend fun link(name: String, carrier: String, target: String) = "openflux://v1/test:{}"
}

class HostingModelTest {
    private val hosting = FakeHosting()
    private val conn = ScriptedConnection().apply { address = { ExitAddress.Known("185.27.134.10") } }
    private val c = testContainer(connection = conn, hosting = hosting)

    private fun TestScope.setup() = HostingSetupModel(c, this, NodeVerifier(c, now = { testScheduler.currentTime }))

    private fun HostingSetupModel.ftpForm() {
        ftpHost = "ftpupload.net"; ftpUser = "if0_1"; ftpPassword = "ftp-pw"; site = "https://me.example"
    }

    @Test
    fun ftpInstallUploadsStartsSavesAndProves() = runTest {
        hosting.webroot = "htdocs"
        val m = setup().apply { ftpForm() }
        m.install(); advanceUntilIdle()
        assertNull(m.error)
        assertTrue(hosting.uploaded && hosting.running)
        assertEquals("start:cupsonline:https://cups.online/r1", hosting.calls.first { it.startsWith("start") })
        val p = c.profiles.profiles.value.single()
        assertTrue(p.stream)
        assertEquals(ProfileSource.Node, p.source)
        assertEquals("https://me.example", p.phpNode?.siteUrl)
        assertEquals(Dest.Hosting(p.id), m.done)
        assertEquals("Этот хостинг принимает FTP только без шифрования: пароль FTP прошёл по сети открытым текстом. После установки смените его.", m.securityNote)
        assertEquals("", m.ftpPassword, "the password is forgotten once used")
    }

    @Test
    fun filesOnAHostThatCannotRunTheNodeCanBeRetriedOrRemoved() = runTest {
        hosting.webroot = "htdocs"
        hosting.checkFails = "php_missing"
        val m = setup().apply { ftpForm() }
        m.install(); advanceUntilIdle()
        assertTrue(m.error.orEmpty().isNotEmpty())
        assertFalse(hosting.calls.any { it.startsWith("start") }, "not started on a host that cannot run it")
        assertTrue(m.canRemove, "the files are there: removing them is offered")

        hosting.checkFails = null
        m.install(); advanceUntilIdle()
        assertNull(m.error, "a retry needs no FTP password typed again")
        assertEquals(1, hosting.calls.count { it == "deploy" }, "the files are not uploaded twice")
        assertTrue(hosting.running)
    }

    @Test
    fun filesLeftByAFailedInstallAreRemoved() = runTest {
        hosting.webroot = "htdocs"
        hosting.checkFails = "php_missing"
        val m = setup().apply { ftpForm() }
        m.install(); advanceUntilIdle()
        m.removeFailed(); advanceUntilIdle()
        assertFalse(hosting.uploaded)
        assertFalse(m.canRemove)
        assertTrue(c.profiles.profiles.value.isEmpty())
    }

    @Test
    fun anUnclearWebrootAsksForTheFolder() = runTest {
        val m = setup().apply { ftpForm() }
        m.install(); advanceUntilIdle()
        assertEquals(listOf("htdocs", "public_html"), m.folderChoices)
        m.chooseFolder("htdocs"); advanceUntilIdle()
        assertTrue(hosting.uploaded)
        assertTrue(m.folderChoices.isEmpty())
    }

    @Test
    fun aWrongFtpPasswordIsSaidAndNothingIsUploaded() = runTest {
        val m = setup().apply { ftpForm(); ftpPassword = "nope" }
        m.install(); advanceUntilIdle()
        assertEquals("FTP не принял логин или пароль. Пароль FTP на хостинге часто не совпадает с паролем от личного кабинета", m.error)
        assertFalse(hosting.uploaded)
    }

    @Test
    fun aNewSiteIsWaitedFor() = runTest {
        hosting.webroot = "htdocs"; hosting.siteDownFor = 3
        val m = setup().apply { ftpForm() }
        m.install(); advanceUntilIdle()
        assertNull(m.error)
        assertTrue(hosting.running)
    }

    @Test
    fun anExistingNodeIsAddedByItsPageAddress() = runTest {
        hosting.token = "abcdefgh12"
        val m = setup().apply { existing = true }
        m.onExistingAddress("https://me.example/cupsexit.php?k=abcdefgh12&room=0f8e3b6a-1c2d-4e5f-8a9b-0c1d2e3f4a5b")
        assertEquals("abcdefgh12", m.token)
        assertEquals(TransportType.CUPSONLINE, m.carrier)
        m.install(); advanceUntilIdle()
        assertNull(m.error)
        assertFalse("deploy" in hosting.calls)
        assertEquals("abcdefgh12", c.profiles.profiles.value.single().phpNode?.token)
        assertFalse(hosting.calls.any { it == "newRoom" }, "the room the address named is kept")
    }

    @Test
    fun aWrongKeyForAnExistingNodeIsExplained() = runTest {
        hosting.token = "abcdefgh12"
        val m = setup().apply { existing = true; site = "https://me.example"; token = "wrongkey99" }
        m.install(); advanceUntilIdle()
        assertTrue(m.error!!.startsWith("Нода не приняла этот ключ доступа"))
    }

    @Test
    fun mailruNeedsAPublicLink() = runTest {
        hosting.webroot = "htdocs"
        val m = setup().apply { ftpForm(); carrier = TransportType.MAILRU; mailru = "x" }
        m.install(); advanceUntilIdle()
        assertEquals("Нужна публичная ссылка на документ Mail.ru: https://cloud.mail.ru/public/…", m.error)
        assertFalse(hosting.uploaded)
    }

    @Test
    fun theNodeScreenStartsStopsAndForgets() = runTest {
        hosting.webroot = "htdocs"
        val s = setup().apply { ftpForm() }
        s.install(); advanceUntilIdle()
        val id = c.profiles.profiles.value.single().id
        val n = HostingNodeModel(c, id, this)
        n.stop(); advanceUntilIdle()
        assertFalse(hosting.running)
        assertEquals(false, n.state?.running)
        n.start(); advanceUntilIdle()
        assertTrue(hosting.running)
        n.refresh(); advanceUntilIdle()
        assertEquals(true, n.state?.running)
        n.copyToken()
        assertEquals("tok12345", (c.platform as TestPlatform).clipboard)
        n.forget()
        assertTrue(c.profiles.profiles.value.isEmpty())
    }
}
