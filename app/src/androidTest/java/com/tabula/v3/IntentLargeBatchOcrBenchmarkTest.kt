package com.tabula.v3

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.intent.IntentBatchAnalyzer
import com.tabula.v3.data.intent.IntentBatchTimings
import com.tabula.v3.data.intent.IntentVerdictCache
import com.tabula.v3.data.intent.TesseractTextRecognizer
import com.tabula.v3.data.intent.allCandidates
import com.tabula.v3.data.model.ImageFile
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #105 大批量 OCR 云端性能基线（96 张固定夹具，文字辅助关闭）。
 *
 * 与 `IntentSampleOcrDeviceTest.measuresColdAndWarmBatchWithRealOcr`（12 张，#110 合入）的分工：
 * - 那条是功能级回归的小夹具；本条把候选集放大到 96 张（50 张 720×1280 基础样图 +
 *   34 张 1440×2560 高分辨率单张 + 12 张高分辨率重复内容张），量**冷缓存首次扫描**
 *   与**同批重开**的阶段耗时（模型初始化 / 解码 / 原生识别 / 缓存写入）与计数口径
 *   （OCR 次数、缓存命中、缓存条目数、截断）。
 * - 夹具清单与数据集指纹来自 `intent_samples/perf_large_manifest.json`
 *   （seed=20261128，确定性生成，sha256 逐张记录）。样图与金标准同源，
 *   保护类夹具（mustKeepProtected）混入其中，继续充当安全线。
 *
 * 硬断言：
 * 1. 冷轮：每张恰一次 OCR 尝试、零缓存命中；
 * 2. 暖轮：零 OCR 尝试、全部命中缓存，且判定与冷轮逐张一致；
 * 3. 保护类夹具在任何轮次都不得进入推荐列表；
 * 4. 辅助请求数为 0（文字辅助关闭）。
 *
 * 输出：每轮一条 `[ocr-large-benchmark]` JSON 行（解析脚本按行取）。
 */
@RunWith(AndroidJUnit4::class)
class IntentLargeBatchOcrBenchmarkTest {

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    /** 样图在 test APK 的 assets 里（`androidTest.assets.srcDirs = src/test/resources`）。 */
    private val testContext = ins.context
    private val cacheDir by lazy { File(context.cacheDir, "intent-large-batch").apply { mkdirs() } }
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

    private data class Fixture(
        val file: String,
        val kind: String,
        val width: Int,
        val height: Int,
        val savedDaysAgo: Int,
        val mustKeepProtected: Boolean
    )

