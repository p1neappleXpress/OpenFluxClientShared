package io.openflux.app.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.openflux.app.theme.AppTheme
import io.openflux.app.theme.MinTouch

private val ButtonPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

@Composable
private fun ButtonLabel(text: String, leading: ImageVector?) {
    if (leading != null) {
        Icon(leading, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(AppTheme.spacing.s))
    }
    // A narrow button (two side by side on a small phone) shrinks its label rather than cutting it.
    BasicText(
        text,
        style = AppTheme.type.title.copy(color = LocalContentColor.current),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = AppTheme.type.title.fontSize, stepSize = 0.5.sp),
    )
}

/** The one main action of a screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: ImageVector? = null) {
    val c = AppTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = MinTouch),
        enabled = enabled,
        shape = RoundedCornerShape(AppTheme.shapes.medium),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.accent, contentColor = c.onAccent,
            disabledContainerColor = c.surfaceHigh, disabledContentColor = c.textMuted,
        ),
        contentPadding = ButtonPadding,
    ) { ButtonLabel(text, leading) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: ImageVector? = null) {
    val c = AppTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = MinTouch),
        enabled = enabled,
        shape = RoundedCornerShape(AppTheme.shapes.medium),
        border = BorderStroke(1.dp, c.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text, disabledContentColor = c.textMuted),
        contentPadding = ButtonPadding,
    ) { ButtonLabel(text, leading) }
}

/** Deletes or breaks something: the colour says so before the dialog does. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = AppTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = MinTouch),
        enabled = enabled,
        shape = RoundedCornerShape(AppTheme.shapes.medium),
        border = BorderStroke(1.dp, c.danger),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.danger, disabledContentColor = c.textMuted),
        contentPadding = ButtonPadding,
    ) { ButtonLabel(text, null) }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = AppTheme.colors.accent) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = AppTheme.colors.textMuted),
    ) { Text(text, style = AppTheme.type.title, maxLines = 1) }
}

/** An icon with a tooltip naming it (and its shortcut, when [description] carries one: "Добавить · Ctrl+N"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = AppTheme.colors
    val button = @Composable {
        IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(contentColor = c.text, disabledContentColor = c.textMuted),
        ) { Icon(icon, contentDescription = description.substringBefore(" · ")) }
    }
    if (AppTheme.touch) {
        button()
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(description) } },
            state = rememberTooltipState(),
        ) { button() }
    }
}
