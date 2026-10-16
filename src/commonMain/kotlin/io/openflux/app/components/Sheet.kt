package io.openflux.app.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.openflux.app.theme.AppTheme

/**
 * A choice or a short form over the screen: a sheet from the bottom on a phone, a dialog in a wider window.
 * Back or a tap outside dismisses it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = AppTheme.colors
    val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val body = @Composable {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = AppTheme.spacing.l)) {
            if (title != null) {
                Text(
                    title,
                    style = AppTheme.type.title,
                    color = c.text,
                    modifier = Modifier.padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.m),
                )
            }
            content()
        }
    }
    if (widthDp < 600.dp) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.surface,
            contentColor = c.text,
        ) { body() }
    } else {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier.widthIn(max = 480.dp),
                shape = RoundedCornerShape(AppTheme.shapes.large),
                color = c.surface,
                contentColor = c.text,
            ) { Column(Modifier.padding(top = AppTheme.spacing.s)) { body() } }
        }
    }
}
