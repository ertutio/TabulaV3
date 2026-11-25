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
 * 意图只是把某个桶的优先级临时抬高，冷却与去重规则完全不变。
 *
 * 审计 #90 修正（第三轮）：
 * - **回退到用户所选策略**，不再固定回落到均衡抽样。目标来源耗尽时若强行
 *   改用均衡，等于在用户选了「原随机」时悄悄改掉他的策略；
 * - [shouldSuggest] 的候选池口径改为与**下一批抽样**一致（调用方传入已扣
 *   冷却、屏蔽图集与当前批次的候选），不再自己猜；
 * - 阈值定为「主来源 ≥3 条成功记录 **且** ≥60%」，2+1 不提示、3+2 提示。
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
     * - 主来源至少 [MIN_SHARE_COUNT] 张**成功处理**记录
     * - 且占成功处理总数至少 [MIN_SHARE]（3+2 = 60% 达标，2+1 卡在条数上）
     * - 该来源在**下一批候选**里还有至少 [MIN_REMAINING] 张——
     *   [eligibleImages] 必须与下一批抽样同口径（已扣冷却、屏蔽图集与
     *   当前批次已抽到的身份），否则会提示一个抽不出来的来源
     * - 用户还没忽略过（[dismissed]）、本轮也还没接受过同一来源
     *
     * @param session 会话统计（含已处理照片的来源快照与"本轮已接受"标记）
     * @param eligibleImages 下一批可选候选（已扣冷却 / 屏蔽 / 当前批次）
     * @param sourceKeyOf 来源键解析（与抽样层同一个函数）
     * @param dismissed 用户是否已经忽略过这个提示
     */
    fun shouldSuggest(
        session: RoundSessionRules.Session,
        eligibleImages: List<ImageFile>,
        sourceKeyOf: (ImageFile) -> String,
        dismissed: Boolean = false
    ): String? {
        if (dismissed || session.currentBatch.isEmpty()) return null

        val bySource = session.currentBatch.groupBy { it.sourceKey }
        val (dominantKey, dominantRows) = bySource.maxByOrNull { it.value.size } ?: return null
        if (dominantRows.size < MIN_SHARE_COUNT) return null
        if (dominantRows.size.toDouble() / session.currentBatch.size < MIN_SHARE) return null

        // 本轮已经接受过同一来源：不要在继续处理的过程中反复弹同一条提示。
        if (session.acceptedThisRound == dominantKey) return null

        // 只能提示下一批真能抽到的东西：候选里必须有批外（当前批次之外）的
        // 同来源照片。只剩当前批次那几张时提示没有意义。
        val outOfBatch = eligibleImages.count {
            sourceKeyOf(it) == dominantKey && it.mediaIdentityKey() !in session.currentBatchIdentityKeys
        }
        if (outOfBatch < MIN_REMAINING) return null

        return dominantKey
    }

    /**
     * 带意图的抽样：先把选中来源的照片尽量抽满，不足部分按**用户所选策略**
     * 补齐。
     *
     * 审计 #90：补齐路径必须尊重 [strategy]（默认行为是原随机 UNIFORM）。
     * 目标来源彻底耗尽时同样回落到 [strategy]，而不是固定改成均衡抽样——
     * 那是用户的设置，不该被这个提示顺带改掉。
     *
     * **不**改变冷却与去重：传入的 [available] 必须已经扣除冷却。
     *
     * @param available 候选池（已扣除冷却、屏蔽图集与当前批次）
     * @param batchSize 目标批次
     * @param intent 当前意图；不 active 时等价于纯按策略抽样
     * @param strategy 用户选择的漫步抽样策略（回退与补齐都用它）
     * @param random 随机源
     */
    fun sampleWithIntent(
        available: List<ImageFile>,
        batchSize: Int,
        intent: Intent,
        strategy: SourceBalanceRules.Strategy = SourceBalanceRules.Strategy.UNIFORM,
        random: Random
    ): List<ImageFile> {
        if (batchSize <= 0 || available.isEmpty()) return emptyList()

        fun fallback(pool: List<ImageFile>, size: Int): List<ImageFile> =
            SourceBalanceRules.sampleByStrategy(pool, size, strategy, random)

        if (!intent.isActive) return fallback(available, batchSize)

        val preferred = available
            .filter { SourceBalanceRules.sourceKeyOf(it) == intent.sourceKey }
        if (preferred.isEmpty()) {
            // 该来源已无剩余：按用户所选策略出批，不静默改成均衡
            return fallback(available, batchSize)
        }

        val preferredTake = preferred.shuffled(random).take(batchSize)
        if (preferredTake.size >= batchSize) return preferredTake

        val used = preferredTake.map { it.mediaIdentityKey() }.toSet()
        val rest = available.filter { it.mediaIdentityKey() !in used }
        return preferredTake + fallback(rest, batchSize - preferredTake.size)
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

    /** 至少要有这么多张成功处理记录来自同一来源，才认为是"在集中处理它"。 */
    const val MIN_SHARE_COUNT = 3

    /** 且要占到成功处理总数的这个比例（3/5 = 60% 恰好达标）。 */
    const val MIN_SHARE = 0.6

    /** 该来源在下一批候选里至少还剩这么多张（当前批次之外）才提示。 */
    const val MIN_REMAINING = 1
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
