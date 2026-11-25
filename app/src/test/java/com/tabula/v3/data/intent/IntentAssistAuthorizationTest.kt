package com.tabula.v3.data.intent

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 批次授权的单测（#95 第 3 轮审计，第 2 条）。
 *
 * 审计要求区分两件事，并补测试证明：
 * - 用户在**当前批**主动开启辅助后重算 → 授权保留；
 * - 提交 / 长期保留 / 范围变化后**重建批次** → 撤销旧授权、作废旧请求，
 *   按本地模式分析，**新批默认关闭且零请求**；
 - 迟到的旧结果不得覆盖新批。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentAssistAuthorizationTest {

    private class CountingTransport(
        private val delayMs: Long = 0,
        private val answer: String = """{"choices":[{"message":{"content":"LOWER"}}]}"""
    ) : AssistTransport {
        val payloads = mutableListOf<String>()
        override fun post(
            url: String,
            body: String,
            authHeader: String,
            timeoutMillis: Int,
            headers: Map<String, String>
        ): String {
            payloads += body
            if (delayMs > 0) Thread.sleep(delayMs)
            return answer
        }
    }

    private fun keyStore(): IntentAssistKeyStore {
        val file = File.createTempFile("auth-key", ".tmp").apply { deleteOnExit() }
        return IntentAssistKeyStore(file).apply { write("sk-synthetic-not-a-real-key") }
    }

    private fun snippet() = IntentTextSanitizer.sanitizeForAssistant(
        listOf("某视频平台", "复制链接", "扫码观看")
    )

    // ==================== 状态机本身 ====================

    @Test
    fun userToggleKeepsAuthorization() {
        val auth = IntentAssistAuthorization()
        assertFalse("初始必须关闭", auth.granted)

        auth.onUserToggle(true)
        assertTrue("用户开启后应保持", auth.granted)
        assertEquals(IntentAssistAuthorization.Transition.USER_ENABLED, auth.lastTransition)

        // 用户自己开关不会推进代际：否则他自己的在途请求会被误判过期
        assertEquals(0, auth.epoch)

        auth.onUserToggle(false)
        assertFalse(auth.granted)
        assertEquals(IntentAssistAuthorization.Transition.USER_DISABLED, auth.lastTransition)
    }

    @Test
    fun rebuildRevokesAuthorizationAndBumpsEpoch() {
        val auth = IntentAssistAuthorization()
        auth.acknowledgeNotice()
        auth.onUserToggle(true)
        val epochBefore = auth.epoch

        auth.onRebuild()

        assertFalse("重建后必须关闭", auth.granted)
        assertFalse("说明确认也要清掉，新批需重新看", auth.noticeAcknowledged)
        assertTrue("代际必须推进", auth.epoch > epochBefore)
        assertEquals(IntentAssistAuthorization.Transition.REVOKED_ON_REBUILD, auth.lastTransition)
    }

    @Test
    fun rebuildAfterUserEnableLeavesGrantedFalse() {
        // 精确复现页面上的顺序：用户开启 → 提交 → rebuild
        val auth = IntentAssistAuthorization()
        auth.acknowledgeNotice()
        auth.onUserToggle(true)
        assertTrue(auth.granted)
        auth.onRebuild()
        assertFalse("重建后 granted 必须为 false（界面读的就是它）", auth.granted)
        assertFalse(auth.effective)
    }

    @Test
    fun staleEpochIsReportedAsStale() {
        val auth = IntentAssistAuthorization()
        auth.onUserToggle(true)
        val epochAtRequest = auth.epoch
        assertFalse(auth.isStale(epochAtRequest))

        auth.onRebuild()
        assertTrue("重建后旧代际的请求必须被判过期", auth.isStale(epochAtRequest))
    }

    // ==================== 新批默认关闭 + 零请求 ====================

    @Test
    fun newBatchAfterRebuildSendsZeroRequests() = runBlocking {
        val transport = CountingTransport()
        val store = keyStore()
        val auth = IntentAssistAuthorization()

        // 用户在旧批开启 → 有请求
        auth.onUserToggle(true)
        val oldAssist = DeepSeekTextAssist(store, auth.effective, transport)
        oldAssist.classify(snippet())
        assertEquals(1, transport.payloads.size)

        // 提交 / 长期保留 / 范围变化 → 重建
        auth.onRebuild()
        oldAssist.invalidate()

        // 新批：默认关闭 → 即使 Key 还在，也零请求
        val newAssist = DeepSeekTextAssist(store, auth.effective, transport)
        assertFalse("新批默认关闭", newAssist.isEnabled)
        newAssist.classify(snippet())
        assertEquals("新批不得发出任何请求", 1, transport.payloads.size)
    }

    @Test
    fun newBatchAnalysisReportsAssistOffAndMakesNoRequests() = runBlocking {
        val transport = CountingTransport()
        val store = keyStore()
        val auth = IntentAssistAuthorization()
        auth.onUserToggle(true)
        auth.onRebuild()   // 模拟「提交后重建」

        // 新批按本地模式分析：连辅助实例都不创建
        val assist = if (auth.effective) DeepSeekTextAssist(store, true, transport) else null
        assertTrue("重建后不应创建辅助实例", assist == null)
        assertEquals(0, transport.payloads.size)
    }

    // ==================== 迟到结果不得覆盖新批 ====================

    @Test
    fun lateResultFromOldBatchCannotOverwriteNewBatch() = runBlocking {
        val transport = CountingTransport(delayMs = 300)
        val store = keyStore()
        val generation = AtomicInteger(0)
        val assist = DeepSeekTextAssist(
            keyStore = store,
            userEnabledForBatch = true,
            transport = transport,
            sharedGeneration = generation
        )

        val request = async { assist.classify(snippet()) }
        delay(100)
        // 提交导致重建：上层推进代际
        generation.incrementAndGet()
        val suggestion = request.await()

        assertTrue("迟到结果必须标过期", suggestion.stale)
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
    }

    @Test
    fun staleSuggestionIsIgnoredByBuilder() = runBlocking {
        // 即使传输层正常返回，只要被标 stale，builder 也不得采纳
        val stale = AssistSuggestion(
            recommendation = IntentRecommendation.KEEP,
            reason = "旧批次结论",
            stale = true
        )
        val assist = object : TextAssist {
            override val isEnabled = true
            override suspend fun classify(snippet: IntentTextSanitizer.SanitizedSnippet) = stale
        }
        val now = 1_796_000_000_000L
        val image = com.tabula.v3.data.model.ImageFile(
            id = 1,
            uri = android.net.Uri.parse("content://media/external_primary/images/media/1"),
            displayName = "s.png",
            dateModified = now,
            size = 100,
            width = 720, height = 1280,
            bucketDisplayName = "Screenshots",
            volumeName = "external_primary",
            relativePath = "DCIM/Screenshots/",
            dateAdded = now - 10L * 86_400_000L
        )
        val classified = IntentBatchBuilder.classifyAll(
            candidates = listOf(
                IntentCandidateInput(image, image.dateAdded, listOf("某视频平台", "复制链接"), 0.9f)
            ),
            nowMillis = now
        )
        val refined = IntentBatchBuilder.refineWithAssist(classified, assist)
        assertEquals(
            "过期建议不得被采纳",
            IntentRecommendation.UNCERTAIN,
            refined.first().verdict.recommendation
        )
    }

    // ==================== 与 Analyzer 的代际联动 ====================

    @Test
    fun analyzerDiscardsBatchWhenGenerationAdvances() = runBlocking {
        val cachePrefs = org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("auth_generation_test", android.content.Context.MODE_PRIVATE)
        val cache = IntentVerdictCache(cachePrefs, maxEntries = 20).apply { clear() }
        val generation = AtomicInteger(0)
        val recognizer = object : TextRecognizer {
            override val isAvailable = true
            override val engineId = "fake-auth-v1"
            override suspend fun recognize(bitmap: android.graphics.Bitmap): OcrResult {
                generation.incrementAndGet()   // 分析中途「重建」
                return OcrResult.Success(listOf("满199减30", "活动已于 2026-11-10 23:59 结束"), 0.9f)
            }
            override fun close() = Unit
        }
        val analyzer = IntentBatchAnalyzer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            recognizer = recognizer,
            cache = cache,
            batchGeneration = { generation.get() }
        )
        val image = com.tabula.v3.data.model.ImageFile(
            id = 2,
            uri = android.net.Uri.parse("content://media/external_primary/images/media/2"),
            displayName = "s2.png",
            dateModified = 1_796_000_000_000L,
            size = 100,
            width = 720, height = 1280,
            bucketDisplayName = "Screenshots",
            volumeName = "external_primary",
            relativePath = "DCIM/Screenshots/",
            dateAdded = 1_796_000_000_000L - 10L * 86_400_000L
        )
        val analysis = analyzer.analyze(
            images = listOf(image),
            batchLimit = 5,
            nowMillis = 1_796_000_000_000L
        )
        assertTrue("代际推进后整批结果作废", analysis.stale)
        assertTrue(analysis.batch.ordered.isEmpty())
    }
}
