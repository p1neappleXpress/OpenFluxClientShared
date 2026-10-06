package io.openflux.desktop.web

import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFBrowser
import dev.datlag.kcef.KCEFClient
import io.openflux.desktop.data.AppDirs
import io.openflux.desktop.model.SetupPages
import io.openflux.desktop.ui.BrowserPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import org.cef.CefApp
import org.cef.CefSettings
import org.cef.callback.CefCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import org.cef.security.CefSSLInfo
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefRendering
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.awt.Component
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/** A page open in the built-in browser. Close it when done. */
class KcefPage internal constructor(private val browser: KCEFBrowser) : BrowserPage {
    /** The browser's native view, for a SwingPanel. */
    val component: Component get() = browser.uiComponent

    val url: String get() = browser.url.orEmpty()

    val loading: Boolean get() = runCatching { browser.isLoading }.getOrDefault(true)

    @Volatile var closed = false
        private set

    fun load(url: String) = browser.loadURL(url)

    /**
     * Runs [expression] (a JavaScript expression, a Promise is awaited) in
     * the page and returns its value as a string. A thrown error comes back
     * as {"state":"fail","error":...}.
     */
    suspend fun evaluate(expression: String, timeoutMs: Long = 90_000): String {
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<String>()
        BuiltInBrowser.pending[id] = result
        val script = """
            Promise.resolve().then(() => ($expression))
              .then(r => String(r), e => JSON.stringify({state: 'fail', error: String((e && e.message) || e)}))
              .then(r => window.${BuiltInBrowser.QUERY}({request: ${JsonPrimitive(id)} + '|' + r, onSuccess: function () {}, onFailure: function () {}}));
        """.trimIndent()
        try {
            browser.executeJavaScript(script, browser.url, 0)
            return withTimeoutOrNull(timeoutMs) { result.await() }
                ?: run {
                    BrowserLog.problem("скрипт на ${BrowserLog.short(url)} не ответил за ${timeoutMs / 1000} с (загружается: $loading)")
                    throw IllegalStateException("Страница не ответила")
                }
        } finally {
            BuiltInBrowser.pending.remove(id)
        }
    }

    /**
     * Closing a browser makes JCEF (CefBrowser_N.doClose) send WINDOW_CLOSING
     * to the window its view sits in: meant for a browser in a frame of its
     * own, here it is the OpenFlux window, which then went to the tray (or
     * quit, without "close to tray") as soon as a wizard step or a check was
     * done. So the view leaves the window first, on the UI thread, where
     * JCEF looks for that window.
     */
    fun close() {
        if (closed) return
        closed = true
        BuiltInBrowser.forgetSetupPage(browser)
        val dispose = {
            val view = browser.uiComponent
            view.parent?.let { parent ->
                parent.remove(view)
                parent.revalidate()
            }
            runCatching { browser.dispose() }
            Unit
        }
        if (SwingUtilities.isEventDispatchThread()) dispose() else SwingUtilities.invokeLater(dispose)
    }
}

/**
 * Chromium inside OpenFlux (KCEF, JetBrains' JCEF), for Yandex: signing in
 * to create a channel's document and the checks Yandex shows. It is
 * downloaded on first use to the local app data folder. The cache lives in
 * memory and cookies are wiped after each use, so a login stays only in what
 * the caller took from [cookies]. All traffic goes through [proxy].
 */
object BuiltInBrowser {
    internal const val QUERY = "openfluxQuery"
    internal val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /**
     * Pages that are a script's own (openHtml, openOwn), and the address their
     * frame must keep: the only pages the submit bridge is injected into and
     * that window.openfluxSubmit is heard from, so a site a setup page links
     * to cannot hand the script data. CEF assigns a browser's identifier once
     * it has created it, after createImmediately() returns (it reads -1 until
     * then), and the browser object the load handler is given is not the
     * KCEFBrowser built here. So the pages are kept as they were made and
     * matched by identifier when a page has loaded or asks, not when it is
     * registered.
     */
    private class SetupPage(val prefix: String, val onSubmit: ((String) -> Unit)?)

    private val setupPages = ConcurrentHashMap<KCEFBrowser, SetupPage>()
    internal fun forgetSetupPage(browser: KCEFBrowser) { setupPages -= browser }

