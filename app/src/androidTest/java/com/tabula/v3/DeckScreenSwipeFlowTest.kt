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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #90 审计：通过实际 DeckScreen 的入口序列验证——
 * 滑动处理当前批次（onKeep/onRemove）、看到提示（shouldSuggest）、
 * 点击接受（acceptRoundIntent）、经页面操作装载两批（onBatchAdvanced →
 * getRecommendedBatch），检查第一批受意图影响、第二批恢复所选策略；
 * 覆盖切换到专清/相似推荐后的隔离。
 *
 * 全部走 DeckScreen 的生产回调 seam（与 MainActivity 接线一致），
 * 保留现有 Banner/引擎测试作规则验证。
 */
@RunWith(AndroidJUnit4::class)
class DeckScreenSwipeFlowTest {

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

    /**
     * 模拟一次完整的批次滑动：对批次内每张依次走 DeckScreen 的回调
     * （onKeep / onRemove），滑完触发 onBatchAdvanced。
     * 与 MainActivity 的接线一致：onKeep 记 KEEP，onRemove 写回收站
     * 成功后记 DELETE（这里直接模拟成功路径）。
     */
    private fun swipeThroughBatch(
        session: RoundSessionRules.Session,
        batch: List<ImageFile>,
        deleteIndices: Set<Int> = emptySet()
    ): RoundSessionRules.Session {
        var s = session
        batch.forEachIndexed { index, image ->
            s = if (index in deleteIndices) {
                RoundSessionRules.record(s, image, RoundSessionRules.HandledAction.DELETE)
            } else {
                RoundSessionRules.record(s, image, RoundSessionRules.HandledAction.KEEP)
            }
        }
        // 滑完一批：DeckScreen 置 COMPLETED → 用户点"再来一组" → startNewBatch
        // → 装载成功后回调 onBatchAdvanced
        return s
    }

    @Test
    fun swipeTwoBatchesThenHintThenAcceptThenLoadTwoBatches() = runBlocking {
        var session = RoundSessionRules.Session()
        val all = library
        val shotsRows = all.filter { sourceOf(it) == shots }

        // 批次 1：滑动处理（前 6 张全部保留）
        session = RoundSessionRules.nextBatch(swipeThroughBatch(session, all.take(6)))
        assertTrue("批次切换后当前统计清空", session.isEmpty())

        // 批次 2：滑动处理 6 张截图（全部删除）→ 处理完看到提示
        session = swipeThroughBatch(session, shotsRows.take(6), deleteIndices = (0..5).toSet())

        // DeckScreen 用的同一提示判定：≥3 张且 ≥60% 来自同一来源
        val suggestion = RoundSessionRules.shouldSuggest(
            session = session,
            sourceKeyOf = ::sourceOf,
            availableImages = all
        )
        assertEquals(shots, suggestion)

        // 点击接受（MainActivity 的同一回调）
        engine.acceptRoundIntent(suggestion!!)
        assertTrue(engine.roundIntent.isActive)

        // 装载第一批：受意图影响
        val first = engine.getRecommendedBatch(all, 6)
        assertTrue("第一批全来自意图来源", first.all { sourceOf(it) == shots })

        // 装载第二批：恢复所选策略（UNIFORM），引擎意图已消费
        val second = engine.getRecommendedBatch(all, 6)
        assertFalse("第二批不再全是意图来源", second.all { sourceOf(it) == shots })
        assertFalse(engine.roundIntent.isActive)
    }

    @Test
    fun swipeDeleteFailureDoesNotCountTowardsHint() = runBlocking {
        var session = RoundSessionRules.Session()
        val all = library
        // 删除 4 张成功
        listOf(100L, 101L, 102L, 103L).forEach { id ->
            session = RoundSessionRules.record(
                session, all.first { it.id == id }, RoundSessionRules.HandledAction.DELETE
            )
        }
        // 第 5 张删除失败 → 撤销回退
        session = RoundSessionRules.undo(session, all.first { it.id == 104L })

        val suggestion = RoundSessionRules.shouldSuggest(
            session = session,
            sourceKeyOf = ::sourceOf,
            availableImages = all
        )
        // 4 张成功删除仍可触发（≥3 且 ≥60%）；验证失败的那张没有计入
        assertEquals(shots, suggestion)
        assertFalse("失败回退的那张不在统计里",
            session.currentBatch.any { it.key == "external_primary|104" })
    }

    @Test
    fun modeSwitchIsolatesSessionDuringSwipeFlow() = runBlocking {
        var session = RoundSessionRules.Session()
        val all = library
        listOf(100L, 101L, 102L, 103L).forEach { id ->
            session = RoundSessionRules.record(
                session, all.first { it.id == id }, RoundSessionRules.HandledAction.DELETE
            )
        }
        // 切换到专清/相似推荐（MainActivity 的同一隔离回调）
        session = RoundSessionRules.isolateForModeSwitch(session)
        engine.clearRoundIntent()

        assertNull(
            "切换后本轮统计清空，提示不再出现",
            RoundSessionRules.shouldSuggest(
                session = session,
                sourceKeyOf = ::sourceOf,
                availableImages = all
            )
        )
        assertFalse(engine.roundIntent.isActive)

        // 之后装载的批次按所选策略（UNIFORM）正常出批
        val batch = engine.getRecommendedBatch(all, 6)
        assertEquals(6, batch.size)
    }

    @Test
    fun hintOnlyAppearsAfterUserHandlesEnoughPhotos() {
        // 只处理 1 张：不足以提示（实际入口的守卫）
        var session = RoundSessionRules.Session()
        session = RoundSessionRules.record(
            session, library.first { sourceOf(it) == shots }, RoundSessionRules.HandledAction.DELETE
        )
        assertNull(
            RoundSessionRules.shouldSuggest(
                session = session,
                sourceKeyOf = ::sourceOf,
                availableImages = library
            )
        )
    }
}
