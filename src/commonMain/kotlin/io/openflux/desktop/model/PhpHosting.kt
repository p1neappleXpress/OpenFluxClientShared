package io.openflux.desktop.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The mode without a server: the exit is a small PHP program on an ordinary
 * (even free) web host. The wizard puts it there over FTP; the core does every
 * step (package phphost) and answers with a code for each failure, which this
 * file words. See the core's provision/phphost.
 */

/** How to reach the hosting account over FTP. The password lives only in memory, for the wizard's length. */
data class FtpTarget(
    val host: String,
    val port: Int = 21,
    val user: String,
    val password: String,
    /** "auto" (TLS if the server has it, then plain), "none" or "explicit". */
    val tls: String = "auto",
    /** The folder the site is served from; "" lets the core find it. */
    val dir: String = "",
) {
    override fun toString() = "FtpTarget($user@$host:$port)"
}

/** What the core learned about the account (phphost.Probe). */
@Serializable
data class PhpProbe(
    /** "tls", "tls_unverified" or "none": how well the FTP password was protected on the way. */
    val security: String = "",
    val dir: String = "",
    val candidates: List<String> = emptyList(),
    val writable: Boolean = false,
    @SerialName("has_node") val hasNode: Boolean = false,
    val token: String = "",
)

/** What an upload left behind (phphost.Installed). */
@Serializable
data class PhpInstalled(
    val dir: String = "",
    val token: String = "",
    val files: Int = 0,
    val bytes: Long = 0,
    val security: String = "",
    @SerialName("token_reused") val tokenReused: Boolean = false,
)

