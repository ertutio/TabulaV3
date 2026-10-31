package com.tabula.v3.data.repository

import android.content.Context
import android.net.Uri
import com.tabula.v3.data.repository.LocalImageRepository.LoadResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * C1-T03-SNAPSHOT-CROSSVOLUME-DELETE：真实入口 loadAllData → projectSnapshot。
 * 回收站软删除按 (卷, 数值 ID) 消费身份——不同卷的同数值 ID 是不同照片，
 * 回收其中一张不得把另一卷的同 ID 行从浏览/待整理投影与桶计数中移除（TC13）。
 * 既有缺陷（ID-only 过滤早于 T03 字段），非本次引入。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SnapshotCrossVolumeSoftDeleteTest {
    private val app: android.app.Application get() = RuntimeEnvironment.getApplication()
    private val context: Context get() = app
    private lateinit var provider: EvidenceTestProvider
    private lateinit var repository: LocalImageRepository

    @Before
    fun setUp() {
        provider = EvidenceTestProvider(
            context,
            File(context.cacheDir, "snapshot-crossvolume-seed.jpg").apply { writeBytes(byteArrayOf(1)) },
            Uri.parse("content://media/external_primary/images/media/10"),
            Uri.parse("content://media/external_primary/images/media/10?alias=source"),
            Uri.parse("content://media/external_primary/files/10")
        )
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_IMAGES)
        provider.rows.clear()
        repository = LocalImageRepository(context)
    }

    private fun seedRow(id: Long, volume: String, path: String) {
        if (volume == "external_primary") {
            provider.rows[id] = EvidenceMediaRow(
                id = id,
                file = File(context.cacheDir, "snapshot-crossvolume-$volume-$id.jpg").apply { writeBytes(byteArrayOf(id.toByte())) },
                volumeName = volume,
                displayName = "row-$volume-$id.jpg",
                bucketName = "Camera",
                relativePath = path,
                dateModified = 1_700_000_000L + id
            )
        } else {
            // 第二卷行经 provider 的 additionalVolumeRows 登记；路径参数
            // 指向非 Tabula 目录，确保行进入待整理投影（Target 默认路径会被排除）。
            provider.addTargetCandidate(
                "row-$volume-$id.jpg",
                byteArrayOf(id.toByte()),
                idOverride = id,
                volumeName = volume,
                bucketName = "Camera",
                relativePath = path
            )
        }
    }

    @Test
    fun softDeleteOnOneVolumeKeepsSameIdRowOnOtherVolume() = runBlocking {
        // 同数值 ID：主卷 DCIM/Camera/10 与第二卷 DCIM/Camera/10 是不同照片；
        // 外加一卷各自的控制行 11/12，防止计数恰好相等掩盖串扰。
        seedRow(10L, "external_primary", "DCIM/Camera/")
        seedRow(11L, "external_primary", "DCIM/Camera/")
        seedRow(10L, "external_secondary", "DCIM/Camera/")
        seedRow(12L, "external_secondary", "DCIM/Camera/")

        val loaded = repository.loadAllData()
        assertEquals(
            "聚合查询必须同时返回两卷的同数值 ID 行",
            2, loaded.allImages.count { it.id == 10L }
        )

        // 真实软删除语义：回收站记录即 ImageFile（卷已知）。
        val recycled = loaded.allImages.first { it.id == 10L && it.volumeName == "external_primary" }
        val projected: LoadResult = projectSnapshot(loaded, listOf(recycled))

        // 断言实际保留对象，而非字段存在性：
        assertTrue(
            "另一卷的同数值 ID 行必须保留在浏览投影中",
            projected.allImages.any { it.id == 10L && it.volumeName == "external_secondary" }
        )
        assertTrue(
            "被回收那张必须从浏览投影移除",
            projected.allImages.none { it.id == 10L && it.volumeName == "external_primary" }
        )
        assertTrue(
            "另一卷的同数值 ID 行必须保留在待整理投影中",
            projected.imagesExcludingTabula.any { it.id == 10L && it.volumeName == "external_secondary" }
        )
        // 桶计数/封面按 (卷, 路径) 重算：第二卷桶不受主卷回收影响（10_sec 与 12_sec 都在）。
        // 封面=桶内日期序最新：addTargetCandidate 对 additional 行硬编码更早的
        // dateModified，故第二卷最新行是 10_sec。
        val secondaryBucket = projected.systemBuckets.first { it.volumeName == "external_secondary" }
        assertEquals(2, secondaryBucket.imageCount)
        assertEquals(10L, secondaryBucket.coverImageId)
        val primaryBucket = projected.systemBuckets.first { it.volumeName == "external_primary" }
        assertEquals(1, primaryBucket.imageCount)
        assertEquals(11L, primaryBucket.coverImageId)
    }

    @Test
    fun recycleBinKeepsCrossVolumeSameIdRecordsIndependently() = runBlocking {
        // C1-T03-SNAPSHOT-CROSSVOLUME-DELETE（回收站侧）：真实 RecycleBinManager
        // 去重/移除/匹配逻辑 + 临时存储。两卷同数值 ID 依次回收：每条记录独立
        // 持久化，重载后两行均被投影过滤；恢复一条只移除对应记录；重复回收幂等；
        // 旧记录兼容。存储层用 Memory 而非 Atomic：Windows JVM 上 AtomicFile
        // 第二次写入的 rename-over-existing 静默失败使回读校验必然抛出
        // （与既有 atomicReadRestoresLegacyBackup 本地失败同根因，CI Linux 绿），
        // 管理器去重/移除逻辑与存储无关，Atomic 层由 CI 与既有契约测试覆盖。
        seedRow(10L, "external_primary", "DCIM/Camera/")
        seedRow(12L, "external_primary", "DCIM/Camera/")
        seedRow(10L, "external_secondary", "DCIM/Camera/")
        val loaded = repository.loadAllData()
        val primary10 = loaded.allImages.first { it.id == 10L && it.volumeName == "external_primary" }
        val secondary10 = loaded.allImages.first { it.id == 10L && it.volumeName == "external_secondary" }

        val bin = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        bin.addAllToRecycleBin(listOf(primary10))

        // 第一次回收：一条记录；另一卷同 ID 行仍保留在投影（重载语义）。
        assertEquals(1, bin.loadRecycleBin().size)
        val projected1 = projectSnapshot(repository.loadAllData(), bin.loadRecycleBin())
        assertTrue(
            "回收主卷后另一卷同 ID 行必须保留在待整理投影",
            projected1.imagesExcludingTabula.any { it.id == 10L && it.volumeName == "external_secondary" }
        )

        // 第二次回收：旧行为按纯 ID 去重静默忽略（本用例的有效反例点）。
        bin.addAllToRecycleBin(listOf(secondary10))
        val records = bin.loadRecycleBin()
        assertEquals("两条不同卷记录必须独立持久化", 2, records.size)
        assertTrue(records.any { it.id == 10L && it.volumeName == "external_primary" })
        assertTrue(records.any { it.id == 10L && it.volumeName == "external_secondary" })

        // 重载后两行均被投影过滤（浏览与待整理一致）。
        val projected2 = projectSnapshot(repository.loadAllData(), records)
        assertTrue(projected2.imagesExcludingTabula.none { it.id == 10L })
        assertTrue(projected2.allImages.none { it.id == 10L })

        // 恢复主卷记录：只移除对应记录，另一卷记录留在回收站。
        bin.removeAllFromRecycleBin(listOf(records.first { it.volumeName == "external_primary" }))
        val afterRestore = bin.loadRecycleBin()
        assertEquals(1, afterRestore.size)
        assertEquals("external_secondary", afterRestore.single().volumeName)

        // 重复回收幂等：再次添加同卷同 ID 记录不新增条目。
        bin.addAllToRecycleBin(listOf(primary10))
        assertEquals(2, bin.loadRecycleBin().size)

        // 旧记录兼容：卷未知记录与同 ID 已知卷记录并存时按纯 ID 去重（T03 前语义）。
        bin.addAllToRecycleBin(listOf(primary10.copy(volumeName = null)))
        assertEquals(2, bin.loadRecycleBin().size)
    }

    @Test
    fun binDeletionUriDistinguishesCrossVolumeSameId() = runBlocking {
        // C1-T03-BINURI-DELETE：永久删除链（deleteRecycleBinBatch → beginDeletion →
        // request → finishDeletion）按记录 URI 消费身份。两卷同数值 ID 的行必须携带
        // 卷限定 item URI（真实身份），使日志、系统删除、核验与记录清除各自独立；
        // 聚合 URI 下两条记录共享同一 URI，物理删除一张会双删另一卷记录。
        seedRow(10L, "external_primary", "DCIM/Camera/")
        seedRow(10L, "external_secondary", "DCIM/Camera/")
        seedRow(11L, "external_primary", "DCIM/Camera/")
        val loaded = repository.loadAllData()
        val primary10 = loaded.allImages.first { it.id == 10L && it.volumeName == "external_primary" }
        val secondary10 = loaded.allImages.first { it.id == 10L && it.volumeName == "external_secondary" }

        // 反例前置断言：两条记录的 item URI 必须可区分（卷限定）。
        val recordUris = listOf(primary10, secondary10).map { it.uri }
        println("DIAG uris=$recordUris volumes=${listOf(primary10, secondary10).map { it.volumeName }}")
        assertEquals(
            "跨卷同数值 ID 行必须携带可区分的卷限定 URI uris=$recordUris volumes=${listOf(primary10, secondary10).map { it.volumeName }}",
            2, recordUris.distinct().size
        )
        assertTrue("主卷行必须携带主卷限定 URI", primary10.uri.toString().contains("external_primary"))
        assertTrue("第二卷行必须携带第二卷限定 URI", secondary10.uri.toString().contains("external_secondary"))

        val bin = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        bin.addAllToRecycleBin(listOf(primary10, secondary10))
        // 真实流程（deleteRecycleBinBatch）：把待删除记录的 URI 写入 journal 后交给系统。
        val deletionTargets = listOf(primary10.uri, secondary10.uri)
        assertEquals("journal 必须能区分两条跨卷同 ID 记录", 2, deletionTargets.distinct().size)
        bin.beginDeletion(deletionTargets)

        // 模拟系统只物理删除了主卷那张：deleted 集合以卷限定 URI 表达。
        bin.finishDeletion(deleted = setOf(primary10.uri))

        val remaining = bin.loadRecycleBin()
        assertEquals("物理删除主卷一张不得双删另一卷记录", 1, remaining.size)
        assertEquals(
            "剩余记录必须是第二卷那张",
            "external_secondary", remaining.single().volumeName
        )
        // journal 提交完成：unknown 为空且 pending 已清空。
        assertTrue(bin.pendingDeletionUris().isEmpty())

        // 对照钉死：旧聚合 URI 语义（两记录共享同一 URI）下同一流程会双删——
        // 这就是卷限定 URI 修复的动机。
        val aggregateUri = Uri.parse("content://media/external/images/media/10")
        val legacyBin = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        legacyBin.addAllToRecycleBin(
            listOf(
                primary10.copy(uri = aggregateUri),
                secondary10.copy(uri = aggregateUri)
            )
        )
        legacyBin.beginDeletion(listOf(aggregateUri))
        legacyBin.finishDeletion(deleted = setOf(aggregateUri))
        assertEquals("聚合 URI 下两条记录会被一并清除（修复前行为对照）", 0, legacyBin.loadRecycleBin().size)
    }

    @Test
    fun legacyNullVolumeRecordStillFiltersById() = runBlocking {
        // 旧数据兼容：卷未知的回收记录（T03 前序列化）仍按纯 ID 过滤。
        seedRow(10L, "external_primary", "DCIM/Camera/")
        val loaded = repository.loadAllData()
        val legacyRecord = loaded.allImages.first { it.id == 10L }.copy(volumeName = null)
        val projected = projectSnapshot(loaded, listOf(legacyRecord))
        assertTrue(projected.allImages.none { it.id == 10L })
    }
}
