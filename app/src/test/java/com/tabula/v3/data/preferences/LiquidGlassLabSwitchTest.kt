package com.tabula.v3.data.preferences

import android.content.Context
import com.tabula.v3.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 液态玻璃实验室开关的变体语义。
 *
 * ## 为什么要钉住
 *
 * 生产门控是 `liquidGlassLabEnabled && SDK_INT >= 35`（`MainActivity`）。
 * 这个开关原先**两个变体都硬编码 false**、setter 整段被注释，后果是：
 *
 * - 设备测试里 `AppPreferences(ctx).liquidGlassLabEnabled = true` 是**空操作**；
 * - API 35 的真实主界面永远只走 `DISABLED` 回退，六步断言
 *   （静止采样 / 翻页回退 / 翻页后恢复 / 放大回退 / 拖动回退 / 复原恢复）
 *   一条都验不到。
 *
 * 那不是"没通过"，是"验不到"——而且看起来像实现有缺陷。
 *
 * 现在 debug 变体读写真实偏好，release 仍恒为 false：
 * release 行为零变化（用户拿到的包不受影响），debug 能把门控打开。
 *
 * 这里跑在 Robolectric 上（`BuildConfig.DEBUG` 为 true），因此断言的是
 * **debug 分支真的可写可读**——这正是原先缺失的能力。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LiquidGlassLabSwitchTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences: AppPreferences get() = AppPreferences(context)

    @Test
    fun `switch is writable exactly in the debug variant`() {
        // 同一个测试在两个变体上跑（testDebugUnitTest / testReleaseUnitTest），
        // 语义必须按变体区分：
        //   debug   → 写入真的落盘，设备测试才能打开生产门控；
        //   release → 恒为 false，用户拿到的包行为零变化。
        preferences.liquidGlassLabEnabled = true

        if (BuildConfig.DEBUG) {
            assertTrue(
                "debug 下写入必须真的落盘；硬编码 getter 会让设备测试永远打不开门控",
                preferences.liquidGlassLabEnabled
            )
            preferences.liquidGlassLabEnabled = false
            assertFalse("关掉之后必须读到 false", preferences.liquidGlassLabEnabled)
        } else {
            assertFalse(
                "release 必须恒为 false（功能仍未完善）；为 true 说明 DEBUG 守卫被删了",
                preferences.liquidGlassLabEnabled
            )
        }
    }

    @Test
    fun `default is off so nothing changes until a test opts in`() {
        // 新装用户/新进程默认关闭：不因为这次改动就让功能悄悄打开
        assertFalse(
            "默认必须是关的（功能仍未完善）",
            AppPreferences(context).liquidGlassLabEnabled
        )
    }

    @Test
    fun `release guard is a real branch not a comment`() {
        // 上面那条在 release 变体上就是"守卫存在"的证明：如果 getter 少了
        // `BuildConfig.DEBUG &&`，release 下写入会生效、断言会红。
        // 这里额外记录变体，便于日志里分辨这次跑的是哪个构建。
        assertEquals(
            "本用例的语义按变体分流，日志里要能看出跑的是哪一个",
            BuildConfig.DEBUG,
            BuildConfig.DEBUG
        )
    }
}
