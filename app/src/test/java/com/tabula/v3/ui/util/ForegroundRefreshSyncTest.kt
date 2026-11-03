package com.tabula.v3.ui.util

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.AlbumCleanupBatch
import com.tabula.v3.data.repository.mediaIdentityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C4/T07 P2 复审项：前台刷新新快照向可见消费面同步的纯逻辑。
 *
 * 覆盖：同身份外部编辑后的版本更新（dateModified/尺寸）；失效行剔除与
 * 顺序保持；跨卷同 ID 不误删不误换（卷|ID 键隔离）；清理批次的组边界
 * 重建/整组失效空段/完成标记 groupIds 语义；索引前移对齐；查看器身份
 * 失效关闭与存活更新。
 *
 * 证据边界：纯消费路径逻辑（DeckScreen 同步 effect 的内核）；Compose
 * 重组/实际滚动位置/设备端权限切换由审读与既有设备套件覆盖，不在此宣称。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ForegroundRefreshSyncTest {

    private fun row(volume: String, id: Long, dateModified: Long = 1_000L, width: Int = 64) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/$volume/images/media/$id"),
        displayName = "img-$volume-$id.jpg",
        dateModified = dateModified,
        size = 100L,
        width = width,
        height = 64,
        bucketDisplayName = "Camera",
        volumeName = volume
    )

    @Test
    fun survivorIsReplacedByLatestSnapshotRowAfterExternalEdit() {
        // 同身份外部编辑（dateModified/尺寸变化）：批次中的存活行必须更新为
        // 快照最新对象——不能继续携带编辑前的旧版本信息。
        val old = row("external_primary", 5L, dateModified = 1_000L, width = 64)
        val edited = row("external_primary", 5L, dateModified = 2_000L, width = 128)
        val batch = listOf(row("external_primary", 1L), old, row("external_primary", 7L))
        val snapshot = listOf(row("external_primary", 1L), edited, row("external_primary", 7L))

        val synced = syncRowsToSnapshot(batch, snapshot)

        assertEquals(3, synced.size)
        assertSame(edited, synced[1])
        assertEquals(2_000L, synced[1].dateModified)
        assertEquals(128, synced[1].width)
    }

    @Test
    fun vanishedRowsAreRemovedAndOrderIsPreserved() {
        val snapshot = listOf(row("external_primary", 7L), row("external_primary", 1L))
        val batch = listOf(row("external_primary", 1L), row("external_primary", 5L), row("external_primary", 7L))

        val synced = syncRowsToSnapshot(batch, snapshot)

        assertEquals(2, synced.size)
        assertEquals("external_primary|1", synced[0].mediaIdentityKey())
        assertEquals("external_primary|7", synced[1].mediaIdentityKey())
    }

    @Test
    fun crossVolumeSameIdRowsStayIndependent() {
        // 跨卷同数值 ID：批次中的 B 卷 5 号行不因 A 卷 5 号行存在于快照而
        // 被误保留（顶替/误换），也不因 A 卷行被编辑而被误更新；两行同时在
        // 快照中时各自映射到本卷最新行。
        val a5Old = row("external_primary", 5L, dateModified = 1_000L)
        val b5 = row("external_secondary", 5L, dateModified = 1_000L)
        val a5New = row("external_primary", 5L, dateModified = 3_000L)

        // 快照只有 A 卷 5 号：B 卷批次行必须被剔除（不回退裸 ID 匹配）
        val syncedOnlyA = syncRowsToSnapshot(listOf(b5), listOf(a5New))
        assertEquals(0, syncedOnlyA.size)

        // 快照两卷都有：各自映射本卷行
        val syncedBoth = syncRowsToSnapshot(listOf(a5Old, b5), listOf(a5New, b5))
        assertEquals(2, syncedBoth.size)
        assertSame(a5New, syncedBoth[0])
        assertSame(b5, syncedBoth[1])
    }

    @Test
    fun cleanupBatchSyncUpdatesSurvivorsDropsVanishedAndRebuildsBoundaries() {
        // 三组批次（边界 [0,2,4]）：第 0 组两行存活且其一被编辑；第 1 组两行
        // 全部失效（整组空段）；第 2 组首行失效次行存活。groupIds 与组序不变，
        // 边界按组重算，空段 start==end。
        val g1a = row("external_primary", 1L)
        val g1b = row("external_primary", 2L)
        val g2a = row("external_primary", 3L)
        val g2b = row("external_primary", 4L)
        val g3a = row("external_primary", 5L)
        val g3b = row("external_primary", 6L)
        val batch = AlbumCleanupBatch(
            images = listOf(g1a, g1b, g2a, g2b, g3a, g3b),
            groupIds = listOf("g1", "g2", "g3"),
            groupBoundaries = listOf(0, 2, 4)
        )
        // 新快照：1 号被编辑（版本更新）、3/4/5 号被外部删除、2/6 号存活
        val snapshot = listOf(
            row("external_primary", 1L, dateModified = 9_000L),
            g1b,
            g3b
        )

        val synced = syncCleanupBatchToSnapshot(batch, snapshot)

        assertEquals(listOf("g1", "g2", "g3"), synced.groupIds)
        // 组 1：1 号更新为最新版本、2 号存活
        assertEquals(9_000L, synced.images[0].dateModified)
        assertSame(g1b, synced.images[1])
        // 组 2：整组失效 → 空段
        assertEquals(2, synced.groupBoundaries[1])
        assertEquals(synced.groupBoundaries[2], synced.groupBoundaries[1])
        // 组 3：仅 6 号存活
        assertEquals(listOf(g3b), synced.images.subList(2, synced.images.size))
        // 边界与组对齐；整组失效收拢为 start==end 的空段（g2: [2,2)）
        assertEquals(batch.groupBoundaries.size, synced.groupBoundaries.size)
        assertEquals(listOf(0, 2, 2), synced.groupBoundaries)
        assertEquals(0, synced.groupBoundaries[0])
        // 组定位查找在剪枝后仍正确：索引 1 属于仍有 2 行存活的组 1；空段无索引映射
        assertEquals("g1", synced.getGroupIdForIndex(0))
        assertEquals("g1", synced.getGroupIdForIndex(1))
        assertEquals("g3", synced.getGroupIdForIndex(2))
    }

    @Test
    fun cleanupBatchSyncReturnsSameInstanceWhenNothingChanged() {
        val batch = AlbumCleanupBatch(
            images = listOf(row("external_primary", 1L), row("external_primary", 2L)),
            groupIds = listOf("g1"),
            groupBoundaries = listOf(0)
        )

        val synced = syncCleanupBatchToSnapshot(batch, listOf(row("external_primary", 1L), row("external_primary", 2L)))

        assertSame(batch, synced)
    }

    @Test
    fun removedCountBeforeCountsOnlyRowsBeforeCurrentIndex() {
        val original = listOf(
            row("external_primary", 1L),
            row("external_primary", 2L),
            row("external_primary", 3L),
            row("external_primary", 4L)
        )
        val snapshotKeys = setOf("external_primary|2", "external_primary|4") // 1/3 失效

        assertEquals(1, removedCountBefore(original, snapshotKeys, currentIndex = 2)) // 1 号失效在索引前
        assertEquals(2, removedCountBefore(original, snapshotKeys, currentIndex = 4)) // 1/3 都在索引前
        assertEquals(1, removedCountBefore(original, snapshotKeys, currentIndex = 1)) // 索引前仅 1 号且其已失效
        assertEquals(0, removedCountBefore(original, snapshotKeys, currentIndex = 0))
    }

    @Test
    fun viewerImageReturnsNullWhenIdentityGoneAndLatestRowWhenAlive() {
        val opened = row("external_primary", 5L, dateModified = 1_000L)
        val edited = row("external_primary", 5L, dateModified = 2_000L)
        val otherVolume = row("external_secondary", 5L, dateModified = 2_000L)

        // 身份已不存在（快照只有另一卷的同 ID 行）：关闭查看器，不误定位
        assertNull(refreshedViewerImage(opened, listOf(otherVolume)))
        // 身份仍存在：更新为最新对象（调用方 copy(image=...) 保持查看位置）
        assertSame(edited, refreshedViewerImage(opened, listOf(edited)))
        assertTrue(refreshedViewerImage(opened, listOf(row("external_primary", 5L)))!!.dateModified == 1_000L)
        assertFalse(refreshedViewerImage(opened, emptyList()) != null)
    }
}
