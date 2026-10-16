package io.openflux.app

import io.openflux.app.account.PurchaseModel
import io.openflux.app.account.PurchaseUi
import io.openflux.app.account.purchaseUi
import io.openflux.app.account.tariffsOf
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotInvoice
import io.openflux.desktop.model.BotPaymentMethod
import io.openflux.desktop.model.BotPendingInvoice
import io.openflux.desktop.model.BotPlan
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Buying a subscription in the window: a term, the invoice, the payment page, waiting, paid. */
class PurchaseModelTest {
    private val id = "0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11"
    private val start30 = BotPlan("own30", "Start", 30, 32212254720, "OpenFlux — подключение для одного устройства", 3000)
    private val start90 = BotPlan("own90", "Start", 90, 96636764160, "OpenFlux — подключение для одного устройства", 8000)
    private val pro30 = BotPlan("pro30", "Pro", 30, 0, "Без лимита трафика", 9900)

    private class Bot : BotApi {
        var plans: () -> List<BotPlan> = { emptyList() }
        var create: () -> BotInvoice = { BotInvoice("0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11", "https://pay.example/x", "30.00", "active") }
        var status: () -> BotInvoice = { BotInvoice("0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11", "https://pay.example/x", "30.00", "active") }
        var bought = 0
        var methods: () -> List<BotPaymentMethod> = { emptyList() }
        var lastGateway = ""
        override suspend fun subscription(token: String) = BotSubscription(isActive = bought > 0)
        override suspend fun signOut(token: String) {}
        override suspend fun resolve(connectToken: String): BotConnection = throw BotException("not_found", "Не найдено")
        override suspend fun sessions(token: String): List<BotSessionItem> = emptyList()
        override suspend fun createSession(token: String): BotSessionItem = throw BotException("forbidden", "x")
        override suspend fun plans(token: String?) = plans()
        override suspend fun paymentMethods(token: String) = methods()
        override suspend fun createInvoice(token: String, termCode: String, idempotencyKey: String, gateway: String): BotInvoice { lastGateway = gateway; return create() }
        override suspend fun invoice(token: String, id: String) = status().also { if (it.paid) bought++ }
    }

    private val bot = Bot()
    private val platform = TestPlatform()
    private val c = testContainer(bot = bot, platform = platform)

    private suspend fun signedIn() = c.bot.completeLogin(BotSession("ofs_x", 7, "ivan"))

    // ---- what the sheet shows ----

    @Test
    fun termsAreGroupedByTariffInTheirOrder() {
        val t = tariffsOf(listOf(start30, pro30, start90))
        assertEquals(listOf("Start", "Pro"), t.map { it.name })
        assertEquals(listOf("own30", "own90"), t[0].terms.map { it.id })
        assertEquals("OpenFlux — подключение для одного устройства", t[0].description)
    }

    @Test
    fun theSheetShowsTheInvoiceBeforeAnythingElse() {
        val waiting = BotPendingInvoice("own30", "k", BotInvoice(id, "https://pay.example/x", "30.00", "active"))
        assertIs<PurchaseUi.Waiting>(purchaseUi(null, null, null, waiting, false, null))
        assertIs<PurchaseUi.Paid>(purchaseUi(null, null, null, waiting.copy(invoice = waiting.invoice!!.copy(status = "paid")), false, null))
        assertIs<PurchaseUi.Expired>(purchaseUi(null, null, null, waiting.copy(invoice = waiting.invoice!!.copy(status = "expired")), false, null))
        assertEquals(PurchaseUi.Loading, purchaseUi(null, null, null, null, false, null))
        assertEquals(PurchaseUi.Failed("нет сети"), purchaseUi(null, "нет сети", null, null, false, null))
        assertEquals(PurchaseUi.NothingOnSale, purchaseUi(emptyList(), null, null, null, false, null))
        val choose = assertIs<PurchaseUi.Choose>(purchaseUi(listOf(start30), null, "own30", null, false, null))
        assertEquals("own30", choose.selected?.id)
    }

    @Test
    fun aCallLostOnTheWayOffersTheSameTermAgain() {
        val lost = BotPendingInvoice("own90", "k", null)
        val ui = assertIs<PurchaseUi.Choose>(purchaseUi(listOf(start30, start90), null, "own30", lost, false, "Сервис OpenFlux не отвечает"))
        assertEquals("own90", ui.selected?.id, "the term of the purchase that was lost, so its key is used again")
        assertEquals("Сервис OpenFlux не отвечает", ui.error)
    }

    // ---- the model ----

    @Test
    fun theWholeFlowOfBuying() = runTest {
        bot.plans = { listOf(start30, start90) }
        signedIn()
        val m = PurchaseModel(c, this)
        m.open(); runCurrent()
        val choose = assertIs<PurchaseUi.Choose>(m.ui)
        assertEquals("own30", choose.selected?.id, "the first term is chosen")
        m.select("own90")
        m.pay(); runCurrent()
        assertEquals(listOf("https://pay.example/x"), platform.opened, "the payment page opens in the browser")
        assertIs<PurchaseUi.Waiting>(m.ui)

        bot.status = { BotInvoice(id, "https://pay.example/x", "30.00", "paid") }
        advanceTimeBy(5_100); runCurrent()
        assertIs<PurchaseUi.Paid>(m.ui, "the sheet asks every few seconds")
        assertTrue(c.bot.state.value.subscription!!.isActive, "the subscription came in with the payment")
        m.done(); runCurrent()
        assertNull(c.bot.state.value.pendingInvoice)
        assertTrue(!m.isOpen)
    }

