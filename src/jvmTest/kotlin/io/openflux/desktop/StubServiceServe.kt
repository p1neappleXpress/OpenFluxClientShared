package io.openflux.desktop

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

/**
 * Serves the sign-in page and the API on 127.0.0.1:8099 for a hand run of the desktop app (STUB_SERVE=1; stops when
 * <tmp>/stub-stop exists). The page signs the person in at once and sends the browser back to the app. Not a test of
 * anything: without STUB_SERVE it returns at once. Run the app against it with
 * OPENFLUX_API_BASE=http://127.0.0.1:8099 OPENFLUX_ID_ISSUER=http://127.0.0.1:8099/realms/openflux and an app id built in.
 */
class StubServiceServe {
    @Test
    fun serve() {
        if (System.getenv("STUB_SERVE") != "1") return
        val link = System.getenv("STUB_LINK") ?: error("STUB_LINK: an openflux:// link to answer connect/resolve with")
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 8099), 0)
        s.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val (code, json) = when (ex.requestURI.path) {
                "/realms/openflux/protocol/openid-connect/auth" -> {
                    val q = ex.requestURI.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
                    ex.responseHeaders.add("Location", "${q["redirect_uri"]}?state=${URLEncoder.encode(q["state"], "UTF-8")}&code=stubcode")
                    302 to ""
                }
                "/api/v1/auth/app/exchange" -> 200 to """{"userId":"7","role":"user","token":"ofs_stub","expiresAt":"${System.currentTimeMillis() / 1000 + 86400}","sessionId":"s1"}"""
                "/api/v1/sessions" -> 200 to """[{"sessionId":"s1","status":"running","connectToken":"StubStubStubStubStubStub"}]"""
                "/api/v1/connect/resolve" -> 200 to """{"name":"Заглушка #1","link":${JsonPrimitive(link)}}"""
                "/api/v1/subscription" -> 200 to """{"planId":"own30","planName":"Заглушка","status":"active","daysLeft":20,"isActive":true}"""
                "/api/v1/auth/logout" -> 200 to "{}"
                else -> 404 to "{}"
            }
            println("stub: ${ex.requestMethod} ${ex.requestURI.path} ${body.ifBlank { "" }} -> $code")
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        s.start()
        val stop = File(System.getProperty("java.io.tmpdir"), "stub-stop").also { it.delete() }
        while (!stop.exists()) Thread.sleep(500)
        s.stop(0)
    }
}
