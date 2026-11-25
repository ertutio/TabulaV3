package com.tabula.v3.ui.components.liquidglass

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实查看器（回收站全屏查看器）里的背景采样同步契约。
 *
 * 采样层是**另一份**静止的 `ContentScale.Fit` Bitmap，而查看器的照片经
 * `HorizontalPager` 平移、`graphicsLayer{scale/translation}` 缩放拖动。
 * 两者只在"没翻页、没放大、没拖动"时才是同一幅画面；其余状态必须**明确回退**，
 * 不允许继续显示错位采样。
 */
class LiquidGlassViewerBackdropSyncTest {

    private val source = IntRect(0, 0, 1080, 2400)
    private val button = IntRect(22, 122, 22 + 132, 122 + 132)
    private val padding = 11

    private fun plan(sync: ViewerBackdropSync?) = planBackdropSample(
        sdkInt = 35,
        hasBackdropSource = true,
        isRoundedRectShape = true,
        glassBoundsInWindow = button,
        sourceBoundsInWindow = source,
        paddingPx = padding,
        viewerSync = sync
    )

    // ===== 1. 静止状态：正常采样 =====

    @Test
    fun `settled viewer samples normally`() {
        val p = plan(ViewerBackdropSync.Settled)
        assertTrue(p.useSampledBackdrop)
        assertEquals(BackdropFallbackReason.NONE, p.reason)
    }

    @Test
    fun `null sync means the caller does not participate and sampling proceeds`() {
        // 面板、调试预览等静态场景传 null：行为与加这个参数之前完全一致
        val p = plan(null)
        assertTrue(p.useSampledBackdrop)
    }

    // ===== 2. 翻页中：必须回退 =====

    @Test
    fun `pager scrolling falls back with VIEWER_SCROLLING`() {
        val p = plan(ViewerBackdropSync.Settled.copy(pagerSettled = false))
        assertFalse("翻页中不能继续采样", p.useSampledBackdrop)
        assertEquals(BackdropFallbackReason.VIEWER_SCROLLING, p.reason)
    }

    @Test
    fun `pager mid scroll with residual offset also falls back`() {
        // isScrollInProgress 可能已经变 false，但仍有未归零的偏移：
        // 那一刻屏幕上是"两页之间"，采样层画的是某一整页 → 必然错位
        val sync = ViewerBackdropSync(
            pagerSettled = false,
            scale = 1f,
            panX = 0f,
            panY = 0f
        )
        assertFalse(sync.canSample)
        assertEquals(BackdropFallbackReason.VIEWER_SCROLLING, plan(sync).reason)
    }

    @Test
    fun `pager settled resumes sampling`() {
        // 翻页完成后必须恢复：不能因为"曾经滚动过"就永久回退
        val afterSwipe = ViewerBackdropSync.Settled
        assertTrue(plan(afterSwipe).useSampledBackdrop)
    }

    // ===== 3. 放大：必须回退 =====

    @Test
    fun `zoomed in falls back with VIEWER_TRANSFORMED`() {
        val p = plan(ViewerBackdropSync.Settled.copy(scale = 2.5f))
        assertFalse("放大后不能继续采样", p.useSampledBackdrop)
        assertEquals(BackdropFallbackReason.VIEWER_TRANSFORMED, p.reason)
    }

    @Test
    fun `zoom below one also falls back`() {
        // 捏合缩小同样是变换；采样层没有对应变换
        val p = plan(ViewerBackdropSync.Settled.copy(scale = 0.6f))
        assertEquals(BackdropFallbackReason.VIEWER_TRANSFORMED, p.reason)
    }

    @Test
    fun `scale within the finger jitter tolerance still samples`() {
        // 手指刚碰到屏幕时 scale 会在 1.0 附近浮动；若要求严格相等，
        // 按钮会闪一下回退。容差内视为未缩放。
        listOf(1f, 1.005f, 0.995f, 1.009f, 0.991f).forEach { scale ->
            assertTrue("scale=$scale 应视为未缩放", isIdentityTransform(scale, 0f, 0f))
            assertTrue(plan(ViewerBackdropSync.Settled.copy(scale = scale)).useSampledBackdrop)
        }
    }

    @Test
    fun `scale beyond the tolerance falls back`() {
        listOf(1.02f, 0.98f, 1.5f, 8f).forEach { scale ->
            assertFalse("scale=$scale 必须判为已缩放", isIdentityTransform(scale, 0f, 0f))
            assertEquals(
                BackdropFallbackReason.VIEWER_TRANSFORMED,
                plan(ViewerBackdropSync.Settled.copy(scale = scale)).reason
            )
        }
    }

    // ===== 4. 拖动：必须回退 =====

    @Test
    fun `panning falls back with VIEWER_TRANSFORMED`() {
        val p = plan(ViewerBackdropSync.Settled.copy(panX = 24f, panY = 0f))
        assertEquals(BackdropFallbackReason.VIEWER_TRANSFORMED, p.reason)
    }

    @Test
    fun `vertical pan also falls back`() {
        val p = plan(ViewerBackdropSync.Settled.copy(panX = 0f, panY = -13f))
        assertEquals(BackdropFallbackReason.VIEWER_TRANSFORMED, p.reason)
    }

    @Test
    fun `sub pixel pan still samples`() {
        // 亚像素位移在 48dp 按钮上不可见
        assertTrue(isIdentityTransform(1f, 0.4f, -0.4f))
        assertTrue(plan(ViewerBackdropSync.Settled.copy(panX = 0.4f)).useSampledBackdrop)
    }

