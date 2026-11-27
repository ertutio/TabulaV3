package com.tabula.v3.data.intent

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import com.tabula.v3.data.model.ImageFile
import java.io.File
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 缓存与 Key 存储的单测（#94）。
 *
 * 两条契约最关键：
 * 1. 缓存**只**存分类与脱敏理由（结构上就没有 OCR 原文的槽位）；
 * 2. 缓存按媒体内容 / 规则版本 / 引擎版本 / 辅助开关失效，且不跨这四者复用。
 *
 * Key 存储的契约：放在 noBackupFilesDir 的独立文件、不写日志、缺失或损坏只停用辅助。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentVerdictCacheTest {

    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var cache: IntentVerdictCache
    private val now = 1_796_000_000_000L
    private val utc = TimeZone.getTimeZone("UTC")
    private val expiredCoupon = OcrResult.Success(
        listOf("满199减30", "活动已于 2026-11-10 23:59 结束"), 0.9f
    )
    // A 16 × 16 RGB PNG. The recognizer is fake, but the analyzer uses its normal decoder.
    private val imageBytes = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAEElEQVR4nGNgGAWjYBTAAAADEAABPywr7AAAAABJRU5ErkJggg==",
        Base64.DEFAULT
    )

    private val verdict = IntentVerdict(
        category = IntentCategory.EXPIRED_COUPON,
        recommendation = IntentRecommendation.RECOMMEND,
        expiry = ExpiryState.EXPIRED,
        reason = "图上写着截止 2026-11-10",
        signals = setOf(IntentSignal.COUPON, IntentSignal.EXPIRY_DATE),
        ocrConfidence = 0.88f,
        deadlineMillis = 1_794_000_000_000L
    )

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("intent_cache_test", Context.MODE_PRIVATE)
        cache = IntentVerdictCache(prefs)
    }

    @Test
    fun writeThenReadReturnsTheSameVerdict() {
        cache.write("external_primary|1", verdict, "fp1", "engine-v1", "assist-off")
        val read = cache.read("external_primary|1", "fp1", "engine-v1", "assist-off")
        assertNotNull(read)
        assertEquals(IntentCategory.EXPIRED_COUPON, read!!.category)
        assertEquals(IntentRecommendation.RECOMMEND, read.recommendation)
        assertEquals(ExpiryState.EXPIRED, read.expiry)
        assertEquals("图上写着截止 2026-11-10", read.reason)
        assertEquals(setOf(IntentSignal.COUPON, IntentSignal.EXPIRY_DATE), read.signals)
    }

    @Test
    fun changedMediaContentInvalidates() {
        cache.write("external_primary|2", verdict, "fp1", "engine-v1", "assist-off")
        assertNull(cache.read("external_primary|2", "fp2", "engine-v1", "assist-off"))
    }

    @Test
    fun changedEngineInvalidates() {
        cache.write("external_primary|3", verdict, "fp1", "engine-v1", "assist-off")
        assertNull(cache.read("external_primary|3", "fp1", "engine-v2", "assist-off"))
    }

    @Test
    fun togglingAssistInvalidates() {
        cache.write("external_primary|4", verdict, "fp1", "engine-v1", "assist-off")
        assertNull(cache.read("external_primary|4", "fp1", "engine-v1", "assist-on"))
        assertNotNull(cache.read("external_primary|4", "fp1", "engine-v1", "assist-off"))
    }

    @Test
    fun invalidationRemovesEntry() {
        cache.write("external_primary|5", verdict, "fp1", "engine-v1", "assist-off")
        cache.invalidate("external_primary|5")
        assertNull(cache.read("external_primary|5", "fp1", "engine-v1", "assist-off"))
    }

    @Test
    fun cacheStoresNoOcrText() {
        cache.write("external_primary|6", verdict, "fp1", "engine-v1", "assist-off")
        val dumped = prefs.all.values.joinToString("\n")
        // 结构上没有原文槽位：这里再用「图上原文的敏感字面量」兜一层断言
        assertFalse(dumped.contains("验证码"))
        assertFalse(dumped.contains("123456"))
        assertFalse(dumped.contains("http"))
    }

    @Test
    fun ruleVersionBumpInvalidatesOldEntries() {
        cache.write("external_primary|7", verdict, "fp1", "engine-v1", "assist-off")
        // 直接写一条旧版本记录（模拟升级后读到旧的缓存）
        val raw = prefs.getString("intent_verdict_external_primary|7", null)!!
        val parts = raw.split('\u0001').toMutableList()
        parts[10] = (IntentRules.RULES_VERSION - 1).toString()
        prefs.edit().putString("intent_verdict_external_primary|7", parts.joinToString("\u0001")).apply()
        assertNull(cache.read("external_primary|7", "fp1", "engine-v1", "assist-off"))
    }

    @Test
    fun clearRemovesEverything() {
        cache.write("external_primary|8", verdict, "fp1", "engine-v1", "assist-off")
        assertTrue(cache.size() > 0)
        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test
    fun mediaFingerprintChangesWithSizeAndModifiedTime() {
        val a = IntentVerdictCache.mediaFingerprint("external_primary", 1, 100, 200)
        val b = IntentVerdictCache.mediaFingerprint("external_primary", 1, 101, 200)
        val c = IntentVerdictCache.mediaFingerprint("external_primary", 1, 100, 201)
        assertFalse(a == b)
        assertFalse(a == c)
        assertEquals(a, IntentVerdictCache.mediaFingerprint("external_primary", 1, 100, 200))
    }

    @Test
    fun warmBatchReusesVerdictsBeyondRecommendationLimit() = runBlocking {
        val images = (1L..13L).map { photo(it) }
        val recognizer = TestRecognizer { expiredCoupon }
        val analyzer = IntentBatchAnalyzer(context, recognizer, cache)

        val cold = analyzer.analyze(images, batchLimit = 10, nowMillis = now, timeZone = utc)
        val warm = analyzer.analyze(images, batchLimit = 10, nowMillis = now, timeZone = utc)

        assertEquals((13L downTo 4L).toList(), cold.batch.recommended.map { it.image.id })
        assertEquals(13, cold.ocrAttemptCount)
        assertEquals(13, cache.size())
        assertEquals(cold.batch, warm.batch)
        assertEquals(13, warm.usedCacheCount)
        assertEquals(0, warm.ocrAttemptCount)
        assertEquals(13, recognizer.calls)
    }

    @Test
    fun couponExcludedByLimitEntersRecommendationsAfterItsDeadline() = runBlocking {
        val before = java.time.Instant.parse("2026-12-30T12:00:00Z").toEpochMilli()
        val after = java.time.Instant.parse("2027-01-01T12:00:00Z").toEpochMilli()
        val images = (1L..11L).map { photo(it) }
        val lastCoupon = OcrResult.Success(listOf("满199减30", "有效期至 2026-12-31"), 0.9f)
        val recognizer = TestRecognizer { call -> if (call <= 10) expiredCoupon else lastCoupon }
        val analyzer = IntentBatchAnalyzer(context, recognizer, cache)

        val initial = analyzer.analyze(images, batchLimit = 10, nowMillis = before, timeZone = utc)

        assertEquals((10L downTo 1L).toList(), initial.batch.recommended.map { it.image.id })
        assertTrue(initial.batch.lowerPriority.isEmpty())
        assertEquals(11, initial.ocrAttemptCount)
        assertEquals(11, cache.size())

        val afterDeadline = analyzer.analyze(images, batchLimit = 10, nowMillis = after, timeZone = utc)

        assertEquals("Only the coupon that just expired should be recognized again", 1, afterDeadline.ocrAttemptCount)
        assertEquals(10, afterDeadline.usedCacheCount)
        val newRecommendation = afterDeadline.batch.recommended.single { it.image.id == 11L }.verdict
        assertEquals(ExpiryState.EXPIRED, newRecommendation.expiry)
        assertEquals(IntentRecommendation.RECOMMEND, newRecommendation.recommendation)
        assertTrue(newRecommendation.reason.contains("2026-12-31"))
        assertFalse(newRecommendation.reason.contains("还没到"))

        val repeated = analyzer.analyze(images, batchLimit = 10, nowMillis = after, timeZone = utc)
        assertEquals(11, repeated.usedCacheCount)
        assertEquals(0, repeated.ocrAttemptCount)
        assertEquals(afterDeadline.batch, repeated.batch)
    }

    @Test
    fun newsExcludedByLimitEntersRecommendationsAfterSevenDays() = runBlocking {
        val dayMillis = 86_400_000L
        val images = (1L..10L).map { photo(it) } + photo(11, savedAtMillis = now - 6 * dayMillis)
        val recognizer = TestRecognizer {
            OcrResult.Success(listOf("即时快讯", "城市公交新增两条线路"), 0.9f)
        }
        val analyzer = IntentBatchAnalyzer(context, recognizer, cache)

        val initial = analyzer.analyze(images, batchLimit = 10, nowMillis = now, timeZone = utc)

        assertEquals((10L downTo 1L).toList(), initial.batch.recommended.map { it.image.id })
        assertTrue(initial.batch.lowerPriority.isEmpty())
        assertEquals(11, initial.ocrAttemptCount)
        assertEquals(11, cache.size())

        val sevenDays = now + dayMillis
        val afterRecheck = analyzer.analyze(images, batchLimit = 10, nowMillis = sevenDays, timeZone = utc)

        assertEquals("Only the news item that just reached seven days should be recognized again", 1, afterRecheck.ocrAttemptCount)
        assertEquals(10, afterRecheck.usedCacheCount)
        val recommendation = afterRecheck.batch.recommended.single { it.image.id == 11L }.verdict
        assertEquals(IntentCategory.STALE_NEWS, recommendation.category)
        assertEquals(IntentRecommendation.RECOMMEND, recommendation.recommendation)

        val repeated = analyzer.analyze(images, batchLimit = 10, nowMillis = sevenDays, timeZone = utc)
        assertEquals(11, repeated.usedCacheCount)
        assertEquals(0, repeated.ocrAttemptCount)
        assertEquals(afterRecheck.batch, repeated.batch)
    }

    @Test
    fun mixedBatchLeavesHitAndRetainedOverrideOutOfWrites() = runBlocking {
        val cached = photo(1)
        val retained = photo(2)
        val fresh = photo(3)
        val recognizer = TestRecognizer { expiredCoupon }
        val identity = IntentBatchBuilder.cleanupIdentity(cached)
        cache.write(identity, verdict, fingerprint(cached), recognizer.engineId, "assist-off")
        val key = "intent_verdict_$identity"
        val raw = "1\u0001" + prefs.getString(key, null)!!.substringAfter('\u0001')
        prefs.edit().putString(key, raw).apply()
        val analyzer = IntentBatchAnalyzer(context, recognizer, cache)

        val mixed = analyzer.analyze(
            listOf(cached, retained, fresh), batchLimit = 10, nowMillis = now, timeZone = utc,
            retainedIdentityKeys = setOf(identity, IntentBatchBuilder.cleanupIdentity(retained))
        )

        assertEquals(1, mixed.usedCacheCount)
        assertEquals(2, mixed.ocrAttemptCount)
        assertEquals(setOf(1L, 2L), mixed.batch.excluded.map { it.image.id }.toSet())
        assertEquals(raw, prefs.getString(key, null))
        assertNull(cache.read(IntentBatchBuilder.cleanupIdentity(retained), fingerprint(retained), recognizer.engineId, "assist-off"))
        assertEquals(2, cache.size())

        val afterUnretaining = analyzer.analyze(listOf(retained), batchLimit = 10, nowMillis = now, timeZone = utc)
        assertEquals(1, afterUnretaining.ocrAttemptCount)
        assertEquals(2L, afterUnretaining.batch.recommended.single().image.id)
    }

    @Test
    fun transientRecognitionFailureIsRetriedAndEmptyOutcomeIsReused() = runBlocking {
        val images = listOf(photo(1))
        val recognizer = TestRecognizer { call ->
            if (call == 1) OcrResult.Failed("transient") else OcrResult.Empty
        }
        val analyzer = IntentBatchAnalyzer(context, recognizer, cache)

        val failure = analyzer.analyze(images, batchLimit = 10, nowMillis = now, timeZone = utc)
        assertEquals(1, failure.batch.uncertain.size)
        assertEquals(0, cache.size())
        val retry = analyzer.analyze(images, batchLimit = 10, nowMillis = now, timeZone = utc)
        val warm = analyzer.analyze(images, batchLimit = 10, nowMillis = now, timeZone = utc)

        assertEquals(1, retry.ocrAttemptCount)
        assertEquals(1, cache.size())
        assertEquals(1, warm.usedCacheCount)
        assertEquals(0, warm.ocrAttemptCount)
        assertEquals(2, recognizer.calls)
    }

    @Test
    fun batchEvictionCapsVerdictsAndKeepsUnrelatedPreferences() {
        prefs.edit().putString("unrelated", "kept").apply()
        cache.write("old", verdict, "old-fp", "engine-v1", "assist-off")
        val writes = (1..405).map { IntentVerdictCache.Write("new-$it", verdict, "fp-$it") }

        cache.writeBatch(writes, "engine-v1", "assist-off")

        assertEquals(400, cache.size())
        assertEquals("kept", prefs.getString("unrelated", null))
        assertNull(cache.read("old", "old-fp", "engine-v1", "assist-off"))
        assertNull(cache.read("new-5", "fp-5", "engine-v1", "assist-off"))
        assertNotNull(cache.read("new-6", "fp-6", "engine-v1", "assist-off"))
        assertNotNull(cache.read("new-405", "fp-405", "engine-v1", "assist-off"))
    }

    @Test
    fun supersededBatchDoesNotPublishComputedVerdicts() = runBlocking {
        val generation = AtomicInteger()
        val recognizer = TestRecognizer { expiredCoupon }
        val analyzer = IntentBatchAnalyzer(context, recognizer, cache, batchGeneration = generation::get)

        val result = analyzer.analyze(listOf(photo(1)), batchLimit = 10, nowMillis = now, timeZone = utc) { progress ->
            if (progress == IntentBatchProgress.Organizing) {
                synchronized(cache) { generation.incrementAndGet() }
            }
        }

        assertEquals(1, recognizer.calls)
        assertTrue(result.stale)
        assertEquals(0, cache.size())
    }

    @Test
    fun cancelledPublicationNeitherReplacesNorEvictsVerdicts() {
        val small = IntentVerdictCache(prefs, maxEntries = 1)
        small.write("old", verdict, "old-fp", "engine-v1", "assist-off")
        val writes = listOf(IntentVerdictCache.Write("new", verdict, "new-fp"))

        assertThrows(CancellationException::class.java) {
            small.writeBatch(writes, "engine-v1", "assist-off") { throw CancellationException() }
        }

        assertEquals(1, small.size())
        assertEquals(verdict, small.read("old", "old-fp", "engine-v1", "assist-off"))
        assertNull(small.read("new", "new-fp", "engine-v1", "assist-off"))
    }

    private fun photo(id: Long, savedAtMillis: Long = now - 10L * 86_400_000L): ImageFile {
        val file = File(context.cacheDir, "intent-cache-$id.png")
        file.writeBytes(imageBytes)
        return ImageFile(
            id = id,
            uri = Uri.fromFile(file),
            displayName = file.name,
            dateModified = now,
            size = file.length(),
            width = 16,
            height = 16,
            bucketDisplayName = "Screenshots",
            volumeName = "external_primary",
            relativePath = "DCIM/Screenshots/",
            dateAdded = savedAtMillis
        )
    }

    private fun fingerprint(image: ImageFile) = IntentVerdictCache.mediaFingerprint(
        image.volumeName, image.id, image.size, image.dateModified
    )

    private class TestRecognizer(private val result: (Int) -> OcrResult) : TextRecognizer {
        override val isAvailable = true
        override val engineId = "test-cache-v1"
        var calls = 0
            private set

        override suspend fun recognize(bitmap: Bitmap): OcrResult = result(++calls)
        override fun close() = Unit
    }
}

