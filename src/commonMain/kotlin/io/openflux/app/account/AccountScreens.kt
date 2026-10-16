package io.openflux.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.openflux.app.components.Banner
import io.openflux.app.components.Collapsible
import io.openflux.app.components.ConfirmDialog
import io.openflux.app.components.Group
import io.openflux.app.components.LocalToaster
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.RowItem
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.SecretField
import io.openflux.app.components.Section
import io.openflux.app.components.Sheet
import io.openflux.app.components.TextAction
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.home.look
import io.openflux.app.nav.Dest
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.shell.rememberScreenModel
import io.openflux.app.text.Fmt
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.BotSlot
import io.openflux.desktop.model.BotState
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.model.SubscriptionKind
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.LocalAppContainer

@Composable
private fun Submit(busy: String?, label: String, onClick: () -> Unit) {
    if (busy != null) ProgressLine(busy) else PrimaryButton(label, onClick, Modifier.fillMaxWidth())
}

/**
 * The subscription (spec 8.2): the OpenFlux account first (its subscription, buying, the connections from the bot),
 * then, folded, a subscription by key on another service (opensub). While the window is open it follows the bot:
 * the service cannot tell the app of a change made there, so the app asks every few seconds.
 */
@Composable
fun AccountScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val m = rememberScreenModel { SubscriptionModel(container, it) }
    val buy = rememberScreenModel { PurchaseModel(container, it) }
    val state by container.subscription.state.collectAsState()
    val bot by container.bot.state.collectAsState()
    var botSignOutAsk by remember { mutableStateOf(false) }
    var openConnection by remember { mutableStateOf<String?>(null) }
    val connectionState by container.connection.state.collectAsState()
    val connectedProfileId = connectionState.profile?.id?.takeIf { connectionState.isActive }
    DisposableEffect(Unit) {
        val watch = container.bot.watch()
        onDispose { watch.close() }
    }
    LaunchedEffect(m.notice) { m.notice?.let { toaster.show(it); m.clearNotice() } }
    LaunchedEffect(buy.notice) { buy.notice?.let { toaster.show(it); buy.clearNotice() } }
    val platform = container.platform
    ScreenScaffold("Подписка", onBack = { navigator.back() }, actions = { TextAction("Обновить", m::refreshAll, enabled = m.busy == null) }) {
        OpenFluxAccountContent(
            summary = accountSummary(bot, platform.now(), platform.utcOffsetSeconds),
            bot = bot,
            busy = m.busy,
            error = m.error,
            offsetSeconds = platform.utcOffsetSeconds,
            actions = AccountActions(
                onSignIn = { navigator.open(Dest.Auth) },
                onBuy = buy::open,
                onRenewInBot = m::openBot,
                onReopenPayment = buy::reopen,
                onCheckPayment = buy::check,
                onDismissInvoice = buy::done,
                onAddLink = { navigator.open(Dest.AddKey) },
                onRemoveLink = m::removeBotLink,
                onSignOut = { botSignOutAsk = true },
                onOpenConnection = { openConnection = it },
                onStartSlot = m::startSlot,
                onStopSlot = m::stopSlot,
                onOpenSlot = { slot ->
                    // A manual place opens its documents (its connection is made from them); an auto one opens
                    // its connection when it has one, else its sheet (to start it).
                    if (!slot.manual && slot.connectToken.isNotEmpty()) openConnection = slot.connectToken
                    else m.openSlotSetup(slot.id)
                },
            ),
            connectedProfileId = connectedProfileId,
        )
        Collapsible("Подписка по ключу", initiallyOpen = state != null && bot.session == null) {
            Column(Modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                KeySubscription(m)
            }
        }
    }
    if (botSignOutAsk) {
        ConfirmDialog(
            title = "Выйти из аккаунта OpenFlux?",
            text = "Подключения аккаунта пропадут с этого устройства. Подписка останется: войдите снова, и они вернутся.",
            confirm = "Выйти",
            onConfirm = { botSignOutAsk = false; m.botSignOut() },
            onDismiss = { botSignOutAsk = false },
        )
    }
    val shown = openConnection?.let { t -> bot.links.firstOrNull { it.token == t } }
    if (shown != null) {
        Sheet(onDismiss = { openConnection = null }, title = shown.name.ifBlank { "Подключение" }) {
            AccountConnectionContent(
                shown,
                viaTelegram = bot.session?.viaTelegram == true,
                connected = shown.profileId == connectedProfileId,
                platform = platform,
                onConnect = {
                    container.profiles.profiles.value.firstOrNull { it.id == shown.profileId }?.let { p ->
                        io.openflux.app.shell.connectAsClient(container, p)
                        toaster.show("Подключаюсь через «${p.name}»")
                    }
                    openConnection = null
                },
                onOpenBot = m::openBot,
            )
        }
    }
    val setupSlot = m.openSlot
    if (setupSlot != null) {
        Sheet(onDismiss = m::closeSlotSetup, title = slotTitle(setupSlot)) {
            SlotSetupContent(
                setupSlot,
                m.documents,
                m.busy,
                m.error,
                platform.utcOffsetSeconds,
                SlotSetupActions(
                    onStart = { m.startSlot(setupSlot.id) },
                    onStop = { m.stopSlot(setupSlot.id) },
                    onAddDocument = m::addDocument,
                    onRemoveDocument = m::removeDocument,
                    onCheckDocument = m::checkDocument,
                    onConnect = m::connectSlot,
                ),
            )
        }
    }
    if (buy.isOpen) {
        Sheet(onDismiss = buy::close, title = "Покупка подписки") {
            PurchaseContent(
                buy.uiFor(bot.pendingInvoice),
                PurchaseActions(
                    onSelect = buy::select, onChooseGateway = buy::chooseGateway, onPay = buy::pay, onReopen = buy::reopen, onCheck = buy::check,
                    onAgain = buy::again, onDone = buy::done, onReload = buy::reload,
                ),
                platform.utcOffsetSeconds,
            )
        }
    }
}

