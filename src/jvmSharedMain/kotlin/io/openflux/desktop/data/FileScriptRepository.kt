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
    private val offeredFile = File(dir, OFFERED_FILE)
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
        val previous = byId(name)
        File(dir, fileName).writeBytes(data)
        if (!isFlux) File(dir, "$fileName.sig").writeBytes(sig)
        // A bare .js replaced by a .flux (or the other way round) leaves the old file behind.
        if (previous != null && previous.fileName != fileName) {
            File(dir, previous.fileName).delete()
            File(dir, previous.fileName + ".sig").delete()
            File(dir, previous.fileName + ".prev").delete()
        }

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

    /**
     * Brings the scripts shipped inside the app into the registry, on the first
     * run of the experimental features and after every update of the app:
     *  - one this installation was never offered is installed (so a new release
     *    adds its new transports to people who already have the old ones);
     *  - one the user deleted stays deleted (what was offered is remembered in
     *    [OFFERED_FILE]), one they switched off stays off, one they added
     *    themselves under the same name is left alone;
     *  - an installed bundled copy is replaced when the shipped one is newer
     *    ([isNewer]: the shipped version first), or when it is the same version
     *    but arrives as a .flux package, which, unlike a bare .js, says where
     *    its updates come from.
     * [officialKey] is the key the shipped files must verify under.
     */
    protected fun syncShipped(
        shipped: List<ShippedScript>,
        officialKey: String,
        isNewer: (shippedVersion: String, installedVersion: String) -> Boolean = { a, b -> ScriptVersions.compare(a, b) > 0 },
    ) {
        val offered = readOffered()
        if (offered != null) {
            alreadyOffered += offered
        } else if (scripts.value.any { it.source == ScriptSource.Bundled }) {
            // An installation from before the list existed: that build installed the five
            // transports it shipped all at once, so what is missing of them was deleted.
            alreadyOffered += LEGACY_SHIPPED
        }
        for (item in shipped) {
            runCatching {
                val report = report(item.data, item.sig, officialKey)?.takeIf { it.isValid() } ?: return@runCatching
                val name = report["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: return@runCatching
                val version = report["version"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val installed = byId(name)
                if (installed == null) {
                    if (name in alreadyOffered) return@runCatching
                    install(item.data, item.sig, officialKey, ScriptSource.Bundled, "bundled:${item.file}", now = 0L)
                    alreadyOffered += name
                    return@runCatching
                }
                if (installed.source != ScriptSource.Bundled) return@runCatching
                val wrapped = installed.fileName.endsWith(".js") && item.file.endsWith(".flux")
                if (!isNewer(version, installed.version) && !(wrapped && version == installed.version)) return@runCatching
                val wasEnabled = installed.enabled
                install(item.data, item.sig, officialKey, ScriptSource.Bundled, "bundled:${item.file}", now = installed.addedAt)
                if (!wasEnabled) setEnabled(name, false)
            }
        }
        writeOffered()
    }

    private val alreadyOffered = mutableSetOf<String>()

    /** The names this installation was offered, null when it has never recorded them. */
    private fun readOffered(): Set<String>? = runCatching {
        if (!offeredFile.exists()) null else json.decodeFromString<List<String>>(offeredFile.readText()).toSet()
    }.getOrNull()

    private fun writeOffered() {
        runCatching { offeredFile.writeText(json.encodeToString(alreadyOffered.sorted())) }
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

    private companion object {
        /** Where the names of the shipped transports this installation was offered are kept. */
        const val OFFERED_FILE = "bundled-offered.json"

        /** What every build before the offered list shipped. */
        val LEGACY_SHIPPED = listOf("yandex", "vyandex", "boards", "mailru", "cupsonline")
    }
}

/** A transport shipped inside the app: its file (a .flux package, or a bare .js) and the detached signature (empty for a .flux). */
class ShippedScript(val file: String, val data: ByteArray, val sig: ByteArray = ByteArray(0))
