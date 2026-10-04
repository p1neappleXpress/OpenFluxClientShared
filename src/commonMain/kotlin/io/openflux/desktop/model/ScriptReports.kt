package io.openflux.desktop.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Takes what the core's trust report ([io.openflux.desktop.service.PlatformServices.inspectTransport])
 * says about the installed file: its version and, for a .flux, the package
 * id, wire generation and update addresses the update check needs.
 */
fun InstalledScript.withTrustReport(report: JsonObject): InstalledScript = copy(
    version = report["version"]?.jsonPrimitive?.contentOrNull ?: version,
    packageId = report["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    wire = report["wire"]?.jsonPrimitive?.intOrNull ?: 1,
    updateUrls = (report["update"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
)