/** What the person can do in the OpenFlux account part. */
data class AccountActions(
    val onSignIn: () -> Unit = {},
    val onBuy: () -> Unit = {},
    val onRenewInBot: () -> Unit = {},
    val onReopenPayment: () -> Unit = {},
    val onCheckPayment: () -> Unit = {},
    val onDismissInvoice: () -> Unit = {},
    val onAddLink: () -> Unit = {},
    val onRemoveLink: (String) -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onOpenConnection: (String) -> Unit = {},
    val onStartSlot: (String) -> Unit = {},
    val onStopSlot: (String) -> Unit = {},
    val onOpenSlot: (BotSlot) -> Unit = {},
)

/** The OpenFlux account: drawn from its state alone, so it is drawn in shots too. */
@Composable
fun OpenFluxAccountContent(
    summary: AccountSummary?,
    bot: BotState,
    busy: String?,
    error: String?,
    actions: AccountActions,
    offsetSeconds: Int = 0,
    /** The profile this device is connected through, to mark that connection. */
    connectedProfileId: String? = null,
) {
    val idle = busy == null
    if (summary == null) {
        Group("Аккаунт OpenFlux") {
            Text(
                "Войдите, чтобы купить подписку и получить подключение. Вход по почте или через Telegram.",
                style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
            )
        }
        PrimaryButton("Войти в аккаунт OpenFlux", actions.onSignIn, Modifier.fillMaxWidth())
    } else {
        Section("Аккаунт OpenFlux") {
            ValueRow("Вход", summary.signIn)
            ValueRow("Подписка", summary.state)
            for ((k, v) in summary.facts) ValueRow(k, v)
        }
        if (bot.preparing) ProgressLine("Готовлю подключение…")
        AccountBanner(bot, actions, offsetSeconds)
        val waiting = bot.pendingInvoice?.let { it.invoice != null && it.waiting } == true
        PrimaryButton(if (waiting) "Оплатить счёт" else summary.buyLabel, actions.onBuy, Modifier.fillMaxWidth(), enabled = idle)
        if (summary.renewInBot) SecondaryButton("Продлить место в @${OpenFluxBot.USERNAME}", actions.onRenewInBot, Modifier.fillMaxWidth())
        summary.note?.let { Text(it, style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary) }
    }
    busy?.let { ProgressLine(it) }
    error?.let { Banner(it, Tone.Danger) }
    val slots = bot.slots
    if (!slots.isNullOrEmpty()) {
        // The account's places, from the slots API: each with its state and the one thing it can do now.
        SlotsSection(slots, offsetSeconds, idle, actions.onStartSlot, actions.onStopSlot, actions.onOpenSlot)
    } else {
        AccountConnectionsSection(bot.links.filter { it.account }, bot.session?.viaTelegram == true, connectedProfileId, actions.onOpenConnection)
    }
    Section("Подключения по ссылке из бота") {
        for (link in bot.links.filterNot { it.account }) {
            RowItem(
                link.name.ifBlank { "Подключение из бота" },
                subtitle = if (link.gone) "закончилось или заменено: возьмите новую ссылку в боте" else "обновляется само",
                trailing = { TextAction("Убрать", { actions.onRemoveLink(link.token) }, enabled = idle) },
            )
        }
        RowItem("Добавить ссылку из бота", subtitle = "link.openflux.dev/…: подключение появится здесь и будет обновляться само", onClick = actions.onAddLink)
    }
    if (summary != null) {
        Section(null) { RowItem("Выйти из аккаунта OpenFlux", onClick = actions.onSignOut.takeIf { idle }) }
    }
}

