package io.openflux.desktop

import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.ui.accounts.AccountsScreenModel
import io.openflux.desktop.ui.components.Tone
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountsScreenModelTest {
    @Test
    fun statusTexts() {
        assertEquals("Вход не выполнен" to Tone.Neutral, AccountsScreenModel.statusText(AuthStatus.SignedOut, 0))
        assertEquals(
            "Вход выполнен: ivan · проверено 10 мин назад" to Tone.Success,
            AccountsScreenModel.statusText(AuthStatus.SignedIn("ivan", 0), 10 * 60_000L),
        )
        assertEquals("Вход выполнен · проверено только что" to Tone.Success, AccountsScreenModel.statusText(AuthStatus.SignedIn("", 0), 5_000))
        assertEquals("Сессия истекла: ivan" to Tone.Danger, AccountsScreenModel.statusText(AuthStatus.Expired("ivan"), 0))
        assertEquals(Tone.Warning, AccountsScreenModel.statusText(AuthStatus.NeedsCheck("ivan"), 0).second)
        assertEquals("Открываю…" to Tone.Accent, AccountsScreenModel.statusText(AuthStatus.Busy("Открываю…"), 0))
    }

    @Test
    fun ago() {
        assertEquals("только что", AccountsScreenModel.ago(-5))
        assertEquals("3 ч назад", AccountsScreenModel.ago(3 * 3_600_000L))
        assertEquals("3 дн назад", AccountsScreenModel.ago(3 * 86_400_000L))
    }
}