    /** The registered page [browser] is, if [frameUrl] is still at its own address. */
    private fun setupPage(browser: CefBrowser?, frameUrl: String?): SetupPage? {
        val id = browser?.identifier ?: return null
        if (id <= 0 || frameUrl == null) return null
        return setupPages.entries.firstOrNull { (page, _) -> page.identifier == id }
            ?.value?.takeIf { frameUrl.startsWith(it.prefix, ignoreCase = true) }
    }

    private fun isSetupPage(browser: CefBrowser?, frameUrl: String?): Boolean = setupPage(browser, frameUrl) != null

    /**
     * Raw JSON a setup page handed to window.openfluxSubmit, for pages opened
     * without their own callback (the connection's setup request). A page
     * opened with `onSubmit` (a script's settings wizard) is answered there
     * instead, never here: what one page submits must not reach the other's owner.
     */
    private val _submissions = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val submissions: SharedFlow<String> = _submissions.asSharedFlow()

    val proxy = BrowserProxy()
    private val lock = Mutex()
    @Volatile private var client: KCEFClient? = null
    /** KCEF gets stuck "initializing" when its start fails; this process cannot start it again. */
    @Volatile private var broken: String? = null

    private val installDir: File
        get() {
            val local = System.getenv("LOCALAPPDATA")?.let { File(it, "OpenFlux") }
            return File(local ?: AppDirs.config, "browser")
        }

    /**
     * Opens [url] in a new page; with [upstream] ("127.0.0.1:port") the page
     * goes out through the node's proxy. [onStep] reports the first-run
     * download.
     */
    suspend fun open(url: String, upstream: String? = null, onStep: (String) -> Unit = {}): KcefPage {
        val client = client(onStep)
        proxy.upstream = upstream
        return create(client, url, setup = null).also {
            BrowserLog.info("открываю ${BrowserLog.short(url)}" + if (upstream != null) " через прокси ноды $upstream" else " напрямую")
        }
    }

    /**
     * Opens a script transport's own setup/login page (js/template_html.html
     * in the core) inline - never through the node's proxy, same as
     * httpserver.listen() being loopback-only. The page's own
     * window.openfluxSubmit(payload) reaches [submissions] as raw JSON; the
     * function is there before the page's own scripts run.
     */
    suspend fun openHtml(html: String, onStep: (String) -> Unit = {}, onSubmit: ((String) -> Unit)? = null): KcefPage {
        val client = client(onStep)
        proxy.upstream = null
        val page = SetupPages.inject(html, BRIDGE_JS)
        val url = "data:text/html;charset=utf-8;base64," + Base64.getEncoder().encodeToString(page.toByteArray(Charsets.UTF_8))
        return create(client, url, SetupPage("data:text/html;", onSubmit)).also {
            BrowserLog.info("открываю страницу настройки скрипта")
        }
    }

    /**
     * Opens a script transport's own page served by the script itself
     * (httpserver.listen() in the core), an http address on loopback; the
     * core only lets through the address of a server that script started.
     * Never through the node's proxy. window.openfluxSubmit is put into the
     * page when it has loaded and fires "openflux-ready"; it stays with that
     * address (a link to another site does not get it).
     */
    suspend fun openOwn(url: String, onStep: (String) -> Unit = {}): KcefPage {
        val origin = SetupPages.loopbackOrigin(url)
            ?: throw IllegalArgumentException("Страница настройки скрипта открывается только с адреса 127.0.0.1 его собственного сервера")
        val client = client(onStep)
        proxy.upstream = null
        return create(client, url, SetupPage(origin, null)).also {
            BrowserLog.info("открываю страницу настройки скрипта ${BrowserLog.short(url)}")
        }
    }

    /** Off-screen: frames are drawn by OsrView, see there why. */
    private suspend fun create(client: KCEFClient, url: String, setup: SetupPage?): KcefPage = withContext(Dispatchers.Swing) {
        val view = OsrView()
        val browser = client.createBrowser(url, CefRendering.CefRenderingWithHandler(view.renderHandler, view), false)
        view.browser = browser
        if (setup != null) setupPages[browser] = setup
        // Create it now, not when shown: scripts and cookies work before the page is on screen.
        browser.createImmediately()
        KcefPage(browser)
    }

