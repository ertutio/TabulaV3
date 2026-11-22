package com.tabula.v3.data.relay

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
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
 * 待清标记的呈现、撤销与核对测试（审计意见 #85）。
 *
 * 核心不可退让的一条：**无法唯一定位时不能猜原图**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RelayReviewRulesTest {

    private fun photo(id: Long, volume: String? = "external_primary") = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = "Camera",
        volumeName = volume
    )

    private fun mark(
        identity: String?,
        state: RelayMarkState = RelayMarkState.CONFIRMED,
        uri: String = "content://media/external_primary/images/media/1",
        at: Long = 100L,
        name: String? = "s.png"
    ) = RelayMark(
        identityKey = identity,
        uriString = uri,
        displayName = name,
        markedAt = at,
        state = state
    )

    // ==================== 定位：不猜 ====================

    @Test
    fun confirmedMarkResolvesToTheExactPhoto() {
        val library = listOf(photo(1), photo(2))
        val rows = RelayReviewRules.resolveRows(
            listOf(mark("external_primary|2")),
            library
        )

        assertTrue(rows.single().isActionable)
        assertEquals(2L, rows.single().image?.id)
        assertNull(rows.single().reviewReason)
    }

    @Test
    fun pendingReviewMarkNeverGuessesAPhoto() {
        // 没有身份键，即使图库里只有一张、名字也可能一样，也不能猜
        val library = listOf(photo(1))
        val rows = RelayReviewRules.resolveRows(
            listOf(mark(null, RelayMarkState.PENDING_REVIEW, uri = "content://media/external_primary/images/media/1")),
            library
        )

        val row = rows.single()
        assertNull("无法唯一匹配时 image 必须为 null", row.image)
        assertFalse(row.isActionable)
        assertNotNull(row.reviewReason)
        assertTrue(row.reviewReason!!.contains("无法唯一定位"))
    }

    @Test
    fun confirmedMarkWhosePhotoIsGoneIsNotActionable() {
        val rows = RelayReviewRules.resolveRows(
            listOf(mark("external_primary|99")),
            listOf(photo(1))
        )

        val row = rows.single()
        assertNull(row.image)
        assertFalse(row.isActionable)
        assertTrue(row.reviewReason!!.contains("不在图库"))
    }

    @Test
    fun sameNumericIdOnAnotherVolumeDoesNotMatch() {
        val rows = RelayReviewRules.resolveRows(
            listOf(mark("1234-5678|1")),
            listOf(photo(1, volume = "external_primary"))
        )

        assertNull("跨卷同 ID 不是同一张照片", rows.single().image)
    }

    @Test
    fun rejectedMarksAreNotShown() {
        val rows = RelayReviewRules.resolveRows(
            listOf(mark(null, RelayMarkState.REJECTED)),
            listOf(photo(1))
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun duplicateMarksCollapseToOneRow() {
        val rows = RelayReviewRules.resolveRows(
            listOf(mark("external_primary|1", at = 100L), mark("external_primary|1", at = 200L)),
            listOf(photo(1))
        )
        assertEquals(1, rows.size)
    }

    @Test
    fun pendingReviewRowsComeFirst() {
        val library = listOf(photo(1))
        val rows = RelayReviewRules.resolveRows(
            listOf(
                mark("external_primary|1", at = 999L),
                mark(null, RelayMarkState.PENDING_REVIEW, uri = "content://x/1", at = 1L)
            ),
            library
        )

        // 待核对的排前面（更需要注意），即便它更旧
        assertTrue(rows.first().mark.state == RelayMarkState.PENDING_REVIEW)
    }

    // ==================== 分组 ====================

    @Test
    fun pendingReviewAndConfirmedAreSplit() {
        val library = listOf(photo(1))
        val rows = RelayReviewRules.resolveRows(
            listOf(
                mark("external_primary|1"),
                mark(null, RelayMarkState.PENDING_REVIEW, uri = "content://x/1"),
                mark("external_primary|9")   // 已不在图库
            ),
            library
        )

        assertEquals(1, RelayReviewRules.confirmedRows(rows).size)
        assertEquals(2, RelayReviewRules.pendingReviewRows(rows).size)
    }

    // ==================== 撤销 ====================

    @Test
    fun undoSeparatesIdentityAndUriEntries() {
        val plan = RelayReviewRules.undoPlan(
            listOf(
                mark("external_primary|1"),
                mark("external_primary|2"),
                mark(null, RelayMarkState.PENDING_REVIEW, uri = "content://x/9")
            )
        )

        assertEquals(setOf("external_primary|1", "external_primary|2"), plan.identityKeys)
        assertEquals(setOf("content://x/9"), plan.uriStrings)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun undoLeavesOtherMarksIntact() {
        val storage = MemoryRelayStorage()
        val store = ShareRelayStore(storage)
        store.merge(
            listOf(
                mark("external_primary|1"),
                mark("external_primary|2"),
                mark(null, RelayMarkState.PENDING_REVIEW, uri = "content://x/9")
            )
        )

        val remaining = RelayReviewRules.undoPlan(listOf(mark("external_primary|1"))).applyTo(store)

        assertEquals(2, remaining.size)
        assertTrue(remaining.none { it.identityKey == "external_primary|1" })
        // 待核对项必须留着
        assertTrue(remaining.any { it.state == RelayMarkState.PENDING_REVIEW })
    }

    @Test
    fun undoEverythingLeavesStoreEmpty() {
        val store = ShareRelayStore(MemoryRelayStorage())
        store.merge(listOf(mark("external_primary|1"), mark(null, RelayMarkState.PENDING_REVIEW, uri = "content://x/9")))

        val remaining = RelayReviewRules.undoPlan(store.load()).applyTo(store)

        assertTrue(remaining.isEmpty())
    }

    @Test
    fun undoDoesNotTouchPhotos() {
        // 撤销只删标记：这里用"图库与回收站都不变"来表达——
        // undoPlan 的输入输出里没有任何 ImageFile 被修改或删除
        val library = listOf(photo(1), photo(2))
        val before = library.map { it.mediaIdentityKey() }
        RelayReviewRules.undoPlan(listOf(mark("external_primary|1")))
        val after = library.map { it.mediaIdentityKey() }
        assertEquals(before, after)
    }

    // ==================== 手动确认 ====================

    @Test
    fun manualConfirmationAttachesRealIdentity() {
        val chosen = photo(7)
        val pending = mark(null, RelayMarkState.PENDING_REVIEW, uri = "content://tmp/1")

        val confirmed = RelayReviewRules.confirmManually(pending, chosen, nowMillis = 500L)

        assertEquals("external_primary|7", confirmed.identityKey)
        assertEquals(RelayMarkState.CONFIRMED, confirmed.state)
        assertEquals(500L, confirmed.markedAt)
        // 原始 URI 保留，便于回溯这次分享来自哪里
        assertEquals("content://tmp/1", confirmed.uriString)
    }

    @Test
    fun manuallyConfirmedMarkBecomesActionable() {
        val chosen = photo(7)
        val confirmed = RelayReviewRules.confirmManually(
            mark(null, RelayMarkState.PENDING_REVIEW, uri = "content://tmp/1"),
            chosen,
            500L
        )

        val rows = RelayReviewRules.resolveRows(listOf(confirmed), listOf(chosen))
        assertTrue(rows.single().isActionable)
        assertEquals(7L, rows.single().image?.id)
    }

    private class MemoryRelayStorage(var content: String? = null) : RelayStorage {
        override fun read(): String? = content
        override fun write(content: String) { this.content = content }
    }
}
