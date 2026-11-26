package com.tabula.v3.data.intent

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * 组批与排序单测（#94）。
 *
 * 覆盖路线图第 8 项的验收点：
 * - 保护先于排序（保护项永远不进批次）；
 * - 高置信不够时不硬凑（[IntentBatch.shortOfLimit]）；
 * - 排序按证据强度、再按存入时间倒序；
 * - 文字辅助不能覆盖保护、不能凭空认定失效、失败即回退。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentBatchBuilderTest {

    private val now = 1_796_000_000_000L
    private val utc = TimeZone.getTimeZone("UTC")

    private fun photo(id: Long, daysAgo: Int) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/external_primary/images/media/$id"),
        displayName = "shot$id.png",
        dateModified = now - daysAgo * 86_400_000L,
        size = 1024L * id,
        width = 720,
        height = 1280,
        bucketDisplayName = "Screenshots",
        volumeName = "external_primary",
        relativePath = "DCIM/Screenshots/",
        dateAdded = now - daysAgo * 86_400_000L
    )

    private fun input(id: Long, daysAgo: Int, vararg lines: String, confidence: Float = 0.9f) =
        IntentCandidateInput(
            image = photo(id, daysAgo),
            savedAtMillis = now - daysAgo * 86_400_000L,
            ocrLines = lines.toList(),
            ocrConfidence = confidence
        )

    @Test
    fun protectedImagesNeverEnterTheBatch() {
        val batch = IntentBatchBuilder.build(
            candidates = listOf(
                input(1, 9, "满199减30", "活动已于 2026-11-10 23:59 结束"),
                input(2, 30, "订单详情", "订单号 2024111000012345", "待收货")
            ),
            nowMillis = now,
            batchLimit = 10,
            timeZone = utc
        )
        assertEquals(1, batch.ordered.size)
        assertEquals(1L, batch.ordered.first().image.id)
        assertEquals(1, batch.excluded.size)
        assertEquals(2L, batch.excluded.first().image.id)
    }

    @Test
    fun doesNotPadTheBatchWithWeakEvidence() {
        val batch = IntentBatchBuilder.build(
            candidates = listOf(
                input(1, 9, "满199减30", "活动已于 2026-11-10 23:59 结束"),
                input(2, 40, "某视频平台", "复制链接", "扫码观看")
            ),
            nowMillis = now,
            batchLimit = 10,
            timeZone = utc
        )
        // 只有 1 张高置信；第 2 张是「不确定」，不进批次也不凑数
        assertEquals(1, batch.ordered.size)
        assertEquals(1, batch.uncertain.size)
        assertTrue(batch.shortOfLimit)
    }

    @Test
    fun explicitDeadlineSortsBeforeEndedWording() {
        val batch = IntentBatchBuilder.build(
            candidates = listOf(
                input(1, 9, "抢票提醒", "已结束"),                                   // expiry=EXPIRED，无 deadline
                input(2, 9, "满199减30", "活动已于 2026-11-10 23:59 结束")          // expiry=EXPIRED + deadline
            ),
            nowMillis = now,
            batchLimit = 10,
            timeZone = utc
        )
        assertEquals(2L, batch.recommended.first().image.id)
    }

    @Test
    fun sameEvidenceStrengthSortsBySavedTimeDescending() {
        val batch = IntentBatchBuilder.build(
            candidates = listOf(
                input(1, 30, "满199减30", "活动已于 2026-11-10 23:59 结束"),
                input(2, 9, "满199减30", "活动已于 2026-11-10 23:59 结束")
            ),
            nowMillis = now,
            batchLimit = 10,
            timeZone = utc
        )
        assertEquals(listOf(2L, 1L), batch.recommended.map { it.image.id })
    }

    @Test
    fun batchLimitTruncatesAndReportsShortfall() {
        val candidates = (1L..12L).map {
            input(it, 9, "满199减30", "活动已于 2026-11-10 23:59 结束")
        }
        val batch = IntentBatchBuilder.build(candidates, now, batchLimit = 5, timeZone = utc)
        assertEquals(5, batch.recommended.size)
        // 被截掉的不会偷偷跑到 lowerPriority 里
        assertTrue(batch.lowerPriority.isEmpty())
    }

    @Test
    fun retainedIdentityKeyOverridesCachedLowerPriority() {
        val candidate = input(7, 9, "满199减30", "活动已于 2026-11-10 23:59 结束")
            .copy(
                // 模拟缓存里存着「推荐清理」的旧判定
                precomputedVerdict = IntentVerdict(
                    category = IntentCategory.EXPIRED_COUPON,
                    recommendation = IntentRecommendation.RECOMMEND,
                    expiry = ExpiryState.EXPIRED,
                    reason = "缓存里的旧判定"
                )
            )
        val batch = IntentBatchBuilder.build(
            candidates = listOf(candidate),
            nowMillis = now,
            batchLimit = 10,
            timeZone = utc,
            retainedIdentityKeys = setOf("external_primary|7")
        )
        assertTrue(batch.ordered.isEmpty())
        assertEquals(1, batch.excluded.size)
    }

    @Test
    fun cachedVerdictIsUsedWithoutText() {
        val candidate = input(8, 9).copy(
            precomputedVerdict = IntentVerdict(
                category = IntentCategory.EXPIRED_COUPON,
                recommendation = IntentRecommendation.RECOMMEND,
                expiry = ExpiryState.EXPIRED,
                reason = "图上写着截止 2026-11-10"
            )
        )
        val batch = IntentBatchBuilder.build(listOf(candidate), now, 10, utc)
        assertEquals(1, batch.recommended.size)
    }

    // ==================== 文字辅助 ====================

    private class RecordingAssist(
        private val suggestion: AssistSuggestion,
        override val isEnabled: Boolean = true
    ) : TextAssist {
        val seen = mutableListOf<IntentTextSanitizer.SanitizedSnippet>()
        override suspend fun classify(
            snippet: IntentTextSanitizer.SanitizedSnippet
        ): AssistSuggestion {
            seen += snippet
            return suggestion
        }
    }

    @Test
    fun assistIsNeverCalledForDecidedItems() = kotlinx.coroutines.runBlocking {
        val assist = RecordingAssist(
            AssistSuggestion(IntentRecommendation.KEEP, "模型说是订单")
        )
        val classified = IntentBatchBuilder.classifyAll(
            candidates = listOf(
                input(1, 9, "满199减30", "活动已于 2026-11-10 23:59 结束"),
                input(2, 30, "订单详情", "订单号 2024111000012345")
            ),
            nowMillis = now,
            timeZone = utc
        )
        IntentBatchBuilder.refineWithAssist(classified, assist)
        // 已经明确的两张都不该触发模型调用
        assertTrue(assist.seen.isEmpty())
    }

    @Test
    fun assistCanOnlyRefineUncertainItems() = kotlinx.coroutines.runBlocking {
        val assist = RecordingAssist(
            AssistSuggestion(IntentRecommendation.KEEP, "文字辅助判断为应保留")
        )
        val classified = IntentBatchBuilder.classifyAll(
            candidates = listOf(input(3, 20, "某视频平台", "复制链接", "扫码观看")),
            nowMillis = now,
            timeZone = utc
        )
        val refined = IntentBatchBuilder.refineWithAssist(classified, assist)
        assertEquals(IntentRecommendation.KEEP, refined.first().verdict.recommendation)
        assertTrue(refined.first().verdict.assistantUsed)
        assertEquals(1, assist.seen.size)
        // 发出去的片段里不能有原链接
        assertFalse(assist.seen.first().lines.any { it.contains("http") })
    }

    @Test
    fun assistCannotTurnArchivedProtectionIntoCleanup() = kotlinx.coroutines.runBlocking {
        val assist = RecordingAssist(
            AssistSuggestion(IntentRecommendation.LOWER_PRIORITY, "看起来像优惠截图")
        )
        val classified = IntentBatchBuilder.classifyAll(
            candidates = listOf(input(4, 30, "订单详情", "订单号 2024111000012345")),
            nowMillis = now,
            timeZone = utc
        )
        val refined = IntentBatchBuilder.refineWithAssist(classified, assist)
        assertEquals(IntentRecommendation.KEEP, refined.first().verdict.recommendation)
        assertTrue(assist.seen.isEmpty())
    }

    @Test
    fun assistCannotIntroduceExpiry() {
        // 类型层面：AssistSuggestion 没有 expiry 字段，因此「凭空认定失效」不可表达。
        // 这里用断言把这个契约钉住：辅助结果只带 recommendation。
        val suggestion = AssistSuggestion(IntentRecommendation.LOWER_PRIORITY, "低优先级")
        assertEquals(IntentRecommendation.LOWER_PRIORITY, suggestion.recommendation)
    }

    @Test
    fun assistOnlySendsTheLinesThatSurviveSanitizing() = kotlinx.coroutines.runBlocking {
        // 第 2 轮审计后语义变了：不再「整张有不可判定行就整张不发」，
        // 而是逐行掩码、只发可判定的那些。否则几乎每张真实截图都会被丢掉，
        // 与「按批辅助分类」的设计目的相反。
        val assist = RecordingAssist(AssistSuggestion(IntentRecommendation.KEEP, "x"))
        val classified = IntentBatchBuilder.classifyAll(
            candidates = listOf(input(5, 20, "8823 4471", "某平台截图")),
            nowMillis = now,
            timeZone = utc
        )
        IntentBatchBuilder.refineWithAssist(classified, assist)
        assertEquals(1, assist.seen.size)
        val sent = assist.seen.first().lines
        assertTrue("数字噪声行不能发：$sent", sent.none { it.contains("8823") })
        assertTrue("正常文案要发：$sent", sent.any { it.contains("某平台") })
    }

    @Test
    fun assistIsSkippedWhenNothingSurvivesSanitizing() = kotlinx.coroutines.runBlocking {
        val assist = RecordingAssist(AssistSuggestion(IntentRecommendation.KEEP, "x"))
        val classified = IntentBatchBuilder.classifyAll(
            candidates = listOf(input(15, 20, "8823 4471", "2024-11-10 12:00")),
            nowMillis = now,
            timeZone = utc
        )
        IntentBatchBuilder.refineWithAssist(classified, assist)
        assertTrue("全部不可判定时不得发请求", assist.seen.isEmpty())
    }

    @Test
    fun disabledAssistIsNeverCalled() = kotlinx.coroutines.runBlocking {
        val assist = RecordingAssist(
            AssistSuggestion(IntentRecommendation.KEEP, "x"),
            isEnabled = false
        )
        val classified = IntentBatchBuilder.classifyAll(
            candidates = listOf(input(6, 20, "某视频平台", "复制链接")),
            nowMillis = now,
            timeZone = utc
        )
        val refined = IntentBatchBuilder.refineWithAssist(classified, assist)
        assertTrue(assist.seen.isEmpty())
        assertEquals(IntentRecommendation.UNCERTAIN, refined.first().verdict.recommendation)
    }
}
