package com.tabula.v3.data.balance

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 随机漫步的「来源覆盖均衡」抽样（路线图 #83 第 6 项）。
 *
 * 问题：纯均匀随机抽样在相册大小悬殊时几乎只抽到最大的那几个相册——
 * 一个 5000 张的相机相册会把 20 张的截图目录完全淹掉，用户感觉"老是
 * 那几张"。
 *
 * 做法（路线图给的方向）：
 * 1. 按来源相册分桶（bucket 身份 = 卷 + 相对路径，不是显示名）
 * 2. 抽样权重 = 桶内剩余张数的**平方根**
 * 3. 按权重无放回地抽桶，桶内再随机抽图
 *
 * 平方根是"既不放弃大相册、也不让小相册消失"的折中：5000 张的桶权重
 * ≈70，20 张的桶权重 ≈4.5——大桶仍占优，但小桶有了实质机会（线性权重
 * 下 20 张的桶只有 0.4% 概率，平方根把它抬到约 6%）。
 *
 * 三条既有规则原样保留：
 * - **冷却**：冷却中的照片不进候选池
 * - **跨卷身份去重**：同一 `卷|ID` 在批次内只出现一次
 * - **冷却不足时按旧记录回填**：可用照片不够时，按 pickedAt 从早到晚补
 *
 * 纯逻辑，随机源可注入，因此可以用固定种子做可复现的对比实验。
 */
object SourceBalanceRules {

    /**
     * 一个来源桶。
     *
     * @param key 来源身份（`卷|相对路径`）；无路径的照片归入 `卷|` 桶
     * @param images 桶内**可用**照片（已扣除冷却）
     */
    data class Bucket(
        val key: String,
        val images: List<ImageFile>
    )

    /**
     * 把照片按来源分桶。
     *
     * 身份口径与暂存清理、相似组一致：卷 + 相对路径。拿不到相对路径时
     * 退化为按卷分（同一卷的无路径照片落进同一个桶），不做跨卷猜测。
     */
    fun bucketize(images: List<ImageFile>): List<Bucket> =
        images
            .groupBy { sourceKeyOf(it) }
            .map { (key, rows) -> Bucket(key, rows) }
            .sortedBy { it.key }

    fun sourceKeyOf(image: ImageFile): String {
        val volume = image.volumeName.orEmpty()
        val path = image.relativePath?.takeIf { it.isNotBlank() }.orEmpty()
        return "$volume|$path"
    }

    /**
     * 按策略从可用池里选出一批（审计意见 #89）。
     *
     * 这是 RecommendationEngine 实际调用的分派点，抽成纯函数以便对
     * "原随机 vs 均衡"做确定性对比测试。
     *
     * @param strategy UNIFORM = 原随机（改动前行为，默认）；SOURCE_BALANCED = 均衡
     */
    fun sampleByStrategy(
        available: List<ImageFile>,
        batchSize: Int,
        strategy: Strategy,
        random: Random
    ): List<ImageFile> {
        if (batchSize <= 0 || available.isEmpty()) return emptyList()
        return when (strategy) {
            Strategy.UNIFORM -> uniformSample(available, batchSize, random)
            Strategy.SOURCE_BALANCED -> balancedSample(available, batchSize, random)
        }
    }

    /** 抽样策略（与 AppPreferences.WalkSamplingStrategy 一一对应）。 */
    enum class Strategy { UNIFORM, SOURCE_BALANCED }

    /**
     * 原随机：纯均匀随机，去重后取前 batchSize 张。
     *
     * 与改动前 `availableImages.shuffled().take(batchSize)` 同义，只是显式
     * 去重（跨卷同数值 ID 不能在同一批里出现两次）。
     */
    fun uniformSample(
        available: List<ImageFile>,
        batchSize: Int,
        random: Random
    ): List<ImageFile> {
        if (batchSize <= 0 || available.isEmpty()) return emptyList()
        val picked = mutableListOf<ImageFile>()
        val seen = mutableSetOf<String>()
        for (image in available.shuffled(random)) {
            if (picked.size >= batchSize) break
            if (seen.add(image.mediaIdentityKey())) picked += image
        }
        return picked
    }

