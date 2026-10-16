package io.openflux.desktop

import io.openflux.desktop.data.RedirectSignIn
import io.openflux.desktop.model.BotException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedirectSignInTest {
    private val redirect = "io.openflux.client:/oauth2redirect"
    private val opened = mutableListOf<String>()
    private val s = RedirectSignIn("openflux-android", redirect, open = { opened += it })

    @Test
    fun theWayBackIsTheRegisteredAddress() = runBlocking { assertEquals(redirect, s.begin("st").redirectUri) }

    @Test
    fun aReturnOfThisAttemptIsTakenAndHandedOver() = runBlocking {
        val way = s.begin("st")
        assertTrue(s.deliver("$redirect?code=c&state=st"))
        assertEquals("$redirect?code=c&state=st", withTimeout(2_000) { way.await() })
    }

    @Test
    fun aReturnOfAnotherStateIsConsumedButNeverHandedOver() = runBlocking {
        val way = s.begin("st")
        assertTrue(s.deliver("$redirect?code=old&state=old"), "it is ours (the scheme), so nothing else should take it")
        assertNull(withTimeoutOrNull(200) { way.await() })
    }

    @Test
    fun aReturnWithNoAttemptWaitingIsDropped() = runBlocking {
        assertTrue(s.deliver("$redirect?code=c&state=st"))
        val way = s.begin("st")
        assertNull(withTimeoutOrNull(200) { way.await() }, "a return from before this attempt began is gone")
        way.close()
        assertTrue(s.deliver("$redirect?code=c&state=st"))
    }

    @Test
    fun otherLinksAreNotTaken() {
        assertFalse(s.deliver("openflux://abc"))
        assertFalse(s.deliver("https://link.openflux.dev/AAAAAAAAAAAAAAAAAAAA"))
        assertFalse(s.deliver("io.openflux.client:/oauth2redirectX?code=c"))
    }

    @Test
    fun aNewerSignInSupersedesTheOlder() = runBlocking {
        val old = s.begin("old")
        val new = s.begin("new")
        assertEquals("superseded", assertFailsWith<BotException> { old.await() }.code)
        old.close()
        assertTrue(s.deliver("$redirect?code=c&state=new"))
        assertEquals("$redirect?code=c&state=new", withTimeout(2_000) { new.await() })
    }

    @Test
    fun thePageOpensThroughTheGivenOpener() {
        s.openPage("https://id.openflux.dev/x")
        assertEquals(listOf("https://id.openflux.dev/x"), opened)
    }
}
