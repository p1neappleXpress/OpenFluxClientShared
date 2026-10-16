package io.openflux.desktop

import io.openflux.desktop.data.LoopbackSignIn
import io.openflux.desktop.model.BotException
import io.openflux.desktop.service.SignInWay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopbackSignInTest {
    private val held = mutableListOf<ServerSocket>()
    private val ways = mutableListOf<SignInWay>()

    @AfterTest
    fun cleanUp() { ways.forEach { it.close() }; held.forEach { it.close() } }

    /** Ports free right now (taken and let go), for tests that name their ports. */
    private fun freePorts(n: Int) = List(n) { ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort } }
    private fun signIn(ports: List<Int>) = LoopbackSignIn("openflux-desktop", ports, open = {})
    private suspend fun LoopbackSignIn.start(state: String = "st") = begin(state).also { ways += it }
    private fun hold(port: Int) { held += ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")) }
    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        return try { c.responseCode to (if (c.responseCode < 400) c.inputStream else c.errorStream)?.readBytes()?.decodeToString().orEmpty() } finally { c.disconnect() }
    }

    @Test
    fun theFirstFreePortIsTheWayBack() = runBlocking {
        val (a, b) = freePorts(2)
        hold(a)
        assertEquals("http://127.0.0.1:$b/callback", signIn(listOf(a, b)).start().redirectUri)
    }

    @Test
    fun theBrowsersReturnIsHandedOverAndAnsweredWithAPage() = runBlocking {
        val way = signIn(freePorts(1)).start("st")
        val (code, page) = get("${way.redirectUri}?state=st&code=c1")
        assertEquals(200, code)
        assertTrue(page.contains("OpenFlux"))
        assertEquals("${way.redirectUri}?state=st&code=c1", withTimeout(5_000) { way.await() })
    }

    @Test
    fun aReturnOfAnotherStateIsRefusedAndNeverHandedOver() = runBlocking {
        val way = signIn(freePorts(1)).start("st")
        assertEquals(400, get("${way.redirectUri}?state=old&code=old").first)
        assertEquals(400, get("${way.redirectUri}?code=nostate").first)
        assertNull(withTimeoutOrNull(300) { way.await() })
        // junk cannot push the real return out
        assertEquals(200, get("${way.redirectUri}?state=st&code=real").first)
        assertEquals(400, get("${way.redirectUri}?state=junk&code=junk").first)
        assertEquals("${way.redirectUri}?state=st&code=real", withTimeout(5_000) { way.await() })
    }

    @Test
    fun otherPathsAreNotTheWayBack() = runBlocking {
        val way = signIn(freePorts(1)).start()
        val root = way.redirectUri.removeSuffix("/callback")
        assertEquals(404, get("$root/favicon.ico").first)
        assertEquals(404, get("$root/callbackX?state=st&code=c").first)
        assertNull(withTimeoutOrNull(300) { way.await() })
    }

    @Test
    fun allPortsBusyIsSaidInWords() = runBlocking {
        val ports = freePorts(2)
        ports.forEach(::hold)
        val e = assertFailsWith<BotException> { signIn(ports).start() }
        assertEquals("no_return", e.code)
        assertTrue(e.message!!.contains("порт"))
    }

    @Test
    fun byDefaultTheSystemGivesThePortAndTheReturnComesThere() = runBlocking {
        val way = LoopbackSignIn("openflux-desktop", open = {}).start("st")
        val port = way.redirectUri.removePrefix("http://127.0.0.1:").substringBefore("/").toInt()
        assertTrue(port > 0 && port !in 47621..47625, "the system's port, not a registered one: $port")
        // two sign-ins at once never meet on one port
        val other = LoopbackSignIn("openflux-desktop", open = {}).start("st2")
        assertTrue(other.redirectUri != way.redirectUri)
        assertEquals(200, get("${way.redirectUri}?state=st&code=c").first)
        assertEquals("${way.redirectUri}?state=st&code=c", withTimeout(5_000) { way.await() })
    }

    @Test
    fun closingFreesThePort() = runBlocking {
        val port = freePorts(1).single()
        val way = signIn(listOf(port)).start()
        way.close()
        way.close() // twice is harmless
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
    }

    @Test
    fun aNewerSignInSupersedesTheOlderWithoutTouchingIt() = runBlocking {
        val (a, b) = freePorts(2)
        val s = signIn(listOf(a, b))
        val old = s.start("old")
        val new = s.start("new")
        assertEquals("superseded", assertFailsWith<BotException> { old.await() }.code)
        old.close() // the older screen's cleanup must not stop the newer one's listener
        assertEquals(200, get("${new.redirectUri}?state=new&code=c").first)
        assertEquals("${new.redirectUri}?state=new&code=c", withTimeout(5_000) { new.await() })
    }

    @Test
    fun thePageIsWhollyAnsweredEvenWhenTheWayIsClosedAtOnce() = runBlocking {
        val s = signIn(freePorts(1))
        repeat(25) { i ->
            val way = s.start("st$i")
            var result: Pair<Int, String>? = null
            val t = thread { result = runCatching { get("${way.redirectUri}?state=st$i&code=c") }.getOrNull() }
            withTimeout(5_000) { way.await() }
            way.close() // what the sign-in does the moment the return arrives
            t.join(5_000)
            assertEquals(200, result?.first, "attempt $i: the browser got the page")
            assertTrue(result!!.second.contains("OpenFlux"), "attempt $i")
        }
    }
}
