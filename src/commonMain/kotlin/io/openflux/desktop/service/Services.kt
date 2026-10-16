package io.openflux.desktop.service

import androidx.compose.runtime.staticCompositionLocalOf
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AccountSession
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.CaptchaPrompt
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.CoreSource
import io.openflux.desktop.model.NodeCoreSource
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.LogLine
import io.openflux.desktop.model.NewChannel
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.NodePlan
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.ServerProbe
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.ScriptSource
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ShareLinkCodec
import io.openflux.desktop.model.TrafficStats
import io.openflux.desktop.model.YandexDocument
import io.openflux.app.platform.BrowserPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/** Saved sign-ins, one per service. The file is readable by its owner only. */
interface AccountRepository {
    val sessions: StateFlow<Map<AccountKind, AccountSession>>
    fun save(session: AccountSession)
    fun remove(kind: AccountKind)
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

    /**
     * Starts [profile] in [mode], or in the settings' mode when null. The
     * node wizard checks a new node as a client whatever the app is set
     * to: in exit mode the check ran a second exit on the node's own
     * documents, which took its clients' packets.
     */
    fun connect(profile: Profile, mode: ConnectionMode? = null)
    fun disconnect()
    fun refreshExitAddress()
    fun clearLogs()

    fun openCaptcha()
    fun submitCaptcha()
    fun dismissCaptcha()

    /**
     * Hands the saved sign-in of [kind] to the exit over the tunnel, for
     * each of the profile's transports of that service; the exit applies
     * and keeps it. Returns how many transports it went to.
     */
    suspend fun pushAccountToExit(kind: AccountKind): Int =
        throw UnsupportedOperationException("Передать вход ноде здесь нельзя")

    /** Stops the core and undoes system changes; called once on app exit. */
    fun shutdown()
}

/** Where the app runs; decides wording and touch-sized controls. */
enum class PlatformKind { Desktop, Android }

/** Platform facilities the UI needs without touching the platform itself. */
interface PlatformServices {
    val kind: PlatformKind get() = PlatformKind.Desktop
    val appVersion: String
    /** What this device is called in the account's device list. */
    val deviceName: String get() = "Устройство"
    /** Seconds east of UTC in this device's zone now, for dates the user reads. */
    val utcOffsetSeconds: Int get() = 0
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
    /** Whether [shareText] has somewhere to send a link: the system share sheet (Android); the desktop offers messengers instead. */
    val shareSupported: Boolean get() = false
    /** Hands [text] (a link) to another app to send; false if nothing took it. */
    fun shareText(text: String): Boolean = false
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

    // --- The app's own updates (see AppUpdates; defaults keep platforms without them building) ---

    /** Whether the app can download and start the installer of a new release; otherwise the release's page is opened. */
    val updateInstallSupported: Boolean get() = false
    /** The text at an https [url] (GitHub's release list, a release's SHA256SUMS.txt); throws when it cannot be read. */
    suspend fun httpText(url: String): String = throw UnsupportedOperationException("Загрузка не поддерживается")
    /** The file of a release this system installs (the installer for its OS and CPU), null when there is none. */
    fun updateAsset(assets: List<io.openflux.desktop.model.AppAsset>): io.openflux.desktop.model.AppAsset? = null
    /**
     * Downloads [asset] and returns the path of the file. Checks its SHA-256 against [sha256] (hex) and throws,
     * leaving nothing behind, when it differs. [onProgress] gets bytes done and the total (0 when unknown).
     */
    suspend fun downloadUpdate(asset: io.openflux.desktop.model.AppAsset, sha256: String, onProgress: (Long, Long) -> Unit): String =
        throw UnsupportedOperationException("Обновление здесь не поддерживается")
    /**
     * Starts the installer at [path]. The desktop quits for the Windows installer, which must replace the app;
     * Android may first ask the person to allow installing from OpenFlux ([InstallResult.started] false).
     */
    suspend fun installUpdate(path: String): io.openflux.desktop.model.InstallResult =
        io.openflux.desktop.model.InstallResult(false, "Обновление здесь не поддерживается")

    /** Whether [downloadCore] works here (the desktop; Android has its core built in). */
    val coreDownloadSupported: Boolean get() = false
    /** The release tag of the core downloaded for [source] (Fork, Official), null when there is none. */
    fun downloadedCore(source: CoreSource): String? = null
    /**
     * Downloads the newest release core for [source] from its repository on
     * GitHub, checked against the release's SHA256SUMS.txt, and returns the
     * release tag.
     */
    suspend fun downloadCore(source: CoreSource): String = throw UnsupportedOperationException("Скачивание ядра здесь не поддерживается")

    /** New cups.online rooms, packed the way the core takes them; throws when none could be opened. */
    suspend fun newCupsRooms(): String = throw UnsupportedOperationException("Комнаты здесь не создаются")
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
    /**
     * SSH in, download the pinned installer for [source] (whose core the
     * node gets and follows) and look at the server.
     */
    suspend fun connect(target: SshTarget, source: NodeCoreSource): ServerProbe
    suspend fun newChannel(): NewChannel
    /**
     * What installing [channel] with [transports] (besides direct) would
     * change; the server picks the port. [autoUpdate] turns the server's
     * core updater on or off.
     */
    suspend fun plan(channel: String, transports: List<NodeTransport>, withCookies: Boolean, autoUpdate: Boolean, mode: NodeMode = NodeMode.L4): NodePlan
    /** Install and start the channel. [cookieHeader] "" leaves the node signed out of Yandex. */
    suspend fun apply(
        channel: NewChannel,
        transports: List<NodeTransport>,
        port: Int,
        autoUpdate: Boolean,
        sudoPassword: String,
        cookieHeader: String,
        mode: NodeMode = NodeMode.L4,
    )
    suspend fun remove(channel: String, sudoPassword: String)

