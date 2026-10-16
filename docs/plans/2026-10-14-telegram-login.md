# Вход через Telegram в приложениях: план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** В Desktop и Android единственный способ войти в аккаунт — Telegram (бот `@openflux_bot`, deep link + опрос), и после входа подключение появляется в списке само.

**Architecture:** `BotApi` получает `startLogin/pollLogin/sessions/createSession`; `HttpBotApi` говорит с `auth/app/start|poll` по ключу приложения `X-App-Key`. `BotAccountManager` после входа держит «подключение аккаунта»: берёт или создаёт сессию, меняет её `connect_token` на ссылку `openflux://` через уже существующий `connect/resolve` и ведёт обычный профиль `ProfileSource.Bot`. Автомат `TelegramLoginModel` ведёт вход; экран `TelegramLoginScreen` заменяет парольный `AuthScreen`.

**Tech Stack:** Kotlin Multiplatform (commonMain, jvmSharedMain, jvmMain), Compose Multiplatform 1.11, kotlinx.coroutines/serialization, `com.sun.net.httpserver` для HTTP-тестов, Gradle.

**Spec:** `docs/specs/2026-10-14-telegram-login-design.md` (читать вместе с ним), контракт сервера: `docs/specs/2026-10-14-telegram-login-backend-brief.md`.

## Global Constraints

- Работа ведётся в сабмодуле `OpenFluxDesktop/shared` (ветка `telegram-login`); пути ниже даны от корня репозитория shared. Тесты запускаются из `C:\Users\meepo\openflux\OpenFluxDesktop`: `./gradlew :shared:jvmTest --tests '<класс>'`.
- Коммиты: сообщение в стиле репозитория (`feat(login): …`, `test: …`, `refactor: …`), **без** строк `Co-Authored-By`, без упоминаний ИИ (правило владельца).
- Ничего не пушить, не выпускать и не публиковать без слова пользователя.
- Тексты для людей — по-русски, без слов об обходе блокировок, цензуры, DPI и белых списков.
- Ключи и токены не попадают в логи, исключения и `toString()` (образец: `BotSession.toString()`).
- Ключ приложения (`ofk_…`) не коммитится: только из свойства сборки `openflux.appKey` или переменной `OPENFLUX_APP_KEY`.
- Поля JSON сервера — grpc-gateway: camelCase, 64-битные числа строками (читать через `long()`), пустые поля опущены.
- Опрос `poll` — не чаще раза в 2 секунды; `nonce` живёт 5 минут и одноразовый.
- Существующие тесты не ломаются: старые методы (`BotApi.signIn`, парольные части) удаляются только в Task 6.
- Оставлено вне плана (решение в TASKS.md): удаление сервиса `opensub` на 141, управление уже выполненным входом в `opensub` (смена пароля, устройства, удаление) и подписки по ключам `/sub/…` остаются как есть; сквозной проход на эмуляторе Android — только против настоящего сервера (после выкатки владельцем).

## Review Focus

Что спек подразумевает, но не пишет, и что вероятнее всего сломается у человека (каждая строка закреплена тестом в указанной задаче):

1. Одноразовый токен: после `approved` повторный опрос не нужен и не делается; поздний `expired` не должен «разлогинить» (Task 3, `approvedStopsPolling`).
2. Человек ушёл в Telegram, сеть моргнула: опрос продолжается, а не падает в ошибку (Task 3, `networkHiccupsDoNotEndTheWait`).
3. Нет свободного слота / подписка кончилась: вход проходит, подключения нет, объясняем, не крутим создание сессий по кругу (Task 2, `noFreeSlotSaysSo`).
4. Первое подключение готовится несколько секунд (`creating`): профиль не появляется раньше времени и не помечается «закончилось» (Task 2, `aConnectionBeingMadeAppearsWhenReady`).
5. Бот заменил или остановил сессию: старый профиль уходит, новый встаёт, туннель через старый останавливается (Task 2, `aReplacedConnectionIsReplaced`).
6. Выход убирает подключение аккаунта, но не трогает ссылки, добавленные вручную (Task 2, `signOutKeepsManualLinks`).
7. Двойное нажатие «Войти» — один вход; отмена останавливает опрос (Task 3, `startTwiceIsOneLogin`, `cancelStops`).
8. Отключённый ключ приложения и сборка без ключа называются понятно, а не «нет сети» (Task 1, `aDisabledAppIsNamed`, `noKeyNoLogin`).
9. `nonce` и ссылка входа не попадают в `toString()` (Task 1, `secretsStayOutOfToString`).

---

## File Structure

| Файл | Что делает |
|---|---|
| `src/commonMain/.../desktop/model/OpenFluxBot.kt` | Модели: `LoginStart`, `LoginPoll`, `BotSessionItem`; поля `BotState.preparing/connectionNote`, `BotLink.account`; `BotException.forbidden` |
| `src/commonMain/.../desktop/service/BotAccount.kt` | `BotApi` (новые методы), `BotAccountManager` (`startLogin/pollLogin/completeLogin`, `ensureConnectionLocked`, выход) |
| `src/jvmSharedMain/.../desktop/data/HttpBotApi.kt` | HTTP: `auth/app/start|poll`, `sessions`, ключ приложения |
| `src/jvmSharedMain/.../desktop/data/AppKeys.kt` (новый) | Чтение и проверка ключа приложения и адреса API |
| `src/commonMain/.../app/account/TelegramLogin.kt` (новый) | `LoginUi`, `TelegramLoginModel`, `TelegramLoginContent`, `TelegramLoginScreen` |
| `src/commonMain/.../app/account/AccountScreens.kt`, `AccountModels.kt` | Точки входа, `BotSection`; в Task 6 — удаление парольного входа |
| `src/commonMain/.../app/shell/Entries.kt` | `Dest.Auth` → `TelegramLoginScreen` |
| `src/commonMain/.../app/home/HomeScreen.kt` | Строка входа и «готовим локацию» |
| тесты: `jvmTest/.../desktop/BotLoginApiTest.kt`, `BotConnectionTest.kt`, `AppKeysTest.kt`; `jvmTest/.../app/TelegramLoginModelTest.kt`, `TelegramLoginShotsTest.kt`; правка `TestContainer.kt` |
| `OpenFluxDesktop/desktopApp/build.gradle.kts`, `OpenFluxAndroid/androidApp/build.gradle.kts`, `OpenFluxAndroid/.../OpenFluxApplication.kt`, `.../AppFactory.kt` | Ключ приложения в сборку и в `HttpBotApi` |

---

### Task 1: Модели входа и HTTP-клиент

**Files:**
- Modify: `src/commonMain/kotlin/io/openflux/desktop/model/OpenFluxBot.kt`
- Modify: `src/commonMain/kotlin/io/openflux/desktop/service/BotAccount.kt` (интерфейс `BotApi`)
- Modify: `src/jvmSharedMain/kotlin/io/openflux/desktop/data/HttpBotApi.kt`
- Test: `src/jvmTest/kotlin/io/openflux/desktop/BotLoginApiTest.kt`

**Interfaces:**
- Produces:
  - `data class LoginStart(val nonce: String, val deepLink: String, val expiresAt: Long)` (`expiresAt` — unix-секунды)
  - `sealed interface LoginPoll { Pending; Approved(val session: BotSession); Denied; Expired }`
  - `data class BotSessionItem(val id: String, val status: String, val connectToken: String)` с `ready` (`status == "running" && connectToken.isNotEmpty()`)
  - `BotApi.startLogin(): LoginStart`, `BotApi.pollLogin(nonce: String): LoginPoll`, `BotApi.sessions(token: String): List<BotSessionItem>`, `BotApi.createSession(token: String): BotSessionItem` — в интерфейсе с реализацией по умолчанию «unsupported», чтобы чужие реализации в тестах не ломались
  - `BotException.forbidden`; коды ошибок `no_app_key`, `app_disabled`, `forbidden`
  - `HttpBotApi(base: String = OpenFluxBot.API, appKey: String = "")`

- [ ] **Step 1: Настроить рабочую ветку в сабмодуле**

```bash
cd /c/Users/meepo/openflux/OpenFluxDesktop/shared
git fetch local telegram-login
git checkout -b telegram-login FETCH_HEAD
git log --oneline -2
```
Expected: на ветке `telegram-login`, верхний коммит — `docs: Telegram login in the apps (spec) …` или новее.

- [ ] **Step 2: Написать падающий тест `BotLoginApiTest.kt`**

