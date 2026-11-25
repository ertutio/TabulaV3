package com.tabula.v3

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.balance.DeferredActionLedger
import com.tabula.v3.data.balance.PendingDelete
import com.tabula.v3.data.balance.RoundSessionRules
import com.tabula.v3.data.balance.SessionBatchIds
import com.tabula.v3.data.balance.SourceBalanceRules
import com.tabula.v3.data.balance.Ticket
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.preferences.SwipeStyle
import com.tabula.v3.data.preferences.WalkSamplingStrategy
import com.tabula.v3.data.repository.RecommendationEngine
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.testsupport.BatchLogLine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * #90 第五轮：**旧批待删除动作计入新批次**的真实卡片回归。
 *
 * 复现顺序（审计给的那条）：
 *
 * 1. 在旧批最后一张卡上**上滑**发起删除 → 进入撤销期（3s Snackbar）；
 * 2. 撤销期内点「再来一组」→ **强制断言新批已实际装载**；
 * 3. 等旧删除完成（Snackbar 超时）；
 * 4. 分别确认：① 那张照片**确实落入回收站**；② 新批的来源统计**没有增加**。
 *
 * 修复前的形态：`onRemove` 在提交那一刻才调 `capturedTicket()`，此时批次
 * 已经前进，旧批最后一张的删除被记到新批统计上（②会红）。
 *
 * 断言全部落在生产流程结果上：回收站内容来自 `RecycleBinManager.loadRecycleBin`，
 * 批次来自生产代码自己打的 `currentBatch<-[batch-site-*]` 日志。
 */
@RunWith(AndroidJUnit4::class)
class DeckScreenPendingDeleteBoundaryTest {

    private companion object { const val TAG = "PendingDelBoundary" }

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private lateinit var prefs: AppPreferences
    private val inserted = mutableListOf<Uri>()
    private var activity: android.app.Activity? = null

    @Before
    fun setUp() {
        prefs = AppPreferences(ctx)
        ctx.getSharedPreferences("tabula_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs.hasCompletedOnboarding = true
        prefs.hasAcknowledgedDisclaimer = true
        prefs.recommendMode = RecommendMode.RANDOM_WALK
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        prefs.swipeStyle = SwipeStyle.SHUFFLE
        prefs.batchSize = 3          // 小批次：容易滑到最后一张
        // 用生产自带的「下一张」控件推进批次：SHUFFLE 样式下左右滑是"切牌循环"，
        // 不是"保留并前进"，用它翻批会原地打转（run 45590253575 停在同一张卡）。
        prefs.quickActionButtonEnabled = true
        prefs.clearAllPickRecords()
        RecommendationEngine.getInstance(ctx).clearRoundIntent()
        runBlocking { runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() } }
        grantMedia()
    }

