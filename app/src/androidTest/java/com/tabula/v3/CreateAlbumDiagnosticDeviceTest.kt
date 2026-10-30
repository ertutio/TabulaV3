package com.tabula.v3

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.SystemAlbumSyncManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.UUID

/**
 * O2 图集创建诊断（合成最小复现）：经真实入口在 API36/37 记录创建阶段、
 * 异常原文与 Provider/目录结果。诊断结论写入 logcat（CreateAlbumDiag 标签），
 * 由 CI 日志与工件保存；测试只断言路径一致的不变量与 API36 基线，不吞失败细节。
 *
 * - createBucketStageProbeReportsEachStep：先调用真实 createBucket（含
 *   getAlbumRelativePath 的 MediaStore/File 前置探测），再对独立唯一名逐阶段
 *   复现旧路径（insert pending → 写最小 JPEG → unpublish → delete），两路对照
 *   定位失败阶段。
 * - createAlbumRealEntryOutcomeReported：真实 createAlbum 入口；成功路径断言
 *   图集以 imageCount=0 可见，失败路径断言元数据无半提交残留（abort-before-save
 *   不变量）。
 */
@RunWith(AndroidJUnit4::class)
class CreateAlbumDiagnosticDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val fixtures get() = CloudFixtureCleanup(ctx, ins)
    private val albums get() = AlbumManager.getInstance(ctx)
    private val syncManager get() = SystemAlbumSyncManager.getInstance(ctx)
    private var probeAllowed = false
    private val registered = mutableListOf<CloudFixtureCleanup.Fixture>()
    private val dirsToClean = mutableListOf<String>()
    private val metadataAlbumsToClean = mutableListOf<String>()
    private val bucketName = "DiagBucket" + UUID.randomUUID().toString().replace("-", "").take(10)
    private val replicaName = "DiagReplica" + UUID.randomUUID().toString().replace("-", "").take(10)
    private val entryName = "DiagAlbum" + UUID.randomUUID().toString().replace("-", "").take(10)
    private val sentinelName = "DiagSentinel" + UUID.randomUUID().toString().replace("-", "").take(10)

    private fun shell(command: String): String = ins.uiAutomation.executeShellCommand(command).use { p ->
        FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun diag(stage: String, detail: String) {
        android.util.Log.i("CreateAlbumDiag", "device=$stage detail=$detail")
    }

    @Before fun setup() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        probeAllowed = true
        shell("pm grant ${ctx.packageName} ${Manifest.permission.READ_MEDIA_IMAGES}")
        assertEquals(PackageManager.PERMISSION_GRANTED, ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES))
        AppPreferences(ctx).apply { hasCompletedOnboarding = true; hasAcknowledgedDisclaimer = true }
        albums.initialize()
        diag("env", "sdk=${Build.VERSION.SDK_INT} fingerprint=${Build.FINGERPRINT}")
        Unit
    }

    /** createBucket 旧路径的最小 JPEG 占位字节（与 SystemAlbumSyncManager 相同）。 */
    private fun minimalJpeg(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        0xFF.toByte(), 0xD9.toByte()
    )

    private fun dirListing(relativePath: String): String =
        shell("ls /sdcard/$relativePath 2>&1").trim().replace('\n', '|')

    private fun dirExists(relativePath: String): Boolean =
        shell("test -d /sdcard/$relativePath && echo EXISTS || echo ABSENT").trim() == "EXISTS"

    @Test fun createBucketStageProbeReportsEachStep() = runBlocking {
        // 1) 真实 createBucket：包含 getAlbumRelativePath 前置探测 + 占位链。
        val realOutcome = try {
            val path = syncManager.createBucket(bucketName)
            diag("real-createBucket", if (path != null) "OK path=$path" else "NULL (no exception)")
            path
        } catch (e: Throwable) {
            diag("real-createBucket", "EXCEPTION ${e.javaClass.name}: ${e.message}")
            null
        }
        if (realOutcome != null) {
            dirsToClean += realOutcome.trimEnd('/')
            assertTrue(
                "created bucket path must be observable on device: $realOutcome",
                dirExists(realOutcome.trimEnd('/'))
            )
        } else {
            diag("real-createBucket-dir", "dirExists=${dirExists("Pictures/Tabula/$bucketName")}")
        }

        // 2) 逐阶段复现（独立唯一名），记录每步原始结果/异常。
        val replicaPath = "Pictures/Tabula/$replicaName"
        val stageFailures = mutableListOf<String>()
        var placeholderUri: Uri? = null
        try {
            placeholderUri = ctx.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, ".tabula_placeholder_diag_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$replicaPath/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
            diag("replica-insert", if (placeholderUri != null) "OK uri=$placeholderUri" else "NULL (no exception)")
            if (placeholderUri == null) stageFailures += "insert"
            if (placeholderUri != null) registered += fixtures.fixture(placeholderUri)

            if (placeholderUri != null) {
                val written = try {
                    ctx.contentResolver.openOutputStream(placeholderUri!!)?.use { output ->
                        output.write(minimalJpeg())
                        true
                    } ?: false
                } catch (e: Throwable) {
                    diag("replica-write", "EXCEPTION ${e.javaClass.name}: ${e.message}")
                    false
                }
                diag("replica-write", if (written) "OK" else "FAILED")
                if (!written) stageFailures += "write"

                val unpublished = try {
                    ctx.contentResolver.update(
                        placeholderUri!!, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
                    )
                } catch (e: Throwable) {
                    diag("replica-unpublish", "EXCEPTION ${e.javaClass.name}: ${e.message}")
                    -1
                }
                diag("replica-unpublish", "rows=$unpublished")
                if (unpublished != 1) stageFailures += "unpublish"

                val deleted = try {
                    ctx.contentResolver.delete(placeholderUri!!, null, null)
                } catch (e: Throwable) {
                    diag("replica-delete", "EXCEPTION ${e.javaClass.name}: ${e.message}")
                    -1
                }
                diag("replica-delete", "rows=$deleted")
                if (deleted != 1) stageFailures += "delete"
            }
        } finally {
            diag("replica-dir", "exists=${dirExists(replicaPath)} listing=${dirListing("Pictures/Tabula")}")
            if (dirExists(replicaPath)) dirsToClean += replicaPath
        }

        // 3) 对照结论：两路结果一致性（不一致记 WARN 供分析，不作为断言）。
        if (realOutcome == null && stageFailures.isEmpty()) {
            diag("MISMATCH", "real createBucket failed but every replicated stage succeeded")
        }
        if (realOutcome != null && stageFailures.isNotEmpty()) {
            diag("MISMATCH", "real createBucket succeeded but replicated stages failed: $stageFailures")
        }
        if (Build.VERSION.SDK_INT <= 36) {
            // API36 是已知可用基线：两路都必须成功。
            assertNotNull("API36 baseline: real createBucket must succeed", realOutcome)
            assertTrue(
                "API36 baseline: replicated chain must fully succeed, failures=$stageFailures",
                stageFailures.isEmpty()
            )
        }
    }

    @Test fun createAlbumRealEntryOutcomeReported() = runBlocking {
        // 真实入口 createAlbum：成功→图集可见；失败→异常原文记录且元数据无半提交。
        val album = try {
            albums.createAlbum(entryName)
            albums.albums.value.find { it.name == entryName }
        } catch (e: Throwable) {
            diag("createAlbum", "EXCEPTION ${e.javaClass.name}: ${e.message}")
            diag("createAlbum-stack", e.stackTraceToString().lineSequence().take(6).joinToString(" | "))
            null
        }
        if (album != null) {
            diag("createAlbum", "OK systemAlbumPath=${album.systemAlbumPath} imageCount=${album.imageCount}")
            assertEquals("created album must start empty", 0, album.imageCount)
            assertEquals(
                "created album path must follow the Tabula convention",
                "Pictures/Tabula/$entryName", album.systemAlbumPath?.trimEnd('/')
            )
            metadataAlbumsToClean += entryName
            if (album.systemAlbumPath != null) {
                val dir = album.systemAlbumPath!!.trimEnd('/')
                diag("createAlbum-dir", "exists=${dirExists(dir)}")
                if (dirExists(dir)) dirsToClean += dir
            }
        } else {
            diag("createAlbum-dir", "dirExists=${dirExists("Pictures/Tabula/$entryName")}")
            assertNull(
                "failed createAlbum must not leave metadata behind (abort-before-save)",
                albums.albums.value.find { it.name == entryName }
            )
        }
    }

    @After fun cleanup() = runBlocking {
        if (!probeAllowed) return@runBlocking
        val errors = mutableListOf<Throwable>()
        fun attempt(block: () -> Unit) { try { block() } catch (e: Throwable) { errors += e } }
        // 哨兵：与本诊断无关的独立合成媒体，验证清理动作不越界。
        var sentinel: CloudFixtureCleanup.Fixture? = null
        attempt {
            val item = ctx.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$sentinelName.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CloudSafetySentinels/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
            requireNotNull(item)
            ctx.contentResolver.openOutputStream(item)!!.use { it.write(minimalJpeg()) }
            ctx.contentResolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            sentinel = fixtures.fixture(item)
        }
        metadataAlbumsToClean.forEach { name ->
            try { albums.deleteAlbum(name) } catch (e: Throwable) { errors += e }
        }
        dirsToClean.distinct().forEach { dir ->
            attempt {
                shell("rmdir /sdcard/$dir")
                if (dirExists(dir)) diag("cleanup", "rmdir did not remove: $dir")
            }
        }
        registered.forEach { f -> attempt { fixtures.removeFixture(f) } }
        sentinel?.let { f ->
            attempt { check(fixtures.queryFixture(f)) { "Unrelated sentinel was removed" } }
            attempt { fixtures.removeFixture(f) }
        }
        if (errors.isNotEmpty()) {
            val failure = AssertionError("Fixture cleanup failed (${errors.size} errors)")
            errors.forEach { failure.addSuppressed(it) }
            throw failure
        }
        Unit
    }
}
