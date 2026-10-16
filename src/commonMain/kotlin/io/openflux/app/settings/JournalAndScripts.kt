package io.openflux.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.openflux.app.components.Banner
import io.openflux.app.components.Collapsible
import io.openflux.app.components.ConfirmDialog
import io.openflux.app.components.Field
import io.openflux.app.components.IconAction
import io.openflux.app.components.LocalToaster
import io.openflux.app.components.PrimaryButton
import io.openflux.app.components.ProgressLine
import io.openflux.app.components.RowItem
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.components.SecondaryButton
import io.openflux.app.components.Section
import io.openflux.app.components.Segmented
import io.openflux.app.components.Sheet
import io.openflux.app.components.TextAction
import io.openflux.app.components.Toggle
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.shell.LocalNavigator
import io.openflux.app.text.Redact
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.ScriptSource
import io.openflux.desktop.model.ScriptUpdateMessages
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.app.platform.LocalScrollbars
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The journal (spec 8.4): search, filter; copy, clear, autoscroll and masking in the menu. */
@Composable
fun JournalScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val settings by container.settings.settings.collectAsState()
    val all by remember(container) {
        combine(container.connection.logs, container.nodeWizard.logs, container.phpHosting.logs, ::mergeJournal)
    }.collectAsState(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(JournalFilter.All) }
    var menu by remember { mutableStateOf(false) }
    val shown = journalLines(all, filter, query)
    val list = rememberLazyListState()
    LaunchedEffect(shown.size, settings.logAutoScroll) { if (settings.logAutoScroll && shown.isNotEmpty()) list.scrollToItem(shown.lastIndex) }
    ScreenScaffold(
        "Журнал",
        onBack = { navigator.back() },
        scroll = false,
        actions = {
            Box {
                IconAction(Icons.Rounded.MoreVert, "Ещё", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Копировать показанное") }, {
                        menu = false
                        container.platform.setClipboardText(journalText(shown, settings.maskSensitive))
                        toaster.show("Скопировано строк: ${shown.size}")
                    })
                    DropdownMenuItem({ Text("Очистить") }, {
                        menu = false
                        container.connection.clearLogs(); container.nodeWizard.clearLogs(); container.phpHosting.clearLogs()
                    })
                    DropdownMenuItem({ Text((if (settings.logAutoScroll) "✓ " else "") + "Автопрокрутка") }, {
                        menu = false; container.settings.update { it.copy(logAutoScroll = !it.logAutoScroll) }
                    })
                    DropdownMenuItem({ Text((if (settings.maskSensitive) "✓ " else "") + "Скрывать адреса и ключи") }, {
                        menu = false; container.settings.update { it.copy(maskSensitive = !it.maskSensitive) }
                    })
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(vertical = AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
            Field(query, { query = it }, "Поиск в журнале")
            Segmented(JournalFilter.entries, filter, { filter = it }, label = { it.label })
            if (shown.isEmpty()) {
                Text(if (all.isEmpty()) "Журнал пуст" else "Нет строк под фильтр", style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    SelectionContainer {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                            // No keys: the core and the wizards count their lines separately, so ids repeat.
                            items(shown) { line ->
                                Text(
                                    if (settings.maskSensitive) Redact.apply(line.text) else line.text,
                                    style = AppTheme.type.mono,
                                    color = when (line.level) {
                                        LogLevel.Error -> AppTheme.colors.danger
                                        LogLevel.Warning -> AppTheme.colors.warning
                                        LogLevel.Success -> AppTheme.colors.success
                                        LogLevel.Debug -> AppTheme.colors.textMuted
                                        LogLevel.Info -> AppTheme.colors.text
                                    },
                                )
                            }
                        }
                    }
                    LocalScrollbars.current.Vertical(list, Modifier.align(androidx.compose.ui.Alignment.CenterEnd))
                }
            }
        }
    }
}

