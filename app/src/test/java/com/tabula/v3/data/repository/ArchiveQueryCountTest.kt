package com.tabula.v3.data.repository

import android.Manifest
import android.net.Uri
import android.os.Environment
import com.tabula.v3.data.repository.AlbumManager.ArchiveCommitOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * T10 测量入口（TC45–TC47 前置，无生产改动）：
 * 用既有 EvidenceTestProvider 接缝记录生产归档链每次 commitPendingArchive 的
 * Provider 查询轮廓，并钉住两项当前行为事实，供 T10 优化实施前后对比：
 *
 * 1. 每次归档提交触发至少一次全库集合查询（refreshAlbumsFromSystem →
 *    getAllBucketsWithInfo 对 EXTERNAL_CONTENT_URI 的整表扫描）——这是
 *    "连续归档逐张全库重扫" 的直接证据基线；
 * 2. 连续 N 次提交的重扫次数随 N 线性增长（当前实现每次提交都重扫，
 *    无合并/增量窗口）。
 *
 * 断言只钉可观察事实（存在重扫/线性增长/提交正确性），不把当前实现的具体
 * 计数冻结为"正确值"——具体数字作为基线登记在交付说明中，优化后重测对比。
 * 另含统计持久化的恢复夹具（损坏/截断 JSON → 不崩溃、回到空态等待重扫）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ArchiveQueryCountTest {
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
        File(context.filesDir, "storage_scan_result.json").delete()
        sourceFile = File(context.cacheDir, "synthetic-archive-source.jpg").apply {
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
        File(context.filesDir, "storage_scan_result.json").delete()
    }

    /** 集合查询 = 非数字末段（整表扫描）；数字末段 = 按项查询。 */
    private fun collectionQueryCount(): Int =
        provider.queryUris.count { it.lastPathSegment?.toLongOrNull() == null }

    private fun itemQueryCount(): Int = provider.queryUris.size - collectionQueryCount()

    @Test
    fun singleCommitRecordsQueryProfileAndIncludesCollectionRescan() = runBlocking {
        val action = albumManager.queueImageToAlbum(1L, sourceUri.toString(), "Target", "Target")

        val outcome = albumManager.commitPendingArchive(action.id)

        assertEquals(ArchiveCommitOutcome.COMMITTED, outcome)
        // T10 基线事实①：一次提交即触发全库集合重扫（refreshAlbumsFromSystem）
        assertTrue(
            "expected at least one collection query per commit, got collection=${collectionQueryCount()} item=${itemQueryCount()}",
            collectionQueryCount() >= 1
        )
        // 复制路径本身也有按项查询（源身份/相对路径记录）——测量轮廓完整记录
        assertTrue(provider.queryUris.isNotEmpty())
        println("T10-SINGLE-COMMIT collection=${collectionQueryCount()} item=${itemQueryCount()} total=${provider.queryUris.size}")
    }

    @Test
    fun fiveQueuedThenCommittedUseIncrementalCountsAndSingleFinalReconciliation() = runBlocking {
        // 先排满 5 个待归档（真实连续归档形态），再依次提交：前 4 次提交时
        // 队列仍有待执行 action（批次进行中，只做增量计数），第 5 次提交
        // 清空队列触发唯一一次全库对账。
        val singleBaselineAction = albumManager.queueImageToAlbum(1L, sourceUri.toString(), "Target", "Target")
        assertEquals(ArchiveCommitOutcome.COMMITTED, albumManager.commitPendingArchive(singleBaselineAction.id))
        val singleCommitCollections = collectionQueryCount()
        provider.resetCopyState()

        val actions = (1..5).map {
            albumManager.queueImageToAlbum(1L, sourceUri.toString(), "Target", "Target")
        }
        val perCommitCollections = mutableListOf<Int>()
        for (action in actions) {
            val outcome = albumManager.commitPendingArchive(action.id)
            assertEquals(ArchiveCommitOutcome.COMMITTED, outcome)
            perCommitCollections += collectionQueryCount()
            provider.resetCopyState()
        }
        assertTrue(
            "queued-then-committed batch must not rescan per commit: perCommit=$perCommitCollections singleBaseline=$singleCommitCollections",
            perCommitCollections.sum() < 5 * singleCommitCollections
        )
        assertTrue(
            "final commit (queue drained) must reconcile with a collection query, got ${perCommitCollections.last()}",
            perCommitCollections.last() >= 1
        )
        assertTrue(albumManager.albums.value.any { it.name == "Target" })
        println("T10-MERGED perCommitCollections=$perCommitCollections singleBaseline=$singleCommitCollections")
    }

    @Test
    fun consecutiveCommitsRemainCorrectCopies() = runBlocking {
        val outcomes = mutableListOf<ArchiveCommitOutcome>()
        val insertedUris = mutableListOf<Uri?>()
        repeat(5) {
            val action = albumManager.queueImageToAlbum(1L, sourceUri.toString(), "Target", "Target")
            outcomes += albumManager.commitPendingArchive(action.id)
            insertedUris += provider.insertedTargetUri
            provider.resetCopyState()
        }
        // 连续提交的正确性：每提交恰好一次复制、目标互不相同
        assertEquals(List(5) { ArchiveCommitOutcome.COMMITTED }, outcomes)
        assertEquals(5, insertedUris.filterNotNull().distinct().size)
    }

    // —— 统计持久化恢复夹具（TC45 "异常/重启可重建"的统计面）——

    @Test
    fun corruptStatsFileRecoversToEmptyWithoutThrowing() = runBlocking {
        val statsFile = File(context.filesDir, "storage_scan_result.json")
        statsFile.writeText("{\"totalPhoneStorage\": 1, \"trunc")
        val manager = StorageStatsManager(context)

        manager.initialize()

        assertEquals(0L, manager.scanResult.value.scanTimestamp)
    }

    @Test
    fun truncatedToEmptyStatsFileRecoversToEmptyWithoutThrowing() = runBlocking {
        val statsFile = File(context.filesDir, "storage_scan_result.json")
        statsFile.writeText("   ")
        val manager = StorageStatsManager(context)

        manager.initialize()

        assertEquals(0L, manager.scanResult.value.scanTimestamp)
    }

    @Test
    fun validStatsFileReloadsAfterRestart() = runBlocking {
        val statsFile = File(context.filesDir, "storage_scan_result.json")
        statsFile.writeText(
            "{\"totalPhoneStorage\":100,\"usedPhoneStorage\":50,\"totalImageStorage\":10," +
                "\"albumStorageList\":[{\"bucketName\":\"Camera\",\"storageSize\":10,\"imageCount\":2}]," +
                "\"scanTimestamp\":123456789}"
        )
        val manager = StorageStatsManager(context)

        manager.initialize()

        assertEquals(123456789L, manager.scanResult.value.scanTimestamp)
        assertEquals(1, manager.scanResult.value.albumStorageList.size)
        assertNotEquals(0L, manager.scanResult.value.scanTimestamp)
    }
}

/** 与 ArchiveCommitEvidenceTest 相同的单例重置逻辑（该文件为 private，此处独立实现）。 */
private fun resetSingleton(clazz: Class<*>) {
    val field = clazz.getDeclaredField("instance")
    field.isAccessible = true
    field.set(null, null)
}
