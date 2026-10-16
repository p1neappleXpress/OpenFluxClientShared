package io.openflux.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.openflux.app.components.Banner
import io.openflux.app.components.Field
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.RowItem
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.Section
import io.openflux.app.components.TextAction
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.text.Fmt
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.BotDocument
import io.openflux.desktop.model.BotSlot

/** A place's name for people: the person's own, else "#19" (the bot's number), else the tariff. */
fun slotTitle(slot: BotSlot): String = slot.alias.ifBlank {
    when {
        slot.connectionNo > 0 -> "Место #${slot.connectionNo}"
        slot.planName.isNotBlank() -> slot.planName
        else -> "Место"
    }
}

/** What a place is doing, in a word: works, is paused, is free, or waits for its own documents (manual). */
fun slotStateText(slot: BotSlot): String = when {
    slot.manual && slot.needsSetup -> "не настроено"
    slot.state == "running" -> "работает"
    slot.state == "paused" -> "приостановлено"
    slot.state == "free" -> "свободно"
    else -> slot.state
}

/** One line of facts about a place: tariff · what it is doing · until when. */
fun slotFacts(slot: BotSlot, offsetSeconds: Int): String = buildList {
    if (slot.planName.isNotBlank()) add(slot.planName)
    add(slotStateText(slot))
    if (slot.expiresAt > 0) add("до ${Fmt.date(slot.expiresAt, offsetSeconds)}")
}.joinToString(" · ")

/** The name of a document's service, in Russian. */
fun documentKindName(kind: String): String = when (kind) {
    "yandex" -> "Яндекс Диск"
    "mailru" -> "Облако Mail.ru"
    "telemost" -> "Телемост"
    "boards" -> "Доски"
    "cups" -> "Cups"
    else -> "Документ"
}

/** What is known of a document: checked, not checked yet, or the service's own word. */
fun documentStateText(d: BotDocument): String = when (d.status) {
    "ok" -> "проверен"
    "unknown" -> "ещё не проверялся: первая отправка покажет"
    else -> d.status.ifBlank { "ещё не проверялся" }
}

/**
 * The account's places (the bot's "Мои подключения"): each with its state and the one thing it can do now —
 * Настроить (a manual place with no documents yet), Запустить (a paused or free place) or Остановить (a running one).
 * Tapping a row opens the place: its connection (connect and share) or, for a manual one, its documents.
 */
@Composable
fun SlotsSection(
    slots: List<BotSlot>,
    offsetSeconds: Int,
    idle: Boolean,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
    onOpen: (BotSlot) -> Unit,
) {
    if (slots.isEmpty()) return
    Section("Места") {
        for (slot in slots) {
            RowItem(
                slotTitle(slot),
                subtitle = slotFacts(slot, offsetSeconds),
                enabled = idle,
                trailing = {
                    when {
                        slot.needsSetup -> TextAction("Настроить", { onOpen(slot) }, enabled = idle)
                        slot.canStart -> TextAction("Запустить", { onStart(slot.id) }, enabled = idle)
                        slot.canStop -> TextAction("Остановить", { onStop(slot.id) }, enabled = idle)
                    }
                },
                onClick = { if (slot.connectToken.isNotEmpty() || slot.manual) onOpen(slot) },
            )
        }
    }
}

/** What the person can do on a place's sheet. */
data class SlotSetupActions(
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onAddDocument: (String) -> Unit = {},
    val onRemoveDocument: (String) -> Unit = {},
    val onCheckDocument: (String) -> Unit = {},
    val onConnect: () -> Unit = {},
)

/**
 * A place's sheet: what it is (tariff, state, term, its connection's number), and — for a manual place — its own
 * documents. A manual connection takes *all* the place's documents, in their order (the first working one becomes
 * the main route; Телемост and Доски always go last — the bot's own rule), so nothing is picked here. Documents are not
 * required: without them the service makes cups.online and Avito rooms, which need no account. Documents can be edited
 * only while the place holds no live connection.
 * Drawn from plain data, so it is tested and shot alone.
 */
@Composable
fun SlotSetupContent(
    slot: BotSlot,
    documents: List<BotDocument>?,
    busy: String?,
    error: String?,
    offsetSeconds: Int,
    actions: SlotSetupActions,
) {
    val idle = busy == null
    // A live connection holds the place's documents: they can be looked at, not edited, until it stops.
    val live = slot.canStop
    Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
        Section(null) {
            if (slot.planName.isNotBlank()) ValueRow("Тариф", slot.planName)
            ValueRow("Состояние", slotStateText(slot))
            if (slot.expiresAt > 0) ValueRow("Срок", "до ${Fmt.date(slot.expiresAt, offsetSeconds)}")
            if (slot.connectionNo > 0) ValueRow("Номер подключения", "#${slot.connectionNo}")
        }
        if (!slot.manual) {
            when {
                slot.canStart -> PrimaryButton("Запустить", actions.onStart, Modifier.fillMaxWidth(), enabled = idle)
                slot.canStop -> SecondaryButton("Остановить", actions.onStop, Modifier.fillMaxWidth(), enabled = idle)
            }
        } else {
            Text(
                "Свои документы: подключение берёт их все, по порядку — первый рабочий становится основным, " +
                    "Телемост и Доски идут последними. До пяти, разных видов; без них работают cups.online и Avito.",
                style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
            )
            when {
                documents == null -> ProgressLine("Загружаю документы…")
                documents.isEmpty() -> Text(
                    "Своих документов нет: подключение пойдёт через cups.online и Avito, им аккаунт не нужен. " +
                        "Добавьте документ (Яндекс Диск, Облако Mail.ru, Телемост, Доски), если нужен ещё транспорт.",
                    style = AppTheme.type.bodySmall, color = AppTheme.colors.textSecondary,
                )
                else -> Section("Документы") {
                    for (d in documents) {
                        RowItem(
                            d.label.ifBlank { documentKindName(d.kind) },
                            subtitle = documentStateText(d),
                            trailing = if (live) null else {
                                {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextAction("Проверить", { actions.onCheckDocument(d.id) }, enabled = idle)
                                        TextAction("Удалить", { actions.onRemoveDocument(d.id) }, enabled = idle, color = AppTheme.colors.danger)
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (!live) AddDocumentField(idle, actions.onAddDocument)
            when {
                live -> SecondaryButton("Остановить", actions.onStop, Modifier.fillMaxWidth(), enabled = idle)
                slot.canStart -> PrimaryButton("Запустить", actions.onConnect, Modifier.fillMaxWidth(), enabled = idle && documents != null)
                else -> PrimaryButton("Подключить", actions.onConnect, Modifier.fillMaxWidth(), enabled = idle && documents != null)
            }
        }
        busy?.let { ProgressLine(it) }
        error?.let { Banner(it, Tone.Danger) }
    }
}

@Composable
private fun AddDocumentField(idle: Boolean, onAdd: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
        Field(url, { url = it }, "Ссылка на документ", placeholder = "https://disk.yandex.ru/i/…")
        SecondaryButton(
            "Добавить документ",
            { val u = url.trim(); if (u.isNotEmpty()) { onAdd(u); url = "" } },
            Modifier.fillMaxWidth(),
            enabled = idle && url.isNotBlank(),
        )
    }
}
