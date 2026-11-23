package com.tabula.v3.ui.components.liquidglass

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 折射位移的几何契约：**只改位移时**，边缘发生预期位移、中心不发生额外偏移、
 * 玻璃外部与折射带外原样透传、采样点不越出背景来源（不采到玻璃自身）。
 *
 * 这些是纯几何结论，可以在 JVM 上逐点算出来；不必等真机截图，也不会被"两张
 * 截图碰巧相同"误导。模型与 `LIQUID_GLASS_BACKDROP_SHADER` 的 `main()` 同式，
 * shader 里改了衰减曲线而这里没同步时，这组断言会先红。
 */
class LiquidGlassBackdropRefractionTest {

    private val glassWidth = 500f
    private val glassHeight = 400f
    private val cornerRadius = 40f
    private val source = IntRect(0, 0, 1080, 1800)

    private fun appearance(amountDp: Float, heightDp: Float = 18f, blurDp: Float = 20f) =
        LiquidGlassBackdropSpec(
            refractionHeightDp = heightDp.dp,
            refractionAmountDp = amountDp.dp,
            blurRadiusDp = blurDp.dp
        ).resolve(2.75f)

    private fun plan(paddingPx: Int = 20) = planBackdropSample(
        sdkInt = 35,
        hasBackdropSource = true,
        isRoundedRectShape = true,
        glassBoundsInWindow = IntRect(120, 340, 120 + 500, 340 + 400),
        sourceBoundsInWindow = source,
        paddingPx = paddingPx
    )

    // ===== 1. 中心不动 =====

    @Test
    fun `center of the glass is displaced by exactly zero`() {
        val weak = appearance(amountDp = 2f)
        val strong = appearance(amountDp = 10f)
        val centerX = glassWidth / 2f
        val centerY = glassHeight / 2f

        listOf(weak, strong).forEach { spec ->
            val sample = backdropRefractionAt(
                centerX, centerY, glassWidth, glassHeight, cornerRadius, spec
            )
            assertTrue(sample.insideGlass)
            assertFalse("中心不在折射带内", sample.insideRefractionBand)
            assertEquals(0f, sample.offsetX, 0.0001f)
            assertEquals(0f, sample.offsetY, 0.0001f)
            assertEquals(0f, sample.displacementPx, 0.0001f)
        }
    }

    @Test
    fun `only changing the displacement leaves the center pixel identical`() {
        val weak = appearance(amountDp = 2f)
        val strong = appearance(amountDp = 20f)
        // 中心：两种位移下采样点都必须等于自身（否则中心会被"额外偏移"）
        val localX = glassWidth / 2f
        val localY = glassHeight / 2f
        val a = backdropRefractionAt(localX, localY, glassWidth, glassHeight, cornerRadius, weak)
        val b = backdropRefractionAt(localX, localY, glassWidth, glassHeight, cornerRadius, strong)
        assertEquals(a.offsetX, b.offsetX, 0.0001f)
        assertEquals(a.offsetY, b.offsetY, 0.0001f)
        assertEquals(0f, b.displacementPx, 0.0001f)
    }

    // ===== 2. 边缘发生预期位移 =====

    @Test
    fun `top edge is displaced downward into the glass`() {
        val spec = appearance(amountDp = 10f)
        // 上边缘内侧 1px
        val sample = backdropRefractionAt(
            glassWidth / 2f, 1f, glassWidth, glassHeight, cornerRadius, spec
        )
        assertTrue(sample.insideGlass)
        assertTrue(sample.insideRefractionBand)
        // 位移方向是"把背景向内掰"：上边缘处 offset.y 必须为正（向下）
        assertTrue("上边缘必须向下位移，实际 ${sample.offsetY}", sample.offsetY > 1f)
        // 水平方向无位移（远离圆角）
        assertEquals(0f, sample.offsetX, 0.5f)
    }

