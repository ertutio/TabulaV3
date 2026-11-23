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
        val persistedMarks: List<RelayMark>,
        /** 审计 #85：标记落库失败时如实上报（null = 成功） */
        val writeFailure: String?
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
        val persisted: List<RelayMark>
        val writeFailure: String?
        if (resolution.marks.isNotEmpty()) {
            when (val write = store.merge(resolution.marks)) {
                is ShareRelayStore.WriteResult.Ok -> {
                    persisted = write.marks
                    writeFailure = null
                }
                is ShareRelayStore.WriteResult.Failed -> {
                    // 读/写失败：不覆盖原始数据，只上报
                    persisted = store.load()
                    writeFailure = write.reason
                }
            }
        } else {
            persisted = store.load()
            writeFailure = null
        }
        return Outcome(items, resolution, persisted, writeFailure)
    }
}
