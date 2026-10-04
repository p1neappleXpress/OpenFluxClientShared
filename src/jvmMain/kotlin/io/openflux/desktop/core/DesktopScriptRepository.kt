package io.openflux.desktop.core

import io.openflux.desktop.data.AppDirs
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.ScriptCarrierLookup
import io.openflux.desktop.model.ScriptParam
import io.openflux.desktop.model.ScriptSource
import io.openflux.desktop.model.withTrustReport
import io.openflux.desktop.service.PlatformServices
import io.openflux.desktop.service.ScriptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * File-backed registry of installed JS script transports, mirroring the
 * Android app's AndroidScriptRepository: the signed script files live in
 * [dir], registry.json lists them. Installing is gated on the core's own
 * signature check ([PlatformServices.inspectTransport], which on desktop
 * shells out to `--inspect-script` - see JvmPlatformServices), and the
 * pinned key is kept so the core re-verifies on every load.
 */
class DesktopScriptRepository(private val platform: PlatformServices) : ScriptRepository {
    val dir: File = File(AppDirs.config, "scripts").apply { mkdirs() }
    private val registryFile = File(dir, "registry.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _scripts = MutableStateFlow(load())
    override val scripts: StateFlow<List<InstalledScript>> = _scripts

    override fun upsert(script: InstalledScript) {
        _scripts.value = _scripts.value.filterNot { it.id == script.id } + script
        persist()
    }

    override fun delete(id: String) {
        _scripts.value.firstOrNull { it.id == id }?.let { s ->
            File(dir, s.fileName).delete()
            File(dir, s.fileName + ".sig").delete()
            File(dir, s.fileName + ".prev").delete()
        }
        _scripts.value = _scripts.value.filterNot { it.id == id }
        persist()
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        _scripts.value = _scripts.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        persist()
    }

    /** The on-disk file + pinned key for a carrier, resolved for CoreConfig; null when the script is gone. */
    fun carrier(id: String): ScriptCarrierLookup? = byId(id)?.let {
        ScriptCarrierLookup(path = File(dir, it.fileName).absolutePath, pubkeyHex = it.pubkeyHex, name = it.id)
    }

    /**
     * Verifies [data] (a .flux or bare .js) against [pubkeyHex] through the
     * core ([PlatformServices.inspectTransport]), and only if it reports a
     * valid signature writes it to [dir] and records it. Returns the
     * installed script, or throws with a reason for the trust dialog to show.
     */
    override fun install(data: ByteArray, sig: ByteArray, pubkeyHex: String, source: ScriptSource, origin: String, now: Long): InstalledScript {
        val report = json.parseToJsonElement(platform.inspectTransport(data, sig, pubkeyHex.trim())).jsonObject
        if (report["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw IllegalArgumentException(report["error"]?.jsonPrimitive?.contentOrNull ?: "скрипт не читается")
        }
        if (report["signature"]?.jsonPrimitive?.contentOrNull != "valid") {
            throw IllegalArgumentException("подпись не совпадает с ключом автора")
        }
        val name = report["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "script"
        val isFlux = data.size >= 2 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()
        val fileName = if (isFlux) "$name.flux" else "$name.js"
        File(dir, fileName).writeBytes(data)
        if (!isFlux) File(dir, "$fileName.sig").writeBytes(sig)

        val script = InstalledScript(
            id = name,
            name = name,
            version = report["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            pubkeyHex = pubkeyHex.trim(),
            fingerprint = report["fingerprint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            fileName = fileName,
            icon = io.openflux.desktop.model.TransportType.fromCli(name)?.icon ?: "ic_code",
            official = report["official"]?.jsonPrimitive?.booleanOrNull == true,
            params = parseParams(report["params"]),
            source = source,
            origin = origin,
            addedAt = now,
        ).withTrustReport(report)
        upsert(script)
        return script
    }

    override val dirPath: String get() = dir.absolutePath

    override fun hasPrevious(id: String): Boolean = byId(id)?.let { File(dir, it.fileName + ".prev").exists() } == true

    override fun refresh(id: String): InstalledScript? {
        val s = byId(id) ?: return null
        val file = File(dir, s.fileName)
        if (!file.exists()) return null
        val sig = File(dir, s.fileName + ".sig").takeIf { it.exists() }?.readBytes() ?: ByteArray(0)
        val report = runCatching { json.parseToJsonElement(platform.inspectTransport(file.readBytes(), sig, s.pubkeyHex)).jsonObject }.getOrNull() ?: return s
        if (report["ok"]?.jsonPrimitive?.booleanOrNull != true || report["signature"]?.jsonPrimitive?.contentOrNull != "valid") return s
        val updated = s.withTrustReport(report)
        _scripts.value = _scripts.value.map { if (it.id == id) updated else it }
        persist()
        return updated
    }

    private fun parseParams(el: kotlinx.serialization.json.JsonElement?): List<ScriptParam> {
        val arr = (el as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.map { o ->
            ScriptParam(
                key = o["key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                label = o["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                type = o["type"]?.jsonPrimitive?.contentOrNull ?: "text",
                required = o["required"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
    }

    private fun load(): List<InstalledScript> = runCatching {
        if (!registryFile.exists()) return emptyList()
        json.decodeFromString<List<InstalledScript>>(registryFile.readText())
            .filter { File(dir, it.fileName).exists() }
    }.getOrDefault(emptyList())

    private fun persist() {
        runCatching { registryFile.writeText(json.encodeToString(_scripts.value)) }
    }
}
