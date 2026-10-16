package io.openflux.desktop.service

import io.openflux.desktop.model.BotBanner
import io.openflux.desktop.model.BotDocument
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotInvoice
import io.openflux.desktop.model.BotLink
import io.openflux.desktop.model.BotPaymentMethod
import io.openflux.desktop.model.BotPendingInvoice
import io.openflux.desktop.model.BotPlan
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSlot
import io.openflux.desktop.model.BotState
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareLinkCodec
import io.openflux.desktop.model.ShareLinkException
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A bot connection link resolved: the profile's name and its `openflux://` link. */
data class BotConnection(val name: String, val link: String) {
    override fun toString() = "BotConnection($name)"
}

/** The bot's API (api.openflux.dev). Every call fails with [BotException]. */
interface BotApi {
    suspend fun subscription(token: String): BotSubscription
    suspend fun signOut(token: String)
    /** The connection behind a short link; not_found once it ended or was replaced. */
    suspend fun resolve(connectToken: String): BotConnection

    /** Trades the sign-in page's one-time code for our session (POST /api/v1/auth/app/exchange). */
    suspend fun exchange(code: String, verifier: String, redirectUri: String): BotSession =
        throw BotException("unsupported", "Вход здесь не поддерживается")
    /** The account's connections. */
    suspend fun sessions(token: String): List<BotSessionItem> = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")
    /** Makes a connection in a free automatic slot; "forbidden" when there is none. */
    suspend fun createSession(token: String): BotSessionItem = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")

    /** The terms the person (or, without [token], anybody) may buy. */
    suspend fun plans(token: String?): List<BotPlan> = throw BotException("unsupported", "Покупка здесь не поддерживается")
    /**
     * An invoice for a NEW slot on the term [termCode]. A repeat with the same [idempotencyKey] (8–64 of `A-Z a-z 0-9 - _`)
     * returns the same invoice, so a call lost on the way is repeated with the key it had.
     */
    suspend fun createInvoice(token: String, termCode: String, idempotencyKey: String, gateway: String = ""): BotInvoice =
        throw BotException("unsupported", "Покупка здесь не поддерживается")
    /** The ways the person may pay now, in the service's order (the first is the default). */
    suspend fun paymentMethods(token: String): List<BotPaymentMethod> = emptyList()

    /** The account's slots; not_found from a service that has no slots API yet. */
    suspend fun slots(token: String): List<BotSlot> = throw BotException("not_found", "Не найдено")
    /** The Start button of a slot: its paused connection comes back, a free automatic slot gets one. */
    suspend fun startSlot(token: String, slotId: String): BotSlot = throw BotException("unsupported", "Здесь не поддерживается")
    /** The Stop button of a slot: its connection pauses, the slot keeps it (key and document) for Start. */
    suspend fun stopSlot(token: String, slotId: String): BotSlot = throw BotException("unsupported", "Здесь не поддерживается")
    /** The person's own documents of a manual slot. */
    suspend fun documents(token: String, slotId: String): List<BotDocument> = throw BotException("unsupported", "Здесь не поддерживается")
    suspend fun addDocument(token: String, slotId: String, url: String): BotDocument = throw BotException("unsupported", "Здесь не поддерживается")
    suspend fun deleteDocument(token: String, slotId: String, documentId: String): Unit = throw BotException("unsupported", "Здесь не поддерживается")
    suspend fun checkDocument(token: String, slotId: String, documentId: String): BotDocument = throw BotException("unsupported", "Здесь не поддерживается")
    /** The connection of a free manual slot from its documents, in the order given (the first is tried first). */
    suspend fun connectSlot(token: String, slotId: String, documentIds: List<String>): BotSlot = throw BotException("unsupported", "Здесь не поддерживается")
    /** One of the person's own invoices; not_found for anyone else's. */
    suspend fun invoice(token: String, id: String): BotInvoice = throw BotException("unsupported", "Покупка здесь не поддерживается")
    /** The service's banners for everyone. */
    suspend fun banners(): List<BotBanner> = throw BotException("unsupported", "Сервис OpenFlux здесь не поддерживается")
}

object NoBotApi : BotApi {
    private fun unsupported(): Nothing = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")
    override suspend fun subscription(token: String) = unsupported()
    override suspend fun signOut(token: String) = unsupported()
    override suspend fun resolve(connectToken: String) = unsupported()
}

interface BotRepository {
    val state: StateFlow<BotState>
    fun save(state: BotState)
}

