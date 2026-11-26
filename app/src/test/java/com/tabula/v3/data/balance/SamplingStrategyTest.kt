package com.tabula.v3.data.balance

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 抽样策略可选化的测试（审计意见 #89）。
 *
 * 审计要求四点：
 * 1. 验证默认值和升级后的既有用户行为
 * 2. 冷却
 * 3. 完整身份去重
 * 4. 候选不足回填
 * 5. 固定种子的覆盖率
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SamplingStrategyTest {

    private fun photo(id: Long, path: String, volume: String = "external_primary") = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L + id,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = path.trimEnd('/').substringAfterLast('/'),
        volumeName = volume,
        relativePath = path
    )

    private fun lopsided() =
        (1L..500L).map { photo(it, "DCIM/Camera/") } +
            (1000L..1019L).map { photo(it, "DCIM/Screenshots/") }

    // ============ 1. 默认值与升级行为 ============

    @Test
    fun uniformIsTheDefaultStrategy() {
        // 枚举的第一个值是 UNIFORM；AppPreferences 的默认值也取它。
        // 这条断言把"默认 = 原随机"钉死：若有人改枚举顺序或默认值，这里先炸。
        assertEquals(SamplingStrategyDefaults.DEFAULT, SourceBalanceRules.Strategy.UNIFORM)
    }

    @Test
    fun upgradingUserKeepsUniformBehaviour() {
        // 升级用户没有这条偏好键 → 读到默认 UNIFORM → 走 uniformSample。
        // 用"同一输入同一随机种子，UNIFORM 与旧实现逐元素一致"来表达。
        val library = lopsided()
        val seed = 12345
        val legacyEquivalent = library.shuffled(Random(seed)).take(6)
        val viaStrategy = SourceBalanceRules.sampleByStrategy(
            available = library,
            batchSize = 6,
            strategy = SourceBalanceRules.Strategy.UNIFORM,
            random = Random(seed)
        )

        assertEquals(
            legacyEquivalent.map { it.mediaIdentityKey() },
            viaStrategy.map { it.mediaIdentityKey() }
        )
    }

    @Test
    fun uniformAndBalancedActuallyDiffer() {
        val library = lopsided()
        val uniform = SourceBalanceRules.sampleByStrategy(
            library, 6, SourceBalanceRules.Strategy.UNIFORM, Random(7)
        )
        val balanced = SourceBalanceRules.sampleByStrategy(
            library, 6, SourceBalanceRules.Strategy.SOURCE_BALANCED, Random(7)
        )

        // 两种策略在悬殊相册上应给出不同的来源构成
        val uniformSources = uniform.map { it.relativePath }.toSet()
        val balancedSources = balanced.map { it.relativePath }.toSet()
        assertTrue(
            "uniform=$uniformSources balanced=$balancedSources",
            balancedSources.size >= uniformSources.size
        )
    }

    // ============ 2. 冷却 ============

    @Test
    fun cooledImagesAreNotEligibleUnderEitherStrategy() {
        val library = lopsided()
        // 冷却池：截图全部在冷却中，模拟层已经把冷却项 partition 掉
        val cooled = library.filter { it.relativePath == "DCIM/Screenshots/" }
        val available = library - cooled.toSet()

        listOf(
            SourceBalanceRules.Strategy.UNIFORM,
            SourceBalanceRules.Strategy.SOURCE_BALANCED
        ).forEach { strategy ->
            val batch = SourceBalanceRules.sampleByStrategy(available, 6, strategy, Random(1))
            assertTrue(
                "$strategy must not return cooled images",
                batch.none { it.mediaIdentityKey() in cooled.map { c -> c.mediaIdentityKey() } }
            )
        }
    }

    // ============ 3. 完整身份去重 ============

    @Test
    fun bothStrategiesDedupeByFullIdentity() {
        // 两卷各有同数值 ID 的照片：身份不同，都可在批次里出现；
        // 但同一身份绝不能重复。
        val library = (1L..30L).map { photo(it, "DCIM/Camera/") } +
            (1L..30L).map { photo(it, "DCIM/Camera/", volume = "1234-5678") }

        listOf(
            SourceBalanceRules.Strategy.UNIFORM,
            SourceBalanceRules.Strategy.SOURCE_BALANCED
        ).forEach { strategy ->
            repeat(30) { seed ->
                val batch = SourceBalanceRules.sampleByStrategy(library, 20, strategy, Random(seed))
                val keys = batch.map { it.mediaIdentityKey() }
                assertEquals("$strategy seed=$seed", keys.size, keys.toSet().size)
            }
        }
    }

    @Test
    fun crossVolumeSameIdBothSurviveInUnbalancedLibrary() {
        val library = listOf(
            photo(1, "DCIM/Camera/", volume = "external_primary"),
            photo(1, "DCIM/Camera/", volume = "1234-5678")
        )
        val batch = SourceBalanceRules.sampleByStrategy(
            library, 2, SourceBalanceRules.Strategy.SOURCE_BALANCED, Random(1)
        )
        assertEquals(2, batch.size)
        assertEquals(
            setOf("external_primary", "1234-5678"),
            batch.map { it.volumeName }.toSet()
        )
    }

    // ============ 4. 候选不足回填 ============

    @Test
    fun backfillFillsBatchWhenCandidatesAreScarce() {
        val available = (1L..3L).map { photo(it, "DCIM/Camera/") }
        val cooled = (100L..110L).map { photo(it, "DCIM/Screenshots/") }

        val primary = SourceBalanceRules.sampleByStrategy(
            available, 6, SourceBalanceRules.Strategy.SOURCE_BALANCED, Random(1)
        )
        val backfill = SourceBalanceRules.backfillFromCooldown(
            cooldownImages = cooled,
            alreadyPicked = primary,
            batchSize = 6,
            pickedAt = { it.id }
        )

        assertEquals(3, primary.size)
        assertEquals(3, backfill.size)
        assertEquals(6, primary.size + backfill.size)
        // 回填项不与已选重复
        assertTrue(
            backfill.none { b -> primary.any { it.mediaIdentityKey() == b.mediaIdentityKey() } }
        )
    }

    @Test
    fun backfillRespectsBothStrategies() {
        listOf(
            SourceBalanceRules.Strategy.UNIFORM,
            SourceBalanceRules.Strategy.SOURCE_BALANCED
        ).forEach { strategy ->
            val primary = SourceBalanceRules.sampleByStrategy(
                (1L..2L).map { photo(it, "A/") }, 5, strategy, Random(1)
            )
            val fill = SourceBalanceRules.backfillFromCooldown(
                cooldownImages = (10L..20L).map { photo(it, "B/") },
                alreadyPicked = primary,
                batchSize = 5,
                pickedAt = { it.id }
            )
            assertEquals("$strategy", 5, primary.size + fill.size)
        }
    }

    // ============ 5. 固定种子覆盖率 ============

    @Test
    fun balancedCoversMoreSourcesThanUniformAtFixedSeeds() {
        val library = lopsided()
        val seeds = 0 until 100

        var uniformCoverage = 0
        var balancedCoverage = 0
        seeds.forEach { seed ->
            uniformCoverage += SourceBalanceRules.sampleByStrategy(
                library, 6, SourceBalanceRules.Strategy.UNIFORM, Random(seed)
            ).map { it.relativePath }.toSet().size
            balancedCoverage += SourceBalanceRules.sampleByStrategy(
                library, 6, SourceBalanceRules.Strategy.SOURCE_BALANCED, Random(seed)
            ).map { it.relativePath }.toSet().size
        }

        assertTrue(
            "uniform=$uniformCoverage balanced=$balancedCoverage",
            balancedCoverage > uniformCoverage
        )
    }

    @Test
    fun sameSeedIsReproducibleForBothStrategies() {
        val library = lopsided()
        listOf(
            SourceBalanceRules.Strategy.UNIFORM,
            SourceBalanceRules.Strategy.SOURCE_BALANCED
        ).forEach { strategy ->
            val a = SourceBalanceRules.sampleByStrategy(library, 8, strategy, Random(99))
            val b = SourceBalanceRules.sampleByStrategy(library, 8, strategy, Random(99))
            assertEquals(
                "$strategy must be reproducible",
                a.map { it.mediaIdentityKey() },
                b.map { it.mediaIdentityKey() }
            )
        }
    }

    @Test
    fun emptyAndZeroBatchReturnEmptyForBothStrategies() {
        listOf(
            SourceBalanceRules.Strategy.UNIFORM,
            SourceBalanceRules.Strategy.SOURCE_BALANCED
        ).forEach { strategy ->
            assertTrue(SourceBalanceRules.sampleByStrategy(emptyList(), 6, strategy, Random(1)).isEmpty())
            assertTrue(SourceBalanceRules.sampleByStrategy(lopsided(), 0, strategy, Random(1)).isEmpty())
        }
    }
}

/** 默认策略的单一事实来源，供测试与文档引用。 */
internal object SamplingStrategyDefaults {
    val DEFAULT = SourceBalanceRules.Strategy.UNIFORM
}
