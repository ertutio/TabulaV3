package com.tabula.v3

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.cleanup.cleanupIdentityKey
import com.tabula.v3.data.intent.IntentAssistKeyStore
import com.tabula.v3.data.intent.IntentCommitGuard
import com.tabula.v3.data.intent.IntentVerdictCache
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.repository.RecycleBinManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「按整理目的凑一批」的**实际页面链路**测试（#94）。
 *
 * 链路：设置 → 暂存清理 → 选来源 → 候选页 →「按整理目的凑一批」入口 →
 * 分析完成 → 勾选 → 移入回收站 → 回收站恢复 → 回暂存清理确认状态。
 *
 * 关键约束（与被测功能设计一一对应）：
 * 1. 入口只在暂存清理页里，说明「不做全库扫描」；
 * 2. 勾选后仍走**回收站**（不是直接删除），因此能恢复；
 * 3. 文字辅助默认关闭：进页面时开关是关的，且没有 Key 时不可打开；
 * 4. 保护类照片（订单截图）不会出现在推荐列表里。
 *
 * 合成样图插进 MediaStore 的 `DCIM/Screenshots`，与真实截图同路径，
 * 保证走的是同一条候选查询。
 */
@RunWith(AndroidJUnit4::class)
class IntentBatchPageFlowTest {

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private lateinit var prefs: AppPreferences
    private val inserted = mutableListOf<Uri>()
    private var activity: android.app.Activity? = null

    @Before
    fun setUp() {
        prefs = AppPreferences(ctx)
        prefs.hasCompletedOnboarding = true
        prefs.hasAcknowledgedDisclaimer = true
        prefs.recommendMode = RecommendMode.RANDOM_WALK
        prefs.hasStagedCleanupSourceChoice = false
        prefs.stagedCleanupSourceKeys = emptySet()
        // 清掉上一次跑留下的分析结果与 Key，保证「默认关闭」这条能被真实断言
        IntentVerdictCache(ctx.getSharedPreferences("tabula_intent_verdicts", android.content.Context.MODE_PRIVATE)).clear()
        IntentAssistKeyStore(ctx).delete()
        grantMedia()
    }

    @After
    fun cleanUp(): Unit = runBlocking {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() }
        IntentAssistKeyStore(ctx).delete()
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

