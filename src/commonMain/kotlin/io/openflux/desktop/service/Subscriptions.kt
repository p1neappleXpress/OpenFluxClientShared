package io.openflux.desktop.service

import io.openflux.desktop.model.AccountInfo
import io.openflux.desktop.model.AuthResult
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.OrderInfo
import io.openflux.desktop.model.PendingOrder
import io.openflux.desktop.model.PlansResult
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ServiceEndpoint
import io.openflux.desktop.model.ServiceMove
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionDocument
import io.openflux.desktop.model.SubscriptionException
import io.openflux.desktop.model.SubscriptionInvite
import io.openflux.desktop.model.SubscriptionKind
import io.openflux.desktop.model.SubscriptionState
import io.openflux.desktop.model.ShareLinkCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The subscription service's API (docs/subscriptions.ru.md). Every call fails
 * with [SubscriptionException]. The platform implements it with HTTPS and the
 * certificate pin.
 */
interface SubscriptionApi {
    suspend fun plans(ep: ServiceEndpoint): PlansResult
    suspend fun register(ep: ServiceEndpoint, login: String, password: String, deviceName: String): AuthResult
    suspend fun login(ep: ServiceEndpoint, login: String, password: String, deviceName: String, replaceDeviceId: String? = null): AuthResult
    suspend fun recover(ep: ServiceEndpoint, login: String, recoveryKey: String, newPassword: String, deviceName: String): AuthResult
    suspend fun account(ep: ServiceEndpoint, token: String): AccountInfo
    suspend fun document(ep: ServiceEndpoint, token: String): SubscriptionDocument
    /** A subscription key without an account: the document at [url]. */
    suspend fun keyDocument(ep: ServiceEndpoint, url: String): SubscriptionDocument
    suspend fun startTrial(ep: ServiceEndpoint, token: String): AccountInfo
    suspend fun checkout(ep: ServiceEndpoint, token: String, planId: String): OrderInfo
    suspend fun order(ep: ServiceEndpoint, token: String, orderId: String): OrderInfo
    suspend fun changePassword(ep: ServiceEndpoint, token: String, password: String, newPassword: String): AccountInfo
    suspend fun logout(ep: ServiceEndpoint, token: String)
    suspend fun removeDevice(ep: ServiceEndpoint, token: String, deviceId: String): AccountInfo
    suspend fun deleteAccount(ep: ServiceEndpoint, token: String, password: String)
    /** Succeeds when the service at [ep] answers, with its pin: where a moved service is checked before the app follows it. */
    suspend fun health(ep: ServiceEndpoint)
}

/** Where the subscription is kept between runs. */
interface SubscriptionRepository {
    val state: StateFlow<SubscriptionState?>
    fun save(state: SubscriptionState?)
}

class InMemorySubscriptionRepository : SubscriptionRepository {
    private val flow = MutableStateFlow<SubscriptionState?>(null)
    override val state: StateFlow<SubscriptionState?> = flow.asStateFlow()
    override fun save(state: SubscriptionState?) { flow.value = state }
}

/** For a platform without the service client: every call says so. */
object NoSubscriptionApi : SubscriptionApi {
    private fun unsupported(): Nothing = throw SubscriptionException("unsupported", "Подписка здесь не поддерживается")
    override suspend fun plans(ep: ServiceEndpoint) = unsupported()
    override suspend fun register(ep: ServiceEndpoint, login: String, password: String, deviceName: String) = unsupported()
    override suspend fun login(ep: ServiceEndpoint, login: String, password: String, deviceName: String, replaceDeviceId: String?) = unsupported()
    override suspend fun recover(ep: ServiceEndpoint, login: String, recoveryKey: String, newPassword: String, deviceName: String) = unsupported()
    override suspend fun account(ep: ServiceEndpoint, token: String) = unsupported()
    override suspend fun document(ep: ServiceEndpoint, token: String) = unsupported()
    override suspend fun keyDocument(ep: ServiceEndpoint, url: String) = unsupported()
    override suspend fun startTrial(ep: ServiceEndpoint, token: String) = unsupported()
    override suspend fun checkout(ep: ServiceEndpoint, token: String, planId: String) = unsupported()
    override suspend fun order(ep: ServiceEndpoint, token: String, orderId: String) = unsupported()
    override suspend fun changePassword(ep: ServiceEndpoint, token: String, password: String, newPassword: String) = unsupported()
    override suspend fun logout(ep: ServiceEndpoint, token: String) = unsupported()
    override suspend fun removeDevice(ep: ServiceEndpoint, token: String, deviceId: String) = unsupported()
    override suspend fun deleteAccount(ep: ServiceEndpoint, token: String, password: String) = unsupported()
    override suspend fun health(ep: ServiceEndpoint) = unsupported()
}

