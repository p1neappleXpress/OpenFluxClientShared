package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotState
import io.openflux.desktop.service.BotAccountManager
import kotlin.test.Test

/** Home tells a signed-in person why there is nothing to connect to, instead of offering to sign in again. */
@OptIn(ExperimentalTestApi::class)
class HomeBotNoteTest {
    @Test
    fun signedInWithoutAConnectionSaysWhy() = runComposeUiTest {
        val c = testContainer()
        c.botRepo.save(BotState(session = BotSession("ofs_x", 7, "ivan"), connectionNote = BotAccountManager.NO_FREE_SLOT_TG))
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText(BotAccountManager.NO_FREE_SLOT_TG).assertIsDisplayed()
        onNodeWithText("Открыть подписку").assertIsDisplayed()
    }

    @Test
    fun signedInAndNothingYetStillSaysSo() = runComposeUiTest {
        val c = testContainer()
        c.botRepo.save(BotState(session = BotSession("ofs_x", 7, "ivan")))
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithText("Вы вошли, подключения пока нет").assertIsDisplayed()
    }
}
