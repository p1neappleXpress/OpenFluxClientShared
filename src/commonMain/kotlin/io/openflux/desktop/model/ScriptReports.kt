package io.openflux.desktop.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Takes what the core's trust report ([io.openflux.desktop.service.PlatformServices.inspectTransport])
 * says about the installed file: its version and, for a .flux, the package
 * id, wire generation and update addresses the update check needs.
 */
fun InstalledScript.withTrustReport(report: JsonObject): InstalledScript = copy(
    version = report["version"]?.jsonPrimitive?.contentOrNull ?: version,
    // An update may declare new settings: the record follows the file.
    params = if (report["params"] != null) parseScriptParams(report["params"]) else params,
    settingsPage = report["settingsPage"]?.jsonPrimitive?.booleanOrNull == true,
    packageId = report["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    wire = report["wire"]?.jsonPrimitive?.intOrNull ?: 1,
    updateUrls = (report["update"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
)

/** The params of a core trust or settings report ("params": [...]); anything unreadable is skipped. */
fun parseScriptParams(el: JsonElement?): List<ScriptParam> {
    val arr = (el as? JsonArray) ?: return emptyList()
    return arr.mapNotNull { it as? JsonObject }.map { o ->
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
        ScriptParam(
            key = str("key"),
            label = str("label"),
            type = str("type").ifBlank { "text" },
            required = o["required"]?.jsonPrimitive?.booleanOrNull == true,
            scope = str("scope"),
            default = str("default"),
            description = str("description"),
            placeholder = str("placeholder"),
            options = (o["options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .map { ScriptOption(str2(it, "value"), str2(it, "label")) },
            min = (o["min"] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull,
            max = (o["max"] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull,
            pattern = str("pattern"),
            group = str("group"),
            advanced = o["advanced"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }
}

private fun str2(o: JsonObject, k: String): String =
    (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