    @Test
    fun `left edge is displaced rightward into the glass`() {
        val spec = appearance(amountDp = 10f)
        val sample = backdropRefractionAt(
            1f, glassHeight / 2f, glassWidth, glassHeight, cornerRadius, spec
        )
        assertTrue(sample.offsetX > 1f)
        assertEquals(0f, sample.offsetY, 0.5f)
    }

    @Test
    fun `bottom edge is displaced upward into the glass`() {
        val spec = appearance(amountDp = 10f)
        val sample = backdropRefractionAt(
            glassWidth / 2f, glassHeight - 1f, glassWidth, glassHeight, cornerRadius, spec
        )
        assertTrue("下边缘必须向上位移", sample.offsetY < -1f)
    }

    @Test
    fun `right edge is displaced leftward into the glass`() {
        val spec = appearance(amountDp = 10f)
        val sample = backdropRefractionAt(
            glassWidth - 1f, glassHeight / 2f, glassWidth, glassHeight, cornerRadius, spec
        )
        assertTrue("右边缘必须向左位移", sample.offsetX < -1f)
    }

    @Test
    fun `displacement grows monotonically toward the edge`() {
        val spec = appearance(amountDp = 10f, heightDp = 18f) // 带 = 49.5px
        val distances = listOf(2f, 10f, 20f, 35f, 45f)
        val strengths = distances.map { dy ->
            backdropRefractionAt(
                glassWidth / 2f, dy, glassWidth, glassHeight, cornerRadius, spec
            ).displacementPx
        }
        // 越靠边缘位移越大（列表顺序就是从边缘往里）
        strengths.zipWithNext().forEach { (near, far) ->
            assertTrue("位移必须向内单调衰减：near=$near far=$far", near > far)
        }
    }

    @Test
    fun `displacement is proportional to the configured amount`() {
        val weak = appearance(amountDp = 5f)
        val strong = appearance(amountDp = 10f)
        val y = 4f
        val weakSample = backdropRefractionAt(
            glassWidth / 2f, y, glassWidth, glassHeight, cornerRadius, weak
        )
        val strongSample = backdropRefractionAt(
            glassWidth / 2f, y, glassWidth, glassHeight, cornerRadius, strong
        )
        assertEquals(2f, strongSample.displacementPx / weakSample.displacementPx, 0.02f)
    }

    @Test
    fun `max displacement never exceeds the configured amount`() {
        val spec = appearance(amountDp = 10f)
        val maxAmount = spec.refractionAmountPx
        var checked = 0
        var y = 0.5f
        while (y < glassHeight) {
            var x = 0.5f
            while (x < glassWidth) {
                val sample = backdropRefractionAt(x, y, glassWidth, glassHeight, cornerRadius, spec)
                assertTrue(
                    "位移不得超过上限：${sample.displacementPx} > $maxAmount at ($x,$y)",
                    sample.displacementPx <= maxAmount + 0.01f
                )
                checked++
                x += 7f
            }
            y += 7f
        }
        assertTrue("扫描点太少", checked > 1000)
    }

    @Test
    fun `corners displace diagonally inward`() {
        val spec = appearance(amountDp = 10f)
        // 左上圆角：圆角半径 40，圆弧圆心在 (40,40)，弧上的点是 (40-40/√2, 40-40/√2)。
        // 取弧内一点 (14,14)（SDF 略负、位于折射带内）——注意 (2,2) 已经被圆角切掉，
        // 落在玻璃外部，那里本来就不该有位移。
        val cornerX = 14f
        val cornerY = 14f
        assertTrue(
            "取样点必须在玻璃内部",
            roundedRectSdf(cornerX, cornerY, glassWidth, glassHeight, cornerRadius) < 0f
        )
        val sample = backdropRefractionAt(
            cornerX, cornerY, glassWidth, glassHeight, cornerRadius, spec
        )
        assertTrue(sample.insideRefractionBand)
        // 圆角处的 SDF 梯度指向左上方，位移反向 → 右下（向内）
        assertTrue("圆角处必须斜向内位移 x=${sample.offsetX}", sample.offsetX > 0f)
        assertTrue("圆角处必须斜向内位移 y=${sample.offsetY}", sample.offsetY > 0f)
        // 两个分量应当接近（45° 方向），而不是贴边那种单轴位移
        assertEquals(1f, sample.offsetX / sample.offsetY, 0.15f)
    }

