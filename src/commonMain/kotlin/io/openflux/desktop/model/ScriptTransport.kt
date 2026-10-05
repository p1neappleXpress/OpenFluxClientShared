package io.openflux.desktop.model

import kotlinx.serialization.Serializable

/** One choice of a select param. */
@Serializable
data class ScriptOption(val value: String, val label: String = "") {
    val shown: String get() = label.ifBlank { value }
}

/**
 * One input a script transport asks for, from its info().params (see
 * transport/script.Param in the core, which also resolves [scope]).
 */
@Serializable
data class ScriptParam(
    val key: String,
    val label: String = "",
    /** "url" | "text" | "secret" | "number" | "boolean" | "select" | "textarea". */
    val type: String = "text",
    val required: Boolean = false,
    /** "profile" (the profile editor's value field) or "settings" (the wizard); "" for a record older than the field. */
    val scope: String = "",
    val default: String = "",
    val description: String = "",
    val placeholder: String = "",
    val options: List<ScriptOption> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val pattern: String = "",
    val group: String = "",
    val advanced: Boolean = false,
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
 *
 * Its saved VALUES (what the user typed in the profile's field or the
 * settings wizard) are not here: a script is installed once but used by any
 * number of profiles/carriers, each with its own, so they live on the carrier
 * itself ([ExtraTransport.settings], [Profile.settings]) and reach the core
 * from there - one record per carrier, not a global one shared by all of them.
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
    /** The script brings a settings page of its own (Transport.settings), so it has settings even with none declared. */
    val settingsPage: Boolean = false,
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

    /**
     * The main input shown on the profile's value field. The core resolves each
     * param's scope; a record from before that (no scope anywhere) keeps the old
     * rule: the first param.
     */
    val primaryParam: ScriptParam?
        get() = if (params.any { it.scope.isNotEmpty() }) params.firstOrNull { it.scope == "profile" } else params.firstOrNull()

    /**
     * [params] besides [primaryParam] - "the other settings" for a summary
     * next to the profile's own field. The wizard page itself asks for every
     * param, [primaryParam] included: both it and the profile editor's field
     * read and write the one value a profile (or, extra carrier) keeps for it.
     */
    val settingParams: List<ScriptParam>
        get() = if (params.any { it.scope.isNotEmpty() }) params.filter { it.scope == "settings" } else params.drop(1)

    /** Whether there is anything the wizard can open - even just [primaryParam]. */
    val hasSettings: Boolean get() = params.isNotEmpty() || settingsPage

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
