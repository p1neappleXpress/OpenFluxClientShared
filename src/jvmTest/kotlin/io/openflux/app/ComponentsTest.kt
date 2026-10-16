package io.openflux.app

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import io.openflux.app.components.Collapsible
import io.openflux.app.components.ScreenScaffold
import io.openflux.app.nav.LocalInPane
import io.openflux.app.components.RowItem
import io.openflux.app.components.SecretField
import io.openflux.app.components.Segmented
import io.openflux.app.theme.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ComponentsTest {
    @Test
    fun collapsibleHidesUntilOpened() = runComposeUiTest {
        setContent { AppTheme(dark = true, touch = false) { Collapsible("Дополнительно") { Text("порт") } } }
        onNodeWithText("порт").assertDoesNotExist()
        onNodeWithText("Дополнительно").performClick()
        onNodeWithText("порт").assertIsDisplayed()
    }

    @Test
    fun segmentedReportsChoice() = runComposeUiTest {
        var picked = "L4"
        setContent {
            AppTheme(dark = true, touch = false) {
                var s by remember { mutableStateOf("L4") }
                Segmented(listOf("L4", "L3"), s, { s = it; picked = it }, label = { it })
            }
        }
        onNodeWithText("L3").performClick()
        assertEquals("L3", picked)
    }

    @Test
    fun secretFieldRevealsOnDemand() = runComposeUiTest {
        setContent { AppTheme(dark = true, touch = false) { SecretField("hunter2", {}, "Пароль") } }
        // What is drawn, not the field's value (which semantics always carry as InputText).
        fun shown(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))
        onNode(shown("hunter2")).assertDoesNotExist()
        onNode(shown("•••••••")).assertExists()
        onNodeWithContentDescription("Показать").performClick()
        onNode(shown("hunter2")).assertIsDisplayed()
    }

    @Test
    fun rowItemClicks() = runComposeUiTest {
        var clicks = 0
        setContent { AppTheme(dark = true, touch = false) { RowItem("Сервер", onClick = { clicks++ }) } }
        onNodeWithText("Сервер").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun scaffoldHasNoBackArrowBesideItsList() = runComposeUiTest {
        setContent {
            AppTheme(dark = true, touch = false) {
                Column {
                    ScreenScaffold("Один", Modifier.height(100.dp), onBack = {}) {}
                    CompositionLocalProvider(LocalInPane provides true) { ScreenScaffold("Два", Modifier.height(100.dp), onBack = {}) {} }
                }
            }
        }
        onAllNodesWithContentDescription("Назад").assertCountEquals(1)
    }
}