/** At most one line of what needs attention (spec 3): the purchase, then why there is no connection, then a failed refresh. */
@Composable
private fun AccountBanner(bot: BotState, actions: AccountActions, offsetSeconds: Int) {
    val inv = bot.pendingInvoice?.invoice
    when {
        inv != null && inv.paid -> Banner("Оплата получена", Tone.Success, action = "Закрыть", onAction = actions.onDismissInvoice)
        inv != null && inv.expired -> Banner("Счёт не оплачен и закрыт", Tone.Warning, action = "Закрыть", onAction = actions.onDismissInvoice)
        // The button under it ("Оплатить счёт") opens the sheet with the payment page and the check.
        inv != null -> Banner(
            "Счёт" + (inv.amountText?.let { " на $it" }.orEmpty()) + " ждёт оплаты" +
                (if (inv.expiresAt > 0) " до ${Fmt.date(inv.expiresAt, offsetSeconds)}" else ""),
            Tone.Info,
        )
        bot.connectionNote != null -> Banner(bot.connectionNote, Tone.Warning)
        bot.lastError != null -> Banner("Не удалось обновить: ${bot.lastError}", Tone.Warning)
    }
}

/** A subscription by key on another service (opensub, spec 8.2): renewing, a trial, the devices, the password. */
@Composable
private fun KeySubscription(m: SubscriptionModel) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val state by container.subscription.state.collectAsState()
    val preparing by container.subscription.preparing.collectAsState()
    var passwordOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var signOutAsk by remember { mutableStateOf(false) }
    val s = state
    if (s == null) {
        Text("Ключ подписки выдаёт владелец своего сервиса. С ключом локации появятся здесь.", style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
        SecondaryButton("У меня ключ подписки", { navigator.open(Dest.AddKey) }, Modifier.fillMaxWidth())
        return
    }
    val platform = container.platform
    val look = s.look(platform.now(), platform.utcOffsetSeconds)
    val account = s.account
    Section(null) {
        ValueRow("Подписка", s.title.ifBlank { "OpenFlux" })
        if (account != null) ValueRow("Логин", account.login)
        ValueRow("Состояние", look.badge)
        ValueRow("Срок", look.line.removeSuffix("."))
        account?.plan?.let { ValueRow("Тариф", it.name) }
        if (account != null) ValueRow("Устройства", "${account.devices.size} из ${account.deviceLimit}")
    }
    if (preparing) ProgressLine("Готовим вашу локацию: обычно это минута-две")
    s.lastError?.let { Banner("Не удалось обновить: $it", Tone.Warning) }
    s.pendingOrder?.let { p ->
        Banner(
            "Ждём оплату заказа ${p.orderId}",
            Tone.Info,
            action = "Проверить",
            onAction = m::checkOrder,
            secondAction = if (p.paymentUrl.startsWith("https://")) "Открыть оплату снова" else null,
            onSecondAction = if (p.paymentUrl.startsWith("https://")) m::reopenPayment else null,
        )
    }
    if (s.announce.isNotBlank() && s.announce != s.announceSeen) {
        Banner(s.announce, Tone.Info, action = "Закрыть", onAction = container.subscription::dismissAnnounce)
    }
    when {
        s.kind == SubscriptionKind.Account && account?.trialAvailable == true && !s.active ->
            PrimaryButton("Начать пробный период", m::startTrial, Modifier.fillMaxWidth(), enabled = m.busy == null)
        s.kind == SubscriptionKind.Account -> PrimaryButton(if (look.attention) "Продлить" else "Продлить заранее", m::openPlans, Modifier.fillMaxWidth(), enabled = m.busy == null)
        s.support.isNotBlank() -> PrimaryButton("Продлить через поддержку", m::openSupport, Modifier.fillMaxWidth())
    }
    if (account != null && account.devices.isNotEmpty()) {
        Section("Устройства") {
            for (d in account.devices) {
                RowItem(
                    d.name.ifBlank { "Устройство" },
                    subtitle = if (d.current) "это устройство" else if (d.lastSeen > 0) "было в сети ${Fmt.ago(platform.now() - d.lastSeen * 1000)}" else null,
                    trailing = if (d.current) null else ({ TextAction("Убрать", { m.removeDevice(d.id) }, enabled = m.busy == null) }),
                )
            }
        }
    }
    Section(null) {
        if (s.support.isNotBlank()) RowItem("Поддержка", onClick = m::openSupport)
        if (s.kind == SubscriptionKind.Account) RowItem("Сменить пароль", onClick = { m.clearDialogError(); passwordOpen = true })
        RowItem(if (s.kind == SubscriptionKind.Account) "Выйти" else "Убрать подписку с устройства", onClick = { signOutAsk = true })
        if (s.kind == SubscriptionKind.Account) RowItem("Удалить аккаунт", onClick = { m.clearDialogError(); deleteOpen = true })
    }
    if (signOutAsk) {
        ConfirmDialog(
            title = if (s.kind == SubscriptionKind.Account) "Выйти из аккаунта?" else "Убрать подписку?",
            text = "Локации подписки пропадут с этого устройства.",
            confirm = if (s.kind == SubscriptionKind.Account) "Выйти" else "Убрать",
            onConfirm = { signOutAsk = false; m.signOut() },
            onDismiss = { signOutAsk = false },
        )
    }
    if (passwordOpen) PasswordSheet(m) { passwordOpen = false }
    if (deleteOpen) DeleteSheet(m) { deleteOpen = false }
    if (m.plansOpen) PlansSheet(m)
}

