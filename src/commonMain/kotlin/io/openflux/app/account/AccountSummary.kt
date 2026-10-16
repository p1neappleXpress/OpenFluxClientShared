package io.openflux.app.account

import io.openflux.app.text.Fmt
import io.openflux.desktop.model.BotState

enum class SummaryTone { Ok, Attention, Unknown }

/**
 * What the window says of the OpenFlux account. GET /api/v1/subscription is a summary of the person's slots, not one
 * subscription: its plan and end are those of the slot that ends first and maxSessions is the number of slots, so with
 * several slots they are said as such. Pure, so it is tested and drawn in shots.
 */
data class AccountSummary(
    /** How the person signed in: "Telegram, @ivan", "Telegram", "по почте". */
    val signIn: String,
    /** One line: "Действует", "Заканчивается: осталось 2 дня", "Нет действующей подписки", "Обновляю…". */
    val state: String,
    val tone: SummaryTone,
    /** "Name" to "value" rows. */
    val facts: List<Pair<String, String>>,
    val active: Boolean,
    /** "Купить подписку", or "Купить ещё место" while one holds: every purchase is a new slot. */
    val buyLabel: String,
    /** A Telegram account renews a slot in the bot (the API cannot renew yet). */
    val renewInBot: Boolean,
    /** A word under the buttons, when there is one to say. */
    val note: String?,
)

/** Whole days until [expiresAt] (unix seconds), counted up: the last day is 1, not 0 as the service's days_left says. */
fun daysLeft(expiresAt: Long, nowMillis: Long): Int {
    val ms = expiresAt * 1000 - nowMillis
    return if (ms <= 0) 0 else ((ms + DAY_MS - 1) / DAY_MS).toInt()
}

/** The summary of the signed-in account, or null when nobody is signed in. */
fun accountSummary(s: BotState, nowMillis: Long, offsetSeconds: Int): AccountSummary? {
    val session = s.session ?: return null
    val signIn = when {
        !session.viaTelegram -> "по почте"
        session.username.isNotBlank() -> "Telegram, @${session.username}"
        else -> "Telegram"
    }
    val sub = s.subscription
    if (sub == null || !sub.isActive) {
        return AccountSummary(
            signIn = signIn,
            state = if (sub == null) "Обновляю…" else "Нет действующей подписки",
            tone = if (sub == null) SummaryTone.Unknown else SummaryTone.Attention,
            facts = emptyList(), active = false, buyLabel = "Купить подписку", renewInBot = false, note = null,
        )
    }
    val days = if (sub.expiresAt > 0) daysLeft(sub.expiresAt, nowMillis) else -1
    val ending = days in 0..ATTENTION_DAYS
    val end = if (sub.expiresAt > 0) Fmt.date(sub.expiresAt, offsetSeconds) + ", " + Fmt.daysLeft(days) else null
    val facts = buildList {
        if (sub.maxSessions > 1) {
            add("Мест" to sub.maxSessions.toString())
            if (end != null) add("Ближайшее окончание" to end + sub.planName.takeIf { it.isNotBlank() }?.let { " (тариф $it)" }.orEmpty())
            if (sub.trafficUsedBytes > 0) add("Трафик всех мест" to Fmt.bytes(sub.trafficUsedBytes))
        } else {
            if (sub.planName.isNotBlank()) add("Тариф" to sub.planName)
            if (end != null) add("Срок" to "до $end")
            if (sub.trafficLimitBytes > 0) add("Трафик" to "${Fmt.bytes(sub.trafficUsedBytes)} из ${Fmt.bytes(sub.trafficLimitBytes)}")
        }
    }
    return AccountSummary(
        signIn = signIn,
        state = if (ending) "Заканчивается: ${Fmt.daysLeft(days)}" else "Действует",
        tone = if (ending) SummaryTone.Attention else SummaryTone.Ok,
        facts = facts,
        active = true,
        buyLabel = "Купить ещё место",
        renewInBot = session.viaTelegram,
        note = if (ending && !session.viaTelegram) "Продлить место из приложения пока нельзя. Когда срок закончится, купите подписку заново" else null,
    )
}

private const val DAY_MS = 86_400_000L
private const val ATTENTION_DAYS = 3
