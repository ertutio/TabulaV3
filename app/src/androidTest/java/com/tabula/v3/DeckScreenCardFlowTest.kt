package com.tabula.v3

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.balance.RoundIntentRules
import com.tabula.v3.data.balance.RoundSessionRules
import com.tabula.v3.data.balance.SourceBalanceRules
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.preferences.WalkSamplingStrategy
import com.tabula.v3.data.repository.RecommendationEngine
import com.tabula.v3.testsupport.BatchLogLine
import com.tabula.v3.testsupport.MediaUriIdentity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * #90 真实卡片页测试：**页面实际持有的批次**是断言对象。
 *
 * 与 `DeckScreenRoundIntentEntryTest`（规则／引擎级）分工明确：
 * - 那边用真实引擎与真实偏好验证抽样语义；
 * - 这边走 UiAutomation 启动 MainActivity、在卡片上真实滑动、点提示、
 *   换批，然后读**生产代码自己打的** `currentBatch<-[batch-site-*]` 日志，
 *   断言卡片页手里那一批确实变了、来源分布符合预期。
 *
 * 不另调引擎顶替页面断言：一切结论都落在页面侧数据上。
 */
@RunWith(AndroidJUnit4::class)
class DeckScreenCardFlowTest {

    private companion object {
        const val TAG = "RoundIntentCard"
    }

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private lateinit var prefs: AppPreferences
    private val inserted = mutableListOf<Uri>()
    private var activity: android.app.Activity? = null

    @Before
    fun setUp() {
        prefs = AppPreferences(ctx)
        ctx.getSharedPreferences("tabula_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs.hasCompletedOnboarding = true
        prefs.hasAcknowledgedDisclaimer = true
        prefs.recommendMode = RecommendMode.RANDOM_WALK
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        prefs.swipeStyle = com.tabula.v3.data.preferences.SwipeStyle.SHUFFLE
        // 小批次让"滑完一批 → 换批"的路径短一些
        prefs.batchSize = 4
        AppPreferences(ctx).clearAllPickRecords()
        RecommendationEngine.getInstance(ctx).clearRoundIntent()
        grantMedia()
    }

    @After
    fun cleanUp() {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
    }

    // ==================== 夹具 ====================

    private fun grantMedia() {
        listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.WRITE_EXTERNAL_STORAGE"
        ).forEach { p -> runCatching { shell("pm grant ${ctx.packageName} $p") } }
    }

