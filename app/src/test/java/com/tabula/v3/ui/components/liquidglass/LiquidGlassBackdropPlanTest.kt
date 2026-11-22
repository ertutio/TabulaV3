package com.tabula.v3.ui.components.liquidglass

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背景采样层的几何与回退决策测试。
 *
 * 这里锁定的是"什么时候采、采多大、平移多少"三件事；一旦有人改动对齐算法
 * 或放宽回退条件，这些断言会直接失败，避免悄悄采到玻璃自身。
 */
class LiquidGlassBackdropPlanTest {

    private val source = IntRect(left = 0, top = 0, right = 1080, bottom = 2400)

    @Test
    fun `sdk below 33 falls back to original material`() {
        val plan = planBackdropSample(
            sdkInt = 31,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = IntRect(100, 200, 500, 400),
            sourceBoundsInWindow = source,
            paddingPx = 8
        )
        assertFalse(plan.useSampledBackdrop)
        assertEquals(BackdropSamplingStatus.ORIGINAL, plan.status)
        assertEquals(BackdropFallbackReason.UNSUPPORTED_API, plan.reason)
    }

    @Test
    fun `missing backdrop falls back to original material`() {
        val plan = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = false,
            isRoundedRectShape = true,
            glassBoundsInWindow = IntRect(100, 200, 500, 400),
            sourceBoundsInWindow = source,
            paddingPx = 8
        )
        assertEquals(BackdropFallbackReason.NO_BACKDROP, plan.reason)
        assertFalse(plan.useSampledBackdrop)
    }

    @Test
    fun `explicitly disabled spec keeps the previous picture`() {
        val plan = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = IntRect(100, 200, 500, 400),
            sourceBoundsInWindow = source,
            paddingPx = 8,
            enabled = false
        )
        assertEquals(BackdropFallbackReason.DISABLED, plan.reason)
    }

    @Test
    fun `non rounded rect shape keeps the previous materials`() {
        val plan = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = false,
            glassBoundsInWindow = IntRect(100, 200, 500, 400),
            sourceBoundsInWindow = source,
            paddingPx = 8
        )
        assertEquals(BackdropFallbackReason.UNSUPPORTED_SHAPE, plan.reason)
    }

    @Test
    fun `coordinates not ready yet falls back`() {
        val plan = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = null,
            sourceBoundsInWindow = source,
            paddingPx = 8
        )
        assertEquals(BackdropFallbackReason.NO_COORDINATES, plan.reason)
    }

    @Test
    fun `zero sized glass falls back`() {
        val plan = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = IntRect(100, 200, 100, 400),
            sourceBoundsInWindow = source,
            paddingPx = 8
        )
        assertEquals(BackdropFallbackReason.DEGENERATE_GEOMETRY, plan.reason)
    }

    @Test
    fun `sampling near the source edge falls back instead of reading glass itself`() {
        val plan = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = IntRect(left = 2, top = 200, right = 400, bottom = 400),
            sourceBoundsInWindow = source,
            paddingPx = 8
        )
        assertEquals(BackdropFallbackReason.OUT_OF_SOURCE_BOUNDS, plan.reason)
    }

    @Test
    fun `sample layer covers glass plus padding only`() {
        val plan = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = IntRect(left = 100, top = 300, right = 600, bottom = 700),
            sourceBoundsInWindow = source,
            paddingPx = 10
        )
        assertTrue(plan.useSampledBackdrop)
        assertEquals(BackdropFallbackReason.NONE, plan.reason)
        assertEquals(500 + 20, plan.layerWidthPx)
        assertEquals(400 + 20, plan.layerHeightPx)
        assertEquals(10, plan.paddingPx)
        // 离屏范围只比玻璃大一圈采样外扩，不是整屏
        assertTrue(plan.layerWidthPx < source.width)
        assertTrue(plan.layerHeightPx < source.height)
    }

    @Test
    fun `alignment maps the source pixel under the glass to the padding origin`() {
        val plan = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = IntRect(left = 120, top = 340, right = 620, bottom = 740),
            sourceBoundsInWindow = source,
            paddingPx = 12
        )
        // 玻璃左上角在背景坐标系里的位置
        assertEquals(120, plan.glassOriginInSource.x)
        assertEquals(340, plan.glassOriginInSource.y)
        // 背景像素 120 → 图层内 padding(12)，平移量 = 12 - 120
        assertEquals(-108f, plan.sourceTranslateX, 0.001f)
        assertEquals(-328f, plan.sourceTranslateY, 0.001f)
    }

    @Test
    fun `alignment follows scroll offset without drifting`() {
        val scrollOffsets = listOf(0, 37, 128, 4096)
        scrollOffsets.forEach { dy ->
            val plan = planBackdropSample(
                sdkInt = 35,
                hasBackdropSource = true,
                isRoundedRectShape = true,
                glassBoundsInWindow = IntRect(left = 0, top = 400 - dy, right = 1080, bottom = 800 - dy),
                sourceBoundsInWindow = source,
                paddingPx = 4
            )
            if (plan.useSampledBackdrop) {
                // 背景平移 + 玻璃原点 恒等于 padding：滚动后不会累积误差
                assertEquals(4f, plan.sourceTranslateY + plan.glassOriginInSource.y, 0.001f)
            }
        }
    }

    @Test
    fun `effect key is null when sampling is off so no render effect is built`() {
        val plan = BackdropSamplePlan.original(BackdropFallbackReason.NO_BACKDROP)
        val appearance = LiquidGlassBackdropSpec.Default.resolve(density = 2.75f)
        assertNull(backdropEffectKey(plan, appearance, cornerRadiusPx = 40f, contentRevision = 0))
    }

    @Test
    fun `effect key changes when the background container moves`() {
        val appearance = LiquidGlassBackdropSpec.Default.resolve(density = 2.75f)
        val before = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = IntRect(100, 300, 600, 700),
            sourceBoundsInWindow = source,
            paddingPx = 10
        )
        val after = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = IntRect(100, 380, 600, 780),
            sourceBoundsInWindow = source,
            paddingPx = 10
        )
        val keyBefore = backdropEffectKey(before, appearance, 40f, contentRevision = 3)
        val keyAfter = backdropEffectKey(after, appearance, 40f, contentRevision = 3)
        assertNotNull(keyBefore)
        assertNotNull(keyAfter)
        assertTrue("滚动后必须重新对齐", keyBefore != keyAfter)
    }

    @Test
    fun `effect key is stable for a still scene`() {
        val appearance = LiquidGlassBackdropSpec.Default.resolve(density = 2.75f)
        val bounds = IntRect(100, 300, 600, 700)
        val first = planBackdropSample(35, true, true, bounds, source, 10)
        val second = planBackdropSample(35, true, true, bounds, source, 10)
        assertEquals(
            backdropEffectKey(first, appearance, 40f, 1),
            backdropEffectKey(second, appearance, 40f, 1)
        )
    }

    @Test
    fun `padding is clamped to zero so a negative request cannot grow the layer`() {
        val plan = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = IntRect(100, 300, 600, 700),
            sourceBoundsInWindow = source,
            paddingPx = -20
        )
        assertTrue(plan.useSampledBackdrop)
        assertEquals(0, plan.paddingPx)
        assertEquals(500, plan.layerWidthPx)
    }

    @Test
    fun `spec resolves dp to pixels and clamps dispersion`() {
        val appearance = LiquidGlassBackdropSpec(
            refractionHeightDp = androidx.compose.ui.unit.Dp(20f),
            refractionAmountDp = androidx.compose.ui.unit.Dp(10f),
            dispersion = 2f,
            blurRadiusDp = androidx.compose.ui.unit.Dp(16f)
        ).resolve(density = 3f)
        assertEquals(60f, appearance.refractionHeightPx, 0.001f)
        assertEquals(30f, appearance.refractionAmountPx, 0.001f)
        assertEquals(1f, appearance.dispersion, 0.001f)
        assertEquals(48f, appearance.blurRadiusPx, 0.001f)
    }

    @Test
    fun `off spec never samples`() {
        assertFalse(LiquidGlassBackdropSpec.Off.enabled)
    }

    @Test
    fun `audit line is readable for both branches`() {
        val sampled = planBackdropSample(
            35, true, true, IntRect(10, 20, 110, 220), source, 5
        )
        assertTrue(sampled.auditLine().startsWith("SAMPLED"))
        val original = BackdropSamplePlan.original(BackdropFallbackReason.NO_BACKDROP)
        assertEquals("ORIGINAL reason=NO_BACKDROP", original.auditLine())
    }
}

