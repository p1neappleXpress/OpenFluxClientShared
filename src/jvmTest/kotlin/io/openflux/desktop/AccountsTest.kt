package io.openflux.desktop

import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AccountSession
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.model.MailruCloud
import io.openflux.desktop.service.AccountBrowser
import io.openflux.desktop.service.AccountException
import io.openflux.desktop.service.AccountRepository
import io.openflux.desktop.service.Accounts
import io.openflux.desktop.service.ProbeResult
import io.openflux.desktop.service.SessionProbe
import io.openflux.desktop.ui.BrowserPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoryAccounts : AccountRepository {
    override val sessions = MutableStateFlow<Map<AccountKind, AccountSession>>(emptyMap())
    override fun save(session: AccountSession) { sessions.value = sessions.value + (session.kind to session) }
    override fun remove(kind: AccountKind) { sessions.value = sessions.value - kind }
}

class FakeBrowser : AccountBrowser {
    override val page: StateFlow<BrowserPage?> = MutableStateFlow(null)
    override var url = ""
    override var loading = false
    override var closed = true
    var jar = mapOf<String, String>()
    val opened = mutableListOf<Pair<String, Map<String, String>>>()
    var script: (String) -> String = { """{"state":"done","url":"https://docs.yandex.ru/edit/d/AAAAAAAAAAAAAAAAAAAA?x=1"}""" }
    var onOpen: FakeBrowser.(String) -> Unit = {}

    override suspend fun open(kind: AccountKind, url: String, cookies: Map<String, String>, onStep: (String) -> Unit) {
        opened += url to cookies
        closed = false
        this.url = url
        jar = cookies
        onOpen(url)
    }

    val loaded = mutableListOf<String>()
    var onLoad: FakeBrowser.(String) -> Unit = {}
    override fun load(url: String) {
        loaded += url
        this.url = url
        onLoad(url)
    }
    override suspend fun evaluate(script: String) = this.script(script)
    override suspend fun cookies(kind: AccountKind) = jar
    override fun close() {
        closed = true
        jar = emptyMap()
    }
}

class FakeProbe(var result: ProbeResult) : SessionProbe {
    override suspend fun probe(kind: AccountKind, cookies: Map<String, String>) = result
}

/** Accounts over in-memory fakes, for tests that build an AppContainer. */
fun testAccounts(
    scope: CoroutineScope,
    repo: AccountRepository = MemoryAccounts(),
    browser: FakeBrowser = FakeBrowser(),
    probe: FakeProbe = FakeProbe(ProbeResult.SignedIn),
) = Accounts(repo, browser, probe, now = { 1_000_000L }, scope = scope)

class AccountsTest {
    private val login = mapOf("Session_id" to "s", "yandex_login" to "ivan")

    @Test
    fun signInStoresSessionOnceLoggedIn() = runTest {
        val browser = FakeBrowser().apply { onOpen = { url = "https://disk.yandex.ru/client/disk"; jar = login } }
        val repo = MemoryAccounts()
        val a = testAccounts(this, repo, browser)
        val session = a.signIn(AccountKind.Yandex)
        assertEquals("ivan", session.login)
        assertEquals(login, repo.sessions.value.getValue(AccountKind.Yandex).cookies)
        assertIs<AuthStatus.SignedIn>(a.statusOf(AccountKind.Yandex))
        assertTrue(browser.closed)
    }

    @Test
    fun mailruSignInCarriesOnToCloud() = runTest {
        val jar = mapOf("Mpop" to "1700000000:abc:ivan@mail.ru:")
        val browser = FakeBrowser().apply {
            // VK ID leaves the page on VK; Cloud then gets its own cookie.
            onOpen = { url = "https://m.vk.ru/feed"; this.jar = jar }
            onLoad = { this.jar = this.jar + ("sdcs" to "x") }
        }
        val session = testAccounts(this, browser = browser).signIn(AccountKind.Mailru)
        assertEquals("ivan@mail.ru", session.login)
        assertEquals(listOf("https://cloud.mail.ru/home/"), browser.loaded)
        assertEquals("x", session.cookies["sdcs"])
    }

    @Test
    fun mailruDocumentIsPublicCloudLink() = runTest {
        val repo = MemoryAccounts().apply {
            save(AccountSession(AccountKind.Mailru, "ivan@mail.ru", mapOf("Mpop" to "a:b:ivan@mail.ru:", "sdcs" to "x"), 1, 1))
        }
        val browser = FakeBrowser().apply {
            onOpen = { url = "https://cloud.mail.ru/home" }
            script = { """{"state":"done","url":"https://cloud.mail.ru/public/n2CE/cKhGUVKw1"}""" }
        }
        val url = testAccounts(this, repo, browser).createDocument(AccountKind.Mailru, "doc")
        assertEquals("https://cloud.mail.ru/public/n2CE/cKhGUVKw1", url)
        assertEquals(MailruCloud.HOME, browser.opened.single().first)
    }

