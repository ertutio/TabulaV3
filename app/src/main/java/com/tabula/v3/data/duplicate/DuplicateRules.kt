package com.tabula.v3.data.duplicate

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey

/**
 * 删除前的「还剩哪个版本」判定（路线图 #83 第 5 项）。
 *
 * 分两步交付，本文件同时承载两层的判定：
 *
 * **第一步（本 PR 主体）：精确副本** —— 按全文 SHA-256 判定字节相同。
 * 只对不同媒体身份、且内容摘要一致的文件提示"相同副本"。这是可以下
 * 确定性结论的部分：字节相同就是同一份内容，没有猜测。
 *
 * **第二步（判定已就位，默认不启用）：视觉相似版本** —— 复用感知哈希
 * （dHash + 汉明距离）找出跨日期、跨相册的压缩版等候选，标为"可能是
 * 其他版本"，把选择权留给用户。这一层**必须**带上"可能"的语气，且
 * 不能参与任何自动删除决策。
 *
 * 两条硬约束：
 * 1. **不做自动删除**：本文件只产出"提示"，删除始终由用户逐项确认。
 * 2. **确认前必须复核**：候选可能在本批已被删、被移动或在回收站里，
 *    因此 [DuplicateRules.reverify] 要求在用户确认时重新检查另一份是否
 *    仍然存在且不在本次选中集合内。
 */
object DuplicateRules {

    /** 内容摘要相同时用来比较的一条候选。 */
    data class ContentVersion(
        /** 全文 SHA-256（小写十六进制） */
        val sha256: String,
        /** 文件大小（字节）；作为快速预筛与摘要的交叉校验 */
        val size: Long
    ) {
        /**
         * 摘要是否有意义：空内容或空摘要都不是有效的"相同"证据。
         * 0 字节文件互相"相同"没有价值，提示它们只会误导用户。
         */
        val isMeaningful: Boolean get() = sha256.isNotBlank() && size > 0L
    }

    /**
     * 一条"相同副本"提示。
     *
     * @param image 当前正在看的这张
     * @param duplicates 与它字节相同的**其他**照片（已排除自身与跨卷同 ID 误判）
     */
    data class ExactDuplicateFinding(
        val image: ImageFile,
        val duplicates: List<ImageFile>
    ) {
        val hasDuplicate: Boolean get() = duplicates.isNotEmpty()
    }

    /**
     * 视觉相似候选（第二步）。距离越小越像；[isLikelySameShot] 为 true 时
     * 才值得提示，且仍标注"可能是其他版本"。
     */
    data class VisualCandidate(
        val image: ImageFile,
        val other: ImageFile,
        val hammingDistance: Int
    ) {
        /** dHash 相差 ≤10 位通常仍是同一场景的不同压缩/尺寸版本。 */
        val isLikelySameShot: Boolean get() = hammingDistance <= VISUAL_SIMILARITY_THRESHOLD
    }

    /** 第二步的默认阈值：与 SimilarGroupDetector 的 dHash 口径保持同一量级。 */
    const val VISUAL_SIMILARITY_THRESHOLD = 10

    /**
     * 从一组"照片 → 内容摘要"里找出相同副本。
     *
     * 口径：
     * - 只比**不同媒体身份**的行（跨卷同数值 ID 是不同照片）
     * - 摘要必须有意义（非空、非 0 字节）
     * - 同一 (摘要, 大小) 下的所有成员互为副本；大小也参与比较，
     *   因为摘要相同而大小不同说明摘要计算有问题，宁可不错报
     * - 结果按照片身份排序，保证多次运行一致
     *
     * @param candidates 候选照片 → 内容摘要（缺失摘要的照片不参与）
     * @param recycledIdentityKeys 已在回收站的照片身份键——它们不算"还剩一份"
     */
    fun findExactDuplicates(
        candidates: Map<ImageFile, ContentVersion>,
        recycledIdentityKeys: Set<String> = emptySet()
    ): List<ExactDuplicateFinding> {
        val usable = candidates.filterValues { it.isMeaningful }
            .filterKeys { it.mediaIdentityKey() !in recycledIdentityKeys }

        // (sha256, size) -> 成员
        val groups = usable.entries
            .groupBy { it.value.sha256.lowercase() to it.value.size }

        return usable.keys
            .sortedBy { it.mediaIdentityKey() }
            .mapNotNull { image ->
                val version = usable[image] ?: return@mapNotNull null
                val key = version.sha256.lowercase() to version.size
                val group = groups[key].orEmpty()
                val others = group.map { it.key }
                    .filter { it.mediaIdentityKey() != image.mediaIdentityKey() }
                    .distinctBy { it.mediaIdentityKey() }
                    .sortedBy { it.mediaIdentityKey() }
                if (others.isEmpty()) null else ExactDuplicateFinding(image, others)
            }
    }

    /**
     * 用户确认删除前**必须**调用的复核。
     *
     * 检查两件事：
     * 1. 提示里的"另一份"此刻是否还读得到（[stillReadable]）
     * 2. 它是否落在本次要删的集合里（[selectedForDeletion]）——
     *    如果两份都要删，那"还剩一份"的前提就不成立了
     *
     * @return true 表示可以继续删（副本确实还在，且不在本次删除集合中）
     */
    fun reverify(
        duplicate: ImageFile,
        selectedForDeletion: Set<String>,
        stillReadable: (ImageFile) -> Boolean
    ): Boolean {
        if (duplicate.mediaIdentityKey() in selectedForDeletion) return false
        return stillReadable(duplicate)
    }

    /**
     * 把视觉候选按"最像的在前"排序，并过滤掉不合格项。
     */
    fun rankVisualCandidates(
        candidates: List<VisualCandidate>
    ): List<VisualCandidate> = candidates
        .filter { it.isLikelySameShot }
        .filter { it.image.mediaIdentityKey() != it.other.mediaIdentityKey() }
        .distinctBy { listOf(it.image.mediaIdentityKey(), it.other.mediaIdentityKey()).sorted() }
        .sortedWith(
            compareBy<VisualCandidate> { it.hammingDistance }
                .thenBy { it.image.mediaIdentityKey() }
                .thenBy { it.other.mediaIdentityKey() }
        )

    /**
     * 批量找视觉候选：对每张图与其余所有图算距离。调用方负责先做时间/
     * 尺寸预筛，避免 O(n²) 全量计算。
     */
    fun visualCandidatesFor(
        image: ImageFile,
        others: List<ImageFile>,
        distanceOf: (ImageFile, ImageFile) -> Int
    ): List<VisualCandidate> = others
        .filter { it.mediaIdentityKey() != image.mediaIdentityKey() }
        .map { VisualCandidate(image, it, distanceOf(image, it)) }
        .filter { it.isLikelySameShot }
        .sortedBy { it.hammingDistance }
}
