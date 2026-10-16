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
