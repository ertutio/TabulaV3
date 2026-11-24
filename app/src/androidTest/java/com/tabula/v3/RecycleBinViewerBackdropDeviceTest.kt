package com.tabula.v3

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.LocalImageRepository
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.ui.components.liquidglass.BackdropFallbackReason
import com.tabula.v3.ui.components.liquidglass.LIQUID_GLASS_BACKDROP_MIN_SDK
import com.tabula.v3.ui.components.liquidglass.ViewerBackdropSync
import com.tabula.v3.ui.components.liquidglass.shouldAttachPhotoBackdrop
import com.tabula.v3.ui.components.liquidglass.shouldLoadBackdropPhoto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/**
 * 真实回收站全屏查看器：背景采样在**翻页 / 放大 / 拖动**时是否同步或回退。
 *
 * ## 为什么必须有这个测试
 *
 * 采样层是**另一份**静止的 `ContentScale.Fit` Bitmap，而查看器的照片经
 * `HorizontalPager` 平移、`graphicsLayer{scale/translation}` 缩放拖动。
 * 两者只在"没翻页、没放大、没拖动"时才是同一幅画面。此前只有 JVM 层的
 * `ViewerBackdropSync` 单测，**没有任何测试操作过真实查看器**。
 *
 * ## 生产门控（这决定了本测试在每个 API 上验什么）
 *
 * `MainActivity` 里是 `liquidGlassEnabled = liquidGlassLabEnabled && SDK_INT >= 35`。
 * 也就是说：
 *
 * - **API 35+**：真实生产入口会启用液态玻璃 → 本测试验证完整六步
 *   （静止采样 / 翻页回退 / 翻页后恢复 / 放大回退 / 拖动回退 / 复原后恢复）；
 * - **API 33–34**：真实生产入口**按设计不启用**液态玻璃 → 本测试验证
 *   **符合门控的回退**（`reason=DISABLED`，按钮走普通路径、不发起采样加载）。
 *   底层采样能力由组件级/预览测试覆盖，**不在这里冒充"真实入口采样通过"**。
 *
 * 因此测试不会在 API 33 上假装验证了采样——那需要在预览页把门控强行打开，
 * 而那不是生产入口。
 *
 * ## 采样方案怎么读出来
 *
 * 顶栏的 `ViewerBackdropAuditLine` 输出的 `AUDIT ...` 行，**由驱动 UI 分支的
 * 同一个 decision 生成**（`path=glass|fallback` 直接标明走了哪条路）。测试读
 * 无障碍树里的这一行——读的是生产渲染决策，不是测试另算一遍。
 *
 * ## 夹具为什么是 4 张（不是 3 张）
 *
 * 早先版本造 3 张照片并**全部**移入回收站，于是普通图库为空。空图库时
 * `DeckScreen` 进 `EmptyState`，而 `EmptyState` 里没有 TopBar——「回收站」
 * 入口根本不存在，`waitFor("回收站")` 必然超时。run 45421997727 的
 * `timed out waiting for 回收站` 就是这个原因。
 *
 * 注意：**空图库保留顶部栏是 #84 的功能修复（`166d236`），不属于 #92**。
 * #92 这里只做两件事：夹具留一张照片在图库（让入口存在）、并把第 4 张也
 * 纳入清理。空图库入口的回归由 #84 的组合分支验证。
 */
@RunWith(AndroidJUnit4::class)
class RecycleBinViewerBackdropDeviceTest {

    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val ui get() = PartialSelectionUi

    /** 回收站 UI 的项计数锚（RecycleBinTopBar）。 */
    private val BIN_COUNT_ANCHOR = "项待处理"

    /** 查看器页码锚（FullScreenViewer 顶栏中央）。 */
    private val PAGE_ANCHOR = Regex("^\\d+ / \\d+$")

    /** 审计行前缀。 */
    private val AUDIT_PREFIX = "AUDIT "

    private val seeded = mutableListOf<String>()

    /** 生产门控是否会在本机启用液态玻璃（与 MainActivity 同一判据）。 */
    private fun productionGlassEnabled(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 35

    @Before
    fun requireEmulator() {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ui.shellScript("pm grant ${ctx.packageName} ${Manifest.permission.READ_MEDIA_IMAGES} 2>&1")
        }

        AppPreferences(ctx).apply {
            hasCompletedOnboarding = true
            hasAcknowledgedDisclaimer = true
        }
    }

