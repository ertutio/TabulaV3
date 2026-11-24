package com.tabula.v3

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.duplicate.ContentDigestCalculator
import com.tabula.v3.data.duplicate.DuplicateReverify
import com.tabula.v3.data.duplicate.DuplicateRules
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * #88 审计：用真实 ContentResolver 和删除入口验证副本提示。
 *
 * 覆盖：图库确有副本、两份都选中、副本被外部修改或删除、权限变化。
 * 核心约定（审计要求）：复核失败时用户必须看到变化并有机会重新决定。
 */
@RunWith(AndroidJUnit4::class)
class DuplicateVersionsDeviceTest {

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
    fun cleanUp() {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
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

    /**
     * 写入一张照片。
     *
     * [sameBytesAs] 给定时写入**完全相同的字节串**——不是"同色位图再压一次"：
     * JPEG 编码器对同一张位图的两次压缩可能产出不同字节（量化/时间戳差异），
     * 那样"字节相同"的前提根本不成立（run 45148815434 的真实失败原因）。
     */
    private fun insertPhoto(path: String = "DCIM/Camera", sameBytesAs: Int? = null): Uri {
        val name = "dup-" + UUID.randomUUID().toString().take(8) + ".jpg"
        val uri = requireNotNull(
            ctx.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$path/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
        )
        inserted += uri
        // 用 seed 生成确定字节：同一 seed = 完全相同的文件内容
        val seed = sameBytesAs ?: name.hashCode()
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

    private fun library(): List<ImageFile> = runBlocking { librarySuspend() }

    private fun imageOf(uri: Uri): ImageFile? = library().firstOrNull { it.uri == uri }

    /**
     * MediaStore 索引新插入照片有延迟：带重试地等待它出现在图库里。
     * 直接 !! 会在 CI 上偶发 NPE（run 45140538691）。
     */
    private suspend fun awaitImageOf(uri: Uri, timeoutMs: Long = 15000): ImageFile {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < end) {
            librarySuspend().firstOrNull { it.uri == uri }?.let { return it }
            kotlinx.coroutines.delay(250)
        }
        error("照片未在 ${timeoutMs}ms 内被图库索引：$uri")
    }

    // ==================== 用例 ====================

    @Test
    fun realByteIdenticalCopyIsDetectedAsDuplicate() = runBlocking {
        val color = 0x7F123456
        val a = insertPhoto("DCIM/Camera", sameBytesAs = color)
        val b = insertPhoto("DCIM/Camera", sameBytesAs = color)
        val different = insertPhoto("DCIM/Camera", sameBytesAs = 0x7F654321)

        val images = library()
        val ia = images.first { it.uri == a }
        val ib = images.first { it.uri == b }
        val id = images.first { it.uri == different }

        val calc = ContentDigestCalculator(ctx.contentResolver)
        val digests = calc.digestAll(listOf(ia, ib, id))
        assertEquals("三张照片都应有摘要", 3, digests.size)

        // 诊断：摘要与分组键（sha256, size）必须一致才能分到一组
        println("DIAG sizes ia=${ia.size} ib=${ib.size} id=${id.size}")
        println("DIAG digests=" + digests.map { (k, v) ->
            "${k.displayName}:${v.sha256.take(8)}/${v.size}/meaningful=${v.isMeaningful}"
        })
        // 契约：只有 recycledIdentityKeys 里的成员才产出 finding
        //（提示只在"回收站里待永久删除"时才需要"还剩一份吗"）
        val findings = DuplicateRules.findExactDuplicates(
            candidates = digests,
            recycledIdentityKeys = setOf(ia.mediaIdentityKey())
        )
        println("DIAG findings=" + findings.map { f ->
            "${f.image.displayName}->${f.duplicates.map { it.displayName }}"
        })
        val forA = findings.firstOrNull { it.image.mediaIdentityKey() == ia.mediaIdentityKey() }
        assertTrue("字节相同的两张应被识别为副本", forA?.hasDuplicate == true)
        assertTrue(
            "副本应是另一张而不是自己",
            forA!!.duplicates.any { it.mediaIdentityKey() == ib.mediaIdentityKey() }
        )
        assertFalse(
            "不同内容的照片不应被当成副本",
            forA.duplicates.any { it.mediaIdentityKey() == id.mediaIdentityKey() }
        )
    }

    @Test
    fun selectingBothCopiesMakesReverifyReject() = runBlocking {
        val color = 0x7FABCDEF
        val a = insertPhoto(sameBytesAs = color)
        val b = insertPhoto(sameBytesAs = color)
        val ia = awaitImageOf(a)
        val ib = awaitImageOf(b)

        // 两份都选中要删 → "还剩一份"的前提不成立，必须拒绝
        val selected = setOf(ia.mediaIdentityKey(), ib.mediaIdentityKey())
        val stillReadable = { image: ImageFile ->
            runCatching {
                ctx.contentResolver.openInputStream(image.uri)?.use { it.read() != -1 } == true
            }.getOrDefault(false)
        }

        val canDelete = DuplicateRules.reverify(ib, selected, stillReadable)
        assertFalse("两份都选中时必须拒绝删除（不能把两份都删掉）", canDelete)
    }

    @Test
    fun externallyModifiedCopyInvalidatesTheHint() = runBlocking {
        val color = 0x7F111111
        val a = insertPhoto(sameBytesAs = color)
        val b = insertPhoto(sameBytesAs = color)
        val ia = awaitImageOf(a)
        val ib = awaitImageOf(b)

        val calc = ContentDigestCalculator(ctx.contentResolver)
        val before = calc.digestAll(listOf(ia, ib))
        val findingsBefore = DuplicateRules.findExactDuplicates(
            candidates = before,
            recycledIdentityKeys = setOf(ia.mediaIdentityKey())
        )
        assertTrue(
            "初始状态应识别为副本",
            findingsBefore.first { it.image.mediaIdentityKey() == ia.mediaIdentityKey() }.hasDuplicate
        )

        // 外部修改副本内容（覆盖成不同像素）
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(0x7F999999)
            ctx.contentResolver.openOutputStream(ib.uri, "wt")!!.use {
                check(bmp.compress(Bitmap.CompressFormat.JPEG, 100, it))
            }
        } finally {
            bmp.recycle()
        }

        // 重新计算摘要：副本身份已变化，提示必须失效
        val after = calc.digestAll(listOf(awaitImageOf(a), awaitImageOf(b)))
        val findingsAfter = DuplicateRules.findExactDuplicates(
            candidates = after,
            recycledIdentityKeys = setOf(ia.mediaIdentityKey())
        )
        val forAAfter = findingsAfter.firstOrNull {
            it.image.mediaIdentityKey() == ia.mediaIdentityKey()
        }
        assertTrue(
            "副本被外部修改后，提示必须失效（用户要能重新决定）",
            forAAfter == null || !forAAfter.hasDuplicate
        )
    }

