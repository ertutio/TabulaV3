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
    val enabled: Boolean = true
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
