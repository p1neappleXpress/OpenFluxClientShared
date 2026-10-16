# Новый интерфейс, часть 1: дизайн-система и каркас навигации — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** В пакете `io.openflux.app` общего модуля появляется работающая оболочка нового интерфейса — тема и базовые компоненты в духе Amnezia, разделы Главная / Серверы / ＋ / Поделиться / Настройки на Navigation 3 с правилами «хозяин экрана» и «назад», сохранением стеков и predictive back — и оба приложения могут запустить её в отладочной сборке, не трогая старый интерфейс.

**Architecture:** `AppNavigator` — чистый класс над snapshot-состоянием (раздел + стек `Dest` на раздел), сериализуемый в `NavState` (JSON). `AppNavHost` декорирует стек каждого раздела отдельно (`rememberDecoratedNavEntries`) и отдаёт `NavDisplay` записи «Главная + текущий раздел», поэтому «назад» с корня раздела ведёт на Главную, а модели экранов чужих разделов не очищаются при переключении. Экраны разделов в этой части — заглушки; настоящие экраны — части 2–5.

**Tech Stack:** Kotlin 2.4.10, Compose Multiplatform 1.11.1, Material3 1.11.0-alpha07, `org.jetbrains.androidx.navigation3:navigation3-ui:1.1.2` (runtime `androidx.navigation3:navigation3-runtime:1.1.7`), `org.jetbrains.androidx.lifecycle:*:2.11.0`, kotlinx-serialization 1.9.0; тесты — kotlin-test, compose `ui-test` (`androidx.compose.ui.test.v2.runComposeUiTest` / `runSkikoComposeUiTest`) на JVM.

**Spec:** `shared/docs/specs/2026-10-12-ui-redesign-design.md` (разделы 1a, 3, 3a, 4, 9 — части 0 и 1, 10).

## Global Constraints

- Старый интерфейс `io.openflux.desktop.ui.**` **не меняется** ни одной строкой; `main` не трогается; работа — в ветке `nav3` сабмодуля `OpenFluxDesktop/shared` и в ветке `nav3` репозитория `OpenFluxDesktop` (каталог версий, точка входа).
- `model/`, `service/`, `data/`, платформенный код — меняются только там, где сказано в задаче.
- Новый код — пакет `io.openflux.app` (подпакеты `theme`, `components`, `nav`, `shell`, `catalog`), каталог `src/commonMain/kotlin/io/openflux/app/`.
- Платформенные мосты `io.openflux.desktop.ui.Platform.kt` (`Scrollbars`, `Shortcuts`, `BrowserViews`, `PlatformBackHandler`, `LocalTouchUi`, `LocalScrollbars`, `LocalShortcuts`, `LocalBrowserViews`) — **используются как есть** (переезд — часть 6).
- `node-install.sh` и ядро не меняются.
- Тексты интерфейса — на русском; без информационных карточек, один баннер на экран, сочетания клавиш — в подсказках (spec 3).
- Сборка: Gradle запускается из `C:\Users\meepo\openflux\OpenFluxDesktop` с JDK 21: `export JAVA_HOME="$HOME/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2"`; ядро не собирается: `-PskipCore=true`.
- Тесты общего модуля: `./gradlew :shared:jvmTest -PskipCore=true --console=plain` (один класс: `--tests 'io.openflux.app.XxxTest'`).
- Коммиты — от `meepo161 <meepo161@gmail.com>`, **без** `Co-Authored-By` и без упоминаний Claude/Anthropic (правило владельца). Коммит сабмодуля — из `OpenFluxDesktop/shared`; коммит приложения — из `OpenFluxDesktop` / `OpenFluxAndroid`.
- Ширины окна (как сейчас): телефон < 600 dp — нижняя панель; 600–1000 dp — колонка из значков; ≥ 1000 dp — колонка с подписями; список и деталь рядом — ≥ 900 dp.

## Review Focus

1. **Переключение раздела не убивает модели экранов другого раздела.** Человек открыл сервер в «Серверах», ушёл в «Настройки», вернулся — модель экрана сервера (SSH-сессия) та же. Тест: Task 6 `switchingSectionKeepsViewModels`.
2. **«Назад» снимает экран и очищает его модель.** Закрытый мастер отпускает SSH. Тест: Task 6 `backClearsPoppedViewModel`.
3. **Восстановление стека с экранами, чьих объектов уже нет** (профиль удалён между запусками, состояние повреждено или из старой версии). Ожидание: такие экраны отбрасываются, мусорная строка даёт стартовое состояние, приложение не падает. Тесты: Task 5 `restoreDropsDeadScreens`, `decodeGarbageIsNull`.
4. **Повторный `open` того же экрана** (двойное нажатие). Ожидание: экран в стеке один раз. Тест: Task 5 `openTwiceKeepsOne`.
5. **Узкий экран 320 dp и шрифт ×1,3.** Ожидание: нижняя панель с пятью пунктами и строки списка не обрезаны. Тест: Task 4 — каталог в кадре `SMALL` + `bottomBarFitsSmallScreen` в Task 7.

---

## Файлы

```
OpenFluxDesktop/
  gradle/libs.versions.toml                      (Task 0: navigation3, lifecycle, ui-test)
  desktopApp/src/main/kotlin/io/openflux/desktop/main.kt   (Task 9: OPENFLUX_UI=new)
  shared/
    build.gradle.kts                             (Task 0)
    src/commonMain/kotlin/io/openflux/desktop/model/AppSettings.kt  (Task 8: navState)
    src/commonMain/kotlin/io/openflux/app/
      theme/Tokens.kt          цвета, типографика, отступы, радиусы; AppTheme2
      components/Buttons.kt    PrimaryButton, SecondaryButton, TextButton2, DangerButton, IconButton2
      components/Rows.kt       RowItem, Section, Collapsible, StatusDot
      components/Inputs.kt     Field, SecretField, Segmented, Toggle, Chip
      components/Feedback.kt   Banner, Toast2 (+ ToastHost2), ConfirmDialog
      components/Sheet.kt      Sheet (шторка / диалог по ширине)
      components/Scaffold.kt   ScreenScaffold
      nav/Dest.kt              Section, Dest, NavState (+ JSON)
      nav/AppNavigator.kt      правила 4.2
      nav/AppNavHost.kt        NavDisplay, декораторы, стратегии
      nav/ListDetail.kt        ListDetailSceneStrategy
      shell/Shell.kt           нижняя панель / колонка, ＋, сочетания клавиш, назад
      shell/AddMenu.kt         меню ＋ (пункты-заглушки)
      shell/Placeholder.kt     заглушки экранов разделов
      shell/OpenFluxApp2.kt    точка входа
    src/jvmTest/kotlin/io/openflux/app/
      Shots.kt, ComponentsTest.kt, CatalogTest.kt, DestTest.kt, AppNavigatorTest.kt,
      NavStateTest.kt, ListDetailTest.kt, AppNavHostTest.kt, ShellTest.kt
OpenFluxAndroid/
  gradle/libs.versions.toml                      (Task 9)
  shared                                         (Task 9: сабмодуль на nav3)
  androidApp/src/debug/AndroidManifest.xml       (Task 9: activity-alias NewUiActivity)
  androidApp/src/main/kotlin/io/openflux/android/MainActivity.kt (Task 9)
```

Суффиксы `2` (`AppTheme2`, `TextButton2`, `Toast2`, `IconButton2`) — только чтобы не путаться со старыми и M3-именами в импортах; при замене (часть 6) суффиксы снимаются.

---

### Task 0: Зависимости и тестовая оснастка

**Files:**
- Modify: `OpenFluxDesktop/gradle/libs.versions.toml` (уже содержит `navigation3`, `lifecycle` — незакоммичено)
- Modify: `OpenFluxDesktop/shared/build.gradle.kts`
- Test: `OpenFluxDesktop/shared/src/jvmTest/kotlin/io/openflux/app/SmokeTest.kt`

**Interfaces:**
- Produces: в `commonMain` доступны `androidx.navigation3.*`, `androidx.lifecycle.ViewModel`, `viewModel {}`, `rememberViewModelStoreNavEntryDecorator`; в `jvmTest` — `androidx.compose.ui.test.v2.runComposeUiTest`, `runSkikoComposeUiTest`.

- [ ] **Step 1: Каталог версий.** Убедиться, что в `OpenFluxDesktop/gradle/libs.versions.toml` есть (добавить недостающее):

```toml
[versions]
navigation3 = "1.1.2"
lifecycle = "2.11.0"

[libraries]
navigation3-ui = { module = "org.jetbrains.androidx.navigation3:navigation3-ui", version.ref = "navigation3" }
lifecycle-viewmodel-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-viewmodel-navigation3 = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3", version.ref = "lifecycle" }
compose-uiTest = { module = "org.jetbrains.compose.ui:ui-test", version.ref = "composeMultiplatform" }
```

