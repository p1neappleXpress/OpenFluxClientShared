package io.openflux.desktop

import io.openflux.desktop.model.AuthAnswer
import io.openflux.desktop.model.AuthAttempt
import io.openflux.desktop.model.OpenFluxId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenFluxIdTest {
    private val attempt = AuthAttempt(state = "st_1", verifier = "v".repeat(43), challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")

    @Test
    fun theSignInPageAddressCarriesExactlyWhatTheServiceAsksFor() {
        assertEquals(
            "https://id.openflux.dev/realms/openflux/protocol/openid-connect/auth" +
                "?client_id=openflux-desktop&response_type=code&scope=openid" +
                "&redirect_uri=http%3A%2F%2F127.0.0.1%3A47621%2Fcallback&state=st_1" +
                "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256",
            OpenFluxId.authorizeUrl("openflux-desktop", "http://127.0.0.1:47621/callback", attempt),
        )
    }

    @Test
    fun theAndroidReturnAddressIsEncodedToo() {
        val url = OpenFluxId.authorizeUrl("openflux-android", "io.openflux.client:/oauth2redirect", attempt)
        assertTrue(url.contains("&redirect_uri=io.openflux.client%3A%2Foauth2redirect&"))
    }

    @Test
    fun aStandInIssuerMovesTheAddress() {
        val url = OpenFluxId.authorizeUrl("c", "http://127.0.0.1:1/callback", attempt, issuer = "http://127.0.0.1:8099/realms/openflux")
        assertTrue(url.startsWith("http://127.0.0.1:8099/realms/openflux/protocol/openid-connect/auth?"))
    }

    @Test
    fun aReturnWithTheCodeIsRead() {
        assertEquals(AuthAnswer.Code("a.b-c"), OpenFluxId.readReturn("http://127.0.0.1:47621/callback?state=st_1&session_state=x&code=a.b-c", "st_1"))
        assertEquals(AuthAnswer.Code("a/b c"), OpenFluxId.readReturn("io.openflux.client:/oauth2redirect?code=a%2Fb+c&state=st_1#frag", "st_1"))
    }

    @Test
    fun anotherAttemptsReturnIsForeign() {
        assertEquals(AuthAnswer.Foreign, OpenFluxId.readReturn("http://127.0.0.1:47621/callback?code=c&state=old", "st_1"))
        assertEquals(AuthAnswer.Foreign, OpenFluxId.readReturn("http://127.0.0.1:47621/callback?code=c", "st_1"))
        assertEquals(AuthAnswer.Foreign, OpenFluxId.readReturn("http://127.0.0.1:47621/callback?state=st_1", "st_1"), "no code")
        assertEquals(AuthAnswer.Foreign, OpenFluxId.readReturn("http://127.0.0.1:47621/callback?state=st_1&code=" + "x".repeat(513), "st_1"))
    }

    @Test
    fun aRefusalIsRead() {
        assertEquals(AuthAnswer.Refused("access_denied"), OpenFluxId.readReturn("http://127.0.0.1:47621/callback?error=access_denied&state=st_1", "st_1"))
    }

    @Test
    fun brokenPercentEscapesDoNotCrash() {
        assertEquals(AuthAnswer.Code("%zz%-1"), OpenFluxId.readReturn("x:/y?state=st_1&code=%zz%-1", "st_1"))
    }

    @Test
    fun anEscapeAtTheVeryEndIsDecoded() {
        assertEquals("A", OpenFluxId.decode("%41"))
        assertEquals("%4", OpenFluxId.decode("%4"))
    }

    @Test
    fun secretsAreNotPrinted() {
        assertFalse(attempt.toString().contains("vvv"))
        assertFalse(AuthAnswer.Code("secret-code").toString().contains("secret-code"))
    }
}
