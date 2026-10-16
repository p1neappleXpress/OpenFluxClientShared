package io.openflux.app

import io.openflux.app.account.SubscriptionModel
import io.openflux.app.account.documentKindName
import io.openflux.app.account.documentStateText
import io.openflux.app.account.slotFacts
import io.openflux.app.account.slotStateText
import io.openflux.app.account.slotTitle
import io.openflux.desktop.model.BotDocument
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSlot
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The places section: how a place is named and described, and the model that starts, stops and sets one up. */
class SlotsTest {
    private val slotId = "5f0c7c2e-1d1a-4b0e-9a55-2b0d0c9e7a10"
    private val manualId = "6f0c7c2e-1d1a-4b0e-9a55-2b0d0c9e7a10"
    private val day = 86_400L

    private fun autoRunning() = BotSlot(slotId, "Ноутбук", "Start", "auto", "running", 1_794_412_374, 19, canStop = true, connectToken = "AAAAAAAAAAAAAAAAAAAAAA")
    private fun autoPaused() = BotSlot(slotId, "", "Start", "auto", "paused", 1_794_412_374, 19, canStart = true, connectToken = "AAAAAAAAAAAAAAAAAAAAAA")
    private fun freeManual() = BotSlot(manualId, "", "Start", "manual", "free", 1_794_412_374, needsSetup = true)
    private fun doc(id: String, kind: String = "yandex", status: String = "ok") = BotDocument(id, kind, "Яндекс Диск …a1b2", status)

    // ---- what a place looks like ----

    @Test
    fun aPlaceIsNamedByThePersonElseByItsNumber() {
        assertEquals("Ноутбук", slotTitle(autoRunning()), "the person's own name wins")
        assertEquals("Место #19", slotTitle(autoPaused()), "else the bot's number")
        assertEquals("Start", slotTitle(BotSlot("x", planName = "Start")), "else the tariff")
        assertEquals("Место", slotTitle(BotSlot("x")))
    }

    @Test
    fun aPlaceSaysWhatItIsDoing() {
        assertEquals("работает", slotStateText(autoRunning()))
        assertEquals("приостановлено", slotStateText(autoPaused()))
        assertEquals("свободно", slotStateText(BotSlot("x", state = "free")))
        assertEquals("не настроено", slotStateText(freeManual()), "a manual place without documents is not set up yet")
    }

    @Test
    fun aPlacesFactsAreTheTariffItsStateAndTheTerm() {
        assertEquals("Start · работает", slotFacts(autoRunning().copy(expiresAt = 0), 3 * 3600))
        assertTrue(slotFacts(autoRunning(), 3 * 3600).contains("до "), "the term is shown when there is one")
    }

    @Test
    fun aDocumentsServiceAndStateAreNamedInRussian() {
        assertEquals("Яндекс Диск", documentKindName("yandex"))
        assertEquals("Облако Mail.ru", documentKindName("mailru"))
        assertEquals("Телемост", documentKindName("telemost"))
        assertEquals("Документ", documentKindName("unknown-kind"))
        assertEquals("проверен", documentStateText(doc("d1", status = "ok")))
        assertTrue(documentStateText(doc("d1", status = "unknown")).contains("не проверялся"))
    }

    // ---- the model ----

    private class Bot : BotApi {
        var slots = mutableListOf<BotSlot>()
        var documents = mutableListOf<BotDocument>()
        val connectOrders = mutableListOf<List<String>>()
        var nextDoc = 10
        override suspend fun subscription(token: String) = BotSubscription(isActive = true)
        override suspend fun signOut(token: String) {}
        override suspend fun resolve(connectToken: String): BotConnection = throw BotException("not_found", "Не найдено")
        override suspend fun sessions(token: String) = emptyList<BotSessionItem>()
        override suspend fun createSession(token: String): BotSessionItem = throw BotException("forbidden", "x")
        override suspend fun slots(token: String): List<BotSlot> = slots
        override suspend fun documents(token: String, slotId: String): List<BotDocument> = documents
        override suspend fun startSlot(token: String, slotId: String): BotSlot =
            slots.first { it.id == slotId }.copy(state = "running", canStart = false, canStop = true)
        override suspend fun stopSlot(token: String, slotId: String): BotSlot =
            slots.first { it.id == slotId }.copy(state = "paused", canStart = true, canStop = false)
        override suspend fun addDocument(token: String, slotId: String, url: String): BotDocument {
            val d = BotDocument("d${nextDoc++}", "yandex", url, "ok")
            documents += d
            return d
        }
        override suspend fun deleteDocument(token: String, slotId: String, documentId: String) {
            documents.removeAll { it.id == documentId }
        }
        override suspend fun checkDocument(token: String, slotId: String, documentId: String): BotDocument =
            documents.first { it.id == documentId }.copy(status = "ok")
        override suspend fun connectSlot(token: String, slotId: String, documentIds: List<String>): BotSlot {
            connectOrders += documentIds
            return slots.first { it.id == slotId }.copy(state = "running", needsSetup = false, canStop = true, connectToken = "AAAAAAAAAAAAAAAAAAAAAA")
        }
    }

