package com.tabula.v3.ui.components.liquidglass

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.tabula.v3.BuildConfig

/**
 * 采样方案的**设备测试出口**：把生产组件自己的方案渲染成一行 `AUDIT ...`。
 *
 * 为什么需要它：真实查看器（回收站全屏查看器）在翻页/放大/拖动时必须回退采样，
 * 而这件事只有操作真实页面才能验证。没有出口时，测试只能"另算一遍"或读像素，
 * 两者都不算验证生产组件自己的状态。
 *
 * 形态与调试预览的 `auditMarkers` 完全一致，因此设备测试可以直接复用同一套解析
 * （`sampled=` / `reason=` / `glass=` / `hostdraw=` / `layerdraw=`）。
 *
 * **只在 debug 变体渲染**：release 里直接返回，不产生节点、不参与布局，
 * 生产行为零变化（与 `MediaLoadTestHook`、`CardStackDebug` 同口径）。
 *
 * 渲染位置由调用方决定；视觉上不可见（0 号色、1sp），但仍在无障碍树里可读。
 */
@Composable
fun BackdropAuditLine(
    state: LiquidGlassBackdropState,
    viewerSync: ViewerBackdropSync?,
    modifier: Modifier = Modifier
) {
    if (!BuildConfig.DEBUG) return

    // 读 state 里的方案；查看器同步状态单独标注，便于区分"采样层自己没建起来"
    // 与"查看器主动要求回退"。
    val plan = state.lastPlan
    val line = plan.auditMarkers(
        health = state.effectHealth,
        hostWidth = state.sourceSize.width,
        hostHeight = state.sourceSize.height,
        hostDraws = state.hostDrawCount,
        layerDraws = state.layerDrawCount,
        revision = state.revision,
        shapeLabel = if (viewerSync == null) "" else "viewer",
        screenX = state.lastGlassWindow?.left,
        screenY = state.lastGlassWindow?.top
    ) + if (viewerSync == null) {
        ""
    } else {
        " sync=${if (viewerSync.canSample) "ok" else "blocked"}" +
            " scale=${viewerSync.scale}" +
            " pan=(${viewerSync.panX},${viewerSync.panY})" +
            " pager=${viewerSync.pagerSettled}"
    }

    Box(modifier = modifier.semantics { contentDescription = line }) {
        Text(text = line, color = Color.Transparent, fontSize = 1.sp)
    }
}
