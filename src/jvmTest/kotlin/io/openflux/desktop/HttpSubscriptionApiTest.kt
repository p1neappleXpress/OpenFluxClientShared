package io.openflux.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.openflux.desktop.data.HttpSubscriptionApi
import io.openflux.desktop.model.ServiceEndpoint
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionException
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The client against real HTTP and TLS servers: the wire format, the errors and, above all, the pin. */
class HttpSubscriptionApiTest {
    private class Seen(val method: String, val path: String, val auth: String?, val body: String, val transports: String? = null)

    private val seen = CopyOnWriteArrayList<Seen>()
    private val servers = mutableListOf<com.sun.net.httpserver.HttpServer>()
    private val api = HttpSubscriptionApi()

    @AfterTest
    fun stop() = servers.forEach { it.stop(0) }

    private val handler = HttpHandler { ex: HttpExchange ->
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        seen += Seen(ex.requestMethod, ex.requestURI.rawPath, ex.requestHeaders.getFirst("Authorization"), body, ex.requestHeaders.getFirst("X-OpenFlux-Transports"))
        val (code, json) = when (ex.requestURI.rawPath) {
            "/api/v1/plans" -> 200 to """{"plans":[{"id":"month","name":"Месяц","days":30,"devices":3,"price":"299 ₽"}],"payment":{"provider":"manual","instructions":"Напишите в поддержку"},"future":"field"}"""
            "/api/v1/register" -> 201 to """{"deviceToken":"tok","deviceId":"d1","recoveryKey":"AAAA-BBBB","account":{"id":"u","login":"ivan","status":"active","expires":2000000000,"plan":{"id":"trial","name":"Пробный","devices":1},"trialAvailable":false,"devices":[{"id":"d1","name":"Тест","current":true}],"deviceLimit":1}}"""
            "/api/v1/login" -> 409 to """{"error":"device_limit","message":"Достигнут предел устройств.","devices":[{"id":"d9","name":"Телефон","createdAt":1,"lastSeen":2}]}"""
            "/api/v1/account" -> if (ex.requestHeaders.getFirst("Authorization") == "Bearer tok") 200 to """{"login":"ivan","status":"active"}""" else 401 to """{"error":"unauthorized","message":"Войдите в аккаунт."}"""
            "/api/v1/subscription" -> 200 to """{"version":1,"title":"OpenFlux","status":"active","expires":2000000000,"updateHours":6,"profiles":[{"id":"de","name":"Германия","link":"openflux://v1/x"}]}"""
            "/healthz" -> 200 to "ok\n"
            "/sub/KEY" -> 200 to """{"version":1,"status":"expired","profiles":[]}"""
            "/sub/NULL" -> 200 to """{"version":1,"status":"active","profiles":null}"""
            "/api/v1/orders/o%2F1" -> 200 to """{"orderId":"o/1","status":"paid","expires":5}"""
            "/api/v1/account/delete" -> 401 to """{"error":"invalid_credentials","message":"Неверный пароль."}"""
            "/api/v1/logout" -> 200 to """{"ok":true}"""
            "/api/v1/redirect" -> { ex.responseHeaders.add("Location", "https://evil.example/"); 302 to "" }
            "/api/v1/garbage" -> 200 to "<html>not json</html>"
            "/api/v1/huge" -> 200 to "x".repeat(2 * 1024 * 1024)
            else -> 404 to """{"error":"not_found","message":"Такого нет."}"""
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, if (code == 302) -1 else bytes.size.toLong())
        if (code != 302) ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    private fun http(): ServiceEndpoint {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/", handler)
        s.start()
        servers += s
        return ServiceEndpoint("http://127.0.0.1:${s.address.port}")
    }

    private val keystore: KeyStore = KeyStore.getInstance("PKCS12").also { store ->
        HttpSubscriptionApiTest::class.java.classLoader.getResourceAsStream("pinned-test.p12")!!.use { store.load(it, "changeit".toCharArray()) }
    }

    /** The test certificate's pin, as the service prints it. */
    private val realPin: String = MessageDigest.getInstance("SHA-256")
        .digest((keystore.getCertificate("t") as X509Certificate).encoded).joinToString("") { "%02x".format(it) }

    private fun https(): Int {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keystore, "changeit".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val s = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.httpsConfigurator = HttpsConfigurator(context)
        s.createContext("/", handler)
        s.start()
        servers += s
        return s.address.port
    }

    @Test
    fun readsPlansAndIgnoresFieldsItDoesNotKnow() = runBlocking {
        val plans = api.plans(http())
        assertEquals(1, plans.plans.size)
        assertEquals("299 ₽", plans.plans[0].price)
        assertEquals("Напишите в поддержку", plans.payment.instructions)
    }

    @Test
    fun registerSendsTheCredentialsAsJson() = runBlocking {
        val ep = http()
        val auth = api.register(ep, "ivan", "correct horse", "Тест")
        assertEquals("tok", auth.deviceToken)
        assertEquals("AAAA-BBBB", auth.recoveryKey)
        assertEquals("trial", auth.account.plan?.id)
        assertTrue(auth.account.devices.single().current)
        val sent = seen.single()
        assertEquals("POST", sent.method)
        assertEquals("/api/v1/register", sent.path)
        assertEquals(null, sent.auth, "no token before signing in")
        assertTrue(sent.body.contains("\"login\":\"ivan\"") && sent.body.contains("\"deviceName\":\"Тест\"") && sent.body.contains("\"password\":\"correct horse\""), sent.body)
    }

    @Test
    fun theTokenTravelsAsABearerHeaderAndAMissingOneIsUnauthorized() = runBlocking {
        val ep = http()
        assertEquals("ivan", api.account(ep, "tok").login)
        assertEquals("Bearer tok", seen.last().auth)
        val ex = assertFailsWith<SubscriptionException> { api.account(ep, "stale") }
        assertTrue(ex.unauthorized)
        assertEquals("Войдите в аккаунт.", ex.message)
    }

    @Test
    fun serviceErrorsKeepTheirCodeMessageAndDevices() = runBlocking {
        val ex = assertFailsWith<SubscriptionException> { api.login(http(), "ivan", "pw", "Тест", null) }
        assertEquals("device_limit", ex.code)
        assertEquals("Достигнут предел устройств.", ex.message)
        assertEquals("Телефон", ex.devices.single().name)
        assertEquals(true, seen.last().body.contains("\"replaceDeviceId\":\"\""))
        Unit
    }

    @Test
    fun keysAndOrdersAndLogoutUseTheirPaths() = runBlocking {
        val ep = http()
        val doc = api.keyDocument(ep, ep.base + "/sub/KEY")
        assertEquals(SubStatus.EXPIRED, doc.status)
        assertEquals(null, seen.last().auth, "a key's address is its credential, no header")
        assertEquals(SubStatus.ACTIVE, api.document(ep, "tok").status)
        assertEquals("Германия", api.document(ep, "tok").profiles.single().name)
        assertEquals("paid", api.order(ep, "tok", "o/1").status, "an id is escaped in the path")
        api.logout(ep, "tok")
        assertEquals("/api/v1/logout", seen.last().path)
        assertEquals("POST", seen.last().method)
        val bad = assertFailsWith<SubscriptionException> { api.deleteAccount(ep, "tok", "wrong") }
        assertEquals("invalid_credentials", bad.code)
        assertEquals("POST", seen.last().method, "delete goes as POST: not every client can send DELETE with a body")
        assertEquals("/api/v1/account/delete", seen.last().path)
    }

    @Test
    fun unknownPathsAndBrokenAnswersAreErrorsNotCrashes() = runBlocking {
        val ep = http()
        assertEquals("not_found", assertFailsWith<SubscriptionException> { api.keyDocument(ep, ep.base + "/sub/missing") }.code)
        val garbage = assertFailsWith<SubscriptionException> { api.keyDocument(ep, ep.base + "/api/v1/garbage") }
        assertEquals(SubscriptionException.BAD_RESPONSE, garbage.code)
        assertEquals(SubscriptionException.BAD_RESPONSE, assertFailsWith<SubscriptionException> { api.keyDocument(ep, ep.base + "/api/v1/huge") }.code, "an answer is limited in size")
        assertEquals(SubscriptionException.BAD_RESPONSE, assertFailsWith<SubscriptionException> { api.keyDocument(ep, ep.base + "/api/v1/redirect") }.code, "a redirect would carry the token elsewhere")
    }

    @Test
    fun aNullProfilesAnswerIsAnEmptyListNotABrokenAnswer() = runBlocking {
        val ep = http()
        val doc = api.keyDocument(ep, ep.base + "/sub/NULL")
        assertEquals(SubStatus.ACTIVE, doc.status)
        assertTrue(doc.profiles.isEmpty(), "a service answering profiles:null must not break the client")
    }

    @Test
    fun aDeadServiceIsANetworkError() = runBlocking {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val ex = assertFailsWith<SubscriptionException> { api.plans(ServiceEndpoint("http://127.0.0.1:$port")) }
        assertTrue(ex.network)
        assertEquals("Сервис не отвечает. Проверьте сеть.", ex.message)
    }

    /** The service builds links only from what the app can run: never a type it would drop the location for. */
    @Test
    fun everyRequestNamesTheTransportsThisBuildRuns() = runBlocking {
        val ep = http()
        api.document(ep, "tok")
        api.keyDocument(ep, ep.base + "/sub/KEY")
        for (s in seen) {
            val names = s.transports.orEmpty().split(",")
            assertEquals(listOf("vyandex", "yandex", "boards", "mailru", "cupsonline", "telemost", "avito"), names, s.path)
        }
    }

    @Test
    fun healthWantsTheServiceToAnswer() = runBlocking {
        val ep = http()
        api.health(ep)
        assertEquals("/healthz", seen.last().path)
        assertEquals(null, seen.last().auth, "no token goes to an address being checked")
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertTrue(assertFailsWith<SubscriptionException> { api.health(ServiceEndpoint("http://127.0.0.1:$port")) }.network)
        val noHealth = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { it.sendResponseHeaders(404, -1); it.close() }
            start()
        }
        servers += noHealth
        assertFailsWith<SubscriptionException> { api.health(ServiceEndpoint("http://127.0.0.1:${noHealth.address.port}")) }
        Unit
    }