- [ ] **Step 2: Зависимости модуля.** В `shared/build.gradle.kts`, `commonMain.dependencies` после `kotlinx.serialization.json`:

```kotlin
            implementation(libs.navigation3.ui)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.viewmodel.navigation3)
```

и в `jvmTest.dependencies`:

```kotlin
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.uiTest)
```

- [ ] **Step 3: Дымовой тест.**

```kotlin
package io.openflux.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SmokeTest {
    @Test
    fun navDisplayRendersOnJvm() = runComposeUiTest {
        setContent {
            NavDisplay(backStack = listOf("a"), onBack = {}) { key -> NavEntry(key) { Text("экран $it") } }
        }
        onNodeWithText("экран a").assertExists()
    }
}
```

- [ ] **Step 4: Прогон.** `./gradlew :shared:jvmTest -PskipCore=true --console=plain --tests 'io.openflux.app.SmokeTest'` → PASS. Если `assertExists` не импортируется как расширение — заменить на `onNodeWithText("экран a").assertIsDisplayed()` с импортом `androidx.compose.ui.test.assertIsDisplayed`. Затем весь `:shared:jvmTest` → PASS (старые тесты не сломаны).

- [ ] **Step 5: Сборка Android-варианта модуля:** `./gradlew :shared:compileDebugKotlinAndroid -PskipCore=true` → BUILD SUCCESSFUL.

- [ ] **Step 6: Коммиты.**

```bash
cd OpenFluxDesktop/shared && git add build.gradle.kts src/jvmTest/kotlin/io/openflux/app/SmokeTest.kt && git commit -m "New UI: Navigation 3, lifecycle and UI tests in the shared module"
cd .. && git add gradle/libs.versions.toml && git commit -m "Version catalog: Navigation 3, lifecycle, compose ui-test"
```

---

### Task 1: Токены и тема

**Files:**
- Create: `shared/src/commonMain/kotlin/io/openflux/app/theme/Tokens.kt`
- Test: `shared/src/jvmTest/kotlin/io/openflux/app/TokensTest.kt`

**Interfaces:**
- Produces:
  - `data class Colors(background, surface, surfaceHigh, border, text, textSecondary, textMuted, accent, onAccent, success, warning, danger: Color)`; `val DarkColors: Colors`, `val LightColors: Colors`
  - `data class Type(screenTitle, title, body, bodySmall, caption, mono: TextStyle)`
  - `data class Spacing(xs=4.dp, s=8.dp, m=12.dp, l=16.dp, xl=24.dp, xxl=32.dp, page=16.dp)`
  - `data class Shapes(small=8.dp, medium=12.dp, large=20.dp)` (радиусы `Dp`)
  - `object AppTheme2 { val colors: Colors @Composable; val type: Type @Composable; val spacing: Spacing @Composable; val shapes: Shapes @Composable }`
  - `@Composable fun AppTheme2(dark: Boolean, touch: Boolean, content: @Composable () -> Unit)` — ставит `MaterialTheme` с цветовой схемой из токенов и `CompositionLocal`-ы; `touch` увеличивает `body` на 1 sp и минимальную высоту строки (`Spacing` не меняется).
  - `val MinTouch: Dp = 48.dp`; `@Composable fun rowMinHeight(): Dp` = 56.dp при touch, 44.dp иначе.

- [ ] **Step 1: Падающий тест** — контраст текста и акцента достаточен в обеих темах (WCAG ≥ 4.5 для текста, ≥ 3 для акцента на фоне):

```kotlin
package io.openflux.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.openflux.app.theme.Colors
import io.openflux.app.theme.DarkColors
import io.openflux.app.theme.LightColors
import kotlin.test.Test
import kotlin.test.assertTrue

class TokensTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(c: Colors, name: String) {
        assertTrue(contrast(c.text, c.background) >= 4.5, "$name: text")
        assertTrue(contrast(c.text, c.surface) >= 4.5, "$name: text on surface")
        assertTrue(contrast(c.textSecondary, c.surface) >= 4.5, "$name: secondary")
        assertTrue(contrast(c.accent, c.background) >= 3.0, "$name: accent")
        assertTrue(contrast(c.onAccent, c.accent) >= 4.5, "$name: on accent")
        assertTrue(contrast(c.danger, c.surface) >= 3.0, "$name: danger")
    }

    @Test fun darkIsReadable() = check(DarkColors, "dark")
    @Test fun lightIsReadable() = check(LightColors, "light")
}
```

- [ ] **Step 2:** прогон → FAIL (нет `io.openflux.app.theme`).

- [ ] **Step 3: Реализация.**

```kotlin
package io.openflux.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The colours of the new interface: near-black, one warm accent, separate state colours (spec 3a). */
@Immutable
data class Colors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
)

val DarkColors = Colors(
    background = Color(0xFF0E0E11),
    surface = Color(0xFF1A1B1F),
    surfaceHigh = Color(0xFF26272C),
    border = Color(0xFF2E2F35),
    text = Color(0xFFECECEE),
    textSecondary = Color(0xFFA9ACB2),
    textMuted = Color(0xFF6E7178),
    accent = Color(0xFFFBB26A),
    onAccent = Color(0xFF1A1205),
    success = Color(0xFF5CC98A),
    warning = Color(0xFFF2C14E),
    danger = Color(0xFFF2675C),
)

val LightColors = Colors(
    background = Color(0xFFF6F6F7),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDEDF0),
    border = Color(0xFFDDDDE2),
    text = Color(0xFF16171A),
    textSecondary = Color(0xFF55585E),
    textMuted = Color(0xFF8A8D93),
    accent = Color(0xFFB8641A),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF1E8A4C),
    warning = Color(0xFF9A6B00),
    danger = Color(0xFFC6362B),
)

@Immutable
data class Type(
    val screenTitle: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
)

private fun type(touch: Boolean): Type {
    val bump = if (touch) 1 else 0
    return Type(
        screenTitle = TextStyle(fontSize = (22 + bump).sp, lineHeight = (28 + bump).sp, fontWeight = FontWeight.SemiBold),
        title = TextStyle(fontSize = (16 + bump).sp, lineHeight = (22 + bump).sp, fontWeight = FontWeight.Medium),
        body = TextStyle(fontSize = (14 + bump).sp, lineHeight = (20 + bump).sp),
        bodySmall = TextStyle(fontSize = (13 + bump).sp, lineHeight = (18 + bump).sp),
        caption = TextStyle(fontSize = (12 + bump).sp, lineHeight = (16 + bump).sp, fontWeight = FontWeight.Medium),
        mono = TextStyle(fontSize = (13 + bump).sp, lineHeight = (18 + bump).sp, fontFamily = FontFamily.Monospace),
    )
}

@Immutable
data class Spacing(
    val xs: Dp = 4.dp, val s: Dp = 8.dp, val m: Dp = 12.dp, val l: Dp = 16.dp,
    val xl: Dp = 24.dp, val xxl: Dp = 32.dp, val page: Dp = 16.dp,
)

@Immutable
data class Shapes(val small: Dp = 8.dp, val medium: Dp = 12.dp, val large: Dp = 20.dp)

val MinTouch: Dp = 48.dp

private val LocalColors = staticCompositionLocalOf { DarkColors }
private val LocalType = staticCompositionLocalOf { type(false) }
private val LocalSpacing = staticCompositionLocalOf { Spacing() }
private val LocalShapes = staticCompositionLocalOf { Shapes() }
private val LocalTouch = staticCompositionLocalOf { false }

object AppTheme2 {
    val colors: Colors @Composable get() = LocalColors.current
    val type: Type @Composable get() = LocalType.current
    val spacing: Spacing @Composable get() = LocalSpacing.current
    val shapes: Shapes @Composable get() = LocalShapes.current
    val touch: Boolean @Composable get() = LocalTouch.current
}

/** A row's minimum height: a finger needs more than a pointer. */
@Composable
fun rowMinHeight(): Dp = if (AppTheme2.touch) 56.dp else 44.dp

@Composable
fun AppTheme2(dark: Boolean, touch: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = c.onAccent,
        background = c.background, onBackground = c.text,
        surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surfaceHigh,
        outline = c.border, outlineVariant = c.border, error = c.danger,
    )
    CompositionLocalProvider(
        LocalColors provides c,
        LocalType provides type(touch),
        LocalSpacing provides Spacing(),
        LocalShapes provides Shapes(),
        LocalTouch provides touch,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
```

- [ ] **Step 4:** прогон `--tests 'io.openflux.app.TokensTest'` → PASS. Если какая-то проверка контраста падает — подправить **только** соответствующий цвет (светлее/темнее), не порог.

- [ ] **Step 5: Коммит** (`shared`): `git add src/commonMain/kotlin/io/openflux/app/theme src/jvmTest/kotlin/io/openflux/app/TokensTest.kt && git commit -m "New UI: theme tokens, dark first, one warm accent"`

