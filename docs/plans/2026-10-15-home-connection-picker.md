# Главная: единый выбор подключения: план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** На Главной выбор подключения и подключение становятся очевидными и одинаковыми на всех ширинах: одна нажимаемая строка над круглой кнопкой открывает пикер, выбор в пикере только меняет цель, а подключает всегда круглая кнопка.

**Architecture:** `HomeRoot` теряет ветку широкого окна и боковую панель `ConnectionList`; пикер — всегда `Sheet`. Строка выбранного подключения в `Main` становится всегда нажимаемой (шеврон ▾, подпись секции «Подключение») и открывает `Sheet` с заголовком «Выбрать подключение». `ConnectionList`/`ProfileRow` выбирают строку без подключения; подключение двойным щелчком убирается.

**Tech Stack:** Kotlin Multiplatform (`commonMain`), Compose Multiplatform 1.11, `Sheet` (ModalBottomSheet на телефоне, Dialog в широком окне), Compose UI Test (`runComposeUiTest`), Skiko-скриншоты (`Shots`), Gradle.

**Spec:** `docs/specs/2026-10-15-home-connection-picker-design.md` (читать вместе с ним); контекст — `docs/specs/2026-10-12-ui-redesign-design.md`, разделы 6 и 6.1.

## Global Constraints

- Работа ведётся в сабмодуле `OpenFluxDesktop/shared` на ветке `home-connection-picker` (от `subscription-screen` = `b53532b`); пути ниже — от корня репозитория shared. Тесты запускаются из `C:\Users\meepo\openflux\OpenFluxDesktop`: `./gradlew :shared:jvmTest --tests '<класс>'`.
- В рабочем дереве уже есть незакоммиченные правки пользователя по «местам» (`Slots.kt`, `SlotsTest.kt`, `BotSlotsManagerTest.kt`, `Account*.kt`, `Purchase.kt`, `OpenFluxBot.kt`, `BotAccount.kt`, `AccountShotsTest.kt`, `PurchaseModelTest.kt`). **Не трогать их и не коммитить.** Наши коммиты включают только файлы Главной, тесты Главной и документы.
- Коммиты: сообщение в стиле репозитория (`feat(home): …`, `test: …`, `docs: …`), **без** строк `Co-Authored-By`, без упоминаний ИИ.
- Тексты для людей — по-русски, без слов об обходе блокировок, цензуры, DPI и белых списков.
- Оставлено вне плана: сам экран «Приступим» (нет подключений), модель `HomeModel`, переключатель «Клиент/Выходная нода», чипы «Весь трафик»/«Системный прокси», баннер, «Сведения» — не меняются.
- Ничего не пушить в `OpenFluxTeam`/форк до подтверждения пользователя (Task 3 выполняется после отдельного «да»).

## Review Focus

Что вероятнее всего сломается у человека и закреплено проверкой:

1. На широком окне строка подключения раньше была **некликабельна** (`pickable = false`): выбор и подключение было не найти. Теперь строка всегда нажимаема — Task 1, `theChosenConnectionOpensThePicker`.
2. Выбор в пикере не должен подключать (подключает только круглая кнопка) — Task 1, `pickingDoesNotConnect`; уже есть `HomePickTest.clickingASubscriptionLocationChoosesIt`.
3. На широком окне больше нет постоянной боковой панели: чужие подключения видны только после открытия пикера — Task 1, `theChosenConnectionOpensThePicker` (в начале `Франкфурт` не виден, `Выбрать подключение` не виден).
4. Заголовок пикера «Выбрать подключение» не совпадает с подписью секции «Подключение» (иначе тест и человек путаются) — Task 1.
5. Двойной щелчок больше не подключает — Task 1 (удаление `onDoubleClick`), закреплено тем, что одиночное нажатие только выбирает (`pickingDoesNotConnect`).
6. Существующие тесты Главной и выбор подключения не ломаются (`HomeScreenTest`, `HomePickTest`, `HomeBotNoteTest`) — Task 1.

---

## File Structure

| Файл | Что делает |
|---|---|
| `src/commonMain/kotlin/io/openflux/app/home/HomeScreen.kt` | `HomeRoot` — одна колонка, пикер всегда `Sheet`, заголовок «Выбрать подключение»; `Main` — строка-выбор всегда нажимаема; `ConnectionList`/`ProfileRow` — выбор без подключения, без двойного щелчка |
| `src/jvmTest/kotlin/io/openflux/app/HomeScreenTest.kt` | Новые UI-тесты: строка открывает пикер; выбор не подключает; кнопка подключает выбранное |
| `src/jvmTest/kotlin/io/openflux/app/HomeShotsTest.kt` | Без правок кода; перегенерировать картинки (в т.ч. `wide` — без боковой панели) |
| `docs/specs/2026-10-15-home-connection-picker-design.md` | Спецификация (уже написан) |
| `docs/plans/2026-10-15-home-connection-picker.md` | Этот план |

