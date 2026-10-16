package io.openflux.desktop

import com.sun.net.httpserver.HttpServer
import io.openflux.desktop.data.HttpBotApi
import io.openflux.desktop.model.BotBanner
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotInvoice
import io.openflux.desktop.model.BotPlan
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

/** Buying through api.openflux.dev: the terms on sale, an invoice and its state, the service's banners. */
class BotPurchaseApiTest {
    private val servers = mutableListOf<HttpServer>()
    private class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
    private val seen = mutableListOf<Seen>()

    @AfterTest
    fun stop() = servers.forEach { it.stop(0) }

    /** A server answering every request with (status, json) from [answer]; records the requests. */
    private fun server(answer: (path: String, n: Int) -> Pair<Int, String>): String {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += Seen(ex.requestMethod, ex.requestURI.rawPath, ex.requestHeaders.mapValues { it.value.first() }, body)
            val (code, json) = answer(ex.requestURI.rawPath, seen.size)
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        s.start()
        servers += s
        return "http://127.0.0.1:${s.address.port}"
    }

    private val key = "k3y-0f-the-purchase"

    // The live service's answer on 2026-10-15.
    private val livePlans = """[{"id":"own30", "name":"Start", "durationDays":30, "maxSessions":1, "trafficLimitBytes":"32212254720", "description":"OpenFlux — подключение для одного устройства", "price":3000, "currency":"RUB"}]"""