---

### Task 2: Базовые компоненты

**Files:**
- Create: `app/components/Buttons.kt`, `Rows.kt`, `Inputs.kt`, `Feedback.kt`, `Sheet.kt`, `Scaffold.kt` (все под `shared/src/commonMain/kotlin/io/openflux/app/`)
- Test: `shared/src/jvmTest/kotlin/io/openflux/app/ComponentsTest.kt`

**Interfaces:**
- Consumes: `AppTheme2`, `rowMinHeight()`, `MinTouch` (Task 1); `io.openflux.desktop.ui.LocalScrollbars`.
- Produces (точные сигнатуры, ими пользуются следующие части):

```kotlin
// Buttons.kt
@Composable fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: ImageVector? = null)
@Composable fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: ImageVector? = null)
@Composable fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true)
@Composable fun TextButton2(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = AppTheme2.colors.accent)
@Composable fun IconButton2(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true)
// Rows.kt
enum class Status { Off, Busy, On, Warning, Error }
@Composable fun StatusDot(status: Status, modifier: Modifier = Modifier)
@Composable fun RowItem(title: String, modifier: Modifier = Modifier, subtitle: String? = null, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null, selected: Boolean = false, enabled: Boolean = true)
@Composable fun ValueRow(title: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false, onCopy: (() -> Unit)? = null)
@Composable fun Section(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)
@Composable fun Collapsible(title: String, modifier: Modifier = Modifier, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit)
// Inputs.kt
@Composable fun Field(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, placeholder: String? = null, error: String? = null, singleLine: Boolean = true, mono: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text, trailing: (@Composable () -> Unit)? = null)
@Composable fun SecretField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, error: String? = null)
@Composable fun <T> Segmented(options: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String, modifier: Modifier = Modifier, enabled: (T) -> Boolean = { true })
@Composable fun Toggle(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true)
@Composable fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null)
// Feedback.kt
enum class Tone { Info, Success, Warning, Danger }
@Composable fun Banner(text: String, tone: Tone, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null, secondAction: String? = null, onSecondAction: (() -> Unit)? = null)
class Toaster2 { val current: String?; fun show(text: String) }   // одно сообщение, 2,5 с
@Composable fun ToastHost2(toaster: Toaster2, modifier: Modifier = Modifier)
val LocalToaster2: ProvidableCompositionLocal<Toaster2>
@Composable fun ConfirmDialog(title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false, dismiss: String = "Отмена")
// Sheet.kt
@Composable fun Sheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit)
// Scaffold.kt
@Composable fun ScreenScaffold(title: String?, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, bottomBar: (@Composable () -> Unit)? = null, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit)
```

Правила внешнего вида (обязательные): `RowItem` — без рамок-карточек, фон `surface`, радиус `shapes.medium` у **группы** (`Section`), между строками внутри `Section` — разделитель 1 dp цвета `border` с отступом слева под `leading`; `selected` — акцентная полоса 3 dp слева и `title` цвета `accent`. `Banner` — одна строка текста (максимум 3 строки при переносе), значок тона слева, действие — `TextButton2` справа. `Sheet` — `ModalBottomSheet` при ширине окна < 600 dp, иначе `Dialog` шириной до 480 dp с тем же содержимым. `ScreenScaffold` — заголовок `screenTitle` со стрелкой «назад» (`IconButton2(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")`) при `onBack != null`; содержимое с отступами `spacing.page`, при `scroll` — `verticalScroll` и полоса прокрутки из `LocalScrollbars`; `bottomBar` прибит к низу над содержимым. Сочетание клавиш в подсказке — через `TooltipBox` у `IconButton2` с текстом `description`.

- [ ] **Step 1: Падающие тесты поведения.**

```kotlin
package io.openflux.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.components.Collapsible
import io.openflux.app.components.RowItem
import io.openflux.app.components.SecretField
import io.openflux.app.components.Segmented
import io.openflux.app.theme.AppTheme2
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ComponentsTest {
    @Test
    fun collapsibleHidesUntilOpened() = runComposeUiTest {
        setContent { AppTheme2(dark = true, touch = false) { Collapsible("Дополнительно") { Text("порт") } } }
        onNodeWithText("порт").assertDoesNotExist()
        onNodeWithText("Дополнительно").performClick()
        onNodeWithText("порт").assertIsDisplayed()
    }

    @Test
    fun segmentedReportsChoice() = runComposeUiTest {
        var picked = "L4"
        setContent {
            AppTheme2(dark = true, touch = false) {
                var s by remember { mutableStateOf("L4") }
                Segmented(listOf("L4", "L3"), s, { s = it; picked = it }, label = { it })
            }
        }
        onNodeWithText("L3").performClick()
        assertEquals("L3", picked)
    }

    @Test
    fun secretFieldRevealsOnDemand() = runComposeUiTest {
        setContent { AppTheme2(dark = true, touch = false) { SecretField("hunter2", {}, "Пароль") } }
        onNodeWithText("hunter2").assertDoesNotExist()
        onNodeWithContentDescription("Показать").performClick()
        onNodeWithText("hunter2").assertIsDisplayed()
    }

    @Test
    fun rowItemClicks() = runComposeUiTest {
        var clicks = 0
        setContent { AppTheme2(dark = true, touch = false) { RowItem("Сервер", onClick = { clicks++ }) } }
        onNodeWithText("Сервер").performClick()
        assertEquals(1, clicks)
    }
}
```

- [ ] **Step 2:** прогон → FAIL (компонентов нет).

- [ ] **Step 3: Реализация** — по сигнатурам и правилам выше, на M3 (`Button`, `OutlinedButton`, `TextButton`, `IconButton`, `OutlinedTextField`, `SingleChoiceSegmentedButtonRow`/`SegmentedButton`, `Switch`, `FilterChip`, `ModalBottomSheet`, `Dialog`, `AlertDialog`, `TooltipBox`) с цветами из `AppTheme2`. `SecretField` использует `PasswordVisualTransformation` и `IconButton2(Icons.Rounded.Visibility / VisibilityOff, if (shown) "Скрыть" else "Показать")`. `Collapsible` — `RowItem(title, trailing = шеврон, onClick = toggle)` + `AnimatedVisibility`. `Toaster2.show` заменяет текущее сообщение и снимает его через 2 500 мс (`LaunchedEffect(current)` в `ToastHost2`). `Section(title)` — подпись `caption` цвета `textMuted` заглавными над группой. Размер файла — до ~200 строк; если больше, разделить по смыслу внутри `components/`.

- [ ] **Step 4:** прогон `--tests 'io.openflux.app.ComponentsTest'` → PASS.

- [ ] **Step 5: Коммит:** `git add src/commonMain/kotlin/io/openflux/app/components src/jvmTest/kotlin/io/openflux/app/ComponentsTest.kt && git commit -m "New UI: base components: rows, sections, inputs, banner, sheet, scaffold"`

---

### Task 3: Каталог компонентов в PNG

**Files:**
- Create: `shared/src/jvmTest/kotlin/io/openflux/app/Shots.kt`, `shared/src/jvmTest/kotlin/io/openflux/app/CatalogTest.kt`
- Modify: `shared/.gitignore` (добавить строку `/shots/`, если файла нет — создать)

**Interfaces:**
- Consumes: Task 1, Task 2.
- Produces: `object Shots { val PHONE: Frame; val SMALL: Frame; val WIDE: Frame; fun render(name: String, frame: Frame = PHONE, touch: Boolean = true, content: @Composable () -> Unit) }` — следующие части добавляют свои экраны в каталог через него.

- [ ] **Step 1: `Shots.kt`.**

```kotlin
package io.openflux.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.openflux.app.theme.AppTheme2
import java.io.File
import javax.imageio.ImageIO

/** Renders the new UI off-screen; with SHOTS=<dir> writes <name>[-light][-small|-wide].png there. */
@OptIn(ExperimentalTestApi::class)
object Shots {
    data class Frame(val widthDp: Float, val heightDp: Float, val fontScale: Float, val suffix: String)

    val PHONE = Frame(412f, 870f, 1f, "")
    val SMALL = Frame(320f, 640f, 1.3f, "-small")
    val WIDE = Frame(1280f, 800f, 1f, "-wide")
    private const val SCALE = 2f

    fun render(name: String, frame: Frame = PHONE, touch: Boolean = frame != WIDE, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            runSkikoComposeUiTest(
                size = Size(frame.widthDp * SCALE, frame.heightDp * SCALE),
                density = Density(SCALE, frame.fontScale),
            ) {
                mainClock.autoAdvance = false
                setContent {
                    AppTheme2(dark, touch) {
                        Box(Modifier.fillMaxSize().background(AppTheme2.colors.background)) { content() }
                    }
                }
                mainClock.advanceTimeBy(400)
                val image = captureToImage().toAwtImage()
                val dir = System.getenv("SHOTS") ?: return@runSkikoComposeUiTest
                File(dir).mkdirs()
                ImageIO.write(image, "png", File(dir, name + (if (dark) "" else "-light") + frame.suffix + ".png"))
            }
        }
    }
}
```

