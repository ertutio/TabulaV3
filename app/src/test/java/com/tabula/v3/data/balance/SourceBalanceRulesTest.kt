package com.tabula.v3.data.balance

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 来源覆盖均衡抽样测试（路线图 #83 第 6 项）。
 *
 * 路线图给的验收方式：用固定随机种子在大小悬殊的相册上与现有抽法比较，
 * 记录每批覆盖的相册数、最大相册占比；测试单相册、候选少于批量、
 * 全在冷却三种边界。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SourceBalanceRulesTest {

    private fun photo(id: Long, path: String, volume: String = "external_primary") = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L + id,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = path.substringAfterLast('/').ifBlank { path },
        volumeName = volume,
        relativePath = path
    )

    /** 一个大相册 500 张 + 一个小相册 20 张（1:25 的悬殊）。 */
    private fun lopsidedLibrary(): List<ImageFile> =
        (1L..500L).map { photo(it, "DCIM/Camera/") } +
            (1000L..1019L).map { photo(it, "DCIM/Screenshots/") }

    // ==================== 分桶 ====================

    @Test
    fun bucketsAreKeyedByVolumeAndPathNotDisplayName() {
        val images = listOf(
            photo(1, "DCIM/Camera/"),
            photo(2, "DCIM/Camera/"),
            photo(3, "DCIM/Screenshots/"),
            photo(4, "DCIM/Camera/", volume = "1234-5678")
        )

        val buckets = SourceBalanceRules.bucketize(images)

        assertEquals(3, buckets.size)
        // 桶按 key 字典序排：1234-5678|DCIM/Camera/ 在前
        assertEquals(listOf(1, 2, 1), buckets.map { it.images.size })
    }

    @Test
    fun photosWithoutPathFallBackToVolumeBucket() {
        val noPath = photo(1, "").copy(relativePath = null, volumeName = "external_primary")
        val buckets = SourceBalanceRules.bucketize(listOf(noPath))
        assertEquals("external_primary|", buckets.single().key)
    }

    // ==================== 覆盖改善（核心验收） ====================

    @Test
    fun balancedSamplingCoversMoreAlbumsThanUniformRandom() {
        val library = lopsidedLibrary()
        val batchSize = 6
        val seeds = 0 until 200

        var balancedAlbumsTotal = 0
        var uniformAlbumsTotal = 0
        var balancedMaxShare = 0
        var uniformMaxShare = 0

        seeds.forEach { seed ->
            val balanced = SourceBalanceRules.balancedSample(
                available = library,
                batchSize = batchSize,
                random = Random(seed)
            )
            val uniform = library.shuffled(Random(seed)).take(batchSize)

            balancedAlbumsTotal += balanced.map { it.relativePath }.toSet().size
            uniformAlbumsTotal += uniform.map { it.relativePath }.toSet().size
            balancedMaxShare = maxOf(
                balancedMaxShare,
                balanced.count { it.relativePath == "DCIM/Camera/" }
            )
            uniformMaxShare = maxOf(
                uniformMaxShare,
                uniform.count { it.relativePath == "DCIM/Camera/" }
            )
        }

        // 均衡抽样平均覆盖的来源数必须严格多于均匀随机
        assertTrue(
            "balancedAvg=${balancedAlbumsTotal / seeds.count()} " +
                "uniformAvg=${uniformAlbumsTotal / seeds.count()}",
            balancedAlbumsTotal > uniformAlbumsTotal
        )
        // 且最大相册占比不会每次都吃满整批
        assertTrue(
            "balancedMax=$balancedMaxShare uniformMax=$uniformMaxShare",
            balancedMaxShare <= uniformMaxShare
        )
    }

    @Test
    fun smallAlbumGetsRealOpportunities() {
        val library = lopsidedLibrary()
        var batchesWithScreenshot = 0
        val runs = 200

        repeat(runs) { seed ->
            val batch = SourceBalanceRules.balancedSample(
                available = library,
                batchSize = 6,
                random = Random(seed)
            )
            if (batch.any { it.relativePath == "DCIM/Screenshots/" }) batchesWithScreenshot++
        }

        // 均匀随机下这个比例约 6*20/520 ≈ 23%；平方根加权应该明显更高
        assertTrue(
            "screenshot appeared in $batchesWithScreenshot/$runs batches",
            batchesWithScreenshot > runs / 2
        )
    }

    // ==================== 边界 ====================

    @Test
    fun singleAlbumStillReturnsAFullBatch() {
        val library = (1L..50L).map { photo(it, "DCIM/Camera/") }
        val batch = SourceBalanceRules.balancedSample(library, 10, Random(1))

        assertEquals(10, batch.size)
        assertEquals(1, batch.map { it.relativePath }.toSet().size)
    }

    @Test
    fun fewerCandidatesThanBatchReturnsAllOfThem() {
        val library = listOf(photo(1, "A/"), photo(2, "B/"))
        val batch = SourceBalanceRules.balancedSample(library, 10, Random(1))

        assertEquals(2, batch.size)
        assertEquals(2, batch.map { it.mediaIdentityKey() }.toSet().size)
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertTrue(SourceBalanceRules.balancedSample(emptyList(), 10, Random(1)).isEmpty())
        assertTrue(SourceBalanceRules.balancedSample(listOf(photo(1, "A/")), 0, Random(1)).isEmpty())
    }

    @Test
    fun batchNeverContainsDuplicateIdentities() {
        // 跨卷同数值 ID 是不同照片，但同一身份不能重复出现
        val library = (1L..30L).map { photo(it, "DCIM/Camera/") } +
            (1L..30L).map { photo(it, "DCIM/Camera/", volume = "1234-5678") }

        repeat(50) { seed ->
            val batch = SourceBalanceRules.balancedSample(library, 20, Random(seed))
            val keys = batch.map { it.mediaIdentityKey() }
            assertEquals("seed=$seed", keys.size, keys.toSet().size)
        }
    }

    // ==================== 冷却回填 ====================

    @Test
    fun backfillPicksEarliestCooledFirst() {
        val cooled = listOf(
            photo(1, "A/"),
            photo(2, "A/"),
            photo(3, "A/")
        )
        val pickedAt = mapOf(1L to 300L, 2L to 100L, 3L to 200L)

        val backfill = SourceBalanceRules.backfillFromCooldown(
            cooldownImages = cooled,
            alreadyPicked = listOf(photo(99, "B/")),
            batchSize = 3,
            pickedAt = { pickedAt.getValue(it.id) }
        )

        // 已选 1 张，batchSize=3 → 还能补 2 张，按冷却时间从早到晚：2(100) → 3(200)
        assertEquals(listOf(2L, 3L), backfill.map { it.id })
    }

    @Test
    fun backfillStopsAtBatchSize() {
        val cooled = (1L..10L).map { photo(it, "A/") }
        val backfill = SourceBalanceRules.backfillFromCooldown(
            cooldownImages = cooled,
            alreadyPicked = (100L..104L).map { photo(it, "B/") },
            batchSize = 7,
            pickedAt = { it.id }
        )
        assertEquals(2, backfill.size)
    }

    @Test
    fun backfillAvoidsDuplicatingAlreadyPicked() {
        val same = photo(1, "A/")
        val backfill = SourceBalanceRules.backfillFromCooldown(
            cooldownImages = listOf(same, photo(2, "A/")),
            alreadyPicked = listOf(same),
            batchSize = 5,
            pickedAt = { it.id }
        )
        assertEquals(listOf(2L), backfill.map { it.id })
    }

    @Test
    fun backfillWithFullBatchContributesNothing() {
        val backfill = SourceBalanceRules.backfillFromCooldown(
            cooldownImages = listOf(photo(1, "A/")),
            alreadyPicked = (1L..5L).map { photo(it, "B/") },
            batchSize = 5,
            pickedAt = { it.id }
        )
        assertTrue(backfill.isEmpty())
    }

    // ==================== 可复现性 ====================

    @Test
    fun sameSeedProducesSameBatch() {
        val library = lopsidedLibrary()
        val a = SourceBalanceRules.balancedSample(library, 8, Random(42))
        val b = SourceBalanceRules.balancedSample(library, 8, Random(42))

        assertEquals(a.map { it.mediaIdentityKey() }, b.map { it.mediaIdentityKey() })
    }
}