    private fun loadManifest(): List<Fixture> {
        val raw = testContext.assets.open("intent_samples/perf_large_manifest.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(raw)
        val files = root.getJSONArray("files")
        return (0 until files.length()).map { i ->
            val o = files.getJSONObject(i)
            Fixture(
                file = o.getString("file"),
                kind = o.getString("kind"),
                width = o.getInt("width"),
                height = o.getInt("height"),
                savedDaysAgo = o.getInt("savedDaysAgo"),
                mustKeepProtected = o.getBoolean("mustKeepProtected")
            )
        }
    }

    /** 把 assets 里的夹具插入 MediaStore；名内带序号保证 DATE_ADDED 顺序稳定。 */
    private fun stageFixture(fixture: Fixture, index: Int): ImageFile {
        val file = File(cacheDir, fixture.file)
        testContext.assets.open("intent_samples/${fixture.file}").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fixture.file)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Screenshots/")
            put(
                MediaStore.Images.Media.DATE_ADDED,
                (System.currentTimeMillis() / 1000) - fixture.savedDaysAgo * 86_400L + index
            )
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法插入 ${fixture.file}")
        context.contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        }
        inserted += uri
        val timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        val reference = Calendar.getInstance(timeZone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 28, 12, 0)
        }.timeInMillis
        return ImageFile(
            id = ContentUris.parseId(uri),
            uri = uri,
            displayName = fixture.file,
            dateModified = reference,
            size = file.length(),
            width = bounds.outWidth,
            height = bounds.outHeight,
            bucketDisplayName = "Screenshots",
            volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
            relativePath = "DCIM/Screenshots/",
            dateAdded = reference - fixture.savedDaysAgo * 86_400_000L + index * 1_000L
        )
    }

    @Test
    fun measuresColdAndWarmOnLargeBatch() = runBlocking {
        val manifest = loadManifest()
        assertEquals("大批量夹具应为 96 张", 96, manifest.size)
        val images = manifest.mapIndexed { index, fixture -> stageFixture(fixture, index) }
        val protectedNames = manifest.filter { it.mustKeepProtected }.map { it.file }.toSet()
        val cache = IntentVerdictCache(
            context.getSharedPreferences("ocr_large_batch_measurement", Context.MODE_PRIVATE)
        )
        val timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        val reference = Calendar.getInstance(timeZone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 28, 12, 0)
        }.timeInMillis

        println("[ocr-large-benchmark] " + JSONObject()
            .put("phase", "setup")
            .put("candidateCount", images.size)
            .put("protectedCount", protectedNames.size)
            .put("cacheCapacity", IntentVerdictCache.MAX_ENTRIES)
            .put("batchLimit", 10)
            .put("textAssist", false)
            .put("engineId", recognizer.engineId)
            .put("sizes", JSONObject().put("720x1280", 50).put("1440x2560", 46))
            .put("fixtures", JSONArray(images.take(3).map { image ->
                JSONObject().put("name", image.displayName)
                    .put("width", image.width).put("height", image.height)
            }))
        )

        try {
            repeat(3) { round ->
                cache.clear()
                recognizer.close()
                recognizer = TesseractTextRecognizer(context)
                val analyzer = IntentBatchAnalyzer(context, recognizer, cache)
                var coldVerdicts: Map<String, List<Any?>> = emptyMap()
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
                        assertEquals("冷轮必须逐张识别", images.size, result.ocrAttemptCount)
                        assertEquals("冷轮不得命中缓存", 0, result.usedCacheCount)
                        coldVerdicts = verdicts
                    } else {
                        assertEquals("暖轮必须零 OCR", 0, result.ocrAttemptCount)
                        assertEquals("暖轮必须全量命中", images.size, result.usedCacheCount)
                        assertEquals("暖轮判定必须与冷轮一致", coldVerdicts, verdicts)
                    }
                    val recommended = result.batch.recommended.map { it.image.displayName }.toSet()
                    val protectedLeaked = protectedNames.intersect(recommended)
                    assertTrue(
                        "保护类夹具不得进入推荐：$protectedLeaked",
                        protectedLeaked.isEmpty()
                    )
                    val hiResDecoded = phases.decodedSizes.count { (w, _) -> w > 720 }
                    println("[ocr-large-benchmark] " + JSONObject()
                        .put("phase", "measure")
                        .put("round", round + 1).put("pass", pass)
                        .put("initializationMs", phases.initializationNanos / 1_000_000.0)
                        .put("decodeMs", phases.decodeNanos / 1_000_000.0)
                        .put("nativeRecognitionMs", phases.nativeRecognitionNanos / 1_000_000.0)
                        .put("cacheWriteMs", phases.cacheWriteNanos / 1_000_000.0)
                        .put("analysisReadyMs", readyNanos / 1_000_000.0)
                        .put("ocrAttempts", result.ocrAttemptCount)
                        .put("cacheHits", result.usedCacheCount)
                        .put("cacheEntries", cache.size())
                        .put("decodedCount", phases.decodedSizes.size)
                        .put("hiResDecodedCount", hiResDecoded)
                        .put("decodedSizesSample", JSONArray(phases.decodedSizes.take(4).map { (w, h) ->
                            JSONArray(listOf(w, h))
                        }))
                        .put("recommendedCount", result.batch.recommended.size)
                        .put("lowerPriorityCount", result.batch.lowerPriority.size)
                        .put("uncertainCount", result.batch.uncertain.size)
                        .put("excludedCount", result.batch.excluded.size)
                        .put("batchLimit", result.batch.batchLimit)
                    )
                }
            }
        } finally {
            cache.clear()
        }
    }

    /** 超过 400 条缓存容量的重开测量：410 张夹具（96 张复用 + 314 张复制体）。 */
    @Test
    fun measuresReopenBeyondCacheCapacity() = runBlocking {
        val manifest = loadManifest()
        assertEquals(96, manifest.size)
        val images = mutableListOf<ImageFile>()
        manifest.mapIndexed { index, fixture -> stageFixture(fixture, index) }.let { images.addAll(it) }
        // 314 张复制体：与已有夹具内容相同（共享文件字节），但 DISPLAY_NAME 唯一 →
        // 各自独立的 MediaStore 行与身份键（卷|ID 不同），指纹里 id 不同 → 缓存键不同。
        val timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        val reference = Calendar.getInstance(timeZone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 28, 12, 0)
        }.timeInMillis
        repeat(314) { copy ->
            val source = manifest[copy % manifest.size]
            val name = String.format(Locale.US, "capacity-%04d-%s", copy, source.file)
            val file = File(cacheDir, name)
            testContext.assets.open("intent_samples/${source.file}").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Screenshots/")
                put(
                    MediaStore.Images.Media.DATE_ADDED,
                    (System.currentTimeMillis() / 1000) - source.savedDaysAgo * 86_400L + copy
                )
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法插入 $name")
            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
            inserted += uri
            images += ImageFile(
                id = ContentUris.parseId(uri),
                uri = uri,
                displayName = name,
                dateModified = reference,
                size = file.length(),
                width = bounds.outWidth,
                height = bounds.outHeight,
                bucketDisplayName = "Screenshots",
                volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
                relativePath = "DCIM/Screenshots/",
                dateAdded = reference - source.savedDaysAgo * 86_400_000L + copy * 1_000L
            )
        }
        assertEquals(410, images.size)

        val cache = IntentVerdictCache(
            context.getSharedPreferences("ocr_large_capacity_measurement", Context.MODE_PRIVATE)
        )
        try {
            // 冷轮：410 张全部识别；缓存按写入顺序淘汰，最终条目数 = 400。
            recognizer.close()
            recognizer = TesseractTextRecognizer(context)
            var analyzer = IntentBatchAnalyzer(context, recognizer, cache)
            var timings: IntentBatchTimings? = null
            val coldStart = System.nanoTime()
            val cold = analyzer.analyze(
                images = images, batchLimit = 10, nowMillis = reference,
                timeZone = timeZone, allowTextAssist = false, onTimings = { timings = it }
            )
            val coldReadyMs = (System.nanoTime() - coldStart) / 1_000_000.0
            val coldTimings = checkNotNull(timings)
            assertEquals(410, cold.ocrAttemptCount)
            assertEquals(0, cold.usedCacheCount)
            assertEquals("超出容量后缓存必须封顶 400 条", 400, cache.size())

            // 暖轮：96 张原夹具中，在淘汰中幸存的那部分命中；被淘汰的重识别。
            timings = null
            val warmStart = System.nanoTime()
            val warm = analyzer.analyze(
                images = images, batchLimit = 10, nowMillis = reference,
                timeZone = timeZone, allowTextAssist = false, onTimings = { timings = it }
            )
            val warmReadyMs = (System.nanoTime() - warmStart) / 1_000_000.0
            val warmTimings = checkNotNull(timings)
            assertEquals(410, warm.ocrAttemptCount + warm.usedCacheCount)
            assertEquals("暖轮后缓存仍为 400 条", 400, cache.size())
            val retainedOriginal = images.size - warm.ocrAttemptCount
            println("[ocr-large-benchmark] " + JSONObject()
                .put("phase", "capacity")
                .put("candidateCount", images.size)
                .put("cacheCapacity", IntentVerdictCache.MAX_ENTRIES)
                .put("coldReadyMs", coldReadyMs)
                .put("coldDecodeMs", coldTimings.decodeNanos / 1_000_000.0)
                .put("coldNativeMs", coldTimings.nativeRecognitionNanos / 1_000_000.0)
                .put("coldCacheWriteMs", coldTimings.cacheWriteNanos / 1_000_000.0)
                .put("coldOcrAttempts", cold.ocrAttemptCount)
                .put("coldCacheEntries", cache.size())
                .put("warmReadyMs", warmReadyMs)
                .put("warmDecodeMs", warmTimings.decodeNanos / 1_000_000.0)
                .put("warmNativeMs", warmTimings.nativeRecognitionNanos / 1_000_000.0)
                .put("warmOcrAttempts", warm.ocrAttemptCount)
                .put("warmCacheHits", warm.usedCacheCount)
                .put("retainedOriginals", retainedOriginal)
                .put("evictedCount", 410 - 400)
            )
        } finally {
            cache.clear()
        }
    }
}
