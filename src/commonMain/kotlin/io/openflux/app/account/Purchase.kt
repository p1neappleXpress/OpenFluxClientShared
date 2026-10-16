package io.openflux.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.openflux.app.components.Banner
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.RowItem
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.Section
import io.openflux.app.components.TextAction
import io.openflux.app.components.Tone
import io.openflux.app.text.Fmt
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotInvoice
import io.openflux.desktop.model.BotPaymentMethod
import io.openflux.desktop.model.BotPendingInvoice
import io.openflux.desktop.model.BotPlan
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A tariff with its terms on sale: GET /api/v1/plans lists terms, and the tariff's name repeats on each. */
data class Tariff(val name: String, val description: String, val terms: List<BotPlan>)

/** The terms grouped by tariff, in the order the service lists them. */
fun tariffsOf(plans: List<BotPlan>): List<Tariff> =
    plans.groupBy { it.name }.map { (name, terms) -> Tariff(name, terms.firstOrNull { it.description.isNotBlank() }?.description.orEmpty(), terms) }

/** What the purchase sheet shows. */
sealed interface PurchaseUi {
    data object Loading : PurchaseUi
    data class Failed(val message: String) : PurchaseUi
    data object NothingOnSale : PurchaseUi
    data class Choose(
        val tariffs: List<Tariff>,
        val selected: BotPlan?,
        val busy: Boolean,
        val error: String?,
        /** The ways to pay the service offers now; empty when it picks the way itself. */
        val methods: List<BotPaymentMethod> = emptyList(),
        /** The way chosen ([methods]' id), sent with the invoice; null when there is nothing to choose. */
        val chosenGateway: String? = null,
    ) : PurchaseUi
    data class Waiting(val invoice: BotInvoice, val term: BotPlan?, val error: String?) : PurchaseUi
    data class Paid(val term: BotPlan?) : PurchaseUi
    data class Expired(val term: BotPlan?) : PurchaseUi
}

/**
 * The sheet's state: an invoice of the purchase first (waiting, paid, expired), then the terms. A purchase whose call
 * was lost has no invoice yet: its term stays chosen, so paying again goes with the key it had.
 */
fun purchaseUi(
    plans: List<BotPlan>?,
    plansError: String?,
    selectedId: String?,
    pending: BotPendingInvoice?,
    busy: Boolean,
    error: String?,
    methods: List<BotPaymentMethod> = emptyList(),
    chosenGateway: String? = null,
): PurchaseUi {
    val inv = pending?.invoice
    val term = pending?.let { p -> plans?.firstOrNull { it.id == p.termCode } }
    if (inv != null) {
        return when {
            inv.paid -> PurchaseUi.Paid(term)
            inv.expired -> PurchaseUi.Expired(term)
            else -> PurchaseUi.Waiting(inv, term, error)
        }
    }
    if (plans == null) return if (plansError != null) PurchaseUi.Failed(plansError) else PurchaseUi.Loading
    if (plans.isEmpty()) return PurchaseUi.NothingOnSale
    val selected = plans.firstOrNull { it.id == pending?.termCode } ?: plans.firstOrNull { it.id == selectedId } ?: plans.first()
    val chosen = chosenGateway?.takeIf { id -> methods.any { it.id == id } } ?: methods.firstOrNull()?.id
    return PurchaseUi.Choose(tariffsOf(plans), selected, busy, error, methods, chosen)
}

/**
 * Buying a slot from the window: the terms on sale, the invoice, its page in the browser, then waiting for the
 * payment (asked every few seconds while the sheet is open; the account's own loop asks too). The invoice itself is
 * kept by [io.openflux.desktop.service.BotAccountManager], so closing the sheet or the app loses nothing.
 */
