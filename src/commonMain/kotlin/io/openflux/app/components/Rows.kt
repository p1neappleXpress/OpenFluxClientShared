package io.openflux.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.openflux.app.theme.AppTheme
import io.openflux.app.theme.rowMinHeight

/** The copy icon, the same everywhere. */
val CopyIcon = Icons.Rounded.ContentCopy

/** What a thing is doing, as a dot: off, busy, on, needs a look, broken. */
enum class Status { Off, Busy, On, Warning, Error }

@Composable
fun StatusDot(status: Status, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val color = when (status) {
        Status.Off -> c.textMuted
        Status.Busy, Status.Warning -> c.warning
        Status.On -> c.success
        Status.Error -> c.danger
    }
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

/**
 * A line of a list: what almost every screen is made of. No frame of its own; a [Section] groups rows.
 * [selected] marks the chosen one with an accent stripe and an accent title.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onDoubleClick: (() -> Unit)? = null,
) {
    val c = AppTheme.colors
    val sp = AppTheme.spacing
    val clickable = if (onClick != null || onLongClick != null || onDoubleClick != null) {
        Modifier.combinedClickable(enabled = enabled, onClick = onClick ?: {}, onLongClick = onLongClick, onDoubleClick = onDoubleClick)
    } else Modifier
    val stripe = if (selected) Modifier.drawBehind { drawRect(c.accent, size = Size(3.dp.toPx(), size.height)) } else Modifier
    Row(
        modifier.fillMaxWidth().heightIn(min = rowMinHeight()).then(clickable).then(stripe)
            .padding(horizontal = sp.l, vertical = sp.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(sp.m))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AppTheme.type.body,
                color = when {
                    !enabled -> c.textMuted
                    selected -> c.accent
                    else -> c.text
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = AppTheme.type.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(sp.s))
            trailing()
        }
    }
}

/** "Name · value", the value copyable: how facts are shown instead of cards. A value too long for the line goes under the name whole. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ValueRow(title: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false, onCopy: (() -> Unit)? = null) {
    val c = AppTheme.colors
    val sp = AppTheme.spacing
    Row(
        modifier.fillMaxWidth().heightIn(min = rowMinHeight()).padding(start = sp.l, end = if (onCopy != null) sp.xs else sp.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            Modifier.weight(1f).padding(vertical = sp.s),
            horizontalArrangement = Arrangement.spacedBy(sp.m, Alignment.End),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = AppTheme.type.body, color = c.textSecondary, maxLines = 1)
            Text(value, style = if (mono) AppTheme.type.mono else AppTheme.type.body, color = c.text)
        }
        if (onCopy != null) IconAction(Icons.Rounded.ContentCopy, "Копировать", onCopy)
    }
}

/**
 * A group of rows on one surface, with a thin line between them and an optional small heading above.
 * Children are laid out top to bottom.
 */
@Composable
fun Section(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = AppTheme.colors
    val sp = AppTheme.spacing
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = AppTheme.type.caption,
                color = c.textMuted,
                modifier = Modifier.padding(start = sp.l, bottom = sp.xs),
            )
        }
        // Where one row ends and the next begins, written by the layout and read by the drawing of the same frame.
        val seams = remember { Seams() }
        val inset = sp.l
        Layout(
            content = content,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppTheme.shapes.medium)).background(c.surface)
                .drawWithContent {
                    drawContent()
                    val x = inset.toPx()
                    val stroke = 1.dp.toPx()
                    for (y in seams.ys) drawLine(c.border, Offset(x, y - stroke / 2), Offset(size.width, y - stroke / 2), stroke)
                },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
            val visible = placeables.filter { it.height > 0 }
            seams.ys = visible.dropLast(1).runningFold(0f) { y, p -> y + p.height }.drop(1)
            layout(constraints.maxWidth, placeables.sumOf { it.height }) {
                var y = 0
                placeables.forEach { p -> p.placeRelative(0, y); y += p.height }
            }
        }
    }
}

private class Seams {
    var ys: List<Float> = emptyList()
}

/** A group with a heading whose content is not rows: a switcher, a field, a line of text, padded like rows. */
@Composable
fun Group(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Section(title, modifier) {
        Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), content = content)
    }
}

/** A heading row that opens and closes what is under it: the rare settings ("Дополнительно"). */
@Composable
fun Collapsible(title: String, modifier: Modifier = Modifier, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f)
    Column(modifier.fillMaxWidth()) {
        RowItem(
            title,
            trailing = { Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = AppTheme.colors.textSecondary, modifier = Modifier.rotate(turn)) },
            onClick = { open = !open },
        )
        AnimatedVisibility(open) { Column(content = content) }
    }
}