class InMemoryBotRepository : BotRepository {
    private val flow = MutableStateFlow(BotState())
    override val state: StateFlow<BotState> = flow.asStateFlow()
    override fun save(state: BotState) { flow.value = state }
}

/**
 * The OpenFlux bot on this device, until its API takes apps directly: people buy in the bot, which gives each
 * connection a short link. The app turns such a link into a profile ([ProfileSource.Bot], read-only like a
 * subscription's), keeps it current and says when the bot no longer serves it (the subscription ended, or the
 * connection was replaced: the bot then gives a new link). A sign-in (email or Telegram, on the OpenFlux page) shows the subscription itself.
 */
class BotAccountManager(
    private val api: BotApi,
    private val repo: BotRepository,
    private val profiles: ProfileRepository,
    private val codec: ShareLinkCodec,
    private val platform: PlatformServices,
    private val connection: ConnectionService,
    private val accounts: Accounts,
    /** A new Idempotency-Key for a purchase. */
    private val newIdempotencyKey: () -> String = ::randomIdempotencyKey,
) {
    val state: StateFlow<BotState> get() = repo.state
    private val lock = Mutex()

    /** Asks the background loop to look again now (a sign-in just finished, a window opened). */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** How many windows show the account now: while any does, the loop asks every [WATCH_MS]. */
    private val watchers = MutableStateFlow(0)

    /** The subscription a connection was last refused for, and when: not asked again for it every few seconds. */
    private var refusedFor: BotSubscription? = null
    private var refusedAt = 0L

    // ---- following the bot ----

    /**
     * A window that shows the account opened: the service has no way to tell the app of a change made in the bot
     * (a renewal, a paused connection, a payment), so the app asks at once and then every [WATCH_MS] until the
     * window [AutoCloseable.close]s what this returns. Closing twice is harmless.
     */
    fun watch(): AutoCloseable {
        watchers.update { it + 1 }
        wake.trySend(Unit)
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                watchers.update { it - 1 }
            }
        }
    }

    // ---- buying ----

    /** The terms on sale: the account's own when signed in, else what anybody may buy. */
    suspend fun plans(): List<BotPlan> = api.plans(repo.state.value.session?.token)

    /**
     * An invoice for a new slot on [termCode], kept as [BotState.pendingInvoice]. The same term while its invoice waits
     * gives that invoice back; a call lost on the way (or one the service is still handling) is repeated with the key
     * it had, so it never makes a second invoice. An invoice paid at once (the free tier) brings the subscription in.
     */
    suspend fun checkout(termCode: String, gateway: String = ""): BotInvoice {
        val inv = lock.withLock {
            val token = repo.state.value.session?.token ?: throw BotException("unauthorized", "Войдите в аккаунт OpenFlux")
            val old = repo.state.value.pendingInvoice
            if (old != null && old.termCode == termCode && old.invoice != null && old.waiting) return old.invoice
            val pending = if (old != null && old.termCode == termCode && old.invoice == null) {
                old.copy(gateway = gateway)
            } else {
                BotPendingInvoice(termCode, newIdempotencyKey(), gateway = gateway)
            }
            savePending(pending) // before the call: the key must outlive a call lost on the way
            val inv = try {
                api.createInvoice(token, termCode, pending.key, pending.gateway)
            } catch (e: BotException) {
                // Lost on the way, or still being handled: the service may hold an invoice under this key. Else it holds none.
                if (e.code != "network" && e.code != "busy") savePending(null)
                throw e
            }
            savePending(pending.copy(invoice = inv))
            inv
        }
        if (inv.paid) afterPayment()
        return inv
    }

    /**
     * Asks about the invoice that waits: paid or expired, it stays as it is until [dismissInvoice] (the window says
     * so); one the service does not know is forgotten. A payment brings the subscription and the connection in.
     */
    suspend fun checkInvoice(): BotInvoice? {
        val (inv, paidNow) = lock.withLock {
            val token = repo.state.value.session?.token ?: return repo.state.value.pendingInvoice?.invoice
            val wasPaid = repo.state.value.pendingInvoice?.invoice?.paid == true
            val inv = askInvoiceLocked(token)
            inv to (inv?.paid == true && !wasPaid)
        }
        if (paidNow) afterPayment()
        return inv
    }

    /** Keeps what the app already told the person by a notification; under the lock, so no other change is lost. */
    suspend fun setNoticed(keys: Set<String>) = lock.withLock { repo.save(repo.state.value.copy(noticed = keys.toList())) }

    /** Forgets the purchase (the person saw it paid or ended, or gave it up: an unpaid invoice then just ends). */
    suspend fun dismissInvoice() = lock.withLock { savePending(null) }

    private suspend fun askInvoiceLocked(token: String): BotInvoice? {
        val p = repo.state.value.pendingInvoice ?: return null
        val before = p.invoice ?: return null
        if (!p.waiting) return before
        val inv = try {
            api.invoice(token, before.id)
        } catch (e: BotException) {
            if (e.notFound) {
                savePending(null)
                return null
            }
            throw e
        }
        savePending(p.copy(invoice = inv))
        return inv
    }

    private suspend fun afterPayment() {
        refresh()
        wake.trySend(Unit)
    }

    private fun savePending(p: BotPendingInvoice?) = repo.save(repo.state.value.copy(pendingInvoice = p))

    // ---- signing in ----

    /** The session for a code the sign-in page gave; [completeLogin] then keeps it. */
    suspend fun exchange(code: String, verifier: String, redirectUri: String): BotSession = api.exchange(code, verifier, redirectUri)

    /** Keeps the session a finished Telegram sign-in gave, then brings the account's subscription and connection in. */
    suspend fun completeLogin(session: BotSession) {
        lock.withLock { repo.save(repo.state.value.copy(session = session, lastError = null, connectionNote = null)) }
        try {
            refresh()
        } finally {
            // The background loop may be asleep for hours (signed out when it began): wake it, also when this was cancelled.
            wake.trySend(Unit)
        }
    }

    /** Signs out of this device: the account's connection goes with it, links added by hand stay. */
    suspend fun signOut() {
        val s = repo.state.value.session ?: return
        runCatching { api.signOut(s.token) }
        lock.withLock {
            val mine = repo.state.value.links.filter { it.account }
            stopIfRunning(mine.map { it.profileId }.toSet())
            mine.forEach { profiles.delete(it.profileId) }
            repo.save(
                repo.state.value.copy(
                    session = null, subscription = null, links = repo.state.value.links.filterNot { it.account }, pendingInvoice = null,
                    preparing = false, connectionNote = null,
                ),
            )
        }
    }

    // ---- the account's places ----

    /** The account's slots (the bot's "Мои подключения"); a service without the API answers not_found, kept as unknown. */
    suspend fun slots(): List<BotSlot> = withToken { api.slots(it) }

    /** The Start of a slot: its paused connection comes back, a free automatic slot gets one. */
    suspend fun startSlot(slotId: String): BotSlot = slotAction { token -> api.startSlot(token, slotId) }

    /** The Stop of a slot: its connection pauses and the slot keeps it (key and document) for Start. */
    suspend fun stopSlot(slotId: String): BotSlot = slotAction { token -> api.stopSlot(token, slotId) }

    /** Sets a free manual slot up from its own documents, in the order given (the first is tried first). */
    suspend fun connectSlot(slotId: String, documentIds: List<String>): BotSlot =
        slotAction { token -> api.connectSlot(token, slotId, documentIds) }

    /** The answer of a slot action is put back on the state, so the window shows the new state at once. */
    private suspend fun slotAction(call: suspend (String) -> BotSlot): BotSlot {
        val slot = withToken { token -> call(token) }
        lock.withLock {
            val slots = repo.state.value.slots.orEmpty()
            val i = slots.indexOfFirst { it.id == slot.id }
            val updated = if (i >= 0) slots.toMutableList().also { it[i] = slot } else slots + slot
            repo.save(repo.state.value.copy(slots = updated))
        }
        return slot
    }

    /** The person's own documents of a manual slot. */
    suspend fun documents(slotId: String): List<BotDocument> = withToken { api.documents(it, slotId) }
    suspend fun addDocument(slotId: String, url: String): BotDocument = withToken { api.addDocument(it, slotId, url) }
    suspend fun deleteDocument(slotId: String, documentId: String) = withToken { api.deleteDocument(it, slotId, documentId) }
    suspend fun checkDocument(slotId: String, documentId: String): BotDocument = withToken { api.checkDocument(it, slotId, documentId) }

    /** The ways to pay now, in the service's order; empty when signed out (nothing to buy with). */
    suspend fun paymentMethods(): List<BotPaymentMethod> {
        val token = repo.state.value.session?.token ?: return emptyList()
        return api.paymentMethods(token)
    }

    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = repo.state.value.session?.token ?: throw BotException("unauthorized", "Войдите в аккаунт OpenFlux")
        return block(token)
    }

    // ---- connection links ----

    /** Turns a bot link into a profile (or updates the one it made before); returns the profile. */
    suspend fun addLink(text: String): Profile {
        val token = OpenFluxBot.connectToken(text) ?: throw BotException("bad_link", "Это не ссылка бота OpenFlux")
        return lock.withLock { addLinkLocked(token, account = false) }
    }

    private suspend fun addLinkLocked(token: String, account: Boolean): Profile {
        val conn = try {
            api.resolve(token)
        } catch (e: BotException) {
            if (e.notFound) throw BotException("not_found", GONE) else throw e
        }
        val known = repo.state.value.links.firstOrNull { it.token == token }
        val p = profileOf(conn, known?.profileId ?: profiles.newId(), known)
        profiles.upsert(p)
        val link = BotLink(token, p.id, conn.name, platform.now(), account = account || known?.account == true)
        saveLinks(repo.state.value.links.filterNot { it.token == token } + link)
        return p
    }

    /** Forgets a connection: its profile goes too. */
    suspend fun removeLink(token: String) = lock.withLock { removeLinkLocked(token) }

    private fun removeLinkLocked(token: String) {
        val link = repo.state.value.links.firstOrNull { it.token == token } ?: return
        stopIfRunning(setOf(link.profileId))
        profiles.delete(link.profileId)
        saveLinks(repo.state.value.links.filterNot { it.token == token })
    }

    /** The link of a profile the bot gave, for "Открыть в боте" and the card; null for any other profile. */
    fun linkOf(profileId: String): BotLink? = repo.state.value.links.firstOrNull { it.profileId == profileId }

    // ---- keeping it current ----

    /** Asks about every connection and, signed in, the subscription. Failures stay on the state, not thrown. */
    suspend fun refresh() = refresh(fromLoop = false)

    /** [fromLoop]: the background loop asks, not the person; a connection just refused is not asked for again. */
    private suspend fun refresh(fromLoop: Boolean) {
        _refreshing.value = true
        try {
            lock.withLock {
                try {
                    refreshLocked(fromLoop)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A link the core cannot read, an answer of a shape we do not know: said to the person, never a crash.
                    repo.save(repo.state.value.copy(preparing = false, lastError = unreadable(e)))
                }
            }
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun refreshLocked(fromLoop: Boolean) {
        // A payment first: the subscription asked below then already has the slot it bought.
        val signedIn = repo.state.value.session
        if (signedIn != null && repo.state.value.pendingInvoice?.waiting == true) {
            try {
                askInvoiceLocked(signedIn.token)
            } catch (e: BotException) {
                // Asked again on the next round; the subscription below says whether the session still holds.
            }
        }
        var error: String? = null
        val links = repo.state.value.links.map { link ->
            try {
                val conn = api.resolve(link.token)
                val old = profiles.profiles.value.firstOrNull { it.id == link.profileId }
                profiles.upsert(profileOf(conn, link.profileId, link, old))
                link.copy(name = conn.name, checkedAt = platform.now(), gone = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                if (e.notFound) {
                    stopIfRunning(setOf(link.profileId))
                    link.copy(checkedAt = platform.now(), gone = true)
                } else {
                    error = e.message
                    link
                }
            } catch (e: ShareLinkException) {
                // One link this app's core cannot read must not stop the others: the reason is said, the rest go on.
                error = unreadable(e)
                link
            }
        }
        var s = repo.state.value.copy(links = links)
        val session = s.session
        if (session != null) {
            s = try {
                s.copy(subscription = api.subscription(session.token))
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                if (e.unauthorized) s.copy(session = null, subscription = null, pendingInvoice = null) else s.also { error = e.message }
            }
        }
        // The account's slots (the bot's "Мои подключения"): not_found means the service has no slots API yet.
        s.session?.let { sess ->
            try {
                s = s.copy(slots = api.slots(sess.token))
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                if (e.notFound) s = s.copy(slots = null)
            }
        }
        repo.save(s.copy(lastError = error))
        ensureConnectionLocked(fromLoop)
    }

    /**
     * The signed-in account's connections: every one of the bot that has a link, running or paused, becomes a
     * profile (a paused connection keeps its slot, key and carriers, and works again the moment it is resumed in
     * the bot); a new one is asked for only when none runs and none is being made.
     */
    private suspend fun ensureConnectionLocked(fromLoop: Boolean) {
        val session = repo.state.value.session ?: return
        // A connection the bot ended or replaced goes; below asks for the current ones.
        for (l in repo.state.value.links.filter { it.account && it.gone }) removeLinkLocked(l.token)

        val items = try {
            api.sessions(session.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BotException) {
            return ensureFailed(e)
        }
        // A link of the account whose connection is over (or replaced) goes. One that is merely restarting stays,
        // so a tunnel in use is never cut by a status that passes.
        val alive = items.filter { it.connectToken.isNotEmpty() && it.status !in ENDED }.map { it.connectToken }.toSet()
        for (l in repo.state.value.links.filter { it.account && it.token !in alive }) removeLinkLocked(l.token)

        var waiting = false
        var unreadable: String? = null
        for (item in items.filter { it.connectToken.isNotEmpty() && (it.status == RUNNING || it.status == PAUSED) }) {
            if (repo.state.value.links.any { it.token == item.connectToken }) continue
            try {
                addLinkLocked(item.connectToken, account = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                if (e.notFound) waiting = true else return ensureFailed(e) // not found: the link is not ready yet
            } catch (e: ShareLinkException) {
                unreadable = unreadable(e) // one the core cannot read must not hide the others
            }
        }
        // Each connection of the account keeps its state for the window (running, paused, being made).
        saveLinks(repo.state.value.links.map { l -> items.firstOrNull { it.connectToken == l.token }?.let { l.copy(status = it.status) } ?: l })
        // A connection that runs and whose link is held: the account has one (a running one whose link is not ready yet is still "being made").
        val running = items.any { item -> item.ready && repo.state.value.links.any { it.token == item.connectToken } }
        val paused = items.any { it.status == PAUSED }
        when {
            running -> {
                repo.save(repo.state.value.copy(preparing = false, connectionNote = null, lastError = unreadable ?: repo.state.value.lastError))
                return
            }
            // A connection the core cannot read: said with its reason; nothing is waited for and nothing is made over it.
            !waiting && unreadable != null -> {
                repo.save(repo.state.value.copy(preparing = false, connectionNote = null, lastError = unreadable))
                return
            }
            // Something is being made, or is up but without its link yet: never make another over it.
            waiting || items.any { it.status !in ENDED && it.status != PAUSED } -> {
                repo.save(repo.state.value.copy(preparing = true, connectionNote = null, lastError = unreadable ?: repo.state.value.lastError))
                return
            }
        }
        // Nothing runs and nothing is being made: a new connection is asked for in a free automatic slot. The REST API
        // never brings a paused one back (unlike the bot's "New connection"): it refuses and says why.
        val sub = repo.state.value.subscription
        // Refused for this very subscription a moment ago: the loop (every 20 seconds while a window is open) does not
        // ask again until the subscription changes or a while passed. The person's own refresh always asks.
        if (fromLoop && repo.state.value.connectionNote != null && refusedFor == sub && platform.now() - refusedAt < RETRY_REFUSED_MS) return
        val made = try {
            api.createSession(session.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BotException) {
            if (e.forbidden) {
                refusedFor = sub
                refusedAt = platform.now()
            }
            if (paused && e.forbidden) {
                val note = if (session.viaTelegram) PAUSED_NOTE else PAUSED_NOTE_EMAIL
                return repo.save(repo.state.value.copy(preparing = false, connectionNote = note, lastError = unreadable ?: repo.state.value.lastError))
            }
            return ensureFailed(e)
        }
        refusedFor = null
        if (!made.ready) {
            repo.save(repo.state.value.copy(preparing = true, connectionNote = null))
            return
        }
        if (repo.state.value.links.none { it.token == made.connectToken }) {
            try {
                addLinkLocked(made.connectToken, account = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                return ensureFailed(e)
            } catch (e: ShareLinkException) {
                return repo.save(repo.state.value.copy(preparing = false, lastError = unreadable(e)))
            }
        }
        repo.save(repo.state.value.copy(preparing = false, connectionNote = null))
    }
    /**
     * Why the service refused a connection, in words for this account. Its own words speak of the bot, so only a
     * Telegram account gets them; an account by email has no bot and is never sent there.
     */
    private fun refusalNote(s: BotState, e: BotException): String {
        if (s.subscription?.isActive == false) return NO_SUBSCRIPTION
        if (s.session?.viaTelegram != true) return NO_FREE_SLOT
        return e.message?.takeIf { it.isNotBlank() && it != HttpForbidden } ?: NO_FREE_SLOT_TG
    }

    private fun ensureFailed(e: BotException) {
        val s = repo.state.value
        repo.save(
            when {
                e.unauthorized -> s.copy(session = null, subscription = null, preparing = false, pendingInvoice = null)
                // The service's own words (why there is no slot) when it gave them, else the usual sentence.
                e.forbidden -> s.copy(preparing = false, connectionNote = refusalNote(s, e))
                e.notFound -> s.copy(preparing = true, connectionNote = null) // the link is not ready yet
                else -> s.copy(preparing = false, lastError = e.message)
            },
        )
    }

    /**
     * Refreshes when the app starts and then every quarter of an hour; more often while the connection is being made,
     * while a window shows the account ([watch]) and while an invoice waits for its payment.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                val s = repo.state.value
                if (s.session != null || s.links.isNotEmpty()) refresh(fromLoop = true)
                wake.tryReceive() // a wake-up that came while refreshing is answered by that refresh
                withTimeoutOrNull(nextRoundMs(repo.state.value)) { wake.receive() }
            }
        }
    }

    private fun nextRoundMs(s: BotState): Long = when {
        s.preparing -> PREPARE_MS
        watchers.value > 0 -> WATCH_MS
        s.session != null && s.pendingInvoice?.invoice != null && s.pendingInvoice.waiting -> PAYMENT_MS
        else -> REFRESH_MS
    }

    private suspend fun profileOf(conn: BotConnection, id: String, link: BotLink?, old: Profile? = null): Profile {
        val config = codec.decode(conn.link)
        val fresh = Profile.fromShare(config, id, platform.now(), ProfileSource.Bot)
            .copy(name = conn.name.ifBlank { link?.name.orEmpty() }.ifBlank { "OpenFlux" }, icon = "ic_public")
        return if (old == null) fresh else fresh.copy(createdAt = old.createdAt)
    }

    private fun stopIfRunning(ids: Set<String>) {
        val running = connection.state.value
        if (running.isActive && running.profile?.id in ids) connection.disconnect()
    }

    private fun saveLinks(links: List<BotLink>) = repo.save(repo.state.value.copy(links = links))

    /** What the person is told when a connection of the bot cannot be read: the reason when the core gave one. */
    private fun unreadable(e: Exception): String =
        if (e is ShareLinkException) "Не удалось прочитать подключение от бота: ${e.message}" else UNREADABLE

    companion object {
        /** The words an HTTP 403 carries when the service said nothing of its own; not shown as a reason. */
        const val HttpForbidden = "Нет доступа: подписка не действует или нет свободного места"
        private const val RUNNING = "running"
        private const val PAUSED = "paused"
        const val PAUSED_NOTE = "Подключения приостановлены и заняли все места. Возобновите одно в @${OpenFluxBot.USERNAME}, в «Мои подключения»: оно заработает само"
        /** An account by email has no bot: a paused connection cannot be resumed from here yet (the REST API has no way). */
        const val PAUSED_NOTE_EMAIL = "Подключение приостановлено и держит место, из приложения его не возобновить. Купите ещё одно в разделе «Подписка»"
        const val NO_SUBSCRIPTION = "Подключения нет: подписка не действует. Купите её в разделе «Подписка»"
        /** The subscription holds, yet no automatic slot is free: all are taken, or a slot is a manual one (own documents). */
        const val NO_FREE_SLOT = "Подключения нет: все места заняты или место для своих документов. Купите ещё одно в разделе «Подписка»"
        const val NO_FREE_SLOT_TG = "$NO_FREE_SLOT или освободите место в @${OpenFluxBot.USERNAME}"
        const val GONE = "Бот больше не обслуживает это подключение: подписка закончилась или подключение заменили. Возьмите новую ссылку в @${OpenFluxBot.USERNAME}"
        const val UNREADABLE = "Не удалось прочитать подключение от бота: обновите приложение"
        /** A connection in one of these statuses is over; any other (running, creating, starting…) is still the account's. */
        private val ENDED = setOf("stopped", "failed", "error", "deleted", "expired")
        private const val PREPARE_MS = 15 * 1000L
        private const val WATCH_MS = 20 * 1000L
        private const val PAYMENT_MS = 30 * 1000L
        private const val REFRESH_MS = 15 * 60 * 1000L
        /** A connection refused for the same subscription is asked for by the loop again only after this. */
        private const val RETRY_REFUSED_MS = 5 * 60 * 1000L
    }
}

/** 32 letters and digits: unique enough for one person's purchases (the key is not a secret). */
fun randomIdempotencyKey(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..32).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
}
