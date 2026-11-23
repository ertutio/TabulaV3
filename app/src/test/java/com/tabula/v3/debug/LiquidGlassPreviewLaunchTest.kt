package com.tabula.v3.debug

import android.content.Intent
import android.os.Looper
import android.view.View
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 启动冒烟：预览页必须能组合 + 布局 + 画一帧，不抛异常。
 *
 * 存在的理由：模拟器取证多次出现"首帧画出来、随后 app 崩掉"或"am start 直接返回
 * 255"，而探针当时只看到"没有 AUDIT 标记"，把崩溃误读成实现缺陷。本地这一条
 * 把"启动就崩"先拦下来，不必等一轮 CI 往返（一次往返约 13 分钟）。
 *
 * **它抓到过两个真实缺陷**：
 * 1. `PhysicalLiquidGlassBoxImpl` 的 Layout 里对同一个 `Measurable` 测量两次，
 *    Compose 抛 `measure() may not be called multiple times`——布局阶段抛异常，
 *    首帧已画出去，于是"闪一下然后 keeps stopping"；
 * 2. `backdropShapeMetrics` 在 `sizePx` 为 0 时返回半径 0，随后 `CircleShape`
 *    路径上 `RoundedCornerShape(0f)` 与 `clip` 组合出 0 尺寸裁剪，导致
 *    `am start` 直接失败。
 *
 * 只保留**一个**测试方法：Robolectric 的 Activity 启动 + Compose 布局单次约
 * 200 秒（首次要加载 android-all），拆成多个方法会让 CI 多花十几分钟。
 *
 * Robolectric 里 `RenderEffect` / `RuntimeShader` 是 shadow 的，因此这里证明的是
 * **组合与布局阶段**不崩（子项下标、状态写入、约束、单次测量）；着色器与 GPU
 * 行为仍由模拟器取证覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiquidGlassPreviewLaunchTest {

    private fun launch(vararg extras: Pair<String, Any>): LiquidGlassPreviewActivity {
        val intent = Intent(
            RuntimeEnvironment.getApplication(),
            LiquidGlassPreviewActivity::class.java
        ).apply {
            extras.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                    is String -> putExtra(key, value)
                }
            }
        }
        val controller = Robolectric.buildActivity(
            LiquidGlassPreviewActivity::class.java,
            intent
        ).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        measureAndLayout(activity)
        assertFalse("预览页在组合/布局阶段就崩了（extras=$extras）", activity.isFinishing)
        return activity
    }

    private fun measureAndLayout(activity: LiquidGlassPreviewActivity) {
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, 1080, 2400)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `every preview entry survives compose layout and a second measure pass`() {
        // 1) 默认入口：折射开、圆角矩形、宿主 100%
        val first = launch()

        // 2) 第二次 measure/layout：尺寸变化会重新布局，重复测量就在这里暴露。
        measureAndLayout(first)

        // 3) 探针实际会跑的每一组参数都要覆盖：任何一组崩了，模拟器取证就整轮作废。
        //    顺序与 tools/run-liquid-glass-preview-probe.py 的调用一致。
        launch(
            LiquidGlassPreviewActivity.EXTRA_REFRACTION to false,
            LiquidGlassPreviewActivity.EXTRA_SHAPE to "CutCorner"
        )
        launch(LiquidGlassPreviewActivity.EXTRA_DIAGNOSTIC_BLUR to true)
        launch(
            LiquidGlassPreviewActivity.EXTRA_BACKDROP to "SizeDependent",
            LiquidGlassPreviewActivity.EXTRA_HOST_SCALE_PERCENT to 60,
            LiquidGlassPreviewActivity.EXTRA_SHAPE to "Circle"
        )
        launch(
            LiquidGlassPreviewActivity.EXTRA_BACKDROP to "Grid",
            LiquidGlassPreviewActivity.EXTRA_REFRACTION_AMOUNT_DP to 2
        )
        launch(
            LiquidGlassPreviewActivity.EXTRA_BACKDROP to "Grid",
            LiquidGlassPreviewActivity.EXTRA_REFRACTION_AMOUNT_DP to 12
        )
        launch(
            LiquidGlassPreviewActivity.EXTRA_PHOTO to true,
            LiquidGlassPreviewActivity.EXTRA_OFFSET_Y to 180,
            LiquidGlassPreviewActivity.EXTRA_BACKDROP to "Photo"
        )
        launch(
            LiquidGlassPreviewActivity.EXTRA_GLASS_HEIGHT_DP to 280,
            LiquidGlassPreviewActivity.EXTRA_OFFSET_Y to 120
        )
    }
}
