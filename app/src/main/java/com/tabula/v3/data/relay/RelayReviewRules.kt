package com.tabula.v3.data.relay

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey

/**
 * 待清标记在界面上的呈现与撤销（审计意见 #85）。
 *
 * 审计要求：
 * - 把已确认的待清标记接进暂存清理页
 * - 实现撤销
 * - 提供待核对记录的用户入口
 * - **无法唯一匹配时不能猜测原图**
 *
 * 本文件只做"标记 → 界面行"的映射与撤销判定，纯逻辑、可测；
 * 真正的删除仍走用户确认后的回收站流程，标记本身不删除任何文件。
 */
object RelayReviewRules {

    /**
     * 一条待清记录在界面上的形态。
     *
     * @param mark 原始标记
     * @param image 能唯一定位到的图库照片；`null` 表示**无法唯一匹配**
     * @param isActionable 是否允许"移入回收站"操作
     */
    data class ReviewRow(
        val mark: RelayMark,
        val image: ImageFile?,
        val isActionable: Boolean,
        /**
         * 审计修复（#85）：标记的照片已在回收站里（写成功但标记移除失败）。
         *
         * 此时照片已从图库列表消失，但标记还在。页面必须仍能显示这条，
         * 并允许用户**只重试移除标记**，不能要求用户重新选一张已经
         * 不在图库里的照片。
         */
        val alreadyInRecycleBin: Boolean = false
    ) {
        /**
         * 需要用户核对的理由。`null` 表示这条记录已确认且可操作。
         */
        val reviewReason: String?
            get() = when {
                mark.state == RelayMarkState.REJECTED -> "不是有效图片"
                alreadyInRecycleBin ->
                    "照片已在回收站，但标记移除失败；可重试移除标记"
                isActionable -> null
                image == null && mark.identityKey == null ->
                    "无法唯一定位原图，需要你确认是图库里的哪一张"
                image == null -> "标记的照片已不在图库中"
                else -> "需要核对"
            }

        /** 是否可以重试移除标记（已入回收站但标记仍在）。 */
        val canRetryMarkRemoval: Boolean get() = alreadyInRecycleBin
    }

    /**
     * 把标记列表解析成界面行。
     *
     * 定位口径（**不猜**）：
     * - 标记带身份键（`卷|ID`）时，按完整媒体身份在图库里精确查找；
     *   找到就是它，找不到就是"已不在图库"
     * - 标记没有身份键（`PENDING_REVIEW`）时 **绝不**按显示名或大小去
     *   猜一张图出来——`image` 保持 null，交给用户手动确认
     * - 同一身份键出现多条时只保留最新一条（存储层已去重，这里兜底）
     *
     * @param library 当前可访问的照片（调用方传入，已排除回收站内的）
     */
    fun resolveRows(
        marks: List<RelayMark>,
        library: List<ImageFile>,
        recycleBinKeys: Set<String> = emptySet()
    ): List<ReviewRow> {
        val byIdentity = library.groupBy { it.mediaIdentityKey() }
        return marks
            .filter { it.state != RelayMarkState.REJECTED }
            .sortedWith(
                compareByDescending<RelayMark> { it.state == RelayMarkState.PENDING_REVIEW }
                    .thenByDescending { it.markedAt }
            )
            .distinctBy { it.identityKey ?: "uri|${it.uriString}" }
            .map { mark ->
                val key = mark.identityKey
                // 只按完整身份精确匹配；多值（跨卷同 ID）时取第一个但不是"猜"，
                // 因为身份键本身已经含卷，命中即唯一
                val image = key?.let { k -> byIdentity[k]?.firstOrNull() }
                // 图库里找不到，但身份键命中回收站 → 写成功、标记移除失败
                val inBin = key != null && key in recycleBinKeys && image == null
                ReviewRow(
                    mark = mark,
                    image = image,
                    isActionable = image != null && mark.state == RelayMarkState.CONFIRMED,
                    alreadyInRecycleBin = inBin
                )
            }
    }

    /** 只保留需要用户核对的行。 */
    fun pendingReviewRows(rows: List<ReviewRow>): List<ReviewRow> =
        rows.filter { it.reviewReason != null }

    /** 已确认且可操作的行（暂存清理页展示的那部分）。 */
    fun confirmedRows(rows: List<ReviewRow>): List<ReviewRow> =
        rows.filter { it.isActionable }

    /**
     * 撤销一批标记。
     *
     * 撤销的语义：**只删标记，不碰照片**。照片无论是否在回收站都保持原状。
     *
     * @return 应当从存储里移除的身份键与 URI
     */
    fun undoPlan(marks: List<RelayMark>): UndoPlan = UndoPlan(
        identityKeys = marks.mapNotNull { it.identityKey }.toSet(),
        uriStrings = marks.filter { it.identityKey == null }.map { it.uriString }.toSet()
    )

    data class UndoPlan(
        val identityKeys: Set<String>,
        val uriStrings: Set<String>
    ) {
        val isEmpty: Boolean get() = identityKeys.isEmpty() && uriStrings.isEmpty()

        /**
         * 审计 #85：撤销读/写失败时返回 [ShareRelayStore.WriteResult.Failed]，
         * 调用方必须如实提示，不能当成功清掉页面行。
         */
        fun applyTo(store: ShareRelayStore): ShareRelayStore.WriteResult {
            val identityResult = if (identityKeys.isNotEmpty()) {
                store.remove(identityKeys)
            } else ShareRelayStore.WriteResult.Ok(store.load())
            if (identityResult is ShareRelayStore.WriteResult.Failed) return identityResult

            return if (uriStrings.isNotEmpty()) {
                store.removeByUri(uriStrings)
            } else identityResult
        }
    }

    /**
     * 待核对项的手动确认：用户在核对界面**自己选了一张**图库照片。
     *
     * 这是唯一能把 `PENDING_REVIEW` 转成 `CONFIRMED` 的路径——系统从不
     * 自动猜。确认后身份键落到标记上，后续即可参与暂存清理。
     */
    fun confirmManually(mark: RelayMark, chosen: ImageFile, nowMillis: Long): RelayMark =
        mark.copy(
            identityKey = chosen.mediaIdentityKey(),
            markedAt = nowMillis,
            state = RelayMarkState.CONFIRMED
        )
}
