package io.openflux.desktop

import io.openflux.app.testContainer
import io.openflux.desktop.data.FileBotRepository
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotInvoice
import io.openflux.desktop.model.BotPendingInvoice
import io.openflux.desktop.model.BotPlan
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotState
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.service.BotAccountManager
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import io.openflux.desktop.service.InMemoryBotRepository
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Buying a slot from the app: the invoice, its key kept across a lost call, waiting for the payment. */
class BotPurchaseTest {
    private val session = BotSession("ofs_x", 7, "ivan", 1_900_000_000)
    private val invoiceId = "0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11"

    private class Bot : BotApi {
        val plansOnSale = mutableListOf(BotPlan("own30", "Start", 30, 32212254720, "", 3000))
        val invoiceCalls = mutableListOf<Triple<String, String, String>>()
        var create: (term: String) -> BotInvoice = { BotInvoice("0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11", "https://pay.example/x", "30.00", "active", 1_760_500_000, 1_760_586_400) }
        var asked = 0
        var status: () -> BotInvoice = { BotInvoice("0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11", "https://pay.example/x", "30.00", "active") }
        var subscriptionCalls = 0
        var plansToken: String? = "unset"
        override suspend fun subscription(token: String): BotSubscription { subscriptionCalls++; return BotSubscription(isActive = true) }
        override suspend fun signOut(token: String) {}
        override suspend fun resolve(connectToken: String): BotConnection = throw BotException("not_found", "Не найдено")
        override suspend fun sessions(token: String): List<BotSessionItem> = emptyList()
        var createCalls = 0
        override suspend fun createSession(token: String): BotSessionItem { createCalls++; throw BotException("forbidden", BotAccountManager.HttpForbidden) }
        override suspend fun plans(token: String?): List<BotPlan> { plansToken = token; return plansOnSale }
        override suspend fun createInvoice(token: String, termCode: String, idempotencyKey: String, gateway: String): BotInvoice {
            invoiceCalls += Triple(token, termCode, idempotencyKey)
            return create(termCode)
        }
        override suspend fun invoice(token: String, id: String): BotInvoice { asked++; return status() }
    }

