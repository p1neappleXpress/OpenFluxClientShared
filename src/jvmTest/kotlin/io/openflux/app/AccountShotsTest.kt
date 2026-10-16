package io.openflux.app

import androidx.compose.material3.Text
import io.openflux.app.account.AccountActions
import io.openflux.app.account.OpenFluxAccountContent
import io.openflux.app.account.PurchaseActions
import io.openflux.app.account.PurchaseContent
import io.openflux.app.account.PurchaseUi
import io.openflux.app.account.SlotSetupActions
import io.openflux.app.account.SlotSetupContent
import io.openflux.app.account.accountSummary
import io.openflux.app.account.tariffsOf
import io.openflux.app.components.Collapsible
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.TextAction
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.BotDocument
import io.openflux.desktop.model.BotInvoice
import io.openflux.desktop.model.BotLink
import io.openflux.desktop.model.BotPaymentMethod
import io.openflux.desktop.model.BotPendingInvoice
import io.openflux.desktop.model.BotPlan
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSlot
import io.openflux.desktop.model.BotState
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.service.BotAccountManager
import kotlin.test.Test

/** The subscription window's states, for review by eye (SHOTS=<dir>). */
class AccountShotsTest {
    private val day = 86_400_000L
    private val now = 1_792_065_600_000L // 2026-10-15 12:00 UTC
    private val msk = 3 * 3600
    private val telegram = BotSession("ofs_x", 7, "ivan")
    private val email = BotSession("ofs_e", 10_000_000_000_000_001)
    private fun sub(days: Double, slots: Int = 1) = BotSubscription(
        planId = "own30", planName = "Start", status = "active", expiresAt = (now + (days * day).toLong()) / 1000,
        maxSessions = slots, trafficUsedBytes = 3_221_225_472L * slots, trafficLimitBytes = 32_212_254_720, isActive = true,
    )
    private val none = BotSubscription(planId = "none", planName = "No Active Subscription", status = "inactive")
    private val invoice = BotInvoice("0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11", "https://pay.example/x", "30.00", "active", now / 1000, now / 1000 + 86_400)
    private val accountLink = BotLink("AAAAAAAAAAAAAAAAAAAAAA", "p1", "OpenFlux #4", account = true, status = "running")
    private val pausedLink = BotLink("CCCCCCCCCCCCCCCCCCCCCC", "p3", "OpenFlux #5", account = true, status = "paused")
    private val handLink = BotLink("BBBBBBBBBBBBBBBBBBBBBB", "p2", "Рабочий ноутбук #19")

    private fun shot(name: String, bot: BotState, frame: Shots.Frame = Shots.PHONE) =
        Shots.render("account-$name", frame) {
            ScreenScaffold("Подписка", onBack = {}, actions = { TextAction("Обновить", {}) }) {
                OpenFluxAccountContent(accountSummary(bot, now, msk), bot, null, null, AccountActions(), msk)
                Collapsible("Подписка по ключу") { Text("…", style = AppTheme.type.body) }
            }
        }

    @Test fun signedOut() = shot("signed-out", BotState())
    @Test fun signedOutSmall() = shot("signed-out", BotState(), Shots.SMALL)
    @Test fun loading() = shot("loading", BotState(session = telegram))
    @Test fun noSubscription() = shot("none", BotState(session = email, subscription = none, connectionNote = BotAccountManager.NO_SUBSCRIPTION))
    @Test fun active() = shot("active", BotState(session = telegram, subscription = sub(27.5), links = listOf(accountLink, handLink)))
    @Test fun activeWide() = shot("active", BotState(session = telegram, subscription = sub(27.5), links = listOf(accountLink)), Shots.WIDE)
    @Test fun endingEmail() = shot("ending-email", BotState(session = email, subscription = sub(2.2), links = listOf(accountLink)))
    @Test fun severalSlots() = shot("slots", BotState(session = telegram, subscription = sub(10.0, slots = 3), links = listOf(accountLink, pausedLink, handLink)))
    @Test fun noFreeSlot() = shot("no-slot", BotState(session = telegram, subscription = sub(20.0), connectionNote = BotAccountManager.NO_FREE_SLOT_TG))
    @Test fun preparing() = shot("preparing", BotState(session = email, subscription = sub(30.0), preparing = true))
    @Test fun waitingPayment() = shot("waiting", BotState(session = email, subscription = none, pendingInvoice = BotPendingInvoice("own30", "k", invoice)))
    @Test fun paid() = shot("paid", BotState(session = email, subscription = sub(30.0), preparing = true, pendingInvoice = BotPendingInvoice("own30", "k", invoice.copy(status = "paid"))))
    @Test fun paidSmall() = shot("paid", BotState(session = email, subscription = sub(30.0), pendingInvoice = BotPendingInvoice("own30", "k", invoice.copy(status = "paid"))), Shots.SMALL)

