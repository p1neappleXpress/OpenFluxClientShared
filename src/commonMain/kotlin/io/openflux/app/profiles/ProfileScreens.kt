package io.openflux.app.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.openflux.app.components.Banner
import io.openflux.app.components.Collapsible
import io.openflux.app.components.ConfirmDialog
import io.openflux.app.components.Field
import io.openflux.app.components.IconAction
import io.openflux.app.components.LocalToaster
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.RowItem
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.SecretField
import io.openflux.app.components.Section
import io.openflux.app.components.Segmented
import io.openflux.app.components.Sheet
import io.openflux.app.components.TextAction
import io.openflux.app.components.Toggle
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.home.serverOf
import io.openflux.app.nav.Dest
import io.openflux.app.servers.isOwnNode
import io.openflux.app.servers.title
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.shell.rememberScreenModel
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.Codec
import io.openflux.desktop.model.DocumentKind
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.NetworkKind
import io.openflux.desktop.model.NodeTransports
import io.openflux.desktop.model.PhpHosts
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.ValueKind
import io.openflux.desktop.model.editableNode
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.managed
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.service.PlatformKind
import io.openflux.app.platform.LocalShortcuts
import io.openflux.app.platform.LocalTouchUi
import io.openflux.app.platform.PlatformBackHandler

/** "в Яндексе", "в Облаке Mail": where a document is made. */
val io.openflux.desktop.model.AccountKind.where: String
    get() = when (this) {
        io.openflux.desktop.model.AccountKind.Yandex -> "Яндексе"
        io.openflux.desktop.model.AccountKind.Mailru -> "Облаке Mail"
        else -> label
    }

/** What carries the traffic, in one line under a transport's name. */
fun TransportType.blurb(): String = when (this) {
    TransportType.VYANDEX -> "Документ Яндекса, по ссылке на редактирование"
    TransportType.YANDEX -> "Файл на Яндекс Диске"
    TransportType.BOARDS -> "Доска Яндекса"
    TransportType.MAILRU -> "Публичный файл Облака Mail.ru"
    TransportType.CUPSONLINE -> "Комнаты cups.online, код выдаёт нода"
    TransportType.TELEMOST -> "Конференция Яндекс Телемоста, по ссылке-приглашению"
    TransportType.AVITO -> "Комнаты Avito Code, код выдаёт нода"
    TransportType.ONEME -> "Звонок в MAX, нужен токен MAX Web"
    TransportType.DIRECT -> "Прямое TCP-соединение с нодой"
    TransportType.SCRIPT -> "JS-транспорт"
}

