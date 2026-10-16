package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import io.openflux.app.shell.OpenFluxApp
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The sign-in screen driven the way a person does it; the "browser" comes back at once. */
@OptIn(ExperimentalTestApi::class)
class SignInScreenTest {
    /** Opening the page is answered by the page itself: back with [query] (state is the attempt's). */
    private class InstantBrowser(val query: String) : SignInSupport {
        private val back = Channel<String>(Channel.UNLIMITED)
        override val clientId = "openflux-test"
        override fun newAttempt() = AuthAttempt("st1", "v".repeat(43), "ch1")
        override suspend fun begin(state: String) = object : SignInWay {
            override val redirectUri = "http://127.0.0.1:47621/callback"
            override suspend fun await() = back.receive()
            override fun close() = Unit
        }
        override fun openPage(url: String) { back.trySend("http://127.0.0.1:47621/callback?$query") }
    }

    private class Api(val session: BotSession?) : BotApi {
        override suspend fun exchange(code: String, verifier: String, redirectUri: String) =
            session ?: throw BotException("sign_in_failed", "Не получилось войти. Если входили по почте, подтвердите адрес")
        override suspend fun subscription(token: String) = BotSubscription()
        override suspend fun signOut(token: String) = Unit
        override suspend fun resolve(connectToken: String): BotConnection = throw BotException("not_found", "Не найдено")
        override suspend fun sessions(token: String) = emptyList<BotSessionItem>()
        override suspend fun createSession(token: String): BotSessionItem = throw BotException("forbidden", "Нет доступа")
    }

    private fun open(c: io.openflux.desktop.service.AppContainer) =
        c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.Auth) }.snapshot().encode()) }

    @Test
    fun theButtonOpensThePageAndTheReturnSignsIn() = runComposeUiTest {
        val c = testContainer(bot = Api(BotSession("ofs_ui", 7, "", 1_900_000_000)), signIn = InstantBrowser("state=st1&code=c1"))
        open(c)
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Вход в OpenFlux").assertIsDisplayed()
        onNodeWithText("Войти").performClick()
        waitUntil(timeoutMillis = 10_000) { c.bot.state.value.session != null }
        assertEquals("ofs_ui", c.bot.state.value.session?.token)
    }

    @Test
    fun aFailedSignInIsSaidAndCanBeRetried() = runComposeUiTest {
        val c = testContainer(bot = Api(null), signIn = InstantBrowser("state=st1&code=c1"))
        open(c)
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Войти").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("подтвердите адрес", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Повторить").assertIsDisplayed()
        assertNull(c.bot.state.value.session)
    }
}
