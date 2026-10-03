package io.openflux.desktop.ui.scripts

import androidx.compose.foundation.background
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
import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.KeyValueRow
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
    var showAdd by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.spacing.page, vertical = AppTheme.spacing.xl),
    ) {
        PageHeader("Транспорты", "JS-транспорты на равных правах с обычными — добавляйте свои")
        Spacer(Modifier.height(AppTheme.spacing.xl))
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.l)) {
            AppButton("Импортировать транспорт", { showAdd = true }, leading = Icons.Rounded.Add)
            SectionLabel("Установленные (${scripts.size})")
            if (scripts.isEmpty()) {
                Banner("Пока нет JS-транспортов. Импортируйте из GitHub или файла — они появятся при выборе транспорта в профиле.", Tone.Neutral)
            } else {
                scripts.forEach { s ->
                    ScriptCard(
                        s,
                        onToggle = { container.scripts.setEnabled(s.id, it) },
                        onDelete = { container.scripts.delete(s.id) },
                        onCopy = { container.platform.setClipboardText(s.fingerprint) },
                    )
                }
            }
            Banner(
                "Скрипт-транспорт работает без песочницы: полный доступ к сети. Подпись автора обязательна и проверяется при каждом запуске — импортируйте только из доверенного источника.",
                Tone.Warning,
            )
        }
    }

    if (showAdd) AddScriptDialog(container) { showAdd = false }
}

@Composable
private fun ScriptCard(script: InstalledScript, onToggle: (Boolean) -> Unit, onDelete: () -> Unit, onCopy: () -> Unit) {
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
                    inspect(bytes, signature, ScriptSource.GitHub, u)
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