- [ ] **Step 2: `CatalogTest.kt`** — экран-витрина `ComponentsCatalog()` (функция в самом тесте): `ScreenScaffold("Компоненты", onBack = {})` и внутри — `Section("Строки")` с `RowItem` (обычная, `selected`, с `StatusDot` каждого `Status`, с `subtitle`, с ⋮), `ValueRow` моно с копированием; `Section("Поля")` — `Field` с ошибкой, `SecretField`, `Segmented("L4","L3")`, `Toggle`, ряд `Chip`; все четыре `Banner` (тон Info/Success/Warning/Danger, с действием); кнопки всех видов; `Collapsible(initiallyOpen = true)`. Тесты:

```kotlin
    @Test fun catalogPhone() = Shots.render("components") { ComponentsCatalog() }
    @Test fun catalogSmall() = Shots.render("components", Shots.SMALL) { ComponentsCatalog() }
    @Test fun catalogWide() = Shots.render("components", Shots.WIDE) { ComponentsCatalog() }
    @Test fun sheetPhone() = Shots.render("sheet") { Sheet(onDismiss = {}, title = "Подключение") { RowItem("Амстердам", selected = true); RowItem("Франкфурт") } }
    @Test fun confirmDialog() = Shots.render("confirm") { ConfirmDialog("Удалить ноду?", "Нода of-abc будет удалена с сервера.", "Удалить", {}, {}, danger = true) }
```

- [ ] **Step 3:** прогон с выводом: `SHOTS=../../shots-part1 ./gradlew :shared:jvmTest -PskipCore=true --tests 'io.openflux.app.CatalogTest'` → PASS, в `OpenFluxDesktop/shots-part1` 14 PNG (`components`, `components-light`, `-small`, `-light-small`, `-wide`, `-light-wide`, `sheet`, `sheet-light`, `confirm`, `confirm-light`). Открыть `components.png`, `components-small.png`, `components-light.png` и проверить глазами: нет обрезанного текста, нет карточек-рамок вокруг строк, акцент только на выбранном/главном, тёмная тема по умолчанию. Найденное — исправить в компонентах и перегенерировать.

- [ ] **Step 4: Коммит:** `git add .gitignore src/jvmTest/kotlin/io/openflux/app/Shots.kt src/jvmTest/kotlin/io/openflux/app/CatalogTest.kt && git commit -m "New UI: component catalogue rendered to PNG for review"`

---

### Task 4: Разделы, экраны и состояние навигации

**Files:**
- Create: `shared/src/commonMain/kotlin/io/openflux/app/nav/Dest.kt`
- Test: `shared/src/jvmTest/kotlin/io/openflux/app/DestTest.kt`

**Interfaces:**
- Produces:

```kotlin
@Serializable enum class Section(val title: String) { Home("Главная"), Servers("Серверы"), Share("Поделиться"), Settings("Настройки") }

@Serializable sealed interface Dest {
    /** The section this screen belongs to (spec 4.2): opening it from anywhere goes there. */
    val owner: Section
    /** True for the first screen of a section. */
    val isRoot: Boolean get() = false
    /** Ids of the profiles / servers this screen shows; a screen whose object is gone is dropped. */
    val profileId: String? get() = null
    val serverId: String? get() = null

    @Serializable @SerialName("home") data object Home : Dest { override val owner = Section.Home; override val isRoot = true }
    @Serializable @SerialName("servers") data object Servers : Dest { override val owner = Section.Servers; override val isRoot = true }
    @Serializable @SerialName("share") data object Share : Dest { override val owner = Section.Share; override val isRoot = true }
    @Serializable @SerialName("settings") data object Settings : Dest { override val owner = Section.Settings; override val isRoot = true }

    /** A profile's details (spec 6.2). */
    @Serializable @SerialName("profile") data class Profile(val id: String) : Dest { override val owner = Section.Home; override val profileId = id }
    /** One of the person's servers, "host:port:user" (spec 5.2). */
    @Serializable @SerialName("server") data class Server(val id: String) : Dest { override val owner = Section.Servers; override val serverId = id }
    /** A node on a server (spec 5.3). */
    @Serializable @SerialName("node") data class Node(val server: String, val channel: String) : Dest { override val owner = Section.Servers; override val serverId = server }
    /** Giving one profile away: QR and link (spec 7.2). */
    @Serializable @SerialName("give") data class Give(val profile: String) : Dest { override val owner = Section.Share; override val profileId = profile }
    /** A page of Settings by its name (spec 8.3). */
    @Serializable @SerialName("settings-page") data class SettingsPage(val page: String) : Dest { override val owner = Section.Settings }
}

fun rootOf(section: Section): Dest

@Serializable data class NavState(val section: Section = Section.Home, val stacks: Map<Section, List<Dest>> = Section.entries.associateWith { listOf(rootOf(it)) }) {
    fun encode(): String
    companion object { fun decode(text: String?): NavState? }   // null на пустой/битой строке или неизвестном экране
}
```

Следующие части **добавляют** подклассы `Dest` (с `@SerialName`) и не меняют существующие имена.

- [ ] **Step 1: Падающие тесты.**

```kotlin
package io.openflux.app

import io.openflux.app.nav.Dest
import io.openflux.app.nav.NavState
import io.openflux.app.nav.Section
import io.openflux.app.nav.rootOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DestTest {
    @Test
    fun everySectionHasItsRoot() {
        for (s in Section.entries) {
            val root = rootOf(s)
            assertEquals(s, root.owner)
            assertTrue(root.isRoot)
        }
    }

    @Test
    fun owners() {
        assertEquals(Section.Home, Dest.Profile("p").owner)
        assertEquals(Section.Servers, Dest.Node("h:22:root", "of-a").owner)
        assertEquals(Section.Share, Dest.Give("p").owner)
        assertEquals("h:22:root", Dest.Node("h:22:root", "of-a").serverId)
    }

    @Test
    fun stateRoundTrips() {
        val s = NavState(
            section = Section.Servers,
            stacks = NavState().stacks + (Section.Servers to listOf(Dest.Servers, Dest.Server("h:22:root"), Dest.Node("h:22:root", "of-a"))),
        )
        assertEquals(s, NavState.decode(s.encode()))
    }

    @Test
    fun decodeGarbageIsNull() {
        assertNull(NavState.decode(""))
        assertNull(NavState.decode(null))
        assertNull(NavState.decode("{not json"))
        assertNull(NavState.decode("""{"section":"Servers","stacks":{"Servers":[{"type":"no-such-screen"}]}}"""))
    }
}
```

- [ ] **Step 2:** прогон → FAIL.

- [ ] **Step 3: Реализация.** `Dest` и `NavState` — как в Interfaces. JSON:

```kotlin
private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

fun encode(): String = json.encodeToString(serializer(), this)

companion object {
    fun decode(text: String?): NavState? =
        if (text.isNullOrBlank()) null
        else runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
}
```

`rootOf`: `Home → Dest.Home`, `Servers → Dest.Servers`, `Share → Dest.Share`, `Settings → Dest.Settings`. Плагин сериализации уже подключён в модуле (`kotlinSerialization`).

- [ ] **Step 4:** прогон `--tests 'io.openflux.app.DestTest'` → PASS.

- [ ] **Step 5: Коммит:** `git add src/commonMain/kotlin/io/openflux/app/nav/Dest.kt src/jvmTest/kotlin/io/openflux/app/DestTest.kt && git commit -m "New UI: sections, screens with their owner section, saved navigation state"`

---

### Task 5: `AppNavigator` — правила «хозяин экрана» и «назад»

**Files:**
- Create: `shared/src/commonMain/kotlin/io/openflux/app/nav/AppNavigator.kt`
- Test: `shared/src/jvmTest/kotlin/io/openflux/app/AppNavigatorTest.kt`

**Interfaces:**
- Consumes: `Section`, `Dest`, `NavState`, `rootOf` (Task 4).
- Produces:

```kotlin
@Stable
class AppNavigator(initial: NavState = NavState()) {
    val section: Section                       // snapshot state
    fun stack(section: Section): List<Dest>    // snapshot list, first = root
    val top: Dest
    val canGoBack: Boolean                      // depth > 1 || section != Home
    fun open(dest: Dest)                        // switch to dest.owner; root -> pop to root; else push (not twice in a row)
    fun select(section: Section)                // same -> pop to root; other -> switch
    fun back(): Boolean                         // pop; at root of non-Home -> Home; at Home root -> false
    fun replaceTop(dest: Dest)                  // in dest.owner: drop top (if not root), then open(dest)
    fun forget(predicate: (Dest) -> Boolean)   // drop matching non-root screens from every stack
    fun snapshot(): NavState
    companion object {
        /** [state] with screens [keep] rejects dropped (and everything above them in that stack). */
        fun restore(state: NavState?, keep: (Dest) -> Boolean): AppNavigator
    }
}
```

