package io.openflux.desktop.ui.accounts

import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.LocalTouchUi
import io.openflux.desktop.ui.BrowserPage
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.ui.LocalBrowserViews
import io.openflux.desktop.ui.components.AppDialog
import io.openflux.desktop.ui.components.fillUpTo
import io.openflux.desktop.ui.components.windowSize
import io.openflux.desktop.ui.theme.AppTheme

/**
 * The service's own page in the built-in browser while the user signs in
 * or a document is being created. Closing it cancels.
 */
@Composable
fun SignInDialog() {
    val accounts = LocalAppContainer.current.accounts
    val kind by accounts.signingIn.collectAsState()
    val page by accounts.page.collectAsState()
    val status by accounts.status.collectAsState()
    val current = kind ?: return
    val stepText = (status[current] as? AuthStatus.Busy)?.step.orEmpty()
    if (LocalTouchUi.current) {
        FullScreenSignIn(current.label, stepText, page, accounts::cancel)
        return
    }
    val window = windowSize()
    val pageHeight = (window.height - 300.dp).coerceIn(220.dp, 680.dp)
    val step = stepText
    AppDialog(
        modifier = Modifier.fillUpTo(if (window.width >= 1400.dp) 960.dp else 760.dp),
        title = "Вход в ${current.label}",
        onDismiss = accounts::cancel,
        secondary = "Отмена",
        enterSubmits = false,
    ) {
        Text(
            "Войдите в свой аккаунт. OpenFlux сохранит только сессию — на этом устройстве и на ваших нодах; " +
                "пароль не сохраняется. Окно закроется само.",
            style = AppTheme.typography.body,
            color = AppTheme.colors.text,
        )
        if (step.isNotEmpty()) {
            Spacer(Modifier.height(AppTheme.spacing.s))
            Text(step, style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary)
        }
        Spacer(Modifier.height(AppTheme.spacing.m))
        Box(
            Modifier.fillMaxWidth().height(pageHeight).clip(AppTheme.shapes.card)
                .border(1.dp, AppTheme.colors.border, AppTheme.shapes.card),
            contentAlignment = Alignment.Center,
        ) {
            val shown = page
            if (shown != null) LocalBrowserViews.current.Page(shown, Modifier.fillMaxSize())
            else Text(step.ifEmpty { "Открываю страницу входа…" }, style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
        }
    }
}

/**
 * On a phone the page gets the whole screen at a steady size: inside a
 * dialog's scrolling body the keyboard kept resizing it, and the sign-in
 * page closed its own menus (another way to get the code) as it did.
 */
@Composable
private fun FullScreenSignIn(label: String, step: String, page: BrowserPage?, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(AppTheme.colors.surface)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Вход в $label", style = AppTheme.typography.sectionTitle, color = AppTheme.colors.text)
                    Text(
                        step.ifEmpty { "OpenFlux сохранит только сессию; пароль не сохраняется" },
                        style = AppTheme.typography.caption,
                        color = AppTheme.colors.textSecondary,
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.width(AppTheme.spacing.m))
                AppButton("Отмена", onClose, style = ButtonStyle.Secondary)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.border))
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (page != null) LocalBrowserViews.current.Page(page, Modifier.fillMaxSize())
                else Text("Открываю страницу входа…", style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
            }
        }
    }
}
