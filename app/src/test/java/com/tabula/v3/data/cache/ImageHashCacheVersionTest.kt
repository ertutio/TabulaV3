package com.tabula.v3.data.cache

import com.tabula.v3.data.repository.PerceptualHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ImageHashCacheVersionTest {
    private val cache get() = ImageHashCache(RuntimeEnvironment.getApplication())

    private fun success(hash: Long = 42L) = PerceptualHash.HashResult.Success(hash)

    @Before
    fun setUp() {
        cache.clearAll()
    }

    @Test
    fun versionedRoundTripAndStaleInvalidation() {
        cache.saveHash(1L, success(), contentVersion = 1000L)

        assertNotNull(cache.getHash(1L, contentVersion = 1000L))
        assertNull(cache.getHash(1L, contentVersion = 2000L))
    }

    @Test
    fun legacyEntryWithoutVersionIsStaleForVersionedReads() {
        cache.saveHash(1L, success()) // 旧格式：无版本键

        assertNull(cache.getHash(1L, contentVersion = 1000L))
        assertNotNull(cache.getHash(1L)) // 旧调用方兼容读取
    }

    @Test
    fun failedEntryStaysQueryableAtSameVersion() {
        cache.saveHash(2L, PerceptualHash.HashResult.Failed("io"), contentVersion = 1000L)

        val entry = cache.getHash(2L, contentVersion = 1000L)
        assertNotNull(entry)
        assertTrue(entry!!.isFailed)
        assertNull(cache.getHash(2L, contentVersion = 2000L))
    }

    @Test
    fun batchVersionFiltering() {
        cache.saveHashesBatch(
            mapOf(1L to success(11L), 2L to success(22L)),
            contentVersions = mapOf(1L to 100L, 2L to 200L)
        )

        val fresh = cache.getHashesBatch(listOf(1L, 2L), contentVersions = mapOf(1L to 100L, 2L to 200L))
        assertEquals(2, fresh.size)

        val stale = cache.getHashesBatch(listOf(1L, 2L), contentVersions = mapOf(1L to 999L, 2L to 200L))
        assertNull(stale[1L])
        assertNotNull(stale[2L])
    }

    @Test
    fun cleanupRemovesVersionKeys() {
        cache.saveHash(1L, success(), contentVersion = 1000L)

        cache.cleanupStaleHashes(validImageIds = emptySet())

        assertNull(cache.getHash(1L, contentVersion = 1000L))
    }

    @Test
    fun overwriteWithSameVersionUpdatesHash() {
        cache.saveHash(1L, success(1L), contentVersion = 1000L)
        cache.saveHash(1L, success(2L), contentVersion = 1000L)

        assertEquals(2L, cache.getHash(1L, contentVersion = 1000L)!!.hash)
    }

    @Test
    fun crossVolumeSameIdEntriesStayIndependent() {
        // C1/T03-HASHVOLUME：不同卷的同数值 ID 是不同照片，缓存必须按完整
        // 媒体身份隔离。旧口径按裸数值 ID 存储：第二卷的保存会覆盖第一卷的
        // 哈希与版本键（本用例的有效反例点）。
        val primary = ImageHashCache.HashKey("external_primary", 5L)
        val secondary = ImageHashCache.HashKey("external_secondary", 5L)

        cache.saveHash(primary, success(11L), contentVersion = 100L)
        cache.saveHash(secondary, success(22L), contentVersion = 200L)

        val primaryEntry = cache.getHash(primary, contentVersion = 100L)
        assertEquals(
            "主卷条目不得被另一卷同 ID 行覆盖",
            11L, primaryEntry!!.hash
        )
        val secondaryEntry = cache.getHash(secondary, contentVersion = 200L)
        assertEquals(
            "第二卷条目必须独立可读",
            22L, secondaryEntry!!.hash
        )
        // 版本键同样按身份隔离：另一卷的版本写入不得使本卷条目过期。
        assertNotNull(cache.getHashesBatch(listOf(primary), contentVersions = mapOf(primary to 100L))[primary])
    }

    @Test
    fun volumeQualifiedEntriesSurviveCleanupWithSameIdInValidSet() {
        // 清理按完整身份键判定：有效集合只含主卷同 ID 行时，第二卷条目必须
        // 被清理、主卷条目保留——裸 ID 语义下两者共享键，无法只清一卷。
        val primary = ImageHashCache.HashKey("external_primary", 5L)
        val secondary = ImageHashCache.HashKey("external_secondary", 5L)
        cache.saveHash(primary, success(11L), contentVersion = 100L)
        cache.saveHash(secondary, success(22L), contentVersion = 100L)

        cache.cleanupStaleHashes(validKeys = setOf(primary))

        assertNotNull("有效集合内的主卷条目必须保留", cache.getHash(primary, contentVersion = 100L))
        assertNull("有效集合外的第二卷条目必须清理", cache.getHash(secondary, contentVersion = 100L))
    }
}
