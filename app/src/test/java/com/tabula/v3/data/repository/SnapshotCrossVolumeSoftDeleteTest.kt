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
    fun legacyNullVolumeRecordStillFiltersById() = runBlocking {
        // 旧数据兼容：卷未知的回收记录（T03 前序列化）仍按纯 ID 过滤。
        seedRow(10L, "external_primary", "DCIM/Camera/")
        val loaded = repository.loadAllData()
        val legacyRecord = loaded.allImages.first { it.id == 10L }.copy(volumeName = null)
        val projected = projectSnapshot(loaded, listOf(legacyRecord))
        assertTrue(projected.allImages.none { it.id == 10L })
    }
}
