package io.openflux.app.shell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.openflux.app.components.LocalToaster
import io.openflux.app.components.ToastHost
import io.openflux.app.components.Toaster
import io.openflux.app.nav.AppNavHost
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.nav.rememberAppNavigator
import io.openflux.app.servers.ServerLink
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.ThemeMode
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.service.PlatformKind
import io.openflux.app.platform.BrowserViews
import io.openflux.app.platform.LocalBrowserViews
import io.openflux.app.platform.LocalScrollbars
import io.openflux.app.platform.LocalShortcuts
import io.openflux.app.platform.LocalTouchUi
import io.openflux.app.platform.NoBrowserViews
import io.openflux.app.platform.Scrollbars
import io.openflux.app.platform.Shortcuts

/** Where the person is in the new interface; screens open others through it. */
val LocalNavigator = staticCompositionLocalOf<AppNavigator> { error("AppNavigator is not provided") }

/**
 * The new interface (shared/docs/specs/2026-10-12-ui-redesign-design.md), with the same parameters as the old
 * OpenFluxApp so an app switches with one line.
 */
@Composable
fun OpenFluxApp(container: AppContainer, scrollbars: Scrollbars, shortcuts: Shortcuts, browsers: BrowserViews = NoBrowserViews) =
    OpenFluxApp(container, scrollbars, shortcuts, browsers, link = null)

/** [link]: a link already connected to servers (tests and the screen catalogue); null makes the app's own. */
@Composable
internal fun OpenFluxApp(container: AppContainer, scrollbars: Scrollbars, shortcuts: Shortcuts, browsers: BrowserViews, link: ServerLink?) {
    val settings by container.settings.settings.collectAsState()
    // The app's own updates; the script transports' updates only with the experimental features on.
    LaunchedEffect(Unit) { container.updates.start() }
    LaunchedEffect(settings.experimental) { if (settings.experimental) runCatching { container.scriptUpdater.autoCheck() } }
    val dark = when (settings.theme) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val touch = container.platform.kind == PlatformKind.Android
    val toaster = remember { Toaster() }
    val navigator = rememberAppNavigator(container)
    val serverLink = remember(container) { link ?: ServerLink(container) }
    DisposableEffect(serverLink) { onDispose { serverLink.close() } }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    // A key or a profile link opened from outside (a scanned code, a chat) goes to "Ключ, ссылка или файл".
    LaunchedEffect(container) {
        container.incomingLink.collect { link ->
            if (link != null) {
                container.incomingLink.value = null
                container.pendingKey.value = link
                navigator.open(Dest.AddKey)
            }
        }
    }
    AppTheme(dark, touch) {
        CompositionLocalProvider(
            LocalTouchUi provides touch,
            LocalAppContainer provides container,
            LocalScrollbars provides scrollbars,
            LocalShortcuts provides shortcuts,
            LocalBrowserViews provides browsers,
            LocalToaster provides toaster,
            LocalNavigator provides navigator,
            LocalServerLink provides serverLink,
            LocalOpenAdd provides { addOpen = true },
        ) {
            Box(Modifier.fillMaxSize()) {
                Shell(navigator, onAdd = { addOpen = true }) { wide ->
                    androidx.compose.foundation.layout.Column {
                        UpdateLine()
                        AppNavHost(navigator, wide, androidx.compose.ui.Modifier.weight(1f), nothingChosen = { NothingChosen(it) }) { appEntry(it, navigator) }
                    }
                }
                // Above the bottom bar on a phone.
                ToastHost(toaster, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 88.dp))
            }
            CaptchaOverlay()
            SignInOverlay()
            ScriptSettingsOverlay()
            if (addOpen) {
                AddMenu(
                    cameraScan = container.platform.cameraScanSupported,
                    onChoice = { choice ->
                        addOpen = false
                        when (choice) {
                            AddChoice.Server -> navigator.open(Dest.AddServer)
                            AddChoice.Hosting -> navigator.open(Dest.NewHosting)
                            AddChoice.Manual -> navigator.open(Dest.ProfileEdit())
                            AddChoice.Key -> navigator.open(Dest.AddKey)
                            AddChoice.Scan -> navigator.open(Dest.Scan)
                            AddChoice.Account -> {
                                val signedIn = container.bot.state.value.session != null
                                navigator.open(if (signedIn) Dest.Account else Dest.Auth)
                            }
                        }
                    },
                    onDismiss = { addOpen = false },
                )
            }
        }
    }
}
