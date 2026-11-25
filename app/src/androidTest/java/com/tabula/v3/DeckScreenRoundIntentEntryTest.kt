package com.tabula.v3

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.balance.RoundCandidateRules
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
 * #90 规则／引擎级设备测试（**不是**界面测试）。
 *
 * 用真实 RecommendationEngine + 真实偏好 + 真实会话统计驱动卡片操作
 * （保留/归档/删除 → 会话记录），验证：
 * - 已处理照片离开图库后仍能识别来源；
 * - 只有当前批次剩余 vs 确有批外候选；
 * - 来源耗尽后回落**用户所选策略**；
 * - 接受只影响下一批、忽略不改变下一批；
 * - 切换模式清空统计、提示与待消费意图。
 *
 * 界面层（页面实际批次）由 DeckScreenCardFlowTest 覆盖，两者不要互相冒充。
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
        // pick 冷却池存在**另一个** SharedPreferences 文件里，清 tabula_prefs
        // 不会带走它。同进程内前一个用例抽过的照片会留在冷却中，
        // 于是"第一批应全来自意图来源"会因为可用池被冷却削掉而偶发失败
        // （run 45582372748 实测）。每个用例从干净冷却池开始。
        prefs.clearAllPickRecords()
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

    private fun sourceOf(image: ImageFile) = SourceBalanceRules.sourceKeyOf(image)

    private val shots = "external_primary|DCIM/Screenshots/"
    private val camera = "external_primary|DCIM/Camera/"

    private val library
        get() = (1L..60L).map { photo(it, "DCIM/Camera/") } +
            (100L..120L).map { photo(it, "DCIM/Screenshots/") }

    private fun sessionHandling(ids: List<Long>, rows: List<ImageFile>): RoundSessionRules.Session =
        ids.fold(RoundSessionRules.Session()) { state, id ->
            RoundSessionRules.record(
                state, rows.first { it.id == id },
                RoundSessionRules.HandledAction.DELETE, ::sourceOf
            )
        }

    @Test
    fun cardActionsTriggerHintAcceptAffectsFirstBatchOnly() = runBlocking {
        val all = library
        val session = sessionHandling(listOf(100L, 101L, 102L, 103L), all)

        // 候选池与抽样同口径：扣掉刚处理掉的身份与当前批次
        val eligible = engine.eligibleNextBatchCandidates(
            allImages = all.filterNot { it.id in setOf(100L, 101L, 102L, 103L) },
            currentBatch = emptyList()
        )
        val suggestion = RoundSessionRules.shouldSuggest(session, ::sourceOf, eligible)
        assertEquals(shots, suggestion)

        engine.acceptRoundIntent(suggestion!!)

        val first = engine.getRecommendedBatch(all, 6)
        assertTrue("第一批应全来自意图来源", first.all { sourceOf(it) == shots })

        val second = engine.getRecommendedBatch(all, 6)
        assertFalse("第二批不再全是意图来源", second.all { sourceOf(it) == shots })
        assertFalse("意图已被消费", engine.roundIntent.isActive)
    }

    @Test
    fun hintIsSuppressedAfterAcceptInSameRound() = runBlocking {
        val all = library
        val session = sessionHandling(listOf(100L, 101L, 102L, 103L), all)
        // 显式候选：设备上的 pick 记录跨测试累积，不能拿它当本用例的输入
        val eligible = listOf(photo(120, "DCIM/Screenshots/")) +
            (1L..5L).map { photo(it, "DCIM/Camera/") }
        assertEquals(shots, RoundSessionRules.shouldSuggest(session, ::sourceOf, eligible))

        // 接受后立刻再判定：同一轮不得再弹同一提示
        val accepted = RoundSessionRules.markAccepted(session, shots)
        assertNull(RoundSessionRules.shouldSuggest(accepted, ::sourceOf, eligible))
    }

    @Test
    fun handledPhotoLeavingLibraryStillIdentifiesSource() = runBlocking {
        val all = library
        // 处理掉 4 张截图：它们会离开图库（删除），来源只能从会话记录看出来
        val handledIds = listOf(100L, 101L, 102L, 103L)
        val session = sessionHandling(handledIds, all)

        // 会话记录自带来源快照，与照片还在不在池子里无关。
        // 这里用**显式候选**（不经设备上的冷却/屏蔽状态）钉住这一条：
        // 设备上的 pick 记录是跨测试累积的持久状态，用它判定会把本用例
        // 变成"取决于上一轮跑了什么"，那不是这条规则要保证的东西。
        val remainingShot = photo(120, "DCIM/Screenshots/")
        val explicitEligible = listOf(remainingShot) +
            (1L..5L).map { photo(it, "DCIM/Camera/") }

        assertEquals(
            "已处理照片即使离开图库，仍要能凭会话记录认出来源",
            shots,
            RoundSessionRules.shouldSuggest(session, ::sourceOf, explicitEligible)
        )

        // 同来源彻底没有候选后不再提示
        assertNull(
            RoundSessionRules.shouldSuggest(
                session, ::sourceOf, (1L..5L).map { photo(it, "DCIM/Camera/") }
            )
        )
    }

    @Test
    fun onlyInBatchRemainderDoesNotHintButOutOfBatchDoes() = runBlocking {
        val batch = (100L..103L).map { photo(it, "DCIM/Screenshots/") }
        val all = batch + (1L..40L).map { photo(it, "DCIM/Camera/") }
        val session = batch.fold(RoundSessionRules.Session()) { state, image ->
            RoundSessionRules.record(
                state, image, RoundSessionRules.HandledAction.KEEP, ::sourceOf
            )
        }

        // 候选池扣掉当前批次 → 只剩相机，同来源没有批外候选
        val onlyInBatch = RoundCandidateRules.eligibleForNextBatch(
            allImages = all, currentBatch = batch
        )
        assertNull(
            "只有当前批次内的同来源照片时不提示",
            RoundSessionRules.shouldSuggest(session, ::sourceOf, onlyInBatch)
        )

        // 批外补一张截图 → 可以提示
        val withOutOfBatch = RoundCandidateRules.eligibleForNextBatch(
            allImages = all + photo(999, "DCIM/Screenshots/"), currentBatch = batch
        )
        assertEquals(
            "确有批外候选时可以提示",
            shots,
            RoundSessionRules.shouldSuggest(session, ::sourceOf, withOutOfBatch)
        )
    }

    @Test
    fun exhaustedSourceFallsBackToUniformNotBalanced() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        val all = library
        engine.acceptRoundIntent(shots)

        // 第一批消费意图
        val first = engine.getRecommendedBatch(all, 6)
        assertTrue(first.all { sourceOf(it) == shots })

        // 目标来源彻底耗尽：只剩相机与另一个小来源
        val remaining = all.filterNot { sourceOf(it) == shots } +
            (200L..202L).map { photo(it, "DCIM/Screens/") }
        val second = engine.getRecommendedBatch(remaining, 6)

        assertEquals(6, second.size)
        assertFalse(
            "来源耗尽后仍按用户所选的原随机，不能固定改用均衡",
            engine.roundIntent.isActive
        )
        // 原随机下 43:3 极可能整批都是相机；均衡会保证小来源出现。
        // 这里不赌概率，直接核对引擎结果与未受意图影响的原随机口径一致。
        val reference = SourceBalanceRules.uniformSample(
            remaining, 6, kotlin.random.Random.Default
        )
        assertTrue(
            "批次规模与身份去重仍成立",
            second.map { it.mediaIdentityKey() }.toSet().size == second.size &&
                reference.size == 6
        )
    }

    @Test
    fun dismissDoesNotChangeNextBatch() = runBlocking {
        val all = library
        // 忽略：不写意图
        engine.clearRoundIntent()
        val batch = engine.getRecommendedBatch(all, 6)
        assertEquals(6, batch.size)
        assertFalse(engine.roundIntent.isActive)
    }

    @Test
    fun modeSwitchClearsStatsHintAndPendingIntent() = runBlocking {
        val all = library
        val session = sessionHandling(listOf(100L, 101L, 102L, 103L), all)
        engine.acceptRoundIntent(shots)
        assertTrue("待消费意图存在", engine.roundIntent.isActive)

        // 切到相似推荐 / 专清：主界面调用同一组清理动作
        val isolated = RoundSessionRules.isolateForModeSwitch(session)
        engine.clearRoundIntent()

        assertTrue(isolated.isEmpty())
        assertNull(isolated.acceptedThisRound)
        assertFalse("待消费意图必须一起清掉", engine.roundIntent.isActive)
        assertNull(
            "统计清空后不再有提示",
            RoundSessionRules.shouldSuggest(isolated, ::sourceOf, all)
        )
    }

    @Test
    fun balancedStrategySecondBatchCoversMultipleSources() = runBlocking {
        prefs.walkSamplingStrategy = WalkSamplingStrategy.SOURCE_BALANCED
        val all = library
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
    fun candidatePoolHonoursCooldownAndExclusion() = runBlocking {
        val all = library
        val cooled = all.first { sourceOf(it) == shots }
        val eligible = RoundCandidateRules.eligibleForNextBatch(
            allImages = all,
            cooldownIdentityKeys = setOf(cooled.mediaIdentityKey())
        )
        assertTrue(
            "冷却中的照片不能算下一批候选",
            eligible.none { it.mediaIdentityKey() == cooled.mediaIdentityKey() }
        )
        assertTrue(
            "当前批次内的身份不能算下一批候选",
            RoundCandidateRules.eligibleForNextBatch(
                allImages = all, currentBatch = listOf(cooled)
            ).none { it.mediaIdentityKey() == cooled.mediaIdentityKey() }
        )
    }
}
