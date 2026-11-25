package com.tabula.v3.data.intent

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import java.io.File
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 实验性开关的语义单测（#94 第 2 轮审计，第 3 条）。
 *
 * 审计要求的每一条都对应一个用例：
 * - 默认关闭 → [noKeyMeansZeroRequests] / [disabledMeansZeroRequests]
 * - 关闭后停止后续请求 → [closingStopsSubsequentRequests]
 * - 在途结果作废 → [inFlightResultIsDiscardedAfterInvalidate]
 * - 换批后旧结果不覆盖新批次 → [generationChangeMarksAnalysisStale]
 * - 无 Key/断网/超时/被拒/无效回退本地 → [failuresFallBackToLocal]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentAssistSwitchTest {

    private lateinit var keyFile: File
    private lateinit var keyStore: IntentAssistKeyStore
    private val now = 1_796_000_000_000L
    private val utc = TimeZone.getTimeZone("UTC")

    /** 记录调用次数与请求体的假传输层。 */
    private class CountingTransport(
        private val respond: (String) -> String = { """{"choices":[{"message":{"content":"LOWER"}}]}""" },
        private val delayMs: Long = 0
    ) : AssistTransport {
        val payloads = mutableListOf<String>()
        var calls = 0
        override fun post(url: String, body: String, authHeader: String, timeoutMillis: Int): String {
            calls++
            payloads += body
            if (delayMs > 0) Thread.sleep(delayMs)
            return respond(body)
        }
    }

    @Before
    fun setUp() {
        keyFile = File.createTempFile("assist-key", ".tmp").apply { deleteOnExit() }
        keyStore = IntentAssistKeyStore(keyFile)
        keyStore.delete()
    }

    private fun assist(
        enabled: Boolean,
        transport: AssistTransport,
        generation: AtomicInteger = AtomicInteger(0)
    ) = DeepSeekTextAssist(
        keyStore = keyStore,
        userEnabledForBatch = enabled,
        transport = transport,
        sharedGeneration = generation
    )

    private fun snippet() = IntentTextSanitizer.sanitizeForAssistant(
        listOf("某视频平台", "复制链接", "扫码观看")
    )

    // ==================== 默认关闭 / 零请求 ====================

    @Test
    fun noKeyMeansZeroRequests() = runBlocking {
        val transport = CountingTransport()
        val assist = assist(enabled = true, transport = transport)
        assertFalse("没有 Key 时 isEnabled 必须为 false", assist.isEnabled)
        assist.classify(snippet())
        assertEquals("没有 Key 时不得发请求", 0, transport.calls)
        assertEquals(1, assist.skippedRequestCount)
    }

    @Test
    fun disabledMeansZeroRequests() = runBlocking {
        keyStore.write("sk-test-0001")
        val transport = CountingTransport()
        val assist = assist(enabled = false, transport = transport)
        assertFalse(assist.isEnabled)
        assist.classify(snippet())
        assertEquals("开关关闭时不得发请求", 0, transport.calls)
    }

    @Test
    fun enabledWithKeySendsOneRequest() = runBlocking {
        keyStore.write("sk-test-0002")
        val transport = CountingTransport()
        val assist = assist(enabled = true, transport = transport)
        assertTrue(assist.isEnabled)
        assist.classify(snippet())
        assertEquals(1, transport.calls)
        // 请求体里不能有图像/路径
        val payload = transport.payloads.first()
        listOf("base64", "content://", "/storage/", "DCIM", ".png", "image_url", "file://")
            .forEach { assertFalse("请求体不得包含 $it", payload.contains(it, ignoreCase = true)) }
    }

    // ==================== 关闭即停 ====================

    @Test
    fun closingStopsSubsequentRequests() = runBlocking {
        keyStore.write("sk-test-0003")
        val transport = CountingTransport()
        val assist = assist(enabled = true, transport = transport)
        assist.classify(snippet())
        assertEquals(1, transport.calls)

        assist.invalidate()
        assertFalse("关闭后 isEnabled 立即为 false", assist.isEnabled)
        assist.classify(snippet())
        assertEquals("关闭后不得再发请求", 1, transport.calls)
    }

    @Test
    fun inFlightResultIsDiscardedAfterInvalidate() = runBlocking {
        keyStore.write("sk-test-0004")
        val transport = CountingTransport(delayMs = 250)
        val generation = AtomicInteger(0)
        val assist = assist(enabled = true, transport = transport, generation = generation)

        val request = async { assist.classify(snippet()) }
        // 等到请求真的发出去，再模拟「用户在等待期间关掉了开关 / 换了批」
        delay(100)
        assist.invalidate()
        val suggestion = request.await()

        assertEquals("在途请求必须被标成过期", true, suggestion.stale)
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
    }

    @Test
    fun generationBumpByUpperLayerAlsoInvalidates() = runBlocking {
        keyStore.write("sk-test-0005")
        val transport = CountingTransport(delayMs = 200)
        val generation = AtomicInteger(0)
        val assist = assist(enabled = true, transport = transport, generation = generation)

        val request = async { assist.classify(snippet()) }
        delay(80)
        // 上层换批：只 +1，不碰 assist 自身
        generation.incrementAndGet()
        val suggestion = request.await()
        assertTrue("上层换批也要让在途结果作废", suggestion.stale)
    }

    // ==================== 失败回退 ====================

    @Test
    fun failuresFallBackToLocal() = runBlocking {
        keyStore.write("sk-test-0006")
        val cases = listOf(
            "network" to { throw java.io.IOException("offline") },
            "denied" to { throw java.io.IOException("http 401") },
            "garbage" to { "not json at all" },
            "empty" to { """{"choices":[]}""" }
        )
        cases.forEach { (label, responder) ->
            val transport = object : AssistTransport {
                override fun post(url: String, body: String, authHeader: String, timeoutMillis: Int): String =
                    @Suppress("UNCHECKED_CAST")
                    (responder as () -> String)()
            }
            val suggestion = assist(enabled = true, transport = transport).classify(snippet())
            assertEquals(
                "「$label」必须回退为空建议",
                IntentRecommendation.UNCERTAIN,
                suggestion.recommendation
            )
            assertFalse(suggestion.stale)
        }
    }

    @Test
    fun modelCannotEmitExpiredOrCleanupAction() = runBlocking {
        keyStore.write("sk-test-0007")
        // 模型就算回「删除这些」也只被解析成空建议（只认 KEEP / LOWER）
        val transport = CountingTransport(respond = { """{"choices":[{"message":{"content":"DELETE all of them now"}}]}""" })
        val suggestion = assist(enabled = true, transport = transport).classify(snippet())
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
        // AssistSuggestion 里没有 expiry 字段，类型层面就无法「认定过期」
        assertTrue(suggestion.category != IntentCategory.EXPIRED_COUPON)
    }

    // ==================== analyzer 层的代际 ====================

    private fun photo(id: Long) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/external_primary/images/media/$id"),
        displayName = "s$id.png",
        dateModified = now,
        size = 1000L + id,
        width = 720, height = 1280,
        bucketDisplayName = "Screenshots",
        volumeName = "external_primary",
        relativePath = "DCIM/Screenshots/",
        dateAdded = now - 10L * 86_400_000L
    )

    @Test
    fun generationChangeMarksAnalysisStale() = runBlocking {
        val cachePrefs = org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("intent_switch_test", android.content.Context.MODE_PRIVATE)
        val cache = IntentVerdictCache(cachePrefs, maxEntries = 50)
        cache.clear()

        val generation = AtomicInteger(0)
        // 在**识别过程中**换批（更接近真实：用户看着进度条换了来源），
        // 而不是在 analyze 之前——那样只是测到「一开始就过期」，没测到「中途过期」。
        val recognizer = object : TextRecognizer {
            override val isAvailable = true
            override val engineId = "fake-v1"
            override suspend fun recognize(bitmap: android.graphics.Bitmap): OcrResult {
                generation.incrementAndGet()
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

        val analysis = analyzer.analyze(
            images = listOf(photo(1), photo(2)), batchLimit = 5, nowMillis = now, timeZone = utc
        )
        assertTrue("代际变化后结果必须标记为过期", analysis.stale)
        assertTrue(analysis.batch.ordered.isEmpty())
    }

    @Test
    fun freshAnalysisIsNotStale() = runBlocking {
        val cachePrefs = org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("intent_switch_test2", android.content.Context.MODE_PRIVATE)
        val cache = IntentVerdictCache(cachePrefs, maxEntries = 50)
        cache.clear()
        val recognizer = object : TextRecognizer {
            override val isAvailable = true
            override val engineId = "fake-v1"
            override suspend fun recognize(bitmap: android.graphics.Bitmap) = OcrResult.Empty
            override fun close() = Unit
        }
        val analyzer = IntentBatchAnalyzer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            recognizer = recognizer,
            cache = cache,
            batchGeneration = { 7 }
        )
        val analysis = analyzer.analyze(
            images = listOf(photo(3)), batchLimit = 5, nowMillis = now, timeZone = utc
        )
        assertFalse(analysis.stale)
    }
}
