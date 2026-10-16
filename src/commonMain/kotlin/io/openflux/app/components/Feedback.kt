package io.openflux.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.openflux.app.theme.AppTheme
import kotlinx.coroutines.delay

enum class Tone { Info, Success, Warning, Danger }

/** One line of what needs attention, with what to do about it. A screen shows at most one (spec 3). */
@Composable
fun Banner(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    secondAction: String? = null,
    onSecondAction: (() -> Unit)? = null,
    more: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val c = AppTheme.colors
    val (icon, color) = when (tone) {
        Tone.Info -> Icons.Rounded.Info to c.textSecondary
        Tone.Success -> Icons.Rounded.CheckCircle to c.success
        Tone.Warning -> Icons.Rounded.Warning to c.warning
        Tone.Danger -> Icons.Rounded.Error to c.danger
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(AppTheme.shapes.medium)).background(c.surfaceHigh)
            .padding(start = AppTheme.spacing.m, end = AppTheme.spacing.xs, top = AppTheme.spacing.xs, bottom = AppTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(
            text,
            modifier = Modifier.weight(1f).padding(vertical = AppTheme.spacing.s),
            style = AppTheme.type.bodySmall,
            color = c.text,
            maxLines = 3,
        )
        if (secondAction != null && onSecondAction != null) TextAction(secondAction, onSecondAction, color = c.textSecondary)
        if (action != null && onAction != null) TextAction(action, onAction)
        if (more.isNotEmpty()) Box {
            var open by remember { mutableStateOf(false) }
            IconAction(Icons.Rounded.MoreVert, "Ещё", { open = true })
            DropdownMenu(open, { open = false }) {
                more.forEach { (label, run) -> DropdownMenuItem({ Text(label) }, { open = false; run() }) }
            }
        }
    }
}

/** A short note at the bottom of the window ("Скопировано"); a new one replaces the old. */
@Stable
class Toaster {
    var current by mutableStateOf<String?>(null)
        private set
    internal var serial by mutableStateOf(0)
        private set

    fun show(text: String) {
        current = text
        serial++
    }

    internal fun clear(at: Int) {
        if (serial == at) current = null
    }
}

val LocalToaster = staticCompositionLocalOf { Toaster() }

@Composable
fun ToastHost(toaster: Toaster, modifier: Modifier = Modifier) {
    val serial = toaster.serial
    LaunchedEffect(serial) {
        delay(2_500)
        toaster.clear(serial)
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(toaster.current != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                toaster.current.orEmpty(),
                modifier = Modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(AppTheme.shapes.medium))
                    .background(AppTheme.colors.text).padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.m),
                style = AppTheme.type.body,
                color = AppTheme.colors.background,
            )
        }
    }
}

/** "Are you sure?" for what cannot be undone; [danger] colours the confirming button. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    dismiss: String = "Отмена",
) {
    val c = AppTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.textSecondary,
        shape = RoundedCornerShape(AppTheme.shapes.large),
        title = { Text(title, style = AppTheme.type.title) },
        text = text?.let { { Text(it, style = AppTheme.type.body) } },
        confirmButton = { TextAction(confirm, { onConfirm() }, color = if (danger) c.danger else c.accent) },
        dismissButton = { TextAction(dismiss, onDismiss, color = c.textSecondary) },
    )
}
