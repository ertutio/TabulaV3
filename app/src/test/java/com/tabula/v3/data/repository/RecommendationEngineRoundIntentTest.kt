package com.tabula.v3.data.repository

import android.content.Context
import android.net.Uri
import com.tabula.v3.data.balance.RoundCandidateRules
import com.tabula.v3.data.balance.RoundIntentRules
import com.tabula.v3.data.balance.SourceBalanceRules
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.preferences.WalkSamplingStrategy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #90 审计：通过真实 RecommendationEngine 连续请求多批，验证
 * - 意图由引擎原子消费、只影响下一批；
 * - 目标来源耗尽后回落到**用户所选策略**（不固定改成均衡）；
 * - 候选池与下一批抽样同口径。
 *
 * "可区分两种策略"的夹具：小来源 3 张、大来源 40 张。
 * - SOURCE_BALANCED 第一轮保证覆盖每个来源桶 → 每批都出现小来源；
 * - UNIFORM 是纯均匀随机 → 多轮里必然出现"覆盖不到小来源"的批次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RecommendationEngineRoundIntentTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPreferences

    private val shots = "external_primary|DCIM/Screenshots/"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = AppPreferences(context)
        context.getSharedPreferences("tabula_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs.recommendMode = RecommendMode.RANDOM_WALK
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
    }

    private fun photo(id: Long, path: String) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/external_primary/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L + id,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = path.trimEnd('/').substringAfterLast('/'),
        volumeName = "external_primary",
        relativePath = path
    )

    private fun sourceOf(image: ImageFile) = SourceBalanceRules.sourceKeyOf(image)

    private fun library() =
        (1L..40L).map { photo(it, "DCIM/Camera/") } +
            (100L..102L).map { photo(it, "DCIM/Screenshots/") }

    // ============ 意图的原子性 ============

    @Test
    fun engineConsumesIntentAtomically() = runBlocking {
        val engine = RecommendationEngine(context)
        engine.acceptRoundIntent(shots)

        assertTrue(engine.roundIntent.isActive)
        val consumed = engine.consumeRoundIntent()
        assertNotNull(consumed)
        assertEquals(shots, consumed!!.sourceKey)
        assertFalse("取出后引擎内意图应已消费", engine.roundIntent.isActive)
        assertNull("第二个调用者不能复用同一意图", engine.consumeRoundIntent())
    }

    @Test
    fun clearRoundIntentRemovesIt() = runBlocking {
        val engine = RecommendationEngine(context)
        engine.acceptRoundIntent(shots)
        engine.clearRoundIntent()
        assertFalse(engine.roundIntent.isActive)
        assertNull(engine.consumeRoundIntent())
    }

    // ============ 接受只影响下一批 ============

    @Test
    fun firstBatchUsesIntentSecondBatchFallsBackToUniform() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        val engine = RecommendationEngine(context)
        val all = library()

        engine.acceptRoundIntent(shots)
        val first = engine.getRecommendedBatch(all, 2)
        assertTrue("第一批应来自意图来源", first.all { sourceOf(it) == shots })

        val second = engine.getRecommendedBatch(all, 6)
        assertFalse(
            "第二批不能全是意图来源（应回落原随机）",
            second.all { sourceOf(it) == shots }
        )
        assertFalse("引擎内意图已消费", engine.roundIntent.isActive)
    }

    /**
     * 来源耗尽 → 回落**用户所选策略**。
     *
     * 夹具可区分两策略：小来源 3 张 / 大来源 40 张。
     * 意图来源是根本没照片的目录，因此每一批都走回退路径。
     */
    @Test
    fun exhaustedSourceFallsBackToUniformAcrossManyBatches() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        val engine = RecommendationEngine(context)
        val all = library()

        // 每次接受一个不存在的来源 → 回退路径必走
        val coverage = mutableListOf<Int>()
        repeat(20) {
            prefs.clearAllPickRecords()
            engine.acceptRoundIntent("external_primary|DCIM/Gone/")
            val batch = engine.getRecommendedBatch(all, 6)
            assertEquals("回退也要出满批", 6, batch.size)
            coverage += batch.map(::sourceOf).toSet().size
        }
        assertTrue(
            "原随机下必须出现覆盖不到小来源的批次（实际：$coverage）",
            coverage.any { it < 2 }
        )
    }

    @Test
    fun exhaustedSourceFallsBackToBalancedAcrossManyBatches() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.SOURCE_BALANCED
        val engine = RecommendationEngine(context)
        val all = library()

        val coverage = mutableListOf<Int>()
        repeat(10) {
            prefs.clearAllPickRecords()
            engine.acceptRoundIntent("external_primary|DCIM/Gone/")
            val batch = engine.getRecommendedBatch(all, 6)
            assertEquals(6, batch.size)
            coverage += batch.map(::sourceOf).toSet().size
        }
        assertTrue(
            "均衡策略下每批都必须覆盖两个来源（实际：$coverage）",
            coverage.all { it == 2 }
        )
    }

    /**
     * 同一份库、同一引擎：两种策略的回退结果必须**可区分**。
     * 若不区分，说明回退被写死成了某一种策略。
     */
    @Test
    fun exhaustedFallbackActuallyDiffersBetweenStrategies() = runBlocking {
        val all = library()

        prefs.walkSamplingStrategy = WalkSamplingStrategy.SOURCE_BALANCED
        val balancedEngine = RecommendationEngine(context)
        val balancedCoverage = mutableListOf<Int>()
        repeat(10) {
            prefs.clearAllPickRecords()
            balancedEngine.acceptRoundIntent("external_primary|DCIM/Gone/")
            balancedCoverage += balancedEngine.getRecommendedBatch(all, 6)
                .map(::sourceOf).toSet().size
        }

        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        val uniformEngine = RecommendationEngine(context)
        val uniformCoverage = mutableListOf<Int>()
        repeat(20) {
            prefs.clearAllPickRecords()
            uniformEngine.acceptRoundIntent("external_primary|DCIM/Gone/")
            uniformCoverage += uniformEngine.getRecommendedBatch(all, 6)
                .map(::sourceOf).toSet().size
        }

        assertTrue(
            "均衡平均覆盖必须高于原随机（均衡=${balancedCoverage.average()} " +
                "随机=${uniformCoverage.average()}）",
            balancedCoverage.average() > uniformCoverage.average()
        )
    }

    @Test
    fun partialSourceFillsRemainderAndConsumesIntent() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        val engine = RecommendationEngine(context)
        val all = library()

        engine.acceptRoundIntent(shots)
        // 小来源只有 3 张，批次 6：应全部取出后从其它来源补齐
        val first = engine.getRecommendedBatch(all, 6)
        assertEquals(6, first.size)
        assertEquals(3, first.count { sourceOf(it) == shots })
        assertFalse(engine.roundIntent.isActive)
    }

    @Test
    fun coolingBackfillStillWorksAfterIntent() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        val engine = RecommendationEngine(context)
        val few = (1L..3L).map { photo(it, "DCIM/Screenshots/") }
        engine.acceptRoundIntent(shots)

        val batch = engine.getRecommendedBatch(few, 6)

        assertTrue("可用照片只有 3 张时批次不超过它", batch.size <= 3)
        assertFalse(engine.roundIntent.isActive)
    }

    // ============ 候选池口径 ============

    @Test
    fun eligibleCandidatesExcludeCurrentBatchAndCooldown() = runBlocking {
        val engine = RecommendationEngine(context)
        val all = library()
        val current = all.take(2)

        val eligible = engine.eligibleNextBatchCandidates(all, currentBatch = current)

        val currentKeys = current.map { it.mediaIdentityKey() }.toSet()
        assertTrue(
            "当前批次已抽到的身份不算下一批候选",
            eligible.none { it.mediaIdentityKey() in currentKeys }
        )
        assertEquals(all.size - current.size, eligible.size)
    }

    @Test
    fun candidateRuleDropsCooldownIdentityAndLegacyBareId() {
        val all = library()
        val cooled = all.first()
        val eligible = RoundCandidateRules.eligibleForNextBatch(
            allImages = all,
            cooldownIdentityKeys = setOf(cooled.mediaIdentityKey()),
            cooldownLegacyBareIds = setOf(3L)
        )
        assertTrue(eligible.none { it.mediaIdentityKey() == cooled.mediaIdentityKey() })
        assertTrue("旧裸 ID 冷却同样生效", eligible.none { it.id == 3L })
    }

    // ============ 一次性 ============

    @Test
    fun repeatedConsumeReturnsNullAfterFirstUse() = runBlocking {
        val engine = RecommendationEngine(context)
        engine.acceptRoundIntent(shots)
        assertNotNull(engine.consumeRoundIntent())
        assertNull(engine.consumeRoundIntent())
        assertNull(engine.consumeRoundIntent())
        assertFalse(engine.roundIntent.isActive)
    }

    @Test
    fun clearingWhileIntentPendingMakesNextBatchStrategyOnly() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        val engine = RecommendationEngine(context)
        val all = library()

        engine.acceptRoundIntent(shots)
        engine.clearRoundIntent()

        val batch = engine.getRecommendedBatch(all, 6)
        assertEquals(6, batch.size)
        assertFalse(
            "清掉意图后不得再优先该来源",
            batch.all { sourceOf(it) == shots }
        )
    }

    @Test
    fun acceptedIntentSurvivesOnlyOneBatchEvenIfBatchIsEmpty() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        val engine = RecommendationEngine(context)
        engine.acceptRoundIntent(shots)

        // 空输入：引擎直接返回空，不消费意图（没有批次可影响）
        val empty = engine.getRecommendedBatch(emptyList(), 6)
        assertTrue(empty.isEmpty())
        assertTrue("空输入不消耗意图", engine.roundIntent.isActive)

        // 真有照片后，用在该批上并被消费
        val batch = engine.getRecommendedBatch(library(), 6)
        assertTrue(batch.isNotEmpty())
        assertFalse(engine.roundIntent.isActive)
    }
}
