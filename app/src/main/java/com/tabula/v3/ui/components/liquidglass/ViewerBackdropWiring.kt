package com.tabula.v3.ui.components.liquidglass

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import android.os.Build

/**
 * 查看器背景采样接线的**共用部分**（回收站查看器与普通查看器都用这一段）。
 *
 * ## 为什么抽出来
 *
 * 两个查看器的接线原本写在 `RecycleBinScreen` 里：门控 → 加载 → 采样同步 → 遮罩高度。
 * 普通查看器要接同一套时，如果把这段复制过去，就会出现两份「先判门控再加图片请求」的
 * 顺序约定——而那个顺序正是之前修过的真实缺陷（早期实现先无条件加载、后判门控，
 * 功能关着也照样发一次 Coil 请求）。抽成一处后，两条路径不可能再分叉。
 *
 * ## 只抽「确实共用」的四件东西
 *
 * 1. [shouldLoadBackdropPhoto]：两层门控（开关 + 系统版本）合并成一个「要不要发图片请求」；
 * 2. [rememberViewerBackdropPhoto]：按门控结果决定是否真的把 `data` 交给 Coil；
 * 3. [viewerBackdropScrimHeightPx]：顶部遮罩高度（状态栏 inset + 按钮高 + 垂直内边距）；
 * 4. [rememberViewerBackdropWiring]：把上面三件按固定顺序拼成一次调用。
 *
 * 刻意**不抽**的部分：采样层外观参数（`LiquidGlassBackdropSpec`）与采样状态持有方式。
 * 回收站查看器要在顶栏暴露审计出口，普通查看器不暴露；两者对「谁持有 state」的需求不同，
 * 强行统一会把审计出口绑进共享代码。这两处各自保留。
 */
object ViewerBackdropWiring {

    /**
     * 要不要为采样加载照片。
     *
     * **必须在加载之前调用**：返回 false 时调用方不该把 `data` 交给加载器，
     * 这样关闭状态或系统不支持时不会发出任何图片请求（解码与内存占用都省掉）。
     *
     * 与 [shouldAttachPhotoBackdrop] 的区别：那个回答「此刻能不能把照片接进采样层」，
     * 这个回答「要不要为此去解码一张图」。前者依赖 `hasPhoto`（已经加载好了），
     * 后者必须在此之前做决定，因此不看 `hasPhoto`。
     */
    fun shouldLoadBackdropPhoto(
        liquidGlassEnabled: Boolean,
        sdkInt: Int
    ): Boolean = liquidGlassEnabled && sdkInt >= MIN_SDK_FOR_BACKDROP

    /** 玻璃采样依赖的系统版本下限（与主题层的门控同源）。 */
    const val MIN_SDK_FOR_BACKDROP = 35
}

/**
 * 按门控结果决定是否真的加载采样照片。
 *
 * @param gateOpen [ViewerBackdropWiring.shouldLoadBackdropPhoto] 的结果；
 *   false 时 `data` 传 null，[rememberBackdropPhoto] 里 `LaunchedEffect` 会直接 return，
 *   Coil 请求不会发出。
 * @param data 当前页照片的 URI（只在 [gateOpen] 为真时才被使用）
 */
@Composable
fun rememberViewerBackdropPhoto(
    gateOpen: Boolean,
    data: Any?
): android.graphics.Bitmap? {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val maxWidthPx = if (gateOpen) {
        configuration.screenWidthDp * density.density.toInt().coerceAtLeast(1)
    } else {
        0
    }
    return rememberBackdropPhoto(
        data = if (gateOpen) data else null,
        maxWidthPx = maxWidthPx
    )
}

/**
 * 顶部遮罩高度：采样层必须把屏幕上的顶部压暗一并画进背景，
 * 否则玻璃采到的是没压暗的照片、比周围亮一截，会被误读成折射算错。
 */
@Composable
fun viewerBackdropScrimHeightPx(
    statusBarInsetPx: Float,
    buttonSizeDp: Float = 48f,
    verticalPaddingDp: Float = 8f
): Float {
    val density = LocalDensity.current
    return topScrimHeightPx(
        statusBarInsetPx = statusBarInsetPx,
        buttonHeightPx = buttonSizeDp * density.density,
        verticalPaddingPx = verticalPaddingDp * density.density
    )
}

/** [rememberViewerBackdropWiring] 的返回值。 */
data class ViewerBackdropWiringResult(
    /** 门控是否打开（关闭时不应有任何图片请求）。 */
    val gateOpen: Boolean,
    /** 采样用位图；未就绪/门控关闭/加载失败时为 null。 */
    val photo: android.graphics.Bitmap?,
    /** 顶部遮罩高度（px）。 */
    val scrimHeightPx: Float
)

/**
 * 一次拿到查看器采样接线需要的三样东西，顺序固定为
 * **门控 → 加载 → 遮罩高度**。
 *
 * 复用这个函数的调用方不可能再把「先加载后门控」的顺序写反。
 */
@Composable
fun rememberViewerBackdropWiring(
    liquidGlassEnabled: Boolean,
    currentPhotoData: Any?,
    statusBarInsetPx: Float,
    sdkInt: Int = Build.VERSION.SDK_INT
): ViewerBackdropWiringResult {
    val gateOpen = ViewerBackdropWiring.shouldLoadBackdropPhoto(
        liquidGlassEnabled = liquidGlassEnabled,
        sdkInt = sdkInt
    )
    val photo = rememberViewerBackdropPhoto(gateOpen = gateOpen, data = currentPhotoData)
    val scrimHeightPx = viewerBackdropScrimHeightPx(statusBarInsetPx = statusBarInsetPx)
    return ViewerBackdropWiringResult(
        gateOpen = gateOpen,
        photo = photo,
        scrimHeightPx = scrimHeightPx
    )
}

/** 采样层默认用的背景视图修饰符尺寸基准（48dp 按钮 + 8dp 内边距）。 */
internal val DefaultViewerButtonSizeDp = 48.dp