```kotlin
package io.openflux.desktop

import com.sun.net.httpserver.HttpServer
import io.openflux.desktop.data.HttpBotApi
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.LoginPoll
import io.openflux.desktop.model.LoginStart
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotLoginApiTest {
    private val servers = mutableListOf<HttpServer>()
    private class Seen(val path: String, val headers: Map<String, String>, val body: String) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
    private val seen = mutableListOf<Seen>()

    @AfterTest
    fun stop() = servers.forEach { it.stop(0) }

    /** A server answering each path with (status, json) from [answer]; records the requests. */
    private fun server(answer: (path: String, body: String) -> Pair<Int, String>): String {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += Seen(ex.requestURI.path, ex.requestHeaders.mapValues { it.value.first() }, body)
            val (code, json) = answer(ex.requestURI.path, body)
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        servers += s
        return "http://127.0.0.1:${s.address.port}"
    }

    private val key = "ofk_testtesttesttest"

    @Test
    fun startLoginSendsTheKeyAndReadsTheLink() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """{"nonce":"n123","deepLink":"https://t.me/openflux_bot?start=auth_n123","expiresAt":"1900000000"}""" }, key)
        val s = api.startLogin()
        assertEquals(LoginStart("n123", "https://t.me/openflux_bot?start=auth_n123", 1900000000), s)
        val r = seen.single()
        assertEquals("/api/v1/auth/app/start", r.path)
        assertEquals(key, r.header("X-App-Key"))
    }

    @Test
    fun aLinkThatIsNotTelegramsIsRefused() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """{"nonce":"n1","deepLink":"https://evil.example/x","expiresAt":"1"}""" }, key)
        assertEquals("bad_response", assertFailsWith<BotException> { api.startLogin() }.code)
    }

    @Test
    fun pollReadsEveryStatus() = runBlocking {
        val answers = ArrayDeque(
            listOf(
                """{"status":"pending"}""",
                """{"status":"approved","auth":{"token":"ofs_tok","userId":"7","username":"ivan","role":"user","expiresAt":"1900000000","sessionId":"s1"}}""",
                """{"status":"denied"}""",
                """{"status":"expired"}""",
                """{"status":"unknown"}""",
            ),
        )
        val api = HttpBotApi(server { _, _ -> 200 to answers.removeFirst() }, key)
        assertEquals(LoginPoll.Pending, api.pollLogin("n"))
        assertEquals(LoginPoll.Approved(BotSession("ofs_tok", 7, "ivan", 1900000000)), api.pollLogin("n"))
        assertEquals(LoginPoll.Denied, api.pollLogin("n"))
        assertEquals(LoginPoll.Expired, api.pollLogin("n"))
        assertEquals(LoginPoll.Expired, api.pollLogin("n"), "an unknown nonce is as good as an expired one")
        assertTrue(seen.all { it.path == "/api/v1/auth/app/poll" && it.header("X-App-Key") == key && """"nonce":"n"""" in it.body })
    }

    @Test
    fun anApprovedAnswerWithoutATokenIsBroken() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to """{"status":"approved","auth":{"userId":"7"}}""" }, key)
        assertEquals("bad_response", assertFailsWith<BotException> { api.pollLogin("n") }.code)
    }

    @Test
    fun aDisabledAppIsNamed() = runBlocking {
        for (code in listOf(401, 403)) {
            val api = HttpBotApi(server { _, _ -> code to """{"code":16,"message":"app disabled"}""" }, key)
            assertEquals("app_disabled", assertFailsWith<BotException> { api.startLogin() }.code, "start $code")
            assertEquals("app_disabled", assertFailsWith<BotException> { api.pollLogin("n") }.code, "poll $code")
        }
    }

    @Test
    fun noKeyNoLogin() = runBlocking {
        val api = HttpBotApi(server { _, _ -> 200 to "{}" }, "")
        assertEquals("no_app_key", assertFailsWith<BotException> { api.startLogin() }.code)
        assertEquals("no_app_key", assertFailsWith<BotException> { api.pollLogin("n") }.code)
        assertTrue(seen.isEmpty(), "nothing leaves the device without a key")
    }

    @Test
    fun sessionsAreReadFromABareArrayOrAnObject() = runBlocking {
        val bare = HttpBotApi(server { _, _ -> 200 to """[{"sessionId":"s1","status":"running","connectToken":"tok1","clientUrl":"https://x"},{"sessionId":"s2","status":"creating"}]""" }, key)
        assertEquals(listOf(BotSessionItem("s1", "running", "tok1"), BotSessionItem("s2", "creating", "")), bare.sessions("ofs_tok"))
        val wrapped = HttpBotApi(server { _, _ -> 200 to """{"sessions":[{"sessionId":"s3","status":"stopped"}],"total":1}""" }, key)
        assertEquals(listOf(BotSessionItem("s3", "stopped", "")), wrapped.sessions("ofs_tok"))
        val empty = HttpBotApi(server { _, _ -> 200 to "[]" }, key)
        assertTrue(empty.sessions("ofs_tok").isEmpty())
        assertEquals("Bearer ofs_tok", seen.first().header("Authorization"))
    }

    @Test
    fun aCreatedSessionIsReadAndARefusalIsForbidden() = runBlocking {
        val ok = HttpBotApi(server { _, _ -> 200 to """{"session":{"sessionId":"s9","status":"creating"}}""" }, key)
        assertEquals(BotSessionItem("s9", "creating", ""), ok.createSession("ofs_tok"))
        val no = HttpBotApi(server { _, _ -> 403 to """{"code":7,"message":"no free slot for an automatic connection"}""" }, key)
        assertTrue(assertFailsWith<BotException> { no.createSession("ofs_tok") }.forbidden)
    }

    @Test
    fun secretsStayOutOfToString() {
        val s = LoginStart("secretnonce", "https://t.me/openflux_bot?start=auth_secretnonce", 5)
        assertFalse("secretnonce" in s.toString())
    }
}
```

- [ ] **Step 3: Запустить тест, убедиться, что не компилируется**

Run: `cd /c/Users/meepo/openflux/OpenFluxDesktop && ./gradlew :shared:jvmTest --tests 'io.openflux.desktop.BotLoginApiTest' 2>&1 | tail -25`
Expected: FAIL — `Unresolved reference: LoginStart` (и остальные новые типы).

- [ ] **Step 4: Модели в `OpenFluxBot.kt`**

В конец файла (после `BotException`) заменить `BotException` и добавить модели:

```kotlin
/** A failed bot API call; [code] is "network", "unauthorized", "forbidden", "not_found" or "http_<n>", and for a sign-in "no_app_key" or "app_disabled". */
class BotException(val code: String, message: String) : Exception(message) {
    val unauthorized: Boolean get() = code == "unauthorized"
    val forbidden: Boolean get() = code == "forbidden"
    val notFound: Boolean get() = code == "not_found"
}

/** A Telegram sign-in the app began: [nonce] is its secret, [deepLink] opens the bot with it; [expiresAt] is unix seconds. */
data class LoginStart(val nonce: String, val deepLink: String, val expiresAt: Long) {
    override fun toString() = "LoginStart(expiresAt=$expiresAt)"
}

/** The bot's answer to "did the person approve?". [Approved] carries the session once. */
sealed interface LoginPoll {
    data object Pending : LoginPoll
    data class Approved(val session: BotSession) : LoginPoll
    data object Denied : LoginPoll
    data object Expired : LoginPoll
}

/** One of the account's connections as GET /api/v1/sessions reports it: [connectToken] resolves to its `openflux://` link. */
data class BotSessionItem(val id: String, val status: String, val connectToken: String) {
    val ready: Boolean get() = status == "running" && connectToken.isNotEmpty()
    override fun toString() = "BotSessionItem($id, $status)"
}
```

- [ ] **Step 5: Интерфейс `BotApi` в `BotAccount.kt`**

Внутри `interface BotApi` после `resolve` добавить:

```kotlin
    /** Begins a Telegram sign-in: the link that opens the bot, and the nonce to ask about. */
    suspend fun startLogin(): LoginStart = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")
    suspend fun pollLogin(nonce: String): LoginPoll = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")
    /** The account's connections. */
    suspend fun sessions(token: String): List<BotSessionItem> = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")
    /** Makes a connection in a free automatic slot; "forbidden" when there is none. */
    suspend fun createSession(token: String): BotSessionItem = throw BotException("unsupported", "Бот OpenFlux здесь не поддерживается")
```
и импорты `io.openflux.desktop.model.BotSessionItem`, `LoginPoll`, `LoginStart`.

- [ ] **Step 6: HTTP-клиент в `HttpBotApi.kt`**

Конструктор и новые методы (импорты: `JsonArray`, `LoginPoll`, `LoginStart`, `BotSessionItem`):

```kotlin
class HttpBotApi(private val base: String = OpenFluxBot.API, private val appKey: String = "") : BotApi {
```

после `resolve(...)` добавить:

```kotlin
    override suspend fun startLogin(): LoginStart {
        val o = appRequest("/api/v1/auth/app/start", JsonObject(emptyMap())).jsonObject
        val nonce = o.str("nonce")
        val link = o.str("deepLink")
        if (nonce.isEmpty() || !link.startsWith("https://t.me/")) throw BotException("bad_response", "Бот ответил непонятно: обновите приложение")
        return LoginStart(nonce, link, o.long("expiresAt"))
    }

    override suspend fun pollLogin(nonce: String): LoginPoll {
        val o = appRequest("/api/v1/auth/app/poll", JsonObject(mapOf("nonce" to JsonPrimitive(nonce)))).jsonObject
        return when (o.str("status")) {
            "pending" -> LoginPoll.Pending
            "denied" -> LoginPoll.Denied
            "expired", "unknown" -> LoginPoll.Expired
            "approved" -> {
                val auth = o["auth"] as? JsonObject
                val token = auth?.str("token").orEmpty()
                if (auth == null || token.isEmpty()) throw BotException("bad_response", "Бот не выдал сессию: обновите приложение")
                LoginPoll.Approved(BotSession(token, auth.long("userId"), auth.str("username"), auth.long("expiresAt")))
            }
            else -> throw BotException("bad_response", "Бот ответил непонятно: обновите приложение")
        }
    }

    override suspend fun sessions(token: String): List<BotSessionItem> {
        val e = request("GET", "/api/v1/sessions", token, null)
        val array = e as? JsonArray ?: (e as? JsonObject)?.get("sessions") as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(::sessionItem) }
    }

    override suspend fun createSession(token: String): BotSessionItem {
        val o = request("POST", "/api/v1/sessions", token, JsonObject(emptyMap())).jsonObject
        return sessionItem((o["session"] as? JsonObject) ?: o)
    }

    private fun sessionItem(o: JsonObject) = BotSessionItem(o.str("sessionId"), o.str("status"), o.str("connectToken"))

    /** A sign-in call: the app's key goes in X-App-Key; a key the service refuses means this build may no longer sign in. */
    private suspend fun appRequest(path: String, body: JsonObject): JsonElement {
        if (appKey.isEmpty()) throw BotException("no_app_key", "Вход через Telegram в этой сборке выключен: в ней нет ключа приложения")
        return try {
            request("POST", path, null, body, mapOf("X-App-Key" to appKey))
        } catch (e: BotException) {
            if (e.unauthorized || e.forbidden) throw BotException("app_disabled", "Эта версия приложения больше не может войти. Обновите приложение")
            throw e
        }
    }
```

В `request(...)` в блоке `when` добавить ветку перед `code == 404`:

```kotlin
                    code == 403 -> throw BotException("forbidden", "Нет доступа: подписка не действует или нет свободного места")
