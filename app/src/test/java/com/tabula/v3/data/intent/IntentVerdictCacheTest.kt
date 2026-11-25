package com.tabula.v3.data.intent

import android.content.Context
import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
