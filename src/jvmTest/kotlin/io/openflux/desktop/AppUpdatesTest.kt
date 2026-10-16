package io.openflux.desktop

import com.sun.net.httpserver.HttpServer
import io.openflux.desktop.data.UpdateDownloads
import io.openflux.desktop.model.AppAsset
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.AppVersions
import io.openflux.desktop.model.InstallResult
import io.openflux.desktop.model.ReleaseFeed
import io.openflux.desktop.model.UpdateState
import io.openflux.desktop.platform.DesktopUpdate
import io.openflux.desktop.service.AppUpdates
import io.openflux.desktop.service.PlatformServices
import io.openflux.desktop.service.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatesTest {
    private val sums = "%s  OpenFlux-2.12.0-windows-x64-setup.exe\n%s *OpenFlux-2.12.0-linux-x64.deb\n"
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun releaseJson(vararg tags: String, prerelease: Set<String> = emptySet(), draft: Set<String> = emptySet()): String =
        tags.joinToString(prefix = "[", postfix = "]") { tag ->
            val version = tag.removePrefix("v")
            """{"tag_name":"$tag","draft":${tag in draft},"prerelease":${tag in prerelease},"body":"Что нового в $tag","html_url":"https://github.com/x/y/releases/tag/$tag",
              "assets":[
                {"name":"OpenFlux-$version-windows-x64-setup.exe","browser_download_url":"https://github.com/x/y/releases/download/$tag/OpenFlux-$version-windows-x64-setup.exe","size":1048576},
                {"name":"SHA256SUMS.txt","browser_download_url":"https://github.com/x/y/releases/download/$tag/SHA256SUMS.txt","size":200},
                {"name":"evil.exe","browser_download_url":"http://example.com/evil.exe","size":1}
              ]}"""
        }

    @Test
    fun versionsCompareByNumbers() {
        assertEquals(listOf(2, 11, 1), AppVersions.parse("v2.11.1"))
        assertEquals(listOf(2, 12), AppVersions.parse("2.12-rc1"))
        assertNull(AppVersions.parse("dev"))
        assertNull(AppVersions.parse("nightly-20261010"))
        assertNull(AppVersions.parse(""))
        assertTrue(AppVersions.isNewer("v2.12.0", "2.11.1"))
        assertTrue(AppVersions.isNewer("2.11.10", "2.11.9"), "numbers, not text: 10 is more than 9")
        assertTrue(AppVersions.isNewer("3.0", "2.99.99"))
        assertFalse(AppVersions.isNewer("2.11.1", "2.11.1"))
        assertFalse(AppVersions.isNewer("2.11", "2.11.0"), "2.11 is 2.11.0")
        assertFalse(AppVersions.isNewer("2.11.0", "2.11.1"))
        assertFalse(AppVersions.isNewer("2.12.0", "dev"), "a dev build is never nagged")
        assertFalse(AppVersions.isNewer("nightly-1", "2.0"))
    }

    @Test
    fun theFeedOffersOnlyPublishedVersionTags() {
        val body = releaseJson("v2.12.0", "v2.13.0-rc1", "nightly-20261010", "v2.11.1", "v9.9.9", prerelease = setOf("v2.13.0-rc1"), draft = setOf("v9.9.9"))
        val list = ReleaseFeed.releases(body)
        assertEquals(listOf("v2.12.0", "v2.11.1"), list.map { it.tag })
        val r = list.first()
        assertEquals("2.12.0", r.version)
        assertEquals("Что нового в v2.12.0", r.notes)
        assertEquals(listOf("OpenFlux-2.12.0-windows-x64-setup.exe", "SHA256SUMS.txt"), r.assets.map { it.name }, "an http asset is not taken")
        assertNotNull(r.checksums)
        assertEquals("2.12.0", ReleaseFeed.newer(list, "2.11.1")?.version)
        assertNull(ReleaseFeed.newer(list, "2.12.0"))
        assertNull(ReleaseFeed.newer(list, "dev"))
        assertEquals(emptyList(), ReleaseFeed.releases("not json"))
        assertEquals(emptyList(), ReleaseFeed.releases("""{"message":"API rate limit exceeded"}"""))
    }

    @Test
    fun theNewestIsChosenWhateverTheOrder() {
        val list = ReleaseFeed.releases(releaseJson("v2.11.5", "v2.13.0", "v2.12.0"))
        assertEquals("2.13.0", ReleaseFeed.newer(list, "2.11.1")?.version)
    }

    @Test
    fun checksumsAreReadFromSha256sumOutput() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        val parsed = ReleaseFeed.checksums("$a  one.exe\n$b *two.deb\r\nnot a line\n${"c".repeat(63)}  short.bin\n\n")
        assertEquals(mapOf("one.exe" to a, "two.deb" to b.lowercase()), parsed)
    }

    @Test
    fun desktopPicksItsOwnInstaller() {
        val names = listOf(
            "OpenFlux-2.12.0-linux-x64.deb", "OpenFlux-2.12.0-linux-x64.tar.gz", "OpenFlux-2.12.0-macos-arm64.dmg",
            "OpenFlux-2.12.0-macos-x64.dmg", "OpenFlux-2.12.0-windows-x64-portable.zip", "OpenFlux-2.12.0-windows-x64-setup.exe",
            "OpenFlux-2.12.0-windows-x64.msi", "SHA256SUMS.txt",
        )
        val assets = names.map { AppAsset(it, "https://github.com/$it", 1) }
        assertEquals("OpenFlux-2.12.0-windows-x64-setup.exe", DesktopUpdate.pick(assets, "windows 11", "amd64", false)?.name)
        assertEquals("OpenFlux-2.12.0-macos-arm64.dmg", DesktopUpdate.pick(assets, "mac os x", "aarch64", false)?.name)
        assertEquals("OpenFlux-2.12.0-macos-x64.dmg", DesktopUpdate.pick(assets, "mac os x", "x86_64", false)?.name)
        assertEquals("OpenFlux-2.12.0-linux-x64.deb", DesktopUpdate.pick(assets, "linux", "amd64", true)?.name)
        assertNull(DesktopUpdate.pick(assets, "linux", "amd64", false), "no dpkg: the page of the release is opened")
        assertNull(DesktopUpdate.pick(assets, "linux", "aarch64", true))
        assertNull(DesktopUpdate.pick(assets, "freebsd", "amd64", true))
        assertNull(DesktopUpdate.pick(emptyList(), "windows 11", "amd64", true))
    }

    // ---- the manager ----

    private class Settings0(initial: AppSettings = AppSettings()) : SettingsRepository {
        override val settings = MutableStateFlow(initial)
        override fun update(transform: (AppSettings) -> AppSettings) { settings.value = transform(settings.value) }
    }

    private inner class Platform(var version: String = "2.11.1", var supported: Boolean = true) : PlatformServices {
        val urls = mutableMapOf<String, String>()
        var failFeed = false
        val opened = mutableListOf<String>()
        val downloaded = mutableListOf<Pair<String, String>>()
        var install = InstallResult(true, null)
        var downloadError: String? = null
        val installed = mutableListOf<String>()
        var clock = 1_800_000_000_000L
        override val appVersion get() = version
        override val coreVersion = "test"
        override val clientRepo = "x/y"
        override val systemProxySupported = false
        override val fullTunnelSupported = false
        override val elevated = false
        override fun restartElevated() = false
        override fun clipboardText(): String? = null
        override fun setClipboardText(text: String) = Unit
        override fun qrFromClipboardImage(): String? = null
        override fun qrFromFile(path: String): String? = null
        override suspend fun pickFile(title: String, extensions: List<String>): String? = null
        override fun readTextFile(path: String, maxBytes: Int): String? = null
        override fun qrMatrix(text: String): List<BooleanArray> = emptyList()
        override fun openUrl(url: String) { opened += url }
        override fun newSecret() = "00".repeat(32)
        override fun now() = clock
        override suspend fun latestRelease(): String? = null

        override val updateInstallSupported get() = supported
        override suspend fun httpText(url: String): String {
            if (failFeed && url.contains("api.github.com")) throw java.io.IOException("нет связи")
            return urls[url] ?: throw java.io.IOException("404 $url")
        }
        override fun updateAsset(assets: List<AppAsset>) = assets.firstOrNull { it.name.endsWith("-setup.exe") }
        override suspend fun downloadUpdate(asset: AppAsset, sha256: String, onProgress: (Long, Long) -> Unit): String {
            downloadError?.let { throw java.io.IOException(it) }
            downloaded += asset.name to sha256
            onProgress(asset.size / 2, asset.size)
            return "/tmp/${asset.name}"
        }
        override suspend fun installUpdate(path: String): InstallResult { installed += path; return install }
    }

    private val feedUrl = "https://api.github.com/repos/x/y/releases?per_page=15"

    private fun TestScope.setup(platform: Platform, settings: Settings0 = Settings0()): Pair<AppUpdates, Settings0> {
        platform.urls[feedUrl] = releaseJson("v2.12.0", "v2.11.1")
        platform.urls["https://github.com/x/y/releases/download/v2.12.0/SHA256SUMS.txt"] =
            sums.format("a".repeat(64), "b".repeat(64))
        return AppUpdates(platform, settings, this) to settings
    }

    @Test
    fun aManualCheckSaysWhatItFound() = runTest {
        val p = Platform()
        val (updates, settings) = setup(p)
        updates.check(manual = true)
        val s = updates.state.value as UpdateState.Available
        assertEquals("2.12.0", s.release.version)
        assertEquals("OpenFlux-2.12.0-windows-x64-setup.exe", s.asset?.name)
        assertEquals(p.clock, settings.settings.value.updateCheckedAt)

        p.version = "2.12.0"
        updates.check(manual = true)
        assertEquals(UpdateState.UpToDate, updates.state.value)
        p.failFeed = true
        updates.check(manual = true)
        assertTrue((updates.state.value as UpdateState.Failed).message.contains("нет связи"))
    }

    @Test
    fun aQuietCheckStaysQuietWhenThereIsNothingToSay() = runTest {
        val p = Platform(version = "2.12.0")
        val (updates, _) = setup(p)
        updates.check(manual = false)
        assertEquals(UpdateState.Idle, updates.state.value)
        p.version = "2.11.1"
        p.failFeed = true
        updates.check(manual = false)
        assertEquals(UpdateState.Idle, updates.state.value, "a failed automatic check is not shown")
        p.failFeed = false
        updates.check(manual = false)
        assertTrue(updates.state.value is UpdateState.Available)
    }

    @Test
    fun aSkippedReleaseIsNotOfferedAgainButANewerOneIs() = runTest {
        val p = Platform()
        val (updates, settings) = setup(p)
        updates.check(manual = false)
        updates.skip()
        assertEquals("v2.12.0", settings.settings.value.skippedUpdate)
        assertEquals(UpdateState.Idle, updates.state.value)
        updates.check(manual = false)
        assertEquals(UpdateState.Idle, updates.state.value, "skipped")
        updates.check(manual = true)
        assertTrue(updates.state.value is UpdateState.Available, "a check the person asks for shows it anyway")
        p.urls[feedUrl] = releaseJson("v2.13.0", "v2.12.0")
        updates.check(manual = false)
        assertEquals("2.13.0", (updates.state.value as UpdateState.Available).release.version)
    }

    @Test
    fun dismissingHidesUntilTheNextCheck() = runTest {
        val p = Platform()
        val (updates, _) = setup(p)
        updates.check(manual = false)
        updates.dismiss()
        assertEquals(UpdateState.Idle, updates.state.value)
        updates.check(manual = false)
        assertTrue(updates.state.value is UpdateState.Available)
    }

    @Test
    fun installingChecksTheSumAndStartsTheInstaller() = runTest {
        val p = Platform()
        val (updates, _) = setup(p)
        updates.check(manual = true)
        updates.install()
        runCurrent()
        assertEquals(listOf("OpenFlux-2.12.0-windows-x64-setup.exe" to "a".repeat(64)), p.downloaded)
        assertEquals(listOf("/tmp/OpenFlux-2.12.0-windows-x64-setup.exe"), p.installed)
        assertTrue(updates.state.value is UpdateState.Installing)
    }

    @Test
    fun withoutAChecksumNothingIsInstalled() = runTest {
        val p = Platform()
        val (updates, _) = setup(p)
        p.urls["https://github.com/x/y/releases/download/v2.12.0/SHA256SUMS.txt"] = "${"a".repeat(64)}  something-else.exe\n"
        updates.check(manual = true)
        updates.install()
        runCurrent()
        val failed = updates.state.value as UpdateState.Failed
        assertTrue(failed.message.contains("нет суммы"), failed.message)
        assertTrue(p.downloaded.isEmpty() && p.installed.isEmpty())

        p.urls.remove("https://github.com/x/y/releases/download/v2.12.0/SHA256SUMS.txt")
        updates.install()
        runCurrent()
        assertTrue(updates.state.value is UpdateState.Failed, "a missing SHA256SUMS.txt fails too")
        assertTrue(p.installed.isEmpty())
    }

    @Test
    fun aFailedDownloadCanBeRetried() = runTest {
        val p = Platform()
        val (updates, _) = setup(p)
        updates.check(manual = true)
        p.downloadError = "Контрольная сумма не совпала"
        updates.install()
        runCurrent()
        assertEquals("Контрольная сумма не совпала", (updates.state.value as UpdateState.Failed).message)
        assertTrue(p.installed.isEmpty(), "a file that does not match is not started")
        p.downloadError = null
        updates.install()
        runCurrent()
        assertTrue(updates.state.value is UpdateState.Installing)
        assertEquals(1, p.installed.size)
    }

    @Test
    fun androidMayAskForPermissionFirst() = runTest {
        val p = Platform()
        val (updates, _) = setup(p)
        p.install = InstallResult(false, "Разрешите установку и нажмите «Повторить».")
        updates.check(manual = true)
        updates.install()
        runCurrent()
        assertEquals("Разрешите установку и нажмите «Повторить».", (updates.state.value as UpdateState.Failed).message)
        p.install = InstallResult(true, null)
        updates.install()
        runCurrent()
        assertTrue(updates.state.value is UpdateState.Installing)
    }

    @Test
    fun whereTheAppCannotInstallThePageOpens() = runTest {
        val p = Platform(supported = false)
        val (updates, _) = setup(p)
        updates.check(manual = true)
        assertNull((updates.state.value as UpdateState.Available).asset)
        updates.install()
        runCurrent()
        assertEquals(listOf("https://github.com/x/y/releases/tag/v2.12.0"), p.opened)
        assertTrue(p.downloaded.isEmpty())
    }

    @Test
    fun theLoopChecksAfterAShortWaitAndFollowsTheSwitch() = runTest {
        val p = Platform()
        val (updates, settings) = setup(p, Settings0(AppSettings(autoUpdate = false)))
        val updater = AppUpdates(p, settings, this, firstDelayMs = 1000, everyMs = 10_000)
        updater.start()
        updater.start()
        advanceTimeBy(1500)
        assertEquals(UpdateState.Idle, updater.state.value, "automatic checks are off")
        settings.update { it.copy(autoUpdate = true) }
        advanceTimeBy(10_000)
        assertTrue(updater.state.value is UpdateState.Available)
        assertEquals(UpdateState.Idle, updates.state.value)
        coroutineContext.cancelChildren()
    }

    // ---- the download ----

    @Test
    fun aDownloadIsCheckedAndOnlyThenNamed() {
        val body = ByteArray(700_000) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { ex -> ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }
        server.createContext("/redirect") { ex -> ex.responseHeaders.add("Location", "/ok"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/missing") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        server.start()
        val dir = java.nio.file.Files.createTempDirectory("update-test").toFile()
        UpdateDownloads.allowed = { it.host == "127.0.0.1" }
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val progress = mutableListOf<Pair<Long, Long>>()
            val file = kotlinx.coroutines.runBlocking { UpdateDownloads.file("$base/redirect", dir, "OpenFlux-1.0-setup.exe", sha(body)) { d, t -> progress += d to t } }
            assertEquals("OpenFlux-1.0-setup.exe", file.name)
            assertEquals(body.size.toLong(), file.length())
            assertTrue(progress.isNotEmpty() && progress.last() == body.size.toLong() to body.size.toLong(), progress.toString())
            assertFalse(File(dir, "OpenFlux-1.0-setup.exe.part").exists())

            // Asked again for the same file (and sum): it is not fetched twice.
            server.removeContext("/ok")
            server.removeContext("/redirect")
            val again = kotlinx.coroutines.runBlocking { UpdateDownloads.file("$base/redirect", dir, "OpenFlux-1.0-setup.exe", sha(body)) { _, _ -> } }
            assertEquals(file, again)
            server.createContext("/ok") { ex -> ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }

            // A wrong sum: nothing is left, not even the earlier file's name taken by a bad one.
            val bad = assertFailsWith<java.io.IOException> {
                kotlinx.coroutines.runBlocking { UpdateDownloads.file("$base/ok", dir, "bad.exe", "0".repeat(64)) { _, _ -> } }
            }
            assertTrue(bad.message.orEmpty().contains("Контрольная сумма"), bad.message ?: "")
            assertFalse(File(dir, "bad.exe").exists())
            assertFalse(File(dir, "bad.exe.part").exists())

            assertFailsWith<java.io.IOException> {
                kotlinx.coroutines.runBlocking { UpdateDownloads.file("$base/missing", dir, "x.exe", sha(body)) { _, _ -> } }
            }
            // A name cannot climb out of the folder.
            assertFailsWith<IllegalArgumentException> {
                kotlinx.coroutines.runBlocking { UpdateDownloads.file("$base/ok", dir, "../evil.exe", sha(body)) { _, _ -> } }
            }
            assertEquals("body", kotlinx.coroutines.runBlocking {
                server.createContext("/t") { ex -> val b = "body".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
                UpdateDownloads.text("$base/t")
            })
        } finally {
            server.stop(0)
            dir.deleteRecursively()
            UpdateDownloads.allowed = { url ->
                url.protocol == "https" && listOf("github.com", "githubusercontent.com").any { url.host == it || url.host.endsWith(".$it") }
            }
        }
    }

    @Test
    fun onlyGithubAndOnlyHttpsAreOpened() {
        val dir = java.nio.file.Files.createTempDirectory("update-test2").toFile()
        try {
            for (url in listOf("http://github.com/x", "https://example.com/x.exe", "https://github.com.evil.com/x", "https://evilgithub.com/x")) {
                val e = assertFailsWith<java.io.IOException>(url) {
                    kotlinx.coroutines.runBlocking { UpdateDownloads.file(url, dir, "x.exe", "0".repeat(64)) { _, _ -> } }
                }
                assertTrue(e.message.orEmpty().contains("не принадлежит GitHub"), "$url: ${e.message}")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
