package com.tabula.v3.ui.components.liquidglass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃背景采样层的外观参数。
 *
 * 这些参数只影响新增的采样层；`PhysicalLiquidGlassConfig` 里的高光、边缘、
 * 内阴影、噪点和形状完全不受影响，两者互不覆盖。
 *
 * 参数命名沿用参考实现里的 `refractionHeight` / `refractionAmount` 语义，
 * 便于和外部资料逐项对照：
 * - [refractionHeightPx]：折射带宽度（像素），只在这条窄带内做位移；
 * - [refractionAmountPx]：最大位移（像素）；
 * - [dispersion]：RGB 采样位移的分离比例（色散）；
 * - [blurRadiusPx]：采样前的模糊半径，先模糊再折射。
 */
@Immutable
data class LiquidGlassBackdropSpec(
    val refractionHeightDp: Dp = 18.dp,
    val refractionAmountDp: Dp = 10.dp,
    val dispersion: Float = 0.12f,
    val blurRadiusDp: Dp = 20.dp,
    val enabled: Boolean = true,
    /**
     * 诊断开关：把 renderEffect 换成一个大半径模糊。
     *
     * 用途是区分两类失败：模糊出现说明"renderEffect 渲染路径正常、问题在 shader
     * 逻辑"，模糊不出现说明效果根本没被应用。只在调试预览里打开，不参与生产。
     */
    val diagnosticBlurOnly: Boolean = false
) {
    companion object {
        val Default = LiquidGlassBackdropSpec()

        /** 关闭采样层：完全回到原有材质路径，用于对照与回退。 */
        val Off = LiquidGlassBackdropSpec(enabled = false)
    }
}

/**
 * 解析后的像素参数，供 shader 与单测使用。
 */
data class LiquidGlassBackdropAppearance(
    val refractionHeightPx: Float,
    val refractionAmountPx: Float,
    val dispersion: Float,
    val blurRadiusPx: Float
)

fun LiquidGlassBackdropSpec.resolve(density: Float): LiquidGlassBackdropAppearance =
    LiquidGlassBackdropAppearance(
        refractionHeightPx = (refractionHeightDp.value * density).coerceAtLeast(0f),
        refractionAmountPx = (refractionAmountDp.value * density).coerceAtLeast(0f),
        dispersion = dispersion.coerceIn(0f, 1f),
        blurRadiusPx = (blurRadiusDp.value * density).coerceAtLeast(0f)
    )

/**
 * "只改位移"对照测试用的采样参数。
 *
 * 面板默认的 20dp 模糊（2.75x 下 55px）会把 72px 网格抹成近乎纯色，于是
 * **边缘位移再大也看不出差异**——模拟器取证里"位移 2dp vs 12dp"在玻璃区域内
 * 是 0 像素变化（`changed_pixels=0`），就是被模糊抹平的。模糊本身是对照的
 * 不变量（两组必须相同），所以只能在**对照测试**里把模糊压小，让位移成为
 * 唯一可见的变量。
 *
 * 生产参数不受影响：`LiquidGlassBackdropSpec.Default` 仍是 20dp 模糊。
 * 这一点很重要——不能为了让测试出数就把生产模糊改小。
 */
fun LiquidGlassBackdropSpec.forDisplacementComparison(): LiquidGlassBackdropSpec =
    copy(blurRadiusDp = 2.dp)
