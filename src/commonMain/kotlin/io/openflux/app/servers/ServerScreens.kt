package io.openflux.app.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.openflux.app.components.Banner
import io.openflux.app.components.Chip
import io.openflux.app.components.Collapsible
import io.openflux.app.components.ConfirmDialog
import io.openflux.app.components.DangerButton
import io.openflux.app.components.Field
import io.openflux.app.components.IconAction
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
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.nav.Dest
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.shell.LocalServerLink
import io.openflux.app.shell.rememberScreenModel
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.isActive
import io.openflux.desktop.service.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
private fun Chevron() = Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = AppTheme.colors.textMuted)

/**
 * The Servers section's first screen (spec 5): the person's servers, their nodes on web hostings, and this
 * device as a node. [selected] marks the one open beside the list in a wide window.
 */
@Composable
fun ServersRoot(selected: Dest? = null) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val link = LocalServerLink.current
    val settings by container.settings.settings.collectAsState()
    val profiles by container.profiles.profiles.collectAsState()
    val state by container.connection.state.collectAsState()
    val hostings = profiles.filter { it.phpNode != null }
    ScreenScaffold("Серверы", actions = { IconAction(Icons.Rounded.Add, "Добавить сервер", { navigator.open(Dest.AddServer) }) }) {
        if (settings.knownServers.isEmpty() && hostings.isEmpty()) {
            Text(
                "Своих серверов пока нет. Подключите сервер (VDS) по SSH или поставьте ноду на обычный хостинг.",
                style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                PrimaryButton("Добавить сервер", { navigator.open(Dest.AddServer) }, leading = Icons.Rounded.Dns)
                SecondaryButton("Без сервера", { navigator.open(Dest.NewHosting) }, leading = Icons.Rounded.Cloud)
            }
        } else {
            if (settings.knownServers.isNotEmpty()) {
                Section("Мои серверы") {
                    for (s in settings.knownServers) {
                        val id = serverIdOf(s)
                        val probe = link.probe(id)
                        RowItem(
                            s.host,
                            subtitle = buildString {
                                append(s.user)
                                if (s.port != 22) append(" · порт ${s.port}")
                                if (probe != null) append(" · нод: ${probe.channels.size}")
                            },
                            leading = { StatusDot(if (link.openTo == id) Status.On else Status.Off) },
                            trailing = { Chevron() },
                            selected = selected?.serverId == id,
                            onClick = { navigator.open(Dest.Server(id)) },
                        )
                    }
                }
            }
            if (hostings.isNotEmpty()) {
                Section("Без сервера") {
                    for (p in hostings) {
                        RowItem(
                            p.name,
                            subtitle = p.phpNode?.siteUrl,
                            leading = { Icon(Icons.Rounded.Cloud, contentDescription = null, tint = AppTheme.colors.textSecondary) },
                            trailing = { Chevron() },
                            selected = selected?.profileId == p.id,
                            onClick = { navigator.open(Dest.Hosting(p.id)) },
                        )
                    }
                }
            }
        }
//        Section(null) {
//            val exit = state.isActive && settings.mode == ConnectionMode.Exit
//            RowItem(
//                "Это устройство",
//                subtitle = if (exit) "Работает как нода" else "Раздать интернет другим через это устройство",
//                leading = { Icon(Icons.Rounded.Devices, contentDescription = null, tint = AppTheme.colors.textSecondary) },
//                trailing = { Chevron() },
//                selected = selected == Dest.ThisDevice,
//                onClick = { navigator.open(Dest.ThisDevice) },
//            )
//        }
    }
}

/** The way into a server: address, login, password or key; the rare under "Дополнительно". */
@Composable
fun LoginFields(form: LoginForm, showAddress: Boolean = true) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    if (showAddress) {
        Field(form.host, { form.host = it; form.error = null }, "Адрес сервера", placeholder = "203.0.113.7 или 203.0.113.7:2222")
        Field(form.user, { form.user = it; form.error = null }, "Логин")
    }
    Segmented(listOf(false, true), form.useKey, { form.useKey = it; form.error = null }, label = { if (it) "Ключ" else "Пароль" })
    if (form.useKey) {
        Field(form.privateKey, { form.privateKey = it; form.error = null }, "Закрытый ключ", singleLine = false, mono = true, placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----")
        TextAction("Выбрать файл ключа…", { scope.launch { form.readKeyFile(container.platform) } })
        SecretField(form.passphrase, { form.passphrase = it }, "Парольная фраза ключа, если есть")
    } else {
        SecretField(form.password, { form.password = it; form.error = null }, "Пароль")
    }
    Section(null) {
        Collapsible("Дополнительно") {
            Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                SecretField(form.sudo, { form.sudo = it }, "Пароль sudo, если логин не root")
            }
        }
    }
}

/** The server's key, new or changed, to confirm before going on (spec 5.1). */
@Composable
fun HostKeyDialog(onTrust: () -> Unit) {
    val link = LocalServerLink.current
    val prompt = link.hostKeyPrompt ?: return
    if (prompt.mismatch) {
        ConfirmDialog(
            title = "Ключ сервера изменился",
            text = "Раньше у этого сервера был другой ключ. Так бывает после переустановки системы — или когда кто-то подменяет сервер. " +
                "Если вы систему не переустанавливали, не продолжайте.\n\nКлюч сейчас: ${prompt.fingerprint}",
            confirm = "Сервер переустановлен",
            onConfirm = onTrust,
            onDismiss = link::dismissHostKey,
            danger = true,
        )
    } else {
        ConfirmDialog(
            title = "Новый сервер",
            text = "Ключ сервера: ${prompt.fingerprint}\n\nЕсли хостинг показывает отпечаток ключа, сверьте его.",
            confirm = "Доверять",
            onConfirm = onTrust,
            onDismiss = link::dismissHostKey,
        )
    }
}