    private var keys = 0
    private val platform = io.openflux.app.TestPlatform()
    private fun env(api: Bot = Bot(), repo: io.openflux.desktop.service.BotRepository = InMemoryBotRepository()) = run {
        val c = testContainer(platform = platform)
        val accounts = testAccounts(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val m = BotAccountManager(api, repo, c.profiles, c.shareCodec, c.platform, c.connection, accounts, newIdempotencyKey = { "key-${++keys}-of-the-test" })
        Pair(m, api)
    }

    private suspend fun signedIn(api: Bot = Bot()) = env(api).also { (m, _) -> m.completeLogin(session) }

    @Test
    fun theTermsAreAskedForWithTheSessionWhenThereIsOne() = runTest {
        val (m, api) = env()
        assertEquals(listOf("own30"), m.plans().map { it.id })
        assertNull(api.plansToken, "signed out: what anybody may buy")
        m.completeLogin(session)
        m.plans()
        assertEquals("ofs_x", api.plansToken)
    }

    @Test
    fun anInvoiceIsMadeWithANewKeyAndKeptAsPending() = runTest {
        val (m, api) = signedIn()
        val inv = m.checkout("own30")
        assertEquals("https://pay.example/x", inv.payUrl)
        assertEquals(Triple("ofs_x", "own30", "key-1-of-the-test"), api.invoiceCalls.single())
        assertEquals(BotPendingInvoice("own30", "key-1-of-the-test", inv), m.state.value.pendingInvoice)
    }

    @Test
    fun aCallLostOnTheWayIsRepeatedWithTheSameKey() = runTest {
        val api = Bot()
        val (m, _) = signedIn(api)
        api.create = { throw BotException("network", "Сервис OpenFlux не отвечает. Проверьте сеть.") }
        assertEquals("network", assertFailsWith<BotException> { m.checkout("own30") }.code)
        assertEquals(BotPendingInvoice("own30", "key-1-of-the-test", null), m.state.value.pendingInvoice, "the key is kept: the service may have made the invoice")

        api.create = { BotInvoice(invoiceId, "https://pay.example/x", "30.00", "active") }
        m.checkout("own30")
        assertEquals(listOf("key-1-of-the-test", "key-1-of-the-test"), api.invoiceCalls.map { it.third }, "no second invoice for the same purchase")
    }

    @Test
    fun theSameTermWhileItsInvoiceWaitsGivesThatInvoiceBack() = runTest {
        val (m, api) = signedIn()
        val first = m.checkout("own30")
        assertEquals(first, m.checkout("own30"))
        assertEquals(1, api.invoiceCalls.size)
    }

    @Test
    fun anotherTermIsANewPurchaseWithANewKey() = runTest {
        val (m, api) = signedIn()
        m.checkout("own30")
        m.checkout("own90")
        assertEquals(listOf("key-1-of-the-test", "key-2-of-the-test"), api.invoiceCalls.map { it.third })
        assertEquals("own90", m.state.value.pendingInvoice?.termCode)
    }

    @Test
    fun aRefusalLeavesNothingPending() = runTest {
        val api = Bot()
        val (m, _) = signedIn(api)
        api.create = { throw BotException("slot_limit", "Больше мест на этом аккаунте взять нельзя") }
        assertEquals("slot_limit", assertFailsWith<BotException> { m.checkout("own30") }.code)
        assertNull(m.state.value.pendingInvoice)
        api.create = { BotInvoice(invoiceId) }
        m.checkout("own30")
        assertEquals("key-2-of-the-test", api.invoiceCalls.last().third, "a refused key is not reused")
    }

    @Test
    fun signedOutNothingIsBought() = runTest {
        val (m, api) = env()
        assertEquals("unauthorized", assertFailsWith<BotException> { m.checkout("own30") }.code)
        assertTrue(api.invoiceCalls.isEmpty())
    }

    @Test
    fun aPaidInvoiceBringsTheSubscriptionInAndStaysToBeSeen() = runTest {
        val (m, api) = signedIn()
        m.checkout("own30")
        val before = api.subscriptionCalls
        api.status = { BotInvoice(invoiceId, "https://pay.example/x", "30.00", "paid") }
        val inv = m.checkInvoice()
        assertTrue(inv!!.paid)
        assertTrue(api.subscriptionCalls > before, "the subscription is asked again at once")
        assertTrue(m.state.value.pendingInvoice!!.invoice!!.paid, "kept until the person has seen it")

        // A new purchase after a paid one is new, with a new key.
        api.create = { BotInvoice(invoiceId.replace('0', '1'), "https://pay.example/y", "30.00", "active") }
        m.checkout("own30")
        assertEquals("key-2-of-the-test", api.invoiceCalls.last().third)
    }

    @Test
    fun aFreeInvoicePaidAtOnceBringsTheSubscriptionIn() = runTest {
        val (m, api) = signedIn()
        api.create = { BotInvoice(invoiceId, "", "0.00", "paid") }
        val before = api.subscriptionCalls
        assertTrue(m.checkout("own30").paid)
        assertTrue(api.subscriptionCalls > before)
        assertEquals(0, api.asked, "nothing to wait for")
    }

    @Test
    fun anExpiredInvoiceIsSaidAndANewOneCanBeMade() = runTest {
        val (m, api) = signedIn()
        m.checkout("own30")
        api.status = { BotInvoice(invoiceId, "https://pay.example/x", "30.00", "expired") }
        assertTrue(m.checkInvoice()!!.expired)
        assertTrue(m.state.value.pendingInvoice!!.invoice!!.expired)
        m.checkout("own30")
        assertEquals(2, api.invoiceCalls.size, "an expired invoice is not given back")
    }

    @Test
    fun anInvoiceTheServiceDoesNotKnowIsForgotten() = runTest {
        val (m, api) = signedIn()
        m.checkout("own30")
        api.status = { throw BotException("not_found", "Не найдено") }
        assertNull(m.checkInvoice())
        assertNull(m.state.value.pendingInvoice)
    }

    @Test
    fun aFailedQuestionKeepsTheInvoice() = runTest {
        val (m, api) = signedIn()
        m.checkout("own30")
        api.status = { throw BotException("network", "Сервис OpenFlux не отвечает. Проверьте сеть.") }
        assertEquals("network", assertFailsWith<BotException> { m.checkInvoice() }.code)
        assertEquals("active", m.state.value.pendingInvoice?.invoice?.status)
    }

    @Test
    fun dismissingForgetsTheInvoice() = runTest {
        val (m, _) = signedIn()
        m.checkout("own30")
        m.dismissInvoice()
        assertNull(m.state.value.pendingInvoice)
    }

    @Test
    fun signingOutForgetsTheInvoice() = runTest {
        val (m, _) = signedIn()
        m.checkout("own30")
        m.signOut()
        assertNull(m.state.value.pendingInvoice)
    }

    @Test
    fun aRefreshNoticesAPaymentMadeWhileTheAppWasClosed() = runTest {
        val (m, api) = signedIn()
        m.checkout("own30")
        api.status = { BotInvoice(invoiceId, "https://pay.example/x", "30.00", "paid") }
        val before = api.subscriptionCalls
        m.refresh()
        assertTrue(m.state.value.pendingInvoice!!.invoice!!.paid)
        assertTrue(api.subscriptionCalls > before, "the subscription is asked after the payment was seen")
    }

    @Test
    fun aRefreshDoesNotAskAboutAnInvoiceAlreadySettled() = runTest {
        val (m, api) = signedIn()
        api.create = { BotInvoice(invoiceId, "", "0.00", "paid") }
        m.checkout("own30")
        m.refresh()
        assertEquals(0, api.asked)
    }

    @Test
    fun theLoopAsksAboutAWaitingInvoiceOftenThenGoesBackToSleep() = runTest {
        val (m, api) = signedIn()
        m.checkout("own30")
        m.start(backgroundScope)
        runCurrent()
        val first = api.asked
        advanceTimeBy(31_000); runCurrent()
        assertTrue(api.asked > first, "a waiting invoice is asked about every half a minute")

        api.status = { BotInvoice(invoiceId, "https://pay.example/x", "30.00", "paid") }
        advanceTimeBy(31_000); runCurrent()
        val settled = api.asked
        advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals(settled, api.asked, "a paid one is not asked about again")
    }

    // ---- the window follows the bot ----

    @Test
    fun anOpenWindowRefreshesAtOnceAndThenOften() = runTest {
        val (m, api) = signedIn()
        m.start(backgroundScope)
        runCurrent()
        val start = api.subscriptionCalls
        val watch = m.watch()
        runCurrent()
        assertEquals(start + 1, api.subscriptionCalls, "opening the window asks at once")
        advanceTimeBy(21_000); runCurrent()
        assertEquals(start + 2, api.subscriptionCalls, "and again every 20 seconds while it is open")

        watch.close()
        watch.close() // closing twice is harmless
        val closed = api.subscriptionCalls
        advanceTimeBy(5 * 60_000); runCurrent()
        assertTrue(api.subscriptionCalls <= closed + 1, "a closed window stops the frequent questions (one round was already due)")
    }

    @Test
    fun anOpenWindowDoesNotAskForARefusedConnectionEveryRound() = runTest {
        val (m, api) = signedIn() // the fake refuses every connection: no free automatic slot
        m.start(backgroundScope)
        runCurrent()
        val w = m.watch()
        runCurrent()
        val first = api.createCalls
        advanceTimeBy(3 * 60_000); runCurrent()
        assertEquals(first, api.createCalls, "nine rounds, no new question about the same refusal")
        platform.clock += 6 * 60_000
        advanceTimeBy(30_000); runCurrent()
        assertTrue(api.createCalls > first, "asked again after a while")
        m.refresh()
        val afterOwn = api.createCalls
        m.refresh()
        assertEquals(afterOwn + 1, api.createCalls, "the person's own refresh always asks")
        w.close()
    }

    @Test
    fun twoWindowsWatchUntilBothAreClosed() = runTest {
        val (m, api) = signedIn()
        m.start(backgroundScope)
        runCurrent()
        val a = m.watch()
        val b = m.watch()
        runCurrent()
        a.close()
        val before = api.subscriptionCalls
        advanceTimeBy(21_000); runCurrent()
        assertTrue(api.subscriptionCalls > before, "one window is still open")
        b.close()
    }

    @Test
    fun withoutAWindowAChangeInTheBotIsSeenWithinAQuarterOfAnHour() = runTest {
        val (m, api) = signedIn()
        m.start(backgroundScope)
        runCurrent()
        val before = api.subscriptionCalls
        advanceTimeBy(15 * 60_000 + 1_000); runCurrent()
        assertTrue(api.subscriptionCalls > before)
    }

    @Test
    fun aWaitingInvoiceSurvivesARestart() {
        val dir = Files.createTempDirectory("bot").toFile()
        try {
            val pending = BotPendingInvoice("own30", "key-1-of-the-test", BotInvoice(invoiceId, "https://pay.example/x", "30.00", "active", 1, 2))
            FileBotRepository(dir).save(BotState(session = session, pendingInvoice = pending))
            assertEquals(pending, FileBotRepository(dir).state.value.pendingInvoice)
            assertFalse(java.io.File(dir, "bot.json").readText().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
