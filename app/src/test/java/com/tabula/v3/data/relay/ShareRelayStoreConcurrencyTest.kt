package com.tabula.v3.data.relay

import com.tabula.v3.data.model.ImageFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #85 第六轮审计：
 * 1. Store 读写串行化——分享新增与撤销/确认并发时不互相覆盖、不丢无关记录。
 *    用屏障让两个操作在各自线程上先后进入读写过程（可复现交错）。
 * 2. 手动确认按完整身份合并已存在的目标记录，重启后目标身份只有一条。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShareRelayStoreConcurrencyTest {

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
        markedAt = 100L + id,
        state = RelayMarkState.CONFIRMED
    )

    private fun pendingMark(uri: String) = RelayMark(
        identityKey = null,
        uriString = uri,
        displayName = "tmp.png",
        markedAt = 100L,
        state = RelayMarkState.PENDING_REVIEW
    )

    // ============ 并发：分享新增 vs 撤销 ============

    @Test
    fun concurrentShareAndUndoDoNotLoseUnrelatedMarks() {
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        // 预置一条无关标记，验证不丢失
        store.merge(listOf(confirmedMark(99)))

        // 操作 A：分享新增两张
        val opA = {
            store.merge(listOf(confirmedMark(1), confirmedMark(2)))
        }
        // 操作 B：撤销另一条无关标记
        val opB = {
            store.remove(listOf("external_primary|99"))
        }

        runInterleaved(opA, opB)

        val marks = store.load()
        val keys = marks.map { it.identityKey }.toSet()
        // 两个操作的效果都必须体现：新增的两条在、被撤销的不在、无关的（无）
        assertTrue("操作 A 的新增不能被 B 覆盖", marks.any { it.identityKey == "external_primary|1" })
        assertTrue(marks.any { it.identityKey == "external_primary|2" })
        assertFalse("操作 B 的撤销必须生效", marks.any { it.identityKey == "external_primary|99" })
        // 没有多余记录
        assertEquals(2, marks.size)
        assertFalse(keys.contains(null))
    }

    @Test
    fun concurrentShareAndConfirmDoNotDuplicateTarget() {
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        val pending = pendingMark("content://tmp/1")
        store.merge(listOf(pending, confirmedMark(50)))

        // 操作 A：用户手动确认 pending → replace 移除 pending 加 confirmed(8)
        val chosen = photo(8)
        val opA = {
            RelayReviewController(store) { 999L }.manualConfirm(pending, chosen)
        }
        // 操作 B：同时另一台设备的分享又写入了同一条待核对项
        val opB = {
            store.merge(listOf(pending))
        }

        runInterleaved(opA, opB)

        val marks = store.load()
        // 无论谁先谁后：confirmed(8) 必须在（A 的效果）；pending 最多一条
        // （交错顺序决定 pending 是否被 A 的 replace 移除——两种结果都合法，
        //  关键不变量是不重复、不覆盖 A 的确认结果）
        assertTrue("确认结果不能被并发分享覆盖", marks.any { it.identityKey == "external_primary|8" })
        assertTrue(
            "pending 不能重复（0 或 1 条取决于交错顺序）",
            marks.count { it.uriString == "content://tmp/1" } <= 1
        )
        assertTrue(marks.any { it.identityKey == "external_primary|50" })
    }

    @Test
    fun concurrentRemoveAndMergeKeepUnrelatedMarks() {
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(confirmedMark(1), confirmedMark(2), confirmedMark(3)))

        val opA = { store.remove(listOf("external_primary|1")) }
        val opB = { store.merge(listOf(confirmedMark(4))) }

        runInterleaved(opA, opB)

        val keys = store.load().map { it.identityKey }.toSet()
        // remove 删掉 1；merge 加进 4；2、3 与任何操作都无关，不能丢
        assertTrue("无关记录 2 不能丢失", "external_primary|2" in keys)
        assertTrue("无关记录 3 不能丢失", "external_primary|3" in keys)
        assertFalse("remove 必须生效", "external_primary|1" in keys)
        assertTrue("merge 必须生效", "external_primary|4" in keys)
    }

    // ============ 手动确认：完整身份合并 + 重启 ============

    @Test
    fun confirmMergesExistingTargetRecordAndKeepsLatestTime() {
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        // 存储里已有一条同身份的旧确认记录（例如之前手动确认过又分享过）
        val existing = confirmedMark(8).copy(markedAt = 500L, displayName = "旧记录.png")
        store.merge(listOf(pendingMark("content://tmp/1"), existing))
        val controller = RelayReviewController(store) { 900L }  // 确认时间更早

        val result = controller.manualConfirm(pendingMark("content://tmp/1"), photo(8))

        assertTrue(result is ShareRelayStore.WriteResult.Ok)
        val marks = (result as ShareRelayStore.WriteResult.Ok).marks

        // 目标身份只有一条
        assertEquals(1, marks.count { it.identityKey == "external_primary|8" })
        val merged = marks.first { it.identityKey == "external_primary|8" }
        // 时间取更晚的（既有 500 > 确认 900？900 更晚 → 用 900）
        assertEquals(900L, merged.markedAt)
        // 来源信息：本次确认带了新 displayName，优先
        assertEquals("tmp.png", merged.displayName)
        // 待核对项已移除
        assertFalse(marks.any { it.uriString == "content://tmp/1" })
    }

    @Test
    fun afterConfirmRestartTargetIdentityHasExactlyOneRecord() {
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        val pendingUri = "content://tmp/1"
        store.merge(listOf(pendingMark(pendingUri), confirmedMark(8)))
        val controller = RelayReviewController(store) { 900L }

        controller.manualConfirm(pendingMark(pendingUri), photo(8))

        // 重启：新 store 读同一份存储
        val restarted = ShareRelayStore(storage).load()

        val targetRecords = restarted.filter { it.identityKey == "external_primary|8" }
        assertEquals("重启后目标身份应只有一条", 1, targetRecords.size)
        assertEquals(RelayMarkState.CONFIRMED, targetRecords.single().state)
        // 待核对项（URI 键）不再存在
        assertFalse(restarted.any { it.uriString == pendingUri && it.identityKey == null })
    }

    @Test
    fun olderConfirmationDoesNotOverwriteNewerExistingRecord() {
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        val existing = confirmedMark(8).copy(markedAt = 5_000L)
        store.merge(listOf(pendingMark("content://tmp/1"), existing))
        val controller = RelayReviewController(store) { 900L }  // 更早的确认时间

        controller.manualConfirm(pendingMark("content://tmp/1"), photo(8))

        val merged = store.load().first { it.identityKey == "external_primary|8" }
        assertEquals("既有更晚的时间应保留", 5_000L, merged.markedAt)
    }

    // ============ 屏障交错的通用工具 ============

    /**
     * 让两个操作在两个线程上尽可能交错执行（CountDownLatch 双向屏障），
     * 触发"读后写"窗口内的并发覆盖。
     */
    private fun runInterleaved(opA: () -> Any, opB: () -> Any) {
        val pool = Executors.newFixedThreadPool(2)
        val aReady = CountDownLatch(1)
        val bReady = CountDownLatch(1)
        val go = CountDownLatch(1)
        var errorA: Throwable? = null
        var errorB: Throwable? = null

        pool.execute {
            try {
                aReady.countDown()
                go.await(5, TimeUnit.SECONDS)
                opA()
            } catch (t: Throwable) { errorA = t }
        }
        pool.execute {
            try {
                bReady.countDown()
                go.await(5, TimeUnit.SECONDS)
                opB()
            } catch (t: Throwable) { errorB = t }
        }

        aReady.await(5, TimeUnit.SECONDS)
        bReady.await(5, TimeUnit.SECONDS)
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        errorA?.let { throw it }
        errorB?.let { throw it }
    }

    private class MemStorage(var content: String? = null) : RelayStorage {
        override fun read(): String? = content
        override fun write(value: String) { content = value }
    }
}