@Composable
private fun PasswordSheet(m: SubscriptionModel, onClose: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    Sheet(onDismiss = onClose, title = "Смена пароля") {
        Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            SecretField(old, { old = it }, "Текущий пароль")
            SecretField(new, { new = it }, "Новый пароль")
            SecretField(again, { again = it }, "Новый пароль ещё раз")
            Text("На других устройствах придётся войти заново.", style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
            m.dialogError?.let { Banner(it, Tone.Danger) }
            Submit(m.busy, "Сменить") { m.changePassword(old, new, again, onDone = onClose) }
        }
    }
}

@Composable
private fun DeleteSheet(m: SubscriptionModel, onClose: () -> Unit) {
    var password by remember { mutableStateOf("") }
    Sheet(onDismiss = onClose, title = "Удалить аккаунт?") {
        Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            Text("Аккаунт, подписка и устройства удалятся навсегда. Вернуть их не получится.", style = AppTheme.type.body, color = AppTheme.colors.text)
            SecretField(password, { password = it }, "Пароль")
            m.dialogError?.let { Banner(it, Tone.Danger) }
            if (m.busy != null) ProgressLine(m.busy!!)
            else io.openflux.app.components.DangerButton("Удалить навсегда", { m.deleteAccount(password, onDone = onClose) }, Modifier.fillMaxWidth())
        }
    }
}

/** Renewing a subscription by key: a plan, the order and how to pay, then "paid" (spec 8.2). */
@Composable
private fun PlansSheet(m: SubscriptionModel) {
    Sheet(onDismiss = m::closePlans, title = "Продление") {
        Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            val plans = m.plans
            val order = m.order
            when {
                m.paid -> {
                    Banner("Оплата получена. Подписка продлена", Tone.Success)
                    PrimaryButton("Готово", m::closePlans, Modifier.fillMaxWidth())
                }
                order != null -> {
                    Text(order.instructions.ifBlank { plans?.payment?.instructions.orEmpty() }.ifBlank { "Оплатите заказ ${order.orderId} по инструкции сервиса." }, style = AppTheme.type.body, color = AppTheme.colors.text)
                    ProgressLine("Жду подтверждения оплаты…")
                    SecondaryButton("Проверить оплату", m::checkOrder, Modifier.fillMaxWidth())
                }
                plans == null && m.plansError == null -> ProgressLine("Загружаю тарифы…")
                plans != null && plans.plans.isEmpty() -> Text("Платных тарифов пока нет.", style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
                plans != null -> {
                    Section(null) {
                        for (p in plans.plans) {
                            RowItem(
                                p.name,
                                subtitle = "${Fmt.days(p.days)} · устройств: ${p.devices}" + if (p.price.isNotBlank()) " · ${p.price}" else "",
                                selected = m.selectedPlan == p.id,
                                onClick = { m.selectedPlan = p.id },
                            )
                        }
                    }
                    Submit(m.busy, "Оформить заказ", m::checkout)
                }
            }
            m.plansError?.let { Banner(it, Tone.Danger) }
        }
    }
}
