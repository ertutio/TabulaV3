package com.tabula.v3.data.relay

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey

/**
 * 分享待清 → 回收站的事务 seam（#85 审计）。
 *
 * 顺序至关重要（旧实现是反的，写失败会丢标记）：
 *   1. 先写回收站
 *   2. 读回确认目标照片**真的持久化**了
 *   3. 才移除待清标记
 *
 * 失败语义：
 * - 写回收站失败：标记**仍在**，用户能看到、可重试
 * - 写成功但移除标记失败：标记仍在，但回收站已按身份去重，重试
 *   不会重复写；界面如实提示"已进回收站，标记移除失败，可安全重试"
 * - 两步都成功：标记消失、照片在回收站
 *
 * 所有 IO 通过注入的函数完成，测试可精确在任一步注入失败。
 */
class RelayRecycleTransaction(
    private val recycleWrite: suspend (ImageFile) -> Unit,
    private val recycleRead: suspend () -> List<ImageFile>,
    private val markRemove: (String) -> Unit,
    private val markRead: () -> List<RelayMark>
) {

    sealed interface Outcome {
        /** 两步都完成 */
        data object Done : Outcome

        /** 回收站写入失败，标记保留，可安全重试 */
        data class RecycleFailed(val reason: String) : Outcome

        /**
         * 回收站已持久化，但标记移除失败。
         * 因回收站按身份去重，重试只会再删标记、不会重复写入。
         */
        data class MarkRemovalFailed(val identityKey: String) : Outcome

        /** 读回没有目标照片：视为写失败，标记保留 */
        data object NotPersisted : Outcome
    }

    suspend fun execute(mark: RelayMark, image: ImageFile): Outcome {
        val key = image.mediaIdentityKey()

        // 1. 先写回收站（按身份幂等）
        runCatching { recycleWrite(image) }.onFailure { e ->
            return Outcome.RecycleFailed(e.message ?: "recycle write failed")
        }

        // 2. 读回确认持久化
        val persistedKeys = runCatching { recycleRead() }
            .getOrElse { return Outcome.RecycleFailed(it.message ?: "recycle read failed") }
            .map { it.mediaIdentityKey() }
            .toSet()
        if (key !in persistedKeys) return Outcome.NotPersisted

        // 3. 最后才移除待清标记
        runCatching { markRemove(key) }.onFailure {
            return Outcome.MarkRemovalFailed(key)
        }

        // 确认标记确实不在了；若仍读得到，按"移除失败"如实提示
        val stillMarked = runCatching { markRead() }
            .getOrDefault(emptyList())
            .any { it.identityKey == key }
        return if (stillMarked) Outcome.MarkRemovalFailed(key) else Outcome.Done
    }

    /** 供 UI 如实提示用户。 */
    fun message(outcome: Outcome): String = when (outcome) {
        Outcome.Done -> "已移入回收站，可在回收站恢复"
        is Outcome.RecycleFailed -> "未能移入回收站，标记已保留，可重试"
        Outcome.NotPersisted -> "回收站未能确认保存，标记已保留，可重试"
        is Outcome.MarkRemovalFailed ->
            "照片已在回收站，但标记移除失败；重试不会重复写入，可安全重试"
    }
}
