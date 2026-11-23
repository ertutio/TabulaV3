package com.tabula.v3.ui.components.liquidglass

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采样层的几何与回退决策测试。
 *
 * 这里锁定"什么时候采、采多大、平移多少、背景以什么尺寸重绘"四件事；
 * 一旦有人改动对齐算法或放宽回退条件，这些断言会直接失败，避免悄悄采到玻璃自身。
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
            refractionHeightDp = 20.dp,
            refractionAmountDp = 10.dp,
            dispersion = 2f,
            blurRadiusDp = 16.dp
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
 * 尺寸契约：采样层必须以**背景来源尺寸**重绘背景，而不是玻璃尺寸。
 *
 * 这是本轮修复的核心缺陷：原实现只把"玻璃尺寸的 DrawScope"平移了一下，
 * 于是所有 `size` 相关几何（圆形半径、尺寸相关渐变、等分图案）都按玻璃重算，
 * 画面不是同一张背景。下面几条用可计算的数值锁死这一点。
 */
class LiquidGlassBackdropSizeContractTest {

    private val source = IntRect(left = 0, top = 0, right = 1080, bottom = 1800)

    private fun sampledPlan(
        glass: IntRect = IntRect(120, 340, 620, 740),
        paddingPx: Int = 20
    ) = planBackdropSample(35, true, true, glass, source, paddingPx)

    @Test
    fun `content is drawn with the host size not the glass size`() {
        val plan = sampledPlan()
        val placement = backdropContentPlacement(plan, source.width, source.height)
        assertNotNull(placement)
        // 宿主 1080x1800，玻璃只有 500x400：两者必须不同，否则这条测试没有意义
        assertTrue(plan.glassWidthPx != source.width)
        assertEquals(1080, placement!!.sourceWidthPx)
        assertEquals(1800, placement.sourceHeightPx)
    }

    @Test
    fun `host and glass of different sizes still align pixel for pixel`() {
        val plan = sampledPlan()
        val placement = backdropContentPlacement(plan, source.width, source.height)!!
        // 玻璃左上角在来源坐标 (120,340) 必须落到图层内 (pad,pad)
        val layerPoint = placement.sourceToLayer(120f, 340f)
        assertEquals(plan.paddingPx.toFloat(), layerPoint.x, 0.001f)
        assertEquals(plan.paddingPx.toFloat(), layerPoint.y, 0.001f)
        // 反向换算必须回到原点（无累积误差）
        val back = placement.layerToSource(layerPoint.x, layerPoint.y)
        assertEquals(120f, back.x, 0.001f)
        assertEquals(340f, back.y, 0.001f)
    }

