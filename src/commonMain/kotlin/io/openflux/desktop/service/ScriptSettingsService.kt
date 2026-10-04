package io.openflux.desktop.service

import io.openflux.desktop.model.ScriptSettingsMessages
import io.openflux.desktop.model.ScriptSettingsPage
import io.openflux.desktop.model.SetupPages
import io.openflux.desktop.ui.BrowserPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Hosts a script's own page in the platform's built-in browser (a WebView, a KCEF page). */
interface SettingsPageHost {
    /**
     * Opens [html] as a script's own page. [onSubmit] gets the JSON the page
     * hands to window.openfluxSubmit, and only this page's: it is not heard by
     * the connection's own setup requests.
     */
    suspend fun open(html: String, onStep: (String) -> Unit, onSubmit: (String) -> Unit): BrowserPage

    /** Closes a page [open] returned. */
    fun close(page: BrowserPage)
}

/** For platforms without a built-in browser: settings cannot be opened there. */
object NoSettingsPageHost : SettingsPageHost {
    override suspend fun open(html: String, onStep: (String) -> Unit, onSubmit: (String) -> Unit): BrowserPage =
        throw UnsupportedOperationException("На этой платформе нет встроенного браузера")

    override fun close(page: BrowserPage) {}
}

/** The settings dialog's state: a script's wizard page being loaded, shown, or why it cannot be. */
data class ScriptSettingsState(
    val scriptId: String,
    val title: String,
    val page: BrowserPage? = null,
    /** The built-in browser getting ready (first-run download). */
    val progress: String = "Открываю настройки…",
    val error: String = "",
)

/**
 * The "Настройки" of an installed script transport. The core builds the page
 * (the script's own, else the form generated from what the script declares),
 * the platform's built-in browser shows it, and what it submits is saved with
 * the script: the script gets it as cfg.params the next time it starts. It
 * never involves a running connection.
 */
class ScriptSettingsService(
    private val scripts: ScriptRepository,
    private val platform: PlatformServices,
    private val host: SettingsPageHost,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow<ScriptSettingsState?>(null)
    val state: StateFlow<ScriptSettingsState?> = _state.asStateFlow()

    private var token = 0

    fun open(scriptId: String) {
        val script = scripts.byId(scriptId) ?: return
        close()
        val mine = ++token
        _state.value = ScriptSettingsState(scriptId, script.name)
        scope.launch {
            val answer = withContext(Dispatchers.IO) {
                val pkg = scripts.packageBytes(scriptId)
                    ?: return@withContext ScriptSettingsPage(ok = false, code = "failed", error = "файл скрипта не найден")
                val values = JsonObject(script.settings.mapValues { JsonPrimitive(it.value) })
                ScriptSettingsPage.parse(platform.scriptSettings(pkg.first, pkg.second, script.pubkeyHex, values.toString(), "ru"))
            }
            if (token != mine) return@launch
            if (!answer.ok) {
                _state.update { it?.copy(progress = "", error = ScriptSettingsMessages.text(answer)) }
                return@launch
            }
            val declared = answer.params.map { it.key }.toSet()
            val opened = runCatching {
                host.open(
                    answer.html,
                    onStep = { step -> if (token == mine) _state.update { it?.copy(progress = step) } },
                    onSubmit = { json -> if (token == mine) save(scriptId, json, declared) },
                )
            }
            if (token != mine) {
                opened.getOrNull()?.let(host::close)
                return@launch
            }
            opened.onSuccess { page -> _state.update { it?.copy(page = page, progress = "") } }
                .onFailure { e -> _state.update { it?.copy(progress = "", error = e.message ?: "Не удалось открыть страницу настроек") } }
        }
    }

    /** What the wizard's Save handed over: kept with the script, the dialog closes. */
    private fun save(scriptId: String, json: String, declared: Set<String>) {
        val values = try {
            SetupPages.flatten(json)
        } catch (e: IllegalArgumentException) {
            _state.update { it?.copy(error = e.message.orEmpty()) }
            return
        }
        // Only what the script declares (a custom page may send more; it would only be noise in cfg.params).
        val kept = if (declared.isEmpty()) values else values.filterKeys { it in declared }
        scripts.saveSettings(scriptId, kept)
        close()
    }

    /** Closes the dialog and its page. */
    fun close() {
        token++
        val current = _state.value
        _state.value = null
        current?.page?.let(host::close)
    }
}
