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
import io.openflux.desktop.ui.LocalBrowserViews
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.ui.components.AppDialog
import io.openflux.desktop.ui.components.Banner
import io.openflux.desktop.ui.components.TextAction
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.components.fillUpTo
import io.openflux.desktop.ui.components.windowSize
import io.openflux.desktop.ui.theme.AppTheme

/**
 * A Yandex check, or a script transport's own setup page, the core cannot
 * pass/fill by itself. The page opens in the built-in browser (through the
 * node's address when the node asked). For a real check, "Готово" hands the
 * resulting cookies to the core; for a script's own page ([CaptchaPrompt.own]:
 * inline html, or the script's own server on 127.0.0.1) there is no such
 * button - the page submits itself (window.openfluxSubmit, see the core's
 * docs/scripted-transports.md), and this dialog just hosts it.
 */
@Composable
fun CaptchaDialog() {
    val connection = LocalAppContainer.current.connection
    val prompt by connection.captcha.collectAsState()
    val page by connection.captchaPage.collectAsState()
    val browsers = LocalBrowserViews.current
    val current = prompt ?: return
    val isSetupPage = current.own
    // The check page gets what the window can spare, within reason.
    val window = windowSize()
    val pageHeight = (window.height - 330.dp).coerceIn(180.dp, 640.dp)
    AppDialog(
        modifier = Modifier.fillUpTo(if (window.width >= 1400.dp) 960.dp else 760.dp),
        title = when {
            // For a script's own page the reason is the title its author wrote.
            isSetupPage -> current.reason.ifBlank {
                if (current.transport.isNotBlank()) "Настройка «${current.transport}»" else "Транспорт просит настройку"
            }
            current.remote -> "Нода просит пройти проверку Яндекса"
            else -> "Яндекс просит пройти проверку"
        },
        onDismiss = connection::dismissCaptcha,
        primary = if (isSetupPage) null else if (current.busy) "Передаю…" else "Готово, проверка пройдена",
        onPrimary = connection::submitCaptcha,
        primaryEnabled = !isSetupPage && !current.busy && page != null,
        secondary = "Позже",
    ) {
        Text(
            when {
                isSetupPage -> "Заполните страницу настройки ниже и нажмите её собственную кнопку: OpenFlux передаст данные транспорту сам."
                current.remote -> "Страница открыта с адреса ноды. Пройдите проверку, затем нажмите «Готово»: " +
                    "cookies уйдут ноде, и канал через Яндекс поднимется."
                else -> "Пройдите проверку, затем нажмите «Готово». Если страница откроется без проверки, OpenFlux передаст cookies сам."
            },
            style = AppTheme.typography.body,
            color = AppTheme.colors.text,
        )
        // The error first: the page below may take the rest of a short window.
        if (current.error.isNotBlank()) {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Banner(current.error, Tone.Danger, icon = Icons.Rounded.ErrorOutline)
        }
        Spacer(Modifier.height(AppTheme.spacing.m))
        Box(
            Modifier.fillMaxWidth().height(pageHeight).clip(AppTheme.shapes.card)
                .border(1.dp, AppTheme.colors.border, AppTheme.shapes.card),
            contentAlignment = Alignment.Center,
        ) {
            val shown = page
            if (shown != null) browsers.Page(shown, Modifier.fillMaxSize())
            else Text(current.progress.ifEmpty { "Открываю страницу проверки…" }, style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
        }
        Spacer(Modifier.height(AppTheme.spacing.s))
        TextAction(
            if (isSetupPage) "Открыть страницу настройки заново" else "Открыть страницу проверки заново",
            connection::openCaptcha,
            enabled = !current.busy,
        )
    }
}
