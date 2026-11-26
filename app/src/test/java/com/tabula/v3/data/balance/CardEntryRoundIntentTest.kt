package com.tabula.v3.data.balance

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.WalkSamplingStrategy
import com.tabula.v3.data.repository.RecommendationEngine
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #90 审计：实际卡片入口验证——用户在卡片上保留/归档/删除后接受意图，
 * 第一批受意图影响，第二批恢复所选策略；引擎原子消费，卡片会话统计
 * 与引擎消费互不干扰。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CardEntryRoundIntentTest {

    private lateinit var engine: RecommendationEngine

    @Before
    fun setUp() {
        engine = RecommendationEngine(RuntimeEnvironment.getApplication())
        engine.clearRoundIntent()
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

    private val shots = "external_primary|DCIM/Screenshots/"

    @Test
    fun cardEntryFirstBatchAffectedSecondRestoresStrategy() = runBlocking {
        val all = (1L..60L).map { photo(it, "DCIM/Camera/") } +
            (100L..120L).map { photo(it, "DCIM/Screenshots/") }

        // 用户在卡片上连续处理截图（会话统计），然后接受提示
        var session = RoundSessionRules.Session()
        listOf(100L, 101L, 102L, 103L).forEach { id ->
            session = RoundSessionRules.record(
                session, all.first { it.id == id }, RoundSessionRules.HandledAction.DELETE
            )
        }
        engine.acceptRoundIntent(shots)

        // 第一批（接受意图后的第一批）
        val first = engine.getRecommendedBatch(all, 6)
        assertTrue("第一批受意图影响", first.all { sourceOf(it) == shots })

        // 第二批：引擎已消费，恢复用户所选策略（默认 UNIFORM）
        val second = engine.getRecommendedBatch(all, 6)
        assertFalse("第二批不再全是意图来源", second.all { sourceOf(it) == shots })
        assertFalse(engine.roundIntent.isActive)
    }

    @Test
    fun sessionStatsAndEngineConsumptionAreIndependent() = runBlocking {
        val all = (1L..10L).map { photo(it, "DCIM/Screenshots/") }
        var session = RoundSessionRules.Session()
        session = RoundSessionRules.record(session, all[0], RoundSessionRules.HandledAction.KEEP)

        // 引擎消费意图不影响会话统计
        engine.acceptRoundIntent(shots)
        engine.getRecommendedBatch(all, 6)
        assertEquals(1, session.currentBatch.size)

        // 会话切换也不影响引擎意图（已各自独立）
        session = RoundSessionRules.isolateForModeSwitch(session)
        assertTrue(session.isEmpty())
    }

    @Test
    fun balancedStrategyAlsoFallsBackAfterIntent() = runBlocking {
        val prefs = com.tabula.v3.data.preferences.AppPreferences(RuntimeEnvironment.getApplication())
        prefs.walkSamplingStrategy = WalkSamplingStrategy.SOURCE_BALANCED
        val all = (1L..60L).map { photo(it, "DCIM/Camera/") } +
            (100L..120L).map { photo(it, "DCIM/Screenshots/") }

        engine.acceptRoundIntent(shots)
        val first = engine.getRecommendedBatch(all, 6)
        assertTrue(first.all { sourceOf(it) == shots })

        val second = engine.getRecommendedBatch(all, 6)
        // SOURCE_BALANCED 第二批应覆盖多个来源
        assertTrue(second.map(::sourceOf).toSet().size >= 2)
        assertFalse(engine.roundIntent.isActive)
    }

    private fun sourceOf(image: ImageFile) = SourceBalanceRules.sourceKeyOf(image)
}