    @Test
    fun theOnlyTermIsBoughtWithoutChoosing() = runTest {
        bot.plans = { listOf(start30) }
        signedIn()
        val m = PurchaseModel(c, this)
        m.open(); runCurrent()
        m.pay(); runCurrent()
        assertIs<PurchaseUi.Waiting>(m.ui)
        m.close()
    }

    @Test
    fun aRefusalIsSaidOnTheChoice() = runTest {
        bot.plans = { listOf(start30) }
        bot.create = { throw BotException("slot_limit", "Больше мест на этом аккаунте взять нельзя") }
        signedIn()
        val m = PurchaseModel(c, this)
        m.open(); runCurrent()
        m.pay(); runCurrent()
        val ui = assertIs<PurchaseUi.Choose>(m.ui)
        assertEquals("Больше мест на этом аккаунте взять нельзя", ui.error)
        assertTrue(platform.opened.isEmpty())
        m.close()
    }

    @Test
    fun anExpiredInvoiceLeadsToANewOne() = runTest {
        bot.plans = { listOf(start30) }
        signedIn()
        val m = PurchaseModel(c, this)
        m.open(); runCurrent()
        m.pay(); runCurrent()
        bot.status = { BotInvoice(id, "https://pay.example/x", "30.00", "expired") }
        m.check(); runCurrent()
        assertIs<PurchaseUi.Expired>(m.ui)
        m.again(); runCurrent()
        assertIs<PurchaseUi.Choose>(m.ui)
        assertNull(c.bot.state.value.pendingInvoice)
        m.close()
    }

    @Test
    fun aCheckBeforeThePaymentSaysItHasNotComeYet() = runTest {
        bot.plans = { listOf(start30) }
        signedIn()
        val m = PurchaseModel(c, this)
        m.open(); runCurrent()
        m.pay(); runCurrent()
        m.check(); runCurrent()
        assertEquals("Оплата ещё не пришла", m.notice)
        m.reopen()
        assertEquals(2, platform.opened.size, "the payment page opens again")
        m.close()
    }

    @Test
    fun closingTheSheetKeepsTheInvoiceAndStopsAsking() = runTest {
        bot.plans = { listOf(start30) }
        signedIn()
        val m = PurchaseModel(c, this)
        m.open(); runCurrent()
        m.pay(); runCurrent()
        m.close()
        var asked = 0
        bot.status = { asked++; BotInvoice(id, "https://pay.example/x", "30.00", "active") }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, asked, "the sheet no longer asks (the account's own loop does, when it runs)")
        assertEquals("active", c.bot.state.value.pendingInvoice?.invoice?.status, "the window still shows the invoice")
    }

    @Test
    fun theWaysToPayComeWithTheTerms() = runTest {
        bot.plans = { listOf(start30) }
        bot.methods = { listOf(BotPaymentMethod("platega", "Карта или СБП"), BotPaymentMethod("cryptopay", "Криптовалюта")) }
        signedIn()
        val m = PurchaseModel(c, this)
        m.open(); runCurrent()
        val choose = assertIs<PurchaseUi.Choose>(m.ui)
        assertEquals(listOf("platega", "cryptopay"), choose.methods.map { it.id })
        assertEquals("platega", choose.chosenGateway, "the first way is the default")
        m.chooseGateway("cryptopay")
        assertEquals("cryptopay", assertIs<PurchaseUi.Choose>(m.ui).chosenGateway)
        m.close()
    }

    @Test
    fun theChosenWayToPayGoesWithTheInvoice() = runTest {
        bot.plans = { listOf(start30) }
        bot.methods = { listOf(BotPaymentMethod("platega", "Карта или СБП"), BotPaymentMethod("cryptopay", "Криптовалюта")) }
        signedIn()
        val m = PurchaseModel(c, this)
        m.open(); runCurrent()
        m.chooseGateway("cryptopay")
        m.pay(); runCurrent()
        assertEquals("cryptopay", bot.lastGateway, "the way chosen goes with the invoice")
        m.close()
    }

    @Test
    fun plansThatDoNotLoadAreSaidAndCanBeAskedAgain() = runTest {
        bot.plans = { throw BotException("network", "Сервис OpenFlux не отвечает. Проверьте сеть.") }
        signedIn()
        val m = PurchaseModel(c, this)
        m.open(); runCurrent()
        assertEquals(PurchaseUi.Failed("Сервис OpenFlux не отвечает. Проверьте сеть."), m.ui)
        bot.plans = { listOf(start30) }
        m.reload(); runCurrent()
        assertIs<PurchaseUi.Choose>(m.ui)
        m.close()
        advanceUntilIdle()
    }
}