/** An upload's progress (phphost.Progress). */
@Serializable
data class PhpProgress(
    val phase: String = "",
    val file: String = "",
    val n: Int = 0,
    val of: Int = 0,
    @SerialName("bytes_done") val bytesDone: Long = 0,
    @SerialName("bytes_total") val bytesTotal: Long = 0,
) {
    /** 0..1 over all the bytes. */
    val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/** The node's ping: what the host is and whether it can run the node (phphost.Status). */
@Serializable
data class PhpStatus(
    @SerialName("phpbox") val version: String = "",
    val carrier: String = "",
    val php: String = "",
    val missing: List<String> = emptyList(),
    @SerialName("state_dir") val stateDir: Boolean = false,
    val parser: Boolean = false,
)

/** Whether the node is running (phphost.NodeState). */
@Serializable
data class PhpNodeState(
    val running: Boolean = false,
    val draining: Int = 0,
    val chain: Boolean = false,
    val stopping: Boolean = false,
)

/** A cups.online room made for the node: its uuid and the address clients and the node take. */
@Serializable
data class PhpRoom(val room: String = "", val url: String = "")

/**
 * Where a profile's node lives, to start it again (the core's `start` is
 * idempotent): the site and the token that guards its pages. The token is a
 * secret like a key: it never goes into a shared link.
 */
@Serializable
data class PhpNodeRef(val siteUrl: String, val token: String) {
    override fun toString() = "PhpNodeRef($siteUrl)"
}

/**
 * A failed hosting step. [code] is the core's reason (phphost.Code*), [param]
 * the value it is about; the message is the app's words for it.
 */
class PhpHostingException(val code: String, val param: String = "", val detail: String = "") :
    Exception(PhpMessages.text(code, param, detail)) {
    /** The account was reached but the web folder is unclear: the user picks from these. */
    val candidates: List<String> get() = if (code == "ftp_no_webroot") param.split(',').filter { it.isNotBlank() } else emptyList()
}

object PhpMessages {
    /** Every reason the core reports, for the tests that keep this list whole. */
    val codes = listOf(
        "bad_params", "ftp_connect", "ftp_login", "ftp_tls", "ftp_no_webroot", "ftp_dir_missing", "ftp_not_writable",
        "ftp_upload", "site_unreachable", "site_antibot", "site_not_phpbox", "site_token", "php_missing", "node_not_started",
    )

    fun text(code: String, param: String = "", detail: String = ""): String = when (code) {
        "bad_params" -> if (param == "host/user/password") "Укажите адрес FTP-сервера, логин и пароль" else "Проверьте введённые данные"
        "ftp_connect" -> "Не удалось подключиться к FTP-серверу${if (param.isNotBlank()) " $param" else ""}: проверьте адрес и порт, " +
            "что хостинг пускает FTP из вашей сети и что это FTP или FTPS, а не SFTP (SSH)"
        "ftp_login" -> "FTP не принял логин или пароль. Пароль FTP на хостинге часто не совпадает с паролем от личного кабинета"
        "ftp_tls" -> "Не получилось установить защищённое соединение с FTP-сервером"
        "ftp_no_webroot" -> "Не понятно, какая папка на хостинге отдаётся как сайт. Выберите её из списка"
        "ftp_dir_missing" -> "Папки «$param» на хостинге нет"
        "ftp_not_writable" -> "В папку сайта${if (param.isNotBlank()) " («$param»)" else ""} нельзя записывать: проверьте права или выберите другую папку"
        "ftp_upload" -> "Файл $param не загрузился: хостинг оборвал передачу или места не хватило. Повторите"
        "site_unreachable" -> "Сайт${if (param.isNotBlank()) " $param" else ""} не отвечает: проверьте адрес. " +
            "У только что созданного домена бывает несколько минут, пока он заработает"
        "site_antibot" -> "Хостинг ставит перед сайтом проверку браузером, которую приложению не пройти. " +
            "Откройте адрес ноды в браузере и повторите"
        "site_not_phpbox" -> "Сайт отвечает, но это не нода: проверьте адрес сайта, папку, в которую залиты файлы, и что на хостинге включён PHP"
        "site_token" -> "Нода не приняла ключ доступа: на хостинге лежат файлы от другой установки. Установите заново"
        "php_missing" -> "На этом хостинге отключены PHP-функции, без которых нода не работает: $param"
        "node_not_started" -> "Нода не запустилась за отведённое время. Откройте адрес ноды в браузере: там виден её журнал"
        else -> if (detail.isNotBlank()) "Ошибка установки на хостинг: $detail" else "Ошибка установки на хостинг"
    }

    /** What the user should know about how the FTP password travelled. */
    fun security(level: String): String? = when (level) {
        "tls" -> null
        "tls_unverified" -> "Пароль FTP передан в шифрованном соединении, но сертификат хостинга не подтверждён (для бесплатных хостингов это обычно)."
        "none" -> "Этот хостинг принимает FTP только без шифрования: пароль FTP прошёл по сети открытым текстом. После установки смените его."
        else -> null
    }
}

/** What the hosting form needs to decide before it calls the core. */
object PhpHosts {
    /** A known free hosting family and what to tell the user about its FTP. */
    data class Preset(val title: String, val ftpHost: String, val userHint: String, val note: String)

    /**
     * Shared FTP servers that many accounts use (the address is the same for everyone), as a
     * shortcut for the form. Any other hosting works the same way: its FTP address comes from its
     * own panel.
     */
    val presets = listOf(
        Preset(
            "InfinityFree (ftpupload.net)", "ftpupload.net", "if0_…",
            "FTP-данные в разделе «FTP Details» аккаунта; пароль FTP — тот, что вы задали для аккаунта хостинга.",
        ),
    )

    /** The preset for [host], if it is one of the known ones. */
    fun presetFor(host: String): Preset? = presets.firstOrNull { it.ftpHost.equals(host.trim(), ignoreCase = true) }

    private val HOST = Regex("""^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$""")

    /** An FTP host name or address, null if [input] is not one. */
    fun ftpHost(input: String): String? = input.trim().removePrefix("ftp://").trimEnd('/').takeIf { it.isNotEmpty() && HOST.matches(it) }

    /**
     * The address of the site as the wizard uses it: a scheme, the host, no path or query.
     * null when [input] cannot be one (no dot in the host, spaces, ...).
     */
    fun siteUrl(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (!s.contains("://")) s = "https://$s"
        val rest = s.substringAfter("://")
        val scheme = s.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        if (!host.contains('.') || !HOST.matches(host.substringBefore(':'))) return null
        return "$scheme://$host"
    }

    /** The FTP form's problems in the user's words, empty when it can be sent. */
    fun ftpProblems(host: String, port: String, user: String, password: String): List<String> = buildList {
        if (ftpHost(host) == null) add("Укажите адрес FTP-сервера, например ftpupload.net")
        if (port.isNotBlank() && port.trim().toIntOrNull()?.let { it in 1..65535 } != true) add("Порт FTP — число от 1 до 65535 (обычно 21)")
        if (user.isBlank()) add("Укажите логин FTP")
        if (password.isEmpty()) add("Укажите пароль FTP")
    }

    private val CUPS_ROOM = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")
    private val CUPS_URL = Regex("""^https://interview\.cups\.online/live-coding/\?room=[0-9a-fA-F-]{36}$""")

    /** A cups.online room as the exit and the clients take it: its address, from a bare uuid or an address; null if it is neither. */
    fun cupsRoom(input: String): String? {
        val s = input.trim()
        return when {
            CUPS_ROOM.matches(s) -> "https://interview.cups.online/live-coding/?room=${s.lowercase()}"
            CUPS_URL.matches(s) -> s
            else -> null
        }
    }

    /** The carriers the PHP node has a port for, as the wizard offers them. */
    val carriers = listOf(TransportType.CUPSONLINE, TransportType.MAILRU)
}
