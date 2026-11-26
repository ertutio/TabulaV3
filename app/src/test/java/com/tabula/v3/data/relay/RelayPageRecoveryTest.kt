package com.tabula.v3.data.relay

import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #85 审计：回收站已写入、标记移除失败后的真实页面恢复路径。
 *
 * 页面走的是 ShareRelayStore（真实序列化）+ RelayReviewRules.resolveRows
 * （页面用的同一 seam）+ RelayReviewController（页面操作），不直接调规则冒充。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RelayPageRecoveryTest {

    private fun photo(id: Long) = ImageFile(
        id = id,
        uri = android.net.Uri.parse("content://media/external_primary/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = "Camera",
        volumeName = "external_primary"
    )

    private fun confirmedMark(id: Long) = RelayMark(
        identityKey = "external_primary|$id",
        uriString = "content://media/external_primary/images/media/$id",
        displayName = "p$id.jpg",
        markedAt = 100L,
        state = RelayMarkState.CONFIRMED
    )

    private class MemStorage(var content: String? = null, var failRead: Boolean = false) : RelayStorage {
        override fun read(): String? {
            if (failRead) throw IllegalStateException("mark storage read failed")
            return content
        }
        override fun write(content: String) { this.content = content }
    }

    /**
     * 完整页面路径：写成功 → 标记移除失败 → 页面刷新/重启 → 重试移除 → 标记消失。
     * 回收站只有一条记录（写入按身份幂等）。
     */
    @Test
    fun pageRecoversAndRetriesMarkRemovalAfterPartialFailure() = runBlocking {
        val image = photo(1)
        val mark = confirmedMark(1)
        val key = image.mediaIdentityKey()

        // 一份真实序列化的标记存储
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(mark))

        // 回收站：独立的事实来源（按身份去重）
        val recycleBin = mutableListOf<ImageFile>()
        var failMarkRemove = false

        val tx = RelayRecycleTransaction(
            recycleWrite = {
                if (recycleBin.none { it.mediaIdentityKey() == key }) recycleBin += it
            },
            recycleRead = { recycleBin.toList() },
            markRemove = {
                if (failMarkRemove) throw IllegalStateException("remove failed")
                store.remove(listOf(it))
            },
            markRead = { store.loadResult() }
        )

        // 1) 第一次：回收站写成功，标记移除失败
        failMarkRemove = true
        val first = tx.execute(mark, image)
        assertTrue(first is RelayRecycleTransaction.Outcome.MarkRemovalFailed)
        assertEquals(1, recycleBin.size)
        assertTrue(store.loadResult() is ShareRelayStore.LoadResult.Ok)
        assertTrue((store.loadResult() as ShareRelayStore.LoadResult.Ok).marks.any { it.identityKey == key })

        // 2) 页面刷新 / 重启：新 store 读同一份存储；图库里已经没有这张（进了回收站）
        val afterRestartStore = ShareRelayStore(storage)
        val pageMarks = afterRestartStore.load()
        val library = emptyList<ImageFile>()   // 已不在图库
        val rows = RelayReviewRules.resolveRows(
            marks = pageMarks,
            library = library,
            recycleBinKeys = recycleBin.map { it.mediaIdentityKey() }.toSet()
        )

        assertEquals(1, rows.size)
        val row = rows.single()
        assertTrue("页面必须识别为已入回收站", row.alreadyInRecycleBin)
        assertTrue("必须允许只重试移除标记，不要求重新选图", row.canRetryMarkRemoval)
        assertTrue(row.reviewReason!!.contains("回收站"))

        // 3) 用户点"重试移除标记"：页面 controller 只删标记
        failMarkRemove = false
        RelayReviewController(afterRestartStore).undo(row.mark)

        // 4) 标记消失，回收站仍只有一条
        assertTrue(afterRestartStore.load().isEmpty())
        assertEquals(1, recycleBin.size)
        assertEquals(key, recycleBin.single().mediaIdentityKey())
    }

    @Test
    fun markReadFailureIsNotConfirmedAsSuccess() = runBlocking {
        val image = photo(2)
        val mark = confirmedMark(2)
        val storage = MemStorage(failRead = true)
        val store = ShareRelayStore(storage)

        val result = store.loadResult()
        assertTrue("标记读回失败必须是 Failed，不能当空列表",
            result is ShareRelayStore.LoadResult.Failed)

        // 事务里读回失败 → MarkRemovalFailed（未确认），不是 Done
        val tx = RelayRecycleTransaction(
            recycleWrite = { },
            recycleRead = { listOf(image) },  // 回收站读回有目标
            markRemove = { },
            markRead = { store.loadResult() }
        )
        val outcome = tx.execute(mark, image)
        assertTrue(outcome is RelayRecycleTransaction.Outcome.MarkRemovalFailed)
    }

    @Test
    fun normalEmptyStoreIsNotAReadFailure() {
        val store = ShareRelayStore(MemStorage())
        val result = store.loadResult()
        assertTrue(result is ShareRelayStore.LoadResult.Ok)
        assertTrue((result as ShareRelayStore.LoadResult.Ok).marks.isEmpty())
        assertFalse(store.load().isNotEmpty())
    }

    @Test
    fun recycledRowWithNoMarkIsNotShown() {
        // 回收站里有照片但没有标记 → 页面无行可显示（不是幽灵记录）
        val rows = RelayReviewRules.resolveRows(
            marks = emptyList(),
            library = emptyList(),
            recycleBinKeys = setOf("external_primary|1")
        )
        assertTrue(rows.isEmpty())
    }
}
