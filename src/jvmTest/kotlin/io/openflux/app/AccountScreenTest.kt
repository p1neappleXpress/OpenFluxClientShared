package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotInvoice
import io.openflux.desktop.model.BotPlan
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotState
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/** The subscription window driven as a person does: the OpenFlux account first, buying, and following the bot. */
@OptIn(ExperimentalTestApi::class)
class AccountScreenTest {
    private class Api : BotApi {
        val asked = AtomicInteger()
        @Volatile var sub = BotSubscription(planId = "own30", planName = "Start", status = "active", expiresAt = 1_900_000_000, maxSessions = 1, isActive = true)
        override suspend fun subscription(token: String): BotSubscription { asked.incrementAndGet(); return sub }
        override suspend fun signOut(token: String) = Unit
        override suspend fun resolve(connectToken: String): BotConnection = throw BotException("not_found", "Не найдено")
        override suspend fun sessions(token: String) = emptyList<BotSessionItem>()
        override suspend fun createSession(token: String): BotSessionItem = throw BotException("forbidden", "x")
        override suspend fun plans(token: String?) = listOf(BotPlan("own30", "Start", 30, 32212254720, "OpenFlux — подключение для одного устройства", 3000))
        override suspend fun createInvoice(token: String, termCode: String, idempotencyKey: String, gateway: String) =
            BotInvoice("0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11", "https://pay.example/x", "30.00", "active", 1_800_000_000, 1_800_086_400)
        override suspend fun invoice(token: String, id: String) =
            BotInvoice("0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11", "https://pay.example/x", "30.00", "active", 1_800_000_000, 1_800_086_400)
    }

    private fun open(c: AppContainer) = c.settings.update { it.copy(navState = AppNavigator().apply { open(Dest.Account) }.snapshot().encode()) }

    @Test
    fun signedOutTheWayIsToSignInAndKeysAreFolded() = runComposeUiTest {
        val c = testContainer()
        open(c)
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Войти в аккаунт OpenFlux").assertIsDisplayed()
        onNodeWithText("Подписка по ключу").performScrollTo().assertIsDisplayed()
        onNodeWithText("У меня ключ подписки").assertDoesNotExist()
    }

    @Test
    fun aSignedInSubscriptionIsShownFirstNeverAsNone() = runComposeUiTest {
        val api = Api()
        val c = testContainer(bot = api)
        c.botRepo.save(BotState(session = BotSession("ofs_x", 7, "ivan"), subscription = api.sub))
        open(c)
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Telegram, @ivan").assertIsDisplayed()
        onNodeWithText("Действует").assertIsDisplayed()
        onNodeWithText("Купить ещё место").assertIsDisplayed()
        onNodeWithText("На этом устройстве нет подписки.").assertDoesNotExist()
    }

    @Test
    fun theOpenWindowAsksTheServiceByItself() = runComposeUiTest {
        val api = Api()
        val c = testContainer(bot = api)
        c.botRepo.save(BotState(session = BotSession("ofs_x", 7, "ivan"), subscription = api.sub))
        val loop = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            c.bot.start(loop)
            waitUntil(timeoutMillis = 10_000) { api.asked.get() >= 1 } // the app's own first round
            // A slot bought in the bot meanwhile: the app's loop sleeps for a quarter of an hour, the window does not wait.
            api.sub = api.sub.copy(maxSessions = 2)
            open(c)
            setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Мест")).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            loop.cancel()
        }
    }

    @Test
    fun buyingOpensThePaymentPageAndTheWindowWaitsForIt() = runComposeUiTest {
        val api = Api()
        val platform = TestPlatform()
        val c = testContainer(bot = api, platform = platform)
        c.botRepo.save(BotState(session = BotSession("ofs_x", 10_000_000_000_000_001), subscription = BotSubscription(planId = "none", status = "inactive")))
        open(c)
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Нет действующей подписки").assertIsDisplayed()
        onNodeWithText("Купить подписку").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Оплатить 30 ₽")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Оплатить 30 ₽").performClick()
        waitUntil(timeoutMillis = 5_000) { platform.opened.isNotEmpty() }
        assertEquals(listOf("https://pay.example/x"), platform.opened)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Открыть оплату снова")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("active", c.bot.state.value.pendingInvoice?.invoice?.status)
    }
}
