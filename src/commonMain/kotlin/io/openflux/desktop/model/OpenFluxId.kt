package io.openflux.desktop.model

/** One sign-in's secrets; never logged, printed or saved. */
class AuthAttempt(val state: String, val verifier: String, val challenge: String) {
    override fun toString() = "AuthAttempt(…)"
}

/** What the browser came back with. */
sealed interface AuthAnswer {
    data class Code(val code: String) : AuthAnswer {
        override fun toString() = "Code(…)"
    }
    /** The sign-in page said no (error=…), e.g. access_denied. */
    data class Refused(val error: String) : AuthAnswer
    /** Not this attempt's answer (another state, no code): ignored. */
    data object Foreign : AuthAnswer
}

/**
 * The OpenFlux sign-in page: Keycloak on id.openflux.dev, realm "openflux" (email and password, or Telegram).
 * The app opens it with PKCE S256 and hands the one-time code to our service, which redeems it (docs/ru/partner-api.md
 * of nevex-net/openflux); the app never sees Keycloak's tokens.
 */
object OpenFluxId {
    const val ISSUER = "https://id.openflux.dev/realms/openflux"
    /** The Keycloak clients of our apps (not secrets); the service's owner registers them with their return addresses. */
    const val DESKTOP_CLIENT = "openflux-desktop"
    const val ANDROID_CLIENT = "openflux-android"
    private const val MAX_CODE = 512

    /** The sign-in page for one attempt: exactly the parameters the service documents, nothing else. */
    fun authorizeUrl(clientId: String, redirectUri: String, attempt: AuthAttempt, issuer: String = ISSUER): String =
        issuer.trimEnd('/') + "/protocol/openid-connect/auth?" + listOf(
            "client_id" to clientId,
            "response_type" to "code",
            "scope" to "openid",
            "redirect_uri" to redirectUri,
            "state" to attempt.state,
            "code_challenge" to attempt.challenge,
            "code_challenge_method" to "S256",
        ).joinToString("&") { (k, v) -> "$k=${encode(v)}" }

    /** Reads the address the browser came back on; [state] is the attempt's own. */
    fun readReturn(url: String, state: String): AuthAnswer {
        val query = url.substringBefore('#').substringAfter('?', "")
        val params = query.split('&').filter { it.isNotEmpty() }.associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
        if (params["state"] != state) return AuthAnswer.Foreign
        params["error"]?.let { return AuthAnswer.Refused(it) }
        val code = params["code"].orEmpty()
        return if (code.isEmpty() || code.length > MAX_CODE) AuthAnswer.Foreign else AuthAnswer.Code(code)
    }

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val HEX = "0123456789ABCDEF"

    internal fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            if (c < 128 && c.toChar() in UNRESERVED) append(c.toChar()) else append('%').append(HEX[c shr 4]).append(HEX[c and 15])
        }
    }

    internal fun decode(s: String): String {
        val out = StringBuilder(s.length)
        val pending = ArrayList<Byte>()
        fun flush() {
            if (pending.isNotEmpty()) { out.append(pending.toByteArray().decodeToString()); pending.clear() }
        }
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val hex = if (ch == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).takeIf { h -> h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } } else null
            when {
                hex != null -> { pending += hex.toInt(16).toByte(); i += 3 }
                else -> { flush(); out.append(if (ch == '+') ' ' else ch); i++ }
            }
        }
        flush()
        return out.toString()
    }
}