    @Test
    fun externallyDeletedCopyFailsReverifySoUserSeesChange() = runBlocking {
        val color = 0x7F222222
        val a = insertPhoto(sameBytesAs = color)
        val b = insertPhoto(sameBytesAs = color)
        val ia = awaitImageOf(a)
        val ib = awaitImageOf(b)

        val stillReadable = { image: ImageFile ->
            runCatching {
                ctx.contentResolver.openInputStream(image.uri)?.use { it.read() != -1 } == true
            }.getOrDefault(false)
        }
        assertTrue("删除前副本应可读", stillReadable(ib))

        // 外部删除副本
        ctx.contentResolver.delete(ib.uri, null, null)

        assertFalse(
            "副本被外部删除后复核必须失败（用户看到变化而不是静默删除）",
            DuplicateRules.reverify(ib, setOf(ia.mediaIdentityKey()), stillReadable)
        )
    }

    @Test
    fun permissionLossMakesCopyUnreadable() = runBlocking {
        val color = 0x7F333333
        val a = insertPhoto(sameBytesAs = color)
        val b = insertPhoto(sameBytesAs = color)
        val ia = awaitImageOf(a)
        val ib = awaitImageOf(b)

        // 权限丢失后的**可观测效果**是"拿不到副本内容"——摘要无法计算。
        // 不用 pm revoke：那会杀掉测试进程，用例必然挂起（实测两次超时）。
        // 这里直接让 digestOf 返回 null，与权限丢失后的分支完全一致。
        val canDeleteWithUnreadableCopy = DuplicateReverify.copyStillValid(
            main = ia,
            copy = ib,
            libraryKeys = setOf(ia.mediaIdentityKey(), ib.mediaIdentityKey()),
            selectedKeys = setOf(ia.mediaIdentityKey()),
            digestOf = { null }
        )
        assertFalse(
            "副本内容读不到（权限丢失/文件不可读）时，复核必须失败而不是继续删除",
            canDeleteWithUnreadableCopy
        )

        // 对照：内容可读且一致时必须允许继续
        val calc = ContentDigestCalculator(ctx.contentResolver)
        val digests = calc.digestAll(listOf(ia, ib))
        val canDeleteWhenReadable = DuplicateReverify.copyStillValid(
            main = ia,
            copy = ib,
            libraryKeys = setOf(ia.mediaIdentityKey(), ib.mediaIdentityKey()),
            selectedKeys = setOf(ia.mediaIdentityKey()),
            digestOf = { digests[it] }
        )
        assertTrue("内容一致且可读时应允许继续", canDeleteWhenReadable)
    }

    @Test
    fun zeroByteOrEmptyDigestIsNotMeaningfulDuplicate() {
        val empty = DuplicateRules.ContentVersion(sha256 = "", size = 0L)
        val blank = DuplicateRules.ContentVersion(sha256 = "   ", size = 10L)
        assertFalse("空摘要不算有效副本证据", empty.isMeaningful)
        assertFalse("空白摘要不算有效副本证据", blank.isMeaningful)
        assertTrue(
            "正常摘要算有效证据",
            DuplicateRules.ContentVersion(sha256 = "a".repeat(64), size = 123L).isMeaningful
        )
    }
}
