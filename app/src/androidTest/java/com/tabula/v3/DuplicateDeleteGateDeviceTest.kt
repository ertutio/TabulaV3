package com.tabula.v3

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.duplicate.ContentDigestCalculator
import com.tabula.v3.data.duplicate.DuplicateReverify
import com.tabula.v3.data.duplicate.DuplicateRules
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * #88 审计：**真实回收站删除入口前的复核链路**。
 *
 * 场景：回收站里有一张照片，提示"别处还有副本" → 外部修改/删除副本 →
 * 用户点删除 → 删除必须被**停止**、提示必须**变化**、用户可重新决定。
 *
 * 与 MainActivity.deleteRecycleBinImages 的接线一致：
 *   1. 进回收站的照片算摘要 → duplicateDetails 里"还剩副本"的提示
 *   2. 删除前 `reverifyDuplicatesBeforeDeletion` → 重算摘要 + verifyAll
 *   3. shouldStop == true → 中止删除，invalidatedKeys 从提示里摘掉
 *
 * **权限撤销**：pm revoke 会杀进程（#88 已实测两次挂起），
 * 该项保留为真实设备待验，本文件不做、也不把"注入 null 摘要"写成
 * "权限撤销已验证"。
 */
@RunWith(AndroidJUnit4::class)
class DuplicateDeleteGateDeviceTest {

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private lateinit var prefs: AppPreferences
    private val inserted = mutableListOf<Uri>()

    @Before
    fun setUp() {
        prefs = AppPreferences(ctx)
        prefs.hasCompletedOnboarding = true
        prefs.hasAcknowledgedDisclaimer = true
        grantMedia()
    }