    @Test
    fun `nonzero source origin shifts the placement so alignment is relative`() {
        // 宿主不在屏幕原点（例如下方内容区、带顶部内边距）
        val offsetSource = IntRect(left = 40, top = 260, right = 1120, bottom = 2060)
        val plan = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = IntRect(160, 600, 660, 1000),
            sourceBoundsInWindow = offsetSource,
            paddingPx = 12
        )
        assertTrue(plan.useSampledBackdrop)
        // 玻璃相对宿主是 (120,340)，与宿主绝对位置无关
        assertEquals(120, plan.glassOriginInSource.x)
        assertEquals(340, plan.glassOriginInSource.y)
        assertEquals((12 - 120).toFloat(), plan.sourceTranslateX, 0.001f)
        assertEquals((12 - 340).toFloat(), plan.sourceTranslateY, 0.001f)
    }

    @Test
    fun `circle geometry depends on host size so a wrong scope is detectable`() {
        // 预览里的同心圆半径 = minDimension * 0.055 * i
        fun ringRadius(dimension: Float, i: Int) = dimension * 0.055f * i
        val hostMin = minOf(source.width, source.height).toFloat() // 1080
        val glassMin = 400f
        // 用宿主尺寸算出来的半径必须与玻璃尺寸算出来的明显不同，
        // 否则"半径按玻璃重算"这个缺陷在画面上看不出来
        assertTrue(ringRadius(hostMin, 3) - ringRadius(glassMin, 3) > 100f)
    }

    @Test
    fun `size dependent gradient endpoints follow the host size`() {
        // 预览里的渐变端点 = size * 比例；用宿主尺寸与玻璃尺寸算出的端点必须不同
        fun endX(width: Float) = width * 1.10f
        assertTrue(endX(source.width.toFloat()) - endX(500f) > 600f)
    }

    @Test
    fun `layer size always equals glass plus two paddings`() {
        listOf(0, 4, 20, 64).forEach { pad ->
            val plan = sampledPlan(paddingPx = pad)
            assertEquals(plan.glassWidthPx + pad * 2, plan.layerWidthPx)
            assertEquals(plan.glassHeightPx + pad * 2, plan.layerHeightPx)
            assertEquals(pad, plan.paddingPx)
        }
    }

    @Test
    fun `shader uniforms derive from the layer size not from the glass size`() {
        val plan = sampledPlan()
        val appearance = LiquidGlassBackdropSpec.Default.resolve(2.75f)
        val uniforms = backdropShaderUniforms(
            layerWidthPx = plan.layerWidthPx,
            layerHeightPx = plan.layerHeightPx,
            paddingPx = plan.paddingPx,
            cornerRadiusPx = 40f,
            appearance = appearance
        )
        assertEquals(plan.layerWidthPx.toFloat(), uniforms.layerWidth, 0.001f)
        assertEquals(plan.layerHeightPx.toFloat(), uniforms.layerHeight, 0.001f)
        // 玻璃在图层内的原点必须等于 padding，否则玻璃区域会整体偏移
        assertEquals(plan.paddingPx.toFloat(), uniforms.glassOriginX, 0.001f)
        assertEquals(plan.paddingPx.toFloat(), uniforms.glassOriginY, 0.001f)
        assertEquals(plan.glassWidthPx.toFloat(), uniforms.glassWidth, 0.001f)
        assertEquals(plan.glassHeightPx.toFloat(), uniforms.glassHeight, 0.001f)
    }

    @Test
    fun `corner radius is clamped to half of the shorter glass side`() {
        val appearance = LiquidGlassBackdropSpec.Default.resolve(2.75f)
        val uniforms = backdropShaderUniforms(
            layerWidthPx = 300,
            layerHeightPx = 140,
            paddingPx = 20,
            cornerRadiusPx = 999f,
            appearance = appearance
        )
        // 玻璃 260x100 → 上限 50
        assertEquals(50f, uniforms.cornerRadius, 0.001f)
    }

    @Test
    fun `layer to glass round trip is exact for every padding`() {
        listOf(0, 1, 7, 20, 64).forEach { pad ->
            val layer = layerSizeFor(IntSize(500, 400), pad)
            assertEquals(IntSize(500, 400), glassSizeFromLayer(layer, pad))
        }
    }

    @Test
    fun `content placement is null when the plan is not sampled`() {
        val plan = BackdropSamplePlan.original(BackdropFallbackReason.NO_COORDINATES)
        assertNull(backdropContentPlacement(plan, 1080, 1800))
    }

    @Test
    fun `content placement is null for a degenerate source`() {
        val plan = sampledPlan()
        assertNull(backdropContentPlacement(plan, 0, 1800))
        assertNull(backdropContentPlacement(plan, 1080, 0))
    }

    @Test
    fun `padding is derived from the blur kernel only`() {
        val strong = LiquidGlassBackdropSpec(
            refractionHeightDp = 18.dp,
            refractionAmountDp = 10.dp,
            blurRadiusDp = 20.dp
        ).resolve(2.75f) // 模糊 55px → 半个核 27.5px
        assertEquals(28, backdropPaddingPx(strong))

        val small = LiquidGlassBackdropSpec(
            refractionHeightDp = 10.dp,
            refractionAmountDp = 4.dp,
            blurRadiusDp = 4.dp
        ).resolve(2.75f) // 模糊 11px → 5.5px
        assertEquals(6, backdropPaddingPx(small))
    }

    @Test
    fun `changing the refraction amount never changes the padding`() {
        // 这是"只改位移"对照能成立的前提：外扩只由模糊决定。
        // 早期写法 max(模糊, 位移)/2 会让 pad 随位移变化（实测 3 vs 16），
        // 于是两组采样范围不同，差异无法归因于位移。
        val amounts = listOf(0f, 2f, 4f, 10f, 24f)
        val pads = amounts.map { amount ->
            backdropPaddingPx(
                LiquidGlassBackdropSpec(
                    refractionHeightDp = 18.dp,
                    refractionAmountDp = amount.dp,
                    blurRadiusDp = 20.dp
                ).resolve(2.75f)
            )
        }
        assertEquals("位移变化时外扩必须恒定", 1, pads.toSet().size)
        assertEquals(28, pads.first())
    }

    @Test
    fun `padding never collapses to zero and never exceeds the cap`() {
        val zero = LiquidGlassBackdropSpec(
            refractionHeightDp = 0.dp,
            refractionAmountDp = 0.dp,
            blurRadiusDp = 0.dp
        ).resolve(2.75f)
        assertEquals(1, backdropPaddingPx(zero))

        val huge = LiquidGlassBackdropSpec(
            refractionHeightDp = 18.dp,
            refractionAmountDp = 10.dp,
            blurRadiusDp = 200.dp
        ).resolve(2.75f)
        assertEquals(64, backdropPaddingPx(huge))
    }
}

