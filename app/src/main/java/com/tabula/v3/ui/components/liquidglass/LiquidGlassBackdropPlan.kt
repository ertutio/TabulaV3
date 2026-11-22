package com.tabula.v3.ui.components.liquidglass

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect

/**
 * 液态玻璃"背景采样层"的纯几何决策与坐标对齐。
 *
 * 这一层不碰任何 Android 图形 API，只回答三个可审计的问题：
 * 1. 这次到底走背景采样，还是沿用原有效果；
 * 2. 采样图层开多大（离屏范围只覆盖玻璃节点本身 + 采样外扩，不含整屏）；
 * 3. 背景内容在采样图层里平移多少像素，玻璃区域才与背景对齐。
 *
 * 原型只接受圆角矩形；其它形状、坐标未就绪、或采样范围会越过背景来源时
 * 一律回退到 `PhysicalLiquidGlassBox` 原有材质，避免"采到玻璃自身"或闪黑。
 */
enum class BackdropSamplingStatus {
    /** 使用背景采样层（新增效果） */
    SAMPLED,

    /** 沿用原有材质，不绘制背景采样层 */
    ORIGINAL
}

/** 回退原因：写进调试预览与测试断言，便于审计"这次为什么没走采样"。 */
enum class BackdropFallbackReason {
    NONE,

    /** 调用方显式关闭 */
    DISABLED,

    /** 调用方没有提供背景 */
    NO_BACKDROP,

    /** 系统版本低于背景采样门槛 */
    UNSUPPORTED_API,

    /** 非圆角矩形，原型不处理 */
    UNSUPPORTED_SHAPE,

    /** 还没有拿到布局坐标（首帧） */
    NO_COORDINATES,

    /** 玻璃节点或背景尺寸为零 */
    DEGENERATE_GEOMETRY,

    /** 采样范围（含外扩）超出背景来源，无法完整采样 */
    OUT_OF_SOURCE_BOUNDS
}

/**
 * 一次采样的完整几何方案。所有坐标都是整像素，避免累积到半像素后闪边。
 *
 * 采样图层与玻璃节点的关系：
 * - 图层尺寸 = 玻璃尺寸 + 2 × [paddingPx]；
 * - 玻璃内容在图层内固定在 `(paddingPx, paddingPx)`；
 * - 背景内容在图层内平移 [sourceTranslateX] / [sourceTranslateY]；
 * - 图层回绘到玻璃节点时，反向平移 `-paddingPx`。
 */
data class BackdropSamplePlan(
    val status: BackdropSamplingStatus,
    val reason: BackdropFallbackReason,
    val layerWidthPx: Int,
    val layerHeightPx: Int,
    val paddingPx: Int,
    val sourceTranslateX: Float,
    val sourceTranslateY: Float,
    val glassWidthPx: Int,
    val glassHeightPx: Int,
    /** 玻璃左上角在背景来源坐标系中的像素位置，用于调试与回归断言 */
    val glassOriginInSource: IntOffset
) {
    val useSampledBackdrop: Boolean get() = status == BackdropSamplingStatus.SAMPLED

    companion object {
        fun original(reason: BackdropFallbackReason): BackdropSamplePlan = BackdropSamplePlan(
            status = BackdropSamplingStatus.ORIGINAL,
            reason = reason,
            layerWidthPx = 0,
            layerHeightPx = 0,
            paddingPx = 0,
            sourceTranslateX = 0f,
            sourceTranslateY = 0f,
            glassWidthPx = 0,
            glassHeightPx = 0,
            glassOriginInSource = IntOffset.Zero
        )
    }
}

/** 背景采样所要求的最低系统版本：RuntimeShader + RenderEffect 链生效的版本。 */
const val LIQUID_GLASS_BACKDROP_MIN_SDK: Int = 33

/**
 * 生成采样方案。入参全部显式传入，便于在 JVM 单测里覆盖每种回退分支。
 *
 * @param sdkInt 当前系统版本
 * @param hasBackdropSource 调用方是否提供了背景来源
 * @param isRoundedRectShape 形状是否为圆角矩形（原型限定）
 * @param glassBoundsInWindow 玻璃节点相对窗口的像素矩形；`null` 表示坐标尚未就绪
 * @param sourceBoundsInWindow 背景来源相对窗口的像素矩形
 * @param paddingPx 采样外扩像素，给边缘折射留取样余量
 */
