package com.tabula.v3.ui.components.liquidglass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.tabula.v3.ui.components.PhysicalLiquidGlassConfig
import com.tabula.v3.ui.theme.liquidGlassColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassThemeContrastTest {
    @Test
    fun lightSettingsTextHasSufficientContrast() {
        assertReadableSettings(darkTheme = false)
    }

    @Test
    fun darkSettingsTextHasSufficientContrast() {
        assertReadableSettings(darkTheme = true)
    }

    @Test
    fun compactGlassKeepsItsShapedEdgeWithoutStraightHighlightStrips() {
        val button = PhysicalLiquidGlassConfig.Button
        assertEquals(0f, button.primarySpecularIntensity, 0f)
        assertEquals(0f, button.topRimLightAlpha, 0f)
        assertEquals(0f, button.topInnerDarkAlpha, 0f)
        assertEquals(0.dp, button.bottomSeparatorHeight)
        assertEquals(0f, button.bottomInnerHighlightAlpha, 0f)
        assertTrue(button.borderAlpha > 0f)
        assertTrue(button.refractionEdgePeak > 0f)
    }

    private fun assertReadableSettings(darkTheme: Boolean) {
        val colors = liquidGlassColorScheme(darkTheme)
        assertContrast(colors.onBackground, colors.background)
        assertContrast(colors.onSurfaceVariant, colors.background)
        for (surface in listOf(colors.surface, colors.surfaceVariant)) {
            assertEquals(1f, surface.alpha, 0f)
            assertContrast(colors.onSurface, surface)
            assertContrast(colors.onSurfaceVariant, surface)
        }
        assertTrue(colors.surface.luminance() > colors.background.luminance())
        assertEquals(darkTheme, colors.background.luminance() < 0.5f)
    }

    private fun assertContrast(foreground: Color, background: Color) {
        val first = foreground.luminance()
        val second = background.luminance()
        val ratio = (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
        assertTrue("Contrast $ratio between $foreground and $background", ratio >= 4.5f)
    }
}
