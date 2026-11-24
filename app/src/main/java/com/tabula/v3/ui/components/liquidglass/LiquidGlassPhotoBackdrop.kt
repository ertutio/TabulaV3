package com.tabula.v3.ui.components.liquidglass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlin.math.min

/**
 * 把真实照片接成背景采样层的来源。
 *
 * 生产页面（例如回收站全屏查看器）的照片由 Coil 加载、由 `AsyncImage` 交互式绘制；
 * 采样层需要的是"能用同一个 lambda 重画一次"的背景，因此这里另外取一份
 * **同一张图的 Bitmap**（Coil 内存缓存命中，不重复解码），由宿主与采样层各画一次。
 *
 * 两条约束：
 * 1. **缩放要一致**：宿主与采样层都用 [fitInsideRect] 算目标矩形，采样层里
 *    `DrawScope.size` 又是宿主尺寸（见 [FixedSizeDrawScope]），因此两边得到的是
 *    同一个矩形、同一套裁切；
 * 2. **降采样要克制**：只为采样层取一张不超过屏幕宽度的 Bitmap，
 *    不把原图整张读回内存（[rememberBackdropPhoto] 里 `size(...)`）。
 */

/** `ContentScale.Fit` 的结果矩形（像素）。 */
data class FitInsideRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * 按 `ContentScale.Fit` 把 [contentWidth] × [contentHeight] 的内容放进
 * [boxWidth] × [boxHeight]，居中留边。
 *
 * 与 Compose 的 `ContentScale.Fit` 同义：等比缩放到**刚好放得下**，不裁切。
 * 尺寸非法（任一为 0）时返回 null，调用方跳过绘制而不是画一个 0 尺寸矩形。
 */
fun fitInsideRect(
    contentWidth: Int,
    contentHeight: Int,
    boxWidth: Float,
    boxHeight: Float
): FitInsideRect? {
    if (contentWidth <= 0 || contentHeight <= 0) return null
    if (boxWidth <= 0f || boxHeight <= 0f) return null
    val scale = min(boxWidth / contentWidth, boxHeight / contentHeight)
    val width = contentWidth * scale
    val height = contentHeight * scale
    return FitInsideRect(
        left = (boxWidth - width) / 2f,
        top = (boxHeight - height) / 2f,
        width = width,
        height = height
    )
}

/**
 * 照片背景的绘制方式：先铺底色，再按 Fit 画照片，最后铺顶部遮罩。
 *
 * 底色用不透明黑，与全屏查看器的既有底一致——这样"照片之外的区域"在宿主与采样层
 * 里是同一个颜色，玻璃边缘采到的也是屏幕上真实存在的内容（而不是透明或黑边）。
 *
 * [topScrimHeightPx] 是顶部渐变遮罩的高度。屏幕上的遮罩画在照片之上、按钮之下，
 * 因此采样层必须**同样**把遮罩画进背景，否则玻璃采到的是未经压暗的照片，
 * 会比周围亮一截——那种差异会被误读成"折射算错了"。
 */
fun DrawScope.drawPhotoBackdrop(
    bitmap: Bitmap?,
    background: Color = Color.Black,
    topScrimHeightPx: Float = 0f,
    topScrimAlpha: Float = 0.5f
) {
    drawRect(background, Offset.Zero, Size(size.width, size.height))
    if (bitmap != null && !bitmap.isRecycled) {
        val target = fitInsideRect(
            contentWidth = bitmap.width,
            contentHeight = bitmap.height,
            boxWidth = size.width,
            boxHeight = size.height
        )
        if (target != null) {
            drawImage(
                image = bitmap.asImageBitmap(),
                srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                srcSize = androidx.compose.ui.unit.IntSize(bitmap.width, bitmap.height),
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    target.left.toInt(),
                    target.top.toInt()
                ),
                dstSize = androidx.compose.ui.unit.IntSize(
                    target.width.toInt().coerceAtLeast(1),
                    target.height.toInt().coerceAtLeast(1)
                ),
                filterQuality = FilterQuality.Low
            )
        }
    }
    if (topScrimHeightPx > 0.5f) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    background.copy(alpha = topScrimAlpha),
                    Color.Transparent
                ),
                startY = 0f,
                endY = topScrimHeightPx
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, topScrimHeightPx)
        )
    }
}

/**
 * 顶部遮罩的高度（像素）：状态栏 inset + 按钮高度 + 上下内边距。
 *
 * 与屏幕上的遮罩用同一套数值，采样层才不会在按钮附近采到"没被压暗的照片"。
 */
fun topScrimHeightPx(
    statusBarInsetPx: Float,
    buttonHeightPx: Float,
    verticalPaddingPx: Float
): Float = (statusBarInsetPx + buttonHeightPx + verticalPaddingPx * 2f).coerceAtLeast(0f)

/**
 * 取一张用于背景采样的照片 Bitmap。
 *
 * - 走 Coil 的同一个 `ImageLoader`：已显示的页面通常已在内存缓存里，不会重复解码；
 * - 请求里带 `size(maxWidthPx)`：只为采样层要一张不超过屏幕宽度的图，
 *   不把原图整张读回内存；
 * - `allowHardware(false)`：采样层会把它画进带 `renderEffect` 的图层，
 *   硬件位图在部分驱动上不能作为该图层的源；
 * - 加载失败（图被删、解码失败、URI 失效）时保持 null：背景只剩底色，
 *   采样方案仍成立，但画面就是纯色——这属于"来源不可用"的正常回退，不抛异常。
 */
@Composable
fun rememberBackdropPhoto(
    data: Any?,
    maxWidthPx: Int
): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(data, maxWidthPx) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(data, maxWidthPx) {
        bitmap = null
        if (data == null || maxWidthPx <= 0) return@LaunchedEffect
        bitmap = loadBackdropBitmap(context, data, maxWidthPx)
    }
    return bitmap
}

/** [rememberBackdropPhoto] 的非 Composable 版本，便于在测试里直接验证加载约定。 */
suspend fun loadBackdropBitmap(
    context: Context,
    data: Any,
    maxWidthPx: Int
): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(data)
        .size(maxWidthPx.coerceAtLeast(1))
        .allowHardware(false)
        .build()
    val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
    val drawable = (result as? SuccessResult)?.drawable ?: return null
    return when (drawable) {
        is BitmapDrawable -> drawable.bitmap
        else -> null
    }
}

/**
 * 采样层可用的照片来源判定。
 *
 * 三道门控缺一不可：实验室开关（生产入口的 Android 15+ 判定由调用方保证）、
 * 系统达到采样门槛、确实有一张可画的照片。任一不满足就完全回到接入前的画面，
 * 不是"少画一层"而是"行为和接入前逐像素一致"。
 */
fun shouldAttachPhotoBackdrop(
    liquidGlassEnabled: Boolean,
    sdkInt: Int,
    hasPhoto: Boolean
): Boolean = liquidGlassEnabled &&
    sdkInt >= LIQUID_GLASS_BACKDROP_MIN_SDK &&
    hasPhoto

