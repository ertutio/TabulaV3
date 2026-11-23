package com.tabula.v3.ui.components.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabula.v3.ui.components.PhysicalLiquidGlassBox
import com.tabula.v3.ui.components.PhysicalLiquidGlassConfig

/** 调试预览里可切换的玻璃形状；用来覆盖"非圆角形状回退"与圆形采样。 */
enum class DebugGlassShape(val label: String) {
    RoundedRect("圆角矩形"),
    Circle("圆形"),
    CutCorner("切角(应回退)");

    fun toShape(cornerRadiusDp: Float): Shape = when (this) {
        RoundedRect -> RoundedCornerShape(cornerRadiusDp.dp)
        Circle -> CircleShape
        CutCorner -> CutCornerShape(cornerRadiusDp.dp)
    }
}

/** 调试预览里可切换的背景来源。 */
enum class DebugBackdropKind(val label: String) {
    Grid("网格"),
    Photo("合成照片"),
    SizeDependent("尺寸相关(圆+渐变)");

    fun draw(offsetX: Float, offsetY: Float, phase: Int): DrawScope.() -> Unit = when (this) {
        Grid -> debugGridBackground(offsetX, offsetY)
        Photo -> debugSyntheticPhotoBackground(offsetX, offsetY)
        SizeDependent -> debugSizeDependentBackground(offsetX, offsetY, phase)
    }
}

/**
 * 独立调试预览：液态玻璃背景采样层。
 *
 * 验收要求在这里逐条落地：
 * - 可移动的高对比网格 / 合成照片 / **尺寸相关背景**（圆形半径与渐变端点都取自
 *   `size`，用来验证采样层用的是宿主尺寸而不是玻璃尺寸）；
 * - **宿主与玻璃尺寸可不同**（宿主缩放到 60%~100%），验证两者尺寸契约一致；
 * - 同一背景、同一组材质参数下**只切换折射位移**（模糊、采样范围、背景都不变），
 *   用来做"边缘位移 / 中心不动"的对照；
 * - 玻璃之外的文字与图标保持清晰（它们不在采样图层里，也不经过 shader）；
 * - 滚动 / 改尺寸 / 换背景 / 移动图案时背景不错位、不闪黑、不采到玻璃自身；
 * - 页脚常驻显示采样方案、宿主与玻璃尺寸、以及**绘制次数**，便于审计"到底刷新
 *   了几次"，而不是靠两张截图相同来冒充静止。
 */
