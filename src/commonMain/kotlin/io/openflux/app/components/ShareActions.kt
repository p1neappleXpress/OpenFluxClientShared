package io.openflux.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.service.LocalAppContainer

/**
 * What to do with a link to give away. Android opens the system share sheet with every installed app; the desktop
 * has no such sheet to call from here, so it offers the common messengers and mail, each with the link filled in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareLinkActions(link: String, modifier: Modifier = Modifier) {
    val platform = LocalAppContainer.current.platform
    val toaster = LocalToaster.current
    val copy = { platform.setClipboardText(link); toaster.show("Ссылка скопирована") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
        if (platform.shareSupported) {
            PrimaryButton("Поделиться ссылкой", { if (!platform.shareText(link)) copy() }, Modifier.fillMaxWidth(), leading = Icons.Rounded.Share)
            SecondaryButton("Копировать ссылку", copy, Modifier.fillMaxWidth())
        } else {
            val q = java.net.URLEncoder.encode(link, "UTF-8").replace("+", "%20")
            PrimaryButton("Копировать ссылку", copy, Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                SecondaryButton("Telegram", { platform.openUrl("https://t.me/share/url?url=$q") })
                SecondaryButton("WhatsApp", { platform.openUrl("https://wa.me/?text=$q") })
                SecondaryButton("Почта", { platform.openUrl("mailto:?subject=OpenFlux&body=$q") })
            }
        }
    }
}