/**
 * 层序与 API 门控：新增背景层不能挤占原有高光/边缘/内阴影/噪点/形状的位置。
 */
class LiquidGlassLayerOrderTest {

    @Test
    fun `content stays on the same relative position with and without the backdrop layer`() {
        // 没有背景层时是原有 8 层实现，内容在第 4 层
        assertEquals(4, glassContentLayerIndex(hasBackdropLayer = false))
        // 有背景层时它插在最前，内容顺延一位，其余层的相对顺序不变
        assertEquals(5, glassContentLayerIndex(hasBackdropLayer = true))
        assertEquals(8, LIQUID_GLASS_CORE_LAYER_COUNT)
    }

    @Test
    fun `api 31 and 32 never produce a sample plan`() {
        listOf(31, 32).forEach { sdk ->
            val plan = planBackdropSample(
                sdkInt = sdk,
                hasBackdropSource = true,
                isRoundedRectShape = true,
                glassBoundsInWindow = androidx.compose.ui.unit.IntRect(100, 300, 600, 700),
                sourceBoundsInWindow = androidx.compose.ui.unit.IntRect(0, 0, 1080, 2400),
                paddingPx = 10
            )
            assertFalse("API $sdk 应回退到原有显示", plan.useSampledBackdrop)
            assertEquals(BackdropFallbackReason.UNSUPPORTED_API, plan.reason)
        }
    }

