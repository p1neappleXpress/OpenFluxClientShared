package io.openflux.app

import io.openflux.app.account.SignInContent
import io.openflux.app.account.SignInUi
import io.openflux.app.components.ScreenScaffold
import kotlin.test.Test

/** The sign-in screen's states, for review by eye (SHOTS=<dir>). */
class SignInShotsTest {
    private fun shot(name: String, ui: SignInUi, frame: Shots.Frame = Shots.PHONE) =
        Shots.render("signin-$name", frame) {
            ScreenScaffold("Вход", onBack = {}) { SignInContent(ui, onStart = {}, onCancel = {}, onReopen = {}, onCopy = {}) }
        }

    private val page = "https://id.openflux.dev/realms/openflux/protocol/openid-connect/auth?client_id=openflux-desktop"

    @Test fun idle() = shot("idle", SignInUi.Idle)
    @Test fun opening() = shot("opening", SignInUi.Opening)
    @Test fun waiting() = shot("waiting", SignInUi.Waiting(page))
    @Test fun checking() = shot("checking", SignInUi.Checking)
    @Test fun preparing() = shot("preparing", SignInUi.Preparing)
    @Test fun expired() = shot("expired", SignInUi.Expired)
    @Test fun failed() = shot("failed", SignInUi.Failed("Не получилось войти. Если входили по почте, подтвердите адрес по ссылке из письма и попробуйте снова"))
    @Test fun waitingSmall() = shot("waiting", SignInUi.Waiting(page), Shots.SMALL)
    @Test fun idleWide() = shot("idle", SignInUi.Idle, Shots.WIDE)
}
