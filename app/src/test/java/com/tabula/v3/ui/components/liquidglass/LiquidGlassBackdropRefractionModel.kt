package com.tabula.v3.ui.components.liquidglass

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 折射位移场的**纯 Kotlin 模型**，与 `LIQUID_GLASS_BACKDROP_SHADER` 里的
 * `main()` 一一对应。
 *
 * 存在的理由：shader 只能在设备上跑，而"边缘是否发生预期位移、中心是否完全不动、
 * 只改位移时其它一切不变"这三件事是**几何结论**，可以在 JVM 上逐点验证。
 * 一旦有人改 shader 里的衰减曲线或位移方向却忘了同步，这组断言会先红。
 *
 * 模型与 shader 的对应关系：
 * - `sd`：圆角矩形 SDF（局部坐标，原点在玻璃左上角）；
 * - `distToEdge = -sd`，`t = clamp(distToEdge / refractionHeight, 0, 1)`；
 * - `strength = (1 - t)^2`，越靠边缘越大、向内衰减到 0；
 * - `dir = normalize(∇sd)`，指向玻璃外侧；
 * - `offset = -dir * strength * refractionAmount`（把背景"向内掰"）。
 */
data class BackdropRefractionSample(
    /** 采样点是否在玻璃内部（外部一律原样透传） */
    val insideGlass: Boolean,
    /** 是否落在折射带内（带外原样透传，位移为 0） */
    val insideRefractionBand: Boolean,
    /** 到玻璃边缘的距离（像素，内部为正） */
    val distanceToEdgePx: Float,
    /** 归一化强度 0..1 */
    val strength: Float,
    /** 实际采样位移（像素）；正方向与屏幕坐标一致 */
    val offsetX: Float,
    val offsetY: Float
) {
    val displacementPx: Float get() = hypot(offsetX, offsetY)
}

/**
 * 圆角矩形 SDF（与 shader 里的 `sdRoundedRect` 同式）。
 *
 * @param localX/localY 相对玻璃左上角的坐标
 * @param glassWidth/glassHeight 玻璃尺寸
 * @param cornerRadius 圆角半径（已夹到短边一半以内）
 */
fun roundedRectSdf(
    localX: Float,
    localY: Float,
    glassWidth: Float,
    glassHeight: Float,
    cornerRadius: Float
): Float {
    val halfW = glassWidth / 2f
    val halfH = glassHeight / 2f
    val radius = cornerRadius.coerceIn(0f, min(halfW, halfH))
    val px = localX - halfW
    val py = localY - halfH
    val qx = abs(px) - halfW + radius
    val qy = abs(py) - halfH + radius
    return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - radius
}

/** SDF 梯度（中心差分，与 shader 的 `e = 0.75` 一致）。 */
fun roundedRectSdfGradient(
    localX: Float,
    localY: Float,
    glassWidth: Float,
    glassHeight: Float,
    cornerRadius: Float
): Pair<Float, Float> {
    val e = 0.75f
    val dx = (
        roundedRectSdf(localX + e, localY, glassWidth, glassHeight, cornerRadius) -
            roundedRectSdf(localX - e, localY, glassWidth, glassHeight, cornerRadius)
        ) / (2f * e)
    val dy = (
        roundedRectSdf(localX, localY + e, glassWidth, glassHeight, cornerRadius) -
            roundedRectSdf(localX, localY - e, glassWidth, glassHeight, cornerRadius)
        ) / (2f * e)
    return dx to dy
}

/**
 * 计算某个**玻璃局部坐标**处的折射位移。
 *
 * 入参全部是像素，与 shader 的 uniform 语义一致，因此测试里可以直接拿方案
 * （`BackdropSamplePlan`）的尺寸与外观参数调用它。
 */
fun backdropRefractionAt(
    localX: Float,
    localY: Float,
    glassWidth: Float,
    glassHeight: Float,
    cornerRadius: Float,
    appearance: LiquidGlassBackdropAppearance
): BackdropRefractionSample {
    val sd = roundedRectSdf(localX, localY, glassWidth, glassHeight, cornerRadius)
    val inside = sd <= 0f
    val distToEdge = -sd

    val band = appearance.refractionHeightPx
    val amount = appearance.refractionAmountPx
    if (!inside || band <= 0.0001f || amount <= 0.0001f) {
        return BackdropRefractionSample(
            insideGlass = inside,
            insideRefractionBand = false,
            distanceToEdgePx = distToEdge,
            strength = 0f,
            offsetX = 0f,
            offsetY = 0f
        )
    }
    if (distToEdge >= band) {
        return BackdropRefractionSample(
            insideGlass = true,
            insideRefractionBand = false,
            distanceToEdgePx = distToEdge,
            strength = 0f,
            offsetX = 0f,
            offsetY = 0f
        )
    }

    val t = (distToEdge / band).coerceIn(0f, 1f)
    val strength = (1f - t).pow(2)
    val (gx, gy) = roundedRectSdfGradient(localX, localY, glassWidth, glassHeight, cornerRadius)
    val len = hypot(gx, gy)
    val dirX = if (len > 0.0001f) gx / len else 0f
    val dirY = if (len > 0.0001f) gy / len else 1f

    return BackdropRefractionSample(
        insideGlass = true,
        insideRefractionBand = true,
        distanceToEdgePx = distToEdge,
        strength = strength,
        offsetX = -dirX * strength * amount,
        offsetY = -dirY * strength * amount
    )
}

/**
 * 把"玻璃局部坐标"换算成"背景来源坐标"，用于验证采样点落在背景的哪一处。
 *
 * 采样点 = 图层坐标 - 平移量，其中图层坐标 = 玻璃局部 + padding。
 */
fun backdropSamplePointInSource(
    plan: BackdropSamplePlan,
    localX: Float,
    localY: Float,
    offsetX: Float,
    offsetY: Float
): Pair<Float, Float> {
    val layerX = localX + plan.paddingPx + offsetX
    val layerY = localY + plan.paddingPx + offsetY
    return (layerX - plan.sourceTranslateX) to (layerY - plan.sourceTranslateY)
}

/**
 * 采样点是否仍在"背景来源"内（越界会被 clamp 复制边缘像素，那正是"采到玻璃自身
 * 或黑边"的来源，必须为 false）。
 */
fun isSampleInsideSource(
    plan: BackdropSamplePlan,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    localX: Float,
    localY: Float,
    offsetX: Float = 0f,
    offsetY: Float = 0f
): Boolean {
    val (x, y) = backdropSamplePointInSource(plan, localX, localY, offsetX, offsetY)
    return x >= 0f && y >= 0f && x <= sourceWidthPx && y <= sourceHeightPx
}
