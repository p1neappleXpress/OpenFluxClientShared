package io.openflux.desktop.data

import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.ScriptSource
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.parseScriptParams
import io.openflux.desktop.model.withTrustReport
import io.openflux.desktop.service.ScriptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * File-backed registry of installed JS script transports, the one both apps
 * use (they used to carry two copies that differed only in how the core is
 * asked): the signed script files live in [dir], registry.json lists them.
 * Installing is gated on the core's own signature check ([inspect]: in-process
 * on Android, the core binary's `--inspect-script` on the desktop), and the
 * pinned key is kept so the core re-verifies on every load.
 */
abstract class FileScriptRepository(
    val dir: File,
    /** Asks the core to read a transport: (data, detached signature, candidate key) -> its JSON trust report. */
    private val inspect: (ByteArray, ByteArray, String) -> String,
) : ScriptRepository {
    init { dir.mkdirs() }

    private val registryFile = File(dir, "registry.json")
    protected val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _scripts = MutableStateFlow(load())
    override val scripts: StateFlow<List<InstalledScript>> = _scripts

    override val dirPath: String get() = dir.absolutePath

    /** The core's trust report for a file, null when it could not be read as JSON. */
    protected fun report(data: ByteArray, sig: ByteArray, pubkeyHex: String): JsonObject? =
        runCatching { json.parseToJsonElement(inspect(data, sig, pubkeyHex)).jsonObject }.getOrNull()

    /** Whether a report says the transport reads and is signed by the key it was checked against. */
    protected fun JsonObject.isValid(): Boolean =
        this["ok"]?.jsonPrimitive?.booleanOrNull == true && this["signature"]?.jsonPrimitive?.contentOrNull == "valid"

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

    /**
     * Verifies [data] (a .flux or bare .js) against [pubkeyHex] through the
     * core, and only if it reports a valid signature writes it to [dir] and
     * records it. Returns the installed script, or throws with a reason for
     * the trust dialog to show.
     */
    override fun install(data: ByteArray, sig: ByteArray, pubkeyHex: String, source: ScriptSource, origin: String, now: Long): InstalledScript {
        val report = report(data, sig, pubkeyHex.trim()) ?: throw IllegalArgumentException("скрипт не читается")
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
            icon = TransportType.fromCli(name)?.icon ?: "ic_code",
            official = report["official"]?.jsonPrimitive?.booleanOrNull == true,
            params = parseScriptParams(report["params"]),
            source = source,
            origin = origin,
            addedAt = now,
        ).withTrustReport(report)
        upsert(script)
        return script
    }

    override fun repin(id: String, pubkeyHex: String, fingerprint: String) {
        _scripts.value = _scripts.value.map {
            if (it.id == id) it.copy(pubkeyHex = pubkeyHex, fingerprint = fingerprint) else it
        }
        persist()
    }

    override fun packageBytes(id: String): Pair<ByteArray, ByteArray>? {
        val s = byId(id) ?: return null
        val file = File(dir, s.fileName).takeIf { it.exists() } ?: return null
        val sig = File(dir, s.fileName + ".sig").takeIf { it.exists() }?.readBytes() ?: ByteArray(0)
        return file.readBytes() to sig
    }

    override fun hasPrevious(id: String): Boolean = byId(id)?.let { File(dir, it.fileName + ".prev").exists() } == true

    override fun refresh(id: String): InstalledScript? {
        val s = byId(id) ?: return null
        val file = File(dir, s.fileName)
        if (!file.exists()) return null
        val sig = File(dir, s.fileName + ".sig").takeIf { it.exists() }?.readBytes() ?: ByteArray(0)
        val report = report(file.readBytes(), sig, s.pubkeyHex) ?: return s
        if (!report.isValid()) return s
        val updated = s.withTrustReport(report)
        _scripts.value = _scripts.value.map { if (it.id == id) updated else it }
        persist()
        return updated
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
