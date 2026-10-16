package io.openflux.app

import io.openflux.app.settings.VersionTaps
import io.openflux.app.settings.serviceStatus
import io.openflux.app.settings.serviceUsage
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsLogicTest {
    @Test
    fun tenTapsTurnOnDeveloperMode() {
        val s = TestSettings()
        val t = VersionTaps(s)
        repeat(4) { assertNull(t.tap()) }
        assertEquals("Ещё 5 нажатий до режима разработчика", t.tap())
        assertEquals("Ещё 4 нажатия до режима разработчика", t.tap())
        repeat(2) { t.tap() }
        assertEquals("Ещё 1 нажатие до режима разработчика", t.tap())
        assertEquals("Режим разработчика включён", t.tap())
        assertTrue(s.settings.value.developerMode)
        assertEquals("Режим разработчика уже включён", t.tap())
    }

    @Test
    fun servicesInWords() {
        assertEquals("вход выполнен: ivan · проверено 5 мин назад", serviceStatus(AuthStatus.SignedIn("ivan", 0), 5 * 60_000))
        val p = Profile(id = "a", name = "a", transport = TransportType.VYANDEX)
        assertEquals("в 1 профиле · Документ и Доска Яндекса", serviceUsage(AccountKind.Yandex, listOf(p)))
        assertEquals("Документ Mail.ru", serviceUsage(AccountKind.Mailru, listOf(p)))
    }
}
