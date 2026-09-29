package io.openflux.desktop.model

import kotlinx.serialization.Serializable

/**
 * An account of a service whose documents carry the tunnel. One per kind:
 * the user signs in once in the built-in browser and the app keeps the
 * session (cookies) to create documents and to open them signed in.
 */
@Serializable
enum class AccountKind(
    val label: String,
    val icon: String,
    val signInUrl: String,
    val homeUrl: String,
    val cookieDomain: String,
    val createsDocuments: Boolean,
    val transports: Set<TransportType>,
    /** Whether a sign-in can be told from its cookies; MAX keeps its token elsewhere. */
    val signsIn: Boolean = true,
    /**
     * Whether the sign-in counts only once the page is back at [homeUrl]:
     * Yandex sets its login cookie before the rest of the session.
     */
    val waitForHome: Boolean = true,
    /**
     * Where the sign-in is carried on to, and the cookie that shows it got
     * there: Mail.ru hands its session to Cloud only when Cloud is opened.
     */
    val finishUrl: String? = null,
    val finishCookie: String? = null,
    /**
     * Whether the core opens this service's documents as the signed-in
     * user. Mail.ru's transport opens its document anonymously, and Cloud
     * refuses that request (403) when it carries an account's cookies.
     */
    val opensSignedIn: Boolean = true,
) {
    Yandex(
        "Яндекс", "ic_yandex", YandexDisk.START_URL, YandexDisk.DISK_CLIENT, ".yandex.ru", true,
        setOf(TransportType.VYANDEX, TransportType.YANDEX, TransportType.BOARDS),
    ),
    Mailru(
        "Mail.ru", "ic_mailru", "https://account.mail.ru/login?page=https%3A%2F%2Fcloud.mail.ru%2Fhome%2F",
        "https://cloud.mail.ru/home", ".mail.ru", true, setOf(TransportType.MAILRU),
        // VK ID may leave the page on its own site (m.vk.ru) after signing in.
        waitForHome = false,
        finishUrl = "https://cloud.mail.ru/home/",
        finishCookie = "sdcs",
        opensSignedIn = false,
    ),
    Max("MAX", "ic_max", "https://web.max.ru/", "https://web.max.ru/", ".max.ru", false, setOf(TransportType.ONEME), signsIn = false);

    /** Pages whose cookies together make up the sign-in. */
    val cookieUrls: List<String>
        get() = when (this) {
            Yandex -> YandexDisk.ACCOUNT_URLS
            Mailru -> listOf("https://cloud.mail.ru/", "https://auth.mail.ru/", "https://account.mail.ru/", "https://mail.ru/", "https://e.mail.ru/")
            Max -> listOf(homeUrl)
        }

    companion object {
        fun of(type: TransportType): AccountKind? = entries.firstOrNull { type in it.transports }
    }
}

/** A saved sign-in. [cookies] never leave accounts.json, the core's cookie store and the user's own nodes. */
@Serializable
data class AccountSession(
    val kind: AccountKind,
    val login: String = "",
    val cookies: Map<String, String> = emptyMap(),
    val signedInAt: Long = 0,
    val checkedAt: Long = 0,
    val expired: Boolean = false,
) {
    override fun toString() = "AccountSession($kind, $login, ${cookies.size} cookies, expired=$expired)"
}

/** What the Accounts screen shows for a service. */
sealed interface AuthStatus {
    data object SignedOut : AuthStatus
    data class Checking(val login: String) : AuthStatus
    data class SignedIn(val login: String, val checkedAt: Long) : AuthStatus
    data class Expired(val login: String) : AuthStatus
    /** The service wants a person to pass a check before it answers; the session may still be fine. */
    data class NeedsCheck(val login: String) : AuthStatus
    /** Signing in or creating a document; [step] is the progress line. */
    data class Busy(val step: String) : AuthStatus
    data class Failed(val message: String, val login: String = "") : AuthStatus
}

object AccountCookies {
    /** Cookies that mean someone is signed in, per service. */
    private val LOGIN_COOKIE = mapOf(
        AccountKind.Yandex to "Session_id",
        AccountKind.Mailru to "Mpop",
        AccountKind.Max to "",
    )

    fun signedIn(kind: AccountKind, cookies: Map<String, String>): Boolean {
        val name = LOGIN_COOKIE[kind].orEmpty()
        return name.isNotEmpty() && !cookies[name].isNullOrEmpty()
    }

    /** The account's name as the service keeps it in a cookie, "" when unknown. */
    fun login(kind: AccountKind, cookies: Map<String, String>): String = when (kind) {
        AccountKind.Yandex -> cookies["yandex_login"].orEmpty()
        // Mpop is "<time>:<hash>:<email>:"; the email is the login.
        AccountKind.Mailru -> cookies["Mpop"].orEmpty().split(':').firstOrNull { '@' in it }.orEmpty()
        AccountKind.Max -> ""
    }

    /** "a=1; b=2" as a Cookie header sends them. */
    fun header(cookies: Map<String, String>): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** A Cookie header ("a=1; b=2") back into name -> value; the first of a repeated name wins. */
    fun parseHeader(header: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (part in header.split(';')) {
            val text = part.trim()
            val eq = text.indexOf('=')
            if (eq <= 0) continue
            val name = text.substring(0, eq).trim()
            if (name.isNotEmpty() && name !in out) out[name] = text.substring(eq + 1).trim()
        }
        return out
    }

    /**
     * The core's cookie store ({document URL: {name: value}}) with each
     * valid account merged into the jars of the profile's carriers of that
     * service: the account's cookies win, a passed check's stay. For a
     * service the core opens anonymously the account's cookies are taken
     * back out instead (earlier versions put them in). [key] is
     * how the core names a carrier's jar: the document URL on the desktop,
     * "type URL" in the Android library.
     */
    fun seed(
        store: Map<String, Map<String, String>>,
        profile: Profile,
        sessions: Map<AccountKind, AccountSession>,
        key: (SessionSpec) -> String = { it.value },
    ): Map<String, Map<String, String>> {
        val out = store.toMutableMap()
        for (spec in profile.sessionSpecs()) {
            val kind = AccountKind.of(spec.type) ?: continue
            val session = sessions[kind]?.takeUnless { it.expired || it.cookies.isEmpty() } ?: continue
            if (spec.value.isEmpty()) continue
            val name = key(spec)
            if (kind.opensSignedIn) {
                out[name] = out[name].orEmpty() + session.cookies
            } else {
                val kept = out[name]?.minus(session.cookies.keys) ?: continue
                if (kept.isEmpty()) out.remove(name) else out[name] = kept
            }
        }
        return out
    }
}