/** A profile (spec 6.2): what it is made of, and what can be done with it. */
@Composable
fun ProfileScreen(dest: Dest.Profile) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val actions = remember(container) { ProfileActions(container) }
    val profiles by container.profiles.profiles.collectAsState()
    val state by container.connection.state.collectAsState()
    val settings by container.settings.settings.collectAsState()
    val scripts by container.scripts.scripts.collectAsState()
    val p = profiles.firstOrNull { it.id == dest.id } ?: return
    var menu by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf(false) }
    val running = state.isActive && state.profile?.id == p.id
    ScreenScaffold(
        p.name,
        onBack = { navigator.back() },
        actions = {
            if (!p.managed) Box {
                IconAction(Icons.Rounded.MoreVert, "Ещё", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Дублировать") }, { menu = false; actions.duplicate(p)?.let { navigator.open(Dest.Profile(it.id)) } })
                    if (!p.isOwnNode && p.editableNode) DropdownMenuItem({ Text("Это моя нода") }, { menu = false; actions.markOwn(p); toaster.show("Теперь в «Мои серверы»") })
                    DropdownMenuItem({ Text("Удалить", color = AppTheme.colors.danger) }, { menu = false; deleteAsk = true })
                }
            }
        },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
            if (running) SecondaryButton("Отключить", { container.connection.disconnect() }, leading = Icons.Rounded.Stop)
            else SecondaryButton("Подключить", { actions.connect(p) }, leading = Icons.Rounded.PlayArrow)
            if (!p.managed) SecondaryButton("Изменить", { navigator.open(Dest.ProfileEdit(p.id)) }, leading = Icons.Rounded.Edit)
            if (actions.canShare(p)) SecondaryButton("Поделиться", { navigator.open(Dest.Give(p.id)) }, leading = Icons.Rounded.Share)
        }
        if (p.managed) Banner("Приходит с подпиской, менять её нельзя", Tone.Info, action = "Подписка", onAction = { navigator.open(Dest.Account) })
        p.problems().firstOrNull()?.let { Banner(it, Tone.Danger) }
        Section("Транспорты") {
            for (c in p.carriers) {
                val name = if (c.type == TransportType.SCRIPT) scripts.firstOrNull { it.id == c.scriptId }?.name ?: c.type.title else c.type.title
                val hidden = p.managed || c.type.kind == ValueKind.Token
                val value = when {
                    p.managed -> "скрыто"
                    c.type.kind == ValueKind.Token -> "токен скрыт" + if (c.uid.isNotBlank()) " · ${c.uid}" else ""
                    else -> c.value.ifBlank { "—" }
                }
                RowItem(name, subtitle = value + if (p.session && p.carriers.size > 1) " · приоритет ${c.priority}" else "", trailing = if (hidden || c.value.isBlank()) null else ({
                    IconAction(io.openflux.app.components.CopyIcon, "Копировать", { container.platform.setClipboardText(c.value); toaster.show("Скопировано") })
                }))
            }
        }
        Section("Параметры") {
            ValueRow("Режим", p.editMode.label)
            ValueRow("Шифрование", if (p.secret.isNotEmpty()) "AES-256-GCM" else "нет ключа")
            if (!p.session && !p.stream) ValueRow("Кодек", p.codec.label)
            ValueRow("Источник", p.source.label)
        }
        if (p.isOwnNode) {
            val server = serverOf(p, settings.knownServers)
            if (p.phpNode != null) RowItem("Открыть ноду на хостинге", onClick = { navigator.open(Dest.Hosting(p.id)) })
            else if (server != null) RowItem("Открыть сервер", onClick = { navigator.open(Dest.Server(server)) })
        }
    }
    if (deleteAsk) {
        ConfirmDialog(
            title = "Удалить «${p.name}»?",
            text = if (p.isOwnNode) "Профиль пропадёт с этого устройства. Сама нода продолжит работать." else "Профиль пропадёт с этого устройства.",
            confirm = "Удалить",
            onConfirm = { deleteAsk = false; actions.delete(p); navigator.back() },
            onDismiss = { deleteAsk = false },
            danger = true,
        )
    }
}

