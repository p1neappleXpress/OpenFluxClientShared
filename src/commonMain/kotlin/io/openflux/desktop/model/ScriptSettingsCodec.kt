package io.openflux.desktop.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64

/**
 * A script's saved settings as one plain line, for a place that cannot hold
 * them as they are: the generated .conf, where a value ends at '#' or ';' and
 * cannot hold a newline (a text setting can). The line is the settings as a
 * JSON object of strings, base64url without padding - what the core's
 * script.DecodeSettings reads (`Params = <line>` in a script's [Transport]).
 */
object ScriptSettingsCodec {
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun encode(values: Map<String, String>): String =
        base64.encode(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString().encodeToByteArray())
}
