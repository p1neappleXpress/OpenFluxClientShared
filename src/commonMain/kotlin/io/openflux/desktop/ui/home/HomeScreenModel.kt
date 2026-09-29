package io.openflux.desktop.ui.home

import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.model.AccountKind
import cafe.adriel.voyager.core.model.ScreenModel
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.AppContainer

/** Home actions; the screen reads the container's flows directly. */
class HomeScreenModel(private val container: AppContainer) : ScreenModel {
    val connection = container.connection
    val settings = container.settings
    val profiles = container.profiles

    /** The profile the connect button uses: the saved choice, else the first. */
    fun selectedProfile(list: List<Profile>, selectedId: String?): Profile? =
        list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()

    fun select(profile: Profile) {
        settings.update { it.copy(selectedProfileId = profile.id) }
        if (connection.state.value.isActive) connection.connect(profile)
    }

    fun toggle(profile: Profile?) {
        if (connection.state.value.isActive) {
            connection.disconnect()
        } else if (profile != null) {
            settings.update { it.copy(selectedProfileId = profile.id) }
            connection.connect(profile)
        }
    }

    fun setMode(mode: ConnectionMode) = settings.update { it.copy(mode = mode) }

    fun setSystemProxy(enabled: Boolean) = settings.update { it.copy(systemProxy = enabled) }

    /** Switching the full tunnel restarts a running connection in the new mode. */
    fun setFullTunnel(enabled: Boolean) {
        settings.update { it.copy(fullTunnel = enabled) }
        val state = connection.state.value
        if (state.isActive) state.profile?.let(connection::connect)
    }

    fun restartElevated(): Boolean = container.platform.restartElevated()

    val accounts = container.accounts

    /** The profile's services whose saved sign-in stopped working. */
    fun accountAlerts(profile: Profile, status: Map<AccountKind, AuthStatus>): List<Pair<AccountKind, AuthStatus>> =
        profile.carriers.mapNotNull { AccountKind.of(it.type) }.distinct()
            .mapNotNull { kind -> status[kind]?.takeIf { it is AuthStatus.Expired || it is AuthStatus.NeedsCheck }?.let { kind to it } }

    /** Services of the profile whose sign-in could go to its exit now, by hand. */
    fun pushable(profile: Profile, state: ConnectionState): List<AccountKind> {
        if (state !is ConnectionState.Connected || state.mode != ConnectionMode.Client || !profile.session) return emptyList()
        return profile.carriers.mapNotNull { AccountKind.of(it.type) }.distinct().filter { it.opensSignedIn && accounts.validSession(it) != null }
    }

    /** Signs in again; a connected own node gets the new sign-in by itself. */
    fun relogin(kind: AccountKind, scope: CoroutineScope, onResult: (String, Boolean) -> Unit) {
        scope.launch {
            try {
                accounts.signIn(kind)
                onResult("Вход в ${kind.label} обновлён", true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e.message ?: "Вход не выполнен", false)
            }
        }
    }

    fun pushToExit(kind: AccountKind, scope: CoroutineScope, onResult: (String, Boolean) -> Unit) {
        scope.launch {
            try {
                val n = connection.pushAccountToExit(kind)
                onResult("Вход ${kind.label} передан ноде ($n транспорт.)", true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e.message ?: "Не удалось передать вход", false)
            }
        }
    }

    fun copy(text: String) = container.platform.setClipboardText(text)

    fun qr(text: String) = container.platform.qrMatrix(text)
}
