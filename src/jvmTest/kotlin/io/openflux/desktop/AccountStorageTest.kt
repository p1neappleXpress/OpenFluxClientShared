package io.openflux.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.openflux.desktop.data.CookieStoreSeeder
import io.openflux.desktop.data.FileAccountRepository
import io.openflux.desktop.data.HttpSessionProbe
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AccountSession
import io.openflux.desktop.model.Profile
import io.openflux.desktop.service.ProbeResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountStorageTest {
    @Test
    fun savesReloadsAndRemoves() {
        val dir = Files.createTempDirectory("accounts").toFile()
        val repo = FileAccountRepository(dir)
        repo.save(AccountSession(AccountKind.Yandex, "ivan", mapOf("Session_id" to "s"), 10, 20))
        assertTrue(dir.resolve("accounts.json").isFile)

        val again = FileAccountRepository(dir)
        assertEquals("ivan", again.sessions.value.getValue(AccountKind.Yandex).login)
        assertEquals("s", again.sessions.value.getValue(AccountKind.Yandex).cookies["Session_id"])

        again.remove(AccountKind.Yandex)
        assertNull(FileAccountRepository(dir).sessions.value[AccountKind.Yandex])
    }

    @Test
    fun corruptFileStartsEmpty() {
        val dir = Files.createTempDirectory("accounts").toFile()
        dir.resolve("accounts.json").writeText("{not json")
        assertEquals(emptyMap(), FileAccountRepository(dir).sessions.value)
    }
}

class CookieStoreSeederTest {
    private val url = "https://docs.yandex.ru/edit/d/AAAAAAAAAAAAAAAAAAAA"
    private val sessions = mapOf(AccountKind.Yandex to AccountSession(AccountKind.Yandex, "i", mapOf("Session_id" to "s"), 1, 1))

    @Test
    fun mergesIntoExistingStoreFile() {
        val file = Files.createTempFile("cookies", ".json").toFile()
        file.writeText("""{"$url":{"spravka":"old"}}""")
        CookieStoreSeeder.seed(file, Profile(id = "p", name = "n", value = url), sessions)
        val json = Json.parseToJsonElement(file.readText()).jsonObject.getValue(url).jsonObject
        assertEquals("old", json.getValue("spravka").jsonPrimitive.content)
        assertEquals("s", json.getValue("Session_id").jsonPrimitive.content)
    }

    @Test
    fun corruptOrMissingStoreIsNotFatal() {
        val bad = Files.createTempFile("cookies", ".json").toFile().apply { writeText("{bad") }
        CookieStoreSeeder.seed(bad, Profile(id = "p", name = "n", value = url), sessions)
        assertTrue("Session_id" in bad.readText())
        val missing = Files.createTempDirectory("c").toFile().resolve("cookies/p.json")
        CookieStoreSeeder.seed(missing, Profile(id = "p", name = "n", value = url), sessions)
        assertTrue(missing.isFile)
    }
}

class HttpSessionProbeTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var lastCookie = ""
    private var reply: (HttpExchange) -> Unit = {}

    init {
        server.createContext("/client/disk") { ex ->
            lastCookie = ex.requestHeaders.getFirst("Cookie").orEmpty()
            reply(ex)
            ex.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun probe() = HttpSessionProbe(mapOf(AccountKind.Yandex to "http://127.0.0.1:${server.address.port}/client/disk"))

    private fun respond(code: Int, body: String = "", location: String? = null) {
        reply = { ex ->
            location?.let { ex.responseHeaders.add("Location", it) }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun signedInWhenDiskPageHasCsrfKey() = runTest {
        respond(200, """<script id="preloaded-data">{"config":{"sk":"abc"}}</script>""")
        assertEquals(ProbeResult.SignedIn, probe().probe(AccountKind.Yandex, mapOf("Session_id" to "s", "L" to "l")))
        assertEquals("Session_id=s; L=l", lastCookie)
    }

    @Test
    fun expiredWhenRedirectedToPassport() = runTest {
        respond(302, location = "https://passport.yandex.ru/auth?retpath=x")
        assertEquals(ProbeResult.Expired, probe().probe(AccountKind.Yandex, mapOf("Session_id" to "s")))
    }

    @Test
    fun needsCheckOnShowCaptcha() = runTest {
        respond(302, location = "https://disk.yandex.ru/showcaptcha?retpath=x")
        assertEquals(ProbeResult.NeedsCheck, probe().probe(AccountKind.Yandex, mapOf("Session_id" to "s")))
    }

    @Test
    fun mailruByMailInbox() {
        val probe = HttpSessionProbe()
        assertEquals(ProbeResult.SignedIn, probe.classify(AccountKind.Mailru, 200, "", "<html>"))
        assertEquals(ProbeResult.Expired, probe.classify(AccountKind.Mailru, 302, "https://login.vk.com/?act=autologin", ""))
        assertEquals(ProbeResult.Offline, probe.classify(AccountKind.Mailru, 500, "", ""))
    }

    @Test
    fun offlineWhenNothingListens() = runTest {
        val dead = HttpSessionProbe(mapOf(AccountKind.Yandex to "http://127.0.0.1:1/client/disk"))
        assertEquals(ProbeResult.Offline, dead.probe(AccountKind.Yandex, mapOf("Session_id" to "s")))
    }
}