    @After
    fun cleanup() {
        kotlinx.coroutines.runBlocking {
            runCatching {
                val manager = RecycleBinManager.getInstance(ctx)
                val rows = manager.loadRecycleBin().filter { row ->
                    seeded.any { row.displayName.startsWith(it) }
                }
                if (rows.isNotEmpty()) {
                    manager.removeAllFromRecycleBin(rows, permanentlyRemoved = true)
                }
            }
            runCatching {
                seeded.forEach { prefix ->
                    ctx.contentResolver.delete(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                        arrayOf("$prefix%")
                    )
                }
            }
            Unit
        }
    }

    @Test
    fun viewerBackdropFallsBackWhileScrollingZoomingAndPanning() {
        kotlinx.coroutines.runBlocking {
            if (android.os.Build.VERSION.SDK_INT < LIQUID_GLASS_BACKDROP_MIN_SDK) {
                Log.i(TAG, "skip: API ${android.os.Build.VERSION.SDK_INT} < $LIQUID_GLASS_BACKDROP_MIN_SDK")
                return@runBlocking
            }

            // ===== 夹具：4 张，3 张进回收站、1 张留在图库 =====
            //
            // 留一张在图库是**必需**的：全空图库时 DeckScreen 进 EmptyState，
            // 那里没有 TopBar，「回收站」入口不存在（run 45421997727 的超时）。
            // 空图库入口本身由 #84 修（166d236），不在本 PR 范围。
            val allNames = (1..4).map { index ->
                val uri = FullRecoveryStateContract.seedPhoto(ctx, index)
                val name = requireNotNull(
                    ctx.contentResolver.query(
                        uri,
                        arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                        null, null, null
                    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                )
                seeded += name
                name
            }
            // 第 4 张留在图库（不加入回收站）
            val binNames = allNames.take(3)

            val rows = LocalImageRepository(ctx).loadAllData().allImages
                .filter { row -> binNames.any { row.displayName == it } }
            assertEquals("前置：3 张夹具照片都要能被图库读到", 3, rows.size)
            RecycleBinManager.getInstance(ctx).addAllToRecycleBin(rows)

            // 走真实入口：图库 → 回收站 → 点开照片 → 全屏查看器
            ui.launch()
            ui.waitFor("回收站", timeoutMs = 45000)
            ui.tap("回收站")
            ui.waitFor(BIN_COUNT_ANCHOR, timeoutMs = 45000)
            openFirstPhoto()

            if (!productionGlassEnabled()) {
                // ===== API 33–34：验证**符合生产门控的回退** =====
                //
                // 生产入口在这些版本上不启用液态玻璃，所以正确的行为是：
                // 顶栏走普通按钮、不采样、不发起采样图片加载。
                // 这里**不**验证底层采样能力——那需要绕过生产门控，属于预览/组件测试。
                val fallback = awaitAudit { it["sampled"] == "false" }
                assertNotNull(
                    "API ${android.os.Build.VERSION.SDK_INT}：生产门控关闭时必须回退" +
                        "（实际：${latestAuditText()}）",
                    fallback
                )
                assertEquals(
                    "回退原因必须是 DISABLED（生产门控未开启），不是别的分支",
                    BackdropFallbackReason.DISABLED.name,
                    fallback!!["reason"]
                )
                assertEquals(
                    "按钮必须走回退路径（path=fallback）",
                    "fallback",
                    fallback["path"]
                )
                assertFalse(
                    "门控关闭时不得加载采样照片",
                    shouldLoadBackdropPhoto(
                        liquidGlassEnabled = false,
                        sdkInt = android.os.Build.VERSION.SDK_INT
                    )
                )
                Log.i(TAG, "API ${android.os.Build.VERSION.SDK_INT}: 按生产门控回退，已验证")
                return@runBlocking
            }

            // ===== API 35+：真实生产入口启用液态玻璃，验证完整六步 =====
            //
            // 实验室开关在 **debug 变体**可写（release 里 getter 恒为 false，
            // 见 AppPreferences.liquidGlassLabEnabled）。设备测试跑的是
            // debug APK，所以这里能真正打开生产门控；打不开的话六步断言
            // 一条都验不到，只会反复报 DISABLED。
            AppPreferences(ctx).liquidGlassLabEnabled = true
            assertTrue(
                "前置：API 35+ 且开关打开时生产入口必须加载采样照片",
                shouldLoadBackdropPhoto(liquidGlassEnabled = true, sdkInt = android.os.Build.VERSION.SDK_INT)
            )
            // 开关写的是 SharedPreferences，MainActivity 在 setContent 时读一次，
            // 所以要让它**重新创建**才生效。两个都不能用：
            //   - `am force-stop`：instrumentation 就跑在这个进程里，强停等于自杀
            //     （run 45460100076：`shortMsg=Process crashed.`）；
            //   - 先 HOME 再 `am start`：Activity 还在任务栈里，`am start` 走
            //     onNewIntent，不会重新 setContent（run 45464442907 就是这样，
            //     MainActivity 进 STOPPED 后再没回来）。
            //
            // 用 CLEAR_TASK + NEW_TASK：清掉任务栈并重建 Activity，进程不动。
            ui.shell(
                "am start -n ${ctx.packageName}/com.tabula.v3.MainActivity " +
                    "-a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER " +
                    "--activity-clear-task --activity-new-task"
            )
            SystemClock.sleep(1200)
            ui.waitFor("回收站", timeoutMs = 45000)
            ui.tap("回收站")
            ui.waitFor(BIN_COUNT_ANCHOR, timeoutMs = 45000)
            openFirstPhoto()

            // ----- 1. 静止（翻页完成）：必须采样 -----
            val settled = awaitAudit { audit ->
                audit["sampled"] == "true" && audit["reason"] == "NONE"
            }
            assertNotNull(
                "翻页完成、未缩放未拖动时必须采样（实际：${latestAuditText()}）",
                settled
            )
            assertEquals("静止时不应有回退原因", "NONE", settled!!["reason"])
            assertEquals("静止时按钮必须走玻璃路径", "glass", settled["path"])

            // ----- 2. 翻页中：必须回退，且页码真的变了 -----
            val pageBefore = currentPageNumber()
            val scrolling = swipeAndReadMidScroll()
            assertNotNull("翻页中必须回退（实际：${latestAuditText()}）", scrolling)
            assertEquals(
                "翻页中的回退原因必须是 VIEWER_SCROLLING",
                BackdropFallbackReason.VIEWER_SCROLLING.name,
                scrolling!!["reason"]
            )
            assertEquals("翻页中不能继续采样", "false", scrolling["sampled"])
            assertEquals("翻页中按钮必须走回退路径", "fallback", scrolling["path"])

            // 页码真的变了：证明这次滑动确实翻了页，而不是"原地震动"就通过
            val pageAfter = awaitPageNumberChange(pageBefore, timeoutMs = 8000)
            assertNotEquals(
                "翻页必须真的改变页码（滑动前后都是 $pageBefore，说明没翻过去）",
                pageBefore,
                pageAfter
            )

            // ----- 3. 翻页完成后：必须恢复采样 -----
            val afterSwipe = awaitAudit { audit ->
                audit["sampled"] == "true" && audit["reason"] == "NONE"
            }
            assertNotNull(
                "翻页完成后必须恢复采样，不能永久回退（实际：${latestAuditText()}）",
                afterSwipe
            )
            assertEquals("恢复后必须回到玻璃路径", "glass", afterSwipe!!["path"])

            // ----- 4. 放大后：必须回退 -----
            doubleTapCenter()
            val zoomed = awaitAudit { audit ->
                audit["sampled"] == "false" &&
                    audit["reason"] == BackdropFallbackReason.VIEWER_TRANSFORMED.name
            }
            assertNotNull(
                "放大后必须回退为 VIEWER_TRANSFORMED（实际：${latestAuditText()}）",
                zoomed
            )
            assertEquals("放大后按钮必须走回退路径", "fallback", zoomed!!["path"])
            val zoomedScale = zoomed["scale"]?.toFloatOrNull() ?: 1f
            assertTrue(
                "放大态必须真的放大（scale=$zoomedScale，应 >1.05）",
                zoomedScale > 1.05f
            )

            // ----- 5. 拖动后：必须回退，且平移量真的非零 -----
            //
            // 只断言 "sampled=false" 是不够的：如果一直停在放大态，
            // 那条断言即使拖动没生效也会通过。必须证明 pan 真的变了。
            val panBefore = zoomed["pan"] ?: "(0.0,0.0)"
            dragOnce()
            val panned = awaitAudit { audit ->
                audit["sampled"] == "false" &&
                    audit["reason"] == BackdropFallbackReason.VIEWER_TRANSFORMED.name &&
                    audit["pan"] != panBefore
            }
            assertNotNull(
                "拖动后必须产生非零位移且保持回退（pan 前=$panBefore 后=${latestAuditText()}）",
                panned
            )
            assertNotEquals(
                "拖动必须真的改变平移量（前后都是 $panBefore，说明拖动没生效）",
                panBefore,
                panned!!["pan"]
            )

            // ----- 6. 复原原比例后：必须恢复采样 -----
            doubleTapCenter()
            val restored = awaitAudit { audit ->
                audit["sampled"] == "true" && audit["reason"] == "NONE"
            }
            assertNotNull(
                "缩回原比例后必须恢复采样（实际：${latestAuditText()}）",
                restored
            )
            assertEquals("恢复后必须回到玻璃路径", "glass", restored!!["path"])
        }
    }

    // ==================== 真实 UI 驱动 ====================

    private fun openFirstPhoto() {
        val node = ui.waitFor(seeded.first(), timeoutMs = 30000)
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        check(!bounds.isEmpty) { "照片格 bounds 为空" }
        ui.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        awaitText(PAGE_ANCHOR, timeoutMs = 20000)
    }

    /** 当前页码（`3 / 5` 里的 3）；读不到返回 -1。 */
    private fun currentPageNumber(): Int {
        val node = findText(PAGE_ANCHOR) ?: return -1
        return node.text?.toString()?.substringBefore(' ')?.toIntOrNull() ?: -1
    }

    /** 等页码离开 [from]；超时返回当前页码（调用方据此断言"没变"）。 */
    private fun awaitPageNumberChange(from: Int, timeoutMs: Long): Int {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val now = currentPageNumber()
            if (now > 0 && now != from) return now
            SystemClock.sleep(100)
        }
        return currentPageNumber()
    }