    // ---- the pin ----

    @Test
    fun aPinnedServiceIsTrustedByItsCertificateAlone() = runBlocking {
        val port = https()
        val plans = api.plans(ServiceEndpoint("https://127.0.0.1:$port", realPin))
        assertEquals("month", plans.plans.single().id)
        // The pin may be written in capitals.
        assertEquals("month", api.plans(ServiceEndpoint("https://127.0.0.1:$port", realPin.uppercase())).plans.single().id)
    }

    @Test
    fun aWrongPinSendsNothing() = runBlocking {
        val port = https()
        val wrong = realPin.replaceFirstChar { if (it == '0') '1' else '0' }
        assertNotEquals(realPin, wrong)
        val ex = assertFailsWith<SubscriptionException> { api.login(ServiceEndpoint("https://127.0.0.1:$port", wrong), "ivan", "secret password", "Тест", null) }
        assertEquals(SubscriptionException.BAD_PIN, ex.code)
        assertTrue(ex.message!!.contains("Логин и пароль не отправлены"), ex.message)
        assertTrue(seen.isEmpty(), "the password must not reach a server with another certificate: ${seen.map { it.path }}")
    }

    @Test
    fun healthChecksTheNewPin() = runBlocking {
        val port = https()
        api.health(ServiceEndpoint("https://127.0.0.1:$port", realPin))
        val wrong = realPin.replaceFirstChar { if (it == '0') '1' else '0' }
        assertEquals(SubscriptionException.BAD_PIN, assertFailsWith<SubscriptionException> { api.health(ServiceEndpoint("https://127.0.0.1:$port", wrong)) }.code)
    }

    @Test
    fun aSelfSignedCertificateWithoutAPinIsRefused() = runBlocking {
        val port = https()
        val ex = assertFailsWith<SubscriptionException> { api.plans(ServiceEndpoint("https://127.0.0.1:$port")) }
        assertEquals(SubscriptionException.NETWORK, ex.code, "the usual certificate check applies when there is no pin")
        assertTrue(seen.isEmpty())
    }
}