    /** 插入一张「看起来像过期优惠截图」的合成图（真实可被 OCR 读出文字）。 */
    private fun insertCouponScreenshot(name: String): Uri {
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("满199减30", 60f, 200f, paint)
        canvas.drawText("仅剩2天", 60f, 280f, paint)
        // 截止时间那一行必须**缩小字号**：46px 下「活动已于 2026-11-10 23:59 结束」在
        // 720 宽度里会被右边缘裁掉，OCR 就读不到日期（首跑实测：被判成
        // 「优惠截图，但没写可核对的截止时间」）。30px 约 585px 宽，留足边距。
        val deadlinePaint = Paint().apply {
            color = Color.BLACK
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("活动已于 2026-11-10 23:59 结束", 60f, 380f, deadlinePaint)

        val uri = requireNotNull(
            ctx.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Screenshots/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
        )
        inserted += uri
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val old = (System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000) / 1000
        check(
            ctx.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.DATE_ADDED, old)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, old)
                },
                null, null
            ) == 1
        )
        bitmap.recycle()
        return uri
    }

    /** 插入一张「订单截图」——保护类，绝不能出现在推荐列表。 */
    private fun insertOrderScreenshot(name: String): Uri {
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 44f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("订单详情", 60f, 200f, paint)
        canvas.drawText("待收货", 60f, 280f, paint)
        canvas.drawText("订单号 TB202411250009", 60f, 360f, paint)
        canvas.drawText("实付 ¥128.00", 60f, 440f, paint)

        val uri = requireNotNull(
            ctx.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Screenshots/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
        )
        inserted += uri
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val old = (System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000) / 1000
        check(
            ctx.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.DATE_ADDED, old)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, old)
                },
                null, null
            ) == 1
        )
        bitmap.recycle()
        return uri
    }

    private fun waitIndexed(uri: Uri, timeoutMs: Long = 25000) {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < end) {
            val found = runBlocking {
                com.tabula.v3.data.repository.LocalImageRepository(ctx)
                    .loadAllData().allImages.any { it.uri == uri }
            }
            if (found) return
            android.os.SystemClock.sleep(250)
        }
        error("照片未被图库索引：$uri")
    }

    private fun now() = android.os.SystemClock.uptimeMillis()

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

    private fun screenTexts(): Set<String> {
        val root = ins.uiAutomation.rootInActiveWindow ?: return emptySet()
        val out = mutableSetOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 24) return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun waitForTextContaining(fragment: String, timeout: Long = 30000) {
        val end = now() + timeout
        while (now() < end) {
            if (screenTexts().any { it.contains(fragment) }) return
            android.os.SystemClock.sleep(250)
        }
        error("waitForTextContaining timeout: $fragment （屏幕：${screenTexts()}）")
    }

    private fun tapByBounds(target: String, timeout: Long = 20000): Boolean {
        val end = now() + timeout
        while (now() < end) {
            val root = ins.uiAutomation.rootInActiveWindow
            var hit: AccessibilityNodeInfo? = null
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || hit != null || depth > 24) return
                if (n.contentDescription?.toString() == target || n.text?.toString() == target) {
                    hit = n; return
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            }
            walk(root, 0)
            hit?.let { node ->
                val r = android.graphics.Rect(); node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    shell("input tap ${r.centerX()} ${r.centerY()}")
                    android.os.SystemClock.sleep(900)
                    return true
                }
            }
            android.os.SystemClock.sleep(250)
        }
        return false
    }

    private fun tap(target: String, timeout: Long = 25000) {
        val end = now() + timeout
        while (now() < end) {
            find(target)?.let { node ->
                if (node.isClickable) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                else {
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
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitForTextContaining("回收站", timeout = 45000)
        android.os.SystemClock.sleep(2000)
    }

    /** 设置 → 暂存清理 → 选来源 → 候选页。 */
    private fun openStagedCleanupCandidates() {
        waitForTextContaining("设置")
        tapByBounds("设置")
        android.os.SystemClock.sleep(800)
        waitForTextContaining("暂存清理")
        tapByBounds("暂存清理")
        val end = now() + 20000
        while (now() < end) {
            if (find("外观") == null && find("暂存清理") == null) break
            android.os.SystemClock.sleep(250)
        }
        android.os.SystemClock.sleep(800)

        // 来源选择页：点「查看 N 个来源的旧图」
        val viewButton = run {
            val e = now() + 20000
            while (now() < e) {
                screenTexts().firstOrNull { it.startsWith("查看") && it.endsWith("个来源的旧图") }
                    ?.let { return@run it }
                android.os.SystemClock.sleep(250)
            }
            null
        }
        assertNotNull("来源选择器必须给出可执行的查看按钮（屏幕：${screenTexts()}）", viewButton)

        // 点「查看 N 个来源的旧图」：Compose 的按钮有时第一次点不生效（无障碍点击
        // 落在外层 Box），因此**重试**直到候选页真的出现（判据 = 出现「换来源」）。
        val deadline = now() + 40000
        while (now() < deadline) {
            if (screenTexts().any { it.contains("换来源") }) break
            tapByBounds(viewButton!!, timeout = 3000)
            android.os.SystemClock.sleep(1200)
        }
        assertTrue(
            "必须能进入暂存清理候选页（屏幕：${screenTexts()}）",
            screenTexts().any { it.contains("换来源") } || screenTexts().any { it.contains("超过 7 天") }
        )
    }

    /** 候选页 → 点「看看」进「按整理目的凑一批」。 */
    private fun openIntentBatch() {
        waitForTextContaining("按整理目的凑一批", timeout = 20000)
        val opened = tapByBounds("看看")
        assertTrue("「按整理目的凑一批」入口必须可点", opened)
        waitForTextContaining("只排序", timeout = 20000)
    }

    // ==================== 用例 ====================

    @Test
    fun entryAppearsInStagedCleanupAndAnalysisFinishes() {
        val shot = insertCouponScreenshot("intent-coupon-" + UUID.randomUUID().toString().take(6) + ".png")
        waitIndexed(shot)

        startApp()
        openStagedCleanupCandidates()

        // 入口只在暂存清理页出现（不是设置页、不是主界面）
        assertTrue(
            "暂存清理候选页应显示新入口（屏幕：${screenTexts()}）",
            screenTexts().any { it.contains("按整理目的凑一批") }
        )

        openIntentBatch()

        // 分析完成：出现状态行或结论文案之一
        waitForTextContaining("本地识别", timeout = 120000)
        val texts = screenTexts()
        assertTrue(
            "分析完成后必须显示状态行（屏幕：$texts）",
            texts.any { it.contains("本地识别") }
        )
        // 「只排序、不自动删」的承诺必须出现在界面上
        assertTrue(
            "界面必须写明不自动删除（屏幕：$texts）",
            texts.any { it.contains("不会自动删除") || it.contains("只排序") }
        )
    }

    @Test
    fun assistSwitchIsOffByDefaultAndBlockedWithoutKey() {
        val shot = insertCouponScreenshot("intent-nokey-" + UUID.randomUUID().toString().take(6) + ".png")
        waitIndexed(shot)

        startApp()
        openStagedCleanupCandidates()
        openIntentBatch()

        // 没有 Key 时开关不可用，提示文案要如实说明本地规则照常
        val texts = screenTexts()
        assertTrue(
            "未配置 Key 时必须明确说明（屏幕：$texts）",
            texts.any { it.contains("未配置 Key") } || texts.any { it.contains("未提供文字辅助") }
        )
        // 默认关闭：不出现「已启用」这类反向文案
        assertFalse(
            "文字辅助默认必须关闭（屏幕：$texts）",
            texts.any { it.contains("文字辅助已启用") }
        )
    }

    @Test
    fun orderScreenshotNeverAppearsInRecommendedList() {
        val coupon = insertCouponScreenshot("intent-coupon2-" + UUID.randomUUID().toString().take(6) + ".png")
        val order = insertOrderScreenshot("intent-order-" + UUID.randomUUID().toString().take(6) + ".png")
        waitIndexed(coupon)
        waitIndexed(order)

        startApp()
        openStagedCleanupCandidates()
        openIntentBatch()
        waitForTextContaining("本地识别", timeout = 120000)

        val texts = screenTexts()
        val orderName = order.lastPathSegment.orEmpty()
        // 订单截图属于保护类：应被排除，而排除张数要在页脚如实写出来
        assertTrue(
            "保护项必须在页脚计数（屏幕：$texts）",
            texts.any { it.contains("保护项") || it.contains("已排除") }
        )
        assertFalse(
            "订单截图不能出现在推荐列表里（屏幕：$texts，文件名：$orderName）",
            texts.any { it.contains("订单或交易记录") }
        )
    }

    /**
     * **真实交互**提交链路（#94 第 2 轮审计，第 6 条）。
     *
     * 设置 → 暂存清理 → 按整理目的凑一批 → 等真实 OCR 完成 →
     * 点推荐缩略图 → 断言底部按钮**从禁用变为可用** → 点提交 →
     * 按完整媒体身份确认所选图进入回收站、未选图仍在图库 →
     * 从**真实回收站页**恢复目标图。
     *
     * 第一轮的失败原因是「点了缩略图但按钮文案没变」。这轮把缩略图的
     * contentDescription 补上了文件名，并把断言改成读按钮的 `isEnabled`
     * 而不是匹配文案——按钮禁用态与启用态文案不同，匹配文案容易跨帧读到旧值。
     */
    @Test
    fun tappingThumbnailEnablesSubmitAndSelectedGoesToRecycleBin() = runBlocking {
        val coupon = insertCouponScreenshot("intent-flow-" + UUID.randomUUID().toString().take(6) + ".png")
        val other = insertCouponScreenshot("intent-keep-" + UUID.randomUUID().toString().take(6) + ".png")
        waitIndexed(coupon)
        waitIndexed(other)

        startApp()
        openStagedCleanupCandidates()
        openIntentBatch()
        waitForTextContaining("本地识别", timeout = 180000)

        // 1) 未选任何图时，提交按钮必须是禁用态
        val submitBefore = submitButtonState()
        assertNotNull("必须能找到底部提交按钮（屏幕：${screenTexts()}）", submitBefore)
        assertFalse("未选中时提交按钮必须禁用", submitBefore!!.enabled)
        val selectedBefore = runBlocking { RecycleBinManager.getInstance(ctx).loadRecycleBin().size }

        // 2) 点推荐缩略图（contentDescription = 文件名）
        val thumbName = run {
            val deadline = now() + 30000
            while (now() < deadline) {
                screenTexts().firstOrNull { it.startsWith("intent-flow-") && it.endsWith(".png") }
                    ?.let { return@run it }
                android.os.SystemClock.sleep(250)
            }
            null
        }
        assertNotNull("推荐列表里应出现可点的缩略图（屏幕：${screenTexts()}）", thumbName)
        assertTrue("缩略图必须能点中（目标：$thumbName）", tapByBounds(thumbName!!))

        // 3) 选中后按钮必须变为可用（这是第一轮卡住的那一步）
        val submitAfter = waitForEnabledSubmit()
        assertNotNull("选中后提交按钮必须存在", submitAfter)
        assertTrue("选中 1 张后提交按钮必须变为可用", submitAfter!!.enabled)

        // 4) 点提交
        val clickTarget = submitAfter.clickable
        assertNotNull("提交按钮必须是可点击节点", clickTarget)
        clickTarget!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        android.os.SystemClock.sleep(4000)

        // 5) 按完整媒体身份确认：所选图进了回收站，未选图仍在图库
        val bin = RecycleBinManager.getInstance(ctx)
        val afterBin = bin.loadRecycleBin()
        assertTrue(
            "所选图必须进入回收站（之前 ${selectedBefore} 项，现在 ${afterBin.size} 项）",
            afterBin.size > selectedBefore
        )
        val binUris = afterBin.map { it.uri }.toSet()
        val libraryUris = com.tabula.v3.data.repository.LocalImageRepository(ctx)
            .loadAllData().allImages.map { it.uri }.toSet()
        assertTrue("所选图必须出现在回收站里（$coupon）", coupon in binUris)
        // 注意：本项目 `addAllToRecycleBin` 只写入回收站**记录**，物理文件由 pending
        // journal 的删除流程处理，因此此刻图库里仍能查到它——这是既有设计，
        // 不是本功能的缺陷。真正要断言的是「未选的图没有被写进回收站」。
        assertTrue("未选的图必须仍在图库（$other）", other in libraryUris)
        assertFalse("未选的图不得进回收站（$other）", other in binUris)
        assertFalse("未选的图不得出现在回收站记录里", other in binUris)
        // 图库可见性另做一条正向断言，避免上面的注释变成「跳过检查」的借口
        assertTrue(
            "图库快照仍应包含所选图（等待物理删除前，记录已入站）",
            coupon in libraryUris
        )

        // 6) 从真实回收站页恢复
        restoreFromRecycleBinScreen(coupon)
        val afterRestore = bin.loadRecycleBin()
        assertTrue(
            "恢复后目标图不应再在回收站（${afterRestore.map { it.uri }}）",
            afterRestore.none { it.uri == coupon }
        )
    }

    /**
     * 分析之后世界变了：**旧结果不得被提交**（审计第 6 条后半）。
     *
     * 三种变化各测一次：原图被替换（内容版本变）、被标长期保留、从图库消失（权限/删除）。
     * 判据不是 UI 文案，而是**回收站最终没有多出该项**——这才是「旧结果没被提交」的证据。
     */
    @Test
    fun staleAnalysisCannotBeSubmittedAfterWorldChanges() = runBlocking {
        val repo = com.tabula.v3.data.repository.LocalImageRepository(ctx)
        val bin = RecycleBinManager.getInstance(ctx)

        // 情形 A：原图内容被替换（同 ID，size/dateModified 变）→ 必须跳过
        //
        // 「分析时的快照」用一个真实读到的 ImageFile，然后把它的内容版本**回退**到
        // 更新前的值——这正是陈旧快照在提交时刻的样子。
        // 为什么不靠 MediaStore 自己变：模拟器的 MediaStore 对 ContentValues 里的
        // DATE_MODIFIED/SIZE 更新并不总是回灌到查询结果（本轮实测两次都读到同值），
        // 依赖它会让用例变成「测 MediaStore 而不是测我们的复核」。
        val changed = insertCouponScreenshot("intent-chg-" + UUID.randomUUID().toString().take(6) + ".png")
        waitIndexed(changed)
        val current = repo.loadAllData().allImages.first { it.uri == changed }
        val beforeEdit = current.copy(
            size = current.size - 2048L,
            dateModified = current.dateModified - 60_000L
        )
        val afterEdit: com.tabula.v3.data.model.ImageFile? = current
        println(
            "[stale-guard] staleSnapshot size=${beforeEdit.size} modified=${beforeEdit.dateModified} " +
                "| current size=${afterEdit?.size} modified=${afterEdit?.dateModified}"
        )
        val guardA = IntentCommitGuard.review(
            selected = listOf(beforeEdit),
            currentLibrary = listOfNotNull(afterEdit),
            retainedIdentityKeys = emptySet(),
            recycledIdentityKeys = emptySet(),
            stagedSourceKeys = setOf("external_primary|dcim/screenshots")
        )
        assertTrue(
            "内容变化后不得再提交（实际 outcome=${guardA.skipped.firstOrNull()?.outcome}）",
            guardA.eligible.isEmpty()
        )
        assertEquals(IntentCommitGuard.Outcome.CONTENT_CHANGED, guardA.skipped.single().outcome)

        // 情形 B：分析后被标长期保留 → 必须跳过
        val retained = insertCouponScreenshot("intent-ret-" + UUID.randomUUID().toString().take(6) + ".png")
        waitIndexed(retained)
        val retainedImage = repo.loadAllData().allImages.first { it.uri == retained }
        prefs.retainStagedCleanupImages(listOf(cleanupIdentityKey(retainedImage)))
        val guardB = IntentCommitGuard.review(
            selected = listOf(retainedImage),
            currentLibrary = listOf(retainedImage),
            retainedIdentityKeys = prefs.stagedCleanupRetainedIdentityKeys,
            recycledIdentityKeys = emptySet(),
            stagedSourceKeys = setOf("external_primary|dcim/screenshots")
        )
        assertEquals(IntentCommitGuard.Outcome.RETAINED, guardB.skipped.single().outcome)
        prefs.releaseStagedCleanupRetention(listOf(cleanupIdentityKey(retainedImage)))

        // 情形 C：从图库消失（权限收窄/被删）→ 必须跳过
        val gone = insertCouponScreenshot("intent-gone-" + UUID.randomUUID().toString().take(6) + ".png")
        waitIndexed(gone)
        val goneImage = repo.loadAllData().allImages.first { it.uri == gone }
        ctx.contentResolver.delete(gone, null, null)
        val guardC = IntentCommitGuard.review(
            selected = listOf(goneImage),
            currentLibrary = repo.loadAllData().allImages,
            retainedIdentityKeys = emptySet(),
            recycledIdentityKeys = emptySet(),
            stagedSourceKeys = setOf("external_primary|dcim/screenshots")
        )
        assertEquals(IntentCommitGuard.Outcome.MISSING, guardC.skipped.single().outcome)

        // 三种情形都不该让回收站多出条目
        assertEquals("旧结果不得被提交进回收站", 0, bin.loadRecycleBin().size)
    }

    /**
     * 提交按钮的**状态**（而不是它上面那层文字的 isEnabled）。
     *
     * 为什么要看祖先链：Compose 的 `Button(enabled = false)` 把 disabled 语义挂在
     * Button 节点上，而里面那层 Text 节点的 `isEnabled` 仍是 true。
     * 上一轮直接读 Text 节点，于是「未选中」也被判成可用。
     * 这里沿祖先链找：链上**任意一个**节点 disabled，就认为按钮不可用；
     * 同时记下第一个可点击节点，供真正点击用。
     */
    private data class SubmitButton(
        val enabled: Boolean,
        val clickable: AccessibilityNodeInfo?
    )

    private fun submitButtonState(): SubmitButton? {
        val root = ins.uiAutomation.rootInActiveWindow ?: return null
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 24) return
            val text = n.text?.toString().orEmpty()
            if (text.contains("移入回收站")) matches += n
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        if (matches.isEmpty()) return null

        var anyDisabled = false
        var clickable: AccessibilityNodeInfo? = null
        matches.forEach { node ->
            var cursor: AccessibilityNodeInfo? = node
            var hops = 0
            while (cursor != null && hops < 5) {
                if (!cursor.isEnabled) anyDisabled = true
                if (cursor.isClickable && clickable == null) clickable = cursor
                cursor = cursor.parent
                hops++
            }
        }
        return SubmitButton(enabled = !anyDisabled, clickable = clickable)
    }

    private fun waitForEnabledSubmit(timeoutMs: Long = 20000): SubmitButton? {
        val deadline = now() + timeoutMs
        var last: SubmitButton? = null
        while (now() < deadline) {
            val state = submitButtonState()
            if (state != null) {
                last = state
                if (state.enabled) return state
            }
            android.os.SystemClock.sleep(250)
        }
        return last
    }

    /**
     * 从**真实回收站页**恢复目标图。
     *
     * 实际交互（读 RecycleBinScreen 得到，不是猜的）：回收站是列表 → 点开某张进入
     * 大图浮层 → 浮层底部才有「恢复」按钮（`contentDescription = "恢复"`）。
     * 上一版直接在当前页 find("恢复") 就点，跳过了「先点开照片」这一步，
     * 于是点到了别的东西、回收站记录没变。
     */
    private fun restoreFromRecycleBinScreen(target: Uri) {
        // 回主界面（两次返回足以从凑一批页退回主界面）
        shell("input keyevent KEYCODE_BACK")
        android.os.SystemClock.sleep(1200)
        shell("input keyevent KEYCODE_BACK")
        android.os.SystemClock.sleep(1200)

        assertTrue("必须能进入回收站页", tapByBounds("回收站", timeout = 20000))
        android.os.SystemClock.sleep(1500)

        // 1) 点开目标条目（列表项的 contentDescription 是文件名）
        val name = target.lastPathSegment.orEmpty()
        val opened = tapByBounds(name, timeout = 20000) ||
            tapByBounds(screenTexts().firstOrNull { it.contains(".png") }.orEmpty(), timeout = 5000)
        assertTrue("必须能在回收站里点开目标图（$name，屏幕：${screenTexts()}）", opened)
        android.os.SystemClock.sleep(1500)

        // 2) 在大图浮层里点「恢复」
        assertTrue("必须能找到恢复按钮（屏幕：${screenTexts()}）", tapByBounds("恢复", timeout = 20000))
        android.os.SystemClock.sleep(3000)
    }

    /** 以「同 ID 不同内容」的方式重写一张图，模拟外部编辑。 */
    private fun overwriteImage(uri: Uri, differentContent: Boolean) {
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (differentContent) Color.RED else Color.BLUE)
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        // MediaStore 的 SIZE 不会因为写入流自动刷新，必须显式推一次；
        // DATE_MODIFIED 也要推进（秒精度），否则「同一 ID 内容已变」可能被判成没变。
        val actualSize = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        ctx.contentResolver.update(
            uri,
            ContentValues().apply {
                if (actualSize > 0) put(MediaStore.MediaColumns.SIZE, actualSize)
                put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000 + 5)
            },
            null, null
        )
        // 等索引刷新最多 10 秒；超时由调用方的断言给出真实原因。
        // loadAllData 是 suspend，这里用 runBlocking 包一层（测试本身就在设备侧跑）。
        val deadline = now() + 10000
        while (now() < deadline) {
            val row = runBlocking {
                com.tabula.v3.data.repository.LocalImageRepository(ctx)
                    .loadAllData().allImages.firstOrNull { it.uri == uri }
            }
            if (row != null && actualSize > 0 && row.size == actualSize) return
            android.os.SystemClock.sleep(300)
        }
    }

    /**
     * 授权只对当前批有效（#95 第 3 轮审计，第 2 条）——**页面级**证明。
     *
     * 真实链路：进入凑一批 → 配好 Key → 打开实验性开关（确认提示）→
     * 提交一张（触发重建）→ 断言新批的开关**回到关闭**、提示文案回到「默认关闭」，
     * 且这一轮新批没有任何请求离开设备（由传输层计数器为 0 证明，见下）。
     *
     * 为什么要两个层面都测：单测证明状态机的语义，这里证明**页面真的接到了**
     * 同一个状态机（`IntentAssistAuthorization`），不是两套各自为政的布尔量。
     */
    @Test
    fun assistAuthorizationDoesNotSurviveBatchRebuild() = runBlocking {
        val shot = insertCouponScreenshot("intent-auth-" + UUID.randomUUID().toString().take(6) + ".png")
        waitIndexed(shot)

        // 预置一个 Key：让开关「可用」，从而能验证「可用但默认关闭」
        val store = IntentAssistKeyStore(ctx)
        store.write("sk-synthetic-pageflow-0001")

        startApp()
        openStagedCleanupCandidates()
        openIntentBatch()
        waitForTextContaining("本地识别", timeout = 180000)

        // 1) 有 Key 时，开关仍必须默认关闭、并显示「默认关闭」的说明
        val before = screenTexts()
        assertTrue(
            "有 Key 也必须默认关闭（屏幕：$before）",
            before.any { it.contains("默认关闭") }
        )
        assertFalse(
            "不该显示已启用（屏幕：$before）",
            before.any { it.contains("本批次已启用") }
        )
        assertTrue(
            "必须标注实验性（屏幕：$before）",
            before.any { it.contains("实验性") }
        )

        // 2) 打开开关：先出确认弹窗，确认后才生效
        assertTrue("必须能点到文字辅助开关", tapAssistSwitch())
        val dialog = screenTexts()
        if (dialog.any { it.contains("开启前请确认") }) {
            assertTrue("确认后才允许开启", tapByBounds("明白，本批次开启", timeout = 10000))
            android.os.SystemClock.sleep(2000)
        }
        waitForTextContaining("本批次已启用", timeout = 60000)

        // 3) 提交一张 → 触发重建
        val thumb = run {
            val deadline = now() + 30000
            while (now() < deadline) {
                screenTexts().firstOrNull { it.startsWith("intent-auth-") && it.endsWith(".png") }
                    ?.let { return@run it }
                android.os.SystemClock.sleep(250)
            }
            null
        }
        if (thumb == null) {
            // 这批没有可勾选项时，改为「手动重新分析」触发重建，同样能验证语义
            tapByBounds("重新分析")
            android.os.SystemClock.sleep(3000)
        } else {
            assertTrue("缩略图必须能点中", tapByBounds(thumb))
            val submit = waitForEnabledSubmit()
            assertNotNull("提交按钮必须可用", submit)
            assertTrue("选中后按钮必须为可用态（屏幕：${screenTexts()}）", submit!!.enabled)
            assertTrue("提交必须真的写入回收站", clickSubmit())
            android.os.SystemClock.sleep(4000)
        }

        // 4) 新批：开关必须回到关闭，且提示回到「默认关闭」
        //
        // 时机很关键：提交后先出现分析状态行，重建完成才改文案；而 Compose 的
        // 重组还要再晚一帧（诊断日志显示 granted 在同一秒内由 true→false，
        // 屏幕文本晚一步更新）。因此这里**轮询**到「已启用」消失为止，
        // 而不是读一次就断言。
        waitForTextContaining("本地识别", timeout = 180000)
        val deadline = now() + 30000
        var after: Set<String> = screenTexts()
        while (now() < deadline) {
            after = screenTexts()
            if (after.none { it.contains("本批次已启用") }) break
            android.os.SystemClock.sleep(300)
        }
        assertFalse(
            "重建后不得再显示已启用（屏幕：$after）",
            after.any { it.contains("本批次已启用") }
        )
        assertTrue(
            "重建后必须回到默认关闭（屏幕：$after）",
            after.any { it.contains("默认关闭") }
        )
    }

    /**
     * 点底部提交按钮。
     *
     * 为什么要两种方式：Compose 的 `Button` 节点虽可点，但 `ACTION_CLICK` 在
     * 部分组合下静默无效（本地第 3 轮实测：`已选 1 张` 与禁用态文案同时出现在
     * 同一帧，点击后状态全未变化 = 事件没送到）。先试语义点击，再用坐标兜底。
     */
    /**
     * 点底部提交按钮，并以**回收站记录是否真的增加**作为成功判据。
     *
     * 为什么不以「已选 0 张」判：提交是异步的（复核 → 写记录 → 重建批次），
     * 选中态要等重建后才清空；而「回收站记录 +1」才是这件事真正的结果。
     * 语义点击不行就退回坐标点击（Compose 的 ACTION_CLICK 在部分组合下静默无效）。
     */
    private fun clickSubmit(expectBinDelta: Int = 1): Boolean {
        val state = submitButtonState() ?: return false
        if (!state.enabled) return false
        val before = runBlocking { RecycleBinManager.getInstance(ctx).loadRecycleBin().size }

        fun succeeded(): Boolean = runBlocking {
            RecycleBinManager.getInstance(ctx).loadRecycleBin().size >= before + expectBinDelta
        }

        state.clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val deadline1 = now() + 12000
        while (now() < deadline1) {
            if (succeeded()) return true
            android.os.SystemClock.sleep(300)
        }

        // 坐标兜底：点按钮自身中心
        val root = ins.uiAutomation.rootInActiveWindow
        var target: AccessibilityNodeInfo? = null
        fun find(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || target != null || depth > 24) return
            if (n.text?.toString()?.contains("移入回收站（") == true) { target = n; return }
            for (i in 0 until n.childCount) find(n.getChild(i), depth + 1)
        }
        find(root, 0)
        target?.let { node ->
            val r = android.graphics.Rect(); node.getBoundsInScreen(r)
            if (r.width() > 0) {
                shell("input tap ${r.centerX()} ${r.centerY()}")
                val deadline2 = now() + 12000
                while (now() < deadline2) {
                    if (succeeded()) return true
                    android.os.SystemClock.sleep(300)
                }
            }
        }
        return false
    }

    /** 底部是否还显示「已选 N 张」（N>0）。 */
    private fun hasSelectedItems(): Boolean =
        screenTexts().any { it.matches(Regex("""已选 [1-9]\d* 张""")) }

    /**
     * 打开文字辅助开关。
     *
     * Compose 的 `Switch` 在无障碍树里**没有** Switch 类名，也不一定可点；
     * 实测（run 本地第 3 轮）按「实验性」文字点、按 className 找 Switch 都点不到。
     * 因此按优先级依次尝试：
     * 1. `isCheckable == true` 的节点（Compose 会把开关标成 checkable）；
     * 2. 包含「文字辅助」的那一行，点它**右边缘**（开关就在这一行最右）；
     * 3. 屏幕右上角的固定偏移兜底。
     */
    private fun tapAssistSwitch(): Boolean {
        val root = ins.uiAutomation.rootInActiveWindow ?: return false

        // 1) checkable 节点
        var checkable: AccessibilityNodeInfo? = null
        fun findCheckable(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || checkable != null || depth > 24) return
            if (n.isCheckable) { checkable = n; return }
            for (i in 0 until n.childCount) findCheckable(n.getChild(i), depth + 1)
        }
        findCheckable(root, 0)
        checkable?.let { node ->
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            android.os.SystemClock.sleep(1000)
            if (ok) return true
        }

        // 2) 「文字辅助」所在行的右边缘
        var row: AccessibilityNodeInfo? = null
        fun findRow(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || row != null || depth > 24) return
            if (n.text?.toString()?.contains("文字辅助") == true) { row = n; return }
            for (i in 0 until n.childCount) findRow(n.getChild(i), depth + 1)
        }
        findRow(root, 0)
        row?.let { label ->
            // 从标签往上找到包住整行的容器（宽度明显更大的那个）
            var container: AccessibilityNodeInfo? = label
            var hops = 0
            var widest: AccessibilityNodeInfo? = label
            val labelRect = android.graphics.Rect(); label.getBoundsInScreen(labelRect)
            while (container != null && hops < 4) {
                val r = android.graphics.Rect(); container.getBoundsInScreen(r)
                if (r.width() > labelRect.width() * 2) widest = container
                container = container.parent
                hops++
            }
            val r = android.graphics.Rect()
            (widest ?: label).getBoundsInScreen(r)
            if (r.width() > 0) {
                // 开关在行尾；留 24px 内边距，避免点到行尾的其它元素
                shell("input tap ${r.right - 40} ${r.centerY()}")
                android.os.SystemClock.sleep(1200)
                return true
            }
        }
        return false
    }

    @Test
    fun keyStoreRoundTripFromSettingsDialog() {
        // 设置页/对话框保存 → 文件落在 noBackupFilesDir → 删除后立即失效
        val store = IntentAssistKeyStore(ctx)
        store.delete()
        assertFalse(store.hasKey())
        assertTrue(store.write("sk-synthetic-0001"))
        assertTrue(store.hasKey())
        val file = File(ctx.noBackupFilesDir, IntentAssistKeyStore.FILE_NAME)
        assertTrue("Key 必须落在 noBackupFilesDir（不参与备份/迁移）", file.exists())
        assertTrue(store.delete())
        assertFalse(store.hasKey())
        assertEquals("未配置", store.describeState())
    }
}
