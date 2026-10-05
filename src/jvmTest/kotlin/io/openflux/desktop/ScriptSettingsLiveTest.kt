package io.openflux.desktop

import io.openflux.desktop.core.CoreBinary
import io.openflux.desktop.data.FileScriptRepository
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.CoreConfig
import io.openflux.desktop.model.CorePaths
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ScriptCarrierLookup
import io.openflux.desktop.model.ScriptSource
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.platform.JvmPlatformServices
import io.openflux.desktop.service.ScriptSettingsService
import io.openflux.desktop.web.KcefPage
import io.openflux.desktop.web.KcefSettingsPageHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.GraphicsEnvironment
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The script settings wizard end to end, with nothing stood in: a signed
 * script is installed through the real core (--inspect-script), "Настройки"
 * asks the real core for the page (--script-settings), the real Chromium shows
 * and fills it, Save lands in the repository, the page comes back prefilled,
 * the generated .conf carries the settings, and a real core process started
 * with that .conf hands them to the script as cfg.params.
 *
 * Needs a screen, the downloaded browser and a core binary
 * (desktopApp/resources/<os>/openflux-...): OPENFLUX_BROWSER_TEST=1.
 */
class ScriptSettingsLiveTest {
    private val script = """
        var Transport = {
          info: function () { return { name: "live-demo", version: "1.0.0", params: [
            { key: "url", label: "Document", type: "url", scope: "profile" },
            { key: "token", label: "Token", type: "secret", required: true },
            { key: "retries", label: "Retries", type: "number", default: 3, min: 1, max: 10 },
            { key: "mode", label: "Mode", type: "select", options: ["a", "b"], default: "a" },
            { key: "notes", label: "Notes", type: "textarea", advanced: true } ] }; },
          open: function (cfg) { console.log("CFGPARAMS " + JSON.stringify(cfg.params) + " URL " + cfg.url); setState("connecting"); },
          write: function () {}, close: function () {}
        };
    """.trimIndent()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun theWizardEndToEnd() {
        if (GraphicsEnvironment.isHeadless() || System.getenv("OPENFLUX_BROWSER_TEST") == null) return
        val dir = File(System.getProperty("java.io.tmpdir"), "ofx-settings-live-${System.nanoTime()}").apply { mkdirs() }
        val resources = listOf("desktopApp/resources/macos", "../desktopApp/resources/macos", "desktopApp/resources/linux", "../desktopApp/resources/linux")
            .map(::File).firstOrNull { it.isDirectory }
        resources?.let { System.setProperty("compose.application.resources.dir", it.absolutePath) }
        val binary = CoreBinary()
        val core = binary.bundled() ?: run { println("ScriptSettingsLiveTest: no core binary bundled, skipping"); return }
        val platform = JvmPlatformServices("test", binary) { "test" }

        // a signed script: Ed25519 from the JDK, the raw 32-byte key as the core wants it
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pub = hex(pair.public.encoded.takeLast(32).toByteArray())
        val src = script.toByteArray()
        val sig = Signature.getInstance("Ed25519").run { initSign(pair.private); update(src); sign() }

        val repo = object : FileScriptRepository(File(dir, "scripts"), platform::inspectTransport) {}
        val installed = repo.install(src, sig, pub, ScriptSource.File, "test", 0)
        assertEquals("live-demo", installed.id)
        assertEquals("url", installed.primaryParam?.key, "the first param is the profile's input")
        assertEquals(listOf("token", "retries", "mode", "notes"), installed.settingParams.map { it.key })
        assertEquals("3", installed.settingParams[1].default)
        assertTrue(installed.hasSettings)

        val service = ScriptSettingsService(repo, platform, KcefSettingsPageHost, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        // Stands in for the carrier (what a profile's field and its settings
        // keep): the wizard now asks for the profile param too, under its own
        // key, so this is what open() prefills with and what save() updates -
        // one value, whichever side touched it.
        var profileValue = "https://doc.example/d"
        var settings = emptyMap<String, String>()
        fun open() = service.open("live-demo", settings + ("url" to profileValue)) { saved ->
            profileValue = saved["url"] ?: profileValue
            settings = saved - "url"
        }
        var frame: JFrame? = null
        try {
            runBlocking {
                suspend fun page(): KcefPage {
                    val p = withTimeoutOrNull(120_000) { while (service.state.value?.page == null) delay(100); service.state.value!!.page }
                    assertNotNull(p, "the wizard page never opened: ${service.state.value}")
                    return p as KcefPage
                }
                suspend fun KcefPage.settled() {
                    withTimeoutOrNull(20_000) { while (loading || url.isEmpty()) delay(100) }
                    delay(600)
                }
                fun show(p: KcefPage) = SwingUtilities.invokeAndWait {
                    frame?.dispose()
                    frame = JFrame("settings").apply { contentPane.add(p.component); setSize(800, 700); isVisible = true }
                }

                // ---- first open: the profile param is here too (f0), prefilled from the
                // carrier's current value; the declared defaults fill the rest ----
                open()
                var p = page(); show(p); p.settled()
                assertEquals("https://doc.example/d", p.evaluate("document.getElementById('f0').value"), "the profile's own field prefills the wizard too")
                assertEquals("3", p.evaluate("document.getElementById('f2').value"), "a declared default prefills the form")
                assertEquals("", p.evaluate("document.getElementById('f1').value"))
                assertEquals("Live-demo".lowercase(), p.evaluate("document.querySelector('h1').textContent").lowercase())

                // Save with the required token empty: the page refuses, nothing is saved
                p.evaluate("(function(){document.getElementById('save').click(); return 1})()")
                delay(500)
                assertTrue(settings.isEmpty(), "a required field left empty must not save")
                assertEquals("true", p.evaluate("String(document.getElementById('f1').closest('.field').classList.contains('bad'))"))

                // fill it in the way a user does (including a new document URL), then Save
                p.evaluate(
                    """(function(){
                        function set(id, v){var e=document.getElementById(id); e.value=v; e.dispatchEvent(new Event('input',{bubbles:true}));}
                        set('f0','https://doc.example/e'); set('f1','s3cr#t;1'); set('f2','7'); set('f3','b'); set('f4','line one\nline two');
                        document.getElementById('save').click(); return 1})()""",
                )
                withTimeoutOrNull(5_000) { while (settings.isEmpty()) delay(50) }
                assertEquals(
                    mapOf("token" to "s3cr#t;1", "retries" to "7", "mode" to "b", "notes" to "line one\nline two"), settings,
                    "what the page submitted is what the caller keeps",
                )
                assertEquals("https://doc.example/e", profileValue, "the profile field changed from the wizard too - one value, either editor")
                assertNull(service.state.value, "the dialog closes once the page has saved")

                // ---- second open: prefilled from what was saved, profile field included ----
                open()
                p = page(); show(p); p.settled()
                assertEquals("https://doc.example/e", p.evaluate("document.getElementById('f0').value"))
                assertEquals("s3cr#t;1", p.evaluate("document.getElementById('f1').value"))
                assertEquals("7", p.evaluate("document.getElementById('f2').value"))
                assertEquals("b", p.evaluate("document.getElementById('f3').value"))
                service.close()

                // ---- an out-of-range number is refused by the page ----
                open()
                p = page(); show(p); p.settled()
                p.evaluate("(function(){var e=document.getElementById('f2'); e.value='11'; e.dispatchEvent(new Event('input',{bubbles:true})); document.getElementById('save').click(); return 1})()")
                delay(500)
                assertEquals("7", settings["retries"], "11 is over the declared max")
                service.close()
            }

            // ---- the saved settings through the generated .conf into a real core process ----
            val key = File(dir, "key").apply { writeText("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n") }
            val profile = Profile(
                id = "p", name = "P", transport = TransportType.SCRIPT, value = profileValue, session = true,
                secret = "0123456789abcdef0123456789abcdef", scriptId = "live-demo", settings = settings,
            )
            val paths = CorePaths(keyFile = key.absolutePath, confFile = File(dir, "p.conf").absolutePath, cookieStore = File(dir, "cookies.json").absolutePath, ipcSocket = null)
            val launch = CoreConfig.build(profile, AppSettings(socksPort = 19181), paths) { id ->
                repo.byId(id)?.let { ScriptCarrierLookup(File(repo.dir, it.fileName).absolutePath, it.pubkeyHex, it.id, it.primaryParam?.key) }
            }
            File(paths.confFile).writeText(launch.conf!!)
            val process = ProcessBuilder(listOf(core.absolutePath) + launch.arguments).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { isDaemon = true; start() }
            val seen = runBlocking { withTimeoutOrNull(15_000) { while ("CFGPARAMS" !in output) delay(100); true } }
            process.destroy(); process.waitFor(5, TimeUnit.SECONDS); reader.join(1000)
            assertNotNull(seen, "the real core never started the script:\n$output")
            val line = output.lines().first { "CFGPARAMS" in it }
            assertTrue(""""token":"s3cr#t;1"""" in line, line)
            assertTrue(""""retries":"7"""" in line && """"mode":"b"""" in line, line)
            assertTrue("""\n""" in line && "line one" in line, "a multi-line setting survives the .conf: $line")
            assertTrue("URL https://doc.example/e" in line, "the profile's own input is still cfg.url: $line")
            assertTrue(""""url":"https://doc.example/e"""" in line, "and the same value also reaches cfg.params[url]: $line")
        } finally {
            SwingUtilities.invokeAndWait { frame?.dispose() }
            dir.deleteRecursively()
        }
    }
}