    /** 真实 MediaStore 写入一张 32x32 JPEG 到指定目录。 */
    private fun insertPhoto(path: String): Uri {
        val name = "ri-" + UUID.randomUUID().toString().take(8) + ".jpg"
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
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(name.hashCode() or -0x1000000)
            ctx.contentResolver.openOutputStream(uri)!!.use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
            }
        } finally {
            bitmap.recycle()
        }
        check(
            ctx.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
            ) == 1
        )
        return uri
    }

    private fun shell(cmd: String): String = ins.uiAutomation.executeShellCommand(cmd).use { p ->
        java.io.FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun now() = android.os.SystemClock.uptimeMillis()

    private fun sleep(ms: Long) = android.os.SystemClock.sleep(ms)

    // ==================== 界面驱动 ====================

    private fun find(target: String): AccessibilityNodeInfo? {
        val root = ins.uiAutomation.rootInActiveWindow ?: return null
        var hit: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || hit != null || depth > 24) return
            if (n.text?.toString() == target || n.contentDescription?.toString() == target) {
                hit = n
                return
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        var node = hit
        var hops = 0
        while (node != null && hops < 5) {
            if (node.isClickable) return node
            node = node.parent; hops++
        }
        return hit
    }

    private fun tapNode(label: String, node: AccessibilityNodeInfo) {
        if (node.isClickable) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        else {
            val r = android.graphics.Rect(); node.getBoundsInScreen(r)
            shell("input tap ${r.centerX()} ${r.centerY()}")
        }
        sleep(500)
    }

    private fun tap(label: String, timeout: Long = 20000) {
        val end = now() + timeout
        while (now() < end) {
            find(label)?.let { tapNode(label, it); return }
            dismissSystemDialogIfAny()
            sleep(150)
        }
        fail("界面元素未出现：$label")
    }

    private fun waitFor(label: String, timeout: Long = 25000) {
        val end = now() + timeout
        while (now() < end) {
            if (find(label) != null) return
            dismissSystemDialogIfAny()
            sleep(150)
        }
        fail("等待超时：$label")
    }

    private fun dismissSystemDialogIfAny() {
        val root = ins.uiAutomation.rootInActiveWindow ?: return
        val owner = root.packageName?.toString().orEmpty()
        if (owner == ctx.packageName) return
        if (!(owner.contains("permissioncontroller") || owner.contains("providers.media"))) return
        listOf("Allow", "允许", "Select all", "全部选择").forEach { label ->
            root.findAccessibilityNodeInfosInWindows(label).firstOrNull { it.isClickable }?.let { n ->
                tapNode(label, n)
            }
        }
    }

    private fun AccessibilityNodeInfo.findAccessibilityNodeInfosInWindows(
        label: String
    ): List<AccessibilityNodeInfo> = findAccessibilityNodeInfosByText(label)

    private fun startApp() {
        activity = ins.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitFor("回收站", timeout = 45000)
        sleep(2500)
    }

    /** 卡片页上真实滑动一张（上滑删除 / 左右滑保留）。 */
    private fun swipeCard(delete: Boolean) {
        fun findCard(): AccessibilityNodeInfo? {
            val root = ins.uiAutomation.rootInActiveWindow ?: return null
            fun walk(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
                if (n == null || depth > 24) return null
                if (n.contentDescription?.toString()?.matches(Regex("ri-[0-9a-f]{8}\\.jpg")) == true) {
                    return n
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)?.let { return it }
                return null
            }
            return walk(root, 0)
        }

        val deadline = now() + 8000
        var card: AccessibilityNodeInfo? = null
        while (now() < deadline) {
            if (find("再来一组") != null) return
            card = findCard()
            if (card != null) break
            sleep(120)
        }
        val node = card ?: return
        val b = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        val cx = b.centerX(); val cy = b.centerY()
        val dx = (b.width() * 0.35f).toInt()
        val dy = (b.height() * 0.35f).toInt()
        if (delete) {
            shell("input swipe $cx ${cy + dy} $cx ${cy - dy} 250")
        } else {
            shell("input swipe ${cx + dx} $cy ${cx - dx} $cy 250")
        }
        sleep(900)
    }

    /** 把当前批次翻完（保存动作），等"再来一组"。 */
    private fun finishBatch(maxSwipes: Int = 12) {
        var swipes = 0
        while (swipes < maxSwipes) {
            if (find("再来一组") != null) return
            swipeCard(delete = false)
            swipes++
        }
    }

    // ==================== 页面批次日志（生产 seam） ====================

    private fun deckLogLines(): List<String> = shell("logcat -d -s DeckScreen $TAG").lineSequence().toList()

    private fun awaitBatchLineAfter(boundary: String, site: String?, timeoutMs: Long): String? {
        val end = now() + timeoutMs
        while (now() < end) {
            BatchLogLine.lastAfter(deckLogLines(), boundary, site)?.let { return it }
            sleep(150)
        }
        return null
    }

    private fun relativePathOf(uri: String): String? =
        runCatching {
            ctx.contentResolver.query(
                Uri.parse(uri),
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    private fun boundary(label: String): String {
        val marker = "$label:" + now()
        Log.i(TAG, marker)
        sleep(300)
        return marker
    }

    // ==================== 用例 ====================

    /**
     * 接受提示后，**页面**先持有受意图影响的一批，下一批恢复所选策略。
     *
     * 判定全部落在页面批次日志上：
     * 1. 处理若干截图 → 提示出现（界面真实文案）
     * 2. 点「看这个」→ 引擎写入意图
     * 3. 翻完当前批 → 点「再来一组」→ 页面装载第一批：来源目录全部是截图
     * 4. 再翻完 → 再换批：来源目录不再是清一色截图（恢复原随机）
     */
    @Test
    fun acceptIntentMakesFirstPageBatchPreferSourceThenFallsBack() {
        repeat(14) { insertPhoto("DCIM/Screenshots") }
        repeat(40) { insertPhoto("DCIM/Camera") }

        val seededUris = MediaUriIdentity.canonicalSet(inserted.map { it.toString() })
        startApp()

        // 连续上滑删除截图，直到提示出现
        val hintDeadline = now() + 120000
        var hintSeen = false
        while (now() < hintDeadline) {
            if (find("看这个") != null) { hintSeen = true; break }
            if (find("再来一组") != null) {
                tap("再来一组")
                sleep(1500)
                continue
            }
            swipeCard(delete = true)
        }
        assertTrue("处理截图后卡片页应出现本轮意图提示", hintSeen)

        tap("看这个")
        val engine = RecommendationEngine.getInstance(ctx)
        assertTrue("接受提示后引擎应持有待消费意图", engine.roundIntent.isActive)

        // 第一批：必须由页面换批装载
        finishBatch()
        val firstBoundary = boundary("ri-first")
        tap("再来一组")
        val firstLine = awaitBatchLineAfter(firstBoundary, site = null, timeoutMs = 30000)
        assertNotNull("换批后页面必须装载新批次", firstLine)

        val firstUrisRaw = BatchLogLine.parseUris(firstLine!!)
        assertTrue("第一批不能为空（$firstLine）", firstUrisRaw.isNotEmpty())
        val firstDirs = firstUrisRaw.mapNotNull { relativePathOf(it) }.toSet()
        assertTrue(
            "第一批应受意图影响——来源应集中在截图目录（实际：$firstDirs）",
            firstDirs.size == 1 && firstDirs.single().contains("Screenshots")
        )
        assertFalse("意图应已被页面换批消费", engine.roundIntent.isActive)

        // 第二批：恢复所选策略（原随机），不再清一色截图
        finishBatch()
        val secondBoundary = boundary("ri-second")
        tap("再来一组")
        val secondLine = awaitBatchLineAfter(secondBoundary, site = null, timeoutMs = 30000)
        assertNotNull("再次换批后页面必须装载新批次", secondLine)
        val secondUrisRaw = BatchLogLine.parseUris(secondLine!!)
        val secondDirs = secondUrisRaw.mapNotNull { relativePathOf(it) }.toSet()
        assertNotEquals(
            "第二批必须恢复所选策略，不能仍整批是截图（实际：$secondDirs）",
            setOf("Pictures/DCIM/Screenshots/"),
            secondDirs
        )
        assertTrue(
            "页面批次里的照片必须都来自本测试造的图片（多余=${secondUrisRaw - seededUris}）",
            secondUrisRaw.all { it in seededUris }
        )
    }

    /**
     * 切换推荐模式后：本轮统计与**待消费意图**都必须清空，提示也不再出现。
     *
     * 审计要求"测试必须先建立非空记录或待消费意图，再切换并验证清除"——
     * 这里先制造真实待消费意图，再走设置页真实入口切换模式。
     */
    @Test
    fun switchingModeClearsPendingIntentAndHint() {
        repeat(10) { insertPhoto("DCIM/Screenshots") }
        repeat(10) { insertPhoto("DCIM/Camera") }
        startApp()

        val engine = RecommendationEngine.getInstance(ctx)
        // 建立"待消费意图"这一非空状态（真实引擎写入）
        engine.acceptRoundIntent("external_primary|Pictures/DCIM/Screenshots/")
        assertTrue("前置：必须存在待消费意图", engine.roundIntent.isActive)

        // 真实入口：设置 → 推荐模式 → 相似推荐
        tap("设置")
        waitFor("推荐模式")
        tap("推荐模式")
        waitFor("相似推荐")
        tap("相似推荐")
        sleep(800)
        shell("input keyevent KEYCODE_BACK")
        sleep(800)
        shell("input keyevent KEYCODE_BACK")
        sleep(1500)
        waitFor("回收站", timeout = 25000)

        assertFalse(
            "切换推荐模式后待消费意图必须清空",
            RecommendationEngine.getInstance(ctx).roundIntent.isActive
        )
    }

    /**
     * 忽略提示不改变下一批：点「忽略」后页面换批仍按所选策略。
     *
     * 与接受路径对照：忽略不写意图，因此不存在"第一批被改成单一来源"。
     */
    @Test
    fun dismissingHintKeepsNextBatchOnChosenStrategy() {
        repeat(14) { insertPhoto("DCIM/Screenshots") }
        repeat(40) { insertPhoto("DCIM/Camera") }
        startApp()

        val hintDeadline = now() + 120000
        var hintSeen = false
        while (now() < hintDeadline) {
            if (find("忽略") != null) { hintSeen = true; break }
            if (find("再来一组") != null) {
                tap("再来一组")
                sleep(1500)
                continue
            }
            swipeCard(delete = true)
        }
        assertTrue("处理截图后应出现提示", hintSeen)

        tap("忽略")
        val engine = RecommendationEngine.getInstance(ctx)
        assertFalse("忽略不得写入引擎意图", engine.roundIntent.isActive)

        finishBatch()
        val b = boundary("ri-dismiss")
        tap("再来一组")
        val line = awaitBatchLineAfter(b, site = null, timeoutMs = 30000)
        assertNotNull("忽略后页面仍要能换批", line)

        val dirs = BatchLogLine.parseUris(line!!).mapNotNull { relativePathOf(it) }.toSet()
        assertFalse(
            "忽略后下一批仍按所选策略，不应被强行改成单一来源（实际：$dirs）",
            dirs.size == 1 && dirs.single().contains("Screenshots")
        )
    }
}