```

- [ ] **Step 7: Запустить тесты — проходят**

Run: `cd /c/Users/meepo/openflux/OpenFluxDesktop && ./gradlew :shared:jvmTest --tests 'io.openflux.desktop.BotLoginApiTest' --tests 'io.openflux.desktop.BotAccountTest' 2>&1 | tail -25`
Expected: BUILD SUCCESSFUL, все тесты `BotLoginApiTest` (9) и прежние `BotAccountTest` зелёные.

- [ ] **Step 8: Коммит**

```bash
cd /c/Users/meepo/openflux/OpenFluxDesktop/shared
git add src/commonMain src/jvmSharedMain src/jvmTest
git commit -m "feat(login): bot API start/poll sign-in and the account's sessions, with the app key"
```

---

### Task 2: Подключение аккаунта в `BotAccountManager`

**Files:**
- Modify: `src/commonMain/kotlin/io/openflux/desktop/model/OpenFluxBot.kt` (`BotLink`, `BotState`)
- Modify: `src/commonMain/kotlin/io/openflux/desktop/service/BotAccount.kt` (`BotAccountManager`)
- Test: `src/jvmTest/kotlin/io/openflux/desktop/BotConnectionTest.kt`

**Interfaces:**
- Consumes: `BotApi.sessions/createSession/resolve/subscription`, `BotSessionItem`, `BotException.forbidden/notFound/unauthorized` (Task 1).
- Produces:
  - `BotLink.account: Boolean = false`; `BotState.preparing: Boolean = false`, `BotState.connectionNote: String? = null`
  - `BotAccountManager.startLogin(): LoginStart`, `pollLogin(nonce: String): LoginPoll`, `completeLogin(session: BotSession)`
  - `BotAccountManager.NO_SLOT: String`
  - `signOut()` убирает связи аккаунта; `refresh()` ведёт подключение аккаунта (`ensureConnectionLocked`)

- [ ] **Step 1: Написать падающий тест `BotConnectionTest.kt`**

```kotlin
package io.openflux.desktop

