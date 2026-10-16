package io.openflux.app

import io.openflux.app.account.SummaryTone
import io.openflux.app.account.accountSummary
import io.openflux.app.account.daysLeft
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotState
import io.openflux.desktop.model.BotSubscription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the window says of the OpenFlux account: honest about what GET /api/v1/subscription is (a summary of the slots). */
class AccountSummaryTest {
    private val day = 86_400_000L
    // 2026-10-15 12:00 UTC
    private val now = 1_792_065_600_000L
    private val telegram = BotSession("ofs_x", 7, "ivan")
    private val email = BotSession("ofs_e", 10_000_000_000_000_001)

    private fun sub(days: Double, slots: Int = 1, used: Long = 0, limit: Long = 0) = BotSubscription(
        planId = "own30", planName = "Start", status = "active", expiresAt = (now + (days * day).toLong()) / 1000,
        daysLeft = days.toInt(), maxSessions = slots, trafficUsedBytes = used, trafficLimitBytes = limit, isActive = true,
    )

    @Test
    fun signedOutThereIsNoSummary() {
        assertNull(accountSummary(BotState(), now, 0))
    }

    @Test
    fun daysAreCountedUpFromTheEndNotDownFromTheService() {
        assertEquals(1, daysLeft((now + day / 2) / 1000, now), "the last day is a day, not 0")
        assertEquals(28, daysLeft((now + 27 * day + 1_000) / 1000, now))
        assertEquals(0, daysLeft((now - day) / 1000, now))
    }

    @Test
    fun oneSlotShowsItsTariffEndAndTraffic() {
        val s = accountSummary(BotState(session = telegram, subscription = sub(27.5, used = 1_073_741_824, limit = 32_212_254_720)), now, 0)!!
        assertEquals("Telegram, @ivan", s.signIn)
        assertEquals("Действует", s.state)
        assertEquals(SummaryTone.Ok, s.tone)
        assertEquals(
            listOf("Тариф" to "Start", "Срок" to "до 12 ноября 2026, осталось 28 дней", "Трафик" to "1 ГБ из 30 ГБ"),
            s.facts,
        )
    }

    @Test
    fun severalSlotsAreNotShownAsOne() {
        val s = accountSummary(BotState(session = telegram, subscription = sub(10.0, slots = 3, used = 5_368_709_120, limit = 32_212_254_720)), now, 0)!!
        assertEquals("Мест", s.facts[0].first)
        assertEquals("3", s.facts[0].second)
        assertEquals("Ближайшее окончание", s.facts[1].first)
        assertTrue(s.facts[1].second.startsWith("25 октября 2026"), s.facts[1].second)
        assertFalse(s.facts.any { it.second.contains(" из ") }, "the account's traffic is not compared with one slot's limit")
        assertEquals("Трафик всех мест" to "5 ГБ", s.facts.last())
        assertEquals("Купить ещё место", s.buyLabel)
    }

    @Test
    fun theLastDaysAskForAttention() {
        val s = accountSummary(BotState(session = telegram, subscription = sub(2.2)), now, 0)!!
        assertEquals(SummaryTone.Attention, s.tone)
        assertEquals("Заканчивается: осталось 3 дня", s.state)
    }

    @Test
    fun withoutASubscriptionTheWayIsToBuy() {
        val s = accountSummary(BotState(session = email, subscription = BotSubscription(planId = "none", planName = "No Active Subscription", status = "inactive")), now, 0)!!
        assertEquals("Нет действующей подписки", s.state)
        assertEquals(SummaryTone.Attention, s.tone)
        assertTrue(s.facts.isEmpty(), "the service's English placeholder is not shown")
        assertEquals("Купить подписку", s.buyLabel)
        assertFalse(s.renewInBot)
    }

    @Test
    fun notAskedYetSaysSo() {
        val s = accountSummary(BotState(session = telegram), now, 0)!!
        assertEquals("Обновляю…", s.state)
        assertEquals(SummaryTone.Unknown, s.tone)
    }

    @Test
    fun aTelegramAccountRenewsInTheBotAnEmailOneIsToldItCannotYet() {
        val tg = accountSummary(BotState(session = telegram, subscription = sub(2.0)), now, 0)!!
        assertTrue(tg.renewInBot)
        assertNull(tg.note)
        val mail = accountSummary(BotState(session = email, subscription = sub(2.0)), now, 0)!!
        assertFalse(mail.renewInBot, "an email account has no bot")
        assertTrue(mail.note!!.contains("Продлить"), mail.note)
        assertFalse(mail.note!!.contains("бот"))
        assertEquals("по почте", mail.signIn, "the account number means nothing to the person")
        val far = accountSummary(BotState(session = email, subscription = sub(20.0)), now, 0)!!
        assertNull(far.note, "nothing to say while the end is far")
    }

    @Test
    fun theDateIsTheDevicesOwn() {
        // 23:30 UTC on the 15th is already the 16th in Moscow.
        val sub = sub(0.0).copy(expiresAt = 1_792_107_000)
        val s = accountSummary(BotState(session = telegram, subscription = sub), 1_792_107_000_000 - 2 * day, 3 * 3600)!!
        assertTrue(s.facts.first { it.first == "Срок" }.second.startsWith("до 16 октября 2026"), s.facts.toString())
    }
}
