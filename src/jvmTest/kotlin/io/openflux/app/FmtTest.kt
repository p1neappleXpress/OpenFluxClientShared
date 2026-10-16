package io.openflux.app

import io.openflux.app.text.Fmt
import io.openflux.app.text.Redact
import kotlin.test.Test
import kotlin.test.assertEquals

class FmtTest {
    @Test
    fun datesAreRussianCivilDates() {
        assertEquals("1 января 1970", Fmt.date(0))
        assertEquals("29 февраля 2024", Fmt.date(1_709_164_800))
        assertEquals("1 марта 2024", Fmt.date(1_709_251_200))
        assertEquals("31 декабря 1999", Fmt.date(946_598_400))
        assertEquals("12 октября 2026", Fmt.date(1_791_763_200 + 3_600))
        assertEquals("31 декабря 1969", Fmt.date(-1))
    }

    @Test
    fun theZoneMovesTheDay() {
        // 22:30 UTC on the 10th is already the 11th at UTC+3, and still the 10th at UTC-5.
        val t = 1_791_589_800L // 2026-10-09 22:30 UTC
        assertEquals("9 октября 2026", Fmt.date(t))
        assertEquals("10 октября 2026", Fmt.date(t, 3 * 3600))
        assertEquals("9 октября 2026", Fmt.date(t, -5 * 3600))
    }

    @Test
    fun countsAreInRussian() {
        for ((n, want) in mapOf(1 to "1 день", 2 to "2 дня", 4 to "4 дня", 5 to "5 дней", 11 to "11 дней", 12 to "12 дней", 21 to "21 день", 22 to "22 дня", 100 to "100 дней", 111 to "111 дней")) {
            assertEquals(want, Fmt.days(n))
        }
        assertEquals("остался 1 день", Fmt.daysLeft(1))
        assertEquals("осталось 3 дня", Fmt.daysLeft(3))
        assertEquals("осталось 11 дней", Fmt.daysLeft(11))
        assertEquals("остался 21 день", Fmt.daysLeft(21))
        assertEquals("осталось меньше суток", Fmt.daysLeft(0))
    }

    @Test
    fun agoIsRoughOnPurpose() {
        assertEquals("только что", Fmt.ago(20_000))
        assertEquals("5 мин назад", Fmt.ago(5 * 60_000L))
        assertEquals("3 ч назад", Fmt.ago(3 * 3_600_000L))
        assertEquals("вчера", Fmt.ago(30 * 3_600_000L))
        assertEquals("4 дн. назад", Fmt.ago(4 * 86_400_000L))
        assertEquals("только что", Fmt.ago(-5))
    }
}

class FmtMoreTest {
    @org.junit.Test
    fun bytesSpeedDuration() {
        kotlin.test.assertEquals("512 Б", Fmt.bytes(512))
        kotlin.test.assertEquals("1,5 КБ", Fmt.bytes(1536))
        kotlin.test.assertEquals("2 МБ", Fmt.bytes(2L * 1024 * 1024))
        kotlin.test.assertEquals("1,5 МБ/с", Fmt.speed(1_572_864))
        kotlin.test.assertEquals("01:05", Fmt.duration(65_000))
        kotlin.test.assertEquals("1:01:05", Fmt.duration(3_665_000))
    }

    @org.junit.Test
    fun redactHidesSecrets() {
        kotlin.test.assertEquals("[адрес скрыт] и [ключ скрыт]", Redact.apply("https://docs.yandex.ru/edit/d/abc и " + "a".repeat(64)))
    }
}
