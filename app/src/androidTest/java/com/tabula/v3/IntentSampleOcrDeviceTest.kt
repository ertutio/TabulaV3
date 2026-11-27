package com.tabula.v3

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.intent.IntentCategory
import com.tabula.v3.data.intent.IntentBatchAnalyzer
import com.tabula.v3.data.intent.IntentBatchTimings
import com.tabula.v3.data.intent.IntentDateExtractor
import com.tabula.v3.data.intent.IntentRecommendation
import com.tabula.v3.data.intent.IntentRules
import com.tabula.v3.data.intent.IntentSignal
import com.tabula.v3.data.intent.IntentTextSanitizer
import com.tabula.v3.data.intent.IntentVerdictCache
import com.tabula.v3.data.intent.OcrResult
import com.tabula.v3.data.intent.TesseractTextRecognizer
import com.tabula.v3.data.intent.allCandidates
import com.tabula.v3.data.model.ImageFile
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 合成样图上的**真实 OCR 端到端**验收（#94）。
 *
 * 与 `IntentSampleGoldenTest` 的分工：
 * - Golden 测试用金标准文字喂规则，量「规则与排序」的上限；
 * - 这一组把样图 PNG 真解码 + 真跑 Tesseract + 真跑规则，量**端到端**表现，
 *   并逐张打印不一致项与 OCR 原文。两组的差就是 OCR 的损失。
 *
 * 硬断言（其余是记录）：
 * 1. 保护类样图在端到端链路上也必须被排除（OCR 读错也不许推荐清理订单/票券）；
 * 2. 引擎在设备上确实可用（模型解压 + init 成功）；
 * 3. 发出去的片段里没有链接正文、没有原始验证码。
 *
 * 跑法（模拟器/真机）：
 * `adb shell am instrument -w -r -e class com.tabula.v3.IntentSampleOcrDeviceTest <pkg>.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class IntentSampleOcrDeviceTest {

    private val ins = InstrumentationRegistry.getInstrumentation()

    /** MediaStore 与缓存目录走被测 App（target）的上下文。 */
    private val context = ins.targetContext

    /**
     * 样图放在 **test APK 的 assets** 里（`androidTest.assets.srcDirs = src/test/resources`），
     * 因此读 assets 必须用 test context；用 targetContext 会 FileNotFoundException。
     */
    private val testContext = ins.context

    private val cacheDir by lazy { File(context.cacheDir, "intent-samples").apply { mkdirs() } }
    private val inserted = mutableListOf<Uri>()

    private lateinit var recognizer: TesseractTextRecognizer

    @Before
    fun setUp() {
        recognizer = TesseractTextRecognizer(context)
    }

    @After
    fun tearDown() {
        recognizer.close()
        inserted.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        inserted.clear()
    }

    /** 把 assets 里的样图插入 MediaStore，模拟一张真实截图。 */
    private fun stageSample(name: String, daysAgo: Int): Uri {
        val file = File(cacheDir, name)
        // **每次都覆盖**：早期版本只在文件不存在时复制，结果样图重新生成后
        // 设备测试仍读到上一次缓存的旧图，把「已修好的难例」测成「还能读出来」。
        testContext.assets.open("intent_samples/$name").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Screenshots/")
            put(
                MediaStore.Images.Media.DATE_ADDED,
                (System.currentTimeMillis() / 1000) - daysAgo * 86_400L
            )
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法插入 $name")
        context.contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        }
        inserted += uri
        return uri
    }

    private data class GoldenSample(
        val file: String,
        val category: String,
        val expectation: String,
        val mustKeepProtected: Boolean,
        val savedDaysAgo: Int,
        val sensitiveTokens: List<String>
    )

    private fun loadGolden(): List<GoldenSample> {
        val raw = testContext.assets.open("intent_samples/golden.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONObject(raw).getJSONArray("samples")
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val tokens = o.optJSONArray("sensitive_tokens")?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            }.orEmpty()
            GoldenSample(
                file = o.getString("file"),
                category = o.getString("category"),
                expectation = o.getString("expectation"),
                mustKeepProtected = o.getBoolean("must_keep_protected"),
                savedDaysAgo = o.optInt("screenshot_saved_days_ago", 10),
                sensitiveTokens = tokens
            )
        }
    }

    @Test
    fun measuresColdAndWarmBatchWithRealOcr() = runBlocking {
        val fixtureNames = setOf(
            "coupon-expired-01.png", "coupon-expired-02.png", "coupon-expired-03.png",
            "coupon-expired-04.png", "event-signup-01.png", "hard-blurry-01.png",
            "hard-mixed-01.png", "hard-nowords-01.png", "news-flash-old-01.png",
            "protect-invoice-01.png", "protect-order-01.png", "protect-ticket-01.png"
        )
        val timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        val reference = Calendar.getInstance(timeZone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 28, 12, 0)
        }.timeInMillis
        val golden = loadGolden().filter { it.file in fixtureNames }
        assertEquals(fixtureNames.size, golden.size)
        val images = golden.map { sample ->
            val uri = stageSample(sample.file, sample.savedDaysAgo)
            val file = File(cacheDir, sample.file)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            ImageFile(
                id = ContentUris.parseId(uri),
                uri = uri,
                displayName = sample.file,
                dateModified = reference,
                size = file.length(),
                width = bounds.outWidth,
                height = bounds.outHeight,
                bucketDisplayName = "Screenshots",
                volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
                relativePath = "DCIM/Screenshots/",
                dateAdded = reference - sample.savedDaysAgo * 86_400_000L
            )
        }
        val cache = IntentVerdictCache(context.getSharedPreferences("ocr_batch_measurement", Context.MODE_PRIVATE))
        println("[ocr-benchmark] " + JSONObject()
            .put("fixtures", JSONArray(images.map { image ->
                JSONObject().put("name", image.displayName).put("width", image.width)
                    .put("height", image.height).put("bytes", image.size)
            }))
            .put("cacheCapacity", IntentVerdictCache.MAX_ENTRIES)
            .put("candidateCount", images.size)
            .put("exceedsCapacity", images.size > IntentVerdictCache.MAX_ENTRIES)
            .put("textAssist", false))

        try {
            repeat(3) { round ->
                cache.clear()
                recognizer.close()
                recognizer = TesseractTextRecognizer(context)
                val analyzer = IntentBatchAnalyzer(context, recognizer, cache)
                var coldVerdicts = emptyMap<String, List<Any?>>()
                for (pass in listOf("cold", "warm")) {
                    var timings: IntentBatchTimings? = null
                    val start = System.nanoTime()
                    val result = analyzer.analyze(
                        images = images,
                        batchLimit = 10,
                        nowMillis = reference,
                        timeZone = timeZone,
                        allowTextAssist = false,
                        onTimings = { timings = it }
                    )
                    val readyNanos = System.nanoTime() - start
                    val phases = checkNotNull(timings)
                    assertTrue(result.engineAvailable)
                    assertEquals(0, result.assistRequestCount)
                    val verdicts = result.batch.allCandidates().associate { candidate ->
                        val verdict = candidate.verdict
                        candidate.image.displayName to listOf(
                            verdict.category.name, verdict.recommendation.name,
                            verdict.expiry.name, verdict.deadlineMillis
                        )
                    }
                    if (pass == "cold") {
                        assertEquals(images.size, result.ocrAttemptCount)
                        assertEquals(0, result.usedCacheCount)
                        coldVerdicts = verdicts
                    } else {
                        assertTrue(result.usedCacheCount > 0)
                        assertEquals(coldVerdicts, verdicts)
                    }
                    val recommended = result.batch.recommended.map { it.image.displayName }.toSet()
                    assertTrue(golden.filter { it.mustKeepProtected }.none { it.file in recommended })
                    println("[ocr-benchmark] " + JSONObject()
                        .put("round", round + 1).put("pass", pass)
                        .put("initializationNanos", phases.initializationNanos)
                        .put("decodeNanos", phases.decodeNanos)
                        .put("nativeRecognitionNanos", phases.nativeRecognitionNanos)
                        .put("cacheWriteNanos", phases.cacheWriteNanos)
                        .put("analysisReadyNanos", readyNanos)
                        .put("ocrAttempts", result.ocrAttemptCount)
                        .put("cacheHits", result.usedCacheCount).put("cacheEntries", cache.size())
                        .put("decodedSizes", JSONArray(phases.decodedSizes.map { (width, height) ->
                            JSONArray(listOf(width, height))
                        }))
                        .put("verdicts", JSONObject(verdicts.mapValues { (_, verdict) -> JSONArray(verdict) })))
                }
            }
        } finally {
            cache.clear()
        }
    }

    @Test
    fun engineIsAvailableOnThisDevice() {
        assertTrue("Tesseract 在设备上应可用（模型解压 + init）", recognizer.isAvailable)
        assertTrue(recognizer.engineId.startsWith("tesseract4android"))
    }

    /**
     * 端到端逐张对账，并按类别汇总。
     *
     * 输出一份可直接抄进 PR 的报告：每类的样例数、命中数、误判明细（含 OCR 原文摘要），
     * 以及 OCR 耗时。**不**因为个别不一致就让测试失败——样图本身就是难例，
     * 硬断言只保留「不许推荐保护类」这条安全线。
     */
    @Test
    fun endToEndAccuracyOnSyntheticSamples() = runBlocking {
        val golden = loadGolden()
        assertEquals("样图应为 50 张", 50, golden.size)

        var totalOcrMs = 0L
        var readSomething = 0
        var protectedRecommended = 0
        var keepRecommended = 0

        data class Row(
            val sample: GoldenSample,
            val verdictCategory: IntentCategory,
            val verdictRecommendation: IntentRecommendation,
            val ocrLines: Int,
            val confidence: Float,
            val ms: Long,
            val text: String,
            val signals: Set<IntentSignal>,
            val reason: String
        )
        val rows = mutableListOf<Row>()

        golden.forEach { sample ->
            val uri = stageSample(sample.file, sample.savedDaysAgo)
            val bitmap = TesseractTextRecognizer.decodeForOcr(context, uri)
                ?: error("样图解不出来：${sample.file}")
            val started = System.currentTimeMillis()
            val result = recognizer.recognize(bitmap)
            val cost = System.currentTimeMillis() - started
            totalOcrMs += cost
            bitmap.recycle()

            val lines = (result as? OcrResult.Success)?.lines.orEmpty()
            val confidence = (result as? OcrResult.Success)?.meanConfidence ?: 0f
            if (lines.isNotEmpty()) readSomething++
            val now = System.currentTimeMillis()
            val verdict = IntentRules.classify(
                lines = lines,
                savedAtMillis = now - sample.savedDaysAgo * 86_400_000L,
                nowMillis = now,
                ocrConfidence = confidence,
                timeZone = TimeZone.getDefault()
            )
            if (sample.mustKeepProtected && verdict.recommendation != IntentRecommendation.KEEP) {
                protectedRecommended++
            }
            if (sample.expectation == "keep" &&
                verdict.recommendation == IntentRecommendation.RECOMMEND
            ) {
                keepRecommended++
            }
            rows += Row(
                sample, verdict.category, verdict.recommendation,
                lines.size, confidence, cost, lines.joinToString(" | "),
                verdict.signals, verdict.reason
            )
        }

        // ---------- 安全线（硬断言） ----------
        assertEquals("端到端也不得推荐保护类样图", 0, protectedRecommended)
        assertEquals("端到端也不得把应留图排进推荐", 0, keepRecommended)

        // ---------- 逐类统计 ----------
        fun cleanable(row: Row) = row.sample.expectation == "recommend" ||
            row.sample.category == "old_price"
        val byCategory = rows.groupBy { it.sample.category }.toSortedMap()
        byCategory.forEach { (category, group) ->
            val expectedClean = group.count { cleanable(it) }
            val gotRecommended = group.count {
                it.verdictRecommendation == IntentRecommendation.RECOMMEND
            }
            val gotLower = group.count {
                it.verdictRecommendation == IntentRecommendation.LOWER_PRIORITY
            }
            val gotUncertain = group.count {
                it.verdictRecommendation == IntentRecommendation.UNCERTAIN
            }
            val gotExcluded = group.count { it.verdictRecommendation == IntentRecommendation.KEEP }
            println(
                "[ocr-e2e] $category n=${group.size} 应清=$expectedClean " +
                    "推荐=$gotRecommended 低优先=$gotLower 不确定=$gotUncertain 排除=$gotExcluded"
            )
        }

        // ---------- 不一致明细（含 OCR 原文，便于判断是 OCR 错还是规则错） ----------
        val mismatches = rows.filter { row ->
            when (row.sample.expectation) {
                "recommend" -> row.verdictRecommendation != IntentRecommendation.RECOMMEND
                "keep" -> row.verdictRecommendation != IntentRecommendation.KEEP
                "uncertain" -> row.verdictRecommendation != IntentRecommendation.UNCERTAIN
                "lower_priority" -> row.verdictRecommendation == IntentRecommendation.RECOMMEND
                else -> false
            }
        }
        mismatches.forEach { row ->
            println(
                "[ocr-e2e] MISMATCH ${row.sample.file} 期望=${row.sample.expectation} " +
                    "实际=${row.verdictCategory}/${row.verdictRecommendation} " +
                    "signals=${row.signals} reason=${row.reason} " +
                    "行=${row.ocrLines} conf=${"%.2f".format(row.confidence)} ocr=${row.text}"
            )
        }

        val cleanRows = rows.filter { cleanable(it) }
        val recommendedRows = cleanRows.count {
            it.verdictRecommendation == IntentRecommendation.RECOMMEND
        }
        println(
            "[ocr-e2e] 汇总：样图 ${rows.size} 张，识别出文字 $readSomething 张，" +
                "应清 ${cleanRows.size} 张，实际推荐 $recommendedRows 张，" +
                "不一致 ${mismatches.size} 张"
        )
        println(
            "[ocr-e2e] OCR 总耗时 ${totalOcrMs}ms，平均 ${totalOcrMs / rows.size.coerceAtLeast(1)}ms/张"
        )
    }

    @Test
    fun unreadableSampleYieldsUncertainRatherThanRecommended() = runBlocking {
        val name = "hard-nowords-01.png"
        val uri = stageSample(name, daysAgo = 12)
        val bitmap = TesseractTextRecognizer.decodeForOcr(context, uri)!!
        val result = recognizer.recognize(bitmap)
        bitmap.recycle()
        val lines = (result as? OcrResult.Success)?.lines.orEmpty()
        val verdict = IntentRules.classify(
            lines = lines,
            savedAtMillis = System.currentTimeMillis() - 12L * 86_400_000L,
            nowMillis = System.currentTimeMillis(),
            ocrConfidence = (result as? OcrResult.Success)?.meanConfidence ?: 0f
        )
        assertTrue(
            "无字图不应被推荐清理（实际 ${verdict.category}/${verdict.recommendation}）",
            verdict.recommendation != IntentRecommendation.RECOMMEND
        )
    }

    @Test
    fun deadlineIsExtractedFromRealOcrText() = runBlocking {
        val name = "coupon-expired-01.png"
        val uri = stageSample(name, daysAgo = 9)
        val bitmap = TesseractTextRecognizer.decodeForOcr(context, uri)!!
        val result = recognizer.recognize(bitmap)
        bitmap.recycle()
        val text = (result as? OcrResult.Success)?.text.orEmpty()
        println("[ocr-device] $name 实际识别文本：$text")
        val date = IntentDateExtractor.findAbsoluteDate(text, 2026)
        assertTrue(
            "真实 OCR 应能读出图上写死的日期；当前文本：$text",
            date != null
        )
    }

    @Test
    fun sanitizedSnippetNeverCarriesLinksOrCodes() = runBlocking {
        val name = "share-expired-01.png"
        val uri = stageSample(name, daysAgo = 20)
        val bitmap = TesseractTextRecognizer.decodeForOcr(context, uri)!!
        val result = recognizer.recognize(bitmap)
        bitmap.recycle()
        val lines = (result as? OcrResult.Success)?.lines.orEmpty()
        val snippet = IntentTextSanitizer.sanitizeForAssistant(lines)
        snippet.lines.forEach { line ->
            assertFalse("外发片段不应带链接：$line", line.contains("http"))
            assertFalse("外发片段不应带域名：$line", line.contains("pan.example.com"))
            assertFalse("外发片段不应带提取码：$line", line.contains("AbCd1234"))
        }
        println("[ocr-e2e] 分享样例脱敏后片段：${snippet.lines}")
    }

    @Test
    fun protectionSignalsSurviveOcrOnOrderScreenshot() = runBlocking {
        val name = "protect-order-01.png"
        val uri = stageSample(name, daysAgo = 30)
        val bitmap = TesseractTextRecognizer.decodeForOcr(context, uri)!!
        val result = recognizer.recognize(bitmap)
        bitmap.recycle()
        val verdict = IntentRules.classify(
            lines = (result as? OcrResult.Success)?.lines.orEmpty(),
            savedAtMillis = System.currentTimeMillis() - 30L * 86_400_000L,
            nowMillis = System.currentTimeMillis(),
            ocrConfidence = (result as? OcrResult.Success)?.meanConfidence ?: 0f
        )
        assertEquals(IntentCategory.PROTECTED, verdict.category)
        assertTrue(verdict.signals.contains(IntentSignal.ORDER))
    }
}
