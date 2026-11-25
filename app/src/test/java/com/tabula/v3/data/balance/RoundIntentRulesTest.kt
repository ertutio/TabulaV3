package com.tabula.v3.data.balance

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本轮整理意图规则测试（路线图 #83 第 7 项 / 审计 #90 第三轮）。
 *
 * 审计钉住的五条：
 * 1. 候选池与下一批抽样同口径（冷却 / 屏蔽 / 当前批次都被扣掉），
 *    已处理照片离开图库后仍能凭会话记录识别来源；
 * 2. 阈值：主来源 ≥3 且 ≥60%——2+1 不提示，3+2 提示；
 * 3. 来源耗尽后回落**用户所选策略**，不固定改成均衡；
 * 4. 接受只影响下一批，随后恢复所选策略；
 * 5. 本轮接受过同一来源后不再重复提示。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoundIntentRulesTest {

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

    private val screenshots = "external_primary|DCIM/Screenshots/"
    private val camera = "external_primary|DCIM/Camera/"

    private fun sourceOf(image: ImageFile) = SourceBalanceRules.sourceKeyOf(image)

    /** 一次会话里成功处理若干张（来源由照片自身决定）。 */
    private fun sessionHandling(
        images: List<ImageFile>,
        action: RoundSessionRules.HandledAction = RoundSessionRules.HandledAction.DELETE
    ): RoundSessionRules.Session =
        images.fold(RoundSessionRules.Session()) { state, image ->
            RoundSessionRules.record(state, image, action)
        }

    // ==================== 提示阈值 ====================

    @Test
    fun twoFromOneSourcePlusOneDoesNotSuggest() {
        // 2+1：条数不到 3，比例 67% 也不够
        val handled = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "DCIM/Screenshots/"),
            photo(3, "DCIM/Camera/")
        )
        val session = sessionHandling(handled)
        val eligible = listOf(photo(10, "DCIM/Screenshots/"))

        assertNull(
            "2+1 不提示：主来源只有 2 条成功记录，未达阈值",
            RoundIntentRules.shouldSuggest(session, eligible, ::sourceOf)
        )
    }

    @Test
    fun threeFromOneSourcePlusTwoSuggestsAtSixtyPercent() {
        // 3+2：3/5 = 60%，恰好达标
        val handled = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "DCIM/Screenshots/"),
            photo(3, "DCIM/Screenshots/"),
            photo(100, "DCIM/Camera/"),
            photo(101, "DCIM/Camera/")
        )
        val session = sessionHandling(handled)
        val eligible = listOf(photo(10, "DCIM/Screenshots/"))

        assertEquals(
            "3+2 = 60% 且主来源 3 条：应当提示",
            screenshots,
            RoundIntentRules.shouldSuggest(session, eligible, ::sourceOf)
        )
    }

    @Test
    fun belowSixtyPercentDoesNotSuggest() {
        // 3+4 = 43%：条数够，比例不够
        val handled = listOf(
            photo(1, "DCIM/Screenshots/"),
            photo(2, "DCIM/Screenshots/"),
            photo(3, "DCIM/Screenshots/")
        ) + (100L..103L).map { photo(it, "DCIM/Camera/") }
        val session = sessionHandling(handled)
        val eligible = listOf(photo(10, "DCIM/Screenshots/"))

        assertNull(
            "3+4 = 43% 不到 60%，不提示",
            RoundIntentRules.shouldSuggest(session, eligible, ::sourceOf)
        )
    }

    @Test
    fun emptySessionNeverSuggests() {
        assertNull(
            RoundIntentRules.shouldSuggest(
                RoundSessionRules.Session(), emptyList(), ::sourceOf
            )
        )
    }

    // ==================== 来源识别：已离开图库的照片 ====================

    @Test
    fun handledPhotosStillIdentifyTheirSourceAfterLeavingTheLibrary() {
        // 用户在截图相册里删掉了 4 张。这些照片已不在 eligibleImages 里
        // （删除 / 归类都会让它们离开图库），来源只能从会话记录快照看出。
        val handledRows = (1L..4L).map { photo(it, "DCIM/Screenshots/") }
        val session = sessionHandling(handledRows)

        // 下一批候选里只有别的来源（同来源确实耗尽了）
        val onlyOtherSources = (100L..110L).map { photo(it, "DCIM/Camera/") }
        assertNull(
            "同来源没有批外候选时不提示",
            RoundIntentRules.shouldSuggest(session, onlyOtherSources, ::sourceOf)
        )

        // 但同来源仍有批外候选时，凭会话记录就能认出它
        val withRemaining = onlyOtherSources + photo(50, "DCIM/Screenshots/")
        assertEquals(
            "已处理照片离开图库后仍能识别来源",
            screenshots,
            RoundIntentRules.shouldSuggest(session, withRemaining, ::sourceOf)
        )
    }

    @Test
    fun sourceKeySnapshotSurvivesThePhotoBeingDroppedFromThePool() {
        val handled = photo(7, "DCIM/Screenshots/")
        val session = sessionHandling(listOf(handled))
        // 入账时就把来源固化了，不依赖照片还在池子里
        assertEquals(screenshots, session.currentBatch.single().sourceKey)
        assertEquals("external_primary|7", session.currentBatch.single().key)
    }

    // ==================== 批外候选：只有批次内剩余 / 确有批外候选 ====================

    @Test
    fun onlyInBatchRemainderDoesNotSuggest() {
        // 当前批次 4 张截图，同来源"剩余"就是这 4 张本身——不在候选里出现，
        // 因为候选池已经扣掉当前批次。没有批外候选 → 不提示。
        val batch = (1L..4L).map { photo(it, "DCIM/Screenshots/") }
        val session = sessionHandling(batch)
        // 候选池按口径已扣当前批次，所以这里只剩相机
        val eligible = (100L..120L).map { photo(it, "DCIM/Camera/") }

        assertNull(
            "只有当前批次内的同来源照片时不提示",
            RoundIntentRules.shouldSuggest(session, eligible, ::sourceOf)
        )
    }

    @Test
    fun outOfBatchCandidateDoesSuggest() {
        val batch = (1L..4L).map { photo(it, "DCIM/Screenshots/") }
        val session = sessionHandling(batch)
        // 批外确实还有一张截图可用
        val eligible = (100L..120L).map { photo(it, "DCIM/Camera/") } +
            photo(50, "DCIM/Screenshots/")

        assertEquals(
            "确有批外候选时可以提示",
            screenshots,
            RoundIntentRules.shouldSuggest(session, eligible, ::sourceOf)
        )
    }

    // ==================== 一次性与忽略 ====================

    @Test
    fun dismissedSuppressesTheSuggestion() {
        val session = sessionHandling((1L..4L).map { photo(it, "DCIM/Screenshots/") })
        val eligible = listOf(photo(50, "DCIM/Screenshots/"))

        assertNull(
            RoundIntentRules.shouldSuggest(session, eligible, ::sourceOf, dismissed = true)
        )
    }

    @Test
    fun acceptingSameSourceSuppressesRepeatHintThisRound() {
        val session0 = sessionHandling((1L..4L).map { photo(it, "DCIM/Screenshots/") })
        val eligible = listOf(photo(50, "DCIM/Screenshots/"))
        assertEquals(screenshots, RoundIntentRules.shouldSuggest(session0, eligible, ::sourceOf))

        // 接受后：同一来源在本轮不再提示（避免处理过程中反复弹同一条）
        val afterAccept = RoundSessionRules.markAccepted(session0, screenshots)
        assertNull(
            "接受后同一轮不得反复弹同一提示",
            RoundIntentRules.shouldSuggest(afterAccept, eligible, ::sourceOf)
        )
    }

    // ==================== 抽样：接受 → 下一批 → 恢复所选策略 ====================

    private fun mixedLibrary() =
        (1L..20L).map { photo(it, "DCIM/Screenshots/") } +
            (100L..300L).map { photo(it, "DCIM/Camera/") }

    @Test
    fun acceptedIntentFillsBatchFromThatSource() {
        val intent = RoundIntentRules.accept(screenshots, nowMillis = 1L)
        val batch = RoundIntentRules.sampleWithIntent(
            available = mixedLibrary(),
            batchSize = 6,
            intent = intent,
            strategy = SourceBalanceRules.Strategy.UNIFORM,
            random = Random(1)
        )

        assertEquals(6, batch.size)
        assertTrue(batch.all { it.relativePath == "DCIM/Screenshots/" })
    }

    @Test
    fun intentOnlyAffectsTheNextBatchThenRestoresUniform() {
        val available = mixedLibrary()
        var intent = RoundIntentRules.accept(screenshots, 1L)

        val first = RoundIntentRules.sampleWithIntent(
            available, 6, intent, SourceBalanceRules.Strategy.UNIFORM, Random(1)
        )
        assertTrue(first.all { it.relativePath == "DCIM/Screenshots/" })

        intent = RoundIntentRules.consume(intent)
        val second = RoundIntentRules.sampleWithIntent(
            available, 6, intent, SourceBalanceRules.Strategy.UNIFORM, Random(1)
        )
        // 20 张截图 / 200 张相机：原随机下不可能整批都落在截图里
        assertFalse(
            "第二批必须恢复用户所选策略，不再整批来自意图来源",
            second.all { it.relativePath == "DCIM/Screenshots/" }
        )
        assertTrue("第二批仍要出满", second.size == 6)
    }

    @Test
    fun exhaustedSourceFallsBackToUniformNotBalanced() {
        // 意图来源已耗尽：只剩两个来源，且用户选的是原随机
        val cameraHeavy = (100L..160L).map { photo(it, "DCIM/Camera/") } +
            (200L..202L).map { photo(it, "DCIM/Screens/") }
        val intent = RoundIntentRules.accept(screenshots, 1L)

        val batch = RoundIntentRules.sampleWithIntent(
            available = cameraHeavy,
            batchSize = 6,
            intent = intent,
            strategy = SourceBalanceRules.Strategy.UNIFORM,
            random = Random(3)
        )

        assertEquals(6, batch.size)
        // 均衡策略会保证小来源（Screens，3 张）出现；原随机按 61:3 的概率
        // 极可能整批都是相机。这里钉住"回退走的是 UNIFORM"。
        val uniformReference = SourceBalanceRules.uniformSample(cameraHeavy, 6, Random(3))
        assertEquals(
            "来源耗尽必须回落到用户所选策略（原随机），不能固定改用均衡",
            uniformReference.map { it.mediaIdentityKey() },
            batch.map { it.mediaIdentityKey() }
        )
    }

    @Test
    fun exhaustedSourceFallsBackToBalancedWhenUserChoseBalanced() {
        val cameraHeavy = (100L..160L).map { photo(it, "DCIM/Camera/") } +
            (200L..202L).map { photo(it, "DCIM/Screens/") }
        val intent = RoundIntentRules.accept(screenshots, 1L)

        val batch = RoundIntentRules.sampleWithIntent(
            available = cameraHeavy,
            batchSize = 6,
            intent = intent,
            strategy = SourceBalanceRules.Strategy.SOURCE_BALANCED,
            random = Random(3)
        )

        val balancedReference = SourceBalanceRules.balancedSample(cameraHeavy, 6, Random(3))
        assertEquals(
            "用户选了均衡时，来源耗尽同样按均衡回退",
            balancedReference.map { it.mediaIdentityKey() },
            batch.map { it.mediaIdentityKey() }
        )
        assertTrue(
            "均衡回退会覆盖小来源",
            batch.map { it.relativePath }.toSet().size >= 2
        )
    }

    @Test
    fun partialSourceFillsRemainderWithChosenStrategy() {
        val available = (1L..2L).map { photo(it, "DCIM/Screenshots/") } +
            (100L..300L).map { photo(it, "DCIM/Camera/") }
        val intent = RoundIntentRules.accept(screenshots, 1L)

        val batch = RoundIntentRules.sampleWithIntent(
            available, 6, intent, SourceBalanceRules.Strategy.UNIFORM, Random(1)
        )
        assertEquals(6, batch.size)
        assertEquals(2, batch.count { it.relativePath == "DCIM/Screenshots/" })
    }

    @Test
    fun noIntentUsesChosenStrategyNotBalanced() {
        val available = mixedLibrary()
        val batch = RoundIntentRules.sampleWithIntent(
            available, 6, RoundIntentRules.clear(),
            SourceBalanceRules.Strategy.UNIFORM, Random(1)
        )
        assertEquals(
            "无意图时就是纯策略抽样",
            SourceBalanceRules.uniformSample(available, 6, Random(1)).map { it.mediaIdentityKey() },
            batch.map { it.mediaIdentityKey() }
        )
    }

    @Test
    fun intentBatchNeverRepeatsAnIdentity() {
        val available = (1L..10L).map { photo(it, "DCIM/Screenshots/") } +
            (1L..10L).map { photo(it, "DCIM/Screenshots/", volume = "1234-5678") }
        val intent = RoundIntentRules.accept(screenshots, 1L)

        val batch = RoundIntentRules.sampleWithIntent(
            available, 10, intent, SourceBalanceRules.Strategy.UNIFORM, Random(7)
        )
        val keys = batch.map { it.mediaIdentityKey() }
        assertEquals(keys.size, keys.toSet().size)
    }

    // ==================== 生命周期 ====================

    @Test
    fun intentIsActiveUntilConsumed() {
        val intent = RoundIntentRules.accept(screenshots, 1L)
        assertTrue(intent.isActive)

        val consumed = RoundIntentRules.consume(intent)
        assertFalse("只影响下一批", consumed.isActive)
        assertEquals(screenshots, consumed.sourceKey)
    }

    @Test
    fun clearReturnsToNoIntent() {
        val cleared = RoundIntentRules.clear()
        assertNull(cleared.sourceKey)
        assertFalse(cleared.isActive)
        assertEquals(0L, cleared.acceptedAt)
    }

    @Test
    fun intentIsSessionOnlyByConstruction() {
        // 本类型不带任何持久化字段；clear/consume 都在内存里产生新值。
        val intent = RoundIntentRules.accept(screenshots, 1L)
        assertEquals(
            listOf("sourceKey", "acceptedAt", "consumed"),
            intent.javaClass.declaredFields.map { it.name }.filterNot { it.contains('$') }
        )
    }
}
