package io.openflux.desktop.web

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Hands one file to whoever asks on loopback. KCEF takes its runtime from a
 * URL and nothing else, and its own download is the thing this app no longer
 * relies on ([RuntimeArchive]); so the archive that is already on the disk,
 * checked, is offered to it at an address of its own. Unpacking, laying the
 * files out and the install lock stay KCEF's.
 */
internal class ArchiveServer(private val file: File) : AutoCloseable {
    private val socket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())

    val url: String get() = "http://127.0.0.1:${socket.localPort}/${file.name}"

    init {
        Thread({ acceptLoop() }, "openflux-browser-archive").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            Thread({ serve(client) }, "openflux-browser-archive-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        client.use {
            client.soTimeout = 30_000
            val head = BrowserProxy.readHead(client.getInputStream()) ?: return
            val out = client.getOutputStream()
            if (!head.startsWith("GET ")) {
                out.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/gzip\r\nContent-Length: ${file.length()}\r\nConnection: close\r\n\r\n")
                    .toByteArray(),
            )
            file.inputStream().use { it.copyTo(out, 64 * 1024) }
            out.flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
