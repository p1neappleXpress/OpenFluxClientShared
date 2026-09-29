package io.openflux.desktop

import io.openflux.desktop.core.CliCoreLinks
import io.openflux.desktop.core.CoreBinary
import io.openflux.desktop.data.AppDirs
import io.openflux.desktop.core.CoreConnectionService
import io.openflux.desktop.data.FileProfileRepository
import io.openflux.desktop.data.FileSettingsRepository
import io.openflux.desktop.model.CoreShareLinkCodec
import io.openflux.desktop.node.CoreNodeWizard
import io.openflux.desktop.platform.JvmPlatformServices
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.data.FileAccountRepository
import io.openflux.desktop.data.HttpSessionProbe
import io.openflux.desktop.service.Accounts
import io.openflux.desktop.web.KcefAccountBrowser
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
    return AppContainer(
        profiles = FileProfileRepository(AppDirs.config),
        settings = settings,
        connection = CoreConnectionService(settings, binary, accounts),
        platform = JvmPlatformServices(appVersion) { binary.version() },
        shareCodec = CoreShareLinkCodec(CliCoreLinks(settings, binary)),
        nodeWizard = CoreNodeWizard(settings, binary, accounts),
        accounts = accounts,
    )
}