- [ ] **Step 1: Падающие тесты** (правила spec 4.2 и Review Focus 3, 4):

```kotlin
package io.openflux.app

import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.nav.NavState
import io.openflux.app.nav.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppNavigatorTest {
    private val server = Dest.Server("h:22:root")
    private val node = Dest.Node("h:22:root", "of-a")

    @Test
    fun startsAtHome() {
        val n = AppNavigator()
        assertEquals(Section.Home, n.section)
        assertEquals(Dest.Home, n.top)
        assertFalse(n.canGoBack)
    }

    @Test
    fun openGoesToTheOwnerSection() {
        val n = AppNavigator()
        n.open(node)
        assertEquals(Section.Servers, n.section)
        assertEquals(listOf(Dest.Servers, node), n.stack(Section.Servers))
        n.open(Dest.Give("p"))
        assertEquals(Section.Share, n.section)
        assertEquals(listOf(Dest.Servers, node), n.stack(Section.Servers), "the other section keeps its stack")
    }

    @Test
    fun openTwiceKeepsOne() {
        val n = AppNavigator()
        n.open(server); n.open(server)
        assertEquals(listOf(Dest.Servers, server), n.stack(Section.Servers))
    }

    @Test
    fun backPopsThenGoesHomeThenStops() {
        val n = AppNavigator()
        n.open(server); n.open(node)
        assertTrue(n.back()); assertEquals(server, n.top)
        assertTrue(n.back()); assertEquals(Dest.Servers, n.top)
        assertTrue(n.back()); assertEquals(Section.Home, n.section)
        assertFalse(n.back())
        assertEquals(listOf(Dest.Servers), n.stack(Section.Servers))
    }

    @Test
    fun selectSameSectionPopsToRoot() {
        val n = AppNavigator()
        n.open(server); n.open(node)
        n.select(Section.Settings)
        assertEquals(Section.Settings, n.section)
        n.select(Section.Servers)
        assertEquals(node, n.top, "a tab shows where the person left it")
        n.select(Section.Servers)
        assertEquals(Dest.Servers, n.top)
    }

    @Test
    fun openingARootPopsToIt() {
        val n = AppNavigator()
        n.open(server)
        n.open(Dest.Servers)
        assertEquals(listOf(Dest.Servers), n.stack(Section.Servers))
    }

    @Test
    fun replaceTopSwapsAWizardForItsResult() {
        val n = AppNavigator()
        n.open(server)
        n.replaceTop(node)
        assertEquals(listOf(Dest.Servers, node), n.stack(Section.Servers))
    }

    @Test
    fun forgetDropsScreensEverywhere() {
        val n = AppNavigator()
        n.open(Dest.Profile("p")); n.open(Dest.Give("p")); n.open(Dest.Give("q"))
        n.forget { it.profileId == "p" }
        assertEquals(listOf(Dest.Home), n.stack(Section.Home))
        assertEquals(listOf(Dest.Share, Dest.Give("q")), n.stack(Section.Share))
    }

    @Test
    fun restoreDropsDeadScreens() {
        val saved = AppNavigator().apply { open(Dest.Profile("gone")); open(server); open(node) }.snapshot()
        val n = AppNavigator.restore(saved) { it.profileId != "gone" }
        assertEquals(listOf(Dest.Home), n.stack(Section.Home))
        assertEquals(listOf(Dest.Servers, server, node), n.stack(Section.Servers))
        assertEquals(Section.Servers, n.section)
    }

    @Test
    fun restoreDropsEverythingAboveADeadScreen() {
        val saved = AppNavigator().apply { open(server); open(node) }.snapshot()
        val n = AppNavigator.restore(saved) { it != server }
        assertEquals(listOf(Dest.Servers), n.stack(Section.Servers))
    }

    @Test
    fun restoreOfNothingStartsFresh() {
        assertEquals(NavState(), AppNavigator.restore(null) { true }.snapshot())
    }

    @Test
    fun restoreRepairsAStackWithoutItsRoot() {
        val broken = NavState(section = Section.Servers, stacks = NavState().stacks + (Section.Servers to listOf(server)))
        val n = AppNavigator.restore(broken) { true }
        assertEquals(listOf(Dest.Servers, server), n.stack(Section.Servers))
    }
}
```

- [ ] **Step 2:** прогон → FAIL.

- [ ] **Step 3: Реализация.**

```kotlin
package io.openflux.app.nav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Where the person is: a section and a stack of screens in each (spec 4.2). Every screen has one owner
 * section; opening it switches there. Back pops; from a section's first screen it goes Home; on Home it
 * is not handled, so the system leaves the app.
 */
@Stable
class AppNavigator(initial: NavState = NavState()) {
    var section by mutableStateOf(initial.section)
        private set

    private val stacks: Map<Section, SnapshotStateList<Dest>> = Section.entries.associateWith { s ->
        mutableStateListOf<Dest>().apply { addAll(initial.stacks[s].orEmpty()) }
    }

    fun stack(section: Section): List<Dest> = stacks.getValue(section)

    val top: Dest get() = stacks.getValue(section).last()

    val canGoBack: Boolean get() = stacks.getValue(section).size > 1 || section != Section.Home

    fun open(dest: Dest) {
        section = dest.owner
        val stack = stacks.getValue(dest.owner)
        if (dest.isRoot) popToRoot(stack) else if (stack.last() != dest) stack.add(dest)
    }

    fun select(target: Section) {
        if (target == section) popToRoot(stacks.getValue(target)) else section = target
    }

    fun back(): Boolean {
        val stack = stacks.getValue(section)
        return when {
            stack.size > 1 -> { stack.removeAt(stack.lastIndex); true }
            section != Section.Home -> { section = Section.Home; true }
            else -> false
        }
    }

    fun replaceTop(dest: Dest) {
        val stack = stacks.getValue(dest.owner)
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        open(dest)
    }

    fun forget(predicate: (Dest) -> Boolean) {
        for (stack in stacks.values) stack.removeAll { !it.isRoot && predicate(it) }
    }

    fun snapshot(): NavState = NavState(section, stacks.mapValues { it.value.toList() })

    private fun popToRoot(stack: SnapshotStateList<Dest>) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    companion object {
        fun restore(state: NavState?, keep: (Dest) -> Boolean): AppNavigator {
            val s = state ?: return AppNavigator()
            val stacks = Section.entries.associateWith { section ->
                val saved = s.stacks[section].orEmpty().filter { it.owner == section }
                val above = saved.dropWhile { it.isRoot }
                listOf(rootOf(section)) + above.takeWhile(keep)
            }
            return AppNavigator(NavState(s.section, stacks))
        }
    }
}
```

- [ ] **Step 4:** прогон `--tests 'io.openflux.app.AppNavigatorTest'` → PASS.

- [ ] **Step 5: Коммит:** `git add src/commonMain/kotlin/io/openflux/app/nav/AppNavigator.kt src/jvmTest/kotlin/io/openflux/app/AppNavigatorTest.kt && git commit -m "New UI: navigator with one owner section per screen and one back rule"`

---

### Task 6: `AppNavHost` — Navigation 3, модели экранов, список и деталь

**Files:**
- Create: `shared/src/commonMain/kotlin/io/openflux/app/nav/ListDetail.kt`, `shared/src/commonMain/kotlin/io/openflux/app/nav/AppNavHost.kt`
- Test: `shared/src/jvmTest/kotlin/io/openflux/app/ListDetailTest.kt`, `shared/src/jvmTest/kotlin/io/openflux/app/AppNavHostTest.kt`

**Interfaces:**
- Consumes: `AppNavigator` (Task 5), `Dest`, `Section` (Task 4).
- Produces:

```kotlin
// ListDetail.kt
/** Metadata marking an entry as a list (left pane) or a detail (right pane) of [section]. */
object ListDetail {
    fun list(section: Section): Map<String, Any>
    fun detail(section: Section): Map<String, Any>
}
class ListDetailSceneStrategy(private val wide: Boolean, private val nothingChosen: @Composable (Section) -> Unit) : SceneStrategy<Dest>
// AppNavHost.kt
@Composable fun AppNavHost(navigator: AppNavigator, wide: Boolean, modifier: Modifier = Modifier, nothingChosen: @Composable (Section) -> Unit = {}, entry: (Dest) -> NavEntry<Dest>)
fun destEntry(dest: Dest, metadata: Map<String, Any> = emptyMap(), content: @Composable () -> Unit): NavEntry<Dest>
```

Как работает `AppNavHost` (обязательное устройство, ради Review Focus 1–2):

