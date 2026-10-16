package io.openflux.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.openflux.app.theme.Colors
import io.openflux.app.theme.DarkColors
import io.openflux.app.theme.LightColors
import kotlin.test.Test
import kotlin.test.assertTrue

class TokensTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(c: Colors, name: String) {
        assertTrue(contrast(c.text, c.background) >= 4.5, "$name: text")
        assertTrue(contrast(c.text, c.surface) >= 4.5, "$name: text on surface")
        assertTrue(contrast(c.textSecondary, c.surface) >= 4.5, "$name: secondary")
        assertTrue(contrast(c.accent, c.background) >= 3.0, "$name: accent")
        assertTrue(contrast(c.onAccent, c.accent) >= 4.5, "$name: on accent")
        assertTrue(contrast(c.danger, c.surface) >= 3.0, "$name: danger")
    }

    @Test fun darkIsReadable() = check(DarkColors, "dark")
    @Test fun lightIsReadable() = check(LightColors, "light")
}