/** The JS transports (spec 8.5): import, updates, roll back, turn off, delete. */
@Composable
fun ScriptsScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val scripts by container.scripts.scripts.collectAsState()
    val reports by container.scriptUpdater.reports.collectAsState()
    val busy by container.scriptUpdater.busy.collectAsState()
    val settings by container.settings.settings.collectAsState()
    var addOpen by remember { mutableStateOf(false) }
    var updateFor by remember { mutableStateOf<InstalledScript?>(null) }
    var deleteFor by remember { mutableStateOf<InstalledScript?>(null) }
    var rev by remember { mutableStateOf(0) }
    LaunchedEffect(settings.experimental) { if (!settings.experimental) navigator.back() }
    ScreenScaffold("Транспорты на JS", onBack = { navigator.back() }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
            PrimaryButton("Импортировать", { addOpen = true })
            SecondaryButton("Проверить обновления", {
                scope.launch {
                    val n = container.scriptUpdater.checkAll()
                    toaster.show(if (n == 0) "Все транспорты свежие" else "Есть обновления: $n")
                }
            }, enabled = busy.isEmpty())
        }
        val (own, added) = scripts.partition { it.official || it.source == ScriptSource.Bundled }
        val row: @Composable (InstalledScript) -> Unit = { s ->
            val report = reports[s.id]
            val working = s.id in busy
            Section(s.name) {
                Toggle(
                    (if (s.official) "OpenFlux" else "Сторонний") + " · версия ${s.version.ifBlank { "—" }}",
                    s.enabled, { container.scripts.setEnabled(s.id, it) },
                    subtitle = if (s.hasSettings) "Настраивается в профиле, где используется" else null,
                )
                if (report != null && report.available) {
                    RowItem("Доступна ${report.latest}", trailing = { TextAction("Обновить", { updateFor = s }, enabled = !working) })
                } else if (report != null && (report.blocked || report.failed)) {
                    RowItem(ScriptUpdateMessages.failure(report.code))
                }
                if (working) RowItem("Работаю…")
                if (rev >= 0 && container.scripts.hasPrevious(s.id)) RowItem("Вернуть предыдущую версию", onClick = {
                    scope.launch {
                        val r = container.scriptUpdater.rollback(s.id)
                        rev++
                        toaster.show(if (r.installed) "Вернули версию ${r.latest}" else ScriptUpdateMessages.failure(r.code))
                    }
                }, enabled = !working)
                ValueRow("Отпечаток ключа", s.shortFingerprint, mono = true, onCopy = { container.platform.setClipboardText(s.fingerprint); toaster.show("Отпечаток скопирован") })
                ValueRow("Источник", s.source.label)
                RowItem("Удалить", onClick = { deleteFor = s })
            }
        }
        if (added.isEmpty()) Text("Своих транспортов пока нет. Импортируйте из GitHub или файла.", style = AppTheme.type.body, color = AppTheme.colors.textSecondary)
        else added.forEach { row(it) }
        if (own.isNotEmpty()) {
            Section(null) {
                Collapsible("Транспорты OpenFlux (${own.size})") {
                    Toggle("Обновлять автоматически", settings.autoUpdateScripts, { v -> container.settings.update { it.copy(autoUpdateScripts = v) } }, subtitle = "Подписанные ключом OpenFlux, без смены формата обмена")
                }
            }
            own.forEach { row(it) }
        }
        Text("JS-транспорт работает без песочницы: полный доступ к сети. Ставьте только от авторов, которым доверяете.", style = AppTheme.type.bodySmall, color = AppTheme.colors.warning)
    }
    if (addOpen) AddScriptSheet { addOpen = false }
    updateFor?.let { s ->
        val report = reports[s.id]
        if (report == null) updateFor = null else ConfirmDialog(
            title = "Обновить «${s.name}»?",
            text = "${report.current.ifBlank { "—" }} → ${report.latest}" +
                (if (report.notes.isNotBlank()) "\n\n${report.notes}" else "") +
                (if (report.wireBreak) "\n\nОбновление меняет формат обмена: нода должна получить такое же, иначе связи не будет." else "") +
                "\n\n" + (if (report.official) "Подписано ключом OpenFlux." else "Подписано тем же ключом автора (${s.shortFingerprint})."),
            confirm = if (report.wireBreak) "Всё равно обновить" else "Обновить",
            onConfirm = {
                updateFor = null
                scope.launch {
                    val r = container.scriptUpdater.apply(s.id, allowWireBreak = report.wireBreak)
                    rev++
                    toaster.show(if (r.installed) "${s.name}: теперь ${r.latest}" else ScriptUpdateMessages.failure(r.code))
                }
            },
            onDismiss = { updateFor = null },
            danger = report.wireBreak,
        )
    }
    deleteFor?.let { s ->
        ConfirmDialog(
            title = "Удалить «${s.name}»?",
            text = "Профили с этим транспортом перестанут подключаться.",
            confirm = "Удалить",
            onConfirm = { deleteFor = null; container.scripts.delete(s.id) },
            onDismiss = { deleteFor = null },
            danger = true,
        )
    }
}