    /**
     * 滑动一次并在**滑动进行中**读审计行。
     *
     * `input swipe` 是同步阻塞的：命令返回时手势已结束、pager 已开始吸附，
     * 所以不能"滑完再读"。这里把 swipe 放到后台线程，主线程在滑动期间轮询
     * 审计行，抓到 `sampled=false` 就立刻返回。
     */
    private fun swipeAndReadMidScroll(): Map<String, String>? {
        val swiper = Thread {
            ui.shell("input swipe 900 1200 180 1200 220")
        }
        swiper.start()
        val deadline = SystemClock.uptimeMillis() + 4000
        var hit: Map<String, String>? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val audit = readAudit()
            if (audit != null &&
                audit["sampled"] == "false" &&
                audit["reason"] == BackdropFallbackReason.VIEWER_SCROLLING.name
            ) {
                hit = audit
                break
            }
            SystemClock.sleep(30)
        }
        swiper.join(5000)
        SystemClock.sleep(1200)
        return hit
    }

    /** 双击画面中心 → 放大（生产 `doubleTapScale`）。 */
    private fun doubleTapCenter() {
        val (x, y) = screenCenter()
        ui.shell("input tap $x $y")
        SystemClock.sleep(80)
        ui.shell("input tap $x $y")
        SystemClock.sleep(600)
    }

    /** 放大态下拖一次（单指拖动，生产 `detectDragGestures` 承接）。 */
    private fun dragOnce() {
        val (x, y) = screenCenter()
        ui.shell("input swipe $x $y ${x - 160} ${y - 120} 260")
        SystemClock.sleep(400)
    }

    private fun screenCenter(): Pair<Int, Int> {
        val out = ui.shell("wm size")
        val match = Regex("(\\d+)x(\\d+)").find(out)
        val w = match?.groupValues?.get(1)?.toInt() ?: 1080
        val h = match?.groupValues?.get(2)?.toInt() ?: 2400
        return (w / 2) to (h / 2)
    }

    // ==================== 审计行读取 ====================

    private fun latestAuditText(): String? {
        val node = auditNodes().firstOrNull() ?: return null
        val raw = node.text?.toString()?.takeIf { it.startsWith(AUDIT_PREFIX) }
            ?: node.contentDescription?.toString()?.takeIf { it.startsWith(AUDIT_PREFIX) }
            ?: return null
        return raw.substringAfter(AUDIT_PREFIX)
    }

    private fun auditNodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        collectAudit(ins.uiAutomation.rootInActiveWindow, out)
        return out
    }

    private fun collectAudit(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if ((text != null && text.startsWith(AUDIT_PREFIX)) ||
            (desc != null && desc.startsWith(AUDIT_PREFIX))
        ) {
            out += node
        }
        for (i in 0 until node.childCount) collectAudit(node.getChild(i), out)
    }

    /**
     * `AUDIT sampled=.. reason=.. path=.. glass=.. pan=(x,y) ..` → map。
     *
     * `pan=(12.5,-3.0)` 这类带括号/逗号的值会按 `=` 切开后原样保留，
     * 因此可以直接做"前后不相等"的比较。
     */
    private fun readAudit(): Map<String, String>? {
        val line = latestAuditText() ?: return null
        val parsed = mutableMapOf<String, String>()
        line.split(" ").forEach { token ->
            val index = token.indexOf('=')
            if (index > 0) {
                parsed[token.substring(0, index)] = token.substring(index + 1)
            }
        }
        return parsed.takeIf { it.containsKey("sampled") }
    }

    private fun awaitAudit(
        timeoutMs: Long = 8000,
        predicate: (Map<String, String>) -> Boolean
    ): Map<String, String>? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val audit = readAudit()
            if (audit != null && predicate(audit)) return audit
            SystemClock.sleep(80)
        }
        return null
    }

    private fun awaitText(pattern: Regex, timeoutMs: Long): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            findText(pattern)?.let { return it }
            SystemClock.sleep(100)
        }
        saveEvidence("viewer-missing")
        error("等待超时：$pattern")
    }

    private fun findText(pattern: Regex): AccessibilityNodeInfo? =
        findTextIn(ins.uiAutomation.rootInActiveWindow, pattern)

    private fun findTextIn(node: AccessibilityNodeInfo?, pattern: Regex): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()
        if (node.isVisibleToUser && text != null && pattern.matches(text)) return node
        for (i in 0 until node.childCount) findTextIn(node.getChild(i), pattern)?.let { return it }
        return null
    }

    // ==================== 失败取证 ====================

    /**
     * 失败现场：截图 + 无障碍树 + 前台 Activity + 权限 + logcat。
     *
     * 写进 `getExternalFilesDir(null)/liquid-glass-failures/`，由
     * `tools/run-emulator-evidence.sh` 在失败时整体拉回 CI artifact。
     * 没有这些，"超时"只能靠猜（上一轮就是这样）。
     */
    private fun saveEvidence(tag: String) {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "liquid-glass-failures")
            .apply { mkdirs() }
        val stamp = SystemClock.uptimeMillis()

        // 截图
        runCatching {
            ins.uiAutomation.takeScreenshot()?.let { bitmap ->
                try {
                    dir.resolve("$tag-$stamp.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

        // 无障碍树（含每条文本，用于判断停在哪个界面）
        runCatching {
            val sb = StringBuilder()
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 25) return
                if (node.isVisibleToUser) {
                    val rect = Rect().also { node.getBoundsInScreen(it) }
                    sb.append("  ".repeat(depth))
                        .append(node.className).append(" text=").append(node.text)
                        .append(" desc=").append(node.contentDescription)
                        .append(" bounds=").append(rect.toShortString())
                        .append('\n')
                }
                for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
            }
            walk(ins.uiAutomation.rootInActiveWindow, 0)
            dir.resolve("$tag-$stamp-tree.txt").writeText(sb.toString())
        }

        // 前台 Activity + 权限状态
        runCatching {
            val focus = ui.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
            val grants = ui.shell(
                "dumpsys package ${ctx.packageName} | grep -E 'READ_MEDIA|READ_EXTERNAL'"
            )
            dir.resolve("$tag-$stamp-focus.txt").writeText("$focus\n$grants")
        }

        // logcat + crash buffer
        runCatching {
            val main = ui.shell("logcat -d -v threadtime")
            val crash = ui.shell("logcat -d -b crash -v threadtime")
            dir.resolve("$tag-$stamp-logcat.txt").writeText("$main\n=== crash ===\n$crash")
        }

        Log.i(TAG, "evidence saved: $tag (dir=$dir)")
    }

    companion object {
        private const val TAG = "ViewerBackdropDevice"
    }
}