---

### Task 1: Единый пикер на Главной

**Files:**
- Modify: `src/commonMain/kotlin/io/openflux/app/home/HomeScreen.kt`
- Test: `src/jvmTest/kotlin/io/openflux/app/HomeScreenTest.kt`

**Interfaces:**
- `Main(m: HomeModel, chosen: Profile?, state: ConnectionState, onPick: () -> Unit)` — параметр `pickable` убирается.
- `ProfileRow(p: Profile, selected: Boolean, dot: Status, touch: Boolean, onChoose: () -> Unit)` — параметры `onChosen` и `onDoubleClick` убираются.
- `ConnectionList(m: HomeModel, profiles: List<Profile>, chosen: Profile?, onChosen: () -> Unit)` — сигнатура прежняя; `onChosen` вызывается после выбора (закрыть пикер).

- [ ] **Step 1: Написать падающий тест**

Открой `src/jvmTest/kotlin/io/openflux/app/HomeScreenTest.kt`, добавь импорты и два теста. Итоговый файл начинается с таких импортов:

```kotlin
package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.service.AppContainer
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
```

Добавь в класс `HomeScreenTest` (после `theDetailsOpenOnRequest`):

```kotlin
    @Test
    fun theChosenConnectionOpensThePicker() = runComposeUiTest {
        val c = homeWorld()
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        // No side panel and no picker: another connection is not on screen until the row is tapped.
        assertTrue(onAllNodes(hasText("Франкфурт")).fetchSemanticsNodes().isEmpty(), "no side panel any more")
        assertTrue(onAllNodes(hasText("Выбрать подключение")).fetchSemanticsNodes().isEmpty(), "the picker is closed")
        onAllNodesWithText("Амстердам")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Выбрать подключение")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Франкфурт").assertIsDisplayed()
    }

    @Test
    fun pickingDoesNotConnectOnlyTheButtonDoes() = runComposeUiTest {
        val conn = ScriptedConnection()
        val c = homeWorld(conn)
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onAllNodesWithText("Амстердам")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Франкфурт")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Франкфурт").performClick()
        waitForIdle()
        assertEquals("b", c.settings.settings.value.selectedProfileId, "picking chooses")
        assertEquals(ConnectionState.Idle, conn.state.value, "picking never connects")
        onNodeWithContentDescription("Подключить").performClick()
        assertEquals("b", (conn.state.value as ConnectionState.Connected).profile.id, "the button connects the chosen one")
    }
```

- [ ] **Step 2: Запустить тесты и убедиться, что они падают**

Run: `./gradlew :shared:jvmTest --tests 'io.openflux.app.HomeScreenTest'`
Expected: `theChosenConnectionOpensThePicker` FAIL (на широком окне строка некликабельна, заголовок пикера ещё «Подключение»); `pickingDoesNotConnectOnlyTheButtonDoes` может упасть на последней проверке (на широком окне выбор из панели, но кнопка подключает выбранное — вероятно, проходит; главный драйвер — первый тест).

- [ ] **Step 3: Реализовать изменение**

В `src/commonMain/kotlin/io/openflux/app/home/HomeScreen.kt`:

1. Замени функцию `HomeRoot` целиком на:

```kotlin
/** Home (spec 6): the button, what it connects, a few switches, one banner, and the details on request. */
@Composable
fun HomeRoot() {
    val container = LocalAppContainer.current
    val m = remember(container) { HomeModel(container) }
    val profiles by container.profiles.profiles.collectAsState()
    val settings by container.settings.settings.collectAsState()
    val state by container.connection.state.collectAsState()
    val subscription by container.subscription.state.collectAsState()
    val preparing by container.subscription.preparing.collectAsState()
    val bot by container.bot.state.collectAsState()
    var picker by rememberSaveable { mutableStateOf(false) }
    val chosen = m.selected(profiles)
    ScreenScaffold(null, scroll = false) {
        if (profiles.isEmpty()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.l)) {
                StartPanel(hasSubscription = subscription != null, preparing = preparing || bot.preparing, botSignedIn = bot.session != null, botNote = bot.connectionNote)
            }
            return@ScreenScaffold
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = AppTheme.spacing.s),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.l),
        ) {
            Main(m, chosen, state, onPick = { picker = true })
        }
    }
    if (picker) {
        Sheet(onDismiss = { picker = false }, title = "Выбрать подключение") {
            Column(Modifier.padding(horizontal = AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                ConnectionList(m, profiles, chosen, onChosen = { picker = false })
            }
        }
    }
}
```

