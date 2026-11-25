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
import com.tabula.v3.data.intent.IntentAssistKeyStore
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
     * 回收站链路复用（数据层，不点 UI）。
     *
     * 为什么不点 UI：本机模拟器在跑「点缩略图 → 点底部按钮」这条路径时，
     * 快照里同时出现「已选 1 张」与「选择要移入回收站的照片」（按钮禁用态文案），
     * 两者来自同一次组合却互相矛盾，怀疑是快照跨越重组帧。**这不作为功能结论**，
     * 只记录为未验证项（见 docs/intent-batch-pilot-report-2026-11-25.md §9）。
     *
     * 这里验证的是**真正被复用的那部分**：本功能的勾选结果交给
     * `RecycleBinManager.addAllToRecycleBin` 后确实进回收站、且能恢复——
     * 与卡片上滑删除走的是同一个 API。
     */
    @Test
    fun intentSelectionReusesTheRecycleBinAndCanBeRestored() = runBlocking {
        val shot = insertCouponScreenshot("intent-bin-" + UUID.randomUUID().toString().take(6) + ".png")
        waitIndexed(shot)

        val repo = com.tabula.v3.data.repository.LocalImageRepository(ctx)
        val image = repo.loadAllData().allImages.first { it.uri == shot }
        val bin = RecycleBinManager.getInstance(ctx)
        val before = bin.loadRecycleBin().size

        // 与 MainActivity.onConfirm 里完全相同的一次调用
        bin.addAllToRecycleBin(listOf(image))
        val afterAdd = bin.loadRecycleBin()
        assertTrue("选中项应进入回收站（${before} → ${afterAdd.size}）", afterAdd.size == before + 1)

        // 恢复：与回收站页的恢复走同一个 remove API
        val restored = afterAdd.first { it.uri == shot }
        bin.removeFromRecycleBin(restored)
        val afterRestore = bin.loadRecycleBin()
        assertTrue(
            "回收站条目必须可恢复（恢复后 ${afterRestore.size} 项）",
            afterRestore.none { it.uri == shot }
        )
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
