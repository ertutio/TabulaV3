package com.tabula.v3.data.burst

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.SimilarGroup
import com.tabula.v3.data.repository.mediaIdentityKey

/**
 * 连拍选片的决策规则（路线图 #83 第 4 项）。
 *
 * 交互背景：一个相似组里通常是同一场景的连拍，用户只想留一两张。
 * 所以这里不是"一张张决定删不删"，而是**先明确勾选要保留的**，其余
 * 才成为待回收候选——默认保守，避免误删。
 *
 * 纯逻辑，无 Android/Compose 依赖，逐条可测。几个必须钉住的边界：
 *
 * 1. **至少留一张**：全组都不保留时拒绝提交（返回 [BurstSelectionError.KeepNothing]）。
 *    用户真想全删，得走别的入口，不能在这个界面里一键清空一组。
 * 2. **部分失败不推进进度**：只有整组的最终提交成功，调用方才把该组标成
 *    完成；取消、退出、写入失败都不应把尚未处理的组记成已处理。
 * 3. **身份用 `卷|ID`**：跨卷同数值 ID 不互相顶替。
 * 4. **保留项必须在组内**：不在组内的 ID 视为无效输入，不静默忽略。
 */
object BurstSelectionRules {

    /** 用户在这次选片里的决定。 */
    data class Decision(
        /** 用户明确勾选保留的照片（至少一张） */
        val kept: List<ImageFile>,
        /** 其余照片 = 待移入回收站的候选 */
        val discardCandidates: List<ImageFile>
    ) {
        val keepCount: Int get() = kept.size
        val discardCount: Int get() = discardCandidates.size

        /** 一张都不删：用户选择整组都要。 */
        val keepsEverything: Boolean get() = discardCandidates.isEmpty()
    }

    sealed interface Result {
        data class Valid(val decision: Decision) : Result
        data class Invalid(val error: BurstSelectionError) : Result
    }

    enum class BurstSelectionError {
        /** 一张都没勾选保留 */
        KeepNothing,

        /** 勾选的保留项里有不属于这个组的照片 */
        KeepOutsideGroup,

        /** 组本身为空 */
        EmptyGroup,

        /** 传入的组里有重复成员（同一张照片出现两次） */
        DuplicateMembers
    }

    /**
     * 依据用户勾选的保留集，算出这次的最终决定。
     *
     * @param group 当前相似组
     * @param keptIdentityKeys 用户勾选保留的照片身份键（`卷|ID`）
     */
    fun resolve(
        group: SimilarGroup,
        keptIdentityKeys: Set<String>
    ): Result {
        if (group.images.isEmpty()) return Result.Invalid(BurstSelectionError.EmptyGroup)

        val identityKeys = group.images.map { it.mediaIdentityKey() }
        if (identityKeys.size != identityKeys.toSet().size) {
            return Result.Invalid(BurstSelectionError.DuplicateMembers)
        }

        val groupKeys = identityKeys.toSet()
        if (keptIdentityKeys.any { it !in groupKeys }) {
            return Result.Invalid(BurstSelectionError.KeepOutsideGroup)
        }
        if (keptIdentityKeys.isEmpty()) {
            return Result.Invalid(BurstSelectionError.KeepNothing)
        }

        val kept = group.images.filter { it.mediaIdentityKey() in keptIdentityKeys }
        val discard = group.images.filter { it.mediaIdentityKey() !in keptIdentityKeys }
        return Result.Valid(Decision(kept = kept, discardCandidates = discard))
    }

    /**
     * 默认保留集：把组内最早与最晚的一张作为初始勾选。
     *
     * 连拍里"第一张"和"最后一张"往往是最稳的两张（开头对焦完成、结尾
     * 表情放松），作为起点比"全选保留"省事、比"全选删除"安全。用户随时
     * 可以改。组内只有一张时就是它自己。
     */
    fun defaultKeptKeys(group: SimilarGroup): Set<String> {
        if (group.images.isEmpty()) return emptySet()
        val sorted = group.images.sortedBy { it.dateModified }
        if (sorted.size == 1) return setOf(sorted.first().mediaIdentityKey())
        return setOf(
            sorted.first().mediaIdentityKey(),
            sorted.last().mediaIdentityKey()
        )
    }

    /**
     * 全组保留（"这组我都留"）。
     */
    fun keepAllKeys(group: SimilarGroup): Set<String> =
        group.images.map { it.mediaIdentityKey() }.toSet()

    /**
     * 只有勾选发生变化时才需要重算；这里给出一个稳定的摘要，供
     * Compose 的 remember 键使用，避免每次重组都算一遍决定。
     */
    fun selectionDigest(keptIdentityKeys: Collection<String>): String =
        keptIdentityKeys.sorted().joinToString(",")

    /**
     * 判断当前这一批照片是否"恰好来自同一个相似组"，从而可以进入连拍选片。
     *
     * 相似推荐模式返回的一批照片就是组内成员；但如果推荐引擎回退到了随机
     * 模式（没有可用组时），这批照片彼此无关，此时不该弹选片界面。
     *
     * 判定口径：
     * - 同一相似组内成员的**时间跨度**有限（检测器用 10 分钟窗口），因此
     *   整批都落在这个跨度内才考虑；
     * - 且成员数量 ≥ [MIN_BURST_SIZE]——两张也可能值得比，但一张没有意义；
     * - 相册（bucket）一致：跨相册的"时间接近"多半是巧合。
     *
     * 这是一个保守的启发式：宁可不出选片入口，也不要让用户以为随机抽到
     * 的几张是连拍。真正可靠的关系仍以 [SimilarGroup] 本身为准，调用方
     * 能拿到组对象时应直接用它，不要走这个判断。
     */
    fun looksLikeOneBurst(
        images: List<ImageFile>,
        maxSpanMs: Long = DEFAULT_BURST_SPAN_MS,
        minSize: Int = MIN_BURST_SIZE
    ): Boolean {
        if (images.size < minSize) return false
        val span = images.maxOf { it.dateModified } - images.minOf { it.dateModified }
        if (span > maxSpanMs) return false
        val buckets = images.mapNotNull { it.bucketDisplayName }.toSet()
        return buckets.size <= 1
    }

    /** 与 SimilarGroupDetector.TIME_WINDOW_MS 对齐的默认时间跨度。 */
    const val DEFAULT_BURST_SPAN_MS = 10 * 60 * 1000L

    /** 少于两张就没有"选"的意义。 */
    const val MIN_BURST_SIZE = 2

    /**
     * 把一批"看起来是连拍"的照片包成 [SimilarGroup]，供选片界面使用。
     *
     * 只在 [looksLikeOneBurst] 为真时调用；否则返回值无意义。这是给
     * "只有一批照片、拿不到组对象"的调用点准备的兜底，组身份按成员
     * 集合内容寻址（与 SimilarGroup.id 同口径），因此同一批照片每次得到
     * 相同的组 ID，冷却与进度不会错乱。
     */
    fun groupFromBatch(images: List<ImageFile>): SimilarGroup {
        require(images.isNotEmpty()) { "batch must not be empty" }
        return SimilarGroup(
            images = images,
            startTime = images.minOf { it.dateModified },
            endTime = images.maxOf { it.dateModified }
        )
    }
}