import io.openflux.app.testContainer
import io.openflux.desktop.service.BotConnection
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareTransport
import io.openflux.desktop.service.BotAccountManager
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.InMemoryBotRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotConnectionTest {
    private val session = BotSession("ofs_x", 7, "ivan", 1_900_000_000)

    private class Bot : BotApi {
        var sessions = mutableListOf<BotSessionItem>()
        var create: () -> BotSessionItem = { throw BotException("forbidden", "Нет доступа") }
        var created = 0
        val links = mutableMapOf<String, BotConnection>()
        var sessionsError: BotException? = null
        var signedOut = 0
        override suspend fun subscription(token: String) = BotSubscription(planId = "own30", planName = "Start", status = "active", isActive = true)
        override suspend fun signOut(token: String) { signedOut++ }
        override suspend fun resolve(connectToken: String) = links[connectToken] ?: throw BotException("not_found", "Не найдено")
        override suspend fun sessions(token: String): List<BotSessionItem> = sessionsError?.let { throw it } ?: sessions.toList()
        override suspend fun createSession(token: String): BotSessionItem { created++; return create().also { sessions += it } }
    }

    private suspend fun link(name: String, key: String) = FakeShareLinkCodec().encode(
        ShareConfig(
            name = name, negotiate = true, secret = key, context = "https://example.com/doc",
            transports = listOf(ShareTransport(type = "mailru", url = "https://cloud.mail.ru/public/aaaa/bbbb", priority = 90), ShareTransport(type = "direct", dial = "203.0.113.5:443", priority = 50)),
        ),
    )

    private fun env(api: Bot = Bot()) = run {
        val c = testContainer()
        val accounts = testAccounts(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        Triple(BotAccountManager(api, InMemoryBotRepository(), c.profiles, c.shareCodec, c.platform, c.connection, accounts), c, api)
    }

    private val tokenA = "AAAAAAAAAAAAAAAAAAAAAA"
    private val tokenB = "BBBBBBBBBBBBBBBBBBBBBB"

    @Test
    fun signingInBringsTheRunningConnectionAsAProfile() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("OpenFlux #4", link("x", "ab".repeat(32)))
        m.completeLogin(session)
        val p = c.profiles.profiles.value.single()
        assertEquals(ProfileSource.Bot, p.source)
        assertEquals("OpenFlux #4", p.name)
        val st = m.state.value
        assertEquals(session, st.session)
        assertTrue(st.links.single().account)
        assertFalse(st.preparing)
        assertEquals(0, api.created, "a running connection is used, not doubled")
    }

    @Test
    fun withoutAConnectionOneIsMade() = runTest {
        val (m, c, api) = env()
        api.create = { BotSessionItem("s1", "running", tokenA).also { api.links[tokenA] = BotConnection("OpenFlux #5", runBlockingLink()) } }
        m.completeLogin(session)
        assertEquals(1, api.created)
        assertEquals("OpenFlux #5", c.profiles.profiles.value.single().name)
    }

    private fun runBlockingLink() = kotlinx.coroutines.runBlocking { link("x", "cd".repeat(32)) }

    @Test
    fun aConnectionBeingMadeAppearsWhenReady() = runTest {
        val (m, c, api) = env()
        api.create = { BotSessionItem("s1", "creating", "") }
        m.completeLogin(session)
        assertTrue(m.state.value.preparing)
        assertTrue(c.profiles.profiles.value.isEmpty(), "no profile before the link exists")
        assertTrue(m.state.value.links.none { it.gone })

        // Still being made: no second connection is asked for.
        m.refresh()
        assertEquals(1, api.created)
        assertTrue(m.state.value.preparing)

        api.sessions[0] = BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("OpenFlux #6", link("x", "ef".repeat(32)))
        m.refresh()
        assertFalse(m.state.value.preparing)
        assertEquals("OpenFlux #6", c.profiles.profiles.value.single().name)
        assertEquals(1, api.created)
    }

    @Test
    fun aLinkThatIsNotResolvableYetKeepsWaiting() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA) // no link in api.links yet
        m.completeLogin(session)
        assertTrue(m.state.value.preparing)
        assertNull(m.state.value.lastError)
        assertTrue(c.profiles.profiles.value.isEmpty())
    }

    @Test
    fun noFreeSlotSaysSo() = runTest {
        val (m, c, api) = env()
        m.completeLogin(session)
        assertEquals(BotAccountManager.NO_SLOT, m.state.value.connectionNote)
        assertFalse(m.state.value.preparing)
        assertNull(m.state.value.lastError)
        assertEquals(session, m.state.value.session, "still signed in")
        assertTrue(c.profiles.profiles.value.isEmpty())
        // The subscription is renewed in the bot: the next refresh makes the connection and the note goes.
        api.create = { BotSessionItem("s1", "running", tokenA).also { api.links[tokenA] = BotConnection("OpenFlux #7", runBlockingLink()) } }
        m.refresh()
        assertNull(m.state.value.connectionNote)
        assertEquals(1, c.profiles.profiles.value.size)
    }

    @Test
    fun aReplacedConnectionIsReplaced() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("OpenFlux #4", link("x", "ab".repeat(32)))
        m.completeLogin(session)
        val old = c.profiles.profiles.value.single()
        c.connection.connect(old, ConnectionMode.Client)

        // The bot stopped it and made another.
        api.links.remove(tokenA)
        api.sessions.clear()
        api.sessions += BotSessionItem("s2", "running", tokenB)
        api.links[tokenB] = BotConnection("OpenFlux #8", link("x", "cd".repeat(32)))
        m.refresh()

        val p = c.profiles.profiles.value.single()
        assertEquals("OpenFlux #8", p.name)
        assertEquals("cd".repeat(32), p.secret)
        assertFalse(c.connection.state.value is ConnectionState.Connected, "the tunnel through the old one stops")
        assertEquals(tokenB, m.state.value.links.single().token)
    }

    @Test
    fun signOutKeepsManualLinks() = runTest {
        val (m, c, api) = env()
        api.links[tokenB] = BotConnection("Ручная", link("x", "11".repeat(32)))
        m.addLink("https://link.openflux.dev/$tokenB")
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("Аккаунт", link("x", "22".repeat(32)))
        m.completeLogin(session)
        assertEquals(2, c.profiles.profiles.value.size)

        m.signOut()
        assertNull(m.state.value.session)
        assertEquals(listOf("Ручная"), c.profiles.profiles.value.map { it.name })
        assertEquals(listOf(tokenB), m.state.value.links.map { it.token })
        assertEquals(1, api.signedOut)
    }

    @Test
    fun anExpiredSessionEndsTheSignInButKeepsTheProfile() = runTest {
        val (m, c, api) = env()
        api.sessions += BotSessionItem("s1", "running", tokenA)
        api.links[tokenA] = BotConnection("Аккаунт", link("x", "22".repeat(32)))
        m.completeLogin(session)
        api.sessionsError = BotException("unauthorized", "Войдите через Telegram заново")
        m.refresh()
        assertNull(m.state.value.session)
        assertEquals(1, c.profiles.profiles.value.size, "the connection keeps working until the bot ends it")
    }

    @Test
    fun loginCallsGoToTheApi() = runTest {
        val api = object : BotApi by Bot() {
            override suspend fun startLogin() = io.openflux.desktop.model.LoginStart("n", "https://t.me/openflux_bot?start=auth_n", 5)
            override suspend fun pollLogin(nonce: String) = io.openflux.desktop.model.LoginPoll.Pending
        }
        val c = testContainer()
        val m = BotAccountManager(api, InMemoryBotRepository(), c.profiles, c.shareCodec, c.platform, c.connection, testAccounts(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
        assertEquals("n", m.startLogin().nonce)
        assertEquals(io.openflux.desktop.model.LoginPoll.Pending, m.pollLogin("n"))
    }
}
```

- [ ] **Step 2: Запустить — не компилируется**

Run: `cd /c/Users/meepo/openflux/OpenFluxDesktop && ./gradlew :shared:jvmTest --tests 'io.openflux.desktop.BotConnectionTest' 2>&1 | tail -25`
Expected: FAIL — `Unresolved reference: completeLogin`, `account`, `preparing`, `NO_SLOT`.

- [ ] **Step 3: Поля состояния в `OpenFluxBot.kt`**

В `BotLink` добавить последним полем (с комментарием):

```kotlin
    /** The connection of the signed-in account (made or found by the app), not one added by hand: sign-out takes it away. */
    val account: Boolean = false,
```

В `BotState` добавить:

```kotlin
    /** The account's connection is being made (or its link is not ready yet): the app asks again soon. */
    val preparing: Boolean = false,
    /** Why the signed-in account has no connection (no free slot, subscription ended), in words for the person. */
    val connectionNote: String? = null,
```

- [ ] **Step 4: Менеджер в `BotAccount.kt`**

(a) Заменить `signOut()` и добавить вход:

```kotlin
    // ---- signing in ----

    suspend fun startLogin(): LoginStart = api.startLogin()

    suspend fun pollLogin(nonce: String): LoginPoll = api.pollLogin(nonce)

    /** Keeps the session a finished Telegram sign-in gave, then brings the account's subscription and connection in. */
    suspend fun completeLogin(session: BotSession) {
        lock.withLock { repo.save(repo.state.value.copy(session = session, lastError = null, connectionNote = null)) }
        refresh()
    }

    /** Signs out of this device: the account's connection goes with it, links added by hand stay. */
    suspend fun signOut() {
        val s = repo.state.value.session ?: return
        runCatching { api.signOut(s.token) }
        lock.withLock {
            val mine = repo.state.value.links.filter { it.account }
            stopIfRunning(mine.map { it.profileId }.toSet())
            mine.forEach { profiles.delete(it.profileId) }
            repo.save(repo.state.value.copy(session = null, subscription = null, links = repo.state.value.links.filterNot { it.account }, preparing = false, connectionNote = null))
        }
    }
```
(Старый `signIn()` пока остаётся.)

(b) Разделить `addLink`/`removeLink` на блокирующую и «Locked» части:

```kotlin
    suspend fun addLink(text: String): Profile {
        val token = OpenFluxBot.connectToken(text) ?: throw BotException("bad_link", "Это не ссылка бота OpenFlux")
        return lock.withLock { addLinkLocked(token, account = false) }
    }

    private suspend fun addLinkLocked(token: String, account: Boolean): Profile {
        val conn = try {
            api.resolve(token)
        } catch (e: BotException) {
            if (e.notFound) throw BotException("not_found", GONE) else throw e
        }
        val known = repo.state.value.links.firstOrNull { it.token == token }
        val p = profileOf(conn, known?.profileId ?: profiles.newId(), known)
        profiles.upsert(p)
        val link = BotLink(token, p.id, conn.name, platform.now(), account = account || known?.account == true)
        saveLinks(repo.state.value.links.filterNot { it.token == token } + link)
        return p
    }

    /** Forgets a connection: its profile goes too. */
    suspend fun removeLink(token: String) = lock.withLock { removeLinkLocked(token) }

    private fun removeLinkLocked(token: String) {
        val link = repo.state.value.links.firstOrNull { it.token == token } ?: return
        stopIfRunning(setOf(link.profileId))
        profiles.delete(link.profileId)
        saveLinks(repo.state.value.links.filterNot { it.token == token })
    }
```
(`removeLink` раньше был `suspend` с `lock.withLock` — сигнатура сохраняется.)

(c) В конце `refreshLocked()` после `repo.save(s.copy(lastError = error))` добавить `ensureConnectionLocked()`, и добавить функции:

```kotlin
    /** The signed-in account's connection: the running one of the bot, or a new one in a free slot. */
    private suspend fun ensureConnectionLocked() {
        val session = repo.state.value.session ?: return
        // A connection the bot ended or replaced goes; below asks for the current one.
        val ended = repo.state.value.links.filter { it.account && it.gone }
        for (l in ended) removeLinkLocked(l.token)

        val items = try { api.sessions(session.token) } catch (e: CancellationException) { throw e } catch (e: BotException) { return ensureFailed(e) }
        val ready = items.firstOrNull { it.ready }
        val item = when {
            ready != null -> ready
            items.any { it.status == "creating" } -> null
            else -> try { api.createSession(session.token) } catch (e: CancellationException) { throw e } catch (e: BotException) { return ensureFailed(e) }
        }
        if (item == null || !item.ready) {
            repo.save(repo.state.value.copy(preparing = true, connectionNote = null))
            return
        }
        val current = repo.state.value.links.firstOrNull { it.account }
        if (current != null && current.token != item.connectToken) removeLinkLocked(current.token)
        if (repo.state.value.links.none { it.token == item.connectToken }) {
            try { addLinkLocked(item.connectToken, account = true) } catch (e: CancellationException) { throw e } catch (e: BotException) { return ensureFailed(e) }
        }
        repo.save(repo.state.value.copy(preparing = false, connectionNote = null))
    }

    private fun ensureFailed(e: BotException) {
        val s = repo.state.value
        repo.save(
            when {
                e.unauthorized -> s.copy(session = null, subscription = null, preparing = false)
                e.forbidden -> s.copy(preparing = false, connectionNote = NO_SLOT)
                e.notFound -> s.copy(preparing = true, connectionNote = null) // the link is not ready yet
                else -> s.copy(preparing = false, lastError = e.message)
            },
        )
    }
```

(d) В `start(scope)` заменить задержку:

```kotlin
                delay(if (repo.state.value.preparing) PREPARE_MS else REFRESH_MS)
```
и в `companion object` добавить:

```kotlin
        const val NO_SLOT = "Подключения нет: подписка не действует или все места заняты. Продлите подписку в @${OpenFluxBot.USERNAME}"
        private const val PREPARE_MS = 15 * 1000L
```

Импорты: `LoginPoll`, `LoginStart`.

- [ ] **Step 5: Запустить — проходит**

Run: `cd /c/Users/meepo/openflux/OpenFluxDesktop && ./gradlew :shared:jvmTest --tests 'io.openflux.desktop.BotConnectionTest' --tests 'io.openflux.desktop.BotAccountTest' 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL; все 9 тестов `BotConnectionTest` и прежние `BotAccountTest` зелёные. Если `aLinkThatIsNotResolvableYetKeepsWaiting` падает на `lastError`, значит `notFound` попал в `else`: проверь порядок веток `ensureFailed`.

- [ ] **Step 6: Коммит**

```bash
cd /c/Users/meepo/openflux/OpenFluxDesktop/shared
git add src
git commit -m "feat(login): the signed-in account's connection becomes a profile by itself"
```

---

### Task 3: Автомат входа `TelegramLoginModel`

**Files:**
- Create: `src/commonMain/kotlin/io/openflux/app/account/TelegramLogin.kt` (модель, экран — в Task 4)
- Modify: `src/jvmTest/kotlin/io/openflux/app/TestContainer.kt` (параметр `bot`, запись `openUrl`)
- Test: `src/jvmTest/kotlin/io/openflux/app/TelegramLoginModelTest.kt`

**Interfaces:**
- Consumes: `BotAccountManager.startLogin/pollLogin/completeLogin`, `LoginPoll`, `LoginStart` (Task 1–2), `PlatformServices.openUrl/now`.
- Produces:
  - `sealed interface LoginUi { Idle; Starting; Waiting(hint: String?); Preparing; Denied; Expired; Failed(message: String); Done }`
  - `class TelegramLoginModel(container: AppContainer, scope: CoroutineScope, pollMs: Long = 2000)` с `ui`, `start()`, `cancel()`, `reopen()`, `pollNow()`
  - `testContainer(bot: BotApi = NoBotApi, …)`, `TestPlatform.opened: MutableList<String>`

- [ ] **Step 1: Правки `TestContainer.kt`**

В `testContainer(...)` добавить параметр и передачу:

```kotlin
    bot: io.openflux.desktop.service.BotApi = io.openflux.desktop.service.NoBotApi,
```
и в `AppContainer(...)` — `botApi = bot,`. В `TestPlatform` заменить `override fun openUrl(url: String) = Unit` на:

```kotlin
    /** The addresses opened for the person, in order. */
    val opened = mutableListOf<String>()
    override fun openUrl(url: String) { opened += url }
```

- [ ] **Step 2: Падающий тест `TelegramLoginModelTest.kt`**

```kotlin
package io.openflux.app

import io.openflux.app.account.LoginUi
import io.openflux.app.account.TelegramLoginModel
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.LoginPoll
import io.openflux.desktop.model.LoginStart
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramLoginModelTest {
    private val link = "https://t.me/openflux_bot?start=auth_n1"
    private val approved = LoginPoll.Approved(BotSession("ofs_a", 7, "ivan", 1_900_000_000))

    /** Polls answer from [script] in order: a [LoginPoll] is returned, a [BotException] thrown. */
    private class Scripted(val script: ArrayDeque<Any>, val startError: BotException? = null) : BotApi {
        var starts = 0
        override suspend fun startLogin(): LoginStart { starts++; startError?.let { throw it }; return LoginStart("n1", "https://t.me/openflux_bot?start=auth_n1", 2_000) }
        override suspend fun pollLogin(nonce: String): LoginPoll = when (val x = script.removeFirst()) {
            is LoginPoll -> x
            is BotException -> throw x
            else -> error("bad script")
        }
        override suspend fun subscription(token: String) = BotSubscription()
        override suspend fun signOut(token: String) = Unit
        override suspend fun resolve(connectToken: String): BotConnection = throw BotException("not_found", "Не найдено")
        override suspend fun sessions(token: String) = emptyList<BotSessionItem>()
        override suspend fun createSession(token: String): BotSessionItem = throw BotException("forbidden", "Нет доступа")
    }

    private fun world(vararg script: Any, startError: BotException? = null, platform: TestPlatform = TestPlatform()): Triple<io.openflux.desktop.service.AppContainer, Scripted, TestPlatform> {
        val api = Scripted(ArrayDeque(script.toList()), startError)
        return Triple(testContainer(bot = api, platform = platform), api, platform)
    }

    @Test
    fun approvedAfterPendingPollsSignsIn() = runTest {
        val (c, api, platform) = world(LoginPoll.Pending, LoginPoll.Pending, approved)
        val m = TelegramLoginModel(c, this)
        m.start()
        runCurrent()
        assertIs<LoginUi.Waiting>(m.ui)
        assertEquals(listOf(link), platform.opened, "Telegram is opened with the link")
        advanceUntilIdle()
        assertEquals(LoginUi.Done, m.ui)
        assertEquals("ofs_a", c.bot.state.value.session?.token)
        assertTrue(api.script.isEmpty())
    }

    @Test
    fun approvedStopsPolling() = runTest {
        val (c, api, _) = world(approved, LoginPoll.Expired) // a late "expired" must never be asked for
        val m = TelegramLoginModel(c, this)
        m.start()
        advanceUntilIdle()
        assertEquals(LoginUi.Done, m.ui)
        assertEquals(1, api.script.size, "the second answer was never asked for")
        assertEquals("ofs_a", c.bot.state.value.session?.token)
    }

    @Test
    fun deniedAndExpiredStop() = runTest {
        val (c1, a1, _) = world(LoginPoll.Denied)
        val m1 = TelegramLoginModel(c1, this)
        m1.start(); advanceUntilIdle()
        assertEquals(LoginUi.Denied, m1.ui)
        assertNull(c1.bot.state.value.session)
        assertTrue(a1.script.isEmpty())

        val (c2, _, _) = world(LoginPoll.Pending, LoginPoll.Expired)
        val m2 = TelegramLoginModel(c2, this)
        m2.start(); advanceUntilIdle()
        assertEquals(LoginUi.Expired, m2.ui)
    }

    @Test
    fun networkHiccupsDoNotEndTheWait() = runTest {
        val (c, api, _) = world(BotException("network", "Бот OpenFlux не отвечает. Проверьте сеть."), BotException("rate_limited", "Слишком много запросов"), LoginPoll.Pending, approved)
        val m = TelegramLoginModel(c, this)
        m.start()
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        val hint = assertIs<LoginUi.Waiting>(m.ui).hint
        assertEquals("Бот OpenFlux не отвечает. Проверьте сеть.", hint)
        advanceUntilIdle()
        assertEquals(LoginUi.Done, m.ui)
        assertTrue(api.script.isEmpty())
    }

    @Test
    fun theLocalDeadlineEndsTheWaitWithoutAsking() = runTest {
        val platform = TestPlatform().apply { clock = 3_000_000L } // the sign-in ran out at 2_000 s = 2_000_000 ms
        val (c, api, _) = world(LoginPoll.Pending, platform = platform)
        val m = TelegramLoginModel(c, this)
        m.start(); advanceUntilIdle()
        assertEquals(LoginUi.Expired, m.ui)
        assertEquals(1, api.script.size, "nobody was asked")
    }

    @Test
    fun startTwiceIsOneLogin() = runTest {
        val (c, api, _) = world(LoginPoll.Pending, LoginPoll.Pending, LoginPoll.Pending)
        val m = TelegramLoginModel(c, this)
        m.start(); m.start()
        runCurrent()
        m.start()
        runCurrent()
        assertEquals(1, api.starts)
        m.cancel()
    }

    @Test
    fun cancelStops() = runTest {
        val (c, api, _) = world(LoginPoll.Pending, LoginPoll.Pending)
        val m = TelegramLoginModel(c, this)
        m.start(); runCurrent()
        m.cancel()
        assertEquals(LoginUi.Idle, m.ui)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, api.script.size, "no poll after cancelling")
    }

    @Test
    fun aDisabledAppFails() = runTest {
        val (c, _, _) = world(startError = BotException("app_disabled", "Эта версия приложения больше не может войти. Обновите приложение"))
        val m = TelegramLoginModel(c, this)
        m.start(); advanceUntilIdle()
        assertEquals(LoginUi.Failed("Эта версия приложения больше не может войти. Обновите приложение"), m.ui)
        m.start(); advanceUntilIdle() // "Повторить"
        assertEquals(LoginUi.Failed("Эта версия приложения больше не может войти. Обновите приложение"), m.ui)
    }

    @Test
    fun comingBackAsksAtOnce() = runTest {
        val (c, api, _) = world(LoginPoll.Pending, approved)
        val m = TelegramLoginModel(c, this, pollMs = 60_000)
        m.start(); runCurrent()
        assertEquals(2, api.script.size, "the first poll waits its minute")
        m.pollNow(); runCurrent()
        assertEquals(1, api.script.size)
        m.pollNow(); runCurrent()
        assertEquals(LoginUi.Done, m.ui)
    }
}
```

- [ ] **Step 3: Запустить — не компилируется**

Run: `cd /c/Users/meepo/openflux/OpenFluxDesktop && ./gradlew :shared:jvmTest --tests 'io.openflux.app.TelegramLoginModelTest' 2>&1 | tail -20`
Expected: FAIL — `Unresolved reference: TelegramLoginModel`.

- [ ] **Step 4: Модель `TelegramLogin.kt`**

```kotlin
package io.openflux.app.account

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.LoginPoll
import io.openflux.desktop.model.LoginStart
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the sign-in screen shows. */
sealed interface LoginUi {
    data object Idle : LoginUi
    data object Starting : LoginUi
    /** In Telegram, waiting for the person; [hint] says why the last question failed (no network), if it did. */
    data class Waiting(val hint: String? = null) : LoginUi
    data object Preparing : LoginUi
    data object Denied : LoginUi
    data object Expired : LoginUi
    data class Failed(val message: String) : LoginUi
    data object Done : LoginUi
}

/**
 * Signing in through the bot: begins a sign-in, opens Telegram with its link and asks every [pollMs] whether the
 * person approved, until they did, refused, or the five minutes ran out. A failed question (no network, too many
 * requests) does not end the wait: the person may be in Telegram, and the app may be asleep.
 */
@Stable
class TelegramLoginModel(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val pollMs: Long = POLL_MS,
) {
    var ui by mutableStateOf<LoginUi>(LoginUi.Idle)
        private set
    private var job: Job? = null
    private var login: LoginStart? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Begins a sign-in; one at a time. */
    fun start() {
        if (job?.isActive == true) return
        ui = LoginUi.Starting
        job = scope.launch { run() }
    }

    /** Gives up: the nonce simply runs out on the service. */
    fun cancel() {
        job?.cancel()
        job = null
        login = null
        ui = LoginUi.Idle
    }

    /** Opens Telegram with the same sign-in again. */
    fun reopen() {
        login?.let { container.platform.openUrl(it.deepLink) }
    }

    /** The app came back to the front: ask at once. */
    fun pollNow() {
        wake.trySend(Unit)
    }

    private suspend fun run() {
        val begun = try {
            container.bot.startLogin()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BotException) {
            ui = LoginUi.Failed(e.message ?: GENERIC)
            return
        }
        login = begun
        container.platform.openUrl(begun.deepLink)
        ui = LoginUi.Waiting()
        val deadline = begun.expiresAt * 1000
        while (true) {
            withTimeoutOrNull(pollMs) { wake.receive() }
            if (deadline > 0 && container.platform.now() >= deadline) {
                ui = LoginUi.Expired
                return
            }
            val answer = try {
                container.bot.pollLogin(begun.nonce)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                if (e.code == "network" || e.code == "rate_limited" || e.code.startsWith("http_")) {
                    ui = LoginUi.Waiting(hint = e.message)
                    continue
                }
                ui = LoginUi.Failed(e.message ?: GENERIC)
                return
            }
            when (answer) {
                LoginPoll.Pending -> ui = LoginUi.Waiting()
                LoginPoll.Denied -> { ui = LoginUi.Denied; return }
                LoginPoll.Expired -> { ui = LoginUi.Expired; return }
                is LoginPoll.Approved -> {
                    ui = LoginUi.Preparing
                    container.bot.completeLogin(answer.session)
                    ui = LoginUi.Done
                    return
                }
            }
        }
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val GENERIC = "Не получилось войти"
    }
}
```

- [ ] **Step 5: Запустить — проходит**

Run: `cd /c/Users/meepo/openflux/OpenFluxDesktop && ./gradlew :shared:jvmTest --tests 'io.openflux.app.TelegramLoginModelTest' 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL, 9 тестов зелёные. Если `startTwiceIsOneLogin` зависает, проверь, что `cancel()` вызван (иначе `runTest` ждёт бесконечный опрос).

- [ ] **Step 6: Коммит**

```bash
cd /c/Users/meepo/openflux/OpenFluxDesktop/shared
git add src
git commit -m "feat(login): the Telegram sign-in state machine"
```

---

### Task 4: Экран входа и точки входа

**Files:**
- Modify: `src/commonMain/kotlin/io/openflux/app/account/TelegramLogin.kt` (экран и содержимое)
- Modify: `src/commonMain/kotlin/io/openflux/app/shell/Entries.kt:47`
- Modify: `src/commonMain/kotlin/io/openflux/app/account/AccountScreens.kt` (`AccountScreen` без подписки, `BotSection`)
- Modify: `src/commonMain/kotlin/io/openflux/app/home/HomeScreen.kt` (`HomeRoot`, `StartPanel`)
- Test: `src/jvmTest/kotlin/io/openflux/app/TelegramLoginShotsTest.kt`

**Interfaces:**
- Consumes: `TelegramLoginModel`, `LoginUi` (Task 3), `BotState.preparing/connectionNote` (Task 2).
- Produces: `@Composable fun TelegramLoginContent(ui: LoginUi, onStart: () -> Unit, onCancel: () -> Unit, onReopen: () -> Unit)`, `@Composable fun TelegramLoginScreen()`.

- [ ] **Step 1: Тест-«скриншоты» (они же проверка, что все состояния рисуются)**

`TelegramLoginShotsTest.kt`:

```kotlin
package io.openflux.app

import io.openflux.app.account.LoginUi
import io.openflux.app.account.TelegramLoginContent
import io.openflux.app.components.ScreenScaffold
import kotlin.test.Test

/** The sign-in screen's states, for review by eye (SHOTS=<dir>). */
class TelegramLoginShotsTest {
    private fun shot(name: String, ui: LoginUi, frame: Shots.Frame = Shots.PHONE) =
        Shots.render("login-$name", frame) {
            ScreenScaffold("Вход", onBack = {}) { TelegramLoginContent(ui, onStart = {}, onCancel = {}, onReopen = {}) }
        }

    @Test fun idle() = shot("idle", LoginUi.Idle)
    @Test fun starting() = shot("starting", LoginUi.Starting)
    @Test fun waiting() = shot("waiting", LoginUi.Waiting())
    @Test fun waitingOffline() = shot("waiting-offline", LoginUi.Waiting(hint = "Бот OpenFlux не отвечает. Проверьте сеть."))
    @Test fun preparing() = shot("preparing", LoginUi.Preparing)
    @Test fun denied() = shot("denied", LoginUi.Denied)
    @Test fun expired() = shot("expired", LoginUi.Expired)
    @Test fun failed() = shot("failed", LoginUi.Failed("Эта версия приложения больше не может войти. Обновите приложение"))
    @Test fun waitingSmall() = shot("waiting", LoginUi.Waiting(), Shots.SMALL)
    @Test fun idleWide() = shot("idle", LoginUi.Idle, Shots.WIDE)
}
```

- [ ] **Step 2: Запустить — не компилируется**

Run: `cd /c/Users/meepo/openflux/OpenFluxDesktop && ./gradlew :shared:jvmTest --tests 'io.openflux.app.TelegramLoginShotsTest' 2>&1 | tail -15`
Expected: FAIL — `Unresolved reference: TelegramLoginContent`.

- [ ] **Step 3: Содержимое и экран в `TelegramLogin.kt`**

Добавить импорты (`Arrangement`, `Column`, `fillMaxWidth`, `Text`, `Composable`, `LaunchedEffect`, `Modifier`, `snapshotFlow`, `LocalWindowInfo`, `filter`, `Banner`, `PrimaryButton`, `ProgressLine`, `ScreenScaffold`, `SecondaryButton`, `TextAction`, `Tone`, `LocalNavigator`, `rememberScreenModel`, `AppTheme`, `OpenFluxBot`, `LocalAppContainer`) и в конец файла:

```kotlin
/** The sign-in screen's body for each state; the screen around it is [TelegramLoginScreen]. */
@Composable
fun TelegramLoginContent(ui: LoginUi, onStart: () -> Unit, onCancel: () -> Unit, onReopen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
        when (ui) {
            LoginUi.Idle -> {
                Text("Вход через Telegram", style = AppTheme.type.screenTitle, color = AppTheme.colors.text)
                Text(
                    "Подтвердите вход в боте @${OpenFluxBot.USERNAME}: подписка и подключение появятся здесь сами. Пароль не нужен.",
                    style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
                )
                PrimaryButton("Войти через Telegram", onStart, Modifier.fillMaxWidth())
            }
            LoginUi.Starting -> ProgressLine("Готовлю вход…")
            is LoginUi.Waiting -> {
                Text("Откройте Telegram и подтвердите вход в боте", style = AppTheme.type.screenTitle, color = AppTheme.colors.text)
                Text(
                    "Телеграм должен открыться сам. Если нет, нажмите кнопку ниже.",
                    style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
                )
                ProgressLine("Жду подтверждения…")
                ui.hint?.let { Banner(it, Tone.Warning) }
                SecondaryButton("Открыть Telegram ещё раз", onReopen, Modifier.fillMaxWidth())
                TextAction("Отмена", onCancel)
            }
            LoginUi.Preparing -> ProgressLine("Готовлю подключение…")
            LoginUi.Denied -> {
                Banner("Вход отклонён в боте", Tone.Warning)
                PrimaryButton("Повторить", onStart, Modifier.fillMaxWidth())
            }
            LoginUi.Expired -> {
                Banner("Время на подтверждение вышло", Tone.Info)
                PrimaryButton("Повторить", onStart, Modifier.fillMaxWidth())
            }
            is LoginUi.Failed -> {
                Banner(ui.message, Tone.Danger)
                PrimaryButton("Повторить", onStart, Modifier.fillMaxWidth())
            }
            LoginUi.Done -> ProgressLine("Готово")
        }
    }
}

