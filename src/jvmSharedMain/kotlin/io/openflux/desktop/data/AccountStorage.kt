package io.openflux.desktop.data

import io.openflux.desktop.model.AccountCookies
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AccountSession
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.SessionSpec
import io.openflux.desktop.model.YandexDisk
import io.openflux.desktop.service.AccountRepository
import io.openflux.desktop.service.ProbeResult
import io.openflux.desktop.service.SessionProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * accounts.json next to the profiles: the sessions the Accounts screen
 * keeps. On Android [dir] is noBackupFilesDir so a sign-in never goes to a
 * cloud backup.
 */
class FileAccountRepository(dir: File) : AccountRepository {
    private val store = JsonFile(File(dir, "accounts.json"), ListSerializer(AccountSession.serializer()))
    private val state = MutableStateFlow(store.read().orEmpty().associateBy { it.kind })
    override val sessions: StateFlow<Map<AccountKind, AccountSession>> = state.asStateFlow()

    @Synchronized
    override fun save(session: AccountSession) {
        val next = state.value + (session.kind to session)
        store.write(next.values.toList())
        state.value = next
    }

    @Synchronized
    override fun remove(kind: AccountKind) {
        val next = state.value - kind
        store.write(next.values.toList())
        state.value = next
    }
}

/**
 * Opens the service's own page with the saved cookies, directly (not
 * through the tunnel or the system proxy) and without following
 * redirects: a sign-in page means the session is gone.
 */
class HttpSessionProbe(private val urls: Map<AccountKind, String> = DEFAULT_URLS) : SessionProbe {
    override suspend fun probe(kind: AccountKind, cookies: Map<String, String>): ProbeResult = withContext(Dispatchers.IO) {
        val url = urls[kind] ?: return@withContext ProbeResult.Offline
        runCatching {
            val c = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", YandexDisk.USER_AGENT)
            c.setRequestProperty("Cookie", AccountCookies.header(cookies))
            try {
                val code = c.responseCode
                val location = c.getHeaderField("Location").orEmpty()
                val body = if (code in 200..299) c.inputStream.use { it.readBytes().decodeToString() } else ""
                classify(kind, code, location, body)
            } finally {
                c.disconnect()
            }
        }.getOrDefault(ProbeResult.Offline)
    }

    internal fun classify(kind: AccountKind, code: Int, location: String, body: String): ProbeResult = when {
        "showcaptcha" in location || "showcaptcha" in body -> ProbeResult.NeedsCheck
        code in 300..399 && SIGN_IN_HOSTS.any { it in location } -> ProbeResult.Expired
        kind == AccountKind.Yandex && code == 200 && "\"sk\":\"" in body -> ProbeResult.SignedIn
        kind == AccountKind.Yandex && code == 200 -> ProbeResult.Expired
        kind == AccountKind.Mailru && code == 200 -> ProbeResult.SignedIn
        else -> ProbeResult.Offline
    }

    companion object {
        val DEFAULT_URLS = mapOf(
            AccountKind.Yandex to "https://disk.yandex.ru/client/disk",
            // Mail answers a signed-out visitor with VK ID's sign-in; Cloud's
            // /home is a page for anyone and first bounces through auth.mail.ru.
            AccountKind.Mailru to "https://e.mail.ru/inbox/",
        )
        private val SIGN_IN_HOSTS = listOf("passport.yandex", "account.mail.ru", "e.mail.ru/login", "login.vk.com", "id.vk.ru", "id.vk.com")
    }
}

/** Writes the user's sign-ins into the core's cookie store before it starts (see transport/cookiestore.go). */
object CookieStoreSeeder {
    private val serializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))

    fun seed(
        file: File,
        profile: Profile,
        sessions: Map<AccountKind, AccountSession>,
        key: (SessionSpec) -> String = { it.value },
    ) {
        val existing = runCatching { StoreJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())
        val seeded = AccountCookies.seed(existing, profile, sessions, key)
        if (seeded == existing) return
        JsonFile(file, serializer).write(seeded)
    }
}
