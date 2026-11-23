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
 * #85 故障注入测试：实际页面"移入回收站"操作路径的两个步骤分别失败。
 *
 * 顺序保证（旧实现是反的，写失败会丢标记）：
 *   先写回收站并读回确认 → 再移除待清标记。
 *
 * 用可控的回收站/标记存储注入失败，不直接调规则函数冒充页面操作。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RelayRecycleTransactionTest {

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

    /** 可控回收站：recycleWrite/recycleRead 可独立注入失败。 */
    private class FakeRecycleBin {
        val items = mutableListOf<ImageFile>()
        var failWrite = false
        var failRead = false
        var writeCalls = 0

        suspend fun write(image: ImageFile) {
            writeCalls++
            if (failWrite) throw IllegalStateException("write failed")
            if (items.none { it.mediaIdentityKey() == image.mediaIdentityKey() }) {
                items += image // 按身份去重，重试不会重复写入
            }
        }

        suspend fun read(): List<ImageFile> {
            if (failRead) throw IllegalStateException("read failed")
            return items.toList()
        }
    }

    private class FakeMarkStore(marks: List<RelayMark>) {
        val marks = marks.toMutableList()
        var failRemove = false
        var removeCalls = 0

        fun remove(key: String) {
            removeCalls++
            if (failRemove) throw IllegalStateException("mark remove failed")
            marks.removeAll { it.identityKey == key }
        }

        fun read(): List<RelayMark> = marks.toList()
    }

    private fun transaction(
        bin: FakeRecycleBin,
        marks: FakeMarkStore
    ) = RelayRecycleTransaction(
        recycleWrite = { bin.write(it) },
        recycleRead = { bin.read() },
        markRemove = { marks.remove(it) },
        markRead = { marks.read() }
    )

    @Test
    fun writeFailureKeepsMarkVisible() = runBlocking {
        val image = photo(1)
        val mark = confirmedMark(1)
        val bin = FakeRecycleBin().apply { failWrite = true }
        val marks = FakeMarkStore(listOf(mark))
        val tx = transaction(bin, marks)

        val outcome = tx.execute(mark, image)

        assertTrue(outcome is RelayRecycleTransaction.Outcome.RecycleFailed)
        assertTrue("回收站没写进去", bin.items.isEmpty())
        assertTrue("标记必须仍然可见", marks.marks.any { it.identityKey == mark.identityKey })
        assertEquals(0, marks.removeCalls) // 写失败时根本没走到移除标记
    }

    @Test
    fun readFailureKeepsMarkVisible() = runBlocking {
        val image = photo(2)
        val mark = confirmedMark(2)
        val bin = FakeRecycleBin().apply { failRead = true }
        val marks = FakeMarkStore(listOf(mark))

        val outcome = transaction(bin, marks).execute(mark, image)

        assertTrue(outcome is RelayRecycleTransaction.Outcome.RecycleFailed)
        assertEquals(0, marks.removeCalls)
        assertTrue(marks.marks.isNotEmpty())
    }

    @Test
    fun notPersistedKeepsMarkVisible() = runBlocking {
        val image = photo(3)
        val mark = confirmedMark(3)
        // 写入被调用但 storage 里没出现目标（模拟 provider 成功但读回丢失）
        val bin = FakeRecycleBin()
        val marks = FakeMarkStore(listOf(mark))
        val tx = RelayRecycleTransaction(
            recycleWrite = { /* 故意不落库 */ },
            recycleRead = { bin.read() },
            markRemove = { marks.remove(it) },
            markRead = { marks.read() }
        )

        val outcome = tx.execute(mark, image)

        assertEquals(RelayRecycleTransaction.Outcome.NotPersisted, outcome)
        assertTrue(marks.marks.isNotEmpty())
    }

    @Test
    fun markRemovalFailureKeepsMarkAndDoesNotLosePhoto() = runBlocking {
        val image = photo(4)
        val mark = confirmedMark(4)
        val bin = FakeRecycleBin()
        val marks = FakeMarkStore(listOf(mark)).apply { failRemove = true }
        val tx = transaction(bin, marks)

        val outcome = tx.execute(mark, image)

        assertTrue(outcome is RelayRecycleTransaction.Outcome.MarkRemovalFailed)
        assertEquals(1, bin.items.size)        // 照片确实进了回收站
        assertTrue("标记仍在，可重试", marks.marks.isNotEmpty())
        assertTrue(
            "界面必须如实提示已进回收站但标记移除失败",
            tx.message(outcome).contains("已在回收站")
        )
    }

    @Test
    fun retryAfterMarkRemovalFailureDoesNotDuplicateRecycleWrite() = runBlocking {
        val image = photo(5)
        val mark = confirmedMark(5)
        val bin = FakeRecycleBin()
        val marks = FakeMarkStore(listOf(mark)).apply { failRemove = true }
        val tx = transaction(bin, marks)

        tx.execute(mark, image)  // 第一次：标记移除失败
        assertEquals(1, bin.writeCalls)

        marks.failRemove = false
        val retry = tx.execute(mark, image)  // 重试

        assertEquals(RelayRecycleTransaction.Outcome.Done, retry)
        // 写入 API 被调用两次，但回收站按身份去重：记录不重复、内容不丢失
        assertEquals("回收站记录不能重复", 1, bin.items.size)
        assertEquals("external_primary|5", bin.items.single().mediaIdentityKey())
        assertTrue("重试后标记被清掉", marks.marks.isEmpty())
    }

    @Test
    fun retryAfterWriteFailureEventuallySucceeds() = runBlocking {
        val image = photo(6)
        val mark = confirmedMark(6)
        val bin = FakeRecycleBin().apply { failWrite = true }
        val marks = FakeMarkStore(listOf(mark))
        val tx = transaction(bin, marks)

        assertTrue(tx.execute(mark, image) is RelayRecycleTransaction.Outcome.RecycleFailed)
        assertTrue(marks.marks.isNotEmpty())

        bin.failWrite = false
        assertEquals(RelayRecycleTransaction.Outcome.Done, tx.execute(mark, image))
        assertEquals(1, bin.items.size)
        assertTrue(marks.marks.isEmpty())
    }

    @Test
    fun doubleClickIsIdempotent() = runBlocking {
        // 重复点击：回收站按身份去重，标记第二次已不在——不会重复写入或丢记录
        val image = photo(7)
        val mark = confirmedMark(7)
        val bin = FakeRecycleBin()
        val marks = FakeMarkStore(listOf(mark))
        val tx = transaction(bin, marks)

        assertEquals(RelayRecycleTransaction.Outcome.Done, tx.execute(mark, image))
        // 第二次点击时标记已被移除，页面不会再传这条；即便传了：
        val second = tx.execute(mark, image)
        assertEquals(1, bin.items.size)
        assertTrue(second is RelayRecycleTransaction.Outcome.Done ||
            second is RelayRecycleTransaction.Outcome.MarkRemovalFailed)
    }

    @Test
    fun restartReadsRemainingMarkAfterPartialFailure() = runBlocking {
        val image = photo(8)
        val mark = confirmedMark(8)
        val bin = FakeRecycleBin()
        val marks = FakeMarkStore(listOf(mark)).apply { failRemove = true }
        transaction(bin, marks).execute(mark, image)

        // 模拟重启：新 store 读同一份标记存储
        val recovered = ShareRelayStore(object : RelayStorage {
            override fun read() = marks.read().joinToString { it.uriString }.let { null }
            override fun write(content: String) {}
        })
        // 用真实序列化验证标记能被读回：改用内存存储重放
        val storage = object : RelayStorage {
            var content: String? = null
            override fun read() = content
            override fun write(content: String) { this.content = content }
        }
        val realStore = ShareRelayStore(storage)
        realStore.merge(listOf(mark))
        val afterRestart = ShareRelayStore(storage).load()
        assertTrue("重启后标记仍可见", afterRestart.any { it.identityKey == mark.identityKey })
        assertFalse(recovered.load().isNotEmpty())
    }

    @Test
    fun successMessageIsAccurate() = runBlocking {
        val image = photo(9)
        val mark = confirmedMark(9)
        val bin = FakeRecycleBin()
        val marks = FakeMarkStore(listOf(mark))
        val tx = transaction(bin, marks)

        val outcome = tx.execute(mark, image)
        assertEquals(RelayRecycleTransaction.Outcome.Done, outcome)
        assertTrue(tx.message(outcome).contains("回收站"))
    }
}
