package io.openflux.desktop.data

import io.openflux.desktop.model.AuthAttempt
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE S256 (RFC 7636) for the OpenFlux sign-in: a fresh verifier and state per attempt. */
object Pkce {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** 32 random bytes give a 43-character verifier; 16 give the state. */
    fun newAttempt(): AuthAttempt {
        val verifier = token(32)
        return AuthAttempt(state = token(16), verifier = verifier, challenge = challengeOf(verifier))
    }

    fun challengeOf(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun token(bytes: Int): String = b64.encodeToString(ByteArray(bytes).also(random::nextBytes))
}
