package com.tabula.v3.ui.components.liquidglass

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * 液态玻璃背景采样：同一个 Compose 图层提供背景。
 *
 * 分工（对应原型验收项）：
 * - [LiquidGlassBackdropHost]：提供背景的绘制入口与窗口位置。背景**不录成
 *   整屏位图**，玻璃侧按需在自己的小层里局部重绘。
 * - [LiquidGlassBackdropSampling]：玻璃侧。按玻璃在窗口中的位置对齐背景，
 *   在"玻璃尺寸 + 采样外扩"的层里局部重绘背景，串上折射 shader。
 * - 采样层里只有背景，因此边缘折射永远采不到玻璃自身。
 *
 * 为什么不走"录成 GraphicsLayer 再引用"：模拟器取证实测，被引用的图层
 * 不会经过 `renderEffect`（自绘图案上 48px 模糊生效 26.9 万像素，换成引用
 * 外部图层后同一效果 0 像素变化）。因此这里让采样层自己拥有内容。
 *
 * ## 尺寸契约（不要再改成"只平移玻璃大小的 DrawScope"）
 *
 * 背景 lambda 在宿主里看到的 `size` 是**宿主尺寸**。采样层里必须让它看到
 * **同一个尺寸**，否则所有依赖 `size` 的几何（圆形半径、尺寸相关渐变、居中
 * 元素）都会按玻璃尺寸重算，画面不再是"同一张背景平移"。因此采样层用
 * [BackdropContentPlacement] 给出的来源尺寸建立子 DrawScope，再整体平移。
 *
 * ## 绘制失效
 *
 * 背景内容版本 [LiquidGlassBackdropState.revision] 与背景 lambda 本身都是
 * Compose 状态，并且都在**绘制阶段被读取**：调用方换背景或调
 * [LiquidGlassBackdropState.invalidateContent] 之后，采样层这一笔绘制被标脏、
 * 重画一次；静止时没人改，就一次也不重画。
 *
 * 重画次数通过 [LiquidGlassBackdropState.hostDrawCount] /
 * [LiquidGlassBackdropState.layerDrawCount] 暴露成**可观测指标**，供探针判断
 * "到底刷新了几次"，而不是拿两张截图相同冒充刷新次数验证。
 */
class LiquidGlassBackdropState {
    /** 背景绘制方式。作为状态持有：换背景 lambda 本身就会让采样层重画。 */
    private var contentState by mutableStateOf<((DrawScope.() -> Unit)?)>(null)

    internal val content: (DrawScope.() -> Unit)?
        get() = contentState

    internal val hasContent: Boolean get() = contentState != null

    /** 背景来源在窗口中的像素范围；未布局完成时为 [IntRect.Zero]。 */
    var sourceBounds: IntRect
        get() = sourceBoundsState
        internal set(value) {
            sourceBoundsState = value
        }

    private var sourceBoundsState by mutableStateOf(IntRect.Zero)
    private var boundsKnownState by mutableStateOf(false)

    internal var boundsKnown: Boolean
        get() = boundsKnownState
        set(value) {
            boundsKnownState = value
        }

    /** 背景内容版本号；玻璃采样层据此判断是否需要重新录制。 */
    val revision: Int
        get() = revisionState

    private var revisionState by mutableIntStateOf(0)

    /** 背景来源尺寸（像素）。采样层按它建立背景的 DrawScope，保证与宿主同尺寸。 */
    val sourceSize: IntSize
        get() = IntSize(sourceBoundsState.width, sourceBoundsState.height)

    /**
     * 宿主（背景）的绘制次数。普通计数器，不是 Compose 状态：写它不能触发重组，
     * 否则统计本身就会让画面一直刷新。
     */
    val hostDrawCount: Int get() = hostDraws.get()

    /** 采样层的绘制次数（只统计真正重绘背景的那一层）。 */
    val layerDrawCount: Int get() = layerDraws.get()