private val json = Json { ignoreUnknownKeys = true }

private data class ScriptPreview(val name: String, val version: String, val fingerprint: String, val official: Boolean, val valid: Boolean, val params: Int)

/** Importing a transport: from a link or a file, checked against its author's key before anything is installed. */
@Composable
private fun AddScriptSheet(onClose: () -> Unit) {
    val container = LocalAppContainer.current
    val platform = container.platform
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ScriptPreview?>(null) }
    var data by remember { mutableStateOf(ByteArray(0)) }
    var sig by remember { mutableStateOf(ByteArray(0)) }
    var source by remember { mutableStateOf(ScriptSource.GitHub) }
    var origin by remember { mutableStateOf("") }
    fun authorKey() = key.trim().ifBlank { platform.officialScriptKey }
    fun inspect(bytes: ByteArray, signature: ByteArray, src: ScriptSource, from: String) {
        data = bytes; sig = signature; source = src; origin = from
        val r = runCatching { json.parseToJsonElement(platform.inspectTransport(bytes, signature, authorKey())).jsonObject }.getOrNull()
        if (r == null || r["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            error = r?.get("error")?.jsonPrimitive?.contentOrNull ?: "Не удалось прочитать транспорт"
            return
        }
        error = null
        preview = ScriptPreview(
            name = r["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            version = r["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            fingerprint = r["fingerprint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            official = r["official"]?.jsonPrimitive?.booleanOrNull == true,
            valid = r["signature"]?.jsonPrimitive?.contentOrNull == "valid",
            params = (r["params"] as? JsonArray)?.size ?: 0,
        )
    }
    Sheet(onDismiss = onClose, title = if (preview == null) "Импорт транспорта" else "Проверьте транспорт") {
        Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            val p = preview
            if (p == null) {
                Field(url, { url = it; error = null }, "Ссылка на .js или .flux", placeholder = "https://raw.githubusercontent.com/…", mono = true)
                Field(key, { key = it }, "Ключ автора (hex); пусто — ключ OpenFlux", mono = true)
                TextAction("Из файла…", {
                    scope.launch {
                        val path = platform.pickFile("Файл транспорта", listOf("flux", "js")) ?: return@launch
                        val bytes = platform.readBytes(path)
                        if (bytes == null) error = "Не удалось прочитать файл" else inspect(bytes, ByteArray(0), ScriptSource.File, path)
                    }
                })
                if (busy) ProgressLine("Скачиваю…")
                else PrimaryButton("Проверить", {
                    busy = true; error = null
                    scope.launch {
                        val u = url.trim()
                        val bytes = platform.fetchBytes(u)
                        if (bytes == null) error = "Не удалось скачать по ссылке"
                        else inspect(bytes, if (u.endsWith(".js")) platform.fetchBytes("$u.sig") ?: ByteArray(0) else ByteArray(0), if (u.contains("github", ignoreCase = true)) ScriptSource.GitHub else ScriptSource.Link, u)
                        busy = false
                    }
                }, Modifier.fillMaxWidth(), enabled = url.isNotBlank())
            } else {
                Section(null) {
                    ValueRow("Транспорт", "${p.name} · ${p.version.ifBlank { "—" }}")
                    ValueRow("Подпись", if (p.valid) "верна" else "не совпадает")
                    ValueRow("Автор", if (p.official) "OpenFlux" else "сторонний")
                    ValueRow("Отпечаток", p.fingerprint.chunked(2).take(8).joinToString(" "), mono = true)
                    ValueRow("Параметры", p.params.toString())
                }
                if (!p.valid) {
                    Banner("Подпись не совпадает с ключом автора: ставить нельзя. Проверьте ключ или источник", Tone.Danger)
                } else {
                    Banner("Без песочницы: доверяйте только этому автору и отпечатку", Tone.Warning)
                    if (busy) ProgressLine("Устанавливаю…")
                    else PrimaryButton("Доверять и установить", {
                        busy = true; error = null
                        scope.launch {
                            runCatching { container.scripts.install(data, sig, authorKey(), source, origin, platform.now()) }
                                .onSuccess { onClose() }
                                .onFailure { error = it.message ?: "Не удалось установить" }
                            busy = false
                        }
                    }, Modifier.fillMaxWidth())
                }
            }
            error?.let { Banner(it, Tone.Danger) }
        }
    }
}
