package io.openflux.app.servers

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A node's state on its server, in a word. */
fun nodeStateText(state: String): String = when (state) {
    "active" -> "работает"
    "activating", "reloading" -> "запускается"
    "inactive", "deactivating" -> "остановлена"
    "failed" -> "сбой"
    "" -> "состояние неизвестно"
    else -> state
}

/** The person's own nodes: made by the node wizard, or on a web hosting. */
val Profile.isOwnNode: Boolean get() = source == ProfileSource.Node || phpNode != null

/**
 * One server's screen (spec 5.2): the way in when the link has none, the nodes on it, what the server said
 * about itself. Reading needs the sudo password when the server asks for one.
 */
@Stable
class ServerScreenModel(private val container: AppContainer, private val link: ServerLink, val serverId: String, private val scope: CoroutineScope) {
    val login = LoginForm(link).also { f ->
        container.settings.settings.value.knownServers.firstOrNull { serverIdOf(it) == serverId }?.let(f::prefill)
    }
    var channels by mutableStateOf<List<InstalledChannel>?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var sudoInput by mutableStateOf("")
    private var job: Job? = null

    val hasAccess: Boolean get() = link.hasAccess(serverId)
    val needsSudo: Boolean get() = link.needsSudo(serverId) && link.sudo(serverId).isEmpty()
    val probe get() = link.probe(serverId)
    val host: String get() = serverId.substringBefore(':').let { h ->
        container.settings.settings.value.knownServers.firstOrNull { serverIdOf(it) == serverId }?.host ?: h
    }

    fun load() = run("Читаю ноды на сервере…") {
        if (sudoInput.isNotEmpty()) link.setSudo(serverId, sudoInput)
        channels = link.channels(serverId)
    }

    fun refresh() = run("Читаю сервер…") {
        link.refresh(serverId)
        channels = link.channels(serverId)
    }

    fun connect() = run("Подключаюсь к серверу…") {
        if (login.connect() != null) channels = link.channels(serverId)
    }

    fun trust() = run("Подключаюсь к серверу…") {
        if (login.trust() != null) channels = link.channels(serverId)
    }

    fun closeAccess() {
        link.closeAccess(serverId)
        channels = null
    }

    fun forget() = link.forget(serverId)

    /** The name this device knows a node by: its profile's, if it has one. */
    fun localName(info: InstalledChannel): String? {
        val dials = setOf("$host:${info.port}", "[$host]:${info.port}")
        return container.profiles.profiles.value.firstOrNull { p ->
            (info.key.isNotEmpty() && p.secret == info.key) ||
                (p.secret.isNotBlank() && p.carriers.any { it.type == TransportType.DIRECT && it.value.trim() in dials })
        }?.name
    }

    private fun run(message: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        busy = message
        error = null
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NodeWizardException) {
                error = if (e.sudo && e.message != "Введите пароль sudo") "sudo не принял пароль" else e.message
            } catch (e: Exception) {
                error = e.message ?: "Не получилось"
            } finally {
                busy = null
            }
        }
    }
}