/** What a pasted key shows before it is added. */
data class KeyPreview(val invite: SubscriptionInvite, val document: SubscriptionDocument)

/**
 * The app's subscription: signing in, the document refreshed in the
 * background, and the profiles that come with it. The profiles of a
 * subscription are the app's own (source [ProfileSource.Subscription]): it
 * adds, updates and removes them as the service's document says.
 */
class SubscriptionManager(
    private val api: SubscriptionApi,
    private val repo: SubscriptionRepository,
    private val profiles: ProfileRepository,
    private val codec: ShareLinkCodec,
    private val platform: PlatformServices,
    private val connection: ConnectionService,
    private val defaultEndpoint: ServiceEndpoint,
) {
    val state: StateFlow<SubscriptionState?> get() = repo.state
    val endpoint: ServiceEndpoint get() = state.value?.endpoint ?: defaultEndpoint
    private val lock = Mutex()

    /** The refresh running now, for the card's spinner. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * An active subscription without a location yet: the service makes a personal node on the first request and
     * answers with none until it is up (a minute or two), so the app asks again often for a while.
     */
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing.asStateFlow()

    /** When an active document first came without locations; null once it has some (or is not active). */
    private var emptySince: Long? = null

    /** Wakes the background loop when it should start asking often. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Plans for sale; asked from the service when the screen needs them. */
    suspend fun plans(): PlansResult = api.plans(endpoint)

    // ---- signing in ----

    suspend fun register(login: String, password: String): AuthResult = signedIn(api.register(endpoint, login.trim(), password, platform.deviceName))

    suspend fun login(login: String, password: String, replaceDeviceId: String? = null): AuthResult =
        signedIn(api.login(endpoint, login.trim(), password, platform.deviceName, replaceDeviceId))

    suspend fun recover(login: String, recoveryKey: String, newPassword: String): AuthResult =
        signedIn(api.recover(endpoint, login.trim(), recoveryKey.trim(), newPassword, platform.deviceName))

    private suspend fun signedIn(auth: AuthResult): AuthResult = lock.withLock {
        val ep = endpoint
        // A key's profiles and another account's go; the new account's come with the document.
        val state = SubscriptionState(
            endpoint = ep, kind = SubscriptionKind.Account, deviceToken = auth.deviceToken, deviceId = auth.deviceId,
            account = auth.account, status = auth.account.status, expires = auth.account.expires, support = auth.account.support,
        )
        repo.save(state)
        runCatching { fetchAndApply(state) }.onFailure { if (it is CancellationException) throw it }
        auth
    }

    // ---- a key without an account ----

    /** Asks the service what [text] is a key to, without keeping anything. */
    suspend fun previewKey(text: String): KeyPreview {
        val invite = SubscriptionInvite.parse(text) ?: throw SubscriptionException("bad_invite", "Это не ключ подписки")
        return KeyPreview(invite, api.keyDocument(invite.endpoint, invite.url))
    }

    suspend fun addKey(preview: KeyPreview): Unit = lock.withLock {
        val state = SubscriptionState(endpoint = preview.invite.endpoint, kind = SubscriptionKind.Key, keyUrl = preview.invite.url)
        repo.save(state)
        applyDocument(state, preview.document)
    }

    // ---- the document ----

    /** Refreshes now (the card's button, the app starting); a failure is kept for the card, not thrown. */
    suspend fun refresh(): Boolean {
        if (state.value == null) return false
        _refreshing.value = true
        return try {
            lock.withLock { refreshLocked() }
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun refreshLocked(): Boolean = try {
        // Read under the lock: a sign-out or an order may have changed it since refresh() was called.
        val state = repo.state.value
        if (state == null) false else {
            fetchAndApply(checkOrder(state))
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SubscriptionException) {
        when {
            // Signed out elsewhere, or the password changed: sign in again.
            e.unauthorized -> dropSession()
            else -> repo.save(repo.state.value?.copy(lastError = e.message))
        }
        false
    }

    private suspend fun fetchAndApply(state: SubscriptionState) {
        if (state.kind == SubscriptionKind.Key) {
            val doc = api.keyDocument(state.endpoint, state.keyUrl)
            applyDocument(state, doc)
            follow(doc.service)
            return
        }
        val doc = api.document(state.endpoint, state.deviceToken)
        // The account (devices, plan) comes along; its failure does not spoil the document.
        val account = runCatching { api.account(state.endpoint, state.deviceToken) }.getOrNull()
        applyDocument(state.copy(account = account ?: state.account), doc)
        follow(doc.service ?: account?.service)
    }

    /**
     * Asks about the order the app waits for before the document, so a paid one shows in it. One that is over, or
     * older than a day, is no longer waited for; a failed question keeps it for the next refresh.
     */
    private suspend fun checkOrder(state: SubscriptionState): SubscriptionState {
        val pending = state.pendingOrder ?: return state
        if (state.kind != SubscriptionKind.Account || platform.now() - pending.createdAt > ORDER_WAIT_MS) {
            return state.copy(pendingOrder = null).also(repo::save)
        }
        val order = try {
            api.order(state.endpoint, state.deviceToken, pending.orderId)
        } catch (e: SubscriptionException) {
            if (e.unauthorized) throw e
            return state
        }
        return if (order.closed) state.copy(pendingOrder = null).also(repo::save) else state
    }

    /**
     * Follows a moved service: the new address must answer with its pin first, or the app stays where it is and
     * asks again on the next refresh. The device token (and a key's token) go along unchanged.
     */
    private suspend fun follow(move: ServiceMove?) {
        val target = move?.endpoint() ?: return
        val s = repo.state.value ?: return
        if (target.base == s.endpoint.base && target.pin == s.endpoint.pin) return
        try {
            api.health(target)
        } catch (e: SubscriptionException) {
            return
        }
        val keyUrl = if (s.kind == SubscriptionKind.Key && s.keyUrl.contains("/sub/")) target.base + "/sub/" + s.keyUrl.substringAfter("/sub/") else s.keyUrl
        repo.save(s.copy(endpoint = target, keyUrl = keyUrl))
    }

    /**
     * Makes the profiles and the card say what [doc] says: new locations come,
     * known ones get their new key and documents, gone ones go, and a document
     * that is not active empties the list.
     */
    private suspend fun applyDocument(state: SubscriptionState, doc: SubscriptionDocument) {
        val wanted = LinkedHashMap<String, Profile>()
        if (doc.status == SubStatus.ACTIVE) {
            for (p in doc.profiles) {
                val id = profileId(p.id)
                val config = try {
                    codec.decode(p.link)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue // a link this build cannot read is skipped, the others still work
                }
                val fresh = Profile.fromShare(config, id, platform.now(), ProfileSource.Subscription).copy(name = p.name, icon = "ic_public")
                val old = profiles.profiles.value.firstOrNull { it.id == id }
                wanted[id] = if (old == null) fresh else fresh.copy(createdAt = old.createdAt)
            }
        }
        val empty = doc.status == SubStatus.ACTIVE && wanted.isEmpty()
        emptySince = if (empty) emptySince ?: platform.now() else null
        _preparing.value = waitingForLocations(platform.now())
        if (empty) wake.trySend(Unit)
        for (profile in wanted.values) profiles.upsert(profile)
        val gone = profiles.profiles.value.filter { it.source == ProfileSource.Subscription && it.id !in wanted }
        val running = connection.state.value
        if (running.isActive && (running.profile?.id in gone.map { it.id } || (doc.status != SubStatus.ACTIVE && running.profile?.source == ProfileSource.Subscription))) {
            connection.disconnect()
        }
        gone.forEach { profiles.delete(it.id) }
        repo.save(
            state.copy(
                title = doc.title.ifBlank { state.title }, status = doc.status, expires = doc.expires, support = doc.support.ifBlank { state.support },
                announce = doc.announce, updateHours = doc.updateHours.coerceIn(1, 168), lastRefreshed = platform.now(), lastError = null,
            ),
        )
    }

    private fun profileId(remote: String) = "sub-$remote"

    /** The token is no longer good: the device is signed out, the subscription's profiles go. */
    private fun dropSession() {
        emptySince = null
        _preparing.value = false
        val running = connection.state.value
        if (running.isActive && running.profile?.source == ProfileSource.Subscription) connection.disconnect()
        profiles.profiles.value.filter { it.source == ProfileSource.Subscription }.forEach { profiles.delete(it.id) }
        repo.save(null)
    }

    // ---- the account ----

    suspend fun startTrial(): AccountInfo = authed { token -> api.startTrial(endpoint, token) }.also { refresh() }

    /** Orders [planId]; the order is kept (as [SubscriptionState.pendingOrder]) until it is paid or over. */
    suspend fun checkout(planId: String): OrderInfo {
        val order = authed { token -> api.checkout(endpoint, token, planId) }
        lock.withLock {
            val s = repo.state.value
            if (s?.kind == SubscriptionKind.Account) repo.save(s.copy(pendingOrder = PendingOrder(order.orderId, planId, platform.now(), order.paymentUrl)))
        }
        return order
    }

    /** The order's state; a paid one refreshes the subscription, and one that is over is no longer waited for. */
    suspend fun order(orderId: String): OrderInfo {
        val order = authed { token -> api.order(endpoint, token, orderId) }
        if (order.closed) {
            lock.withLock {
                val s = repo.state.value
                if (s?.pendingOrder?.orderId == orderId) repo.save(s.copy(pendingOrder = null))
            }
        }
        if (order.paid) refresh()
        return order
    }

    suspend fun changePassword(password: String, newPassword: String): AccountInfo =
        authed { token -> api.changePassword(endpoint, token, password, newPassword) }.also { setAccount(it) }

    suspend fun removeDevice(deviceId: String): AccountInfo =
        authed { token -> api.removeDevice(endpoint, token, deviceId) }.also { setAccount(it) }

    /** Leaves this device: the service forgets it, the profiles of the subscription go. */
    suspend fun signOut() = lock.withLock {
        val s = repo.state.value ?: return@withLock
        if (s.kind == SubscriptionKind.Account) runCatching { api.logout(s.endpoint, s.deviceToken) }
        dropSession()
    }

    suspend fun deleteAccount(password: String) {
        authed { token -> api.deleteAccount(endpoint, token, password) }
        lock.withLock { dropSession() }
    }

    /** Closes the owner's message so it is not shown again. */
    fun dismissAnnounce() {
        val s = repo.state.value ?: return
        repo.save(s.copy(announceSeen = s.announce))
    }

    private fun setAccount(account: AccountInfo) {
        val s = repo.state.value ?: return
        repo.save(s.copy(account = account, status = account.status, expires = account.expires))
    }

    private suspend fun <T> authed(call: suspend (String) -> T): T {
        val s = repo.state.value
        if (s == null || s.kind != SubscriptionKind.Account) throw SubscriptionException("unauthorized", "Войдите в аккаунт.")
        return try {
            call(s.deviceToken)
        } catch (e: SubscriptionException) {
            if (e.unauthorized) lock.withLock { dropSession() }
            throw e
        }
    }

    // ---- in the background ----

    private fun waitingForLocations(now: Long) = emptySince?.let { now - it < LOCATIONS_WAIT_MS } == true

    /**
     * Refreshes when the app starts, then whenever the service's `updateHours` have passed, and every
     * [LOCATIONS_EVERY_MS] while the locations are being prepared.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            var first = true
            while (true) {
                val s = state.value
                val now = platform.now()
                val due = s != null && (
                    first || now - s.lastRefreshed >= s.updateHours * 3_600_000L ||
                        (waitingForLocations(now) && now - s.lastRefreshed >= LOCATIONS_EVERY_MS)
                    )
                if (due) refresh()
                first = false
                _preparing.value = waitingForLocations(platform.now())
                withTimeoutOrNull(if (waitingForLocations(platform.now())) LOCATIONS_EVERY_MS else CHECK_EVERY_MS) { wake.receive() }
            }
        }
    }

    private companion object {
        const val CHECK_EVERY_MS = 5 * 60 * 1000L
        /** How often, and for how long, an active subscription without locations is asked again. */
        const val LOCATIONS_EVERY_MS = 15 * 1000L
        const val LOCATIONS_WAIT_MS = 10 * 60 * 1000L
        /** How long an unpaid order is waited for: a payment link does not live longer. */
        const val ORDER_WAIT_MS = 24 * 60 * 60 * 1000L
    }
}
