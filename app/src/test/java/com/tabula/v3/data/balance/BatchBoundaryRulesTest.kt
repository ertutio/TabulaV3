package com.tabula.v3.data.balance

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.mediaIdentityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #90 第四轮：批次边界与延迟动作记账（规则层）。
 *
 * 组 ①：旧批次的动作在换批 / 退出重进之后才完成，不能污染新批次；
 *       同批成功仍正常计数。
 *
 * 断言走真实的生产 seam（[RoundSessionRules.recordIfStillCurrent] /
 * [RoundSessionRules.undoIfStillCurrent] / [BatchChange]），不是为测试新写的
 * 影子函数。界面级端到端（真实延迟）见 DeckScreenBatchBoundaryTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BatchBoundaryRulesTest {

    private fun photo(id: Long, path: String) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/external_primary/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L + id,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = path.trimEnd('/').substringAfterLast('/'),
        volumeName = "external_primary",
        relativePath = path
    )

    private fun sourceOf(image: ImageFile) = SourceBalanceRules.sourceKeyOf(image)

    private val shots = "external_primary|DCIM/Screenshots/"
    private val camera = "external_primary|DCIM/Camera/"

    // ==================== 边界定义 ====================

    @Test
    fun onlyManualNextBatchAndAutoRefillStartANewBatch() {
        assertTrue(BatchChange.MANUAL_NEXT_BATCH.startsNewBatch)
        assertTrue(BatchChange.AUTO_REFILL_AFTER_INVALIDATION.startsNewBatch)

        assertFalse("删单张卡片不是换批", BatchChange.IN_BATCH_CARD_REMOVED.startsNewBatch)
        assertFalse("撤销不是换批", BatchChange.IN_BATCH_ARCHIVE_UNDONE.startsNewBatch)
        assertFalse("同批快照刷新不是换批", BatchChange.IN_BATCH_SNAPSHOT_REFRESH.startsNewBatch)
        assertFalse("首次装载不算换掉上一批", BatchChange.INITIAL_LOAD.startsNewBatch)

        assertTrue(BatchChange.MANUAL_NEXT_BATCH.clearsPreviousBatchStats)
        assertFalse(BatchChange.IN_BATCH_CARD_REMOVED.clearsPreviousBatchStats)
    }

    @Test
    fun sessionAndBatchIdsAdvanceIndependently() {
        val base = SessionBatchIds(sessionId = 1, batchId = 5)
        val nextBatch = base.newBatch()
        assertEquals("换批不动会话", 1, nextBatch.sessionId)
        assertEquals(6, nextBatch.batchId)

        val newSession = base.newSession()
        assertEquals(2, newSession.sessionId)
        assertNotEquals("新会话必须换批次标识", base.batchId, newSession.batchId)
    }

    @Test
    fun ticketCaptureIsStableAgainstLaterChanges() {
        val ids = SessionBatchIds(sessionId = 3, batchId = 7)
        val captured = Ticket.None.capture(ids)
        assertEquals(3, captured.sessionId)
        assertEquals(7, captured.batchId)

        // 捕获之后再换批，不会改掉已带走的那份
        val moved = ids.newBatch()
        assertTrue(captured.isStillCurrent(ids))
        assertFalse(captured.isStillCurrent(moved))
    }

    // ==================== 组 ①：延迟动作不能污染新批次 ====================

    @Test
    fun deleteInitiatedInOldBatchDoesNotPolluteNewBatch() {
        val ids = SessionBatchIds(sessionId = 1, batchId = 10)
        val oldPhoto = photo(100, "DCIM/Screenshots/")
        val newPhoto = photo(200, "DCIM/Camera/")

        // 旧批次里发起删除，带走当时的 ticket
        val ticket = Ticket.None.capture(ids)

        // 用户在旧批里成功删掉一张（同批，正常计数）
        var session = RoundSessionRules.Session()
        session = RoundSessionRules.recordIfStillCurrent(
            session, oldPhoto, RoundSessionRules.HandledAction.DELETE, ticket, ids, ::sourceOf
        )
        assertEquals("同批成功要正常计数", 1, session.currentBatch.size)
        assertEquals(shots, session.currentBatch.single().sourceKey)

        // 真正换批：统计清空、批次标识前进
        session = RoundSessionRules.nextBatch(session)
        val newIds = ids.newBatch()
        assertTrue(session.isEmpty())

        // 旧批的那条延迟动作现在才完成——它属于上一批
        val polluted = RoundSessionRules.recordIfStillCurrent(
            session, oldPhoto, RoundSessionRules.HandledAction.DELETE, ticket, newIds, ::sourceOf
        )
        assertTrue("旧批动作不能记进新批次", polluted.isEmpty())

        // 新批次自己的动作照常记账
        val freshTicket = Ticket.None.capture(newIds)
        val after = RoundSessionRules.recordIfStillCurrent(
            polluted, newPhoto, RoundSessionRules.HandledAction.DELETE, freshTicket, newIds, ::sourceOf
        )
        assertEquals(1, after.currentBatch.size)
        assertEquals("新批次只应带自己的来源", camera, after.currentBatch.single().sourceKey)
    }

    @Test
    fun archiveCompletedAfterBatchAdvanceDoesNotPolluteNewBatch() {
        val ids = SessionBatchIds(sessionId = 2, batchId = 20)
        val archived = photo(300, "DCIM/Screenshots/")
        val ticket = Ticket.None.capture(ids)

        // 换批（「再来一组」）
        var session = RoundSessionRules.nextBatch(RoundSessionRules.Session())
        val newIds = ids.newBatch()

        // 归档等到 Snackbar 超时才提交成功
        val after = RoundSessionRules.recordIfStillCurrent(
            session, archived, RoundSessionRules.HandledAction.ARCHIVE, ticket, newIds, ::sourceOf
        )
        assertTrue("延迟归档不能记进新批次", after.isEmpty())
    }

    @Test
    fun delayedActionAfterSessionRestartDoesNotPolluteNewSession() {
        val ids = SessionBatchIds(sessionId = 4, batchId = 30)
        val old = photo(400, "DCIM/Screenshots/")
        val ticket = Ticket.None.capture(ids)

        // 退出整理再重进：新会话 + 新批次
        val afterExit = RoundSessionRules.endSession()
        val newIds = ids.newSession()

        val after = RoundSessionRules.recordIfStillCurrent(
            afterExit, old, RoundSessionRules.HandledAction.DELETE, ticket, newIds, ::sourceOf
        )
        assertTrue("退出重进后旧动作不能记进新会话", after.isEmpty())
    }

    @Test
    fun failureAndUndoDoNotTouchNewBatchStats() {
        val ids = SessionBatchIds(sessionId = 5, batchId = 40)
        val image = photo(500, "DCIM/Screenshots/")
        val oldTicket = Ticket.None.capture(ids)

        // 新批次里已经有一张同身份的成功记录（用户在新批里又处理了它一次）
        val newIds = ids.newBatch()
        var session = RoundSessionRules.recordIfStillCurrent(
            RoundSessionRules.Session(), image, RoundSessionRules.HandledAction.DELETE,
            Ticket.None.capture(newIds), newIds, ::sourceOf
        )
        assertEquals(1, session.currentBatch.size)

        // 旧批次那条动作失败 → 它的回退绝不能删掉新批次的记录
        val afterUndo = RoundSessionRules.undoIfStillCurrent(session, image, oldTicket, newIds)
        assertEquals(
            "换批后旧动作的失败回退不能误动新批次统计",
            1, afterUndo.currentBatch.size
        )
        assertEquals(image.mediaIdentityKey(), afterUndo.currentBatch.single().key)

        // 同批内的失败回退仍然正常生效
        val sameBatchUndo = RoundSessionRules.undoIfStillCurrent(
            session, image, Ticket.None.capture(newIds), newIds
        )
        assertTrue("同批失败回退要生效", sameBatchUndo.isEmpty())
    }

    @Test
    fun inBatchSnapshotRefreshKeepsStatsAndIdentity() {
        val ids = SessionBatchIds(sessionId = 6, batchId = 50)
        val image = photo(600, "DCIM/Screenshots/")
        val ticket = Ticket.None.capture(ids)

        var session = RoundSessionRules.recordIfStillCurrent(
            RoundSessionRules.Session(), image, RoundSessionRules.HandledAction.KEEP,
            ticket, ids, ::sourceOf
        )
        // 同批快照刷新：不换批、不换标识
        assertFalse(BatchChange.IN_BATCH_SNAPSHOT_REFRESH.startsNewBatch)
        val refreshedIds = ids
        val after = RoundSessionRules.recordIfStillCurrent(
            session, photo(601, "DCIM/Screenshots/"), RoundSessionRules.HandledAction.KEEP,
            ticket, refreshedIds, ::sourceOf
        )
        assertEquals("同批刷新不该清掉统计", 2, after.currentBatch.size)
        session = after
        assertTrue(BatchChange.IN_BATCH_SNAPSHOT_REFRESH.clearsPreviousBatchStats.not())
        assertEquals(2, session.currentBatch.size)
    }

    @Test
    fun expiredTicketNeverMatchesAFreshlyStartedSession() {
        // 进程重启 / 首次进入：ids 从 0 开始
        val fresh = SessionBatchIds()
        assertFalse(
            "未捕获的占位 ticket 不与任何真实会话相等",
            Ticket.None.isStillCurrent(fresh)
        )
        val captured = Ticket.None.capture(fresh)
        assertTrue(captured.isStillCurrent(fresh))
    }
}
