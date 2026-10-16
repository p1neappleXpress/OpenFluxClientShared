package io.openflux.desktop.data

import io.openflux.desktop.model.BotBanner
import io.openflux.desktop.model.BotDocument
import io.openflux.desktop.model.BotPaymentMethod
import io.openflux.desktop.model.BotSlot
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotInvoice
import io.openflux.desktop.model.BotPlan
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.service.BotAccountManager
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The OpenFlux bot's API over HTTPS (the usual certificate checks: a real domain). Its JSON is gRPC-gateway's:
 * camelCase names, 64-bit numbers as strings, empty fields left out. A session goes as a bearer token
 * (the sign-in answers with it): the app keeps it, never a cookie.
 */
class HttpBotApi(private val base: String = OpenFluxBot.API, private val appId: String = "") : BotApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun subscription(token: String): BotSubscription {
        val o = request("GET", "/api/v1/subscription", token, null).jsonObject
        return BotSubscription(
            planId = o.str("planId"), planName = o.str("planName"), status = o.str("status"), expiresAt = o.long("expiresAt"),
            daysLeft = o.long("daysLeft").toInt(), maxSessions = o.long("maxSessions").toInt(),
            trafficUsedBytes = o.long("trafficUsedBytes"), trafficLimitBytes = o.long("trafficLimitBytes"),
            isActive = (o["isActive"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    override suspend fun signOut(token: String) {
        request("POST", "/api/v1/auth/logout", token, JsonObject(emptyMap()))
    }

    override suspend fun resolve(connectToken: String): BotConnection {
        val o = request("POST", "/api/v1/connect/resolve", null, JsonObject(mapOf("token" to JsonPrimitive(connectToken)))).jsonObject
        val link = o.str("link")
        if (!link.startsWith("openflux://")) throw BotException("bad_response", BAD_RESPONSE)
        return BotConnection(o.str("name"), link)
    }

    override suspend fun exchange(code: String, verifier: String, redirectUri: String): BotSession {
        if (appId.isEmpty()) throw BotException("no_app_id", "Вход в этой сборке выключен: в ней нет идентификатора приложения")
        val body = JsonObject(
            mapOf("code" to JsonPrimitive(code), "code_verifier" to JsonPrimitive(verifier), "redirect_uri" to JsonPrimitive(redirectUri)),
        )
        val o = try {
            request("POST", "/api/v1/auth/app/exchange", null, body, mapOf("X-App-Id" to appId)) as? JsonObject
        } catch (e: BotException) {
            throw signInError(e)
        }
        if (o == null) throw BotException("bad_response", "Сервис ответил непонятно: обновите приложение")
        val token = o.str("token")
        if (token.isEmpty()) throw BotException("bad_response", "Сервис не выдал сессию: обновите приложение")
        val userId = o.long("userId").takeIf { it > 0 } ?: o.long("user_id")
        val expiresAt = o.long("expiresAt").takeIf { it > 0 } ?: o.long("expires_at")
        return BotSession(token, userId, "", expiresAt)
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

    override suspend fun plans(token: String?): List<BotPlan> {
        val e = request("GET", "/api/v1/plans", token, null, refuse = ::plansRefusal)
        val array = e as? JsonArray ?: (e as? JsonObject)?.get("plans") as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            BotPlan(
                id = o.str("id").ifEmpty { return@mapNotNull null },
                name = o.str("name"), durationDays = o.long("durationDays").toInt(), trafficLimitBytes = o.long("trafficLimitBytes"),
                description = o.str("description"), price = o.long("price"), currency = o.str("currency").ifEmpty { "RUB" },
            )
        }
    }

    override suspend fun paymentMethods(token: String): List<BotPaymentMethod> {
        val e = try {
            request("GET", "/api/v1/payment-methods", token, null)
        } catch (e: BotException) {
            if (e.notFound) return emptyList() else throw e // a service without the list: it picks the way itself
        }
        val array = e as? JsonArray ?: (e as? JsonObject)?.get("methods") as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let { o -> BotPaymentMethod(o.str("id"), o.str("name")).takeIf { m -> m.id.isNotEmpty() } } }
    }

    override suspend fun slots(token: String): List<BotSlot> {
        val e = request("GET", "/api/v1/slots", token, null)
        val array = e as? JsonArray ?: (e as? JsonObject)?.get("slots") as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(::slotOf) }
    }

    override suspend fun startSlot(token: String, slotId: String): BotSlot = slotAction(token, slotId, "start")
    override suspend fun stopSlot(token: String, slotId: String): BotSlot = slotAction(token, slotId, "stop")

    private suspend fun slotAction(token: String, slotId: String, action: String): BotSlot {
        if (!INVOICE_ID.matches(slotId)) throw BotException("bad_request", BAD_REQUEST)
        val o = request("POST", "/api/v1/slots/$slotId/$action", token, JsonObject(emptyMap()), refuse = ::slotRefusal) as? JsonObject
        return (o?.get("slot") as? JsonObject)?.let(::slotOf) ?: throw BotException("bad_response", BAD_RESPONSE)
    }

    override suspend fun documents(token: String, slotId: String): List<BotDocument> {
        if (!INVOICE_ID.matches(slotId)) throw BotException("bad_request", BAD_REQUEST)
        val e = request("GET", "/api/v1/slots/$slotId/documents", token, null, refuse = ::documentRefusal)
        val array = e as? JsonArray ?: (e as? JsonObject)?.get("documents") as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(::documentOf) }
    }

