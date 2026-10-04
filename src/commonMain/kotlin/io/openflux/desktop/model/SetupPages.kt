package io.openflux.desktop.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * What both apps do with a script transport's own setup page (see
 * transport/script/setuppage.go and devhost in the core: the contract and its
 * reference). The core decides which pages are the script's own
 * ([CaptchaPrompt.own]); this is only how the app handles one.
 */
object SetupPages {
    /** Fired on window once window.openfluxSubmit is defined. */
    const val READY_EVENT = "openflux-ready"

    /**
     * The snippet that defines window.openfluxSubmit(payload) and fires
     * [READY_EVENT], for a bridge whose native side is called by
     * [callNative] (an expression taking the JSON text `j`, e.g.
     * `window.OpenFluxSubmit.submit(j)`). It does nothing if the function is
     * already there, so it can be put in twice: before the page's scripts
     * (an inline page) and again when the page has loaded (a page the
     * script serves itself, which cannot be edited on the way).
     */
    fun bridgeScript(callNative: String): String =
        "(function(){if(window.openfluxSubmit)return;" +
            "window.openfluxSubmit=function(p){try{var j=JSON.stringify(p);$callNative}catch(e){}};" +
            "setTimeout(function(){try{window.dispatchEvent(new Event('$READY_EVENT'))}catch(e){}},0)})();"

    /**
     * Puts [snippet] (a script, no tags) into an HTML document where it runs
     * before the page's own scripts and the page stays in standards mode:
     * after <head>, else after <html>, else after the doctype, else in front.
     * A script in front of the doctype would throw the page into quirks
     * mode. The core's devhost.InjectSnippet does the same.
     */
    fun inject(page: String, snippet: String): String {
        val tag = "<script>$snippet</script>"
        val lower = asciiLower(page)
        for (open in listOf("<head", "<html")) {
            val i = lower.indexOf(open)
            if (i < 0) continue
            val end = i + open.length
            // The tag must really be the tag (<header is not <head).
            if (end < lower.length && lower[end] in " \t\n\r>/") {
                val close = lower.indexOf('>', end)
                if (close >= 0) return page.substring(0, close + 1) + tag + page.substring(close + 1)
            }
        }
        if (lower.trimStart().startsWith("<!doctype")) {
            val close = lower.indexOf('>')
            if (close >= 0) return page.substring(0, close + 1) + tag + page.substring(close + 1)
        }
        return tag + page
    }

    /**
     * What a setup or settings page handed to window.openfluxSubmit, as the
     * {name: value} map the script receives. The rules are the core's
     * (script.FlattenSubmission): a flat object or {client: {...}, node: {...}}
     * (the client half is delivered); strings, numbers and booleans become
     * strings; null, objects and arrays are dropped; nothing left is an error.
     */
    fun flatten(json: String): Map<String, String> {
        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Страница настройки передала не JSON-объект")
        }
        val scoped = (root["client"] as? JsonObject) ?: root
        val jar = scoped.entries
            .filter { it.key.isNotEmpty() }
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { k to it.content } }
            .toMap()
        require(jar.isNotEmpty()) { "Страница настройки не передала данных" }
        return jar
    }

    /** A-Z only, so every index into the result is an index into [s]. */
    private fun asciiLower(s: String): String {
        val out = CharArray(s.length) { val c = s[it]; if (c in 'A'..'Z') c + 32 else c }
        return out.concatToString()
    }

    /**
     * "scheme://host:port/" of an http page on loopback (the script's own
     * server), the prefix a page's address must keep for the bridge to stay
     * with it; null for anything else.
     */
    fun loopbackOrigin(url: String): String? {
        val m = LOOPBACK.matchEntire(url.trim()) ?: return null
        val port = m.groupValues[2].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return "http://${m.groupValues[1]}:$port/"
    }

    private val LOOPBACK = Regex("""(?i)http://(127\.\d{1,3}\.\d{1,3}\.\d{1,3}|localhost|\[::1\]):(\d{1,5})(?:[/?#].*)?""")
}
