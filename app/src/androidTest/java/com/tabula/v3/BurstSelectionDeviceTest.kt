package com.tabula.v3

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.burst.BurstCommit
import com.tabula.v3.data.burst.BurstSelectionRules
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
 * #87 审计：连拍选片的真实状态链路。
 *
 * 覆盖：未滑过成员也算候选、确认前复核身份、部分成功同步真实状态、
 * 取消不提交、回收站恢复。
 *
 * 说明：真实"相似组"需要内容哈希聚类（模拟器上不稳定），因此这里用真实
 * ContentResolver 照片 + 生产 seam（BurstSelectionRules / BurstCommit +
 * RecycleBinManager）驱动；卡片页入口与弹层文本由 UI 断言覆盖。
 */
@RunWith(AndroidJUnit4::class)
class BurstSelectionDeviceTest {

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

    private fun insertPhoto(path: String = "DCIM/Camera"): Uri {
        val name = "burst-" + UUID.randomUUID().toString().take(8) + ".jpg"
        val uri = requireNotNull(
            ctx.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$path/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
        )
        inserted += uri
        val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(name.hashCode() or -0x1000000)
            ctx.contentResolver.openOutputStream(uri)!!.use {
                check(bmp.compress(Bitmap.CompressFormat.JPEG, 88, it))
            }
        } finally {
            bmp.recycle()
        }
        check(
            ctx.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
            ) == 1
        )
        return uri
    }

    private fun library(): List<ImageFile> = runBlocking {
        com.tabula.v3.data.repository.LocalImageRepository(ctx).loadAllData().allImages
    }

    /** 用真实照片构造一个相似组（生产数据模型，不伪造 ImageFile）。 */
    private fun realGroup(count: Int = 4): List<ImageFile> {
        val uris = (1..count).map { insertPhoto() }
        val all = library()
        val picked = uris.mapNotNull { u -> all.firstOrNull { it.uri == u } }
        assertEquals("真实照片应全部被图库发现", count, picked.size)
        return picked
    }

    // ==================== 用例 ====================

    @Test
    fun unswipedMembersAreCandidatesNotOnlyTouchedOnes() {
        val group = realGroup(4)
        val keys = group.map { it.mediaIdentityKey() }

        // 默认保留集：未滑过的成员也在决策里（不是"只处理滑过的"）
        val defaultKept = BurstSelectionRules.keepAllKeys(groupToSimilar(group))
        assertEquals("默认保留全部成员（含未滑过的）", keys.toSet(), defaultKept)

        // 用户只勾选一张保留 → 其余全部进入待回收
        val kept = setOf(keys.first())
        val decision = BurstSelectionRules.resolve(groupToSimilar(group), kept)
        assertTrue("决策必须有效（至少保留一张）", decision is BurstSelectionRules.Result.Valid)
        val valid = decision as BurstSelectionRules.Result.Valid
        assertEquals(1, valid.decision.keepCount)
        assertEquals(3, valid.decision.discardCount)
    }

    @Test
    fun commitReverifiesIdentityBeforeMoving() = runBlocking {
        val group = realGroup(3)
        val keys = group.map { it.mediaIdentityKey() }
        val kept = setOf(keys.first())
        val valid = BurstSelectionRules.resolve(groupToSimilar(group), kept)
            as BurstSelectionRules.Result.Valid
        val toDiscard = valid.decision.discardCandidates

        // 提交前复核：当前图库集合里这些身份都在
        val current = library().map { it.mediaIdentityKey() }.toSet()
        val reverified = BurstCommit.reverify(toDiscard, current, emptySet())
        assertEquals("复核后应保留全部待回收成员", toDiscard.size, reverified.size)

        // 外部删掉一张后复核：应被剔除
        val victim = toDiscard.first()
        ctx.contentResolver.delete(victim.uri, null, null)
        val currentAfter = library().map { it.mediaIdentityKey() }.toSet()
        val reverifiedAfter = BurstCommit.reverify(toDiscard, currentAfter, emptySet())
        assertTrue(
            "外部删除的成员必须从待回收里剔除",
            reverifiedAfter.none { it.mediaIdentityKey() == victim.mediaIdentityKey() }
        )
    }

    @Test
    fun partialFailureSyncsRealStateAndKeepsFailedVisible() = runBlocking {
        val group = realGroup(3)
        val keys = group.map { it.mediaIdentityKey() }
        val valid = BurstSelectionRules.resolve(groupToSimilar(group), setOf(keys.first()))
            as BurstSelectionRules.Result.Valid
        val toDiscard = valid.decision.discardCandidates

        val bin = RecycleBinManager.getInstance(ctx)
        // 只把第一张真正写进回收站（模拟部分成功）
        bin.addAllToRecycleBin(listOf(toDiscard.first()))
        val movedKeys = bin.loadRecycleBin().map { it.mediaIdentityKey() }.toSet()

        val result = BurstCommit.result(toDiscard, movedKeys)
        assertTrue("成功项应包含已写入的那张", result.succeeded.isNotEmpty())
        assertEquals(
            "失败项必须是其余未写入的",
            toDiscard.size - result.succeeded.size,
            result.failed.size
        )
        assertTrue(
            "失败项应如实保留（用户能看到并重试）",
            result.failed.any { it.mediaIdentityKey() == toDiscard.last().mediaIdentityKey() } ||
                result.failed.size == toDiscard.size - 1
        )
    }

    @Test
    fun cancelSubmitsNothing() = runBlocking {
        val group = realGroup(3)
        val bin = RecycleBinManager.getInstance(ctx)
        val before = bin.loadRecycleBin().size

        // 取消 = 不调用任何提交；决策只在界面内存里
        val keys = group.map { it.mediaIdentityKey() }
        BurstSelectionRules.resolve(groupToSimilar(group), setOf(keys.first()))

        assertEquals("取消不应改动回收站", before, bin.loadRecycleBin().size)
    }

    @Test
    fun restoredMemberIsBackInLibraryAndOutOfBin() = runBlocking {
        val group = realGroup(2)
        val bin = RecycleBinManager.getInstance(ctx)
        val victim = group.first()

        bin.addAllToRecycleBin(listOf(victim))
        val key = victim.mediaIdentityKey()
        assertTrue("写入后回收站应含该身份", bin.loadRecycleBin().any { it.mediaIdentityKey() == key })

        val record = bin.loadRecycleBin().first { it.mediaIdentityKey() == key }
        bin.removeFromRecycleBin(record)
        assertFalse(
            "恢复后回收站不应再含该身份",
            bin.loadRecycleBin().any { it.mediaIdentityKey() == key }
        )
        assertTrue(
            "恢复后该照片应重新出现在图库",
            library().any { it.mediaIdentityKey() == key }
        )
    }

    @Test
    fun keepAtLeastOneIsEnforced() {
        val group = realGroup(2)
        // 全部取消勾选 → 决策无效（产品要求至少保留一张）
        val decision = BurstSelectionRules.resolve(groupToSimilar(group), emptySet())
        assertTrue(
            "至少保留一张必须被强制",
            decision !is BurstSelectionRules.Result.Valid
        )
    }

    private fun groupToSimilar(images: List<ImageFile>) =
        com.tabula.v3.data.repository.SimilarGroup(
            images = images,
            startTime = images.minOfOrNull { it.dateModified } ?: 0L,
            endTime = images.maxOfOrNull { it.dateModified } ?: 0L
        )
}