```kotlin
@Composable
fun AppNavHost(navigator: AppNavigator, wide: Boolean, modifier: Modifier = Modifier, nothingChosen: @Composable (Section) -> Unit = {}, entry: (Dest) -> NavEntry<Dest>) {
    val decorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator<Dest>(),
        rememberViewModelStoreNavEntryDecorator<Dest>(),
    )
    // Every section's stack is decorated on its own, always: a section out of sight keeps its screens' state and models.
    val decorated = Section.entries.associateWith { s ->
        key(s) { rememberDecoratedNavEntries(navigator.stack(s), decorators, entry) }
    }
    val home = decorated.getValue(Section.Home)
    val entries = if (navigator.section == Section.Home) home else home + decorated.getValue(navigator.section)
    NavDisplay(
        entries = entries,
        modifier = modifier,
        sceneStrategy = ListDetailSceneStrategy(wide, nothingChosen),
        onBack = { navigator.back() },
    )
}

fun destEntry(dest: Dest, metadata: Map<String, Any> = emptyMap(), content: @Composable () -> Unit): NavEntry<Dest> =
    NavEntry(dest, contentKey = "${dest.owner.name}/$dest", metadata = metadata) { content() }
```

«Главная + текущий раздел» в `entries` даёт: «назад» с корня раздела — на Главную, и predictive back показывает Главную под уходящим экраном. Если на этой версии `NavDisplay(entries=…)` требует, чтобы ключи записей были уникальны по `contentKey`, — так и есть: `contentKey` включает раздел.

`ListDetailSceneStrategy.calculateScene(entries)`: при `wide` и если последняя запись помечена `detail(s)` либо `list(s)` — сцена из двух панелей: слева ближайшая запись с `list(s)` (ширина `(maxWidth * 0.32f).coerceIn(300.dp, 420.dp)`), разделитель 1 dp, справа последняя запись, если она `detail(s)`, иначе `nothingChosen(s)`; `key = "list-detail/${s.name}"` (выбор другой детали не перерисовывает список), `entries` = записи на экране, `previousEntries = entries.dropLast(1)`. Иначе `null` (одна панель). Записи без меток — всегда одна панель.

- [ ] **Step 1: Падающие тесты стратегии** (`ListDetailTest`):

```kotlin
package io.openflux.app

import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.SceneStrategyScope
import io.openflux.app.nav.Dest
import io.openflux.app.nav.ListDetail
import io.openflux.app.nav.ListDetailSceneStrategy
import io.openflux.app.nav.Section
import io.openflux.app.nav.destEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ListDetailTest {
    private val list = destEntry(Dest.Servers, ListDetail.list(Section.Servers)) {}
    private fun detail(id: String) = destEntry(Dest.Server(id), ListDetail.detail(Section.Servers)) {}
    private val plain = destEntry(Dest.SettingsPage("x")) {}

    private fun scene(wide: Boolean, vararg e: NavEntry<Dest>) =
        with(ListDetailSceneStrategy(wide) {}) { SceneStrategyScope<Dest>().calculateScene(e.toList()) }

    @Test
    fun wideShowsListBesideDetail() {
        val s = assertNotNull(scene(true, list, detail("a")))
        assertEquals(listOf(list, detail("a")).map { it.contentKey }, s.entries.map { it.contentKey })
        assertEquals(s.key, scene(true, list, detail("b"))?.key)
    }

    @Test
    fun wideListAlone() {
        assertNotNull(scene(true, list))
    }

    @Test
    fun narrowAndUnmarkedAreSinglePane() {
        assertNull(scene(false, list, detail("a")))
        assertNull(scene(true, list, detail("a"), plain))
    }
}
```

- [ ] **Step 2: Падающие тесты хоста** (`AppNavHostTest`, Review Focus 1–2):

```kotlin
package io.openflux.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.openflux.app.nav.AppNavHost
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.nav.Section
import io.openflux.app.nav.destEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Probe : ViewModel() {
    var cleared = false
    override fun onCleared() { cleared = true }
}

@OptIn(ExperimentalTestApi::class)
class AppNavHostTest {
    private val models = mutableMapOf<Dest, Probe>()

    private fun host(n: AppNavigator): @androidx.compose.runtime.Composable () -> Unit = {
        AppNavHost(n, wide = false) { dest ->
            destEntry(dest) {
                val m = viewModel { Probe() }
                models[dest] = m
                Text("экран $dest")
            }
        }
    }

    @Test
    fun backClearsPoppedViewModel() = runComposeUiTest {
        val n = AppNavigator()
        setContent(host(n))
        val server = Dest.Server("h:22:root")
        runOnIdle { n.open(server) }
        onNodeWithText("экран $server").assertIsDisplayed()
        val m = models.getValue(server)
        runOnIdle { n.back() }
        waitForIdle()
        assertTrue(m.cleared)
    }

    @Test
    fun switchingSectionKeepsViewModels() = runComposeUiTest {
        val n = AppNavigator()
        setContent(host(n))
        val server = Dest.Server("h:22:root")
        runOnIdle { n.open(server) }
        waitForIdle()
        val m = models.getValue(server)
        runOnIdle { n.select(Section.Settings) }
        onNodeWithText("экран ${Dest.Settings}").assertIsDisplayed()
        runOnIdle { n.select(Section.Servers) }
        onNodeWithText("экран $server").assertIsDisplayed()
        assertFalse(m.cleared)
        assertSame(m, models.getValue(server))
    }

    @Test
    fun backFromSectionRootShowsHome() = runComposeUiTest {
        val n = AppNavigator()
        setContent(host(n))
        runOnIdle { n.select(Section.Share) }
        onNodeWithText("экран ${Dest.Share}").assertIsDisplayed()
        runOnIdle { n.back() }
        onNodeWithText("экран ${Dest.Home}").assertIsDisplayed()
        assertEquals(Section.Home, n.section)
    }
}
```

- [ ] **Step 3:** прогон обоих → FAIL.

- [ ] **Step 4: Реализация** `ListDetail.kt` и `AppNavHost.kt` по Interfaces и описанию. Метки — ключи `"openflux.pane"` → `"list"`/`"detail"` и `"openflux.section"` → `Section`. Сцена из двух панелей — `private class ListDetailScene(...) : Scene<Dest>` (как в `wip/nav3-old-ui-migration`: `git show wip/nav3-old-ui-migration:src/commonMain/kotlin/io/openflux/desktop/ui/nav/Places.kt` — взять устройство `BoxWithConstraints`/`Row`, заменив `AppTheme` на `AppTheme2`, а `LocalSplitView` не переносить: деталь в двух панелях получает `LocalInPane provides true` — `val LocalInPane = staticCompositionLocalOf { false }` в `ListDetail.kt`; `ScreenScaffold` (Task 2) при `LocalInPane.current` не показывает стрелку «назад»).

  Изменение `ScreenScaffold`: `val showBack = onBack != null && !LocalInPane.current`.

- [ ] **Step 5:** прогон `--tests 'io.openflux.app.ListDetailTest' --tests 'io.openflux.app.AppNavHostTest'` → PASS. Если `switchingSectionKeepsViewModels` падает (модель очищена), значит декоратор считает запись снятой при её уходе из `entries` `NavDisplay` — тогда передавать в `NavDisplay` записи **всех** разделов нельзя, а ViewModel-декоратор подключать к `rememberDecoratedNavEntries` каждого раздела **до** `NavDisplay` (как в коде выше) — проверить, что именно так и сделано; `NavDisplay(entries=…)` декораторы не принимает и снятий не считает.

- [ ] **Step 6: Коммит:** `git add src/commonMain/kotlin/io/openflux/app/nav src/commonMain/kotlin/io/openflux/app/components/Scaffold.kt src/jvmTest/kotlin/io/openflux/app/ListDetailTest.kt src/jvmTest/kotlin/io/openflux/app/AppNavHostTest.kt && git commit -m "New UI: Navigation 3 host: each section decorated on its own, list beside detail"`

---

### Task 7: Оболочка, меню ＋ и `OpenFluxApp2`

**Files:**
- Create: `shared/src/commonMain/kotlin/io/openflux/app/shell/Shell.kt`, `AddMenu.kt`, `Placeholder.kt`, `OpenFluxApp2.kt`
- Test: `shared/src/jvmTest/kotlin/io/openflux/app/ShellTest.kt`; кадры оболочки — в `CatalogTest`

**Interfaces:**
- Consumes: всё выше; `AppContainer`, `LocalAppContainer` (`io.openflux.desktop.service`); `Scrollbars`, `Shortcuts`, `BrowserViews`, `NoBrowserViews`, `PlatformBackHandler`, `LocalTouchUi`, `LocalScrollbars`, `LocalShortcuts`, `LocalBrowserViews` (`io.openflux.desktop.ui`); `ThemeMode` (`io.openflux.desktop.model`).
- Produces:

