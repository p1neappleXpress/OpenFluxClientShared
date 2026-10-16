package io.openflux.desktop.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A file of a GitHub release: the installer for one system. */
data class AppAsset(val name: String, val url: String, val size: Long)

/** A published release of the app: its version, what changed, and the files. */
data class AppRelease(
    val tag: String,
    /** "2.11.1" of the tag "v2.11.1". */
    val version: String,
    val notes: String,
    val pageUrl: String,
    val assets: List<AppAsset>,
) {
    /** The release's `SHA256SUMS.txt`: the only source of checksums the installer is trusted by. */
    val checksums: AppAsset? get() = assets.firstOrNull { it.name.equals(CHECKSUMS_NAME, ignoreCase = true) }

    companion object {
        const val CHECKSUMS_NAME = "SHA256SUMS.txt"
    }
}

/** Version numbers as the releases write them: "v2.11.1", "2.12", "2.12.0-rc1". */
object AppVersions {
    /** The numeric parts of [text] ("v2.11.1" is 2, 11, 1), null when it is not a version (a "dev" build, a "nightly-…" tag). */
    fun parse(text: String): List<Int>? {
        val core = text.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        val parts = core.split('.')
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return numbers.takeIf { it.all { n -> n >= 0 } }
    }

    /** True when [candidate] is a higher version than [current]; false when either is not a version. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        // "2.12.0-rc1" is not newer than "2.12.0": same numbers, only a pre-release suffix.
        return false
    }
}

/** Reads what GitHub's releases API and a release's SHA256SUMS.txt say. */
object ReleaseFeed {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The releases of the list, newest first as GitHub gives them. Drafts and pre-releases are not offered, and
     * neither is a tag that is not a version (the "nightly-…" test builds).
     */
    fun releases(body: String): List<AppRelease> {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            if (o.flag("draft") || o.flag("prerelease")) return@mapNotNull null
            val tag = o["tag_name"]?.jsonPrimitive?.content.orEmpty()
            if (AppVersions.parse(tag) == null) return@mapNotNull null
            val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
                val ao = a as? JsonObject ?: return@mapNotNull null
                val name = ao["name"]?.jsonPrimitive?.content.orEmpty()
                val url = ao["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                if (name.isEmpty() || !url.startsWith("https://")) null else AppAsset(name, url, ao["size"]?.jsonPrimitive?.longOrNull ?: 0)
            }
            AppRelease(
                tag = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                notes = o["body"]?.jsonPrimitive?.content.orEmpty().trim(),
                pageUrl = o["html_url"]?.jsonPrimitive?.content.orEmpty(),
                assets = assets,
            )
        }
    }

    /** The newest release that is higher than [current], or null. */
    fun newer(releases: List<AppRelease>, current: String): AppRelease? =
        releases.filter { AppVersions.isNewer(it.version, current) }
            .maxWithOrNull { a, b -> if (AppVersions.isNewer(a.version, b.version)) 1 else if (AppVersions.isNewer(b.version, a.version)) -1 else 0 }

    /** `sha256sum`'s output: "<hex>  <name>" (or "<hex> *<name>"), one file a line. Names are lower-cased by the caller's key. */
    fun checksums(text: String): Map<String, String> = buildMap {
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val hash = trimmed.substringBefore(' ').lowercase()
            val name = trimmed.substringAfter(' ', "").trim().removePrefix("*").trim()
            if (name.isNotEmpty() && hash.length == 64 && hash.all { it in "0123456789abcdef" }) put(name, hash)
        }
    }

    private fun JsonObject.flag(key: String): Boolean = this[key]?.jsonPrimitive?.let { it.booleanOrNull ?: false } ?: false
}

/**
 * What starting an installer came to: [started], and what the person is told ([message]): to do next when it
 * started (the desktop systems that do not quit), or why it did not (Android: allow installing first).
 */
data class InstallResult(val started: Boolean, val message: String? = null)

/** Where an update of the app stands; the banner and Settings read it. */
sealed interface UpdateState {
    /** Nothing to show. */
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** The newest release is the one running. */
    data object UpToDate : UpdateState
    /** A newer release; [asset] is null where the app cannot install it itself (its page is opened instead). */
    data class Available(val release: AppRelease, val asset: AppAsset?) : UpdateState
    data class Downloading(val release: AppRelease, val done: Long, val total: Long) : UpdateState
    /** The installer has been started (or, on Android, the system's confirmation is up). */
    data class Installing(val release: AppRelease, val note: String?) : UpdateState
    data class Failed(val message: String, val release: AppRelease?) : UpdateState
}
