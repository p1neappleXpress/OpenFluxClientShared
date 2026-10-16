package io.openflux.app

import io.openflux.app.account.SignInModel
import io.openflux.app.account.SignInUi
import io.openflux.desktop.model.AuthAttempt
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import io.openflux.desktop.service.SignInSupport
import io.openflux.desktop.service.SignInWay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInModelTest {
    private val back = "http://127.0.0.1:47621/callback"
    private val verifier = "v".repeat(43)

    class FakeSignIn(override val clientId: String = "openflux-test") : SignInSupport {
        val returns = Channel<String>(Channel.UNLIMITED)
        val pages = mutableListOf<String>()
        var begins = 0
        var closes = 0
        var beginError: BotException? = null
        var awaitError: Exception? = null
        override fun newAttempt() = AuthAttempt("st1", "v".repeat(43), "ch1")
        override suspend fun begin(state: String): SignInWay {
            begins++
            beginError?.let { throw it }
            return object : SignInWay {
                override val redirectUri = "http://127.0.0.1:47621/callback"
                override suspend fun await(): String { awaitError?.let { throw it }; return returns.receive() }
                override fun close() { closes++ }
            }
        }
        override fun openPage(url: String) { pages += url }
    }

    private class Api(val result: Any) : BotApi {
        val exchanges = mutableListOf<Triple<String, String, String>>()
        override suspend fun exchange(code: String, verifier: String, redirectUri: String): BotSession {
            exchanges += Triple(code, verifier, redirectUri)
            return when (result) { is BotSession -> result; is Exception -> throw result; else -> error("bad") }
        }
        override suspend fun subscription(token: String) = BotSubscription()
        override suspend fun signOut(token: String) = Unit
        override suspend fun resolve(connectToken: String): BotConnection = throw BotException("not_found", "Не найдено")
        override suspend fun sessions(token: String) = emptyList<BotSessionItem>()
        override suspend fun createSession(token: String): BotSessionItem = throw BotException("forbidden", "Нет доступа")
    }

    private val session = BotSession("ofs_a", 7, "", 1_900_000_000)

    @Test
    fun aCodeOnTheWayBackSignsIn() = runTest {
        val s = FakeSignIn(); val api = Api(session)
        val c = testContainer(bot = api, signIn = s)
        val m = SignInModel(c, this)
        m.start(); runCurrent()
        val waiting = assertIs<SignInUi.Waiting>(m.ui)
        assertEquals(listOf(waiting.pageUrl), s.pages)
        assertTrue(waiting.pageUrl.contains("client_id=openflux-test") && waiting.pageUrl.contains("state=st1") && waiting.pageUrl.contains("code_challenge=ch1"))
        s.returns.send("$back?state=st1&code=c1"); runCurrent()
        assertEquals(SignInUi.Done, m.ui)
        assertEquals(listOf(Triple("c1", verifier, back)), api.exchanges)
        assertEquals("ofs_a", c.bot.state.value.session?.token)
        assertTrue(s.closes >= 1, "the way back is closed")
    }

    @Test
    fun aReturnOfAnotherAttemptIsIgnored() = runTest {
        val s = FakeSignIn(); val api = Api(session)
        val m = SignInModel(testContainer(bot = api, signIn = s), this)
        m.start(); runCurrent()
        s.returns.send("$back?state=other&code=x"); runCurrent()
        assertIs<SignInUi.Waiting>(m.ui)
        assertTrue(api.exchanges.isEmpty())
        s.returns.send("$back?state=st1&code=c1"); runCurrent()
        assertEquals(SignInUi.Done, m.ui)
    }

    @Test
    fun aRefusalOnThePageIsSaid() = runTest {
        val s = FakeSignIn()
        val m = SignInModel(testContainer(bot = Api(session), signIn = s), this)
        m.start(); runCurrent()
        s.returns.send("$back?state=st1&error=access_denied"); runCurrent()
        assertEquals(SignInUi.Failed("Вход отменён"), m.ui)
    }

    @Test
    fun nobodyComingBackEndsTheWaitAndFreesThePort() = runTest {
        val s = FakeSignIn()
        val m = SignInModel(testContainer(bot = Api(session), signIn = s), this)
        m.start(); runCurrent()
        advanceTimeBy(15 * 60 * 1000L + 1); runCurrent()
        assertEquals(SignInUi.Expired, m.ui)
        assertTrue(s.closes >= 1)
    }

    @Test
    fun aFailedExchangeIsSaidInTheServicesWords() = runTest {
        val s = FakeSignIn()
        val c = testContainer(bot = Api(BotException("sign_in_failed", "Не получилось войти")), signIn = s)
        val m = SignInModel(c, this)
        m.start(); runCurrent()
        s.returns.send("$back?state=st1&code=c1"); runCurrent()
        assertEquals(SignInUi.Failed("Не получилось войти"), m.ui)
        assertNull(c.bot.state.value.session)
    }

    @Test
    fun aBuildWithoutAClientCannotBegin() = runTest {
        val s = FakeSignIn(clientId = "")
        val m = SignInModel(testContainer(bot = Api(session), signIn = s), this)
        m.start(); runCurrent()
        assertIs<SignInUi.Failed>(m.ui)
        assertTrue(s.pages.isEmpty())
    }

    @Test
    fun noWayBackIsSaid() = runTest {
        val s = FakeSignIn().apply { beginError = BotException("no_return", "порты заняты") }
        val m = SignInModel(testContainer(bot = Api(session), signIn = s), this)
        m.start(); runCurrent()
        assertEquals(SignInUi.Failed("порты заняты"), m.ui)
        assertTrue(s.pages.isEmpty())
    }

    @Test
    fun aReturnAfterCancelDoesNothing() = runTest {
        val s = FakeSignIn(); val api = Api(session)
        val m = SignInModel(testContainer(bot = api, signIn = s), this)
        m.start(); runCurrent()
        m.cancel(); runCurrent()
        assertEquals(SignInUi.Idle, m.ui)
        assertTrue(s.closes >= 1)
        s.returns.send("$back?state=st1&code=c1"); runCurrent()
        assertTrue(api.exchanges.isEmpty())
    }

    @Test
    fun aSecondStartWhileWaitingOpensNothingNew() = runTest {
        val s = FakeSignIn()
        val m = SignInModel(testContainer(bot = Api(session), signIn = s), this)
        m.start(); runCurrent(); m.start(); runCurrent()
        assertEquals(1, s.pages.size)
        assertEquals(1, s.begins)
        m.cancel()
    }

    @Test
    fun reopenAndCopyUseTheSamePage() = runTest {
        val s = FakeSignIn(); val platform = TestPlatform()
        val m = SignInModel(testContainer(bot = Api(session), signIn = s, platform = platform), this)
        m.start(); runCurrent()
        m.reopen(); m.copyLink()
        assertEquals(2, s.pages.size)
        assertEquals(s.pages[0], s.pages[1])
        assertEquals(s.pages[0], platform.clipboard)
        m.cancel()
    }

    @Test
    fun aNewerSignInTakingOverIsSaid() = runTest {
        val s = FakeSignIn().apply { awaitError = BotException("superseded", "Вход начат заново") }
        val m = SignInModel(testContainer(bot = Api(session), signIn = s), this)
        m.start(); runCurrent()
        assertEquals(SignInUi.Failed("Вход начат заново"), m.ui)
        assertTrue(s.closes >= 1)
    }

    @Test
    fun anAnswerNobodyExpectedEndsInWordsNotACrash() = runTest {
        val s = FakeSignIn()
        val m = SignInModel(testContainer(bot = Api(IllegalArgumentException("not an object")), signIn = s), this)
        m.start(); runCurrent()
        s.returns.send("$back?state=st1&code=c1"); runCurrent()
        assertEquals(SignInUi.Failed("Не получилось войти"), m.ui)
        assertTrue(s.closes >= 1)
    }
}
