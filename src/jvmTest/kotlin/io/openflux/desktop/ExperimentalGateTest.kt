package io.openflux.desktop

import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.CoreConfig
import io.openflux.desktop.model.CorePaths
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ScriptCarrierLookup
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.ui.profiles.ProfilesTab
import io.openflux.desktop.ui.scripts.ScriptsTab
import io.openflux.desktop.ui.shell.appTabs
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JS-engine features are off until "Экспериментальные функции" is turned on. */
class ExperimentalGateTest {
    private val paths = CorePaths("C:/rt/key", "C:/rt/p.conf", "C:/cfg/cookies/p.json", "C:/rt/ipc.sock")
    private val secret = "f".repeat(64)
    private val lookup = ScriptCarrierLookup("/p.flux", "aa", "demo")

    private val script = Profile(
        id = "s", name = "JS", transport = TransportType.SCRIPT, value = "https://example.org/doc", secret = secret,
        session = true, scriptId = "demo",
    )
    private val native = Profile(
        id = "n", name = "Native", transport = TransportType.VYANDEX, value = "https://disk.yandex.ru/i/one", secret = secret, session = true,
    )

    @Test
    fun theFeaturesAreOffByDefaultAndSettingsSavedBeforeReadAsOff() {
        assertFalse(AppSettings().experimental)
        val old = """{"theme":"System","mode":"Client"}"""
        assertFalse(Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), old).experimental)
    }

    @Test
    fun theTransportsTabIsThereOnlyWithTheFeaturesOn() {
        assertFalse(ScriptsTab in appTabs(false), "the Transports tab is hidden by default")
        assertTrue(ScriptsTab in appTabs(true))
        assertEquals(appTabs(true) - ScriptsTab, appTabs(false), "nothing else changes")
        assertTrue(ProfilesTab in appTabs(false))
    }

    @Test
    fun aProfileThroughAScriptDoesNotConnectUntilTheyAreOn() {
        val error = assertFailsWith<IllegalArgumentException> { CoreConfig.build(script, AppSettings(), paths) { lookup } }
        assertEquals(CoreConfig.SCRIPTS_OFF, error.message)
        assertTrue("Экспериментальные функции" in error.message.orEmpty())
        assertTrue(CoreConfig.build(script, AppSettings(experimental = true), paths) { lookup }.conf.orEmpty().contains("Type = script"))
    }

    @Test
    fun aScriptAmongSeveralCarriersCountsAsWell() {
        val mixed = native.copy(extras = listOf(ExtraTransport(TransportType.SCRIPT, "https://example.org/doc", scriptId = "demo", priority = 50)))
        assertFailsWith<IllegalArgumentException> { CoreConfig.build(mixed, AppSettings(), paths) { lookup } }
        assertTrue(CoreConfig.build(mixed, AppSettings(experimental = true), paths) { lookup }.conf != null)
    }

    @Test
    fun nativeTransportsAreUntouchedWhateverTheSetting() {
        for (on in listOf(false, true)) {
            for (mode in ConnectionMode.entries) {
                val launch = CoreConfig.build(native, AppSettings(experimental = on, mode = mode), paths)
                assertFalse("Type = script" in launch.conf.orEmpty())
            }
        }
    }

    @Test
    fun anExtraThatIsNotAScriptDoesNotNeedThem() {
        val extras = native.copy(extras = listOf(ExtraTransport(TransportType.VYANDEX, "https://disk.yandex.ru/i/two", priority = 50)))
        assertTrue(CoreConfig.build(extras, AppSettings(), paths).conf != null)
        assertContentEquals(listOf(TransportType.VYANDEX, TransportType.VYANDEX), extras.carriers.map { it.type })
    }
}
