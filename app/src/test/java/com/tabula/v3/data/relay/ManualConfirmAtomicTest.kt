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

/**
 * #85 第五轮审计：
 * 1. 手动确认是一次原子存储更新——同一次读取、移除+加入、只写一次；
 *    读失败 / 唯一一次写失败时原记录和其他记录均可恢复；不丢待核对项。
 * 2. 旧两步流程（removeByUri + merge）的回归场景：证明第二步失败会丢待核对项，
 *    而原子版本不会。
 * 3. ShareRelayActivity 的 writeFailure：分享入口注入存储失败，转发仍可用、反馈准确。
 * 4. 页面撤销/重试失败保留状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ManualConfirmAtomicTest {

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

    private fun pendingMark(uri: String = "content://tmp/1") = RelayMark(
        identityKey = null,
        uriString = uri,
        displayName = "tmp.png",
        markedAt = 100L,
        state = RelayMarkState.PENDING_REVIEW
    )

    private fun confirmedMark(id: Long) = RelayMark(
        identityKey = "external_primary|$id",
        uriString = "content://media/external_primary/images/media/$id",
        displayName = "p$id.jpg",
        markedAt = 100L,
        state = RelayMarkState.CONFIRMED
    )

    private class MemStorage(var content: String? = null, var failWrite: Boolean = false) : RelayStorage {
        override fun read(): String? = content
        override fun write(value: String) {
            if (failWrite) throw IllegalStateException("write boom")
            content = value
        }
    }

    // ============ 原子更新成功路径 ============

    @Test
    fun atomicConfirmReplacesPendingWithConfirmedInOneWrite() {
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(pendingMark(), confirmedMark(5)))
        val controller = RelayReviewController(store) { 999L }

        val result = controller.manualConfirm(pendingMark(), photo(8))

        assertTrue(result is ShareRelayStore.WriteResult.Ok)
        val marks = (result as ShareRelayStore.WriteResult.Ok).marks
        println("DBG marks=" + marks.map { it.identityKey to it.uriString to it.state })
        // 待核对项被替换为已确认项；其他标记保留
        assertEquals(2, marks.size)
        assertTrue(marks.any { it.identityKey == "external_primary|8" && it.state == RelayMarkState.CONFIRMED })
        assertFalse(marks.any { it.uriString == "content://tmp/1" })
        assertTrue(marks.any { it.identityKey == "external_primary|5" })
        // 存储真实内容 = 写入结果（确认写入结果）
        val reloaded = ShareRelayStore(storage).load()
        assertEquals(marks.map { it.identityKey }, reloaded.map { it.identityKey })
    }

    // ============ 故障注入 ============

    @Test
    fun atomicConfirmStopsOnReadFailureAndPendingSurvives() {
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(pendingMark(), confirmedMark(5)))
        val before = storage.content
        assertNotNull(before)
        // 注入读失败（读的是同一份物理存储）
        val brokenStorage = object : RelayStorage {
            override fun read(): String? = throw IllegalStateException("read boom")
            override fun write(value: String) { storage.content = value }
        }
        val storeBroken = ShareRelayStore(brokenStorage)
        val controller = RelayReviewController(storeBroken) { 999L }

        val result = controller.manualConfirm(pendingMark(), photo(8))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        // 原始数据可恢复：合法存储内容没被覆盖
        assertEquals(before, storage.content)
        // 其他标记与待核对项都还在原存储里
        assertEquals(2, store.load().size)
        assertTrue(store.load().any { it.state == RelayMarkState.PENDING_REVIEW })
    }

    @Test
    fun atomicConfirmStopsOnSingleWriteFailureAndAllRecordsRecoverable() {
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(pendingMark(), confirmedMark(5)))
        val before = storage.content
        assertNotNull(before)
        storage.failWrite = true
        val controller = RelayReviewController(store) { 999L }

        val result = controller.manualConfirm(pendingMark(), photo(8))

        assertTrue("唯一一次写入失败必须上报", result is ShareRelayStore.WriteResult.Failed)
        // 原记录和其他记录均可恢复：存储内容原样
        assertEquals(before, storage.content)
        assertEquals(2, store.load().size)
        assertTrue(store.load().any { it.state == RelayMarkState.PENDING_REVIEW })
    }

    @Test
    fun atomicConfirmSurvivesRestart() {
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(pendingMark(), confirmedMark(5)))
        val controller = RelayReviewController(store) { 999L }

        controller.manualConfirm(pendingMark(), photo(8))

        // 重启：新 store 读同一份存储
        val afterRestart = ShareRelayStore(storage)
        val marks = afterRestart.load()
        assertEquals(2, marks.size)
        assertTrue(marks.any { it.identityKey == "external_primary|8" })
    }

    // ============ 旧两步流程回归：第二步失败会丢待核对项 ============

    @Test
    fun legacyTwoStepFailureWouldHaveLostPendingRecord() {
        // 回归证明：旧行为（先 removeByUri 成功写掉 pending，再 merge 失败）
        // 会把待核对项从存储里删掉，无法恢复。
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(pendingMark(), confirmedMark(5)))

        store.removeByUri(listOf("content://tmp/1"))
        assertEquals(1, store.load().size)
        assertFalse(store.load().any { it.uriString == "content://tmp/1" })

        // 第二步 merge 失败——pending 已从存储消失，恢复不了
        storage.failWrite = true
        val lost = store.load()
        assertEquals(1, lost.size)
        assertTrue(
            "旧两步流程在第二步失败时确实会丢失待核对项",
            lost.none { it.uriString == "content://tmp/1" }
        )
        storage.failWrite = false
    }

    @Test
    fun atomicVersionDoesNotLosePendingOnWriteFailure() {
        // 对照：原子版本在同样的写失败下，pending 仍在存储里
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(pendingMark(), confirmedMark(5)))
        storage.failWrite = true
        val controller = RelayReviewController(store) { 999L }

        controller.manualConfirm(pendingMark(), photo(8))

        assertTrue(
            "原子更新失败时待核对项不丢失",
            store.load().any {
                it.uriString == "content://tmp/1" && it.state == RelayMarkState.PENDING_REVIEW
            }
        )
        storage.failWrite = false
    }

    // ============ ShareRelayActivity 路径：writeFailure 转发仍可用 ============

    @Test
    fun shareEntryWriteFailureStillForwardsAndReports() {
        val storage = MemStorage(failWrite = true)
        val store = ShareRelayStore(storage)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(
                android.content.Intent.EXTRA_STREAM,
                android.net.Uri.parse("content://media/external_primary/images/media/1")
            )
        }

        val outcome = ShareRelayEntry.handle(
            RuntimeEnvironment.getApplication(), store, intent,
            localIdentityKeys = setOf("external_primary|1"), nowMillis = 500L
        )

        assertNotNull(outcome)
        assertTrue("转发仍可用", outcome!!.resolution.hasForwardTargets)
        assertEquals(1, outcome.resolution.forwardUris.size)
        assertNotNull("写失败必须上报", outcome.writeFailure)
        assertTrue("不能显示已成功标记：存储里没有记录", store.load().isEmpty())
    }

    @Test
    fun shareEntrySuccessHasNoWriteFailure() {
        val store = ShareRelayStore(MemStorage())
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(
                android.content.Intent.EXTRA_STREAM,
                android.net.Uri.parse("content://media/external_primary/images/media/1")
            )
        }

        val outcome = ShareRelayEntry.handle(
            RuntimeEnvironment.getApplication(), store, intent,
            localIdentityKeys = setOf("external_primary|1"), nowMillis = 500L
        )

        assertNotNull(outcome)
        assertTrue(outcome!!.resolution.hasForwardTargets)
        assertNull(outcome.writeFailure)
        assertTrue(store.load().isNotEmpty())
    }

    // ============ 页面撤销/重试失败保留状态 ============

    @Test
    fun pageUndoFailureKeepsPageState() {
        val storage = MemStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(confirmedMark(7), confirmedMark(8)))
        val rowsBefore = controller(store, storage).rows(listOf(photo(7), photo(8))).size

        storage.failWrite = true
        val result = controller(store, storage).undo(confirmedMark(7))
        assertTrue(result is ShareRelayStore.WriteResult.Failed)

        // 页面状态保留：两条记录都还在
        assertEquals(2, store.load().size)
        assertEquals(rowsBefore, controller(store, storage).rows(listOf(photo(7), photo(8))).size)
        storage.failWrite = false
    }

    private fun controller(store: ShareRelayStore, storage: MemStorage) =
        RelayReviewController(store) { 999L }
}
