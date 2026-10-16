package io.openflux.app

import com.sun.net.httpserver.HttpServer
import io.openflux.app.account.SignInModel
import io.openflux.app.account.SignInUi
import io.openflux.desktop.FakeShareLinkCodec
import io.openflux.desktop.data.HttpBotApi
import io.openflux.desktop.data.LoopbackSignIn
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole sign-in over real HTTP: the real client, the real way back on 127.0.0.1 and the state machine against a
 * stand-in that answers like the service (exchange, the account's sessions, connect/resolve).
 */
class SignInWireTest {
    private class Seen(val path: String, val headers: Map<String, String>, val body: String) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun query(url: String) = URI(url).rawQuery.split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

    @Test
    fun signingInOnThePageBringsTheConnection() = runBlocking {
        val appId = "ofa_wiretestwiretest"
        val link = FakeShareLinkCodec().encode(
            ShareConfig(
                name = "Заглушка #1", negotiate = true, secret = "ab".repeat(32), context = "https://example.com/doc",
                transports = listOf(ShareTransport(type = "direct", dial = "203.0.113.5:443", priority = 50)),
            ),
        )
        val seen = mutableListOf<Seen>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            synchronized(seen) { seen += Seen(ex.requestURI.path, ex.requestHeaders.mapValues { it.value.first() }, body) }
            val (code, json) = when (ex.requestURI.path) {
                "/api/v1/auth/app/exchange" -> 200 to """{"userId":"7","role":"user","token":"ofs_wire","expiresAt":"1900000000","sessionId":"s1"}"""
                "/api/v1/sessions" -> 200 to """[{"sessionId":"s1","status":"running","connectToken":"WireWireWireWireWireWire"}]"""
                "/api/v1/connect/resolve" -> 200 to """{"name":"Заглушка #1","link":${JsonPrimitive(link)}}"""
                "/api/v1/subscription" -> 200 to """{"planId":"own30","planName":"Заглушка","status":"active","daysLeft":20,"isActive":true}"""
                "/api/v1/auth/logout" -> 200 to "{}"
                else -> 404 to "{}"
            }
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        // The "browser": the page signs the person in at once and returns to the app's address.
        val browser = LoopbackSignIn("openflux-desktop", listOf(port), open = { page ->
            val q = query(page)
            thread {
                val c = URL("${q["redirect_uri"]}?state=${q["state"]}&code=wirecode").openConnection() as HttpURLConnection
                c.responseCode
                c.disconnect()
            }
        })
        try {
            val c = testContainer(bot = HttpBotApi("http://127.0.0.1:${server.address.port}", appId), signIn = browser)
            val m = SignInModel(c, this)
            m.start()
            withTimeout(15_000) { while (m.ui != SignInUi.Done) { check(m.ui !is SignInUi.Failed) { m.ui.toString() }; delay(20) } }

            assertEquals("ofs_wire", c.bot.state.value.session?.token)
            assertEquals("Заглушка", c.bot.state.value.subscription?.planName)
            val p = c.profiles.profiles.value.single()
            assertEquals(ProfileSource.Bot, p.source)

            val calls = synchronized(seen) { seen.toList() }
            val ex = calls.first()
            assertEquals("/api/v1/auth/app/exchange", ex.path)
            assertEquals(appId, ex.header("X-App-Id"))
            val body = Json.parseToJsonElement(ex.body).jsonObject
            assertEquals("wirecode", body["code"]!!.jsonPrimitive.content)
            assertEquals("http://127.0.0.1:$port/callback", body["redirect_uri"]!!.jsonPrimitive.content)
            assertTrue(body["code_verifier"]!!.jsonPrimitive.content.length >= 43)
            assertTrue(calls.drop(1).filter { it.path != "/api/v1/connect/resolve" }.all { it.header("Authorization") == "Bearer ofs_wire" })
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close() // the way back is closed again

            c.bot.signOut()
            assertNull(c.bot.state.value.session)
            assertTrue(c.profiles.profiles.value.isEmpty())
        } finally {
            server.stop(0)
        }
    }
}
