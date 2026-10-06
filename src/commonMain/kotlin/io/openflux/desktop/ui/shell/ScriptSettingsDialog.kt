package io.openflux.desktop.ui.shell

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.ui.LocalBrowserViews
import io.openflux.desktop.ui.components.AppDialog
import io.openflux.desktop.ui.components.Banner
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.components.fillUpTo
import io.openflux.desktop.ui.components.windowSize
import io.openflux.desktop.ui.theme.AppTheme

/**
 * "Настройки" of an installed script transport: the page the core builds from
 * what the script declares (or the one the script brings), in the built-in
 * browser. The page saves itself (window.openfluxSubmit); this dialog only
 * hosts it, so there is no "Готово" here - the page has its own Save.
 */
@Composable
fun ScriptSettingsDialog() {
    val service = LocalAppContainer.current.scriptSettings
    val state by service.state.collectAsState()
    val browsers = LocalBrowserViews.current
    val current = state ?: return
    val window = windowSize()
    val pageHeight = (window.height - 300.dp).coerceIn(220.dp, 720.dp)
    AppDialog(
        modifier = Modifier.fillUpTo(if (window.width >= 1400.dp) 860.dp else 700.dp),
        title = "Настройки «${current.title}»",
        onDismiss = service::close,
        primary = null,
        onPrimary = {},
        secondary = "Закрыть",
        enterSubmits = false,
    ) {
        Text(
            "Измените настройки и нажмите «Сохранить» на странице. Они вступят в силу при следующем подключении.",
            style = AppTheme.typography.body,
            color = AppTheme.colors.text,
        )
        if (current.error.isNotBlank()) {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Banner(current.error, Tone.Danger, icon = Icons.Rounded.ErrorOutline)
        }
        // No empty frame when there is nothing to show but an error.
        if (current.page != null || current.error.isBlank()) {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Box(
                Modifier.fillMaxWidth().height(pageHeight).clip(AppTheme.shapes.card)
                    .border(1.dp, AppTheme.colors.border, AppTheme.shapes.card),
                contentAlignment = Alignment.Center,
            ) {
                val shown = current.page
                if (shown != null) browsers.Page(shown, Modifier.fillMaxSize())
                else Text(current.progress.ifEmpty { "Открываю настройки…" }, style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
            }
        }
    }
}