/**
 * Key 存储的单测：文件位置、重填/更换/删除、损坏与缺失都只影响辅助。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentAssistKeyStoreTest {

    private lateinit var context: Context
    private lateinit var store: IntentAssistKeyStore

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        store = IntentAssistKeyStore(context)
        store.delete()
    }

    @Test
    fun keyFileLivesInNoBackupDir() {
        val file = java.io.File(context.noBackupFilesDir, IntentAssistKeyStore.FILE_NAME)
        assertEquals(file.absolutePath, java.io.File(context.noBackupFilesDir, "intent-assist.key").absolutePath)
        store.write("sk-test-0001")
        assertTrue(file.exists())
    }

    @Test
    fun writeReadReplaceDelete() {
        assertFalse(store.hasKey())
        assertTrue(store.write("sk-first"))
        assertEquals("sk-first", store.read())
        assertTrue(store.write("sk-second"))
        assertEquals("sk-second", store.read())
        assertTrue(store.delete())
        assertNull(store.read())
        assertFalse(store.hasKey())
    }

    @Test
    fun blankKeyIsRejected() {
        assertFalse(store.write("   "))
        assertNull(store.read())
    }

    @Test
    fun corruptFileMeansNoKeyNotACrash() {
        val file = java.io.File(context.noBackupFilesDir, IntentAssistKeyStore.FILE_NAME)
        file.parentFile?.mkdirs()
        file.writeText("")
        assertNull(store.read())
        assertEquals("文件损坏或为空", store.describeState())
    }

    @Test
    fun deleteOnMissingFileIsIdempotent() {
        assertTrue(store.delete())
        assertTrue(store.delete())
    }
}