    @Test
    fun `points cut away by the rounded corner are outside the glass`() {
        // 圆角把方形角落切掉：这正是"圆形/圆角形状"不能按矩形算采样的原因
        assertTrue(roundedRectSdf(2f, 2f, glassWidth, glassHeight, cornerRadius) > 0f)
        val sample = backdropRefractionAt(2f, 2f, glassWidth, glassHeight, cornerRadius, appearance(10f))
        assertFalse(sample.insideGlass)
        assertEquals(0f, sample.displacementPx, 0.0001f)
    }

    // ===== 3. 外部与带外原样透传 =====

    @Test
    fun `outside the glass is passed through untouched`() {
        val spec = appearance(amountDp = 20f)
        val outside = listOf(
            -10f to glassHeight / 2f,
            glassWidth + 10f to glassHeight / 2f,
            glassWidth / 2f to -10f,
            glassWidth / 2f to glassHeight + 10f
        )
        outside.forEach { (x, y) ->
            val sample = backdropRefractionAt(x, y, glassWidth, glassHeight, cornerRadius, spec)
            assertFalse("($x,$y) 应在玻璃外部", sample.insideGlass)
            assertEquals(0f, sample.displacementPx, 0.0001f)
        }
    }

    @Test
    fun `outside the refraction band is passed through untouched`() {
        val spec = appearance(amountDp = 10f, heightDp = 10f) // 带 = 27.5px
        val y = spec.refractionHeightPx + 5f
        val sample = backdropRefractionAt(
            glassWidth / 2f, y, glassWidth, glassHeight, cornerRadius, spec
        )
        assertTrue(sample.insideGlass)
        assertFalse(sample.insideRefractionBand)
        assertEquals(0f, sample.displacementPx, 0.0001f)
    }

    @Test
    fun `zero amount disables displacement everywhere`() {
        val spec = appearance(amountDp = 0f)
        var y = 0.5f
        while (y < glassHeight) {
            var x = 0.5f
            while (x < glassWidth) {
                val sample = backdropRefractionAt(x, y, glassWidth, glassHeight, cornerRadius, spec)
                assertEquals(0f, sample.displacementPx, 0.0001f)
                x += 23f
            }
            y += 23f
        }
    }

    @Test
    fun `zero band disables displacement everywhere`() {
        val spec = appearance(amountDp = 10f, heightDp = 0f)
        val sample = backdropRefractionAt(1f, 1f, glassWidth, glassHeight, cornerRadius, spec)
        assertEquals(0f, sample.displacementPx, 0.0001f)
    }

    // ===== 4. 不采到玻璃自身 / 边缘采样余量 =====

    @Test
    fun `every displaced sample stays inside the background source`() {
        val spec = appearance(amountDp = 10f)
        val p = plan(paddingPx = backdropPaddingPx(spec))
        var checked = 0
        var y = 0.5f
        while (y < glassHeight) {
            var x = 0.5f
            while (x < glassWidth) {
                val sample = backdropRefractionAt(x, y, glassWidth, glassHeight, cornerRadius, spec)
                val inside = isSampleInsideSource(
                    plan = p,
                    sourceWidthPx = source.width,
                    sourceHeightPx = source.height,
                    localX = x,
                    localY = y,
                    offsetX = sample.offsetX,
                    offsetY = sample.offsetY
                )
                assertTrue("采样点越界 at ($x,$y) offset=(${sample.offsetX},${sample.offsetY})", inside)
                checked++
                x += 11f
            }
            y += 11f
        }
        assertTrue(checked > 400)
    }

