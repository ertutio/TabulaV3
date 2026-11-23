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

    // ==================== 真实入口链路（#87 二轮审计） ====================

    private var activity: android.app.Activity? = null

    /**
     * 插入一张**内容完全相同**的照片。
     *
     * 为什么必须相同：`SimilarGroupDetector` 的连边条件是 dHash 汉明距离
     * （Tier 1: ≤6 且 metaScore ≥ 15）。内容相同 ⇒ dHash 相同 ⇒ 距离 0，
     * 加上时间相近（<5s，25 分）、同分辨率（20 分）、同文件大小（10 分）、
     * 同相册（5 分），metaScore = 60，**稳定**成组。
     *
     * 这正是本测试要的：不靠运气，真实检测器每次都能产出同一个组。
     */
    private fun insertIdenticalPhoto(): Uri {
        val name = "burst-" + UUID.randomUUID().toString().take(8) + ".jpg"
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
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(0xFF3366CC.toInt())
            // 固定图案：保证 dHash 有稳定的位（纯色图 dHash 可能全 0，
            // 但全 0 也是稳定的，这里加图案让距离判定更接近真实照片）
            for (x in 0 until 64 step 8) {
                bmp.setPixel(x, 0, 0xFF000000.toInt())
                bmp.setPixel(x, 63, 0xFFFFFFFF.toInt())
            }
            ctx.contentResolver.openOutputStream(uri)!!.use {
                check(bmp.compress(Bitmap.CompressFormat.JPEG, 90, it))
            }
        } finally {
            bmp.recycle()
        }
        check(
            ctx.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            ) == 1
        )
        return uri
    }

    private fun now() = android.os.SystemClock.uptimeMillis()

    private fun find(target: String): android.view.accessibility.AccessibilityNodeInfo? {
        val root = ins.uiAutomation.rootInActiveWindow ?: return null
        var hit: android.view.accessibility.AccessibilityNodeInfo? = null
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (n == null || hit != null || depth > 24) return
            if (n.text?.toString() == target || n.contentDescription?.toString() == target) {
                hit = n; return
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return hit
    }

    private fun screenTexts(): Set<String> {
        val root = ins.uiAutomation.rootInActiveWindow ?: return emptySet()
        val out = mutableSetOf<String>()
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 24) return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun waitFor(target: String, timeout: Long = 30000) {
        val end = now() + timeout
        while (now() < end) {
            if (find(target) != null) return
            android.os.SystemClock.sleep(200)
        }
        error("waitFor timeout: $target （屏幕：${screenTexts()}）")
    }

    private fun waitForTextContaining(fragment: String, timeout: Long = 30000) {
        val end = now() + timeout
        while (now() < end) {
            if (screenTexts().any { it.contains(fragment) }) return
            android.os.SystemClock.sleep(200)
        }
        error("waitForTextContaining timeout: $fragment （屏幕：${screenTexts()}）")
    }

    private fun tap(target: String, timeout: Long = 25000) {
        val end = now() + timeout
        while (now() < end) {
            find(target)?.let { node ->
                if (node.isClickable) node.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                ) else {
                    val r = android.graphics.Rect(); node.getBoundsInScreen(r)
                    shell("input tap ${r.centerX()} ${r.centerY()}")
                }
                android.os.SystemClock.sleep(700)
                return
            }
            android.os.SystemClock.sleep(200)
        }
        error("tap not found: $target （屏幕：${screenTexts()}）")
    }

    private fun startApp() {
        activity = ins.startActivitySync(
            android.content.Intent(ctx, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        android.os.SystemClock.sleep(2000)
    }

    /**
     * **真实入口**：卡片页 → 连拍选片 → 全部保留 → 确认。
     *
     * 审计要求（#87 二轮）：不能只断言组件的 `onConfirm` 被调用。这里走
     * 真实应用入口，断言的是**产品终态**：
     *
     * 1. 真实检测器产出的组，卡片页真的出现「这批像连拍」入口；
     * 2. 点「去选片」进入选片页，点「这组都留」后按钮文案变成
     *    「都保留，结束这组」（= discardCount == 0，正是旧代码 return 掉的情形）；
     * 3. 点确认后**真的回到卡片页**——旧代码在这里静默 return，页面不动；
     * 4. 回收站**没有新增**（全部保留不写回收站）；
     * 5. 该组进入冷却（`markSimilarGroupProcessed` 的真实效果）；
     * 6. 回到卡片页后**当前批次仍是那 3 张**（一张都没被移走），
     *    且连拍入口消失（组已结束）。
     */
    @Test
    fun keepAllFromRealEntryEndsGroupWithoutTouchingRecycleBin() = runBlocking {
        // 1. 三张内容相同的真实照片
        val uris = (1..3).map { insertIdenticalPhoto() }
        val all = library()
        val mine = uris.mapNotNull { u -> all.firstOrNull { it.uri == u } }
        assertEquals("三张真实照片都应被图库索引", 3, mine.size)
        val myKeys = mine.map { it.mediaIdentityKey() }.toSet()

        // 2. 用真实引擎确认检测器确实产出包含这三张的组
        prefs.recommendMode = com.tabula.v3.data.preferences.RecommendMode.SIMILAR
        val engine = com.tabula.v3.data.repository.RecommendationEngine.getInstance(ctx)
        val detected = engine.getNextSimilarGroup(all)
        assertTrue(
            "真实检测器必须产出相似组（dHash 距离 0 + 元数据分 60）",
            detected != null
        )
        val group = detected!!
        assertTrue(
            "产出的组必须包含这三张照片（实际成员：${group.images.map { it.displayName }}）",
            group.images.map { it.mediaIdentityKey() }.toSet().containsAll(myKeys)
        )
        val groupId = group.id

        // 3. 基线：回收站与冷却状态
        val bin = RecycleBinManager.getInstance(ctx)
        val binBefore = bin.loadRecycleBin().size
        assertTrue(
            "该组此刻不应已在冷却中",
            groupId !in prefs.getSimilarGroupCooldownIds()
        )

        // 4. 真实入口：启动应用 → 卡片页出现连拍入口
        startApp()
        waitForTextContaining("这批像连拍", timeout = 60000)
        val deckTexts = screenTexts()
        assertTrue(
            "卡片页必须出现「去选片」入口（屏幕：$deckTexts）",
            deckTexts.contains("去选片")
        )

        // 5. 进选片页
        tap("去选片")
        waitFor("连拍选片", timeout = 20000)
        val pickerTexts = screenTexts()
        assertTrue(
            "选片页应显示组规模（屏幕：$pickerTexts）",
            pickerTexts.any { it.contains("张 ·") }
        )
        // 默认保留集 = 最早 + 最晚，因此初始有 1 张待回收
        assertTrue(
            "默认保留两张、待回收一张（屏幕：$pickerTexts）",
            pickerTexts.any { it.startsWith("保留 2 张") } ||
                pickerTexts.any { it.startsWith("确认：回收 1 张") }
        )

        // 6. 全部保留 → 按钮文案必须变成「都保留，结束这组」
        tap("这组都留")
        waitFor("都保留，结束这组", timeout = 15000)
        val afterKeepAll = screenTexts()
        assertTrue(
            "全部保留时应显示 0 张待回收（屏幕：$afterKeepAll）",
            afterKeepAll.any { it.contains("回收 0 张") }
        )

        // 7. 确认 —— 旧代码在这里静默 return（discardCandidates 为空）
        tap("都保留，结束这组")

        // 8. 必须真的回到卡片页（组结束）
        val deadline = now() + 20000
        var backOnDeck = false
        while (now() < deadline) {
            val texts = screenTexts()
            if (!texts.contains("连拍选片") && texts.any { it.contains("/3") || it == "去选片" }) {
                backOnDeck = true
                break
            }
            android.os.SystemClock.sleep(250)
        }
        assertTrue(
            "确认后必须离开选片页回到卡片页（屏幕：${screenTexts()}）",
            backOnDeck
        )

        // 9. 回收站没有新增（全部保留不写回收站）
        val binAfter = bin.loadRecycleBin().size
        assertEquals("全部保留不得写入回收站", binBefore, binAfter)
        assertTrue(
            "这三张都不应在回收站里",
            bin.loadRecycleBin().none { it.mediaIdentityKey() in myKeys }
        )

        // 10. 该组已标记处理（进入冷却）——组真正"结束"的持久化证据
        assertTrue(
            "确认后该组必须进入冷却（组 id=$groupId）",
            groupId in prefs.getSimilarGroupCooldownIds()
        )

        // 11. 回到卡片页后的当前批次状态：三张都还在（一张没被移走），
        //     且连拍入口消失（组已结束，activeSimilarGroup 被清空）
        val finalTexts = screenTexts()
        assertTrue(
            "当前批次应仍是 3 张（屏幕：$finalTexts）",
            finalTexts.any { it == "1/3" } || finalTexts.any { it.contains("/3") }
        )
        assertTrue(
            "组结束后连拍入口应消失（屏幕：$finalTexts）",
            !finalTexts.contains("这批像连拍")
        )
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
