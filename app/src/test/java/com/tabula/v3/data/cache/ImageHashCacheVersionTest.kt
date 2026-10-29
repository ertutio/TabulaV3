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
}