@Composable
fun LiquidGlassDebugPreview(
    modifier: Modifier = Modifier,
    initialRefractionOn: Boolean = true,
    initialUsePhoto: Boolean = false,
    initialOffsetX: Float = 0f,
    initialOffsetY: Float = 0f,
    initialGlassHeightDp: Float = 160f,
    initialRefractionAmountDp: Float = 10f,
    initialShape: DebugGlassShape = DebugGlassShape.RoundedRect,
    initialBackdropKind: DebugBackdropKind? = null,
    initialHostScalePercent: Int = 100,
    diagnosticBlurOnly: Boolean = false
) {
    val state = rememberLiquidGlassBackdropState()
    var refractionOn by remember { mutableStateOf(initialRefractionOn) }
    var backdropKind by remember {
        mutableStateOf(
            initialBackdropKind
                ?: if (initialUsePhoto) DebugBackdropKind.Photo else DebugBackdropKind.Grid
        )
    }
    var offsetX by remember { mutableFloatStateOf(initialOffsetX) }
    var offsetY by remember { mutableFloatStateOf(initialOffsetY) }
    var glassHeightDp by remember { mutableFloatStateOf(initialGlassHeightDp) }
    var refractionAmountDp by remember { mutableFloatStateOf(initialRefractionAmountDp) }
    var shape by remember { mutableStateOf(initialShape) }
    var hostScalePercent by remember { mutableIntStateOf(initialHostScalePercent) }
    var patternPhase by remember { mutableIntStateOf(0) }

    val spec = remember(refractionOn, refractionAmountDp, diagnosticBlurOnly) {
        when {
            !refractionOn -> LiquidGlassBackdropSpec.Off
            diagnosticBlurOnly -> LiquidGlassBackdropSpec.Default.copy(
                refractionAmountDp = refractionAmountDp.dp,
                diagnosticBlurOnly = true
            )
            else -> LiquidGlassBackdropSpec.Default.copy(refractionAmountDp = refractionAmountDp.dp)
        }
    }
    val config = remember {
        // 预览页关掉表面装饰性动画（`subtleMotionEnabled`），否则整块玻璃每帧都在
        // 重画，"静止时绘制次数不变"这条指标就永远不成立。生产配置不变：
        // `PhysicalLiquidGlassConfig.iOSLiquidGlass` 仍然开着它。
        PhysicalLiquidGlassConfig.iOSLiquidGlass.copy(
            cornerRadius = 20.dp,
            blurRadius = 12.dp,
            subtleMotionEnabled = false
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B14))
    ) {
        // 背景层：宿主尺寸可缩放，玻璃尺寸独立——两者不同才能验证尺寸契约
        Box(modifier = Modifier.weight(1f)) {
            val background: DrawScope.() -> Unit =
                remember(backdropKind, offsetX, offsetY, patternPhase) {
                    backdropKind.draw(offsetX, offsetY, patternPhase)
                }
            LiquidGlassBackdropHost(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(hostScalePaddingDp(hostScalePercent)),
                background = background
            )

            // 玻璃节点 + 前景文字图标：全部在背景图层之外
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 40.dp)
                    .align(Alignment.TopCenter)
            ) {
                PhysicalLiquidGlassBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(glassHeightDp.dp),
                    shape = shape.toShape(config.cornerRadius.value),
                    config = config,
                    backdropState = state,
                    backdropSpec = spec,
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Photo,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = "前景文字保持清晰",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            // 这里刻意不显示折射开关状态：玻璃内容必须在两种状态下
                            // 逐像素一致，否则前后差异会混进文字变化，无法证明折射生效。
                            Text(
                                text = "AAA 123 · AAA 123",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            SamplingAuditBadge(
                samplingSpec = spec,
                plan = state.lastPlan,
                health = state.effectHealth,
                revision = state.revision,
                hostDrawCount = state.hostDrawCount,
                layerDrawCount = state.layerDrawCount,
                hostSize = state.sourceSize,
                glassWindow = state.lastGlassWindow,
                shapeLabel = shape.label,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF12131F))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ControlRow(label = "折射开关") {
                Switch(checked = refractionOn, onCheckedChange = { refractionOn = it })
            }
            // 只改位移：模糊、采样范围、背景都不变，用来做"边缘位移 / 中心不动"的对照
            LabeledSlider(
                label = "折射位移 ${refractionAmountDp.toInt()}dp",
                value = refractionAmountDp,
                valueRange = 0f..24f,
                onChange = { refractionAmountDp = it }
            )
            ControlRow(label = "背景：${backdropKind.label}") {
                DebugButton("切换背景") {
                    backdropKind = when (backdropKind) {
                        DebugBackdropKind.Grid -> DebugBackdropKind.Photo
                        DebugBackdropKind.Photo -> DebugBackdropKind.SizeDependent
                        DebugBackdropKind.SizeDependent -> DebugBackdropKind.Grid
                    }
                }
            }
            ControlRow(label = "形状：${shape.label}") {
                DebugButton("切换形状") {
                    shape = when (shape) {
                        DebugGlassShape.RoundedRect -> DebugGlassShape.Circle
                        DebugGlassShape.Circle -> DebugGlassShape.CutCorner
                        DebugGlassShape.CutCorner -> DebugGlassShape.RoundedRect
                    }
                }
            }
            ControlRow(label = "宿主缩放 ${hostScalePercent}%") {
                DebugButton("改宿主尺寸") {
                    hostScalePercent = if (hostScalePercent >= 100) 60 else hostScalePercent + 20
                }
            }
            ControlRow(label = "图案相位 $patternPhase") {
                DebugButton("移动图案") {
                    patternPhase += 1
                    // 背景内容变了：显式声明，采样层必须重画一次
                    state.invalidateContent()
                }
            }
            LabeledSlider(
                label = "水平移动 ${offsetX.toInt()}px",
                value = offsetX,
                valueRange = -400f..400f,
                onChange = { offsetX = it }
            )
            LabeledSlider(
                label = "垂直移动 ${offsetY.toInt()}px",
                value = offsetY,
                valueRange = -800f..800f,
                onChange = { offsetY = it }
            )
            LabeledSlider(
                label = "玻璃高度 ${glassHeightDp.toInt()}dp",
                value = glassHeightDp,
                valueRange = 72f..320f,
                onChange = { glassHeightDp = it }
            )
        }
    }
}

/** 宿主缩放到 [percent]% 时四周留出的 padding。 */
private fun hostScalePaddingDp(percent: Int): androidx.compose.ui.unit.Dp {
    val clamped = percent.coerceIn(30, 100)
    return ((100 - clamped) * 1.2f).dp
}

@Composable
private fun DebugButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0xFF2A2C42), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = Color(0xFFFFD400), fontSize = 12.sp)
    }
}

