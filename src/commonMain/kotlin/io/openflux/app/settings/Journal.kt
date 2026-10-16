package io.openflux.app.settings

import io.openflux.app.text.Redact
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.LogLine

/** Which lines of the journal are shown. */
enum class JournalFilter(val label: String) { All("Все"), Important("Важные"), Errors("Ошибки") }

/** The core's log, the node wizard's and the hosting's, merged by time into one timeline. */
fun mergeJournal(core: List<LogLine>, wizard: List<LogLine>, hosting: List<LogLine>): List<LogLine> =
    if (wizard.isEmpty() && hosting.isEmpty()) core else (core + wizard + hosting).sortedWith(compareBy({ it.time }, { it.id }))

fun journalLines(lines: List<LogLine>, filter: JournalFilter, query: String): List<LogLine> {
    val q = query.trim().lowercase()
    return lines.filter { line ->
        val levelOk = when (filter) {
            JournalFilter.All -> true
            JournalFilter.Important -> (line.level != LogLevel.Debug && line.level != LogLevel.Info) || line.text.contains("OpenFlux")
            JournalFilter.Errors -> line.level == LogLevel.Error || line.level == LogLevel.Warning
        }
        levelOk && (q.isEmpty() || line.text.lowercase().contains(q))
    }
}

/** What "Копировать показанное" puts on the clipboard: the lines, addresses and keys hidden when [mask]. */
fun journalText(lines: List<LogLine>, mask: Boolean): String = lines.joinToString("\n") { if (mask) Redact.apply(it.text) else it.text }
