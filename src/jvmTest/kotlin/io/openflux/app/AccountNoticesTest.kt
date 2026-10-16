package io.openflux.app

import io.openflux.app.account.AccountNotice
import io.openflux.app.account.AccountNotifier
import io.openflux.app.account.accountNotices
import io.openflux.desktop.model.BotInvoice
import io.openflux.desktop.model.BotPendingInvoice
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotState
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.service.BotAccountManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the app tells the person by itself: the end of the subscription, a paused connection, a payment. */
class AccountNoticesTest {
    private val day = 86_400_000L
    private val now = 1_792_065_600_000L // 2026-10-15 12:00 UTC
    private val telegram = BotSession("ofs_x", 7, "ivan")
    private val email = BotSession("ofs_e", 10_000_000_000_000_001)
    private fun sub(days: Double) = BotSubscription(planId = "own30", planName = "Start", status = "active", expiresAt = (now + (days * day).toLong()) / 1000, maxSessions = 1, isActive = true)
    private val none = BotSubscription(planId = "none", status = "inactive")

    private fun run(s: BotState, noticed: Set<String> = emptySet()) = accountNotices(s, noticed, now, 0)

    @Test
    fun theLastThreeDaysAreSaidOnce() {
        val s = BotState(session = telegram, subscription = sub(2.5))
        val (first, keys) = run(s)
        assertEquals(listOf("Подписка заканчивается"), first.map { it.title })
        assertTrue(first.single().text.startsWith("Осталось 3 дня, до 18 октября 2026"), first.single().text)
        assertTrue(first.single().text.contains("@openflux_bot"), "a Telegram account may renew in the bot")
        val (again, _) = run(s, keys)
        assertTrue(again.isEmpty(), "said once")
        // Renewed: a new end, a new notice when it comes near again.
        assertTrue(run(s.copy(subscription = sub(30.0)), keys).first.isEmpty())
    }

    @Test
    fun anEmailAccountIsNotSentToTheBot() {
        val (n, _) = run(BotState(session = email, subscription = sub(1.0)))
        assertFalse(n.single().text.contains("бот"), n.single().text)
    }

    @Test
    fun farFromTheEndNothingIsSaid() {
        assertTrue(run(BotState(session = telegram, subscription = sub(10.0))).first.isEmpty())
    }

    @Test
    fun theEndIsSaidOnlyWhenASubscriptionHeldBefore() {
        val (_, held) = run(BotState(session = telegram, subscription = sub(20.0)))
        val (ended, keys) = run(BotState(session = telegram, subscription = none), held)
        assertEquals(listOf("Подписка закончилась"), ended.map { it.title })
        assertTrue(run(BotState(session = telegram, subscription = none), keys).first.isEmpty(), "said once")
        assertTrue(run(BotState(session = telegram, subscription = none)).first.isEmpty(), "a sign-in without a subscription is not an end")
    }

    @Test
    fun aSubscriptionNotAskedYetIsNotAnEnd() {
        val (_, held) = run(BotState(session = telegram, subscription = sub(20.0)))
        val (n, keys) = run(BotState(session = telegram, subscription = null), held)
        assertTrue(n.isEmpty())
        assertEquals(listOf("Подписка закончилась"), run(BotState(session = telegram, subscription = none), keys).first.map { it.title })
    }

    @Test
    fun aPausedConnectionIsSaidOnceAndAgainAfterItWasResumed() {
        val paused = BotState(session = telegram, subscription = sub(20.0), connectionNote = BotAccountManager.PAUSED_NOTE)
        val (n, keys) = run(paused)
        assertEquals(listOf(AccountNotice("paused", "Подключение приостановлено", BotAccountManager.PAUSED_NOTE)), n)
        val (_, resumed) = run(paused.copy(connectionNote = null), keys)
        assertEquals(1, run(paused, resumed).first.size)
    }

    @Test
    fun aPaymentIsSaidOnce() {
        val paid = BotState(session = email, subscription = sub(30.0), pendingInvoice = BotPendingInvoice("own30", "k", BotInvoice("i1", status = "paid")))
        val (n, keys) = run(paid)
        assertEquals(listOf("Оплата получена"), n.map { it.title })
        assertTrue(run(paid, keys).first.isEmpty())
    }

    @Test
    fun signedOutNothingIsSaidAndNothingKept() {
        assertEquals(Pair(emptyList<AccountNotice>(), emptySet<String>()), run(BotState(subscription = sub(1.0)), setOf("~active", "ending:1")))
    }

    @Test
    fun theNotifierSaysEachThingOnceAndKeepsWhatItSaid() = runTest {
        val state = MutableStateFlow(BotState(session = telegram, subscription = sub(2.5)))
        val saved = mutableListOf<Set<String>>()
        val n = AccountNotifier(state, { saved += it; state.value = state.value.copy(noticed = it.toList()) }, { now }, { 0 })
        val heard = mutableListOf<AccountNotice>()
        backgroundScope.launch { n.notices.collect { heard += it } }
        n.start(backgroundScope)
        runCurrent()
        assertEquals(listOf("Подписка заканчивается"), heard.map { it.title })
        state.value = state.value.copy(lastError = "сеть") // another change: nothing new to say
        runCurrent()
        assertEquals(1, heard.size)
        assertTrue(saved.last().any { it.startsWith("ending:") }, "kept, so a restart does not say it again")
    }
}
