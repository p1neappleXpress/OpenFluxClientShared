package io.openflux.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.openflux.app.shell.OpenFluxApp
import io.openflux.app.shell.WidthClass
import io.openflux.app.shell.widthClassOf
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
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
        setContent { OpenFluxApp(testContainer(), NoScrollbars, Shortcuts()) }
        onNodeWithText("Поделиться").performClick()
        onNodeWithText("Пока нечем делиться", substring = true).assertIsDisplayed()
        onNodeWithContentDescription("Добавить").performClick()
        onNodeWithText("Свой сервер").assertIsDisplayed()
        onNodeWithText("Ключ, ссылка или файл").assertIsDisplayed()
    }

    @Test
    fun bottomBarFitsSmallScreen() = runComposeUiTest {
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.3f)) {
                Box(Modifier.requiredSize(320.dp, 640.dp)) { OpenFluxApp(testContainer(), NoScrollbars, Shortcuts()) }
            }
        }
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        // Every place is there and named for a screen reader, even where only the chosen one shows its label.
        for (label in listOf("Главная", "Серверы", "Поделиться", "Настройки")) onNode(hasContentDescription(label) and tab).assertIsDisplayed()
        onNode(hasText("Главная") and tab).assertIsDisplayed()
    }
}
