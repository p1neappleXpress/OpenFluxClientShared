package io.openflux.desktop.service

import androidx.compose.runtime.staticCompositionLocalOf
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.CaptchaPrompt
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.LogLine
import io.openflux.desktop.model.NewChannel
import io.openflux.desktop.model.NodePlan
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.ServerProbe
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.ScriptSource
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ShareLinkCodec
import io.openflux.desktop.model.TrafficStats
import io.openflux.desktop.ui.BrowserPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Saved profiles. Writes are persisted before the flow updates. */
interface ProfileRepository {
    val profiles: StateFlow<List<Profile>>
    fun upsert(profile: Profile)
    fun delete(id: String)
    fun newId(): String
}

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    fun update(transform: (AppSettings) -> AppSettings)
}

/** Installed JS (goja) script transports. Writes persist before the flow updates. */
interface ScriptRepository {
    val scripts: StateFlow<List<InstalledScript>>
    fun upsert(script: InstalledScript)
    fun delete(id: String)
    fun setEnabled(id: String, enabled: Boolean)
    fun byId(id: String): InstalledScript? = scripts.value.firstOrNull { it.id == id }

    /** Folder the packages are in; "" when the platform has none. */
    val dirPath: String get() = ""

    /**
     * Re-reads the installed package file and updates its record (after an
     * update or rollback, or to fill in what an older install lacks: the
     * package id, wire, update addresses). Returns the record, null if gone.
     */
    fun refresh(id: String): InstalledScript? = null

    /** Whether the version this one replaced is still on disk, so a rollback is possible. */
    fun hasPrevious(id: String): Boolean = false

    /** Pins the install to another author key, after the core accepted an update signed by it (a rotation of OpenFlux's own keys). */
    fun repin(id: String, pubkeyHex: String, fingerprint: String) {}

    /**
     * The installed file and its detached signature (empty for a .flux): what
     * the core reads to build the settings page. Null when it is gone.
     */
    fun packageBytes(id: String): Pair<ByteArray, ByteArray>? = null

    /**
     * Verifies a downloaded transport ([data] a .flux or bare .js, [sig] the
     * detached signature for a .js) against [pubkeyHex] and, only if the
     * signature is valid, stores and records it. Throws with a reason the
     * trust dialog shows. Unsupported on platforms without script transports.
     */
    fun install(
        data: ByteArray,
        sig: ByteArray,
        pubkeyHex: String,
        source: ScriptSource,
        origin: String,
        now: Long,
    ): InstalledScript = throw UnsupportedOperationException("script transports not supported here")
}

/** A no-op registry so platforms that don't ship script transports still build. */
class InMemoryScriptRepository : ScriptRepository {
    private val _scripts = MutableStateFlow<List<InstalledScript>>(emptyList())
    override val scripts: StateFlow<List<InstalledScript>> = _scripts
    override fun upsert(script: InstalledScript) {
        _scripts.value = _scripts.value.filterNot { it.id == script.id } + script
    }
    override fun delete(id: String) { _scripts.value = _scripts.value.filterNot { it.id == id } }
    override fun setEnabled(id: String, enabled: Boolean) {
        _scripts.value = _scripts.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }
}

/** Runs the OpenFlux core for one profile at a time. */
interface ConnectionService {
    val state: StateFlow<ConnectionState>
    val traffic: StateFlow<TrafficStats>
    val exitAddress: StateFlow<ExitAddress>
    val logs: StateFlow<List<LogLine>>
    val captcha: StateFlow<CaptchaPrompt?>
    /** Exit mode: the `openflux://` link the core printed for clients. */
    val exitShareLink: StateFlow<String?>
    /** The local SOCKS5 address while connected as a client. */
    val socksAddress: StateFlow<String?>
    /** The Yandex check in the built-in browser while [captcha] is open. */
    val captchaPage: StateFlow<BrowserPage?>

    fun connect(profile: Profile)
    fun disconnect()
    fun refreshExitAddress()
    fun clearLogs()

    fun openCaptcha()
    fun submitCaptcha()
    fun dismissCaptcha()

    /** Stops the core and undoes system changes; called once on app exit. */
    fun shutdown()
}

/** Where the app runs; decides wording and touch-sized controls. */
enum class PlatformKind { Desktop, Android }

