package io.openflux.desktop.model

import kotlinx.serialization.Serializable

/**
 * Where the subscription service lives: its https address and, for a
 * self-signed certificate (an IP address without a domain), the SHA-256 of
 * that certificate in hex, which the app trusts instead of the usual chain.
 */
@Serializable
data class ServiceEndpoint(val url: String, val pin: String = "") {
    val base: String get() = url.trimEnd('/')
}

/** The service this build of the app talks to unless a key names another. */
object ServiceDefaults {
    val endpoint = ServiceEndpoint(
        url = "https://141.11.209.233:8443",
        pin = "2dad03b51bb16f347879ef0b5330e9638a18a7a25bfbe7cf4144db12ae14ca39",
    )
}

/** What a subscription says about itself; the service's words. */
object SubStatus {
    const val ACTIVE = "active"
    const val EXPIRED = "expired"
    const val REVOKED = "revoked"
}

@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val createdAt: Long = 0,
    val lastSeen: Long = 0,
    val current: Boolean = false,
)

@Serializable
data class PlanRef(val id: String, val name: String, val devices: Int = 1)

/** The signed-in account as the service reports it. */
@Serializable
data class AccountInfo(
    val id: String = "",
    val login: String,
    val status: String = SubStatus.EXPIRED,
    /** Unix seconds; 0 means the account never had a subscription. */
    val expires: Long = 0,
    val plan: PlanRef? = null,
    val trialAvailable: Boolean = false,
    val devices: List<DeviceInfo> = emptyList(),
    val deviceLimit: Int = 1,
    val support: String = "",
    /** Set when the service has moved: where the app should go. */
    val service: ServiceMove? = null,
)

@Serializable
data class AuthResult(
    val deviceToken: String,
    val deviceId: String,
    /** Shown once, on registration and after a recovery. */
    val recoveryKey: String = "",
    val account: AccountInfo,
) {
    override fun toString() = "AuthResult(${account.login})"
}

@Serializable
data class SubPlan(
    val id: String,
    val name: String,
    val days: Int,
    val devices: Int = 1,
    val price: String = "",
)

@Serializable
data class PaymentInfo(val provider: String = "manual", val instructions: String = "")

@Serializable
data class PlansResult(val plans: List<SubPlan> = emptyList(), val payment: PaymentInfo = PaymentInfo())

@Serializable
data class OrderInfo(
    val orderId: String,
    val planId: String = "",
    /** pending, paid or canceled. */
    val status: String = "pending",
    val expires: Long = 0,
    val paymentUrl: String = "",
    val instructions: String = "",
) {
    val paid: Boolean get() = status == "paid"

    /** Paid, or over without being paid; anything else (pending, a status this build does not know) is still waited for. */
    val closed: Boolean get() = status in CLOSED_ORDER
}

private val CLOSED_ORDER = setOf("paid", "canceled", "expired", "failed")

/** An order the app waits to be paid, kept across restarts: the person may pay in a bank's app while this one is closed. */
@Serializable
data class PendingOrder(
    val orderId: String,
    val planId: String = "",
    /** The platform clock's milliseconds when it was made. */
    val createdAt: Long = 0,
    val paymentUrl: String = "",
) {
    override fun toString() = "PendingOrder($orderId)"
}

/**
 * The service's new address, from its document or account answer. The app
 * follows it once the new address answers with the new pin.
 */
@Serializable
data class ServiceMove(val url: String, val pin: String = "") {
    /** The endpoint to move to, or null when the address or pin is not one the app would trust. */
    fun endpoint(): ServiceEndpoint? {
        val u = url.trim().trimEnd('/')
        val scheme = u.substringBefore("://", "")
        val authority = u.substringAfter("://", "").substringBefore('/')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        if (host.isEmpty() || authority.contains('@') || u.contains('?') || u.contains('#')) return null
        if (scheme != "https" && !(scheme == "http" && host in LOCAL_HOSTS)) return null
        val p = pin.trim().lowercase()
        if (p.isNotEmpty() && !HEX64.matches(p)) return null
        return ServiceEndpoint(u, p)
    }

    private companion object {
        val HEX64 = Regex("^[0-9a-f]{64}$")
        val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    }
}

@Serializable
data class DocProfile(val id: String, val name: String, val link: String) {
    override fun toString() = "DocProfile($id, $name)"
}

/** The subscription document: status, end and the locations (profile links). */
@Serializable
data class SubscriptionDocument(
    val version: Int = 1,
    val title: String = "",
    val status: String = SubStatus.ACTIVE,
    val expires: Long = 0,
    val updateHours: Int = 6,
    val support: String = "",
    val announce: String = "",
    val profiles: List<DocProfile> = emptyList(),
    /** Set when the service has moved: where the app should go. */
    val service: ServiceMove? = null,
)

enum class SubscriptionKind { Account, Key }

