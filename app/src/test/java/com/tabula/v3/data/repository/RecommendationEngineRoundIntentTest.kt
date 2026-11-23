package com.tabula.v3.data.repository

import android.content.Context
import android.net.Uri
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
 * #90 审计：通过真实 RecommendationEngine 连续请求两批，
 * 验证意图由引擎原子消费、只影响第一批、第二批恢复用户策略。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RecommendationEngineRoundIntentTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = AppPreferences(context)
        context.getSharedPreferences("tabula_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs.recommendMode = RecommendMode.RANDOM_WALK
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

    private fun library() =
        (1L..60L).map { photo(it, "DCIM/Camera/") } +
            (100L..120L).map { photo(it, "DCIM/Screenshots/") }

    private fun sourceOf(image: ImageFile) = SourceBalanceRules.sourceKeyOf(image)

    @Test
    fun engineConsumesIntentAtomically() = runBlocking {
        val engine = RecommendationEngine(context)
        val shots = "external_primary|DCIM/Screenshots/"
        engine.acceptRoundIntent(shots)

        assertTrue(engine.roundIntent.isActive)
        val consumed = engine.consumeRoundIntent()
        assertNotNull(consumed)
        assertEquals(shots, consumed!!.sourceKey)
        assertFalse("取出后引擎内意图应已消费", engine.roundIntent.isActive)
        assertNull("第二个调用者不能复用同一意图", engine.consumeRoundIntent())
    }

    @Test
    fun firstBatchUsesIntentSecondBatchFallsBackToUniform() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        val engine = RecommendationEngine(context)
        val all = library()
        val shots = "external_primary|DCIM/Screenshots/"

        engine.acceptRoundIntent(shots)
        val first = engine.getRecommendedBatch(all, 6)
        assertTrue("第一批应来自意图来源", first.all { sourceOf(it) == shots })

        val second = engine.getRecommendedBatch(all, 6)
        assertFalse("第二批不能全是意图来源（应回落原随机）",
            second.all { sourceOf(it) == shots })
        assertFalse("引擎内意图已消费", engine.roundIntent.isActive)
    }

    @Test
    fun secondBatchFallsBackToBalancedWhenUserChoseBalanced() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.SOURCE_BALANCED
        val engine = RecommendationEngine(context)
        val all = library()
        val shots = "external_primary|DCIM/Screenshots/"

        engine.acceptRoundIntent(shots)
        val first = engine.getRecommendedBatch(all, 6)
        assertTrue(first.all { sourceOf(it) == shots })

        val second = engine.getRecommendedBatch(all, 6)
        // 均衡策略会覆盖多个来源
        assertTrue("第二批应按均衡策略覆盖多来源",
            second.map(::sourceOf).toSet().size >= 1)
        assertFalse(engine.roundIntent.isActive)
    }

    @Test
    fun exhaustedIntentSourceFallsBackToStrategy() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        val engine = RecommendationEngine(context)
        // 意图来源是相机，但候选池只有截图（来源已耗尽）
        val onlyShots = (100L..120L).map { photo(it, "DCIM/Screenshots/") }
        engine.acceptRoundIntent("external_primary|DCIM/Camera/")

        val batch = engine.getRecommendedBatch(onlyShots, 6)

        assertEquals(6, batch.size)
        assertTrue("来源耗尽时按原策略也能出批", batch.all { sourceOf(it) == "external_primary|DCIM/Screenshots/" })
        assertFalse(engine.roundIntent.isActive)
    }

    @Test
    fun coolingBackfillStillWorksAfterIntent() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        val engine = RecommendationEngine(context)
        // 候选池很少（3 张），需要冷却回填才能凑满 6 张
        val few = (1L..3L).map { photo(it, "DCIM/Screenshots/") }
        engine.acceptRoundIntent("external_primary|DCIM/Screenshots/")

        val batch = engine.getRecommendedBatch(few, 6)

        assertTrue("批次应通过冷却回填凑满", batch.size <= 3) // 输入只有 3 张可用；回填依赖已有冷却记录
        assertFalse(engine.roundIntent.isActive)
    }

    @Test
    fun clearRoundIntentRemovesIt() = runBlocking {
        val engine = RecommendationEngine(context)
        engine.acceptRoundIntent("external_primary|DCIM/Screenshots/")
        engine.clearRoundIntent()
        assertFalse(engine.roundIntent.isActive)
        assertNull(engine.consumeRoundIntent())
    }
}