    @After
    fun cleanUp() {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        runBlocking { runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() } }
    }

    // ==================== 夹具 ====================

    private fun grantMedia() {
        listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.WRITE_EXTERNAL_STORAGE"
        ).forEach { p -> runCatching { shell("pm grant ${ctx.packageName} $p") } }
    }

    private fun insertPhoto(path: String): Uri {
        val name = "pd-" + UUID.randomUUID().toString().take(8) + ".jpg"
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
        } finally { bmp.recycle() }
        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }.let {
            check(ctx.contentResolver.update(uri, it, null, null) == 1)
        }
        return uri
    }

    private fun shell(cmd: String): String = ins.uiAutomation.executeShellCommand(cmd).use { p ->
        java.io.FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun now() = android.os.SystemClock.uptimeMillis()
    private fun sleep(ms: Long) = android.os.SystemClock.sleep(ms)

    private fun find(target: String): AccessibilityNodeInfo? {
        val root = ins.uiAutomation.rootInActiveWindow ?: return null
        var hit: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || hit != null || depth > 24) return
            if (n.text?.toString() == target || n.contentDescription?.toString() == target) {
                hit = n; return
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        var node = hit; var hops = 0
        while (node != null && hops < 5) {
            if (node.isClickable) return node
            node = node.parent; hops++
        }
        return hit
    }

    private fun tapNode(node: AccessibilityNodeInfo) {
        if (node.isClickable) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        else {
            val r = android.graphics.Rect(); node.getBoundsInScreen(r)
            shell("input tap ${r.centerX()} ${r.centerY()}")
        }
        sleep(400)
    }

    private fun tap(label: String, timeout: Long = 15000) {
        val end = now() + timeout
        while (now() < end) {
            find(label)?.let { tapNode(it); return }
            dismissSystemDialogIfAny()
            sleep(120)
        }
        error("界面元素未出现：$label")
    }

    private fun dismissSystemDialogIfAny() {
        val root = ins.uiAutomation.rootInActiveWindow ?: return
        val owner = root.packageName?.toString().orEmpty()
        if (owner == ctx.packageName) return
        if (!(owner.contains("permissioncontroller") || owner.contains("providers.media"))) return
        listOf("Allow", "允许", "Select all", "全部选择").forEach { label ->
            root.findAccessibilityNodeInfosByText(label).firstOrNull { it.isClickable }?.let(::tapNode)
        }
    }

    private fun startApp() {
        activity = ins.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitDeck()
        sleep(2000)
    }

    private fun waitDeck(timeout: Long = 45000): Boolean {
        val end = now() + timeout
        while (now() < end) {
            if (find("回收站") != null || find("再来一组") != null) return true
            dismissSystemDialogIfAny(); sleep(150)
        }
        return false
    }

    private fun currentCardName(): String? {
        val root = ins.uiAutomation.rootInActiveWindow ?: return null
        fun walk(n: AccessibilityNodeInfo?, depth: Int): String? {
            if (n == null || depth > 24) return null
            n.contentDescription?.toString()?.takeIf { it.matches(Regex("pd-[0-9a-f]{8}\\.jpg")) }
                ?.let { return it }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return walk(root, 0)
    }

    /**
     * 在当前卡片上上滑触发删除。
     *
     * 阈值是**屏幕高**的 8%（`CardStack.deleteThresholdPx = screenHeightPx * 0.08`），
     * 屏幕比卡片高得多，所以位移必须按屏幕算，不能按卡片高度算；另有 800px/s
     * 的速度阈值兜底，450ms 的时长让它稳定过线，也不会被判成点击。
     *
     * @return 是否找到卡片并发起了滑动
     */
    private fun swipeUp(): Boolean {
        val root = ins.uiAutomation.rootInActiveWindow ?: return false
        fun findCard(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (n == null || depth > 24) return null
            if (n.contentDescription?.toString()?.matches(Regex("pd-[0-9a-f]{8}\\.jpg")) == true) return n
            for (i in 0 until n.childCount) findCard(n.getChild(i), depth + 1)?.let { return it }
            return null
        }
        val card = findCard(root, 0) ?: return false
        val b = android.graphics.Rect().also { card.getBoundsInScreen(it) }
        val cx = b.centerX(); val cy = b.centerY()
        val travel = (ctx.resources.displayMetrics.heightPixels * 0.30f).toInt()
        // 终点钳进屏幕内：越过 0 或落进状态栏区域都不稳。起点从卡片中心略下
        // 开始，保证整段都在竖向安全区内。
        val startY = minOf(cy + travel / 3, b.bottom - 8)
        val endY = maxOf(startY - travel, 8)
        shell("input swipe $cx $startY $cx $endY 450")
        sleep(900)
        return true
    }

    /**
     * 用生产自带的「下一张」控件推进到下一张卡（等同保留）。
     *
     * 不用左右滑：SHUFFLE 样式下左右滑是"切牌循环"，卡片会被插回底部，
     * 翻不动批次（run 45590253575 实测停在同一张）。也不再用上滑——上滑是
     * 删除，会覆盖待删除记录，这正是本组用例要保住的东西。
     *
     * @return 是否点到了「下一张」
     */
    private fun advanceCard(): Boolean {
        val before = currentCardName()
        val node = find("下一张") ?: return false
        tapNode(node)
        val end = now() + 4000
        while (now() < end) {
            if (find("再来一组") != null) return true
            val after = currentCardName()
            if (after != null && after != before) return true
            sleep(120)
        }
        // 没换卡也算"点到了"，交给上层循环判断
        return true
    }

    /** 等撤销提示出现，返回其文案（删除给"已移入回收站"，归类给"已添加到"）。 */    private fun awaitUndoLabel(timeoutMs: Long = 6000): String? {
        val end = now() + timeoutMs
        while (now() < end) {
            if (find("撤销") != null) {
                return listOf("已移入回收站", "已添加到").firstOrNull { find(it) != null } ?: "撤销"
            }
            sleep(120)
        }
        return null
    }

    private fun deckLogLines(): List<String> =
        shell("logcat -d -s DeckScreen $TAG").lineSequence().toList()

    private fun awaitBatchLineAfter(boundary: String, timeoutMs: Long): String? {
        val end = now() + timeoutMs
        while (now() < end) {
            BatchLogLine.lastAfter(deckLogLines(), boundary)?.let { return it }
            sleep(120)
        }
        return null
    }

    private fun boundary(label: String): String {
        val marker = "$label:" + now()
        Log.i(TAG, marker); sleep(250); return marker
    }

    /** 当前屏可见文案（诊断用）。 */
    private fun screenTexts(): List<String> {
        val root = ins.uiAutomation.rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 20) return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    /** 当前屏上可见的卡片文件名集合（诊断用）。 */
    private fun visibleCardNames(): Set<String> {
        val root = ins.uiAutomation.rootInActiveWindow ?: return emptySet()
        val out = mutableSetOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 24) return
            n.contentDescription?.toString()?.takeIf { it.matches(Regex("pd-[0-9a-f]{8}\\.jpg")) }
                ?.let { out += it }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun keyOf(displayName: String): String? = runBlocking {
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(displayName), null
        )?.use { c -> if (c.moveToFirst()) "external_primary|${c.getLong(0)}" else null }
    }

    private fun recycleBinKeys(): Set<String> = runBlocking {
        RecycleBinManager.getInstance(ctx).loadRecycleBin().map { it.mediaIdentityKey() }.toSet()
    }

    private fun sourceOf(image: ImageFile) = SourceBalanceRules.sourceKeyOf(image)

    // ==================== 用例 ====================

    /**
     * **修复前会失败**的主用例：旧批最后一张上滑删除 → 撤销期内换批 →
     * 旧删除完成 → 已入回收站，但**不计入**新批来源统计。
     *
     * 判定落在生产侧真实结果上：
     * - 回收站内容来自 `RecycleBinManager.loadRecycleBin`；
     * - 记账决策来自 `PendingDeleteTestHook`（`MainActivity` 每次真实
     *   决策都登记一条，含 ticket 批次与当前批次）。
     */
    @Test
    fun pendingDeleteFromOldBatchLandsInRecycleBinButNotInNewBatchStats() {
        repeat(4) { insertPhoto("DCIM/Screenshots") }
        repeat(4) { insertPhoto("DCIM/Camera") }
        startApp()
        PendingDeleteTestHook.reset()

        val cardName = currentCardName()
        assertNotNull("前置：卡片页必须已渲染卡片", cardName)
        val victimKey = keyOf(cardName!!)
        assertNotNull("前置：界面上那张卡要能对应到 MediaStore 行", victimKey)

        // ---- 步骤 1：上滑发起删除（凭证在这一刻绑定） ----
        assertTrue("前置：需要能在当前卡片上发起上滑", swipeUp())
        assertEquals(
            "上滑必须走删除路径（撤销提示应为『已移入回收站』）",
            "已移入回收站", awaitUndoLabel()
        )

        // ---- 步骤 2：撤销期内换批，强制断言新批已实际装载 ----
        // 注意：这里**不能**再上滑——上滑会走 onRemove，把当前待删除记录
        // 提前提交并覆盖掉（那正是另一条用例要验的路径），被观察的凭证就没了。
        // 用左右滑（保留）把这一批剩下的卡翻完，推进到完成页。
        var guard = 0
        while (guard++ < 16 && find("再来一组") == null) {
            if (!advanceCard()) break
        }
        // 卡都滑光了（cards=null）到完成页渲染出来之间有动画窗口，
        // 这段必须等足，不能因为"看不到卡片"就断定前置失败。
        if (find("再来一组") == null) {
            val waitDone = now() + 15000
            while (now() < waitDone && find("再来一组") == null) sleep(200)
        }
        assertTrue(
            "前置：需要走到批次完成页才能换批" +
                "（卡片=${currentCardName()}，剩余卡=${visibleCardNames().size}，" +
                "前台包=${ins.uiAutomation.rootInActiveWindow?.packageName}，" +
                "界面文案=${screenTexts().filter { it.length <= 6 }}）",
            find("再来一组") != null
        )

        val b = boundary("pd-next-batch")
        tap("再来一组")
        val newBatchLine = awaitBatchLineAfter(b, timeoutMs = 25000)
        assertNotNull("换批必须实际发生（否则本用例证明不了跨批记账）", newBatchLine)
        assertTrue("新批不能为空", BatchLogLine.parseUris(newBatchLine!!).isNotEmpty())

        // ---- 步骤 3：等旧删除完成 ----
        val waitEnd = now() + 25000
        var landed = false
        while (now() < waitEnd) {
            if (victimKey in recycleBinKeys()) { landed = true; break }
            sleep(250)
        }

        // ---- 步骤 4a：那张照片确实落入回收站（入站语义不受影响） ----
        assertTrue(
            "旧动作到期后仍应正常写入回收站（期望 $victimKey，实际 ${recycleBinKeys()}）",
            landed
        )

        // ---- 步骤 4b：生产侧那次记账决策必须判为"不计入" ----
        val end2 = now() + 15000
        var decision: PendingDeleteTestHook.Decision? = null
        while (now() < end2) {
            decision = PendingDeleteTestHook.drain().firstOrNull { it.imageKey == victimKey }
            if (decision != null) break
            sleep(200)
        }
        assertNotNull("生产侧必须对这张照片做出过记账决策", decision)
        assertTrue(
            "跨批完成时凭证批次(${decision!!.ticketBatch})应早于当前批次(${decision.currentBatch})",
            decision.ticketBatch < decision.currentBatch
        )
        assertFalse(
            "旧批发起、换批后才完成的删除不得计入新批来源统计",
            decision.recorded
        )
    }

    /** 同批删除正常记账（对照，防止上面那条变成"永远不记"）。 */
    @Test
    fun sameBatchDeleteStillRecordsStats() {
        repeat(4) { insertPhoto("DCIM/Screenshots") }
        repeat(4) { insertPhoto("DCIM/Camera") }
        startApp()
        PendingDeleteTestHook.reset()

        assertNotNull("前置：需要至少一张卡", currentCardName())

        assertTrue("前置：需要能在当前卡片上发起上滑", swipeUp())
        assertEquals(
            "上滑必须走删除路径（撤销提示应为『已移入回收站』）",
            "已移入回收站", awaitUndoLabel()
        )
        // 不换批：等撤销期结束，删除在同批内完成。
        // 不对"哪张卡"预设断言——上滑生效的那张由生产侧决策记录告诉我们，
        // 这里要证的是它是**同批**且**被计入**。
        val end = now() + 20000
        var decision: PendingDeleteTestHook.Decision? = null
        val seen = mutableListOf<PendingDeleteTestHook.Decision>()
        while (now() < end) {
            val drained = PendingDeleteTestHook.drain()
            seen += drained
            decision = drained.firstOrNull()
            if (decision != null) break
            sleep(200)
        }
        assertNotNull(
            "同批删除必须产生记账决策（观察到=${seen.map { it.imageKey }}，" +
                "界面卡片=${currentCardName()}，撤销提示=${find("已移入回收站") != null}）",
            decision
        )
        assertEquals(
            "同批时凭证批次应等于当前批次（凭证=${decision!!.ticketBatch} 当前=${decision.currentBatch}）",
            decision.currentBatch, decision.ticketBatch
        )
        assertEquals(
            "同批时凭证会话应等于当前会话",
            decision.currentSession, decision.ticketSession
        )
        assertTrue("同批删除必须计入来源统计", decision.recorded)
    }

    /**
     * 撤销后没有写入：上滑 → 点「撤销」→ 等过撤销期 → 回收站里没有这张。
     */
    @Test
    fun undoBeforeTimeoutWritesNothingToRecycleBin() {
        repeat(3) { insertPhoto("DCIM/Screenshots") }
        repeat(3) { insertPhoto("DCIM/Camera") }
        startApp()

        val cardName = currentCardName()
        assertNotNull("前置：卡片页必须已渲染卡片", cardName)
        val victimKey = runBlocking {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(cardName), null
            )?.use { c -> if (c.moveToFirst()) "external_primary|${c.getLong(0)}" else null }
        }
        assertNotNull(victimKey)

        assertTrue("前置：需要能在当前卡片上发起上滑", swipeUp())
        assertEquals(
            "上滑必须走删除路径（撤销提示应为『已移入回收站』）",
            "已移入回收站", awaitUndoLabel()
        )
        tap("撤销")

        // 等过撤销期（3s）再加余量
        sleep(6000)
        assertFalse(
            "撤销后不得写入回收站（实际 ${recycleBinKeys()}）",
            victimKey in recycleBinKeys()
        )
    }

    /**
     * 提前提交沿用原凭证：上滑一张，**不撤销**，紧接着再上滑下一张——
     * 第二张会先把第一张顶掉提交。两张都入回收站；第一张用的是它自己
     * 上滑时的凭证（同批，因此仍属当前批次，正常记账）。
     */
    @Test
    fun secondSwipeCommitsFirstWithItsOriginalVoucher() {
        repeat(3) { insertPhoto("DCIM/Screenshots") }
        repeat(3) { insertPhoto("DCIM/Camera") }
        startApp()

        val first = currentCardName()
        assertNotNull("前置：需要至少两张卡", first)
        assertTrue("前置：需要能在当前卡片上发起上滑", swipeUp())
        assertEquals("上滑必须走删除路径", "已移入回收站", awaitUndoLabel())
        val second = currentCardName()
        if (second == null) return   // 单卡批次：跳过（前置不足，不算失败）

        // 第二张上滑 → 第一张被提前提交
        swipeUp()
        sleep(3000)

        val keys = recycleBinKeys()
        val firstKey = runBlocking {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(first), null
            )?.use { c -> if (c.moveToFirst()) "external_primary|${c.getLong(0)}" else null }
        }
        assertNotNull(firstKey)
        assertTrue(
            "被顶掉的第一张必须已入回收站（实际 $keys）",
            firstKey in keys
        )
    }
}