    @Test
    fun `displacement moves the sample point inward by exactly the offset`() {
        val spec = appearance(amountDp = 10f)
        val p = plan(paddingPx = 20)
        val y = 3f
        val x = glassWidth / 2f
        val sample = backdropRefractionAt(x, y, glassWidth, glassHeight, cornerRadius, spec)

        val without = backdropSamplePointInSource(p, x, y, 0f, 0f)
        val with = backdropSamplePointInSource(p, x, y, sample.offsetX, sample.offsetY)
        // 采样点在"来源坐标"里：玻璃原点 (120,340) + 局部坐标
        assertEquals(120f + x, without.first, 0.001f)
        assertEquals(340f + y, without.second, 0.001f)
        assertEquals(sample.offsetX, with.first - without.first, 0.001f)
        assertEquals(sample.offsetY, with.second - without.second, 0.001f)
    }

    @Test
    fun `padding covers the largest possible inward displacement plus the blur kernel`() {
        // 位移是向内的，理论上不会越界；但模糊核会在图层边缘取到外面，
        // 因此外扩必须至少覆盖半个模糊核。
        val spec = appearance(amountDp = 10f, blurDp = 20f)
        val pad = backdropPaddingPx(spec)
        assertTrue(
            "外扩 ${pad}px 不足以覆盖半个模糊核 ${spec.blurRadiusPx / 2f}px",
            pad >= (spec.blurRadiusPx / 2f) - 0.5f
        )
    }

    @Test
    fun `plan rejects a glass that would need sampling outside the source`() {
        // 玻璃贴着来源上边缘：即使外扩为 0 也无法完整采样
        val rejected = planBackdropSample(
            sdkInt = 35,
            hasBackdropSource = true,
            isRoundedRectShape = true,
            glassBoundsInWindow = IntRect(0, 0, 500, 400),
            sourceBoundsInWindow = IntRect(0, 0, 1080, 1800),
            paddingPx = 20
        )
        assertEquals(BackdropFallbackReason.OUT_OF_SOURCE_BOUNDS, rejected.reason)
    }

    // ===== 5. 与网格对齐：边缘位移、中心不动 =====

    @Test
    fun `high contrast grid line shifts at the edge but not at the center`() {
        // 网格：每 72px 一条竖线。把"采样点"映射回网格相位，看它是否跨过一条线。
        val cell = 72f
        val spec = appearance(amountDp = 10f)
        val p = plan(paddingPx = 20)

        fun gridPhaseAt(sourceX: Float): Float = ((sourceX % cell) + cell) % cell

        // 左边缘：水平位移最大，网格相位必须改变
        val edgeX = 1f
        val edgeY = glassHeight / 2f
        val edgeSample = backdropRefractionAt(edgeX, edgeY, glassWidth, glassHeight, cornerRadius, spec)
        val edgeBase = backdropSamplePointInSource(p, edgeX, edgeY, 0f, 0f)
        val edgeShifted = backdropSamplePointInSource(
            p, edgeX, edgeY, edgeSample.offsetX, edgeSample.offsetY
        )
        val edgePhaseDelta = abs(gridPhaseAt(edgeShifted.first) - gridPhaseAt(edgeBase.first))
        assertTrue("左边缘必须改变网格相位，实际 $edgePhaseDelta", edgePhaseDelta > 5f)

        // 中心：位移为 0，网格相位必须完全不变
        val centerX = glassWidth / 2f
        val centerY = glassHeight / 2f
        val centerSample = backdropRefractionAt(
            centerX, centerY, glassWidth, glassHeight, cornerRadius, spec
        )
        val centerBase = backdropSamplePointInSource(p, centerX, centerY, 0f, 0f)
        val centerShifted = backdropSamplePointInSource(
            p, centerX, centerY, centerSample.offsetX, centerSample.offsetY
        )
        assertEquals(0f, centerShifted.first - centerBase.first, 0.0001f)
        assertEquals(0f, centerShifted.second - centerBase.second, 0.0001f)
    }

