package io.openflux.desktop.model

/** Paths the core gets for one run. */
data class CorePaths(
    val keyFile: String?,
    val confFile: String,
    val cookieStore: String,
    val ipcSocket: String?,
)

/**
 * A SCRIPT carrier's on-disk path + pinned key, resolved by the platform
 * layer for CoreConfig.build/session - facts about the installed FILE, the
 * same for every carrier using it. What the carrier itself saved (profile
 * value, settings wizard) travels on the carrier/spec instead; see
 * [ExtraTransport.settings].
 */
data class ScriptCarrierLookup(
    val path: String,
    val pubkeyHex: String,
    val name: String,
    /** [InstalledScript.primaryParam]'s key, null when the script has none. */
    val primaryParamKey: String? = null,
)

/** How to start the core for a profile: the .conf body (Session) and flags. */
data class CoreLaunch(
    val arguments: List<String>,
    val conf: String?,
    val socksAddress: String?,
    val httpProxyAddress: String?,
    /** The core's IPC status says whether it reaches the exit (Sessions); otherwise its log does. */
    val usesIpc: Boolean,
)

/**
 * Turns a profile and the settings into the core's command line. Sessions
 * go through a `--config` file (the only way to name several transports of
 * one type); classic profiles use the single-transport flags.
 */
object CoreConfig {
    const val LOOPBACK = "127.0.0.1"

    /** Why a profile with a JS transport does not connect while the experimental features are off. */
    const val SCRIPTS_OFF = "В профиле JS-транспорт, а экспериментальные функции выключены: включите их в настройках («Экспериментальные функции»)"

    /**
     * [scriptCarrier] resolves a SCRIPT carrier's installed script (by
     * [ExtraTransport.scriptId]/[SessionSpec.scriptId]) to its on-disk path
     * and pinned key - platform-specific (the scripts directory lives under
     * each app's own data folder), so it is injected rather than looked up
     * here. The default (never called in practice: a script-less profile
     * never reaches it) keeps every other caller/test source-compatible.
     */
    fun build(
        profile: Profile,
        settings: AppSettings,
        paths: CorePaths,
        scriptCarrier: (scriptId: String) -> ScriptCarrierLookup? = { null },
    ): CoreLaunch {
        val problems = profile.problems()
        require(problems.isEmpty()) { problems.first() }
        require(settings.experimental || profile.carriers.none { it.type == TransportType.SCRIPT }) { SCRIPTS_OFF }
        val exit = settings.mode == ConnectionMode.Exit
        val socks = "$LOOPBACK:${settings.socksPort}"
        val http = "$LOOPBACK:${settings.socksPort + 1}"
        if (profile.stream) {
            require(!exit) { "Режим без сервера работает только как клиент: выхода в нём нет, сервер заменяет PHP-хостинг" }
            return stream(profile, settings, paths, socks, http)
        }
        return if (profile.session) session(profile, settings, paths, exit, socks, http, scriptCarrier) else classic(profile, settings, paths, exit, socks, http)
    }

    private fun session(
        profile: Profile, settings: AppSettings, paths: CorePaths, exit: Boolean, socks: String, http: String,
        scriptCarrier: (scriptId: String) -> ScriptCarrierLookup?,
    ): CoreLaunch {
        val conf = buildString {
            appendLine("# OpenFlux Desktop: ${profile.name}")
            appendLine("[Interface]")
            if (exit) {
                appendLine("Role = exit")
                appendLine("Mode = ${settings.exitBackend.cliName}")
            } else if (settings.fullTunnel) {
                appendLine("Role = client")
                appendLine("Inbound = tun")
            } else {
                appendLine("Role = client")
                appendLine("Inbound = socks5")
                appendLine("Socks5 = $socks")
            }
            appendLine("EncryptionKeyFile = ${confValue(paths.keyFile ?: error("Session requires a key"))}")
            appendLine("CookieStore = ${confValue(paths.cookieStore)}")
            val specs = profile.sessionSpecs().filterNot { exit && it.type == TransportType.DIRECT }
            for (spec in specs) {
                appendLine()
                appendLine("[Transport ${spec.name}]")
                appendLine("Type = ${spec.type.cliName}")
                appendLine("Priority = ${spec.priority}")
                when (spec.type.kind) {
                    ValueKind.DocumentUrl -> appendLine("URL = ${confValue(spec.value)}")
                    ValueKind.Address -> appendLine("Dial = ${confValue(spec.value)}")
                    ValueKind.Token -> {
                        appendLine("Token = ${confValue(spec.value)}")
                        appendLine("UID = ${confValue(spec.uid)}")
                    }
                }
                if (spec.type == TransportType.SCRIPT) {
                    val carrier = scriptCarrier(spec.scriptId)
                        ?: error("Скрипт-транспорт «${spec.scriptId}» не найден (удалён или не импортирован на этом устройстве)")
                    appendLine("Path = ${confValue(carrier.path)}")
                    appendLine("Pubkey = ${confValue(carrier.pubkeyHex)}")
                    appendLine("Name = ${confValue(carrier.name)}")
                    // The profile param's own value rides along under its declared key too,
                    // mirroring the URL line above: a script that reads cfg.params[key]
                    // instead of cfg.url sees the one the profile editor's field saved.
                    val merged = spec.settings + (carrier.primaryParamKey?.let { mapOf(it to spec.value) } ?: emptyMap())
                    // The saved settings ride as one line: a .conf value ends at '#' or ';' and a setting may hold either.
                    if (merged.isNotEmpty()) appendLine("Params = ${ScriptSettingsCodec.encode(merged)}")
                }
            }
            // The exit listens for direct only when the profile has it; its
            // address there is what clients dial, the exit takes the port
            // from the settings.
            val direct = profile.carriers.firstOrNull { it.type == TransportType.DIRECT }
            if (exit && direct != null) {
                appendLine()
                appendLine("[Transport direct]")
                appendLine("Type = direct")
                appendLine("Priority = ${direct.priority}")
                appendLine("Listen = 0.0.0.0:${settings.exitDirectPort}")
            }
        }
        val args = buildList {
            add("--config"); add(paths.confFile)
            // An imported context goes on the command line (.conf values end
            // at '#'); without one the core derives it by its rule, the one
            // the exit uses too.
            if (profile.context.isNotBlank()) add("--session-context=${profile.context}")
            if (paths.ipcSocket != null) add("--ipc-socket=${paths.ipcSocket}")
            if (exit) {
                add("--share")
                if (settings.exitShareHost.isNotBlank()) add("--share-host=${settings.exitShareHost.trim()}")
            } else if (!settings.fullTunnel) {
                add("--http-proxy=$http")
            }
            if (settings.debugLevel > 0) add("--debug=${settings.debugLevel}")
        }
        val proxies = !exit && !settings.fullTunnel
        return CoreLaunch(args, conf, if (proxies) socks else null, if (proxies) http else null, usesIpc = paths.ipcSocket != null)
    }

