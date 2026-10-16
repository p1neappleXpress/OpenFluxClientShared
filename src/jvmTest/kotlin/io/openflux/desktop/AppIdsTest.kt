package io.openflux.desktop

import io.openflux.desktop.data.AppIds
import io.openflux.desktop.model.OpenFluxBot
import kotlin.test.Test
import kotlin.test.assertEquals

class AppIdsTest {
    @Test
    fun anIdTheServiceIssuedWithTheOldPrefixStillCounts() {
        assertEquals("ofk_TVWKnezaWBmmemdTTQLyWdILskWO58OYgH4jXL9sMCQ".replace("TVWK", "AAAA"), AppIds.valid("ofk_AAAAnezaWBmmemdTTQLyWdILskWO58OYgH4jXL9sMCQ"))
        assertEquals("", AppIds.valid("ofx_abcdefgh12345678"))
    }

    @Test
    fun onlyAnAppIdOfTheRightShapeCounts() {
        assertEquals("ofa_abcdefgh12345678", AppIds.valid("  ofa_abcdefgh12345678\n"))
        for (bad in listOf(null, "", "ofa_", "ofa_short", "abc_abcdefgh12345678", "ofa_abcdefgh 12345678", "ofa_" + "a".repeat(129))) {
            assertEquals("", AppIds.valid(bad), bad.toString())
        }
    }

    @Test
    fun theApiAddressMayBeMovedOnlyToThisMachine() {
        assertEquals(OpenFluxBot.API, AppIds.pickBase(null))
        assertEquals(OpenFluxBot.API, AppIds.pickBase("https://evil.example"))
        assertEquals(OpenFluxBot.API, AppIds.pickBase("http://evil.example:8080"))
        assertEquals(OpenFluxBot.API, AppIds.pickBase("http://127.0.0.1.evil.example:8080"))
        assertEquals("http://127.0.0.1:8099", AppIds.pickBase("http://127.0.0.1:8099"))
        assertEquals("http://localhost:8099", AppIds.pickBase("http://localhost:8099"))
    }

    @Test
    fun theIssuerMovesOnlyToThisMachine() {
        assertEquals("https://id.openflux.dev/realms/openflux", AppIds.pickIssuer(null))
        assertEquals("https://id.openflux.dev/realms/openflux", AppIds.pickIssuer("https://evil.example/realms/openflux"))
        assertEquals("https://id.openflux.dev/realms/openflux", AppIds.pickIssuer("http://127.0.0.1.evil.example/realms/openflux"))
        assertEquals("http://127.0.0.1:8099/realms/openflux", AppIds.pickIssuer("http://127.0.0.1:8099/realms/openflux/"))
    }

    @Test
    fun theDesktopClientDefaultsToOurs() = assertEquals("openflux-desktop", AppIds.desktopClientId())
}