    /**
     * 按平方根权重无放回抽桶。
     *
     * @param buckets 候选桶
     * @param count 要抽多少个桶（不超过桶数）
     * @param random 随机源（注入以便测试可复现）
     */
    fun pickBuckets(
        buckets: List<Bucket>,
        count: Int,
        random: Random
    ): List<Bucket> {
        if (count <= 0 || buckets.isEmpty()) return emptyList()
        val pool = buckets.toMutableList()
        val picked = mutableListOf<Bucket>()

        repeat(minOf(count, buckets.size)) {
            val weights = pool.map { sqrt(it.images.size.toDouble()) }
            val total = weights.sum()
            if (total <= 0.0) return@repeat
            var target = random.nextDouble() * total
            var chosen = pool.lastIndex
            for (index in pool.indices) {
                target -= weights[index]
                if (target <= 0.0) {
                    chosen = index
                    break
                }
            }
            picked += pool.removeAt(chosen)
        }
        return picked
    }

    /**
     * 均衡抽样主入口。
     *
     * 两轮：
     * 1. **保证覆盖**：按平方根权重抽出桶顺序，每桶先贡献 1 张——
     *    这样只要批次大小 ≥ 桶数，每个来源都至少出现一次。
     * 2. **按权重补满**：剩余名额按平方根权重继续抽桶（每轮重算权重，
     *    已被抽空的桶自然退出），直到批次填满或没有候选。
     *
     * 权重每轮重算意味着大桶会因为"还有更多剩余"而持续获得更多机会，
     * 但小桶在第二轮仍保有与其剩余量相称的概率——不会回到纯均匀抽样的
     * "大桶通吃"。
     *
     * @param available 可用照片（**已**扣除冷却）
     * @param batchSize 目标批次大小
     * @param random 随机源
     */
    fun balancedSample(
        available: List<ImageFile>,
        batchSize: Int,
        random: Random
    ): List<ImageFile> {
        if (batchSize <= 0 || available.isEmpty()) return emptyList()
        val buckets = bucketize(available)
        val result = mutableListOf<ImageFile>()
        val usedKeys = mutableSetOf<String>()

        // 每个桶内的随机顺序提前打乱一次，后续按序取，结果对种子可复现
        val shuffledByKey: Map<String, List<ImageFile>> =
            buckets.associate { it.key to it.images.shuffled(random) }
        val cursor = buckets.associate { it.key to 0 }.toMutableMap()

        fun remaining(key: String): Int {
            val list = shuffledByKey[key] ?: return 0
            return (list.size - (cursor[key] ?: 0)).coerceAtLeast(0)
        }

        fun takeOne(key: String): Boolean {
            val list = shuffledByKey[key] ?: return false
            var index = cursor[key] ?: 0
            while (index < list.size) {
                val image = list[index]
                index++
                if (usedKeys.add(image.mediaIdentityKey())) {
                    cursor[key] = index
                    result += image
                    return true
                }
            }
            cursor[key] = index
            return false
        }

        // 第一轮：每个来源至少一张（受批次大小限制）
        val orderedKeys = pickBuckets(buckets, minOf(buckets.size, batchSize), random).map { it.key }
        for (key in orderedKeys) {
            if (result.size >= batchSize) break
            takeOne(key)
        }

        // 第二轮：按剩余量的平方根权重补满
        var guard = 0
        while (result.size < batchSize && guard < batchSize * 8) {
            guard++
            val live = buckets.mapNotNull { bucket ->
                val left = remaining(bucket.key)
                if (left > 0) Bucket(bucket.key, List(left) { bucket.images.first() }) else null
            }
            if (live.isEmpty()) break
            val next = pickBuckets(live, 1, random).firstOrNull() ?: break
            if (!takeOne(next.key)) break
        }

        return result
    }

    /**
     * 冷却不足时的回填：按 pickedAt 从早到晚补，**不重置**这些照片的冷却期。
     *
     * 与 [com.tabula.v3.data.repository.RecommendationEngine] 现有行为一致，
     * 抽出来单独成函数便于测试"回填不改变记录"。
     */
    fun backfillFromCooldown(
        cooldownImages: List<ImageFile>,
        alreadyPicked: List<ImageFile>,
        batchSize: Int,
        pickedAt: (ImageFile) -> Long
    ): List<ImageFile> {
        if (alreadyPicked.size >= batchSize || cooldownImages.isEmpty()) return emptyList()
        val used = alreadyPicked.map { it.mediaIdentityKey() }.toMutableSet()
        val result = mutableListOf<ImageFile>()
        cooldownImages
            .sortedWith(compareBy<ImageFile> { pickedAt(it) }.thenBy { it.mediaIdentityKey() })
            .forEach { image ->
                if (alreadyPicked.size + result.size >= batchSize) return@forEach
                val key = image.mediaIdentityKey()
                if (used.add(key)) result += image
            }
        return result
    }
}
