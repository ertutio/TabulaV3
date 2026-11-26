package com.tabula.v3.ui.components.liquidglass

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.tabula.v3.BuildConfig
import kotlinx.coroutines.delay

/**
 * 查看器顶栏的**设备测试出口**：把"此刻走哪条渲染路径、为什么"渲染成一行
 * `AUDIT ...`，形态与调试预览的 `auditMarkers` 一致，测试可复用同一套解析。
 *
 * ## 与旧实现的区别（这是本文件存在的理由）
 *
 * 旧实现读 [LiquidGlassBackdropState.lastPlan]。那是**玻璃节点**上报的读数，
 * 而回退时（`viewerCanSample = false` 或 attach 门控关闭）顶栏**根本不渲染
 * 玻璃节点**——没有节点上报，`lastPlan` 就停在上一轮的采样结果上。审计行于是
 * 写着 `sampled=true reason=NONE`，屏幕上却已是回退后的普通按钮。设备测试
 * 读这一行会把"正在回退"读成"仍在采样"。
 *
 * `sampled` 和 `path` 表示 [decision] 选择的按钮分支，不能证明采样已就绪。
 * `ready` 同时检查当前主句柄的方案、尺寸、效果和本版本绘制记录。
 *
 * ## 采样层的读数仍然保留
 *
 * `hostdraw` / `layerdraw` / `glass` / `host` 这些只有真在采样时才有意义，
 * 所以仅在 `path=glass` 时输出；回退时输出 `-`，避免把陈旧数值当成当前值。
 *
 * **只在 debug 变体渲染**：release 里直接返回，不产生节点、不参与布局
 * （与 `MediaLoadTestHook`、`CardStackDebug` 同口径）。
 */
@Composable
fun ViewerBackdropAuditLine(
    decision: ViewerBackdropDecision,
    state: LiquidGlassBackdropState,
    viewerSync: ViewerBackdropSync?,
    page: Int,
    modifier: Modifier = Modifier
) {
    if (!BuildConfig.DEBUG) return

    // Draw counters are deliberately non-observable. Refresh this debug-only node
    // briefly after each transition, without making drawing trigger recomposition.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(decision.sampling, state.revision, state.primaryDrawKey, page) {
        if (decision.sampling) repeat(30) { delay(100); tick++ }
    }
    @Suppress("UNUSED_VARIABLE") val auditTick = tick
    val line = buildString {
        append("AUDIT sampled=").append(if (decision.sampling) "true" else "false")
        append(" reason=").append(decision.reason.name)
        append(" path=").append(decision.path)
        append(" page=").append(page)

        if (decision.sampling) {
            // 玻璃分支：计划与绘制可能仍在等待布局或首帧。
            val plan = state.lastPlan
            val ready = plan.useSampledBackdrop && plan.glassWidthPx > 0 &&
                plan.glassHeightPx > 0 && plan.layerWidthPx > 0 && plan.layerHeightPx > 0 &&
                state.primaryEffectHealth == BackdropEffectHealth.Ok && state.primaryLayerDrawCount > 0 &&
                state.primaryHasDrawnCurrentPlan &&
                state.primaryLastLayerDrawRevision == state.revision
            append(" ready=").append(ready)
            append(" plan=").append(plan.status.name)
            append(" planreason=").append(plan.reason.name)
            append(" glass=").append(plan.glassWidthPx).append('x').append(plan.glassHeightPx)
            append(" layer=").append(plan.layerWidthPx).append('x').append(plan.layerHeightPx)
            append(" host=").append(state.sourceSize.width).append('x')
                .append(state.sourceSize.height)
            append(" hostdraw=").append(state.hostDrawCount)
            append(" layerdraw=").append(state.layerDrawCount)
            append(" buttondraw=").append(state.primaryLayerDrawCount)
            append(" drawrev=").append(state.primaryLastLayerDrawRevision)
            append(" rev=").append(state.revision)
            append(" effect=").append(state.primaryEffectHealth)
            append(" drawplan=").append(state.primaryHasDrawnCurrentPlan)
        } else {
            // 回退：采样层读数**没有意义**，显式标成 "-" 而不是留旧值
            append(" ready=false plan=- planreason=- glass=- layer=- host=- hostdraw=- layerdraw=- buttondraw=- drawrev=- rev=-")
            append(" effect=- drawplan=-")
        }

        if (viewerSync != null) {
            append(" sync=").append(if (viewerSync.canSample) "ok" else "blocked")
            append(" scale=").append(viewerSync.scale)
            append(" pan=(").append(viewerSync.panX).append(',').append(viewerSync.panY).append(')')
            append(" pager=").append(viewerSync.pagerSettled)
        }
    }

    Box(modifier = modifier.semantics { contentDescription = line }) {
        Text(text = line, color = Color.Transparent, fontSize = 1.sp)
    }
}