    @Test
    fun `api 33 and above may sample`() {
        listOf(33, 34, 35, 36).forEach { sdk ->
            val plan = planBackdropSample(
                sdkInt = sdk,
                hasBackdropSource = true,
                isRoundedRectShape = true,
                glassBoundsInWindow = androidx.compose.ui.unit.IntRect(100, 300, 600, 700),
                sourceBoundsInWindow = androidx.compose.ui.unit.IntRect(0, 0, 1080, 2400),
                paddingPx = 10
            )
            assertTrue("API $sdk 应可采样", plan.useSampledBackdrop)
        }
    }
}

/**
 * 背景内容版本与重建键的关系：背景变了必须重录，静止时必须稳定。
 */
class LiquidGlassBackdropRevisionTest {

    private val appearance = LiquidGlassBackdropSpec.Default.resolve(density = 2.75f)
    private val bounds = androidx.compose.ui.unit.IntRect(100, 300, 600, 700)
    private val source = androidx.compose.ui.unit.IntRect(0, 0, 1080, 2400)

    @Test
    fun `content revision change forces a re-record`() {
        val plan = planBackdropSample(35, true, true, bounds, source, 10)
        val before = backdropEffectKey(plan, appearance, 40f, contentRevision = 7)
        val after = backdropEffectKey(plan, appearance, 40f, contentRevision = 8)
        assertTrue("背景换图后必须重新采样", before != after)
    }

    @Test
    fun `appearance change forces a re-record`() {
        val plan = planBackdropSample(35, true, true, bounds, source, 10)
        val weak = LiquidGlassBackdropSpec(
            refractionHeightDp = androidx.compose.ui.unit.Dp(18f),
            refractionAmountDp = androidx.compose.ui.unit.Dp(10f)
        ).resolve(density = 2.75f)
        val strong = LiquidGlassBackdropSpec(
            refractionHeightDp = androidx.compose.ui.unit.Dp(18f),
            refractionAmountDp = androidx.compose.ui.unit.Dp(24f)
        ).resolve(density = 2.75f)
        assertTrue(
            "改参数后必须重建效果",
            backdropEffectKey(plan, weak, 40f, 1) != backdropEffectKey(plan, strong, 40f, 1)
        )
    }

    @Test
    fun `size change forces a re-record`() {
        val before = planBackdropSample(35, true, true, bounds, source, 10)
        val after = planBackdropSample(
            35, true, true,
            androidx.compose.ui.unit.IntRect(100, 300, 600, 860), source, 10
        )
        assertTrue(
            "改尺寸后必须重建效果",
            backdropEffectKey(before, appearance, 40f, 1) != backdropEffectKey(after, appearance, 40f, 1)
        )
    }
}

/**
 * 审计标记：CI 靠它判断"方案成立"与"效果真的建起来"两件事，格式不能悄悄变形。
 */
class LiquidGlassAuditMarkerTest {

    @Test
    fun `sampled plan exposes origin layer and padding`() {
        val plan = planBackdropSample(
            35, true, true,
            androidx.compose.ui.unit.IntRect(120, 340, 620, 740),
            androidx.compose.ui.unit.IntRect(0, 0, 1080, 2400),
            12
        )
        val markers = plan.auditMarkers(BackdropEffectHealth.Ok)
        assertTrue(markers.startsWith("AUDIT "))
        assertTrue(markers.contains("sampled=true"))
        assertTrue(markers.contains("glass=500x400"))
        assertTrue(markers.contains("origin=(120,340)"))
        assertTrue(markers.contains("layer=524x424"))
        assertTrue(markers.contains("pad=12"))
        assertTrue(markers.contains("effect=Ok"))
    }

    @Test
    fun `fallback plan reports false and the reason`() {
        val plan = BackdropSamplePlan.original(BackdropFallbackReason.OUT_OF_SOURCE_BOUNDS)
        val markers = plan.auditMarkers(BackdropEffectHealth.Unknown)
        assertTrue(markers.contains("sampled=false"))
        assertTrue(markers.contains("reason=OUT_OF_SOURCE_BOUNDS"))
        assertTrue(markers.contains("effect=Unknown"))
    }

    @Test
    fun `audit markers stay single token space separated for regex parsing`() {
        val plan = planBackdropSample(
            35, true, true,
            androidx.compose.ui.unit.IntRect(10, 20, 110, 220),
            androidx.compose.ui.unit.IntRect(0, 0, 1080, 2400),
            5
        )
        val markers = plan.auditMarkers(BackdropEffectHealth.Failed)
        assertFalse("不能出现换行", markers.contains("\n"))
        // origin/尺寸各自是单个 token，CI 用 split() 后按 = 取值
        markers.removePrefix("AUDIT ").split(" ").forEach { token ->
            assertTrue("token 必须含 =: $token", token.contains("="))
        }
    }
}
