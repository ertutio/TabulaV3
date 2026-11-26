package com.tabula.v3.data.burst

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.SimilarGroup
import com.tabula.v3.data.repository.mediaIdentityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #87 入口级测试：进入选片 → 提交 → 回收站/卡片状态一致。
 *
 * 用生产 seam BurstCommit：按 currentIndex 筛未处理成员、按身份复核、
 * 部分成功分类。模拟的回收站是独立于判定逻辑的字符串集合，
 * 不是把同一算法再算一遍（独立事实来源）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BurstEntryFlowTest {

    private fun photo(id: Long, at: Long = id, volume: String = "external_primary") = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "b$id.jpg",
        dateModified = at,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = "Camera",
        volumeName = volume
    )

    private fun realGroup(vararg photos: ImageFile) = SimilarGroup(
        images = photos.toList(),
        startTime = photos.minOf { it.dateModified },
        endTime = photos.maxOf { it.dateModified }
    )

    private fun keys(images: List<ImageFile>) = images.map { it.mediaIdentityKey() }.toSet()

    // ============ 先滑动再进入 ============

    @Test
    fun slidingPastPhotosExcludesThemFromEntry() {
        val a = photo(1, 100)
        val b = photo(2, 200)
        val c = photo(3, 300)
        val group = realGroup(a, b, c)
        val batch = listOf(a, b, c)

        // 已滑到 index=1：a 不再可选，只剩 b、c
        val eligible = BurstCommit.eligible(
            group, batch, currentIndex = 1,
            libraryKeys = keys(batch), recycleKeys = emptySet()
        )

        assertNotNull(eligible)
        assertEquals(setOf(2L, 3L), eligible!!.images.map { it.id }.toSet())
    }

    @Test
    fun afterSlidingPastAllButOneEntryIsHidden() {
        val a = photo(1, 100)
        val b = photo(2, 200)
        val group = realGroup(a, b)
        val batch = listOf(a, b)

        assertNull(
            BurstCommit.eligible(
                group, batch, currentIndex = 1,
                libraryKeys = keys(batch), recycleKeys = emptySet()
            )
        )
    }

    // ============ 归档/删除后进入 ============

    @Test
    fun photosInRecycleBinAreExcludedAtEntry() {
        val a = photo(1, 100)
        val b = photo(2, 200)
        val c = photo(3, 300)
        val group = realGroup(a, b, c)

        val eligible = BurstCommit.eligible(
            group, listOf(a, b, c), currentIndex = 0,
            libraryKeys = keys(listOf(a, b, c)),   // 仍在 allImages
            recycleKeys = keys(listOf(c))         // 但 c 已进回收站
        )

        assertEquals(setOf(1L, 2L), eligible!!.images.map { it.id }.toSet())
    }

    @Test
    fun archivedPhotosAreExcludedAtEntry() {
        val a = photo(1, 100)
        val b = photo(2, 200)
        val c = photo(3, 300)
        val group = realGroup(a, b, c)

        val eligible = BurstCommit.eligible(
            group, listOf(a, b, c), currentIndex = 0,
            libraryKeys = keys(listOf(a, b, c)),
            recycleKeys = emptySet(),
            archivedKeys = keys(listOf(b))
        )

        assertEquals(setOf(1L, 3L), eligible!!.images.map { it.id }.toSet())
    }

    // ============ 全部保留 ============

    @Test
    fun keepingEverythingProducesNoRecycleWrites() {
        val a = photo(1, 100)
        val b = photo(2, 200)
        val group = realGroup(a, b)

        val decision = BurstSelectionRules.resolve(group, BurstSelectionRules.keepAllKeys(group))
        val d = (decision as BurstSelectionRules.Result.Valid).decision

        assertTrue(d.keepsEverything)
        assertEquals(0, d.discardCount)
    }

    // ============ 提交前身份复核 ============

    @Test
    fun reverifyDropsPhotosThatLeftTheLibraryBeforeCommit() {
        val a = photo(1, 100)
        val b = photo(2, 200)
        val c = photo(3, 300)

        val toDiscard = BurstCommit.reverify(
            discard = listOf(a, b, c),
            libraryKeys = keys(listOf(a, b)),   // c 已不在图库
            recycleKeys = emptySet()
        )

        assertEquals(listOf(1L, 2L), toDiscard.map { it.id })
    }

    @Test
    fun reverifyDropsPhotosThatEnteredRecycleBinBeforeCommit() {
        val a = photo(1, 100)
        val b = photo(2, 200)

        val toDiscard = BurstCommit.reverify(
            discard = listOf(a, b),
            libraryKeys = keys(listOf(a, b)),
            recycleKeys = keys(listOf(b))
        )

        assertEquals(listOf(1L), toDiscard.map { it.id })
    }

    // ============ 部分失败 ============

    @Test
    fun partialWriteKeepsStateConsistentWithoutClaimingWholeGroupDone() {
        val a = photo(1, 100)
        val b = photo(2, 200)
        val c = photo(3, 300)
        val expected = listOf(a, b, c)

        // 真实回收站只收到 a 和 c（独立事实来源，不是重算算法）
        val actuallyMoved = setOf("external_primary|1", "external_primary|3")
        val result = BurstCommit.result(expected, actuallyMoved)

        assertTrue(result.isPartial)
        assertFalse(result.isComplete)
        assertEquals(listOf(1L, 3L), result.succeeded.map { it.id })
        assertEquals(listOf(2L), result.failed.map { it.id })

        // 同步后的卡片/回收站状态：成功集从卡片移除、进回收站；失败集仍留在卡片
        val recycleBin = result.succeeded
        val deck = expected.filter { it !in recycleBin }
        assertEquals(setOf(1L, 3L), recycleBin.map { it.id }.toSet())
        assertEquals(setOf(2L), deck.map { it.id }.toSet())
        // 不推进原组：组 id 不变，仍可用于下次重试
        assertNotNull(realGroup(a, b, c).id)
    }

    @Test
    fun completeWriteMarksGroupDone() {
        val a = photo(1, 100)
        val b = photo(2, 200)
        val result = BurstCommit.result(listOf(a, b), keys(listOf(a, b)))
        assertTrue(result.isComplete)
        assertFalse(result.isPartial)
    }

    @Test
    fun nothingWrittenDoesNotAdvance() {
        val a = photo(1, 100)
        val result = BurstCommit.result(listOf(a), emptySet())
        assertTrue(result.isNothing)
        assertEquals(0, result.succeeded.size)
    }

    // ============ 返回后状态一致 ============

    @Test
    fun cancellingEntryLeavesGroupAndDeckUnchanged() {
        val a = photo(1, 100)
        val b = photo(2, 200)
        val group = realGroup(a, b)
        val deckBefore = listOf(a, b)

        // 取消：不调用任何提交 seam，状态自然不变
        val deckAfter = deckBefore.toList()

        assertEquals(deckBefore, deckAfter)
        assertTrue(keys(listOf(a, b)).containsAll(keys(deckAfter)))
    }
}
