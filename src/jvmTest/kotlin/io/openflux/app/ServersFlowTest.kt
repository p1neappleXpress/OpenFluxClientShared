package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.ExitAddress
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ServersFlowTest {
    @Test
    fun aNewServerGetsAQuickNode() = runComposeUiTest {
        val server = FakeServer("a.example")
        val conn = ScriptedConnection().apply { address = { ExitAddress.Known("a.example") } }
        val c = testContainer(node = FakeNodeService(server), connection = conn)
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Серверы").performClick()
        onNodeWithText("Добавить сервер").performClick()
        onNode(hasSetTextAction() and hasText("Адрес сервера")).performTextInput("a.example")
        onNode(hasSetTextAction() and hasText("Пароль")).performTextInput("pw")
        onNodeWithText("Подключиться").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Новый сервер")).fetchSemanticsNodes().size > 1 }
        onNodeWithText("Доверять").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Быстро")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Быстро").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Работает", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Отключить").assertIsDisplayed() // the check left this device connected through the new node
        assertEquals(1, server.channels.size)
        assertEquals("Нода a.example", c.profiles.profiles.value.single().name)
    }
}