/** What the app keeps about its subscription. At most one at a time. */
@Serializable
data class SubscriptionState(
    val endpoint: ServiceEndpoint,
    val kind: SubscriptionKind,
    /** Account: this device's token. The password is never kept. */
    val deviceToken: String = "",
    val deviceId: String = "",
    /** Key: the address of the document, with its secret token. */
    val keyUrl: String = "",
    val account: AccountInfo? = null,
    val title: String = "",
    val status: String = SubStatus.ACTIVE,
    val expires: Long = 0,
    val support: String = "",
    val announce: String = "",
    val updateHours: Int = 6,
    /** The platform clock's milliseconds of the last answer from the service; 0 never. (expires is the service's unix seconds.) */
    val lastRefreshed: Long = 0,
    /** Why the last refresh failed, for the card; null when it did not. */
    val lastError: String? = null,
    /** The message the owner wants shown, which the user has already closed. */
    val announceSeen: String = "",
    /** The order the app waits to be paid; null when there is none. */
    val pendingOrder: PendingOrder? = null,
) {
    val active: Boolean get() = status == SubStatus.ACTIVE
    val signedIn: Boolean get() = kind == SubscriptionKind.Key || deviceToken.isNotEmpty()

    /**
     * Whole days left at [now] (unix seconds); null when it never ends or is over, 0 when less than a day is left.
     * An hour of grace: a plan of 3 days just taken reads "3 дня", not "4".
     */
    fun daysLeft(now: Long): Int? =
        if (expires == 0L || expires <= now) null else (((expires - now) + 3_600) / 86_400).toInt()

    override fun toString() = "SubscriptionState($kind, $status)"
}

/**
 * A subscription key: the address of the document and the certificate pin,
 * as `openflux://sub?u=<url>&p=<pin>&n=<name>` or the plain address with
 * `#pin=<hex>` after it. The core's `subscription` package reads the same forms.
 */
@Serializable
data class SubscriptionInvite(val url: String, val pin: String = "", val name: String = "") {
    /** The service this key belongs to: its address up to the path. */
    val endpoint: ServiceEndpoint
        get() {
            val cut = url.indexOf("/sub/")
            return ServiceEndpoint(if (cut > 0) url.substring(0, cut) else url, pin)
        }

    override fun toString() = "SubscriptionInvite(${endpoint.url})"

    companion object {
        private const val PREFIX = "openflux://sub"
        private val HEX = Regex("^[0-9a-f]{64}$")

        /** The cheap check that routes pasted text: a subscription key, not a profile link. */
        fun looksLike(text: String): Boolean {
            val s = text.trim()
            if (s.startsWith(PREFIX)) return true
            return (s.startsWith("https://") || s.startsWith("http://")) && s.substringBefore('#').substringBefore('?').contains("/sub/")
        }

        /** Reads a key, or null when [text] is not one. */
        fun parse(text: String): SubscriptionInvite? {
            val s = text.trim()
            val (rawUrl, rawPin, rawName) = if (s.startsWith(PREFIX)) {
                val query = parseQuery(s.removePrefix(PREFIX).removePrefix("?"))
                Triple(query["u"].orEmpty(), query["p"].orEmpty(), query["n"].orEmpty().trim())
            } else {
                val fragment = parseQuery(s.substringAfter('#', ""))
                Triple(s.substringBefore('#'), fragment["pin"].orEmpty(), "")
            }
            val url = rawUrl.trim()
            val scheme = url.substringBefore("://", "")
            val rest = url.substringAfter("://", "")
            val host = rest.substringBefore('/').substringAfterLast('@').let { if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':') }
            if (scheme != "https" && !(scheme == "http" && (host == "localhost" || host == "127.0.0.1" || host == "[::1]"))) return null
            if (host.isEmpty() || rest.substringBefore('/').contains('@') || !rest.substringBefore('#').contains("/sub/")) return null
            val pin = rawPin.lowercase().removePrefix("sha256:").replace(":", "").replace(" ", "")
            if (pin.isNotEmpty() && !HEX.matches(pin)) return null
            return SubscriptionInvite(url.substringBefore('#'), pin, rawName.take(80))
        }

        private fun parseQuery(q: String): Map<String, String> =
            q.split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to percentDecode(it.substringAfter('=')) }
    }
}

/** application/x-www-form-urlencoded decoding (UTF-8, + is a space). */
internal fun percentDecode(s: String): String {
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '+' -> { bytes += ' '.code.toByte(); i++ }
            c == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).all { it.isHex() } -> {
                bytes += s.substring(i + 1, i + 3).toInt(16).toByte(); i += 3
            }
            else -> { bytes += c.toString().encodeToByteArray().toList(); i++ }
        }
    }
    return bytes.toByteArray().decodeToString()
}

private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** A failed call to the service: [code] is the service's (or the app's) reason, [message] words for people. */
class SubscriptionException(
    val code: String,
    message: String,
    /** With device_limit: the account's devices, to pick one to replace. */
    val devices: List<DeviceInfo> = emptyList(),
) : Exception(message) {
    val unauthorized: Boolean get() = code == "unauthorized"
    val network: Boolean get() = code == "network"

    companion object {
        const val NETWORK = "network"
        const val BAD_PIN = "bad_pin"
        const val BAD_RESPONSE = "bad_response"
    }
}
