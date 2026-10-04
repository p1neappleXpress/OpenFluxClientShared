package io.openflux.desktop.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The core's answer to "open this script's settings": a page to show, or a
 * code saying why there is none. Codes only; the words are [ScriptSettingsMessages]'s.
 */
data class ScriptSettingsPage(
    val ok: Boolean,
    val code: String = "",
    /** The core's own reason (English, for the log); never shown as the message. */
    val error: String = "",
    val name: String = "",
    val version: String = "",
    /** The script brought its own page (Transport.settings) rather than the generated wizard. */
    val custom: Boolean = false,
    val html: String = "",
    /** The settings the wizard asks for. */
    val params: List<ScriptParam> = emptyList(),
    /** The current values with the declared defaults filled in. */
    val values: Map<String, String> = emptyMap(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ScriptSettingsPage {
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return ScriptSettingsPage(ok = false, code = "failed", error = "ядро ответило не JSON")
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return ScriptSettingsPage(
                ok = (o["ok"] as? JsonPrimitive)?.booleanOrNull == true,
                code = str("code"),
                error = str("error"),
                name = str("name"),
                version = str("version"),
                custom = (o["custom"] as? JsonPrimitive)?.booleanOrNull == true,
                html = str("html"),
                params = parseScriptParams(o["params"]),
                values = (o["values"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty(),
            )
        }
    }
}

/** What the user is told when a script's settings cannot be opened. */
object ScriptSettingsMessages {
    fun text(page: ScriptSettingsPage): String = when (page.code) {
        "no_key" -> "У скрипта нет закреплённого ключа автора: переустановите его"
        "bad_signature" -> "Файл скрипта не совпадает с подписью автора. Переустановите транспорт"
        "no_settings" -> "У этого транспорта нет настроек"
        "needs_newer_app" -> "Транспорту нужна более новая версия приложения"
        else -> "Не удалось открыть настройки: ${page.error.ifBlank { "неизвестная ошибка" }}"
    }
}