```kotlin
// OpenFluxApp2.kt
@Composable fun OpenFluxApp2(container: AppContainer, scrollbars: Scrollbars, shortcuts: Shortcuts, browsers: BrowserViews = NoBrowserViews)
val LocalNavigator: ProvidableCompositionLocal<AppNavigator>
// Shell.kt
enum class WidthClass { Phone, Compact, Medium, Expanded }
fun widthClassOf(width: Dp): WidthClass              // <600 Phone, <1000 Compact, <1440 Medium, else Expanded
@Composable fun Shell(navigator: AppNavigator, onAdd: () -> Unit, content: @Composable (wide: Boolean) -> Unit)
// AddMenu.kt
enum class AddChoice(val title: String) { Key("Ключ, ссылка или файл"), Scan("Сканировать QR"), Server("Свой сервер"), Hosting("Без сервера (хостинг)"), Account("Аккаунт OpenFlux"), Manual("Вручную") }
@Composable fun AddMenu(cameraScan: Boolean, onChoice: (AddChoice) -> Unit, onDismiss: () -> Unit)
// Placeholder.kt
@Composable fun Placeholder(dest: Dest)               // ScreenScaffold(title) + одна строка «Экран появится в следующей части»
fun placeholderEntry(dest: Dest, navigator: AppNavigator): NavEntry<Dest>
```

Поведение `Shell` (spec 4.1):
- Телефон: снизу панель из **пяти** мест: Главная, Серверы, ＋ (круглая акцентная кнопка по центру), Поделиться, Настройки; панель скрыта, пока открыта клавиатура (`WindowInsets.ime`), как сейчас. Значки: `Icons.Rounded.Home`, `Icons.Rounded.Dns`, `Icons.Rounded.Add`, `Icons.Rounded.Share`, `Icons.Rounded.Settings`; на Главной — `StatusDot` подключения, если состояние не `Idle` (`container.connection.state`).
- Шире: колонка слева: ＋ сверху (акцентная), под ней четыре раздела; `Compact` — только значки, `Medium`/`Expanded` — значки и подписи. Подсказка у пункта — «Серверы · Ctrl+2» и т. п. (только не на touch).
- Нажатие на раздел — `navigator.select(section)`; ＋ — `onAdd()`.
- Края: `windowInsetsPadding(WindowInsets.safeDrawing)` на корне, фон `colors.background`.
- `PlatformBackHandler(enabled = touch && navigator.canGoBack) { navigator.back() }` — системное «назад» отдаётся `NavDisplay` (predictive back); этот обработчик — только запасной, **не ставить**, если `NavDisplay` уже перехватывает жест: проверить в Task 9 на эмуляторе, при двойном срабатывании — убрать.
- Сочетания (только не touch, через `LocalShortcuts.current.register`): Ctrl+1…4 — `select(Home/Servers/Share/Settings)`; Ctrl+N — `onAdd()`; Ctrl+Enter — подключить/отключить: `if (state.isActive) connection.disconnect() else` выбранный профиль (`settings.selectedProfileId`, иначе первый) → `connection.connect(profile)`.

`OpenFluxApp2`:
- тема из `settings.theme` (`System` → `isSystemInDarkTheme()`), `touch = container.platform.kind == PlatformKind.Android`;
- `CompositionLocal`-ы: `LocalTouchUi`, `LocalAppContainer`, `LocalScrollbars`, `LocalShortcuts`, `LocalBrowserViews`, `LocalToaster2`, `LocalNavigator`;
- навигатор — Task 8 (в этой задаче: `remember { AppNavigator() }`);
- `Shell(navigator, onAdd = { addOpen = true }) { wide -> AppNavHost(navigator, wide, entry = { placeholderEntry(it, navigator) }) }`;
- `if (addOpen) AddMenu(container.platform.cameraScanSupported, onChoice = { toaster.show("${it.title}: появится в части 4"); addOpen = false }, onDismiss = { addOpen = false })`;
- `ToastHost2` внизу (над нижней панелью на телефоне);
- запуск фоновых задач, как в старом `OpenFluxApp`: `LaunchedEffect(Unit) { container.updates.start() }` и проверка скриптов при `settings.experimental`.

`wide` для `AppNavHost` = ширина области содержимого ≥ 900 dp.

- [ ] **Step 1: Падающие тесты** (`ShellTest`; фейковый контейнер — взять фабрику из существующих тестов: найти `AppContainer(` в `src/jvmTest` (`ExperimentalGateTest.kt`/`NodeWizardModelTest.kt`) и вынести общую `fun testContainer(): AppContainer` в `src/jvmTest/kotlin/io/openflux/app/TestContainer.kt`, **не меняя** старые тесты):

```kotlin
package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.shell.OpenFluxApp2
import io.openflux.app.shell.WidthClass
import io.openflux.app.shell.widthClassOf
import io.openflux.desktop.ui.NoScrollbars
import io.openflux.desktop.ui.Shortcuts
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ShellTest {
    @Test
    fun widthClasses() {
        assertEquals(WidthClass.Phone, widthClassOf(320.dp))
        assertEquals(WidthClass.Compact, widthClassOf(800.dp))
        assertEquals(WidthClass.Medium, widthClassOf(1200.dp))
        assertEquals(WidthClass.Expanded, widthClassOf(1600.dp))
    }

    @Test
    fun sectionsAndAddMenu() = runComposeUiTest {
        setContent { OpenFluxApp2(testContainer(), NoScrollbars, Shortcuts()) }
        onNodeWithText("Серверы").performClick()
        onNodeWithText("Экран появится в следующей части").assertIsDisplayed()
        onNodeWithContentDescription("Добавить").performClick()
        onNodeWithText("Свой сервер").assertIsDisplayed()
        onNodeWithText("Ключ, ссылка или файл").assertIsDisplayed()
    }
}
```

Кадры для ревью — в `CatalogTest`:

```kotlin
    @Test fun shellPhone() = Shots.render("shell") { OpenFluxApp2(testContainer(), NoScrollbars, Shortcuts()) }
    @Test fun shellSmall() = Shots.render("shell", Shots.SMALL) { OpenFluxApp2(testContainer(), NoScrollbars, Shortcuts()) }
    @Test fun shellWide() = Shots.render("shell", Shots.WIDE) { OpenFluxApp2(testContainer(), NoScrollbars, Shortcuts()) }
    @Test fun addMenu() = Shots.render("add-menu") { AddMenu(cameraScan = true, onChoice = {}, onDismiss = {}) }
```

и Review Focus 5:

в `ShellTest`:

```kotlin
    @Test
    fun bottomBarFitsSmallScreen() = runComposeUiTest {
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.3f)) {
                Box(Modifier.requiredSize(320.dp, 640.dp)) { OpenFluxApp2(testContainer(), NoScrollbars, Shortcuts()) }
            }
        }
        for (label in listOf("Главная", "Серверы", "Поделиться", "Настройки")) onNodeWithText(label).assertIsDisplayed()
    }
```

(импорты: `androidx.compose.foundation.layout.Box`, `androidx.compose.foundation.layout.requiredSize`, `androidx.compose.runtime.CompositionLocalProvider`, `androidx.compose.ui.Modifier`, `androidx.compose.ui.platform.LocalDensity`, `androidx.compose.ui.unit.Density`). Подписи нижней панели — `maxLines = 1`, `softWrap = false`, шрифт `caption`. Обрезку дополнительно проверить глазами по `shell-small.png`.

- [ ] **Step 2:** прогон → FAIL.

- [ ] **Step 3: Реализация** по Interfaces и описанию.

- [ ] **Step 4:** прогон `--tests 'io.openflux.app.ShellTest'` → PASS; `SHOTS=../../shots-part1 ./gradlew :shared:jvmTest -PskipCore=true --tests 'io.openflux.app.CatalogTest'` → PASS; осмотреть `shell.png`, `shell-small.png`, `shell-wide.png`, `add-menu.png`.

- [ ] **Step 5: Коммит:** `git add src/commonMain/kotlin/io/openflux/app/shell src/jvmTest/kotlin/io/openflux/app && git commit -m "New UI: shell with five places, the add menu and the entry point"`

---

### Task 8: Сохранение и восстановление стеков

**Files:**
- Modify: `shared/src/commonMain/kotlin/io/openflux/desktop/model/AppSettings.kt` (одно поле)
- Modify: `shared/src/commonMain/kotlin/io/openflux/app/shell/OpenFluxApp2.kt`
- Create: `shared/src/commonMain/kotlin/io/openflux/app/nav/RememberNavigator.kt`
- Test: `shared/src/jvmTest/kotlin/io/openflux/app/NavStateTest.kt`

**Interfaces:**
- Consumes: `AppNavigator.restore`, `NavState.encode/decode` (Tasks 4–5).
- Produces:

