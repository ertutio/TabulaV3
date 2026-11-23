package com.tabula.v3.data.relay

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #85 入口级测试：从真实系统分享 Intent 写入记录，再打开页面完成操作。
 *
 * 不再只调 RelayReviewRules，而是走生产 seam：
 * ShareRelayEntry.handle（Activity 用的同一入口）→ ShareRelayStore 落盘 →
 * RelayReviewController（页面用的同一 seam）→ 存储读回。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShareRelayEntryFlowTest {

    private lateinit var context: Context
    private lateinit var backingStorage: RelayStorage
    private lateinit var store: ShareRelayStore
    private lateinit var controller: RelayReviewController

    private fun photo(id: Long, volume: String = "external_primary", name: String = "p$id.jpg") = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = name,
        dateModified = 1_700_000_000_000L,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = "Camera",
        volumeName = volume
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        backingStorage = MemoryRelayStorage()
        store = ShareRelayStore(backingStorage)
        controller = RelayReviewController(store) { 9_999L }
    }

    private fun shareIntent(vararg uris: Uri): Intent {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
            }
        }
        return intent
    }

    @Test
    fun shareEntryPersistsConfirmedMarkThenPageShowsIt() {
        val image = photo(7)
        val outcome = ShareRelayEntry.handle(
            context, store, shareIntent(image.uri),
            localIdentityKeys = setOf("external_primary|7"),
            nowMillis = 500L
        )

        assertNotNull(outcome)
        assertEquals(1, outcome!!.resolution.confirmedCount)
        assertTrue("记录要真的落盘", store.load().isNotEmpty())

        val rows = controller.rows(listOf(image))
        assertEquals(1, rows.size)
        assertTrue(rows.single().isActionable)
        assertEquals(7L, rows.single().image?.id)
    }

    @Test
    fun transientUriBecomesPendingReviewAndPageNeverGuesses() {
        val tempUri = Uri.parse("content://com.android.shell/cache/screen_tmp.png")
        val outcome = ShareRelayEntry.handle(
            context, store, shareIntent(tempUri),
            localIdentityKeys = setOf("external_primary|7"),
            nowMillis = 500L
        )
        assertNotNull(outcome)
        assertEquals(1, outcome!!.resolution.reviewCount)
        assertTrue("转发仍然可用", outcome.resolution.hasForwardTargets)

        val rows = controller.rows(listOf(photo(7)))
        assertEquals(1, rows.size)
        assertNull("不能猜原图", rows.single().image)
        assertFalse(rows.single().isActionable)
    }

    @Test
    fun pageUndoRemovesMarkButKeepsPhoto() {
        val image = photo(7)
        ShareRelayEntry.handle(
            context, store, shareIntent(image.uri),
            localIdentityKeys = setOf("external_primary|7"), nowMillis = 500L
        )
        val mark = controller.rows(listOf(image)).single().mark

        controller.undo(mark)

        assertTrue("撤销后标记消失", store.load().isEmpty())
        assertEquals("照片不受影响", image.id, 7L)
    }

    @Test
    fun pageManualConfirmTurnsPendingIntoActionable() {
        val image = photo(8, name = "exactly.png")
        val tempUri = Uri.parse("content://com.android.shell/cache/tmp.png")
        ShareRelayEntry.handle(
            context, store, shareIntent(tempUri),
            localIdentityKeys = emptySet(), nowMillis = 500L
        )
        val pending = controller.rows(listOf(image)).single().mark
        assertEquals(RelayMarkState.PENDING_REVIEW, pending.state)

        controller.manualConfirm(pending, image)

        val rows = controller.rows(listOf(image))
        assertEquals(1, rows.size)
        assertTrue("手动确认后可操作", rows.single().isActionable)
        assertEquals("external_primary|8", rows.single().mark.identityKey)
    }

    @Test
    fun pageRecycleClearsMarkAfterRealRecycleBinWrite() {
        val image = photo(9)
        ShareRelayEntry.handle(
            context, store, shareIntent(image.uri),
            localIdentityKeys = setOf("external_primary|9"), nowMillis = 500L
        )
        val row = controller.rows(listOf(image)).single()

        val key = controller.recycle(row.mark)
        assertEquals("external_primary|9", key)
        controller.onRecycled(key!!)

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun multipleShareWritesOneMarkPerIdentity() {
        val image = photo(11)
        ShareRelayEntry.handle(
            context, store, shareIntent(image.uri, image.uri),
            localIdentityKeys = setOf("external_primary|11"), nowMillis = 500L
        )
        assertEquals(1, store.load().size)
    }

    @Test
    fun restartReadsMarksBackFromPersistence() {
        val image = photo(12)
        ShareRelayEntry.handle(
            context, store, shareIntent(image.uri),
            localIdentityKeys = setOf("external_primary|12"), nowMillis = 500L
        )
        val afterRestart = ShareRelayStore(backingStorage)
        val restartedController = RelayReviewController(afterRestart) { 9_999L }
        assertEquals(1, restartedController.rows(listOf(image)).size)
    }

    private class MemoryRelayStorage(var content: String? = null) : RelayStorage {
        override fun read(): String? = content
        override fun write(content: String) { this.content = content }
    }
}