/** Signing in (spec 8.1): the account is the person's Telegram, confirmed in the bot. */
@Composable
fun TelegramLoginScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val m = rememberScreenModel { TelegramLoginModel(container, it) }
    val window = LocalWindowInfo.current
    LaunchedEffect(m.ui) { if (m.ui == LoginUi.Done) navigator.back() }
    // Back from Telegram: ask at once instead of waiting out the interval.
    LaunchedEffect(window) { snapshotFlow { window.isWindowFocused }.filter { it }.collect { m.pollNow() } }
    ScreenScaffold("Вход", onBack = { m.cancel(); navigator.back() }) {
        TelegramLoginContent(m.ui, onStart = m::start, onCancel = m::cancel, onReopen = m::reopen)
    }
}
```

- [ ] **Step 4: `Entries.kt`**

Заменить строку 47: `Dest.Auth -> destEntry(dest) { TelegramLoginScreen() }` и добавить импорт `io.openflux.app.account.TelegramLoginScreen` (импорт `AuthScreen` останется до Task 6).

- [ ] **Step 5: `AccountScreen` и `BotSection` в `AccountScreens.kt`**

В `AccountScreen` ветка `if (s == null)`: заменить кнопку

```kotlin
            PrimaryButton("Войти через Telegram", { navigator.replaceTop(Dest.Auth) }, Modifier.fillMaxWidth())
