package io.openflux.desktop

import com.sun.net.httpserver.HttpServer
import io.openflux.desktop.data.HttpBotApi
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotLoginApiTest {
    private val servers = mutableListOf<HttpServer>()
    private class Seen(val path: String, val headers: Map<String, String>, val body: String) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
    private val seen = mutableListOf<Seen>()

    @AfterTest
    fun stop() = servers.forEach { it.stop(0) }

    /** A server answering each path with (status, json) from [answer]; records the requests. */
    private fun server(answer: (path: String, body: String) -> Pair<Int, String>): String {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += Seen(ex.requestURI.path, ex.requestHeaders.mapValues { it.value.first() }, body)
            val (code, json) = answer(ex.requestURI.path, body)
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        servers += s
        return "http://127.0.0.1:${s.address.port}"
    }

    private val appId = "ofa_testtesttesttest"

    @Test
    fun aRefusalCarriesTheServicesOwnWordsWithoutItsHtmlAndEmoji() = runBlocking {
        val said = """{"code":7, "message":"session creation blocked: ⚠️ <b>Все ваши оплаченные подключения (2 из 2) уже активны.</b>\n\n<i>💡 Остановите ненужное.</i>", "details":[]}"""
        val api = HttpBotApi(server { _, _ -> 403 to said }, appId)
        val e = assertFailsWith<BotException> { api.createSession("ofs_x") }
        assertEquals("forbidden", e.code)
        assertEquals("Все ваши оплаченные подключения (2 из 2) уже активны. Остановите ненужное.", e.message)
    }

    @Test
    fun aRefusalWithoutWordsKeepsTheUsualSentence() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 403 to "" }, appId)
        assertEquals(io.openflux.desktop.service.BotAccountManager.HttpForbidden, assertFailsWith<BotException> { api.createSession("ofs_x") }.message)
    }

    @Test
    fun aRefusalInEnglishIsNotShownToThePerson() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 403 to """{"code":7,"message":"no free slot for an automatic connection"}""" }, appId)
        assertEquals(io.openflux.desktop.service.BotAccountManager.HttpForbidden, assertFailsWith<BotException> { api.createSession("ofs_x") }.message)
    }

    @Test
    fun exchangeSendsTheCodeVerifierAndAddressWithTheKey() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """{"userId":"7","role":"user","token":"ofs_tok","expiresAt":"1900000000","sessionId":"s1"}""" }, appId)
        val s = api.exchange("c0de", "v".repeat(43), "http://127.0.0.1:47621/callback")
        assertEquals(BotSession("ofs_tok", 7, "", 1900000000), s)
        val r = seen.single()
        assertEquals("/api/v1/auth/app/exchange", r.path)
        assertEquals(appId, r.header("X-App-Id"))
        assertEquals(null, r.header("Authorization"))
        val body = Json.parseToJsonElement(r.body).jsonObject
        assertEquals("c0de", body["code"]!!.jsonPrimitive.content)
        assertEquals("v".repeat(43), body["code_verifier"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:47621/callback", body["redirect_uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun snakeCaseAnswersAreReadToo() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """{"user_id":"10000000000000001","token":"ofs_t","expires_at":"1900000000"}""" }, appId)
        assertEquals(BotSession("ofs_t", 10000000000000001, "", 1900000000), api.exchange("c", "v".repeat(43), "x:/y"))
    }

    @Test
    fun exchangeWithoutAKeyNeverCallsTheService() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to "{}" }, "")
        assertEquals("no_app_id", assertFailsWith<BotException> { api.exchange("c", "v".repeat(43), "x:/y") }.code)
        assertTrue(seen.isEmpty(), "nothing leaves the device without an app id")
    }

    @Test
    fun anAnswerWithoutATokenIsRefused() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """{"userId":"7"}""" }, appId)
        assertEquals("bad_response", assertFailsWith<BotException> { api.exchange("c", "v".repeat(43), "x:/y") }.code)
    }

    @Test
    fun anAnswerThatIsNotAnObjectIsRefusedNotThrown() = runBlocking {
        for (body in listOf("null", "[]", "\"x\"", "7")) {
            val api = HttpBotApi(server { _, _ -> 200 to body }, appId)
            assertEquals("bad_response", assertFailsWith<BotException> { api.exchange("c", "v".repeat(43), "x:/y") }.code, body)
        }
    }

    @Test
    fun everyRefusalOfTheServiceHasItsOwnCode() = runBlocking {
        val cases = mapOf(400 to "bad_request", 401 to "sign_in_failed", 403 to "blocked", 404 to "not_ready", 429 to "rate_limited", 503 to "unavailable")
        for ((status, code) in cases) {
            val api = HttpBotApi(server { _, _ -> status to """{"code":16,"message":"sign-in failed"}""" }, appId)
            assertEquals(code, assertFailsWith<BotException> { api.exchange("c", "v".repeat(43), "x:/y") }.code, "HTTP $status")
        }
    }

    @Test
    fun aFailedSignInMentionsTheEmailConfirmation() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 401 to "{}" }, appId)
        val e = assertFailsWith<BotException> { api.exchange("c", "v".repeat(43), "x:/y") }
        assertTrue(e.message!!.contains("подтвердите"), e.message)
    }

    @Test
    fun theCodeAndVerifierNeverReachAnErrorMessage() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 500 to "{}" }, appId)
        val e = assertFailsWith<BotException> { api.exchange("SECRETCODE", "SECRETVERIFIER".padEnd(43, 'x'), "x:/y") }
        assertFalse(e.message!!.contains("SECRET"))
    }

    @Test
    fun sessionsAreReadFromABareArrayOrAnObject() = runBlocking {
        val bare = HttpBotApi(server { _, _ -> 200 to """[{"sessionId":"s1","status":"running","connectToken":"tok1","clientUrl":"https://x"},{"sessionId":"s2","status":"creating"}]""" }, appId)
        assertEquals(listOf(BotSessionItem("s1", "running", "tok1"), BotSessionItem("s2", "creating", "")), bare.sessions("ofs_tok"))
        val wrapped = HttpBotApi(server { _, _ -> 200 to """{"sessions":[{"sessionId":"s3","status":"stopped"}],"total":1}""" }, appId)
        assertEquals(listOf(BotSessionItem("s3", "stopped", "")), wrapped.sessions("ofs_tok"))
        val empty = HttpBotApi(server { _, _ -> 200 to "[]" }, appId)
        assertTrue(empty.sessions("ofs_tok").isEmpty())
        assertEquals("Bearer ofs_tok", seen.first().header("Authorization"))
    }

    @Test
    fun aCreatedSessionIsReadAndARefusalIsForbidden() = runBlocking {
        val ok = HttpBotApi(server { _, _ -> 200 to """{"session":{"sessionId":"s9","status":"creating"}}""" }, appId)
        assertEquals(BotSessionItem("s9", "creating", ""), ok.createSession("ofs_tok"))
        val no = HttpBotApi(server { _, _ -> 403 to """{"code":7,"message":"no free slot for an automatic connection"}""" }, appId)
        assertTrue(assertFailsWith<BotException> { no.createSession("ofs_tok") }.forbidden)
    }

    @Test
    fun secretsStayOutOfToString() {
        assertFalse("ofs_secret" in BotSession("ofs_secret", 7).toString())
    }
}