    /**
     * The mode without a server: the core's `--mode=stream` (the stream mux over cups.online or a
     * Mail.ru document to the PHP node), as proxies or, with the full tunnel, through the
     * system's TUN. No key, no codec, no Session: nothing of those applies.
     */
    private fun stream(profile: Profile, settings: AppSettings, paths: CorePaths, socks: String, http: String): CoreLaunch {
        val args = buildList {
            add("--role=client")
            add("--mode=stream")
            if (settings.fullTunnel) {
                add("--inbound=tun")
            } else {
                // Named, not left to the core: core 0.3.0 takes utun for a client on macOS when --inbound is missing.
                add("--inbound=socks5")
                add("--socks5=$socks")
                add("--http-proxy=$http")
            }
            add("--transport=${profile.transport.cliName}")
            add("--url=${profile.value.trim()}")
            // Traffic totals for the speed counters (proxy mode; the log says when it is up).
            if (paths.ipcSocket != null && !settings.fullTunnel) add("--ipc-socket=${paths.ipcSocket}")
            if (settings.debugLevel > 0) add("--debug=${settings.debugLevel}")
        }
        return if (settings.fullTunnel) CoreLaunch(args, null, null, null, usesIpc = false)
        else CoreLaunch(args, null, socks, http, usesIpc = false)
    }

    private fun classic(profile: Profile, settings: AppSettings, paths: CorePaths, exit: Boolean, socks: String, http: String): CoreLaunch {
        val args = buildList {
            if (exit) {
                // The same exit a Session profile runs, serving classic
                // clients of this transport.
                add("--role=exit")
                add("--mode=${settings.exitBackend.cliName}")
            } else if (settings.fullTunnel) {
                add("--role=client")
                add("--inbound=tun")
            } else {
                add("--role=client")
                add("--inbound=socks5")
                add("--socks5=$socks")
                add("--http-proxy=$http")
            }
            add("--transport=${profile.transport.cliName}")
            add("--codec=${profile.codec.cliName}")
            if (profile.transport == TransportType.ONEME) {
                add("--maxToken=${profile.value.trim()}")
                add("--maxUid=${profile.uid.trim()}")
            } else {
                add("--url=${profile.value.trim()}")
            }
            if (paths.keyFile != null) add("--encryption-key-file=${paths.keyFile}")
            add("--cookie-store=${paths.cookieStore}")
            // Traffic totals for the speed counters; the state still comes
            // from the log (usesIpc = false).
            if (paths.ipcSocket != null) add("--ipc-socket=${paths.ipcSocket}")
            if (exit) {
                add("--share")
                if (settings.exitShareHost.isNotBlank()) add("--share-host=${settings.exitShareHost.trim()}")
            }
            if (settings.debugLevel > 0) add("--debug=${settings.debugLevel}")
        }
        return if (exit || settings.fullTunnel) CoreLaunch(args, null, null, null, usesIpc = false)
        else CoreLaunch(args, null, socks, http, usesIpc = false)
    }

    /** .conf values end at '#' or ';' (comments); refuse ones that would be cut. */
    private fun confValue(value: String): String {
        require(value.none { it == '#' || it == ';' || it == '\n' || it == '\r' }) {
            "Значение «${value.take(40)}» нельзя передать ядру: в нём есть # или ;"
        }
        return value
    }
}
