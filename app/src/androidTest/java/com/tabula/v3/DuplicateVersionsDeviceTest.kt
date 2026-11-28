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
import com.tabula.v3.data.repository.RecycleBinManager
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

    // ==================== #105：候选筛选抽出的三条硬约束 ====================
    //
    // 这三条是 #105 把候选筛选从 MainActivity 抽到 DuplicateScanCandidates 时
    // 必须保持的语义。JVM 单测（DuplicateScanCandidatesTest）用合成图库钉住了
    // 规则；这里用真实 MediaStore 字节再验一遍，避免"规则对、接线错"。

    /**
     * 跨卷同数值 ID 是两张照片。
     *
     * 设备上只有 external_primary 一卷，无法真的往 SD 卡写文件；因此这里保留
     * **真实字节**（读同一个 URI），只把身份换成另一卷——要验的是身份判定，
     * 不是存储。身份键是 `volumeName|id`，所以两行必须互为副本而不是"自己"。
     */
    @Test
    fun sameNumericIdOnAnotherVolumeIsACopyNotItself() = runBlocking {
        val color = 0x7F444444
        val uri = insertPhoto(sameBytesAs = color)
        val primary = awaitImageOf(uri)
        // 同 id、同字节，但属于另一卷
        val sdRow = primary.copy(volumeName = "1234-5678")

        val calc = ContentDigestCalculator(ctx.contentResolver)
        val version = calc.digestAll(listOf(primary)).getValue(primary)
        assertTrue("真实字节应算出有意义的摘要", version.isMeaningful)

        val findings = DuplicateRules.findExactDuplicates(
            candidates = mapOf(primary to version, sdRow to version),
            recycledIdentityKeys = setOf(primary.mediaIdentityKey())
        )

        val forPrimary = findings.single { it.image.mediaIdentityKey() == primary.mediaIdentityKey() }
        assertEquals(
            "同数值 ID 的另一卷照片是真副本，不是自身",
            listOf("1234-5678"),
            forPrimary.duplicates.map { it.volumeName }
        )
        assertTrue(
            "不能把自己算成自己的副本",
            forPrimary.duplicates.none { it.mediaIdentityKey() == primary.mediaIdentityKey() }
        )

        // 反向：SD 卡那行在回收站、主卷在库里 → 主卷那张是它的副本
        val reversed = DuplicateRules.findExactDuplicates(
            candidates = mapOf(primary to version, sdRow to version),
            recycledIdentityKeys = setOf(sdRow.mediaIdentityKey())
        )
        assertEquals(
            listOf("external_primary"),
            reversed.single().duplicates.map { it.volumeName }
        )
    }

    /**
     * 回收站成员不能作为"库内还剩一份"的证据。
     *
     * 两张字节相同的照片**都进了回收站**：它们互相比不算留存，图库也没有
     * 第三份 → 必须一条提示都没有。这条是 #88 审计的原话，也是 #105 抽函数
     * 时最容易改坏的一处（把 `recycledIdentityKeys` 的排除写成只排自己）。
     */
    @Test
    fun recycleBinMembersAreNotLibraryEvidence() = runBlocking {
        val color = 0x7F555555
        val aUri = insertPhoto(sameBytesAs = color)
        val bUri = insertPhoto(sameBytesAs = color)
        val a = awaitImageOf(aUri)
        val b = awaitImageOf(bUri)
        assertEquals("两张应字节相同", a.size, b.size)

        val bin = RecycleBinManager.getInstance(ctx)
        try {
            bin.addAllToRecycleBin(listOf(a, b))
            val keys = setOf(a.mediaIdentityKey(), b.mediaIdentityKey())
            val inBin = bin.loadRecycleBin().filter { it.mediaIdentityKey() in keys }
            assertEquals("两张都应进回收站", 2, inBin.size)

            val calc = ContentDigestCalculator(ctx.contentResolver)
            val versions = calc.digestAll(inBin)
            assertEquals(2, versions.size)

            val findings = DuplicateRules.findExactDuplicates(
                candidates = versions,
                recycledIdentityKeys = inBin.map { it.mediaIdentityKey() }.toSet()
            )
            assertTrue(
                "两张都在回收站时，图库没有留存 → 不能声称「还剩一份」",
                findings.isEmpty()
            )

            // 对照：同一批摘要，只把 a 当被查对象、b 当库内副本 → 应有提示。
            // 说明上一条的空结果来自"回收站身份集合"，不是摘要算错。
            val control = DuplicateRules.findExactDuplicates(
                candidates = versions,
                recycledIdentityKeys = setOf(a.mediaIdentityKey())
            )
            assertEquals(1, control.size)
            assertEquals(
                listOf(b.mediaIdentityKey()),
                control.single().duplicates.map { it.mediaIdentityKey() }
            )
        } finally {
            runCatching { bin.clearRecycleBin() }
        }
    }

    /**
     * 永久删除前复核：走真实回收站行 + 真实摘要，两个方向都要成立。
     *
     * 1. 库内副本未变 → `verifyAll` 不拦截
     * 2. 库内副本被外部改写 → `verifyAll` 拦截，且该提示被标为失效
     *
     * 与 `DuplicateDeleteGateDeviceTest` 的分工：那边验的是删除入口的接线
     * （进站、提示、门禁）；这里只验 #105 改动后复核 seam 仍然认**新算的摘要**，
     * 不认扫描时缓存的旧结果。
     */
    @Test
    fun permanentDeletionReverifyUsesFreshDigests() = runBlocking {
        val seed = 0x7F666666
        val mainUri = insertPhoto(sameBytesAs = seed)
        val copyUri = insertPhoto(sameBytesAs = seed)
        val main = awaitImageOf(mainUri)
        val copy = awaitImageOf(copyUri)

        val bin = RecycleBinManager.getInstance(ctx)
        try {
            bin.addAllToRecycleBin(listOf(main))
            val inBin = bin.loadRecycleBin().first { it.mediaIdentityKey() == main.mediaIdentityKey() }
            val calc = ContentDigestCalculator(ctx.contentResolver)

            // 扫描阶段的提示（与 MainActivity 的 duplicateDetails 同源）
            val scanned = calc.digestAll(listOf(inBin, copy))
            val details = DuplicateRules.findExactDuplicates(
                scanned, setOf(inBin.mediaIdentityKey())
            ).associate { it.image.mediaIdentityKey() to it.duplicates }
            assertTrue("初始应有「还有副本」提示", details.containsKey(inBin.mediaIdentityKey()))

            // 1) 副本未变 → 允许永久删除
            val unchanged = calc.digestAll(listOf(inBin, copy))
            val pass = DuplicateReverify.verifyAll(
                toDelete = listOf(inBin),
                details = details,
                libraryKeys = librarySuspend().map { it.mediaIdentityKey() }.toSet() +
                    setOf(copy.mediaIdentityKey()),
                digestOf = { unchanged[it] }
            )
            assertFalse("副本未变化时不应拦截永久删除", pass.shouldStop)
            assertTrue(pass.invalidatedKeys.isEmpty())

            // 2) 副本被外部改写 → 必须拦截，且提示失效
            ctx.contentResolver.openOutputStream(copyUri, "wt")!!.use {
                it.write(ByteArray(2048) { i -> ((0x0BADF00D shr ((i % 4) * 8)) xor i).toByte() })
            }
            // 用**同一个 ImageFile 实例**重算：digest 读的是 URI 当前内容，
            // 所以这里拿到的是改后的摘要。换成重新查库得到的实例会让
            // digestOf 的 map 键对不上，测试会因为"取不到摘要"而假通过。
            val changed = calc.digestAll(listOf(inBin, copy))
            assertFalse(
                "改写后副本摘要必须与主图不同（否则下面的拦截断言没有意义）",
                changed[copy]?.sha256 == changed[inBin]?.sha256
            )
            val blocked = DuplicateReverify.verifyAll(
                toDelete = listOf(inBin),
                details = details,
                libraryKeys = librarySuspend().map { it.mediaIdentityKey() }.toSet(),
                digestOf = { changed[it] }
            )
            assertTrue("副本内容变化后必须停止永久删除", blocked.shouldStop)
            assertTrue(
                "该提示必须被标为失效，让用户看到变化后重新决定",
                blocked.invalidatedKeys.contains(inBin.mediaIdentityKey())
            )
        } finally {
            runCatching { bin.clearRecycleBin() }
        }
    }
}
