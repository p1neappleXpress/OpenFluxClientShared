package io.openflux.desktop

import io.openflux.app.TestPlatform
import io.openflux.app.testContainer
import io.openflux.desktop.model.BotDocument
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotInvoice
import io.openflux.desktop.model.BotPaymentMethod
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSlot
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.service.BotAccountManager
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import io.openflux.desktop.service.InMemoryBotRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The manager over the service of 0e3f2db: the slots list, Start/Stop, the own documents of a manual slot, the ways to pay. */
class BotSlotsManagerTest {
    private val session = BotSession("ofs_x", 7, "ivan", 1_900_000_000)
    private val slotId = "5f0c7c2e-1d1a-4b0e-9a55-2b0d0c9e7a10"
    private val manualId = "6f0c7c2e-1d1a-4b0e-9a55-2b0d0c9e7a10"

    private fun running() = BotSlot(slotId, "Ноутбук", "Start", "auto", "running", 1_794_412_374, 19, canStop = true)
    private fun freeManual() = BotSlot(manualId, "", "Start", "manual", "free", 1_794_412_374, canStart = false, needsSetup = true)

    private class Bot : BotApi {
        var slots = mutableListOf<BotSlot>()
        var slotsError: BotException? = null
        var documents = mutableListOf<BotDocument>()
        var methods = listOf(BotPaymentMethod("platega", "Карта или СБП"))
        val slotCalls = mutableListOf<String>()
        val docCalls = mutableListOf<String>()
        val invoices = mutableListOf<Triple<String, String, String>>()
        val connectOrders = mutableListOf<List<String>>()
        override suspend fun subscription(token: String) = BotSubscription(isActive = true)
        override suspend fun signOut(token: String) {}
        override suspend fun resolve(connectToken: String): BotConnection = throw BotException("not_found", "Не найдено")
        override suspend fun sessions(token: String) = emptyList<BotSessionItem>()
        override suspend fun createSession(token: String): BotSessionItem = throw BotException("forbidden", "x")
        override suspend fun slots(token: String): List<BotSlot> {
            slotsError?.let { throw it }
            return slots
        }
        override suspend fun startSlot(token: String, slotId: String): BotSlot {
            slotCalls += "start $slotId"
            return slots.first { it.id == slotId }.copy(state = "running", canStart = false, canStop = true, connectToken = "AAAAAAAAAAAAAAAAAAAAAA")
        }
        override suspend fun stopSlot(token: String, slotId: String): BotSlot {
            slotCalls += "stop $slotId"
            return slots.first { it.id == slotId }.copy(state = "paused", canStart = true, canStop = false)
        }
        override suspend fun documents(token: String, slotId: String): List<BotDocument> = documents
        override suspend fun addDocument(token: String, slotId: String, url: String): BotDocument {
            docCalls += "add $url"
            val d = BotDocument("d${documents.size + 1}", "yandex", url, "ok")
            documents += d
            return d
        }
        override suspend fun deleteDocument(token: String, slotId: String, documentId: String) {
            docCalls += "delete $documentId"
            documents.removeAll { it.id == documentId }
        }
        override suspend fun checkDocument(token: String, slotId: String, documentId: String): BotDocument {
            docCalls += "check $documentId"
            return documents.first { it.id == documentId }.copy(status = "ok")
        }
        override suspend fun connectSlot(token: String, slotId: String, documentIds: List<String>): BotSlot {
            connectOrders += documentIds
            return slots.first { it.id == slotId }.copy(state = "running", needsSetup = false, canStop = true, connectToken = "AAAAAAAAAAAAAAAAAAAAAA")
        }
        override suspend fun paymentMethods(token: String): List<BotPaymentMethod> = methods
        override suspend fun createInvoice(token: String, termCode: String, idempotencyKey: String, gateway: String): BotInvoice {
            invoices += Triple(termCode, idempotencyKey, gateway)
            return BotInvoice("0b7c3c3e-5a39-4e43-9c8e-0d7d0d6c1a11", "https://pay.example/x", "30.00", "active")
        }
        override suspend fun invoice(token: String, id: String): BotInvoice = throw BotException("not_found", "Не найдено")
    }

    private fun env(api: Bot = Bot()): Pair<BotAccountManager, Bot> {
        val c = testContainer(platform = TestPlatform())
        val accounts = testAccounts(CoroutineScope(Dispatchers.Unconfined))
        val m = BotAccountManager(api, InMemoryBotRepository(), c.profiles, c.shareCodec, c.platform, c.connection, accounts, newIdempotencyKey = { "key-1-of-the-test" })
        return Pair(m, api)
    }

    private suspend fun signedIn(api: Bot = Bot()): Pair<BotAccountManager, Bot> {
        val (m, a) = env(api)
        m.completeLogin(session)
        return Pair(m, a)
    }

