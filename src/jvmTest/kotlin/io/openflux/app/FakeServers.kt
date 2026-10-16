package io.openflux.app

import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.LogLine
import io.openflux.desktop.model.NewChannel
import io.openflux.desktop.model.NodeCoreSource
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.NodePlan
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.FakeShareLinkCodec
import io.openflux.desktop.model.ServerProbe
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareTransport
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.model.YandexDocument
import io.openflux.desktop.service.NodeWizardService
import io.openflux.app.platform.BrowserPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A server as the fake sees it. */
class FakeServer(
    val host: String,
    var hostKey: String = "SHA256:$host",
    var sudo: String = "root",
    var sudoPassword: String = "",
    var autoupdate: Boolean = true,
    val channels: MutableMap<String, InstalledChannel> = linkedMapOf(),
)

/**
 * The SSH side of the node wizard over in-memory servers: one connection at a time, like the real one.
 * [log] records every call as "host:method".
 */
class FakeNodeService(vararg servers: FakeServer) : NodeWizardService {
    val servers = servers.associateBy { it.host }.toMutableMap()
    val log = mutableListOf<String>()
    var connected: FakeServer? = null
        private set
    /** The next call (not connect) fails as if SSH dropped. */
    var dropNext = false
    var failPlan: String? = null
    private var next = 0

    private fun here(method: String): FakeServer {
        val s = connected ?: throw NodeWizardException("скрипт установки не ответил")
        log += "${s.host}:$method"
        if (dropNext) { dropNext = false; connected = null; throw NodeWizardException("скрипт установки не ответил") }
        return s
    }

    private fun sudo(s: FakeServer, given: String) {
        if (s.sudo == "password" && given != s.sudoPassword) throw NodeWizardException("sudo не принял пароль", sudo = true)
    }

    override suspend fun connect(target: SshTarget, source: NodeCoreSource): ServerProbe {
        val s = servers[target.host] ?: throw NodeWizardException("Сервер ${target.host} не отвечает")
        log += "${s.host}:connect"
        if (target.hostKey != s.hostKey) {
            throw NodeWizardException("Новый сервер", hostKey = s.hostKey, trust = target.hostKey.isEmpty(), mismatch = target.hostKey.isNotEmpty())
        }
        if (target.password.isEmpty() && target.privateKey.isEmpty()) throw NodeWizardException("Сервер не принял вход")
        connected = s
        return ServerProbe(os = "Ubuntu 24.04", arch = "amd64", systemd = true, sudo = s.sudo, core = "node-v1.4.1", autoupdate = s.autoupdate, channels = s.channels.keys.toList())
    }

    override suspend fun newChannel(): NewChannel {
        here("newChannel")
        next++
        return NewChannel("of-n$next", "k".repeat(64))
    }

    override suspend fun plan(channel: String, transports: List<NodeTransport>, withCookies: Boolean, autoUpdate: Boolean, mode: NodeMode): NodePlan {
        here("plan")
        failPlan?.let { throw NodeWizardException(it) }
        return NodePlan(channel = channel, port = 8445 + next, actions = listOf("поставить ядро"))
    }

    override suspend fun apply(channel: NewChannel, transports: List<NodeTransport>, port: Int, autoUpdate: Boolean, sudoPassword: String, cookieHeader: String, mode: NodeMode) {
        val s = here("apply")
        sudo(s, sudoPassword)
        s.autoupdate = autoUpdate
        if (cookieHeader.isNotEmpty()) cookiesSent += cookieHeader
        s.channels[channel.id] = InstalledChannel(channel.id, "active", port, "ctx-${channel.id}", transports, mode.cli, channel.key)
    }

    override suspend fun remove(channel: String, sudoPassword: String) {
        val s = here("remove")
        sudo(s, sudoPassword)
        s.channels.remove(channel)
    }

    override suspend fun showChannel(channel: String, sudoPassword: String): InstalledChannel {
        val s = here("showChannel")
        sudo(s, sudoPassword)
        return s.channels[channel] ?: throw NodeWizardException("канала $channel нет на сервере")
    }

    val cookiesSent = mutableListOf<String>()

    override suspend fun setTransports(channel: String, transports: List<NodeTransport>, sudoPassword: String, cookieHeader: String, mode: NodeMode?): InstalledChannel {
        val s = here("setTransports")
        sudo(s, sudoPassword)
        if (transports.isEmpty()) throw NodeWizardException("нужен хотя бы один документ: без него канал не нужен")
        if (cookieHeader.isNotEmpty()) cookiesSent += cookieHeader
        val old = s.channels[channel] ?: throw NodeWizardException("канала $channel нет на сервере")
        val now = old.copy(transports = transports, mode = mode?.cli ?: old.mode)
        s.channels[channel] = now
        return now
    }

    override suspend fun channelShareLink(name: String, key: String, host: String, port: Int, transports: List<NodeTransport>, context: String): String =
        link(name, key, host, port, transports, context)

    override suspend fun checkDocument(documentUrl: String) { here("checkDocument") }
    override suspend fun createCupsRooms(): String { here("createRooms"); return "rooms" }
    override suspend fun createAvitoRooms(): String { here("createAvitoRooms"); return "avito-rooms" }
    override suspend fun shareLink(name: String, key: String, host: String, port: Int, transports: List<NodeTransport>): String =
        link(name, key, host, port, transports)

    /** A link FakeShareLinkCodec reads: the transports by priority, then direct to host:port. */
    private suspend fun link(name: String, key: String, host: String, port: Int, transports: List<NodeTransport>, context: String = "") =
        FakeShareLinkCodec().encode(
            ShareConfig(
                name = name, negotiate = true, secret = key, context = context,
                // As the core: a link cannot carry a script package.
                transports = transports.filter { it.type != "script" }.mapIndexed { i, t -> ShareTransport(type = t.type, url = t.url, priority = 90 - i) } +
                    ShareTransport(type = "direct", dial = "$host:$port", priority = 50),
            ),
        )

    override suspend fun resolve(host: String): Set<String> = setOf(host)
    override val documentPage: StateFlow<BrowserPage?> = MutableStateFlow(null)
    override val logs: StateFlow<List<LogLine>> = MutableStateFlow(emptyList())
    override fun clearLogs() = Unit
    override fun note(text: String, level: LogLevel) = Unit
    override suspend fun createDocument(fileName: String, onStep: (String) -> Unit): YandexDocument =
        YandexDocument("https://docs.yandex.ru/edit/d/" + "a".repeat(20), "Session_id=s; yandex_login=ivan")
    override fun cancelDocument() = Unit
    override fun close() { connected = null; log += "close" }
}
