package com.tabula.v3.ui.components.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs

/**
 * 调试预览用的高对比网格。
 *
 * 目的：让"玻璃边缘把背景掰弯"变成肉眼可判的事。横竖线都是 1px 硬边，
 * 一旦经过玻璃边缘发生位移，线条会明显断开或弯折；网格本身不透明，也不
 * 依赖任何图片资源，因此在任何设备上都能复现。
 *
 * @param offsetX/offsetY 网格相对视口的像素偏移，用来模拟滚动或背景移动。
 */
/** 网格背景的绘制方式。同一个 lambda 会被宿主与采样层各画一次。 */
fun debugGridBackground(
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    cellSizePx: Float = 72f,
    lineWidthPx: Float = 2f
): DrawScope.() -> Unit {
    val palette = DebugGridPalette(
        background = Color(0xFF0E1020),
        major = Color(0xFFF6F8FF),
        minor = Color(0xFF4356A8),
        accent = Color(0xFFFFC857)
    )
    return {
        drawDebugGrid(palette, offsetX, offsetY, cellSizePx, lineWidthPx)
    }
}

internal data class DebugGridPalette(
    val background: Color,
    val major: Color,
    val minor: Color,
    val accent: Color
)

/**
 * 网格绘制本体。抽成 DrawScope 扩展，便于在预览与测试里确认线宽与间距。
 * 每 5 格一条强调线，让位移量更容易被读出来。
 */
internal fun DrawScope.drawDebugGrid(
    palette: DebugGridPalette,
    offsetX: Float,
    offsetY: Float,
    cellSizePx: Float,
    lineWidthPx: Float
) {
    drawRect(palette.background, Offset.Zero, Size(size.width, size.height))

    val step = cellSizePx.coerceAtLeast(8f)
    val startX = -(offsetX.mod(step))
    val startY = -(offsetY.mod(step))

    var x = startX
    var indexX = kotlin.math.floor((offsetX / step).toDouble()).toInt()
    while (x <= size.width) {
        val isMajor = indexX % 5 == 0
        drawLine(
            color = if (isMajor) palette.major else palette.minor,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = if (isMajor) lineWidthPx * 1.6f else lineWidthPx
        )
        if (isMajor) {
            drawAccentSquare(palette.accent, x, startY, lineWidthPx)
        }
        x += step
        indexX += 1
    }

    var y = startY
    var indexY = kotlin.math.floor((offsetY / step).toDouble()).toInt()
    while (y <= size.height) {
        val isMajor = indexY % 5 == 0
        drawLine(
            color = if (isMajor) palette.major else palette.minor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = if (isMajor) lineWidthPx * 1.6f else lineWidthPx
        )
        y += step
        indexY += 1
    }
}

private fun DrawScope.drawAccentSquare(color: Color, x: Float, y: Float, lineWidthPx: Float) {
    val s = lineWidthPx * 4f
    drawRect(color = color, topLeft = Offset(x - s / 2f, y - s / 2f), size = Size(s, s))
}

/**
 * 合成照片背景：用渐变 + 高对比几何块造一张"假照片"。
 *
 * 不读相册、不占磁盘、无需权限，因此可以作为固定的回归素材：
 * 同一张合成图在任何设备、任何一次运行里都一致。
 */
/** 合成照片背景的绘制方式；与网格同构，供宿主与采样层各画一次。 */
fun debugSyntheticPhotoBackground(
    offsetX: Float = 0f,
    offsetY: Float = 0f
): DrawScope.() -> Unit {
    val gradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF2B1055),
            Color(0xFF7597DE),
            Color(0xFFEF8A62),
            Color(0xFF1B998B)
        ),
        start = Offset(0f, 0f),
        end = Offset(1400f, 2200f)
    )
    return {
        drawRect(gradient, Offset.Zero, Size(size.width, size.height))
        drawSyntheticPhotoDetails(offsetX, offsetY)
    }
}

internal fun DrawScope.drawSyntheticPhotoDetails(offsetX: Float, offsetY: Float) {
    // 高对比同心圆：折射会让圆环在玻璃边缘处错位，便于目视判断
    val ringCenter = Offset(size.width * 0.28f + offsetX, size.height * 0.24f + offsetY)
    for (i in 1..6) {
        drawCircle(
            color = if (i % 2 == 0) Color(0xFFFFF3C4) else Color(0xFF120B24),
            radius = i * size.minDimension * 0.06f,
            center = ringCenter,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
        )
    }

    // 斜条纹带：边缘位移在斜线上最容易被看见
    val stripeTop = size.height * 0.55f + offsetY
    for (i in 0 until 10) {
        drawLine(
            color = if (i % 2 == 0) Color(0xFF0B0B0B) else Color(0xFFFFD400),
            start = Offset(offsetX + i * 90f, stripeTop),
            end = Offset(offsetX + i * 90f + 220f, stripeTop + size.height * 0.4f),
            strokeWidth = 22f
        )
    }

    // 角上的硬边方块：用于检查圆角与裁切有没有越界
    val boxSize = size.minDimension * 0.18f
    drawRect(
        color = Color(0xFFFF2E63),
        topLeft = Offset(size.width - boxSize * 1.2f + offsetX * 0.4f, size.height * 0.72f + offsetY * 0.4f),
        size = Size(boxSize, boxSize)
    )
    drawRect(
        color = Color(0xFF00E5FF),
        topLeft = Offset(size.width * 0.12f + offsetX * 0.4f, size.height * 0.80f + offsetY * 0.4f),
        size = Size(boxSize * 1.4f, boxSize * 0.7f)
    )
}

private fun Float.mod(divisor: Float): Float {
    if (divisor <= 0f) return 0f
    val r = this % divisor
    return if (r < 0f) r + divisor else r
}

/** 让网格偏移在同一循环内保持稳定：仅用于测试与预览的可读性。 */
internal fun gridPhase(offset: Float, cellSizePx: Float): Int {
    if (cellSizePx <= 0f) return 0
    return kotlin.math.floor(abs(offset) / cellSizePx).toInt()
}
