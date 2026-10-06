package io.openflux.desktop.ui.scripts

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import io.openflux.desktop.ui.components.appClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.ScriptSource
import io.openflux.desktop.model.ScriptUpdateMessages
import io.openflux.desktop.model.ScriptUpdateReport
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.components.AppCard
import io.openflux.desktop.ui.components.AppDialog
import io.openflux.desktop.ui.components.AppIconButton
import io.openflux.desktop.ui.components.AppIcons
import io.openflux.desktop.ui.components.AppSwitch
import io.openflux.desktop.ui.components.AppTextField
import io.openflux.desktop.ui.components.Banner
import io.openflux.desktop.ui.components.ButtonRow
import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.KeyValueRow
import io.openflux.desktop.ui.components.LocalToaster
import io.openflux.desktop.ui.components.TextAction
import io.openflux.desktop.ui.components.PageHeader
import io.openflux.desktop.ui.components.SectionLabel
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.components.toneColor
import io.openflux.desktop.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.painterResource

private val json = Json { ignoreUnknownKeys = true }

private data class Preview(
    val name: String,
    val version: String,
    val fingerprint: String,
    val official: Boolean,
    val signature: String,
    val paramCount: Int,
)

/** Top-level tab: the full transport catalogue with convenient import. */
object ScriptsTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 4u, title = "Транспорты", icon = painterResource(AppIcons.Swap))

    @Composable
    override fun Content() = ScriptsScreen(LocalAppContainer.current)
}

@Composable
fun ScriptsScreen(container: AppContainer) {
    val scripts by container.scripts.scripts.collectAsState()
    val updater = container.scriptUpdater
    val reports by updater.reports.collectAsState()
    val busy by updater.busy.collectAsState()
    val settings by container.settings.settings.collectAsState()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var updateFor by remember { mutableStateOf<String?>(null) }
    // A rollback or an update changes the file under the record; re-read what is on disk.
    var rev by remember { mutableStateOf(0) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.spacing.page, vertical = AppTheme.spacing.xl),
    ) {
        PageHeader("Транспорты", "JS-транспорты на равных правах с обычными — добавляйте свои")
        Spacer(Modifier.height(AppTheme.spacing.xl))
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.l)) {
            ButtonRow {
                AppButton("Импортировать транспорт", { showAdd = true }, leading = Icons.Rounded.Add)
                AppButton(
                    "Проверить обновления",
                    {
                        scope.launch {
                            val n = updater.checkAll()
                            toaster.show(if (n == 0) "Все транспорты свежие" else "Есть обновления: $n", if (n == 0) Tone.Neutral else Tone.Accent)
                        }
                    },
                    style = ButtonStyle.Secondary,
                    enabled = busy.isEmpty(),
                    leading = Icons.Rounded.Refresh,
                )
            }
            // OpenFlux's own transports are the common case and rarely need a look: the
            // ones the user added come first, the rest is folded away below.
            val (own, added) = scripts.partition { it.official || it.source == ScriptSource.Bundled }
            val card: @Composable (InstalledScript) -> Unit = { s ->
                ScriptCard(
                    s,
                    report = reports[s.id],
                    busy = s.id in busy,
                    hasPrevious = rev >= 0 && container.scripts.hasPrevious(s.id),
                    onToggle = { container.scripts.setEnabled(s.id, it) },
                    onDelete = { container.scripts.delete(s.id) },
                    onCopy = { container.platform.setClipboardText(s.fingerprint) },
                    onUpdate = { updateFor = s.id },
                    onRollback = {
                        scope.launch {
                            val r = updater.rollback(s.id)
                            rev++
                            toaster.show(
                                if (r.installed) "Вернули версию ${r.latest}" else ScriptUpdateMessages.failure(r.code),
                                if (r.installed) Tone.Success else Tone.Danger,
                            )
                        }
                    },
                )
            }
            SectionLabel("Добавленные (${added.size})")
            if (added.isEmpty()) {
                Banner("Своих транспортов пока нет. Импортируйте из GitHub или файла — они появятся при выборе транспорта в профиле.", Tone.Neutral)
            } else {
                added.forEach { card(it) }
            }
            var ownOpen by remember { mutableStateOf(false) }
            val waiting = own.count { reports[it.id]?.let { r -> r.available || r.blocked } == true }
            FoldHeader(
                title = "Транспорты OpenFlux (${own.size})",
                note = if (waiting > 0) "обновлений: $waiting" else null,
                open = ownOpen,
                onToggle = { ownOpen = !ownOpen },
            )
            if (ownOpen) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Обновлять их сами", style = AppTheme.typography.body, color = AppTheme.colors.text)
                        Text(
                            "Подписанные ключом OpenFlux, без смены формата обмена. Остальные транспорты спросят.",
                            style = AppTheme.typography.caption,
                            color = AppTheme.colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.width(AppTheme.spacing.m))
                    AppSwitch(settings.autoUpdateScripts, { on -> container.settings.update { it.copy(autoUpdateScripts = on) } })
                }
                own.forEach { card(it) }
            }
            Banner(
                "Скрипт-транспорт работает без песочницы: полный доступ к сети. Подпись автора обязательна и проверяется при каждом запуске — импортируйте только из доверенного источника.",
                Tone.Warning,
            )
        }
    }

    if (showAdd) AddScriptDialog(container) { showAdd = false }

    updateFor?.let { id ->
        val script = scripts.firstOrNull { it.id == id }
        val report = reports[id]
        if (script == null || report == null) updateFor = null else UpdateDialog(
            script, report,
            onApply = {
                updateFor = null
                scope.launch {
                    val r = updater.apply(id, allowWireBreak = report.wireBreak)
                    rev++
                    toaster.show(
                        if (r.installed) "${script.name}: теперь ${r.latest}" else ScriptUpdateMessages.failure(r.code),
                        if (r.installed) Tone.Success else Tone.Danger,
                    )
                }
            },
            onClose = { updateFor = null },
        )
    }
}

