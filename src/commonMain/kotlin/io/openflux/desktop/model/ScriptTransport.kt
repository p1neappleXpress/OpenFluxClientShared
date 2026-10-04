package io.openflux.desktop.model

import kotlinx.serialization.Serializable

/** One input a script transport asks for, from its info().params. */
@Serializable
data class ScriptParam(
    val key: String,
    val label: String = "",
    /** "url" | "text" | "secret". */
    val type: String = "text",
    val required: Boolean = false,
)

/** Where an installed script came from; shown in its details. */
@Serializable
enum class ScriptSource(val label: String) {
    Bundled("В комплекте"),
    GitHub("GitHub"),
    Link("Ссылка"),
    File("Файл"),
    Base64("Текст base64"),
}

/**
 * A JS (goja) script transport installed on this device: the signed script
 * file plus the author key pinned when the user trusted it (TOFU). The core
 * re-verifies the signature against [pubkeyHex] every time it loads the file,
 * so a swapped file on disk fails closed - this record is for the UI and for
 * building the carrier, not a trust shortcut.
 */
@Serializable
data class InstalledScript(
    /** Stable id, usually the script's own info().name. */
    val id: String,
    val name: String,
    val version: String = "",
    /** Pinned author public key (hex) the signature is checked against. */
    val pubkeyHex: String,
    /** SHA-256 of the key, the fingerprint the user compares out of band. */
    val fingerprint: String,
    /** File in the scripts dir: "<id>.flux", or "<id>.js" (with "<id>.js.sig" beside it). */
    val fileName: String,
    /** Drawable name for the list, e.g. "ic_mailru"; resolved via AppIcons.byName. */
    val icon: String = "ic_code",
    /** Signed by the first-party OpenFlux key. */
    val official: Boolean = false,
    val enabled: Boolean = true,
    val params: List<ScriptParam> = emptyList(),
    val source: ScriptSource = ScriptSource.File,
    /** URL or repo reference it was added from, for updates and display. */
    val origin: String = "",
    val addedAt: Long = 0,
    /** The package's own id (manifest.json), what its update.json is filed under; "" for a bare .js or an install older than this field. */
    val packageId: String = "",
    /** Wire-format generation of the installed version (1 when the package does not say). */
    val wire: Int = 1,
    /** The https update.json addresses the package's manifest names; empty = nothing to check. */
    val updateUrls: List<String> = emptyList(),
) {
    /** A signed package that says where its updates are. */
    val updatable: Boolean get() = fileName.endsWith(".flux") && updateUrls.isNotEmpty()

    /** The main input shown on the profile's value field (first param). */
    val primaryParam: ScriptParam? get() = params.firstOrNull()

    /** Fingerprint grouped for display: "ab cd ef 12 …". */
    val shortFingerprint: String
        get() = fingerprint.chunked(2).take(4).joinToString(" ") + if (fingerprint.length > 8) " …" else ""
}

/** What the core's check/apply/rollback answered, as JSON, decoded. Codes only: wording is [ScriptUpdateMessages]'s. */
@Serializable
data class ScriptUpdateReport(
    val status: String = "",
    val code: String = "",
    val current: String = "",
    val latest: String = "",
    val wire: Int = 0,
    val wireBreak: Boolean = false,
    val notes: String = "",
    val official: Boolean = false,
    val autoOk: Boolean = false,
    /** Set when the update was signed by another of OpenFlux's own keys (a key rotation): pin the install to it. */
    val newKey: String = "",
) {
    val available: Boolean get() = status == "available"
    val blocked: Boolean get() = status == "blocked"
    val installed: Boolean get() = status == "installed"
    val failed: Boolean get() = status == "error"
}
