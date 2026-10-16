package io.openflux.app.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.openflux.app.components.Banner
import io.openflux.app.components.ChangesBar
import io.openflux.app.components.Collapsible
import io.openflux.app.components.ConfirmDialog
import io.openflux.app.components.Field
import io.openflux.app.components.IconAction
import io.openflux.app.components.LocalToaster
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.RowItem
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.SecretField
import io.openflux.app.components.Section
import io.openflux.app.components.Segmented
import io.openflux.app.components.Status
import io.openflux.app.components.StatusDot
import io.openflux.app.components.TextAction
import io.openflux.app.components.Toggle
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.nav.Dest
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.shell.LocalServerLink
import io.openflux.app.shell.rememberScreenModel
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.NodeTransports
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.app.platform.PlatformBackHandler
import kotlinx.coroutines.launch

/** An installed node (spec 5.3). */
@Composable
fun NodeScreen(dest: Dest.Node) {
    val container = LocalAppContainer.current
    val link = LocalServerLink.current
    val m = rememberScreenModel { NodeEditorModel(container, link, dest.server, dest.channel, it) }
    LaunchedEffect(m) { if (m.installed == null && link.hasAccess(dest.server)) m.load() }
    NodeEditor(m, dest.server)
}

/** A new node on a server; [Dest.NewNode.quick] installs a direct-only node at once (spec 5.1). */
@Composable
fun NewNodeScreen(dest: Dest.NewNode) {
    val container = LocalAppContainer.current
    val link = LocalServerLink.current
    val navigator = LocalNavigator.current
    val m = rememberScreenModel { NodeEditorModel(container, link, dest.server, null, it) }
    LaunchedEffect(m) { if (dest.quick && m.busy == null && m.verifyFailed == null && m.done == null) m.install() }
    LaunchedEffect(m.done) { m.done?.let { navigator.replaceTop(it) } }
    NodeEditor(m, dest.server)
}

