package com.tabula.v3.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import com.tabula.v3.ui.components.ActionIconButton
import com.tabula.v3.ui.components.LocalLiquidGlassEnabled
import com.tabula.v3.ui.components.liquidglass.LiquidGlassPhotoBackdropViewer
import com.tabula.v3.ui.components.liquidglass.auditMarkers
import com.tabula.v3.ui.components.liquidglass.rememberLiquidGlassBackdropState

/**
 * **生产接线**的调试预览：直接渲染 [LiquidGlassPhotoBackdropViewer]。
 *
 * 与 `LiquidGlassDebugPreview` 的分工：
 * - 那个测采样层本身（网格 / 形状 / 只改位移的对照）；
 * - 这个测**生产页面的接线**——同一个组件、同一套门控、同一个 CompositionLocal
 *   下发，只是把照片换成合成 Bitmap。
 *
 * 于是模拟器取证覆盖的就是回收站全屏查看器实际跑的代码路径：
 * 顶部按钮真的能从背景采样、背景移动后仍对齐、开关关闭或来源不可用时回退。
 *
 * @param labSwitchOn 对应生产入口的实验室开关；关闭时整条接线不生效
 * @param photoAvailable false 时模拟"来源不可用"（照片被删/解码失败）
 * @param photoOffsetX/photoOffsetY 合成照片内容的平移，用来模拟背景移动
 * @param topScrimHeightPx 顶部渐变遮罩高度；与生产同一套数值
 */
@Composable
fun LiquidGlassProductionWiringPreview(
    modifier: Modifier = Modifier,
    labSwitchOn: Boolean = true,
    photoAvailable: Boolean = true,
    photoOffsetX: Int = 0,
    photoOffsetY: Int = 0,
    topScrimHeightPx: Float = 180f
) {
    val photo = remember(photoAvailable, photoOffsetX, photoOffsetY) {
        if (photoAvailable) syntheticBackdropBitmap(photoOffsetX, photoOffsetY) else null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B14))
    ) {
        Box(modifier = Modifier.weight(1f)) {
            // 生产组件本体：回收站全屏查看器用的就是这一个。
            // `LocalLiquidGlassEnabled` 在生产里由 TabulaTheme 提供（实验室开关 +
            // SDK>=35），这里按同样的语义把它打开/关闭，否则 ActionIconButton
            // 会整条走普通 Box 路径，采样根本不会发生。
            val auditState = rememberLiquidGlassBackdropState()
            val sampled = CompositionLocalProvider(
                LocalLiquidGlassEnabled provides labSwitchOn
            ) {
                LiquidGlassPhotoBackdropViewer(
                    photo = photo,
                    liquidGlassEnabled = labSwitchOn,
                    scrimHeightPx = topScrimHeightPx,
                    modifier = Modifier.fillMaxSize(),
                    state = auditState,
                    content = {
                        // 真实页面里这里是 HorizontalPager；预览里背景已是照片，留空
                        Box(modifier = Modifier.fillMaxSize())
                    },
                    topBar = { sampled -> ProductionTopBar(sampled = sampled) }
                ).sampled
            }

            // 审计读数：探针从这里解析 sampled / hostdraw / layerdraw / reason。
            // 数值来自生产组件的真实状态，不是另编的常量。
            val plan = auditState.lastPlan
            Text(
                text = plan.auditMarkers(
                    health = auditState.effectHealth,
                    hostWidth = auditState.sourceSize.width,
                    hostHeight = auditState.sourceSize.height,
                    hostDraws = auditState.hostDrawCount,
                    layerDraws = auditState.layerDrawCount,
                    revision = auditState.revision,
                    shapeLabel = "production-wiring",
                    screenX = auditState.lastGlassWindow?.left,
                    screenY = auditState.lastGlassWindow?.top
                ) + " wiring=$sampled",
                color = Color(0xFFFFD400),
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(6.dp)
            )
        }

        Text(
            text = "生产接线预览：开关=${if (labSwitchOn) "开" else "关"} " +
                "照片=${if (photoAvailable) "可用" else "不可用"} " +
                "背景平移=($photoOffsetX,$photoOffsetY) " +
                "API=${Build.VERSION.SDK_INT}",
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF12131F))
                .padding(8.dp)
        )
    }
}

/** 生产顶栏形态：采样可用时走玻璃按钮，否则回退成原始 IconButton。 */
@Composable
private fun ProductionTopBar(sampled: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        if (sampled) {
            ActionIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = "关闭（生产接线）",
                onClick = {},
                backgroundColor = Color.White.copy(alpha = 0.9f),
                iconColor = Color.White,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ActionIconButton(
                    icon = Icons.Outlined.Share,
                    contentDescription = "分享（生产接线）",
                    onClick = {},
                    backgroundColor = Color.White.copy(alpha = 0.9f),
                    iconColor = Color.White
                )
                ActionIconButton(
                    icon = Icons.Outlined.Delete,
                    contentDescription = "删除（生产接线）",
                    onClick = {},
                    backgroundColor = Color.White.copy(alpha = 0.9f),
                    iconColor = Color.White
                )
                ActionIconButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "设置（生产接线）",
                    onClick = {},
                    backgroundColor = Color.White.copy(alpha = 0.9f),
                    iconColor = Color.White
                )
            }
        } else {
            IconButton(
                onClick = {},
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "关闭（回退形态）",
                    tint = Color.White
                )
            }
        }

        Text(
            text = "1 / 3",
            color = Color.White,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/**
 * 合成一张"照片"：渐变 + 同心圆 + 斜条纹 + 对角硬边。
 *
 * 生产接线吃的是 Bitmap（Coil 解码结果），用真 Bitmap 才能验证同一段代码路径。
 * 几何全部由 Bitmap 尺寸决定，因此"背景尺寸"与"玻璃尺寸"是两套值——
 * 正是尺寸契约要验证的情形。
 */
fun syntheticBackdropBitmap(
    offsetX: Int = 0,
    offsetY: Int = 0,
    width: Int = 720,
    height: Int = 1280
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = 0xFF101A3A.toInt()
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

    paint.shader = LinearGradient(
        0f, 0f, width.toFloat(), height.toFloat(),
        intArrayOf(
            0xFF2E7DFF.toInt(),
            0xFF7CFFB2.toInt(),
            0xFFFFC857.toInt(),
            0xFF3A1030.toInt()
        ),
        null,
        Shader.TileMode.CLAMP
    )
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    paint.shader = null

    val minDim = minOf(width, height).toFloat()
    val cx = width * 0.5f + offsetX
    val cy = height * 0.42f + offsetY
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = minDim * 0.012f
    for (i in 1..7) {
        paint.color = if (i % 2 == 0) 0xFF0B0B14.toInt() else 0xFFF7FAFF.toInt()
        canvas.drawCircle(cx, cy, minDim * 0.055f * i, paint)
    }
    paint.style = Paint.Style.FILL

    val columns = 12
    val columnWidth = width / columns
    paint.color = 0x33FFFFFF
    for (i in 0 until columns step 2) {
        canvas.drawRect(
            i * columnWidth.toFloat(),
            height * 0.78f + offsetY * 0.3f,
            (i + 0.86f) * columnWidth,
            height * 0.94f + offsetY * 0.3f,
            paint
        )
    }

    paint.color = 0xFFFF2E63.toInt()
    paint.strokeWidth = minDim * 0.02f
    canvas.drawLine(
        0f, height * 0.62f + offsetY * 0.2f,
        width.toFloat(), height * 0.30f + offsetY * 0.2f,
        paint
    )

    return bitmap
}