/** Platform facilities the UI needs without touching the platform itself. */
interface PlatformServices {
    val kind: PlatformKind get() = PlatformKind.Desktop
    val appVersion: String
    val coreVersion: String
    /** Where this app's own releases are published, e.g. "p1neappleXpress/OpenFluxDesktop". */
    val clientRepo: String
    /** Whether this OS can point its system proxy at OpenFlux. */
    val systemProxySupported: Boolean
    /** Whether the full tunnel (all traffic through Wintun on Windows, utun on macOS) runs on this OS. */
    val fullTunnelSupported: Boolean
    /** Whether OpenFlux runs with administrator rights, which the full tunnel needs. */
    val elevated: Boolean
    /**
     * How the full tunnel gets administrator rights for the core on each
     * connect, in the user's words ("macOS спросит пароль администратора"),
     * when OpenFlux itself is not elevated; null when nothing is asked.
     */
    val fullTunnelPrompt: String? get() = null
    /** Whether an exit node can forward packets here (the core's L3 backend): Windows and Linux, not macOS or a phone. */
    val exitL3Supported: Boolean get() = false
    /** What the L3 exit asks of the user on this computer, in their words; null when nothing. */
    val exitL3Needs: String? get() = null

    /** Starts OpenFlux again as administrator (UAC) and exits this copy; false if that did not happen. */
    fun restartElevated(): Boolean

    fun clipboardText(): String?
    fun setClipboardText(text: String)
    /** Whether [qrFromClipboardImage] can find images on the clipboard. */
    val clipboardImageSupported: Boolean get() = true
    /** Text of a QR code in the image on the clipboard, null when none. */
    fun qrFromClipboardImage(): String?
    /** Text of a QR code in an image file ([pickFile]'s result). */
    fun qrFromFile(path: String): String?
    /** A file the user picks (a path or, on Android, a content URI); null if they cancel. */
    suspend fun pickFile(title: String, extensions: List<String>): String?
    /** Whether [scanQr] has a camera to use. */
    val cameraScanSupported: Boolean get() = false
    /** Text of a QR code the camera read; null if the user cancels. */
    suspend fun scanQr(): String? = null
    /** A small text file's contents (an SSH key), null if it cannot be read. */
    fun readTextFile(path: String, maxBytes: Int = 64 * 1024): String?
    /** The QR modules of [text], rows of dark (true) cells. */
    fun qrMatrix(text: String): List<BooleanArray>
    fun openUrl(url: String)
    /** A new random channel key (64 hex characters). */
    fun newSecret(): String
    fun now(): Long
    /** Newest app release tag on GitHub, null when unknown. */
    suspend fun latestRelease(): String?
    /**
     * Newest release of either channel (a main `v*` or a `nightly-*` test
     * build), by publication date; null when unknown or not supported.
     */
    suspend fun latestNightly(): String? = null

    // --- JS script transports (defaults keep platforms without them building) ---

    /** The OpenFlux first-party script signing key (hex); "" when scripts aren't supported. */
    val officialScriptKey: String get() = ""

    /**
     * Reads a downloaded transport and returns the core's JSON trust report
     * (name, version, params, signature, fingerprint, official) without
     * running it. data is a .flux package or a bare .js; sig is the detached
     * signature for a bare .js (empty for .flux); pubkeyHex is the candidate
     * author key or "".
     */
    fun inspectTransport(data: ByteArray, sig: ByteArray, pubkeyHex: String): String =
        """{"ok":false,"error":"script transports not supported on this platform"}"""

    /**
     * The settings wizard page of an installed transport: the core verifies
     * [data]/[sig] against the pinned [pubkeyHex], reads the script's declared
     * settings and returns its JSON SettingsReport (ok, code, error, params,
     * html, values) for [valuesJson], the current settings as a JSON object of
     * strings. [lang] is "ru" or "en". Blocking: call off the UI thread.
     */
    fun scriptSettings(data: ByteArray, sig: ByteArray, pubkeyHex: String, valuesJson: String, lang: String): String =
        """{"ok":false,"code":"failed","error":"script transports not supported on this platform"}"""

    /** SHA-256 (hex) of an author public key, "" if it can't be decoded. */
    fun scriptFingerprint(pubkeyHex: String): String = ""

