package io.openflux.desktop.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A core release asset to download: the release tag and the file's URL. */
data class CoreAsset(val tag: String, val url: String, val sumsUrl: String)

/**
 * Reading a core repository's GitHub releases: the general CLI releases are
 * tagged v1.2.3 and carry openflux-<os>-<arch>[.exe] with SHA256SUMS.txt
 * (the node-v* ones are the exit-node build for node-install.sh).
 */
object CoreReleases {
    private val TAG = Regex("""^v\d+\.\d+\.\d+$""")
    private const val SUMS = "SHA256SUMS.txt"

    /** The newest published release (GitHub lists them newest first) with [fileName] and its sums. */
    fun pick(releases: JsonArray, fileName: String): CoreAsset? = releases.asSequence()
        .map { it.jsonObject }
        .filter { it["draft"]?.jsonPrimitive?.booleanOrNull != true && it["prerelease"]?.jsonPrimitive?.booleanOrNull != true }
        .mapNotNull { release ->
            val tag = release["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!TAG.matches(tag)) return@mapNotNull null
            val assets = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
            fun url(name: String) = assets.firstOrNull { it["name"]?.jsonPrimitive?.content == name }
                ?.get("browser_download_url")?.jsonPrimitive?.content
            val file = url(fileName) ?: return@mapNotNull null
            val sums = url(SUMS) ?: return@mapNotNull null
            CoreAsset(tag, file, sums)
        }
        .firstOrNull()

    /** The SHA-256 SHA256SUMS.txt gives [fileName] ("<hex>  <name>" lines), null when it has none. */
    fun sha256(sums: String, fileName: String): String? = sums.lineSequence()
        .map { it.trim().split(Regex("\\s+"), limit = 2) }
        .firstOrNull { it.size == 2 && it[1].removePrefix("*") == fileName && it[0].matches(Regex("[0-9a-fA-F]{64}")) }
        ?.get(0)?.lowercase()
}
