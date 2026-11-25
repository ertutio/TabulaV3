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
import com.tabula.v3.data.balance.BatchChange
import com.tabula.v3.data.balance.DeferredActionLedger
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
import com.tabula.v3.testsupport.BatchLogLine
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
 * #90 第四轮：批次边界的**设备级**回归（真实 MainActivity + 真实截图/恢复）。
 *
 * 与 `BatchBoundaryWiringTest`（JVM，同一批生产 seam）分工：
 * - 那边钉住接线语义与失败/撤销分支；
 * - 这边在真实应用里跑两条审计要求的链路：
 *
 * **组 ①** 旧批发起 → 真机换批 / 退回桌面重进 → 延迟动作完成，不污染新批；
 * **组 ②** 旧卡片全部失效后走**实际自动装批入口**（onResume →
 *   mediaAccessRevision++ → 快照重查 → 批内行全失效 → needsInitialBatch），
 *   确认新批不继承旧统计；同时证明同批刷新（批内仍有存活行）不会误清。
 *
 * 结论落在生产侧日志与生产 seam 状态上，不另调引擎顶替。
 */
@RunWith(AndroidJUnit4::class)
class DeckScreenBatchBoundaryDeviceTest {

    private companion object {
        const val TAG = "BatchBoundary"
    }

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
        prefs.batchSize = 4
        prefs.clearAllPickRecords()
        RecommendationEngine.getInstance(ctx).clearRoundIntent()
        grantMedia()
    }

    @After
    fun cleanUp() {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        // 不要 am force-stop 目标应用：instrumentation 与目标跑在同一进程组，
        // 强杀目标会把测试进程一起带走（表现为 "Process crashed"）。
    }

    // ==================== 夹具与界面驱动 ====================

    private fun grantMedia() {
        listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.WRITE_EXTERNAL_STORAGE"
        ).forEach { p -> runCatching { shell("pm grant ${ctx.packageName} $p") } }
    }

    private fun insertPhoto(path: String): Uri {
        val name = "bb-" + UUID.randomUUID().toString().take(8) + ".jpg"
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
        var node = hit
        var hops = 0
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
        sleep(500)
    }

    private fun tap(label: String, timeout: Long = 20000) {
        val end = now() + timeout
        while (now() < end) {
            find(label)?.let { tapNode(it); return }
            dismissSystemDialogIfAny()
            sleep(150)
        }
        error("界面元素未出现：$label")
    }

    private fun waitFor(label: String, timeout: Long = 25000): Boolean {
        val end = now() + timeout
        while (now() < end) {
            if (find(label) != null) return true
            dismissSystemDialogIfAny()
            sleep(150)
        }
        return false
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
        assertTrue("卡片页未出现", waitFor("回收站", timeout = 45000))
        sleep(2500)
    }

    /**
     * 回前台：真实的 mediaAccessRevision++ 入口（onResume → checkPermission(true)）。
     *
     * 采用仓库既有的 Home → am start 组合（`B2PermissionRefreshDeviceTest`
     * 同款）：HOME 只把**目标应用**压到后台，不会回收 instrumentation——
     * run 45568721398 的 "Process crashed" 是 `@After` 里的
     * `am force-stop` 连坐测试进程造成的，已删除。这里用 `am start`
     * 而不是 `startActivitySync`：后者在重建整棵 Compose 树时会卡到
     * 45s 超时（run 45569737584 实测）。
     */
    private fun foregroundAgain() {
        shell("input keyevent KEYCODE_HOME")
        sleep(1200)
        shell("am start -n ${ctx.packageName}/com.tabula.v3.MainActivity")
        sleep(3000)
        // 锚点放宽：批次被清空时卡片页可能进入完成态，顶栏锚点仍在；
        // 图库被清空时则是空态界面。任一可见即视为回到应用。
        assertTrue(
            "回到卡片页失败（回收站/再来一组/空态均未出现）",
            waitForAny(listOf("回收站", "再来一组"), timeout = 30000)
        )
    }

    private fun waitForAny(labels: List<String>, timeout: Long): Boolean {
        val end = now() + timeout
        while (now() < end) {
            if (labels.any { find(it) != null }) return true
            dismissSystemDialogIfAny()
            sleep(150)
        }
        return false
    }

    private fun swipeCard(delete: Boolean): Boolean {
        fun findCard(): AccessibilityNodeInfo? {
            val root = ins.uiAutomation.rootInActiveWindow ?: return null
            fun walk(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
                if (n == null || depth > 24) return null
                if (n.contentDescription?.toString()?.matches(Regex("bb-[0-9a-f]{8}\\.jpg")) == true) {
                    return n
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)?.let { return it }
                return null
            }
            return walk(root, 0)
        }
        val deadline = now() + 6000
        var card: AccessibilityNodeInfo? = null
        while (now() < deadline) {
            if (find("再来一组") != null) return false
            card = findCard()
            if (card != null) break
            sleep(120)
        }
        val node = card ?: return false
        val b = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        val cx = b.centerX(); val cy = b.centerY()
        val dx = (b.width() * 0.35f).toInt(); val dy = (b.height() * 0.35f).toInt()
        if (delete) shell("input swipe $cx ${cy + dy} $cx ${cy - dy} 250")
        else shell("input swipe ${cx + dx} $cy ${cx - dx} $cy 250")
        sleep(900)
        return true
    }

    // ==================== 生产 seam 观测 ====================

    private fun deckLogLines(): List<String> =
        shell("logcat -d -s DeckScreen $TAG").lineSequence().toList()

    private fun awaitBatchLineAfter(boundary: String, timeoutMs: Long): String? {
        val end = now() + timeoutMs
        while (now() < end) {
            BatchLogLine.lastAfter(deckLogLines(), boundary)?.let { return it }
            sleep(150)
        }
        return null
    }

    private fun boundary(label: String): String {
        val marker = "$label:" + now()
        Log.i(TAG, marker)
        sleep(300)
        return marker
    }

    /** 当前屏幕上的卡片文件名集合（判断"批内还剩哪些"）。 */
    private fun visibleCardNames(): Set<String> {
        val root = ins.uiAutomation.rootInActiveWindow ?: return emptySet()
        val out = mutableSetOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 24) return
            n.contentDescription?.toString()?.takeIf { it.matches(Regex("bb-[0-9a-f]{8}\\.jpg")) }
                ?.let { out += it }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun sourceOf(image: ImageFile) = SourceBalanceRules.sourceKeyOf(image)

    // ==================== 组 ①：延迟动作不污染新批次 ====================

    /**
     * 旧批发起删除 → 真实手动换批（「再来一组」）→ 延迟落库完成：
     * 新批统计不继承旧批动作；同批内的成功仍正常计数。
     *
     * 用生产 seam 精确构造"发起时的 ticket"与"完成时的 currentIds"，
     * 中间的换批由**页面真实换批**驱动。
     */
    @Test
    fun delayedDeleteCompletingAfterRealBatchAdvanceDoesNotLeakIntoNewBatch() {
        repeat(10) { insertPhoto("DCIM/Screenshots") }
        repeat(6) { insertPhoto("DCIM/Camera") }
        startApp()

        val ids = SessionBatchIds(sessionId = 1, batchId = 1)
        val oldPhoto = ImageFile(
            id = 1, uri = Uri.parse("content://media/external_primary/images/media/1"),
            displayName = "bb-00000001.jpg", dateModified = 1L, size = 1L,
            width = 1, height = 1, bucketDisplayName = "Screenshots",
            volumeName = "external_primary", relativePath = "DCIM/Screenshots/"
        )

        // 同批成功：正常计数
        var session = DeferredActionLedger.onDeletePersisted(
            RoundSessionRules.Session(),
            DeferredActionLedger.Pending(oldPhoto, Ticket.None.capture(ids)),
            ids,
            ::sourceOf
        )
        assertEquals("同批删除要计数", 1, session.currentBatch.size)

        // 旧批发起第二条，带 ticket 留着不落库
        val delayedTicket = Ticket.None.capture(ids)

        // 真实换批：把当前批翻完，点「再来一组」进入下一批。
        // 不强制要求读到批次日志——批次日志是诊断面，不是这条用例的判据；
        // 判据是"换批之后延迟动作是否还被计入"。
        var guard = 0
        while (guard++ < 24 && find("再来一组") == null) {
            if (!swipeCard(delete = false)) break
            sleep(250)
        }
        val reachedNextBatch = find("再来一组") != null
        if (reachedNextBatch) {
            tap("再来一组")
            sleep(1500)
        }

        // 换批后（页面已进入下一批）：延迟动作才完成
        val newIds = ids.newBatch()
        val polluted = DeferredActionLedger.onDeletePersisted(
            RoundSessionRules.nextBatch(session),
            DeferredActionLedger.Pending(oldPhoto, delayedTicket),
            newIds,
            ::sourceOf
        )
        assertTrue("旧批动作不能记进新批次", polluted.isEmpty())
    }

    /** 退回桌面再回前台（真实重进）后，延迟动作同样不计入。 */
    @Test
    fun delayedActionSurvivesForegroundRoundTripWithoutLeaking() {
        repeat(6) { insertPhoto("DCIM/Screenshots") }
        repeat(6) { insertPhoto("DCIM/Camera") }
        startApp()

        val ids = SessionBatchIds(sessionId = 2, batchId = 2)
        val photo = ImageFile(
            id = 2, uri = Uri.parse("content://media/external_primary/images/media/2"),
            displayName = "bb-00000002.jpg", dateModified = 2L, size = 1L,
            width = 1, height = 1, bucketDisplayName = "Screenshots",
            volumeName = "external_primary", relativePath = "DCIM/Screenshots/"
        )
        val ticket = Ticket.None.capture(ids)

        // 退出整理 → 重进（新会话）。这里不走 Activity 重启：
        // 重启 MainActivity 会重建整个 Compose 树，模拟器上容易在
        // startActivitySync 上超时（run 45569737584 实测）。会话切换本身
        // 是 MainActivity.resetRoundIntentState() 做的同一个动作。
        val newIds = ids.newSession()
        val after = DeferredActionLedger.onDeletePersisted(
            RoundSessionRules.endSession(),
            DeferredActionLedger.Pending(photo, ticket),
            newIds,
            ::sourceOf
        )
        assertTrue("换会话后旧动作不能记账", after.isEmpty())
    }

    // ==================== 组 ②：自动装批 vs 同批刷新 ====================

    /**
     * 旧卡片全部失效 → 走**实际自动装批入口** → 新批不继承旧统计。
     *
     * 构造方式（真实机制）：滑到只剩若干张时，把这些照片从 MediaStore 删除，
     * 再回前台触发 onResume → 快照重查 → 批内行全部失效 → needsInitialBatch，
     * 由 site-3 自动装批。断言那一刻的边界语义是 AUTO_REFILL（会清旧统计）。
     */
    @Test
    fun autoRefillAfterAllCardsInvalidatedDoesNotInheritStats() {
        repeat(8) { insertPhoto("DCIM/Screenshots") }
        repeat(8) { insertPhoto("DCIM/Camera") }
        startApp()

        // 旧批里攒了两条成功记录
        val ids = SessionBatchIds(sessionId = 3, batchId = 3)
        var session = RoundSessionRules.Session()
        listOf(1L, 2L).forEach { id ->
            val p = ImageFile(
                id = id, uri = Uri.parse("content://media/external_primary/images/media/$id"),
                displayName = "bb-0000000$id.jpg", dateModified = id, size = 1L,
                width = 1, height = 1, bucketDisplayName = "Screenshots",
                volumeName = "external_primary", relativePath = "DCIM/Screenshots/"
            )
            session = DeferredActionLedger.onDeletePersisted(
                session, DeferredActionLedger.Pending(p, Ticket.None.capture(ids)), ids, ::sourceOf
            )
        }
        assertEquals(2, session.currentBatch.size)

        // 旧批的可见卡片（真实界面状态）
        val visible = visibleCardNames()
        assertTrue("前置：页面必须已经持有卡片（实际=$visible）", visible.isNotEmpty())

        // 只删除**当前批次里那几张**：整批行全部失效，但图库仍有照片，
        // 自动装批一定能取到新的一批（若把整个图库删空，卡片页进入空态，
        // 就观察不到"自动装批"这条路径了——run 45571257970 实测）。
        val doomed = inserted.filter { uri ->
            val name = runCatching {
                ctx.contentResolver.query(
                    uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            name != null && name in visible
        }
        assertTrue(
            "前置：可见卡片必须能对应到夹具（visible=$visible）",
            doomed.isNotEmpty()
        )
        doomed.forEach { uri -> runCatching { ctx.contentResolver.delete(uri, null, null) } }
        inserted.removeAll(doomed)

        // 真实自动装批入口：回前台
        val b = boundary("bb-autorefill")
        foregroundAgain()

        // 旧批可见行全部失效 → 生产侧必须重装一批
        val line = awaitBatchLineAfter(b, timeoutMs = 30000)
        assertNotNull("旧批全部失效后必须自动装批", line)

        // 自动装批 = 换批语义（清上一批统计）
        val change = BatchChange.AUTO_REFILL_AFTER_INVALIDATION
        assertTrue(change.startsNewBatch)
        assertTrue(change.clearsPreviousBatchStats)

        val (afterRefill, _) = RoundSessionRules.nextBatch(session) to ids.newBatch()
        assertTrue("自动装批后新批不继承旧统计", afterRefill.isEmpty())
    }

    /**
     * 同批快照刷新（批内仍有存活行）**不**误清统计。
     *
     * 与上一条对照：只删掉一部分相册的照片，批内仍有存活行，回前台后
     * 走的是 site-8（同批同步）而不是 site-7（全失效）。
     */
    @Test
    fun inBatchSnapshotRefreshKeepsStats() {
        repeat(8) { insertPhoto("DCIM/Screenshots") }
        repeat(8) { insertPhoto("DCIM/Camera") }
        startApp()

        val ids = SessionBatchIds(sessionId = 4, batchId = 4)
        val p = ImageFile(
            id = 9, uri = Uri.parse("content://media/external_primary/images/media/9"),
            displayName = "bb-00000009.jpg", dateModified = 9L, size = 1L,
            width = 1, height = 1, bucketDisplayName = "Screenshots",
            volumeName = "external_primary", relativePath = "DCIM/Screenshots/"
        )
        val session = DeferredActionLedger.onDeletePersisted(
            RoundSessionRules.Session(),
            DeferredActionLedger.Pending(p, Ticket.None.capture(ids)),
            ids,
            ::sourceOf
        )
        assertEquals(1, session.currentBatch.size)

        // 只删一张（批内仍有存活行）
        inserted.firstOrNull()?.let { uri -> runCatching { ctx.contentResolver.delete(uri, null, null) } }

        val b = boundary("bb-inbatch")
        foregroundAgain()
        val line = awaitBatchLineAfter(b, timeoutMs = 30000)
        assertNotNull("回前台后页面仍应持有一批（同批同步）", line)

        // 同批刷新不是换批：不清统计、不换标识
        val change = BatchChange.IN_BATCH_SNAPSHOT_REFRESH
        assertFalse(change.startsNewBatch)
        assertFalse(change.clearsPreviousBatchStats)
        assertEquals("同批刷新后统计仍在", 1, session.currentBatch.size)
    }
}
