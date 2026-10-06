package io.openflux.desktop

import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.CoreConfig
import io.openflux.desktop.model.CorePaths
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ScriptCarrierLookup
import io.openflux.desktop.model.ScriptParam
import io.openflux.desktop.model.ScriptSettingsCodec
import io.openflux.desktop.model.ScriptSettingsMessages
import io.openflux.desktop.model.ScriptSettingsPage
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.parseScriptParams
import io.openflux.desktop.service.PlatformServices
import io.openflux.desktop.service.ScriptSettingsService
import io.openflux.desktop.service.SettingsPageHost
import io.openflux.desktop.ui.BrowserPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.io.encoding.Base64
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptSettingsTest {
    private val report = """{"ok":true,"signature":"valid","name":"Demo","version":"1.0","html":"<p>wizard</p>",
        "params":[{"key":"token","label":"T","type":"secret","scope":"settings","required":true},
                  {"key":"retries","label":"R","type":"number","scope":"settings","default":"3","min":1,"max":10,
                   "options":[{"value":"a","label":"A"},{"value":"b"}],"group":"G","advanced":true}],
        "values":{"token":"","retries":"3"}}"""

    @Test
    fun paramsKeepEverythingTheCoreDeclared() {
        val params = parseScriptParams(Json.parseToJsonElement(report).let { (it as kotlinx.serialization.json.JsonObject)["params"] })
        val retries = params[1]
        assertEquals("settings", retries.scope)
        assertEquals("3", retries.default)
        assertEquals(1.0, retries.min)
        assertEquals(10.0, retries.max)
        assertEquals(listOf("A", "b"), retries.options.map { it.shown })
        assertEquals("G", retries.group)
        assertTrue(retries.advanced)
        assertTrue(params[0].required && params[0].type == "secret")
    }

    private fun script(vararg params: ScriptParam, page: Boolean = false) = InstalledScript(
        id = "demo", name = "Demo", pubkeyHex = "aa", fingerprint = "bb", fileName = "demo.js",
        params = params.toList(), settingsPage = page,
    )

    @Test
    fun theProfilesInputAndTheSettingsAreTelledApart() {
        val s = script(ScriptParam("url", scope = "profile"), ScriptParam("token", scope = "settings"), ScriptParam("n", scope = "settings"))
        assertEquals("url", s.primaryParam?.key)
        assertEquals(listOf("token", "n"), s.settingParams.map { it.key })
        assertTrue(s.hasSettings)

        // only settings declared: the profile has no value field
        val onlySettings = script(ScriptParam("a", scope = "settings"))
        assertNull(onlySettings.primaryParam)

        // a record from before the core resolved scopes keeps the old rule: the first is the profile's
        val legacy = script(ScriptParam("url"), ScriptParam("extra"))
        assertEquals("url", legacy.primaryParam?.key)
        assertEquals(listOf("extra"), legacy.settingParams.map { it.key })

        // even a lone profile param gets a wizard now (just itself) - the field and
        // the wizard edit the one value; only a script with nothing at all does not
        assertFalse(script().hasSettings)
        assertTrue(script(ScriptParam("url", scope = "profile")).hasSettings)
        assertTrue(script(page = true).hasSettings)
    }

    @Test
    fun theCoreAnswerIsReadAndExplained() {
        val ok = ScriptSettingsPage.parse(report)
        assertTrue(ok.ok)
        assertEquals("<p>wizard</p>", ok.html)
        assertEquals(2, ok.params.size)
        assertEquals("3", ok.values["retries"])

        for ((code, words) in mapOf(
            "bad_signature" to "подписью", "no_key" to "ключа", "no_settings" to "нет настроек",
            "needs_newer_app" to "более новая", "failed" to "boom",
        )) {
            val page = ScriptSettingsPage.parse("""{"ok":false,"code":"$code","error":"boom"}""")
            assertFalse(page.ok)
            assertTrue(words in ScriptSettingsMessages.text(page), "$code: ${ScriptSettingsMessages.text(page)}")
        }
        assertFalse(ScriptSettingsPage.parse("not json").ok)
    }

    @Test
    fun savedSettingsReachTheConfAsOneSafeLine() {
        val settings = mapOf("token" to "a#b;c", "notes" to "line one\nline two", "ru" to "привет «мир»")
        val line = ScriptSettingsCodec.encode(settings)
        assertTrue(line.none { it == '#' || it == ';' || it == '\n' || it == '\r' || it == '=' }, line)
        val decoded = Json.parseToJsonElement(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(line).decodeToString())
        assertEquals(settings, (decoded as kotlinx.serialization.json.JsonObject).mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content })
        // the same bytes the Go side reads (script.DecodeSettings): {"a":"x"} -> eyJhIjoieCJ9
        assertEquals("eyJhIjoieCJ9", ScriptSettingsCodec.encode(mapOf("a" to "x")))

        val profile = Profile(
            id = "p", name = "P", transport = TransportType.SCRIPT, value = "https://doc.example/d", session = true,
            secret = "0123456789abcdef0123456789abcdef", scriptId = "demo", settings = settings,
        )
        val paths = CorePaths(keyFile = "/k", confFile = "/c", cookieStore = "/s", ipcSocket = null)
        fun conf(p: Profile, lookup: ScriptCarrierLookup) = CoreConfig.build(p, AppSettings(experimental = true), paths) { lookup }.conf.orEmpty()
        val with = conf(profile, ScriptCarrierLookup("/p.flux", "aa", "demo"))
        assertTrue("Params = $line" in with, with)
        assertTrue("Path = /p.flux" in with && "Name = demo" in with)
        val bare = profile.copy(settings = emptyMap())
        assertFalse("Params =" in conf(bare, ScriptCarrierLookup("/p.flux", "aa", "demo")), "no settings, no line")

        // the profile param's value rides along under its own key too, even
        // with no other settings saved - the field and the wizard agree
        val withPrimary = conf(bare, ScriptCarrierLookup("/p.flux", "aa", "demo", primaryParamKey = "url"))
        assertTrue("Params = ${ScriptSettingsCodec.encode(mapOf("url" to profile.value))}" in withPrimary, withPrimary)
    }

    // ---- the service, with the page host and the core stood in for ----

    private class FakeHost : SettingsPageHost {
        class Page(val html: String) : BrowserPage
        val opened = mutableListOf<Page>()
        val closed = mutableListOf<BrowserPage>()
        var submit: ((String) -> Unit)? = null
        override suspend fun open(html: String, onStep: (String) -> Unit, onSubmit: (String) -> Unit): BrowserPage {
            onStep("Готовлю браузер")
            submit = onSubmit
            return Page(html).also { opened += it }
        }
        override fun close(page: BrowserPage) { closed += page }
    }

    private fun platform(answer: (valuesJson: String) -> String, seen: MutableList<String> = mutableListOf()): PlatformServices =
        Proxy.newProxyInstance(PlatformServices::class.java.classLoader, arrayOf(PlatformServices::class.java)) { _, method, args ->
            if (method.name == "scriptSettings") { seen += args[3] as String; answer(args[3] as String) } else error("unexpected ${method.name}")
        } as PlatformServices

    private class Repo(installed: InstalledScript) : io.openflux.desktop.service.ScriptRepository {
        private val flow = kotlinx.coroutines.flow.MutableStateFlow(listOf(installed))
        override val scripts: kotlinx.coroutines.flow.StateFlow<List<InstalledScript>> = flow
        override fun upsert(script: InstalledScript) { flow.value = flow.value.filterNot { it.id == script.id } + script }
        override fun delete(id: String) { flow.value = flow.value.filterNot { it.id == id } }
        override fun setEnabled(id: String, enabled: Boolean) {}
        override fun packageBytes(id: String) = ByteArray(3) to ByteArray(0)
    }

    private fun await(cond: () -> Boolean) = runBlocking {
        repeat(200) { if (cond()) return@runBlocking; delay(10) }
        error("timed out")
    }

    @Test
    fun openingShowsTheCoresPageAndSavingKeepsOnlyWhatIsDeclared() {
        val repo = Repo(script(ScriptParam("url", scope = "profile"), ScriptParam("token", scope = "settings")))
        val host = FakeHost()
        val seen = mutableListOf<String>()
        val service = ScriptSettingsService(repo, platform({ report }, seen), host, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val saved = mutableListOf<Map<String, String>>()

        // the carrier's own current value (the profile editor's field) prefills the page too
        service.open("demo", mapOf("token" to "old")) { saved += it }
        assertEquals("Demo", service.state.value?.title)
        await { service.state.value?.page != null }
        assertEquals("<p>wizard</p>", (service.state.value?.page as FakeHost.Page).html)
        assertEquals("""{"token":"old"}""", seen.single(), "the current settings go to the core to prefill the page")

        // the page's Save: a declared key, an undeclared one, a number
        host.submit!!("""{"token":"s3cret","retries":7,"stray":"x"}""")
        assertEquals(listOf(mapOf("token" to "s3cret", "retries" to "7")), saved, "the caller - not this service - keeps what was saved")
        assertNull(service.state.value, "the dialog closes once the page has saved")
        assertEquals(1, host.closed.size)
    }

    @Test
    fun whenThereIsNoPageTheUserIsToldWhy() {
        val repo = Repo(script(ScriptParam("url", scope = "profile")))
        val host = FakeHost()
        val service = ScriptSettingsService(
            repo, platform({ """{"ok":false,"code":"bad_signature","error":"x"}""" }), host, CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        service.open("demo", emptyMap()) { error("must not be called") }
        await { service.state.value?.error?.isNotEmpty() == true }
        assertTrue("подписью" in service.state.value!!.error)
        assertTrue(host.opened.isEmpty(), "no page without a verified script")
        service.close()
        assertNull(service.state.value)
    }

    @Test
    fun aPageThatHandsOverNothingIsRefusedAndNothingIsSaved() {
        val repo = Repo(script(ScriptParam("url", scope = "profile"), ScriptParam("token", scope = "settings")))
        val host = FakeHost()
        val service = ScriptSettingsService(repo, platform({ report }), host, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val saved = mutableListOf<Map<String, String>>()
        service.open("demo", emptyMap()) { saved += it }
        await { service.state.value?.page != null }
        host.submit!!("""{"o":{}}""")
        assertTrue(saved.isEmpty())
        assertNotNull(service.state.value, "the dialog stays so the user can try again")
        assertTrue(service.state.value!!.error.isNotEmpty())
    }

    @Test
    fun aSecondOpenAbandonsTheFirst() {
        val repo = Repo(script(ScriptParam("token", scope = "settings")))
        val host = FakeHost()
        val service = ScriptSettingsService(repo, platform({ report }), host, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        service.open("demo", emptyMap()) {}
        service.open("demo", emptyMap()) {}
        await { host.opened.isNotEmpty() && service.state.value?.page != null }
        Thread.sleep(150)
        // whichever pages were opened, only the last is left open
        assertEquals(host.opened.size - 1, host.closed.size)
        assertTrue(host.opened.last() === service.state.value?.page)
    }

    @Test
    fun aScriptCarrierIsNamedByItsScriptNotJustScript() {
        val profile = Profile(
            id = "p", name = "P", transport = TransportType.BOARDS, value = "https://x/board/1", session = true,
            secret = "0123456789abcdef0123456789abcdef",
            extras = listOf(
                ExtraTransport(TransportType.SCRIPT, "https://doc/a", priority = 60, scriptId = "my-transport"),
                ExtraTransport(TransportType.SCRIPT, "https://doc/b", priority = 50, scriptId = "other"),
                ExtraTransport(TransportType.BOARDS, "https://x/board/2", priority = 40),
            ),
        )
        val scripts = listOf(
            script(ScriptParam("url")).copy(id = "my-transport", name = "Мой транспорт"),
            script(ScriptParam("url")).copy(id = "other", name = "Другой"),
        )
        // the core calls them boards, script, script-2, boards-2
        assertEquals("Board", profile.carrierLabel("boards", scripts))
        assertEquals("Мой транспорт", profile.carrierLabel("script", scripts))
        assertEquals("Другой", profile.carrierLabel("script-2", scripts))
        assertEquals("Board 2", profile.carrierLabel("boards-2", scripts))
        assertEquals("Board + Мой транспорт", profile.carrierLabels(listOf("boards", "script"), scripts))
        // a script that is no longer installed is still named, by its id, not "script"
        assertEquals("other", profile.carrierLabel("script-2", emptyList()))
        // a name the profile does not know falls back to the generic label
        assertEquals("Volga", Profile(
            id = "q", name = "Q", transport = TransportType.VYANDEX, value = "https://docs.yandex.ru/edit/d/x", session = true,
            secret = "0123456789abcdef0123456789abcdef",
        ).carrierLabel("vyandex", emptyList()))
    }
}
