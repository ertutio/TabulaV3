package com.tabula.v3.ui.components.liquidglass

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.tabula.v3.ui.components.LocalLiquidGlassBackdrop

/**
 * "真实照片铺底 + 顶部玻璃按钮"的生产形态。
 *
 * 抽成一个组件，是为了让**生产页面**与**调试预览**跑同一段接入代码：
 * 生产页面（回收站全屏查看器）传入 MediaStore 照片，调试预览传入合成 Bitmap，
 * 于是模拟器取证覆盖的就是生产接线本身，而不是另写一套仅供测试的近似实现。
 *
 * 三道门控由 [shouldAttachPhotoBackdrop] 收口：实验室开关、API 门槛、照片确实可用。
 * 任一不满足时**完全不铺背景宿主**，[topBar] 收到 `sampled = false`，
 * 调用方据此渲染接入前的原始按钮——画面与接入前逐像素一致，而不是"少画一层"。
 *
 * @param photo 已经解码好的照片；null 表示来源不可用（图被删、解码失败、尚未加载）
 * @param liquidGlassEnabled 液态玻璃实验室开关（生产入口的 Android 15+ 判定由调用方保证）
 * @param scrimHeightPx 顶部渐变遮罩高度；必须与屏幕上的遮罩用同一套数值，
 *        否则采样层会采到"没被压暗的照片"，比周围亮一截
 * @param background 照片之外的底色（默认黑，与全屏查看器的既有底一致）
 * @param content 铺满的内容层（照片列表/分页器），画在背景之上、顶栏之下
 * @param topBar 顶栏浮层；`sampled = true` 表示可以用背景采样
 * @return 采样状态与是否真的接入了背景，供调试预览输出审计标记
 */
@Composable
fun LiquidGlassPhotoBackdropViewer(
    photo: Bitmap?,
    liquidGlassEnabled: Boolean,
    scrimHeightPx: Float,
    modifier: Modifier = Modifier,
    background: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Black,
    /**
     * 采样层外观参数。查看器把"翻页中/放大/拖动 → 必须回退"的判定放进
     * `viewerSync`，由采样层据此回退，而不是继续显示与屏幕错位的采样。
     */
    backdropSpec: LiquidGlassBackdropSpec = LiquidGlassBackdropSpec.Default,
    state: LiquidGlassBackdropState = rememberLiquidGlassBackdropState(),
    content: @Composable BoxScope.() -> Unit,
    topBar: @Composable BoxScope.(decision: ViewerBackdropDecision) -> Unit
): PhotoBackdropAttachment {
    // 两层门控（接不接背景 / 此刻能不能采）收敛成一个值：
    // **同一个 decision 同时驱动 UI 分支与审计行**，二者不可能分叉。
    // 早期实现里 topBar 只收到一个 Boolean，审计行另外去读
    // LiquidGlassBackdropState.lastPlan——而回退时玻璃节点根本不渲染，
    // lastPlan 会停在上一轮采样结果上（详见 ViewerBackdropDecision 的注释）。
    val decision = ViewerBackdropDecision.of(
        liquidGlassEnabled = liquidGlassEnabled,
        sdkInt = Build.VERSION.SDK_INT,
        hasPhoto = photo != null,
        viewerSync = backdropSpec.viewerSync
    ).copy(photoRect = backdropSpec.photoRectPx)
    val sampled = decision.attachGateReason == BackdropFallbackReason.NONE
    // 查看器在翻页/放大/拖动时也要"不采样"：这与 attach 门控是两个层次
    // ——那个决定"要不要接背景"，这个决定"此刻能不能采"。
    val viewerCanSample = decision.syncReason?.let { it == BackdropFallbackReason.NONE } ?: true

    // 照片换了（翻页 / 重新解码）必须让采样层重画：只改绘制内容不改几何时，
    // 这是唯一会触发重画的信号。
    LaunchedEffect(photo) {
        if (photo != null) state.invalidateContent()
    }

    // 遮罩高度变化（状态栏 inset 变化、旋转屏幕）同样要重画：背景 lambda 里
    // 用到了 scrimHeightPx，但 lambda 身份不变，只靠 attachContent 的比较
    // 发现不了这个变化。
    LaunchedEffect(scrimHeightPx) {
        state.invalidateContent()
    }

    // 照片矩形变化同样必须重画。这与 `scrimHeightPx` 是同一类问题：
    // lambda 里用到 photoRectPx，但只改 lambda 捕获的值不会让它换身份，
    // 采样层会继续显示旧位置的采样结果（比如入场动画结束后还停在中途位置）。
    val photoRectPx = backdropSpec.photoRectPx
    LaunchedEffect(photoRectPx) {
        if (photoRectPx != null) state.invalidateContent()
    }

    val drawing: DrawScope.() -> Unit = remember(photo, scrimHeightPx, background, photoRectPx) {
        {
            drawPhotoBackdrop(
                bitmap = photo,
                background = background,
                topScrimHeightPx = scrimHeightPx,
                photoRectPx = photoRectPx
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (sampled) {
            LiquidGlassBackdropHost(
                state = state,
                modifier = Modifier.fillMaxSize(),
                background = drawing
            )
        }
        content()
        if (sampled) {
            CompositionLocalProvider(LocalLiquidGlassBackdrop provides state) {
                // 翻页/缩放/拖动期间采样层会按 viewerSync 回退，顶栏按钮也必须
                // 同步回到原始材质——否则按钮还挂着背景来源，会显示"采到旧照片"
                // 的错位效果。
                topBar(decision)
            }
        } else {
            topBar(decision)
        }
    }

    return PhotoBackdropAttachment(
        state = state,
        sampled = decision.sampling,
        decision = decision
    )
}

/** [LiquidGlassPhotoBackdropViewer] 的返回结果：给调试预览输出审计标记用。 */
data class PhotoBackdropAttachment(
    val state: LiquidGlassBackdropState,
    val sampled: Boolean,
    /** 本次渲染走的路径与原因；与 UI 分支同源，供调试预览与设备测试读取。 */
    val decision: ViewerBackdropDecision = ViewerBackdropDecision(
        attachGateReason = BackdropFallbackReason.NOT_DRAWN_YET
    )
)