    override suspend fun addDocument(token: String, slotId: String, url: String): BotDocument {
        if (!INVOICE_ID.matches(slotId)) throw BotException("bad_request", BAD_REQUEST)
        val body = JsonObject(mapOf("url" to JsonPrimitive(url.trim())))
        val o = request("POST", "/api/v1/slots/$slotId/documents", token, body, refuse = ::documentRefusal) as? JsonObject
        return o?.let(::documentOf) ?: throw BotException("bad_response", BAD_RESPONSE)
    }

    override suspend fun deleteDocument(token: String, slotId: String, documentId: String) {
        if (!INVOICE_ID.matches(slotId) || !DOCUMENT_ID.matches(documentId)) throw BotException("bad_request", BAD_REQUEST)
        request("DELETE", "/api/v1/slots/$slotId/documents/$documentId", token, null, refuse = ::documentRefusal)
    }

    override suspend fun checkDocument(token: String, slotId: String, documentId: String): BotDocument {
        if (!INVOICE_ID.matches(slotId) || !DOCUMENT_ID.matches(documentId)) throw BotException("bad_request", BAD_REQUEST)
        val o = request("POST", "/api/v1/slots/$slotId/documents/$documentId/check", token, JsonObject(emptyMap()), refuse = ::documentRefusal) as? JsonObject
        return o?.let(::documentOf) ?: throw BotException("bad_response", BAD_RESPONSE)
    }

    override suspend fun connectSlot(token: String, slotId: String, documentIds: List<String>): BotSlot {
        if (!INVOICE_ID.matches(slotId) || documentIds.any { !DOCUMENT_ID.matches(it) }) throw BotException("bad_request", BAD_REQUEST)
        val body = JsonObject(mapOf("document_ids" to JsonArray(documentIds.map(::JsonPrimitive))))
        val o = request("POST", "/api/v1/slots/$slotId/connect", token, body, refuse = ::documentRefusal) as? JsonObject
        return (o?.get("slot") as? JsonObject)?.let(::slotOf) ?: throw BotException("bad_response", BAD_RESPONSE)
    }

    private fun slotOf(o: JsonObject): BotSlot? {
        val id = o.str("slotId").ifEmpty { return null }
        val session = o["session"] as? JsonObject
        return BotSlot(
            id = id, alias = o.str("alias"), planName = o.str("planName"), mode = o.str("mode").ifEmpty { "auto" },
            state = o.str("state").ifEmpty { "free" }, expiresAt = o.long("expiresAt"), connectionNo = o.long("connectionNo"),
            canStart = o.bool("canStart"), canStop = o.bool("canStop"), needsSetup = o.bool("needsSetup"),
            sessionId = session?.str("sessionId").orEmpty(), connectToken = session?.str("connectToken").orEmpty(),
        )
    }

    private fun documentOf(o: JsonObject): BotDocument? =
        BotDocument(o.str("id"), o.str("kind"), o.str("label"), o.str("status")).takeIf { it.id.isNotEmpty() }

    /** Why a Start or Stop was refused, in the person's words (the service's are English). */
    private fun slotRefusal(status: Int, said: String): BotException? = when (status) {
        404 -> BotException("slot_gone", "Этого места больше нет. Обновите список")
        409 -> BotException("slot_changed", "Место уже в другом состоянии. Обновите список")
        403 -> BotException(
            "slot_refused",
            when {
                "manual" in said -> "Это место настраивается из своих документов, а не запускается"
                "traffic" in said -> "Трафик этого места исчерпан"
                "expired" in said || "ended" in said -> "Срок этого места закончился"
                "blocked" in said -> "Этот аккаунт заблокирован. Напишите в поддержку"
                else -> "Сервис не разрешил это действие с местом"
            },
        )
        400 -> BotException("connection_lost", "Подключение не удалось восстановить, место снова свободно: запустите его ещё раз")
        503 -> BotException("no_room", "Сейчас нет свободной мощности, попробуйте через пару минут")
        in 500..599 -> BotException("http_$status", "Сервис OpenFlux не смог выполнить действие с местом. Попробуйте позже")
        else -> null
    }

