package com.tabula.v3.data.balance

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey

/**
 * 「下一批候选」的唯一口径（#90 审计）。
 *
 * 提示「这轮再看几张这个相册」之前，必须先确认那个来源在**下一批**里真的
 * 抽得到东西。这里的候选池与 [RecommendationEngine.getRandomWalkBatch] 用
 * 同一套资格判定，顺序也一致：
 *
 * 1. 扣掉被屏蔽图集（`AlbumManager.getExcludedAlbumIds()`，按 bucket 名）
 * 2. 扣掉冷却中的照片（完整媒体身份键；裸 ID 旧记录同样兜底）
 * 3. 扣掉当前批次已经抽到的身份
 *
 * 少做任何一条都会给出抽不出来的提示：屏蔽掉的相册、冷却中的照片、
 * 以及正躺在当前批次里的那几张，都不是"下一批可用"。
 *
 * 纯逻辑，输入全部显式传入，JVM 单测直接钉住。
 */
object RoundCandidateRules {

    /**
     * 下一批可选候选。
     *
     * @param allImages 完整照片池（调用方已排除已归集到 Tabula 图集的照片）
     * @param excludedAlbumIds 被屏蔽的图集名（bucketDisplayName）
     * @param cooldownIdentityKeys 冷却中的完整身份键（`卷|ID`）
     * @param cooldownLegacyBareIds 冷却中的旧裸 ID（未迁移记录兜底）
     * @param currentBatch 当前批次，其身份要从候选里扣掉
     */
    fun eligibleForNextBatch(
        allImages: List<ImageFile>,
        excludedAlbumIds: Set<String> = emptySet(),
        cooldownIdentityKeys: Set<String> = emptySet(),
        cooldownLegacyBareIds: Set<Long> = emptySet(),
        currentBatch: List<ImageFile> = emptyList()
    ): List<ImageFile> {
        val currentKeys = currentBatch.map { it.mediaIdentityKey() }.toSet()
        return allImages.filter { image ->
            image.bucketDisplayName !in excludedAlbumIds &&
                image.mediaIdentityKey() !in cooldownIdentityKeys &&
                image.id !in cooldownLegacyBareIds &&
                image.mediaIdentityKey() !in currentKeys
        }
    }
}