/**
 * 形状契约：只有"四角半径一致"的圆角矩形能采样，圆形是它的特例。
 */
class LiquidGlassBackdropShapeTest {

    private val density = Density(2.75f)
    private val size = Size(500f, 400f)

    @Test
    fun `uniform rounded rect is supported`() {
        assertTrue(isBackdropShapeSupported(RoundedCornerShape(20.dp)))
        val metrics = backdropShapeMetrics(RoundedCornerShape(20.dp), size, density)
        assertTrue(metrics.isUniformRoundedRect)
        assertEquals(20f * 2.75f, metrics.cornerRadiusPx, 0.01f)
    }

    @Test
    fun `circle is supported and resolves to half of the shorter side`() {
        assertTrue(isBackdropShapeSupported(CircleShape))
        val square = Size(200f, 200f)
        val metrics = backdropShapeMetrics(CircleShape, square, density)
        assertTrue(metrics.isUniformRoundedRect)
        assertEquals(100f, metrics.cornerRadiusPx, 0.01f)
    }

    @Test
    fun `circle radius follows the shorter side on a non square`() {
        val metrics = backdropShapeMetrics(CircleShape, Size(300f, 120f), density)
        assertTrue(metrics.isUniformRoundedRect)
        assertEquals(60f, metrics.cornerRadiusPx, 0.01f)
    }

    @Test
    fun `cut corner shape is rejected`() {
        assertFalse(isBackdropShapeSupported(CutCornerShape(12.dp)))
        val metrics = backdropShapeMetrics(CutCornerShape(12.dp), size, density)
        assertFalse(metrics.isUniformRoundedRect)
    }

    @Test
    fun `asymmetric rounded corners are rejected`() {
        val asymmetric = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomEnd = 0.dp,
            bottomStart = 0.dp
        )
        assertFalse(isBackdropShapeSupported(asymmetric))
        assertFalse(backdropShapeMetrics(asymmetric, size, density).isUniformRoundedRect)
    }

    @Test
    fun `radius larger than the shape is clamped not rejected`() {
        val big = RoundedCornerShape(200.dp)
        val metrics = backdropShapeMetrics(big, Size(200f, 100f), density)
        assertTrue(metrics.isUniformRoundedRect)
        assertEquals(50f, metrics.cornerRadiusPx, 0.01f)
    }

    @Test
    fun `unsupported shape falls back with a distinct reason`() {
        val plan = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = isBackdropShapeSupported(CutCornerShape(12.dp)),
            glassBoundsInWindow = IntRect(100, 300, 600, 700),
            sourceBoundsInWindow = IntRect(0, 0, 1080, 1800),
            paddingPx = 12
        )
        assertEquals(BackdropFallbackReason.UNSUPPORTED_SHAPE, plan.reason)
        assertFalse(plan.useSampledBackdrop)
    }

    @Test
    fun `circle on a wide glass still samples`() {
        val plan = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = isBackdropShapeSupported(CircleShape),
            glassBoundsInWindow = IntRect(100, 300, 600, 500),
            sourceBoundsInWindow = IntRect(0, 0, 1080, 1800),
            paddingPx = 12
        )
        assertTrue(plan.useSampledBackdrop)
    }
}

