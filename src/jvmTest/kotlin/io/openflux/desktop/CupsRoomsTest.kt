package io.openflux.desktop

import com.sun.net.httpserver.HttpServer
import io.openflux.desktop.data.CupsRooms
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CupsRoomsTest {
    private fun page(uuid: String) =
        """<div id="app" data-room="{&quot;uuid&quot;: &quot;$uuid&quot;, &quot;name&quot;: &quot;x&quot;}" data-user="{}"></div>"""

    @Test
    fun roomFromPage() {
        assertEquals("4a469022-c4b9-4d70-a764-c318396737cd", CupsRooms.roomOf(page("4a469022-c4b9-4d70-a764-c318396737cd")))
        assertNull(CupsRooms.roomOf("<html></html>"))
    }

    @Test
    fun packsLikeTheCore() {
        // Go: base64.RawURLEncoding of json.Marshal([]string{"a","b"}).
        assertEquals("WyJhIiwiYiJd", CupsRooms.pack(listOf("a", "b")))
        assertEquals(listOf("a", "b"), CupsRooms.unpack("WyJhIiwiYiJd"))
    }

    @Test
    fun createsRoomsFromFreshPages() = runTest {
        val n = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/live-coding/") { ex ->
            val body = page("00000000-0000-0000-0000-00000000000${n.incrementAndGet()}").toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val packed = CupsRooms.create(3, "http://127.0.0.1:${server.address.port}/live-coding/", pauseMs = 1)
            assertEquals(
                listOf("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000003"),
                CupsRooms.unpack(packed),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun failsWhenNoRoomComes() = runTest {
        assertFailsWith<IOException> { CupsRooms.create(1, "http://127.0.0.1:1/live-coding/", pauseMs = 1) }
    }
}