    @Test
    fun aRefreshBringsTheSlotsIn() = runTest {
        val api = Bot()
        api.slots = mutableListOf(running(), freeManual())
        val (m, _) = signedIn(api)
        assertEquals(listOf(running(), freeManual()), m.state.value.slots)
    }

    @Test
    fun aServiceWithoutTheSlotsApiLeavesThemUnknown() = runTest {
        val api = Bot()
        api.slotsError = BotException("not_found", "Не найдено")
        val (m, _) = signedIn(api)
        assertNull(m.state.value.slots, "the manager reads not_found as 'no slots API'")
        assertNull(m.state.value.lastError)
    }

    @Test
    fun startTurnsAFreeSlotIntoARunningOneAndKeepsItInTheList() = runTest {
        val api = Bot()
        api.slots = mutableListOf(running().copy(state = "paused", canStart = true, canStop = false))
        val (m, _) = signedIn(api)
        val slot = m.startSlot(slotId)
        assertEquals("running", slot.state)
        assertEquals(slot, m.state.value.slots!!.single(), "the list on screen takes the answered slot")
        assertEquals(listOf("start $slotId"), api.slotCalls)
    }

    @Test
    fun stopPausesTheSlotAndKeepsItInTheList() = runTest {
        val api = Bot()
        api.slots = mutableListOf(running())
        val (m, _) = signedIn(api)
        val slot = m.stopSlot(slotId)
        assertEquals("paused", slot.state)
        assertEquals(slot, m.state.value.slots!!.single())
        assertEquals(listOf("stop $slotId"), api.slotCalls)
    }

    @Test
    fun aSlotActionRefusedByTheServiceIsRaisedNotSwallowed() = runTest {
        val base = Bot().also { it.slots = mutableListOf(running().copy(state = "paused", canStart = true, canStop = false)) }
        val failing = object : BotApi by base {
            override suspend fun startSlot(token: String, slotId: String): BotSlot =
                throw BotException("slot_changed", "Место уже в другом состоянии. Обновите список")
        }
        val c = testContainer(platform = TestPlatform())
        val m = BotAccountManager(failing, InMemoryBotRepository(), c.profiles, c.shareCodec, c.platform, c.connection, testAccounts(CoroutineScope(Dispatchers.Unconfined)), newIdempotencyKey = { "key-1-of-the-test" })
        m.completeLogin(session)
        assertEquals("slot_changed", assertFailsWith<BotException> { m.startSlot(slotId) }.code)
    }

    @Test
    fun signedOutASlotCannotBeTouched() = runTest {
        val api = Bot()
        val (m, a) = env(api)
        assertEquals("unauthorized", assertFailsWith<BotException> { m.startSlot(slotId) }.code)
        assertTrue(a.slotCalls.isEmpty())
    }

    @Test
    fun theDocumentsOfAManualSlotAreAsked() = runTest {
        val api = Bot()
        api.slots = mutableListOf(freeManual())
        api.documents = mutableListOf(BotDocument("d1", "yandex", "Яндекс Диск …a1b2", "ok"))
        val (m, _) = signedIn(api)
        assertEquals(listOf(BotDocument("d1", "yandex", "Яндекс Диск …a1b2", "ok")), m.documents(manualId))
    }

    @Test
    fun addingCheckingAndDeletingADocument() = runTest {
        val api = Bot()
        api.slots = mutableListOf(freeManual())
        val (m, _) = signedIn(api)
        val added = m.addDocument(manualId, "https://disk.yandex.ru/i/abc")
        assertEquals("https://disk.yandex.ru/i/abc", added.label)
        m.checkDocument(manualId, added.id)
        m.deleteDocument(manualId, added.id)
        assertEquals(listOf("add https://disk.yandex.ru/i/abc", "check ${added.id}", "delete ${added.id}"), api.docCalls)
    }

    @Test
    fun connectingAManualSlotKeepsTheOrderAndUpdatesTheSlot() = runTest {
        val api = Bot()
        api.slots = mutableListOf(freeManual())
        val (m, _) = signedIn(api)
        val slot = m.connectSlot(manualId, listOf("d2", "d1"))
        assertEquals("running", slot.state)
        assertEquals(slot, m.state.value.slots!!.single())
        assertEquals(listOf(listOf("d2", "d1")), api.connectOrders, "the order is kept")
    }

    @Test
    fun thePaymentMethodsAreAskedWithTheSession() = runTest {
        val (m, _) = signedIn()
        assertEquals(listOf(BotPaymentMethod("platega", "Карта или СБП")), m.paymentMethods())
    }

    @Test
    fun signedOutThereIsNoPaymentMethodToAskFor() = runTest {
        val (m, _) = env()
        assertTrue(m.paymentMethods().isEmpty())
    }

    @Test
    fun theChosenWayToPayGoesWithThePurchase() = runTest {
        val (m, api) = signedIn()
        m.checkout("own30", gateway = "cryptopay")
        assertEquals(Triple("own30", "key-1-of-the-test", "cryptopay"), api.invoices.single())
    }
}
