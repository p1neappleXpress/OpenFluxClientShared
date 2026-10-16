package io.openflux.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.openflux.app.components.Banner
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.QrView
import io.openflux.app.components.RowItem
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.Section
import io.openflux.app.components.ShareLinkActions
import io.openflux.app.components.Tone
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.BotLink
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.service.PlatformServices

/**
 * A connection of the account in a few words: what GET /api/v1/sessions last said, and whether this device uses it.
 * A paused one is resumed in the bot (the API cannot yet), so only a Telegram account is sent there.
 */
fun connectionStateText(link: BotLink, viaTelegram: Boolean, connected: Boolean): String = when {
    link.gone -> "закончилось или заменено"
    connected -> "работает, подключено на этом устройстве"
    link.status == "running" -> "работает"
    link.status == "paused" -> if (viaTelegram) "приостановлено: возобновите в @${OpenFluxBot.USERNAME}" else "приостановлено"
    else -> "готовится"
}

/** The account's connections, each opening its own sheet (connect, share). */
@Composable
fun AccountConnectionsSection(links: List<BotLink>, viaTelegram: Boolean, connectedProfileId: String?, onOpen: (String) -> Unit) {
    if (links.isEmpty()) return
    Section("Подключения аккаунта") {
        for (link in links) {
            RowItem(
                link.name.ifBlank { "Подключение" },
                subtitle = connectionStateText(link, viaTelegram, link.profileId == connectedProfileId),
                onClick = { onOpen(link.token) },
            )
        }
    }
}

/**
 * One connection of the account: connect this device, or give it to another one. The bot's short link is what is
 * given (as the bot does), not the key: the other device adds it with "Добавить ссылку из бота" and follows the bot.
 */
@Composable
fun AccountConnectionContent(
    link: BotLink,
    viaTelegram: Boolean,
    connected: Boolean,
    platform: PlatformServices,
    onConnect: () -> Unit,
    onOpenBot: () -> Unit,
) {
    val shortLink = OpenFluxBot.linkOf(link.token)
    Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
        when {
            link.gone -> Banner("Бот больше не обслуживает это подключение", Tone.Warning)
            connected -> Banner("Это устройство подключено через него", Tone.Success)
            link.status == "paused" -> {
                Banner("Подключение приостановлено: пока его не возобновят, оно не работает", Tone.Warning)
                if (viaTelegram) SecondaryButton("Возобновить в @${OpenFluxBot.USERNAME}", onOpenBot, Modifier.fillMaxWidth())
            }
            else -> {
                Text(connectionStateText(link, viaTelegram, connected).replaceFirstChar { it.uppercase() }, style = AppTheme.type.body, color = AppTheme.colors.text)
                PrimaryButton("Подключиться", onConnect, Modifier.fillMaxWidth())
            }
        }
        if (!link.gone) {
            Section("Поделиться") {
                Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                    Text(
                        "На другом устройстве добавьте эту ссылку через «Добавить ссылку из бота» или отсканируйте QR. " +
                            "Одновременно работает одно устройство: второе вытеснит первое.",
                        style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
                    )
                    QrView(shortLink, platform)
                    Text(shortLink, style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary)
                    ShareLinkActions(shortLink)
                }
            }
        }
    }
}