@Stable
class PurchaseModel(private val container: AppContainer, private val scope: CoroutineScope) {
    private val bot = container.bot
    var isOpen by mutableStateOf(false)
        private set
    var plans by mutableStateOf<List<BotPlan>?>(null)
        private set
    var plansError by mutableStateOf<String?>(null)
        private set
    var selected by mutableStateOf<String?>(null)
        private set
    /** The ways to pay the service offers now (the first is the default). */
    var methods by mutableStateOf<List<BotPaymentMethod>>(emptyList())
        private set
    /** The way to pay chosen for this purchase; sent as the invoice's `gateway`. */
    var chosenGateway by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** A passing word for a toast. */
    var notice by mutableStateOf<String?>(null)
        private set
    private var poll: Job? = null

    val ui: PurchaseUi get() = uiFor(bot.state.value.pendingInvoice)
    fun uiFor(pending: BotPendingInvoice?): PurchaseUi = purchaseUi(plans, plansError, selected, pending, busy, error, methods, chosenGateway)

    fun clearNotice() { notice = null }

    fun open() {
        isOpen = true
        error = null
        reload()
        poll?.cancel()
        poll = scope.launch {
            while (isOpen) {
                delay(POLL_MS)
                val p = bot.state.value.pendingInvoice
                if (p?.invoice != null && p.waiting) {
                    try {
                        bot.checkInvoice()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: BotException) {
                        // Asked again in a few seconds; "Проверить" says what failed.
                    }
                }
            }
        }
    }

    fun close() {
        isOpen = false
        poll?.cancel()
    }

    fun reload() {
        plansError = null
        scope.launch {
            try {
                val p = bot.plans()
                plans = p
                if (p.none { it.id == selected }) selected = p.firstOrNull()?.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                plansError = e.message ?: "Не удалось загрузить тарифы"
                return@launch
            }
            // The ways to pay are asked with the terms; a failure here leaves the choice to the service.
            methods = try {
                bot.paymentMethods()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                emptyList()
            }
            if (chosenGateway == null || methods.none { it.id == chosenGateway }) chosenGateway = methods.firstOrNull()?.id
        }
    }

    /** The person picked a way to pay; it goes with the invoice when the purchase is made. */
    fun chooseGateway(id: String) { chosenGateway = id }

    /** Another term; a purchase whose call was lost on another term is given up (its invoice, if any, just ends). */
    fun select(id: String) {
        selected = id
        error = null
        val p = bot.state.value.pendingInvoice
        if (p != null && p.invoice == null && p.termCode != id) scope.launch { bot.dismissInvoice() }
    }

