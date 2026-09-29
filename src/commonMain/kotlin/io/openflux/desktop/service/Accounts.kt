package io.openflux.desktop.service

import io.openflux.desktop.model.AccountCookies
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AccountSession
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.model.MailruCloud
import io.openflux.desktop.model.NodeDocuments
import io.openflux.desktop.model.YandexDisk
import io.openflux.desktop.model.YandexDocument
import io.openflux.desktop.ui.BrowserPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The user's own accounts of the services the tunnel rides on: signing in
 * once in the built-in browser, creating documents as that user, and
 * knowing whether the saved session still works. The browser keeps no
 * cookies between uses; the session lives in [AccountRepository].
 */
class Accounts(
    private val repo: AccountRepository,
    private val browser: AccountBrowser,
    private val probe: SessionProbe,
    private val now: () -> Long,
    private val scope: CoroutineScope,
) {
    val sessions: StateFlow<Map<AccountKind, AccountSession>> = repo.sessions
    private val _status = MutableStateFlow(repo.sessions.value.mapValues { (_, s) -> fromSession(s) })
    val status: StateFlow<Map<AccountKind, AuthStatus>> = _status.asStateFlow()
    val page: StateFlow<BrowserPage?> = browser.page
    private val _signingIn = MutableStateFlow<AccountKind?>(null)

    /** The service whose page the sign-in dialog shows; null when none (or a screen shows it itself). */
    val signingIn: StateFlow<AccountKind?> = _signingIn.asStateFlow()

    fun statusOf(kind: AccountKind): AuthStatus = _status.value[kind] ?: AuthStatus.SignedOut

    /** A saved session that still works, null when there is none. */
    fun validSession(kind: AccountKind): AccountSession? =
        repo.sessions.value[kind]?.takeUnless { it.expired || it.cookies.isEmpty() }

    private fun set(kind: AccountKind, status: AuthStatus) = _status.update { it + (kind to status) }

    private fun fromSession(s: AccountSession): AuthStatus =
        if (s.expired) AuthStatus.Expired(s.login) else AuthStatus.SignedIn(s.login, s.checkedAt)

    private fun restore(kind: AccountKind) = set(kind, repo.sessions.value[kind]?.let(::fromSession) ?: AuthStatus.SignedOut)

    /**
     * Opens the service's sign-in page and waits until the user has signed
     * in. [inDialog] false: the caller shows [page] itself (the node wizard).
     */
    suspend fun signIn(kind: AccountKind, inDialog: Boolean = true): AccountSession {
        if (!kind.signsIn) throw AccountException("Вход в ${kind.label} пока не поддерживается: вставьте токен в профиль")
        set(kind, AuthStatus.Busy("Войдите в аккаунт ${kind.label} во встроенном браузере"))
        _signingIn.value = kind.takeIf { inDialog }
        try {
            browser.open(kind, kind.signInUrl, emptyMap()) { set(kind, AuthStatus.Busy(it)) }
            val deadline = now() + YandexDisk.SIGN_IN_TIMEOUT_MS
            var carriedOn = false
            while (true) {
                if (browser.closed) throw AccountException("Вход в ${kind.label} отменён", cancelled = true)
                if (now() > deadline) throw AccountException("Время на вход в ${kind.label} вышло")
                val jar = browser.cookies(kind)
                val finish = kind.finishUrl
                if (AccountCookies.signedIn(kind, jar) && finish != null && kind.finishCookie !in jar) {
                    // Signed in, but not yet everywhere the session is needed.
                    if (!carriedOn && !browser.loading) {
                        carriedOn = true
                        set(kind, AuthStatus.Busy("Вход выполнен, открываю ${kind.label}…"))
                        browser.load(finish)
                    }
                } else if (AccountCookies.signedIn(kind, jar) && (!kind.waitForHome || browser.url.startsWith(kind.homeUrl))) {
                    val t = now()
                    val session = AccountSession(kind, AccountCookies.login(kind, jar), jar, signedInAt = t, checkedAt = t)
                    repo.save(session)
                    set(kind, AuthStatus.SignedIn(session.login, t))
                    return session
                }
                delay(1000)
            }
        } catch (e: Throwable) {
            restore(kind)
            throw e
        } finally {
            _signingIn.value = null
            browser.close()
        }
    }

    /**
     * Creates a document editable by anyone with the link, as the saved
     * user (signing in first when there is no working session), and
     * returns its link.
     */
    suspend fun createDocument(kind: AccountKind, fileName: String, inDialog: Boolean = true): String {
        if (!kind.createsDocuments) {
            throw AccountException("${kind.label} пока не умеет создавать документы сам — вставьте ссылку вручную")
        }
        require(Regex("^[a-z0-9-]{1,64}$").matches(fileName)) { "Неверное имя документа" }
        val session = validSession(kind) ?: signIn(kind, inDialog)
        val maker = DocumentMaker.of(kind)
        set(kind, AuthStatus.Busy("Открываю ${maker.place}…"))
        _signingIn.value = kind.takeIf { inDialog }
        try {
            browser.open(kind, maker.startUrl, session.cookies) { set(kind, AuthStatus.Busy(it)) }
            val deadline = now() + DISK_TIMEOUT_MS
            while (true) {
                if (browser.closed) throw AccountException("Создание документа отменено", cancelled = true)
                if (now() > deadline) throw AccountException("${maker.place} не открылся за 2 минуты")
                val url = browser.url
                if (maker.signInPages.any { it in url }) {
                    markExpired(kind)
                    throw AccountException("Сессия ${kind.label} истекла — войдите заново", expired = true)
                }
                if (!browser.loading && url.startsWith(maker.readyUrl)) {
                    set(kind, AuthStatus.Busy("Создаю документ: ${maker.place}…"))
                    val raw = runCatching { browser.evaluate(maker.script(fileName)) }.getOrNull()
                    val result = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                    when (result?.get("state")?.jsonPrimitive?.content) {
                        "done" -> {
                            val doc = result["url"]?.jsonPrimitive?.content?.let(maker.clean)
                                ?: throw AccountException("${kind.label} вернул неожиданную ссылку на документ")
                            // The service may have refreshed the session while the page was open.
                            val fresh = runCatching { browser.cookies(kind) }.getOrDefault(emptyMap())
                                .takeIf { AccountCookies.signedIn(kind, it) } ?: session.cookies
                            val t = now()
                            repo.save(session.copy(cookies = fresh, checkedAt = t, expired = false))
                            set(kind, AuthStatus.SignedIn(session.login, t))
                            return doc
                        }
                        "fail" -> throw AccountException(
                            "Не получилось создать документ: " + (result["error"]?.jsonPrimitive?.content ?: "ошибка ${kind.label}"),
                        )
                        else -> Unit // the page is still loading its data
                    }
                }
                delay(1000)
            }
        } catch (e: Throwable) {
            if (!(e is AccountException && e.expired)) restore(kind)
            throw e
        } finally {
            _signingIn.value = null
            browser.close()
        }
    }

    /**
     * The node wizard's document: created with the Yandex account (signing
     * in first when needed) in the same sign-in window as the Accounts tab
     * (a steady-size dialog, full screen on a phone: inside the wizard's
     * scrolling step the page kept being resized and closed its own menus,
     * such as the one to get the code another way); [onStep] gets the
     * progress lines. The returned cookies are the account's.
     */
    suspend fun createWizardDocument(fileName: String, onStep: (String) -> Unit): YandexDocument = coroutineScope {
        val kind = AccountKind.Yandex
        val progress = launch { status.collect { (it[kind] as? AuthStatus.Busy)?.let { busy -> onStep(busy.step) } } }
        try {
            val url = createDocument(kind, fileName, inDialog = true)
            YandexDocument(url, AccountCookies.header(validSession(kind)?.cookies.orEmpty()))
        } finally {
            progress.cancel()
        }
    }

    /** Asks the service whether the saved session still works. */
    suspend fun check(kind: AccountKind): AuthStatus {
        val session = repo.sessions.value[kind] ?: return AuthStatus.SignedOut.also { set(kind, it) }
        set(kind, AuthStatus.Checking(session.login))
        val status = try {
            when (probe.probe(kind, session.cookies)) {
                ProbeResult.SignedIn -> {
                    val t = now()
                    repo.save(session.copy(checkedAt = t, expired = false))
                    AuthStatus.SignedIn(session.login, t)
                }
                ProbeResult.Expired -> {
                    repo.save(session.copy(expired = true))
                    AuthStatus.Expired(session.login)
                }
                ProbeResult.NeedsCheck -> AuthStatus.NeedsCheck(session.login)
                ProbeResult.Offline -> AuthStatus.Failed("Нет связи с ${kind.label}, проверю позже", session.login)
            }
        } catch (e: CancellationException) {
            restore(kind)
            throw e
        }
        set(kind, status)
        return status
    }

    /** Checks the sessions not checked for [RECHECK_MS]. */
    fun checkAllInBackground(force: Boolean = false) {
        val t = now()
        for ((kind, s) in repo.sessions.value) {
            if (kind != AccountKind.Max && !s.expired && (force || t - s.checkedAt > RECHECK_MS)) scope.launch { check(kind) }
        }
    }

    /** The core or a page found the session gone. */
    fun markExpired(kind: AccountKind) {
        val s = repo.sessions.value[kind] ?: return
        if (!s.expired) repo.save(s.copy(expired = true))
        set(kind, AuthStatus.Expired(s.login))
    }

    fun signOut(kind: AccountKind) {
        repo.remove(kind)
        set(kind, AuthStatus.SignedOut)
    }

    /** Closes the open page; the running sign-in or document then fails as cancelled. */
    fun cancel() = browser.close()

    companion object {
        const val RECHECK_MS = 30 * 60 * 1000L
        private const val DISK_TIMEOUT_MS = 2 * 60 * 1000L
    }
}

/**
 * How a service makes a document on its own page: which page to open,
 * when it is ready for [script], which pages mean the session is gone.
 */
internal class DocumentMaker(
    val place: String,
    val startUrl: String,
    val readyUrl: String,
    val signInPages: List<String>,
    val script: (String) -> String,
    val clean: (String) -> String?,
) {
    companion object {
        fun of(kind: AccountKind): DocumentMaker = when (kind) {
            AccountKind.Yandex -> DocumentMaker(
                "Яндекс Диск", YandexDisk.DISK_CLIENT, YandexDisk.DISK_CLIENT,
                listOf("passport.yandex"), YandexDisk::script, NodeDocuments::clean,
            )
            AccountKind.Mailru -> DocumentMaker(
                "Облако Mail", MailruCloud.HOME, MailruCloud.HOME,
                // Cloud comes back with autologin=no when Mail did not let the session in.
                listOf("account.mail.ru/login", "id.vk.ru", "login.vk.com", "autologin=no"), MailruCloud::script, MailruCloud::clean,
            )
            AccountKind.Max -> throw AccountException("MAX не создаёт документы")
        }
    }
}
