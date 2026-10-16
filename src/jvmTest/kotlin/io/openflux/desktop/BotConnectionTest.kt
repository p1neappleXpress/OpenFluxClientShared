package io.openflux.desktop

import io.openflux.app.testContainer
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareTransport
import io.openflux.desktop.service.BotAccountManager
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import io.openflux.desktop.service.InMemoryBotRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotConnectionTest {
    private val session = BotSession("ofs_x", 7, "ivan", 1_900_000_000)

    private class Bot : BotApi {
        var sessions = mutableListOf<BotSessionItem>()
        var create: () -> BotSessionItem = { throw BotException("forbidden", BotAccountManager.HttpForbidden) }
        var created = 0
        val links = mutableMapOf<String, BotConnection>()
        var sessionsError: BotException? = null
        var signedOut = 0
        var subscription = BotSubscription(planId = "own30", planName = "Start", status = "active", isActive = true)
        override suspend fun subscription(token: String) = subscription
        override suspend fun signOut(token: String) { signedOut++ }
        override suspend fun resolve(connectToken: String) = links[connectToken] ?: throw BotException("not_found", "Не найдено")
        override suspend fun sessions(token: String): List<BotSessionItem> = sessionsError?.let { throw it } ?: sessions.toList()
        override suspend fun createSession(token: String): BotSessionItem { created++; return create().also { sessions += it } }
    }

    private suspend fun link(name: String, key: String) = FakeShareLinkCodec().encode(
        ShareConfig(
            name = name, negotiate = true, secret = key, context = "https://example.com/doc",
            transports = listOf(ShareTransport(type = "mailru", url = "https://cloud.mail.ru/public/aaaa/bbbb", priority = 90), ShareTransport(type = "direct", dial = "203.0.113.5:443", priority = 50)),
        ),
    )

    private fun blockingLink(key: String) = runBlocking { link("x", key) }

    private fun env(api: Bot = Bot()) = run {
        val c = testContainer()
        val accounts = testAccounts(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        Triple(BotAccountManager(api, InMemoryBotRepository(), c.profiles, c.shareCodec, c.platform, c.connection, accounts), c, api)
    }

    private val tokenA = "AAAAAAAAAAAAAAAAAAAAAA"
    private val tokenB = "BBBBBBBBBBBBBBBBBBBBBB"

    @Test
    fun signingInBringsTheRunningConnectionAsAProfile() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("OpenFlux #4", link("x", "ab".repeat(32)))
        m.completeLogin(session)
        val p = c.profiles.profiles.value.single()
        assertEquals(ProfileSource.Bot, p.source)
        assertEquals("OpenFlux #4", p.name)
        val st = m.state.value
        assertEquals(session, st.session)
        assertTrue(st.links.single().account)
        assertFalse(st.preparing)
        assertEquals(0, api.created, "a running connection is used, not doubled")
    }

    @Test
    fun withoutAConnectionOneIsMade() = runTest {
        val (m, c, api) = env()
        api.create = { BotSessionItem("s1", "running", tokenA).also { api.links[tokenA] = BotConnection("OpenFlux #5", blockingLink("cd".repeat(32))) } }
        m.completeLogin(session)
        assertEquals(1, api.created)
        assertEquals("OpenFlux #5", c.profiles.profiles.value.single().name)
    }

    @Test
    fun aConnectionBeingMadeAppearsWhenReady() = runTest {
        val (m, c, api) = env()
        api.create = { BotSessionItem("s1", "creating", "") }
        m.completeLogin(session)
        assertTrue(m.state.value.preparing)
        assertTrue(c.profiles.profiles.value.isEmpty(), "no profile before the link exists")
        assertTrue(m.state.value.links.none { it.gone })

        // Still being made: no second connection is asked for.
        m.refresh()
        assertEquals(1, api.created)
        assertTrue(m.state.value.preparing)

        api.sessions[0] = BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("OpenFlux #6", link("x", "ef".repeat(32)))
        m.refresh()
        assertFalse(m.state.value.preparing)
        assertEquals("OpenFlux #6", c.profiles.profiles.value.single().name)
        assertEquals(1, api.created)
    }

    @Test
    fun aLinkThatIsNotResolvableYetKeepsWaiting() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA) // no link in api.links yet
        m.completeLogin(session)
        assertTrue(m.state.value.preparing)
        assertNull(m.state.value.lastError)
        assertTrue(c.profiles.profiles.value.isEmpty())
    }

    @Test
    fun noFreeSlotSaysSo() = runTest {
        val (m, c, api) = env()
        m.completeLogin(session)
        assertEquals(BotAccountManager.NO_FREE_SLOT_TG, m.state.value.connectionNote)
        assertFalse(m.state.value.preparing)
        assertNull(m.state.value.lastError)
        assertEquals(session, m.state.value.session, "still signed in")
        assertTrue(c.profiles.profiles.value.isEmpty())
        // The subscription is renewed in the bot: the next refresh makes the connection and the note goes.
        api.create = { BotSessionItem("s1", "running", tokenA).also { api.links[tokenA] = BotConnection("OpenFlux #7", blockingLink("ab".repeat(32))) } }
        m.refresh()
        assertNull(m.state.value.connectionNote)
        assertEquals(1, c.profiles.profiles.value.size)
    }

    @Test
    fun aReplacedConnectionIsReplaced() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("OpenFlux #4", link("x", "ab".repeat(32)))
        m.completeLogin(session)
        val old = c.profiles.profiles.value.single()
        c.connection.connect(old, ConnectionMode.Client)

        // The bot stopped it and made another.
        api.links.remove(tokenA)
        api.sessions.clear()
        api.sessions += BotSessionItem("s2", "running", tokenB)
        api.links[tokenB] = BotConnection("OpenFlux #8", link("x", "cd".repeat(32)))
        m.refresh()

        val p = c.profiles.profiles.value.single()
        assertEquals("OpenFlux #8", p.name)
        assertEquals("cd".repeat(32), p.secret)
        assertFalse(c.connection.state.value is ConnectionState.Connected, "the tunnel through the old one stops")
        assertEquals(tokenB, m.state.value.links.single().token)
    }

    @Test
    fun signOutKeepsManualLinks() = runTest {
        val (m, c, api) = env()
        api.links[tokenB] = BotConnection("Ручная", link("x", "11".repeat(32)))
        m.addLink("https://link.openflux.dev/$tokenB")
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("Аккаунт", link("x", "22".repeat(32)))
        m.completeLogin(session)
        assertEquals(2, c.profiles.profiles.value.size)

        m.signOut()
        assertNull(m.state.value.session)
        assertEquals(listOf("Ручная"), c.profiles.profiles.value.map { it.name })
        assertEquals(listOf(tokenB), m.state.value.links.map { it.token })
        assertEquals(1, api.signedOut)
    }

    @Test
    fun anExpiredSessionEndsTheSignInButKeepsTheProfile() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("Аккаунт", link("x", "22".repeat(32)))
        m.completeLogin(session)
        api.sessionsError = BotException("unauthorized", "Войдите заново")
        m.refresh()
        assertNull(m.state.value.session)
        assertEquals(1, c.profiles.profiles.value.size, "the connection keeps working until the bot ends it")
    }

    @Test
    fun subscriptionFollowsTheBotAndSignOutAsksTheService() = runTest {
        val (m, _, api) = env()
        m.completeLogin(session)
        assertEquals("Start", m.state.value.subscription?.planName)
        m.signOut()
        assertNull(m.state.value.session)
        assertEquals(1, api.signedOut)
    }

    @Test
    fun theExchangeGoesToTheApi() = runTest {
        val api = object : BotApi by Bot() {
            override suspend fun exchange(code: String, verifier: String, redirectUri: String) = session
        }
        val c = testContainer()
        val m = BotAccountManager(api, InMemoryBotRepository(), c.profiles, c.shareCodec, c.platform, c.connection, testAccounts(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
        assertEquals(session, m.exchange("c", "v".repeat(43), "x:/y"))
    }

    // ---- fixes from the whole-branch review ----

    @Test
    fun aConnectionBeingMadeIsAskedAboutAgainWithoutAnyoneRefreshing() = runTest {
        val (m, c, api) = env()
        m.start(backgroundScope) // the app's loop begins signed out and would sleep for hours
        runCurrent()
        api.create = { BotSessionItem("s1", "creating", "") }
        m.completeLogin(session)
        assertTrue(m.state.value.preparing)
        api.sessions[0] = BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("OpenFlux #9", link("x", "12".repeat(32)))
        advanceTimeBy(16_000); runCurrent()
        assertEquals("OpenFlux #9", c.profiles.profiles.value.single().name)
        assertFalse(m.state.value.preparing)
    }

    @Test
    fun aLinkTheCoreCannotReadDoesNotCrashTheSignIn() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("Битая", "not-a-share-link")
        m.completeLogin(session) // must not throw
        assertEquals(session, m.state.value.session)
        assertTrue(m.state.value.lastError != null, "the person is told")
        assertFalse(m.state.value.preparing)
        assertTrue(c.profiles.profiles.value.isEmpty())
    }

    private class RefusingCodec(private val reason: Throwable) : io.openflux.desktop.model.ShareLinkCodec {
        override suspend fun encode(config: ShareConfig): String = throw reason
        override suspend fun decode(link: String): ShareConfig = throw reason
    }

    @Test
    fun aLinkTheCoreDoesNotKnowIsSaidWithItsReasonNotJustUpdate() = runTest {
        val api = Bot()
        val c = testContainer()
        val accounts = testAccounts(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val reason = io.openflux.desktop.model.ShareLinkException("unknown_transport", "avito")
        val m = BotAccountManager(api, InMemoryBotRepository(), c.profiles, RefusingCodec(reason), c.platform, c.connection, accounts)
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("Slot 2", "openflux://v1/anything")
        m.completeLogin(session)
        val said = m.state.value.lastError.orEmpty()
        assertTrue(said.contains("avito"), "it names the transport the core does not know: $said")
        assertTrue(said.startsWith("Не удалось прочитать подключение от бота:"), said)
        assertEquals(session, m.state.value.session, "still signed in")
        assertFalse(m.state.value.preparing)
    }

    @Test
    fun aPausedConnectionIsAProfileAndTheNoteSaysToResumeIt() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "paused", tokenA)
        api.links[tokenA] = BotConnection("Slot 2", link("x", "ab".repeat(32)))
        m.completeLogin(session)
        assertEquals("Slot 2", c.profiles.profiles.value.single().name, "the paused connection is added: it works once resumed")
        assertTrue(m.state.value.links.single().account)
        assertEquals(1, api.created, "a new one is still asked for: a free automatic slot may be there")
        assertEquals(BotAccountManager.PAUSED_NOTE, m.state.value.connectionNote)
        assertFalse(m.state.value.preparing, "not waiting for a connection that is not coming")
    }

    // ---- each connection's state, for the window ----

    @Test
    fun eachConnectionOfTheAccountKeepsItsState() = runTest {
        val (m, _, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.sessions += BotSessionItem("s2", "paused", tokenB)
        api.links[tokenA] = BotConnection("OpenFlux #1", link("x", "ab".repeat(32)))
        api.links[tokenB] = BotConnection("OpenFlux #2", link("x", "cd".repeat(32)))
        m.completeLogin(session)
        assertEquals(mapOf(tokenA to "running", tokenB to "paused"), m.state.value.links.associate { it.token to it.status })

        api.sessions[1] = BotSessionItem("s2", "running", tokenB) // resumed in the bot
        m.refresh()
        assertEquals("running", m.state.value.links.first { it.token == tokenB }.status, "the window follows the bot")
    }

    // ---- the words, by how the person signed in ----

    private val byEmail = BotSession("ofs_e", 10_000_000_000_000_001, "", 1_900_000_000)

    @Test
    fun withoutASubscriptionTheNoteSaysToBuyHere() = runTest {
        val (m, _, api) = env()
        api.subscription = BotSubscription(planId = "none", status = "inactive", isActive = false)
        m.completeLogin(byEmail)
        assertEquals(BotAccountManager.NO_SUBSCRIPTION, m.state.value.connectionNote)
        m.signOut()
        m.completeLogin(session)
        assertEquals(BotAccountManager.NO_SUBSCRIPTION, m.state.value.connectionNote, "the same for Telegram: buying is here now")
    }

    @Test
    fun anEmailAccountIsNeverSentToTheBot() = runTest {
        val (m, _, api) = env()
        m.completeLogin(byEmail)
        assertEquals(BotAccountManager.NO_FREE_SLOT, m.state.value.connectionNote)
        assertFalse("@" in m.state.value.connectionNote!! || "бот" in m.state.value.connectionNote!!, "an email account has no bot")

        // The service's own words speak of the bot: not for an email account.
        api.create = { throw BotException("forbidden", "Место занимает приостановленное подключение: освободите его в боте, в «Мои подключения».") }
        m.refresh()
        assertEquals(BotAccountManager.NO_FREE_SLOT, m.state.value.connectionNote)
    }

    @Test
    fun aPausedConnectionOfAnEmailAccountIsNotSentToTheBot() = runTest {
        val (m, _, api) = env()
        api.sessions += BotSessionItem("s1", "paused", tokenA)
        api.links[tokenA] = BotConnection("Slot 2", link("x", "ab".repeat(32)))
        m.completeLogin(byEmail)
        assertEquals(BotAccountManager.PAUSED_NOTE_EMAIL, m.state.value.connectionNote)
        assertFalse("бот" in BotAccountManager.PAUSED_NOTE_EMAIL)
    }

    @Test
    fun theWordsSayWhatToDoHere() {
        for (note in listOf(BotAccountManager.NO_SUBSCRIPTION, BotAccountManager.NO_FREE_SLOT, BotAccountManager.NO_FREE_SLOT_TG, BotAccountManager.PAUSED_NOTE_EMAIL)) {
            assertTrue("«Подписка»" in note, note)
            assertFalse("Продлите подписку в" in note, "renewing is not the only reason: $note")
        }
    }

    @Test
    fun theSignInWayIsKnownFromTheAccountNumber() {
        assertTrue(session.viaTelegram)
        assertFalse(byEmail.viaTelegram)
    }

    @Test
    fun aResumedConnectionNeedsNothingElse() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "paused", tokenA)
        api.links[tokenA] = BotConnection("Slot 2", link("x", "ab".repeat(32)))
        m.completeLogin(session)
        api.sessions[0] = BotSessionItem("s1", "running", tokenA) // resumed in the bot
        m.refresh()
        assertNull(m.state.value.connectionNote)
        assertEquals(1, c.profiles.profiles.value.size, "the same profile, not another")
    }

    @Test
    fun theServicesOwnReasonForNoSlotIsShown() = runTest {
        val (m, _, api) = env()
        val words = "Все ваши оплаченные подключения (2 из 2) уже активны. Место занимает приостановленное подключение."
        api.create = { throw BotException("forbidden", words) }
        m.completeLogin(session)
        assertEquals(words, m.state.value.connectionNote)
    }

    @Test
    fun everyConnectionOfTheAccountIsAProfileAndTheOneInUseStays() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("OpenFlux #4", link("x", "ab".repeat(32)))
        m.completeLogin(session)
        val first = c.profiles.profiles.value.single().id
        c.connection.connect(c.profiles.profiles.value.single(), ConnectionMode.Client)

        api.sessions += BotSessionItem("s2", "running", tokenB)
        api.links[tokenB] = BotConnection("OpenFlux #5", link("x", "cd".repeat(32)))
        api.sessions.reverse() // the service lists the other one first now
        m.refresh()

        assertEquals(setOf("OpenFlux #4", "OpenFlux #5"), c.profiles.profiles.value.map { it.name }.toSet(), "both connections are here")
        assertTrue(c.profiles.profiles.value.any { it.id == first }, "the profile in use stays")
        assertTrue(c.connection.state.value is ConnectionState.Connected, "its tunnel keeps running")
        assertEquals(0, api.created, "nothing is made while connections run")
    }

    @Test
    fun aConnectionThatIsOnlyRestartingKeepsItsProfile() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("OpenFlux #4", link("x", "ab".repeat(32)))
        m.completeLogin(session)
        c.connection.connect(c.profiles.profiles.value.single(), ConnectionMode.Client)
        api.sessions[0] = BotSessionItem("s1", "provisioning", tokenA) // restarting: not a reason to cut the tunnel
        m.refresh()
        assertEquals(1, c.profiles.profiles.value.size)
        assertTrue(c.connection.state.value is ConnectionState.Connected)
    }

    @Test
    fun noSecondConnectionIsMadeWhileAnotherIsNotReady() = runTest {
        for (st in listOf(BotSessionItem("s1", "running", ""), BotSessionItem("s1", "starting", ""))) {
            val (m, c, api) = env()
            api.sessions += st
            m.completeLogin(session)
            assertEquals(0, api.created, "no connection is made over ${st.status} without a token")
            assertTrue(m.state.value.preparing)
            assertTrue(c.profiles.profiles.value.isEmpty())
        }
    }
}