/** Connecting to a new server, then how to put a node on it (spec 5.1). */
@Composable
fun AddServerScreen() {
    val navigator = LocalNavigator.current
    val link = LocalServerLink.current
    val form = remember { LoginForm(link) }
    val scope = rememberCoroutineScope()
    var connected by rememberSaveable { mutableStateOf<String?>(null) }
    val settings by LocalAppContainer.current.settings.settings.collectAsState()
    ScreenScaffold("Новый сервер", onBack = { navigator.back() }) {
        val id = connected
        if (id == null) {
            val recent = settings.knownServers.filterNot { link.hasAccess(serverIdOf(it)) }.take(5)
            if (recent.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                    for (s in recent) Chip(s.host, selected = false, onClick = { form.prefill(s) })
                }
            }
            LoginFields(form)
            form.error?.let { Banner(it, Tone.Danger) }
            if (form.busy) ProgressLine("Подключаюсь к серверу и смотрю, что на нём…")
            else PrimaryButton("Подключиться", { scope.launch { connected = form.connect() } }, Modifier.fillMaxWidth())
        } else {
            val probe = link.probe(id)
            Text("Сервер подключён. Как поставить ноду?", style = AppTheme.type.title, color = AppTheme.colors.text)
            Section(null) {
                RowItem(
                    "Быстро",
                    subtitle = "Только прямое подключение. Транспорты можно добавить потом",
                    trailing = { Chevron() },
                    onClick = { navigator.replaceTop(Dest.NewNode(id, quick = true)) },
                )
                RowItem(
                    "Настроить",
                    subtitle = "Выбрать транспорты и режим",
                    trailing = { Chevron() },
                    onClick = { navigator.replaceTop(Dest.NewNode(id)) },
                )
                if (probe != null && probe.channels.isNotEmpty()) {
                    RowItem(
                        "Открыть сервер",
                        subtitle = "На нём уже нод: ${probe.channels.size}",
                        trailing = { Chevron() },
                        onClick = { navigator.replaceTop(Dest.Server(id)) },
                    )
                }
            }
        }
    }
    HostKeyDialog(onTrust = { scope.launch { connected = form.trust() } })
}

/** One server: its nodes, what it said about itself, the way in (spec 5.2). */
@Composable
fun ServerScreen(dest: Dest.Server) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val link = LocalServerLink.current
    val m = rememberScreenModel { ServerScreenModel(container, link, dest.id, it) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var forgetAsk by remember { mutableStateOf(false) }
    LaunchedEffect(m) { if (m.hasAccess && m.channels == null && !m.needsSudo) m.load() }
    ScreenScaffold(m.host, onBack = { navigator.back() }) {
        if (!m.hasAccess) {
            Text("Войдите на сервер, чтобы увидеть и менять его ноды.", style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
            LoginFields(m.login, showAddress = false)
            (m.login.error ?: m.error)?.let { Banner(it, Tone.Danger) }
            val busy = m.busy
            if (busy != null) ProgressLine(busy) else PrimaryButton("Войти", m::connect, Modifier.fillMaxWidth())
            return@ScreenScaffold
        }
        Segmented(listOf(0, 1), tab, { tab = it }, label = { if (it == 0) "Ноды" else "Сервер" })
        if (tab == 0) {
            val channels = m.channels
            when {
                m.needsSudo && channels == null -> {
                    SecretField(m.sudoInput, { m.sudoInput = it }, "Пароль sudo")
                    PrimaryButton("Показать ноды", m::load, Modifier.fillMaxWidth())
                }
                channels == null -> Unit
                channels.isEmpty() -> {
                    Text("На сервере пока нет нод.", style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
                    PrimaryButton("Поставить ноду", { navigator.open(Dest.NewNode(dest.id)) }, leading = Icons.Rounded.Add)
                }
                else -> {
                    Section(null) {
                        for (info in channels) {
                            RowItem(
                                m.localName(info) ?: info.id,
                                subtitle = "${nodeStateText(info.state)} · порт ${info.port} · ${transportsLine(info.transportTypes)}",
                                leading = { StatusDot(if (info.running) Status.On else Status.Error) },
                                trailing = { Chevron() },
                                onClick = { navigator.open(Dest.Node(dest.id, info.id)) },
                            )
                        }
                    }
                    SecondaryButton("Поставить ноду", { navigator.open(Dest.NewNode(dest.id)) }, leading = Icons.Rounded.Add)
                }
            }
        } else {
            val probe = m.probe
            Section(null) {
                if (probe != null) {
                    ValueRow("Система", listOf(probe.os, probe.arch).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "неизвестно" })
                    ValueRow("Ядро OpenFlux", probe.core.ifBlank { "не установлено" })
                    ValueRow("Автообновление ядра", if (probe.autoupdate) "включено" else "выключено")
                }
                RowItem("Обновить сведения", onClick = m::refresh)
                RowItem("Закрыть доступ", subtitle = "Пароль забудется до следующего входа", onClick = m::closeAccess)
            }
            DangerButton("Забыть сервер", { forgetAsk = true })
        }
        m.error?.let { Banner(it, Tone.Danger) }
        m.busy?.let { ProgressLine(it) }
    }
    HostKeyDialog(onTrust = m::trust)
    if (forgetAsk) {
        ConfirmDialog(
            title = "Забыть сервер?",
            text = "Сервер пропадёт из списка. Ноды на нём продолжат работать, профили на этом устройстве останутся.",
            confirm = "Забыть",
            onConfirm = { forgetAsk = false; m.forget() },
            onDismiss = { forgetAsk = false },
            danger = true,
        )
    }
}
