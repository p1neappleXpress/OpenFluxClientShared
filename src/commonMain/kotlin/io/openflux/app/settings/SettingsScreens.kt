package io.openflux.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import io.openflux.app.components.Banner
import io.openflux.app.components.Field
import io.openflux.app.components.LocalToaster
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.RowItem
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.Section
import io.openflux.app.components.Segmented
import io.openflux.app.components.TextAction
import io.openflux.app.components.Toggle
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.nav.Dest
import io.openflux.app.nav.LocalInPane
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.text.Fmt
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.model.CoreRepos
import io.openflux.desktop.model.CoreSource
import io.openflux.desktop.model.ThemeMode
import io.openflux.desktop.model.UpdateChannel
import io.openflux.desktop.model.UpdateState
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.AccountException
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.service.PlatformKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The pages of Settings (spec 8.3); Account and Journal are screens of their own. */
enum class SettingsPage(val id: String, val title: String, val icon: ImageVector) {
    Connection("connection", "Подключение", Icons.Rounded.SwapHoriz),
    Vpn("vpn", "VPN и прокси", Icons.Rounded.Route),
    Account("account", "Аккаунт OpenFlux", Icons.Rounded.AccountCircle),
    Services("services", "Сервисы", Icons.Rounded.Key),
    Core("core", "Ядро", Icons.Rounded.Memory),
    Look("look", "Внешний вид", Icons.Rounded.DarkMode),
    Journal("journal", "Журнал", Icons.Rounded.ListAlt),
    Experimental("experimental", "Экспериментальное", Icons.Rounded.Extension),
    About("about", "О программе", Icons.Rounded.Info);

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

private fun SettingsPage.dest(): Dest = when (this) {
    SettingsPage.Account -> Dest.Account
    SettingsPage.Journal -> Dest.Logs
    else -> Dest.SettingsPage(id)
}

/** Settings' list of pages. */
@Composable
fun SettingsRoot(selected: Dest? = null) {
    val navigator = LocalNavigator.current
    val android = LocalAppContainer.current.platform.kind == PlatformKind.Android
    ScreenScaffold("Настройки") {
        Section(null) {
            for (p in SettingsPage.entries) {
                RowItem(
                    if (p == SettingsPage.Vpn && android) "VPN" else p.title,
                    leading = { Icon(p.icon, contentDescription = null, tint = AppTheme.colors.textSecondary) },
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = AppTheme.colors.textMuted) },
                    selected = selected == p.dest(),
                    onClick = { navigator.open(p.dest()) },
                )
            }
        }
    }
}

/** One page of Settings. */
@Composable
fun SettingsPageScreen(dest: Dest.SettingsPage) {
    val navigator = LocalNavigator.current
    val page = SettingsPage.of(dest.page) ?: return
    val android = LocalAppContainer.current.platform.kind == PlatformKind.Android
    ScreenScaffold(if (page == SettingsPage.Vpn && android) "VPN" else page.title, onBack = { navigator.back() }) {
        when (page) {
            SettingsPage.Connection -> ConnectionPage()
            SettingsPage.Vpn -> VpnPage()
            SettingsPage.Services -> ServicesPage()
            SettingsPage.Core -> CorePage()
            SettingsPage.Look -> LookPage()
            SettingsPage.Experimental -> ExperimentalPage()
            SettingsPage.About -> AboutPage()
            SettingsPage.Account, SettingsPage.Journal -> Unit
        }
    }
}

/** A setting that takes effect at the next connection says so while connected. */
@Composable
private fun NextConnectionNote() {
    val state by LocalAppContainer.current.connection.state.collectAsState()
    if (state.isActive) Text("Применится при переподключении", style = AppTheme.type.bodySmall, color = AppTheme.colors.warning)
}

@Composable
private fun ConnectionPage() {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsState()
    val android = container.platform.kind == PlatformKind.Android
    var port by remember(settings.socksPort) { mutableStateOf(settings.socksPort.toString()) }
    val parsed = port.toIntOrNull()
    val valid = parsed != null && parsed in 1024..65534
    Field(
        port,
        { v ->
            port = v.filter(Char::isDigit).take(5)
            port.toIntOrNull()?.takeIf { it in 1024..65534 }?.let { p -> container.settings.update { it.copy(socksPort = p) } }
        },
        "Порт SOCKS5", keyboardType = KeyboardType.Number, error = if (!valid) "Порт от 1024 до 65534" else null,
    )
    if (valid) {
        Text(
            if (android) "Прокси 127.0.0.1:$parsed работает, когда VPN выключен"
            else "SOCKS5 127.0.0.1:$parsed, HTTP-прокси 127.0.0.1:${parsed!! + 1}",
            style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
        )
    }
    NextConnectionNote()
    Section(null) {
        Toggle("Подключаться при запуске", settings.autoConnect, { v -> container.settings.update { it.copy(autoConnect = v) } }, subtitle = "Выбранное подключение, когда OpenFlux открывается")
    }
}