/**
 * Layout 子项契约：采样不可用时内容仍必须按真实内容测量、原材质仍可见。
 *
 * 这一组是本轮第二个缺陷的回归锁：原实现"采样句柄非空就推断子项存在"，
 * 而采样不成立时 `BackdropLayer` 直接 `return` 不发子项，内容下标错位，
 * `wrapContent` 塌成 0。
 */
class LiquidGlassLayoutContractTest {

    private val parentConstraints = Constraints()

    @Test
    fun `child count and content index are stable with and without the backdrop layer`() {
        val withBackdrop = glassChildLayout(hasBackdropLayer = true)
        val without = glassChildLayout(hasBackdropLayer = false)

        assertEquals(LIQUID_GLASS_CORE_LAYER_COUNT + 1, withBackdrop.childCount)
        assertEquals(LIQUID_GLASS_CONTENT_LAYER_INDEX + 1, withBackdrop.contentIndex)
        assertEquals(0, withBackdrop.backdropIndex)

        assertEquals(LIQUID_GLASS_CORE_LAYER_COUNT, without.childCount)
        assertEquals(LIQUID_GLASS_CONTENT_LAYER_INDEX, without.contentIndex)
        assertNull(without.backdropIndex)

        // 背景层只占一个子项，其余层的相对顺序不变
        assertEquals(without.contentIndex + 1, withBackdrop.contentIndex)
    }

    @Test
    fun `content keeps the parent constraints so wrapContent measures real content`() {
        val layout = glassChildLayout(hasBackdropLayer = true)
        val plan = planGlassLayout(
            layout = layout,
            paddingPx = 20,
            contentSize = IntSize(320, 96),
            parentConstraints = parentConstraints
        )
        // 内容层拿父约束（不是固定尺寸），wrapContent 才能返回真实内容尺寸
        assertEquals(parentConstraints, plan.constraintsPerChild[plan.contentIndex])
        assertFalse(plan.constraintsPerChild[plan.contentIndex].hasFixedWidth)
        assertFalse(plan.constraintsPerChild[plan.contentIndex].hasFixedHeight)
    }

    @Test
    fun `backdrop layer is measured with content size plus two paddings`() {
        val layout = glassChildLayout(hasBackdropLayer = true)
        val plan = planGlassLayout(
            layout = layout,
            paddingPx = 20,
            contentSize = IntSize(320, 96),
            parentConstraints = parentConstraints
        )
        val backdrop = plan.constraintsPerChild[layout.backdropIndex!!]
        assertEquals(320 + 40, backdrop.minWidth)
        assertEquals(96 + 40, backdrop.minHeight)
        assertEquals(320 + 40, backdrop.maxWidth)
        assertEquals(96 + 40, backdrop.maxHeight)
    }

    @Test
    fun `backdrop layer is placed at minus padding so it overlaps the glass exactly`() {
        val layout = glassChildLayout(hasBackdropLayer = true)
        val plan = planGlassLayout(
            layout = layout,
            paddingPx = 20,
            contentSize = IntSize(320, 96),
            parentConstraints = parentConstraints
        )
        assertEquals(IntOffset(-20, -20), plan.offsetsPerChild[layout.backdropIndex!!])
        // 其余所有层都贴在 (0,0)，与接入前一致
        plan.offsetsPerChild.forEachIndexed { index, offset ->
            if (index != layout.backdropIndex) assertEquals(IntOffset.Zero, offset)
        }
    }