```
В `BotSection` заменить строку входа:

```kotlin
            RowItem("Войти через Telegram", subtitle = "Подписка и подключение появятся сами", onClick = { navigator.open(Dest.Auth) })
```
и сразу после блока `if (session == null) {...} else {...}` (перед `RowItem(... "Продлить в боте" ...)`) добавить:

```kotlin
        if (session != null && bot.preparing) ProgressLine("Готовлю подключение…")
        bot.connectionNote?.let { Banner(it, Tone.Warning) }
```

- [ ] **Step 6: `HomeScreen.kt`**

В `HomeRoot` после `val preparing by container.subscription.preparing.collectAsState()` добавить `val bot by container.bot.state.collectAsState()`, а вызов `StartPanel(hasSubscription = subscription != null, preparing = preparing)` заменить на `StartPanel(hasSubscription = subscription != null, preparing = preparing || bot.preparing)`.

В `StartPanel` заменить две строки (`"Бот OpenFlux в Telegram"` и `"Аккаунт OpenFlux"`) одной:

```kotlin
        RowItem("Войти через Telegram", subtitle = "Подписка и подключение появятся сами", onClick = { navigator.open(Dest.Auth) })
```

- [ ] **Step 7: Полный прогон, затем коммит**

Run: `cd /c/Users/meepo/openflux/OpenFluxDesktop && ./gradlew :shared:jvmTest 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. Тесты, искавшие старые подписи («Аккаунт OpenFlux», «Бот OpenFlux в Telegram», «Войти или создать аккаунт»), поправить на новые: `grep -rn "Аккаунт OpenFlux\|Войти или создать\|Бот OpenFlux в Telegram" src/jvmTest`.

Посмотреть картинки: `SHOTS=/tmp/login-shots ./gradlew :shared:jvmTest --tests 'io.openflux.app.TelegramLoginShotsTest'` и открыть PNG из `/tmp/login-shots` (светлая/тёмная тема, маленький экран, широкий). Исправить переносы и отступы, если что-то обрезано.

```bash
cd /c/Users/meepo/openflux/OpenFluxDesktop/shared
git add src
git commit -m "feat(login): the Telegram sign-in screen and its entry points"
```

---

### Task 5: Ключ приложения в сборках

**Files:**
- Create: `src/jvmSharedMain/kotlin/io/openflux/desktop/data/AppKeys.kt`
- Modify: `src/jvmMain/kotlin/io/openflux/desktop/AppFactory.kt:57` (`HttpBotApi(...)`)
- Modify: `OpenFluxDesktop/desktopApp/build.gradle.kts` (генерация ресурса)
- Modify: `OpenFluxAndroid/androidApp/build.gradle.kts` (`BuildConfig.APP_KEY`), `OpenFluxAndroid/androidApp/src/main/kotlin/io/openflux/android/OpenFluxApplication.kt:73`
- Test: `src/jvmTest/kotlin/io/openflux/desktop/AppKeysTest.kt`

**Interfaces:**
- Produces: `object AppKeys { fun valid(text: String?): String; fun desktop(): String; fun apiBase(): String }`

- [ ] **Step 1: Падающий тест `AppKeysTest.kt`**

```kotlin
package io.openflux.desktop

import io.openflux.desktop.data.AppKeys
import io.openflux.desktop.model.OpenFluxBot
import kotlin.test.Test
import kotlin.test.assertEquals

class AppKeysTest {
    @Test
    fun onlyAKeyOfTheRightShapeCounts() {
        assertEquals("ofk_abcdefgh12345678", AppKeys.valid("  ofk_abcdefgh12345678\n"))
        for (bad in listOf(null, "", "ofk_", "ofk_short", "abc_abcdefgh12345678", "ofk_abcdefgh 12345678", "ofk_" + "a".repeat(129))) {
            assertEquals("", AppKeys.valid(bad), bad.toString())
        }
    }

    @Test
    fun theApiAddressMayBeMovedOnlyToThisMachine() {
        assertEquals(OpenFluxBot.API, AppKeys.pickBase(null))
        assertEquals(OpenFluxBot.API, AppKeys.pickBase("https://evil.example"))
        assertEquals(OpenFluxBot.API, AppKeys.pickBase("http://evil.example:8080"))
        assertEquals(OpenFluxBot.API, AppKeys.pickBase("http://127.0.0.1.evil.example:8080"))
        assertEquals("http://127.0.0.1:8099", AppKeys.pickBase("http://127.0.0.1:8099"))
        assertEquals("http://localhost:8099", AppKeys.pickBase("http://localhost:8099"))
    }
}
```

- [ ] **Step 2: Запустить — не компилируется**

Run: `cd /c/Users/meepo/openflux/OpenFluxDesktop && ./gradlew :shared:jvmTest --tests 'io.openflux.desktop.AppKeysTest' 2>&1 | tail -15`
Expected: FAIL — `Unresolved reference: AppKeys`.

- [ ] **Step 3: `AppKeys.kt`**

