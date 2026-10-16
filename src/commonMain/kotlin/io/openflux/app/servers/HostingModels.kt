package io.openflux.app.servers

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.app.nav.Dest
import io.openflux.desktop.model.FtpTarget
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.NodeTransports
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.PhpHostingException
import io.openflux.desktop.model.PhpHosts
import io.openflux.desktop.model.PhpMessages
import io.openflux.desktop.model.PhpNodeRef
import io.openflux.desktop.model.PhpNodeState
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A node on an ordinary web hosting, without a server (spec 5.4), in one go: the files go up by FTP (or the node is
 * already there), the site is waited for, the node is started on a cups.online room or a Mail.ru document, the
 * profile is saved, and a connection through it proves it. Every decision is the core's (provision/phphost); this
 * keeps the form and the order. The FTP password is forgotten once used.
 */
@Stable
class HostingSetupModel(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val verifier: NodeVerifier = NodeVerifier(container),
) {
    private val service = container.phpHosting

    /** The node is already on the hosting (uploaded by hand): its site and key, no FTP. */
    var existing by mutableStateOf(false)
    var ftpHost by mutableStateOf("")
    var ftpPort by mutableStateOf("21")
    var ftpUser by mutableStateOf("")
    var ftpPassword by mutableStateOf("")
    /** The site's address, or (an existing node) the address of its page. */
    var site by mutableStateOf("")
    /** Some hostings have no FTP encryption at all; the person allows a plain connection knowingly. */
    var plainFtp by mutableStateOf(false)
    var folder by mutableStateOf("")
    /** A key of one's own for a new node (optional); for an existing node, its key. */
    var chosenToken by mutableStateOf("")
    var token by mutableStateOf("")
    var carrier by mutableStateOf(TransportType.CUPSONLINE)
    var mailru by mutableStateOf("")
    var name by mutableStateOf("")

    /** Shown when the core cannot tell which folder is the site: the person picks one. */
    var folderChoices by mutableStateOf<List<String>>(emptyList())
        private set
    private var busyText by mutableStateOf<String?>(null)
    val busy: String? get() = verifier.stage ?: busyText
    var error by mutableStateOf<String?>(null)
        private set
    var verifyFailed by mutableStateOf<String?>(null)
        private set
    /** How the FTP password travelled, when not well protected. */
    var securityNote by mutableStateOf<String?>(null)
        private set
    var done by mutableStateOf<Dest.Hosting?>(null)
        private set

    private var knownRoom = ""
    private var ftp: FtpTarget? = null
    /** The key of the files this put on the hosting; a retry goes on from them without uploading again. */
    private var deployedKey: String? = null
    private var made: Profile? = null
    private var job: Job? = null

    val preset get() = PhpHosts.presetFor(ftpHost)

    /** A pasted page address of an existing node (…?k=…&url=…) fills the key and the transport. */
    fun onExistingAddress(input: String) {
        site = input.trim()
        val node = PhpHosts.nodeAddress(site) ?: return
        if (node.token.isNotEmpty()) token = node.token
        when (node.carrier) {
            TransportType.CUPSONLINE -> { carrier = TransportType.CUPSONLINE; if (node.target.isNotEmpty()) knownRoom = node.target }
            TransportType.MAILRU -> { carrier = TransportType.MAILRU; if (node.target.isNotEmpty()) mailru = node.target }
            else -> Unit
        }
    }

    fun chooseFolder(dir: String) {
        folder = dir
        folderChoices = emptyList()
        install()
    }

    fun install() {
        val problem = formProblem()
        if (problem != null) { error = problem; return }
        run("Проверяю…") {
            val siteUrl: String
            val key: String
            if (existing) {
                siteUrl = PhpHosts.nodeAddress(site)!!.site
                key = token.trim()
            } else if (ftp != null && deployedKey != null) {
                siteUrl = PhpHosts.siteUrl(site)!!
                key = deployedKey!!
            } else {
                siteUrl = PhpHosts.siteUrl(site)!!
                val target = FtpTarget(
                    host = PhpHosts.ftpHost(ftpHost) ?: ftpHost.trim(),
                    port = ftpPort.trim().toIntOrNull() ?: 21,
                    user = ftpUser.trim(),
                    password = ftpPassword,
                    tls = if (plainFtp) "none" else "auto",
                    dir = folder.trim(),
                )
                busyText = "Проверяю вход на хостинг…"
                try {
                    service.probe(target)
                } catch (e: PhpHostingException) {
                    if (e.candidates.isNotEmpty()) { folderChoices = e.candidates; throw e }
                    throw e
                }
                ftp = target
                busyText = "Загружаю файлы на хостинг…"
                val installed = service.deploy(target, chosenToken.trim()) { p ->
                    when (p.phase) {
                        "upload" -> if (p.of > 0) busyText = "Загружаю файлы на хостинг: ${p.n} из ${p.of}…"
                        "retry" -> busyText = "Хостинг оборвал передачу, отправляю заново…"
                        "skipped" -> service.note("хостинг не принял необязательный файл, ставлю без него: ${p.note}", LogLevel.Warning)
                    }
                }
                securityNote = PhpMessages.security(installed.security)
                ftpPassword = ""
                deployedKey = installed.token
                key = installed.token
            }
            val target = when (carrier) {
                TransportType.MAILRU -> NodeTransports.cleanMailru(mailru)!!
                else -> knownRoom.ifEmpty { busyText = "Создаю комнату cups.online…"; service.newRoom().url }
            }
            waitForSite(siteUrl, key)
            busyText = "Запускаю ноду…"
            service.start(siteUrl, key, carrier.cliName, target, chain = true)
            val p = Profile(
                id = container.profiles.newId(),
                name = name.trim().ifEmpty { "Хостинг · ${siteUrl.substringAfter("://")}" },
                transport = carrier,
                value = target,
                stream = true,
                source = ProfileSource.Node,
                createdAt = container.platform.now(),
                phpNode = PhpNodeRef(siteUrl, key),
            )
            container.profiles.upsert(p)
            container.settings.update { it.copy(selectedProfileId = p.id) }
            made = p
            prove(p)
        }
    }

    fun retryVerify() {
        val p = made ?: return
        run("Проверяю ноду…") { prove(p) }
    }

    fun stopWaiting() = verifier.stopWaiting()

    fun keepAnyway() {
        val p = made ?: return
        verifyFailed = null
        done = Dest.Hosting(p.id)
    }

    /** Takes the node's files off the hosting (only those this put there) and drops its profile. */
    fun removeFailed() = run("Удаляю ноду с хостинга…") {
        val p = made
        val node = p?.phpNode
        if (p != null && node != null) runCatching { service.stop(node.siteUrl, node.token, p.transport.cliName, p.value) }
        ftp?.let { service.remove(it) }
        p?.let { container.profiles.delete(it.id) }
        ftp = null
        deployedKey = null
        made = null
        verifyFailed = null
    }

    val canRemove: Boolean get() = !existing && ftp != null

    private suspend fun prove(p: Profile) {
        verifyFailed = null
        try {
            verifier.verify(p, emptySet(), null)
            done = Dest.Hosting(p.id)
        } catch (e: NodeWizardException) {
            verifyFailed = e.message ?: "Проверка не прошла"
        }
    }

    private fun formProblem(): String? {
        if (existing) {
            if (PhpHosts.nodeAddress(site) == null) return "Укажите адрес сайта с нодой или адрес её страницы, например https://ваш-сайт.ru"
            PhpHosts.tokenProblem(token.trim())?.let { return it }
        } else {
            if (deployedKey == null) PhpHosts.ftpProblems(ftpHost, ftpPort, ftpUser, ftpPassword).firstOrNull()?.let { return it }
            if (PhpHosts.siteUrl(site) == null) return "Укажите адрес сайта на хостинге, например https://ваш-сайт.ru"
            PhpHosts.chosenTokenProblem(chosenToken.trim())?.let { return it }
        }
        if (carrier == TransportType.MAILRU && NodeTransports.cleanMailru(mailru) == null) {
            return "Нужна публичная ссылка на документ Mail.ru: https://cloud.mail.ru/public/…"
        }
        return null
    }

    /** A new domain can take minutes to answer: try for about a minute; only "does not answer" is worth waiting for. */
    private suspend fun waitForSite(siteUrl: String, key: String) {
        var last: PhpHostingException? = null
        repeat(SITE_TRIES) { attempt ->
            try {
                service.check(siteUrl, key, carrier.cliName)
                return
            } catch (e: PhpHostingException) {
                if (e.code == "site_token" && existing) throw PhpHostingException("site_token_given")
                if (e.code != "site_unreachable" && e.code != "site_not_phpbox") throw e
                last = e
                busyText = "Жду, пока сайт заработает (${attempt + 1} из $SITE_TRIES)…"
                delay(SITE_RETRY_MS)
            }
        }
        throw last ?: PhpHostingException("site_unreachable", siteUrl)
    }

    private fun run(message: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        busyText = message
        error = null
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не получилось"
            } finally {
                busyText = null
            }
        }
    }

    private companion object {
        const val SITE_TRIES = 12
        const val SITE_RETRY_MS = 5_000L
    }
}

