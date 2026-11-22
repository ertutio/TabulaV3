package com.tabula.v3.ui.components.liquidglass

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import kotlin.math.roundToInt

/**
 * 液态玻璃背景采样：同一个 Compose 图层提供背景。
 *
 * 分工（对应原型验收项）：
 * - [LiquidGlassBackdropHost]：把"背景内容"录进一个可复用 [GraphicsLayer]。
 *   只录背景；玻璃节点、前景文字与图标都在这个图层之外绘制。
 * - [LiquidGlassBackdropSampling]：玻璃侧。按玻璃在窗口中的位置，把背景图层
 *   像素对齐后平移进"玻璃 + 采样外扩"的小图层，串上折射 shader，再按外扩
 *   反向回贴并按形状裁切。
 * - 采样层里只有背景，因此边缘折射永远采不到玻璃自身。
 *
 * 刷新策略：只有几何、外观参数或背景版本（[BackdropEffectKey]）变化时才重新
 * 录制采样层；静止画面复用上一次的图层。滚动或改尺寸时位置发生变化，必须
 * 重新对齐——重录的只是"玻璃大小"的小图层，不做整屏回读。
 */
class LiquidGlassBackdropState {
    internal var contentLayer: GraphicsLayer? = null
    private var sourceBoundsState by mutableStateOf(IntRect.Zero)
    private var boundsKnownState by mutableStateOf(false)
    private var revisionState by mutableIntStateOf(0)

    internal var sourceBounds: IntRect
        get() = sourceBoundsState
        set(value) {
            sourceBoundsState = value
        }

    internal var boundsKnown: Boolean
        get() = boundsKnownState
        set(value) {
            boundsKnownState = value
        }

    /** 背景内容版本号；玻璃采样层据此判断是否需要重新录制。 */
    val revision: Int get() = revisionState

    /** 背景在窗口坐标系中的像素范围；未布局完成时返回 null。 */
    fun boundsOrNull(): IntRect? =
        if (boundsKnownState && contentLayer != null && sourceBoundsState.width > 0) {
            sourceBoundsState
        } else {
            null
        }

    /** 调用方声明"背景内容确实变了"（换图、裁切、滚动容器偏移），触发重新采样。 */
    fun invalidateContent() {
        revisionState += 1
    }

    internal fun attachLayer(layer: GraphicsLayer?) {
        contentLayer = layer
    }

    private var planState by mutableStateOf(
        BackdropSamplePlan.original(BackdropFallbackReason.NOT_DRAWN_YET)
    )
    private var effectState by mutableStateOf<BackdropEffectHealth>(BackdropEffectHealth.Unknown)

    /**
     * 最近一次采样方案（可被调试预览读取并显示）。
     *
     * 只在值真的变化时写一次：绘制阶段写状态会安排下一帧重组，写完即稳定，
     * 不会持续刷新。静止场景的"不刷新"由 CI 的逐像素稳定性对照来守。
     */
    val lastPlan: BackdropSamplePlan get() = planState

    /** 采样层的实际渲染健康度：用于区分"方案说采样了但效果没建起来"。 */
    val effectHealth: BackdropEffectHealth get() = effectState

    internal fun reportPlan(plan: BackdropSamplePlan) {
        if (planState != plan) planState = plan
    }

    internal fun reportEffectHealth(health: BackdropEffectHealth) {
        if (effectState != health) effectState = health
    }

    /** 最近一次效果构建失败的原因；仅用于诊断，不参与渲染。 */
    var effectError: String? = null
        private set

    internal fun reportEffectError(message: String) {
        effectError = message
        reportEffectHealth(BackdropEffectHealth.Failed)
    }
}

@Composable
fun rememberLiquidGlassBackdropState(): LiquidGlassBackdropState =
    remember { LiquidGlassBackdropState() }

/**
 * 背景宿主：把 [content] 录进共享图层，再把图层画出来。
 *
 * 同一份内容只绘制一次（录制即绘制），静止时不再触发重绘。API 33 以下不建立
 * 图层，[content] 按原有路径直接绘制，保证 Android 12 走原有显示。
 */
@Composable
fun LiquidGlassBackdropHost(
    state: LiquidGlassBackdropState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    if (Build.VERSION.SDK_INT < LIQUID_GLASS_BACKDROP_MIN_SDK) {
        state.attachLayer(null)
        Box(modifier = modifier, content = content)
        return
    }

    val layer = rememberGraphicsLayer()
    state.attachLayer(layer)

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val rect: Rect = coordinates.boundsInWindow()
                state.sourceBounds = rect.toIntRect()
                state.boundsKnown = true
            }
            .drawWithContent {
                val pixelSize = IntSize(size.width.toInt(), size.height.toInt())
                if (pixelSize.width > 0 && pixelSize.height > 0) {
                    layer.record(this@drawWithContent, layoutDirection, pixelSize) {
                        this@drawWithContent.drawContent()
                    }
                    layer.topLeft = IntOffset.Zero
                    drawLayer(layer)
                } else {
                    drawContent()
                }
            },
        content = content
    )
}