```kotlin
package io.openflux.desktop.data

import io.openflux.desktop.model.OpenFluxBot
import java.net.URI

/**
 * What the build says about itself to the OpenFlux service: its key (X-App-Key; an identifier and a switch for the
 * service, not a secret) and, for development, a local stand-in for the service's address.
 */
object AppKeys {
    private const val RESOURCE = "/openflux-appkey.txt"
    private val SHAPE = Regex("^ofk_[A-Za-z0-9_-]{8,128}$")

    /** The key if [text] has a key's shape, else blank. */
    fun valid(text: String?): String = text?.trim()?.takeIf(SHAPE::matches).orEmpty()

    /** The desktop build's key: -Dopenflux.appKey, then OPENFLUX_APP_KEY, then the resource the build wrote; blank when none. */
    fun desktop(): String =
        valid(System.getProperty("openflux.appKey"))
            .ifEmpty { valid(System.getenv("OPENFLUX_APP_KEY")) }
            .ifEmpty { valid(AppKeys::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }) }

    /** The service's address; OPENFLUX_API_BASE moves it, but only to this machine (a stand-in for tests). */
    fun apiBase(): String = pickBase(System.getenv("OPENFLUX_API_BASE"))

    internal fun pickBase(override: String?): String {
        val uri = runCatching { URI(override.orEmpty().trim()) }.getOrNull() ?: return OpenFluxBot.API
        val local = uri.scheme == "http" && (uri.host == "127.0.0.1" || uri.host == "localhost")
        return if (local) override!!.trim().trimEnd('/') else OpenFluxBot.API
    }
}
```

- [ ] **Step 4: Desktop: `AppFactory.kt` и Gradle**

`AppFactory.kt:57` → `botApi = io.openflux.desktop.data.HttpBotApi(io.openflux.desktop.data.AppKeys.apiBase(), io.openflux.desktop.data.AppKeys.desktop()),`

`OpenFluxDesktop/desktopApp/build.gradle.kts`, после `val appVersion = …`:

```kotlin
/** The key that names this build to the OpenFlux service: -Popenflux.appKey=ofk_… or OPENFLUX_APP_KEY. It is not committed. */
val appKey = ((findProperty("openflux.appKey") as String?) ?: System.getenv("OPENFLUX_APP_KEY")).orEmpty().trim()

val writeAppKey by tasks.registering {
    val out = layout.buildDirectory.file("generated/appkey/openflux-appkey.txt")
    inputs.property("appKey", appKey)
    outputs.file(out)
    doLast { out.get().asFile.apply { parentFile.mkdirs() }.writeText(appKey) }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/appkey")) }
tasks.named("processResources") { dependsOn(writeAppKey) }
```

- [ ] **Step 5: Android: Gradle и `OpenFluxApplication.kt`**

`androidApp/build.gradle.kts` перед `android { … }`:

```kotlin
/** The key that names this build to the OpenFlux service: -Popenflux.appKey=ofk_… or OPENFLUX_APP_KEY. It is not committed. */
val appKey = ((findProperty("openflux.appKey") as String?) ?: System.getenv("OPENFLUX_APP_KEY")).orEmpty().trim()
```
в `defaultConfig` рядом с `CORE_VERSION`: `buildConfigField("String", "APP_KEY", "\"$appKey\"")`.

`OpenFluxApplication.kt:73` → `botApi = io.openflux.desktop.data.HttpBotApi(appKey = io.openflux.desktop.data.AppKeys.valid(BuildConfig.APP_KEY)),`

- [ ] **Step 6: Прогон и проверка сборок**

Run: `cd /c/Users/meepo/openflux/OpenFluxDesktop && ./gradlew :shared:jvmTest --tests 'io.openflux.desktop.AppKeysTest' :desktopApp:processResources -Popenflux.appKey=ofk_testtesttesttest 2>&1 | tail -15 && cat desktopApp/build/generated/appkey/openflux-appkey.txt`
Expected: BUILD SUCCESSFUL; файл содержит `ofk_testtesttesttest`. Без свойства файл пустой, приложение собирается, вход показывает «в этой сборке нет ключа приложения».
В `OpenFluxAndroid`: `./gradlew :androidApp:compileDebugKotlin -Popenflux.appKey=ofk_testtesttesttest 2>&1 | tail -15` (после Task 6 повторить; если нет gomobile AAR: `bash scripts/build-android-core.sh ../OpenFlux`). Сабмодуль `OpenFluxAndroid/shared` для этой проверки ставится на ту же ветку прямо из рабочей копии Desktop (в стандартный клон `push` не получится: там эта ветка открыта): `git -C /c/Users/meepo/openflux/OpenFluxAndroid/shared fetch ../../OpenFluxDesktop/shared telegram-login && git -C /c/Users/meepo/openflux/OpenFluxAndroid/shared checkout FETCH_HEAD`.

- [ ] **Step 7: Коммиты**

```bash
cd /c/Users/meepo/openflux/OpenFluxDesktop/shared && git add src && git commit -m "feat(login): the app key and a local stand-in for the service address"
cd /c/Users/meepo/openflux/OpenFluxDesktop && git checkout -b telegram-login && git add desktopApp/build.gradle.kts shared && git commit -m "build: the app key goes into the desktop build from a property"
cd /c/Users/meepo/openflux/OpenFluxAndroid && git checkout -b telegram-login && git add androidApp && git commit -m "build: the app key goes into the Android build from a property"
```

---

### Task 6: Убрать старый вход

**Files:**
- Modify: `src/commonMain/kotlin/io/openflux/app/account/AccountModels.kt` (убрать `AuthStep`, `AuthModel`, `loginProblem`, `botSignIn`; оставить `MIN_PASSWORD` и `newPasswordProblem` — они нужны смене пароля существующих аккаунтов `opensub`)
- Modify: `src/commonMain/kotlin/io/openflux/app/account/AccountScreens.kt` (убрать `AuthScreen`, `Submit`, если не нужен)
- Modify: `src/commonMain/kotlin/io/openflux/app/shell/Entries.kt` (импорт)
- Modify: `src/commonMain/kotlin/io/openflux/desktop/service/BotAccount.kt` (убрать `signIn`, импорт `TelegramUser`), `.../model/OpenFluxBot.kt` (убрать `signInUrl`, `RETURN_TO`, `AUTH_RESULT`, `authResult`, `percentEncode`, `TelegramUser`, `BOT_ID`, `ORIGIN` если нигде не нужны), `.../jvmSharedMain/.../HttpBotApi.kt` (убрать `signIn`)
- Modify: `src/commonMain/kotlin/io/openflux/desktop/service/Accounts.kt` (убрать `telegramSignIn`), `.../model/Accounts.kt` (убрать `AccountKind.Telegram` и ветки `when`), `.../app/settings/SettingsLogic.kt:52`
- Modify: тесты — `AccountModelsTest.kt`, `BotAccountTest.kt`, прочие, найденные `grep`

**Interfaces:**
- Consumes: всё из Task 1–5.
- Produces: нет новых; удаляется `BotApi.signIn(user)`, `BotAccountManager.signIn()`, `Accounts.telegramSignIn`, `AccountKind.Telegram`, `AuthModel`, `AuthStep`, `AuthScreen`.

- [ ] **Step 1: Найти все использования**

Run: `cd /c/Users/meepo/openflux/OpenFluxDesktop/shared && grep -rn "AuthModel\|AuthStep\|AuthScreen\|telegramSignIn\|AccountKind.Telegram\|TelegramUser\|authResult\|signInUrl\|RETURN_TO\|botSignIn\|\.signIn()\|loginProblem" src ../../OpenFluxAndroid/androidApp/src ../desktopApp/src | grep -v "^src/commonMain/kotlin/io/openflux/app/account/TelegramLogin.kt"`
Expected: список мест; он же — чек-лист для следующих шагов.

- [ ] **Step 2: Удалить парольный вход**

В `AccountModels.kt` удалить `enum class AuthStep`, `loginProblem`, весь `class AuthModel` и в `SubscriptionModel` метод `botSignIn`. В `AccountScreens.kt` удалить `fun AuthScreen()` и (если после этого не используется) `private fun Submit`. В `Entries.kt` убрать импорт `AuthScreen`. Из `AccountModelsTest.kt` удалить тесты, использующие `AuthModel`/`AuthStep` (`registeringChecksTheFormFirst`, `aNewAccountShowsItsRecoveryKeyUntilItIsSaved`, `aFullAccountOffersToReplaceADevice` и остальные из `grep -n "AuthModel" src/jvmTest/kotlin/io/openflux/app/AccountModelsTest.kt`); тесты `SubscriptionModel` оставить.

- [ ] **Step 3: Удалить Login Widget**

Удалить `BotAccountManager.signIn()`, `BotApi.signIn`, `NoBotApi.signIn`, `HttpBotApi.signIn`; в `OpenFluxBot.kt` — `signInUrl`, `RETURN_TO`, `AUTH_RESULT`, `authResult`, `percentEncode`, `TelegramUser` (и `BOT_ID`/`ORIGIN`, если `grep` их больше не находит); в `Accounts.kt` (service) — `telegramSignIn`; в `model/Accounts.kt` — значение `Telegram` и ветки `Telegram ->`/`AccountKind.Telegram` в трёх `when` (строки ≈66, 100, 149); в `SettingsLogic.kt:52` — ветку `AccountKind.Telegram -> "бот OpenFlux"`. В `BotAccountTest.kt` удалить тесты `theSignInPageIsTheBots`, `telegramsAnswerIsReadInEveryBase64`, `signingInThroughTelegramRegistersAndShowsTheSubscription`, `aClosedSignInWindowCancels`, `anExpiredSessionSignsOutQuietly`, поле `userJson`, `signIn` у `FakeBot`, в `theHttpClientSpeaksTheGatewaysJson` — блок с `signIn` и `/api/v1/auth/telegram`; тест подписки и выхода из `signingInThroughTelegram…` (`m.refresh()` → `Pro`, `signOut`) перенести в `BotConnectionTest` как `subscriptionFollowsTheBotAndSignOutAsksTheService`:

```kotlin
    @Test
    fun subscriptionFollowsTheBotAndSignOutAsksTheService() = runTest {
        val (m, _, api) = env()
        m.completeLogin(session)
        assertEquals("Start", m.state.value.subscription?.planName)
        m.signOut()
        assertNull(m.state.value.session)
        assertEquals(1, api.signedOut)
    }
```

- [ ] **Step 4: Компиляция и полный прогон**