    /** GETs a URL (adding a script from GitHub), null on failure. */
    suspend fun fetchBytes(url: String): ByteArray? = null

    /** Reads a picked file (path or content URI) as bytes, null on failure. */
    suspend fun readBytes(pathOrUri: String): ByteArray? = null

    // The core's script-transport updates; each takes the installed transport as
    // JSON ({"id","file","version","wire","pubkey","update":[...]}) and returns
    // the core's JSON report. Blocking (network): call off the UI thread.
    fun checkScriptUpdate(installedJson: String, channel: String): String = UNSUPPORTED_UPDATE
    fun applyScriptUpdate(installedJson: String, channel: String, dir: String, allowWireBreak: Boolean): String = UNSUPPORTED_UPDATE
    fun rollbackScript(installedJson: String, dir: String): String = UNSUPPORTED_UPDATE
}

const val UNSUPPORTED_UPDATE = """{"status":"error","code":"unsupported"}"""

/**
 * The "Своя нода" wizard's server side: installs an independent exit
 * channel on the user's VDS over SSH (the core's --node-wizard), creates
 * the channel's Yandex document in the built-in browser and checks it.
 * Calls block until done and fail with NodeWizardException.
 */
interface NodeWizardService {
    /** SSH in, download the pinned installer and look at the server. */
    suspend fun connect(target: SshTarget): ServerProbe
    suspend fun newChannel(): NewChannel
    /**
     * What installing [channel] with [transports] (besides direct) would
     * change; the server picks the port. [autoUpdate] turns the server's
     * core updater on or off.
     */
    suspend fun plan(channel: String, transports: List<NodeTransport>, autoUpdate: Boolean): NodePlan
    /** Install and start the channel. */
    suspend fun apply(
        channel: NewChannel,
        transports: List<NodeTransport>,
        port: Int,
        autoUpdate: Boolean,
        sudoPassword: String,
    )
    suspend fun remove(channel: String, sudoPassword: String)
    /** Whether the node can use the Yandex document (edit by link), as an anonymous visitor. */
    suspend fun checkDocument(documentUrl: String)
    /** New cups.online rooms for the channel: the packed list the node and its link take. */
    suspend fun createCupsRooms(): String
    /** The channel's `openflux://` link: [transports], then direct to host:port as the backup. */
    suspend fun shareLink(name: String, key: String, host: String, port: Int, transports: List<NodeTransport>): String
    /** The addresses [host] resolves to, to compare with the tunnel's exit. */
    suspend fun resolve(host: String): Set<String>

    /** This attempt's trace: every SSH/RPC call and the wizard's own step narration, for the Logs tab. */
    val logs: StateFlow<List<LogLine>>
    fun clearLogs()
    /** Adds a line to [logs] from outside (the wizard model's own step narration). */
    fun note(text: String, level: LogLevel = LogLevel.Info)

    /** Ends the SSH session and the helper process. */
    fun close()
}

/** Everything the UI depends on, built once in main. */
class AppContainer(
    val profiles: ProfileRepository,
    val settings: SettingsRepository,
    val connection: ConnectionService,
    val platform: PlatformServices,
    val shareCodec: ShareLinkCodec,
    val nodeWizard: NodeWizardService,
    /** The "без сервера" wizard's steps: a PHP node on an ordinary web host, put there over FTP by the core. */
    val phpHosting: PhpHostingService = PhpHostingService(),
    /** Installed JS script transports; in-memory no-op unless the platform ships one. */
    val scripts: ScriptRepository = InMemoryScriptRepository(),
    /** Shows a script's settings page in the platform's built-in browser. */
    settingsPageHost: SettingsPageHost = NoSettingsPageHost,
) {
    /** The "Настройки" of an installed script transport (its wizard page, saved with the script). */
    val scriptSettings: ScriptSettingsService by lazy { ScriptSettingsService(scripts, platform, settingsPageHost) }

    /** Checks installed script transports for updates and applies them. */
    val scriptUpdater: ScriptUpdater by lazy { ScriptUpdater(scripts, platform, settings) }

    /** An `openflux://` link opened from outside (a scanned code, a chat); the Profiles screen imports it. */
    val incomingLink = MutableStateFlow<String?>(null)
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer is not provided") }
