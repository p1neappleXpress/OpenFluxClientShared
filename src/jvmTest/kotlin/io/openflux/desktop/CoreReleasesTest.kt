package io.openflux.desktop

import io.openflux.desktop.core.CoreAsset
import io.openflux.desktop.core.CoreReleases
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoreReleasesTest {
    private fun release(tag: String, vararg assets: String, pre: Boolean = false, draft: Boolean = false) =
        """{"tag_name":"$tag","prerelease":$pre,"draft":$draft,"assets":[""" +
            assets.joinToString(",") { """{"name":"$it","browser_download_url":"https://dl/$tag/$it"}""" } + "]}"

    @Test
    fun picksTheNewestFullCoreRelease() {
        val releases = Json.parseToJsonElement(
            "[" + listOf(
                release("v0.4.0", "openflux-windows-amd64.exe", "SHA256SUMS.txt", pre = true),
                release("node-v1.2.0", "openflux-linux-amd64", "SHA256SUMS"),
                release("v0.3.0", "openflux-linux-amd64", "SHA256SUMS.txt"),
                release("v0.2.0", "openflux-windows-amd64.exe", "SHA256SUMS.txt"),
                release("v0.1.0", "openflux-windows-amd64.exe", "SHA256SUMS.txt"),
            ).joinToString(",") + "]",
        ).jsonArray
        assertEquals(
            CoreAsset("v0.2.0", "https://dl/v0.2.0/openflux-windows-amd64.exe", "https://dl/v0.2.0/SHA256SUMS.txt"),
            CoreReleases.pick(releases, "openflux-windows-amd64.exe"),
        )
        assertEquals("v0.3.0", CoreReleases.pick(releases, "openflux-linux-amd64")?.tag)
        assertNull(CoreReleases.pick(releases, "openflux-darwin-arm64"))
    }

    @Test
    fun readsTheSums() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        val sums = "$a  openflux-linux-amd64\n$b *openflux-windows-amd64.exe\nnot a line\n"
        assertEquals(a, CoreReleases.sha256(sums, "openflux-linux-amd64"))
        assertEquals("b".repeat(64), CoreReleases.sha256(sums, "openflux-windows-amd64.exe"))
        assertNull(CoreReleases.sha256(sums, "openflux-darwin-arm64"))
    }
}
