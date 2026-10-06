package io.openflux.desktop

import com.sun.net.httpserver.HttpServer
import io.openflux.desktop.web.BrowserLog
import io.openflux.desktop.web.BuiltInBrowser
import io.openflux.desktop.web.KcefPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.GraphicsEnvironment
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A live pass over the built-in browser (KCEF) on this machine: start, load,
 * script, cookies, the node's proxy, the setup-page bridge, real mouse input,
 * what is painted. Needs a screen, the downloaded browser and (for the last
 * two checks) the network: OPENFLUX_BROWSER_TEST=1. The report goes to
 * OPENFLUX_LIVE_OUT (a directory), screenshots next to it.
 */
class BuiltInBrowserLiveTest {
    private val results = Collections.synchronizedList(mutableListOf<String>())
    private var failures = 0

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        results += "${if (ok) "PASS" else "FAIL"}  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}"
    }

    private fun info(text: String) { results += "      $text" }

    @Test
    fun builtInBrowserWorks() {
        if (GraphicsEnvironment.isHeadless() || System.getenv("OPENFLUX_BROWSER_TEST") == null) return
        val out = File(System.getenv("OPENFLUX_LIVE_OUT") ?: System.getProperty("java.io.tmpdir")).apply { mkdirs() }
        val closing = Collections.synchronizedList(mutableListOf<String>())
        var frame: JFrame? = null
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val seenUA = Collections.synchronizedList(mutableListOf<String>())
        http.createContext("/") { ex ->
            seenUA += ex.requestHeaders.getFirst("User-Agent").orEmpty()
            val path = ex.requestURI.path
            val body = when (path) {
                "/ua" -> ex.requestHeaders.getFirst("User-Agent").orEmpty()
                "/own" -> """<!doctype html><html><head><title>own</title></head><body style="margin:0">
                    <script>window.early = typeof window.openfluxSubmit; window.readyFired = false;
                      window.addEventListener('openflux-ready', function () { window.readyFired = true; });</script>
                    <button style="position:absolute;left:100px;top:100px;width:200px;height:80px;font-size:24px"
                      onclick="openfluxSubmit({via:'own-mouse', n: 7})">Go</button></body></html>"""
                "/other" -> """<!doctype html><html><head><title>other</title></head><body>other origin</body></html>"""
                else -> """<!doctype html><html><head><title>OF-LIVE</title></head><body style="margin:0;background:#fff">
                    <div id="box" style="position:absolute;left:50px;top:50px;width:300px;height:200px;background:rgb(255,0,0)">red</div>
                    <script>document.getElementById('box').dataset.js='ran'</script></body></html>"""
            }
            if (path == "/") {
                ex.responseHeaders.add("Set-Cookie", "sid=secret123; Path=/; HttpOnly")
                ex.responseHeaders.add("Set-Cookie", "vis=1; Path=/")
            }
            ex.responseHeaders.add("Content-Type", if (path == "/ua") "text/plain" else "text/html; charset=utf-8")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        val base = "http://127.0.0.1:${http.address.port}"

        // A node's proxy: answers every request with a marker and remembers what it was asked.
        val nodeSeen = Collections.synchronizedList(mutableListOf<String>())
        val node = ServerSocket(0, 20, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!node.isClosed) {
                val s = runCatching { node.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        s.use {
                            val r = it.getInputStream().bufferedReader()
                            val first = r.readLine() ?: return@thread
                            nodeSeen += first
                            while (r.readLine()?.isNotEmpty() == true) Unit
                            val body = "<html><head><title>VIA-NODE</title></head><body>via node</body></html>"
                            it.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                                flush()
                            }
                        }
                    }
                }
            }
        }

        val problems = mutableListOf<String>()
        BrowserLog.listener = { line, problem -> if (problem) problems += line }
        val t0 = System.currentTimeMillis()
        try {
            runBlocking {
                suspend fun KcefPage.settled(timeoutMs: Long = 20_000): Boolean {
                    val end = System.currentTimeMillis() + timeoutMs
                    var calm = 0
                    while (System.currentTimeMillis() < end) {
                        if (!loading && url.isNotEmpty() && url != "about:blank") { if (++calm >= 3) return true } else calm = 0
                        delay(100)
                    }
                    return false
                }

                // ---- 1. start + a local page ----
                val page = BuiltInBrowser.open("$base/")
                check("browser starts and creates a page", true, "${System.currentTimeMillis() - t0} ms")
                SwingUtilities.invokeAndWait {
                    frame = JFrame("OpenFlux live test").apply {
                        defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
                        addWindowListener(object : WindowAdapter() {
                            override fun windowClosing(e: WindowEvent) { closing += "closing" }
                        })
                        contentPane.add(page.component)
                        setSize(800, 600)
                        setLocation(100, 100)
                        isVisible = true
                    }
                }
                check("page loads", page.settled(), page.url)
                check("script sees the title", page.evaluate("document.title") == "OF-LIVE")
                check("page script ran (DOM)", page.evaluate("document.getElementById('box').dataset.js") == "ran")
                check("Promise result is awaited", page.evaluate("new Promise(r => setTimeout(() => r('late'), 300))") == "late")
                check("a thrown error comes back as fail", page.evaluate("(() => { throw new Error('boom') })()").contains("\"state\":\"fail\""))

                // ---- 2. identity ----
                val ua = page.evaluate("navigator.userAgent")
                check("navigator.userAgent is the Yandex one", ua == BuiltInBrowser.YANDEX_USER_AGENT, ua)
                val wireUA = page.evaluate("fetch('/ua').then(r => r.text())")
                check("the UA on the wire matches", wireUA == BuiltInBrowser.YANDEX_USER_AGENT, wireUA)

                // ---- 3. cookies ----
                val js = page.evaluate("document.cookie")
                check("document.cookie shows only the visible cookie", "vis=1" in js && "sid" !in js, js)
                val header = BuiltInBrowser.cookieHeader(BuiltInBrowser.cookies("$base/"))
                check("HttpOnly cookie reachable through the cookie manager", "sid=secret123" in header, header.replace("secret123", "***"))
                check("cookieHeader has both", "sid=" in header && "vis=1" in header)

                // ---- 4. painted frames ----
                delay(800)
                val shot = BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB)
                SwingUtilities.invokeAndWait {
                    val g = shot.createGraphics()
                    page.component.paint(g)
                    g.dispose()
                }
                ImageIO.write(shot, "png", File(out, "page.png"))
                // The red box is at 50,50 .. 350,250 logical px.
                val px = shot.getRGB(200, 150)
                val red = (px shr 16) and 0xFF; val green = (px shr 8) and 0xFF; val blue = px and 0xFF
                check("a frame is painted and the page has real content", red > 200 && green < 60 && blue < 60, "pixel(200,150)=($red,$green,$blue), view ${page.component.width}x${page.component.height}")
                val white = shot.getRGB(700, 500) and 0xFFFFFF
                check("the rest is the page background", white == 0xFFFFFF, "%06x".format(white))

                // ---- 5. wipe ----
                BuiltInBrowser.clearCookies()
                delay(300)
                check("clearCookies forgets the cookie", BuiltInBrowser.cookieHeader(BuiltInBrowser.cookies("$base/")).isEmpty())

                // ---- 6. close keeps the window ----
                SwingUtilities.invokeAndWait { }
                page.close()
                delay(1500)
                check("closing a page does not send WINDOW_CLOSING to the window", closing.isEmpty(), closing.toString())
                SwingUtilities.invokeAndWait { frame?.contentPane?.removeAll(); frame?.repaint() }

                // ---- 7. the node's proxy ----
                val viaNode = BuiltInBrowser.open("http://node-check.invalid/x?token=zzz", upstream = "127.0.0.1:${node.localPort}")
                check("page through the node's proxy loads", viaNode.settled(), viaNode.url)
                val title = viaNode.evaluate("document.title")
                check("the node's proxy answered (page came from it)", title == "VIA-NODE", title)
                check("the node saw the request line", nodeSeen.any { it.startsWith("GET http://node-check.invalid/x") }, nodeSeen.firstOrNull().orEmpty().substringBefore('?'))
                viaNode.close()
                BuiltInBrowser.release()
                delay(500)

                // ---- 8. a script's setup page: bridge + real mouse click ----
                val html = """<!doctype html><html><body style="margin:0">
                    <button id="go" style="position:absolute;left:100px;top:100px;width:200px;height:80px;font-size:24px"
                      onclick="window.openfluxSubmit({clicked:true, via:'mouse'})">Submit</button></body></html>"""
                val sub = CompletableDeferredString()
                val collector2 = CoroutineScope(Dispatchers.Default).launch { sub.value = BuiltInBrowser.submissions.first() }
                val hpage = BuiltInBrowser.openHtml(html)
                SwingUtilities.invokeAndWait {
                    frame!!.contentPane.removeAll()
                    frame!!.contentPane.add(hpage.component)
                    frame!!.contentPane.revalidate()
                }
                check("openHtml page loads", hpage.settled(), hpage.url.take(30))
                delay(1000)
                check("window.openfluxSubmit exists", hpage.evaluate("typeof window.openfluxSubmit") == "function")
                // A real click, through the view's input path (not a script call).
                val comp = hpage.component
                fun send(id: Int, x: Int, y: Int) = SwingUtilities.invokeAndWait {
                    comp.dispatchEvent(MouseEvent(comp, id, System.currentTimeMillis(), if (id == MouseEvent.MOUSE_PRESSED) MouseEvent.BUTTON1_DOWN_MASK else 0, x, y, 1, false, MouseEvent.BUTTON1))
                }
                send(MouseEvent.MOUSE_MOVED, 200, 140); delay(100)
                send(MouseEvent.MOUSE_PRESSED, 200, 140); delay(60)
                send(MouseEvent.MOUSE_RELEASED, 200, 140)
                val payload = withTimeoutOrNull(5000) { while (sub.value == null) delay(50); sub.value }
                check("a real mouse click reaches the page and the submit bridge", payload != null && "\"via\":\"mouse\"" in payload, payload.orEmpty())
                hpage.close()
                collector2.cancel()
                delay(500)
                check("window still open after all pages closed", closing.isEmpty(), closing.toString())

                // ---- 9. the real internet through the built-in proxy (CONNECT), view attached at once (as the app does) ----
                fun inked(img: BufferedImage): Int {
                    var n = 0
                    for (y in 0 until img.height step 2) for (x in 0 until img.width step 2) if ((img.getRGB(x, y) and 0xFFFFFF) != 0xFFFFFF) n++
                    return n
                }
                fun snap(page: KcefPage, name: String): Int {
                    val img = BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB)
                    SwingUtilities.invokeAndWait { val g = img.createGraphics(); page.component.paint(g); g.dispose() }
                    ImageIO.write(img, "png", File(out, "$name.png"))
                    return inked(img)
                }
                for ((u, want, name) in listOf(Triple("https://example.com/", "Example Domain", "example"), Triple("https://ya.ru/", "", "yaru"))) {
                    val web = BuiltInBrowser.open(u)
                    SwingUtilities.invokeAndWait {
                        frame!!.contentPane.removeAll(); frame!!.contentPane.add(web.component); frame!!.contentPane.revalidate(); frame!!.contentPane.repaint()
                    }
                    val ok = web.settled(30_000)
                    val t = if (ok) runCatching { web.evaluate("document.title", 15_000) }.getOrDefault("(no answer)") else "(not settled)"
                    check("https $u loads", ok && t.isNotBlank() && (want.isEmpty() || want in t), "title=\"$t\" url=${web.url.take(60)}")
                    delay(2500)
                    val ink = snap(web, name)
                    check("$u is painted (attached before load)", ink > 300, "non-white samples=$ink")
                    web.close()
                }

                // ---- 10. a loaded page attached to the window afterwards ----
                val late = BuiltInBrowser.open("https://example.com/")
                check("late page loads (not attached)", late.settled(30_000), late.url)
                delay(1000)
                SwingUtilities.invokeAndWait {
                    frame!!.contentPane.removeAll(); frame!!.contentPane.add(late.component); frame!!.contentPane.revalidate(); frame!!.contentPane.repaint()
                }
                delay(2500)
                val lateInk = snap(late, "late-attach")
                check("a page loaded off-screen is painted once attached", lateInk > 300, "non-white samples=$lateInk")
                late.close()
                // ---- 11. the script's own server page (openOwn): bridge after load, bound to its origin ----
                val subs = Collections.synchronizedList(mutableListOf<String>())
                val collector3 = CoroutineScope(Dispatchers.Default).launch { BuiltInBrowser.submissions.collect { subs += it } }
                delay(300)
                val own = BuiltInBrowser.openOwn("$base/own")
                SwingUtilities.invokeAndWait {
                    frame!!.contentPane.removeAll(); frame!!.contentPane.add(own.component); frame!!.contentPane.revalidate()
                }
                check("own-server page loads", own.settled(), own.url)
                delay(1000)
                check("window.openfluxSubmit is defined once the page has loaded", own.evaluate("typeof window.openfluxSubmit") == "function")
                check("openflux-ready fired for a page that listens", own.evaluate("window.readyFired") == "true")
                val ownComp = own.component
                fun click(c: java.awt.Component, x: Int, y: Int) {
                    fun send(id: Int) = SwingUtilities.invokeAndWait {
                        c.dispatchEvent(MouseEvent(c, id, System.currentTimeMillis(), if (id == MouseEvent.MOUSE_PRESSED) MouseEvent.BUTTON1_DOWN_MASK else 0, x, y, 1, false, MouseEvent.BUTTON1))
                    }
                    send(MouseEvent.MOUSE_MOVED); kotlinx.coroutines.runBlocking { delay(100) }
                    send(MouseEvent.MOUSE_PRESSED); kotlinx.coroutines.runBlocking { delay(60) }
                    send(MouseEvent.MOUSE_RELEASED)
                }
                click(ownComp, 200, 140)
                withTimeoutOrNull(5000) { while (subs.none { "own-mouse" in it }) delay(50) }
                val ownSub = subs.firstOrNull { "own-mouse" in it }.orEmpty()
                check("a click on the script's own page submits", "\"via\":\"own-mouse\"" in ownSub && "\"n\":7" in ownSub, ownSub)

                // The bridge belongs to the page's own address: after leaving it, nothing it sends is heard.
                subs.clear()
                val before = problems.size
                own.load("http://localhost:${http.address.port}/other")
                delay(800)
                check("the other origin loads", own.settled() && own.url.startsWith("http://localhost:"), own.url)
                check("the bridge is not injected into another origin", own.evaluate("typeof window.openfluxSubmit") == "undefined")
                own.evaluate("window.openfluxQuery({request: 'submit|{\"stolen\":\"1\"}', onSuccess: function () {}, onFailure: function () {}})")
                delay(1200)
                check("a submission from another origin is dropped", subs.isEmpty(), subs.toString())
                check("and the drop is logged", problems.drop(before).any { "отброшены" in it })
                own.close()

                // A normal page (open()) is never a setup page, even though the query channel exists in it.
                val plain = BuiltInBrowser.open("$base/other")
                check("a plain page loads", plain.settled(), plain.url)
                plain.evaluate("window.openfluxQuery({request: 'submit|{\"stolen\":\"2\"}', onSuccess: function () {}, onFailure: function () {}})")
                delay(1200)
                check("a plain page cannot submit", subs.isEmpty(), subs.toString())
                plain.close()

                // Only loopback http is an own server's address.
                for (bad in listOf("https://example.com/", "http://example.com:80/", "http://127.0.0.1/", "file:///etc/passwd")) {
                    val refused = runCatching { BuiltInBrowser.openOwn(bad) }.exceptionOrNull() is IllegalArgumentException
                    check("openOwn refuses $bad", refused)
                }

                // ---- 12. an inline page has the bridge before its own scripts ----
                val early = BuiltInBrowser.openHtml(
                    "<!doctype html><html><head><title>t</title></head><body><script>window.early = typeof window.openfluxSubmit; window.ready = false; " +
                        "window.addEventListener('openflux-ready', function () { window.ready = true; });</script></body></html>",
                )
                check("inline page loads", early.settled(), early.url.take(30))
                delay(500)
                check("inline page: openfluxSubmit exists before the page's own scripts", early.evaluate("window.early") == "function")
                check("inline page: openflux-ready fires for its listener", early.evaluate("window.ready") == "true")
                check("inline page keeps standards mode (doctype first)", early.evaluate("document.compatMode") == "CSS1Compat")
                early.close()
                collector3.cancel()

                BuiltInBrowser.clearCookies()
            }
        } finally {
            BrowserLog.listener = null
            SwingUtilities.invokeAndWait { frame?.dispose() }
            http.stop(0)
            node.close()
        }
        info("local server saw ${seenUA.size} requests; node proxy saw ${nodeSeen.size}")
        if (problems.isNotEmpty()) info("browser problems logged: ${problems.size}: " + problems.take(10).joinToString(" | "))
        File(out, "report.txt").writeText(results.joinToString("\n") + "\n\nfailures=$failures\n")
        assertTrue(failures == 0, "live browser checks failed:\n" + results.joinToString("\n"))
    }

    private class CompletableDeferredString { @Volatile var value: String? = null }
}
