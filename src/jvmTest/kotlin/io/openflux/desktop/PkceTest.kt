package io.openflux.desktop

import io.openflux.desktop.data.Pkce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PkceTest {
    @Test
    fun theChallengeMatchesRfc7636() =
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))

    @Test
    fun anAttemptHasAVerifierTheServiceAccepts() {
        val a = Pkce.newAttempt()
        assertTrue(a.verifier.length in 43..128)
        assertTrue(a.verifier.all { it.isLetterOrDigit() && it.code < 128 || it in "-._~" })
        assertEquals(Pkce.challengeOf(a.verifier), a.challenge)
        assertTrue(a.state.length >= 16 && a.state.all { it.isLetterOrDigit() && it.code < 128 || it in "-_" })
    }

    @Test
    fun attemptsDiffer() {
        val a = Pkce.newAttempt()
        val b = Pkce.newAttempt()
        assertNotEquals(a.verifier, b.verifier)
        assertNotEquals(a.state, b.state)
    }
}
