package com.tabula.v3.ui.components.liquidglass

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 液态玻璃"背景采样层"的纯几何决策与坐标对齐。
 *
 * 这一层不碰任何 Android 图形 API，只回答四个可审计的问题：
 * 1. 这次到底走背景采样，还是沿用原有效果；
 * 2. 采样图层开多大（离屏范围只覆盖玻璃节点本身 + 采样外扩，不含整屏）；
 * 3. 背景内容在采样图层里平移多少像素、以多大的坐标系重绘，玻璃区域才与背景对齐；
 * 4. 玻璃 Layout 的子项结构（层数、内容下标），保证"采样不可用"时内容仍被正确测量。
 *
 * 原型只接受**四角半径一致的圆角矩形**（含圆形：`CircleShape` 即半径 = 短边/2 的
 * 圆角矩形）。切角、四角不等、任意 Path 一律回退到 `PhysicalLiquidGlassBox` 原有
 * 材质，避免"采到玻璃自身"或闪黑。
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

    /** 非"四角半径一致"的圆角矩形（切角 / 四角不等 / 任意形状），原型不处理 */
    UNSUPPORTED_SHAPE,

    /** 还没有拿到布局坐标（首帧） */
    NO_COORDINATES,

    /** 玻璃节点或背景尺寸为零 */
    DEGENERATE_GEOMETRY,

    /** 采样范围（含外扩）超出背景来源，无法完整采样 */
    OUT_OF_SOURCE_BOUNDS,

    /** 还没画过一帧（调试预览首帧的初始值，不代表真实结论） */
    NOT_DRAWN_YET,

    /**
     * 查看器正在翻页：屏幕上的照片与采样层里那张不是同一幅。
     *
     * 采样层是静止的 `ContentScale.Fit` 绘制，而查看器用 `HorizontalPager` 平移
     * 整页照片；两者在滚动期间必然错位。继续显示就是"错位采样"，因此必须回退。
     */
    VIEWER_SCROLLING,

    /**
     * 查看器处于放大或拖动状态：照片经 `graphicsLayer{scale/translation}` 变换，
     * 采样层没有对应变换，边缘折射采到的会是"未放大的那张照片"。
     */
    VIEWER_TRANSFORMED
}

/** 采样层效果的运行状态；与"方案成立"分开报告，避免把未生效当成已生效。 */
enum class BackdropEffectHealth {
    Unknown,
    Ok,
    Failed
}

/**
 * 一次采样的完整几何方案。所有坐标都是整像素，避免累积到半像素后闪边。
 *
 * 采样图层与玻璃节点的关系（**尺寸契约**）：
 * - 图层尺寸 = 玻璃尺寸 + 2 × [paddingPx]，图层**不是**玻璃大小；
 * - 玻璃内容在图层内固定在 `(paddingPx, paddingPx)`；
 * - 图层在 Layout 里以 `(-paddingPx, -paddingPx)` 放置，于是玻璃与图层在屏幕上重合；
 * - 背景内容在图层内平移 [sourceTranslateX] / [sourceTranslateY]，且以
 *   **背景来源尺寸**（不是玻璃尺寸）作为 `DrawScope.size` 重绘。
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
 * @param isRoundedRectShape 形状是否为"四角半径一致"的圆角矩形（原型限定，见 [backdropShapeMetrics]）
 * @param glassBoundsInWindow 玻璃节点相对窗口的像素矩形；`null` 表示坐标尚未就绪
 * @param sourceBoundsInWindow 背景来源相对窗口的像素矩形
 * @param paddingPx 采样外扩像素，给边缘折射与模糊留取样余量（见 [backdropPaddingPx]）
 */
