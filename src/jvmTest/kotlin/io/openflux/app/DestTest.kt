package io.openflux.app

import io.openflux.app.nav.Dest
import io.openflux.app.nav.NavState
import io.openflux.app.nav.Section
import io.openflux.app.nav.rootOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DestTest {
    @Test
    fun everySectionHasItsRoot() {
        for (s in Section.entries) {
            val root = rootOf(s)
            assertEquals(s, root.owner)
            assertTrue(root.isRoot)
        }
    }

    @Test
    fun owners() {
        assertEquals(Section.Home, Dest.Profile("p").owner)
        assertEquals(Section.Servers, Dest.Node("h:22:root", "of-a").owner)
        assertEquals(Section.Share, Dest.Give("p").owner)
        assertEquals("h:22:root", Dest.Node("h:22:root", "of-a").serverId)
    }

    @Test
    fun stateRoundTrips() {
        val s = NavState(
            section = Section.Servers,
            stacks = NavState().stacks + (Section.Servers to listOf(Dest.Servers, Dest.Server("h:22:root"), Dest.Node("h:22:root", "of-a"))),
        )
        assertEquals(s, NavState.decode(s.encode()))
    }

    @Test
    fun decodeGarbageIsNull() {
        assertNull(NavState.decode(""))
        assertNull(NavState.decode(null))
        assertNull(NavState.decode("{not json"))
        assertNull(NavState.decode("""{"section":"Servers","stacks":{"Servers":[{"type":"no-such-screen"}]}}"""))
    }
}
