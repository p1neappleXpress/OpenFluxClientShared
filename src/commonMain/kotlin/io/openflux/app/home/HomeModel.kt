package io.openflux.app.home

import io.openflux.app.servers.isOwnNode
import io.openflux.app.text.Fmt
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionState
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.managed
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.AppContainer

/** The one line Home shows when something needs the person (spec 6, item 4), most urgent first. */
sealed interface HomeBanner {
    data class Failed(val message: String) : HomeBanner
    data object NeedsAdmin : HomeBanner
    data class Account(val kind: AccountKind, val needsCheck: Boolean) : HomeBanner
    data class Subscription(val look: SubLook) : HomeBanner
    data object ThisDevice : HomeBanner
}

/** How a subscription reads: a word, a line about its end, whether it wants the person. */
data class SubLook(val badge: String, val line: String, val attention: Boolean)

/** Days left at which Home starts asking to renew. */
const val RENEW_SOON_DAYS = 3

/** [nowMillis] is the platform clock; [offsetSeconds] its zone east of UTC; `expires` is unix seconds. */
fun SubscriptionState.look(nowMillis: Long, offsetSeconds: Int): SubLook {
    val nowSeconds = nowMillis / 1000
    val trial = account?.plan?.id == "trial"
    fun ended() = SubLook("Срок вышел", "Закончилась ${Fmt.date(expires, offsetSeconds)}.", true)
    return when (status) {
        SubStatus.REVOKED -> SubLook("Отключена", "Доступ отключён. Обратитесь в поддержку.", true)
        SubStatus.EXPIRED -> if (expires == 0L) SubLook("Нет подписки", "Подписка не оформлена.", true) else ended()
        else -> {
            val left = daysLeft(nowSeconds)
            when {
                expires == 0L -> SubLook("Активна", "Без ограничения срока.", false)
                left == null -> ended()
                else -> SubLook(
                    if (trial) "Пробный период" else if (left <= RENEW_SOON_DAYS) "Скоро закончится" else "Активна",
                    "До ${Fmt.date(expires, offsetSeconds)}: ${Fmt.daysLeft(left)}.",
                    left <= RENEW_SOON_DAYS,
                )
            }
        }
    }
}

/** Home's actions (spec 6); the screen reads the container's flows directly. */
class HomeModel(private val container: AppContainer) {
    private val connection = container.connection
    private val settings = container.settings

    /** The profile the button connects: the chosen one, else the first. */
    fun selected(list: List<Profile> = container.profiles.profiles.value): Profile? =
        list.firstOrNull { it.id == settings.settings.value.selectedProfileId } ?: list.firstOrNull()

    /** Chooses [profile] and nothing more: a running connection switches only on [switchTo]. */
    fun choose(profile: Profile) {
        settings.update { it.copy(selectedProfileId = profile.id) }
    }

    /** Moves a running client connection to [profile]. A running exit node stays itself. */
    fun switchTo(profile: Profile) {
        if (connection.state.value.isActive && settings.settings.value.mode == ConnectionMode.Client) connection.connect(profile, ConnectionMode.Client)
    }

    fun toggle() = io.openflux.app.shell.toggleConnection(container)

    /** The full tunnel restarts a running connection in the new way. */
    fun setFullTunnel(on: Boolean) {
        settings.update { it.copy(fullTunnel = on) }
        val s = connection.state.value
        if (s.isActive) s.profile?.let { connection.connect(it) }
    }

    fun setSystemProxy(on: Boolean) = settings.update { it.copy(systemProxy = on) }

    /** The drawer's groups, in order, without empty ones. */
    fun groups(list: List<Profile>): List<Pair<String, List<Profile>>> = listOf(
        "Подписка" to list.filter { it.managed },
        "Мои серверы" to list.filter { !it.managed && it.isOwnNode },
        "Остальные" to list.filter { !it.managed && !it.isOwnNode },
    ).filter { it.second.isNotEmpty() }

    /** Services of [profile] whose saved sign-in stopped working. */
    fun accountAlert(profile: Profile, status: Map<AccountKind, AuthStatus>): HomeBanner.Account? =
        profile.carriers.mapNotNull { AccountKind.of(it.type) }.distinct().firstNotNullOfOrNull { kind ->
            when (status[kind]) {
                is AuthStatus.Expired -> HomeBanner.Account(kind, needsCheck = false)
                is AuthStatus.NeedsCheck -> HomeBanner.Account(kind, needsCheck = true)
                else -> null
            }
        }

    fun banner(
        state: ConnectionState,
        settings: AppSettings,
        profile: Profile?,
        status: Map<AccountKind, AuthStatus>,
        subscription: SubscriptionState?,
        nowMillis: Long,
    ): HomeBanner? {
        val platform = container.platform
        if (state is ConnectionState.Failed) return HomeBanner.Failed(state.message)
        if (settings.mode != ConnectionMode.Exit && settings.fullTunnel && platform.fullTunnelSupported && !platform.elevated && platform.fullTunnelPrompt == null) {
            return HomeBanner.NeedsAdmin
        }
        profile?.let { p -> accountAlert(p, status)?.let { return it } }
        subscription?.look(nowMillis, platform.utcOffsetSeconds)?.takeIf { it.attention }?.let { return HomeBanner.Subscription(it) }
        if (state.isActive && settings.mode == ConnectionMode.Exit) return HomeBanner.ThisDevice
        return null
    }

    /** Services whose sign-in could go to the connected node now, by hand. */
    fun pushable(profile: Profile, state: ConnectionState): List<AccountKind> {
        if (state !is ConnectionState.Connected || state.mode != ConnectionMode.Client || !profile.session) return emptyList()
        return profile.carriers.mapNotNull { c -> AccountKind.of(c.type)?.takeIf { c.type in it.signedInTransports } }
            .distinct().filter { container.accounts.validSession(it) != null }
    }
}

/** The state in a few words: under the button on Home and in the Android notification. */
fun statusLine(state: ConnectionState): String = when (state) {
    ConnectionState.Idle -> "Отключено"
    is ConnectionState.Connecting -> if (state.mode == ConnectionMode.Exit) "Запуск ноды…" else "Подключаюсь…"
    is ConnectionState.Connected -> if (state.mode == ConnectionMode.Exit) "Нода работает" else "Подключено"
    is ConnectionState.Reconnecting -> "Связь прервалась, восстанавливаю…"
    is ConnectionState.Disconnecting -> "Отключаюсь…"
    is ConnectionState.Failed -> "Не подключено"
}

/** A profile's dot: green only while connected through it, busy while it connects, else off. */
fun profileDot(state: ConnectionState, profileId: String): io.openflux.app.components.Status = when {
    state.profile?.id != profileId -> io.openflux.app.components.Status.Off
    state is ConnectionState.Connected -> io.openflux.app.components.Status.On
    state.isActive -> io.openflux.app.components.Status.Busy
    else -> io.openflux.app.components.Status.Off
}
