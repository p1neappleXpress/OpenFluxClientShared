package io.openflux.app.servers

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.desktop.model.KnownServer
import io.openflux.desktop.model.NodeServers
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.service.PlatformServices

/**
 * The way into a server (spec 5.1, step 1): address (with ":port" when it is not 22), login, password or private
 * key; under "Дополнительно" the sudo password. Once [link] holds the way in,
 * the form forgets the secrets.
 */
@Stable
class LoginForm(private val link: ServerLink) {
    var host by mutableStateOf("")
    var user by mutableStateOf("root")
    var useKey by mutableStateOf(false)
    var password by mutableStateOf("")
    var privateKey by mutableStateOf("")
    var passphrase by mutableStateOf("")
    var sudo by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    fun prefill(server: KnownServer) {
        host = if (server.port == 22) server.host else if (':' in server.host) "[${server.host}]:${server.port}" else "${server.host}:${server.port}"
        user = server.user
        error = null
    }

    /** Connects; the server's id, or null with [error] set (or the key prompt open on [link]). */
    suspend fun connect(): String? {
        val (h, port, valid) = parse(host, "22")
        error = when {
            h.isEmpty() -> "Укажите адрес сервера"
            !valid -> "Порт — число от 1 до 65535"
            user.isBlank() -> "Укажите логин"
            useKey && privateKey.isBlank() -> "Вставьте закрытый ключ или выберите файл"
            !useKey && password.isEmpty() -> "Введите пароль"
            else -> null
        }
        if (error != null) return null
        val target = SshTarget(
            host = h, port = port, user = user.trim(),
            password = if (useKey) "" else password,
            privateKey = if (useKey) privateKey.trim() else "",
            passphrase = if (useKey) passphrase else "",
        )
        return attempt { link.connect(target) }
    }

    /** Trusts the key the server showed and connects; the server's id, or null with [error]. */
    suspend fun trust(): String? = attempt { link.trust() }

    /** Reads a private key file the person picks. */
    suspend fun readKeyFile(platform: PlatformServices) {
        val path = platform.pickFile("Закрытый ключ SSH", emptyList()) ?: return
        val text = platform.readTextFile(path)
        if (text == null) error = "Не удалось прочитать ключ: файл больше 64 КБ или недоступен"
        else { privateKey = text.trim(); error = null }
    }

    private suspend fun attempt(block: suspend () -> String): String? {
        busy = true
        error = null
        try {
            val id = block()
            if (sudo.isNotEmpty()) link.setSudo(id, sudo)
            password = ""
            privateKey = ""
            passphrase = ""
            sudo = ""
            return id
        } catch (e: NodeWizardException) {
            // A key to confirm is a question, not a failure: the dialog asks it.
            if (link.hostKeyPrompt == null) error = e.message ?: "Не удалось подключиться"
            return null
        } finally {
            busy = false
        }
    }

    companion object {
        /** "host", "host:port", "[v6]:port" or a bare IPv6 address: host, port, whether the port is valid. */
        fun parse(text: String, defaultPort: String): Triple<String, Int, Boolean> {
            val t = text.trim()
            val (h, p) = when {
                t.startsWith("[") && "]:" in t -> t.substring(1, t.indexOf("]:")) to t.substringAfter("]:")
                t.startsWith("[") && t.endsWith("]") -> t.substring(1, t.length - 1) to defaultPort
                t.count { it == ':' } == 1 -> t.substringBefore(':') to t.substringAfter(':')
                else -> t to defaultPort
            }
            val port = NodeServers.port(p)
            return Triple(h.trim(), port ?: 22, port != null)
        }
    }
}
