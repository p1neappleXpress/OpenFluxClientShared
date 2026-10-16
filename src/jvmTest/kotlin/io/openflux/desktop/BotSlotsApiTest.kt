package io.openflux.desktop

import com.sun.net.httpserver.HttpServer
import io.openflux.desktop.data.HttpBotApi
import io.openflux.desktop.model.BotDocument
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotPaymentMethod
import io.openflux.desktop.model.BotSlot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The slots API of nevex-net/openflux 0e3f2db: GET /slots, Start/Stop, the own documents of a manual slot, the payment methods. */
class BotSlotsApiTest {
    private val servers = mutableListOf<HttpServer>()
    private class Seen(val method: String, val path: String, val body: String)
    private val seen = mutableListOf<Seen>()

    @AfterTest
    fun stop() = servers.forEach { it.stop(0) }

    private fun server(answer: (method: String, path: String) -> Pair<Int, String>): String {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += Seen(ex.requestMethod, ex.requestURI.rawPath, body)
            val (code, json) = answer(ex.requestMethod, ex.requestURI.rawPath)
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        s.start()
        servers += s
        return "http://127.0.0.1:${s.address.port}"
    }

    private val slotId = "5f0c7c2e-1d1a-4b0e-9a55-2b0d0c9e7a10"
    private val running = """{"slotId":"$slotId","alias":"Ноутбук","planName":"Start","mode":"auto","state":"running","expiresAt":"1794412374","daysLeft":27,"connectionNo":"19","canStart":false,"canStop":true,"session":{"sessionId":"s1","status":"running","connectToken":"AAAAAAAAAAAAAAAAAAAAAA"}}"""
    private val freeManual = """{"slotId":"6f0c7c2e-1d1a-4b0e-9a55-2b0d0c9e7a10","planName":"Start","mode":"manual","state":"free","expiresAt":"1794412374","needsSetup":true}"""

