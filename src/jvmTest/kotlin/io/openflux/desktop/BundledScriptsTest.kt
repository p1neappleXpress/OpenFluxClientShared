package io.openflux.desktop

import io.openflux.desktop.data.FileScriptRepository
import io.openflux.desktop.data.ScriptVersions
import io.openflux.desktop.data.ShippedScript
import io.openflux.desktop.model.ScriptSource
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptVersionsTest {
    private fun older(a: String, b: String) {
        assertTrue(ScriptVersions.compare(a, b) < 0, "$a should be older than $b")
        assertTrue(ScriptVersions.compare(b, a) > 0, "$b should be newer than $a")
    }

    @Test
    fun ordersLikeTheCore() {
        older("1.9.0", "1.10.0")
        older("1.1.0", "1.1.1")
        older("1.3.0-beta.1", "1.3.0")
        older("1.3.0-beta.1", "1.3.0-beta.2")
        older("1.3.0-beta.2", "1.3.0-beta.10")
        older("1.3.0-1", "1.3.0-beta")
        older("1.3.0-beta", "1.3.0-beta.1")
        older("not a version", "0.0.1")
        assertEquals(0, ScriptVersions.compare("v1.1.0", "1.1.0+build7"))
        assertEquals(0, ScriptVersions.compare("", "garbage"))
    }
}

/** What the apps do with the transports shipped inside them (the same code on the desktop and on Android). */
class BundledScriptsTest {
    private val key = "ab".repeat(32)

    /** A shipped package here is just "PK name version" (a .flux starts with the zip magic) bytes; the fake core reads it back as a trust report. */
    private class Repo(dir: File) : FileScriptRepository(dir, { data, _, _ ->
        val (_, name, version) = String(data).split(' ')
        """{"ok":true,"signature":"valid","name":"$name","version":"$version","official":true,"fingerprint":"ff"}"""
    }) {
        fun sync(vararg shipped: ShippedScript) = syncShipped(shipped.toList(), "ab".repeat(32))
    }

    private fun pkg(name: String, version: String, ext: String = "flux") = ShippedScript("$name-$version.$ext", "${if (ext == "js") "JS" else "PK"} $name $version".toByteArray(), if (ext == "js") byteArrayOf(1) else ByteArray(0))

    private fun dir(): File = Files.createTempDirectory("scripts").toFile().also { it.deleteOnExit() }

    @Test
    fun aFreshInstallationGetsEverythingShipped() {
        val repo = Repo(dir())
        repo.sync(pkg("yandex", "1.1.0"), pkg("mtslink", "1.0.0"), pkg("bitrix", "1.0.0"))
        assertEquals(listOf("bitrix", "mtslink", "yandex"), repo.scripts.value.map { it.id }.sorted())
        assertTrue(repo.scripts.value.all { it.source == ScriptSource.Bundled })
    }

    @Test
    fun aNewReleaseAddsItsNewTransportsToWhoAlreadyHasTheOldOnes() {
        val d = dir()
        Repo(d).sync(pkg("yandex", "1.1.0"))
        val later = Repo(d) // the app started again after an update
        later.sync(pkg("yandex", "1.1.0"), pkg("mtslink", "1.0.0"))
        assertEquals(listOf("mtslink", "yandex"), later.scripts.value.map { it.id }.sorted())
    }

    @Test
    fun aDeletedTransportStaysDeleted() {
        val d = dir()
        val repo = Repo(d)
        repo.sync(pkg("yandex", "1.1.0"), pkg("mtslink", "1.0.0"))
        repo.delete("mtslink")
        Repo(d).also { it.sync(pkg("yandex", "1.1.0"), pkg("mtslink", "1.0.0")) }.let {
            assertNull(it.byId("mtslink"))
            assertNotNull(it.byId("yandex"))
        }
    }

    @Test
    fun aNewerShippedCopyReplacesTheInstalledOneAndKeepsItSwitchedOff() {
        val d = dir()
        val repo = Repo(d)
        repo.sync(pkg("yandex", "1.1.0"))
        repo.setEnabled("yandex", false)
        repo.sync(pkg("yandex", "1.2.0"))
        val s = repo.byId("yandex")!!
        assertEquals("1.2.0", s.version)
        assertFalse(s.enabled, "a transport the user switched off stays off")
        repo.sync(pkg("yandex", "1.1.0")) // never downgraded
        assertEquals("1.2.0", repo.byId("yandex")!!.version)
    }

    @Test
    fun aBareJsCopyBecomesAFluxPackageOfTheSameVersion() {
        val d = dir()
        val repo = Repo(d)
        repo.sync(pkg("boards", "1.1.0", "js"))
        assertEquals("boards.js", repo.byId("boards")!!.fileName)
        repo.sync(pkg("boards", "1.1.0")) // same version, but a .flux knows where its updates come from
        assertEquals("boards.flux", repo.byId("boards")!!.fileName)
        assertFalse(File(d, "boards.js").exists(), "the old file is not left behind")
        assertFalse(File(d, "boards.js.sig").exists())
    }

    @Test
    fun anInstallationFromBeforeTheListDoesNotGetBackWhatItDeleted() {
        val d = dir()
        // The old build shipped five and installed them all at once; the user then deleted "mailru".
        val old = Repo(d)
        old.sync(pkg("yandex", "1.1.0", "js"), pkg("mailru", "1.1.0", "js"))
        old.delete("mailru")
        File(d, "bundled-offered.json").delete() // an installation that never recorded the list
        val repo = Repo(d)
        repo.sync(pkg("yandex", "1.1.0"), pkg("mailru", "1.1.0"), pkg("mtslink", "1.0.0"))
        assertNull(repo.byId("mailru"), "mailru was one of the five: it stays deleted")
        assertNotNull(repo.byId("mtslink"), "a transport new in this build is installed")
    }

    @Test
    fun aTransportTheUserAddedUnderTheSameNameIsLeftAlone() {
        val d = dir()
        val repo = Repo(d)
        repo.install("JS yandex 9.9.9".toByteArray(), ByteArray(0), key, ScriptSource.GitHub, "https://example.org/yandex.flux", now = 1L)
        repo.sync(pkg("yandex", "1.1.0"))
        val s = repo.byId("yandex")!!
        assertEquals("9.9.9", s.version)
        assertEquals(ScriptSource.GitHub, s.source)
    }
}
