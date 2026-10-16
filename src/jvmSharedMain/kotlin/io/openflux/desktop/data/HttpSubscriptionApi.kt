package io.openflux.desktop.data

import io.openflux.desktop.model.AccountInfo
import io.openflux.desktop.model.AuthResult
import io.openflux.desktop.model.DeviceInfo
import io.openflux.desktop.model.OrderInfo
import io.openflux.desktop.model.PlansResult
import io.openflux.desktop.model.ServiceEndpoint
import io.openflux.desktop.model.SubscriptionDocument
import io.openflux.desktop.model.SubscriptionException
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.service.SubscriptionApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/**
 * The subscription service over HTTPS (desktop and Android share it). With a
 * pin the only certificate trusted is the one whose SHA-256 it is: the service
 * runs on an address without a domain, and no password or token is sent to any
 * other server. Plain http is accepted for this machine only (tests).
 * Every request names the transports this build runs, so the service's links
 * carry no type the app could not read (a link it cannot read loses the location).
 */
class HttpSubscriptionApi : SubscriptionApi {
    // coerceInputValues: a service answering "profiles":null (no usable pool) is
    // read as the empty list the field promises, not as a broken answer.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    @Serializable
    private class ErrorBody(val error: String = "", val message: String = "", val devices: List<DeviceInfo> = emptyList())

    override suspend fun plans(ep: ServiceEndpoint) = get(ep, "/api/v1/plans", null, PlansResult.serializer())

    override suspend fun register(ep: ServiceEndpoint, login: String, password: String, deviceName: String) =
        send(ep, "POST", "/api/v1/register", null, obj("login" to login, "password" to password, "deviceName" to deviceName), AuthResult.serializer())

    override suspend fun login(ep: ServiceEndpoint, login: String, password: String, deviceName: String, replaceDeviceId: String?) =
        send(
            ep, "POST", "/api/v1/login", null,
            obj("login" to login, "password" to password, "deviceName" to deviceName, "replaceDeviceId" to replaceDeviceId.orEmpty()),
            AuthResult.serializer(),
        )

    override suspend fun recover(ep: ServiceEndpoint, login: String, recoveryKey: String, newPassword: String, deviceName: String) =
        send(
            ep, "POST", "/api/v1/recover", null,
            obj("login" to login, "recoveryKey" to recoveryKey, "newPassword" to newPassword, "deviceName" to deviceName),
            AuthResult.serializer(),
        )

    override suspend fun account(ep: ServiceEndpoint, token: String) = get(ep, "/api/v1/account", token, AccountInfo.serializer())

    override suspend fun document(ep: ServiceEndpoint, token: String) = get(ep, "/api/v1/subscription", token, SubscriptionDocument.serializer())

    override suspend fun keyDocument(ep: ServiceEndpoint, url: String) = request(ep, url, "GET", null, null, SubscriptionDocument.serializer())

    override suspend fun startTrial(ep: ServiceEndpoint, token: String) = send(ep, "POST", "/api/v1/trial", token, null, AccountInfo.serializer())

    override suspend fun checkout(ep: ServiceEndpoint, token: String, planId: String) =
        send(ep, "POST", "/api/v1/checkout", token, obj("planId" to planId), OrderInfo.serializer())

    override suspend fun order(ep: ServiceEndpoint, token: String, orderId: String) =
        get(ep, "/api/v1/orders/" + enc(orderId), token, OrderInfo.serializer())

    override suspend fun changePassword(ep: ServiceEndpoint, token: String, password: String, newPassword: String) =
        send(ep, "POST", "/api/v1/password", token, obj("password" to password, "newPassword" to newPassword), AccountInfo.serializer())

    override suspend fun logout(ep: ServiceEndpoint, token: String) {
        send(ep, "POST", "/api/v1/logout", token, null, Ok.serializer())
    }

    override suspend fun removeDevice(ep: ServiceEndpoint, token: String, deviceId: String) =
        send(ep, "DELETE", "/api/v1/devices/" + enc(deviceId), token, null, AccountInfo.serializer())

    override suspend fun deleteAccount(ep: ServiceEndpoint, token: String, password: String) {
        // POST, not DELETE with a body: not every HTTP client on the JVM can send one.
        send(ep, "POST", "/api/v1/account/delete", token, obj("password" to password), Ok.serializer())
    }