    private val hostDraws = AtomicInteger(0)
    private val layerDraws = AtomicInteger(0)

    internal fun countHostDraw() {
        hostDraws.incrementAndGet()
    }

    /** 最近一次采样层绘制时看到的内容版本；用于断言"背景变了确实重画了"。 */
    val lastLayerDrawRevision: Int get() = lastLayerDrawRevisionValue.get()

    private val lastLayerDrawRevisionValue = AtomicInteger(-1)

    internal fun recordLayerDraw(revision: Int) {
        lastLayerDrawRevisionValue.set(revision)
        layerDraws.incrementAndGet()
    }

    /** 背景在窗口坐标系中的像素范围；未布局完成时返回 null。 */
    fun boundsOrNull(): IntRect? =
        if (boundsKnownState && contentState != null && sourceBoundsState.width > 0) {
            sourceBoundsState
        } else {
            null
        }

    /**
     * 调用方声明"背景内容确实变了"（换图、裁切、滚动容器偏移、图案移动）。
     *
     * 只递增版本号：采样层在绘制阶段读它，于是下一帧重画一次；静止时没人调用，
     * 就不会有额外刷新。
     */
    fun invalidateContent() {
        revisionState = nextBackdropRevision(revisionState)
    }

    internal fun attachContent(block: (DrawScope.() -> Unit)?) {
        // 背景 lambda 换了本身就是"内容变了"：必须同时递增版本号。
        // 只写 contentState 时，绘制阶段虽然读到了新 lambda，但在部分路径上
        // （graphicsLayer 内的 drawBehind）这一笔绘制不会被重新标脏——模拟器取证
        // 里就出现过"换背景后 layerdraw 1→1"，画面停在旧内容。版本号是绘制阶段
        // 显式读取的状态，递增它一定让采样层重画一次。
        if (contentState === block) return
        contentState = block
        invalidateContent()
    }

    private data class PrimaryReading(
        val plan: BackdropSamplePlan = BackdropSamplePlan.original(BackdropFallbackReason.NOT_DRAWN_YET),
        val glassWindow: IntRect? = null,
        val effectHealth: BackdropEffectHealth = BackdropEffectHealth.Unknown,
        val drawKey: Any? = null
    )

    private var primaryReading by mutableStateOf(PrimaryReading())
    private var effectState by mutableStateOf<BackdropEffectHealth>(BackdropEffectHealth.Unknown)

    /** 玻璃节点最近一次在窗口坐标系中的矩形；供审计标记输出**屏幕坐标**。 */
    val lastGlassWindow: IntRect? get() = primaryReading.glassWindow

    /**
     * 最近一次采样方案（可被调试预览读取并显示）。
     *
     * 只在值真的变化时写一次：绘制阶段写状态会安排下一帧重组，写完即稳定，
     * 不会持续刷新。静止场景的"不刷新"由绘制计数与逐像素稳定性共同守。
     *
     * **只记录"主玻璃"**：同一个背景来源可以被多个玻璃节点共享（例如主面板 +
     * 顶栏那排 48dp 按钮）。若每个节点都写，最后绘制的那个会把读数覆盖掉——
     * 模拟器取证里就出现过 `sampled=true glass=126x126`（那是按钮的尺寸），
     * 把主面板的读数挤掉了。见 [reportPlan]。
     */
    val lastPlan: BackdropSamplePlan get() = primaryReading.plan

    /** 和当前主节点的方案一起发布的效果构建结果。 */
    val primaryEffectHealth: BackdropEffectHealth get() = primaryReading.effectHealth
    internal val primaryDrawKey: Any? get() = primaryReading.drawKey

    /** 共享诊断，保留任一采样层的最近效果构建结果。 */
    val effectHealth: BackdropEffectHealth get() = effectState

    /** 已布局节点的当前面积。主节点变更会让接任节点重新上报。 */
    private val glassAreas = linkedMapOf<LiquidGlassBackdropSampling, Long>()
    private var primarySampling by mutableStateOf<LiquidGlassBackdropSampling?>(null)

