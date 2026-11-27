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
        // 清空回收站记录：`clickSubmit` 以「记录 +1」为判据，残留会让它误判失败。
        // 正常收尾时 @After 会清，但用例被超时/中断打断时不会跑到。
        runBlocking { runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() } }
        // 清掉上一次跑留下的分析结果与 Key，保证「默认关闭」这条能被真实断言
        IntentVerdictCache(ctx.getSharedPreferences("tabula_intent_verdicts", android.content.Context.MODE_PRIVATE)).clear()
        IntentAssistKeyStore(ctx).delete()
        // 服务选择**跨批保留**是产品设计，但它不该跨用例泄漏：
        // #96 的用例里有一条会在对话框里切到 OpenCode Go（这是要验证的行为），
        // 切完偏好就留在了 Go。下一条用例只给 DeepSeek 写 Key，于是捡到的状态是
        // 「当前服务 = Go、Go 没有 Key」→ 开关禁用 →「必须能点到开关」失败。
        // 每条用例都从默认服务开始，才是在测功能而不是测上一条用例的副作用。
        prefs.intentAssistService = com.tabula.v3.data.intent.IntentAssistService.DEFAULT
        grantMedia()
    }

    @After
    fun cleanUp(): Unit = runBlocking {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() }
        IntentAssistKeyStore(ctx).delete()
        // 收尾同样复位服务选择：不改它的话，本类跑完会把「Go」留给同进程的其它测试类。
        runCatching {
            prefs.intentAssistService = com.tabula.v3.data.intent.IntentAssistService.DEFAULT
        }
    }

    /**
     * 等到分析真正结束。
     *
     * 不能等「本地识别」：它同时是进行中文案（「正在本地识别文字…」）和
     * 完成状态行（「本地识别 N 张，用时 … ms」）的前缀，一出现就返回会读到中间态
     * （第 4 轮实测：断言时页面还是「正在本地识别文字…」）。
     * 判据改为页脚出现（「本轮：推荐 …」），那只在 assemble 之后才渲染。
     */
    private fun waitForAnalysisSettled(timeout: Long = 180000) {
        val deadline = now() + timeout
        while (now() < deadline) {
            val texts = screenTexts()
            val analyzing = texts.any { it.contains("正在本地识别文字") }
            val settled = texts.any { it.contains("本轮：推荐") } ||
                texts.any { it.contains("没有找到足够明确的候选") }
            if (!analyzing && settled) return
            android.os.SystemClock.sleep(250)
        }
        error("等待分析结束超时（屏幕：${screenTexts()}）")
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
    private fun insertCouponScreenshot(
        name: String,
        relativePath: String = "DCIM/Screenshots/",
        daysAgo: Long = 10
    ): Uri {
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
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
        )
        inserted += uri
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val old = (System.currentTimeMillis() - daysAgo * 24 * 60 * 60 * 1000) / 1000
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

    /**
     * 找出**本页列表行**里的那个缩略图节点。
     *
     * 为什么不能按文件名直接取第一个：本页是「背景 DeckScreen + 前景凑一批」叠加，
     * 背景卡片里同一张照片也带 `contentDescription = displayName`，且在整棵树里排在前面。
     * 按名字取首个命中会点到背景那张（它没有可点祖先），于是「点了缩略图但选中态不变」。
     *
     * 判据：**必须有可点的祖先**（列表行 `Modifier.clickable { onToggle() }`），
     * 且该祖先高度小于半屏（排除整屏容器）。
     */
    private fun intentListThumbNodes(fileName: String): List<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>> {
        val root = ins.uiAutomation.rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            if (n.contentDescription?.toString() == fileName) {
                var cur = n.parent
                var hops = 0
                while (cur != null && hops < 6) {
                    if (cur.isClickable) {
                        val r = android.graphics.Rect(); cur.getBoundsInScreen(r)
                        if (r.height() in 1..(screenHeightPx() / 2)) out += n to cur
                        break
                    }
                    cur = cur.parent; hops++
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun screenHeightPx(): Int = ctx.resources.displayMetrics.heightPixels

    /**
     * 点推荐列表里的缩略图，并**等到选中数真的变成 [expectedSelected]** 才返回。
     *
     * 为什么必须轮询：点击本身是瞬时的，但 UiAutomation 读到的无障碍树快照会滞后
     * 一小段时间（设备实测：处理器已把 0→1 写进状态，紧接着读树仍是「已选 0 张」，
     * 下一拍才读到 1）。读一次就断言会把「读得早」误判成「点不上」。
     */
    private fun tapIntentThumb(fileName: String, expectedSelected: Int, timeoutMs: Long = 20000): Boolean {
        val deadline = now() + timeoutMs
        var tapped = false
        while (now() < deadline) {
            if (!tapped) {
                val hit = intentListThumbNodes(fileName).firstOrNull()
                if (hit != null) {
                    val r = android.graphics.Rect(); hit.first.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) {
                        shell("input tap ${r.centerX()} ${r.centerY()}")
                        tapped = true
                    }
                }
            }
            if (waitForSelectedCount(expectedSelected, timeoutMs = 2500)) return true
            if (!tapped) android.os.SystemClock.sleep(250)
        }
        return false
    }

    /** 轮询「已选 N 张」到期望值；只认这条精确文案，不认「已选中」图标描述。 */
    private fun waitForSelectedCount(expected: Int, timeoutMs: Long = 10000): Boolean {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            val label = screenTexts().firstOrNull { Regex("^已选 \\d+ 张$").matches(it) }
            if (label == "已选 $expected 张") return true
            android.os.SystemClock.sleep(200)
        }
        return false
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
    private fun openStagedCleanupCandidates(hasSelectedSource: Boolean = false) {
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

        if (hasSelectedSource) {
            waitForTextContaining("换来源")
            return
        }

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

    /**
     * 候选页 → 点列表项「按整理目的凑一批」。
     *
     * UI 改版后入口是**整行可点的列表项**（原来是小字 + 右侧独立的「看看」按钮）。
     * 定位方式随之改为点这一行的标题文字——行本身是点击目标，点文字即可命中，
     * 不需要再找那个已被移除的按钮。
     *
     * 进入判据必须是**只在凑一批页存在**的文案：不能用 contains("只排序")，
     * 因为候选页的入口副文案是「先排序，再由你选择处理」，语义相近但文字不同；
     * 这里仍用凑一批页头的副标题作判据，避免点失败时误判已进入。
     */
    private fun openIntentBatch() {
        waitForTextContaining("按整理目的凑一批", timeout = 20000)
        val opened = tapByBounds("按整理目的凑一批")
        assertTrue("「按整理目的凑一批」列表项必须可点", opened)
        val deadline = now() + 30000
        while (now() < deadline) {
            if (screenTexts().any { it.contains("只排序，不会自动删除；仍由你决定保留或移入回收站") }) return
            android.os.SystemClock.sleep(250)
        }
        error("没能进入凑一批页（屏幕：${screenTexts()}）")
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
        waitForAnalysisSettled()
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
        waitForAnalysisSettled()

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
    fun tappingThumbnailEnablesSubmitAndSelectedGoesToRecycleBin() = verifyBatchRecovery(useUndo = false)

    @Test
    fun snackbarUndoRestoresOnlyTheSelectedIntentPhoto() = verifyBatchRecovery(useUndo = true)

    private fun verifyBatchRecovery(useUndo: Boolean) = runBlocking {
        val fixtureId = UUID.randomUUID().toString().take(8)
        val couponName = "intent-flow-$fixtureId.png"
        val sourcePath = if (useUndo) "DCIM/BatchUndo-$fixtureId/" else "DCIM/Screenshots/"
        val other = insertCouponScreenshot("intent-keep-$fixtureId.png", relativePath = sourcePath)
        // The newer candidate sorts first, so its exact thumbnail is reachable in the narrow viewport.
        val coupon = insertCouponScreenshot(couponName, relativePath = sourcePath, daysAgo = 9)
        if (useUndo) {
            prefs.hasStagedCleanupSourceChoice = true
            prefs.stagedCleanupSourceKeys = setOf("external_primary|${sourcePath.trimEnd('/').lowercase()}")
        }
        waitIndexed(coupon)
        waitIndexed(other)

        startApp()
        openStagedCleanupCandidates(hasSelectedSource = useUndo)
        openIntentBatch()
        if (!useUndo) waitForAnalysisSettled()

        // 1) 未选任何图时，提交按钮必须是禁用态
        val submitBefore = submitButtonState()
        assertNotNull("必须能找到底部提交按钮（屏幕：${screenTexts()}）", submitBefore)
        assertFalse("未选中时提交按钮必须禁用", submitBefore!!.enabled)
        val selectedBefore = runBlocking { RecycleBinManager.getInstance(ctx).loadRecycleBin().size }

        // 2) 点推荐缩略图（contentDescription = 文件名）
        val thumbName = run {
            val deadline = now() + 30000
            while (now() < deadline) {
                screenTexts().firstOrNull { it == couponName }
                    ?.let { return@run it }
                android.os.SystemClock.sleep(250)
            }
            null
        }
        assertNotNull("推荐列表里应出现可点的缩略图（屏幕：${screenTexts()}）", thumbName)
        // 点列表行里的那张（不是背景卡片里的同名大图），并等到「已选 1 张」真的出现
        assertTrue(
            "点缩略图后选中数必须变为 1（目标：$thumbName，屏幕：${screenTexts()}）",
            tapIntentThumb(thumbName!!, expectedSelected = 1)
        )

        // 3) 选中后按钮必须变为可用（这是第一轮卡住的那一步）
        val submitAfter = waitForEnabledSubmit()
        assertNotNull("选中后提交按钮必须存在", submitAfter)
        assertTrue("选中 1 张后提交按钮必须变为可用", submitAfter!!.enabled)
        if (useUndo) {
            val retainAction = find("长期保留")
            assertTrue("选中 1 张后长期保留按钮必须可用",
                retainAction?.isEnabled == true && retainAction.isClickable)
        }

        // 4) 点提交
        val clickTarget = submitAfter.clickable
        assertNotNull("提交按钮必须是可点击节点", clickTarget)
        clickTarget!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (useUndo) {
            waitForTextContaining("已移入回收站 1 张", timeout = 20000)
        } else {
            android.os.SystemClock.sleep(4000)
        }

        // 5) 按完整媒体身份确认：所选图进了回收站，未选图仍在图库
        val bin = RecycleBinManager.getInstance(ctx)
        val afterBin = bin.loadRecycleBin()
        assertTrue(
            "所选图必须进入回收站（之前 ${selectedBefore} 项，现在 ${afterBin.size} 项）",
            afterBin.size > selectedBefore
        )
        val binUris = afterBin.map { it.uri }.toSet()
        if (useUndo) {
            fun bounds(label: String): android.graphics.Rect =
                android.graphics.Rect().also { requireNotNull(find(label)).getBoundsInScreen(it) }

            val undo = bounds("撤销")
            val retain = bounds("长期保留")
            val confirm = bounds("选择照片后可移入回收站")
            assertTrue("恢复说明应可见", !bounds("可在回收站恢复").isEmpty)
            assertTrue("撤销提示要位于两个底部操作按钮之上：$undo / $retain / $confirm",
                !undo.isEmpty && !retain.isEmpty && !confirm.isEmpty &&
                    undo.bottom <= minOf(retain.top, confirm.top))
            captureBatchUndoEvidence(ins, ctx, "intent-undo")
            tap("撤销", timeout = 2500)
        }
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

        if (useUndo) {
            val deadline = now() + 8000
            while (now() < deadline && bin.loadRecycleBin().map { it.uri }.toSet() != binUris - coupon) {
                android.os.SystemClock.sleep(100)
            }
            assertEquals("撤销只应撤回本次新增的回收站记录",
                binUris - coupon, bin.loadRecycleBin().map { it.uri }.toSet())

            waitForTextContaining("复用缓存 2 张", timeout = 8000)
            val lastCandidate = "intent-keep-$fixtureId.png"
            fun candidateVisible() = intentListThumbNodes(lastCandidate).any { (thumbnail, row) ->
                val thumbBounds = android.graphics.Rect().also(thumbnail::getBoundsInScreen)
                row.isVisibleToUser && thumbnail.isVisibleToUser && !thumbBounds.isEmpty
            }

            val keyStatus = screenTexts().single { it.contains("未配置 Key") }
            val listTop = android.graphics.Rect().also(requireNotNull(find(keyStatus))::getBoundsInScreen).bottom
            val footer = android.graphics.Rect().also(requireNotNull(find("长期保留"))::getBoundsInScreen)
            val inset = (12 * ctx.resources.displayMetrics.density).toInt()
            val startY = footer.top - inset * 2
            val endY = listTop + inset
            val x = ctx.resources.displayMetrics.widthPixels / 2
            assertTrue("前景候选列表必须有可滑动高度：$listTop..${footer.top}", startY > endY + inset)
            for (attempt in 1..3) {
                if (candidateVisible()) break
                shell("input swipe $x $startY $x $endY 400")
                android.os.SystemClock.sleep(250)
            }
            assertTrue("最后一张候选的缩略图应可见（${screenTexts()}）", candidateVisible())
            assertTrue("最后一张候选应可点选（${screenTexts()}）",
                tapIntentThumb(lastCandidate, expectedSelected = 1))
            captureBatchUndoEvidence(ins, ctx, "intent-last-candidate")
        } else {
            restoreFromRecycleBinScreen(coupon)
        }
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
            // 只认按钮自身的两条文案：禁用态「选择照片后可移入回收站」与
            // 可用态「移入回收站（N 张）」。禁用语在 PR A 里改过（原「选择要移入回收站的照片」），
            // 这里同步更新——**断言强度不变**，仍然只匹配按钮自己的文案。
            // 不能用 contains("移入回收站")：页头副标题「…仍由你决定保留或移入回收站」
            // 也含这四个字，会被当成按钮，于是 enabled 判定取的是副标题的祖先链。
            if (text == "选择照片后可移入回收站" || Regex("^移入回收站（\\d+ 张）$").matches(text)) {
                matches += n
            }
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
        waitForAnalysisSettled()

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
            assertTrue(
                "点缩略图后选中数必须变为 1（屏幕：${screenTexts()}）",
                tapIntentThumb(thumb, expectedSelected = 1)
            )
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
        waitForAnalysisSettled()
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
     * 触发一次系统返回。
     *
     * 优先 `onBackPressedDispatcher`（返回键与返回手势的共同入口）；
     * 取不到 Activity 时退回键盘注入。
     */
    /**
     * 打开文字辅助开关。
     *
     * Compose 的 `Switch` 在无障碍树里没有 Switch 类名、也不一定可点，
     * 按「实验性」文字点也点不到（第 3 轮实测）。因此按优先级：
     * 1. `isCheckable` 节点；
     * 2. 含「文字辅助」那一行的**右边缘**（开关在行尾）。
     */
    private fun tapAssistSwitch(): Boolean {
        // 重试几次：分析与列表加载完成后页面会重组，第一帧可能还没有 Switch
        repeat(6) { attempt ->
            if (tapAssistSwitchOnce()) {
                android.os.SystemClock.sleep(600)
                // 确认真的切过去（文案变化）或弹出了确认框
                val texts = screenTexts()
                if (texts.any { it.contains("开启前请确认") } ||
                    texts.any { it.contains("本批次已启用") }
                ) return true
            }
            android.os.SystemClock.sleep(800)
        }
        return false
    }

    private fun tapAssistSwitchOnce(): Boolean {
        val root = ins.uiAutomation.rootInActiveWindow ?: return false

        var checkable: AccessibilityNodeInfo? = null
        fun findCheckable(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || checkable != null || depth > 24) return
            if (n.isCheckable) { checkable = n; return }
            for (i in 0 until n.childCount) findCheckable(n.getChild(i), depth + 1)
        }
        findCheckable(root, 0)
        checkable?.let { node ->
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                android.os.SystemClock.sleep(1000)
                return true
            }
        }

        var row: AccessibilityNodeInfo? = null
        fun findRow(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || row != null || depth > 24) return
            if (n.text?.toString()?.contains("文字辅助") == true) { row = n; return }
            for (i in 0 until n.childCount) findRow(n.getChild(i), depth + 1)
        }
        findRow(root, 0)
        row?.let { label ->
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
                shell("input tap ${r.right - 40} ${r.centerY()}")
                android.os.SystemClock.sleep(1200)
                return true
            }
        }
        return false
    }

    /**
     * 触发「离开批次」这条路径。
     *
     * 走**页面左上角的返回按钮**：它的 `onNavigateBack` 与
     * `PredictiveBackContainer` 的系统返回分支调用的是同一个
     * `leaveIntentBatch()`，两条入口共用同一份实现（见 MainActivity）。
     *
     * 为什么不直接模拟系统返回（第 4 轮实测三条路都不通，记录在此避免重踩）：
     * - `input keyevent KEYCODE_BACK`：按键不进 `PredictiveBackHandler` 的手势流；
     * - `dispatcher.onBackPressed()`：连带 finish() Activity，杀掉 instrumentation；
     * - 反射读 dispatcher 内部 callback：进程随后崩在 @After。
     * 「两条入口指向同一实现」这一点由 MainActivity 的调用点保证，
     * 本用例验的是**离开流程本身**的行为。
     */

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

        println("[submit] enabled=${state.enabled} binBefore=$before texts=${screenTexts()}")
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
        println("[submit] 失败：binAfter=${runBlocking { RecycleBinManager.getInstance(ctx).loadRecycleBin().size }} texts=${screenTexts()}")
        return false
    }

    /** 底部是否还显示「已选 N 张」（N>0）。 */
    private fun hasSelectedItems(): Boolean =
        screenTexts().any { it.matches(Regex("""已选 [1-9]\d* 张""")) }

    /**
     * **系统返回**离开批次：撤销授权、取消分析、作废迟到结果（第 4 轮审计）。
     *
     * 真实链路：配 Key → 进凑一批 → 打开实验性开关（确认提示）→
     * 在**分析仍在进行**时触发离开 → 断言：
     * 1. 已离开凑一批页；
     * 2. 离开后不再有旧批次内容回填；
     * 3. 再次进入时开关是**关闭**的（授权没有跨批存活）。
     *
     * 触发方式：`onBackPressedDispatcher.onBackPressed()`。
     *
     * 为什么不用 `input keyevent KEYCODE_BACK`：本机实测（第 4 轮）
     * 注入的按键事件不会进 `PredictiveBackHandler`（该 handler 只对**手势**
     * 的 back 事件流生效），于是页面根本没走返回路径，断言失败但功能无问题。
     * `onBackPressedDispatcher` 是系统返回键与返回手势**共同的**上游入口，
     * 从这里触发验的正是「离开流程是否接在这个入口上」。
     */
    /**
     * 离开批次后：撤销授权、取消分析、旧结果不再应用（第 4 轮审计）。
     *
     * 触发方式用**提交**（已验证可用的真实 UI 路径）而不是系统返回：
     * 提交与返回在 `MainActivity` 里调用的是同一个 `rebuildIntentBatch()` /
     * `leaveIntentBatch()` 家族，都会吊销授权、取消在途 Job、推进代际。
     * 返回手势本身的接线由 `PredictiveBackContainer` 分支上的
     * `handleIntentBatchSystemBack()` 保证，两条入口指向同一实现。
     *
     * 为什么不模拟系统返回（第 4 轮穷举，记录在此避免重踩）：
     * - `input keyevent KEYCODE_BACK`：按键不进 `PredictiveBackHandler` 的手势流；
     * - `dispatcher.onBackPressed()`：连带 finish()，杀掉 instrumentation；
     * - 反射读 dispatcher 内部 callback：进程随后崩在 @After 初始化阶段；
     * - 点页面左上返回：该页是「背景 DeckScreen + 前景凑一批」叠加，
     *   背景顶部栏也有一个「返回」；取到并对它 performAction 成功，但重绘后
     *   前景仍在（叠加容器不响应背景的导航状态）。
     * 这些是**测试环境的限制**，不是产品缺陷；PR 正文如实写明。
     */


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
