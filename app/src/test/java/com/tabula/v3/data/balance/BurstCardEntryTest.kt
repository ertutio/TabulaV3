package com.tabula.v3.data.balance

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlin.random.Random
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
 * #90 真实卡片操作覆盖：保留、归档、删除、撤销、切换模式、接受意图、下一批消费。
 *
 * 用生产 seam ArchiveIdentityRules / RoundSessionRules / RoundIntentRules，
 * 模拟卡片页的操作序列，验证上一批记录不进入下一批判断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BurstCardEntryTest {

    private fun photo(id: Long, path: String, volume: String = "external_primary") = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "p$id.jpg",
        dateModified = 1_700_000_000_000L + id,
        size = 1000L,
        width = 1080,
        height = 1440,
        bucketDisplayName = path.trimEnd('/').substringAfterLast('/'),
        volumeName = volume,
        relativePath = path
    )

    private fun action(image: ImageFile, id: String = "act-" + image.id) =
        AlbumManager.PendingArchive(
            id = id,
            imageId = image.id,
            imageUri = image.uri.toString(),
            albumId = "album-1",
            albumName = "测试图集"
        )

    private fun shots() = (1L..10L).map { photo(it, "DCIM/Screenshots/") }
    private fun cameras() = (100L..200L).map { photo(it, "DCIM/Camera/") }
    private fun sourceOf(image: ImageFile) = SourceBalanceRules.sourceKeyOf(image)

    // ============ 归档用完整身份，不用 actionId ============

    @Test
    fun archiveUsesFullIdentityNotActionId() {
        val image = photo(42, "DCIM/Screenshots/")
        val a = action(image, id = "random-action-id-not-42")

        assertEquals("external_primary|42", ArchiveIdentityRules.identityKeyOf(a))
        assertEquals(42L, ArchiveIdentityRules.findInSnapshot(a, listOf(image))!!.id)
    }

    @Test
    fun crossVolumeSameIdDoesNotMismatch() {
        val primary = photo(7, "DCIM/Screenshots/", volume = "external_primary")
        val sd = photo(7, "DCIM/Screenshots/", volume = "1234-5678")
        val sdAction = action(sd, id = "act-sd") // 同数值 ID，不同卷

        val found = ArchiveIdentityRules.findInSnapshot(sdAction, listOf(primary))

        assertNull("不能把另一卷的同数值 ID 当成这次归档的照片", found)
    }

    @Test
    fun aggregateVolumeIsNotAnIdentity() {
        val aggregate = Uri.parse("content://media/external/images/media/9")
        val a = AlbumManager.PendingArchive(
            id = "act-9", imageId = 9L, imageUri = aggregate.toString(),
            albumId = "a", albumName = "a"
        )
        assertNull("聚合卷无法唯一定位，不记账", ArchiveIdentityRules.identityKeyOf(a))
    }

    @Test
    fun onlyCommittedOutcomeRecordsBehaviour() {
        assertTrue(ArchiveIdentityRules.shouldRecord(AlbumManager.ArchiveCommitOutcome.COMMITTED))
        assertFalse(ArchiveIdentityRules.shouldRecord(AlbumManager.ArchiveCommitOutcome.COPY_FAILED))
        assertFalse(ArchiveIdentityRules.shouldRecord(AlbumManager.ArchiveCommitOutcome.NOT_CLAIMED))
    }

    // ============ 真实卡片操作序列 ============

    @Test
    fun keepArchiveDeleteAllCountInSession() {
        var session = RoundSessionRules.Session()
        val shots = shots()
        // 保留
        session = RoundSessionRules.record(session, shots[0], RoundSessionRules.HandledAction.KEEP)
        // 归档（真实成功后）
        val archiveAction = action(shots[1])
        val archived = ArchiveIdentityRules.findInSnapshot(archiveAction, shots)
        if (ArchiveIdentityRules.shouldRecord(AlbumManager.ArchiveCommitOutcome.COMMITTED)) {
            archived?.let { session = RoundSessionRules.record(session, it, RoundSessionRules.HandledAction.ARCHIVE) }
        }
        // 删除
        session = RoundSessionRules.record(session, shots[2], RoundSessionRules.HandledAction.DELETE)

        assertEquals(3, session.currentBatch.size)
        assertEquals(
            setOf(
                RoundSessionRules.HandledAction.KEEP,
                RoundSessionRules.HandledAction.ARCHIVE,
                RoundSessionRules.HandledAction.DELETE
            ),
            session.currentBatch.map { it.action }.toSet()
        )
    }

    @Test
    fun undoRemovesOnlyThatPhoto() {
        var session = RoundSessionRules.Session()
        val shots = shots()
        session = RoundSessionRules.record(session, shots[0], RoundSessionRules.HandledAction.KEEP)
        session = RoundSessionRules.record(session, shots[1], RoundSessionRules.HandledAction.DELETE)

        session = RoundSessionRules.undo(session, shots[0])

        assertEquals(1, session.currentBatch.size)
        assertEquals(shots[1].mediaIdentityKey(), session.currentBatch.single().key)
    }

    @Test
    fun failedArchiveDoesNotEnterSession() {
        var session = RoundSessionRules.Session()
        val shots = shots()
        val a = action(shots[0])
        // 复制失败：不记账
        if (ArchiveIdentityRules.shouldRecord(AlbumManager.ArchiveCommitOutcome.COPY_FAILED)) {
            val found = ArchiveIdentityRules.findInSnapshot(a, shots)
            found?.let { session = RoundSessionRules.record(session, it, RoundSessionRules.HandledAction.ARCHIVE) }
        }
        assertTrue(session.isEmpty())
    }

    @Test
    fun previousBatchDoesNotEnterNextBatchJudgement() {
        var session = RoundSessionRules.Session()
        val shots = shots()
        listOf(1L, 2L, 3L, 4L).forEach {
            session = RoundSessionRules.record(session, shots.first { s -> s.id == it }, RoundSessionRules.HandledAction.DELETE)
        }
        assertNotNull(
            "上一批会触发提示",
            RoundSessionRules.shouldSuggest(
                session, ::sourceOf,
                (shots() + cameras())
            )
        )

        // 批次真正切换
        session = RoundSessionRules.nextBatch(session)

        assertTrue("上一批记录不能留在当前批次", session.isEmpty())
        assertEquals(1, session.completedBatches.size)
        assertNull(
            "上一批记录不能进入下一批判断",
            RoundSessionRules.shouldSuggest(
                session, ::sourceOf,
                (shots() + cameras())
            )
        )
    }

    @Test
    fun acceptIntentConsumedByNextBatchOnly() {
        var intent = RoundIntentRules.accept(sourceOf(shots()[0]), 1L)
        val all = shots() + cameras()
        val first = RoundIntentRules.sampleWithIntent(
            all, 6, intent, SourceBalanceRules.Strategy.UNIFORM, Random(1)
        )
        assertTrue(first.all { sourceOf(it) == sourceOf(shots()[0]) })

        intent = RoundIntentRules.consume(intent)
        val second = RoundIntentRules.sampleWithIntent(
            all, 6, intent, SourceBalanceRules.Strategy.UNIFORM, Random(1)
        )
        assertEquals(2, second.map(::sourceOf).toSet().size)
    }

    @Test
    fun modeSwitchIsolatesHandledStats() {
        var session = RoundSessionRules.Session()
        val shots = shots()
        listOf(1L, 2L, 3L).forEach {
            session = RoundSessionRules.record(session, shots.first { s -> s.id == it }, RoundSessionRules.HandledAction.KEEP)
        }
        session = RoundSessionRules.isolateForModeSwitch(session)
        assertTrue("切换专清/相似推荐后本轮统计清空", session.isEmpty())
    }

    @Test
    fun endSessionClearsEverything() {
        var session = RoundSessionRules.Session()
        session = RoundSessionRules.record(session, shots()[0], RoundSessionRules.HandledAction.KEEP)
        session = RoundSessionRules.endSession()
        assertTrue(session.isEmpty())
        assertTrue(session.completedBatches.isEmpty())
    }
}
