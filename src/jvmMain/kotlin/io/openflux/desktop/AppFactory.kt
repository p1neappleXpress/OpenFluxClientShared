package io.openflux.desktop

import io.openflux.desktop.core.CliCoreLinks
import io.openflux.desktop.core.CoreBinary
import io.openflux.desktop.data.AppDirs
import io.openflux.desktop.core.CoreConnectionService
import io.openflux.desktop.core.DesktopScriptRepository
import io.openflux.desktop.data.FileProfileRepository
import io.openflux.desktop.data.FileSettingsRepository
import io.openflux.desktop.model.CoreShareLinkCodec
import io.openflux.desktop.node.CoreNodeWizard
import io.openflux.desktop.node.CorePhpTransport
import io.openflux.desktop.platform.JvmPlatformServices
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.data.FileAccountRepository
import io.openflux.desktop.data.FileSubscriptionRepository
import io.openflux.desktop.data.HttpSubscriptionApi
import io.openflux.desktop.data.HttpSessionProbe
import io.openflux.desktop.service.Accounts
import io.openflux.desktop.web.KcefAccountBrowser
import io.openflux.desktop.service.NodeKeepingConnection
import io.openflux.desktop.service.PhpHostingService
import io.openflux.desktop.web.KcefSettingsPageHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Wires the desktop implementations together; called once from main. */
fun createAppContainer(appVersion: String): AppContainer {
    val settings = FileSettingsRepository(AppDirs.config)
    val binary = CoreBinary()
    val accounts = Accounts(
        repo = FileAccountRepository(AppDirs.config),
        browser = KcefAccountBrowser(),
        probe = HttpSessionProbe(),
        now = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val phpHosting = PhpHostingService(CorePhpTransport(settings, binary), clock = System::currentTimeMillis)
    val platform = JvmPlatformServices(appVersion, binary) { binary.version() }
    val scripts = DesktopScriptRepository(platform)
    val container = AppContainer(
        profiles = FileProfileRepository(AppDirs.config),
        settings = settings,
        // Connecting a profile made by the "без сервера" wizard first asks its node on the hosting to run.
        connection = NodeKeepingConnection(
            CoreConnectionService(settings, binary, accounts, scripts), phpHosting, CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ),
        platform = platform,
        shareCodec = CoreShareLinkCodec(CliCoreLinks(settings, binary)),
        nodeWizard = CoreNodeWizard(settings, binary, accounts),
        accounts = accounts,
        phpHosting = phpHosting,
        scripts = scripts,
        subscriptionApi = HttpSubscriptionApi(),
        subscriptionRepo = FileSubscriptionRepository(AppDirs.config),
        botApi = io.openflux.desktop.data.HttpBotApi(io.openflux.desktop.data.AppIds.apiBase(), io.openflux.desktop.data.AppIds.desktop()),
        botRepo = io.openflux.desktop.data.FileBotRepository(AppDirs.config),
        signIn = io.openflux.desktop.data.LoopbackSignIn(
            io.openflux.desktop.data.AppIds.desktopClientId(),
            issuer = io.openflux.desktop.data.AppIds.idIssuer(),
            open = platform::openUrl,
        ),
        settingsPageHost = KcefSettingsPageHost,
    )
    // The subscription's list of locations is refreshed in the background.
    container.subscription.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    container.bot.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    return container
}
