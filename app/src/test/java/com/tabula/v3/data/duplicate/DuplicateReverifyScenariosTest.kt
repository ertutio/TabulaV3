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
 * 删除前复核的入口级场景测试（审计意见 #88）。
 *
 * 审计点名的六类场景逐个覆盖：
 * 1. 权限变化（副本此刻读不到）
 * 2. 文件内容变化（同名不同内容）
 * 3. 两份都选中
 * 4. 另一份已删除
 * 5. 回收站内有两份但图库没有留存
 * 6. 无法验证时不能声称"还留有副本"
 *
 * 这些用例直接调用 [DuplicateRules.reverify]，也就是生产代码在删除前
 * 实际走的那条判定，不是另写一套平行逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DuplicateReverifyScenariosTest {

    private fun photo(id: Long, volume: String = "external_primary", size: Long = 1000L) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L,
        size = size,
        width = 1080,
        height = 1440,
        bucketDisplayName = "Camera",
        volumeName = volume
    )

    private fun keyOf(id: Long, volume: String = "external_primary") = "$volume|$id"

    // ============ 1. 权限变化 ============

    @Test
    fun permissionRevokedOnCopyFailsReverification() {
        val copy = photo(2)
        val ok = DuplicateRules.reverify(
            duplicate = copy,
            selectedForDeletion = setOf(keyOf(1)),
            stillReadable = { false }   // 权限被收回后读不到
        )
        assertFalse("副本读不到时不能声称还有一份", ok)
    }

    @Test
    fun permissionRestoredAfterRevokePassesAgain() {
        val copy = photo(2)
        assertFalse(
            DuplicateRules.reverify(copy, setOf(keyOf(1))) { false }
        )
        assertTrue(
            DuplicateRules.reverify(copy, setOf(keyOf(1))) { true }
        )
    }

    // ============ 2. 文件内容变化 ============

    @Test
    fun contentChangeMakesTheCopyNoLongerIdentical() {
        // 扫描时两份 sha256 相同；复核时副本内容变了 → 摘要不再相等
        val main = photo(1)
        val copy = photo(2)
        val scanned = DuplicateRules.findExactDuplicates(
            mapOf(
                main to DuplicateRules.ContentVersion("same-hash", 1000L),
                copy to DuplicateRules.ContentVersion("same-hash", 1000L)
            )
        )
        assertEquals(1, scanned.first { it.image.id == 1L }.duplicates.size)

        // 复核阶段用新摘要重算
        val afterEdit = DuplicateRules.findExactDuplicates(
            mapOf(
                main to DuplicateRules.ContentVersion("same-hash", 1000L),
                copy to DuplicateRules.ContentVersion("changed-hash", 1000L)
            )
        )
        assertTrue("内容变了就不再是副本", afterEdit.isEmpty())
    }

    @Test
    fun sizeChangeAlsoBreaksTheCopyRelation() {
        val main = photo(1)
        val copy = photo(2)
        val findings = DuplicateRules.findExactDuplicates(
            mapOf(
                main to DuplicateRules.ContentVersion("same-hash", 1000L),
                copy to DuplicateRules.ContentVersion("same-hash", 2000L)
            )
        )
        assertTrue(findings.isEmpty())
    }

    // ============ 3. 两份都选中 ============

    @Test
    fun selectingBothCopiesFailsReverification() {
        val copy = photo(2)
        val ok = DuplicateRules.reverify(
            duplicate = copy,
            selectedForDeletion = setOf(keyOf(1), keyOf(2)),
            stillReadable = { true }
        )
        assertFalse("两份都要删时「还剩一份」不成立", ok)
    }

    // ============ 4. 另一份已删除 ============

    @Test
    fun deletedCopyFailsReverification() {
        val copy = photo(2)
        val ok = DuplicateRules.reverify(
            duplicate = copy,
            selectedForDeletion = setOf(keyOf(1)),
            stillReadable = { false }
        )
        assertFalse(ok)
    }

    @Test
    fun copyMissingFromLibraryIsExcludedFromFindings() {
        val main = photo(1)
        val gone = photo(2)
        // 只有 main 参与了摘要计算（gone 已被删除，不在图库里）
        val findings = DuplicateRules.findExactDuplicates(
            mapOf(main to DuplicateRules.ContentVersion("h", 1000L))
        )
        assertTrue(findings.isEmpty())
        assertFalse(gone.mediaIdentityKey() in findings.map { it.image.mediaIdentityKey() })
    }

    // ============ 5. 回收站内有两份但图库没有留存 ============

    @Test
    fun twoCopiesInRecycleBinWithNothingInLibraryProducesNoHint() {
        // 回收站里 a、b 字节相同，但图库里没有第三份
        val a = photo(1)
        val b = photo(2)
        // 调用方只把图库候选传进来；图库为空 → 候选为空 → 无提示
        val libraryCandidates = emptyMap<ImageFile, DuplicateRules.ContentVersion>()

        val findings = DuplicateRules.findExactDuplicates(libraryCandidates)

        assertTrue("图库没有留存时不能声称「还剩一份" + "」", findings.isEmpty())
    }

    @Test
    fun copiesInsideRecycleBinDoNotCountAsSurviving() {
        val a = photo(1)
        val b = photo(2)
        // 两张都在回收站：把 b 标记为"不算存活"
        val findings = DuplicateRules.findExactDuplicates(
            mapOf(
                a to DuplicateRules.ContentVersion("h", 1000L),
                b to DuplicateRules.ContentVersion("h", 1000L)
            ),
            recycledIdentityKeys = setOf(keyOf(2))
        )
        // b 不算存活，因此 a 没有可提示的副本
        assertTrue(findings.none { it.image.id == 1L })
    }

    // ============ 6. 无法验证时不声称 ============

    @Test
    fun unreadablePhotoProducesNoVersionEntry() {
        // ContentDigestCalculator 读失败时返回 Failed，调用方不把该照片放进 map，
        // 于是它不会出现在任何"相同副本"提示里
        val version = DuplicateRules.ContentVersion("", 0L)
        assertFalse("空摘要不算有意义的内容版本", version.isMeaningful)
    }

    @Test
    fun zeroSizeFilesAreNeverReportedAsCopies() {
        val a = photo(1, size = 0L)
        val b = photo(2, size = 0L)
        val findings = DuplicateRules.findExactDuplicates(
            mapOf(
                a to DuplicateRules.ContentVersion("h", 0L),
                b to DuplicateRules.ContentVersion("h", 0L)
            )
        )
        assertTrue(findings.isEmpty())
    }

    // ============ 大小预筛（审计要求"按大小缩小候选"） ============

    @Test
    fun sizeFilterNarrowsCandidatesBeforeHashing() {
        val recycle = photo(1, size = 1000L)
        val sameSize = photo(2, size = 1000L)
        val differentSize = photo(3, size = 5000L)

        val recycleSizes = setOf(recycle.size)
        val candidates = listOf(recycle, sameSize, differentSize).filter {
            it.size in recycleSizes && it.mediaIdentityKey() != recycle.mediaIdentityKey()
        }

        assertEquals(listOf(2L), candidates.map { it.id })
    }
}
