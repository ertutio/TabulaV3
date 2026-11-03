package com.tabula.v3.data.repository

import android.content.Context
import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * C4/T07 P2：前台刷新后，专清引擎的实际取批/断点恢复消费入口按最新快照
 * （完整媒体身份）校验——cachedGroups 的行对象停留在分析/恢复时刻，后续
 * 取批、重新预取、断点恢复不得返回已删除行或外部编辑前的旧版本对象。
 *
 * 夹具：真实 AlbumCleanupEngine + 真实 AppPreferences。组经分析结果持久化
 * 通道直接种子（与 analyzeAlbum 落盘同一组写入口：组目录 + 成员身份键），
 * getNextBatch 走真实 restoreGroupsFromPrefs 恢复路径；"后台变化"以刷新后
 * 的 allImages 参数表达（删除 = 行缺席、编辑 = 同身份新对象），组对象保持
 * 种子时刻——精确复现 in-memory cachedGroups 的陈旧行路径。
 *
 * 已知边界：DeckScreen 同步 effect 的空快照分支（isLoading 区分）为接线层，
 * 无 Compose 单测基建，由独立审计推演 + 设备套件覆盖（如实登记）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AlbumCleanupForegroundRefreshTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var preferences: AppPreferences
    private val albumId = "fg-refresh-album"

    @Before
    fun setUp() {
        preferences = AppPreferences.getInstance(context)
        preferences.clearAllAlbumCleanupState()
    }

    private fun row(id: Long, volume: String = "external_primary", dateModified: Long = 1_000L + id) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "img-$volume-$id.jpg",
        dateModified = dateModified,
        size = 100L,
        width = 64,
        height = 64,
        bucketDisplayName = "source",
        volumeName = volume,
        relativePath = "Pictures/source/"
    )

    /** 单成员组便于精确计数；大组用 11 行（> SMALL_GROUP_THRESHOLD，独占批次）。 */
    private fun groupIdOf(members: List<ImageFile>): String =
        SimilarGroup(images = members, startTime = members.minOf { it.dateModified }, endTime = members.maxOf { it.dateModified }).id

    /** 经 analyzeAlbum 同一组写入口种子持久化组（新格式身份键成员）。 */
    private fun seedGroups(
        albumRows: List<ImageFile>,
        groups: List<List<ImageFile>>,
        targetAlbumId: String = albumId
    ) {
        val ids = groups.map { groupIdOf(it) }
        preferences.saveAlbumAnalysisResult(targetAlbumId, groups.size, ids)
        preferences.saveGroupImageCounts(targetAlbumId, groups.associate { groupIdOf(it) to it.size })
        preferences.saveAlbumTotalImages(targetAlbumId, albumRows.size)
        preferences.saveGroupsImageIds(
            targetAlbumId,
            groups.associate { groupIdOf(it) to it.map { row -> row.mediaIdentityKey() } }
        )
    }

    private fun idOf(image: ImageFile) = image.mediaIdentityKey()

    @Test
    fun subsequentBatchesServeFreshRowsAfterExternalDeleteAndEdit() = runBlocking {
        // 三组尺寸互异（12/11/10 > SMALL_GROUP_THRESHOLD=10 各自独占批次；
        // 恢复按组大小降序排列，尺寸互异使顺序确定——组目录在 prefs 中是
        // StringSet 无序，不能依赖种子顺序）。
        val g1 = (1L..12L).map { row(it) }
        val g2 = (13L..23L).map { row(it) }
        val g3 = (24L..33L).map { row(it) }
        seedGroups(g1 + g2 + g3, listOf(g1, g2, g3))
        val engine = AlbumCleanupEngine(context)

        val batch1 = engine.getNextBatch(albumId, g1 + g2 + g3)
        assertEquals(listOf(groupIdOf(g1)), batch1!!.groupIds)
        engine.markGroupsProcessed(batch1.groupIds)

        // 后台变化：13 号被外部删除（刷新池缺席）、24 号被编辑（同身份新对象）
        val edited24 = row(24L, dateModified = 9_000L)
        val refreshed = g1 + (14L..23L).map { row(it) } + listOf(edited24) + (25L..33L).map { row(it) }

        val batch2 = engine.getNextBatch(albumId, refreshed)
        // G2 剪枝后 10 行 ≤ SMALL_GROUP_THRESHOLD：小组合并逻辑把 G3 并入
        // 同一批次（既有行为），交付行全部来自最新快照
        assertEquals(listOf(groupIdOf(g2), groupIdOf(g3)), batch2!!.groupIds)
        assertFalse("已删除的 13 号不得进入后续批次", batch2.images.any { it.id == 13L })
        assertEquals("组内剪枝后行数正确", 20, batch2.images.size)
        assertTrue("存活行必须是快照最新对象（被编辑的 24 号除外）", batch2.images.all { it.id == 24L || it.dateModified == 1_000L + it.id })
        assertSame("编辑后的 24 号必须是最新版本对象", edited24, batch2.images.first { it.id == 24L })
        engine.markGroupsProcessed(batch2.groupIds)

        assertNull("全部组处理后取批必须为 null", engine.getNextBatch(albumId, refreshed))
        assertEquals(0, engine.getRemainingGroups(albumId))
    }

    @Test
    fun fullyVanishedGroupIsMarkedProcessedAndSkipped() = runBlocking {
        val g1 = (1L..12L).map { row(it) }
        val g2 = listOf(row(13L), row(14L))
        val g3 = (15L..24L).map { row(it) }
        seedGroups(g1 + g2 + g3, listOf(g1, g2, g3))
        val engine = AlbumCleanupEngine(context)

        val batch1 = engine.getNextBatch(albumId, g1 + g2 + g3)
        engine.markGroupsProcessed(batch1!!.groupIds)
        val remainingBefore = engine.getRemainingGroups(albumId)

        // 后台：G2 两行全部外部删除（刷新池无 13/14 号）
        val refreshed = g1 + g3
        val batch2 = engine.getNextBatch(albumId, refreshed)

        assertFalse("整组失效的组不得进入批次", batch2!!.groupIds.contains(groupIdOf(g2)))
        assertTrue(batch2.groupIds.contains(groupIdOf(g3)))
        assertEquals("整组失效必须按完成语义标记（剩余组数如实递减）", remainingBefore - 1, engine.getRemainingGroups(albumId))
        engine.markGroupsProcessed(batch2.groupIds)
        assertNull(engine.getNextBatch(albumId, refreshed))
    }

    @Test
    fun checkpointRestoreServesUpdatedRowsAfterExternalEdit() = runBlocking {
        val g1 = (1L..11L).map { row(it) }
        seedGroups(g1, listOf(g1))
        val engine = AlbumCleanupEngine(context)

        val batch1 = engine.getNextBatch(albumId, g1)
        engine.saveCheckpoint(batch1!!.groupIds, 3)

        // 后台编辑断点批内 4 号（idx 3，同身份新对象）
        val edited = row(4L, dateModified = 9_000L)
        val refreshed = g1.map { if (it.id == 4L) edited else it }

        val restored = engine.getCheckpointBatch(albumId, refreshed)
        val (batch, index) = restored!!
        assertEquals("断点索引在行数不变时保持", 3, index)
        assertSame("恢复的行必须是编辑后最新对象", edited, batch.images[3])
        assertEquals(listOf(0), batch.groupBoundaries)
    }

    @Test
    fun checkpointRestoreAbandonsWhenCheckpointRowDeleted() = runBlocking {
        val g1 = (1L..11L).map { row(it) }
        seedGroups(g1, listOf(g1))
        val engine = AlbumCleanupEngine(context)

        val batch1 = engine.getNextBatch(albumId, g1)
        engine.saveCheckpoint(batch1!!.groupIds, 3)

        // 后台删除断点批内 4 号（idx 3）：组成变化 → 按既有语义放弃断点
        val refreshed = g1.filter { it.id != 4L }
        assertNull("断点行被删时必须放弃断点", engine.getCheckpointBatch(albumId, refreshed))
        assertNull("断点必须已清除", engine.getCheckpoint(albumId))

        // 重开后从正常取批入口继续：不返回已删除行
        val next = engine.getNextBatch(albumId, refreshed)
        assertEquals(10, next!!.images.size)
        assertFalse(next.images.any { it.id == 4L })
    }

    @Test
    fun crossVolumeSameIdRowsAreNotSubstitutedOrCollateralPruned() = runBlocking {
        val xAlbum = albumId + "-xvol"
        val b5 = row(5L, volume = "external_secondary")
        val b6 = row(6L, volume = "external_secondary")
        seedGroups(listOf(b5, b6), listOf(listOf(b5, b6)), targetAlbumId = xAlbum)
        val engine = AlbumCleanupEngine(context)

        val batch1 = engine.getNextBatch(xAlbum, listOf(b5, b6))
        assertEquals(listOf(b5, b6), batch1!!.images)

        // 后台：B 卷 5 号被删；A 卷出现同数值 ID 的新行——B5 不得被 A5 顶替，
        // 剩余 B6 照常交付且不混入 A 卷行
        val a5New = row(5L, volume = "external_primary", dateModified = 9_000L)
        val batch2 = engine.getNextBatch(xAlbum, listOf(a5New, b6))
        assertEquals(listOf(b6), batch2!!.images)
        assertFalse(batch2.images.any { it.volumeName == "external_primary" })

        // B 卷组整组失效（B5/B6 均删）而 A 卷同 ID 行存在：组按完成语义标记、
        // 不交付批次（A 卷同 ID 行不得误当作该组仍可操作）
        val batch3 = engine.getNextBatch(xAlbum, listOf(b5, b6))
        engine.markGroupsProcessed(batch3!!.groupIds)
        assertNull(engine.getNextBatch(xAlbum, listOf(a5New)))
    }

    @Test
    fun emptySnapshotMarksRemainingGroupsAndCompletes() = runBlocking {
        val g1 = (1L..11L).map { row(it) }
        val g2 = (12L..22L).map { row(it) }
        seedGroups(g1 + g2, listOf(g1, g2))
        val engine = AlbumCleanupEngine(context)

        val batch1 = engine.getNextBatch(albumId, g1 + g2)
        engine.markGroupsProcessed(batch1!!.groupIds)

        // 有效空快照（全部照片被删/权限收窄至空）：剩余组按完成语义标记，
        // 取批返回 null——不挂起、不返回旧行
        assertNull(engine.getNextBatch(albumId, emptyList()))
        assertEquals(0, engine.getRemainingGroups(albumId))
    }
}
