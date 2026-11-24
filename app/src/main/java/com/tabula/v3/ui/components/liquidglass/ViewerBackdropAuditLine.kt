package com.tabula.v3.ui.components.liquidglass

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.tabula.v3.BuildConfig

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
 * 现在整行由 [decision] 生成，而 [decision] **同时**决定顶栏渲染哪条分支，
 * 二者同源，不可能分叉。`path=glass|fallback` 让测试可以直接断言按钮
 * 确实走了回退路径，而不是拿测试侧重算的期望值自我验证。
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
    modifier: Modifier = Modifier
) {
    if (!BuildConfig.DEBUG) return

    val line = buildString {
        append("AUDIT sampled=").append(if (decision.sampling) "true" else "false")
        append(" reason=").append(decision.reason.name)
        append(" path=").append(decision.path)

        if (decision.sampling) {
            // 真在采样：数值来自生产组件自己的状态
            val plan = state.lastPlan
            append(" glass=").append(plan.glassWidthPx).append('x').append(plan.glassHeightPx)
            append(" host=").append(state.sourceSize.width).append('x')
                .append(state.sourceSize.height)
            append(" hostdraw=").append(state.hostDrawCount)
            append(" layerdraw=").append(state.layerDrawCount)
            append(" rev=").append(state.revision)
            append(" effect=").append(state.effectHealth)
        } else {
            // 回退：采样层读数**没有意义**，显式标成 "-" 而不是留旧值
            append(" glass=- host=- hostdraw=- layerdraw=- rev=-")
            append(" effect=-")
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
