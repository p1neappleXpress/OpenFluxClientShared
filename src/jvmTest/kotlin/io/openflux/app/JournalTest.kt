package io.openflux.app

import io.openflux.app.settings.JournalFilter
import io.openflux.app.settings.journalLines
import io.openflux.app.settings.journalText
import io.openflux.app.settings.mergeJournal
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.LogLine
import kotlin.test.Test
import kotlin.test.assertEquals

class JournalTest {
    private val core = listOf(LogLine(1, 10, "OpenFlux запущен", LogLevel.Info), LogLine(2, 30, "carrier up", LogLevel.Debug))
    private val wizard = listOf(LogLine(1, 20, "ssh: ошибка входа", LogLevel.Error))

    @Test
    fun mergedByTime() = assertEquals(listOf(10L, 20L, 30L), mergeJournal(core, wizard, emptyList()).map { it.time })

    @Test
    fun filters() {
        val all = mergeJournal(core, wizard, emptyList())
        assertEquals(2, journalLines(all, JournalFilter.Important, "").size, "errors and OpenFlux's own lines")
        assertEquals(listOf("ssh: ошибка входа"), journalLines(all, JournalFilter.Errors, "").map { it.text })
        assertEquals(listOf("carrier up"), journalLines(all, JournalFilter.All, "CARRIER").map { it.text })
    }

    @Test
    fun copyHidesSecrets() {
        val line = listOf(LogLine(1, 1, "документ https://docs.yandex.ru/edit/d/abc", LogLevel.Info))
        assertEquals("документ [адрес скрыт]", journalText(line, mask = true))
        assertEquals("документ https://docs.yandex.ru/edit/d/abc", journalText(line, mask = false))
    }
}