    @Test
    fun signInCancelledWhenPageClosed() = runTest {
        val browser = FakeBrowser().apply { onOpen = { closed = true } }
        val a = testAccounts(this, browser = browser)
        val e = assertFailsWith<AccountException> { a.signIn(AccountKind.Yandex) }
        assertTrue(e.cancelled)
        assertEquals(AuthStatus.SignedOut, a.statusOf(AccountKind.Yandex))
    }

    @Test
    fun maxHasNoBrowserSignIn() = runTest {
        val browser = FakeBrowser()
        val e = assertFailsWith<AccountException> { testAccounts(this, browser = browser).signIn(AccountKind.Max) }
        assertEquals(false, e.cancelled)
        assertTrue(browser.opened.isEmpty())
    }

    @Test
    fun createDocumentReusesSessionWithoutSignIn() = runTest {
        val repo = MemoryAccounts().apply { save(AccountSession(AccountKind.Yandex, "ivan", login, 1, 1)) }
        val browser = FakeBrowser().apply { onOpen = { url = "https://disk.yandex.ru/client/disk" } }
        val url = testAccounts(this, repo, browser).createDocument(AccountKind.Yandex, "doc-20260928-1200-abcd")
        assertEquals("https://docs.yandex.ru/edit/d/AAAAAAAAAAAAAAAAAAAA", url)
        assertEquals(AccountKind.Yandex.homeUrl, browser.opened.single().first)
        assertEquals(login, browser.opened.single().second)
        assertTrue(browser.closed)
    }

    @Test
    fun createDocumentSignsInFirstWithoutSession() = runTest {
        val browser = FakeBrowser().apply {
            onOpen = { url = "https://disk.yandex.ru/client/disk"; if (it == AccountKind.Yandex.signInUrl) jar = login }
        }
        testAccounts(this, browser = browser).createDocument(AccountKind.Yandex, "d")
        assertEquals(listOf(AccountKind.Yandex.signInUrl, AccountKind.Yandex.homeUrl), browser.opened.map { it.first })
        assertEquals(login, browser.opened[1].second)
    }

    @Test
    fun createDocumentMarksExpiredWhenYandexAsksToSignIn() = runTest {
        val repo = MemoryAccounts().apply { save(AccountSession(AccountKind.Yandex, "ivan", login, 1, 1)) }
        val browser = FakeBrowser().apply { onOpen = { url = "https://passport.yandex.ru/auth?retpath=x" } }
        val a = testAccounts(this, repo, browser)
        val e = assertFailsWith<AccountException> { a.createDocument(AccountKind.Yandex, "d") }
        assertTrue(e.expired)
        assertTrue(repo.sessions.value.getValue(AccountKind.Yandex).expired)
        assertIs<AuthStatus.Expired>(a.statusOf(AccountKind.Yandex))
    }

    @Test
    fun createDocumentReportsScriptFailure() = runTest {
        val repo = MemoryAccounts().apply { save(AccountSession(AccountKind.Yandex, "ivan", login, 1, 1)) }
        val browser = FakeBrowser().apply {
            onOpen = { url = "https://disk.yandex.ru/client/disk" }
            script = { """{"state":"fail","error":"mpfs/mkdir: 403"}""" }
        }
        val e = assertFailsWith<AccountException> { testAccounts(this, repo, browser).createDocument(AccountKind.Yandex, "d") }
        assertTrue("mpfs/mkdir: 403" in e.message.orEmpty())
    }


    @Test
    fun checkMarksExpired() = runTest {
        val repo = MemoryAccounts().apply { save(AccountSession(AccountKind.Yandex, "ivan", login, 1, 1)) }
        val a = testAccounts(this, repo, probe = FakeProbe(ProbeResult.Expired))
        assertIs<AuthStatus.Expired>(a.check(AccountKind.Yandex))
        assertTrue(repo.sessions.value.getValue(AccountKind.Yandex).expired)
    }

    @Test
    fun checkOfflineKeepsSession() = runTest {
        val repo = MemoryAccounts().apply { save(AccountSession(AccountKind.Yandex, "ivan", login, 1, 1)) }
        val a = testAccounts(this, repo, probe = FakeProbe(ProbeResult.Offline))
        assertIs<AuthStatus.Failed>(a.check(AccountKind.Yandex))
        assertEquals(false, repo.sessions.value.getValue(AccountKind.Yandex).expired)
    }

    @Test
    fun signOutForgetsSession() = runTest {
        val repo = MemoryAccounts().apply { save(AccountSession(AccountKind.Yandex, "ivan", login, 1, 1)) }
        val a = testAccounts(this, repo)
        a.signOut(AccountKind.Yandex)
        assertEquals(AuthStatus.SignedOut, a.statusOf(AccountKind.Yandex))
        assertEquals(emptyMap(), repo.sessions.value)
    }
}
