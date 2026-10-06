package io.openflux.desktop.service

import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.ScriptUpdateReport
import io.openflux.desktop.model.UpdateChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Checks installed script transports for updates and applies them. Every
 * decision (is it newer, is it signed by the pinned key, does the wire
 * change, may it install without asking) is the core's; this class only
 * calls it, remembers the answers for the screen and refreshes the records.
 */
class ScriptUpdater(
    private val scripts: ScriptRepository,
    private val platform: PlatformServices,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _reports = MutableStateFlow<Map<String, ScriptUpdateReport>>(emptyMap())
    /** The latest check/apply answer per script id. */
    val reports: StateFlow<Map<String, ScriptUpdateReport>> = _reports

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    /** Script ids with a check, update or rollback running. */
    val busy: StateFlow<Set<String>> = _busy

    private val channel: String
        get() = if (settings.settings.value.updateChannel == UpdateChannel.Nightly) "nightly" else "stable"

    private fun installedJson(s: InstalledScript): String = buildJsonObject {
        put("id", s.packageId.ifBlank { s.id })
        put("file", s.fileName)
        put("version", s.version)
        put("wire", s.wire)
        put("pubkey", s.pubkeyHex)
        put("update", JsonArray(s.updateUrls.map { JsonPrimitive(it) }))
    }.toString()

    private fun decode(raw: String): ScriptUpdateReport =
        runCatching { json.decodeFromString<ScriptUpdateReport>(raw) }.getOrDefault(ScriptUpdateReport(status = "error", code = "bad_answer"))

    private suspend fun <T> tracked(id: String, block: suspend () -> T): T {
        _busy.update { it + id }
        try { return block() } finally { _busy.update { it - id } }
    }

    /** What an older install lacks (package id, update addresses) is read from its file once. */
    private fun ready(id: String): InstalledScript? {
        val s = scripts.byId(id) ?: return null
        return if (s.packageId.isBlank() && s.fileName.endsWith(".flux")) scripts.refresh(id) ?: s else s
    }

    suspend fun check(id: String): ScriptUpdateReport = tracked(id) {
        val s = ready(id) ?: return@tracked ScriptUpdateReport(status = "error", code = "gone")
        if (!s.updatable) return@tracked ScriptUpdateReport(status = "error", code = "no_source").also { _reports.update { m -> m + (id to it) } }
        val report = withContext(Dispatchers.Default) { decode(platform.checkScriptUpdate(installedJson(s), channel)) }
        _reports.update { it + (id to report) }
        report
    }

    /** Checks every updatable transport; returns how many have an update waiting. */
    suspend fun checkAll(): Int {
        scripts.scripts.value.filter { it.enabled && it.fileName.endsWith(".flux") }.forEach { runCatching { check(it.id) } }
        return _reports.value.values.count { it.available || it.blocked }
    }

    suspend fun apply(id: String, allowWireBreak: Boolean = false): ScriptUpdateReport = tracked(id) {
        val s = ready(id) ?: return@tracked ScriptUpdateReport(status = "error", code = "gone")
        val report = withContext(Dispatchers.Default) {
            decode(platform.applyScriptUpdate(installedJson(s), channel, scripts.dirPath, allowWireBreak))
        }
        if (report.installed) {
            // An official transport may move to another official key: the core
            // checked it, the record has to follow or the file would no longer load.
            if (report.newKey.isNotBlank()) scripts.repin(id, report.newKey, platform.scriptFingerprint(report.newKey))
            scripts.refresh(id)
            _reports.update { it - id }
        } else {
            _reports.update { it + (id to report) }
        }
        report
    }

    suspend fun rollback(id: String): ScriptUpdateReport = tracked(id) {
        val s = ready(id) ?: return@tracked ScriptUpdateReport(status = "error", code = "gone")
        val report = withContext(Dispatchers.Default) { decode(platform.rollbackScript(installedJson(s), scripts.dirPath)) }
        if (report.installed) {
            scripts.refresh(id)
            _reports.update { it - id }
        }
        report
    }

    /**
     * Once a day: check everything and install what the core says needs no
     * question (first-party, same wire, same key) when the user left that on.
     * Everything else only shows as an update waiting on the Transports screen.
     */
    suspend fun autoCheck() {
        val now = platform.now()
        if (now - settings.settings.value.scriptsCheckedAt < DAY_MS) return
        settings.update { it.copy(scriptsCheckedAt = now) }
        checkAll()
        if (!settings.settings.value.autoUpdateScripts) return
        _reports.value.filterValues { it.available && it.autoOk }.keys.toList().forEach { runCatching { apply(it) } }
    }

    private companion object { const val DAY_MS = 24L * 60 * 60 * 1000 }
}
