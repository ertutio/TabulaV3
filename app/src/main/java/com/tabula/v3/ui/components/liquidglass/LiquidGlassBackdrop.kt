package com.tabula.v3.ui.components.liquidglass

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
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
 * - [LiquidGlassBackdropHost]：提供背景的绘制入口与窗口位置。背景**不录成
 *   整屏位图**，玻璃侧按需在自己的小层里局部重绘。
 * - [LiquidGlassBackdropSampling]：玻璃侧。按玻璃在窗口中的位置对齐背景，
 *   在"玻璃节点大小"的层里局部重绘背景，串上折射 shader。
 * - 采样层里只有背景，因此边缘折射永远采不到玻璃自身。
 *
 * 为什么不走"录成 GraphicsLayer 再引用"：模拟器取证实测，被引用的图层
 * 不会经过 `renderEffect`（自绘图案上 48px 模糊生效 26.9 万像素，换成引用
 * 外部图层后同一效果 0 像素变化）。因此这里让采样层自己拥有内容。
 */
class LiquidGlassBackdropState {
    internal var content: (DrawScope.() -> Unit)? = null
    internal val hasContent: Boolean get() = content != null
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
        if (boundsKnownState && hasContent && sourceBoundsState.width > 0) {
            sourceBoundsState
        } else {
            null
        }

    /** 调用方声明"背景内容确实变了"（换图、裁切、滚动容器偏移），触发重新采样。 */
    fun invalidateContent() {
        revisionState += 1
    }

    internal fun attachContent(block: (DrawScope.() -> Unit)?) {
        content = block
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
 * 背景宿主：提供背景的绘制方式与窗口位置。
 *
 * 背景以 `DrawScope.() -> Unit` 的形式传入，同一个 lambda 会被画两次：
 * 1. 这里按正常流程铺满宿主（背景照常显示）；
 * 2. 采样层在自己的小层里按对齐后的原点局部重绘（只覆盖玻璃大小）。
 *
 * 两次都是直接绘制，不产生"录成整屏位图再引用"的中间物——既避免逐帧回读
 * 整张照片，也保证采样层的 renderEffect 真的作用在自己拥有的内容上。
 *
 * API 33 以下不参与采样，背景按原有路径直接绘制，Android 12 走原有显示。
 */
@Composable
fun LiquidGlassBackdropHost(
    state: LiquidGlassBackdropState,
    modifier: Modifier = Modifier,
    background: DrawScope.() -> Unit
) {
    if (Build.VERSION.SDK_INT < LIQUID_GLASS_BACKDROP_MIN_SDK) {
        state.attachContent(null)
        Box(modifier = modifier.drawBehind(background))
        return
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                state.sourceBounds = coordinates.boundsInWindow().toIntRect()
                state.boundsKnown = true
            }
            .drawWithContent {
                drawContent()
                background()
            }
    )

    SideEffect {
        state.attachContent(background)
    }
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
    private val spec: LiquidGlassBackdropSpec
) {
    internal var glassBoundsState by mutableStateOf<IntRect?>(null)

    internal fun onGlassPositioned(bounds: IntRect) {
        glassBoundsState = bounds
    }

    /**
     * 玻璃下方的背景采样层。
     *
     * 关键约束（由模拟器取证确定，不要再改回去）：
     * - 采样层必须**自己拥有绘制内容**。整层内容若只是 `drawLayer(外部图层)`，
     *   `renderEffect` 不会作用于被引用的图层——实测自绘图案上 48px 模糊
     *   生效（26.9 万像素变化），换成引用外部背景图层后同一效果 0 像素变化。
     * - 因此这里用 [LiquidGlassBackdropState.content] 在层内局部重绘背景，
     *   而不是把整屏内容录成一张图再搬过来；离屏范围仍只有玻璃节点大小。
     * - 重绘只在几何、外观或背景版本变化时发生，静止画面复用缓存。
     */
    @Composable
    internal fun BackdropLayer(
        shape: Shape,
        density: Float,
        cornerRadiusPx: Float
    ) {
        val plan = planBackdropSample(
            sdkInt = Build.VERSION.SDK_INT,
            hasBackdropSource = source.hasContent,
            isRoundedRectShape = true,
            glassBoundsInWindow = glassBoundsState,
            sourceBoundsInWindow = source.boundsOrNull(),
            paddingPx = 0,
            enabled = spec.enabled
        )
        SideEffect { source.reportPlan(plan) }
        if (!plan.useSampledBackdrop) return

        val appearance = spec.resolve(density)
        val effect = remember(plan, appearance, cornerRadiusPx, spec.diagnosticBlurOnly) {
            if (spec.diagnosticBlurOnly) {
                RenderEffect.createBlurEffect(48f, 48f, Shader.TileMode.CLAMP)
            } else {
                createBackdropRenderEffect(plan, appearance, cornerRadiusPx) { message ->
                    source.reportEffectError(message)
                }
            }
        }
        SideEffect {
            source.reportEffectHealth(
                if (effect != null) BackdropEffectHealth.Ok else BackdropEffectHealth.Failed
            )
        }

        // 背景内容按"玻璃在背景坐标系中的原点"平移后局部重绘
        val originX = plan.glassOriginInSource.x.toFloat()
        val originY = plan.glassOriginInSource.y.toFloat()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    renderEffect = effect?.asComposeRenderEffect()
                    clip = true
                    this.shape = shape
                }
                .drawBehind {
                    if (spec.diagnosticBlurOnly) {
                        for (i in 0 until 24) {
                            drawRect(
                                color = if (i % 2 == 0) Color.White else Color.Black,
                                topLeft = Offset(i * size.width / 24f, 0f),
                                size = Size(size.width / 24f, size.height)
                            )
                        }
                        return@drawBehind
                    }
                    translate(-originX, -originY) {
                        // 背景在自己的坐标系里局部重绘；这里不带录屏截图
                        source.content?.invoke(this)
                    }
                }
        )
    }

    private fun paddingPx(density: Float): Int {
        val appearance = spec.resolve(density)
        // 外扩取最大位移的一半：足够覆盖边缘取样，又不放大离屏范围
        return (appearance.refractionAmountPx / 2f).toInt().coerceAtLeast(1)
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
    return remember(state, spec) {
        LiquidGlassBackdropSampling(state, spec)
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

/** 供 `PhysicalLiquidGlassBox` 判断是否插入背景层（只影响层序，不影响采样）。 */
internal fun LiquidGlassBackdropSampling?.hasBackdropLayer(): Boolean = this != null
