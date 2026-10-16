package io.openflux.desktop.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode(val label: String) { System("Как в системе"), Light("Светлая"), Dark("Тёмная") }

/** Which releases "check for updates" looks at: the main ones, or also the nightly test builds. */
@Serializable
enum class UpdateChannel(val label: String) { Stable("Основной"), Nightly("Ночной") }

/** How this computer takes part: a client of an exit, or an exit itself. */
@Serializable
enum class ConnectionMode(val label: String, val description: String) {
    Client("Клиент (SOCKS5)", "Трафик программ идёт через ноду"),
    Exit("Выходная нода", "Этот компьютер выпускает в интернет других"),
}

/**
 * How an exit node forwards what its clients send (the core's --mode). L4 ends
 * their connections here and opens new ones: it runs everywhere and needs no
 * rights. L3 passes their packets on as they are: faster (one connection end to
 * end), but it needs administrator or root rights and, on Windows, WinDivert.
 */
@Serializable
enum class ExitBackend(val cliName: String, val label: String) {
    L4("l4", "L4 · потоки"),
    L3("l3", "L3 · пакеты"),
}

/** What to tell the user about an exit backend; [needs] is what this computer asks of them for L3, null for nothing. */
fun ExitBackend.describe(needs: String?): String = when (this) {
    ExitBackend.L4 -> "Принимает соединения клиентов и открывает свои: работает везде, права не нужны."
    ExitBackend.L3 -> "Пересылает пакеты как есть: быстрее, чем L4, но нужны права" + (needs?.let { ": $it" } ?: "") + "."
}

/**
 * Which core binary runs the connection: the one packed with the app, the
 * newest release downloaded from the core repository ([CoreRepos.CORE]), or a
 * file of the user's own. Settings show [choices]; [Fork] and [Official] only
 * keep settings saved by 3.8.1 and older readable, [migrated] turns them into [Release].
 */
@Serializable
enum class CoreSource(val label: String, val repo: String? = null) {
    Bundled("Встроенное"),
    Release("Выпуск с GitHub", CoreRepos.CORE),
    Custom("Свой файл"),
    Fork("Выпуск с GitHub", CoreRepos.CORE),
    Official("Выпуск с GitHub", CoreRepos.CORE);

    companion object {
        val choices = listOf(Bundled, Release, Custom)
    }
}

/** The OpenFlux core repository: the app's core is built from it and its releases are downloaded from it. */
object CoreRepos {
    const val CORE = "p1neappleXpress/OpenFlux"
}

/**
 * The core the "Своя нода" wizard installs on the server; the node's updater
 * then follows that repository's releases. [id] is the core's --node-wizard
 * source.
 */
enum class NodeCoreSource(val id: String, val repo: String) {
    Core("official", CoreRepos.CORE),
}

/** The Windows proxy settings found before OpenFlux changed them. */
@Serializable
data class SavedSystemProxy(val enabled: Boolean, val server: String, val override: String)

/**
 * Where the main window was, in dp (the screen's own units on the desktop).
 * [x], [y], [width] and [height] are the normal, not maximized, bounds.
 */
@Serializable
data class WindowBounds(val x: Float, val y: Float, val width: Float, val height: Float, val maximized: Boolean = false)

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    val mode: ConnectionMode = ConnectionMode.Client,
    val socksPort: Int = 1080,
    /** Point Windows (browsers and most programs) at the core's HTTP proxy while connected. */
    val systemProxy: Boolean = true,
    /**
     * The system proxy used to be off by default, so a connected client did
     * not carry the computer's traffic; set once [systemProxy] was turned on
     * for settings saved before that. See [migrated].
     */
    val systemProxyDefaultOn: Boolean = false,
    /**
     * Client: all of the computer's traffic through Wintun (Windows) or utun (macOS) (the
     * core's --inbound=tun), like the Android VPN, instead of the proxies.
     * Needs administrator rights.
     */
    val fullTunnel: Boolean = false,
    val autoConnect: Boolean = false,
    val selectedProfileId: String? = null,
    val coreSource: CoreSource = CoreSource.Bundled,
    val customCorePath: String = "",
    /**
     * The core's --debug level: 0 off, 1 packet movement (-d), 2 operational
     * logs incl. session/crypto/KDF context (-dd), 3 hexdumps (-ddd).
     */
    val debugLevel: Int = 0,
    val logAutoScroll: Boolean = true,
    /** Hide document URLs and keys in the log view. */
    val maskSensitive: Boolean = true,
    val closeToTray: Boolean = true,
    /** Release channel the update check follows (also the channel of script transports' own updates). */
    val updateChannel: UpdateChannel = UpdateChannel.Stable,
    /** Look for a newer release of the app on GitHub now and then, and offer it. */
    val autoUpdate: Boolean = true,
    /** The release tag the person said "пропустить" to: it is not offered again. */
    val skippedUpdate: String = "",
    /** When the app last asked GitHub for a newer release (ms since epoch); 0 = never. */
    val updateCheckedAt: Long = 0,
    /** Install an update of a first-party script transport without asking (same wire, same key). Others always ask. */
    val autoUpdateScripts: Boolean = true,
    /** When the script transports were last checked for updates (ms since epoch); 0 = never. */
    val scriptsCheckedAt: Long = 0,
    /** Exit mode: address clients dial for direct ("" = the core's guess). */
    val exitShareHost: String = "",
    /** Exit mode: TCP port for the direct transport. */
    val exitDirectPort: Int = 8445,
    /** Exit mode: how the node forwards its clients' traffic. */
    val exitBackend: ExitBackend = ExitBackend.L4,
    /**
     * "Экспериментальные функции": what is still being proved stays off until the user turns it on. Today that
     * is every JS-engine feature: the «Транспорты» tab, installing, updating and configuring script transports,
     * and connecting through one. Off, none of it is shown, started or fetched.
     */
    val experimental: Boolean = false,
    /** Set while OpenFlux has changed the Windows proxy; restored on exit or next start. */
    val savedSystemProxy: SavedSystemProxy? = null,
    val sidebarCollapsed: Boolean = false,
    /**
     * Developer mode: shows the Accounts tab. Turned on by tapping the app
     * version in Settings → About ten times, off by its switch there.
     */
    val developerMode: Boolean = false,
    /** The main window's last size and place, restored on the next start. */
    val window: WindowBounds? = null,
    /** Node wizard: trusted SSH host keys by "host:port". */
    val knownHostKeys: Map<String, String> = emptyMap(),
    /**
     * Open documents as the signed-in account (Accounts tab): the sign-in
     * goes into the core's cookie store, and to your own nodes.
     */
    val useAccountSessions: Boolean = true,
    /** Node wizard: servers used before, newest first. No passwords. */
    val knownServers: List<KnownServer> = emptyList(),
    /** The new interface's place and screens (JSON of io.openflux.app.nav.NavState), restored at the next start on the desktop. */
    val navState: String = "",
)

/** Settings from an older version, brought up to date once on load. */
fun AppSettings.migrated(): AppSettings {
    val proxied = if (systemProxyDefaultOn) this else copy(systemProxy = true, systemProxyDefaultOn = true)
    // The fork / original choice is gone: both mean the one core repository.
    return if (proxied.coreSource == CoreSource.Fork || proxied.coreSource == CoreSource.Official) proxied.copy(coreSource = CoreSource.Release) else proxied
}
