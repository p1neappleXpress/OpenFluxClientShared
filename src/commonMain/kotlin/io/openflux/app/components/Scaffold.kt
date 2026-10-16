package io.openflux.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.openflux.app.nav.LocalInPane
import io.openflux.app.theme.AppTheme
import io.openflux.app.platform.LocalScrollbars

/**
 * The frame of a screen: the back arrow, the title and the actions, then the page (scrolling when [scroll]),
 * and [bottomBar] pinned under it (the "Изменено · Применить" bar of an editor). Lines longer than [maxWidth]
 * are hard to read, so a wide window leaves the rest empty.
 */
@Composable
fun ScreenScaffold(
    title: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    scroll: Boolean = true,
    maxWidth: Dp = 720.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sp = AppTheme.spacing
    // Beside its list (a wide window) the list is the way back.
    val back = onBack.takeUnless { LocalInPane.current }
    Column(modifier.fillMaxSize()) {
        if (title != null || back != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (back != null) sp.xs else sp.page, end = sp.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (back != null) {
                    IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", back)
                    Spacer(Modifier.width(sp.xs))
                }
                Text(
                    title.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = AppTheme.type.screenTitle,
                    color = AppTheme.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (scroll) {
                val state = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(state).padding(horizontal = sp.page, vertical = sp.s)) {
                    Column(Modifier.widthIn(max = maxWidth), verticalArrangement = Arrangement.spacedBy(sp.l), content = content)
                }
                LocalScrollbars.current.Vertical(state, Modifier.align(Alignment.CenterEnd))
            } else {
                Column(Modifier.fillMaxSize().widthIn(max = maxWidth).padding(horizontal = sp.page), content = content)
            }
        }
        if (bottomBar != null) bottomBar()
    }
}
