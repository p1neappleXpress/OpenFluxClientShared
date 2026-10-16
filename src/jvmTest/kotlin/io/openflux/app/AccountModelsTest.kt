package io.openflux.app

import io.openflux.app.account.SubscriptionModel
import io.openflux.desktop.model.PlansResult
import io.openflux.desktop.model.accountLabel
import io.openflux.desktop.model.SubPlan
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionDocument
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountModelsTest {
    private val api = FakeSubApi()
    private val c = testContainer(subscriptions = api)

    @Test
    fun theWholeFlowOfBuying() = runTest {
        api.plans = PlansResult(listOf(SubPlan("month", "Месяц", 30, 2, "299 ₽")))
        c.subscription.login("ivan", "longenough1")
        val s = SubscriptionModel(c, this)
        s.openPlans(); advanceUntilIdle()
        assertEquals("month", s.selectedPlan)
        s.checkout(); runCurrent()
        assertNotNull(s.order)
        api.orderStatus = "paid"
        advanceTimeBy(9_000); runCurrent()
        assertTrue(s.paid, "the poll noticed the payment")
        s.closePlans()
    }

    @Test
    fun changingThePasswordAsksForTheOldOne() = runTest {
        c.subscription.login("ivan", "longenough1")
        advanceUntilIdle()
        val s = SubscriptionModel(c, this)
        s.changePassword("", "newpassword1", "newpassword1")
        assertEquals("Введите текущий пароль", s.dialogError)
        s.changePassword("longenough1", "newpassword1", "newpassword1"); advanceUntilIdle()
        assertNull(s.dialogError)
        assertEquals("Пароль изменён. На других устройствах войдите заново", s.notice)
    }

    @Test
    fun deletingTheAccountNeedsThePasswordAndSignsOut() = runTest {
        c.subscription.login("ivan", "longenough1")
        advanceUntilIdle()
        val s = SubscriptionModel(c, this)
        s.deleteAccount(""); advanceUntilIdle()
        assertEquals("Введите пароль", s.dialogError)
        s.deleteAccount("longenough1"); advanceUntilIdle()
        assertNull(c.subscription.state.value)
    }

    @Test
    fun aTrialStarts() = runTest {
        api.document = SubscriptionDocument(status = SubStatus.EXPIRED)
        c.subscription.login("ivan", "longenough1")
        advanceUntilIdle()
        val s = SubscriptionModel(c, this)
        s.startTrial(); advanceUntilIdle()
        assertNull(s.error)
    }

    @Test
    fun anAccountSaysHowItSignsIn() {
        assertEquals("Telegram, id 7", io.openflux.desktop.model.BotSession("t", 7).accountLabel())
        assertEquals("по почте, № 10000000000000001", io.openflux.desktop.model.BotSession("t", 10000000000000001).accountLabel())
    }
}