/** A tappable row that folds a section away; [note] is a short accent line (e.g. updates waiting). */
@Composable
private fun FoldHeader(title: String, note: String?, open: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val turn by animateFloatAsState(if (open) 180f else 0f)
    Row(
        Modifier.fillMaxWidth()
            .clip(AppTheme.shapes.field)
            .border(1.dp, AppTheme.colors.border, AppTheme.shapes.field)
            .appClickable(interaction, onClick = onToggle)
            .padding(horizontal = AppTheme.spacing.m, vertical = AppTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text, modifier = Modifier.weight(1f))
        if (note != null) {
            Text(note, style = AppTheme.typography.caption, color = toneColor(Tone.Accent))
            Spacer(Modifier.width(AppTheme.spacing.s))
        }
        Icon(Icons.Rounded.ExpandMore, if (open) "Свернуть" else "Развернуть", tint = AppTheme.colors.textSecondary, modifier = Modifier.rotate(turn))
    }
}

@Composable
private fun UpdateDialog(script: InstalledScript, report: ScriptUpdateReport, onApply: () -> Unit, onClose: () -> Unit) {
    AppDialog(
        title = "Обновление «${script.name}»",
        onDismiss = onClose,
        primary = if (report.wireBreak) "Всё равно обновить" else "Обновить",
        onPrimary = onApply,
        primaryEnabled = report.available,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            Text("${report.current.ifBlank { "—" }}  →  ${report.latest}", style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text)
            if (report.notes.isNotBlank()) {
                Text(report.notes, style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
            }
            if (report.wireBreak) {
                Banner(
                    "Обновление меняет формат обмена. Нода, к которой вы подключаетесь, должна получить такое же обновление, иначе связи не будет.",
                    Tone.Warning,
                )
            }
            Banner(
                if (report.official) "Подписано ключом OpenFlux." else "Подписано тем же ключом автора, что и установленная версия (отпечаток ${script.shortFingerprint}). Другой ключ приложение не приняло бы.",
                Tone.Neutral,
            )
        }
    }
}

@Composable
private fun ScriptCard(
    script: InstalledScript,
    report: ScriptUpdateReport?,
    busy: Boolean,
    hasPrevious: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onUpdate: () -> Unit,
    onRollback: () -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).background(AppTheme.colors.surfaceTonal, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(AppIcons.byName(script.icon)), null, tint = AppTheme.colors.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(AppTheme.spacing.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(script.name, style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text)
                    Spacer(Modifier.width(AppTheme.spacing.s))
                    Text(
                        if (script.official) "OpenFlux" else "сторонний",
                        style = AppTheme.typography.caption,
                        color = toneColor(if (script.official) Tone.Accent else Tone.Neutral),
                    )
                }
                Text("версия ${script.version.ifBlank { "—" }}", style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
            }
            AppSwitch(script.enabled, onToggle)
            Spacer(Modifier.width(AppTheme.spacing.s))
            AppIconButton("Удалить", onDelete, icon = Icons.Rounded.Delete)
        }
        if (script.hasSettings) {
            Spacer(Modifier.height(AppTheme.spacing.s))
            Text(
                if (script.settingsPage) "Своя страница настроек" else "Параметров: ${script.params.size}",
                style = AppTheme.typography.caption,
                color = AppTheme.colors.textSecondary,
            )
            Text(
                "Настраивается в профиле, который использует этот транспорт",
                style = AppTheme.typography.caption,
                color = AppTheme.colors.textSecondary,
            )
        }
        if (report != null && (report.available || report.blocked || report.failed)) {
            Spacer(Modifier.height(AppTheme.spacing.s))
            when {
                report.available -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Доступна ${report.latest}", style = AppTheme.typography.bodyStrong, color = toneColor(Tone.Accent), modifier = Modifier.weight(1f))
                    AppButton("Обновить", onUpdate, enabled = !busy)
                }
                else -> Text(
                    ScriptUpdateMessages.failure(report.code),
                    style = AppTheme.typography.caption,
                    color = toneColor(if (report.blocked) Tone.Warning else Tone.Danger),
                )
            }
        }
        if (busy) {
            Spacer(Modifier.height(AppTheme.spacing.xs))
            Text("Работаем…", style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
        }
        if (hasPrevious) TextAction("Вернуть предыдущую версию", onRollback, enabled = !busy)
        Spacer(Modifier.height(AppTheme.spacing.s))
        KeyValueRow("Отпечаток ключа", script.shortFingerprint, trailing = {
            AppIconButton("Копировать отпечаток", onCopy, icon = Icons.Rounded.ContentCopy)
        })
        KeyValueRow("Источник", script.source.label)
    }
}