    @After
    fun cleanUp(): Unit = runBlocking {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() }
    }

    private fun grantMedia() {
        listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        ).forEach { perm -> runCatching { shell("pm grant ${ctx.packageName} $perm") } }
    }

    private fun shell(cmd: String): String = ins.uiAutomation.executeShellCommand(cmd).use { p ->
        java.io.FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** 写入确定字节的照片；同 seed = 完全相同的文件内容。 */
    private fun insertPhoto(seed: Int): Uri {
        val name = "dg-" + UUID.randomUUID().toString().take(8) + ".jpg"
        val uri = requireNotNull(
            ctx.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DCIM/Camera/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
        )
        inserted += uri
        val bytes = ByteArray(2048) { i -> ((seed shr ((i % 4) * 8)) xor i).toByte() }
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        check(
            ctx.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
            ) == 1
        )
        return uri
    }

    private suspend fun librarySuspend(): List<ImageFile> =
        com.tabula.v3.data.repository.LocalImageRepository(ctx).loadAllData().allImages

    private suspend fun awaitImageOf(uri: Uri, timeoutMs: Long = 20000): ImageFile {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < end) {
            librarySuspend().firstOrNull { it.uri == uri }?.let { return it }
            kotlinx.coroutines.delay(250)
        }
        error("照片未被图库索引：$uri")
    }

    // ==================== 用例 ====================

    @Test
    fun externallyModifiedCopyStopsDeletionAndUpdatesHint() = runBlocking {
        val seed = 0x5A5A1234
        val mainUri = insertPhoto(seed)
        val copyUri = insertPhoto(seed)
        val main = awaitImageOf(mainUri)
        val copy = awaitImageOf(copyUri)

        // 走真实回收站：主图进站，副本仍在图库
        val bin = RecycleBinManager.getInstance(ctx)
        bin.addAllToRecycleBin(listOf(main))
        val inBin = bin.loadRecycleBin().first { it.mediaIdentityKey() == main.mediaIdentityKey() }

        // 扫描出提示（与 MainActivity 的 duplicateDetails 同源）
        val calc = ContentDigestCalculator(ctx.contentResolver)
        val before = calc.digestAll(listOf(inBin, copy))
        val recycledKeys = setOf(inBin.mediaIdentityKey())
        val details = DuplicateRules.findExactDuplicates(before, recycledKeys)
            .associate { it.image.mediaIdentityKey() to it.duplicates }
        assertTrue("应扫描出'还有副本'的提示", details.containsKey(inBin.mediaIdentityKey()))

        // 外部修改副本内容（换成不同字节）
        ctx.contentResolver.openOutputStream(copy.uri, "wt")!!.use {
            it.write(ByteArray(2048) { i -> ((0x1234ABCD shr ((i % 4) * 8)) xor i).toByte() })
        }

        // 删除前复核：重算摘要 + verifyAll（与 MainActivity 同一 seam）
        val after = calc.digestAll(listOf(inBin, awaitImageOf(copyUri)))
        val verdict = DuplicateReverify.verifyAll(
            toDelete = listOf(inBin),
            details = details,
            libraryKeys = librarySuspend().map { it.mediaIdentityKey() }.toSet(),
            digestOf = { after[it] }
        )

        assertTrue("副本被外部修改后必须停止删除", verdict.shouldStop)
        assertTrue(
            "该提示必须被判为失效（提示发生变化，用户可重新决定）",
            verdict.invalidatedKeys.contains(inBin.mediaIdentityKey())
        )
    }

    @Test
    fun externallyDeletedCopyStopsDeletion() = runBlocking {
        val seed = 0x5A5A5678
        val mainUri = insertPhoto(seed)
        val copyUri = insertPhoto(seed)
        val main = awaitImageOf(mainUri)
        val copy = awaitImageOf(copyUri)

        val bin = RecycleBinManager.getInstance(ctx)
        bin.addAllToRecycleBin(listOf(main))
        val inBin = bin.loadRecycleBin().first { it.mediaIdentityKey() == main.mediaIdentityKey() }

        val calc = ContentDigestCalculator(ctx.contentResolver)
        val before = calc.digestAll(listOf(inBin, copy))
        val details = DuplicateRules.findExactDuplicates(
            before, setOf(inBin.mediaIdentityKey())
        ).associate { it.image.mediaIdentityKey() to it.duplicates }
        assertTrue("初始应有副本提示", details.containsKey(inBin.mediaIdentityKey()))

        // 外部删除副本
        ctx.contentResolver.delete(copy.uri, null, null)

        val after = calc.digestAll(listOf(inBin))
        val verdict = DuplicateReverify.verifyAll(
            toDelete = listOf(inBin),
            details = details,
            libraryKeys = librarySuspend().map { it.mediaIdentityKey() }.toSet(),
            digestOf = { after[it] }
        )

        assertTrue("副本被外部删除后必须停止删除", verdict.shouldStop)
        assertTrue(
            "提示必须变为失效（用户看到变化后重新决定）",
            verdict.invalidatedKeys.contains(inBin.mediaIdentityKey())
        )
    }

    @Test
    fun unchangedCopyAllowsDeletion() = runBlocking {
        val seed = 0x5A5A9ABC
        val mainUri = insertPhoto(seed)
        val copyUri = insertPhoto(seed)
        val main = awaitImageOf(mainUri)
        val copy = awaitImageOf(copyUri)

        val bin = RecycleBinManager.getInstance(ctx)
        bin.addAllToRecycleBin(listOf(main))
        val inBin = bin.loadRecycleBin().first { it.mediaIdentityKey() == main.mediaIdentityKey() }

        val calc = ContentDigestCalculator(ctx.contentResolver)
        val versions = calc.digestAll(listOf(inBin, copy))
        val details = DuplicateRules.findExactDuplicates(
            versions, setOf(inBin.mediaIdentityKey())
        ).associate { it.image.mediaIdentityKey() to it.duplicates }

        val verdict = DuplicateReverify.verifyAll(
            toDelete = listOf(inBin),
            details = details,
            libraryKeys = librarySuspend().map { it.mediaIdentityKey() }.toSet(),
            digestOf = { versions[it] }
        )

        assertFalse("副本未变化时应允许删除", verdict.shouldStop)
        assertTrue("不应误报提示失效", verdict.invalidatedKeys.isEmpty())
    }

    @Test
    fun deletingBothCopiesStopsDeletion() = runBlocking {
        val seed = 0x5A5ADEF0
        val mainUri = insertPhoto(seed)
        val copyUri = insertPhoto(seed)
        val main = awaitImageOf(mainUri)
        val copy = awaitImageOf(copyUri)

        val bin = RecycleBinManager.getInstance(ctx)
        bin.addAllToRecycleBin(listOf(main, copy))
        val records = bin.loadRecycleBin()
            .filter { it.mediaIdentityKey() in setOf(main.mediaIdentityKey(), copy.mediaIdentityKey()) }
        assertTrue("两张都应在回收站", records.size == 2)

        val calc = ContentDigestCalculator(ctx.contentResolver)
        val versions = calc.digestAll(records)
        // 两份都在待删集合里 → "还剩一份"的前提不成立
        val details = DuplicateRules.findExactDuplicates(
            versions, records.map { it.mediaIdentityKey() }.toSet()
        ).associate { it.image.mediaIdentityKey() to it.duplicates }

        val verdict = DuplicateReverify.verifyAll(
            toDelete = records,
            details = details,
            libraryKeys = librarySuspend().map { it.mediaIdentityKey() }.toSet(),
            digestOf = { versions[it] }
        )
        assertTrue(
            "两份都选中删除时必须停止（不能删掉最后一份）",
            verdict.shouldStop
        )
    }
}
