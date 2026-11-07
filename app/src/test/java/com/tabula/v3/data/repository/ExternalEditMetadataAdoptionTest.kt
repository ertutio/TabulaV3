package com.tabula.v3.data.repository

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.tabula.v3.di.cardMemoryCacheKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * T07/TC32–35（审计轮）：外部编辑后应用采用最新元数据/内容版本——加载层验证。
 *
 * 外部编辑（另一应用改写照片后 MediaStore 重扫派生 DATE_MODIFIED）之后，
 * 应用的下一次加载（loadAllData，即 mediaAccessRevision 触发的整表刷新路径）
 * 必须采用新 DATE_MODIFIED：派生的 cardMemoryCacheKey 随内容版本变化（旧键
 * 不残留），且 DATE_MODIFIED_DESC 排序采纳（编辑行移动到最近项首位）。
 *
 * 范围声明（审计口径）：本测试钉住元数据/内容版本与排序采纳；实际图片像素
 * 刷新（显示层重新解码）与 PARTIAL 授权集合变化另行验证，不可执行处维持
 * blocked 登记（设备用例 ForegroundPermissionUiTest 已覆盖外部编辑回前台
 * 的元数据派生链）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ExternalEditMetadataAdoptionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val sourceUri = Uri.parse("content://media/external/images/media/10")
    private val sourceAliasUri = Uri.parse("content://media/external_primary/images/media/10?alias=source")
    private val sourceFilesCollectionAliasUri = Uri.parse("content://media/external/files/10")
    private val sourceBytes = ByteArray(257) { (it * 31).toByte() }
    private lateinit var sourceFile: File
    private lateinit var provider: EvidenceTestProvider
    private lateinit var repository: LocalImageRepository

    @Before
    fun setUp() {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Tabula/Target"
        ).listFiles()?.forEach { it.delete() }
        sourceFile = File(context.cacheDir, "synthetic-edit-source.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(sourceBytes)
        }
        provider = EvidenceTestProvider(context, sourceFile, sourceUri, sourceAliasUri, sourceFilesCollectionAliasUri)
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        repository = LocalImageRepository(context)
    }

    @After
    fun tearDown() {
        if (::provider.isInitialized) provider.cleanSyntheticFiles()
        if (::sourceFile.isInitialized) sourceFile.delete()
    }

    @Test
    fun externalEditAdoptsNewMetadataAndContentVersionOnNextLoad() = runBlocking {
        val before = repository.loadAllData().allImages.first { it.id == 10L }
        val keyBefore = cardMemoryCacheKey(before)
        assertEquals(1_700_000_000L * 1000, before.dateModified)

        // 外部编辑：另一应用改写内容后 MediaStore 重扫派生 DATE_MODIFIED 更新
        val editedSeconds = 1_700_000_000L + 5_000L
        assertEquals(
            1,
            provider.update(
                sourceUri,
                ContentValues().apply { put(MediaStore.Images.Media.DATE_MODIFIED, editedSeconds) },
                null,
                null
            )
        )

        val after = repository.loadAllData().allImages.first { it.id == 10L }
        assertEquals("下一次加载必须采用编辑后的新内容版本", editedSeconds * 1000, after.dateModified)
        val keyAfter = cardMemoryCacheKey(after)
        assertNotEquals("内容版本变化必须派生新缓存键", keyBefore, keyAfter)
        assertFalse(
            "旧内容版本键不得残留在加载结果中",
            repository.loadAllData().allImages.any { cardMemoryCacheKey(it) == keyBefore }
        )
    }

    @Test
    fun externalEditIsAdoptedByDateModifiedDescOrdering() = runBlocking {
        // 初始顺序：row 12（1_700_000_001）在前；row 10 编辑后成为最近项置顶
        assertEquals(12L, repository.loadAllData().allImages.first().id)

        provider.update(
            sourceUri,
            ContentValues().apply { put(MediaStore.Images.Media.DATE_MODIFIED, 1_700_000_000L + 5_000L) },
            null,
            null
        )

        assertEquals(10L, repository.loadAllData().allImages.first().id)
    }
}