    private val bot = Bot()
    private val c = testContainer(bot = bot, platform = TestPlatform())

    private suspend fun signedIn() = c.bot.completeLogin(BotSession("ofs_x", 7, "ivan"))

    @Test
    fun openingAManualPlaceLoadsItsDocuments() = runTest {
        bot.slots = mutableListOf(freeManual())
        bot.documents = mutableListOf(doc("d1"), doc("d2"))
        signedIn()
        val m = SubscriptionModel(c, this)
        m.openSlotSetup(manualId)
        advanceUntilIdle()
        assertEquals(listOf("d1", "d2"), m.documents?.map { it.id })
        assertEquals(manualId, m.openSlot?.id, "the sheet reads the place from the freshest bot state")
        m.closeSlotSetup()
        assertNull(m.openSlotId)
    }

    @Test
    fun connectingAManualPlaceSendsAllItsDocumentsInOrder() = runTest {
        bot.slots = mutableListOf(freeManual())
        bot.documents = mutableListOf(doc("d1"), doc("d2"))
        signedIn()
        val m = SubscriptionModel(c, this)
        m.openSlotSetup(manualId)
        advanceUntilIdle()
        m.connectSlot()
        advanceUntilIdle()
        assertEquals(listOf(listOf("d1", "d2")), bot.connectOrders, "all the place's documents, in their order")
        assertEquals("Место настроено. Подключение появится само", m.notice)
        assertNull(m.openSlotId, "the sheet closes once the place is set up")
    }

    @Test
    fun aManualPlaceWithNoDocumentsConnectsThroughTheServiceRooms() = runTest {
        bot.slots = mutableListOf(freeManual())
        bot.documents = mutableListOf()
        signedIn()
        val m = SubscriptionModel(c, this)
        m.openSlotSetup(manualId)
        advanceUntilIdle()
        m.connectSlot()
        advanceUntilIdle()
        // No documents of its own: the service makes cups.online and Avito rooms, which need no account.
        assertEquals(listOf(emptyList<String>()), bot.connectOrders, "connected with no documents")
    }

    @Test
    fun aManualPlaceIsNotConnectedBeforeItsDocumentsAreRead() = runTest {
        bot.slots = mutableListOf(freeManual())
        signedIn()
        val m = SubscriptionModel(c, this)
        m.connectSlot() // no sheet open, nothing read
        advanceUntilIdle()
        assertTrue(bot.connectOrders.isEmpty(), "nothing is sent before the place's documents are known")
    }

    @Test
    fun startingAndStoppingAPlaceSaySoAndKeepItOnScreen() = runTest {
        bot.slots = mutableListOf(autoPaused())
        signedIn()
        val m = SubscriptionModel(c, this)
        m.startSlot(slotId)
        advanceUntilIdle()
        assertEquals("Место запущено", m.notice)
        assertEquals("running", c.bot.state.value.slots!!.single().state)
        m.stopSlot(slotId)
        advanceUntilIdle()
        assertEquals("Место остановлено", m.notice)
        assertEquals("paused", c.bot.state.value.slots!!.single().state)
    }

    @Test
    fun addingAndRemovingADocumentKeepsTheListFresh() = runTest {
        bot.slots = mutableListOf(freeManual())
        bot.documents = mutableListOf(doc("d1"))
        signedIn()
        val m = SubscriptionModel(c, this)
        m.openSlotSetup(manualId)
        advanceUntilIdle()
        m.addDocument("https://disk.yandex.ru/i/new")
        advanceUntilIdle()
        assertEquals(listOf("d1", "d10"), m.documents?.map { it.id })
        m.removeDocument("d1")
        advanceUntilIdle()
        assertEquals(listOf("d10"), m.documents?.map { it.id })
    }
}
