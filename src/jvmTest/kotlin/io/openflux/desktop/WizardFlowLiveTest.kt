package io.openflux.desktop

import io.openflux.desktop.core.CoreBinary
import io.openflux.desktop.core.CoreConnectionService
import io.openflux.desktop.data.FileAccountRepository
import io.openflux.desktop.data.HttpSessionProbe
import io.openflux.desktop.core.CliCoreLinks
import io.openflux.desktop.model.CoreShareLinkCodec
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.CoreSource
import io.openflux.desktop.model.Profile
import io.openflux.desktop.node.CoreNodeWizard
import io.openflux.desktop.platform.JvmPlatformServices
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.Accounts
import io.openflux.desktop.service.ProfileRepository
import io.openflux.desktop.service.SettingsRepository
import io.openflux.desktop.ui.node.NodeWizardModel
import io.openflux.desktop.ui.node.WizardStep
import io.openflux.desktop.web.KcefAccountBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * The whole desktop "Своя нода" wizard without clicks, direct only:
 * OPENFLUX_LIVE_WIZARD="core|host|user|keyfile" runs it (and removes the channel).
 */
class WizardFlowLiveTest {
    @Test
    fun wholeWizard() = runBlocking {
        val spec = System.getenv("OPENFLUX_LIVE_WIZARD") ?: return@runBlocking
        val (core, host, user, keyFile) = spec.split("|")
        val dir = Files.createTempDirectory("wizard").toFile()
        val settings = object : SettingsRepository {
            override val settings = MutableStateFlow(AppSettings(coreSource = CoreSource.Custom, customCorePath = core, mode = ConnectionMode.Client, socksPort = 18094, systemProxy = false, debugLevel = 1))
            override fun update(transform: (AppSettings) -> AppSettings) { settings.value = transform(settings.value) }
        }
        val profiles = object : ProfileRepository {
            override val profiles = MutableStateFlow<List<Profile>>(emptyList())
            var n = 0
            override fun upsert(profile: Profile) { profiles.value = profiles.value.filterNot { it.id == profile.id } + profile }
            override fun delete(id: String) { profiles.value = profiles.value.filterNot { it.id == id } }
            override fun newId() = "w${n++}"
        }
        val binary = CoreBinary()
        val accounts = Accounts(FileAccountRepository(dir), KcefAccountBrowser(), HttpSessionProbe(), System::currentTimeMillis, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val connection = CoreConnectionService(settings, binary, accounts)
        val service = CoreNodeWizard(settings, binary, accounts)
        val container = AppContainer(profiles, settings, connection, JvmPlatformServices("test") { binary.version() }, CoreShareLinkCodec(CliCoreLinks(settings, binary)), service, accounts)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wizard = NodeWizardModel(container, scope)
        suspend fun idle(what: String, limitMs: Long = 240_000) {
            val start = System.currentTimeMillis()
            delay(300)
            var last: String? = null
            while ((wizard.busy != null || wizard.hostKeyPrompt == null && false) && System.currentTimeMillis() - start < limitMs) {
                if (wizard.busy != last) { println("  … ${wizard.busy}"); last = wizard.busy }
                delay(300)
            }
            println("$what: step=${wizard.step} error=${wizard.error} verifyFailed=${wizard.verifyFailed} ip=${wizard.verifiedIp}")
        }
        withContext(Dispatchers.Default) {
            wizard.host = host
            wizard.user = user
            wizard.useKey = true
            wizard.privateKey = File(keyFile).readText()
            wizard.connect()
            idle("connect")
            wizard.hostKeyPrompt?.let { println("trusting ${it.fingerprint}"); wizard.trustHostKey(); idle("connect again") }
            wizard.useVolga = false
            wizard.next()
            idle("plan")
            println(wizard.plan?.actions?.joinToString("\n  ", "  "))
            val t0 = System.currentTimeMillis()
            wizard.install()
            idle("install+verify")
            println("took ${(System.currentTimeMillis() - t0) / 1000}s")
            println("--- wizard log ---")
            service.logs.value.takeLast(30).forEach { println("${it.level} ${it.text.take(220)}") }
            println("--- connection log ---")
            connection.logs.value.filterNot { "[TUNNEL]" in it.text }.takeLast(40).forEach { println("${it.level} ${it.text.take(220)}") }
            if (wizard.installed) { wizard.removeChannel(); idle("remove") }
            wizard.close()
            connection.disconnect()
            delay(1500)
        }
    }
}