@Composable
private fun NodeEditor(m: NodeEditorModel, serverId: String) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val link = LocalServerLink.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    // JS transports are an experimental feature: offered while it is on (a node that already runs some shows them anyway).
    val allScripts by container.scripts.scripts.collectAsState()
    val installedScripts = if (settings.experimental) allScripts else emptyList()
    var leaveAsk by remember { mutableStateOf(false) }
    var removeAsk by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val state by container.connection.state.collectAsState()
    val info = m.installed
    val d = m.draft
    val busy = m.busy
    val leave: () -> Unit = { if (m.hasUnsaved) leaveAsk = true else navigator.back() }
    PlatformBackHandler(enabled = m.hasUnsaved) { leaveAsk = true }
    LaunchedEffect(m.notice) { m.notice?.let { toaster.show(it); m.clearNotice() } }

    ScreenScaffold(
        title = if (m.creating) "Новая нода" else d.name.ifBlank { info?.id ?: "Нода" },
        onBack = leave,
        actions = {
            if (info != null) {
                Box {
                    IconAction(Icons.Rounded.MoreVert, "Ещё", { menu = true })
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Обновить состояние") }, { menu = false; m.load() })
                        if (info.transportTypes.any { it == TransportType.VYANDEX || it == TransportType.YANDEX }) {
                            DropdownMenuItem({ Text("Передать вход Яндекса ноде") }, { menu = false; m.pushYandexSignIn() })
                        }
                        DropdownMenuItem({ Text("Удалить с сервера", color = AppTheme.colors.danger) }, { menu = false; removeAsk = true })
                    }
                }
            }
        },
        bottomBar = {
            if (m.creating) {
                Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.page), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                    when {
                        busy != null -> ProgressLine(busy, onDetails = { navigator.open(Dest.Logs) })
                        m.verifyFailed != null -> {
                            Banner(m.verifyFailed!!, Tone.Danger, action = "Повторить", onAction = m::retryVerify)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                                SecondaryButton("Оставить как есть", m::keepAnyway)
                                TextAction("Удалить с сервера", m::removeFailed, color = AppTheme.colors.danger)
                            }
                        }
                        else -> {
                            (m.error ?: d.problem())?.let { Text(it, style = AppTheme.type.bodySmall, color = if (m.error != null) AppTheme.colors.danger else AppTheme.colors.warning) }
                            PrimaryButton("Установить", m::install, Modifier.fillMaxWidth(), enabled = d.problem() == null)
                        }
                    }
                }
            } else {
                ChangesBar(m.changes, busy, m.applyProblem, m.error, m::discard, m::apply)
            }
        },
    ) {
        if (!link.hasAccess(serverId)) {
            Banner("Нет доступа к серверу: войдите на нём заново", Tone.Warning, action = "Открыть сервер", onAction = { navigator.open(Dest.Server(serverId)) })
            return@ScreenScaffold
        }
        if (info != null) {
            val local = m.localProfile
            val through = local != null && state.isActive && state.profile?.id == local.id
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                StatusDot(if (info.running) Status.On else Status.Error)
                Text(nodeStateText(info.state).replaceFirstChar { it.uppercase() } + " · " + info.id, style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
            }
            Section(null) {
                ValueRow("Адрес", "${m.serverHost}:${info.port}", mono = true, onCopy = {
                    container.platform.setClipboardText("${m.serverHost}:${info.port}")
                    toaster.show("Адрес скопирован")
                })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                if (through) SecondaryButton("Отключить", { container.connection.disconnect() }, leading = Icons.Rounded.Stop)
                else SecondaryButton("Подключить себе", m::connectHere, leading = Icons.Rounded.PlayArrow, enabled = busy == null)
                SecondaryButton("Поделиться", {
                    scope.launch { runCatching { m.adopt() }.onSuccess { navigator.open(Dest.Give(it.id)) }.onFailure { toaster.show(it.message ?: "Не получилось") } }
                }, leading = Icons.Rounded.Share, enabled = busy == null)
            }
        } else if (!m.creating) {
            busy?.let { ProgressLine(it) }
            m.error?.let { Banner(it, Tone.Danger, action = "Повторить", onAction = m::load) }
            if (m.needsSudo) SecretField(m.sudoInput, { m.sudoInput = it }, "Пароль sudo")
            return@ScreenScaffold
        }

        if (m.creating) Text("На сервере ${m.serverHost}", style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
        val editable = busy == null
        Section("Транспорты") {
            Toggle(TransportType.VYANDEX.title, d.volga, { on -> m.edit { it.copy(volga = on) } }, enabled = editable)
            Toggle(TransportType.YANDEX.title, d.yandexDocs, { on -> m.edit { it.copy(yandexDocs = on) } }, enabled = editable)
            if (d.needsYandexDoc) {
                LinkRow(
                    value = d.yandexDoc,
                    onValue = { v -> m.edit { it.copy(yandexDoc = v) } },
                    label = "Ссылка на документ Яндекса",
                    actions = {
                        TextAction("Создать в Яндексе", m::createYandexDocument, enabled = editable)
                        if (d.yandexDoc.isNotBlank()) TextAction("Проверить", m::checkDocument, enabled = editable)
                    },
                )
            }
            Toggle(TransportType.MAILRU.title, d.mailru, { on -> m.edit { it.copy(mailru = on) } }, enabled = editable)
            if (d.mailru) {
                LinkRow(d.mailruLink, { v -> m.edit { it.copy(mailruLink = v) } }, "Публичная ссылка Mail.ru") {
                    TextAction("Создать в Облаке Mail", m::createMailruDocument, enabled = editable)
                }
            }
            Toggle(TransportType.BOARDS.title, d.board, { on -> m.edit { it.copy(board = on) } }, enabled = editable)
            if (d.board) {
                LinkRow(d.boardLink, { v -> m.edit { it.copy(boardLink = v) } }, "Ссылка на доску") {
                    TextAction("Создать доску", m::createBoard, enabled = editable)
                }
            }
            Toggle(TransportType.TELEMOST.title, d.telemost, { on -> m.edit { it.copy(telemost = on) } }, enabled = editable)
            if (d.telemost) {
                LinkRow(d.telemostLink, { v -> m.edit { it.copy(telemostLink = v) } }, "Ссылка на конференцию Телемоста") {
                    TextAction("Создать в Телемосте", m::createTelemost, enabled = editable)
                }
            }
            Toggle(
                TransportType.CUPSONLINE.title, d.cups, { on -> m.edit { it.copy(cups = on) } },
                subtitle = if (d.cups && d.cupsRooms.isEmpty()) "Комнаты создадутся сами" else null, enabled = editable,
            )
            if (d.cups) {
                LinkRow(d.cupsRooms, { v -> m.edit { it.copy(cupsRooms = v.trim()) } }, "Код комнат cups.online") {
                    TextAction(if (d.cupsRooms.isEmpty()) "Создать комнаты" else "Новые комнаты", m::createCupsRooms, enabled = editable)
                }
            }
            Toggle(
                TransportType.AVITO.title, d.avito, { on -> m.edit { it.copy(avito = on) } },
                subtitle = if (d.avito && d.avitoRooms.isEmpty()) "Комнаты создадутся сами" else null, enabled = editable,
            )
            if (d.avito) {
                LinkRow(d.avitoRooms, { v -> m.edit { it.copy(avitoRooms = v.trim()) } }, "Код комнат Avito") {
                    TextAction(if (d.avitoRooms.isEmpty()) "Создать комнаты" else "Новые комнаты", m::createAvitoRooms, enabled = editable)
                }
            }
            // Signed .flux packages installed here; the app sends each one to the node.
            val runnable = installedScripts.filter(NodeTransports::nodeCanRun)
            val onNode = d.scripts.map { it.id }.toSet()
            for (script in runnable + d.scripts.filter { s -> runnable.none { it.id == s.id } }.map { s -> missingScript(s.id) }) {
                val chosen = d.scripts.firstOrNull { it.id == script.id }
                Toggle(
                    "JS: " + script.name.ifBlank { script.id }, script.id in onNode,
                    { on -> m.edit { it.copy(scripts = if (on) it.scripts + NodeScript(script.id) else it.scripts.filterNot { s -> s.id == script.id }) } },
                    subtitle = if (script.fileName.isEmpty()) "не установлен на этом устройстве" else null,
                    enabled = editable && (script.fileName.isNotEmpty() || script.id in onNode),
                )
                val param = script.primaryParam
                if (chosen != null && param != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.s)) {
                        Field(chosen.value, { v -> m.edit { it.copy(scripts = it.scripts.map { s -> if (s.id == script.id) s.copy(value = v) else s }) } }, param.label.ifBlank { "Параметр" }, mono = true)
                    }
                }
            }
            RowItem(TransportType.DIRECT.title, subtitle = "Всегда включено: запасной путь, если другие недоступны", enabled = false)
        }
        m.documentWarning?.let { Banner(it, Tone.Info) }

        Section("Режим") {
            Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                Segmented(NodeMode.entries, d.mode, { mode -> m.edit { it.copy(mode = mode) } }, label = { it.label }, enabled = { editable })
                Text(d.mode.short, style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
            }
        }

        Section(null) {
            Collapsible("Дополнительно") {
                Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                    Field(d.name, { v -> m.edit { it.copy(name = v) } }, "Имя на этом устройстве")
                    if (m.creating) {
                        Toggle("Автообновление ядра на сервере", d.autoUpdate, { on -> m.edit { it.copy(autoUpdate = on) } })
                    }
                }
            }
        }
        if (m.needsSudo) SecretField(m.sudoInput, { m.sudoInput = it }, "Пароль sudo")
    }

    if (leaveAsk) {
        ConfirmDialog(
            title = "Изменения не применены",
            text = "Если выйти, они пропадут.",
            confirm = "Выйти без применения",
            onConfirm = { leaveAsk = false; m.discard(); navigator.back() },
            onDismiss = { leaveAsk = false },
            danger = true,
            dismiss = "Остаться",
        )
    }
    if (removeAsk) {
        ConfirmDialog(
            title = "Удалить ноду?",
            text = "Нода остановится и пропадёт с сервера вместе с ключом. Ссылки, которые вы раздавали, перестанут работать. " +
                "Её профиль на этом устройстве тоже удалится.",
            confirm = "Удалить",
            onConfirm = { removeAsk = false; m.remove { navigator.back() } },
            onDismiss = { removeAsk = false },
            danger = true,
        )
    }
}

/** A transport's link field under its switch, with what can be done about it. */
@Composable
private fun LinkRow(value: String, onValue: (String) -> Unit, label: String, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.s)) {
        Field(value, onValue, label, mono = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) { actions() }
    }
}

/** A package the node runs that this device does not have: shown so it can be turned off. */
private fun missingScript(id: String) = InstalledScript(id = id, name = id, pubkeyHex = "", fingerprint = "", fileName = "")