    @Test
    fun `changing only the displacement moves the edge sample but leaves blur and sampling identical`() {
        // 对照测试的前提：除位移外，其它参数一律相同
        val weak = LiquidGlassBackdropSpec(
            refractionHeightDp = 18.dp,
            refractionAmountDp = 2.dp,
            dispersion = 0.12f,
            blurRadiusDp = 20.dp
        ).resolve(2.75f)
        val strong = weak.copy(refractionAmountPx = 10f * 2.75f)

        assertEquals(weak.refractionHeightPx, strong.refractionHeightPx, 0.0001f)
        assertEquals(weak.blurRadiusPx, strong.blurRadiusPx, 0.0001f)
        assertEquals(weak.dispersion, strong.dispersion, 0.0001f)

        // 边缘：位移明显不同
        val edgeWeak = backdropRefractionAt(1f, glassHeight / 2f, glassWidth, glassHeight, cornerRadius, weak)
        val edgeStrong = backdropRefractionAt(1f, glassHeight / 2f, glassWidth, glassHeight, cornerRadius, strong)
        assertTrue(
            "边缘位移必须随参数变化：${edgeWeak.displacementPx} vs ${edgeStrong.displacementPx}",
            edgeStrong.displacementPx - edgeWeak.displacementPx > 10f
        )

        // 中心：两者都必须是 0（不受位移参数影响）
        assertEquals(
            0f,
            backdropRefractionAt(glassWidth / 2f, glassHeight / 2f, glassWidth, glassHeight, cornerRadius, strong)
                .displacementPx,
            0.0001f
        )
    }

    @Test
    fun `refraction band never reaches the glass center for panel sized specs`() {
        // 折射带必须窄于半个短边，否则"中心不偏移"这条前提不成立
        val spec = appearance(amountDp = 10f)
        assertTrue(spec.refractionHeightPx < glassHeight / 2f)
    }

    @Test
    fun `refraction band stays narrow even at the densest density`() {
        // 折射带必须随密度线性放大，且在高密度下仍远窄于玻璃短边：
        // 否则"只在边缘弯折、中心不动"的几何前提会被破坏。
        val densest = LiquidGlassBackdropSpec.Default.resolve(4f)
        val glassShortSide = 400f
        assertTrue(
            "折射带 ${densest.refractionHeightPx}px 已接近短边一半 ${glassShortSide / 2f}px",
            densest.refractionHeightPx < glassShortSide / 2f
        )
        // 位移必须小于折射带，保证位移不超过带内深度
        assertTrue(densest.refractionAmountPx < densest.refractionHeightPx)
    }

    // ===== 6. 数值稳定性 =====

    @Test
    fun `sdf is zero on the edge and negative inside`() {
        assertEquals(0f, roundedRectSdf(0f, glassHeight / 2f, glassWidth, glassHeight, cornerRadius), 0.01f)
        assertEquals(0f, roundedRectSdf(glassWidth, glassHeight / 2f, glassWidth, glassHeight, cornerRadius), 0.01f)
        assertTrue(roundedRectSdf(glassWidth / 2f, glassHeight / 2f, glassWidth, glassHeight, cornerRadius) < -100f)
    }

    @Test
    fun `gradient is unit length away from the flat edges`() {
        val (gx, gy) = roundedRectSdfGradient(1f, glassHeight / 2f, glassWidth, glassHeight, cornerRadius)
        assertEquals(1f, hypot(gx, gy), 0.01f)
    }

