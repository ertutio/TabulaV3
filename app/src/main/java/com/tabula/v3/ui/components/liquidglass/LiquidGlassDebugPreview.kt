package com.tabula.v3.ui.components.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabula.v3.ui.components.PhysicalLiquidGlassBox
import com.tabula.v3.ui.components.PhysicalLiquidGlassConfig

/**
 * 独立调试预览：液态玻璃背景采样层。
 *
 * 验收要求在这里逐条落地：
 * - 可移动的高对比网格 + 一张合成照片，两者都能拖动/滚动；
 * - 同一背景、同一组材质参数下切换"折射"开关；
 * - 玻璃之外的文字与图标保持清晰（它们不在采样图层里，也不经过 shader）；
 * - 滚动或改变尺寸时背景不错位、不闪黑、不采到玻璃自身；
 * - 页脚常驻显示当前采样方案（图层尺寸、对齐平移、回退原因），便于审计。
 */
@Composable
fun LiquidGlassDebugPreview(
    modifier: Modifier = Modifier,
    initialRefractionOn: Boolean = true,
    initialUsePhoto: Boolean = false,
    initialOffsetX: Float = 0f,
    initialOffsetY: Float = 0f,
    initialGlassHeightDp: Float = 160f
) {
    val state = rememberLiquidGlassBackdropState()
    var refractionOn by remember { mutableStateOf(initialRefractionOn) }
    var usePhoto by remember { mutableStateOf(initialUsePhoto) }
    var offsetX by remember { mutableFloatStateOf(initialOffsetX) }
    var offsetY by remember { mutableFloatStateOf(initialOffsetY) }
    var glassHeightDp by remember { mutableFloatStateOf(initialGlassHeightDp) }

    val spec = remember(refractionOn) {
        if (refractionOn) LiquidGlassBackdropSpec.Default else LiquidGlassBackdropSpec.Off
    }
    val config = remember {
        PhysicalLiquidGlassConfig.iOSLiquidGlass.copy(
            cornerRadius = 20.dp,
            blurRadius = 12.dp
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B14))
    ) {
        // 背景层：网格或合成照片，二者共用同一套偏移
        Box(modifier = Modifier.weight(1f)) {
            LiquidGlassBackdropHost(state = state, modifier = Modifier.fillMaxSize()) {
                if (usePhoto) {
                    LiquidGlassDebugSyntheticPhoto(offsetX = offsetX, offsetY = offsetY)
                } else {
                    LiquidGlassDebugGrid(offsetX = offsetX, offsetY = offsetY)
                }
            }

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

            // 采样方案审计读数
            SamplingAuditBadge(
                samplingSpec = spec,
                plan = state.lastPlan,
                health = state.effectHealth,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }

        // 控制面板
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF12131F))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ControlRow(label = "折射开关") {
                Switch(checked = refractionOn, onCheckedChange = { refractionOn = it })
            }
            ControlRow(label = "背景：合成照片") {
                Switch(checked = usePhoto, onCheckedChange = { usePhoto = it })
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

@Composable
private fun SamplingAuditBadge(
    samplingSpec: LiquidGlassBackdropSpec,
    plan: BackdropSamplePlan,
    health: BackdropEffectHealth,
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
        Text(
            text = plan.auditLine(),
            color = Color.White,
            fontSize = 10.sp
        )
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
            text = plan.auditMarkers(health),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp
        )
        Text(
            text = if (samplingSpec.enabled) {
                "折射带 ${samplingSpec.refractionHeightDp.value.toInt()}dp / 位移 ${samplingSpec.refractionAmountDp.value.toInt()}dp / 色散 ${samplingSpec.dispersion}"
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
        Spacer(modifier = Modifier.height(2.dp))
        Spacer(modifier = Modifier.width(0.dp))
    }
}

/**
 * 机器可读的审计标记，供 CI 从 uiautomator dump 里解析。
 * 形如：`AUDIT sampled=true glass=500x400 origin=(100,300) layer=520x420 pad=10 effect=Ok`
 */
internal fun BackdropSamplePlan.auditMarkers(health: BackdropEffectHealth? = null): String {
    val base = "AUDIT sampled=$useSampledBackdrop reason=$reason " +
        "glass=${glassWidthPx}x${glassHeightPx} " +
        "origin=(${glassOriginInSource.x},${glassOriginInSource.y}) " +
        "layer=${layerWidthPx}x${layerHeightPx} pad=$paddingPx"
    return if (health != null) "$base effect=$health" else base
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

/** 是否有必要让宿主建立背景图层：仅作可读性辅助，不参与渲染决策。 */
internal fun LiquidGlassBackdropState.isSamplingActive(): Boolean = contentLayer != null