    // ---- the purchase sheet ----

    private val start30 = BotPlan("own30", "Start", 30, 32212254720, "OpenFlux — подключение для одного устройства", 3000)
    private val start90 = BotPlan("own90", "Start", 90, 96636764160, "OpenFlux — подключение для одного устройства", 8000)

    private fun sheet(name: String, ui: PurchaseUi, frame: Shots.Frame = Shots.PHONE) =
        Shots.render("purchase-$name", frame) {
            ScreenScaffold("Покупка подписки", onBack = {}) { PurchaseContent(ui, PurchaseActions(), msk) }
        }

    @Test fun choose() = sheet("choose", PurchaseUi.Choose(tariffsOf(listOf(start30, start90)), start30, false, null))
    @Test fun chooseOne() = sheet("choose-one", PurchaseUi.Choose(tariffsOf(listOf(start30)), start30, false, null))
    @Test fun chooseRefused() = sheet("refused", PurchaseUi.Choose(tariffsOf(listOf(start30)), start30, false, "Больше мест на этом аккаунте взять нельзя. Освободите одно или напишите в поддержку в @openflux_bot"))
    @Test fun creating() = sheet("creating", PurchaseUi.Choose(tariffsOf(listOf(start30)), start30, true, null))
    @Test fun loadingPlans() = sheet("loading", PurchaseUi.Loading)
    @Test fun failedPlans() = sheet("failed", PurchaseUi.Failed("Сервис OpenFlux не отвечает. Проверьте сеть."))
    @Test fun nothingOnSale() = sheet("nothing", PurchaseUi.NothingOnSale)
    @Test fun waiting() = sheet("waiting", PurchaseUi.Waiting(invoice, start30, null))
    @Test fun waitingSmall() = sheet("waiting", PurchaseUi.Waiting(invoice, start30, null), Shots.SMALL)
    @Test fun paidSheet() = sheet("paid", PurchaseUi.Paid(start30))
    @Test fun expired() = sheet("expired", PurchaseUi.Expired(start30))

    private val methods = listOf(BotPaymentMethod("platega", "Карта или СБП"), BotPaymentMethod("cryptopay", "Криптовалюта"))
    @Test fun chooseWays() = sheet("choose-ways", PurchaseUi.Choose(tariffsOf(listOf(start30, start90)), start30, false, null, methods, "platega"))

    // ---- a place's sheet ----

    private val slotRunning = BotSlot("5f0c7c2e-1d1a-4b0e-9a55-2b0d0c9e7a10", "Ноутбук", "Start", "auto", "running", (now + 27 * day) / 1000, 19, canStop = true, connectToken = "AAAAAAAAAAAAAAAAAAAAAA")
    private val slotManual = BotSlot("6f0c7c2e-1d1a-4b0e-9a55-2b0d0c9e7a10", "", "Start", "manual", "free", (now + 20 * day) / 1000, needsSetup = true)

    @Test fun places() = shot("places", BotState(session = telegram, subscription = sub(27.5), slots = listOf(slotRunning, slotManual)))
    @Test fun placesSmall() = shot("places", BotState(session = telegram, subscription = sub(27.5), slots = listOf(slotRunning, slotManual)), Shots.SMALL)

    private fun placeSetupShot(name: String, documents: List<BotDocument>?) =
        Shots.render(name) {
            ScreenScaffold("Место", onBack = {}) { SlotSetupContent(slotManual, documents, null, null, msk, SlotSetupActions()) }
        }

    @Test fun placeSetup() = placeSetupShot(
        "place-setup",
        listOf(BotDocument("d1", "yandex", "Яндекс Диск …a1b2", "ok"), BotDocument("d2", "mailru", "Облако Mail.ru …c3d4", "unknown")),
    )
    @Test fun placeSetupEmpty() = placeSetupShot("place-setup-empty", emptyList())

    // ---- a connection of the account ----

    private fun connection(name: String, link: BotLink, connected: Boolean = false, tg: Boolean = true) =
        Shots.render("connection-$name") {
            val c = testContainer()
            androidx.compose.runtime.CompositionLocalProvider(io.openflux.desktop.service.LocalAppContainer provides c, io.openflux.app.components.LocalToaster provides io.openflux.app.components.Toaster()) {
                ScreenScaffold(link.name, onBack = {}) { io.openflux.app.account.AccountConnectionContent(link, tg, connected, c.platform, {}, {}) }
            }
        }

    @Test fun connectionRunning() = connection("running", accountLink)
    @Test fun connectionConnected() = connection("connected", accountLink, connected = true)
    @Test fun connectionPaused() = connection("paused", pausedLink)
    @Test fun connectionPausedEmail() = connection("paused-email", pausedLink, tg = false)
}