fun planBackdropSample(
    sdkInt: Int,
    hasBackdropSource: Boolean,
    isRoundedRectShape: Boolean,
    glassBoundsInWindow: IntRect?,
    sourceBoundsInWindow: IntRect?,
    paddingPx: Int,
    enabled: Boolean = true,
    /**
     * 调用方（查看器）声明"此刻背景采样能否与屏幕照片保持一致"。
     *
     * 为 null 表示调用方不参与这个判断（调试预览、面板等静态场景）。
     * 非 null 且不可采样时，按 [ViewerBackdropSync.fallbackReason] 回退——
     * 这是"翻页/放大/拖动时不能继续显示错位采样"的落点。
     */
    viewerSync: ViewerBackdropSync? = null
): BackdropSamplePlan {
    if (!enabled) return BackdropSamplePlan.original(BackdropFallbackReason.DISABLED)
    if (viewerSync != null && !viewerSync.canSample) {
        return BackdropSamplePlan.original(
            viewerSync.fallbackReason ?: BackdropFallbackReason.VIEWER_TRANSFORMED
        )
    }
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
 * 形状可采样性：只有"四角半径一致"的圆角矩形能由 shader 里的圆角矩形 SDF 精确表达。
 *
 * - `RoundedCornerShape(12.dp)` → 四角相等，可采样；
 * - `CircleShape`（= `RoundedCornerShape(50)`）→ 半径 = 短边/2，可采样且渲染为圆；
 * - `CutCornerShape` → 切角不是圆角，SDF 不成立，回退；
 * - 四角不等（如只圆上面两角）→ 单个 `uCornerRadius` 表达不了，回退；
 * - 任意 `Shape`（Path / GenericShape）→ 回退。
 *
 * 返回的半径会被夹到 `[0, 短边/2]`，与 shader 里的夹取一致。
 */
data class BackdropShapeMetrics(
    val isUniformRoundedRect: Boolean,
    val cornerRadiusPx: Float
)

/**
 * 形状是否可采样：**只看形状本身**，与尺寸无关。
 *
 * 这样"能不能采样"在组合阶段就能定下来，不必等第一帧测量；尺寸只影响半径夹取。
 * 四角半径用 dp 值比较（百分比形状按各自比例展开），因此：
 * - `RoundedCornerShape(12.dp)` → 可采样；
 * - `CircleShape`（四角都是 50%）→ 可采样；
 * - 只圆上面两角 / `CutCornerShape` / 任意 Path → 回退。
 */
fun isBackdropShapeSupported(shape: Shape): Boolean {
    val corner = shape as? CornerBasedShape ?: return false
    if (corner is CutCornerShape) return false
    // 四角必须"同一个尺寸值"：`RoundedCornerShape(12.dp)` 与 `CircleShape`（四角都是
    // 50%）都满足；只圆上面两角、或 dp 与百分比混用则不满足——单个 `uCornerRadius`
    // 表达不了两种半径，硬画会把形状掰成另一种。
    val first = corner.topStart
    return corner.topEnd == first &&
        corner.bottomEnd == first &&
        corner.bottomStart == first
}

/**
 * 由形状与**玻璃实际尺寸**求 shader 用的圆角半径（像素）。
 *
 * 尺寸只用来夹取（半径不能超过短边一半，圆形正是取到这一上限），因此同一形状在
 * 不同尺寸的玻璃上都能得到正确半径：`RoundedCornerShape(12.dp)` 在小按钮上是 12dp，
 * `CircleShape` 在任意方形上是边长一半。
 */
fun backdropShapeMetrics(shape: Shape, sizePx: Size, density: Density): BackdropShapeMetrics {
    if (sizePx.minDimension <= 0f) return BackdropShapeMetrics(false, 0f)
    val corner = shape as? CornerBasedShape ?: return BackdropShapeMetrics(false, 0f)
    if (corner is CutCornerShape) return BackdropShapeMetrics(false, 0f)

    val maxRadius = sizePx.minDimension / 2f
    val radii = listOf(
        corner.topStart,
        corner.topEnd,
        corner.bottomEnd,
        corner.bottomStart
    ).map { it.toPx(sizePx, density).coerceIn(0f, maxRadius) }

    val first = radii.first()
    val uniform = radii.all { abs(it - first) <= 0.5f }
    return BackdropShapeMetrics(
        isUniformRoundedRect = uniform,
        cornerRadiusPx = if (uniform) first else 0f
    )
}

/**
 * 采样外扩（像素）：给**模糊核**留真实内容，不让图层边缘夹取自己。
 *
 * 只由模糊决定，**与折射位移无关**——这一点是"只改位移"对照测试能成立的前提：
 * 位移是向玻璃内部走的，永远不会让采样点越出图层；而模糊核会在图层边缘取到
 * 图层外的像素（`TileMode.CLAMP` 会复制边缘），边缘就变成"被拉长的边界色"。
 * 因此外扩 = 半个模糊核即可。
 *
 * 若把位移也纳入外扩（早期写法 `max(模糊, 位移)/2`），改位移时采样范围会跟着变，
 * 对照测试里"除位移外一切相同"就不成立——模拟器证据里两组 `pad` 分别是 3 与 16，
 * 正是这个原因。位移本身不需要外扩，所以这里去掉它。
 *
 * 上限 64px：再大只是白画离屏像素，不会改善观感。
 */
fun backdropPaddingPx(appearance: LiquidGlassBackdropAppearance): Int {
    val halfKernel = appearance.blurRadiusPx / 2f
    return ceil(halfKernel).toInt().coerceIn(1, 64)
}

/**
 * 背景内容在采样图层里的放置方式（**坐标系契约**）。
 *
 * 背景 lambda 在宿主里看到的 `size` 是宿主尺寸；在采样层里必须看到**同一个尺寸**，
 * 否则所有 `size.width` / `size.minDimension` 相关的几何（圆形半径、尺寸相关渐变、
 * 居中元素）都会按玻璃尺寸重算，画面就不是"同一张背景平移"了。
 *
 * 因此采样层用 [sourceWidthPx] × [sourceHeightPx] 建立子 DrawScope，再整体平移
 * ([translateX], [translateY])：图层坐标 = 来源坐标 + 平移量。
 */
data class BackdropContentPlacement(
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val translateX: Float,
    val translateY: Float
) {
    /** 来源坐标 → 图层坐标（背景内容被画到的位置） */
    fun sourceToLayer(x: Float, y: Float): Offset = Offset(x + translateX, y + translateY)

    /** 图层坐标 → 来源坐标（采样点反查，用于验证不会采到图层之外） */
    fun layerToSource(x: Float, y: Float): Offset = Offset(x - translateX, y - translateY)
}

/** 方案可采样且来源尺寸有效时给出放置方式；否则返回 null（不重绘背景）。 */
fun backdropContentPlacement(
    plan: BackdropSamplePlan,
    sourceWidthPx: Int,
    sourceHeightPx: Int
): BackdropContentPlacement? {
    if (!plan.useSampledBackdrop) return null
    if (sourceWidthPx <= 0 || sourceHeightPx <= 0) return null
    return BackdropContentPlacement(
        sourceWidthPx = sourceWidthPx,
        sourceHeightPx = sourceHeightPx,
        translateX = plan.sourceTranslateX,
        translateY = plan.sourceTranslateY
    )
}

/**
 * 采样层 shader 的 uniform 取值。
 *
 * 全部由**图层实际尺寸**推导（而不是玻璃尺寸或上一帧的方案），这样尺寸变化时
 * `uGlassSize` 与 `uGlassOrigin` 永远和真实绘制面积一致，不会出现"shader 以为
 * 图层是 A、实际画到 B"的错位。
 */
data class BackdropShaderUniforms(
    val layerWidth: Float,
    val layerHeight: Float,
    val glassOriginX: Float,
    val glassOriginY: Float,
    val glassWidth: Float,
    val glassHeight: Float,
    val cornerRadius: Float,
    val refractionHeight: Float,
    val refractionAmount: Float,
    val dispersion: Float
)

fun backdropShaderUniforms(
    layerWidthPx: Int,
    layerHeightPx: Int,
    paddingPx: Int,
    cornerRadiusPx: Float,
    appearance: LiquidGlassBackdropAppearance
): BackdropShaderUniforms {
    val pad = paddingPx.coerceAtLeast(0)
    val glassWidth = (layerWidthPx - pad * 2).coerceAtLeast(1)
    val glassHeight = (layerHeightPx - pad * 2).coerceAtLeast(1)
    val maxRadius = minOf(glassWidth, glassHeight) / 2f
    return BackdropShaderUniforms(
        layerWidth = layerWidthPx.toFloat(),
        layerHeight = layerHeightPx.toFloat(),
        glassOriginX = pad.toFloat(),
        glassOriginY = pad.toFloat(),
        glassWidth = glassWidth.toFloat(),
        glassHeight = glassHeight.toFloat(),
        cornerRadius = cornerRadiusPx.coerceIn(0f, maxRadius),
        refractionHeight = appearance.refractionHeightPx,
        refractionAmount = appearance.refractionAmountPx,
        dispersion = appearance.dispersion
    )
}

/**
 * 采样图层（含 RenderEffect）的重建键。
 *
 * 键相同即表示参数、尺寸与对齐都没变，调用方可以完全跳过重建：静止场景不会
 * 因为新效果产生额外刷新。位置与背景内容版本必须进键，否则滚动/换背景后仍复用
 * 旧对齐会错位。
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
 * 玻璃 `Layout` 的子项契约。
 *
 * 这一层单独抽出来，是因为"子项下标错位"是静默的：内容被当成普通图层用固定约束
 * 测量，`wrapContent` 会退化成 0，界面上看不到内容却不会报错。
 *
 * 契约：
 * - 没有背景层时 9 个子项，内容在**下标 4**；
 * - 有背景层时 10 个子项，背景层插在最前，内容顺延到**下标 5**；
 * - **背景层永远只占一个子项**，哪怕采样不可用（此时它什么都不画）。
 *   这样"采样句柄非空"与"子项存在"是同一件事，不会再出现下标漂移。
 */
data class GlassChildLayout(
    val childCount: Int,
    val contentIndex: Int,
    val backdropIndex: Int?
)

/** 原有实现的核心层数（背景模糊 + 表面 + 噪点 + 内阴影 + 内容 + 4 条边界线）。 */
const val LIQUID_GLASS_CORE_LAYER_COUNT: Int = 9

/** 内容层在原有 9 层里的下标（0-based）。 */
const val LIQUID_GLASS_CONTENT_LAYER_INDEX: Int = 4

fun glassChildLayout(hasBackdropLayer: Boolean): GlassChildLayout = if (hasBackdropLayer) {
    GlassChildLayout(
        childCount = LIQUID_GLASS_CORE_LAYER_COUNT + 1,
        contentIndex = LIQUID_GLASS_CONTENT_LAYER_INDEX + 1,
        backdropIndex = 0
    )
} else {
    GlassChildLayout(
        childCount = LIQUID_GLASS_CORE_LAYER_COUNT,
        contentIndex = LIQUID_GLASS_CONTENT_LAYER_INDEX,
        backdropIndex = null
    )
}

/**
 * 按计划顺序收集子项测量结果，**保证内容层不会被测量第二次**。
 *
 * Compose 对同一个 `Measurable` 只允许 `measure()` 一次，重复调用会直接抛
 * `IllegalStateException: measure() may not be called multiple times on the same
 * Measurable`。这条异常发生在**布局阶段**，而首帧往往已经画出去了，所以表现
 * 成"画面闪一下然后 app 崩溃"——模拟器取证里就出现过这个症状，最后是靠本地
 * Robolectric 冒烟才定位到。
 *
 * 抽成纯函数是为了让"内容只测一次"成为可断言的事实，而不是靠读代码：
 * 内容层的 placeable 由调用方先测好传进来，这里只对其余下标调用 [measureOther]。
 */
internal inline fun <T> collectPlaceablesInPlanOrder(
    childCount: Int,
    contentIndex: Int,
    contentPlaceable: T,
    measureOther: (index: Int) -> T
): List<T> = List(childCount) { index ->
    if (index == contentIndex) contentPlaceable else measureOther(index)
}

/**
 * 内容层下标。保留这个入口是因为它被测试直接断言；语义与 [glassChildLayout] 一致。
 */
fun glassContentLayerIndex(hasBackdropLayer: Boolean): Int =
    glassChildLayout(hasBackdropLayer).contentIndex

/**
 * 采样层实际测量出的尺寸换算回玻璃尺寸。
 *
 * Layout 里背景子项被 `Constraints.fixed(content + 2*pad)` 测量并放在 `(-pad,-pad)`，
 * 因此"图层实际尺寸 - 2*pad"就是玻璃尺寸；这里保证它与方案里的玻璃尺寸一致，
 * 不一致时以**实际测量**为准（布局是事实，方案只是预测）。
 */
fun glassSizeFromLayer(layer: IntSize, paddingPx: Int): IntSize {
    val pad = paddingPx.coerceAtLeast(0)
    return IntSize(
        width = (layer.width - pad * 2).coerceAtLeast(0),
        height = (layer.height - pad * 2).coerceAtLeast(0)
    )
}

/** 图层尺寸预测：Layout 用内容尺寸 + 2*pad 决定背景子项的约束。 */
fun layerSizeFor(glassSize: IntSize, paddingPx: Int): IntSize {
    val pad = paddingPx.coerceAtLeast(0)
    return IntSize(glassSize.width + pad * 2, glassSize.height + pad * 2)
}

/**
 * 一次玻璃 Layout 的测量与摆放计划。
 *
 * 抽成纯函数的目的：`Layout` 里"哪个下标是内容、每个子项拿什么约束、放在哪"
 * 这三件事可以被 JVM 单测逐条断言，而不是只能靠读代码。早期版本在这里出过
 * 静默缺陷——采样不可用时不发射背景子项，内容下标错位，`wrapContent` 塌成 0，
 * 界面上什么都没有却不报错。
 */
data class GlassLayoutPlan(
    val contentIndex: Int,
    val childCount: Int,
    /** 每个子项的测量约束；内容层使用父约束（保证 wrapContent 拿到真实内容尺寸） */
    val constraintsPerChild: List<Constraints>,
    /** 每个子项的摆放偏移；背景层为 (-pad,-pad)，其余为 (0,0) */
    val offsetsPerChild: List<IntOffset>,
    val contentSize: IntSize,
    val backdropLayerSize: IntSize
)

/**
 * 生成测量与摆放计划。
 *
 * @param layout 子项契约（层数 / 内容下标 / 背景下标）
 * @param paddingPx 采样外扩；背景层尺寸 = 内容尺寸 + 2*pad
 * @param contentSize 内容层按**父约束**测量出的真实尺寸（wrapContent 时就是内容本身）
 * @param parentConstraints 父约束，用于给内容层之外的层做固定尺寸约束
 */
fun planGlassLayout(
    layout: GlassChildLayout,
    paddingPx: Int,
    contentSize: IntSize,
    parentConstraints: Constraints
): GlassLayoutPlan {
    val pad = paddingPx.coerceAtLeast(0)
    val fixed = Constraints.fixed(
        width = contentSize.width,
        height = contentSize.height
    )
    val layerSize = layerSizeFor(contentSize, pad)
    val backdropConstraints = Constraints.fixed(layerSize.width, layerSize.height)

    val constraints = List(layout.childCount) { index ->
        when (index) {
            layout.contentIndex -> parentConstraints
            layout.backdropIndex -> backdropConstraints
            else -> fixed
        }
    }
    val offsets = List(layout.childCount) { index ->
        if (index == layout.backdropIndex) IntOffset(-pad, -pad) else IntOffset.Zero
    }
    return GlassLayoutPlan(
        contentIndex = layout.contentIndex,
        childCount = layout.childCount,
        constraintsPerChild = constraints,
        offsetsPerChild = offsets,
        contentSize = contentSize,
        backdropLayerSize = layerSize
    )
}

/** 背景内容版本号：只在背景真的变化时递增，静止画面保持稳定。 */
internal fun nextBackdropRevision(current: Int): Int =
    if (current == Int.MAX_VALUE) 1 else current + 1

/** 把浮点矩形收敛成整像素（供宿主把 `boundsInWindow` 转成来源矩形）。 */
internal fun Rect_toIntRect(left: Float, top: Float, right: Float, bottom: Float): IntRect = IntRect(
    left = left.toInt(),
    top = top.toInt(),
    right = right.roundToInt(),
    bottom = bottom.roundToInt()
)