@Composable
private fun VpnPage() {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsState()
    val platform = container.platform
    val android = platform.kind == PlatformKind.Android
    fun setFull(on: Boolean) {
        container.settings.update { it.copy(fullTunnel = on) }
        val s = container.connection.state.value
        if (s.isActive) s.profile?.let { container.connection.connect(it) }
    }
    if (android) {
        Section(null) {
            Toggle("VPN: весь трафик телефона", settings.fullTunnel, ::setFull, subtitle = "Android спросит разрешение при первом подключении")
        }
        Text(
            if (settings.fullTunnel) "Чтобы при обрыве ничего не шло мимо, включите в настройках Android «Постоянный VPN» и «Блокировать подключения без VPN»."
            else "Без VPN работает только прокси SOCKS5 127.0.0.1:${settings.socksPort}: через ноду идут лишь приложения, где он указан.",
            style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
        )
        return
    }
    if (platform.fullTunnelSupported) {
        val rights = when {
            platform.fullTunnelPrompt != null -> platform.fullTunnelPrompt!!
            platform.elevated -> "права администратора есть"
            else -> "нужны права администратора"
        }
        Section(null) {
            Toggle("Весь трафик компьютера (TUN)", settings.fullTunnel, ::setFull, subtitle = "Программы, игры и UDP идут через ноду; $rights")
        }
    }
    if (!platform.systemProxySupported) {
        Text(
            "Системный прокси есть только в Windows. Укажите SOCKS5 127.0.0.1:${settings.socksPort} в нужных программах" + if (platform.fullTunnelSupported) " или включите TUN." else ".",
            style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
        )
        return
    }
    Section(null) {
        Toggle("Системный прокси", settings.systemProxy, { v -> container.settings.update { it.copy(systemProxy = v) } }, subtitle = "Пока OpenFlux подключён, Windows направляет программы на его HTTP-прокси", enabled = !settings.fullTunnel)
        ValueRow("Адрес", "127.0.0.1:${settings.socksPort + 1}", mono = true)
        ValueRow("Исключения", "localhost, 127.*, 10.*, 192.168.*")
    }
    Text("Прежние настройки прокси Windows возвращаются при отключении и выходе.", style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
}

/** Yandex and Mail.ru sign-ins (spec 8.3, "Сервисы"). */
@Composable
private fun ServicesPage() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val status by container.accounts.status.collectAsState()
    val profiles by container.profiles.profiles.collectAsState()
    val settings by container.settings.settings.collectAsState()
    var created by remember { mutableStateOf<Pair<AccountKind, String>?>(null) }
    LaunchedEffect(Unit) { container.accounts.checkAllInBackground() }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountException) {
                if (!e.cancelled) toaster.show(e.message ?: "Не получилось")
            } catch (e: Exception) {
                toaster.show(e.message ?: "Не получилось")
            }
        }
    }
    Text("Войдите один раз: Яндекс реже просит проверку, а ваши ноды получают сессию сами. Пароль OpenFlux не видит.", style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
    for (kind in AccountKind.entries.filter { it.signsIn }) {
        val st = status[kind] ?: AuthStatus.SignedOut
        Section(kind.label) {
            RowItem(serviceStatus(st, container.platform.now()).replaceFirstChar { it.uppercase() }, subtitle = serviceUsage(kind, profiles))
            Column(Modifier.fillMaxWidth().padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.s)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                    when (st) {
                        AuthStatus.SignedOut -> TextAction("Войти в ${kind.label}", { act { container.accounts.signIn(kind) } })
                        is AuthStatus.Checking -> Unit
                        is AuthStatus.Busy -> TextAction("Отмена", container.accounts::cancel)
                        is AuthStatus.SignedIn -> {
                            if (kind.createsDocuments && settings.developerMode) TextAction("Создать документ", {
                                act { created = kind to container.accounts.createDocument(kind, io.openflux.desktop.model.NodeDocuments.fileName("", "", container.platform.now())) }
                            })
                            TextAction("Проверить", { act { container.accounts.check(kind) } })
                            TextAction("Выйти", { container.accounts.signOut(kind) }, color = AppTheme.colors.textSecondary)
                        }
                        is AuthStatus.Expired -> {
                            TextAction("Войти заново", { act { container.accounts.signIn(kind) } })
                            TextAction("Выйти", { container.accounts.signOut(kind) }, color = AppTheme.colors.textSecondary)
                        }
                        is AuthStatus.NeedsCheck -> {
                            TextAction("Войти заново", { act { container.accounts.signIn(kind) } })
                            TextAction("Проверить", { act { container.accounts.check(kind) } })
                        }
                        is AuthStatus.Failed -> {
                            TextAction("Проверить снова", { act { container.accounts.check(kind) } })
                            TextAction("Войти заново", { act { container.accounts.signIn(kind) } })
                            if (st.login.isNotEmpty()) TextAction("Выйти", { container.accounts.signOut(kind) }, color = AppTheme.colors.textSecondary)
                        }
                    }
                }
            }
        }
        val doc = created
        if (doc != null && doc.first == kind) {
            Banner("Документ готов", Tone.Success, action = "Профиль", onAction = {
                val p = io.openflux.desktop.model.Profile(
                    id = container.profiles.newId(), name = "${kind.label} ${doc.second.takeLast(6)}",
                    transport = if (kind == AccountKind.Mailru) io.openflux.desktop.model.TransportType.MAILRU else io.openflux.desktop.model.TransportType.VYANDEX,
                    value = doc.second, secret = container.platform.newSecret(), session = true, createdAt = container.platform.now(),
                )
                container.profiles.upsert(p)
                created = null
                navigator.open(Dest.Profile(p.id))
            }, secondAction = "Скопировать", onSecondAction = { container.platform.setClipboardText(doc.second); toaster.show("Ссылка скопирована") })
        }
    }
    if (status.values.any { it !is AuthStatus.SignedOut }) SecondaryButton("Проверить все", { container.accounts.checkAllInBackground(force = true) })
    Text(
        "Сервис может заблокировать аккаунт, которым пользуются так. Авторы OpenFlux за это не отвечают. Cups.online и прямое подключение входа не требуют.",
        style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted,
    )
}

