package io.openflux.desktop.ui.profiles

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.ui.components.AppDialog
import io.openflux.desktop.ui.components.AppIcons
import io.openflux.desktop.ui.components.SectionLabel
import io.openflux.desktop.ui.components.TextAction
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.components.appClickable
import io.openflux.desktop.ui.components.toneColor
import io.openflux.desktop.ui.scripts.ScriptsTab
import io.openflux.desktop.ui.shell.LocalShell
import io.openflux.desktop.ui.theme.AppTheme
import org.jetbrains.compose.resources.painterResource

/** One line under a built-in transport's name: what carries the traffic. */
private fun TransportType.blurb(): String = when (this) {
    TransportType.VYANDEX -> "Документ Яндекса, по ссылке на редактирование"
    TransportType.YANDEX -> "Файл на Яндекс Диске"
    TransportType.BOARDS -> "Доска Яндекса"
    TransportType.MAILRU -> "Публичный файл Облака Mail.ru"
    TransportType.CUPSONLINE -> "Комнаты cups.online, код выдаёт нода"
    TransportType.ONEME -> "Звонок в MAX, нужен токен MAX Web"
    TransportType.DIRECT -> "Прямое TCP-соединение с нодой"
    TransportType.SCRIPT -> "JS-транспорт"
}

/**
 * Picks a transport for a carrier: built-in ones and installed JS scripts as one
 * list of cards, the same catalogue the «Транспорты» tab manages.
 */
@Composable
internal fun TransportPickerDialog(
    selected: TransportType,
    selectedScriptId: String,
    native: List<TransportType>,
    scripts: List<InstalledScript>,
    /** JS transports (and the way to manage them) are offered only with the experimental features on. */
    showScripts: Boolean,
    onSelect: (TransportType, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val shell = LocalShell.current
    AppDialog(title = "Транспорт", onDismiss = onDismiss, secondary = "Закрыть", enterSubmits = false) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
            if (showScripts) TextAction("Управлять транспортами", { onDismiss(); shell.open(ScriptsTab) })
            SectionLabel("Встроенные")
            native.forEach { type ->
                TransportRow(
                    icon = type.icon,
                    title = type.label,
                    subtitle = type.blurb(),
                    badge = null,
                    checked = selected == type,
                ) { onSelect(type, "") }
            }
            if (showScripts) {
                Spacer(Modifier.height(AppTheme.spacing.xs))
                SectionLabel("JS-транспорты")
            }
            if (showScripts && scripts.isEmpty()) {
                Text(
                    "Пока нет установленных. Импортируйте на вкладке «Транспорты» — они появятся здесь.",
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.textSecondary,
                )
            }
            if (showScripts) scripts.forEach { s ->
                TransportRow(
                    icon = s.icon,
                    title = s.name,
                    subtitle = "версия ${s.version.ifBlank { "—" }} · " + if (s.official) "подписан OpenFlux" else "сторонний автор",
                    badge = "JS",
                    checked = selected == TransportType.SCRIPT && selectedScriptId == s.id,
                ) { onSelect(TransportType.SCRIPT, s.id) }
            }
        }
    }
}

@Composable
private fun TransportRow(icon: String, title: String, subtitle: String, badge: String?, checked: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered) AppTheme.colors.surfaceTonal else Color.Transparent)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AppTheme.shapes.field)
            .background(fill)
            .border(1.dp, if (checked) AppTheme.colors.accent else AppTheme.colors.border, AppTheme.shapes.field)
            .appClickable(interaction, onClick = onClick)
            .padding(horizontal = AppTheme.spacing.m, vertical = AppTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(AppTheme.shapes.field).background(AppTheme.colors.surfaceTonal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(AppIcons.byName(icon)), null, tint = AppTheme.colors.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(AppTheme.spacing.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) {
                    Spacer(Modifier.width(AppTheme.spacing.s))
                    Text(badge, style = AppTheme.typography.caption, color = toneColor(Tone.Accent))
                }
            }
            Text(subtitle, style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (checked) {
            Spacer(Modifier.width(AppTheme.spacing.s))
            Icon(Icons.Rounded.Check, "Выбрано", tint = AppTheme.colors.accent, modifier = Modifier.size(20.dp))
        }
    }
}
