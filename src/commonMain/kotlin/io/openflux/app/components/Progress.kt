package io.openflux.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.openflux.app.theme.AppTheme

/** What a long operation is doing, in one line, with a bar; [onDetails] opens the journal ("Подробнее"). */
@Composable
fun ProgressLine(text: String, modifier: Modifier = Modifier, onDetails: (() -> Unit)? = null) {
    val c = AppTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = AppTheme.type.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (onDetails != null) TextAction("Подробнее", onDetails, color = c.textSecondary)
        }
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceHigh)
    }
}

/**
 * The bar under an editor once something changed: "Изменено: N", then [Отменить] [Применить]. While applying it
 * shows the progress instead; [problem] (why it cannot be applied) or [error] (why applying failed) is one
 * line above the buttons. Hidden when there is nothing to say.
 */
@Composable
fun ChangesBar(
    changes: Int,
    busy: String?,
    problem: String?,
    error: String?,
    onDiscard: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    applyLabel: String = "Применить",
) {
    if (changes == 0 && busy == null && error == null) return
    val c = AppTheme.colors
    val sp = AppTheme.spacing
    Column(
        modifier.fillMaxWidth().background(c.surface).padding(horizontal = sp.page, vertical = sp.m),
        verticalArrangement = Arrangement.spacedBy(sp.s),
    ) {
        if (busy != null) {
            ProgressLine(busy)
            return@Column
        }
        val line = error ?: problem
        if (line != null) Text(line, style = AppTheme.type.bodySmall, color = if (error != null) c.danger else c.warning)
        if (changes > 0) {
            Text("Изменено: $changes", style = AppTheme.type.body, color = c.text, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                SecondaryButton("Отменить", onDiscard, Modifier.weight(1f))
                PrimaryButton(applyLabel, onApply, Modifier.weight(1f), enabled = problem == null)
            }
        }
    }
}
