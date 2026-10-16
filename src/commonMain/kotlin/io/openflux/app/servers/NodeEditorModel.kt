package io.openflux.app.servers

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.app.nav.Dest
import io.openflux.desktop.model.AccountCookies
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.DocumentKind
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.NodeDocuments
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.NodeTransports
import io.openflux.desktop.model.ScriptSettingsCodec
import kotlin.io.encoding.Base64
import io.openflux.desktop.service.AccountException
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One node on a server, made or changed (spec 5.3): the same editor for both. A new node ([channelId] null) is
 * installed with [install] and then proven by connecting through it; an installed one is read with [load],
 * changed in a draft, and [apply] sends the change to the server (set-transports keeps its key, port and
 * context, so links handed out keep working) and updates its profile on this device.
 *
 * One action at a time; [busy] says what is running, [error] why the last one failed. The draft survives a
 * failure. The node's profile here is found by its key or made from the key the server gives ([adopt]).
 */
@Stable
class NodeEditorModel(
    private val container: AppContainer,
    private val link: ServerLink,
    val serverId: String,
    private val channelId: String?,
    private val scope: CoroutineScope,
    private val verifier: NodeVerifier = NodeVerifier(container),
    private val yandexCookies: () -> String = {
        AccountCookies.header(container.accounts.validSession(AccountKind.Yandex)?.cookies.orEmpty())
    },
) {
    val creating: Boolean get() = channelId == null && installedId == null

    private var installedId: String? = channelId

    var installed by mutableStateOf<InstalledChannel?>(null)
        private set
    var original by mutableStateOf<NodeDraft?>(null)
        private set
    var draft by mutableStateOf(NodeDraft(name = defaultName(null)))
        private set

    private var busyText by mutableStateOf<String?>(null)
    /** What is running now, in one line; null when idle. */
    val busy: String? get() = verifier.stage ?: busyText
    var error by mutableStateOf<String?>(null)
        private set
    /** A short note of what just worked ("Применено"). */
    var notice by mutableStateOf<String?>(null)
        private set
    /** Why the new node's check failed; the node stays installed until the person decides. */
    var verifyFailed by mutableStateOf<String?>(null)
        private set
    var verified by mutableStateOf<NodeVerifier.Result?>(null)
        private set
    /** Set when a new node is installed and proven (or kept anyway): the screen becomes this node's. */
    var done by mutableStateOf<Dest.Node?>(null)
        private set
    /** Why the document check from this device was skipped (Yandex asked this device for a captcha). */
    var documentWarning by mutableStateOf<String?>(null)
        private set

    /** The sudo password typed on this screen; the login password is used when the server takes it. */
    var sudoInput by mutableStateOf("")
    val needsSudo: Boolean get() = link.needsSudo(serverId)

    /** The Yandex sign-in the document was made with, and that document: the sign-in goes to the node only with it. */
    private var pendingCookies = ""
    private var pendingCookiesDoc = ""
    private var candidate: Profile? = null
    private var job: Job? = null

    private val host: String get() = hostOf(serverId)
    /** The server's address, for the node's "address:port". */
    val serverHost: String get() = host

    val changes: Int get() = original?.let { draft.changes(it) } ?: 0
    /** Why "Применить" cannot run now; null when it can. */
    val applyProblem: String? get() = original?.let { draft.applyProblem(it) }
    val hasUnsaved: Boolean get() = changes > 0

    /** This node's profile on this device: the one with its key, or one dialing its address. */
    val localProfile: Profile?
        get() {
            val info = installed ?: return null
            val dials = setOf("$host:${info.port}", "[$host]:${info.port}")
            return container.profiles.profiles.value.firstOrNull { p ->
                (info.key.isNotEmpty() && p.secret == info.key) ||
                    (p.secret.isNotBlank() && p.carriers.any { it.type == TransportType.DIRECT && it.value.trim() in dials })
            }
        }

    fun edit(transform: (NodeDraft) -> NodeDraft) {
        draft = transform(draft)
        error = null
        notice = null
    }

    fun discard() {
        original?.let { draft = it }
        error = null
    }

    fun clearNotice() {
        notice = null
    }

    // ---- an installed node ----

    fun load() = run("Читаю ноду на сервере…") {
        val id = installedId ?: return@run
        val info = link.call(serverId, retry = true) { showChannel(id, sudo()) }
        installed = info
        val o = NodeDraft.of(info, localProfile?.name ?: defaultName(id))
        original = o
        draft = o
    }

    fun apply() {
        val o = original ?: return
        val d = draft
        if (!d.serverChanged(o)) {
            renameHere(d.name)
            original = o.copy(name = d.name)
            notice = "Сохранено"
            return
        }
        d.applyProblem(o)?.let { error = it; return }
        run("Применяю на сервере…") {
            val id = installedId ?: return@run
            val ready = withRooms(d)
            val transports = withPackages(ready.toTransports())
            val now = link.call(serverId) { setTransports(id, transports, sudo(), cookiesFor(ready), ready.mode) }
            pendingCookies = ""
            installed = now
            refreshProfile(now, ready.name)
            val fresh = NodeDraft.of(now, ready.name)
            original = fresh
            draft = fresh
            notice = "Применено"
        }
    }

    /** Gives the node the Yandex sign-in saved in the app (Settings → Сервисы), for its Yandex document. */
    fun pushYandexSignIn() {
        val info = installed ?: return
        val cookies = yandexCookies()
        if (!NodeDocuments.signedIn(cookies)) { error = "Сначала войдите в Яндекс: Настройки → Сервисы"; return }
        if (info.transportTypes.none { it == TransportType.VYANDEX || it == TransportType.YANDEX }) {
            error = "У ноды нет транспорта Яндекса"
            return
        }
        run("Передаю вход Яндекса ноде…") {
            installed = link.call(serverId) { setTransports(info.id, info.transports, sudo(), cookies, null) }
            notice = "Вход Яндекса передан ноде"
        }
    }

    /** Deletes the node from the server, and its profile here (it would only fail to connect). */
    fun remove(onGone: () -> Unit) = run("Удаляю ноду с сервера…") {
        val id = installedId ?: return@run
        val local = localProfile
        link.call(serverId) { remove(id, sudo()) }
        local?.let { container.profiles.delete(it.id) }
        onGone()
    }

    /** This node's profile here, made from the server's key when there is none yet. */
    suspend fun adopt(): Profile {
        localProfile?.let { return it }
        val info = installed ?: throw NodeWizardException("Нода ещё не прочитана")
        if (info.key.isEmpty()) throw NodeWizardException("Сервер не отдал ключ ноды: откройте сервер заново")
        val name = draft.name.trim().ifEmpty { defaultName(info.id) }
        val text = link.call(serverId) { channelShareLink(name, info.key, host, info.port, info.transports, info.context) }
        val made = Profile.fromShare(container.shareCodec.decode(text), container.profiles.newId(), container.platform.now(), ProfileSource.Node)
            .copy(icon = "ic_terminal", name = name)
            .withScripts(info.transports)
        container.profiles.upsert(made)
        return made
    }

    /** Connects this device through the node. */
    fun connectHere() = run("Подключаюсь через ноду…") {
        val p = adopt()
        io.openflux.app.shell.connectAsClient(container, p)
        notice = "Подключаюсь через «${p.name}»"
    }

    // ---- a new node ----

    fun install() {
        draft.problem()?.let { error = it; return }
        run("Готовлю ноду…") {
            val ch = link.call(serverId) { newChannel() }
            val ready = withRooms(draft)
            val transports = withPackages(ready.toTransports())
            busyText = "Спрашиваю сервер, что изменится…"
            val plan = link.call(serverId, retry = true) { plan(ch.id, transports, cookiesFor(ready).isNotEmpty(), ready.autoUpdate, ready.mode) }
            busyText = "Устанавливаю ноду: ядро, настройки, запуск…"
            link.call(serverId) { apply(ch, transports, plan.port, ready.autoUpdate, sudo(), cookiesFor(ready), ready.mode) }
            pendingCookies = ""
            installedId = ch.id
            val name = ready.name.trim().ifEmpty { defaultName(ch.id) }
            val text = link.call(serverId) { shareLink(name, ch.key, host, plan.port, transports) }
            val p = Profile.fromShare(container.shareCodec.decode(text), container.profiles.newId(), container.platform.now(), ProfileSource.Node)
                .copy(icon = "ic_terminal", name = name)
                .withScripts(transports)
            container.profiles.upsert(p)
            container.settings.update { it.copy(selectedProfileId = p.id) }
            candidate = p
            prove(p, transports.firstOrNull()?.let { TransportType.fromCli(it.type) })
        }
    }

    fun retryVerify() {
        val p = candidate ?: return
        run("Проверяю ноду…") { prove(p, null) }
    }

    fun stopWaiting() = verifier.stopWaiting()

    /** Keeps the new node though the check did not pass. */
    fun keepAnyway() {
        val id = installedId ?: return
        verifyFailed = null
        done = Dest.Node(serverId, id)
    }

    /** Removes the new node whose check failed, and its profile. */
    fun removeFailed() = run("Удаляю ноду с сервера…") {
        val id = installedId ?: return@run
        link.call(serverId) { remove(id, sudo()) }
        candidate?.let { container.profiles.delete(it.id) }
        candidate = null
        installedId = null
        verifyFailed = null
    }

    private suspend fun prove(p: Profile, primary: TransportType?) {
        verifyFailed = null
        try {
            val addresses = link.call(serverId) { resolve(host) }
            verified = verifier.verify(p, addresses, primary)
            done = Dest.Node(serverId, installedId ?: return)
        } catch (e: NodeWizardException) {
            verifyFailed = e.message ?: "Проверка не прошла"
        }
    }

    // ---- documents ----

    /** Makes the node's Yandex document in the built-in browser (signing in to Yandex there) and checks it. */
    fun createYandexDocument() = run("Открываю Яндекс во встроенном браузере…") {
        val doc = container.nodeWizard.createDocument(NodeDocuments.fileName(draft.name, host, container.platform.now())) { busyText = it }
        if (NodeDocuments.signedIn(doc.cookieHeader)) {
            pendingCookies = doc.cookieHeader
            pendingCookiesDoc = NodeDocuments.clean(doc.url) ?: doc.url
        }
        draft = draft.copy(yandexDoc = doc.url, volga = draft.volga || !draft.yandexDocs)
        checkDocumentNow(doc.url)
    }

    /** Checks a pasted Yandex document the way the node will open it. */
    fun checkDocument() = run("Проверяю документ…") { checkDocumentNow(draft.yandexDoc) }

    private suspend fun checkDocumentNow(url: String) {
        val clean = NodeDocuments.clean(url) ?: throw NodeWizardException("Нужна ссылка на документ Яндекса вида https://docs.yandex.ru/edit/d/…")
        draft = draft.copy(yandexDoc = clean)
        documentWarning = try {
            container.nodeWizard.checkDocument(clean)
            null
        } catch (e: NodeWizardException) {
            if (!e.captcha) throw e
            "Яндекс попросил проверку у этого устройства: документ проверит сама нода"
        }
    }

    fun createMailruDocument() = run("Создаю документ в Облаке Mail…") {
        val url = accountDocument { container.accounts.createDocument(AccountKind.Mailru, it) } ?: return@run
        draft = draft.copy(mailru = true, mailruLink = url)
    }

    fun createBoard() = run("Создаю доску в Яндексе…") {
        val url = accountDocument { container.accounts.createDocument(DocumentKind.YandexBoard, it) } ?: return@run
        draft = draft.copy(board = true, boardLink = url)
    }

    /** The node's Telemost conference: the Telemost page opens in the built-in browser and the person makes it there. */
    fun createTelemost() = run("Открываю Телемост во встроенном браузере…") {
        val url = accountDocument { container.accounts.createDocument(DocumentKind.TelemostConference, it) } ?: return@run
        draft = draft.copy(telemost = true, telemostLink = url)
    }

    /** New Avito Code rooms now (made by the server, as on applying), so their code can be seen, copied or kept. */
    fun createAvitoRooms() = run("Создаю комнаты Avito…") {
        val rooms = link.call(serverId) { this.createAvitoRooms() }
        draft = draft.copy(avito = true, avitoRooms = rooms)
    }

    /** New cups.online rooms now, the same way. */
    fun createCupsRooms() = run("Создаю комнаты cups.online…") {
        val rooms = link.call(serverId) { this.createCupsRooms() }
        draft = draft.copy(cups = true, cupsRooms = rooms)
    }

    private suspend fun accountDocument(make: suspend (String) -> String): String? = try {
        make(NodeDocuments.fileName(draft.name, host, container.platform.now()))
    } catch (e: AccountException) {
        if (e.cancelled) null else throw NodeWizardException(e.message ?: "Не получилось создать документ")
    }

    // ---- helpers ----

    private fun sudo(): String {
        if (sudoInput.isNotEmpty()) link.setSudo(serverId, sudoInput)
        val s = link.sudo(serverId)
        if (needsSudo && s.isEmpty()) throw NodeWizardException("Введите пароль sudo", sudo = true)
        return s
    }

    private suspend fun withRooms(d: NodeDraft): NodeDraft {
        var r = d
        if (r.cups && r.cupsRooms.isEmpty()) {
            busyText = "Создаю комнаты cups.online…"
            val rooms = link.call(serverId) { this.createCupsRooms() }
            r = r.copy(cupsRooms = rooms)
            draft = draft.copy(cupsRooms = rooms)
        }
        if (r.avito && r.avitoRooms.isEmpty()) {
            busyText = "Создаю комнаты Avito…"
            val rooms = link.call(serverId) { this.createAvitoRooms() }
            r = r.copy(avitoRooms = rooms)
            draft = draft.copy(avitoRooms = rooms)
        }
        return r
    }

    /**
     * The JS transports as the node gets them: this device's package (it goes to the server each time, so the node
     * runs the version the app has), the author's key, and the primary parameter folded into the settings line the
     * way the app runs it (CoreConfig).
     */
    private fun withPackages(ts: List<NodeTransport>): List<NodeTransport> = ts.map { t ->
        if (t.type != TransportType.SCRIPT.cliName) return@map t
        val installed = container.scripts.byId(t.script)?.takeIf(NodeTransports::nodeCanRun)
            ?: throw NodeWizardException("JS-транспорт ${t.script} не установлен на этом устройстве как подписанный пакет .flux")
        val bytes = container.scripts.packageBytes(t.script)?.first
            ?: throw NodeWizardException("Пакет JS-транспорта ${installed.name.ifBlank { t.script }} не читается: переустановите его")
        val settings = ScriptSettingsCodec.decode(t.params) + (installed.primaryParam?.key?.let { mapOf(it to t.url) } ?: emptyMap())
        t.copy(
            pubkey = installed.pubkeyHex,
            pkg = Base64.encode(bytes),
            params = if (settings.isEmpty()) "" else ScriptSettingsCodec.encode(settings),
        )
    }

    /** The link cannot carry a JS transport: the node's profile gets them here, as the node runs them. */
    private fun Profile.withScripts(ts: List<NodeTransport>): Profile {
        val scripts = ts.filter { it.type == TransportType.SCRIPT.cliName && it.script.isNotEmpty() }
        if (scripts.isEmpty()) return this
        val installed = scripts.associate { it.script to container.scripts.byId(it.script) }
        val extras = scripts.map { t ->
            val primary = installed[t.script]?.primaryParam?.key
            ExtraTransport(
                TransportType.SCRIPT, t.url, priority = SCRIPT_PRIORITY, scriptId = t.script,
                settings = ScriptSettingsCodec.decode(t.params).filterKeys { it != primary },
            )
        }
        return copy(session = true, extras = this.extras.filterNot { it.type == TransportType.SCRIPT } + extras)
    }

    /** The sign-in goes along only with a Yandex document, and only the one it made. */
    private fun cookiesFor(d: NodeDraft): String =
        if (d.needsYandexDoc && pendingCookiesDoc.isNotEmpty() && d.yandexDoc == pendingCookiesDoc) pendingCookies else ""

    private fun renameHere(name: String) {
        val p = localProfile ?: return
        if (name.isNotBlank() && p.name != name.trim()) container.profiles.upsert(p.copy(name = name.trim()))
    }

    private suspend fun refreshProfile(now: InstalledChannel, name: String) {
        val p = localProfile ?: return
        val key = now.key.ifEmpty { p.secret }
        val text = link.call(serverId) { channelShareLink(name.trim().ifEmpty { p.name }, key, host, now.port, now.transports, now.context) }
        val fresh = Profile.fromShare(container.shareCodec.decode(text), p.id, p.createdAt, p.source)
            .copy(icon = p.icon, name = name.trim().ifEmpty { p.name })
            .withScripts(now.transports)
        container.profiles.upsert(fresh)
    }

    private fun defaultName(id: String?): String {
        val base = "Нода $host"
        val taken = container.profiles.profiles.value.any { it.name == base }
        return if (taken && id != null) "$base · $id" else base
    }

    private fun hostOf(id: String): String =
        container.settings.settings.value.knownServers.firstOrNull { serverIdOf(it) == id }?.host ?: id.substringBefore(':')

    private fun run(message: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        busyText = message
        error = null
        notice = null
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NodeWizardException) {
                error = if (e.sudo && e.message != "Введите пароль sudo") "sudo не принял пароль" else e.message
            } catch (e: Exception) {
                error = e.message ?: "Не получилось"
            } finally {
                busyText = null
            }
        }
    }

    private companion object {
        /** The node's priority for its JS transports (provision.transportPriority). */
        const val SCRIPT_PRIORITY = 60
    }
}
