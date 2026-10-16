package io.openflux.app.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.openflux.app.components.Banner
import io.openflux.app.components.Field
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.QrView
import io.openflux.app.components.ShareLinkActions
import io.openflux.app.components.RowItem
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.Section
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.components.LocalToaster
import io.openflux.app.home.profileLine
import io.openflux.app.nav.Dest
import io.openflux.app.profiles.ProfileActions
import io.openflux.app.servers.isOwnNode
import io.openflux.app.servers.title
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.shell.rememberScreenModel
import io.openflux.app.text.Fmt
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.managed
import io.openflux.desktop.service.LocalAppContainer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember

/** A key, a link or a file (spec 7.1). */
@Composable
fun AddKeyScreen(scan: Boolean = false) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val m = rememberScreenModel { AddKeyModel(container, it).also { k -> if (scan) k.fromQr() } }
    val platform = container.platform
    LaunchedEffect(m) {
        container.pendingKey.collect { k -> if (k != null) { container.pendingKey.value = null; m.onText(k) } }
    }
    ScreenScaffold(
        "Ключ, ссылка или файл",
        onBack = { navigator.back() },
        bottomBar = {
            val r = m.result
            val ok = (r is KeyResult.Profile) || (r is KeyResult.Bot) || (r is KeyResult.Subscription && r.canAdd)
            if (ok) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(AppTheme.spacing.page)) {
                    PrimaryButton(
                        if (r is KeyResult.Subscription) "Подключить подписку" else "Добавить профиль",
                        { m.add()?.let(navigator::replaceTop) },
                        Modifier.fillMaxWidth(),
                        enabled = !m.busy,
                    )
                }
            }
        },
    ) {
        Field(m.text, { m.onText(it) }, "Ключ подписки или ссылка openflux://", singleLine = false, mono = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
            SecondaryButton("Вставить", m::paste, leading = Icons.Rounded.ContentPaste)
            SecondaryButton("Файл", m::fromFile, leading = Icons.Rounded.Description)
            SecondaryButton(if (platform.cameraScanSupported) "Сканировать QR" else "QR из картинки", m::fromQr, leading = Icons.Rounded.QrCodeScanner)
        }
        when (val r = m.result) {
            KeyResult.Empty -> Unit
            KeyResult.Checking -> ProgressLine("Читаю…")
            is KeyResult.Invalid -> Banner(r.message, Tone.Danger)
            is KeyResult.Profile -> Section("Профиль") {
                ValueRow("Название", r.config.name.ifBlank { "без названия" })
                ValueRow("Транспорты", r.config.transports.mapNotNull { TransportType.fromCli(it.type)?.title }.distinct().joinToString(", ").ifEmpty { "—" })
                ValueRow("Шифрование", if (r.config.secret.isNotEmpty()) "AES-256-GCM" else "нет ключа")
            }
            is KeyResult.Bot -> Section("Подключение из бота OpenFlux") {
                ValueRow("Название", r.connection.name.ifBlank { "OpenFlux" })
                ValueRow("Обновление", "само, пока подключение действует в боте")
            }
            is KeyResult.Subscription -> {
                val doc = r.preview.document
                val offset = platform.utcOffsetSeconds
                Section("Подписка") {
                    ValueRow("Название", doc.title.ifBlank { r.preview.invite.name.ifBlank { "Подписка" } })
                    ValueRow(
                        "Срок",
                        when {
                            doc.status == SubStatus.REVOKED -> "доступ отключён"
                            doc.status == SubStatus.EXPIRED -> "вышел" + if (doc.expires > 0) " ${Fmt.date(doc.expires, offset)}" else ""
                            doc.expires == 0L -> "без ограничения"
                            else -> "до ${Fmt.date(doc.expires, offset)}"
                        },
                    )
                    if (doc.profiles.isNotEmpty()) ValueRow("Локации", doc.profiles.joinToString(", ") { it.name })
                }
                if (!r.canAdd) Banner("По этому ключу подключиться нельзя: срок вышел или доступ отключён", Tone.Warning)
                val current = container.subscription.state.collectAsState().value
                if (current != null && r.canAdd) Banner("Новая подписка заменит ту, что есть на этом устройстве", Tone.Info)
            }
        }
    }
}

/** Share (spec 7.2): what to give away. */
@Composable
fun ShareRoot() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val actions = remember(container) { ProfileActions(container) }
    val profiles by container.profiles.profiles.collectAsState()
    val shareable = profiles.filter { actions.canShare(it) }
    ScreenScaffold("Поделиться") {
        if (shareable.isEmpty()) {
            Text(
                "Пока нечем делиться. Поставьте свою ноду в «Серверах» или добавьте подключение через ＋. Локациями подписки делиться нельзя.",
                style = AppTheme.type.body, color = AppTheme.colors.textSecondary,
            )
            return@ScreenScaffold
        }
        val groups = listOf(
            "Мои серверы" to shareable.filter { it.isOwnNode && it.phpNode == null },
            "Хостинг" to shareable.filter { it.phpNode != null },
            "Остальные" to shareable.filter { !it.isOwnNode },
        ).filter { it.second.isNotEmpty() }
        for ((title, list) in groups) {
            Section(title) {
                for (p in list) RowItem(p.name, subtitle = profileLine(p), onClick = { navigator.open(Dest.Give(p.id)) })
            }
        }
        Text("Ноду, которой ещё нет на этом устройстве, можно отдать с её экрана в «Серверах».", style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted)
    }
}

/** Giving one profile away: QR, link, one line of warning (spec 7.2). */
@Composable
fun GiveScreen(dest: Dest.Give) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val actions = remember(container) { ProfileActions(container) }
    val profiles by container.profiles.profiles.collectAsState()
    val p: Profile = profiles.firstOrNull { it.id == dest.profile } ?: return
    val link by produceState<Result<String>?>(null, p) { value = actions.link(p) }
    ScreenScaffold(p.name, onBack = { navigator.back() }) {
        when (val l = link) {
            null -> ProgressLine("Собираю ссылку…")
            else -> l.fold(
                onSuccess = { text ->
                    QrView(text, container.platform)
                    ShareLinkActions(text)
                    Text(
                        if (p.stream) "По этой ссылке подключится любой, у кого она есть."
                        else "По этой ссылке подключится любой. Одновременно работает одно устройство — второе вытеснит первое.",
                        style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
                    )
                },
                onFailure = { Banner(it.message ?: "Этим профилем нельзя поделиться", Tone.Danger) },
            )
        }
    }
}
