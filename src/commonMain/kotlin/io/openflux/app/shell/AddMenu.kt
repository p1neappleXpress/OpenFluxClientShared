package io.openflux.app.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import io.openflux.app.components.RowItem
import io.openflux.app.components.Sheet
import io.openflux.app.theme.AppTheme

/** Everything new comes in through ＋ (spec 7.1). */
enum class AddChoice(val title: String, val icon: ImageVector) {
    Key("Ключ, ссылка или файл", Icons.Rounded.Link),
    Scan("Сканировать QR", Icons.Rounded.QrCodeScanner),
    Server("Свой сервер", Icons.Rounded.Dns),
    Hosting("Без сервера (хостинг)", Icons.Rounded.Cloud),
    Account("Аккаунт OpenFlux", Icons.Rounded.AccountCircle),
    Manual("Вручную", Icons.Rounded.Edit),
}

@Composable
fun AddMenu(cameraScan: Boolean, onChoice: (AddChoice) -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss = onDismiss, title = "Добавить") {
        for (choice in AddChoice.entries) {
            if (choice == AddChoice.Scan && !cameraScan) continue
            RowItem(
                choice.title,
                leading = { Icon(choice.icon, contentDescription = null, tint = AppTheme.colors.textSecondary) },
                onClick = { onChoice(choice) },
            )
        }
    }
}
