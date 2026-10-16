package io.openflux.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.openflux.app.components.Banner
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.TextAction
import io.openflux.app.components.Tone
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.shell.rememberScreenModel
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.AuthAnswer
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.model.OpenFluxId
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.service.SignInWay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the sign-in screen shows. */
sealed interface SignInUi {
    data object Idle : SignInUi
    data object Opening : SignInUi
    /** The page is open in the browser; [pageUrl] can be opened again or copied. */
    data class Waiting(val pageUrl: String) : SignInUi
    data object Checking : SignInUi
    data object Preparing : SignInUi
    data object Expired : SignInUi
    data class Failed(val message: String) : SignInUi
    data object Done : SignInUi
}

/**
 * Signing in on the OpenFlux page (email or Telegram): opens the page with PKCE, waits for the browser to come back
 * with a one-time code, and trades it for our session; then the account's subscription and connection come in.
 */
@Stable
class SignInModel(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val waitMs: Long = WAIT_MS,
) {
    var ui by mutableStateOf<SignInUi>(SignInUi.Idle)
        private set
    private var job: Job? = null
    private var way: SignInWay? = null

    /** Begins a sign-in; one at a time. */
    fun start() {
        if (job?.isActive == true) return
        ui = SignInUi.Opening
        job = scope.launch {
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An answer of a shape nobody expected must end in words on the screen, never in a crash.
                ui = SignInUi.Failed(GENERIC)
            } finally {
                way?.close()
            }
        }
    }

    /** Gives up: the way back closes, a late return is ignored. */
    fun cancel() {
        job?.cancel()
        job = null
        way?.close()
        ui = SignInUi.Idle
    }

    fun reopen() {
        (ui as? SignInUi.Waiting)?.let { container.signIn.openPage(it.pageUrl) }
    }

    /** For a computer whose browser did not open: the person pastes the page's address themselves. */
    fun copyLink() {
        (ui as? SignInUi.Waiting)?.let { container.platform.setClipboardText(it.pageUrl) }
    }

    private suspend fun run() {
        val support = container.signIn
        if (support.clientId.isBlank()) {
            ui = SignInUi.Failed(NO_CLIENT)
            return
        }
        val attempt = support.newAttempt()
        val way = try {
            support.begin(attempt.state)
        } catch (e: BotException) {
            ui = SignInUi.Failed(e.message ?: GENERIC)
            return
        }
        this.way = way
        val page = OpenFluxId.authorizeUrl(support.clientId, way.redirectUri, attempt, support.issuer)
        support.openPage(page)
        ui = SignInUi.Waiting(page)
        val answer = try {
            withTimeoutOrNull(waitMs) {
                var a: AuthAnswer
                do {
                    a = OpenFluxId.readReturn(way.await(), attempt.state)
                } while (a == AuthAnswer.Foreign)
                a
            }
        } catch (e: BotException) {
            // A newer sign-in took the way back over, or this one was closed.
            ui = SignInUi.Failed(e.message ?: GENERIC)
            return
        }
        way.close()
        val code = when (answer) {
            null -> { ui = SignInUi.Expired; return }
            is AuthAnswer.Refused -> { ui = SignInUi.Failed(refusal(answer.error)); return }
            is AuthAnswer.Code -> answer.code
            AuthAnswer.Foreign -> return
        }
        ui = SignInUi.Checking
        val session = try {
            container.bot.exchange(code, attempt.verifier, way.redirectUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BotException) {
            ui = SignInUi.Failed(e.message ?: GENERIC)
            return
        }
        ui = SignInUi.Preparing
        container.bot.completeLogin(session)
        ui = SignInUi.Done
    }

    private fun refusal(error: String) = when (error) {
        "access_denied" -> "Вход отменён"
        "temporarily_unavailable" -> "Страница входа временно недоступна, попробуйте позже"
        else -> "Страница входа вернула ошибку ($error). Попробуйте ещё раз"
    }

    private companion object {
        /** The page's code lives minutes; past this the way back closes. */
        const val WAIT_MS = 15 * 60 * 1000L
        const val GENERIC = "Не получилось войти"
        const val NO_CLIENT = "Вход в этой сборке выключен: в ней нет клиента входа"
    }
}

/** The sign-in screen's body for each state; the screen around it is [SignInScreen]. */
@Composable
fun SignInContent(ui: SignInUi, onStart: () -> Unit, onCancel: () -> Unit, onReopen: () -> Unit, onCopy: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
        when (ui) {
            SignInUi.Idle -> {
                Text("Вход в OpenFlux", style = AppTheme.type.screenTitle, color = AppTheme.colors.text)
                Text(
                    "Войдите по почте или через Telegram на странице OpenFlux: подписка и подключение появятся здесь сами.",
                    style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
                )
                PrimaryButton("Войти", onStart, Modifier.fillMaxWidth())
                Text(
                    "Покупали в боте @${OpenFluxBot.USERNAME}? Выберите вход через Telegram, чтобы открыть тот же аккаунт.",
                    style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
                )
            }
            SignInUi.Opening -> ProgressLine("Открываю страницу входа…")
            is SignInUi.Waiting -> {
                Text("Завершите вход в браузере", style = AppTheme.type.screenTitle, color = AppTheme.colors.text)
                Text(
                    "Страница входа открылась в браузере. Если нет, откройте её ещё раз или скопируйте ссылку в браузер.",
                    style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
                )
                ProgressLine("Жду возврата из браузера…")
                SecondaryButton("Открыть страницу ещё раз", onReopen, Modifier.fillMaxWidth())
                SecondaryButton("Скопировать ссылку", onCopy, Modifier.fillMaxWidth())
                TextAction("Отмена", onCancel)
            }
            SignInUi.Checking -> ProgressLine("Проверяю вход…")
            SignInUi.Preparing -> ProgressLine("Готовлю подключение…")
            SignInUi.Expired -> {
                Banner("Время на вход вышло", Tone.Info)
                PrimaryButton("Повторить", onStart, Modifier.fillMaxWidth())
            }
            is SignInUi.Failed -> {
                Banner(ui.message, Tone.Danger)
                PrimaryButton("Повторить", onStart, Modifier.fillMaxWidth())
            }
            SignInUi.Done -> ProgressLine("Готово")
        }
    }
}

/** Signing in: email or Telegram on the OpenFlux page; the app gets the account's subscription and connection. */
@Composable
fun SignInScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val m = rememberScreenModel { SignInModel(container, it) }
    LaunchedEffect(m.ui) { if (m.ui == SignInUi.Done) navigator.back() }
    ScreenScaffold("Вход", onBack = { m.cancel(); navigator.back() }) {
        SignInContent(m.ui, onStart = m::start, onCancel = m::cancel, onReopen = m::reopen, onCopy = m::copyLink)
    }
}