/** Rect → 整像素 IntRect；四边都向外取整，保证采样范围不缩水。 */
internal fun Rect.toIntRect(): IntRect = IntRect(
    left = left.toInt(),
    top = top.toInt(),
    right = right.roundToInt(),
    bottom = bottom.roundToInt()
)

/**
 * 玻璃侧采样句柄：持有采样图层与上一次的录制键。不放进 Composable 状态，
 * 避免无谓重组。
 */
class LiquidGlassBackdropSampling internal constructor(
    private val source: LiquidGlassBackdropState,
    private val spec: LiquidGlassBackdropSpec,
    private val sampleLayer: GraphicsLayer
) {
    internal var glassBoundsState by mutableStateOf<IntRect?>(null)
    private var lastKey: BackdropEffectKey? = null

    internal fun onGlassPositioned(bounds: IntRect) {
        glassBoundsState = bounds
    }

    /**
     * 背景层修饰符：绘制在原有材质之前。方案不可采样时什么都不画，画面与
     * 接入前完全一致。
     */
    internal fun backdropModifier(
        shape: Shape,
        density: Float,
        cornerRadiusPx: Float
    ): Modifier = Modifier.drawWithContent {
        val plan = planBackdropSample(
            sdkInt = Build.VERSION.SDK_INT,
            hasBackdropSource = source.contentLayer != null,
            isRoundedRectShape = true,
            glassBoundsInWindow = glassBoundsState,
            sourceBoundsInWindow = source.boundsOrNull(),
            paddingPx = paddingPx(density),
            enabled = spec.enabled
        )
        source.reportPlan(plan)
        if (plan.useSampledBackdrop) {
            val path = Path().apply {
                addOutline(shape.createOutline(size, layoutDirection, this@drawWithContent))
            }
            drawSampled(plan, path, density, cornerRadiusPx)
        }
        drawContent()
    }

    private fun paddingPx(density: Float): Int {
        val appearance = spec.resolve(density)
        // 外扩取最大位移的一半：足够覆盖边缘取样，又不放大离屏范围
        return (appearance.refractionAmountPx / 2f).toInt().coerceAtLeast(1)
    }

    private fun DrawScope.drawSampled(
        plan: BackdropSamplePlan,
        path: Path,
        density: Float,
        cornerRadiusPx: Float
    ) {
        val background = source.contentLayer ?: return
        val appearance = spec.resolve(density)
        val key = backdropEffectKey(plan, appearance, cornerRadiusPx, source.revision) ?: return
        if (key != lastKey) {
            sampleLayer.record(this, layoutDirection, IntSize(plan.layerWidthPx, plan.layerHeightPx)) {
                translate(plan.sourceTranslateX, plan.sourceTranslateY) {
                    drawLayer(background)
                }
            }
            val effect = createBackdropRenderEffect(plan, appearance, cornerRadiusPx) { message ->
                source.reportEffectError(message)
            }
            sampleLayer.renderEffect = effect?.asComposeRenderEffect()
            // 采样方案成立但效果没建起来时，画面会退回"未折射的背景"，
            // 必须显式暴露出来，否则验收会被误判成通过。
            source.reportEffectHealth(
                if (effect != null) BackdropEffectHealth.Ok else BackdropEffectHealth.Failed
            )
            lastKey = key
        }
        clipPath(path) {
            translate(-plan.paddingPx.toFloat(), -plan.paddingPx.toFloat()) {
                drawLayer(sampleLayer)
            }
        }
    }
}

/**
 * 创建玻璃侧采样句柄。必须与 [LiquidGlassBackdropHost] 使用同一个
 * [LiquidGlassBackdropState]；不可用时返回 null，调用方继续走原有材质。
 */
@Composable
fun rememberLiquidGlassBackdropSampling(
    state: LiquidGlassBackdropState?,
    spec: LiquidGlassBackdropSpec
): LiquidGlassBackdropSampling? {
    if (state == null || !spec.enabled) return null
    if (Build.VERSION.SDK_INT < LIQUID_GLASS_BACKDROP_MIN_SDK) return null
    val layer = rememberGraphicsLayer()
    return remember(state, spec, layer) {
        LiquidGlassBackdropSampling(state, spec, layer)
    }
}

/**
 * 把玻璃自身在窗口中的位置喂给采样句柄。滚动或改尺寸时这里会重新回调，
 * 采样随之重新对齐——不依赖逐帧轮询。
 */
fun Modifier.liquidGlassBackdropPosition(sampling: LiquidGlassBackdropSampling?): Modifier {
    if (sampling == null) return this
    return this.onGloballyPositioned { coordinates ->
        sampling.onGlassPositioned(coordinates.boundsInWindow().toIntRect())
    }
}

/** 供 `PhysicalLiquidGlassBox` 取背景层修饰符；不可采样时返回 null。 */
internal fun LiquidGlassBackdropSampling?.backdropLayerModifier(
    shape: Shape,
    density: Float,
    cornerRadiusPx: Float
): Modifier? = this?.backdropModifier(shape, density, cornerRadiusPx)
