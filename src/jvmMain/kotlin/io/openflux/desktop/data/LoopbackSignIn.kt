package io.openflux.desktop.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.openflux.desktop.model.AuthAnswer
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.OpenFluxId
import io.openflux.desktop.service.SignInSupport
import io.openflux.desktop.service.SignInWay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The way back on a computer (RFC 8252 §7.3): for one sign-in the app listens on 127.0.0.1 at the first free port of
 * [ports] (0 = the system gives one), the page returns the browser to http://127.0.0.1:<port>/callback. The service
 * ignores the port of a loopback address, so the system's choice needs no registration; the same address, port
 * included, goes to the sign-in page and to the exchange. Only a return carrying the attempt's own state is taken;
 * anything else is refused, so junk sent to the port can neither end the attempt nor push the real return out.
 */
class LoopbackSignIn(
    override val clientId: String,
    private val ports: List<Int> = PORTS,
    override val issuer: String = OpenFluxId.ISSUER,
    private val open: (String) -> Unit,
) : SignInSupport {
    private var current: Way? = null

    override fun newAttempt() = Pkce.newAttempt()

    override suspend fun begin(state: String): SignInWay = withContext(Dispatchers.IO) {
        val older = synchronized(this@LoopbackSignIn) { current.also { current = null } }
        older?.close()
        val host = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        for (port in ports) {
            val server = try {
                HttpServer.create(InetSocketAddress(host, port), 0)
            } catch (e: IOException) {
                continue
            }
            val way = Way(server, state)
            server.createContext("/") { way.answer(it) }
            server.start()
            synchronized(this@LoopbackSignIn) { current = way }
            return@withContext way
        }
        throw BotException("no_return", "Не получилось начать вход: на этом компьютере не открывается порт для возврата из браузера (127.0.0.1). Закройте лишние программы (VPN, защитные) и повторите")
    }

    override fun openPage(url: String) = open(url)

    private inner class Way(private val server: HttpServer, private val state: String) : SignInWay {
        override val redirectUri = "http://127.0.0.1:${server.address.port}$PATH"
        private val arrivals = Channel<String>(Channel.CONFLATED)
        private val delivered = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)

        override suspend fun await(): String = try {
            arrivals.receive()
        } catch (e: ClosedReceiveChannelException) {
            throw BotException("superseded", "Вход начат заново, этот закрыт. Повторите вход")
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            arrivals.close()
            // Closes the listener at once; waits (up to a second) for an answer still being written.
            server.stop(1)
            synchronized(this@LoopbackSignIn) { if (current === this) current = null }
        }

        fun answer(ex: HttpExchange) {
            try {
                if (ex.requestMethod != "GET" || ex.requestURI.rawPath != PATH) {
                    ex.sendResponseHeaders(404, -1)
                    return
                }
                val returned = "$redirectUri?${ex.requestURI.rawQuery.orEmpty()}"
                val ours = OpenFluxId.readReturn(returned, state) !is AuthAnswer.Foreign
                val first = ours && delivered.compareAndSet(false, true)
                respond(ex, if (first) 200 else 400, if (first) PAGE else STALE)
                // After the page is out: the sign-in closes this way the moment it gets the return.
                if (first) arrivals.trySend(returned)
            } finally {
                ex.close()
            }
        }

        private fun respond(ex: HttpExchange, status: Int, page: String) {
            val bytes = page.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.responseHeaders.add("Cache-Control", "no-store")
            ex.responseHeaders.add("Referrer-Policy", "no-referrer")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    companion object {
        /** Port 0: the system gives a free one (the service takes any port of 127.0.0.1, RFC 8252 §7.3). */
        val PORTS = listOf(0)
        const val PATH = "/callback"
        private const val HEAD = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>OpenFlux</title></head>
<body style="font-family:system-ui,sans-serif;max-width:32em;margin:4em auto;padding:0 1em"><h1>OpenFlux</h1>"""
        private const val PAGE = "$HEAD<p>Вход передан в приложение. Эту вкладку можно закрыть.</p></body></html>"
        private const val STALE = "$HEAD<p>Эта ссылка входа устарела. Вернитесь в приложение и начните вход заново.</p></body></html>"
    }
}
