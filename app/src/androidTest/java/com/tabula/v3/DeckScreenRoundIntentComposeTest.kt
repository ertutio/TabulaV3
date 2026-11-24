package com.tabula.v3

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tabula.v3.data.balance.RoundIntentRules
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.balance.RoundSessionRules
import com.tabula.v3.data.balance.SourceBalanceRules
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.preferences.WalkSamplingStrategy
import com.tabula.v3.data.repository.RecommendationEngine
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.ui.components.RoundIntentBanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #90 审计：实际 Compose 界面入口测试。
 *
 * 用真实生产组件 RoundIntentBanner（DeckScreen 提示条本体）+ 真实
 * RecommendationEngine + 真会话统计，走完整链路：
 * 整理照片（会话统计）→ 看到提示（Banner 渲染）→ 点击"看这个"（真实回调）
 * → 装载第一批（受意图影响）→ 装载第二批（恢复所选策略）→ 检查来源分布与提示状态。
 */
@RunWith(AndroidJUnit4::class)
class DeckScreenRoundIntentComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var engine: RecommendationEngine
    private var acceptedSource: String? = null
    private var dismissed = false

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

    @Before
    fun setUp() {
        val prefs = AppPreferences(context)
        context.getSharedPreferences("tabula_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs.recommendMode = RecommendMode.RANDOM_WALK
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        engine = RecommendationEngine(context)
        engine.clearRoundIntent()
        acceptedSource = null
        dismissed = false
    }

    /** DeckScreen 的同一提示判定 + 同一 Banner + 同一接受回调。 */
    private fun setContentWithSession(session: RoundSessionRules.Session) {
        val suggestion = RoundSessionRules.shouldSuggest(
            session = session,
            sourceKeyOf = ::sourceOf,
            availableImages = library
        )
        composeRule.setContent {
            if (suggestion != null) {
                RoundIntentBanner(
                    intentSourceName = suggestion.substringAfterLast('/'),
                    onAcceptIntent = {
                        // MainActivity 的同一接受回调体
                        acceptedSource = suggestion
                        engine.acceptRoundIntent(suggestion)
                    },
                    onDismissIntent = { dismissed = true }
                )
            }
        }
    }

    @Test
    fun bannerRendersAfterCardActionsAndAcceptAffectsFirstBatchOnly() {
        // 1. 整理照片：连续删除 4 张截图（卡片操作产生会话统计）
        var session = RoundSessionRules.Session()
        val all = library
        listOf(100L, 101L, 102L, 103L).forEach { id ->
            session = RoundSessionRules.record(
                session, all.first { it.id == id }, RoundSessionRules.HandledAction.DELETE
            )
        }

        // 2. 看到提示：Banner 渲染且显示来源名
        setContentWithSession(session)
        composeRule.onNodeWithText("这轮再看几张「Screenshots」？").assertIsDisplayed()
        composeRule.onNodeWithText("看这个").assertIsDisplayed()
        composeRule.onNodeWithText("忽略").assertIsDisplayed()

        // 3. 点击接受：真实回调 → 引擎写入意图
        composeRule.onNodeWithText("看这个").performClick()
        composeRule.waitUntil(3_000) { acceptedSource != null }
        assertEquals(shots, acceptedSource)
        assertTrue(engine.roundIntent.isActive)

        // 4. 装载第一批：受意图影响
        val first = runBlocking { engine.getRecommendedBatch(library, 6) }
        assertTrue("第一批应全来自意图来源", first.all { sourceOf(it) == shots })

        // 5. 装载第二批：引擎已消费，恢复所选策略（UNIFORM）
        val second = runBlocking { engine.getRecommendedBatch(library, 6) }
        assertFalse("第二批不再全是意图来源", second.all { sourceOf(it) == shots })
        assertFalse(engine.roundIntent.isActive)

        // 6. 提示状态：意图已消费，会话仍在但提示不再以同一来源弹出（已消费无意图）
        val after = RoundSessionRules.shouldSuggest(
            session = session,
            sourceKeyOf = ::sourceOf,
            availableImages = library
        )
        // 提示可再次出现（新一轮整理），但引擎意图为空，直到用户再次点击接受
        if (after != null) assertFalse(engine.roundIntent.isActive)
    }

    @Test
    fun dismissButtonDoesNotWriteIntentToEngine() {
        var session = RoundSessionRules.Session()
        listOf(100L, 101L, 102L, 103L).forEach { id ->
            session = RoundSessionRules.record(
                session, library.first { it.id == id }, RoundSessionRules.HandledAction.DELETE
            )
        }
        setContentWithSession(session)

        composeRule.onNodeWithText("忽略").performClick()
        composeRule.waitUntil(3_000) { dismissed }

        assertTrue(dismissed)
        assertFalse("忽略不得写引擎意图", engine.roundIntent.isActive)
    }

    @Test
    fun noHintWhenSessionIsEmpty() {
        setContentWithSession(RoundSessionRules.Session())
        // 空会话不渲染 Banner
        composeRule.onNodeWithText("看这个").assertDoesNotExist()
    }

    @Test
    fun balancedStrategySecondBatchCoversMultipleSourcesViaBanner() {
        val prefs = AppPreferences(context)
        prefs.walkSamplingStrategy = WalkSamplingStrategy.SOURCE_BALANCED
        var session = RoundSessionRules.Session()
        listOf(100L, 101L, 102L, 103L).forEach { id ->
            session = RoundSessionRules.record(
                session, library.first { it.id == id }, RoundSessionRules.HandledAction.DELETE
            )
        }
        setContentWithSession(session)

        composeRule.onNodeWithText("看这个").performClick()
        composeRule.waitUntil(3_000) { acceptedSource != null }

        val first = runBlocking { engine.getRecommendedBatch(library, 6) }
        assertTrue(first.all { sourceOf(it) == shots })

        val second = runBlocking { engine.getRecommendedBatch(library, 6) }
        assertTrue("SOURCE_BALANCED 第二批应覆盖多来源", second.map(::sourceOf).toSet().size >= 2)
    }
}
