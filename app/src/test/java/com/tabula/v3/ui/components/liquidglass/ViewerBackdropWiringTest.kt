package com.tabula.v3.ui.components.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 查看器采样接线的**共用部分**单测（PR B）。
 *
 * 两个查看器（回收站 / 普通）现在共用 `ViewerBackdropWiring`，因此这里钉住的每一条
 * 都是**两条路径同时**要满足的约定。最关键的一条是门控顺序：
 *
 * 早期实现先无条件 `rememberBackdropPhoto(...)`、再用 `shouldAttachPhotoBackdrop`
 * 决定要不要接背景——功能关着也白发了 Coil 请求（解码 + 内存）。这条顺序写进
 * `ViewerBackdropWiring.shouldLoadBackdropPhoto` 的语义里：**它必须在加载之前被调用**，
 * 返回 false 时调用方不能把 data 交给加载器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ViewerBackdropWiringTest {

    // ==================== 1. 门控：开关关闭 / 系统过低 一律不加载 ====================

    @Test
    fun disabledSwitchNeverLoads() {
        assertFalse(
            "开关关闭时不得为采样加载照片",
            ViewerBackdropWiring.shouldLoadBackdropPhoto(
                liquidGlassEnabled = false,
                sdkInt = 36
            )
        )
    }

    @Test
    fun oldSdkNeverLoads() {
        listOf(28, 30, 33, 34).forEach { sdk ->
            assertFalse(
                "SDK $sdk 不支持背景采样，不得加载",
                ViewerBackdropWiring.shouldLoadBackdropPhoto(
                    liquidGlassEnabled = true,
                    sdkInt = sdk
                )
            )
        }
    }

    @Test
    fun enabledSwitchOnSupportedSdkLoads() {
        listOf(35, 36).forEach { sdk ->
            assertTrue(
                "SDK $sdk + 开关打开才加载",
                ViewerBackdropWiring.shouldLoadBackdropPhoto(
                    liquidGlassEnabled = true,
                    sdkInt = sdk
                )
            )
        }
    }

    /**
     * 门控常量必须与主题层一致：主题用 `SDK_INT >= 35` 决定要不要接玻璃，
     * 这里若写成别的数字，就会出现「主题已经是玻璃、但采样永不加载」的静默错位。
     */
    @Test
    fun minSdkMatchesThemeGate() {
        assertEquals("采样门控的 SDK 下限必须与主题层一致", 35, ViewerBackdropWiring.MIN_SDK_FOR_BACKDROP)
    }

    // ==================== 2. 采样同步：何时可采、何时必须回退 ====================

    @Test
    fun settledIdentityTransformCanSample() {
        val sync = ViewerBackdropSync.Settled
        assertTrue("未翻页、未缩放、未拖动 → 可采", sync.canSample)
        assertNull("可采时不该有回退原因", sync.fallbackReason)
    }

    @Test
    fun scrollingFallsBack() {
        val sync = ViewerBackdropSync(pagerSettled = false, scale = 1f, panX = 0f, panY = 0f)
        assertFalse("翻页中不可采", sync.canSample)
        assertEquals(
            "翻页中的回退原因必须是 VIEWER_SCROLLING",
            BackdropFallbackReason.VIEWER_SCROLLING,
            sync.fallbackReason
        )
    }

    @Test
    fun zoomedFallsBack() {
        // 明显超出容差的缩放必须回退
        listOf(1.2f, 1.5f, 3f, 8f).forEach { scale ->
            val sync = ViewerBackdropSync(pagerSettled = true, scale = scale, panX = 0f, panY = 0f)
            assertFalse("放大到 $scale 倍时不可采", sync.canSample)
            assertEquals(
                "放大后的回退原因必须是 VIEWER_TRANSFORMED",
                BackdropFallbackReason.VIEWER_TRANSFORMED,
                sync.fallbackReason
            )
        }
    }

    @Test
    fun pannedFallsBack() {
        // 超过平移容差（0.5px）才算「拖动过」：容差内的亚像素抖动不该打断采样，
        // 否则手指轻轻一碰按钮就闪回实色。这里两侧都钉住。
        val beyondEpsilon = listOf(20f to 0f, -20f to 0f, 0f to 12f, 0f to -12f)
        beyondEpsilon.forEach { (dx, dy) ->
            val sync = ViewerBackdropSync(pagerSettled = true, scale = 1f, panX = dx, panY = dy)
            assertFalse("拖动超过容差 (dx=$dx, dy=$dy) 时不可采", sync.canSample)
            assertEquals(
                "拖动后的回退原因必须是 VIEWER_TRANSFORMED",
                BackdropFallbackReason.VIEWER_TRANSFORMED,
                sync.fallbackReason
            )
        }
        val withinEpsilon = listOf(0.5f to 0f, 0f to 0.5f)
        withinEpsilon.forEach { (dx, dy) ->
            val sync = ViewerBackdropSync(pagerSettled = true, scale = 1f, panX = dx, panY = dy)
            assertTrue(
                "容差内 (dx=$dx, dy=$dy) 应继续采样（避免手指抖动打断）",
                sync.canSample
            )
        }
    }

    @Test
    fun backToIdentityTransformResumesSampling() {
        // 场景：放大 → 回原比例 → 应恢复可采（PR B 验收第 6 条）
        val zoomed = ViewerBackdropSync(pagerSettled = true, scale = 3f, panX = 0f, panY = 0f)
        assertFalse(zoomed.canSample)
        val restored = ViewerBackdropSync(pagerSettled = true, scale = 1f, panX = 0f, panY = 0f)
        assertTrue("恢复原比例与原位置后必须重新可采", restored.canSample)
        assertNull(restored.fallbackReason)
    }

    @Test
    fun scaleWithinEpsilonKeepsSampling() {
        // 缩放容差 0.01 是刻意的：手指按在按钮上时的亚像素缩放不该让画面闪回实色。
        // 容差内继续采，超出即回退——两侧都钉住，避免将来把容差改成 0 或改大。
        assertTrue(
            "容差内的缩放（1.005）应继续采样",
            ViewerBackdropSync(pagerSettled = true, scale = 1.005f, panX = 0f, panY = 0f).canSample
        )
        assertTrue(
            "容差下边界（0.995）也应继续采样",
            ViewerBackdropSync(pagerSettled = true, scale = 0.995f, panX = 0f, panY = 0f).canSample
        )
        assertFalse(
            "刚好超出容差（1.02）必须回退",
            ViewerBackdropSync(pagerSettled = true, scale = 1.02f, panX = 0f, panY = 0f).canSample
        )
    }

    /** 容差本身是产品决策，钉住数值避免被无意改动。 */
    @Test
    fun epsilonValuesArePinned() {
        assertEquals("缩放容差", 0.01f, SCALE_EPSILON, 0.0001f)
        assertEquals("平移容差（px）", 0.5f, PAN_EPSILON_PX, 0.0001f)
    }

    // ==================== 3. 遮罩高度可计算且非负 ====================

    @Test
    fun scrimHeightIsNonNegative() {
        // 纯函数版本：状态栏 0 时仍应是非负（不同状态下不能出现负高度）
        val h = topScrimHeightPx(
            statusBarInsetPx = 0f,
            buttonHeightPx = 0f,
            verticalPaddingPx = 0f
        )
        assertEquals(0f, h, 0.001f)
    }

    @Test
    fun scrimHeightGrowsWithStatusBar() {
        val without = topScrimHeightPx(0f, 100f, 10f)
        val with = topScrimHeightPx(80f, 100f, 10f)
        assertTrue("状态栏 inset 变大时遮罩也要变高：$without → $with", with > without)
        assertEquals(80f, with - without, 0.001f)
    }
}
