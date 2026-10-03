package io.openflux.desktop

import io.openflux.desktop.core.CoreBinary
import io.openflux.desktop.platform.JvmPlatformServices
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises JvmPlatformServices.inspectTransport/scriptFingerprint against
 * the REAL core binary CoreBinary.bundled() finds - desktop has no
 * gomobile, so --inspect-script is the one path a downloaded script's
 * signature actually gets checked on here, and it's worth more than a
 * compile check: a real subprocess, real stdout parsing, a real signed
 * script. Release and nightly CI both build the core (gomobile step aside,
 * the same "go build" every :desktopApp:assembleRelease step does) before
 * running :shared:jvmTest, so bundled() finds it there; locally, build it
 * once with `go build -o desktopApp/resources/<os>/openflux-<os>-<arch>
 * ./OpenFlux` from the desktop module root. Skips (not fails) without one,
 * so a plain checkout's test run stays green.
 */
class JvmPlatformServicesScriptTest {
    private val officialKey = "d8bf9c958b994c2faab886cade5f28213f254911f87abe5e34756a289ae91354"

    private fun platformOrSkip(): JvmPlatformServices? {
        // Gradle's Test task working directory does not reliably match the
        // module layout CoreBinary's own path guesses assume, so point it
        // at desktopApp/resources directly (its first, highest-priority
        // candidate) whenever this process can find that directory at all -
        // same real resolution either way, just not dependent on cwd.
        resourcesDir()?.let { System.setProperty("compose.application.resources.dir", it.absolutePath) }
        val binary = CoreBinary()
        if (binary.bundled() == null) {
            println("JvmPlatformServicesScriptTest: no core binary bundled, skipping (see class doc)")
            return null
        }
        return JvmPlatformServices("test", binary) { "test" }
    }

    private fun resourcesDir(): File? {
        val os = System.getProperty("os.name").lowercase()
        val dir = when { os.contains("win") -> "windows"; os.contains("mac") -> "macos"; else -> "linux" }
        return listOf("desktopApp/resources/$dir", "../desktopApp/resources/$dir", "../../desktopApp/resources/$dir")
            .map { File(it) }.firstOrNull { it.isDirectory }
    }

    @Test
    fun inspectTransport_validSignature_officialKey() {
        val platform = platformOrSkip() ?: return
        val report = Json.parseToJsonElement(platform.inspectTransport(resource("boards.js"), resource("boards.js.sig"), officialKey)).jsonObject

        assertTrue(report["ok"]?.jsonPrimitive?.boolean == true, "report: $report")
        assertEquals("valid", report["signature"]?.jsonPrimitive?.content)
        assertEquals(true, report["official"]?.jsonPrimitive?.boolean)
        assertEquals("boards", report["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun inspectTransport_wrongKey_reportsInvalidButStillReadsManifest() {
        val platform = platformOrSkip() ?: return
        val wrongKey = "00".repeat(32)
        val report = Json.parseToJsonElement(platform.inspectTransport(resource("boards.js"), resource("boards.js.sig"), wrongKey)).jsonObject

        assertTrue(report["ok"]?.jsonPrimitive?.boolean == true)
        assertEquals("invalid", report["signature"]?.jsonPrimitive?.content)
        assertEquals("boards", report["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun scriptFingerprint_isDeterministicSha256OfTheKeyBytes() {
        val platform = platformOrSkip() ?: return
        val fp = platform.scriptFingerprint(officialKey)
        assertEquals(64, fp.length, "fingerprint: $fp")
        assertEquals(fp, platform.scriptFingerprint(officialKey))
        assertTrue(platform.scriptFingerprint("not-hex").isEmpty())
    }

    private fun resource(name: String): ByteArray {
        val f = listOf(
            File("../OpenFlux/transport/script/js/$name"),
            File("OpenFlux/transport/script/js/$name"),
        ).firstOrNull { it.isFile } ?: error("fixture $name not found relative to the module directory")
        return f.readBytes()
    }
}