    /** A channel already on the server: its documents, port, state and encryption context (needs root). */
    suspend fun showChannel(channel: String, sudoPassword: String): InstalledChannel =
        throw NodeWizardException("Изменение каналов на сервере здесь не поддерживается")

    /**
     * Changes the documents of a channel already on the server (some added,
     * some removed; direct stays). Its key, port and encryption context stay,
     * so its links keep working. [cookieHeader], when not empty, is a new
     * Yandex sign-in for its Yandex document. [mode] changes how the node forwards traffic; null keeps its own.
     * Returns the channel as it is now.
     */
    suspend fun setTransports(channel: String, transports: List<NodeTransport>, sudoPassword: String, cookieHeader: String = "", mode: NodeMode? = null): InstalledChannel =
        throw NodeWizardException("Изменение каналов на сервере здесь не поддерживается")

    /** [shareLink] for a channel already on the server: the link keeps its [context]. */
    suspend fun channelShareLink(name: String, key: String, host: String, port: Int, transports: List<NodeTransport>, context: String): String =
        throw NodeWizardException("Изменение каналов на сервере здесь не поддерживается")
    /** Whether the node can use the Yandex document (edit by link), as an anonymous visitor. */
    suspend fun checkDocument(documentUrl: String)
    /** New cups.online rooms for the channel: the packed list the node and its link take. */
    suspend fun createCupsRooms(): String
    /** Avito Code rooms for a new channel, packed as the node takes them; they need no sign-in. */
    suspend fun createAvitoRooms(): String = throw NodeWizardException("Комнаты Avito здесь не создаются: обновите приложение")
    /** The channel's `openflux://` link: [transports], then direct to host:port as the backup. */
    suspend fun shareLink(name: String, key: String, host: String, port: Int, transports: List<NodeTransport>): String
    /** The addresses [host] resolves to, to compare with the tunnel's exit. */
    suspend fun resolve(host: String): Set<String>

    /** The Yandex page while [createDocument] runs. */
    val documentPage: StateFlow<BrowserPage?>

    /** This attempt's trace: every SSH/RPC call and the wizard's own step narration, for the Logs tab. */
    val logs: StateFlow<List<LogLine>>
    fun clearLogs()
    /** Adds a line to [logs] from outside (the wizard model's own step narration). */
    fun note(text: String, level: LogLevel = LogLevel.Info)

    /**
     * Opens Yandex in the built-in browser (downloaded on first use) for the
     * user to sign in, then creates /openflux/[fileName] on their Disk with
     * edit access by link. [onStep] reports progress. The sign-in is wiped
     * from the browser afterwards; only the returned cookies keep it.
     */
    suspend fun createDocument(fileName: String, onStep: (String) -> Unit): YandexDocument
    fun cancelDocument()

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
    val accounts: Accounts,
    /** The "без сервера" wizard's steps: a PHP node on an ordinary web host, put there over FTP by the core. */
    val phpHosting: PhpHostingService = PhpHostingService(),
    /** Installed JS script transports; in-memory no-op unless the platform ships one. */
    val scripts: ScriptRepository = InMemoryScriptRepository(),
    /** The subscription service client and where the subscription is kept; none unless the platform ships them. */
    val subscriptionApi: SubscriptionApi = NoSubscriptionApi,
    val subscriptionRepo: SubscriptionRepository = InMemorySubscriptionRepository(),
    /** The OpenFlux bot's API and where its session and connections are kept. */
    val botApi: BotApi = NoBotApi,
    val botRepo: BotRepository = InMemoryBotRepository(),
    val signIn: SignInSupport = NoSignIn,
    /** Shows a script's settings page in the platform's built-in browser. */
    settingsPageHost: SettingsPageHost = NoSettingsPageHost,
) {
    /** Accounts, the subscription document and its profiles. */
    val subscription: SubscriptionManager by lazy {
        SubscriptionManager(subscriptionApi, subscriptionRepo, profiles, shareCodec, platform, connection, io.openflux.desktop.model.ServiceDefaults.endpoint)
    }

    /** The OpenFlux bot: its connection links as profiles, and its subscription after a Telegram sign-in. */
    val bot: BotAccountManager by lazy { BotAccountManager(botApi, botRepo, profiles, shareCodec, platform, connection, accounts) }

    /** The "Настройки" of an installed script transport (its wizard page, saved with the script). */
    val scriptSettings: ScriptSettingsService by lazy { ScriptSettingsService(scripts, platform, settingsPageHost) }

    /** Checks installed script transports for updates and applies them. */
    val scriptUpdater: ScriptUpdater by lazy { ScriptUpdater(scripts, platform, settings) }

    /** The app's own updates from its GitHub releases. */
    val updates: AppUpdates by lazy { AppUpdates(platform, settings, CoroutineScope(SupervisorJob() + Dispatchers.Default)) }

    /** An `openflux://` link opened from outside (a scanned code, a chat); the Profiles screen imports it. */
    val incomingLink = MutableStateFlow<String?>(null)

    /** A subscription key opened from outside; the Subscription screen takes it into its field. */
    val pendingKey = MutableStateFlow<String?>(null)
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer is not provided") }