    /** Why a document (or the connection from documents) was refused, in the person's words. */
    private fun documentRefusal(status: Int, said: String): BotException? = when (status) {
        400 -> when {
            "not a supported document link" in said -> BotException("bad_link", "Это не ссылка на поддерживаемый документ: Яндекс Диск, Облако Mail.ru, Телемост, Доски или Cups")
            "cannot be used" in said -> BotException("doc_unusable", "Документ не подходит: он не открыт на редактирование по ссылке, это не docx или xlsx, или его нет")
            "pick one to five" in said -> BotException("doc_pick", "Выберите от одного до пяти документов разных видов")
            else -> BotException("bad_request", "Документы этого места сейчас менять нельзя: место занято подключением или закончилось")
        }
        404 -> BotException("not_found", "Документ или место не найдены. Обновите список")
        409 -> BotException("doc_taken", "Эта ссылка уже добавлена")
        429 -> if ("limit is reached" in said) BotException("doc_limit", "Документов больше добавить нельзя: достигнут предел") else BotException("rate_limited", "Слишком много проверок документов, подождите минуту")
        503 -> BotException("docs_off", "Свои документы сейчас недоступны")
        in 500..599 -> BotException("http_$status", "Сервис OpenFlux не смог изменить документ. Попробуйте позже")
        else -> null
    }

    override suspend fun createInvoice(token: String, termCode: String, idempotencyKey: String, gateway: String): BotInvoice {
        if (!IDEMPOTENCY_KEY.matches(idempotencyKey)) throw BotException("bad_request", BAD_REQUEST)
        val body = JsonObject(buildMap { put("plan_id", JsonPrimitive(termCode)); if (gateway.isNotEmpty()) put("gateway", JsonPrimitive(gateway)) })
        val headers = mapOf("Idempotency-Key" to idempotencyKey)
        val o = try {
            request("POST", "/api/v1/subscription/invoice", token, body, headers, ::invoiceRefusal)
        } catch (e: BotException) {
            if (e.code != "busy") throw e
            // The same key is still being handled (a call before this one, lost on the way): asked once more.
            delay(BUSY_RETRY_MS)
            request("POST", "/api/v1/subscription/invoice", token, body, headers, ::invoiceRefusal)
        }
        return invoiceOf(o)
    }

    override suspend fun invoice(token: String, id: String): BotInvoice {
        if (!INVOICE_ID.matches(id)) throw BotException("bad_request", BAD_REQUEST)
        return invoiceOf(request("GET", "/api/v1/subscription/invoices/$id", token, null))
    }

