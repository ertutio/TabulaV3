package com.tabula.v3.ui.util

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.AlbumCleanupBatch
import com.tabula.v3.data.repository.mediaIdentityKey

/**
 * C4/T07 前台刷新落地（P2 复审项）：回前台重查产生的新快照（MediaStore 全量
 * 重查结果）向各可见消费面同步的纯逻辑。
 *
 * 同一口径：完整媒体身份键（卷|ID，mediaIdentityKey）——跨卷同数值 ID 行
 * 互不替代（他卷的同 ID 新行不会顶替本行，也不会被误当作本行的最新版本）；
 * 存活行整体替换为快照中的最新 ImageFile（dateModified/尺寸等随外部编辑
 * 更新），消失行剔除，顺序保持。
 */

/** 按身份键把 rows 映射到 snapshot 的最新行：存活→最新对象，失效→剔除，顺序保持。 */
internal fun syncRowsToSnapshot(rows: List<ImageFile>, snapshot: List<ImageFile>): List<ImageFile> {
    if (rows.isEmpty()) return rows
    val byKey = snapshot.associateBy { it.mediaIdentityKey() }
    return rows.mapNotNull { byKey[it.mediaIdentityKey()] }
}

/**
 * originalRows 中位于 [currentIndex] 之前、在 snapshotKeys 中已不存在的行数
 * ——同步后索引按该值前移（移除都在索引之后时索引不动且仍有效）。
 */
internal fun removedCountBefore(
    originalRows: List<ImageFile>,
    snapshotKeys: Set<String>,
    currentIndex: Int
): Int = originalRows.take(currentIndex).count { it.mediaIdentityKey() !in snapshotKeys }

/**
 * 清理批次同步：组内行按快照替换/剔除，组序与 groupIds 保持不变（批次完成
 * 时按 groupIds 整组标记的语义不受影响），groupBoundaries 按组重算——组内
 * 存活行数变化时边界前移；整组失效收拢为 start==end 的空段（getGroupIdForIndex
 * /isLastInGroup 对空段无匹配，完成标记仍覆盖该组）。无变化时返回原实例。
 */
internal fun syncCleanupBatchToSnapshot(
    batch: AlbumCleanupBatch,
    snapshot: List<ImageFile>
): AlbumCleanupBatch {
    val byKey = snapshot.associateBy { it.mediaIdentityKey() }
    val syncedImages = ArrayList<ImageFile>(batch.images.size)
    val newBoundaries = ArrayList<Int>(batch.groupBoundaries.size)
    for (g in batch.groupIds.indices) {
        val start = batch.groupBoundaries[g]
        val end = if (g + 1 < batch.groupBoundaries.size) batch.groupBoundaries[g + 1] else batch.images.size
        newBoundaries.add(syncedImages.size)
        for (i in start until end) {
            val refreshed = byKey[batch.images[i].mediaIdentityKey()]
            if (refreshed != null) syncedImages.add(refreshed)
        }
    }
    if (syncedImages.size == batch.images.size && syncedImages == batch.images) return batch
    return AlbumCleanupBatch(images = syncedImages, groupIds = batch.groupIds, groupBoundaries = newBoundaries)
}

/**
 * 查看器打开中的图片按新快照校验：身份仍存在 → 返回最新行（更新
 * dateModified/尺寸，保持查看位置由调用方以 copy(image=...) 实现）；身份
 * 已不存在（删除/权限收窄/跨卷歧义不可替代）→ 返回 null，调用方关闭查看器。
 */
internal fun refreshedViewerImage(current: ImageFile, snapshot: List<ImageFile>): ImageFile? {
    val byKey = snapshot.associateBy { it.mediaIdentityKey() }
    return byKey[current.mediaIdentityKey()]
}
