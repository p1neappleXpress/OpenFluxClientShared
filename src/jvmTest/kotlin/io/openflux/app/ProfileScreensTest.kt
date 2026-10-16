package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.shell.OpenFluxApp
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ProfileScreensTest {
    @Test
    fun aProfileMadeByHandIsSavedAndShown() = runComposeUiTest {
        val c = testContainer()
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithContentDescription("Добавить").performClick()
        onNodeWithText("Вручную").performClick()
        onNodeWithText("Сохранить").performClick()
        onNodeWithText("Укажите название").assertIsDisplayed()
        onNode(hasSetTextAction() and hasText("Название")).performTextInput("Нода друга")
        onNode(hasSetTextAction() and hasText("Ссылка на документ")).performTextInput("https://docs.yandex.ru/edit/d/" + "a".repeat(20))
        onNodeWithText("Сгенерировать ключ").performClick()
        onNodeWithText("Сохранить").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("AES-256-GCM")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("Нода друга"), c.profiles.profiles.value.map { it.name })
        onNodeWithText("AES-256-GCM").assertIsDisplayed()
    }

    @Test
    fun shots() {
        val c = homeWorld()
        for ((name, dest) in listOf("profile" to Dest.Profile("a"), "profile-edit" to Dest.ProfileEdit("a"), "profile-new" to Dest.ProfileEdit())) {
            c.settings.update { it.copy(navState = AppNavigator().apply { open(dest) }.snapshot().encode()) }
            Shots.render(name) { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        }
    }
}
