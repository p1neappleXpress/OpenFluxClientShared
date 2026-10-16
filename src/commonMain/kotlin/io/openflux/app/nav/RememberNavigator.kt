package io.openflux.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.PlatformKind
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Is the object this screen shows still there? A screen of a deleted profile or a forgotten server is not restored. */
fun aliveIn(container: AppContainer): (Dest) -> Boolean {
    val profiles = container.profiles.profiles.value.map { it.id }.toSet()
    val servers = container.settings.settings.value.knownServers.map { "${it.host}:${it.port}:${it.user}" }.toSet()
    return { dest ->
        (dest.profileId == null || dest.profileId in profiles) && (dest.serverId == null || dest.serverId in servers)
    }
}

/**
 * The navigator of the app's life (spec 4.3): it survives Android killing the process (saved state), and on the
 * desktop the next start opens where the person left (AppSettings.navState). Screens of profiles or servers that
 * are gone are dropped, at the restore and whenever they go.
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberAppNavigator(container: AppContainer): AppNavigator {
    val desktop = container.platform.kind == PlatformKind.Desktop
    val navigator = rememberSaveable(
        saver = Saver<AppNavigator, String>(
            save = { it.snapshot().encode() },
            restore = { AppNavigator.restore(NavState.decode(it), aliveIn(container)) },
        ),
    ) {
        if (desktop) AppNavigator.restore(NavState.decode(container.settings.settings.value.navState), aliveIn(container))
        else AppNavigator()
    }
    LaunchedEffect(navigator) {
        if (desktop) launch {
            snapshotFlow { navigator.snapshot() }.distinctUntilChanged().debounce(500).collect { state ->
                val text = state.encode()
                container.settings.update { if (it.navState == text) it else it.copy(navState = text) }
            }
        }
        launch {
            container.profiles.profiles.collect { list ->
                val ids = list.map { it.id }.toSet()
                navigator.forget { it.profileId != null && it.profileId !in ids }
            }
        }
        container.settings.settings.collect { s ->
            val servers = s.knownServers.map { "${it.host}:${it.port}:${it.user}" }.toSet()
            navigator.forget { it.serverId != null && it.serverId !in servers }
        }
    }
    return navigator
}
