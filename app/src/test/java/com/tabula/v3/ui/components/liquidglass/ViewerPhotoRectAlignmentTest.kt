package com.tabula.v3.ui.components.liquidglass

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **采样坐标必须按照片真实矩形**，而不是整个宿主。
 *
 * ## 这一组测试守的是什么
 *
 * 普通查看器把照片缩进容器的 95%×90% 并居中：1080×2400 的屏上，一张接近屏幕
 * 比例的竖图实际约 1026×2160，四周（尤其上下）还有黑边。采样层若仍按
 * "整个宿主 1080×2400 做 Fit"来摆照片，照片会被放大到 1080×2400 并贴满，
 * 于是按钮下方本该是黑边的位置采到了照片——玻璃边缘的颜色与屏幕上真实存在的
 * 像素对不上。
 *
 * 断言的是**两个矩形不相等**这件事本身：只要 [LiquidGlassBackdropSpec.photoRectPx]
 * 被真的用上，结果必须落在照片矩形里，而不是宿主矩形里。
 */
class ViewerPhotoRectAlignmentTest {

    private val hostWidth = 1080f
    private val hostHeight = 2400f

    /**
     * 接近屏幕比例的竖图（3:4）在查看器 95%×90% 容器里的实际矩形。
     *
     * 与 `ViewerOverlay` 里同一套算法：先取 `min(95%宽/比例, 90%高)` 决定受哪边限制。
     * 1080×2400 上 3:4 的图 **受宽度限制**（1026 宽 → 1368 高），
     * 上下各留约 516px 黑边——不是受高度限制，这里不能凭直觉写。
     */
    private fun viewerPhotoRect(): PhotoDisplayRect {
        val maxWidth = hostWidth * 0.95f   // 1026
        val maxHeight = hostHeight * 0.90f // 2160
        val aspect = 3f / 4f
        val (width, height) =
            if (aspect > maxWidth / maxHeight) maxWidth to (maxWidth / aspect)
            else (maxHeight * aspect) to maxHeight
        return PhotoDisplayRect(
            left = (hostWidth - width) / 2f,
            top = (hostHeight - height) / 2f,
            width = width,
            height = height
        )
    }

    @Test
    fun `photo rect is narrower than host and vertically centered`() {
        val r = viewerPhotoRect()
        assertTrue("照片宽度必须小于宿主", r.width < hostWidth)
        assertEquals("照片必须水平居中", (hostWidth - r.width) / 2f, r.left, 0.01f)
        assertTrue("上下必须留黑边", r.top > 0f)
        assertTrue(r.isValid)
    }

    @Test
    fun `host-fit and photo-fit disagree for a near-screen portrait`() {
        // 旧行为：整个宿主按 Fit
        val hostFit = fitInsideRect(3, 4, boxWidth = hostWidth, boxHeight = hostHeight)!!
        val photo = viewerPhotoRect()

        // 两个矩形必须不同——相同就说明"按宿主 Fit"恰好等价，
        // 这组测试也就没有意义了（会掩盖真实的坐标错位）。
        assertFalse(
            "宿主 Fit 与照片矩形必须不同，否则这条断言抓不到错位",
            hostFit.width == photo.width && hostFit.top == photo.top
        )
        // 宿主 Fit 出来的一定更宽：这正是"按钮下方采到照片"的来源
        assertTrue("宿主 Fit 会把照片放大", hostFit.width > photo.width)
    }

    @Test
    fun `spec without photo rect keeps legacy host-fit behaviour`() {
        // 回收站查看器是满屏，不声明矩形 → 必须保持旧行为，不能悄悄变成 0 尺寸
        val spec = LiquidGlassBackdropSpec.Default
        assertNull("默认不应声明照片矩形", spec.photoRectPx)
    }

    @Test
    fun `invalid rects are rejected so the caller can fall back`() {
        assertNull("零宽必须判无效", PhotoDisplayRect.of(Rect(0f, 0f, 0f, 100f)))
        assertNull("零高必须判无效", PhotoDisplayRect.of(Rect(0f, 0f, 100f, 0f)))
        assertNull("null 输入返回 null", PhotoDisplayRect.of(null))
        assertNotNull("正常矩形必须通过", PhotoDisplayRect.of(Rect(10f, 20f, 30f, 40f)))
    }

    @Test
    fun `ordinary viewer with missing or invalid geometry uses the solid button`() {
        val base = ViewerBackdropDecision(
            attachGateReason = BackdropFallbackReason.NONE,
            requirePhotoRect = true
        )

        assertFalse(base.sampling)
        assertTrue(base.pilotActive)
        assertEquals(BackdropFallbackReason.NO_COORDINATES, base.reason)
        assertFalse(base.copy(photoRect = PhotoDisplayRect(0f, 0f, 0f, 3f)).sampling)
        assertTrue(base.copy(photoRect = viewerPhotoRect()).sampling)
        assertFalse(base.copy(
            photoRect = viewerPhotoRect(),
            syncReason = BackdropFallbackReason.VIEWER_TRANSFORMED
        ).sampling)
    }

    @Test
    fun `fullscreen viewer may sample without an explicit photo rectangle`() {
        assertTrue(ViewerBackdropDecision(BackdropFallbackReason.NONE).sampling)
    }

    @Test
    fun `pilotActive is false only when the pilot itself is off`() {
        // 开关关闭：按钮不存在，回到点背景退出
        assertFalse(
            ViewerBackdropDecision(
                attachGateReason = BackdropFallbackReason.DISABLED
            ).pilotActive
        )
        // 系统不支持：同上
        assertFalse(
            ViewerBackdropDecision(
                attachGateReason = BackdropFallbackReason.UNSUPPORTED_API
            ).pilotActive
        )
        // 试点开着但照片未就绪：按钮**仍在**（实色），用户不能被卡住
        assertTrue(
            ViewerBackdropDecision(
                attachGateReason = BackdropFallbackReason.NO_BACKDROP
            ).pilotActive
        )
        // 试点开着且采样可用
        assertTrue(
            ViewerBackdropDecision(attachGateReason = BackdropFallbackReason.NONE).pilotActive
        )
    }

    @Test
    fun `pilotActive is independent from sampling`() {
        // 放大拖动中：试点开着、按钮在，但不采样 → 实色
        val transformed = ViewerBackdropDecision(
            attachGateReason = BackdropFallbackReason.NONE,
            syncReason = BackdropFallbackReason.VIEWER_TRANSFORMED
        )
        assertTrue("试点仍开着", transformed.pilotActive)
        assertFalse("但此刻不采样", transformed.sampling)
        assertEquals("fallback", transformed.path)

        // 试点关闭：既不显示按钮也不采样
        val off = ViewerBackdropDecision(attachGateReason = BackdropFallbackReason.DISABLED)
        assertFalse(off.pilotActive)
        assertFalse(off.sampling)
    }
}