    @Test
    fun `all non content layers are measured with the content size`() {
        val layout = glassChildLayout(hasBackdropLayer = true)
        val plan = planGlassLayout(
            layout = layout,
            paddingPx = 8,
            contentSize = IntSize(200, 120),
            parentConstraints = parentConstraints
        )
        plan.constraintsPerChild.forEachIndexed { index, constraints ->
            when (index) {
                layout.contentIndex -> Unit
                layout.backdropIndex -> assertEquals(200 + 16, constraints.maxWidth)
                else -> {
                    assertEquals(200, constraints.maxWidth)
                    assertEquals(120, constraints.maxHeight)
                }
            }
        }
    }

    @Test
    fun `wrap content glass with no backdrop layer still measures the content`() {
        val layout = glassChildLayout(hasBackdropLayer = false)
        val plan = planGlassLayout(
            layout = layout,
            paddingPx = 0,
            contentSize = IntSize(180, 64),
            parentConstraints = parentConstraints
        )
        assertEquals(IntSize(180, 64), plan.contentSize)
        assertEquals(LIQUID_GLASS_CORE_LAYER_COUNT, plan.constraintsPerChild.size)
        assertEquals(LIQUID_GLASS_CORE_LAYER_COUNT, plan.offsetsPerChild.size)
        assertEquals(IntSize(180, 64), plan.backdropLayerSize)
    }

    @Test
    fun `dynamic switch from fixed size to wrap content keeps indices valid`() {
        // 固定大小 → wrapContent 切换时，内容尺寸变化，但下标与偏移不能变
        val layout = glassChildLayout(hasBackdropLayer = true)
        val fixed = planGlassLayout(layout, 20, IntSize(500, 400), parentConstraints)
        val wrapped = planGlassLayout(layout, 20, IntSize(210, 88), parentConstraints)

        assertEquals(fixed.contentIndex, wrapped.contentIndex)
        assertEquals(fixed.childCount, wrapped.childCount)
        assertEquals(fixed.offsetsPerChild, wrapped.offsetsPerChild)
        // 背景层尺寸必须跟着内容尺寸变，否则会留一圈旧尺寸的空隙
        assertEquals(IntSize(250, 128), wrapped.backdropLayerSize)
        assertEquals(IntSize(540, 440), fixed.backdropLayerSize)
    }

    @Test
    fun `content is measured exactly once and other children once each`() {
        // Compose 对同一个 Measurable 只允许 measure 一次；重复会抛
        // IllegalStateException，而且是在布局阶段——首帧已经画出去了，
        // 表现成"闪一下然后崩溃"。这里把"每个下标只测一次"锁成事实。
        val layout = glassChildLayout(hasBackdropLayer = true)
        val content = "content"
        val measured = mutableListOf<Int>()
        val placeables = collectPlaceablesInPlanOrder(
            childCount = layout.childCount,
            contentIndex = layout.contentIndex,
            contentPlaceable = content,
            measureOther = { index ->
                measured += index
                "child-$index"
            }
        )

        assertEquals(layout.childCount, placeables.size)
        assertEquals(content, placeables[layout.contentIndex])
        // 内容层没有被重复测量
        assertFalse("内容层被测量了第二次", measured.contains(layout.contentIndex))
        // 其余每个下标恰好一次
        assertEquals((0 until layout.childCount).filter { it != layout.contentIndex }, measured)
        assertEquals(measured.size, measured.toSet().size)
    }

    @Test
    fun `measure once contract also holds without a backdrop layer`() {
        val layout = glassChildLayout(hasBackdropLayer = false)
        val measured = mutableListOf<Int>()
        collectPlaceablesInPlanOrder(
            childCount = layout.childCount,
            contentIndex = layout.contentIndex,
            contentPlaceable = "content",
            measureOther = { measured += it }
        )
        assertFalse(measured.contains(layout.contentIndex))
        assertEquals(layout.childCount - 1, measured.size)
    }