    override suspend fun banners(): List<BotBanner> {
        val e = request("GET", "/api/v1/banners", null, null)
        val array = e as? JsonArray ?: (e as? JsonObject)?.get("banners") as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            BotBanner(o.long("id"), o.str("kind").ifEmpty { "info" }, o.str("textRu"), o.str("textEn"))
                .takeIf { it.textRu.isNotBlank() || it.textEn.isNotBlank() }
        }
    }

    /** An invoice the service answered with: our id, and a payment page only over https (it is opened in the browser). */
    private fun invoiceOf(e: JsonElement): BotInvoice {
        val o = e as? JsonObject ?: throw BotException("bad_response", BAD_RESPONSE)
        val inv = BotInvoice(o.str("id"), o.str("payUrl"), o.str("amount"), o.str("status"), o.long("createdAt"), o.long("expiresAt"))
        if (inv.id.isEmpty()) throw BotException("bad_response", BAD_RESPONSE)
        if (inv.payUrl.isNotEmpty() && !inv.payUrl.startsWith("https://")) throw BotException("bad_response", BAD_RESPONSE)
        return inv
    }

    /** Why an invoice was refused, in the person's words; the service's own are English and technical. */
    private fun invoiceRefusal(status: Int, said: String): BotException? = when (status) {
        400 -> if ("payment method" in said) {
            BotException("method_gone", "Этот способ оплаты сейчас недоступен. Выберите другой")
        } else if ("slot limit" in said) {
            BotException("slot_limit", "Больше мест на этом аккаунте взять нельзя. Освободите одно или напишите в поддержку в @${OpenFluxBot.USERNAME}")
        } else {
            BotException("bad_request", BAD_REQUEST)
        }
        403 -> BotException("blocked", "Этот аккаунт заблокирован. Напишите в поддержку в @${OpenFluxBot.USERNAME}")
        404 -> BotException("plan_gone", "Этот тариф больше не продаётся. Обновите список тарифов")
        409 -> BotException("busy", "Счёт ещё создаётся. Попробуйте через пару секунд")
        503 -> BotException("payments_off", "Оплата временно недоступна. Попробуйте позже")
        in 500..599 -> BotException("http_$status", "Сервис OpenFlux не смог создать счёт. Попробуйте позже")
        else -> null
    }

    private fun plansRefusal(status: Int, said: String): BotException? =
        if (status == 503) BotException("unavailable", russian(said) ?: "Тарифы временно недоступны. Попробуйте позже") else null

    /** The service answers every failed sign-in alike (401): wrong or used code, unconfirmed email, foreign address, unknown app id. */
    private fun signInError(e: BotException): BotException = when (e.code) {
        "unauthorized" -> BotException("sign_in_failed", "Не получилось войти. Если входили по почте, подтвердите адрес по ссылке из письма и попробуйте снова")
        "forbidden" -> BotException("blocked", "Этот аккаунт заблокирован. Напишите в поддержку в @${OpenFluxBot.USERNAME}")
        "not_found" -> BotException("not_ready", "Сервис пока не принимает вход из приложения. Попробуйте позже")
        "http_503" -> BotException("unavailable", "Вход временно недоступен, попробуйте через пару минут")
        "http_400" -> BotException("bad_request", "Приложение отправило неверный запрос входа: обновите приложение")
        else -> e
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L

    /**
     * One call; a refusal (HTTP 300 and up) fails with [BotException]: the one [refuse] makes of the status and the
     * service's message when it makes one, else the usual one for the status.
     */
    private suspend fun request(
        method: String,
        path: String,
        token: String?,
        body: JsonObject?,
        headers: Map<String, String> = emptyMap(),
        refuse: (status: Int, said: String) -> BotException? = { _, _ -> null },
    ): JsonElement =
        withContext(Dispatchers.IO) {
            val conn = try {
                URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw BotException("network", "Сервис OpenFlux не отвечает. Проверьте сеть.")
            }
            try {
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.use { it.readNBytes(MAX_BYTES).toString(Charsets.UTF_8) }.orEmpty()
                if (code >= 300) refuse(code, rawWords(text))?.let { throw it }
                when {
                    code == 401 -> throw BotException("unauthorized", "Войдите заново")
                    code == 403 -> throw BotException("forbidden", serverWords(text) ?: BotAccountManager.HttpForbidden)
                    code == 404 -> throw BotException("not_found", "Не найдено")
                    code == 429 -> throw BotException("rate_limited", "Слишком много запросов к сервису OpenFlux, подождите минуту")
                    code >= 300 -> throw BotException("http_$code", serverWords(text) ?: "Сервис OpenFlux ответил ошибкой $code")
                }
                try {
                    json.parseToJsonElement(text.ifBlank { "{}" })
                } catch (e: Exception) {
                    throw BotException("bad_response", BAD_RESPONSE)
                }
            } catch (e: IOException) {
                throw BotException("network", "Сервис OpenFlux не отвечает. Проверьте сеть.")
            } finally {
                conn.disconnect()
            }
        }

    /** The message of a refusal (gRPC-gateway: {"code":7,"message":"…"}) as the service wrote it, or "". */
    private fun rawWords(body: String): String =
        runCatching { (json.parseToJsonElement(body) as? JsonObject)?.str("message") }.getOrNull().orEmpty()

    /**
     * What the service said in its own words, made fit to show: the bot's HTML and emoji out, the technical prefix
     * and line breaks out. Null when it said nothing usable, or only in English: those words are for developers
     * ("no free slot for an automatic connection", a database error), not for the person.
     */
    private fun serverWords(body: String): String? = russian(rawWords(body))

    private fun russian(message: String): String? {
        val clean = message
            .replace(Regex("<[^>]+>"), "")
            .removePrefix("session creation blocked:")
            .filterNot { it.isSurrogate() || it == '⚠' || it == '☁' || it == '️' }
            .replace(Regex("\\s+"), " ")
            .trim()
        return clean.takeIf { s -> s.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' } }?.take(400)
    }

    private companion object {
        const val MAX_BYTES = 1 shl 20
        const val BUSY_RETRY_MS = 1_000L
        val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9_-]{8,64}$")
        /** Our invoice ids are UUIDs: nothing else is put into a path. */
        val INVOICE_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        /** A document id of the service: nothing else is put into a path. */
        val DOCUMENT_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        const val BAD_REQUEST = "Приложение отправило неверный запрос: обновите приложение"
        const val BAD_RESPONSE = "Сервис OpenFlux ответил непонятно: обновите приложение"
    }
}
