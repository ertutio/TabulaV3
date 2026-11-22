package com.tabula.v3.data.balance

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlin.random.Random

/**
 * 「本轮整理意图」（路线图 #83 第 7 项）。
 *
 * 场景：用户连续处理同一个来源（例如一直在清截图），下一批仍然混着相机照片，
 * 就得反复跳过。这里给一个**用户主动接受**的提示：「这轮再看几张这个相册」，
 * 点了之后下一批优先从该来源取。
 *
 * 三条硬约束（路线图写死的）：
 * 1. **只在当前整理会话内有效** —— 不写偏好、不跨重启、不影响下次打开。
 * 2. **用户可随时退出** —— 忽略提示就按原策略抽图，不反复打扰。
 * 3. **接受只影响下一批** —— 不是"从此都在这个相册里"，取完一批就回落。
 *
 * 与第 6 项的关系：共用同一个候选池与来源分桶（[SourceBalanceRules]）。
 * 意图只是把某个桶的权重临时抬高，冷却与去重规则完全不变。
 *
 * 纯逻辑，无 Android/Compose 依赖。
 */
object RoundIntentRules {

    /**
     * 一次会话内的意图状态。
     *
     * @param sourceKey 用户选中的来源（`卷|相对路径`）；null = 没有意图
     * @param acceptedAt 接受时间（毫秒），用于"只影响下一批"的过期判断
     * @param consumed 是否已经被消费（已经用在某一批上）
     */
    data class Intent(
        val sourceKey: String? = null,
        val acceptedAt: Long = 0L,
        val consumed: Boolean = false
    ) {
        val isActive: Boolean get() = sourceKey != null && !consumed
    }

    /**
     * 判断是否该提示「这轮再看几张这个相册」。
     *
     * 触发条件（全部满足才提示）：
     * - 当前批次里**至少 [MIN_SHARE] 比例**的照片来自同一个来源——
     *   说明用户确实在集中处理它，而不是偶然抽到两张
     * - 该来源在候选池里还有剩余照片（否则提示了也抽不出来）
     * - 用户还没接受过、也没忽略过（[dismissed] 为 true 就不再提）
     *
     * @param currentBatch 用户正在看的这一批
     * @param available 候选池（已扣除冷却）
     * @param dismissed 用户是否已经忽略过这个提示
     */
    fun shouldSuggest(
        currentBatch: List<ImageFile>,
        available: List<ImageFile>,
        dismissed: Boolean = false
    ): String? {
        if (dismissed || currentBatch.isEmpty()) return null

        val bySource = currentBatch.groupBy { SourceBalanceRules.sourceKeyOf(it) }
        val (dominantKey, dominantRows) = bySource.maxByOrNull { it.value.size } ?: return null
        if (dominantRows.size < MIN_SHARE_COUNT) return null
        if (dominantRows.size.toDouble() / currentBatch.size < MIN_SHARE) return null

        // 该来源在候选池里还留着多少（扣除当前批次里已抽到的那批）
        val alreadyShown = currentBatch.map { it.mediaIdentityKey() }.toSet()
        val remaining = available.count {
            SourceBalanceRules.sourceKeyOf(it) == dominantKey &&
                it.mediaIdentityKey() !in alreadyShown
        }
        if (remaining < MIN_REMAINING) return null

        return dominantKey
    }

    /**
     * 带意图的抽样：先把选中来源的照片尽量抽满，不足部分按第 6 项的
     * 均衡策略补齐。
     *
     * **不**改变冷却与去重：传入的 [available] 必须已经扣除冷却。
     *
     * @param available 候选池（已扣除冷却）
     * @param batchSize 目标批次
     * @param intent 当前意图；不 active 时等价于纯均衡抽样
     * @param random 随机源
     */
    fun sampleWithIntent(
        available: List<ImageFile>,
        batchSize: Int,
        intent: Intent,
        random: Random
    ): List<ImageFile> {
        if (batchSize <= 0 || available.isEmpty()) return emptyList()
        if (!intent.isActive) {
            return SourceBalanceRules.balancedSample(available, batchSize, random)
        }

        val preferred = available.filter {
            SourceBalanceRules.sourceKeyOf(it) == intent.sourceKey
        }
        if (preferred.isEmpty()) {
            // 该来源已无剩余：静默回落，不报错、不空转
            return SourceBalanceRules.balancedSample(available, batchSize, random)
        }

        val preferredTake = preferred.shuffled(random).take(batchSize)
        if (preferredTake.size >= batchSize) return preferredTake

        val used = preferredTake.map { it.mediaIdentityKey() }.toSet()
        val rest = available.filter { it.mediaIdentityKey() !in used }
        val fill = SourceBalanceRules.balancedSample(
            available = rest,
            batchSize = batchSize - preferredTake.size,
            random = random
        )
        return preferredTake + fill
    }

    /**
     * 接受意图：记录来源与时间，标记为未消费。
     */
    fun accept(sourceKey: String, nowMillis: Long): Intent =
        Intent(sourceKey = sourceKey, acceptedAt = nowMillis, consumed = false)

    /**
     * 消费意图：**只影响下一批**——抽完就把 consumed 置真，后续批次回落。
     */
    fun consume(intent: Intent): Intent = intent.copy(consumed = true)

    /** 会话结束 / 用户退出整理时清空。 */
    fun clear(): Intent = Intent()

    /** 至少要有这么多张来自同一来源，才认为是"在集中处理它"。 */
    const val MIN_SHARE_COUNT = 3

    /** 且要占到当前批次的这个比例。 */
    const val MIN_SHARE = 0.6

    /** 该来源在候选池里至少还剩这么多张，提示才有意义。 */
    const val MIN_REMAINING = 2
}

/**
 * 来源身份键 → 界面显示名。
 *
 * 键形如 `卷|相对路径`（[SourceBalanceRules.sourceKeyOf] 产出）。
 * 显示取路径末段；为空时回退卷名。**不做**任何大小写或语言转换，
 * 保持用户设备上的原始目录名。
 */
object StagedSourceDisplayName {
    fun of(sourceKey: String): String {
        val separator = sourceKey.indexOf('|')
        val volume = if (separator >= 0) sourceKey.substring(0, separator) else ""
        val path = if (separator >= 0) sourceKey.substring(separator + 1) else sourceKey
        val trimmed = path.trimEnd('/')
        val last = trimmed.substringAfterLast('/').ifBlank { trimmed }
        return last.ifBlank { volume }.ifBlank { "相册" }
    }
}