fun planBackdropSample(
    sdkInt: Int,
    hasBackdropSource: Boolean,
    isRoundedRectShape: Boolean,
    glassBoundsInWindow: IntRect?,
    sourceBoundsInWindow: IntRect?,
    paddingPx: Int,
    enabled: Boolean = true
): BackdropSamplePlan {
    if (!enabled) return BackdropSamplePlan.original(BackdropFallbackReason.DISABLED)
    if (sdkInt < LIQUID_GLASS_BACKDROP_MIN_SDK) {
        return BackdropSamplePlan.original(BackdropFallbackReason.UNSUPPORTED_API)
    }
    if (!hasBackdropSource) return BackdropSamplePlan.original(BackdropFallbackReason.NO_BACKDROP)
    if (!isRoundedRectShape) return BackdropSamplePlan.original(BackdropFallbackReason.UNSUPPORTED_SHAPE)
    if (glassBoundsInWindow == null || sourceBoundsInWindow == null) {
        return BackdropSamplePlan.original(BackdropFallbackReason.NO_COORDINATES)
    }
    if (sourceBoundsInWindow.width <= 0 || sourceBoundsInWindow.height <= 0) {
        return BackdropSamplePlan.original(BackdropFallbackReason.DEGENERATE_GEOMETRY)
    }
    if (glassBoundsInWindow.width <= 0 || glassBoundsInWindow.height <= 0) {
        return BackdropSamplePlan.original(BackdropFallbackReason.DEGENERATE_GEOMETRY)
    }

    val safePadding = paddingPx.coerceAtLeast(0)
    // 玻璃左上角换算到背景来源坐标系
    val originX = glassBoundsInWindow.left - sourceBoundsInWindow.left
    val originY = glassBoundsInWindow.top - sourceBoundsInWindow.top

    // 采样外扩必须整体落在背景来源之内：越界时宁可回退，也不采玻璃自身或黑边
    if (originX - safePadding < 0 || originY - safePadding < 0 ||
        originX + glassBoundsInWindow.width + safePadding > sourceBoundsInWindow.width ||
        originY + glassBoundsInWindow.height + safePadding > sourceBoundsInWindow.height
    ) {
        return BackdropSamplePlan.original(BackdropFallbackReason.OUT_OF_SOURCE_BOUNDS)
    }

    return BackdropSamplePlan(
        status = BackdropSamplingStatus.SAMPLED,
        reason = BackdropFallbackReason.NONE,
        layerWidthPx = glassBoundsInWindow.width + safePadding * 2,
        layerHeightPx = glassBoundsInWindow.height + safePadding * 2,
        paddingPx = safePadding,
        // 背景来源像素 originX 落到图层内的 padding 位置，实现像素级对齐
        sourceTranslateX = (safePadding - originX).toFloat(),
        sourceTranslateY = (safePadding - originY).toFloat(),
        glassWidthPx = glassBoundsInWindow.width,
        glassHeightPx = glassBoundsInWindow.height,
        glassOriginInSource = IntOffset(originX, originY)
    )
}

/**
 * 采样图层（含 RenderEffect）的重建键。
 *
 * 键相同即表示参数、尺寸与对齐都没变，调用方可以完全跳过重建：静止场景不会
 * 因为新效果产生额外刷新。位置必须进键，否则滚动后仍复用旧对齐会错位。
 */
data class BackdropEffectKey(
    val layerWidthPx: Int,
    val layerHeightPx: Int,
    val paddingPx: Int,
    val sourceTranslateX: Float,
    val sourceTranslateY: Float,
    val cornerRadiusPx: Float,
    val refractionHeightPx: Float,
    val refractionAmountPx: Float,
    val dispersion: Float,
    val blurRadiusPx: Float,
    val contentRevision: Int
)

/** 把采样方案与外观参数合成为重建键；方案不可采样时返回 null。 */
fun backdropEffectKey(
    plan: BackdropSamplePlan,
    appearance: LiquidGlassBackdropAppearance,
    cornerRadiusPx: Float,
    contentRevision: Int
): BackdropEffectKey? {
    if (!plan.useSampledBackdrop) return null
    return BackdropEffectKey(
        layerWidthPx = plan.layerWidthPx,
        layerHeightPx = plan.layerHeightPx,
        paddingPx = plan.paddingPx,
        sourceTranslateX = plan.sourceTranslateX,
        sourceTranslateY = plan.sourceTranslateY,
        cornerRadiusPx = cornerRadiusPx,
        refractionHeightPx = appearance.refractionHeightPx,
        refractionAmountPx = appearance.refractionAmountPx,
        dispersion = appearance.dispersion,
        blurRadiusPx = appearance.blurRadiusPx,
        contentRevision = contentRevision
    )
}

/**
 * 内容层在 `PhysicalLiquidGlassBoxImpl` 的 Layout 子项中的下标。
 *
 * 原有实现有 8 层，内容固定在第 4 层。新增背景采样层时它被插在最前，
 * 内容层顺延到第 5 层。这个映射单独抽出来，是为了让"层序没被改变"这件事
 * 可以被测试断言，而不是只靠读代码。
 */
fun glassContentLayerIndex(hasBackdropLayer: Boolean): Int = if (hasBackdropLayer) 5 else 4

/** 原有实现的核心层数（背景模糊 + 表面 + 噪点 + 内容 + 4 条边界线）。 */
const val LIQUID_GLASS_CORE_LAYER_COUNT: Int = 8