    @Test
    fun `circle shape produces the same displacement law`() {
        // 圆形 = 四角半径一致且取到短边一半；这里验证它不会出现奇点
        val square = 300f
        val radius = square / 2f
        val spec = appearance(amountDp = 10f)
        val sample = backdropRefractionAt(square / 2f, 1f, square, square, radius, spec)
        assertTrue(sample.insideGlass)
        assertTrue(sample.displacementPx > 0f)
        assertTrue(sample.displacementPx <= spec.refractionAmountPx + 0.01f)
        // 圆心位移为 0
        assertEquals(
            0f,
            backdropRefractionAt(square / 2f, square / 2f, square, square, radius, spec).displacementPx,
            0.0001f
        )
    }
}

/**
 * "只改位移"对照测试的前置条件：采样范围、模糊、背景、几何都不变。
 *
 * 若有人为了做出差异而顺手改了模糊或采样范围，这些断言会先红——那种差异不能
 * 算作"折射生效"。
 */
class LiquidGlassDisplacementOnlyControlTest {

    private val source = IntRect(0, 0, 1080, 1800)
    private val glass = IntRect(120, 340, 620, 740)

    @Test
    fun `plan is identical for both displacement settings`() {
        val padding = backdropPaddingPx(
            LiquidGlassBackdropSpec.Default.copy(refractionAmountDp = 10.dp).resolve(2.75f)
        )
        val a = planBackdropSample(35, true, true, glass, source, padding)
        val b = planBackdropSample(35, true, true, glass, source, padding)
        assertEquals(a, b)
    }

    @Test
    fun `blur radius is untouched by the displacement setting`() {
        val weak = LiquidGlassBackdropSpec.Default.copy(refractionAmountDp = 2.dp).resolve(2.75f)
        val strong = LiquidGlassBackdropSpec.Default.copy(refractionAmountDp = 20.dp).resolve(2.75f)
        assertEquals(weak.blurRadiusPx, strong.blurRadiusPx, 0.0001f)
        assertEquals(weak.refractionHeightPx, strong.refractionHeightPx, 0.0001f)
        assertEquals(weak.dispersion, strong.dispersion, 0.0001f)
    }

    @Test
    fun `displacement comparison keeps the production blur untouched`() {
        // 对照测试要把模糊压小（否则 72px 网格被抹成纯色，位移看不出差异），
        // 但**生产参数不能被改**：两条断言一起锁住，避免有人为了让测试出数
        // 就把 Default 的模糊改小。
        assertEquals(
            "生产默认模糊必须保持 20dp",
            20f,
            LiquidGlassBackdropSpec.Default.blurRadiusDp.value,
            0.001f
        )
        val comparison = LiquidGlassBackdropSpec.Default.forDisplacementComparison()
        assertTrue(
            "对照用的模糊必须比生产小",
            comparison.blurRadiusDp.value < LiquidGlassBackdropSpec.Default.blurRadiusDp.value
        )
        // 除模糊外一切不变，否则"只改位移"的前提不成立
        assertEquals(
            LiquidGlassBackdropSpec.Default.refractionHeightDp,
            comparison.refractionHeightDp
        )
        assertEquals(
            LiquidGlassBackdropSpec.Default.refractionAmountDp,
            comparison.refractionAmountDp
        )
        assertEquals(
            LiquidGlassBackdropSpec.Default.dispersion,
            comparison.dispersion,
            0.001f
        )
        assertEquals(LiquidGlassBackdropSpec.Default.enabled, comparison.enabled)
    }

    @Test
    fun `padding is stable across displacement settings`() {
        // 外扩由"模糊核与位移中的较大者"决定；两者都在上限内时它不该随位移抖动，
        // 否则对照测试里采样范围会变，"只改位移"就不成立了。
        val weak = LiquidGlassBackdropSpec.Default.copy(
            refractionAmountDp = 2.dp,
            blurRadiusDp = 20.dp
        ).resolve(2.75f)
        val strong = LiquidGlassBackdropSpec.Default.copy(
            refractionAmountDp = 10.dp,
            blurRadiusDp = 20.dp
        ).resolve(2.75f)
        assertEquals(backdropPaddingPx(weak), backdropPaddingPx(strong))
    }
}