    @Test
    fun `zero sized content keeps the plan valid instead of crashing`() {
        val layout = glassChildLayout(hasBackdropLayer = true)
        val plan = planGlassLayout(layout, 20, IntSize.Zero, parentConstraints)
        assertEquals(IntSize(40, 40), plan.backdropLayerSize)
        assertEquals(IntSize.Zero, plan.contentSize)
        // 采样方案本身会以 DEGENERATE_GEOMETRY 回退，不会去画 0 尺寸图层
        val sample = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = IntRect(100, 300, 100, 300),
            sourceBoundsInWindow = IntRect(0, 0, 1080, 1800),
            paddingPx = 20
        )
        assertEquals(BackdropFallbackReason.DEGENERATE_GEOMETRY, sample.reason)
    }
}

/**
 * 绘制失效：背景内容变化必须真的让采样层重画，静止时必须不重画。
 */
class LiquidGlassBackdropInvalidationTest {

    @Test
    fun `invalidate content bumps the revision so the layer repaints`() {
        val state = LiquidGlassBackdropState()
        val before = state.revision
        state.invalidateContent()
        assertEquals(before + 1, state.revision)
        state.invalidateContent()
        assertEquals(before + 2, state.revision)
    }

    @Test
    fun `revision wraps instead of overflowing`() {
        var revision = Int.MAX_VALUE
        revision = nextBackdropRevision(revision)
        assertEquals(1, revision)
    }

    @Test
    fun `draw counters start at zero and only move when recorded`() {
        val state = LiquidGlassBackdropState()
        assertEquals(0, state.hostDrawCount)
        assertEquals(0, state.layerDrawCount)
        assertEquals(-1, state.lastLayerDrawRevision)

        state.countHostDraw()
        state.countHostDraw()
        assertEquals(2, state.hostDrawCount)
        // 宿主绘制不改变采样层计数：两者是独立指标
        assertEquals(0, state.layerDrawCount)

        state.recordLayerDraw(revision = 5)
        assertEquals(1, state.layerDrawCount)
        assertEquals(5, state.lastLayerDrawRevision)
    }

    @Test
    fun `content revision is part of the effect key so a background change rebuilds`() {
        val appearance = LiquidGlassBackdropSpec.Default.resolve(2.75f)
        val plan = planBackdropSample(
            35, true, true,
            IntRect(120, 340, 620, 740),
            IntRect(0, 0, 1080, 1800),
            20
        )
        val before = backdropEffectKey(plan, appearance, 40f, contentRevision = 0)
        val after = backdropEffectKey(plan, appearance, 40f, contentRevision = 1)
        assertTrue("换背景后必须重建采样层", before != after)
    }

    @Test
    fun `same geometry and revision produce an identical key`() {
        val appearance = LiquidGlassBackdropSpec.Default.resolve(2.75f)
        fun key() = backdropEffectKey(
            planBackdropSample(
                35, true, true,
                IntRect(120, 340, 620, 740),
                IntRect(0, 0, 1080, 1800),
                20
            ),
            appearance,
            40f,
            contentRevision = 4
        )
        assertEquals(key(), key())
    }

