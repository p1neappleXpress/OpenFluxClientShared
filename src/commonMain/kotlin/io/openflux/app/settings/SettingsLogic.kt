package io.openflux.app.settings

import io.openflux.app.text.Fmt
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.model.Profile
import io.openflux.desktop.service.SettingsRepository

/** Taps on the app's version: the tenth turns developer mode on, like a phone's build number. */
class VersionTaps(private val settings: SettingsRepository) {
    private var taps = 0

    /** What to tell the person after a tap, or null for nothing. */
    fun tap(): String? {
        if (settings.settings.value.developerMode) return "Режим разработчика уже включён"
        taps++
        val left = TAPS - taps
        return when {
            left <= 0 -> {
                taps = 0
                settings.update { it.copy(developerMode = true) }
                "Режим разработчика включён"
            }
            left <= 5 -> "Ещё $left ${Fmt.plural(left, "нажатие", "нажатия", "нажатий")} до режима разработчика"
            else -> null
        }
    }

    companion object {
        const val TAPS = 10
    }
}

/** A service's sign-in in a few words. */
fun serviceStatus(status: AuthStatus, now: Long): String = when (status) {
    AuthStatus.SignedOut -> "вход не выполнен"
    is AuthStatus.Checking -> "проверяю вход…"
    is AuthStatus.SignedIn -> "вход выполнен" + (if (status.login.isBlank()) "" else ": ${status.login}") + " · проверено " + Fmt.ago(now - status.checkedAt)
    is AuthStatus.Expired -> "сессия истекла" + if (status.login.isBlank()) "" else ": ${status.login}"
    is AuthStatus.NeedsCheck -> "сервис просит проверку — войдите заново"
    is AuthStatus.Busy -> status.step
    is AuthStatus.Failed -> status.message
}

/** Which profiles use the service, and for what. */
fun serviceUsage(kind: AccountKind, profiles: List<Profile>): String {
    val n = profiles.count { p -> p.carriers.any { AccountKind.of(it.type) == kind } }
    val what = when (kind) {
        AccountKind.Yandex -> "Документ и Доска Яндекса"
        AccountKind.Mailru -> "Документ Mail.ru"
        AccountKind.Max -> "MAX"
    }
    return when (n) {
        0 -> what
        else -> "в ${n} ${Fmt.plural(n, "профиле", "профилях", "профилях")} · $what"
    }
}