/** A hosting node's screen (spec 5.4): its state, start and stop, its panel, its key; forget it here. */
@Stable
class HostingNodeModel(private val container: AppContainer, val profileId: String, private val scope: CoroutineScope) {
    private val service = container.phpHosting
    val profile: Profile? get() = container.profiles.profiles.value.firstOrNull { it.id == profileId }
    var state by mutableStateOf<PhpNodeState?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    val maskedToken: String get() = PhpHosts.maskToken(profile?.phpNode?.token.orEmpty())

    fun refresh() = act("Спрашиваю ноду…") { p, n -> state = service.node(n.siteUrl, n.token, p.transport.cliName, p.value.trim()) }

    fun start() = act("Запускаю ноду…") { p, n -> state = service.start(n.siteUrl, n.token, p.transport.cliName, p.value.trim(), chain = true) }

    fun stop() = act("Останавливаю ноду…") { p, n ->
        service.stop(n.siteUrl, n.token, p.transport.cliName, p.value.trim())
        state = PhpNodeState(running = false)
    }

    fun openPanel() = act("Открываю панель ноды…") { p, n -> container.platform.openUrl(service.page(n.siteUrl, n.token, p.transport.cliName, p.value.trim())) }

    fun copyToken() {
        profile?.phpNode?.token?.let(container.platform::setClipboardText)
    }

    /** Removes the profile from this device; the node on the hosting is untouched. */
    fun forget() {
        container.profiles.delete(profileId)
    }

    private fun act(message: String, block: suspend (Profile, PhpNodeRef) -> Unit) {
        val p = profile ?: return
        val n = p.phpNode ?: return
        if (job?.isActive == true) return
        busy = message
        error = null
        job = scope.launch {
            try {
                block(p, n)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не удалось связаться с хостингом"
            } finally {
                busy = null
            }
        }
    }
}
