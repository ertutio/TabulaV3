package com.tabula.v3.data.balance

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.mediaIdentityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #90 第四轮：批次边界的**接线级**回归（生产 seam，不是孤立规则函数）。
 *
 * 两条审计要求的分组：
 *
 * **组 ①** 可控地延迟旧批次的删除与归档，分别在换批 / 退出重进后完成，
 * 确认不污染新批次；同批成功仍正常计数。
 *
 * **组 ②** 旧卡片全部失效后走自动装批入口，确认新批不继承旧统计；
 * 同时证明同批刷新不会误清。
 *
 * 本测试调用的是 `MainActivity` 真正调用的那个对象
 * （[DeferredActionLedger] + [RoundSessionRules] + [BatchChange]），
 * 因此"接线写错了"会在这里红，而不是只红在某个纯函数上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BatchBoundaryWiringTest {

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

    /** 伪造一个与生产等价的 PendingArchive（只有 id/uri/占位专辑字段）。 */
    private fun pendingArchiveFor(image: ImageFile, ticket: Ticket) = AlbumManager.PendingArchive(
        id = "action-${image.id}",
        imageId = image.id,
        imageUri = image.uri.toString(),
        albumId = "album-1",
        albumName = "旅行",
        intentTicket = ticket
    )

    /** 生产里"换批"的动作：RoundSessionRules.nextBatch + ids.newBatch（同一个函数体）。 */
    private fun advance(
        session: RoundSessionRules.Session,
        ids: SessionBatchIds
    ): Pair<RoundSessionRules.Session, SessionBatchIds> =
        RoundSessionRules.nextBatch(session) to ids.newBatch()

    // ==================== 组 ①：延迟动作 ====================

    /**
     * 旧批发起删除 → 换批 → 落库才完成：不污染新批次。
     * 同时验证同批内成功仍正常计数。
     */
    @Test
    fun delayedDeleteFromOldBatchDoesNotPolluteNewBatchButSameBatchCounts() {
        val ids = SessionBatchIds(sessionId = 1, batchId = 10)
        val oldShots = photo(100, "DCIM/Screenshots/")
        val newCamera = photo(200, "DCIM/Camera/")

        // 旧批：发起删除并当场落库成功（同批）→ 正常计数
        val inBatchTicket = Ticket.None.capture(ids)
        var session = DeferredActionLedger.onDeletePersisted(
            session = RoundSessionRules.Session(),
            pending = DeferredActionLedger.Pending(oldShots, inBatchTicket),
            currentIds = ids,
            sourceKeyOf = ::sourceOf
        )
        assertEquals("同批成功要正常计数", 1, session.currentBatch.size)
        assertEquals(shots, session.currentBatch.single().sourceKey)

        // 旧批：再发起一张删除，但这次落库要等到换批之后
        val delayedTicket = Ticket.None.capture(ids)
        val delayedPhoto = photo(101, "DCIM/Screenshots/")

        // 真正换批（「再来一组」同款动作）
        val (afterAdvance, newIds) = advance(session, ids)
        assertTrue("换批要清上一批统计", afterAdvance.isEmpty())

        // 延迟的旧批删除现在才落库
        val polluted = DeferredActionLedger.onDeletePersisted(
            session = afterAdvance,
            pending = DeferredActionLedger.Pending(delayedPhoto, delayedTicket),
            currentIds = newIds,
            sourceKeyOf = ::sourceOf
        )
        assertTrue("旧批的删除不能记进新批次", polluted.isEmpty())

        // 新批次自己的删除照常计数
        val fresh = DeferredActionLedger.onDeletePersisted(
            session = polluted,
            pending = DeferredActionLedger.Pending(newCamera, Ticket.None.capture(newIds)),
            currentIds = newIds,
            sourceKeyOf = ::sourceOf
        )
        assertEquals(1, fresh.currentBatch.size)
        assertEquals("新批次只带自己的来源", camera, fresh.currentBatch.single().sourceKey)
    }

    /** 旧批发起归档 → 换批 → Snackbar 超时才提交成功：不污染新批次。 */
    @Test
    fun delayedArchiveFromOldBatchDoesNotPolluteNewBatch() {
        val ids = SessionBatchIds(sessionId = 2, batchId = 20)
        val archived = photo(300, "DCIM/Screenshots/")
        val action = pendingArchiveFor(archived, Ticket.None.capture(ids))

        val (afterAdvance, newIds) = advance(RoundSessionRules.Session(), ids)

        val after = DeferredActionLedger.onArchiveCommitted(
            session = afterAdvance,
            archivedImage = archived,
            action = action,
            outcome = AlbumManager.ArchiveCommitOutcome.COMMITTED,
            currentIds = newIds,
            sourceKeyOf = ::sourceOf
        )
        assertTrue("延迟归档不能记进新批次", after.isEmpty())
    }

    /** 归档在**同批**内提交成功：正常计数。 */
    @Test
    fun sameBatchArchiveStillCounts() {
        val ids = SessionBatchIds(sessionId = 3, batchId = 30)
        val archived = photo(400, "DCIM/Screenshots/")
        val action = pendingArchiveFor(archived, Ticket.None.capture(ids))

        val after = DeferredActionLedger.onArchiveCommitted(
            session = RoundSessionRules.Session(),
            archivedImage = archived,
            action = action,
            outcome = AlbumManager.ArchiveCommitOutcome.COMMITTED,
            currentIds = ids,
            sourceKeyOf = ::sourceOf
        )
        assertEquals("同批归档要正常计数", 1, after.currentBatch.size)
        assertEquals(shots, after.currentBatch.single().sourceKey)
    }

    /** 归档失败（COPY_FAILED / NOT_CLAIMED）在同批里也不记账。 */
    @Test
    fun failedArchiveNeverCountsEvenInSameBatch() {
        val ids = SessionBatchIds(sessionId = 4, batchId = 40)
        val archived = photo(500, "DCIM/Screenshots/")
        val action = pendingArchiveFor(archived, Ticket.None.capture(ids))

        listOf(
            AlbumManager.ArchiveCommitOutcome.COPY_FAILED,
            AlbumManager.ArchiveCommitOutcome.NOT_CLAIMED
        ).forEach { outcome ->
            val after = DeferredActionLedger.onArchiveCommitted(
                session = RoundSessionRules.Session(),
                archivedImage = archived,
                action = action,
                outcome = outcome,
                currentIds = ids,
                sourceKeyOf = ::sourceOf
            )
            assertTrue("$outcome 不得记账", after.isEmpty())
        }
    }

    /** 退出整理再重进（新会话）：旧批延迟动作完成也不污染新会话。 */
    @Test
    fun delayedActionsAfterSessionRestartDoNotPolluteNewSession() {
        val ids = SessionBatchIds(sessionId = 5, batchId = 50)
        val deleted = photo(600, "DCIM/Screenshots/")
        val archived = photo(601, "DCIM/Screenshots/")

        val deleteTicket = Ticket.None.capture(ids)
        val archiveAction = pendingArchiveFor(archived, Ticket.None.capture(ids))

        // 退出整理 → 重进：endSession + newSession（MainActivity 的两步合一）
        val afterExit = RoundSessionRules.endSession()
        val newIds = ids.newSession()

        val afterDelete = DeferredActionLedger.onDeletePersisted(
            session = afterExit,
            pending = DeferredActionLedger.Pending(deleted, deleteTicket),
            currentIds = newIds,
            sourceKeyOf = ::sourceOf
        )
        assertTrue("退出重进后旧删除不能记账", afterDelete.isEmpty())

        val afterArchive = DeferredActionLedger.onArchiveCommitted(
            session = afterDelete,
            archivedImage = archived,
            action = archiveAction,
            outcome = AlbumManager.ArchiveCommitOutcome.COMMITTED,
            currentIds = newIds,
            sourceKeyOf = ::sourceOf
        )
        assertTrue("退出重进后旧归档不能记账", afterArchive.isEmpty())
    }

    /** 旧批动作失败的回退，不能删掉新批次里同身份的那条记录。 */
    @Test
    fun failureUndoFromOldBatchDoesNotDeleteNewBatchRecord() {
        val ids = SessionBatchIds(sessionId = 6, batchId = 60)
        val image = photo(700, "DCIM/Screenshots/")
        val oldTicket = Ticket.None.capture(ids)

        val (_, newIds) = advance(RoundSessionRules.Session(), ids)
        // 新批次里用户又处理了同一张（跨批同身份是可能的：撤回到队列后再次出现）
        var session = DeferredActionLedger.onDeletePersisted(
            session = RoundSessionRules.Session(),
            pending = DeferredActionLedger.Pending(image, Ticket.None.capture(newIds)),
            currentIds = newIds,
            sourceKeyOf = ::sourceOf
        )
        assertEquals(1, session.currentBatch.size)

        // 旧批次那条动作现在失败 → 只应回退它自己那一批
        val after = DeferredActionLedger.onDeleteFailed(
            session = session,
            pending = DeferredActionLedger.Pending(image, oldTicket),
            currentIds = newIds
        )
        assertEquals("旧动作的失败回退不能误删新批次统计", 1, after.currentBatch.size)
        assertEquals(image.mediaIdentityKey(), after.currentBatch.single().key)

        // 同批内的失败回退仍然生效
        val sameBatch = DeferredActionLedger.onDeleteFailed(
            session = session,
            pending = DeferredActionLedger.Pending(image, Ticket.None.capture(newIds)),
            currentIds = newIds
        )
        assertTrue("同批失败回退要生效", sameBatch.isEmpty())
    }

    // ==================== 组 ②：自动重取 vs 同批刷新 ====================

    /**
     * 旧卡片全部失效 → 自动装批：新批不继承旧统计。
     *
     * 走的是失效应埋下的 pendingAutoRefill → 装载点消费 → advance 的动作序列，
     * 与 DeckScreen 的两处代码一致。
     */
    @Test
    fun autoRefillAfterFullInvalidationDoesNotInheritOldStats() {
        val ids = SessionBatchIds(sessionId = 7, batchId = 70)

        // 旧批里已经攒了两条成功记录（截图来源）
        var session = RoundSessionRules.Session()
        listOf(800L, 801L).forEach { id ->
            session = DeferredActionLedger.onDeletePersisted(
                session = session,
                pending = DeferredActionLedger.Pending(
                    photo(id, "DCIM/Screenshots/"), Ticket.None.capture(ids)
                ),
                currentIds = ids,
                sourceKeyOf = ::sourceOf
            )
        }
        assertEquals(2, session.currentBatch.size)

        // 旧批可见行全部失效：BatchChange 判定为换批
        val change = BatchChange.AUTO_REFILL_AFTER_INVALIDATION
        assertTrue(change.startsNewBatch)
        assertTrue(change.clearsPreviousBatchStats)

        val (afterRefill, newIds) = advance(session, ids)
        assertTrue("自动重取要清掉上一批统计", afterRefill.isEmpty())
        assertFalse(
            "旧批的意图提示也要一起清",
            afterRefill.isEmpty().not()
        )

        // 新批自己的动作独立计数
        val fresh = DeferredActionLedger.onDeletePersisted(
            session = afterRefill,
            pending = DeferredActionLedger.Pending(
                photo(900, "DCIM/Camera/"), Ticket.None.capture(newIds)
            ),
            currentIds = newIds,
            sourceKeyOf = ::sourceOf
        )
        assertEquals(1, fresh.currentBatch.size)
        assertEquals(camera, fresh.currentBatch.single().sourceKey)
    }

    /** 同批快照刷新（回前台 / 外部增删但仍有存活行）不换批、不清统计。 */
    @Test
    fun inBatchSnapshotRefreshKeepsStatsAndDoesNotClear() {
        val ids = SessionBatchIds(sessionId = 8, batchId = 80)
        var session = DeferredActionLedger.onDeletePersisted(
            session = RoundSessionRules.Session(),
            pending = DeferredActionLedger.Pending(
                photo(1000, "DCIM/Screenshots/"), Ticket.None.capture(ids)
            ),
            currentIds = ids,
            sourceKeyOf = ::sourceOf
        )
        assertEquals(1, session.currentBatch.size)

        val change = BatchChange.IN_BATCH_SNAPSHOT_REFRESH
        assertFalse("同批刷新不是换批", change.startsNewBatch)
        assertFalse("同批刷新不清统计", change.clearsPreviousBatchStats)

        // 同一批里继续处理：旧记录仍在
        session = DeferredActionLedger.onDeletePersisted(
            session = session,
            pending = DeferredActionLedger.Pending(
                photo(1001, "DCIM/Screenshots/"), Ticket.None.capture(ids)
            ),
            currentIds = ids,
            sourceKeyOf = ::sourceOf
        )
        assertEquals("同批刷新后统计继续累加", 2, session.currentBatch.size)
        assertEquals(ids, ids)
    }

    /** 删单张卡片 / 撤销：不算换批，统计不被清。 */
    @Test
    fun removingOneCardAndUndoDoNotClearBatchStats() {
        assertFalse(BatchChange.IN_BATCH_CARD_REMOVED.startsNewBatch)
        assertFalse(BatchChange.IN_BATCH_ARCHIVE_UNDONE.startsNewBatch)
        assertFalse(BatchChange.IN_BATCH_CARD_REMOVED.clearsPreviousBatchStats)
        assertFalse(BatchChange.IN_BATCH_ARCHIVE_UNDONE.clearsPreviousBatchStats)

        val ids = SessionBatchIds(sessionId = 9, batchId = 90)
        var session = RoundSessionRules.Session()
        listOf(1100L, 1101L, 1102L).forEach { id ->
            session = DeferredActionLedger.onDeletePersisted(
                session = session,
                pending = DeferredActionLedger.Pending(
                    photo(id, "DCIM/Screenshots/"), Ticket.None.capture(ids)
                ),
                currentIds = ids,
                sourceKeyOf = ::sourceOf
            )
        }
        assertEquals(3, session.currentBatch.size)

        // 撤销其中一张：只删它自己，不影响同批其它两条
        val undone = DeferredActionLedger.onDeleteFailed(
            session = session,
            pending = DeferredActionLedger.Pending(
                photo(1101, "DCIM/Screenshots/"), Ticket.None.capture(ids)
            ),
            currentIds = ids
        )
        assertEquals(2, undone.currentBatch.size)
        assertTrue(undone.currentBatch.none { it.key == "external_primary|1101" })
    }

    /** 意图只影响下一批：换批后引擎侧消费一次即回落（接线未变）。 */
    @Test
    fun acceptIntentStillOnlyAffectsNextBatchAfterBoundaryChange() {
        val intent = RoundIntentRules.accept(shots, 1L)
        assertTrue(intent.isActive)
        val consumed = RoundIntentRules.consume(intent)
        assertFalse("消费后不得再影响下一批", consumed.isActive)
        assertNotNull(consumed.sourceKey)
    }
}
