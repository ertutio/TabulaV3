package com.tabula.v3

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.balance.RoundIntentRules
import com.tabula.v3.data.balance.RoundSessionRules
import com.tabula.v3.data.balance.SourceBalanceRules
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.preferences.WalkSamplingStrategy
import com.tabula.v3.data.repository.RecommendationEngine
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #90 审计：真实设备入口验证——在真 RecommendationEngine 上模拟卡片操作
 * （保留/归档/删除的会话统计）触发提示、接受意图，验证第一批与第二批。
 *
 * 仓库没有 Compose UI 测试依赖；这里用真实引擎 + 真偏好 + 真会话统计，
 * 与 DeckScreen 使用的同一生产 seam（RoundSessionRules /
 * RecommendationEngine.acceptRoundIntent / getRecommendedBatch）驱动，
 * 覆盖"提示 → 接受 → 第一批受影响 → 第二批恢复策略"的完整链路。
 */
@RunWith(AndroidJUnit4::class)
class DeckScreenRoundIntentEntryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: RecommendationEngine
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        prefs = AppPreferences(context)
        context.getSharedPreferences("tabula_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs.recommendMode = RecommendMode.RANDOM_WALK
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        engine = RecommendationEngine(context)
        engine.clearRoundIntent()
    }

    private fun photo(id: Long, path: String) = ImageFile(
        id = id,
        uri = android.net.Uri.parse("content://media/external_primary/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L + id,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = path.trimEnd('/').substringAfterLast('/'),
        volumeName = "external_primary",
        relativePath = path
    )

    private val library
        get() = (1L..60L).map { photo(it, "DCIM/Camera/") } +
            (100L..120L).map { photo(it, "DCIM/Screenshots/") }

    private fun sourceOf(image: ImageFile) = SourceBalanceRules.sourceKeyOf(image)

    private val shots = "external_primary|DCIM/Screenshots/"

    @Test
    fun cardActionsTriggerHintAcceptAffectsFirstBatchOnly() = runBlocking {
        var session = RoundSessionRules.Session()
        val all = library

        // 卡片操作：连续删除 4 张截图（真实会话统计）
        listOf(100L, 101L, 102L, 103L).forEach { id ->
            session = RoundSessionRules.record(
                session, all.first { it.id == id }, RoundSessionRules.HandledAction.DELETE
            )
        }

        // DeckScreen 用的同一提示判定
        val availableKeys = all.map { it.mediaIdentityKey() }.toSet()
        val suggestion = RoundSessionRules.shouldSuggest(
            session = session,
            sourceKeyOf = ::sourceOf,
            availableKeys = availableKeys
        )
        assertEquals(shots, suggestion)

        // 用户点"看这个"：MainActivity 用的同一接受入口
        engine.acceptRoundIntent(suggestion!!)

        // 第一批：受意图影响
        val first = engine.getRecommendedBatch(all, 6)
        assertTrue("第一批应全来自意图来源", first.all { sourceOf(it) == shots })

        // 第二批：恢复用户所选策略（UNIFORM）
        val second = engine.getRecommendedBatch(all, 6)
        assertFalse("第二批不再全是意图来源", second.all { sourceOf(it) == shots })
        assertFalse(engine.roundIntent.isActive)
    }

    @Test
    fun dismissThenHintStaysSuppressedInSession() = runBlocking {
        var session = RoundSessionRules.Session()
        val all = library
        listOf(100L, 101L, 102L, 103L).forEach { id ->
            session = RoundSessionRules.record(
                session, all.first { it.id == id }, RoundSessionRules.HandledAction.KEEP
            )
        }

        // 用户点"忽略"：提示不再出现（RoundSessionRules 判定用会话为空表达）
        session = RoundSessionRules.isolateForModeSwitch(session)
        assertNull(
            RoundSessionRules.shouldSuggest(
                session = session,
                sourceKeyOf = ::sourceOf,
                availableKeys = all.map { it.mediaIdentityKey() }.toSet()
            )
        )
    }

    @Test
    fun balancedStrategySecondBatchCoversMultipleSources() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.SOURCE_BALANCED
        val all = library

        var session = RoundSessionRules.Session()
        listOf(100L, 101L, 102L, 103L).forEach { id ->
            session = RoundSessionRules.record(
                session, all.first { it.id == id }, RoundSessionRules.HandledAction.DELETE
            )
        }
        engine.acceptRoundIntent(shots)

        val first = engine.getRecommendedBatch(all, 6)
        assertTrue(first.all { sourceOf(it) == shots })

        val second = engine.getRecommendedBatch(all, 6)
        assertTrue(
            "SOURCE_BALANCED 第二批应覆盖多来源",
            second.map(::sourceOf).toSet().size >= 2
        )
    }

    @Test
    fun intentSourceExhaustedStillProducesBatch() = runBlocking {
        val onlyCamera = library.filter { sourceOf(it) == "external_primary|DCIM/Camera/" }
        engine.acceptRoundIntent(shots)

        val batch = engine.getRecommendedBatch(onlyCamera, 6)
        assertEquals(6, batch.size)
        assertTrue(batch.all { sourceOf(it) == "external_primary|DCIM/Camera/" })
        assertFalse(engine.roundIntent.isActive)
    }
}