Run: `cd /c/Users/meepo/openflux/OpenFluxDesktop && ./gradlew :shared:jvmTest 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. Затем повторить `grep` из Step 1: пусто (кроме `AccountKind.Telegram`-упоминаний в комментариях истории, если есть — убрать).
Затем Android и Desktop: `cd /c/Users/meepo/openflux/OpenFluxAndroid && ./gradlew :androidApp:compileDebugKotlin -Popenflux.appKey=ofk_testtesttesttest 2>&1 | tail -15` и `cd ../OpenFluxDesktop && ./gradlew :desktopApp:compileKotlin 2>&1 | tail -15`.

- [ ] **Step 5: Коммит**

```bash
cd /c/Users/meepo/openflux/OpenFluxDesktop/shared
git add -A src
git commit -m "refactor(login): the password sign-in and the Telegram widget are gone"
```

---

### Task 7: Проход на десктопе против заглушки сервиса

**Files:**
- Create: `src/jvmTest/kotlin/io/openflux/desktop/StubServiceServe.kt`

**Interfaces:**
- Consumes: `AppKeys.apiBase()` (Task 5), всё приложение.

Заглушка отвечает как сервис (`start`, `poll` с `approved` на третьем опросе, `sessions`, `connect/resolve`, `subscription`) и работает, пока не появится файл-стоп. Это ручной проход, а не автотест: запускается только при `STUB_SERVE=1`.

- [ ] **Step 1: Заглушка**

```kotlin
package io.openflux.desktop

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test

/**
 * Serves the sign-in API on 127.0.0.1:8099 for a hand run of the desktop app (STUB_SERVE=1; stops when
 * <tmp>/stub-stop exists). Poll answers "approved" on its third call. Not a test of anything.
 */
class StubServiceServe {
    @Test
    fun serve() {
        if (System.getenv("STUB_SERVE") != "1") return
        val link = System.getenv("STUB_LINK") ?: error("STUB_LINK: an openflux:// link to answer connect/resolve with")
        var polls = 0
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 8099), 0)
        s.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val (code, json) = when (ex.requestURI.path) {
                "/api/v1/auth/app/start" -> 200 to """{"nonce":"stubnonce","deepLink":"https://t.me/openflux_bot?start=auth_stubnonce","expiresAt":"${System.currentTimeMillis() / 1000 + 300}"}"""
                "/api/v1/auth/app/poll" -> 200 to if (++polls < 3) """{"status":"pending"}""" else """{"status":"approved","auth":{"token":"ofs_stub","userId":"7","username":"stub","expiresAt":"${System.currentTimeMillis() / 1000 + 86400}"}}"""
                "/api/v1/sessions" -> 200 to """[{"sessionId":"s1","status":"running","connectToken":"StubStubStubStubStubStub"}]"""
                "/api/v1/connect/resolve" -> 200 to """{"name":"Заглушка #1","link":"$link"}"""
                "/api/v1/subscription" -> 200 to """{"planId":"own30","planName":"Заглушка","status":"active","daysLeft":20,"isActive":true}"""
                "/api/v1/auth/logout" -> 200 to "{}"
                else -> 404 to "{}"
            }
            println("stub: ${ex.requestMethod} ${ex.requestURI.path} ${if (body.isNotBlank()) body else ""} -> $code")
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        val stop = File(System.getProperty("java.io.tmpdir"), "stub-stop").also { it.delete() }
        while (!stop.exists()) Thread.sleep(500)
        s.stop(0)
    }
}
```

- [ ] **Step 2: Подготовить ссылку и запустить заглушку**

Ссылку `openflux://…` возьми из любого рабочего профиля приложения (кнопка «Поделиться» → скопировать) и положи в переменную. Заглушка в одном окне терминала:

```bash
cd /c/Users/meepo/openflux/OpenFluxDesktop
STUB_SERVE=1 STUB_LINK='<openflux://… из профиля>' ./gradlew :shared:jvmTest --tests 'io.openflux.desktop.StubServiceServe' -i 2>&1 | grep -E "stub:|BUILD"
```

- [ ] **Step 3: Запустить приложение против заглушки**

В другом окне:

```bash
cd /c/Users/meepo/openflux/OpenFluxDesktop
OPENFLUX_API_BASE=http://127.0.0.1:8099 ./gradlew :desktopApp:run -Popenflux.appKey=ofk_testtesttesttest -PskipCore=true
```
Проверить руками: «Главная» → «Войти через Telegram» → экран ожидания, Telegram (или браузер) открывает `t.me/openflux_bot?start=auth_stubnonce`; через ≈6 секунд экран закрывается; в списке подключений появился профиль «Заглушка #1»; «Подписка» показывает «Заглушка»; «Выйти из бота на этом устройстве» убирает профиль. Окно заглушки показывает цепочку `start` → `poll`×3 → `sessions` → `connect/resolve`.
Expected: всё перечисленное; если приложение пишет «нет ключа приложения», ключ не дошёл (проверить `desktopApp/build/generated/appkey/openflux-appkey.txt`).

- [ ] **Step 4: Остановить заглушку, зафиксировать результат**

```bash
rm -f "$(cygpath -u "$TEMP")/stub-stop"; touch "$(cygpath -u "$TEMP")/stub-stop"
```
Записать результат прохода (что видно, что нет) в `TASKS.md`, задача D1. Коммит:

```bash
cd /c/Users/meepo/openflux/OpenFluxDesktop/shared
git add src/jvmTest/kotlin/io/openflux/desktop/StubServiceServe.kt
git commit -m "test: a stand-in for the sign-in API for a hand run of the desktop app"
```

---

### Task 8: Документация, сабмодули и передача владельцу

**Files:**
- Modify: `OpenFluxDesktop/CHANGELOG.md`, `OpenFluxAndroid/CHANGELOG.md` (`## [Unreleased]`)
- Modify: `docs/specs/2026-10-14-telegram-login-design.md` (§5 «Удаляется» и «Проверка» приведены к фактам этого плана)
- Modify: `C:\Users\meepo\openflux\TASKS.md`

- [ ] **Step 1: Привести спек к плану**

В §5 спека: «Парольный вход, регистрация, ключ восстановления — удалены; управление уже выполненным входом в `opensub` (смена пароля, устройства, удаление) и подписки по ключам `/sub/…` остаются, пока `opensub` не выведен из обращения». В «Проверка»: «сквозной проход Android — против настоящего сервера; на десктопе — против заглушки (Task 7)».

- [ ] **Step 2: CHANGELOG приложений**

В `## [Unreleased]` обоих приложений:

```markdown
### Changed

- **Вход только через Telegram.** «Войти через Telegram» открывает бота, вы подтверждаете вход там и возвращаетесь в
  приложение: подписка и подключение появляются сами, ссылку вставлять не нужно. Пароли, регистрация и ключ
  восстановления убраны (аккаунты, в которые вы уже вошли, остаются).
```

- [ ] **Step 3: Сабмодули приложений**

```bash
cd /c/Users/meepo/openflux/OpenFluxClientShared && git fetch ../OpenFluxDesktop/shared telegram-login && git merge --ff-only FETCH_HEAD
cd ../OpenFluxAndroid/shared && git fetch local telegram-login && git checkout FETCH_HEAD
cd .. && git add shared CHANGELOG.md && git commit -m "shared: Telegram sign-in"
cd ../OpenFluxDesktop && git add shared CHANGELOG.md && git commit -m "shared: Telegram sign-in"
```
Проверить: `./gradlew :shared:jvmTest` в Desktop и `:androidApp:compileDebugKotlin` в Android зелёные.

- [ ] **Step 4: Обновить `TASKS.md`**

Отметить B1–B3, C1–C2, D1, E1, E2 по фактическим результатам проверок (только то, что реально прошло), добавить в историю дату, коммиты и результат прохода Task 7; D2 и E3 остаются.

- [ ] **Step 5: Передача владельцу API (требует решения пользователя)**

Остановиться и спросить пользователя, что именно отправлять «PR владельцу API»: (a) бриф `docs/specs/2026-10-14-telegram-login-backend-brief.md` как документ в `nevex-net/openflux` (`docs/…`), (b) реализация части A и `connect_token` в `nevex-net/openflux` по плану владельца, (c) только письмо/сообщение с брифом. Ничего не создавать в `nevex-net/*` и не писать владельцу до ответа.

---

## Self-Review

**Покрытие спека.** §Контракт сервера → Task 1; §1 общий слой → Task 1, 5; §2 автомат → Task 3; §3 `ensureConnection` → Task 2; §4 UI → Task 4; §5 удаление → Task 6 (с уточнением: управление существующими аккаунтами `opensub` и подписки по ключам остаются); §6 платформы → Task 3 (`pollNow` по фокусу окна, `openUrl`), Task 5; §7 безопасность → `toString()` (Task 1), выход (Task 2); Тесты → в каждой задаче; Порядок работ → задачи 1–8. Ключ приложения из сборки → Task 5. Сквозной проход на эмуляторе Android — вне плана (после выкатки сервера), записано в Global Constraints.

**Плейсхолдеры.** В плане нет «TBD/TODO»; места, зависящие от среды (ссылка `openflux://` из профиля, имя JSON-поля `connectToken`, согласуемое с владельцем), указаны явно: поле читается как `connectToken`, при другом имени меняется одна строка `sessionItem()` в `HttpBotApi` и тест `sessionsAreReadFromABareArrayOrAnObject`.

**Согласованность типов.** `LoginStart(nonce, deepLink, expiresAt)`, `LoginPoll.{Pending,Approved(session),Denied,Expired}`, `BotSessionItem(id, status, connectToken)` + `ready`, `BotApi.{startLogin,pollLogin,sessions,createSession}`, `BotAccountManager.{startLogin,pollLogin,completeLogin,signOut,NO_SLOT}`, `BotLink.account`, `BotState.{preparing,connectionNote}`, `LoginUi`, `TelegramLoginModel(container, scope, pollMs)`, `TelegramLoginContent(ui, onStart, onCancel, onReopen)`, `AppKeys.{valid,desktop,apiBase,pickBase}`, `HttpBotApi(base, appKey)` — одинаковы во всех задачах.

**Review Focus.** Все девять строк закреплены тестами в Task 1–3.

**Известные допущения, проверяемые при исполнении.** (1) `BotConnection` лежит в `io.openflux.desktop.service` (в тесте Task 2 указан правильный импорт); (2) `LocalWindowInfo.isWindowFocused` доступен в Compose 1.11 — если нет, `pollNow` вызывается без фокус-сигнала, цена: опрос по таймеру (до 2 секунд задержки), тесты не меняются; (3) стартовые задачи Gradle (`processResources`) в `desktopApp` называются стандартно; (4) формат ключа `ofk_…` взят из плана владельца (`ofk_` + случайные символы).