@Composable
private fun CorePage() {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsState()
    val platform = container.platform
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloads by remember { mutableStateOf(0) }
    if (platform.kind == PlatformKind.Android) {
        Section(null) { ValueRow("Встроенное ядро", platform.coreVersion) }
    } else {
        io.openflux.app.components.Group("Файл ядра") {
            Segmented(CoreSource.choices, settings.coreSource, { s -> container.settings.update { it.copy(coreSource = s) } }, label = { it.label })
        }
        val repo = settings.coreSource.repo
        when {
            settings.coreSource == CoreSource.Bundled -> Section(null) { ValueRow("Встроенное ядро", platform.coreVersion) }
            repo != null -> {
                val source = settings.coreSource
                val tag = remember(source, downloads) { platform.downloadedCore(source) }
                Section(null) { ValueRow("Скачанное ядро", tag?.let { "$it · $repo" } ?: "не скачано") }
                if (downloading) ProgressLine("Скачиваю ядро…")
                else SecondaryButton(if (tag == null) "Скачать последний выпуск" else "Проверить и обновить", {
                    downloading = true; error = null; result = null
                    scope.launch {
                        try {
                            val got = platform.downloadCore(source)
                            result = if (got == tag) "Уже последняя версия: $got" else "Скачано ядро $got"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Не удалось скачать ядро"
                        } finally {
                            downloading = false
                            downloads++
                        }
                    }
                }, enabled = platform.coreDownloadSupported)
                result?.let { Banner(it, Tone.Success) }
                error?.let { Banner(it, Tone.Danger) }
                Text("Выпуск из github.com/$repo, сверенный с SHA256SUMS.txt. Мастер своей ноды всегда работает со встроенным ядром.", style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
            }
            else -> {
                Field(settings.customCorePath, { v -> container.settings.update { it.copy(customCorePath = v) } }, "Путь к файлу ядра", mono = true)
                TextAction("Выбрать файл…", {
                    scope.launch { platform.pickFile("Файл ядра OpenFlux", emptyList())?.let { path -> container.settings.update { it.copy(customCorePath = path) } } }
                })
            }
        }
    }
    io.openflux.app.components.Group("Подробность журнала ядра") {
        Segmented(listOf(0, 1, 2, 3), settings.debugLevel, { v -> container.settings.update { it.copy(debugLevel = v) } }, label = { if (it == 0) "Выкл" else "-" + "d".repeat(it) })
        Text(
            when (settings.debugLevel) {
                0 -> "Только состояние."
                1 -> "Движение пакетов."
                2 -> "Сессии, транспорты, ключи шифрования: для диагностики."
                else -> "Вдобавок дампы пакетов: самый медленный."
            },
            style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
        )
    }
    NextConnectionNote()
}

@Composable
private fun LookPage() {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsState()
    io.openflux.app.components.Group("Тема") {
        Segmented(ThemeMode.entries, settings.theme, { t -> container.settings.update { it.copy(theme = t) } }, label = { it.label })
    }
    if (container.platform.kind == PlatformKind.Desktop) {
        Section(null) {
            Toggle("Сворачивать в трей при закрытии", settings.closeToTray, { v -> container.settings.update { it.copy(closeToTray = v) } })
        }
    }
}

@Composable
private fun ExperimentalPage() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val settings by container.settings.settings.collectAsState()
    Section(null) {
        Toggle(
            "Экспериментальные функции", settings.experimental, { v -> container.settings.update { it.copy(experimental = v) } },
            subtitle = if (settings.experimental) "Включены: профили с JS-транспортом подключаются" else "Выключены: профили с JS-транспортом не подключатся",
        )
        if (settings.experimental) RowItem("Транспорты на JS", subtitle = "Каталог: добавить, обновить, отключить", onClick = { navigator.open(Dest.Scripts) })
    }
    Text(
        "Могут работать нестабильно и меняться от версии к версии. JS-транспорт — чужой код без песочницы, с полным доступом к сети: ставьте только от авторов, которым доверяете.",
        style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
    )
}