    /** Cookies the browser would send to [url], HTTP-only ones included. */
    suspend fun cookies(url: String): List<CefCookie> {
        // Any CEF call before KCEF starts it would start CEF itself with
        // the wrong settings, and KCEF could not start it afterwards.
        if (client == null) return emptyList()
        val found = Collections.synchronizedList(mutableListOf<CefCookie>())
        val done = CompletableDeferred<Unit>()
        val started = CefCookieManager.getGlobalManager().visitUrlCookies(url, true) { cookie, count, total, _ ->
            if (cookie != null) found += cookie
            if (count >= total - 1) done.complete(Unit)
            true
        }
        check(started) { "Встроенный браузер не отдал cookies" }
        // CEF never calls the visitor when there are no cookies.
        withTimeoutOrNull(1500) { done.await() }
        return found.toList()
    }

    /** Forgets every cookie: a sign-in must not outlive what it was made for. */
    fun clearCookies() {
        if (client == null) return // nothing to forget, and see cookies()
        runCatching { CefCookieManager.getGlobalManager().deleteCookies(null, null) }
    }

    /** Back to direct traffic once a page that used the node's proxy is closed. */
    fun release() {
        proxy.upstream = null
    }

    private suspend fun client(onStep: (String) -> Unit): KCEFClient = lock.withLock {
        client?.let { return it }
        broken?.let { throw IllegalStateException(it) }
        try {
            start(onStep).also { client = it }
        } catch (e: Exception) {
            val message = e.message ?: "Встроенный браузер не запустился"
            // A failed download can be tried again; a start that got CEF half up cannot.
            if (runCatching { CefApp.getInstanceIfAny() }.getOrNull() == null) throw e
            val final = if ("перезапустите" in message.lowercase()) message else "$message. Перезапустите OpenFlux и повторите"
            broken = final
            throw IllegalStateException(final, e)
        }
    }

