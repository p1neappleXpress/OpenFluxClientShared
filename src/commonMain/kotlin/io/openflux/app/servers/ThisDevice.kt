package io.openflux.app.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import io.openflux.app.components.Banner
import io.openflux.app.components.Collapsible
import io.openflux.app.components.ConnectButton
import io.openflux.app.components.ConnectLook
import io.openflux.app.components.Field
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.QrView
import io.openflux.app.components.RowItem
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.ShareLinkActions
import io.openflux.app.components.Section
import io.openflux.app.components.Segmented
import io.openflux.app.components.Status
import io.openflux.app.components.StatusDot
import io.openflux.app.components.Tone
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitBackend
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.describe
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.managed
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.service.PlatformKind

/**
 * This device as an exit node for others (spec 5.5): which profile's transports it serves, how (L4/L3 on the
 * desktop), its direct port and the address clients dial; start, stop, and the link once it runs. Stopping makes
 * it a client again, so Home connects as usual.
 */
@Stable
class ThisDeviceModel(private val container: AppContainer) {
    /** Profiles this device can serve: not a subscription's location, not a node on a hosting. */
    val choices: List<Profile> get() = container.profiles.profiles.value.filter { !it.managed && !it.stream }

    val running: Boolean
        get() {
            val s = container.connection.state.value
            return s.isActive && container.settings.settings.value.mode == ConnectionMode.Exit
        }

    fun start(profile: Profile) {
        container.settings.update { it.copy(mode = ConnectionMode.Exit, selectedProfileId = profile.id) }
        container.connection.connect(profile, ConnectionMode.Exit)
    }

    fun stop() {
        container.connection.disconnect()
        container.settings.update { it.copy(mode = ConnectionMode.Client) }
    }
}

@Composable
fun ThisDeviceScreen() {
    val navigator = LocalNavigator.current
    ScreenScaffold("Это устройство", onBack = { navigator.back() }) { ExitNodeBody() }
}

/** The node's controls and, once it runs, its QR and link: «Это устройство» and Home's «Выходная нода». */
@Composable
fun ExitNodeBody() {
    val container = LocalAppContainer.current
    val m = remember(container) { ThisDeviceModel(container) }
    val settings by container.settings.settings.collectAsState()
    val state by container.connection.state.collectAsState()
    val profiles by container.profiles.profiles.collectAsState()
    val link by container.connection.exitShareLink.collectAsState()
    val choices = remember(profiles) { m.choices }
    val exit = settings.mode == ConnectionMode.Exit && state.isActive
    val chosen = choices.firstOrNull { it.id == settings.selectedProfileId } ?: choices.firstOrNull()
    val platform = container.platform
    if (!exit) Text(
        "Устройство может работать нодой: другие подключаются через него к интернету.",
        style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
    )
    if (exit) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ConnectButton(if (state is ConnectionState.Connected) ConnectLook.On else ConnectLook.Busy, "Остановить ноду", m::stop)
        }
        Section(null) {
            RowItem(
                if (state is ConnectionState.Connected) "Нода работает" else "Нода запускается…",
                subtitle = chosen?.name,
                leading = { StatusDot(if (state is ConnectionState.Connected) Status.On else Status.Busy) },
            )
        }
        val text = link
        if (text != null) {
            QrView(text, platform)
            ShareLinkActions(text)
            Text("По этой ссылке подключится любой. Одновременно работает одно устройство — второе вытеснит первое.", style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
        } else {
            Text("Ядро готовит ссылку…", style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
        }
        return
    }
    (state as? ConnectionState.Failed)?.takeIf { settings.mode == ConnectionMode.Exit }?.let { Banner(it.message, Tone.Danger) }
    if (choices.isEmpty()) {
        Banner("Нужен профиль со своими транспортами и ключом: добавьте его через ＋", Tone.Info)
        return
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ConnectButton(ConnectLook.Off, "Запустить ноду", { chosen?.let(m::start) })
    }
    if (state.isActive && settings.mode != ConnectionMode.Exit) {
        Text("Сейчас устройство подключено как клиент: запуск ноды это подключение остановит.", style = AppTheme.type.bodySmall, color = AppTheme.colors.warning)
    }
    Section("Что раздавать") {
        for (p in choices) {
            RowItem(p.name, subtitle = p.transport.title, selected = p.id == chosen?.id, onClick = {
                container.settings.update { it.copy(selectedProfileId = p.id) }
            })
        }
    }
    if (platform.kind == PlatformKind.Desktop && platform.exitL3Supported) {
        Section("Режим") {
            Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                Segmented(ExitBackend.entries, settings.exitBackend, { b -> container.settings.update { it.copy(exitBackend = b) } }, label = { it.label })
                Text(settings.exitBackend.describe(platform.exitL3Needs), style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
            }
        }
    }
    Section(null) {
        Collapsible("Дополнительно") {
            Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                Field(
                    settings.exitDirectPort.toString(),
                    { v -> v.toIntOrNull()?.takeIf { it in 1..65535 }?.let { p -> container.settings.update { it.copy(exitDirectPort = p) } } },
                    "Порт прямого подключения", keyboardType = KeyboardType.Number,
                )
                Field(settings.exitShareHost, { v -> container.settings.update { it.copy(exitShareHost = v.trim()) } }, "Адрес для клиентов", placeholder = "определится сам")
            }
        }
    }
}
