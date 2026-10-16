package io.openflux.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Base64

/**
 * New cups.online rooms without an exit node: every visit to the live-coding
 * page opens a fresh room, the same way the core's exit creates them
 * (transport/cupsonline createRooms). The result is the packed list both
 * peers take as the transport's value; an exit given it joins these rooms
 * instead of making its own.
 */
object CupsRooms {
    const val URL = "https://interview.cups.online/live-coding/"
    const val COUNT = 4
    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private val ROOM = Regex("""data-room="\{&quot;uuid&quot;:\s*&quot;([0-9a-f-]{36})&quot;""")
    private val ids = ListSerializer(String.serializer())

    /** The room a live-coding page opened, null when the page has none. */
    fun roomOf(html: String): String? = ROOM.find(html)?.groupValues?.get(1)

    /** The core's packed list: base64url, no padding, of a JSON array of room ids. */
    fun pack(rooms: List<String>): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(Json.encodeToString(ids, rooms).toByteArray())

    fun unpack(packed: String): List<String> =
        Json.decodeFromString(ids, String(Base64.getUrlDecoder().decode(packed.trim())))

    /** Opens [count] rooms, a short pause apart (cups.online throttles), and packs them. */
    suspend fun create(count: Int = COUNT, url: String = URL, pauseMs: Long = 500): String = withContext(Dispatchers.IO) {
        val rooms = mutableListOf<String>()
        var last: Exception? = null
        repeat(count) { i ->
            for (attempt in 0 until 4) {
                try {
                    rooms += fetchRoom(url)
                    break
                } catch (e: IOException) {
                    last = e
                    delay(pauseMs * (attempt + 1))
                }
            }
            if (i < count - 1) delay(pauseMs)
        }
        if (rooms.isEmpty()) throw IOException("cups.online не выдал ни одной комнаты: ${last?.message ?: "нет ответа"}")
        pack(rooms)
    }

    private fun fetchRoom(url: String): String {
        val c = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9")
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("cups.online ответил $code")
            val html = c.inputStream.use { it.readBytes().decodeToString() }
            return roomOf(html) ?: throw IOException("на странице cups.online нет комнаты")
        } finally {
            c.disconnect()
        }
    }
}