    @Test
    fun `pan beyond half a pixel falls back`() {
        // 超过半个像素就是"错位采样"的起点，不做"看起来差不多"的宽容
        assertFalse(isIdentityTransform(1f, 0.6f, 0f))
        assertEquals(
            BackdropFallbackReason.VIEWER_TRANSFORMED,
            plan(ViewerBackdropSync.Settled.copy(panX = 0.6f)).reason
        )
    }

    @Test
    fun `combined zoom and pan is a single fallback`() {
        val p = plan(ViewerBackdropSync.Settled.copy(scale = 2f, panX = 30f, panY = 30f))
        assertFalse(p.useSampledBackdrop)
        assertEquals(BackdropFallbackReason.VIEWER_TRANSFORMED, p.reason)
    }

    // ===== 5. 优先级与边界 =====

    @Test
    fun `scrolling takes precedence over transformed in the reason`() {
        // 翻页时手指也可能同时按着（scale 略变）；reason 要报"翻页中"，
        // 因为那是最主要的不可同步原因
        val sync = ViewerBackdropSync(
            pagerSettled = false,
            scale = 2f,
            panX = 10f,
            panY = 10f
        )
        assertEquals(BackdropFallbackReason.VIEWER_SCROLLING, sync.fallbackReason)
    }

    @Test
    fun `fallback plan has zero layer size so nothing is drawn`() {
        val p = plan(ViewerBackdropSync.Settled.copy(scale = 3f))
        assertEquals(0, p.layerWidthPx)
        assertEquals(0, p.layerHeightPx)
        assertEquals(0, p.paddingPx)
    }

    @Test
    fun `viewer fallback is distinct from disabled and out of bounds`() {
        val reasons = setOf(
            BackdropFallbackReason.DISABLED,
            BackdropFallbackReason.OUT_OF_SOURCE_BOUNDS,
            BackdropFallbackReason.VIEWER_SCROLLING,
            BackdropFallbackReason.VIEWER_TRANSFORMED
        )
        assertEquals("四种原因必须可区分，便于审计", 4, reasons.size)
    }

    @Test
    fun `settled constant is identity`() {
        assertTrue(ViewerBackdropSync.Settled.pagerSettled)
        assertEquals(1f, ViewerBackdropSync.Settled.scale, 0f)
        assertEquals(0f, ViewerBackdropSync.Settled.panX, 0f)
        assertEquals(0f, ViewerBackdropSync.Settled.panY, 0f)
        assertNull(ViewerBackdropSync.Settled.fallbackReason)
        assertTrue(ViewerBackdropSync.Settled.canSample)
    }

    @Test
    fun `canSample and fallbackReason are always consistent`() {
        val cases = listOf(
            ViewerBackdropSync(true, 1f, 0f, 0f),
            ViewerBackdropSync(false, 1f, 0f, 0f),
            ViewerBackdropSync(true, 2f, 0f, 0f),
            ViewerBackdropSync(true, 1f, 5f, 0f),
            ViewerBackdropSync(false, 3f, 9f, 9f)
        )
        cases.forEach { sync ->
            assertEquals(sync.canSample, sync.fallbackReason == null)
        }
    }
}

/**
 * 采样照片的加载门控必须**先于**请求。
 *
 * 早期实现先 `rememberBackdropPhoto(...)` 无条件加载，再用
 * `shouldAttachPhotoBackdrop` 决定要不要接背景——功能关闭时仍然白白发了一次
 * Coil 请求。这里把"要不要发请求"和"要不要接背景"分成两层锁住。
 */
class LiquidGlassBackdropLoadGateTest {

    @Test
    fun `lab switch off means no photo request`() {
        assertFalse(shouldLoadBackdropPhoto(liquidGlassEnabled = false, sdkInt = 35))
    }

    @Test
    fun `sdk below threshold means no photo request`() {
        assertFalse(shouldLoadBackdropPhoto(liquidGlassEnabled = true, sdkInt = 32))
        assertFalse(shouldLoadBackdropPhoto(liquidGlassEnabled = true, sdkInt = 31))
    }

    @Test
    fun `enabled and supported means the photo is requested`() {
        assertTrue(shouldLoadBackdropPhoto(liquidGlassEnabled = true, sdkInt = 33))
        assertTrue(shouldLoadBackdropPhoto(liquidGlassEnabled = true, sdkInt = 35))
        assertTrue(shouldLoadBackdropPhoto(liquidGlassEnabled = true, sdkInt = 36))
    }

    @Test
    fun `load gate is implied by the attach gate`() {
        // 不变量：能接背景 ⇒ 一定加载过照片。反方向不成立（加载了但解码失败）。
        listOf(false to 31, false to 35, true to 32, true to 33, true to 35).forEach { (on, sdk) ->
            val load = shouldLoadBackdropPhoto(on, sdk)
            val attach = shouldAttachPhotoBackdrop(on, sdk, hasPhoto = true)
            if (attach) {
                assertTrue("attach=true 但 load=false（on=$on sdk=$sdk）", load)
            }
        }
    }

    @Test
    fun `attach gate additionally requires a decoded photo`() {
        assertTrue(shouldAttachPhotoBackdrop(true, 35, hasPhoto = true))
        assertFalse(shouldAttachPhotoBackdrop(true, 35, hasPhoto = false))
        // 门开着但照片没解码出来：仍然不接背景（加载失败的正常回退）
        assertTrue(shouldLoadBackdropPhoto(true, 35))
    }

    @Test
    fun `both gates agree on the closed cases`() {
        listOf(false to 35, true to 31, true to 32).forEach { (on, sdk) ->
            assertFalse("on=$on sdk=$sdk 应完全不参与", shouldLoadBackdropPhoto(on, sdk))
            assertFalse("on=$on sdk=$sdk 应完全不接背景", shouldAttachPhotoBackdrop(on, sdk, true))
        }
    }
}
