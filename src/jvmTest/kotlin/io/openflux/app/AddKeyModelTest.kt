package io.openflux.app

import io.openflux.app.add.AddKeyModel
import io.openflux.app.add.KeyResult
import io.openflux.app.nav.Dest
import io.openflux.desktop.FakeShareLinkCodec
import io.openflux.desktop.model.DocProfile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareTransport
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionDocument
import io.openflux.desktop.model.SubscriptionKind
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AddKeyModelTest {
    private val api = FakeSubApi()
    private val platform = TestPlatform()
    private val c = testContainer(platform = platform, subscriptions = api)
    private val key = "openflux://sub?u=https%3A%2F%2Fsub.example%2Fsub%2Fabc"

    private suspend fun link(name: String) = FakeShareLinkCodec().encode(
        ShareConfig(name = name, secret = "s".repeat(64), transports = listOf(ShareTransport(type = "direct", dial = "203.0.113.7:8445", priority = 50))),
    )

    @Test
    fun aProfileLinkIsShownAndAdded() = runTest {
        val m = AddKeyModel(c, this)
        m.onText(link("Нода друга")); advanceUntilIdle()
        val r = assertIs<KeyResult.Profile>(m.result)
        assertEquals("Нода друга", r.config.name)
        val dest = m.add(); advanceUntilIdle()
        val p = c.profiles.profiles.value.single()
        assertEquals(ProfileSource.Link, p.source)
        assertEquals(Dest.Profile(p.id), dest)
        assertEquals(p.id, c.settings.settings.value.selectedProfileId)
    }

    @Test
    fun aSubscriptionKeyIsPreviewedAndAdded() = runTest {
        api.document = SubscriptionDocument(title = "Мой VPN", status = SubStatus.ACTIVE, profiles = listOf(DocProfile("1", "Амстердам", "x")))
        val m = AddKeyModel(c, this)
        m.onText(key); advanceUntilIdle()
        val r = assertIs<KeyResult.Subscription>(m.result)
        assertEquals("Мой VPN", r.preview.document.title)
        assertEquals(Dest.Account, m.add())
        advanceUntilIdle()
        assertEquals(SubscriptionKind.Key, c.subscription.state.value?.kind)
    }

    @Test
    fun anEndedKeyCannotBeAdded() = runTest {
        api.document = SubscriptionDocument(status = SubStatus.REVOKED)
        val m = AddKeyModel(c, this)
        m.onText(key); advanceUntilIdle()
        val r = assertIs<KeyResult.Subscription>(m.result)
        assertEquals(false, r.canAdd)
    }

    @Test
    fun anythingElseIsSaidPlainly() = runTest {
        val m = AddKeyModel(c, this)
        m.onText("привет"); advanceUntilIdle()
        assertEquals("Это не ключ подписки, не ссылка openflux:// и не ссылка из бота", (m.result as KeyResult.Invalid).message)
        m.onText(""); advanceUntilIdle()
        assertIs<KeyResult.Empty>(m.result)
    }

    @Test
    fun aPasteTakesTheClipboardAndTheFirstLineOfSeveral() = runTest {
        val m = AddKeyModel(c, this)
        m.paste()
        assertEquals("В буфере обмена пусто", (m.result as KeyResult.Invalid).message)
        platform.clipboard = "\n  " + link("Первая") + "\n" + link("Вторая")
        m.paste(); advanceUntilIdle()
        assertEquals("Первая", (m.result as KeyResult.Profile).config.name)
    }

    @Test
    fun textFromOutsideIsTakenOnce() = runTest {
        c.pendingKey.value = key
        val m = AddKeyModel(c, this)
        advanceUntilIdle()
        assertEquals(key, m.text)
        assertEquals(null, c.pendingKey.value)
    }
}