2. В функции `Main` поменяй сигнатуру и строку выбранного подключения. Было:

```kotlin
private fun Main(m: HomeModel, chosen: Profile?, state: ConnectionState, onPick: () -> Unit, pickable: Boolean) {
```

стало:

```kotlin
private fun Main(m: HomeModel, chosen: Profile?, state: ConnectionState, onPick: () -> Unit) {
```

и блок строки выбранного подключения (был `Section(null)` с `trailing`/`onClick` по `pickable`) замени на:

```kotlin
    if (chosen != null) {
        Section("Подключение") {
            RowItem(
                chosen.name,
                subtitle = profileLine(chosen),
                leading = { StatusDot(profileDot(state, chosen.id)) },
                trailing = { Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = AppTheme.colors.textSecondary) },
                onClick = onPick,
            )
        }
    }
```

3. В `ConnectionList` вызов `ProfileRow` (внутри `for (p in list)`) приведи к:

```kotlin
                ProfileRow(p, selected = p.id == chosen?.id, dot = profileDot(state, p.id), touch = touch, onChoose = { m.choose(p); onChosen() })
```

4. Функцию `ProfileRow` поменяй: убери параметр `onChosen` и аргумент `onDoubleClick`. Итоговая сигнатура и вызов `RowItem`:

```kotlin
@Composable
private fun ProfileRow(p: Profile, selected: Boolean, dot: Status, touch: Boolean, onChoose: () -> Unit) {
    // …
    RowItem(
        p.name,
        subtitle = profileLine(p),
        leading = { StatusDot(dot) },
        selected = selected,
        onClick = onChoose,
        onLongClick = if (touch) ({ menu = true }) else null,
        trailing = {
            // … без изменений (Box + IconAction + DropdownMenu)
        },
    )
    // … остальное без изменений
}
```

5. Убери ставшие ненужными импорты (если компилятор их не использует): `androidx.compose.foundation.layout.BoxWithConstraints`, `androidx.compose.foundation.layout.width`, `androidx.compose.foundation.layout.fillMaxHeight`. Импорты `Box`, `Row`, `RowItem` и `androidx.compose.material.icons.rounded.ExpandMore` **оставить** (используются).

- [ ] **Step 4: Запустить тесты и убедиться, что проходят**

Run: `./gradlew :shared:jvmTest --tests 'io.openflux.app.HomeScreenTest' --tests 'io.openflux.app.HomePickTest' --tests 'io.openflux.app.HomeBotNoteTest'`
Expected: PASS, 0 падений.

- [ ] **Step 5: Полный прогон общего слоя**

Run: `./gradlew :shared:jvmTest`
Expected: BUILD SUCCESSFUL, 0 падений (базовый набор ветки; тесты «мест» из незакоммиченных правок пользователя тоже должны быть зелёными).

- [ ] **Step 6: Коммит (без файлов пользователя)**

```bash
git add src/commonMain/kotlin/io/openflux/app/home/HomeScreen.kt \
        src/jvmTest/kotlin/io/openflux/app/HomeScreenTest.kt \
        docs/specs/2026-10-15-home-connection-picker-design.md \
        docs/plans/2026-10-15-home-connection-picker.md
git commit -m "feat(home): one tap opens the connections picker on every window width; picking only chooses, the round button connects"
```

Проверь перед коммитом: `git status` — файлы пользователя (`Slots.kt` и др.) не в индексе.

---

### Task 2: Скриншоты и проверка глазами

**Files:**
- Modify (перегенерация PNG, в git не коммитятся): `SHOTS=<dir>` вне репозитория.
- Read for review: `HomeShotsTest.kt` (без правок кода).

- [ ] **Step 1: Перегенерировать картинки Главной**

Run (PowerShell, из `C:\Users\meepo\openflux\OpenFluxDesktop`):

```powershell
$env:SHOTS = "C:\Users\meepo\AppData\Local\Temp\opencode\home-shots"
./gradlew :shared:jvmTest --tests 'io.openflux.app.HomeShotsTest'
Get-ChildItem $env:SHOTS
```

Expected: файлы `home*.png` и `home-list*.png` (тёмная/светлая, `-small`, `-wide`).

- [ ] **Step 2: Посмотреть глазами**

