package io.openflux.app.account

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.desktop.model.BotDocument
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSlot
import io.openflux.desktop.model.DeviceInfo
import io.openflux.desktop.model.OrderInfo
import io.openflux.desktop.model.PlansResult
import io.openflux.desktop.model.SubscriptionException
import io.openflux.desktop.model.SubscriptionKind
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val MIN_PASSWORD = 10

/** What the service will also check, said before the request. */
fun newPasswordProblem(login: String, p: String, again: String): String? = when {
    p.length < MIN_PASSWORD -> "Пароль: не короче $MIN_PASSWORD символов"
    p != again -> "Пароли не совпадают"
    p.equals(login.trim(), ignoreCase = true) -> "Пароль не должен совпадать с логином"
    else -> null
}

/**
 * The subscription (spec 8.2): renew (plans → order → payment → paid), a trial, the devices, the password, signing
 * out and deleting the account. The service's own words (already Russian) reach the person as they are.
 */
@Stable
class SubscriptionModel(private val container: AppContainer, private val scope: CoroutineScope) {
    private val manager = container.subscription
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    /** The password and delete dialogs' own error: the page's banner is behind the dialog. */
    var dialogError by mutableStateOf<String?>(null)
        private set

    var plansOpen by mutableStateOf(false)
        private set
    var plans by mutableStateOf<PlansResult?>(null)
        private set
    var plansError by mutableStateOf<String?>(null)
        private set
    var selectedPlan by mutableStateOf<String?>(null)
    var order by mutableStateOf<OrderInfo?>(null)
        private set
    var paid by mutableStateOf(false)
        private set
    private var pollJob: Job? = null

    val isAccount: Boolean get() = manager.state.value?.kind == SubscriptionKind.Account

    fun clearNotice() { notice = null }
    fun clearDialogError() { dialogError = null }

    fun refresh() = call("Обновляю…") { manager.refresh() }

    /** The window's "Обновить": the OpenFlux account (and the links from the bot), and a subscription by key if there is one. */
    fun refreshAll() = call("Обновляю…") {
        val bot = container.bot.state.value
        if (bot.session != null || bot.links.isNotEmpty()) container.bot.refresh()
        if (manager.state.value != null) manager.refresh()
    }
    fun startTrial() = call("Включаю пробный период…") { manager.startTrial() }
    fun signOut() = call("Выхожу…") { manager.signOut() }
    fun removeDevice(id: String) = call("Убираю устройство…") { manager.removeDevice(id) }

    /** Opens the order's payment page again: the person closed it, or paid on another device. */
    fun reopenPayment() {
        val url = manager.state.value?.pendingOrder?.paymentUrl.orEmpty()
        if (url.startsWith("https://")) container.platform.openUrl(url)
    }

    // ---- the OpenFlux bot ----

    fun botSignOut() = call("Выхожу из бота…") { container.bot.signOut() }
    fun botRefresh() = call("Обновляю данные бота…") { container.bot.refresh() }
    fun removeBotLink(token: String) = call("Убираю подключение…") { container.bot.removeLink(token) }
    fun openBot() = container.platform.openUrl(io.openflux.desktop.model.OpenFluxBot.CHAT_URL)

    // ---- the account's places (slots) ----

    var openSlotId by mutableStateOf<String?>(null)
        private set
    var documents by mutableStateOf<List<BotDocument>?>(null)
        private set

    /** The place whose sheet is open, read from the freshest bot state (so Start/Stop show in it at once). */
    val openSlot: BotSlot? get() = openSlotId?.let { id -> container.bot.state.value.slots?.firstOrNull { it.id == id } }

    fun startSlot(slotId: String) = call("Запускаю место…") { container.bot.startSlot(slotId); notice = "Место запущено" }
    fun stopSlot(slotId: String) = call("Останавливаю место…") { container.bot.stopSlot(slotId); notice = "Место остановлено" }

