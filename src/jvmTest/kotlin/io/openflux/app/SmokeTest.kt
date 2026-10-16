package io.openflux.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
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
        onNodeWithText("экран a").assertIsDisplayed()
    }
}
