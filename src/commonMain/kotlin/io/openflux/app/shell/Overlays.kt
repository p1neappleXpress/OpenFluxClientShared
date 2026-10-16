package io.openflux.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.openflux.app.components.Banner
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.TextAction
import io.openflux.app.components.Tone
import io.openflux.app.nav.Dest
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.model.UpdateState
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.app.platform.BrowserPage
import io.openflux.app.platform.LocalBrowserViews
import io.openflux.app.platform.LocalTouchUi

/**
 * A page of the built-in browser over the app: full screen on a phone (a dialog's body would resize under the
 * keyboard and close the page's own menus), a large dialog on a desktop. [actions] go under the title.
 */
@Composable
private fun BrowserOverlay(
    title: String,
    note: String?,
    page: BrowserPage?,
    waiting: String,
    onClose: () -> Unit,
    closeLabel: String,
    error: String? = null,
    actions: @Composable () -> Unit = {},
) {
    val touch = LocalTouchUi.current
    val c = AppTheme.colors
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val frame = if (touch) Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            else Modifier.widthIn(max = 960.dp).fillMaxWidth(0.9f).height((maxHeight * 0.88f).coerceAtMost(860.dp)).clip(RoundedCornerShape(AppTheme.shapes.large))
            Surface(frame, color = c.surface, contentColor = c.text) {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), style = AppTheme.type.title, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextAction(closeLabel, onClose, color = c.textSecondary)
                        }
                        note?.let { Text(it, style = AppTheme.type.bodySmall, color = c.textSecondary) }
                        error?.let { Banner(it, Tone.Danger) }
                        actions()
                    }
                    Box(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (touch) 0.dp else AppTheme.spacing.l)
                            .padding(bottom = if (touch) 0.dp else AppTheme.spacing.l)
                            .border(1.dp, c.border),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (page != null) LocalBrowserViews.current.Page(page, Modifier.fillMaxSize())
                        else Text(waiting, style = AppTheme.type.body, color = c.textSecondary)
                    }
                }
            }
        }
    }
}

/** A Yandex check (or a script transport's own setup page) the core cannot pass by itself. */
@Composable
fun CaptchaOverlay() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val connection = container.connection
    val prompt by connection.captcha.collectAsState()
    val page by connection.captchaPage.collectAsState()
    val sessions by container.accounts.sessions.collectAsState()
    val current = prompt ?: return
    val setup = current.own
    BrowserOverlay(
        title = when {
            setup -> current.reason.ifBlank { if (current.transport.isNotBlank()) "Настройка «${current.transport}»" else "Транспорт просит настройку" }
            current.remote -> "Нода просит пройти проверку Яндекса"
            else -> "Яндекс просит пройти проверку"
        },
        note = when {
            setup -> "Заполните страницу и нажмите её кнопку: OpenFlux передаст данные сам."
            current.remote -> "Страница открыта через ноду. Пройдите проверку и нажмите «Готово»."
            else -> "Пройдите проверку и нажмите «Готово»."
        },
        page = page,
        waiting = current.progress.ifEmpty { "Открываю страницу…" },
        onClose = connection::dismissCaptcha,
        closeLabel = "Позже",
        error = current.error.ifBlank { null },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
            if (!setup) PrimaryButton(if (current.busy) "Передаю…" else "Готово", connection::submitCaptcha, enabled = !current.busy && page != null)
            SecondaryButton("Открыть заново", connection::openCaptcha, enabled = !current.busy)
        }
        if (!setup && sessions[AccountKind.Yandex]?.let { !it.expired && it.cookies.isNotEmpty() } != true) {
            TextAction("Не проходить каждый раз: войти в Яндекс", {
                connection.dismissCaptcha()
                navigator.open(Dest.SettingsPage("services"))
            })
        }
    }
}

/** A service's own page while the person signs in or a document is made; closing it cancels. */
@Composable
fun SignInOverlay() {
    val accounts = LocalAppContainer.current.accounts
    val kind by accounts.signingIn.collectAsState()
    val page by accounts.page.collectAsState()
    val status by accounts.status.collectAsState()
    val current = kind ?: return
    val step = (status[current] as? AuthStatus.Busy)?.step
    BrowserOverlay(
        title = "Вход в ${current.label}",
        note = step ?: "OpenFlux сохранит только сессию, пароль — нет. Окно закроется само.",
        page = page,
        waiting = step ?: "Открываю страницу входа…",
        onClose = accounts::cancel,
        closeLabel = "Отмена",
    )
}

/** A script transport's settings page; the page saves itself. */
@Composable
fun ScriptSettingsOverlay() {
    val service = LocalAppContainer.current.scriptSettings
    val state by service.state.collectAsState()
    val current = state ?: return
    BrowserOverlay(
        title = "Настройки «${current.title}»",
        note = "Измените и нажмите «Сохранить» на странице. Применится при следующем подключении.",
        page = current.page,
        waiting = current.progress.ifEmpty { "Открываю настройки…" },
        onClose = service::close,
        closeLabel = "Закрыть",
        error = current.error.ifBlank { null },
    )
}

/** A new release of the app, in one line (spec 8.6). */
@Composable
fun UpdateLine(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val updates = container.updates
    val state by updates.state.collectAsState()
    val pad = modifier.padding(horizontal = AppTheme.spacing.page, vertical = AppTheme.spacing.xs)
    when (val s = state) {
        is UpdateState.Available -> Banner(
            "Вышла версия ${s.release.version}", Tone.Info, pad,
            action = if (s.asset == null) "Скачать" else "Обновить", onAction = updates::install,
            more = listOf("Позже" to updates::dismiss, "Пропустить эту версию" to updates::skip),
        )
        is UpdateState.Downloading -> Column(pad) {
            Text(
                "Скачиваю ${s.release.version}: ${mb(s.done)}" + if (s.total > 0) " из ${mb(s.total)}" else "",
                style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
            )
            if (s.total > 0) LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = AppTheme.colors.accent)
            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppTheme.colors.accent)
        }
        is UpdateState.Installing -> Banner(s.note ?: "Устанавливаю ${s.release.version}…", Tone.Success, pad, action = "Закрыть", onAction = updates::dismiss)
        is UpdateState.Failed -> if (s.release != null) Banner(s.message, Tone.Danger, pad, action = "Повторить", onAction = updates::install, secondAction = "Закрыть", onSecondAction = updates::dismiss)
        else -> Unit
    }
}

private fun mb(bytes: Long): String {
    val tenths = (bytes * 10 + 524_288) / 1_048_576
    return "${tenths / 10},${tenths % 10} МБ"
}
