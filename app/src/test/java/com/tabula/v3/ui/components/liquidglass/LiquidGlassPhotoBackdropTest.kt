package com.tabula.v3.ui.components.liquidglass

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实照片作为背景来源时的几何与门控。
 *
 * 生产页面（回收站全屏查看器）的照片用 `ContentScale.Fit` 绘制，采样层必须用
 * **同一套** Fit 计算与同一张 Bitmap，否则玻璃边缘采到的是"另一张被拉伸的图"。
 */
class LiquidGlassPhotoBackdropTest {

    @Test
    fun `fit centers a wide photo with bars above and below`() {
        // 4000x1000 的宽图放进 1000x2000 的容器：宽度吃满，上下留黑边
        val rect = fitInsideRect(4000, 1000, boxWidth = 1000f, boxHeight = 2000f)!!
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(1000f, rect.width, 0.001f)
        assertEquals(250f, rect.height, 0.001f)
        // 垂直居中
        assertEquals((2000f - 250f) / 2f, rect.top, 0.001f)
    }

    @Test
    fun `fit centers a tall photo with bars left and right`() {
        val rect = fitInsideRect(1000, 4000, boxWidth = 1000f, boxHeight = 2000f)!!
        assertEquals(500f, rect.width, 0.001f)
        assertEquals(2000f, rect.height, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(250f, rect.left, 0.001f)
    }

    @Test
    fun `fit preserves the aspect ratio exactly`() {
        val rect = fitInsideRect(1920, 1080, boxWidth = 1080f, boxHeight = 2400f)!!
        assertEquals(
            1920f / 1080f,
            rect.width / rect.height,
            0.001f
        )
    }

    @Test
    fun `fit is deterministic for the same inputs so host and layer agree`() {
        val a = fitInsideRect(3000, 2000, 1080f, 1800f)
        val b = fitInsideRect(3000, 2000, 1080f, 1800f)
        assertEquals(a, b)
    }

    @Test
    fun `fit rejects degenerate inputs instead of drawing a zero rect`() {
        assertNull(fitInsideRect(0, 1000, 1000f, 1000f))
        assertNull(fitInsideRect(1000, 0, 1000f, 1000f))
        assertNull(fitInsideRect(1000, 1000, 0f, 1000f))
        assertNull(fitInsideRect(1000, 1000, 1000f, 0f))
    }

    @Test
    fun `fit result stays inside the box`() {
        listOf(
            Triple(4000, 3000, Size(1080f, 2400f)),
            Triple(1080, 2400, Size(1080f, 2400f)),
            Triple(100, 5000, Size(1080f, 1920f))
        ).forEach { (w, h, box) ->
            val rect = fitInsideRect(w, h, box.width, box.height)!!
            assertTrue("左边界越界", rect.left >= -0.01f)
            assertTrue("上边界越界", rect.top >= -0.01f)
            assertTrue("右边界越界", rect.right <= box.width + 0.01f)
            assertTrue("下边界越界", rect.bottom <= box.height + 0.01f)
        }
    }

    @Test
    fun `photo backdrop requires lab switch api gate and a loaded bitmap`() {
        assertTrue(shouldAttachPhotoBackdrop(true, 35, true))
        assertTrue(shouldAttachPhotoBackdrop(true, 33, true))
        assertFalse("实验室开关关闭时不接背景", shouldAttachPhotoBackdrop(false, 35, true))
        assertFalse("API 32 不接背景", shouldAttachPhotoBackdrop(true, 32, true))
        assertFalse("照片没加载出来时不接背景", shouldAttachPhotoBackdrop(true, 35, false))
    }

    @Test
    fun `top scrim height matches the values drawn on screen`() {
        // 状态栏 100px + 按钮 132px（48dp@2.75x）+ 上下 22px（8dp@2.75x）
        val height = topScrimHeightPx(
            statusBarInsetPx = 100f,
            buttonHeightPx = 132f,
            verticalPaddingPx = 22f
        )
        assertEquals(100f + 132f + 44f, height, 0.001f)
    }

    @Test
    fun `top scrim height never goes negative`() {
        assertEquals(
            0f,
            topScrimHeightPx(statusBarInsetPx = -50f, buttonHeightPx = 0f, verticalPaddingPx = 0f),
            0.001f
        )
    }

    @Test
    fun `top button samples when the status bar inset leaves room for the sampling margin`() {
        // 真实生产场景：48dp 按钮在状态栏之下（inset 约 100px），外扩 28px 仍然落在屏幕内
        val source = androidx.compose.ui.unit.IntRect(0, 0, 1080, 2400)
        val padding = backdropPaddingPx(
            LiquidGlassBackdropSpec.Default.forCompactControl().resolve(2.75f)
        )
        val button = androidx.compose.ui.unit.IntRect(
            left = 22,
            top = 100 + 22,
            right = 22 + 132,
            bottom = 100 + 22 + 132
        )
        val plan = planBackdropSample(35, true, true, button, source, padding)
        assertTrue("顶部按钮必须能采样", plan.useSampledBackdrop)
        // 图层 = 按钮 + 2*外扩；玻璃在图层内固定在 (pad,pad)
        assertEquals(132 + padding * 2, plan.layerWidthPx)
        assertEquals(132 + padding * 2, plan.layerHeightPx)
    }

    @Test
    fun `top button falls back when there is no inset and the margin would leave the source`() {
        // 没有状态栏 inset 的极端情形（例如全屏手势隐藏状态栏）：外扩会越过屏幕上边缘，
        // 此时宁可回退原有材质，也不去采屏幕外/玻璃自身的内容。
        val source = androidx.compose.ui.unit.IntRect(0, 0, 1080, 2400)
        val padding = backdropPaddingPx(
            LiquidGlassBackdropSpec.Default.forCompactControl().resolve(2.75f)
        )
        val rejected = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = androidx.compose.ui.unit.IntRect(16, 0, 148, 132),
            sourceBoundsInWindow = source,
            paddingPx = padding
        )
        assertEquals(BackdropFallbackReason.OUT_OF_SOURCE_BOUNDS, rejected.reason)
    }

    @Test
    fun `photo background composes with the sampling plan without changing geometry`() {
        // 背景换成照片不影响采样几何：方案只由坐标与外观决定
        val source = androidx.compose.ui.unit.IntRect(0, 0, 1080, 2400)
        val glass = androidx.compose.ui.unit.IntRect(63, 105, 1017, 525)
        val padding = backdropPaddingPx(LiquidGlassBackdropSpec.Default.resolve(2.75f))
        val plan = planBackdropSample(35, true, true, glass, source, padding)
        assertTrue(plan.useSampledBackdrop)
        assertEquals(954 + padding * 2, plan.layerWidthPx)
        // 对齐仍然只取决于坐标差
        assertEquals(63 - padding, -plan.sourceTranslateX.toInt())
        assertEquals(105 - padding, -plan.sourceTranslateY.toInt())
    }
}
