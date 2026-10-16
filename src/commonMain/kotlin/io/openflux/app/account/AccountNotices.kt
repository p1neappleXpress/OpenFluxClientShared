package io.openflux.app.account

import io.openflux.app.text.Fmt
import io.openflux.desktop.model.BotState
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.service.BotAccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Something the app tells the person by itself (a system notification): the bot used to send it as a message. */
data class AccountNotice(val key: String, val title: String, val text: String)

/**
 * What to tell the person now, and the keys of everything that holds now. [noticed] is what holds and was already said:
 * a thing is said when it starts to hold, once, and again only after it stopped holding (a connection paused, resumed,
 * paused again). Keys starting with "~" are said to nobody: they remember a state ("a subscription held") so that its
 * end can be told from "there never was one".
 */
fun accountNotices(s: BotState, noticed: Set<String>, nowMillis: Long, offsetSeconds: Int): Pair<List<AccountNotice>, Set<String>> {
    val session = s.session ?: return Pair(emptyList(), emptySet())
    val holds = linkedMapOf<String, AccountNotice?>()
    val sub = s.subscription
    when {
        // Not asked yet (or the answer failed): what was known stays as it was.
        sub == null -> noticed.filter { it == ACTIVE }.forEach { holds[it] = null }
        sub.isActive -> {
            holds[ACTIVE] = null
            val days = if (sub.expiresAt > 0) daysLeft(sub.expiresAt, nowMillis) else -1
            if (days in 1..3) {
                val way = if (session.viaTelegram) "Продлите место в @${OpenFluxBot.USERNAME} или купите новое в разделе «Подписка»"
                else "Купите новое место в разделе «Подписка»"
                holds["ending:${sub.expiresAt}"] = AccountNotice(
                    "ending:${sub.expiresAt}", "Подписка заканчивается",
                    Fmt.daysLeft(days).replaceFirstChar { it.uppercase() } + ", до ${Fmt.date(sub.expiresAt, offsetSeconds)}. $way",
                )
            }
        }
        ACTIVE in noticed || ENDED in noticed ->
            holds[ENDED] = AccountNotice(ENDED, "Подписка закончилась", "Подключения не работают. Купите подписку в разделе «Подписка»")
    }
    if (s.connectionNote == BotAccountManager.PAUSED_NOTE || s.connectionNote == BotAccountManager.PAUSED_NOTE_EMAIL) {
        holds[PAUSED] = AccountNotice(PAUSED, "Подключение приостановлено", s.connectionNote)
    }
    s.pendingInvoice?.invoice?.takeIf { it.paid }?.let { inv ->
        holds["paid:${inv.id}"] = AccountNotice("paid:${inv.id}", "Оплата получена", "Место добавлено, подключение появится само")
    }
    val say = holds.filter { (k, n) -> n != null && k !in noticed }.values.filterNotNull()
    return Pair(say, holds.keys)
}

private const val ACTIVE = "~active"
private const val ENDED = "ended"
private const val PAUSED = "paused"

/**
 * Watches the account and tells [notices] what to show, each thing once: what was said is kept with the account
 * ([save], e.g. [BotAccountManager.setNoticed]), so a restart does not say it again. The platform shows them (the
 * desktop through its tray).
 */
class AccountNotifier(
    private val state: StateFlow<BotState>,
    private val save: suspend (Set<String>) -> Unit,
    private val now: () -> Long,
    private val offsetSeconds: () -> Int,
) {
    private val flow = MutableSharedFlow<AccountNotice>(extraBufferCapacity = 16)
    val notices: SharedFlow<AccountNotice> = flow.asSharedFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            state.collect { s ->
                val noticed = s.noticed.toSet()
                val (say, holds) = accountNotices(s, noticed, now(), offsetSeconds())
                if (holds != noticed) save(holds)
                say.forEach { flow.emit(it) }
            }
        }
    }
}