    /** `/healthz` answers plain text: only the status counts (and the pin, checked on the way). */
    override suspend fun health(ep: ServiceEndpoint) = withContext(Dispatchers.IO) {
        val conn = try {
            open(ep, ep.base + "/healthz")
        } catch (e: IOException) {
            throw network(e)
        }
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code != 200) throw SubscriptionException(SubscriptionException.BAD_RESPONSE, "Сервис ответил ошибкой $code")
        } catch (e: IOException) {
            throw network(e)
        } finally {
            conn.disconnect()
        }
    }

    @Serializable
    private class Ok(val ok: Boolean = true)

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private suspend fun <T> get(ep: ServiceEndpoint, path: String, token: String?, ser: KSerializer<T>) = send(ep, "GET", path, token, null, ser)

    private suspend fun <T> send(ep: ServiceEndpoint, method: String, path: String, token: String?, body: JsonObject?, ser: KSerializer<T>): T =
        request(ep, ep.base + path, method, token, body, ser)

    private suspend fun <T> request(ep: ServiceEndpoint, url: String, method: String, token: String?, body: JsonObject?, ser: KSerializer<T>): T =
        withContext(Dispatchers.IO) {
            val conn = try {
                open(ep, url)
            } catch (e: IOException) {
                throw network(e)
            }
            try {
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty(TRANSPORTS_HEADER, TRANSPORTS)
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.use { it.readNBytes(MAX_BYTES + 1).also { b -> if (b.size > MAX_BYTES) throw SubscriptionException(SubscriptionException.BAD_RESPONSE, "Ответ сервиса слишком большой") }.toString(Charsets.UTF_8) }.orEmpty()
                if (code in 300..399) throw SubscriptionException(SubscriptionException.BAD_RESPONSE, "Сервис перенаправляет запрос: проверьте адрес")
                if (code >= 400) throw failure(code, text)
                try {
                    json.decodeFromString(ser, text)
                } catch (e: Exception) {
                    throw SubscriptionException(SubscriptionException.BAD_RESPONSE, "Сервис ответил непонятно. Обновите приложение или обратитесь в поддержку.")
                }
            } catch (e: IOException) {
                throw network(e)
            } finally {
                conn.disconnect()
            }
        }

    private fun failure(code: Int, text: String): SubscriptionException {
        val body = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
        val reason = body?.error?.takeIf { it.isNotEmpty() } ?: if (code == 404) "not_found" else "http_$code"
        val message = body?.message?.takeIf { it.isNotEmpty() } ?: "Сервис ответил ошибкой $code"
        return SubscriptionException(reason, message, body?.devices.orEmpty())
    }

    /** A dead network and a certificate that is not the service's look different to the user. */
    private fun network(e: IOException): SubscriptionException {
        var c: Throwable? = e
        while (c != null) {
            if (c is PinMismatch) {
                return SubscriptionException(
                    SubscriptionException.BAD_PIN,
                    "Не удалось убедиться, что это ваш сервис: сертификат не совпал. Логин и пароль не отправлены.",
                )
            }
            c = c.cause
        }
        val detail = if (e is SSLException) "Не удалось установить защищённое соединение" else "Сервис не отвечает. Проверьте сеть."
        return SubscriptionException(SubscriptionException.NETWORK, detail)
    }

    private fun open(ep: ServiceEndpoint, url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection && ep.pin.isNotEmpty()) {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(PinnedTrust(ep.pin)), null)
            conn.sslSocketFactory = context.socketFactory
            // The pin is what names the server: the certificate of an address has no host to check.
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        return conn
    }

    private class PinMismatch : CertificateException("the server's certificate is not the pinned one")

    /** Trusts one certificate: the one whose SHA-256 (hex) is [pin]. */
    private class PinnedTrust(pin: String) : X509TrustManager {
        private val want = pin.lowercase()

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw PinMismatch()
            val sum = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            if (!MessageDigest.isEqual(sum.joinToString("") { "%02x".format(it) }.toByteArray(), want.toByteArray())) throw PinMismatch()
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("no client certificates")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val MAX_BYTES = 1 shl 20
        const val TRANSPORTS_HEADER = "X-OpenFlux-Transports"
        val TRANSPORTS = TransportType.entries.filter { it.linkable }.joinToString(",") { it.cliName }
    }
}