    @Test
    fun slotsAreReadAsTheServiceWritesThem() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to "[$running,$freeManual]" })
        val slots = api.slots("ofs_x")
        assertEquals(
            BotSlot(slotId, "Ноутбук", "Start", "auto", "running", 1794412374, 19, canStart = false, canStop = true, needsSetup = false, sessionId = "s1", connectToken = "AAAAAAAAAAAAAAAAAAAAAA"),
            slots[0],
        )
        assertTrue(slots[1].manual && slots[1].needsSetup && slots[1].sessionId.isEmpty())
        assertEquals("/api/v1/slots", seen.single().path)
    }

    @Test
    fun startAndStopGoToTheSlotAndAnswerWithIt() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """{"slot":$running}""" })
        assertEquals("running", api.startSlot("ofs_x", slotId).state)
        api.stopSlot("ofs_x", slotId)
        assertEquals(listOf("POST /api/v1/slots/$slotId/start", "POST /api/v1/slots/$slotId/stop"), seen.map { "${it.method} ${it.path}" })
    }

    @Test
    fun aSlotIdThatIsNotOursNeverBuildsAPath() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to "{}" })
        for (id in listOf("", "../plans", "x/start")) assertEquals("bad_request", assertFailsWith<BotException> { api.startSlot("ofs_x", id) }.code, id)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun everyRefusalOfASlotIsSaidInRussian() = runBlocking {
        val cases = listOf(
            Triple(404, "slot not found", "slot_gone"),
            Triple(409, "the slot is already running", "slot_changed"),
            Triple(403, "this slot is of a manual plan", "slot_refused"),
            Triple(400, "the connection could not be restored and the slot is free again: start it again", "connection_lost"),
            Triple(503, "no room for the connection now: try again shortly", "no_room"),
            Triple(500, "the slot action failed", "http_500"),
        )
        for ((status, msg, code) in cases) {
            val api = HttpBotApi(server { _, _ -> status to """{"code":1,"message":"$msg"}""" })
            val e = assertFailsWith<BotException> { api.startSlot("ofs_x", slotId) }
            assertEquals(code, e.code, "$status $msg")
            assertTrue(e.message!!.any { it in 'а'..'я' }, e.message)
        }
    }

    @Test
    fun documentsOfAManualSlot() = runBlocking {
        val doc = """{"id":"d1","kind":"yandex","label":"Яндекс Диск …a1b2","status":"ok"}"""
        val api = HttpBotApi(
            server { method, path ->
                when {
                    method == "GET" -> 200 to "[$doc]"
                    method == "DELETE" -> 200 to "{}"
                    path.endsWith("/connect") -> 200 to """{"slot":$running}"""
                    else -> 200 to doc
                }
            },
        )
        assertEquals(listOf(BotDocument("d1", "yandex", "Яндекс Диск …a1b2", "ok")), api.documents("ofs_x", slotId))
        api.addDocument("ofs_x", slotId, "  https://disk.yandex.ru/i/abc  ")
        assertEquals("https://disk.yandex.ru/i/abc", Json.parseToJsonElement(seen.last().body).jsonObject["url"]!!.jsonPrimitive.content, "trimmed")
        api.checkDocument("ofs_x", slotId, "d1")
        api.deleteDocument("ofs_x", slotId, "d1")
        assertEquals("running", api.connectSlot("ofs_x", slotId, listOf("d2", "d1")).state)
        assertEquals(listOf("d2", "d1"), Json.parseToJsonElement(seen.last().body).jsonObject["document_ids"]!!.jsonArray.map { it.jsonPrimitive.content }, "the order is kept")
        assertEquals(
            listOf("GET", "POST", "POST", "DELETE", "POST").zip(listOf("documents", "documents", "documents/d1/check", "documents/d1", "connect")).map { (m, p) -> "$m /api/v1/slots/$slotId/$p" },
            seen.map { "${it.method} ${it.path}" },
        )
    }

    @Test
    fun everyRefusalOfADocumentIsSaidInRussian() = runBlocking {
        val cases = listOf(
            Triple(400, "this link is not a supported document link", "bad_link"),
            Triple(400, "the document cannot be used: it does not open for editing, is not a docx or xlsx, or does not exist", "doc_unusable"),
            Triple(400, "pick one to five documents of this slot, of different kinds", "doc_pick"),
            Triple(400, "the slot has a live connection or has ended: documents can be added to a free slot", "bad_request"),
            Triple(409, "this link is already registered", "doc_taken"),
            Triple(429, "the document limit is reached", "doc_limit"),
            Triple(429, "too many document checks: wait a minute", "rate_limited"),
            Triple(503, "user documents are not enabled", "docs_off"),
        )
        for ((status, msg, code) in cases) {
            val api = HttpBotApi(server { _, _ -> status to """{"code":1,"message":"$msg"}""" })
            val e = assertFailsWith<BotException> { api.addDocument("ofs_x", slotId, "https://disk.yandex.ru/i/abc") }
            assertEquals(code, e.code, "$status $msg")
            assertTrue(e.message!!.any { it in 'а'..'я' }, e.message)
        }
    }

    @Test
    fun paymentMethodsAndTheChosenOneGoWithTheInvoice() = runBlocking {
        val api = HttpBotApi(
            server { _, path ->
                if (path == "/api/v1/payment-methods") 200 to """[{"id":"platega","name":"Карта или СБП"},{"id":"cryptopay","name":"Криптовалюта"}]"""
                else 200 to """{"id":"0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11","status":"active","payUrl":"https://pay.example/x"}"""
            },
        )
        assertEquals(listOf(BotPaymentMethod("platega", "Карта или СБП"), BotPaymentMethod("cryptopay", "Криптовалюта")), api.paymentMethods("ofs_x"))
        api.createInvoice("ofs_x", "own30", "key-of-the-test", "cryptopay")
        assertEquals("cryptopay", Json.parseToJsonElement(seen.last().body).jsonObject["gateway"]!!.jsonPrimitive.content)
        api.createInvoice("ofs_x", "own30", "key-of-the-test2")
        assertEquals(null, Json.parseToJsonElement(seen.last().body).jsonObject["gateway"], "none chosen: the service's first")
    }

    @Test
    fun anOlderServiceWithoutMethodsOrSlotsIsNotAnError() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 404 to "{}" })
        assertTrue(api.paymentMethods("ofs_x").isEmpty())
        assertTrue(assertFailsWith<BotException> { api.slots("ofs_x") }.notFound, "the manager reads not_found as 'no slots API'")
    }

    @Test
    fun aMethodNoLongerOfferedIsSaid() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 400 to """{"code":3,"message":"this payment method is not available: see GET /api/v1/payment-methods"}""" })
        assertEquals("method_gone", assertFailsWith<BotException> { api.createInvoice("ofs_x", "own30", "key-of-the-test", "cryptopay") }.code)
    }
}
