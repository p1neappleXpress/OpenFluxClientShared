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
