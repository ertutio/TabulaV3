package com.tabula.v3.data.repository

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.io.IOException

/**
 * T07/TC56-RESULT：移动/复制逐项明确结果。
 *
 * 目标行为：系统以 SecurityException 拒绝的项必须归类为 NEEDS_PERMISSION 并与
 * 一般失败（0 行更新、IO 失败）分开计数；任何失败下源都保留；取消中止剩余请求。
 * 旧行为把两类失败折叠成同一个 retained/null，无法如实反馈。
 *
 * 夹具说明：LocalImageRepository 返回卷限定 URI（external_primary 形式），
 * 与 EvidenceTestProvider 预注册的 external 形式不同——这里为卷形式补注册
 * 源流供应商，使经 getAllImages 身份解析后的复制/读取走同一份源字节。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MoveCopyItemResultTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val sourceUri = Uri.parse("content://media/external/images/media/10")
    private val sourceAliasUri = Uri.parse("content://media/external_primary/images/media/10?alias=source")
    private val sourceFilesCollectionAliasUri = Uri.parse("content://media/external/files/10")
    private val sourceVolumeUri = Uri.parse("content://media/external_primary/images/media/10")
    private val rowTwelveVolumeUri = Uri.parse("content://media/external_primary/images/media/12")
    private val sourceBytes = ByteArray(257) { (it * 31).toByte() }
    private lateinit var sourceFile: File
    private lateinit var provider: EvidenceTestProvider
    private lateinit var syncManager: SystemAlbumSyncManager
    private lateinit var albumManager: AlbumManager

    @Before
    fun setUp() {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Tabula/Target"
        ).listFiles()?.forEach { it.delete() }
        sourceFile = File(context.cacheDir, "synthetic-transfer-source.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(sourceBytes)
        }
        provider = EvidenceTestProvider(context, sourceFile, sourceUri, sourceAliasUri, sourceFilesCollectionAliasUri)
        ShadowContentResolver.registerProviderInternal("media", provider)
        // 卷限定形式的源 URI（LocalImageRepository 身份解析的产物）指向同一份源字节
        shadowOf(context.contentResolver).registerInputStreamSupplier(sourceVolumeUri, {
            sourceFile.inputStream()
        })
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        val constructor = SystemAlbumSyncManager::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        syncManager = constructor.newInstance(context)
        albumManager = AlbumManager(context)
    }

    @After
    fun tearDown() {
        if (::provider.isInitialized) provider.cleanSyntheticFiles()
        if (::sourceFile.isInitialized) sourceFile.delete()
    }

    // ---------- 逐项移动分类（SystemAlbumSyncManager 接缝） ----------

    @Test
    fun moveUpdateThrowingSecurityExceptionClassifiesNeedsPermission() = runBlocking {
        provider.nonPublishUpdateExceptionFor = { SecurityException("synthetic permission denial") }

        val result = syncManager.moveImageToBucketOutcome(sourceUri, "Target")

        assertEquals(SystemAlbumSyncManager.MediaTransferOutcome.NEEDS_PERMISSION, result.outcome)
        assertNull(result.uri)
        assertTrue(sourceFile.exists())
    }

    @Test
    fun moveZeroRowUpdateClassifiesFailed() = runBlocking {
        provider.nonPublishUpdateResult = 0

        val result = syncManager.moveImageToBucketOutcome(sourceUri, "Target")

        assertEquals(SystemAlbumSyncManager.MediaTransferOutcome.FAILED, result.outcome)
        assertNull(result.uri)
        assertTrue(sourceFile.exists())
    }

    @Test
    fun moveGenericExceptionClassifiesFailedNotNeedsPermission() = runBlocking {
        provider.nonPublishUpdateExceptionFor = { IOException("synthetic io failure") }

        val result = syncManager.moveImageToBucketOutcome(sourceUri, "Target")

        assertEquals(SystemAlbumSyncManager.MediaTransferOutcome.FAILED, result.outcome)
        assertTrue(sourceFile.exists())
    }

    @Test
    fun moveSuccessReturnsSucceededWithSameItemUri() = runBlocking {
        val result = syncManager.moveImageToBucketOutcome(sourceUri, "Target")

        assertEquals(SystemAlbumSyncManager.MediaTransferOutcome.SUCCEEDED, result.outcome)
        assertEquals(sourceUri, result.uri)
        assertTrue(sourceFile.exists())
    }

    // ---------- 逐项复制分类（SystemAlbumSyncManager 接缝） ----------

    @Test
    fun copyInsertThrowingSecurityExceptionClassifiesNeedsPermission() = runBlocking {
        provider.insertException = SecurityException("synthetic permission denial")

        val result = syncManager.addImageToSystemAlbumOutcome(sourceUri, "Target")

        assertEquals(SystemAlbumSyncManager.MediaTransferOutcome.NEEDS_PERMISSION, result.outcome)
        assertNull(result.uri)
        // 授权拒绝下无新副本落地（row 12 是夹具预置的桶内既有行）
        assertEquals(0, provider.rows.values.count { it.id >= 100L })
    }

    @Test
    fun copyGenericFailureClassifiesFailed() = runBlocking {
        provider.failTargetWrite = true

        val result = syncManager.addImageToSystemAlbumOutcome(sourceUri, "Target")

        assertEquals(SystemAlbumSyncManager.MediaTransferOutcome.FAILED, result.outcome)
        assertNull(result.uri)
        assertSourceUnchanged()
    }

    @Test
    fun copySuccessReturnsSucceededWithTargetUri() = runBlocking {
        val result = syncManager.addImageToSystemAlbumOutcome(sourceUri, "Target")

        assertEquals(SystemAlbumSyncManager.MediaTransferOutcome.SUCCEEDED, result.outcome)
        assertNotNull(result.uri)
        assertNotEquals(sourceUri, result.uri)
        assertSourceUnchanged()
    }

    // ---------- 身份查询授权拒绝（T07/TC56-RESULT 审计轮） ----------

    @Test
    fun sourceIdentityQueryPermissionDenialClassifiesNeedsPermissionWithoutSideEffects() = runBlocking {
        // 源元数据查询被拒：原因保留并传播为 NEEDS_PERMISSION；未发起插入、
        // 源不删除、无任何清理副作用。
        provider.queryExceptionFor = { SecurityException("synthetic source query denial") }

        val result = syncManager.addImageToSystemAlbumOutcome(sourceUri, "Target")

        assertEquals(SystemAlbumSyncManager.MediaTransferOutcome.NEEDS_PERMISSION, result.outcome)
        assertNull(result.uri)
        assertEquals(0, provider.insertCalls)
        assertTrue(provider.deleteUris.isEmpty())
        assertSourceUnchanged()
    }

    @Test
    fun targetIdentityQueryPermissionDenialClassifiesNeedsPermissionAndLeavesTargetUntouched() = runBlocking {
        // 插入后目标身份查询被拒：目标所有权未知——不得擅自清理已插入行，
        // 源同样保留；原因传播为 NEEDS_PERMISSION。
        provider.queryExceptionFor = { uri ->
            if (uri.lastPathSegment?.toLongOrNull()?.let { it >= 100L } == true) {
                SecurityException("synthetic target query denial")
            } else null
        }

        val result = syncManager.addImageToSystemAlbumOutcome(sourceUri, "Target")

        assertEquals(SystemAlbumSyncManager.MediaTransferOutcome.NEEDS_PERMISSION, result.outcome)
        assertNull(result.uri)
        assertEquals(1, provider.insertCalls)
        // 所有权未证明的插入行保留（不清理）；源字节不变。
        assertTrue(provider.rows.values.any { it.id >= 100L })
        assertTrue(provider.deleteUris.isEmpty())
        assertSourceUnchanged()
    }

    @Test
    fun sourceIdentityQueryNonSecurityFailureKeepsFailedClassification() = runBlocking {
        // 对照：非授权类查询失败维持既有 FAILED 语义（不误判为权限拒绝）。
        provider.queryExceptionFor = { java.io.IOException("synthetic query failure") }

        val result = syncManager.addImageToSystemAlbumOutcome(sourceUri, "Target")

        assertEquals(SystemAlbumSyncManager.MediaTransferOutcome.FAILED, result.outcome)
        assertEquals(0, provider.insertCalls)
        assertTrue(provider.deleteUris.isEmpty())
        assertSourceUnchanged()
    }

    // ---------- AlbumManager 聚合（身份选择入口；行 10=源，12=桶内行） ----------

    @Test
    fun identityMoveAggregatesPermissionBlockedSeparatelyFromStaleSelection() = runBlocking {
        provider.nonPublishUpdateExceptionFor = { id ->
            if (id == 10L) SecurityException("synthetic permission denial") else null
        }

        val result = albumManager.moveImagesByIdentity(
            listOf("external_primary|10", "external_primary|12", "external_primary|999"),
            "Camera",
            "Target"
        )

        // 12 成功；10 授权拒绝；999 选择失效（不分类为失败）
        assertEquals(1, result.movedCount)
        assertEquals(2, result.retainedCount)
        assertEquals(1, result.permissionBlockedCount)
        assertEquals(0, result.failedCount)
        assertEquals(setOf(sourceVolumeUri, rowTwelveVolumeUri), provider.updateUris.toSet())
        assertNoCopyOrDelete()
    }

    @Test
    fun identityMoveAggregatesGenericFailureSeparatelyFromStaleSelection() = runBlocking {
        provider.nonPublishUpdateExceptionFor = { id ->
            if (id == 10L) IOException("synthetic io failure") else null
        }

        val result = albumManager.moveImagesByIdentity(
            listOf("external_primary|10", "external_primary|12", "external_primary|999"),
            "Camera",
            "Target"
        )

        assertEquals(1, result.movedCount)
        assertEquals(2, result.retainedCount)
        assertEquals(0, result.permissionBlockedCount)
        assertEquals(1, result.failedCount)
    }

    @Test
    fun identityMoveCancellationAfterItemStopsRequestingRemaining() = runBlocking {
        // 第 12 项的更新注入协程取消——取消必须中止剩余请求（排序其后的第 10 项不得再发起）
        provider.addTargetCandidate("extra.jpg", byteArrayOf(1, 2, 3), idOverride = 13L)
        provider.nonPublishUpdateExceptionFor = { id ->
            if (id == 12L) CancellationException("synthetic cancellation") else null
        }

        val reported = try {
            albumManager.moveImagesByIdentity(
                listOf("external_primary|10", "external_primary|12", "external_primary|13"),
                "Camera",
                "Target"
            )
            null
        } catch (expected: CancellationException) {
            expected
        }
        assertTrue(reported is CancellationException)
        val attemptedIds = provider.updateUris.mapNotNull { it.lastPathSegment?.toLongOrNull() }
        // 解析顺序按 DATE_MODIFIED 倒序：13(新) → 12 → 10；取消发生在 12，其后的 10 不再请求
        assertEquals(setOf(13L, 12L), attemptedIds.toSet())
        assertFalse(attemptedIds.contains(10L))
        assertTrue(provider.deleteUris.isEmpty())
    }

    @Test
    fun identityCopyAggregatesCopiedAndStaleSelection() = runBlocking {
        val result = albumManager.copyImagesByIdentity(
            listOf("external_primary|10", "external_primary|999"),
            "Target"
        )

        assertEquals(1, result.copiedCount)
        assertEquals(0, result.permissionBlockedCount)
        assertEquals(0, result.failedCount)
        assertEquals(2, result.requestedCount)
    }

    @Test
    fun identityCopyPermissionDeniedClassifiedNeedsPermission() = runBlocking {
        provider.insertException = SecurityException("synthetic permission denial")

        val result = albumManager.copyImagesByIdentity(
            listOf("external_primary|10"),
            "Target"
        )

        assertEquals(0, result.copiedCount)
        assertEquals(1, result.permissionBlockedCount)
        assertEquals(0, result.failedCount)
    }

    // ---------- 断言辅助 ----------

    private fun assertNoCopyOrDelete() {
        assertEquals(0, provider.insertCalls)
        assertTrue(provider.deleteUris.isEmpty())
    }

    private fun assertSourceUnchanged() {
        assertTrue(sourceFile.readBytes().contentEquals(sourceBytes))
    }
}
