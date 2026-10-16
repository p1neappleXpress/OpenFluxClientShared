package io.openflux.desktop

import io.openflux.desktop.model.AccountInfo
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.AuthResult
import io.openflux.desktop.model.CaptchaPrompt
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.DeviceInfo
import io.openflux.desktop.model.DocProfile
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.LogLine
import io.openflux.desktop.model.OrderInfo
import io.openflux.desktop.model.PlansResult
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ServiceEndpoint
import io.openflux.desktop.model.ServiceMove
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareTransport
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionDocument
import io.openflux.desktop.model.SubscriptionException
import io.openflux.desktop.model.SubscriptionKind
import io.openflux.desktop.model.TrafficStats
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.managed
import io.openflux.desktop.service.ConnectionService
import io.openflux.desktop.service.InMemorySubscriptionRepository
import io.openflux.desktop.service.PlatformServices
import io.openflux.desktop.service.ProfileRepository
import io.openflux.desktop.service.SubscriptionApi
import io.openflux.desktop.service.SubscriptionManager
import io.openflux.app.platform.BrowserPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionManagerTest {
    private val ep = ServiceEndpoint("https://203.0.113.5:8443", "ab".repeat(32))
    private val codec = FakeShareLinkCodec()

    private suspend fun link(name: String, dial: String = "203.0.113.5:34443", key: String = "ab".repeat(32)) = codec.encode(
        ShareConfig(
            name = name, negotiate = true, secret = key, context = "https://example.com/doc",
            transports = listOf(ShareTransport(type = "mailru", url = "https://cloud.mail.ru/public/aaaa/bbbb", priority = 90), ShareTransport(type = "direct", dial = dial, priority = 50)),
        ),
    )

    private class Env(val manager: SubscriptionManager, val api: FakeApi, val repo: InMemorySubscriptionRepository, val profiles: FakeProfiles, val connection: FakeConnection, val platform: FakePlatform)

    private fun env(): Env {
        val api = FakeApi()
        val repo = InMemorySubscriptionRepository()
        val profiles = FakeProfiles()
        val platform = FakePlatform()
        val connection = FakeConnection()
        return Env(SubscriptionManager(api, repo, profiles, FakeShareLinkCodec(), platform, connection, ep), api, repo, profiles, connection, platform)
    }

    private suspend fun Env.doc(vararg profiles: Pair<String, String>, status: String = SubStatus.ACTIVE, expires: Long = 2_000_000_000, announce: String = "") {
        api.document = SubscriptionDocument(
            title = "OpenFlux", status = status, expires = expires, updateHours = 6, support = "https://t.me/support", announce = announce,
            profiles = profiles.map { (id, name) -> DocProfile(id, name, link(name)) },
        )
    }

    @Test
    fun registeringSignsInAndFillsTheLocations() = runTest {
        val e = env()
        e.doc("de" to "Германия", "nl" to "Нидерланды")
        val auth = e.manager.register(" Ivan ", "correct horse battery")
        assertEquals("Ivan", e.api.calls.first { it.startsWith("register") }.substringAfter(":").substringBefore("|"), "the login is trimmed")
        assertEquals("Тест-устройство", e.api.calls.first { it.startsWith("register") }.substringAfter("|"))
        assertEquals("RECOVERY-KEY", auth.recoveryKey)

        val s = assertNotNull(e.repo.state.value)
        assertEquals(SubscriptionKind.Account, s.kind)
        assertEquals("token-1", s.deviceToken)
        assertTrue(s.signedIn && s.active)
        assertEquals("OpenFlux", s.title)
        assertEquals(2_000_000_000, s.expires)

        val ps = e.profiles.profiles.value
        assertEquals(listOf("sub-de", "sub-nl"), ps.map { it.id })
        assertEquals(listOf("Германия", "Нидерланды"), ps.map { it.name })
        assertTrue(ps.all { it.managed && it.source == ProfileSource.Subscription && it.session && it.secret.isNotEmpty() })
    }

    @Test
    fun refreshingUpdatesKeepsAndRemovesLocations() = runTest {
        val e = env()
        e.doc("de" to "Германия", "nl" to "Нидерланды")
        e.manager.login("ivan", "pw")
        val before = e.profiles.profiles.value.first { it.id == "sub-de" }
        e.platform.clock += 60_000

        // de gets a new key and another name, nl is gone, fi is new.
        e.api.document = SubscriptionDocument(
            title = "OpenFlux", status = SubStatus.ACTIVE, expires = 2_100_000_000, updateHours = 12,
            profiles = listOf(DocProfile("de", "Германия 2", link("Германия 2", key = "cd".repeat(32))), DocProfile("fi", "Финляндия", link("Финляндия"))),
        )
        assertTrue(e.manager.refresh())
        val ps = e.profiles.profiles.value
        assertEquals(setOf("sub-de", "sub-fi"), ps.map { it.id }.toSet())
        val de = ps.first { it.id == "sub-de" }
        assertEquals("Германия 2", de.name)
        assertEquals("cd".repeat(32), de.secret, "a rotated key reaches the profile")
        assertEquals(before.createdAt, de.createdAt, "the same location keeps its place in history")
        assertEquals(12, e.repo.state.value?.updateHours)
        assertEquals(2_100_000_000, e.repo.state.value?.expires)
    }

    @Test
    fun anExpiredSubscriptionEmptiesTheListAndStopsTheConnection() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.connection.connect(e.profiles.profiles.value.first(), null)
        assertTrue(e.connection.state.value.isActive)

        e.api.document = SubscriptionDocument(status = SubStatus.EXPIRED, expires = 1_700_000_000)
        assertTrue(e.manager.refresh())
        assertTrue(e.profiles.profiles.value.isEmpty())
        assertFalse(e.connection.state.value.isActive, "the tunnel through a location of an ended subscription is stopped")
        val s = assertNotNull(e.repo.state.value, "the card stays, with the way to renew")
        assertEquals(SubStatus.EXPIRED, s.status)
        assertFalse(s.active)
        assertTrue(s.signedIn)
    }

    @Test
    fun aRevokedSubscriptionAlsoEmptiesTheList() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.api.document = SubscriptionDocument(status = SubStatus.REVOKED)
        e.manager.refresh()
        assertTrue(e.profiles.profiles.value.isEmpty())
        assertEquals(SubStatus.REVOKED, e.repo.state.value?.status)
    }

    @Test
    fun yourOwnProfilesAreNeverTouched() = runTest {
        val e = env()
        val own = Profile(id = "mine", name = "Моя нода", source = ProfileSource.Node)
        e.profiles.upsert(own)
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.api.document = SubscriptionDocument(status = SubStatus.EXPIRED)
        e.manager.refresh()
        e.manager.signOut()
        assertEquals(listOf(own), e.profiles.profiles.value)
    }

    @Test
    fun aDeadTokenSignsTheDeviceOut() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.api.failure = SubscriptionException("unauthorized", "Войдите в аккаунт.")
        assertFalse(e.manager.refresh())
        assertNull(e.repo.state.value, "back to the sign-in")
        assertTrue(e.profiles.profiles.value.isEmpty())
    }

    @Test
    fun noNetworkKeepsWhatThereIs() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.api.failure = SubscriptionException(SubscriptionException.NETWORK, "Сервис не отвечает. Проверьте сеть.")
        assertFalse(e.manager.refresh(), "a failure is reported, not thrown")
        assertEquals(1, e.profiles.profiles.value.size)
        val s = assertNotNull(e.repo.state.value)
        assertEquals("Сервис не отвечает. Проверьте сеть.", s.lastError)
        assertTrue(s.signedIn && s.active)
        e.api.failure = null
        assertTrue(e.manager.refresh())
        assertNull(e.repo.state.value?.lastError)
    }

    @Test
    fun aLinkThisBuildCannotReadIsSkipped() = runTest {
        val e = env()
        e.api.document = SubscriptionDocument(
            status = SubStatus.ACTIVE, expires = 2_000_000_000,
            profiles = listOf(DocProfile("bad", "Сломанная", "openflux://v1/test:{not json"), DocProfile("de", "Германия", link("Германия"))),
        )
        e.manager.login("ivan", "pw")
        assertEquals(listOf("sub-de"), e.profiles.profiles.value.map { it.id })
    }

    @Test
    fun theDeviceLimitComesWithTheDevicesAndReplacingOneWorks() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        val devices = listOf(DeviceInfo("d1", "Телефон Ивана", 1, 2))
        e.api.loginFailure = SubscriptionException("device_limit", "Достигнут предел устройств.", devices)
        val ex = assertFailsWith<SubscriptionException> { e.manager.login("ivan", "pw") }
        assertEquals(devices, ex.devices)
        assertNull(e.repo.state.value, "nothing is kept for a login that did not finish")

        e.api.loginFailure = null
        e.manager.login("ivan", "pw", replaceDeviceId = "d1")
        assertTrue(e.api.calls.any { it == "login:ivan|replace=d1|Тест-устройство" })
        assertNotNull(e.repo.state.value)
    }

    @Test
    fun aWrongPasswordLeavesTheStateAlone() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.api.loginFailure = SubscriptionException("invalid_credentials", "Неверный логин или пароль.")
        assertFailsWith<SubscriptionException> { e.manager.login("ivan", "bad") }
        assertEquals("token-1", e.repo.state.value?.deviceToken, "the signed-in account survives a failed attempt to sign in again")
        assertEquals(1, e.profiles.profiles.value.size)
    }

    @Test
    fun aKeyIsPreviewedBeforeItIsKept() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        assertEquals("bad_invite", assertFailsWith<SubscriptionException> { e.manager.previewKey("hello") }.code)
        assertEquals("bad_invite", assertFailsWith<SubscriptionException> { e.manager.previewKey("openflux://v1/abc") }.code)

        val preview = e.manager.previewKey("https://203.0.113.5:8443/sub/TOKEN#pin=${"ab".repeat(32)}")
        assertEquals(1, preview.document.profiles.size)
        assertEquals("https://203.0.113.5:8443/sub/TOKEN", e.api.calls.last { it.startsWith("key") }.removePrefix("key:"))
        assertNull(e.repo.state.value, "a preview keeps nothing")
        assertTrue(e.profiles.profiles.value.isEmpty())

        e.manager.addKey(preview)
        val s = assertNotNull(e.repo.state.value)
        assertEquals(SubscriptionKind.Key, s.kind)
        assertEquals("https://203.0.113.5:8443/sub/TOKEN", s.keyUrl)
        assertTrue(s.signedIn, "a key needs no account")
        assertEquals(listOf("sub-de"), e.profiles.profiles.value.map { it.id })

        // Refreshing asks the key's address, not the account's.
        e.api.calls.clear()
        e.manager.refresh()
        assertEquals(listOf("key:https://203.0.113.5:8443/sub/TOKEN"), e.api.calls)
    }

    @Test
    fun signingOutRemovesTheProfilesEvenIfTheServiceDoesNotAnswer() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.connection.connect(e.profiles.profiles.value.first(), null)
        e.api.logoutFailure = SubscriptionException(SubscriptionException.NETWORK, "Сервис не отвечает.")
        e.manager.signOut()
        assertTrue(e.api.calls.contains("logout:token-1"))
        assertNull(e.repo.state.value)
        assertTrue(e.profiles.profiles.value.isEmpty())
        assertFalse(e.connection.state.value.isActive)
    }

    @Test
    fun deletingTheAccountNeedsThePasswordAndSignsOut() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.api.deleteFailure = SubscriptionException("invalid_credentials", "Неверный пароль.")
        assertFailsWith<SubscriptionException> { e.manager.deleteAccount("bad") }
        assertNotNull(e.repo.state.value, "a wrong password deletes nothing")
        e.api.deleteFailure = null
        e.manager.deleteAccount("pw")
        assertNull(e.repo.state.value)
        assertTrue(e.profiles.profiles.value.isEmpty())
    }

    @Test
    fun paidOrderRefreshesAndPendingDoesNot() = runTest {
        val e = env()
        e.doc("de" to "Германия", expires = 1_900_000_000)
        e.manager.login("ivan", "pw")
        val order = e.manager.checkout("month")
        assertEquals("pending", order.status)
        e.api.calls.clear()
        assertFalse(e.manager.order(order.orderId).paid)
        assertTrue(e.api.calls.none { it.startsWith("document") }, "no refresh while the order waits")

        e.api.orderStatus = "paid"
        e.doc("de" to "Германия", expires = 2_100_000_000)
        assertTrue(e.manager.order(order.orderId).paid)
        assertEquals(2_100_000_000, e.repo.state.value?.expires, "the new end shows at once")
    }

    // ---- an order waited for across restarts (C1) ----

    /** The app is closed while the person pays in the bank's app: a new manager on the same storage finds the order. */
    @Test
    fun anOrderPaidWhileTheAppWasClosedIsFoundOnTheNextStart() = runTest {
        val e = env()
        e.doc("de" to "Германия", expires = 1_900_000_000)
        e.manager.login("ivan", "pw")
        e.manager.checkout("month")
        val kept = assertNotNull(e.repo.state.value?.pendingOrder)
        assertEquals("o1", kept.orderId)
        assertEquals("month", kept.planId)

        e.api.orderStatus = "paid"
        e.doc("de" to "Германия", expires = 2_100_000_000)
        val restarted = SubscriptionManager(e.api, e.repo, e.profiles, FakeShareLinkCodec(), e.platform, e.connection, ep)
        e.api.calls.clear()
        assertTrue(restarted.refresh())
        assertEquals(
            listOf("order:o1", "document:token-1@${ep.url}"),
            e.api.calls.filter { it.startsWith("order") || it.startsWith("document") },
            "the order is asked about before the document",
        )
        assertNull(e.repo.state.value?.pendingOrder, "a paid order is no longer waited for")
        assertEquals(2_100_000_000, e.repo.state.value?.expires)
    }

    @Test
    fun aPendingOrderIsKeptAndAnOrderThatIsOverIsForgotten() = runTest {
        for (status in listOf("canceled", "expired", "failed")) {
            val e = env()
            e.doc("de" to "Германия")
            e.manager.login("ivan", "pw")
            e.manager.checkout("month")
            assertTrue(e.manager.refresh())
            assertNotNull(e.repo.state.value?.pendingOrder, "pending: still waited for")
            e.api.orderStatus = "refunding" // a status this build does not know
            assertTrue(e.manager.refresh())
            assertNotNull(e.repo.state.value?.pendingOrder, "an unknown status is still waited for")
            e.api.orderStatus = status
            assertTrue(e.manager.refresh())
            assertNull(e.repo.state.value?.pendingOrder, "$status: no longer waited for")
        }
    }

    @Test
    fun askingAboutTheOrderOnTheScreenForgetsOneThatIsOver() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.manager.checkout("month")
        e.manager.order("o1")
        assertNotNull(e.repo.state.value?.pendingOrder)
        e.api.orderStatus = "canceled"
        e.manager.order("o1")
        assertNull(e.repo.state.value?.pendingOrder)
    }

    @Test
    fun anOrderOlderThanADayIsDroppedWithoutAsking() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.manager.checkout("month")
        e.platform.clock += 25 * 3_600_000L
        e.api.calls.clear()
        assertTrue(e.manager.refresh())
        assertTrue(e.api.calls.none { it.startsWith("order") })
        assertNull(e.repo.state.value?.pendingOrder)
    }

    @Test
    fun aFailedOrderQuestionKeepsTheOrderAndStillRefreshes() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.manager.checkout("month")
        e.api.orderFailure = SubscriptionException(SubscriptionException.NETWORK, "Сервис не отвечает.")
        e.doc("de" to "Германия", "nl" to "Нидерланды")
        assertTrue(e.manager.refresh())
        assertNotNull(e.repo.state.value?.pendingOrder)
        assertEquals(setOf("sub-de", "sub-nl"), e.profiles.profiles.value.map { it.id }.toSet())
    }

    @Test
    fun applyingTheDocumentKeepsTheOrder() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.manager.checkout("month")
        assertTrue(e.manager.refresh())
        assertTrue(e.manager.refresh())
        assertEquals("o1", e.repo.state.value?.pendingOrder?.orderId)
    }

    // ---- a service that moved (C3) ----

    private val moved = ServiceEndpoint("https://sub.example.org", "cd".repeat(32))

    @Test
    fun theAppFollowsAMovedServiceOnceItAnswers() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.api.document = e.api.document.copy(service = ServiceMove(moved.url + "/", moved.pin.uppercase()))
        e.api.calls.clear()
        assertTrue(e.manager.refresh())
        assertTrue("health:${moved.url}|${moved.pin}" in e.api.calls, "the new address is asked first, with its pin")
        val s = assertNotNull(e.repo.state.value)
        assertEquals(moved, s.endpoint)
        assertEquals("token-1", s.deviceToken, "the device stays signed in")
        assertEquals(moved, e.manager.endpoint)

        e.api.calls.clear()
        assertTrue(e.manager.refresh())
        assertTrue("document:token-1@${moved.url}" in e.api.calls, "the next refresh goes to the new address")
        assertTrue(e.api.calls.none { it.startsWith("health") }, "already there: nothing to check")
    }

    @Test
    fun theAccountAnswerCanCarryTheMoveToo() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.api.accountService = ServiceMove(moved.url, moved.pin)
        assertTrue(e.manager.refresh())
        assertEquals(moved, e.repo.state.value?.endpoint)
    }

    @Test
    fun aMoveToAnAddressThatDoesNotAnswerIsNotFollowed() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.api.document = e.api.document.copy(service = ServiceMove(moved.url, moved.pin))
        e.api.healthFailure = SubscriptionException(SubscriptionException.BAD_PIN, "сертификат не совпал")
        assertTrue(e.manager.refresh(), "the refresh itself succeeds")
        assertEquals(ep, e.repo.state.value?.endpoint)
        assertEquals(listOf("sub-de"), e.profiles.profiles.value.map { it.id })
    }

    @Test
    fun aMoveTheAppWouldNotTrustIsIgnored() = runTest {
        val untrusted = listOf(
            ServiceMove("http://sub.example.org", moved.pin),
            ServiceMove("https://sub.example.org", "not-a-pin"),
            ServiceMove("https://user@sub.example.org", moved.pin),
            ServiceMove("ftp://sub.example.org"),
            ServiceMove(""),
        )
        for (bad in untrusted) {
            val e = env()
            e.doc("de" to "Германия")
            e.manager.login("ivan", "pw")
            e.api.document = e.api.document.copy(service = bad)
            e.api.calls.clear()
            assertTrue(e.manager.refresh())
            assertEquals(ep, e.repo.state.value?.endpoint, "$bad")
            assertTrue(e.api.calls.none { it.startsWith("health") }, "$bad is not even asked")
        }
        assertEquals(ServiceEndpoint("http://127.0.0.1:8080", ""), ServiceMove("http://127.0.0.1:8080").endpoint(), "plain http for this machine only")
    }

    @Test
    fun aKeyMovesWithItsToken() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.addKey(e.manager.previewKey("https://203.0.113.5:8443/sub/TOKEN#pin=" + "ab".repeat(32)))
        e.api.document = e.api.document.copy(service = ServiceMove(moved.url, moved.pin))
        assertTrue(e.manager.refresh())
        val s = assertNotNull(e.repo.state.value)
        assertEquals(moved, s.endpoint)
        assertEquals("${moved.url}/sub/TOKEN", s.keyUrl)
    }

    // ---- locations the service is still making ----

    private fun Env.documents() = api.calls.count { it.startsWith("document") }

    private fun kotlinx.coroutines.test.TestScope.tick(e: Env, ms: Long) {
        e.platform.clock += ms
        advanceTimeBy(ms)
        runCurrent()
    }

    /** A personal node is made on the first request and comes a minute later: the app asks often meanwhile, not in 6 hours. */
    @Test
    fun locationsBeingMadeAreAskedForOftenUntilTheyCome() = runTest {
        val e = env()
        e.doc() // active, no location yet
        e.manager.start(backgroundScope)
        runCurrent()
        e.manager.login("ivan", "pw")
        runCurrent()
        assertTrue(e.manager.preparing.value)
        e.api.calls.clear()

        tick(e, 15_000)
        assertEquals(1, e.documents(), "asked again within seconds")
        e.doc("de" to "Германия")
        tick(e, 15_000)
        assertEquals(listOf("sub-de"), e.profiles.profiles.value.map { it.id })
        assertFalse(e.manager.preparing.value)

        e.api.calls.clear()
        repeat(4) { tick(e, 5 * 60_000) }
        assertEquals(0, e.documents(), "with a location, back to the service's updateHours")
    }

    @Test
    fun locationsThatDoNotComeAreNoLongerAskedForOften() = runTest {
        val e = env()
        e.doc()
        e.manager.start(backgroundScope)
        runCurrent()
        e.manager.login("ivan", "pw")
        runCurrent()
        repeat(44) { tick(e, 15_000) } // 11 minutes
        assertFalse(e.manager.preparing.value, "after 10 minutes the card says there are none")
        e.api.calls.clear()
        repeat(4) { tick(e, 15_000) }
        assertEquals(0, e.documents())
    }

    @Test
    fun anEndedSubscriptionIsNotPreparing() = runTest {
        val e = env()
        e.doc(status = SubStatus.EXPIRED)
        e.manager.login("ivan", "pw")
        assertFalse(e.manager.preparing.value)
        e.doc()
        e.manager.refresh()
        assertTrue(e.manager.preparing.value)
        e.manager.signOut()
        assertFalse(e.manager.preparing.value)
    }

    @Test
    fun accountCallsNeedASignedInAccount() = runTest {
        val e = env()
        assertEquals("unauthorized", assertFailsWith<SubscriptionException> { e.manager.checkout("month") }.code)
        e.doc("de" to "Германия")
        e.manager.addKey(e.manager.previewKey("https://203.0.113.5:8443/sub/TOKEN"))
        assertEquals("unauthorized", assertFailsWith<SubscriptionException> { e.manager.checkout("month") }.code, "a key has no account")
    }

    @Test
    fun theServiceRejectingATokenInACallSignsOut() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.api.failure = SubscriptionException("unauthorized", "Войдите в аккаунт.")
        assertFailsWith<SubscriptionException> { e.manager.removeDevice("d1") }
        assertNull(e.repo.state.value)
        assertTrue(e.profiles.profiles.value.isEmpty())
    }

    @Test
    fun theAnnouncementIsShownOnceItsClosed() = runTest {
        val e = env()
        e.doc("de" to "Германия", announce = "Профилактика в субботу")
        e.manager.login("ivan", "pw")
        assertEquals("Профилактика в субботу", e.repo.state.value?.announce)
        assertEquals("", e.repo.state.value?.announceSeen)
        e.manager.dismissAnnounce()
        assertEquals("Профилактика в субботу", e.repo.state.value?.announceSeen)
        e.manager.refresh()
        assertEquals("Профилактика в субботу", e.repo.state.value?.announceSeen, "a refresh does not bring it back")
        e.doc("de" to "Германия", announce = "Новая локация")
        e.manager.refresh()
        assertTrue(e.repo.state.value?.announce != e.repo.state.value?.announceSeen, "a new message is shown")
    }

    @Test
    fun backgroundRefreshAsksAtStartAndThenWhenDue() = runTest {
        val e = env()
        e.doc("de" to "Германия")
        e.manager.login("ivan", "pw")
        e.api.calls.clear()

        e.manager.start(backgroundScope)
        runCurrent()
        assertEquals(1, e.api.calls.count { it.startsWith("document") }, "at start")

        // Not yet due after an hour (updateHours is 6).
        e.platform.clock += 3_600_000
        advanceTimeBy(60 * 60 * 1000L)
        assertEquals(1, e.api.calls.count { it.startsWith("document") })

        e.platform.clock += 6 * 3_600_000
        advanceTimeBy(6 * 60 * 60 * 1000L)
        assertTrue(e.api.calls.count { it.startsWith("document") } >= 2, "due again after the interval")
    }

    @Test
    fun noSubscriptionNothingToRefresh() = runTest {
        val e = env()
        assertFalse(e.manager.refresh())
        e.manager.start(backgroundScope)
        runCurrent()
        assertTrue(e.api.calls.isEmpty())
    }

    // ---- fakes ----

    private class FakeApi : SubscriptionApi {
        val calls = mutableListOf<String>()
        var document = SubscriptionDocument()
        var failure: SubscriptionException? = null
        var loginFailure: SubscriptionException? = null
        var logoutFailure: SubscriptionException? = null
        var deleteFailure: SubscriptionException? = null
        var orderStatus = "pending"
        var orderFailure: SubscriptionException? = null
        var healthFailure: SubscriptionException? = null
        var accountService: ServiceMove? = null
        private var tokens = 0

        private fun account(login: String) = AccountInfo(id = "u1", login = login, status = document.status, expires = document.expires)
        private fun auth(login: String): AuthResult {
            tokens++
            return AuthResult("token-$tokens", "dev-$tokens", "RECOVERY-KEY", account(login))
        }

        override suspend fun plans(ep: ServiceEndpoint) = PlansResult()
        override suspend fun register(ep: ServiceEndpoint, login: String, password: String, deviceName: String): AuthResult {
            calls += "register:$login|$deviceName"
            return auth(login)
        }

        override suspend fun login(ep: ServiceEndpoint, login: String, password: String, deviceName: String, replaceDeviceId: String?): AuthResult {
            calls += "login:$login|" + (replaceDeviceId?.let { "replace=$it|" } ?: "") + deviceName
            loginFailure?.let { throw it }
            return auth(login)
        }

        override suspend fun recover(ep: ServiceEndpoint, login: String, recoveryKey: String, newPassword: String, deviceName: String): AuthResult {
            calls += "recover:$login"
            return auth(login)
        }

        override suspend fun account(ep: ServiceEndpoint, token: String): AccountInfo {
            calls += "account:$token"
            failure?.let { throw it }
            return account("ivan").copy(service = accountService)
        }

        override suspend fun document(ep: ServiceEndpoint, token: String): SubscriptionDocument {
            calls += "document:$token@${ep.url}"
            failure?.let { throw it }
            return document
        }

        override suspend fun keyDocument(ep: ServiceEndpoint, url: String): SubscriptionDocument {
            calls += "key:$url"
            failure?.let { throw it }
            return document
        }

        override suspend fun startTrial(ep: ServiceEndpoint, token: String) = account("ivan")
        override suspend fun checkout(ep: ServiceEndpoint, token: String, planId: String): OrderInfo {
            calls += "checkout:$planId"
            failure?.let { throw it }
            return OrderInfo("o1", planId, "pending")
        }

        override suspend fun order(ep: ServiceEndpoint, token: String, orderId: String): OrderInfo {
            calls += "order:$orderId"
            failure?.let { throw it }
            orderFailure?.let { throw it }
            return OrderInfo(orderId, "month", orderStatus, document.expires)
        }

        override suspend fun changePassword(ep: ServiceEndpoint, token: String, password: String, newPassword: String) = account("ivan")
        override suspend fun logout(ep: ServiceEndpoint, token: String) {
            calls += "logout:$token"
            logoutFailure?.let { throw it }
        }

        override suspend fun removeDevice(ep: ServiceEndpoint, token: String, deviceId: String): AccountInfo {
            failure?.let { throw it }
            return account("ivan")
        }

        override suspend fun deleteAccount(ep: ServiceEndpoint, token: String, password: String) {
            calls += "delete:$token"
            deleteFailure?.let { throw it }
        }

        override suspend fun health(ep: ServiceEndpoint) {
            calls += "health:${ep.url}|${ep.pin}"
            healthFailure?.let { throw it }
        }
    }

    private class FakeProfiles : ProfileRepository {
        override val profiles = MutableStateFlow<List<Profile>>(emptyList())
        private var next = 0
        override fun upsert(profile: Profile) {
            val old = profiles.value
            profiles.value = if (old.any { it.id == profile.id }) old.map { if (it.id == profile.id) profile else it } else old + profile
        }

        override fun delete(id: String) { profiles.value = profiles.value.filterNot { it.id == id } }
        override fun newId() = "p${next++}"
    }

    private class FakeConnection : ConnectionService {
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override val traffic = MutableStateFlow(TrafficStats())
        override val exitAddress = MutableStateFlow<ExitAddress>(ExitAddress.Unknown)
        override val logs: StateFlow<List<LogLine>> = MutableStateFlow(emptyList())
        override val captcha: StateFlow<CaptchaPrompt?> = MutableStateFlow(null)
        override val exitShareLink: StateFlow<String?> = MutableStateFlow(null)
        override val socksAddress: StateFlow<String?> = MutableStateFlow(null)
        override val captchaPage: StateFlow<BrowserPage?> = MutableStateFlow(null)
        override fun connect(profile: Profile, mode: ConnectionMode?) { state.value = ConnectionState.Connected(profile, ConnectionMode.Client, 0) }
        override fun disconnect() { state.value = ConnectionState.Idle }
        override fun refreshExitAddress() = Unit
        override fun clearLogs() = Unit
        override fun openCaptcha() = Unit
        override fun submitCaptcha() = Unit
        override fun dismissCaptcha() = Unit
        override fun shutdown() = Unit
    }

    private class FakePlatform : PlatformServices {
        /** Milliseconds; the app asks for [now] in the unit it is given. */
        var clock = 1_800_000_000_000L
        override val appVersion = "test"
        override val deviceName = "Тест-устройство"
        override val coreVersion = "test"
        override val clientRepo = "test"
        override val systemProxySupported = false
        override val fullTunnelSupported = false
        override val elevated = false
        override fun restartElevated() = false
        override fun clipboardText(): String? = null
        override fun setClipboardText(text: String) = Unit
        override fun qrFromClipboardImage(): String? = null
        override fun qrFromFile(path: String): String? = null
        override suspend fun pickFile(title: String, extensions: List<String>): String? = null
        override fun readTextFile(path: String, maxBytes: Int): String? = null
        override fun qrMatrix(text: String): List<BooleanArray> = emptyList()
        override fun openUrl(url: String) = Unit
        override fun newSecret() = "00".repeat(32)
        override fun now() = clock
        override suspend fun latestRelease(): String? = null
    }
}
