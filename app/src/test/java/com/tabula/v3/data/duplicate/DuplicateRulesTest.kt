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
 * 「还剩哪个版本」判定测试（路线图 #83 第 5 项）。
 *
 * 路线图的验收场景直接对应下面几条：
 * 相同字节、同名不同内容、压缩版、看似相同的两张截图、已消失的副本。
 * 第一步（精确副本）与第二步（视觉候选）分别验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DuplicateRulesTest {

    private fun photo(
        id: Long,
        name: String = "p$id.jpg",
        volume: String? = "external_primary",
        size: Long = 1000L
    ) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = name,
        dateModified = 1_700_000_000_000L + id,
        size = size,
        width = 1080,
        height = 1440,
        bucketDisplayName = "Camera",
        volumeName = volume
    )

    private fun keyOf(id: Long, volume: String? = "external_primary") = "${volume ?: ""}|$id"

    // ==================== 精确副本 ====================

    @Test
    fun identicalBytesAreReportedAsCopies() {
        val a = photo(1)
        val b = photo(2)
        // a 在回收站（被查对象），b 在库里（副本证据）
        val findings = DuplicateRules.findExactDuplicates(
            mapOf(
                a to DuplicateRules.ContentVersion("abc123", 1000L),
                b to DuplicateRules.ContentVersion("abc123", 1000L)
            ),
            recycledIdentityKeys = setOf(keyOf(1))
        )

        assertEquals(1, findings.size)
        assertTrue(findings.all { it.hasDuplicate })
        val forA = findings.first { it.image.id == 1L }
        assertEquals(listOf(2L), forA.duplicates.map { it.id })
    }

    @Test
    fun sameNameDifferentContentIsNotACopy() {
        val a = photo(1, name = "IMG_0001.jpg")
        val b = photo(2, name = "IMG_0001.jpg")
        val findings = DuplicateRules.findExactDuplicates(
            mapOf(
                a to DuplicateRules.ContentVersion("hash-a", 1000L),
                b to DuplicateRules.ContentVersion("hash-b", 1000L)
            )
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun sameHashDifferentSizeIsNotTreatedAsACopy() {
        // 摘要相同但大小不同：摘要计算有问题，宁可不错报
        val a = photo(1)
        val b = photo(2)
        val findings = DuplicateRules.findExactDuplicates(
            mapOf(
                a to DuplicateRules.ContentVersion("same-hash", 1000L),
                b to DuplicateRules.ContentVersion("same-hash", 2000L)
            )
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun zeroByteAndBlankHashAreNotMeaningful() {
        val a = photo(1)
        val b = photo(2)
        val findings = DuplicateRules.findExactDuplicates(
            mapOf(
                a to DuplicateRules.ContentVersion("h", 0L),
                b to DuplicateRules.ContentVersion("h", 0L)
            )
        )
        assertTrue(findings.isEmpty())

        val blank = DuplicateRules.findExactDuplicates(
            mapOf(
                a to DuplicateRules.ContentVersion("", 1000L),
                b to DuplicateRules.ContentVersion("", 1000L)
            )
        )
        assertTrue(blank.isEmpty())
    }

    @Test
    fun recycledCopiesDoNotCountAsStillPresent() {
        val a = photo(1)
        val b = photo(2)
        val findings = DuplicateRules.findExactDuplicates(
            mapOf(
                a to DuplicateRules.ContentVersion("same", 1000L),
                b to DuplicateRules.ContentVersion("same", 1000L)
            ),
            recycledIdentityKeys = setOf(keyOf(2))
        )

        // 只有 b 是被查的回收站成员，而 a 算库内副本；但 a 不在回收站
        // → 对 b 而言 a 仍是副本。这里反过来验证：两张都在回收站时互相不算证据
        val bothRecycled = DuplicateRules.findExactDuplicates(
            mapOf(
                a to DuplicateRules.ContentVersion("same", 1000L),
                b to DuplicateRules.ContentVersion("same", 1000L)
            ),
            recycledIdentityKeys = setOf(keyOf(1), keyOf(2))
        )
        // 两张都在回收站，图库没有留存 → 无提示
        assertTrue(bothRecycled.isEmpty())
    }

    @Test
    fun sameNumericIdOnAnotherVolumeIsNotASelfCopy() {
        val a = photo(7, volume = "external_primary")
        val b = photo(7, volume = "1234-5678")
        val findings = DuplicateRules.findExactDuplicates(
            mapOf(
                a to DuplicateRules.ContentVersion("same", 1000L),
                b to DuplicateRules.ContentVersion("same", 1000L)
            ),
            recycledIdentityKeys = setOf(keyOf(7))
        )

        // a 在回收站，b 在另一卷的库里：字节相同就是真副本
        assertEquals(1, findings.size)
        val forA = findings.first { it.image.volumeName == "external_primary" }
        assertEquals("1234-5678", forA.duplicates.single().volumeName)
    }

    @Test
    fun findingsAreSortedByIdentityForStableOutput() {
        val photos = (1L..6L).map { photo(it) }
        val map = photos.associateWith { DuplicateRules.ContentVersion("same", 1000L) }

        val first = DuplicateRules.findExactDuplicates(map).map { it.image.mediaIdentityKey() }
        val second = DuplicateRules.findExactDuplicates(map.entries.reversed().associate { it.key to it.value })
            .map { it.image.mediaIdentityKey() }

        assertEquals(first, second)
        assertEquals(first.sorted(), first)
    }

    // ==================== 确认前复核 ====================

    @Test
    fun reverifyFailsWhenCopyWasAlsoSelected() {
        val copy = photo(2)
        val ok = DuplicateRules.reverify(
            duplicate = copy,
            selectedForDeletion = setOf(keyOf(2)),
            stillReadable = { true }
        )
        assertFalse("两份都要删时，「还剩一份」不成立", ok)
    }

    @Test
    fun reverifyFailsWhenCopyIsGone() {
        val copy = photo(2)
        val ok = DuplicateRules.reverify(
            duplicate = copy,
            selectedForDeletion = emptySet(),
            stillReadable = { false }
        )
        assertFalse("副本已消失时不能继续", ok)
    }

    @Test
    fun reverifyPassesWhenCopyStillReadableAndNotSelected() {
        val copy = photo(2)
        val ok = DuplicateRules.reverify(
            duplicate = copy,
            selectedForDeletion = setOf(keyOf(99)),
            stillReadable = { true }
        )
        assertTrue(ok)
    }

    // ==================== 视觉候选（第二步） ====================

    @Test
    fun closeHashesAreLikelySameShot() {
        val a = photo(1)
        val b = photo(2)
        val candidate = DuplicateRules.VisualCandidate(a, b, hammingDistance = 3)
        assertTrue(candidate.isLikelySameShot)
    }

    @Test
    fun distantHashesAreNotLikelySameShot() {
        val a = photo(1)
        val b = photo(2)
        val candidate = DuplicateRules.VisualCandidate(a, b, hammingDistance = 40)
        assertFalse(candidate.isLikelySameShot)
    }

    @Test
    fun visualRankingFiltersDistantAndSelfPairs() {
        val a = photo(1)
        val b = photo(2)
        val c = photo(3)
        val ranked = DuplicateRules.rankVisualCandidates(
            listOf(
                DuplicateRules.VisualCandidate(a, c, 35),  // 太远，滤掉
                DuplicateRules.VisualCandidate(a, b, 2),
                DuplicateRules.VisualCandidate(a, a, 0)    // 自身，滤掉
            )
        )

        assertEquals(1, ranked.size)
        assertEquals(2L, ranked.single().other.id)
    }

    @Test
    fun visualCandidatesIgnoreThePhotoItself() {
        val a = photo(1)
        val others = listOf(photo(2), photo(3), a)
        val candidates = DuplicateRules.visualCandidatesFor(
            image = a,
            others = others,
            distanceOf = { _, other -> if (other.id == 3L) 1 else 20 }
        )

        assertEquals(1, candidates.size)
        assertEquals(3L, candidates.single().other.id)
    }

    @Test
    fun visualRankingIsOrderedByDistance() {
        val a = photo(1)
        val ranked = DuplicateRules.rankVisualCandidates(
            listOf(
                DuplicateRules.VisualCandidate(a, photo(2), 8),
                DuplicateRules.VisualCandidate(a, photo(3), 1),
                DuplicateRules.VisualCandidate(a, photo(4), 5)
            )
        )
        assertEquals(listOf(1, 5, 8), ranked.map { it.hammingDistance })
    }
}