Открой полученные PNG (Read — это изображения) и убедись:
- `home-wide.png`: **нет** боковой панели; под круглой кнопкой видна строка «Амстердам» с шевроном ▾ и подписью «Подключение».
- `home.png` / `home-small.png`: строка читается, не обрезается; кнопка по центру; баннер/чипы/«Сведения» на месте.
- `home-list.png`: список подключений как прежде (поиск/группы/«Добавить»).

Если что-то обрезается или наезжает — поправь отступы в `HomeRoot`/`Main` и повтори Step 1.

- [ ] **Step 3 (если были правки вёрстки): тесты и коммит**

Run: `./gradlew :shared:jvmTest --tests 'io.openflux.app.HomeScreenTest'`
Если вёрстка менялась —

```bash
git add src/commonMain/kotlin/io/openflux/app/home/HomeScreen.kt
git commit -m "fix(home): keep the connection row readable on a narrow window"
```

---

### Task 3: Публикация ветки, влитие в main, gitlinks

> Выполняется **после** подтверждения пользователя. Ничего не пушить и не вливать до его слова.

**Files:**
- Modify (git): ветка `home-connection-picker` → `OpenFluxClientShared`; gitlink `shared` в `OpenFluxDesktop` и `OpenFluxAndroid`.
- Test: сборки обоих приложений.

- [ ] **Step 1: Собрать оба приложения на ветке (до публикации)**

Run: `./gradlew :desktopApp:compileKotlin` (из `OpenFluxDesktop`)
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :androidApp:compileDebugKotlin` (из `OpenFluxAndroid`)
Expected: BUILD SUCCESSFUL. Если Android-сборка падает из-за незакоммиченных файлов пользователя в `OpenFluxAndroid/shared` — записать это как известное и доложить (наши файлы Главной на Android не отличаются от Desktop).

- [ ] **Step 2: Перенести ветку в корневой клон OpenFluxClientShared**

Из `C:\Users\meepo\openflux\OpenFluxDesktop\shared` (там определён remote `local` = `../../OpenFluxClientShared`):

```bash
git push local home-connection-picker:home-connection-picker
```

Затем в `C:\Users\meepo\openflux\OpenFluxClientShared`:

```bash
git fetch . home-connection-picker
git checkout main
git merge --ff-only home-connection-picker   # 564c06f — прямой предок b53532b, влитие линейное
git log --oneline -3
```

Expected: `main` указывает на верхний коммит ветки; `git log` показывает наши коммиты `feat(home): …` и `docs: …`.

- [ ] **Step 3: Запустить тесты на main корневого клона**

Run (из `OpenFluxDesktop`, проверив, что сабмодуль `shared` указывает на этот коммит — см. Step 4): `./gradlew :shared:jvmTest`
Expected: 0 падений. (Если удобнее — тесты уже прогонялись на этом же дереве в Task 1; на этом шаге достаточно убедиться, что `git -C OpenFluxClientShared log -1` и `git -C OpenFluxDesktop/shared rev-parse HEAD` совпадают.)

- [ ] **Step 4: Обновить gitlink в OpenFluxDesktop**

Из `C:\Users\meepo\openflux\OpenFluxDesktop\shared`:

```bash
git fetch local home-connection-picker:home-connection-picker
git checkout home-connection-picker
git log --oneline -1
```

Из `C:\Users\meepo\openflux\OpenFluxDesktop`:

```bash
git add shared
git commit -m "chore: point the shared submodule at the unified connection picker"
```

- [ ] **Step 5: Обновить gitlink в OpenFluxAndroid**

Из `C:\Users\meepo\openflux\OpenFluxAndroid\shared` (remote `local` = `../../OpenFluxClientShared`):

```bash
git fetch local home-connection-picker:home-connection-picker
git checkout home-connection-picker
git log --oneline -1
```

Из `C:\Users\meepo\openflux\OpenFluxAndroid`:

```bash
git add shared
git commit -m "chore: point the shared submodule at the unified connection picker"
```

- [ ] **Step 6: Опубликовать (по слову пользователя)**

В `C:\Users\meepo\openflux\OpenFluxClientShared` (remote `meepo` = `OpenFluxTeam/OpenFluxClientShared`):

```bash
git push meepo main home-connection-picker
```

Замечание (см. спецификацию, «Публикация»): сабмодули приложений прописаны на форк `meepo161/OpenFluxClientShared`. Если коммит нужен и там — дополнительно `git push origin main home-connection-picker` из корневого клона (или сменить URL сабмодулей в `.gitmodules`). Подтвердить у пользователя перед push.
