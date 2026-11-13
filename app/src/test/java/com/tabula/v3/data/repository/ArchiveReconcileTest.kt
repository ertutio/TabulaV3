package com.tabula.v3.data.repository

import android.Manifest
import android.net.Uri
import android.os.Environment
import com.tabula.v3.data.repository.AlbumManager.ArchiveCommitOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/**
 * T10 收尾回归（2026-11-09）：批次对账的队尾完成语义。
 *
 * 修复前：仅 COMMITTED 提交在队列清空时触发 reconcileAlbumsIfDirty——同批次
 * 内已有成功提交置下的脏标记，在"队尾复制失败"或"队尾撤销"两条出口上滞留，
 * 图集计数保持增量值直到下一次无关刷新。
 *
 * 夹具纪律：**不调用 provider.resetCopyState()**——已复制的目标行跨动作保留，
 * 累计状态不被每轮重置掩盖；断言的真实计数直接对照 provider 行真值
 * （Target bucket = 预置行 12 + 各次成功插入行）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ArchiveReconcileTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val sourceUri = Uri.parse("content://media/external/images/media/10")
    private val sourceAlias = Uri.parse("content://media/external_primary/images/media/10?alias=source")
    private val sourceFilesCollectionAlias = Uri.parse("content://media/external/files/10")
    private val sourceBytes = ByteArray(257) { (it * 31).toByte() }
    private lateinit var sourceFile: File
    private lateinit var provider: EvidenceTestProvider
    private lateinit var albumManager: AlbumManager

    @Before
    fun setUp() {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Tabula/Target")
            .listFiles()?.forEach { it.delete() }
        resetSingleton(AlbumManager::class.java)
        resetSingleton(SystemAlbumSyncManager::class.java)
        resetSingleton(AlbumUsageTracker::class.java)
        File(context.filesDir, "source_images.json").delete()
        File(context.filesDir, "copy_evidence.json").delete()
        sourceFile = File(context.cacheDir, "synthetic-reconcile-source.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(sourceBytes)
        }
        provider = EvidenceTestProvider(context, sourceFile, sourceUri, sourceAlias, sourceFilesCollectionAlias)
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        albumManager = AlbumManager(context)
    }

    @After
    fun tearDown() {
        if (::provider.isInitialized) provider.cleanSyntheticFiles()
        if (::sourceFile.isInitialized) sourceFile.delete()
    }

    private fun collectionQueryCount(): Int =
        provider.queryUris.count { it.lastPathSegment?.toLongOrNull() == null }

    private fun targetAlbumCount(): Int? =
        albumManager.albums.value.firstOrNull { it.name == "Target" }?.imageCount

    private fun awaitCollectionGrowth(before: Int, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (collectionQueryCount() > before) return true
            Thread.sleep(50)
        }
        return false
    }

    /**
     * 收尾时序修正（2026-11-09）：查询计数在 reconcile 发起查询时即增长，
     * 而 albums 状态在该查询完成后才落定——先等最终计数（与封面）就绪，
     * 再检查查询次数，消除"计数读到增量旧值"的竞态。
     */
    /**
     * 收尾判据修正（2026-11-09）：向 provider 真值注入一行额外 Target 行——使
     * "对账后真值计数" 与 "增量计数" 分叉（增量只反映已提交动作，额外行仅
     * 存在于 provider），从而 awaitTargetCount 的期望值成为对账完成的唯一
     * 可区分信号（增量计数在对账前即满足，不能作为完成判据）。
     */
    private fun addExtraTargetRow(uniqueDateModified: Long): Long {
        val uri = provider.addTargetCandidate(
            displayName = "extra-$uniqueDateModified.jpg",
            bytes = byteArrayOf(1, 2, 3, 4)
        )
        val id = uri.lastPathSegment!!.toLong()
        provider.rows[id]!!.dateModified = uniqueDateModified
        return id
    }

    private fun awaitTargetCount(expected: Int, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (targetAlbumCount() == expected) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun copyFailureAtQueueTailStillReconcilesPendingBatch() = runBlocking {
        // 先排 2 个（批次进行中），提交 A 成功（脏标记置下、队列未清空故不触发对账），
        // 队尾 B 复制失败——修复后失败出口也完成对账。
        val actions = (1..2).map {
            albumManager.queueImageToAlbum(1L, sourceUri.toString(), "Target", "Target")
        }
        assertEquals(ArchiveCommitOutcome.COMMITTED, albumManager.commitPendingArchive(actions[0].id))
        // 夹具纪律：不 resetCopyState——已复制目标行与游标跨动作保留
        val insertedId = provider.rows.keys.max()
        val extraId = addExtraTargetRow(1_700_000_010L)
        val beforeFailure = collectionQueryCount()

        provider.failSourceReadOpen = true
        val outcome = albumManager.commitPendingArchive(actions[1].id)
        provider.failSourceReadOpen = false

        assertEquals(ArchiveCommitOutcome.COPY_FAILED, outcome)
        // 先等最终计数落定（reconcile 完成 albums 更新），再检查查询次数
        assertTrue(
            "tail copy-failure must reconcile pending batch to provider truth",
            awaitTargetCount(3)
        )
        assertTrue(
            "tail copy-failure must reconcile the pending batch (collection queries grew)",
            awaitCollectionGrowth(beforeFailure)
        )
        // 真实计数对照 provider 行真值：预置行 12 + 成功插入 + 额外行 = 3
        // （增量计数止于 1——awaitTargetCount(3) 即对账完成信号）
        assertEquals("reconciled count must match provider truth (no phantom increment)",
            3, targetAlbumCount())
        assertTrue("extra provider row must be reflected by reconcile",
            provider.rows.containsKey(extraId))
    }

    @Test
    fun cancelAtQueueTailStillReconcilesPendingBatch() = runBlocking {
        val actions = (1..2).map {
            albumManager.queueImageToAlbum(1L, sourceUri.toString(), "Target", "Target")
        }
        assertEquals(ArchiveCommitOutcome.COMMITTED, albumManager.commitPendingArchive(actions[0].id))
        addExtraTargetRow(1_700_000_011L)
        val beforeCancel = collectionQueryCount()

        val cancelled = albumManager.cancelLastPendingArchive()

        assertEquals(actions[1].id, cancelled?.id)
        // 先等最终计数落定（异步对账完成 albums 更新），再检查查询次数
        assertTrue(
            "tail cancel must reconcile pending batch to provider truth",
            awaitTargetCount(3)
        )
        assertTrue(
            "tail cancel must reconcile the pending batch (async on manager scope)",
            awaitCollectionGrowth(beforeCancel)
        )
        assertEquals("reconciled count must match provider truth", 3, targetAlbumCount())
    }

    @Test
    fun coverAndOrderingSurviveTailReconcile() = runBlocking {
        val actions = (1..2).map {
            albumManager.queueImageToAlbum(1L, sourceUri.toString(), "Target", "Target")
        }
        assertEquals(ArchiveCommitOutcome.COMMITTED, albumManager.commitPendingArchive(actions[0].id))
        addExtraTargetRow(1_700_000_010L)
        val beforeCancel = collectionQueryCount()

        albumManager.cancelLastPendingArchive()
        // 先等第一次对账的最终计数与封面落定（真值 3 = 预置 12 + 插入 + 额外行；
        // 增量计数止于 1，故 3 是对账完成信号），再检查查询次数
        assertTrue(awaitTargetCount(3))
        assertTrue(awaitCollectionGrowth(beforeCancel))
        // 第一次对账后：计数对照真值；封面对照 provider 真值（复制保留源时间戳
        // 1_700_000_000 < 预置行 1_700_000_001 → 真值封面=预置行 12，动态推导）
        assertEquals(3, targetAlbumCount())
        val namesAfterFirst = albumManager.albums.value.map { it.name }
        assertTrue("Target must be present after reconcile", "Target" in namesAfterFirst)
        // 阶段1 封面对照当时真值（额外行 1_700_000_010 为最新）
        val cover1 = albumManager.albums.value.first { it.name == "Target" }.coverImageId
        assertEquals(1_700_000_010L, provider.rows[cover1]!!.dateModified)

        // 第二次对账（又一次队尾提交触发 drain）：排序跨对账稳定、计数推进、
        // 封面继续对照 provider 真值
        addExtraTargetRow(1_700_000_012L)
        val action3 = albumManager.queueImageToAlbum(1L, sourceUri.toString(), "Target", "Target")
        val beforeThird = collectionQueryCount()
        assertEquals(ArchiveCommitOutcome.COMMITTED, albumManager.commitPendingArchive(action3.id))
        // 先等第二次对账的最终计数落定（真值 5；增量在对账前已达 4，非完成信号），
        // 再检查查询次数与排序/封面
        assertTrue(awaitTargetCount(5))
        assertTrue(awaitCollectionGrowth(beforeThird))
        assertEquals(5, targetAlbumCount())
        assertEquals("album ordering must be stable across reconciles",
            namesAfterFirst, albumManager.albums.value.map { it.name })
        // 阶段2 封面对照当时真值（额外行 1_700_000_012 已成为最新——对账跟随真值更新）
        val expectedCover = provider.rows.values
            .filter { it.relativePath.trimEnd('/') == "Pictures/Tabula/Target" }
            .maxByOrNull { it.dateModified }!!
            .id
        assertEquals(expectedCover, albumManager.albums.value.first { it.name == "Target" }.coverImageId)
    }
}

private fun resetSingleton(clazz: Class<*>) {
    val field = clazz.getDeclaredField("instance")
    field.isAccessible = true
    field.set(null, null)
}
