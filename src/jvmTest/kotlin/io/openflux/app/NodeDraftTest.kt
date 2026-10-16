package io.openflux.app

import io.openflux.app.servers.NodeDraft
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.NodeTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NodeDraftTest {
    @Test
    fun telemostAvitoAndScripts() {
        val d = io.openflux.app.servers.NodeDraft(
            telemost = true, telemostLink = "https://telemost.yandex.ru/j/123456/", avito = true, avitoRooms = "rooms",
            scripts = listOf(io.openflux.app.servers.NodeScript("mtslink", " v ")),
        )
        kotlin.test.assertNull(d.problem())
        kotlin.test.assertTrue(d.hasTransport)
        val ts = d.toTransports()
        kotlin.test.assertEquals(listOf("telemost", "avito", "script"), ts.map { it.type })
        kotlin.test.assertEquals("https://telemost.yandex.ru/j/123456", ts[0].url)
        kotlin.test.assertEquals("v", ts[2].url)
        kotlin.test.assertEquals("mtslink", ts[2].script)

        kotlin.test.assertNotNull(d.copy(telemostLink = "https://example.com/j/1").problem())
        kotlin.test.assertNotNull(d.copy(scripts = listOf(io.openflux.app.servers.NodeScript("x", "a#b"))).problem())

        val back = io.openflux.app.servers.NodeDraft.of(
            io.openflux.desktop.model.InstalledChannel("c", transports = ts.map { if (it.type == "avito") it.copy(url = "rooms") else it }), "",
        )
        kotlin.test.assertEquals(0, d.copy(avitoRooms = "rooms", telemostLink = ts[0].url, scripts = listOf(io.openflux.app.servers.NodeScript("mtslink", "v"))).changes(back.copy(name = "")))
        kotlin.test.assertEquals(1, back.changes(back.copy(scripts = emptyList())), "a script turned off is a change")
    }

    private val doc = "https://docs.yandex.ru/edit/d/" + "a".repeat(20)
    private val mailru = "https://cloud.mail.ru/public/DEmN/ETbZW2MPY"
    private val board = "https://boards.yandex.ru/whiteboard/?hash=820d6571111dffeb9e85de65ccfc880a"

    private val installed = InstalledChannel(
        "of-a", "active", 8445, "ctx",
        listOf(NodeTransport("vyandex", doc), NodeTransport("mailru", mailru), NodeTransport("cupsonline", "rooms")),
        mode = "l3",
    )

    @Test
    fun readsAnInstalledNode() {
        val d = NodeDraft.of(installed, "Амстердам")
        assertEquals("Амстердам", d.name)
        assertEquals(true, d.volga); assertEquals(false, d.yandexDocs); assertEquals(doc, d.yandexDoc)
        assertEquals(true, d.mailru); assertEquals(mailru, d.mailruLink)
        assertEquals(true, d.cups); assertEquals("rooms", d.cupsRooms)
        assertEquals(NodeMode.L3, d.mode)
    }

    @Test
    fun transportsInPriorityOrderWithoutDirect() {
        val d = NodeDraft(volga = true, yandexDocs = true, yandexDoc = doc, mailru = true, mailruLink = mailru, board = true, boardLink = board, cups = true, cupsRooms = "r")
        assertEquals(listOf("vyandex", "yandex", "mailru", "boards", "cupsonline"), d.toTransports().map { it.type })
        assertEquals(emptyList(), NodeDraft().toTransports(), "direct only")
    }

    @Test
    fun problemsAreNamedOneAtATime() {
        assertEquals("Нужна ссылка на документ Яндекса вида https://docs.yandex.ru/edit/d/…", NodeDraft(volga = true, yandexDoc = "x").problem())
        assertEquals("Нужна публичная ссылка Mail.ru вида https://cloud.mail.ru/public/…/…", NodeDraft(mailru = true, mailruLink = "x").problem())
        assertEquals("Нужна ссылка на доску вида https://boards.yandex.ru/whiteboard/?hash=…", NodeDraft(board = true, boardLink = "").problem())
        assertNull(NodeDraft(volga = true, yandexDoc = " $doc?x=1 ").problem(), "cleaned like the old wizard")
        assertNull(NodeDraft(cups = true).problem(), "rooms are made when applied")
    }

    @Test
    fun countsChanges() {
        val o = NodeDraft.of(installed, "Амстердам")
        assertEquals(0, o.changes(o))
        assertEquals(1, o.copy(mode = NodeMode.L4).changes(o))
        assertEquals(2, o.copy(mailru = false, board = true, boardLink = board).changes(o))
        assertEquals(1, o.copy(name = "Новое имя").changes(o))
        assertEquals(0, o.copy(mailruLink = "ignored while off", mailru = true).copy(mailruLink = mailru).changes(o))
    }

    @Test
    fun onlyTheNameChangedNeedsNoServer() {
        val o = NodeDraft.of(installed, "Амстердам")
        assertEquals(false, o.copy(name = "Б").serverChanged(o))
        assertEquals(true, o.copy(mode = NodeMode.L4).serverChanged(o))
    }

    @Test
    fun directOnlyNeedsATransportToChange() {
        val directOnly = NodeDraft.of(InstalledChannel("of-b", "active", 8446, mode = "l4"), "Б")
        assertEquals(
            "Режим и транспорты меняются, когда у ноды есть транспорт кроме прямого: включите хотя бы один",
            directOnly.copy(mode = NodeMode.L3).applyProblem(directOnly),
        )
        assertNull(directOnly.copy(cups = true).applyProblem(directOnly))
        val withDoc = NodeDraft.of(installed, "А")
        assertEquals(
            "Нода должна сохранить хотя бы один транспорт кроме прямого",
            withDoc.copy(volga = false, mailru = false, cups = false).applyProblem(withDoc),
        )
    }
}