```kotlin
// AppSettings: новое поле в конце конструктора
    /** The new interface's place and screens (JSON of io.openflux.app.nav.NavState), restored at the next start on the desktop. */
    val navState: String = "",
// RememberNavigator.kt
/** Is the object this screen shows still there? A screen of a deleted profile or a forgotten server is not restored. */
fun aliveIn(container: AppContainer): (Dest) -> Boolean
@Composable fun rememberAppNavigator(container: AppContainer): AppNavigator
```

`rememberAppNavigator`:
1. `rememberSaveable(saver = Saver(save = { it.snapshot().encode() }, restore = { AppNavigator.restore(NavState.decode(it), aliveIn(container)) }))` — переживает смерть процесса на Android;
2. начальное значение (когда сохранённого нет): на десктопе (`container.platform.kind == PlatformKind.Desktop`) — `AppNavigator.restore(NavState.decode(container.settings.settings.value.navState), aliveIn(container))`; на Android — `AppNavigator()`;
3. на десктопе — `LaunchedEffect(navigator) { snapshotFlow { navigator.snapshot() }.distinctUntilChanged().debounce(500).collect { s -> container.settings.update { it.copy(navState = s.encode()) } } }`;
4. `LaunchedEffect(navigator) { container.profiles.profiles.collect { list -> val ids = list.map { it.id }.toSet(); navigator.forget { it.profileId != null && it.profileId !in ids } } }` — удалённый профиль убирает свои экраны (spec 4.2 п. 4); то же для `settings.knownServers` и `serverId`.

`aliveIn`: `profileId != null` → профиль с таким `id` есть в `container.profiles.profiles.value`; `serverId != null` → есть `KnownServer` с `"${host}:${port}:${user}" == serverId`; иначе `true`.

- [ ] **Step 1: Падающие тесты.**

```kotlin
package io.openflux.app

import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.nav.NavState
import io.openflux.app.nav.Section
import io.openflux.app.nav.aliveIn
import io.openflux.desktop.model.KnownServer
import io.openflux.desktop.model.AppSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavStateTest {
    @Test
    fun aliveFollowsTheContainer() {
        val c = testContainer()
        c.settings.update { it.copy(knownServers = listOf(KnownServer("h", 22, "root"))) }
        val alive = aliveIn(c)
        assertTrue(alive(Dest.Server("h:22:root")))
        assertFalse(alive(Dest.Server("x:22:root")))
        assertFalse(alive(Dest.Profile("no-such-profile")))
        assertTrue(alive(Dest.SettingsPage("about")))
    }

    @Test
    fun settingsWithoutNavStateStillLoad() {
        // A settings file of 2.14.x has no navState: it must load with the default.
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), """{"theme":"Dark"}""")
        assertEquals("", old.navState)
    }

    @Test
    fun desktopRestartRestoresTheScreen() {
        val c = testContainer()
        c.settings.update { it.copy(knownServers = listOf(KnownServer("h", 22, "root"))) }
        val saved = AppNavigator().apply { open(Dest.Server("h:22:root")) }.snapshot().encode()
        val n = AppNavigator.restore(NavState.decode(saved), aliveIn(c))
        assertEquals(Section.Servers, n.section)
        assertEquals(Dest.Server("h:22:root"), n.top)
    }
}
```

Если в `testContainer()` настройки только в памяти и `AppSettings` хранится иначе, чем JSON — посмотреть `src/jvmMain/.../data` (как читается файл настроек) и проверить совместимость тем же способом, что `SettingsMigrationTest`.

- [ ] **Step 2:** прогон → FAIL.

- [ ] **Step 3: Реализация** по Interfaces; в `OpenFluxApp2` заменить `remember { AppNavigator() }` на `rememberAppNavigator(container)`.

- [ ] **Step 4:** прогон `--tests 'io.openflux.app.NavStateTest'` и весь `:shared:jvmTest` → PASS (в т. ч. `SettingsMigrationTest`).

- [ ] **Step 5: Коммит:** `git add src/commonMain/kotlin/io/openflux/desktop/model/AppSettings.kt src/commonMain/kotlin/io/openflux/app src/jvmTest/kotlin/io/openflux/app/NavStateTest.kt && git commit -m "New UI: screens survive process death on Android and a restart on the desktop"`

---

### Task 9: Запуск нового интерфейса в обоих приложениях

**Files:**
- Modify: `OpenFluxDesktop/desktopApp/src/main/kotlin/io/openflux/desktop/main.kt`
- Modify: `OpenFluxAndroid/gradle/libs.versions.toml`, `OpenFluxAndroid/shared` (указатель сабмодуля), `OpenFluxAndroid/androidApp/src/debug/AndroidManifest.xml`, `OpenFluxAndroid/androidApp/src/main/kotlin/io/openflux/android/MainActivity.kt`
- Modify: `OpenFluxDesktop/shared` (указатель сабмодуля в `OpenFluxDesktop`)

**Interfaces:**
- Consumes: `OpenFluxApp2` (Task 7).

- [ ] **Step 1: Десктоп.** В `main.kt` рядом с вызовом `OpenFluxApp(...)`:

```kotlin
/** OPENFLUX_UI=new runs the interface being rewritten (shared/docs/specs/2026-10-12-ui-redesign-design.md). */
private val newUi = System.getenv("OPENFLUX_UI") == "new"
```

и в `Window { … }`: `if (newUi) OpenFluxApp2(container, DesktopScrollbars, shortcuts, KcefBrowserViews) else OpenFluxApp(container, DesktopScrollbars, shortcuts, KcefBrowserViews)` с импортом `io.openflux.app.shell.OpenFluxApp2`.

- [ ] **Step 2: Проверка десктопа.** `./gradlew :desktopApp:compileKotlin -PskipCore=true` → SUCCESS. Запуск: `OPENFLUX_UI=new ./gradlew :desktopApp:run -PskipCore=true` (в фоне), убедиться: окно открывается, колонка разделов слева, ＋ открывает меню, Ctrl+1…4 переключают разделы, закрыть и открыть снова — раздел восстановлен. Без переменной — старый интерфейс как раньше.

- [ ] **Step 3: Коммиты десктопа.** В `OpenFluxDesktop`: `git add desktopApp/src/main/kotlin/io/openflux/desktop/main.kt shared && git commit -m "Desktop: OPENFLUX_UI=new runs the new interface"`.

- [ ] **Step 4: Android: сабмодуль и каталог.**

```bash
cd OpenFluxAndroid
git switch -c nav3
cd shared && git fetch ../../OpenFluxDesktop/shared nav3 && git switch -c nav3 FETCH_HEAD && cd ..
```

В `OpenFluxAndroid/gradle/libs.versions.toml` — те же строки, что в Task 0 Step 1.

- [ ] **Step 5: Android: отладочный вход.** В `androidApp/src/debug/AndroidManifest.xml` внутри `<application …>` (атрибуты оставить):

```xml
        <!-- Debug builds only: a second launcher icon runs the new interface (spec 1a). -->
        <activity-alias
            android:name="io.openflux.android.NewUiActivity"
            android:targetActivity="io.openflux.android.MainActivity"
            android:label="OpenFlux · новый"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>
```

В `MainActivity.setContent`: `val newUi = BuildConfig.DEBUG && componentName.className.endsWith(".NewUiActivity")`; `if (newUi) OpenFluxApp2(container, NoScrollbars, remember { Shortcuts() }, WebBrowserViews) else OpenFluxApp(...)` (как сейчас). Проверить имя `targetActivity` по основному манифесту (`androidApp/src/main/AndroidManifest.xml`).

- [ ] **Step 6: Проверка Android.** `./gradlew :androidApp:assembleDebug` из `OpenFluxAndroid` (JDK 21, `-PskipCore=true`, если сборка ядра для Android требует Go — посмотреть `androidApp/build.gradle.kts`) → SUCCESS. Установить на эмулятор (`adb devices`; не на подключённый телефон без спроса владельца): `adb -s emulator-5554 install -r androidApp/build/outputs/apk/debug/*.apk`, запустить «OpenFlux · новый»: нижняя панель из пяти пунктов, ＋ открывает шторку, жест «назад» с корня раздела ведёт на Главную с предпросмотром, с Главной — выход. В «Параметрах разработчика» включить «Не сохранять действия», открыть Серверы → свернуть → вернуться: раздел восстановлен. Если «назад» срабатывает дважды (два перехода за один жест) — убрать `PlatformBackHandler` из `Shell` (Task 7) и повторить.

- [ ] **Step 7: Коммит Android.** `git add gradle/libs.versions.toml shared androidApp/src/debug/AndroidManifest.xml androidApp/src/main/kotlin/io/openflux/android/MainActivity.kt && git commit -m "Android: a debug launcher icon runs the new interface"`

- [ ] **Step 8: Итог части.** Весь `:shared:jvmTest` → PASS; `:desktopApp:compileKotlin` и `:androidApp:assembleDebug` → SUCCESS; каталог PNG перегенерирован; в `shared/docs/plans/2026-10-12-ui-part1-skeleton.md` отмечены выполненные шаги.
