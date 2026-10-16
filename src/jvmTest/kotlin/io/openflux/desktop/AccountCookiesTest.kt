package io.openflux.desktop

import io.openflux.desktop.model.YandexBoards
import io.openflux.desktop.model.DocumentKind
import io.openflux.desktop.model.AccountCookies
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AccountSession
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountCookiesTest {
    private val yandex = mapOf("Session_id" to "s", "yandex_login" to "ivan.petrov", "yandexuid" to "1")

    @Test
    fun kindsByTransport() {
        assertEquals(AccountKind.Yandex, AccountKind.of(TransportType.VYANDEX))
        assertEquals(AccountKind.Yandex, AccountKind.of(TransportType.YANDEX))
        assertEquals(AccountKind.Yandex, AccountKind.of(TransportType.BOARDS))
        assertEquals(AccountKind.Mailru, AccountKind.of(TransportType.MAILRU))
        assertEquals(AccountKind.Max, AccountKind.of(TransportType.ONEME))
        assertNull(AccountKind.of(TransportType.CUPSONLINE))
        assertNull(AccountKind.of(TransportType.DIRECT))
    }

    @Test
    fun documentKindsByTransport() {
        assertEquals(DocumentKind.YandexDocument, DocumentKind.of(TransportType.VYANDEX))
        assertEquals(DocumentKind.YandexDocument, DocumentKind.of(TransportType.YANDEX))
        assertEquals(DocumentKind.YandexBoard, DocumentKind.of(TransportType.BOARDS))
        assertEquals(DocumentKind.MailruDocument, DocumentKind.of(TransportType.MAILRU))
        assertNull(DocumentKind.of(TransportType.CUPSONLINE))
    }

    @Test
    fun boardLinks() {
        val board = "https://boards.yandex.ru/whiteboard/?hash=820d6571111dffeb9e85de65ccfc880a"
        assertEquals(board, YandexBoards.clean("$board&from=cabinet#slide"))
        assertEquals(board, YandexBoards.clean("https://boards.yandex.ru/whiteboard/?from=x&hash=820d6571111dffeb9e85de65ccfc880a"))
        assertNull(YandexBoards.clean("https://evil.example/whiteboard/?hash=820d6571111dffeb9e85de65ccfc880a"))
        assertNull(YandexBoards.clean("https://boards.yandex.ru/cabinet/"))
    }

    @Test
    fun yandexLoginAndSignedIn() {
        assertTrue(AccountCookies.signedIn(AccountKind.Yandex, yandex))
        assertEquals("ivan.petrov", AccountCookies.login(AccountKind.Yandex, yandex))
        assertFalse(AccountCookies.signedIn(AccountKind.Yandex, mapOf("yandexuid" to "1")))
        assertEquals("", AccountCookies.login(AccountKind.Yandex, mapOf("Session_id" to "s")))
    }

    @Test
    fun mailruLoginFromMpop() {
        val jar = mapOf("Mpop" to "1700000000:abcdef:ivan@mail.ru:")
        assertTrue(AccountCookies.signedIn(AccountKind.Mailru, jar))
        assertEquals("ivan@mail.ru", AccountCookies.login(AccountKind.Mailru, jar))
    }

    @Test
    fun headerRoundTrip() {
        assertEquals(mapOf("a" to "1", "b" to "2=3"), AccountCookies.parseHeader("a=1; b=2=3; ;c; a=9"))
        assertEquals("a=1; b=2", AccountCookies.header(mapOf("a" to "1", "b" to "2")))
    }

    @Test
    fun sessionToStringHidesCookies() {
        val s = AccountSession(AccountKind.Yandex, "ivan.petrov", yandex, signedInAt = 1, checkedAt = 1)
        assertFalse("Session_id" in s.toString() || "=s" in s.toString())
        assertTrue("ivan.petrov" in s.toString())
    }

    @Test
    fun seedPutsAccountIntoEveryYandexCarrierAndKeepsOtherKeys() {
        val profile = Profile(
            id = "p1", name = "n", transport = TransportType.VYANDEX,
            value = " https://docs.yandex.ru/edit/d/AAAAAAAAAAAAAAAAAAAA ", session = true, secret = "0123456789abcdef",
            extras = listOf(
                ExtraTransport(TransportType.DIRECT, "1.2.3.4:8445"),
                ExtraTransport(TransportType.MAILRU, "https://cloud.mail.ru/public/x/y"),
            ),
        )
        val store = mapOf(
            "https://docs.yandex.ru/edit/d/AAAAAAAAAAAAAAAAAAAA" to mapOf("spravka" to "old", "Session_id" to "stale"),
            "other" to mapOf("a" to "b"),
        )
        val sessions = mapOf(AccountKind.Yandex to AccountSession(AccountKind.Yandex, "ivan.petrov", yandex, 1, 1))
        val seeded = AccountCookies.seed(store, profile, sessions)
        val doc = seeded.getValue("https://docs.yandex.ru/edit/d/AAAAAAAAAAAAAAAAAAAA")
        assertEquals("s", doc["Session_id"])
        assertEquals("old", doc["spravka"])
        assertEquals(mapOf("a" to "b"), seeded["other"])
        assertNull(seeded["https://cloud.mail.ru/public/x/y"])
        assertNull(seeded["1.2.3.4:8445"])
    }

    @Test
    fun seedTakesMailruAccountOutOfItsAnonymousJar() {
        val url = "https://cloud.mail.ru/public/zMYY/5djxyNJEY"
        val profile = Profile(id = "p", name = "n", transport = TransportType.MAILRU, value = url)
        val mail = mapOf("Mpop" to "a:b:ivan@mail.ru:", "t" to "x")
        val sessions = mapOf(AccountKind.Mailru to AccountSession(AccountKind.Mailru, "ivan@mail.ru", mail, 1, 1))
        val store = mapOf(url to mail + ("captcha" to "ok"), "other" to mapOf("a" to "b"))
        val seeded = AccountCookies.seed(store, profile, sessions)
        assertEquals(mapOf("captcha" to "ok"), seeded[url])
        assertEquals(mapOf("a" to "b"), seeded["other"])
        assertNull(AccountCookies.seed(mapOf(url to mail), profile, sessions)[url])
    }

    @Test
    fun aBoardIsJoinedAsAGuestWithoutTheAccount() {
        val board = "https://boards.yandex.ru/whiteboard/?hash=820d6571111dffeb9e85de65ccfc880a"
        val doc = "https://docs.yandex.ru/edit/d/AAAAAAAAAAAAAAAAAAAA"
        val profile = Profile(
            id = "p", name = "n", transport = TransportType.VYANDEX, value = doc, session = true, secret = "0123456789abcdef",
            extras = listOf(ExtraTransport(TransportType.BOARDS, board)),
        )
        val sessions = mapOf(AccountKind.Yandex to AccountSession(AccountKind.Yandex, "i", yandex, 1, 1))
        val seeded = AccountCookies.seed(mapOf(board to yandex), profile, sessions)
        assertEquals("s", seeded.getValue(doc)["Session_id"])
        assertNull(seeded[board])
        assertEquals(setOf(TransportType.VYANDEX, TransportType.YANDEX), AccountKind.Yandex.signedInTransports)
        assertEquals(emptySet(), AccountKind.Mailru.signedInTransports)
    }

    @Test
    fun seedSkipsExpiredSessions() {
        val profile = Profile(id = "p", name = "n", value = "https://docs.yandex.ru/edit/d/AAAAAAAAAAAAAAAAAAAA")
        val sessions = mapOf(AccountKind.Yandex to AccountSession(AccountKind.Yandex, "x", yandex, 1, 1, expired = true))
        assertEquals(emptyMap(), AccountCookies.seed(emptyMap(), profile, sessions))
    }
}