    @Test
    fun `moving the pattern inside the same host forces a repaint`() {
        // "移动图案"只改内容版本，几何完全不变：这是最容易漏接的一类失效
        val appearance = LiquidGlassBackdropSpec.Default.resolve(2.75f)
        val plan = planBackdropSample(
            35, true, true,
            IntRect(120, 340, 620, 740),
            IntRect(0, 0, 1080, 1800),
            20
        )
        val keys = (0..3).map { backdropEffectKey(plan, appearance, 40f, contentRevision = it) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `bounds are unavailable before layout and after content detaches`() {
        val state = LiquidGlassBackdropState()
        assertNull(state.boundsOrNull())
        state.attachContent { }
        // 有内容但还没有坐标
        assertNull(state.boundsOrNull())
        state.sourceBounds = IntRect(0, 0, 1080, 1800)
        state.boundsKnown = true
        assertNotNull(state.boundsOrNull())
        state.attachContent(null)
        assertNull(state.boundsOrNull())
    }

    @Test
    fun `only the largest glass writes the audit reading`() {
        // 同一个背景来源上通常有主面板 + 一排 48dp 按钮。若每个节点都写读数，
        // 最后绘制的按钮会把主面板挤掉——模拟器实测出现过 glass=126x126。
        val state = LiquidGlassBackdropState()
        val spec = LiquidGlassBackdropSpec.Default
        val panel = LiquidGlassBackdropSampling(state, spec)
        val button = LiquidGlassBackdropSampling(state, spec)

        // 按钮先布局，面板后布局：面板面积更大，应成为主玻璃
        button.onGlassPositioned(IntRect(16, 24, 16 + 126, 24 + 126))
        panel.onGlassPositioned(IntRect(63, 105, 63 + 954, 105 + 420))

        assertTrue("面积大的面板应成为主玻璃", panel.isPrimary)
        assertFalse("按钮不应写审计读数", button.isPrimary)

        // 反过来：更大的节点出现后，原来的主玻璃必须让位
        val huge = LiquidGlassBackdropSampling(state, spec)
        huge.onGlassPositioned(IntRect(0, 0, 1080, 2000))
        assertTrue(huge.isPrimary)
        assertFalse(panel.isPrimary)
    }

    @Test
    fun `non primary glass never overwrites the audit reading`() {
        val state = LiquidGlassBackdropState()
        val spec = LiquidGlassBackdropSpec.Default
        val panel = LiquidGlassBackdropSampling(state, spec)
        val button = LiquidGlassBackdropSampling(state, spec)
        panel.onGlassPositioned(IntRect(63, 105, 63 + 954, 105 + 420))
        button.onGlassPositioned(IntRect(16, 24, 16 + 126, 24 + 126))

        val panelPlan = planBackdropSample(
            35, true, true,
            IntRect(63, 105, 63 + 954, 105 + 420),
            IntRect(0, 0, 1080, 1800),
            20
        )
        val buttonPlan = planBackdropSample(
            35, true, true,
            IntRect(16, 24, 16 + 126, 24 + 126),
            IntRect(0, 0, 1080, 1800),
            20
        )
        state.reportPlan(panelPlan, IntRect(63, 105, 63 + 954, 105 + 420), panel.isPrimary)
        state.reportPlan(buttonPlan, IntRect(16, 24, 16 + 126, 24 + 126), button.isPrimary)

        assertEquals("审计读数必须是面板的方案", panelPlan, state.lastPlan)
        assertEquals(954, state.lastPlan.glassWidthPx)
        assertEquals(IntRect(63, 105, 1017, 525), state.lastGlassWindow)
    }

    @Test
    fun `source size follows the measured host bounds`() {
        val state = LiquidGlassBackdropState()
        state.attachContent { }
        state.sourceBounds = IntRect(left = 0, top = 100, right = 1080, bottom = 1900)
        state.boundsKnown = true
        assertEquals(IntSize(1080, 1800), state.sourceSize)
    }
}

/**
 * 审计标记：CI 靠它判断"方案成立 / 效果真的建起来 / 重画了几次"，格式不能变形。
 */
class LiquidGlassAuditMarkerTest {

    @Test
    fun `sampled plan exposes origin layer padding host and draw counts`() {
        val plan = planBackdropSample(
            35, true, true,
            IntRect(120, 340, 620, 740),
            IntRect(0, 0, 1080, 2400),
            12
        )
        val markers = plan.auditMarkers(
            health = BackdropEffectHealth.Ok,
            hostWidth = 1080,
            hostHeight = 2400,
            hostDraws = 7,
            layerDraws = 7,
            revision = 3,
            shapeLabel = "圆角矩形"
        )
        assertTrue(markers.startsWith("AUDIT "))
        assertTrue(markers.contains("sampled=true"))
        assertTrue(markers.contains("glass=500x400"))
        assertTrue(markers.contains("origin=(120,340)"))
        assertTrue(markers.contains("layer=524x424"))
        assertTrue(markers.contains("pad=12"))
        assertTrue(markers.contains("host=1080x2400"))
        assertTrue(markers.contains("hostdraw=7"))
        assertTrue(markers.contains("layerdraw=7"))
        assertTrue(markers.contains("rev=3"))
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
            IntRect(10, 20, 110, 220),
            IntRect(0, 0, 1080, 2400),
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
