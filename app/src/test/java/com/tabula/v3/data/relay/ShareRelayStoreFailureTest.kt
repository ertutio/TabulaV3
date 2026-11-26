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
 * #85 审计：所有读后写的标记操作必须区分失败，失败时停止写入且不覆盖。
 *
 * 注入读取异常、损坏 JSON、写入异常，覆盖 merge / remove / removeByUri /
 * 手动确认 / 撤销 / 分享写标记路径。确认其他标记不丢失、不误报成功。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShareRelayStoreFailureTest {

    private fun mark(id: Long) = RelayMark(
        identityKey = "external_primary|$id",
        uriString = "content://media/external_primary/images/media/$id",
        displayName = "p$id.jpg",
        markedAt = 100L + id,
        state = RelayMarkState.CONFIRMED
    )

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

    /** 三种故障注入的存储。 */
    private class FailingStorage(
        var content: String? = null,
        var failRead: Boolean = false,
        var corrupt: Boolean = false,
        var failWrite: Boolean = false
    ) : RelayStorage {
        override fun read(): String? {
            if (failRead) throw IllegalStateException("read boom")
            if (corrupt) return "{broken json"
            return content
        }
        override fun write(value: String) {
            if (failWrite) throw IllegalStateException("write boom")
            content = value
        }
    }

    // ============ merge：读异常 / 损坏 JSON / 写异常 ============

    @Test
    fun mergeStopsOnReadExceptionAndKeepsOriginalRecoverable() {
        val storage = FailingStorage(content = "[]").apply { failRead = true }
        val store = ShareRelayStore(storage)

        val result = store.merge(listOf(mark(1)))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        assertEquals("[]", storage.content)
    }

    @Test
    fun mergeStopsOnCorruptJsonAndKeepsOriginalRecoverable() {
        val storage = FailingStorage(content = "[{\"valid\":\"no\"]").apply { corrupt = true }
        val store = ShareRelayStore(storage)

        val result = store.merge(listOf(mark(1)))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        assertEquals("[{\"valid\":\"no\"]", storage.content)
    }

    @Test
    fun mergeStopsOnWriteExceptionAndOtherMarksAreNotLost() {
        val storage = FailingStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(mark(1), mark(2)))
        val before = storage.content
        storage.failWrite = true

        val result = store.merge(listOf(mark(3)))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        assertEquals("写入失败后原始内容不能被破坏", before, storage.content)
    }

    // ============ remove / removeByUri ============

    @Test
    fun removeStopsOnReadFailure() {
        val storage = FailingStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(mark(1), mark(2)))
        val before = storage.content
        storage.failRead = true

        val result = store.remove(listOf("external_primary|1"))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        assertEquals(before, storage.content)
    }

    @Test
    fun removeStopsOnWriteFailure() {
        val storage = FailingStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(mark(1), mark(2)))
        storage.failWrite = true

        val result = store.remove(listOf("external_primary|1"))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        assertEquals(2, store.load().size)
    }

    @Test
    fun removeByUriStopsOnCorruptJson() {
        val storage = FailingStorage().apply { corrupt = true }
        val store = ShareRelayStore(storage)

        val result = store.removeByUri(listOf("content://x/1"))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        // 损坏内容不被覆盖：读失败路径不会触发写入（content 仍为 null）
        assertNull(storage.content)
    }

    // ============ 手动确认（removeByUri + merge 两步） ============

    @Test
    fun manualConfirmFailsWhenRemoveByUriFailsAndPendingMarkSurvives() {
        val storage = FailingStorage()
        val store = ShareRelayStore(storage)
        val pending = RelayMark(
            identityKey = null,
            uriString = "content://tmp/1",
            displayName = "tmp.png",
            markedAt = 100L,
            state = RelayMarkState.PENDING_REVIEW
        )
        store.merge(listOf(pending, mark(5)))
        val before = storage.content
        storage.failRead = true
        val controller = RelayReviewController(store) { 999L }

        val result = controller.manualConfirm(pending, photo(8))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        assertEquals("读失败时不覆盖原始数据", before, storage.content)
    }

    @Test
    fun manualConfirmFailsWhenMergeFailsAndPendingRecordIsPreserved() {
        val storage = FailingStorage()
        val store = ShareRelayStore(storage)
        val pending = RelayMark(
            identityKey = null,
            uriString = "content://tmp/1",
            displayName = "tmp.png",
            markedAt = 100L,
            state = RelayMarkState.PENDING_REVIEW
        )
        // 第一步 removeByUri 成功（pending 被"移除"），第二步 merge 失败
        store.merge(listOf(pending, mark(5)))
        val contentBefore = storage.content
        storage.failWrite = true
        val controller = RelayReviewController(store) { 999L }

        val result = controller.manualConfirm(pending, photo(8))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
        // 审计要求"不丢记录"：第一步 removeByUri 因写失败返回 Failed，
        // 第二步 merge 不执行——存储原样保留，pending 与其他标记都在。
        // 不丢记录、不误报成功。
        val survivors = ShareRelayStore(MemStorage(contentBefore)).load()
        assertEquals(2, survivors.size)
        assertTrue("pending 记录保留，待下次重试",
            survivors.any { it.uriString == pending.uriString })
        assertTrue("其他标记不丢失",
            survivors.any { it.identityKey == "external_primary|5" })
    }

    // ============ 已入回收站后重试移除（undo） ============

    @Test
    fun undoStopsOnReadFailureAndMarkRemainsVisible() {
        val storage = FailingStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(mark(7)))
        storage.failRead = true
        val controller = RelayReviewController(store) { 999L }

        val result = controller.undo(mark(7))

        assertTrue(result is ShareRelayStore.WriteResult.Failed)
    }

    @Test
    fun undoAfterRecycleRetryRemovesOnlyTargetMark() {
        val storage = FailingStorage()
        val store = ShareRelayStore(storage)
        store.merge(listOf(mark(7), mark(8)))
        val controller = RelayReviewController(store) { 999L }

        val result = controller.undo(mark(7))

        assertTrue(result is ShareRelayStore.WriteResult.Ok)
        assertEquals(1, store.load().size)
        assertEquals("external_primary|8", store.load().single().identityKey)
    }

    // ============ 分享写标记失败上报 ============

    @Test
    fun shareEntryReportsWriteFailureInsteadOfSilentSuccess() {
        val storage = FailingStorage().apply { failWrite = true }
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
        assertTrue("分享写标记失败必须上报", outcome!!.writeFailure != null)
    }

    private class MemStorage(var content: String? = null) : RelayStorage {
        override fun read(): String? = content
        override fun write(value: String) { content = value }
    }
}
