package com.tabula.v3.data.duplicate

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 候选筛选的行为规格（#105 抽出后的语义钉子）。
 *
 * 抽函数最容易犯的错是"顺手改语义"。这里把抽出前 `MainActivity` 的三条判定
 * 逐条钉住，另加两条 #105 明确要求的边界：
 * - **跨卷同 ID 是两张照片**：身份键是 `volumeName|id`，不是裸 id
 * - **回收站成员不是自己的候选**：它既不是"还剩一份"的证据，也不该进摘要集合
 *
 * 与 [DuplicateScanMeasurementTest] 的分工：那边测耗时与前后对照，这边只测
 * 语义——改性能实现时这组用例必须一条不动地通过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DuplicateScanCandidatesTest {

    private fun photo(
        id: Long,
        volume: String? = "external_primary",
        size: Long = 1000L
    ) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/${volume ?: ""}/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L + id,
        size = size,
        width = 1080,
        height = 1440,
        bucketDisplayName = "Camera",
        volumeName = volume
    )

    private fun keys(images: List<ImageFile>) = images.map { it.mediaIdentityKey() }

    // ============ 基本筛选 ============

    @Test
    fun onlySameSizeLibraryPhotosBecomeCandidates() {
        val recycled = photo(1, size = 1000L)
        val sameSize = photo(2, size = 1000L)
        val otherSize = photo(3, size = 5000L)

        val selected = DuplicateScanCandidates.selectLibraryCandidates(
            allImages = listOf(sameSize, otherSize),
            deletedImages = listOf(recycled)
        )

        assertEquals(listOf(sameSize.mediaIdentityKey()), keys(selected))
    }

    @Test
    fun recycledPhotosAreNeverTheirOwnCandidates() {
        val recycledA = photo(1)
        val recycledB = photo(2)
        val inLibrary = photo(3)

        val selected = DuplicateScanCandidates.selectLibraryCandidates(
            allImages = listOf(recycledA, recycledB, inLibrary),
            deletedImages = listOf(recycledA, recycledB)
        )

        assertEquals(
            "回收站里的照片不能作为库内副本证据，必须从候选里排除",
            listOf(inLibrary.mediaIdentityKey()),
            keys(selected)
        )
    }

    @Test
    fun zeroSizePhotosNeverBecomeCandidates() {
        val recycled = photo(1, size = 0L)
        val libraryZero = photo(2, size = 0L)

        assertTrue(
            "0 字节的「相同」没有意义，回收站侧也不该产生尺寸分组",
            DuplicateScanCandidates.recycleSizes(listOf(recycled)).isEmpty()
        )
        assertTrue(
            DuplicateScanCandidates.selectLibraryCandidates(
                allImages = listOf(libraryZero),
                deletedImages = listOf(recycled)
            ).isEmpty()
        )
    }

    @Test
    fun emptyRecycleBinProducesNoCandidates() {
        val images = (1L..5L).map { photo(it) }
        assertTrue(
            DuplicateScanCandidates.selectLibraryCandidates(images, emptyList()).isEmpty()
        )
    }

    @Test
    fun candidateOrderFollowsLibraryOrder() {
        val recycled = photo(99, size = 1000L)
        val images = listOf(photo(5), photo(1), photo(3))

        val selected = DuplicateScanCandidates.selectLibraryCandidates(images, listOf(recycled))

        assertEquals(
            "顺序沿用输入，前后对照才能逐项比较",
            listOf("external_primary|5", "external_primary|1", "external_primary|3"),
            keys(selected)
        )
    }

    // ============ 跨卷同 ID ============

    @Test
    fun sameNumericIdOnAnotherVolumeIsASeparateCandidate() {
        // 回收站里是主卷的 7 号；SD 卡上也有一个 7 号——是另一张照片
        val recycledPrimary = photo(7, volume = "external_primary")
        val sdCardSameId = photo(7, volume = "1234-5678")

        val selected = DuplicateScanCandidates.selectLibraryCandidates(
            allImages = listOf(sdCardSameId),
            deletedImages = listOf(recycledPrimary)
        )

        assertEquals(
            "跨卷同数值 ID 是不同照片，SD 卡那张必须留下来当候选",
            listOf("1234-5678|7"),
            keys(selected)
        )
    }

    @Test
    fun recycledOnAnotherVolumeDoesNotShadowTheLibraryCopy() {
        // 反向：回收站里是 SD 卡那张，主卷同 ID 那张在库里
        val recycledSd = photo(7, volume = "1234-5678")
        val libraryPrimary = photo(7, volume = "external_primary")

        val selected = DuplicateScanCandidates.selectLibraryCandidates(
            allImages = listOf(libraryPrimary),
            deletedImages = listOf(recycledSd)
        )

        assertEquals(listOf("external_primary|7"), keys(selected))
    }

    @Test
    fun volumeUnknownIsDistinctFromNamedVolume() {
        // volumeName 为 null 的照片身份键是 "|id"，与任何具名卷都不同
        val recycledUnknown = photo(7, volume = null)
        val libraryNamed = photo(7, volume = "external_primary")

        val selected = DuplicateScanCandidates.selectLibraryCandidates(
            allImages = listOf(libraryNamed),
            deletedImages = listOf(recycledUnknown)
        )

        assertEquals(listOf("external_primary|7"), keys(selected))
        assertEquals("|7", recycledUnknown.mediaIdentityKey())
    }

    // ============ 与分组的衔接 ============

    @Test
    fun selectionFeedsGroupingWithoutSelfPairs() {
        // 端到端（不含 IO）：筛选 → 分组，回收站照片只与库内候选成对
        val recycled = photo(1, size = 1000L)
        val libraryCopy = photo(2, size = 1000L)
        val libraryOther = photo(3, size = 1000L)

        val selected = DuplicateScanCandidates.selectLibraryCandidates(
            allImages = listOf(libraryCopy, libraryOther),
            deletedImages = listOf(recycled)
        )
        assertEquals(2, selected.size)

        val findings = DuplicateRules.findExactDuplicates(
            candidates = mapOf(
                recycled to DuplicateRules.ContentVersion("same", 1000L),
                libraryCopy to DuplicateRules.ContentVersion("same", 1000L),
                libraryOther to DuplicateRules.ContentVersion("other", 1000L)
            ),
            recycledIdentityKeys = setOf(recycled.mediaIdentityKey())
        )

        assertEquals(1, findings.size)
        assertEquals(
            "只应把字节相同的库内那张算作副本",
            listOf(libraryCopy.mediaIdentityKey()),
            keys(findings.single().duplicates)
        )
        assertFalse(
            "同尺寸但内容不同的照片不是副本",
            findings.single().duplicates.any { it.mediaIdentityKey() == libraryOther.mediaIdentityKey() }
        )
    }
}
