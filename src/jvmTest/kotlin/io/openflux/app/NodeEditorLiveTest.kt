package io.openflux.app

import io.openflux.app.servers.LoginForm
import io.openflux.app.servers.NodeEditorModel
import io.openflux.app.servers.ServerLink
import io.openflux.desktop.core.CliCoreLinks
import io.openflux.desktop.core.CoreBinary
import io.openflux.desktop.core.CoreConnectionService
import io.openflux.desktop.core.DesktopScriptRepository
import io.openflux.desktop.data.FileAccountRepository
import io.openflux.desktop.data.HttpSessionProbe
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.CoreShareLinkCodec
import io.openflux.desktop.model.CoreSource
import io.openflux.desktop.model.Profile
import io.openflux.desktop.node.CoreNodeWizard
import io.openflux.desktop.platform.JvmPlatformServices
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.Accounts
import io.openflux.desktop.service.ProfileRepository
import io.openflux.desktop.service.SettingsRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A quick node on a real server through the new editor, without clicks, then removed:
 * OPENFLUX_LIVE_WIZARD="core|host|user|keyfile" runs it; without it the test does nothing.
 */
class NodeEditorLiveTest {
    @Test
    fun quickNodeOnARealServer() = runBlocking {
        val spec = System.getenv("OPENFLUX_LIVE_WIZARD") ?: return@runBlocking
        val (core, host, user, keyFile) = spec.split("|")
        val dir = Files.createTempDirectory("editor").toFile()
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
        val platform = JvmPlatformServices("test", binary) { binary.version() }
        val scripts = DesktopScriptRepository(platform)
        val connection = CoreConnectionService(settings, binary, accounts, scripts)
        val service = CoreNodeWizard(settings, binary, accounts)
        val container = AppContainer(profiles, settings, connection, platform, CoreShareLinkCodec(CliCoreLinks(settings, binary)), service, accounts, scripts = scripts)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val link = ServerLink(container)
        withContext(Dispatchers.Default) {
            val form = LoginForm(link).apply {
                this.host = host
                this.user = user
                useKey = true
                privateKey = File(keyFile).readText()
            }
            var id = form.connect()
            if (id == null && link.hostKeyPrompt != null) {
                println("trusting ${link.hostKeyPrompt?.fingerprint}")
                id = form.trust()
            }
            assertNotNull(id, form.error ?: "no access")
            val editor = NodeEditorModel(container, link, id, null, scope)
            suspend fun idle(what: String, limitMs: Long = 240_000) {
                val start = System.currentTimeMillis()
                delay(300)
                var last: String? = null
                while (editor.busy != null && System.currentTimeMillis() - start < limitMs) {
                    if (editor.busy != last) { println("  … ${editor.busy}"); last = editor.busy }
                    delay(300)
                }
                println("$what: error=${editor.error} verifyFailed=${editor.verifyFailed} verified=${editor.verified} done=${editor.done}")
            }
            val t0 = System.currentTimeMillis()
            editor.install()
            idle("install+verify")
            println("took ${(System.currentTimeMillis() - t0) / 1000}s")
            println("--- wizard log ---")
            service.logs.value.takeLast(30).forEach { println("${it.level} ${it.text.take(220)}") }
            println("--- connection log ---")
            connection.logs.value.filterNot { "[TUNNEL]" in it.text }.takeLast(40).forEach { println("${it.level} ${it.text.take(220)}") }
            val failure = editor.error ?: editor.verifyFailed
            connection.disconnect()
            editor.done?.let { done ->
                NodeEditorModel(container, link, id, done.channel, scope).apply {
                    load(); delay(300); while (busy != null) delay(300)
                    remove {}; delay(300); while (busy != null) delay(300)
                    println("removed: error=$error")
                }
            } ?: editor.removeFailed()
            delay(1500)
            link.close()
            assertNull(failure, failure)
        }
    }
}
