package com.tabula.v3.data.burst

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.SimilarGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 连拍选片规则测试（路线图 #83 第 4 项）。
 *
 * 路线图的验收要求：明确勾选保留、预览其余待回收并确认；用户取消时保留
 * 原组进度，确认后才按实际结果推进；退出、部分操作失败都不应把尚未处理
 * 的组标成完成。前三条在本文件的判定里钉住，"确认后才推进"由调用方
 * 只在提交成功后调用 markSimilarGroupProcessed 保证（见 DeckScreen 接线）。
 *
 * 注：需要 Robolectric —— ImageFile 带 android.net.Uri，纯 JVM 下
 * Uri.parse 会抛 "not mocked"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BurstSelectionRulesTest {

    private fun photo(id: Long, at: Long, volume: String? = "external_primary") = ImageFile(
        id = id,
        uri = android.net.Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "burst$id.jpg",
        dateModified = at,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = "Camera",
        volumeName = volume
    )

    private fun group(vararg photos: ImageFile) = SimilarGroup(
        images = photos.toList(),
        startTime = photos.minOf { it.dateModified },
        endTime = photos.maxOf { it.dateModified }
    )

    private fun keyOf(id: Long, volume: String? = "external_primary") =
        "${volume ?: ""}|$id"

    // ==================== 默认保留集 ====================

    @Test
    fun defaultKeepsEarliestAndLatest() {
        val g = group(photo(3, 300), photo(1, 100), photo(2, 200), photo(4, 400))
        val keys = BurstSelectionRules.defaultKeptKeys(g)
        assertEquals(setOf(keyOf(1), keyOf(4)), keys)
    }

    @Test
    fun singlePhotoGroupKeepsThatPhoto() {
        val g = group(photo(7, 700))
        assertEquals(setOf(keyOf(7)), BurstSelectionRules.defaultKeptKeys(g))
    }

    @Test
    fun emptyGroupHasNoDefault() {
        val g = SimilarGroup(images = emptyList(), startTime = 0, endTime = 0)
        assertTrue(BurstSelectionRules.defaultKeptKeys(g).isEmpty())
    }

    @Test
    fun keepAllCoversEveryMember() {
        val g = group(photo(1, 100), photo(2, 200), photo(3, 300))
        assertEquals(3, BurstSelectionRules.keepAllKeys(g).size)
    }

    // ==================== 决策 ====================

    @Test
    fun keepingOneSendsTheRestToRecycleBin() {
        val g = group(photo(1, 100), photo(2, 200), photo(3, 300))
        val result = BurstSelectionRules.resolve(g, setOf(keyOf(2)))

        assertTrue(result is BurstSelectionRules.Result.Valid)
        val decision = (result as BurstSelectionRules.Result.Valid).decision
        assertEquals(listOf(2L), decision.kept.map { it.id })
        assertEquals(setOf(1L, 3L), decision.discardCandidates.map { it.id }.toSet())
        assertFalse(decision.keepsEverything)
    }

    @Test
    fun keepingEverythingProducesNoDiscardCandidates() {
        val g = group(photo(1, 100), photo(2, 200))
        val result = BurstSelectionRules.resolve(g, BurstSelectionRules.keepAllKeys(g))

        val decision = (result as BurstSelectionRules.Result.Valid).decision
        assertTrue(decision.keepsEverything)
        assertEquals(0, decision.discardCount)
    }

    // ==================== 拒绝的输入 ====================

    @Test
    fun keepingNothingIsRejected() {
        val g = group(photo(1, 100), photo(2, 200))
        val result = BurstSelectionRules.resolve(g, emptySet())

        assertTrue(result is BurstSelectionRules.Result.Invalid)
        assertEquals(
            BurstSelectionRules.BurstSelectionError.KeepNothing,
            (result as BurstSelectionRules.Result.Invalid).error
        )
    }

    @Test
    fun keyFromOutsideTheGroupIsRejected() {
        val g = group(photo(1, 100), photo(2, 200))
        val result = BurstSelectionRules.resolve(g, setOf(keyOf(1), keyOf(99)))

        assertEquals(
            BurstSelectionRules.BurstSelectionError.KeepOutsideGroup,
            (result as BurstSelectionRules.Result.Invalid).error
        )
    }

    @Test
    fun emptyGroupIsRejected() {
        val g = SimilarGroup(images = emptyList(), startTime = 0, endTime = 0)
        val result = BurstSelectionRules.resolve(g, setOf("external_primary|1"))

        assertEquals(
            BurstSelectionRules.BurstSelectionError.EmptyGroup,
            (result as BurstSelectionRules.Result.Invalid).error
        )
    }

    @Test
    fun duplicateMembersAreRejected() {
        val same = photo(1, 100)
        val g = SimilarGroup(images = listOf(same, same), startTime = 100, endTime = 100)
        val result = BurstSelectionRules.resolve(g, setOf(keyOf(1)))

        assertEquals(
            BurstSelectionRules.BurstSelectionError.DuplicateMembers,
            (result as BurstSelectionRules.Result.Invalid).error
        )
    }

    // ==================== 身份 ====================

    @Test
    fun sameNumericIdOnAnotherVolumeIsADifferentPhoto() {
        val g = group(
            photo(1, 100, volume = "external_primary"),
            photo(1, 200, volume = "1234-5678")
        )
        // 只保留第一卷的那张
        val result = BurstSelectionRules.resolve(g, setOf("external_primary|1"))

        val decision = (result as BurstSelectionRules.Result.Valid).decision
        assertEquals("external_primary", decision.kept.single().volumeName)
        assertEquals("1234-5678", decision.discardCandidates.single().volumeName)
    }

    // ==================== 摘要 ====================

    @Test
    fun selectionDigestIsOrderIndependent() {
        val a = BurstSelectionRules.selectionDigest(listOf("b|2", "a|1"))
        val b = BurstSelectionRules.selectionDigest(listOf("a|1", "b|2"))
        assertEquals(a, b)
    }

    @Test
    fun selectionDigestChangesWhenSelectionChanges() {
        val a = BurstSelectionRules.selectionDigest(listOf("a|1"))
        val b = BurstSelectionRules.selectionDigest(listOf("a|1", "a|2"))
        assertTrue(a != b)
    }

    // ==================== 连拍批次识别（入口守卫） ====================

    @Test
    fun tightClusterInOneBucketLooksLikeABurst() {
        val batch = listOf(
            photo(1, 100_000).copy(bucketDisplayName = "Camera"),
            photo(2, 103_000).copy(bucketDisplayName = "Camera"),
            photo(3, 106_000).copy(bucketDisplayName = "Camera")
        )
        assertTrue(BurstSelectionRules.looksLikeOneBurst(batch))
    }

    @Test
    fun singlePhotoIsNeverABurst() {
        assertFalse(BurstSelectionRules.looksLikeOneBurst(listOf(photo(1, 100))))
        assertFalse(BurstSelectionRules.looksLikeOneBurst(emptyList()))
    }

    @Test
    fun photosFarApartInTimeAreNotABurst() {
        // 跨越 30 分钟：超过检测器的 10 分钟窗口
        val batch = listOf(
            photo(1, 0).copy(bucketDisplayName = "Camera"),
            photo(2, 30 * 60 * 1000L).copy(bucketDisplayName = "Camera")
        )
        assertFalse(BurstSelectionRules.looksLikeOneBurst(batch))
    }

    @Test
    fun photosFromDifferentBucketsAreNotABurst() {
        val batch = listOf(
            photo(1, 100_000).copy(bucketDisplayName = "Camera"),
            photo(2, 101_000).copy(bucketDisplayName = "Screenshots")
        )
        assertFalse(BurstSelectionRules.looksLikeOneBurst(batch))
    }

    @Test
    fun groupFromBatchUsesContentAddressedIdentity() {
        val batch = listOf(
            photo(1, 100_000).copy(bucketDisplayName = "Camera"),
            photo(2, 101_000).copy(bucketDisplayName = "Camera")
        )
        val first = BurstSelectionRules.groupFromBatch(batch)
        val second = BurstSelectionRules.groupFromBatch(batch.reversed())

        // 成员集合相同 ⇒ 组身份相同（与 SimilarGroup.id 的内容寻址口径一致）
        assertEquals(first.id, second.id)
        assertEquals(100_000L, first.startTime)
        assertEquals(101_000L, first.endTime)
    }
}