    /** 当前主节点自己的绘制读数，采样句柄重新挂载后从零开始。 */
    val primaryLayerDrawCount: Int get() = primarySampling?.layerDrawCount ?: 0
    val primaryLastLayerDrawRevision: Int get() = primarySampling?.lastLayerDrawRevision ?: -1
    val primaryHasDrawnCurrentPlan: Boolean
        get() {
            val drawKey = primaryReading.drawKey ?: return false
            return primarySampling?.lastLayerDrawKey === drawKey
        }

    internal fun reportGlassArea(sampling: LiquidGlassBackdropSampling, areaPx: Long) {
        if (glassAreas.put(sampling, areaPx) != areaPx) updatePrimarySampling()
    }

    internal fun removeSampling(sampling: LiquidGlassBackdropSampling) {
        glassAreas.remove(sampling)
        if (primarySampling === sampling) {
            updatePrimarySampling()
        }
    }

    internal fun isPrimarySampling(sampling: LiquidGlassBackdropSampling): Boolean =
        primarySampling === sampling

    private fun updatePrimarySampling() {
        val largest = glassAreas.maxByOrNull { it.value }
        val current = primarySampling
        // 同面积时保留当前主节点，避免多个顶栏按钮互相覆盖读数。
        if (largest != null && current != null && largest.value == glassAreas[current]) return
        val next = largest?.key
        if (current === next) return

        primarySampling = next
        primaryReading = PrimaryReading()
    }

    internal fun reportPlan(
        sampling: LiquidGlassBackdropSampling,
        plan: BackdropSamplePlan,
        glassBoundsInWindow: IntRect?,
        effectHealth: BackdropEffectHealth,
        drawKey: Any?
    ) {
        if (!isPrimarySampling(sampling)) return
        primaryReading = PrimaryReading(plan, glassBoundsInWindow, effectHealth, drawKey)
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
 * 2. 采样层在自己的小层里按对齐后的原点、以**宿主尺寸**局部重绘（只覆盖玻璃大小）。
 *
 * **调用方必须用 `remember(...)` 稳定这个 lambda**：它在 `SideEffect` 里被挂到
 * [LiquidGlassBackdropState] 上，身份变化会被判定为"背景内容变了"并触发重画。
 * 每次重组都新建 lambda 的写法会让采样层每帧重画（而且本来就会破坏稳定性）。
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
        SideEffect { state.attachContent(null) }
        Box(modifier = modifier.drawBehind(background))
        return
    }

    // 背景 lambda 换了（换图 / 换绘制方式）必须重新挂到采样状态上，否则采样层
    // 会继续画上一张背景。挂接本身是状态写入，采样层随之重画一次。
    SideEffect {
        state.attachContent(background)
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow().toIntRect()
                if (state.sourceBounds != bounds) state.sourceBounds = bounds
                if (!state.boundsKnown) state.boundsKnown = true
            }
            .drawWithContent {
                drawContent()
                state.countHostDraw()
                background()
            }
    )
}

