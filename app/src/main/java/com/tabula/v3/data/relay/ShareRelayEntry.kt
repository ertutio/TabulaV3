package com.tabula.v3.data.relay

import android.content.Context
import android.content.Intent

/**
 * 系统分享的真实入口 seam（#85 审计）。
 *
 * ShareRelayActivity 的 LaunchedEffect 与入口级测试都走这里：
 * 从真实 Intent 读收到项 → 判定 → 落库 → 准备转发。
 * 转发本身仍由 Activity 独立完成（转发优先，不受标记成败影响）。
 */
object ShareRelayEntry {

    data class Outcome(
        val items: List<RelayItem>,
        val resolution: RelayResolution,
        val persistedMarks: List<RelayMark>
    )

    /**
     * @param localIdentityKeys 当前图库里的完整身份键集合（卷|ID）
     * @return null 表示 Intent 里没有可用图片，未写入任何标记
     */
    fun handle(
        context: Context,
        store: ShareRelayStore,
        intent: Intent?,
        localIdentityKeys: Set<String>,
        nowMillis: Long = System.currentTimeMillis()
    ): Outcome? {
        val items = ShareRelayIntentReader.read(intent) { uri ->
            runCatching { context.contentResolver.getType(uri) }.getOrNull()
        }
        if (items.isEmpty()) return null

        val resolution = ShareRelayRules.resolve(
            items = items,
            localIdentityKeys = localIdentityKeys,
            nowMillis = nowMillis
        )
        val persisted = if (resolution.marks.isNotEmpty()) {
            store.merge(resolution.marks)
        } else {
            store.load()
        }
        return Outcome(items, resolution, persisted)
    }
}