/** A profile's fields (spec 6.3). */
@Composable
fun ProfileEditScreen(dest: Dest.ProfileEdit) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val shortcuts = LocalShortcuts.current
    val touch = LocalTouchUi.current
    val m = rememberScreenModel { ProfileEditorModel(container, dest.id, it) }
    var leaveAsk by remember { mutableStateOf(false) }
    val d = m.draft
    val save = {
        val saved = m.save()
        if (saved != null) {
            toaster.show(if (m.isNew) "Профиль добавлен" else "Профиль сохранён")
            navigator.replaceTop(Dest.Profile(saved.id))
        }
    }
    val leave: () -> Unit = { if (m.hasUnsaved) leaveAsk = true else navigator.back() }
    PlatformBackHandler(enabled = m.hasUnsaved) { leaveAsk = true }
    DisposableEffect(m, touch) {
        if (touch) return@DisposableEffect onDispose { }
        val off = shortcuts.register { e ->
            if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.S) { save(); true }
            else if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { leave(); true }
            else false
        }
        onDispose { off() }
    }
    ScreenScaffold(
        if (m.isNew) "Новый профиль" else "Изменить профиль",
        onBack = leave,
        actions = { if (!m.readOnly) TextAction("Сохранить", save) },
    ) {
        if (m.readOnly) {
            Banner("Приходит с подпиской, менять её нельзя", Tone.Info)
            return@ScreenScaffold
        }
        if (m.showProblems) m.problems.firstOrNull()?.let { Banner(it, Tone.Danger) }
        Field(d.name, { v -> m.edit { it.copy(name = v) } }, "Название", placeholder = "Например, Нода в Нидерландах")
        Section("Режим") {
            Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                Segmented(EditMode.entries, d.editMode, m::setMode, label = { it.label })
                Text(
                    when (d.editMode) {
                        EditMode.Classic -> "Один транспорт."
                        EditMode.Session -> "Несколько транспортов, переключение по приоритету. Нода должна работать в режиме Session."
                        EditMode.Stream -> "Нода на PHP-хостинге, без ключа. Проще создать через ＋ → «Без сервера»."
                    },
                    style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
                )
            }
        }
        d.carriers.forEachIndexed { i, _ ->
            TransportBlock(m, i)
        }
        if (d.session) SecondaryButton("Добавить транспорт", m::addTransport, leading = Icons.Rounded.Add)
        if (!d.stream) {
            SecretField(d.secret, { v -> m.edit { it.copy(secret = v.trim()) } }, "Ключ шифрования")
            TextAction("Сгенерировать ключ", m::newKey)
            when {
                d.secret.isBlank() && !d.session -> Banner("Без ключа трафик виден владельцу площадки (Яндекс, Mail.ru). Задайте ключ, как на ноде.", Tone.Warning)
                d.secret.isNotBlank() && d.secret.length < 24 -> Banner("Короткий ключ можно подобрать. Надёжнее сгенерированный.", Tone.Info)
            }
        }
        if ((!d.session && !d.stream) || d.session) {
            Section(null) {
                Collapsible("Дополнительно") {
                    Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                        if (!d.session && !d.stream) {
                            Text("Кодек", style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
                            Segmented(Codec.entries, d.codec, { c -> m.edit { it.copy(codec = c) } }, label = { it.label })
                        }
                        if (d.session) {
                            Field(d.context, { v -> m.edit { it.copy(context = v.trim()) } }, "Контекст шифрования", placeholder = "Пусто: адрес главного документа")
                        }
                    }
                }
            }
        }
    }
    if (leaveAsk) {
        ConfirmDialog(
            title = "Изменения не сохранены",
            text = "Если выйти, они пропадут.",
            confirm = "Выйти без сохранения",
            onConfirm = { leaveAsk = false; navigator.back() },
            onDismiss = { leaveAsk = false },
            danger = true,
            dismiss = "Остаться",
        )
    }
}

