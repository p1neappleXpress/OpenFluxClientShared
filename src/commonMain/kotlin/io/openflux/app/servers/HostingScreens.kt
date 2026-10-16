package io.openflux.app.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import io.openflux.app.components.Banner
import io.openflux.app.components.Chip
import io.openflux.app.components.Collapsible
import io.openflux.app.components.ConfirmDialog
import io.openflux.app.components.Field
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
import io.openflux.app.shell.rememberScreenModel
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.PhpHosts
import io.openflux.desktop.model.PhpMessages
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.LocalAppContainer

/** A node on a web hosting, made here (spec 5.4). */
@Composable
fun NewHostingScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val m = rememberScreenModel { HostingSetupModel(container, it) }
    val busy = m.busy
    val done = m.done
    LaunchedEffect(done) { if (done != null && m.securityNote == null) navigator.replaceTop(done) }
    ScreenScaffold(
        "Нода без сервера",
        onBack = { navigator.back() },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.page), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                when {
                    done != null -> {
                        Banner(m.securityNote ?: "Нода работает", Tone.Warning)
                        PrimaryButton("Готово", { navigator.replaceTop(done) }, Modifier.fillMaxWidth())
                    }
                    busy != null -> ProgressLine(busy, onDetails = { navigator.open(Dest.Logs) })
                    m.verifyFailed != null -> {
                        Banner(m.verifyFailed!!, Tone.Danger, action = "Повторить", onAction = m::retryVerify)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                            SecondaryButton("Оставить как есть", m::keepAnyway)
                            if (m.canRemove) TextAction("Удалить с хостинга", m::removeFailed, color = AppTheme.colors.danger)
                        }
                    }
                    m.error != null && m.canRemove -> FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                        PrimaryButton("Повторить", m::install)
                        TextAction("Удалить с хостинга", m::removeFailed, color = AppTheme.colors.danger)
                    }
                    else -> PrimaryButton(if (m.existing) "Подключить ноду" else "Установить", m::install, Modifier.fillMaxWidth())
                }
            }
        },
    ) {
        Text("Нода на обычном хостинге с PHP, даже на бесплатном.", style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
        Segmented(listOf(false, true), m.existing, { m.existing = it }, label = { if (it) "Уже залита" else "Залить по FTP" })
        if (m.existing) {
            Field(m.site, m::onExistingAddress, "Адрес сайта или страницы ноды", placeholder = "https://ваш-сайт.ru")
            Field(m.token, { m.token = it }, "Ключ доступа", mono = true)
        } else {
            Field(m.ftpHost, { m.ftpHost = it }, "Адрес FTP-сервера", placeholder = "ftpupload.net")
            m.preset?.let { Text(it.note, style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary) }
            Field(m.ftpUser, { m.ftpUser = it }, "Логин FTP")
            SecretField(m.ftpPassword, { m.ftpPassword = it }, "Пароль FTP")
            Field(m.site, { m.site = it }, "Адрес сайта", placeholder = "https://ваш-сайт.ru")
        }
        if (m.folderChoices.isNotEmpty()) {
            Text("Какая папка на хостинге — сайт?", style = AppTheme.type.title, color = AppTheme.colors.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                for (dir in m.folderChoices) Chip(dir, selected = false, onClick = { m.chooseFolder(dir) })
            }
        }
        Section("Транспорт") {
            Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                Segmented(PhpHosts.carriers, m.carrier, { m.carrier = it }, label = { it.title })
                if (m.carrier == TransportType.MAILRU) Field(m.mailru, { m.mailru = it }, "Публичная ссылка Mail.ru", mono = true)
            }
        }
        Section(null) {
            Collapsible("Дополнительно") {
                Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                    Field(m.name, { m.name = it }, "Имя на этом устройстве")
                    if (!m.existing) {
                        Field(m.ftpPort, { m.ftpPort = it }, "Порт FTP", keyboardType = KeyboardType.Number)
                        Field(m.folder, { m.folder = it }, "Папка сайта", placeholder = "htdocs")
                        Field(m.chosenToken, { m.chosenToken = it }, "Свой ключ доступа", mono = true)
                        Toggle(
                            "Разрешить FTP без шифрования", m.plainFtp, { m.plainFtp = it },
                            subtitle = "Только если хостинг не умеет иначе: пароль пойдёт открытым текстом",
                        )
                    }
                }
            }
        }
        m.error?.let { Banner(it, Tone.Danger) }
    }
}

/** A node on a web hosting (spec 5.4): its state, start and stop, panel, key; forget it here. */
@Composable
fun HostingScreen(dest: Dest.Hosting) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val m = rememberScreenModel { HostingNodeModel(container, dest.profile, it) }
    val profiles by container.profiles.profiles.collectAsState()
    val connection by container.connection.state.collectAsState()
    var forgetAsk by remember { mutableStateOf(false) }
    val p = profiles.firstOrNull { it.id == dest.profile } ?: return
    val node = p.phpNode ?: return
    LaunchedEffect(m) { m.refresh() }
    ScreenScaffold(p.name, onBack = { navigator.back() }) {
        val st = m.state
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
            StatusDot(if (st?.running == true) Status.On else if (st == null) Status.Off else Status.Error)
            Text(
                st?.let { PhpMessages.nodeStatus(it).replaceFirstChar { c -> c.uppercase() } } ?: "Состояние неизвестно",
                style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
            )
        }
        Section(null) {
            ValueRow("Сайт", node.siteUrl.substringAfter("://"))
            ValueRow("Транспорт", p.transport.title)
            ValueRow("Ключ доступа", m.maskedToken, mono = true, onCopy = { m.copyToken(); toaster.show("Ключ скопирован") })
        }
        val through = connection.isActive && connection.profile?.id == p.id
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
            if (through) SecondaryButton("Отключить", { container.connection.disconnect() }, leading = Icons.Rounded.Stop)
            else SecondaryButton("Подключить себе", { io.openflux.app.shell.connectAsClient(container, p) }, leading = Icons.Rounded.PlayArrow)
            SecondaryButton("Поделиться", { navigator.open(Dest.Give(p.id)) }, leading = Icons.Rounded.Share)
        }
        Section("Нода на хостинге") {
            if (st?.running == false) RowItem("Запустить ноду", onClick = m::start, enabled = m.busy == null)
            else RowItem("Остановить ноду", subtitle = "Подключение через неё перестанет работать", onClick = m::stop, enabled = m.busy == null)
            RowItem("Панель ноды", subtitle = "Её страница в браузере: журнал, запуск и остановка", onClick = m::openPanel, enabled = m.busy == null)
            RowItem("Обновить состояние", onClick = m::refresh, enabled = m.busy == null)
        }
        m.busy?.let { ProgressLine(it) }
        m.error?.let { Banner(it, Tone.Danger) }
        TextAction("Убрать из приложения", { forgetAsk = true }, color = AppTheme.colors.danger)
    }
    if (forgetAsk) {
        ConfirmDialog(
            title = "Убрать ноду из приложения?",
            text = "Профиль пропадёт с этого устройства. Сама нода на хостинге продолжит работать: остановить её можно здесь заранее или в её панели.",
            confirm = "Убрать",
            onConfirm = { forgetAsk = false; m.forget(); navigator.back() },
            onDismiss = { forgetAsk = false },
            danger = true,
        )
    }
}
