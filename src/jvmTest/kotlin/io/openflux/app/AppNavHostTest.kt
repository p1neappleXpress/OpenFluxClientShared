package io.openflux.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

    private fun host(n: AppNavigator): @Composable () -> Unit = {
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