    /** Opens a manual place's sheet and loads its own documents. */
    fun openSlotSetup(slotId: String) {
        openSlotId = slotId
        documents = null
        error = null
        scope.launch {
            try {
                documents = container.bot.documents(slotId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                if (openSlotId == slotId) { documents = emptyList(); error = e.message }
            }
        }
    }

    fun closeSlotSetup() { openSlotId = null; documents = null }

    fun addDocument(url: String) {
        val id = openSlotId ?: return
        call("Добавляю документ…") { container.bot.addDocument(id, url); documents = container.bot.documents(id) }
    }

    fun removeDocument(documentId: String) {
        val id = openSlotId ?: return
        call("Убираю документ…") { container.bot.deleteDocument(id, documentId); documents = container.bot.documents(id) }
    }

    fun checkDocument(documentId: String) {
        val id = openSlotId ?: return
        call("Проверяю документ…") {
            val d = container.bot.checkDocument(id, documentId)
            documents = documents?.map { if (it.id == d.id) d else it }
        }
    }

    /**
     * A manual connection is made from all the place's documents, in their list order (the bot's rule). None is
     * fine: the service then makes cups.online and Avito rooms, which need no account (its refusal is shown).
     */
    fun connectSlot() {
        val id = openSlotId ?: return
        val ids = documents?.map { it.id } ?: return
        call("Подключаю место…") {
            container.bot.connectSlot(id, ids)
            closeSlotSetup()
            notice = "Место настроено. Подключение появится само"
        }
    }

    fun openSupport() {
        val url = manager.state.value?.support.orEmpty()
        if (url.startsWith("https://") || url.startsWith("http://")) container.platform.openUrl(url)
    }

    fun changePassword(old: String, new: String, again: String, onDone: () -> Unit = {}) {
        val login = manager.state.value?.account?.login.orEmpty()
        val problem = if (old.isEmpty()) "Введите текущий пароль" else newPasswordProblem(login, new, again)
        if (problem != null) { dialogError = problem; return }
        dialog("Меняю пароль…") {
            manager.changePassword(old, new)
            notice = "Пароль изменён. На других устройствах войдите заново"
            onDone()
        }
    }

    fun deleteAccount(password: String, onDone: () -> Unit = {}) {
        if (password.isEmpty()) { dialogError = "Введите пароль"; return }
        dialog("Удаляю аккаунт…") {
            manager.deleteAccount(password)
            onDone()
        }
    }

    fun openPlans() {
        plansOpen = true
        order = null
        paid = false
        plansError = null
        selectedPlan = null
        scope.launch {
            try {
                plans = manager.plans().also { p -> selectedPlan = p.plans.firstOrNull()?.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                plansError = e.message
            }
        }
    }

    fun closePlans() {
        plansOpen = false
        pollJob?.cancel()
    }

    fun checkout() {
        val id = selectedPlan ?: return
        if (busy != null) return
        busy = "Оформляю заказ…"
        plansError = null
        scope.launch {
            try {
                val o = manager.checkout(id)
                order = o
                if (o.paymentUrl.startsWith("https://")) container.platform.openUrl(o.paymentUrl)
                poll(o.orderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                plansError = e.message
            } finally {
                busy = null
            }
        }
    }

    /** Asks about the order on screen, or the one kept from before the app was closed. */
    fun checkOrder() {
        val id = order?.orderId ?: manager.state.value?.pendingOrder?.orderId ?: return
        scope.launch { checkOrderNow(id) }
    }

    /** Asks about the order until it is paid, while the plans are open. */
    private fun poll(orderId: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (plansOpen && !paid) {
                delay(POLL_MS)
                checkOrderNow(orderId)
            }
        }
    }

    private suspend fun checkOrderNow(orderId: String) {
        try {
            val o = manager.order(orderId)
            if (plansOpen) order = o
            if (o.paid) paid = true
            // Asked from the card, the sheet is not there to say it.
            if (!plansOpen) notice = when {
                o.paid -> "Оплата получена. Подписка продлена"
                o.closed -> "Заказ ${o.orderId} не оплачен. Оформите новый"
                else -> "Оплата ещё не пришла"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SubscriptionException) {
            if (plansOpen) plansError = e.message else error = e.message
        }
    }

    private fun call(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                error = e.message
            } catch (e: io.openflux.desktop.model.BotException) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "Не получилось"
            } finally {
                busy = null
            }
        }
    }

    private fun dialog(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        dialogError = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                dialogError = e.message
            } finally {
                busy = null
            }
        }
    }

    private companion object {
        const val POLL_MS = 8_000L
    }
}
