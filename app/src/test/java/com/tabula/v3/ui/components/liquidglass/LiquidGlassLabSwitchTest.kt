package com.tabula.v3.ui.components.liquidglass

import android.content.Context
import com.tabula.v3.data.preferences.AppPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LiquidGlassLabSwitchTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `defaults to off`() {
        assertFalse(AppPreferences(context).liquidGlassLabEnabled)
    }

    @Test
    fun `enabling is visible to a new preferences instance`() {
        AppPreferences(context).liquidGlassLabEnabled = true

        assertTrue(AppPreferences(context).liquidGlassLabEnabled)
    }

    @Test
    fun `disabling is visible to a new preferences instance`() {
        val preferences = AppPreferences(context)
        preferences.liquidGlassLabEnabled = true
        assertTrue(preferences.liquidGlassLabEnabled)

        preferences.liquidGlassLabEnabled = false

        assertFalse(AppPreferences(context).liquidGlassLabEnabled)
    }
}
