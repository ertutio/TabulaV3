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
 * #88 真实删除入口测试。
 *
 * 审计明确要求的用例：
 * - 回收站有两张相同照片、图库另有一张同尺寸但内容不同的照片 → 预期无提示
 * - 确认前复核失败（内容变化 / 权限撤销 / 副本消失）→ 停止本次删除、更新提示
 * - 两份都选中 → 不算"还剩一份"
 *
 * 摘要来自注入的 ContentResolver 风格的 digestOf（每个身份键一份字节内容），
 * 独立事实来源，不是把判定算法再算一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DuplicateDeleteEntryTest {

    private fun photo(id: Long, size: Long = 1000L) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/external_primary/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L,
        size = size,
        width = 1080,
        height = 1440,
        bucketDisplayName = "Camera",
        volumeName = "external_primary"
    )

    private fun keyOf(id: Long) = "external_primary|$id"

    /** 用"内容标签"模拟 ContentResolver 读到的字节摘要。 */
    private fun digestOf(contents: Map<String, String>): (ImageFile) -> DuplicateRules.ContentVersion? =
        { image ->
            contents[image.mediaIdentityKey()]?.let {
                DuplicateRules.ContentVersion(it, image.size)
            }
        }

    @Test
    fun twoCopiesInRecycleBinAndDifferentContentInLibraryShowNoHint() {
        val r1 = photo(1)
        val r2 = photo(2)
        val libraryOther = photo(3, size = 1000L) // 同尺寸，但内容不同

        val contents = mapOf(
            keyOf(1) to "AAA",
            keyOf(2) to "AAA",     // 回收站两张字节相同
            keyOf(3) to "BBB"      // 图库那张同尺寸不同内容
        )
        val digest = digestOf(contents)
        val recycleKeys = setOf(keyOf(1), keyOf(2))
        val libraryKeys = setOf(keyOf(3))

        val findings = DuplicateRules.findExactDuplicates(
            candidates = mapOf(
                r1 to digest(r1)!!,
                r2 to digest(r2)!!,
                libraryOther to digest(libraryOther)!!
            ),
            recycledIdentityKeys = recycleKeys
        )

        // 关键：回收站成员互相不算证据；图库那张内容不同也不是副本
        assertTrue("预期无提示", findings.isEmpty())
    }

    @Test
    fun libraryCopyWithSameBytesShowsHint() {
        val r1 = photo(1)
        val lib = photo(4)
        val digest = digestOf(mapOf(keyOf(1) to "AAA", keyOf(4) to "AAA"))

        val findings = DuplicateRules.findExactDuplicates(
            candidates = mapOf(r1 to digest(r1)!!, lib to digest(lib)!!),
            recycledIdentityKeys = setOf(keyOf(1))
        )

        assertEquals(1, findings.size)
        assertEquals(listOf(4L), findings.single().duplicates.map { it.id })
    }

    @Test
    fun contentChangeInvalidatesHintAndStopsDeletion() {
        val r1 = photo(1)
        val lib = photo(4)
        // 扫描时相同
        val scanDigest = digestOf(mapOf(keyOf(1) to "AAA", keyOf(4) to "AAA"))
        val findings = DuplicateRules.findExactDuplicates(
            candidates = mapOf(r1 to scanDigest(r1)!!, lib to scanDigest(lib)!!),
            recycledIdentityKeys = setOf(keyOf(1))
        )
        val details = findings.associate { it.image.mediaIdentityKey() to it.duplicates }
        assertTrue(details[keyOf(1)]!!.isNotEmpty())

        // 确认前内容变了
        val nowDigest = digestOf(mapOf(keyOf(1) to "AAA", keyOf(4) to "CHANGED"))
        val verdict = DuplicateReverify.verifyAll(
            toDelete = listOf(r1),
            details = details,
            libraryKeys = setOf(keyOf(1), keyOf(4)),
            digestOf = nowDigest
        )

        assertTrue("复核失败要停止本次删除", verdict.shouldStop)
        assertEquals(setOf(keyOf(1)), verdict.invalidatedKeys)
    }

    @Test
    fun permissionRevokedOnCopyInvalidatesHint() {
        val r1 = photo(1)
        val lib = photo(4)
        val details = mapOf(keyOf(1) to listOf(lib))
        // 权限撤销：副本读不到 → digestOf 返回 null
        val nowDigest = digestOf(mapOf(keyOf(1) to "AAA"))

        val verdict = DuplicateReverify.verifyAll(
            toDelete = listOf(r1),
            details = details,
            libraryKeys = setOf(keyOf(1), keyOf(4)),
            digestOf = nowDigest
        )

        assertTrue(verdict.shouldStop)
        assertEquals(setOf(keyOf(1)), verdict.invalidatedKeys)
    }

    @Test
    fun copyGoneFromLibraryInvalidatesHint() {
        val r1 = photo(1)
        val lib = photo(4)
        val details = mapOf(keyOf(1) to listOf(lib))
        val nowDigest = digestOf(mapOf(keyOf(1) to "AAA", keyOf(4) to "AAA"))

        // 副本已从图库消失：libraryKeys 里没有它
        val verdict = DuplicateReverify.verifyAll(
            toDelete = listOf(r1),
            details = details,
            libraryKeys = setOf(keyOf(1)),
            digestOf = nowDigest
        )

        assertTrue(verdict.shouldStop)
    }

    @Test
    fun bothCopiesSelectedDoNotCountAsSurviving() {
        val r1 = photo(1)
        val r2 = photo(4)
        val details = mapOf(keyOf(1) to listOf(r2), keyOf(4) to listOf(r1))
        val nowDigest = digestOf(mapOf(keyOf(1) to "AAA", keyOf(4) to "AAA"))

        val verdict = DuplicateReverify.verifyAll(
            toDelete = listOf(r1, r2),
            details = details,
            libraryKeys = setOf(keyOf(1), keyOf(4)),
            digestOf = nowDigest
        )

        assertTrue("两份都选中时提示必须失效", verdict.shouldStop)
        assertEquals(setOf(keyOf(1), keyOf(4)), verdict.invalidatedKeys)
    }

    @Test
    fun unchangedCopyAllowsDeletionToProceed() {
        val r1 = photo(1)
        val lib = photo(4)
        val digest = digestOf(mapOf(keyOf(1) to "AAA", keyOf(4) to "AAA"))
        val details = mapOf(keyOf(1) to listOf(lib))

        val verdict = DuplicateReverify.verifyAll(
            toDelete = listOf(r1),
            details = details,
            libraryKeys = setOf(keyOf(1), keyOf(4)),
            digestOf = digest
        )

        assertFalse("复核通过才继续删除", verdict.shouldStop)
        assertTrue(verdict.invalidatedKeys.isEmpty())
    }
}