    private suspend fun start(onStep: (String) -> Unit): KCEFClient {
        val port = proxy.start()
        val initialized = CompletableDeferred<Unit>()
        var last = ""
        val step = { text: String -> if (text != last) { last = text; BrowserLog.info(text); onStep(text) } }
        val dir = installDir
        BrowserLog.info(
            "запуск: папка $dir, установлен ${runtimeInstalled(dir)}, пакет ${packageUrl()}, " +
                "java ${System.getProperty("java.version")} (${System.getProperty("java.home")}), ${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
        )
        runCatching { BrowserLog.cefLogFile.delete() }
        // KCEF is never left to download: see RuntimeArchive. It is given the archive
        // that is already here, or finds the runtime installed and asks for nothing.
        val runtime = RuntimeArchive(
            File(dir.parentFile ?: dir, "browser-download"), runtimeFile(), runtimeSources(), RUNTIME_SHA512[runtimeKey()],
        )
        val served = if (runtimeInstalled(dir)) null else {
            // A lock without the runtime (a cleaner took files, a folder half-deleted): KCEF
            // would trust the lock and fail to start from then on.
            File(dir, "install.lock").delete()
            step("Готовлю встроенный браузер…")
            ArchiveServer(runtime.fetch(step))
        }
        var error: Throwable? = null
        var restart = false
        try {
            withContext(Dispatchers.IO) {
                KCEF.init(
                    builder = {
                        installDir(installDir)
                        download { custom(served?.url ?: packageUrl()) }
                        progress {
                            onLocating { step("Готовлю встроенный браузер…") }
                            // The archive comes from the disk (RuntimeArchive), so KCEF's own "downloading" is a
                            // copy that takes a moment and says nothing; what follows is its work in the order it
                            // does it: unpack, then install (the layout and the lock).
                            onExtracting { step("Распаковываю встроенный браузер…") }
                            onInstall { step("Устанавливаю встроенный браузер…") }
                            onInitializing { step("Запускаю встроенный браузер…") }
                            onInitialized { initialized.complete(Unit) }
                        }
                        settings {
                            cachePath = null
                            locale = "ru-RU"
                            windowlessRenderingEnabled = true
                            // The core's own (transport/yandex volgaUserAgent), like the
                            // Android app's WebViews: Yandex ties a passed check to it.
                            userAgent = YANDEX_USER_AGENT
                            // JCEF's defaults point into the running JVM's java.home; empty
                            // paths make KCEF use the downloaded runtime instead.
                            resourcesDirPath = null
                            localesDirPath = null
                            browserSubProcessPath = helper(dir)
                            logFile = BrowserLog.cefLogFile.absolutePath
                            logSeverity = dev.datlag.kcef.KCEFBuilder.Settings.LogSeverity.Info
                        }
                        val switches = switches(port, dir)
                        args(*switches)
                        // KCEF hands args() only to CefApp.startup; Chromium reads its
                        // switches from the app handler, which KCEF builds with none. Without
                        // this the pages went through the system proxy, with the GPU on.
                        appHandler(KCEF.AppHandler(switches))
                        BrowserLog.info("ключи Chromium: ${switches.joinToString(" ")}")
                    },
                    onError = { error = it; BrowserLog.problem("ошибка запуска: $it") },
                    onRestartRequired = { restart = true; BrowserLog.problem("KCEF просит перезапуск") },
                )
            }
        } finally {
            served?.close()
        }
        BrowserLog.info("файлы: " + listOf("jcef_helper.exe", "jcef_helper", "libcef.dll", "libcef.so", "jcef.dll", "icudtl.dat", "resources.pak", "locales", "Frameworks")
            .filter { File(dir, it).exists() }.joinToString())
        // KCEF's restart request helps only when the runtime did get installed. An install that
        // failed (a locked file, a full disk) is tried again from the archive kept for it, and a
        // restart would not change that.
        if (restart && runtimeInstalled(dir)) throw IllegalStateException("Встроенный браузер установлен: перезапустите OpenFlux и повторите")
        error?.let { throw IllegalStateException("Встроенный браузер не запустился: ${it.message ?: it::class.simpleName}") }
        if (restart) throw IllegalStateException("Встроенный браузер не установился: повторите, скачанное сохранено")
        withTimeoutOrNull(START_TIMEOUT_MS) { initialized.await() }
            ?: throw IllegalStateException("Встроенный браузер не запустился за минуту")
        val created = withTimeoutOrNull(START_TIMEOUT_MS) { KCEF.newClient() }
            ?: throw IllegalStateException("Встроенный браузер не ответил")
        val router = CefMessageRouter.create(CefMessageRouter.CefMessageRouterConfig(QUERY, "${QUERY}Cancel"))
        router.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(
                browser: CefBrowser?,
                frame: CefFrame?,
                queryId: Long,
                request: String?,
                persistent: Boolean,
                callback: CefQueryCallback?,
            ): Boolean {
                val text = request ?: return false
                if (text.startsWith("submit|")) {
                    // Only the script's own page, at its own address, may hand data back.
                    val page = setupPage(browser, frame?.url)
                    if (page == null) {
                        BrowserLog.problem("страница ${browser?.identifier}: данные от страницы, которая не страница настройки скрипта, отброшены")
                        callback?.failure(0, "not a setup page")
                        return true
                    }
                    val payload = text.removePrefix("submit|")
                    if (page.onSubmit != null) page.onSubmit.invoke(payload) else _submissions.tryEmit(payload)
                    callback?.success("")
                    return true
                }
                val deferred = pending.remove(text.substringBefore('|')) ?: return false
                deferred.complete(text.substringAfter('|'))
                callback?.success("")
                return true
            }
        }, true)
        created.addMessageRouter(router)
        watch(created)
        BrowserLog.info("браузер готов: ${runCatching { CefApp.getInstance().version?.toString()?.replace(Regex("\\s+"), " ") }.getOrNull() ?: "?"}")
        // Started from its own folder: the archive has done its job. Until here it is kept, so a
        // failed unpacking or start never costs another download.
        runtime.discard()
        return created
    }

    /** Page events for [BrowserLog]: a white page is a load error or a dead renderer. */
    private fun watch(client: KCEFClient) {
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, type: CefRequest.TransitionType?) {
                if (frame?.isMain == true) BrowserLog.info("страница ${browser?.identifier}: загружаю ${BrowserLog.short(frame.url)}")
            }

            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) BrowserLog.info("страница ${browser?.identifier}: загружена, HTTP $httpStatusCode, ${BrowserLog.short(frame.url)}")
                if (frame?.isMain == true && browser != null && isSetupPage(browser, frame.url)) {
                    browser.executeJavaScript(BRIDGE_JS, frame.url, 0)
                }
            }

            override fun onLoadError(browser: CefBrowser?, frame: CefFrame?, errorCode: CefLoadHandler.ErrorCode?, errorText: String?, failedUrl: String?) {
                BrowserLog.problem("страница ${browser?.identifier}: ошибка загрузки $errorCode $errorText, ${BrowserLog.short(failedUrl)}")
            }
        })
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onAddressChange(browser: CefBrowser?, frame: CefFrame?, url: String?) {
                if (frame?.isMain == true) BrowserLog.info("страница ${browser?.identifier}: адрес ${BrowserLog.short(url)}")
            }

            override fun onConsoleMessage(browser: CefBrowser?, level: CefSettings.LogSeverity?, message: String?, source: String?, line: Int): Boolean {
                if (level != null && level >= CefSettings.LogSeverity.LOGSEVERITY_WARNING) {
                    BrowserLog.problem("страница ${browser?.identifier}: консоль: ${message.orEmpty().take(300)} (${BrowserLog.short(source)}:$line)")
                }
                return false
            }
        })
        client.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onRenderProcessTerminated(browser: CefBrowser?, status: CefRequestHandler.TerminationStatus?) {
                BrowserLog.problem("страница ${browser?.identifier}: процесс отрисовки завершился: $status")
            }

            override fun onCertificateError(browser: CefBrowser?, cert_error: CefLoadHandler.ErrorCode?, request_url: String?, sslInfo: CefSSLInfo?, callback: CefCallback?): Boolean {
                BrowserLog.problem("страница ${browser?.identifier}: ошибка сертификата $cert_error, ${BrowserLog.short(request_url)}")
                return false
            }
        })
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(browser: CefBrowser?) {
                BrowserLog.info("страница ${browser?.identifier}: создана")
            }
        })
    }

    /**
     * Chromium's switches. Our own list, not JCEF's defaults (which pin the
     * scale factor to 1 and blur HiDPI screens). No GPU: a sign-in page does
     * not need it, and a bad driver would take the browser down.
     *
     * On macOS Chromium finds its framework and helper only through these
     * switches. KCEF puts them in CefApp.startup's args alone, so without them
     * here Chromium looks inside OpenFlux.app, finds nothing and crashes the
     * app in CefInitialize (EXC_BREAKPOINT).
     */
    internal fun switches(
        port: Int,
        dir: File,
        os: String = System.getProperty("os.name"),
    ): Array<String> = buildList {
        if (os.startsWith("Mac", ignoreCase = true)) {
            val frameworks = "${dir.canonicalPath}/Frameworks"
            add("--framework-dir-path=$frameworks/Chromium Embedded Framework.framework")
            add("--main-bundle-path=$frameworks/jcef Helper.app")
            add("--browser-subprocess-path=$frameworks/jcef Helper.app/Contents/MacOS/jcef Helper")
        }
        add("--disable-features=SpareRendererForSitePerProcess")
        add("--disable-gpu")
        add("--proxy-server=http://127.0.0.1:$port")
        add("--proxy-bypass-list=<-loopback>")
    }.toTypedArray()

    /** jcef_helper next to libcef; on Windows with its .exe, which KCEF leaves out. */
    private fun helper(dir: File): String? =
        listOf("jcef_helper.exe", "jcef_helper").map { File(dir, it) }.firstOrNull(File::isFile)?.absolutePath

    private const val START_TIMEOUT_MS = 60_000L
    const val YANDEX_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:153.0) Gecko/20100101 Firefox/153.0"

    /** Defines window.openfluxSubmit for a setup page, routed through the same query channel [evaluate] uses. */
    private val BRIDGE_JS = SetupPages.bridgeScript(
        "window.$QUERY({request:'submit|'+j,onSuccess:function(){},onFailure:function(){}})",
    )

    /**
     * The JetBrains Runtime build with JCEF that matches the JCEF classes
     * KCEF ships, pinned: "latest" from GitHub may not fit them, and the
     * JetBrains CDN needs no GitHub API.
     */
    internal fun packageUrl(
        os: String = System.getProperty("os.name"),
        arch: String = System.getProperty("os.arch"),
    ): String = "https://cache-redirector.jetbrains.com/intellij-jbr/${runtimeFile(os, arch)}"

    /** "windows-x64": the platform part of the runtime's file name. */
    internal fun runtimeKey(
        os: String = System.getProperty("os.name"),
        arch: String = System.getProperty("os.arch"),
    ): String {
        val platform = when {
            os.startsWith("Windows", ignoreCase = true) -> "windows"
            os.startsWith("Mac", ignoreCase = true) -> "osx"
            else -> "linux"
        }
        val cpu = if (arch.lowercase() in setOf("aarch64", "arm64")) "aarch64" else "x64"
        return "$platform-$cpu"
    }

    internal fun runtimeFile(
        os: String = System.getProperty("os.name"),
        arch: String = System.getProperty("os.arch"),
    ): String = "jbr_jcef-$JBR_VERSION-${runtimeKey(os, arch)}-$JBR_BUILD.tar.gz"

    /**
     * The same archive in the storage release of this app's repository (.github/workflows/browser-runtime.yml
     * there copies it from the JetBrains CDN, byte for byte, and signs it): GitHub is reachable and fast
     * where the CDN is throttled to about 1 KB/s.
     */
    internal fun mirrorUrl(
        os: String = System.getProperty("os.name"),
        arch: String = System.getProperty("os.arch"),
    ): String = "https://github.com/$MIRROR_REPO/releases/download/browser-runtime-$JBR_VERSION-$JBR_BUILD/${runtimeFile(os, arch)}"

    private const val MIRROR_REPO = "p1neappleXpress/OpenFluxDesktop"

    /**
     * Where the archive may be fetched from, in order of preference: the address in
     * OPENFLUX_BROWSER_RUNTIME_URL (a mirror a network can reach, or one of the user's own), the
     * storage release ([mirrorUrl]), then the JetBrains CDN. All serve the same file: it is checked
     * against [RUNTIME_SHA512], so where it comes from is only a matter of speed.
     */
    internal fun runtimeSources(env: String? = System.getenv("OPENFLUX_BROWSER_RUNTIME_URL")): List<String> =
        listOfNotNull(env?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }) + mirrorUrl() + packageUrl()

    /**
     * Whether KCEF can start from [dir] as it is: its install lock is only written once everything
     * was unpacked, and the runtime's own library has to be there as well.
     */
    internal fun runtimeInstalled(dir: File): Boolean =
        File(dir, "install.lock").isFile && listOf("libcef.dll", "libcef.so", "Frameworks").any { File(dir, it).exists() }

    /**
     * SHA-512 of each runtime archive of [JBR_VERSION] [JBR_BUILD], from the .checksum file JetBrains
     * publishes beside it. Whoever serves the archive, only these bytes are unpacked and run.
     */
    internal val RUNTIME_SHA512 = mapOf(
        "windows-x64" to "5367cbf1188d29d72574263c55e6e855ddce3d64c1908868d8c273183eaa339f6db0b98fb1b225ba5ac61e8f3365afa71fd0372dc4b72b37cd23dd778bb37940",
        "windows-aarch64" to "e889c269c5ab247c12293cd018619cfc31b3b8e664a5f2f71f3e8bed3140305eb7219d9596a514e798322cec838ec244b200fc4ac3d43f35f9a099b52822296a",
        "osx-x64" to "2e8d0cecd41e07a18162633c25592dfd8f32157591b09f487dd7357c1226fbc860d9519eaaf65d6140cc7bfb6bbf957139deac59f47c066684f3785105c2f104",
        "osx-aarch64" to "6ad87205a87e2921b7fa5389dbdd98a2f33a7013842b9e49c53465dca2bb434cae5f60b12d17edfb5e4f792fac78a8d31e222d611a9b74089e7845cbe977c135",
        "linux-x64" to "3c8c2c853acacbb096966175aae7967ea1280a7ab43c5e33a6cec17bd22fe0a243431811dcb66cdb6dd141c1144b13edf8b7b6bde1f7ba649749c0e4bd7b5cd8",
        "linux-aarch64" to "0a34abdd58786b14e4dc2ad56c87a58920337ca8909f5612b45b0cddd7868158beed63a44e8dee6d1ea0eac65ead56e7e788e0d39ba7599749588328feea5995",
    )

    private const val JBR_VERSION = "21.0.6"
    private const val JBR_BUILD = "b895.97"

    /** "a=1; b=2" from [cookies] (already those for one URL), the most specific domain winning, like a browser sends them. */
    fun cookieHeader(cookies: List<CefCookie>): String = selectForUrl(cookies.map { CookieValue(it.name, it.value, it.domain.orEmpty()) })
        .entries.joinToString("; ") { "${it.key}=${it.value}" }

    internal data class CookieValue(val name: String, val value: String, val domain: String)

    internal fun selectForUrl(cookies: List<CookieValue>): Map<String, String> {
        val selected = linkedMapOf<String, Pair<Int, String>>()
        for (c in cookies) {
            if (c.name.isEmpty() || (c.name + c.value).any { it == ';' || it == '\r' || it == '\n' }) continue
            val weight = c.domain.removePrefix(".").length
            if (weight >= (selected[c.name]?.first ?: -1)) selected[c.name] = weight to c.value
        }
        return selected.mapValues { it.value.second }
    }
}