/** One transport of the draft: which, its value, and what helps fill it. */
@Composable
private fun TransportBlock(m: ProfileEditorModel, index: Int) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val settings by container.settings.settings.collectAsState()
    val all by container.scripts.scripts.collectAsState()
    val c = m.transport(index) ?: return
    val d = m.draft
    val scripts = all.filter { it.enabled && settings.experimental }
    val script = if (c.type == TransportType.SCRIPT) scripts.firstOrNull { it.id == c.scriptId } else null
    var pick by remember { mutableStateOf(false) }
    val android = container.platform.kind == PlatformKind.Android
    Section(if (!d.session) "Транспорт" else if (index == 0) "Основной транспорт" else "Транспорт ${index + 1}") {
        RowItem(
            script?.name ?: c.type.title,
            subtitle = if (c.type == TransportType.SCRIPT && script == null) "JS-транспорт выключен" else c.type.blurb(),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) IconAction(Icons.Rounded.Close, "Убрать транспорт", { m.removeTransport(index) })
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = AppTheme.colors.textMuted)
                }
            },
            onClick = { pick = true },
        )
        Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
            if (c.type == TransportType.SCRIPT && !settings.experimental) {
                Text("Экспериментальные функции выключены: с этим транспортом профиль не подключится.", style = AppTheme.type.bodySmall, color = AppTheme.colors.warning)
            }
            val param = script?.primaryParam
            val noField = c.type == TransportType.SCRIPT && script != null && param == null
            if (!noField) {
                val label = when {
                    c.type == TransportType.SCRIPT -> param?.label?.ifBlank { null } ?: "Параметр"
                    c.type.kind == ValueKind.DocumentUrl -> when {
                        d.stream && c.type == TransportType.CUPSONLINE -> "Адрес комнаты cups.online"
                        c.type == TransportType.CUPSONLINE || c.type == TransportType.AVITO -> "Код комнат"
                        else -> "Ссылка на документ"
                    }
                    c.type.kind == ValueKind.Address -> "Адрес ноды"
                    else -> "Токен MAX Web"
                }
                val error = if (c.value.isBlank()) null else when {
                    d.stream && c.type == TransportType.CUPSONLINE -> if (PhpHosts.cupsRoom(c.value) == null) "Нужен адрес комнаты: https://interview.cups.online/live-coding/?room=…" else null
                    d.stream && c.type == TransportType.MAILRU -> if (NodeTransports.cleanMailru(c.value) == null) "Нужна публичная ссылка на документ Mail.ru" else null
                    else -> Profile.carrierProblem(c)?.takeIf { c.type.kind != ValueKind.Token }
                }
                if (c.type.kind == ValueKind.Token || param?.type == "secret") {
                    SecretField(c.value, { v -> m.editTransport(index) { it.copy(value = v.trim()) } }, label, error = error)
                } else {
                    Field(c.value, { v -> m.editTransport(index) { it.copy(value = v.trim()) } }, label, placeholder = if (c.type == TransportType.SCRIPT) param?.type else c.type.valueHint, error = error, mono = true)
                }
            }
            if (script != null && script.hasSettings) {
                TextAction("Настройки транспорта", {
                    val current = c.settings + (param?.key?.let { mapOf(it to c.value) } ?: emptyMap())
                    container.scriptSettings.open(script.id, current) { saved ->
                        val v = param?.key?.let { saved[it] } ?: c.value
                        val rest = param?.key?.let { saved - it } ?: saved
                        m.editTransport(index) { it.copy(value = v, settings = rest) }
                    }
                })
            }
            if (c.type == TransportType.ONEME) Field(c.uid, { v -> m.editTransport(index) { it.copy(uid = v.trim()) } }, "ID пользователя MAX", placeholder = "Число из адреса звонка")
            if (m.busyIndex == index) ProgressLine("Создаю…")
            else {
                DocumentKind.of(c.type)?.let { kind ->
                    TextAction("Создать ${kind.label} в ${kind.account.where}", { m.makeDocument(index) })
                }
                if (c.type == TransportType.CUPSONLINE && !d.stream) TextAction("Сгенерировать комнаты", { m.makeRooms(index) })
            }
            if (m.error != null && m.busyIndex == null) Text(m.error!!, style = AppTheme.type.bodySmall, color = AppTheme.colors.danger)
            if (d.session) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                    Field(
                        c.priority.toString(),
                        { v -> v.filter(Char::isDigit).take(3).toIntOrNull()?.let { p -> m.editTransport(index) { it.copy(priority = p) } } },
                        "Приоритет", keyboardType = KeyboardType.Number, modifier = Modifier.width(120.dp),
                    )
                }
                val networks = NetworkKind.entries.filter { !android || it != NetworkKind.Ethernet }
                Text("Сеть", style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                    for (n in networks) io.openflux.app.components.Chip(n.label, c.network == n, { m.editTransport(index) { it.copy(network = n) } })
                }
            }
        }
    }
    if (pick) {
        Sheet(onDismiss = { pick = false }, title = "Транспорт") {
            val builtIn = when {
                d.stream -> PhpHosts.carriers
                d.session -> TransportType.entries.filter { it != TransportType.SCRIPT }
                else -> TransportType.entries.filter { !it.sessionOnly }
            }
            for (t in builtIn) {
                RowItem(t.title, subtitle = t.blurb(), selected = c.type == t, onClick = {
                    m.editTransport(index) { it.copy(type = t, scriptId = "") }
                    pick = false
                })
            }
            if (settings.experimental && !d.stream && d.session) {
                for (s in scripts) {
                    RowItem(s.name, subtitle = "JS-транспорт", selected = c.scriptId == s.id, onClick = {
                        m.editTransport(index) { it.copy(type = TransportType.SCRIPT, scriptId = s.id) }
                        pick = false
                    })
                }
                TextAction("Управлять транспортами", { pick = false; navigator.open(Dest.Scripts) })
            }
        }
    }
}
