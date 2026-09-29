package io.openflux.desktop.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.ui.LocalScrollbars
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.components.AppCard
import io.openflux.desktop.ui.components.AppIcons
import io.openflux.desktop.ui.components.Banner
import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.IconBubble
import io.openflux.desktop.ui.components.LocalToaster
import io.openflux.desktop.ui.components.PageHeader
import io.openflux.desktop.ui.components.StatusBadge
import io.openflux.desktop.ui.components.TextAction
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.profiles.ProfilesTab
import io.openflux.desktop.ui.shell.LocalShell
import io.openflux.desktop.ui.theme.AppTheme
import org.jetbrains.compose.resources.painterResource

object AccountsTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 2u, title = "Аккаунты", icon = painterResource(AppIcons.Person))

    @Composable
    override fun Content() {
        val container = LocalAppContainer.current
        val model = rememberScreenModel { AccountsScreenModel(container) }
        LaunchedEffect(Unit) { container.accounts.checkAllInBackground() }
        AccountsScreen(model)
    }
}

@Composable
private fun AccountsScreen(model: AccountsScreenModel) {
    val cards by model.cards.collectAsState()
    val scroll = rememberScrollState()
    val scrollbars = LocalScrollbars.current
    val container = LocalAppContainer.current
    val now = container.platform.now()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll)
                .padding(horizontal = AppTheme.spacing.page, vertical = AppTheme.spacing.xl),
        ) {
            PageHeader("Аккаунты", "Войдите один раз — документы и вход на своих нодах OpenFlux сделает сам") {
                if (cards.any { it.status !is AuthStatus.SignedOut }) {
                    AppButton("Проверить все", model::checkAll, style = ButtonStyle.Secondary)
                }
            }
            Spacer(Modifier.height(AppTheme.spacing.xl))
            Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.l)) {
                model.error?.let { Banner(it, Tone.Danger, icon = Icons.Rounded.ErrorOutline) }
                cards.forEach { card -> AccountCardView(card, model, now) }
                Text(
                    "Cups.online и Direct входа не требуют. Пароль OpenFlux не видит и не хранит: " +
                        "остаётся только сессия, на этом устройстве и на ваших нодах.",
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.textSecondary,
                )
            }
        }
        scrollbars.Vertical(scroll, Modifier.align(Alignment.CenterEnd))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountCardView(card: AccountCard, model: AccountsScreenModel, now: Long) {
    val (text, tone) = AccountsScreenModel.statusText(card.status, now)
    val busy = card.status is AuthStatus.Busy || card.status is AuthStatus.Checking
    val shell = LocalShell.current
    val toaster = LocalToaster.current
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(AppIcons.byName(card.kind.icon))
            Spacer(Modifier.width(AppTheme.spacing.m))
            Column(Modifier.weight(1f)) {
                Text(card.kind.label, style = AppTheme.typography.sectionTitle, color = AppTheme.colors.text)
                Text(
                    usage(card),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(AppTheme.spacing.m))
        StatusBadge(text, tone)
        Spacer(Modifier.height(AppTheme.spacing.m))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
            val kind = card.kind
            when (val status = card.status) {
                AuthStatus.SignedOut -> if (kind.signsIn) AppButton("Войти в ${kind.label}", { model.signIn(kind) })
                else Text(
                    "Вход через MAX пока не поддерживается: токен и ID вставьте в профиль вручную.",
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.textSecondary,
                )
                // A check is one short request; only an open page can be cancelled.
                is AuthStatus.Checking -> Unit
                is AuthStatus.Busy -> AppButton("Отмена", model::cancel, style = ButtonStyle.Secondary)
                is AuthStatus.SignedIn -> {
                    if (kind.createsDocuments) AppButton("Создать документ", { model.createDocument(kind) }, leadingResource = AppIcons.Add)
                    AppButton("Проверить", { model.check(kind) }, style = if (kind.createsDocuments) ButtonStyle.Secondary else ButtonStyle.Primary)
                    AppButton("Выйти", { model.signOut(kind) }, style = ButtonStyle.Ghost)
                }
                is AuthStatus.Expired -> {
                    AppButton("Войти заново", { model.signIn(kind) })
                    AppButton("Выйти", { model.signOut(kind) }, style = ButtonStyle.Ghost)
                }
                is AuthStatus.NeedsCheck -> {
                    AppButton("Войти заново", { model.signIn(kind) })
                    AppButton("Проверить", { model.check(kind) }, style = ButtonStyle.Secondary)
                }
                is AuthStatus.Failed -> {
                    AppButton("Проверить снова", { model.check(kind) })
                    AppButton("Войти заново", { model.signIn(kind) }, style = ButtonStyle.Secondary)
                    if (status.login.isNotEmpty()) AppButton("Выйти", { model.signOut(kind) }, style = ButtonStyle.Ghost)
                }
            }
        }
        if (!busy && !card.kind.createsDocuments && card.status is AuthStatus.SignedIn) {
            Spacer(Modifier.height(AppTheme.spacing.s))
            Text(
                "Документ ${card.kind.label} пока создайте на сайте и вставьте ссылку в профиль.",
                style = AppTheme.typography.caption,
                color = AppTheme.colors.textSecondary,
            )
        }
        val created = model.created
        if (created != null && created.kind == card.kind) {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Banner("Документ готов: ${created.url}", Tone.Success, icon = Icons.Rounded.CheckCircle)
            Spacer(Modifier.height(AppTheme.spacing.xs))
            Text(
                "Документ — место встречи клиента и ноды: ноде нужна эта же ссылка и тот же ключ. " +
                    "Добавьте ссылку в профиль своей ноды или создайте профиль и передайте его ключ ноде.",
                style = AppTheme.typography.caption,
                color = AppTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(AppTheme.spacing.s))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                AppButton("Создать профиль", {
                    model.createProfileFromDocument()?.let { profile ->
                        shell.focusProfileId = profile.id
                        shell.open(ProfilesTab)
                        toaster.show("Профиль «${profile.name}» создан: ноде нужны тот же документ и ключ", Tone.Success)
                    }
                })
                AppButton("Скопировать ссылку", {
                    model.copyDocument()
                    toaster.show("Ссылка скопирована", Tone.Success)
                }, style = ButtonStyle.Secondary)
                TextAction("Скрыть", { model.created = null }, Modifier.padding(top = 10.dp))
            }
        }
    }
}

private fun usage(card: AccountCard): String {
    val what = when (card.kind) {
        AccountKind.Yandex -> "Yandex Docs, Volga и Board"
        AccountKind.Mailru -> "Mail.ru Docs"
        AccountKind.Max -> "MAX (OneMe)"
    }
    return when (card.usedBy) {
        0 -> "Для профилей $what"
        1 -> "Используется в 1 профиле · $what"
        else -> "Используется в ${card.usedBy} профилях · $what"
    }
}
