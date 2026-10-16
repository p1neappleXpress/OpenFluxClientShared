package io.openflux.app.servers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Proves a node works the way a person would: connects through it as a client and asks where the traffic
 * leaves, which must be one of the server's addresses. On Android the app is outside its own VPN and cannot
 * ask; a connection that came up is the proof there. Then it waits, for the rest of the time, for the main
 * transport to carry the traffic (a Yandex node may first need its own check, which the captcha dialog shows);
 * [stopWaiting] keeps what was proven.
 *
 * A failure disconnects the test connection and is a [NodeWizardException] with the reason in plain words.
 */
class NodeVerifier(
    private val container: AppContainer,
    private val timeoutMs: Long = 150_000,
    private val pollMs: Long = 400,
    private val retryMs: Long = 4_000,
    private val now: () -> Long = { container.platform.now() },
) {
    data class Result(val ip: String?, val primaryUp: Boolean)

    /** What it is doing now, for one line on the screen; null when idle. */
    var stage by mutableStateOf<String?>(null)
        private set
    private var stop = false

    fun stopWaiting() {
        stop = true
    }

    suspend fun verify(candidate: Profile, serverAddresses: Set<String>, primary: TransportType?): Result {
        val connection = container.connection
        stop = false
        stage = "Подключаюсь к ноде…"
        try {
            val before = connection.state.value
            connection.connect(candidate, ConnectionMode.Client)
            val deadline = now() + timeoutMs
            var started = false
            var lastProblem = ""
            var ip: String? = null
            var proven = false
            while (!proven) {
                if (stop) throw NodeWizardException("Проверка остановлена: нода пока не ответила")
                if (now() > deadline) {
                    throw NodeWizardException(
                        if (lastProblem.isNotEmpty()) "Канал поднялся, но запрос через него не прошёл: $lastProblem"
                        else "Нода не ответила: проверьте, что сервер доступен, а документ и ключ совпадают",
                    )
                }
                val state = connection.state.value
                // connect() works in the background: until the state changes, the previous attempt still shows.
                if (state !== before && state.profile?.id == candidate.id) started = true
                else if (started) throw NodeWizardException("Проверку прервало отключение или другое подключение")
                if (started && state is ConnectionState.Failed) throw NodeWizardException(state.message)
                if (started && state is ConnectionState.Connected) {
                    stage = when {
                        connection.captcha.value != null -> "Яндекс просит пройти проверку: пройдите её в открывшемся окне"
                        lastProblem.isNotEmpty() -> "Канал поднялся, пробую открыть сайт через него ещё раз…"
                        else -> "Канал поднялся, открываю сайт через него…"
                    }
                    when (val address = connection.exitAddress.value) {
                        is ExitAddress.Known -> {
                            if (serverAddresses.isNotEmpty() && address.ip !in serverAddresses) {
                                throw NodeWizardException("Запрос вышел с адреса ${address.ip}, а не с адреса сервера")
                            }
                            ip = address.ip
                            proven = true
                        }
                        is ExitAddress.NotCheckable -> proven = true
                        is ExitAddress.Unavailable -> {
                            lastProblem = address.reason
                            delay(retryMs)
                            connection.refreshExitAddress()
                        }
                        else -> Unit
                    }
                }
                if (!proven) delay(pollMs)
            }
            // Traffic may have gone through the direct backup: give the main transport the rest of the time.
            fun primaryLive() = primary == null || connection.traffic.value.activeTransport == primary.cliName
            if (primary != null) stage = "Жду канал через ${primary.shortLabel}…"
            while (!stop && now() < deadline && !primaryLive()) {
                if (connection.state.value.profile?.id != candidate.id) break
                delay(pollMs)
            }
            return Result(ip, primaryLive())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val state = connection.state.value
            if (state.profile?.id == candidate.id && state.isActive) connection.disconnect()
            throw e as? NodeWizardException ?: NodeWizardException(e.message ?: "Проверка не прошла")
        } finally {
            stage = null
        }
    }
}
