package com.tabula.v3.data.balance

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.model.mediaItemKeyFromUri
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.mediaIdentityKey

/**
 * 归档行为记账的完整身份解析（#90 审计）。
 *
 * 审计要求：不要把 actionId 当照片 ID；用 PendingArchive 中对应照片的
 * 完整媒体身份（卷 + ID），仅在归档真实成功后记录。
 *
 * 生产（MainActivity）与测试共用这个 seam。
 */
object ArchiveIdentityRules {

    /**
     * 从 PendingArchive 解析完整身份键 `卷|ID`。
     *
     * 返回 null 的两种情况都**不记账**：
     * - URI 是聚合卷 `external` 或非 media provider（解析不出具体卷）
     * - 聚合卷意味着无法唯一定位，按"不猜"原则放弃
     */
    fun identityKeyOf(action: AlbumManager.PendingArchive): String? =
        runCatching {
            mediaItemKeyFromUri(Uri.parse(action.imageUri))
                ?.let { "${it.volumeName}|${it.id}" }
                ?.takeIf { it != "|" }
        }.getOrNull()

    /**
     * 在照片快照里按完整身份找对应照片。
     *
     * 跨卷同数值 ID 是不同照片：只有 `卷|ID` 完全一致才命中，
     * 不会把另一卷的同数值 ID 误认为这次归档的那张。
     */
    fun findInSnapshot(
        action: AlbumManager.PendingArchive,
        snapshot: List<ImageFile>
    ): ImageFile? {
        val key = identityKeyOf(action) ?: return null
        return snapshot.firstOrNull { it.mediaIdentityKey() == key }
    }

    /**
     * 是否应当记账：只有 COMMITTED 才算成功。
     * COPY_FAILED / NOT_CLAIMED 都不记，撤销也应该回退。
     */
    fun shouldRecord(outcome: AlbumManager.ArchiveCommitOutcome): Boolean =
        outcome == AlbumManager.ArchiveCommitOutcome.COMMITTED
}