@Composable
private fun AboutPage() {
    val container = LocalAppContainer.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val platform = container.platform
    val settings by container.settings.settings.collectAsState()
    val update by container.updates.state.collectAsState()
    val taps = remember(container) { VersionTaps(container.settings) }
    Section(null) {
        RowItem("Версия приложения", subtitle = platform.appVersion, onClick = { taps.tap()?.let(toaster::show) })
        ValueRow("Ядро OpenFlux", platform.coreVersion)
        ValueRow(
            "Последний выпуск",
            when (val u = update) {
                UpdateState.Checking -> "проверяю…"
                UpdateState.UpToDate -> "у вас последняя версия"
                is UpdateState.Available -> u.release.version
                is UpdateState.Downloading -> "скачиваю ${u.release.version}…"
                is UpdateState.Installing -> "${u.release.version}: установка"
                is UpdateState.Failed -> u.message
                UpdateState.Idle -> if (settings.updateCheckedAt == 0L) "не проверялось" else "проверено ${Fmt.ago(platform.now() - settings.updateCheckedAt)}"
            },
        )
    }
    val offered = update as? UpdateState.Available
    if (offered != null) PrimaryButton("Обновить до ${offered.release.version}", container.updates::install, Modifier.fillMaxWidth())
    SecondaryButton("Проверить обновления", {
        scope.launch {
            container.updates.check(manual = true)
            if (container.updates.state.value == UpdateState.UpToDate) toaster.show("У вас последняя версия")
        }
    }, Modifier.fillMaxWidth())
    Section(null) {
        Toggle("Проверять обновления автоматически", settings.autoUpdate, { v -> container.settings.update { it.copy(autoUpdate = v) } }, subtitle = "Раз в несколько часов. Ставятся только с вашего согласия")
        if (settings.developerMode) {
            Toggle("Режим разработчика", true, { on -> container.settings.update { it.copy(developerMode = on) } })
        }
    }
    if (settings.developerMode) {
        io.openflux.app.components.Group("Канал обновлений") {
            Segmented(UpdateChannel.entries, settings.updateChannel, { c -> container.settings.update { it.copy(updateChannel = c) } }, label = { it.label })
        }
    }
    Section("Исходный код") {
        RowItem("Исходный код ядра", subtitle = "github.com/${CoreRepos.CORE}", onClick = { platform.openUrl("https://github.com/${CoreRepos.CORE}") })
        RowItem("Это приложение", subtitle = "github.com/${platform.clientRepo}", onClick = { platform.openUrl("https://github.com/${platform.clientRepo}") })
    }
    Text(
        "OpenFlux — экспериментальный проект без независимого аудита безопасности. Не публикуйте ключи и ссылки openflux://.",
        style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted,
    )
}