    @Test
    fun theTermsOnSaleAreReadAsTheLiveServiceAnswers() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to livePlans })
        val plans = api.plans("ofs_tok")
        assertEquals(listOf(BotPlan("own30", "Start", 30, 32212254720, "OpenFlux — подключение для одного устройства", 3000, "RUB")), plans)
        assertEquals("GET", seen.single().method)
        assertEquals("/api/v1/plans", seen.single().path)
        assertEquals("Bearer ofs_tok", seen.single().header("Authorization"), "the list depends on who asks")
    }

    @Test
    fun theTermsAreAlsoReadWrappedAndWithoutASession() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """{"plans":[{"id":"t90","name":"Start","durationDays":90,"price":"8000","currency":"RUB"}]}""" })
        assertEquals(listOf(BotPlan("t90", "Start", 90, 0, "", 8000, "RUB")), api.plans(null))
        assertEquals(null, seen.single().header("Authorization"))
        val empty = HttpBotApi(server { _, _ -> 200 to "[]" })
        assertTrue(empty.plans(null).isEmpty())
    }

    @Test
    fun aTermWithoutACodeIsLeftOut() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """[{"name":"Broken","price":100},{"id":"ok","name":"Start","durationDays":30,"price":3000}]""" })
        assertEquals(listOf("ok"), api.plans(null).map { it.id })
    }

    @Test
    fun pricesAreShownInRoubles() {
        assertEquals("30 ₽", BotPlan("own30", "Start", 30, 0, "", 3000, "RUB").priceText)
        assertEquals("30,50 ₽", BotPlan("x", "Start", 30, 0, "", 3050, "RUB").priceText)
        assertEquals("1 250 ₽", BotPlan("x", "Start", 30, 0, "", 125000, "RUB").priceText)
        assertEquals("12 USD", BotPlan("x", "Start", 30, 0, "", 1200, "USD").priceText)
    }

    @Test
    fun anInvoicesAmountIsReadAsRoubles() {
        assertEquals("30 ₽", BotInvoice("i", amount = "30.00").amountText)
        assertEquals("30,50 ₽", BotInvoice("i", amount = "30.5").amountText)
        assertEquals("1 250 ₽", BotInvoice("i", amount = "1250").amountText)
        for (bad in listOf("", "abc", "-3.00", "1e5", "30.001")) assertEquals(null, BotInvoice("i", amount = bad).amountText, bad)
    }

    @Test
    fun anInvoiceGoesWithItsTermAndTheIdempotencyKey() = runBlocking {
        val api = HttpBotApi(
            server { _, _ ->
                200 to """{"invoiceId":"778899","payUrl":"https://pay.example/x","amount":"30.00","asset":"RUB","status":"active","createdAt":"1760500000","id":"0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11","expiresAt":"1760586400"}"""
            },
        )
        val inv = api.createInvoice("ofs_tok", "own30", key)
        assertEquals(BotInvoice("0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11", "https://pay.example/x", "30.00", "active", 1760500000, 1760586400), inv)
        assertFalse(inv.paid)
        val r = seen.single()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/subscription/invoice", r.path)
        assertEquals(key, r.header("Idempotency-Key"))
        assertEquals("Bearer ofs_tok", r.header("Authorization"))
        assertEquals("own30", Json.parseToJsonElement(r.body).jsonObject["plan_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFreeInvoiceIsPaidAtOnceAndNeedsNoPage() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """{"id":"0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11","status":"paid","amount":"0.00"}""" })
        val inv = api.createInvoice("ofs_tok", "free", key)
        assertTrue(inv.paid)
        assertEquals("", inv.payUrl)
    }

    @Test
    fun aPaymentPageThatIsNotHttpsIsRefused() = runBlocking {
        for (url in listOf("http://pay.example/x", "javascript:alert(1)", "file:///etc/passwd")) {
            val api = HttpBotApi(server { _, _ -> 200 to """{"id":"0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11","status":"active","payUrl":"$url"}""" })
            assertEquals("bad_response", assertFailsWith<BotException> { api.createInvoice("ofs_tok", "own30", key) }.code, url)
        }
    }

    @Test
    fun anInvoiceWithoutOurIdIsRefused() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """{"invoiceId":"5","status":"active","payUrl":"https://pay.example/x"}""" })
        assertEquals("bad_response", assertFailsWith<BotException> { api.createInvoice("ofs_tok", "own30", key) }.code)
    }

    @Test
    fun aBadIdempotencyKeyNeverLeavesTheDevice() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to "{}" })
        for (k in listOf("", "short", "x".repeat(65), "has space1", "кириллица1")) {
            assertEquals("bad_request", assertFailsWith<BotException> { api.createInvoice("ofs_tok", "own30", k) }.code, k)
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun aKeyStillBeingHandledIsAskedOnceMore() = runBlocking {
        val api = HttpBotApi(
            server { _, n ->
                if (n == 1) 409 to """{"code":10,"message":"the same request is still being handled, retry in a moment"}"""
                else 200 to """{"id":"0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11","status":"active","payUrl":"https://pay.example/x"}"""
            },
        )
        assertEquals("https://pay.example/x", api.createInvoice("ofs_tok", "own30", key).payUrl)
        assertEquals(2, seen.size)
        assertEquals(key, seen[1].header("Idempotency-Key"), "the repeat carries the same key")
    }

    @Test
    fun aKeyBusyTwiceIsSaidNotRepeatedForever() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 409 to """{"code":10,"message":"the same request is still being handled"}""" })
        assertEquals("busy", assertFailsWith<BotException> { api.createInvoice("ofs_tok", "own30", key) }.code)
        assertEquals(2, seen.size)
    }

    @Test
    fun everyRefusalOfAnInvoiceIsSaidInRussian() = runBlocking {
        val cases = listOf(
            Triple(400, """{"code":9,"message":"the slot limit for this account is reached"}""", "slot_limit"),
            Triple(400, """{"code":3,"message":"this Idempotency-Key was already used for another plan"}""", "bad_request"),
            Triple(401, """{"code":16,"message":"unauthorized"}""", "unauthorized"),
            Triple(403, """{"code":7,"message":"this account is blocked"}""", "blocked"),
            Triple(404, """{"code":5,"message":"plan not found: own30"}""", "plan_gone"),
            Triple(429, """{"code":8,"message":"too many invoices, try again in a minute"}""", "rate_limited"),
            Triple(503, """{"code":14,"message":"payment gateway \"platega\" is currently disabled"}""", "payments_off"),
            Triple(500, """{"code":13,"message":"failed to create invoice: boom"}""", "http_500"),
        )
        for ((status, body, code) in cases) {
            val api = HttpBotApi(server { _, _ -> status to body })
            val e = assertFailsWith<BotException> { api.createInvoice("ofs_tok", "own30", key) }
            assertEquals(code, e.code, "HTTP $status $body")
            assertTrue(e.message!!.any { it in 'а'..'я' }, "said in Russian: ${e.message}")
            assertFalse(e.message!!.contains("platega") || e.message!!.contains("boom"), "no technical words: ${e.message}")
        }
    }

    @Test
    fun anInvoiceIsAskedAboutByOurId() = runBlocking {
        val id = "0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11"
        val api = HttpBotApi(server { _, _ -> 200 to """{"id":"$id","status":"paid","payUrl":"https://pay.example/x","amount":"30.00","createdAt":"1760500000","expiresAt":"1760586400"}""" })
        val inv = api.invoice("ofs_tok", id)
        assertTrue(inv.paid)
        assertEquals("GET", seen.single().method)
        assertEquals("/api/v1/subscription/invoices/$id", seen.single().path)
        assertEquals("Bearer ofs_tok", seen.single().header("Authorization"))
    }

    @Test
    fun anInvoiceIdThatIsNotOursNeverBuildsAPath() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to "{}" })
        for (id in listOf("", "../plans", "a/b", "0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11?x=1")) {
            assertEquals("bad_request", assertFailsWith<BotException> { api.invoice("ofs_tok", id) }.code, id)
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun anUnknownInvoiceIsNotFound() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 404 to """{"code":5,"message":"invoice not found"}""" })
        assertTrue(assertFailsWith<BotException> { api.invoice("ofs_tok", "0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11") }.notFound)
    }

    @Test
    fun invoiceStatesAreNamed() {
        fun inv(status: String) = BotInvoice("i", "", "", status)
        assertTrue(inv("paid").paid)
        assertTrue(inv("expired").expired)
        assertFalse(inv("active").paid || inv("active").expired)
    }

    @Test
    fun bannersAreReadWithoutASessionAndSpeakTheAppsLanguage() = runBlocking {
        val api = HttpBotApi(
            server { _, _ ->
                200 to """[{"id":"2", "kind":"warning", "textRu":"Бот работает в открытом бета-тесте.", "textEn":"The bot is in open beta."},{"id":"3","kind":"info","textRu":"","textEn":"Only English"}]"""
            },
        )
        val banners = api.banners()
        assertEquals(BotBanner(2, "warning", "Бот работает в открытом бета-тесте.", "The bot is in open beta."), banners[0])
        assertEquals("/api/v1/banners", seen.single().path)
        assertEquals(null, seen.single().header("Authorization"))
        assertEquals("Бот работает в открытом бета-тесте.", banners[0].text("ru"))
        assertEquals("The bot is in open beta.", banners[0].text("en"))
        assertEquals("Only English", banners[1].text("ru"), "a missing language falls back to the other")
    }

    @Test
    fun aBannerWithoutTextIsLeftOut() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """[{"id":"4","kind":"info"},{"id":"5","kind":"info","textRu":"Есть"}]""" })
        assertEquals(listOf(5L), api.banners().map { it.id })
    }

    @Test
    fun otherFailuresCarryTheServicesWordsOnlyWhenTheyAreRussian() = runBlocking {
        val ru = HttpBotApi(server { _, _ -> 503 to """{"code":14,"message":"Тарифы временно недоступны"}""" })
        assertEquals("Тарифы временно недоступны", assertFailsWith<BotException> { ru.plans(null) }.message)
        val en = HttpBotApi(server { _, _ -> 503 to """{"code":14,"message":"plans are temporarily unavailable: pq: connection refused"}""" })
        val e = assertFailsWith<BotException> { en.plans(null) }
        assertFalse(e.message!!.contains("pq:"), e.message)
        assertTrue(e.message!!.any { it in 'а'..'я' }, e.message)
    }
}
