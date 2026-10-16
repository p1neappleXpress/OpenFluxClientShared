package io.openflux.desktop

import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionInvite
import io.openflux.desktop.model.SubscriptionState
import io.openflux.desktop.model.SubscriptionKind
import io.openflux.desktop.model.ServiceEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The key's forms are the core's (subscription.ParseInvite): the same text must read the same on both sides. */
class SubscriptionInviteTest {
    private val pin = "0123456789abcdef".repeat(4)

    @Test
    fun readsEveryFormOfAKey() {
        val link = "openflux://sub?u=https%3A%2F%2F203.0.113.5%3A8443%2Fsub%2FTOKEN&p=$pin&n=%D0%9C%D0%BE%D0%B9+%D1%81%D0%B5%D1%80%D0%B2%D0%B8%D1%81"
        val key = assertNotNull(SubscriptionInvite.parse(link))
        assertEquals("https://203.0.113.5:8443/sub/TOKEN", key.url)
        assertEquals(pin, key.pin)
        assertEquals("Мой сервис", key.name)
        assertEquals(ServiceEndpoint("https://203.0.113.5:8443", pin), key.endpoint)

        for (plain in listOf(
            "https://203.0.113.5:8443/sub/TOKEN#pin=$pin",
            "  https://203.0.113.5:8443/sub/TOKEN#pin=sha256:${pin.chunked(2).joinToString(":").uppercase()}\n",
        )) {
            val k = assertNotNull(SubscriptionInvite.parse(plain), plain)
            assertEquals("https://203.0.113.5:8443/sub/TOKEN", k.url)
            assertEquals(pin, k.pin)
        }
        // No pin: the certificate is checked the usual way (a domain).
        val domain = assertNotNull(SubscriptionInvite.parse("https://sub.example.com/sub/TOKEN"))
        assertEquals("", domain.pin)
        assertEquals("https://sub.example.com", domain.endpoint.url)
    }

    @Test
    fun refusesWhatIsNotAKey() {
        for (text in listOf(
            "", "hello", "openflux://v1/abc", "example.com/sub/x", "ftp://203.0.113.5/sub/x",
            "http://203.0.113.5/sub/x", "https://example.com/page", "https://user:pw@example.com/sub/x",
            "https://example.com/sub/x#pin=1234", "https://example.com/sub/x#pin=${"zz".repeat(32)}",
            "openflux://sub?p=$pin",
        )) {
            assertNull(SubscriptionInvite.parse(text), text)
        }
        // This machine over plain http is for tests and a local service.
        assertNotNull(SubscriptionInvite.parse("http://127.0.0.1:9000/sub/x"))
    }

    @Test
    fun routesPastedText() {
        assertTrue(SubscriptionInvite.looksLike("openflux://sub?u=x"))
        assertTrue(SubscriptionInvite.looksLike(" https://h:8443/sub/TOKEN#pin=$pin "))
        assertFalse(SubscriptionInvite.looksLike("openflux://v1/abc"))
        assertFalse(SubscriptionInvite.looksLike("https://example.com/page"))
        assertFalse(SubscriptionInvite.looksLike(""))
    }

    @Test
    fun daysLeftCountsWholeDaysWithAnHourOfGrace() {
        val ep = ServiceEndpoint("https://h")
        fun state(expires: Long) = SubscriptionState(ep, SubscriptionKind.Account, expires = expires, status = SubStatus.ACTIVE)
        val now = 1_000_000L
        assertEquals(3, state(now + 3 * 86_400).daysLeft(now))
        assertEquals(0, state(now + 60).daysLeft(now), "under a day is zero, not a day")
        assertEquals(1, state(now + 86_400 - 1_800).daysLeft(now), "a day less half an hour is a day")
        assertEquals(3, state(now + 3 * 86_400 + 1).daysLeft(now), "a fresh 3-day plan is 3 days")
        assertEquals(2, state(now + 3 * 86_400 - 7_200).daysLeft(now))
        assertNull(state(now).daysLeft(now), "over")
        assertNull(state(now - 5).daysLeft(now))
        assertNull(state(0).daysLeft(now), "never ends")
        assertTrue(state(0).active && state(0).signedIn.not(), "a token-less account is not signed in")
    }
}