    fun pay() {
        if (busy) return
        val p = bot.state.value.pendingInvoice
        val term = p?.takeIf { it.invoice == null }?.termCode ?: selected ?: plans?.firstOrNull()?.id ?: return
        busy = true
        error = null
        scope.launch {
            try {
                val gateway = chosenGateway ?: methods.firstOrNull()?.id.orEmpty()
                val inv = bot.checkout(term, gateway)
                // An invoice paid at once (the free way) still carries a placeholder link (t.me/#invoice_…): nothing to open.
                if (!inv.paid && inv.payUrl.startsWith("https://")) container.platform.openUrl(inv.payUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    fun reopen() {
        val inv = bot.state.value.pendingInvoice?.invoice ?: return
        if (!inv.paid && inv.payUrl.startsWith("https://")) container.platform.openUrl(inv.payUrl)
    }

    fun check() {
        error = null
        scope.launch {
            try {
                val inv = bot.checkInvoice()
                if (inv != null && !inv.paid && !inv.expired) notice = "Оплата ещё не пришла"
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                error = e.message
            }
        }
    }

    /** The invoice ended unpaid (or the person gives it up): back to the terms. */
    fun again() {
        error = null
        scope.launch {
            bot.dismissInvoice()
            if (plans == null) reload()
        }
    }

    /** The payment was seen: the sheet closes and the window no longer speaks of the invoice. */
    fun done() {
        close()
        scope.launch { bot.dismissInvoice() }
    }

    private companion object {
        const val POLL_MS = 5_000L
    }
}

/** What the person can do on the sheet. */
data class PurchaseActions(
    val onSelect: (String) -> Unit = {},
    val onChooseGateway: (String) -> Unit = {},
    val onPay: () -> Unit = {},
    val onReopen: () -> Unit = {},
    val onCheck: () -> Unit = {},
    val onAgain: () -> Unit = {},
    val onDone: () -> Unit = {},
    val onReload: () -> Unit = {},
)

/** A term as a row: "30 дней", and what it gives. */
fun termTitle(p: BotPlan): String = if (p.durationDays > 0) Fmt.days(p.durationDays) else p.name

fun termFacts(p: BotPlan): String = buildList {
    add(p.priceText)
    if (p.trafficLimitBytes > 0) add("${Fmt.bytes(p.trafficLimitBytes)} трафика") else add("трафик без лимита")
    add("одно подключение")
}.joinToString(" · ")

/** The sheet's content for [ui]; drawn alone in shots. */
@Composable
fun PurchaseContent(ui: PurchaseUi, actions: PurchaseActions, offsetSeconds: Int = 0) {
    Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
        when (ui) {
            PurchaseUi.Loading -> ProgressLine("Загружаю тарифы…")
            is PurchaseUi.Failed -> {
                Banner(ui.message, Tone.Danger)
                SecondaryButton("Повторить", actions.onReload, Modifier.fillMaxWidth())
            }
            PurchaseUi.NothingOnSale -> Text("Сейчас ничего не продаётся. Загляните позже.", style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
            is PurchaseUi.Choose -> {
                for (t in ui.tariffs) {
                    Section(t.name) {
                        for (p in t.terms) {
                            RowItem(termTitle(p), subtitle = termFacts(p), selected = ui.selected?.id == p.id, onClick = { actions.onSelect(p.id) }, enabled = !ui.busy)
                        }
                    }
                    if (t.description.isNotBlank()) Text(t.description, style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
                }
                if (ui.methods.isNotEmpty()) {
                    Section("Способ оплаты") {
                        for (m in ui.methods) {
                            RowItem(
                                m.name.ifBlank { m.id },
                                selected = ui.chosenGateway == m.id,
                                onClick = { actions.onChooseGateway(m.id) },
                                enabled = !ui.busy,
                            )
                        }
                    }
                }
                Text(
                    "Каждая покупка — новое место для одного подключения. Оплата откроется в браузере.",
                    style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
                )
                ui.error?.let { Banner(it, Tone.Danger) }
                val price = ui.selected?.priceText
                if (ui.busy) ProgressLine("Создаю счёт…")
                else PrimaryButton(if (price != null) "Оплатить $price" else "Оплатить", actions.onPay, Modifier.fillMaxWidth(), enabled = ui.selected != null)
            }
            is PurchaseUi.Waiting -> {
                val sum = ui.invoice.amountText ?: ui.term?.priceText
                Text(
                    "Счёт" + (sum?.let { " на $it" }.orEmpty()) + " ждёт оплаты. Оплатите его на странице, которая открылась в браузере: подписка появится здесь сама.",
                    style = AppTheme.type.body, color = AppTheme.colors.text,
                )
                if (ui.invoice.expiresAt > 0) {
                    Text("Счёт действует до ${Fmt.date(ui.invoice.expiresAt, offsetSeconds)}", style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
                }
                ProgressLine("Жду подтверждения оплаты…")
                ui.error?.let { Banner(it, Tone.Warning) }
                if (ui.invoice.payUrl.startsWith("https://")) PrimaryButton("Открыть оплату снова", actions.onReopen, Modifier.fillMaxWidth())
                SecondaryButton("Проверить оплату", actions.onCheck, Modifier.fillMaxWidth())
                TextAction("Другой тариф", actions.onAgain)
            }
            is PurchaseUi.Paid -> {
                Banner("Оплата получена. Место добавлено, подключение появится само", Tone.Success)
                PrimaryButton("Готово", actions.onDone, Modifier.fillMaxWidth())
            }
            is PurchaseUi.Expired -> {
                Banner("Счёт не оплачен и закрыт", Tone.Warning)
                PrimaryButton("Создать новый счёт", actions.onAgain, Modifier.fillMaxWidth())
            }
        }
    }
}