@Composable
private fun SamplingAuditBadge(
    samplingSpec: LiquidGlassBackdropSpec,
    plan: BackdropSamplePlan,
    health: BackdropEffectHealth,
    revision: Int,
    hostDrawCount: Int,
    layerDrawCount: Int,
    hostSize: IntSize,
    glassWindow: IntRect?,
    shapeLabel: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = "背景采样层",
            color = Color(0xFFFFD400),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Text(text = plan.auditLine(), color = Color.White, fontSize = 10.sp)
        Text(
            text = "effect=$health",
            color = when (health) {
                BackdropEffectHealth.Ok -> Color(0xFF7CFF9B)
                BackdropEffectHealth.Failed -> Color(0xFFFF6B6B)
                BackdropEffectHealth.Unknown -> Color(0xFFFFD400)
            },
            fontSize = 10.sp
        )
        Text(
            text = plan.auditMarkers(
                health = health,
                hostWidth = hostSize.width,
                hostHeight = hostSize.height,
                hostDraws = hostDrawCount,
                layerDraws = layerDrawCount,
                revision = revision,
                shapeLabel = shapeLabel,
                screenX = glassWindow?.left,
                screenY = glassWindow?.top
            ),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp
        )
        Text(
            text = if (samplingSpec.enabled) {
                "折射带 ${samplingSpec.refractionHeightDp.value.toInt()}dp / " +
                    "位移 ${samplingSpec.refractionAmountDp.value.toInt()}dp / " +
                    "色散 ${samplingSpec.dispersion} / 模糊 ${samplingSpec.blurRadiusDp.value.toInt()}dp"
            } else {
                "折射已关闭：完全回到接入前的画面"
            },
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun ControlRow(
    label: String,
    control: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.White, fontSize = 13.sp)
        control()
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        Slider(value = value, onValueChange = onChange, valueRange = valueRange)
        Box(modifier = Modifier.height(2.dp))
    }
}

/**
 * 机器可读的审计标记，供 CI 从 uiautomator dump 里解析。
 *
 * 形如：
 * `AUDIT sampled=true reason=NONE glass=500x400 origin=(100,300) layer=540x440 pad=20
 *  host=1080x1800 screen=(63,105) hostdraw=3 layerdraw=3 rev=0 shape=圆角矩形 effect=Ok`
 *
 * - `screen` 是**玻璃在窗口坐标系**里的左上角，探针据此裁 ROI，不再靠猜页脚内边距；
 * - `hostdraw` / `layerdraw` 是**绘制次数**：静止时两次 dump 之间必须不变；换背景 /
 *   移动图案 / 改尺寸后必须增加。这才是"绘制失效真的接上了"的证据，而不是拿
 *   "两张截图相同"冒充刷新次数验证。
 */
internal fun BackdropSamplePlan.auditMarkers(
    health: BackdropEffectHealth? = null,
    hostWidth: Int = 0,
    hostHeight: Int = 0,
    hostDraws: Int = 0,
    layerDraws: Int = 0,
    revision: Int = 0,
    shapeLabel: String = "",
    screenX: Int? = null,
    screenY: Int? = null
): String {
    val base = "AUDIT sampled=$useSampledBackdrop reason=$reason " +
        "glass=${glassWidthPx}x${glassHeightPx} " +
        "origin=(${glassOriginInSource.x},${glassOriginInSource.y}) " +
        "layer=${layerWidthPx}x${layerHeightPx} pad=$paddingPx " +
        "host=${hostWidth}x${hostHeight} " +
        (if (screenX != null && screenY != null) "screen=($screenX,$screenY) " else "") +
        "hostdraw=$hostDraws layerdraw=$layerDraws rev=$revision"
    val withShape = if (shapeLabel.isNotEmpty()) "$base shape=$shapeLabel" else base
    return if (health != null) "$withShape effect=$health" else withShape
}

/** 供预览/测试读取的方案摘要：一行可粘贴进 PR 的文本。 */
internal fun BackdropSamplePlan.auditLine(): String = when (status) {
    BackdropSamplingStatus.SAMPLED ->
        "SAMPLED layer=${layerWidthPx}x${layerHeightPx} pad=$paddingPx " +
            "offset=(${sourceTranslateX.toInt()},${sourceTranslateY.toInt()}) " +
            "glass=${glassWidthPx}x${glassHeightPx} origin=(${glassOriginInSource.x},${glassOriginInSource.y})"

    BackdropSamplingStatus.ORIGINAL ->
        "ORIGINAL reason=$reason"
}

/** 背景是否已可采样：仅作可读性辅助，不参与渲染决策。 */
internal fun LiquidGlassBackdropState.isSamplingActive(): Boolean = hasContent