/** Rect → 整像素 IntRect；四边都向外取整，保证采样范围不缩水。 */
internal fun androidx.compose.ui.geometry.Rect.toIntRect(): IntRect = IntRect(
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
    internal val isPrimary: Boolean get() = source.isPrimarySampling(this)

    private val layerDraws = AtomicInteger(0)
    private val layerDrawRevision = AtomicInteger(-1)
    internal val layerDrawCount: Int get() = layerDraws.get()
    internal val lastLayerDrawRevision: Int get() = layerDrawRevision.get()
    @Volatile
    internal var lastLayerDrawKey: Any? = null
        private set

    internal fun recordLayerDraw(revision: Int, drawKey: Any) {
        layerDrawRevision.set(revision)
        lastLayerDrawKey = drawKey
        layerDraws.incrementAndGet()
        source.recordLayerDraw(revision)
    }

    internal var glassBoundsState by mutableStateOf<IntRect?>(null)

    internal fun onGlassPositioned(bounds: IntRect) {
        if (glassBoundsState != bounds) glassBoundsState = bounds
        // 面积最大的节点是"主玻璃"，由它向审计读数写方案。
        // 同一个背景来源上通常有主面板 + 一排 48dp 按钮；若不区分，最后绘制的
        // 按钮会把读数覆盖成 `glass=126x126`，探针就会误判主面板回退失败。
        source.reportGlassArea(this, bounds.width.toLong() * bounds.height.toLong())
    }

    /** 采样外扩（像素）：由外观参数决定，给模糊核与折射位移留真实内容。 */
    fun paddingPx(density: Float): Int = backdropPaddingPx(spec.resolve(density))

    /** 形状是否可采样；与尺寸无关，组合阶段即可判定。 */
    fun supportsShape(shape: Shape): Boolean = isBackdropShapeSupported(shape)

    /**
     * 玻璃下方的背景采样层。
     *
     * **永远只发射一个布局节点**：采样不成立时它画空，但仍然占住 Layout 里的
     * 那个子项。这样"采样句柄非空"与"子项存在"是同一件事，内容层下标不会漂移
     * （早期版本在采样不可用时 `return`，导致 Layout 用错误的下标测量内容，
     * `wrapContent` 直接塌成 0）。
     *
     * 关键约束（由模拟器取证确定，不要再改回去）：
     * - 采样层必须**自己拥有绘制内容**。整层内容若只是 `drawLayer(外部图层)`，
     *   `renderEffect` 不会作用于被引用的图层——实测自绘图案上 48px 模糊
     *   生效（26.9 万像素变化），换成引用外部背景图层后同一效果 0 像素变化。
     * - 因此这里用 [LiquidGlassBackdropState.content] 在层内局部重绘背景，
     *   而不是把整屏内容录成一张图再搬过来；离屏范围仍只有玻璃节点大小 + 外扩。
     * - 重绘只在几何、外观、背景 lambda 或背景版本变化时发生，静止画面不重画。
     *
     * 尺寸由父 `Layout` 决定（固定约束 = 玻璃尺寸 + 2 × padding），这里只管填满，
     * 因此"图层尺寸"与"玻璃尺寸"始终来自同一次测量，不存在两套尺寸。
     */
    @Composable
    internal fun BackdropLayer(
        shape: Shape,
        density: Density
    ) {
        val padding = paddingPx(density.density)
        val glassBounds = glassBoundsState
        val plan = planBackdropSample(
            sdkInt = Build.VERSION.SDK_INT,
            hasBackdropSource = source.hasContent,
            isRoundedRectShape = isBackdropShapeSupported(shape),
            glassBoundsInWindow = glassBounds,
            sourceBoundsInWindow = source.boundsOrNull(),
            paddingPx = padding,
            enabled = spec.enabled,
            // 查看器在翻页/放大/拖动时声明"此刻不可保证一致"，这里据此回退，
            // 而不是继续显示一张与屏幕错位的采样。
            viewerSync = spec.viewerSync
        )
        val appearance = spec.resolve(density.density)
        // 圆角半径由**玻璃实际尺寸**夹取（圆形正是取到短边一半），
        // 与 shader 里的夹取保持一致；尺寸变化会让 remember 键失效并重建效果。
        val shapeMetrics = backdropShapeMetrics(
            shape = shape,
            sizePx = Size(plan.glassWidthPx.toFloat(), plan.glassHeightPx.toFloat()),
            density = density
        )
        // remember 必须无条件调用（组合规则）；不可采样时返回 null，不建效果。
        val effect = remember(
            plan,
            appearance,
            shapeMetrics,
            spec.diagnosticBlurOnly
        ) {
            if (!plan.useSampledBackdrop) {
                null
            } else if (spec.diagnosticBlurOnly) {
                RenderEffect.createBlurEffect(48f, 48f, Shader.TileMode.CLAMP)
            } else {
                createBackdropRenderEffect(plan, appearance, shapeMetrics.cornerRadiusPx) { message ->
                    source.reportEffectError(message)
                }
            }
        }
        val health = when {
            !plan.useSampledBackdrop -> BackdropEffectHealth.Unknown
            effect != null -> BackdropEffectHealth.Ok
            else -> BackdropEffectHealth.Failed
        }
        val placement = backdropContentPlacement(
            plan = plan,
            sourceWidthPx = source.sourceSize.width,
            sourceHeightPx = source.sourceSize.height
        )
        // 三种"不可用"（方案不成立 / 效果建不起来 / 来源尺寸无效）都收敛到同一个
        // 结果：**仍然发射一个子项**，但什么都不画，让原有材质完整可见。
        // 这样"子项存在"由组合结构保证，而不是由"采样句柄非空"推断——
        // 早期版本在这里提前 return，导致 Layout 用错的下标测量内容，
        // wrapContent 塌成 0，界面上看不到内容却不报错。
        val active = effect != null && placement != null
        // 来源版本不一定随几何或效果变化，单独标记本次采样配置。
        val drawKey = remember(plan, effect, placement) { Any() }
        val reportPrimary = isPrimary
        SideEffect {
            if (reportPrimary) {
                source.reportPlan(this, plan, glassBounds, health, drawKey.takeIf { active })
            }
            source.reportEffectHealth(health)
        }
        val sourceSize = if (placement != null) {
            Size(placement.sourceWidthPx.toFloat(), placement.sourceHeightPx.toFloat())
        } else {
            Size.Zero
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (active) {
                        Modifier.graphicsLayer {
                            renderEffect = effect?.asComposeRenderEffect()
                            // 只夹到自己这一层（玻璃 + 外扩）；玻璃形状由父 Layout 的 clip 负责
                            clip = true
                        }
                    } else {
                        Modifier
                    }
                )
                .drawBehind {
                    if (!active || placement == null) return@drawBehind
                    // 在绘制阶段读版本号与背景 lambda：两者都是状态，
                    // 任一变化都会把这一笔绘制标脏并重画一次。
                    val revision = source.revision
                    val content = source.content ?: return@drawBehind
                    if (spec.diagnosticBlurOnly) {
                        for (i in 0 until 24) {
                            drawRect(
                                color = if (i % 2 == 0) Color.White else Color.Black,
                                topLeft = Offset(i * size.width / 24f, 0f),
                                size = Size(size.width / 24f, size.height)
                            )
                        }
                    } else {
                        translate(placement.translateX, placement.translateY) {
                            // 背景在自己的**来源尺寸**坐标系里局部重绘；
                            // 这里不带录屏截图，也不采玻璃自身。
                            content(FixedSizeDrawScope(this, sourceSize))
                        }
                    }
                    recordLayerDraw(revision, drawKey)
                }
        )
    }

}

/**
 * 把 `size` 固定成"背景来源尺寸"的 DrawScope 代理。
 *
 * 只覆盖 [size] / [center]；画布、变换、密度、字体加载器全部转发给真实 scope。
 * 这样背景 lambda 里的 `size.width`、`size.minDimension`、`drawCircle(radius =
 * size.minDimension * k)`、`Brush.linearGradient(end = Offset(1400f, 2200f))`
 * 之类都与宿主里完全一致，采样层才是"同一张背景平移"，而不是按玻璃尺寸重算。
 */
internal class FixedSizeDrawScope(
    private val delegate: DrawScope,
    private val fixedSize: Size
) : DrawScope by delegate {
    override val size: Size get() = fixedSize
    override val center: Offset get() = Offset(fixedSize.width / 2f, fixedSize.height / 2f)
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
    val sampling = remember(state, spec) {
        LiquidGlassBackdropSampling(state, spec)
    }
    DisposableEffect(sampling) {
        onDispose { state.removeSampling(sampling) }
    }
    return sampling
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
