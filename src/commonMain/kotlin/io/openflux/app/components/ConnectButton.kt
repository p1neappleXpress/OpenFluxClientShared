package io.openflux.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.openflux.app.theme.AppTheme

/** What the big button shows. */
enum class ConnectLook { Off, Busy, On, Error }

/**
 * Home's big round button (spec 6): grey when off, an accent ring turning while it connects, filled accent when
 * connected, a red ring after a failure. [label] names the action for a screen reader.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ConnectButton(look: ConnectLook, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 168.dp, shortcut: String? = null) {
    if (shortcut == null || AppTheme.touch) return ConnectCircle(look, label, onClick, modifier, size)
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider(androidx.compose.material3.TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { androidx.compose.material3.Text("$label · $shortcut") } },
        state = androidx.compose.material3.rememberTooltipState(),
    ) { ConnectCircle(look, label, onClick, modifier, size) }
}

@Composable
private fun ConnectCircle(look: ConnectLook, label: String, onClick: () -> Unit, modifier: Modifier, size: Dp) {
    val c = AppTheme.colors
    val fill by animateColorAsState(if (look == ConnectLook.On) c.accent else c.surface)
    val iconColor by animateColorAsState(
        when (look) {
            ConnectLook.On -> c.onAccent
            ConnectLook.Error -> c.danger
            ConnectLook.Busy -> c.accent
            ConnectLook.Off -> c.textSecondary
        },
    )
    val ring = when (look) {
        ConnectLook.On -> c.accent
        ConnectLook.Error -> c.danger
        ConnectLook.Busy -> c.accent
        ConnectLook.Off -> c.border
    }
    val turn by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart))
    Box(
        modifier.size(size).clip(CircleShape).background(fill)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            val w = 6.dp.toPx()
            if (look == ConnectLook.Busy) {
                drawArc(c.border, 0f, 360f, false, style = Stroke(w))
                drawArc(ring, turn, 90f, false, style = Stroke(w))
            } else {
                drawArc(ring, 0f, 360f, false, style = Stroke(w))
            }
        }
        Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = iconColor, modifier = Modifier.size(size * 0.36f))
    }
}