@Composable
private fun AddScriptDialog(container: AppContainer, onClose: () -> Unit) {
    val platform = container.platform
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var data by remember { mutableStateOf(ByteArray(0)) }
    var sig by remember { mutableStateOf(ByteArray(0)) }
    var source by remember { mutableStateOf(ScriptSource.GitHub) }
    var origin by remember { mutableStateOf("") }

    fun effectiveKey() = key.trim().ifBlank { platform.officialScriptKey }

    fun inspect(bytes: ByteArray, signature: ByteArray, src: ScriptSource, from: String) {
        data = bytes; sig = signature; source = src; origin = from
        val report = runCatching { json.parseToJsonElement(platform.inspectTransport(bytes, signature, effectiveKey())).jsonObject }.getOrNull()
        if (report == null || report["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            error = report?.get("error")?.jsonPrimitive?.contentOrNull ?: "Не удалось прочитать транспорт"
            return
        }
        error = null
        preview = Preview(
            name = report["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            version = report["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            fingerprint = report["fingerprint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            official = report["official"]?.jsonPrimitive?.booleanOrNull == true,
            signature = report["signature"]?.jsonPrimitive?.contentOrNull ?: "unverified",
            paramCount = (report["params"] as? JsonArray)?.size ?: 0,
        )
    }

    AppDialog(
        title = if (preview == null) "Импорт транспорта" else "Проверьте транспорт",
        onDismiss = onClose,
        primary = if (preview == null) "Проверить" else "Доверять и установить",
        primaryEnabled = !busy && (preview != null || url.isNotBlank()),
        onPrimary = {
            if (preview == null) {
                busy = true; error = null
                scope.launch {
                    val u = url.trim()
                    val bytes = platform.fetchBytes(u)
                    if (bytes == null) { error = "Не удалось скачать по ссылке"; busy = false; return@launch }
                    val signature = if (u.endsWith(".js")) (platform.fetchBytes("$u.sig") ?: ByteArray(0)) else ByteArray(0)
                    inspect(bytes, signature, if (u.contains("github", ignoreCase = true)) ScriptSource.GitHub else ScriptSource.Link, u)
                    busy = false
                }
            } else {
                busy = true; error = null
                scope.launch {
                    runCatching { container.scripts.install(data, sig, effectiveKey(), source, origin, platform.now()) }
                        .onSuccess { onClose() }
                        .onFailure { error = it.message ?: "Не удалось установить" }
                    busy = false
                }
            }
        },
    ) {
        val p = preview
        if (p == null) {
            AppTextField(
                url, { url = it; error = null },
                label = "Ссылка на скрипт (GitHub raw .js или .flux)",
                placeholder = "https://raw.githubusercontent.com/<owner>/<repo>/main/<name>.js",
            )
            Spacer(Modifier.height(AppTheme.spacing.m))
            AppTextField(
                key, { key = it },
                label = "Ключ автора (hex), пусто = официальный OpenFlux",
                placeholder = platform.officialScriptKey,
                monospace = true,
            )
            Spacer(Modifier.height(AppTheme.spacing.m))
            AppButton(
                "Из файла (.flux)", {
                    scope.launch {
                        val path = platform.pickFile("Файл транспорта", listOf("flux", "js")) ?: return@launch
                        val bytes = platform.readBytes(path)
                        if (bytes == null) { error = "Не удалось прочитать файл"; return@launch }
                        inspect(bytes, ByteArray(0), ScriptSource.File, path)
                    }
                },
                style = ButtonStyle.Secondary, leading = Icons.Rounded.Download,
            )
        } else {
            KeyValueRow("Транспорт", "${p.name}  ·  ${p.version.ifBlank { "—" }}")
            KeyValueRow("Подпись", if (p.signature == "valid") "верна" else "НЕ совпадает")
            KeyValueRow("Автор", if (p.official) "OpenFlux (официальный)" else "неизвестный")
            KeyValueRow("Отпечаток", p.fingerprint.chunked(2).take(8).joinToString(" "))
            KeyValueRow("Параметры", p.paramCount.toString())
            Spacer(Modifier.height(AppTheme.spacing.m))
            if (p.signature != "valid") {
                Banner("Подпись не совпадает с ключом автора. Установка невозможна — проверьте ключ или источник.", Tone.Danger)
            } else {
                Banner("Скрипт работает без песочницы. Устанавливайте, только если доверяете этому автору и отпечатку.", Tone.Warning)
            }
        }
        error?.let {
            Spacer(Modifier.height(AppTheme.spacing.s))
            Banner(it, Tone.Danger)
        }
    }
}
