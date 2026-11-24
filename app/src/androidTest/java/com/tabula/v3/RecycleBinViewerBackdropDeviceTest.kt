package com.tabula.v3

import android.Manifest
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
import com.tabula.v3.ui.components.liquidglass.ViewerBackdropSync
import com.tabula.v3.ui.components.liquidglass.shouldAttachPhotoBackdrop
import com.tabula.v3.ui.components.liquidglass.shouldLoadBackdropPhoto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * 采样层是**另一份**静止的 `ContentScale.Fit` Bitmap（[LiquidGlassPhotoBackdropViewer]
 * 里画一次），而查看器的照片经 `HorizontalPager` 平移、`graphicsLayer{scale/translation}`
 * 缩放拖动。两者只在"没翻页、没放大、没拖动"时才是同一幅画面。此前只有
 * JVM 层的 `ViewerBackdropSync` 单测，**没有任何测试操作过真实查看器**——
 * 也就是说"真实页面上真的会回退"这件事从未被验证。
 *
 * ## 本测试做什么
 *
 * 操作**真实**的 `RecycleBinScreen` → `FullScreenViewer`（不是预览页、不是替身），
 * 覆盖四个状态并检查采样方案：
 *
 * | 场景 | 期望 |
 * | --- | --- |
 * | 静止（翻页完成） | `sampled=true`，`reason=NONE` |
 * | 翻页中 | `sampled=false`，`reason=VIEWER_SCROLLING` |
 * | 放大后 | `sampled=false`，`reason=VIEWER_TRANSFORMED` |
 * | 拖动后 | `sampled=false`，`reason=VIEWER_TRANSFORMED` |
 *
 * ## 采样方案怎么读出来
 *
 * 查看器把当前方案写进 `LiquidGlassBackdropState`，并由顶栏遮罩上的隐藏审计
 * 文本暴露成一行 `AUDIT ...`（与调试预览同格式，见 `auditMarkers`）。测试从
 * 无障碍树读这一行——读的是**生产组件自己的状态**，不是测试另算一遍。
 *
 * ## 环境前提
 *
 * - 模拟器（`allowSyntheticMediaProbe=true`、HARDWARE=ranchu/goldfish），
 *   与其它设备测试同口径；
 * - 需要 API 33+（采样层门槛）；低版本直接跳过并记录原因，不伪装通过。
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

    /** 审计行前缀（`auditMarkers` 的输出形状）。 */
    private val AUDIT_PREFIX = "AUDIT "

    private val seeded = mutableListOf<String>()

    @Before
    fun requireEmulator() {
        // 与 FullRecoveryUi/PartialSelectionUi 同口径：合成媒体探针必须显式开启，
        // 且必须是模拟器——真机不跑（会写真实相册）。
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))

        // 权限自备，不依赖 CI 在外部 pm grant：
        // 少一个"外部前置没做 → 测试红了但看不出原因"的失败模式。
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ui.shellScript("pm grant ${ctx.packageName} ${Manifest.permission.READ_MEDIA_IMAGES} 2>&1")
        }
    }

    @After
    fun cleanup() = kotlinx.coroutines.runBlocking {
        runCatching {
            val manager = RecycleBinManager.getInstance(ctx)
            val rows = manager.loadRecycleBin().filter { row ->
                seeded.any { row.displayName.startsWith(it) }
            }
            if (rows.isNotEmpty()) manager.removeAllFromRecycleBin(rows, permanentlyRemoved = true)
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
    }

    @Test
    fun viewerBackdropFallsBackWhileScrollingZoomingAndPanning() =
        kotlinx.coroutines.runBlocking {
        // API 门槛：采样层要求 33+。低于门槛时这条测试没有意义——
        // 明确跳过并记录，而不是让它"碰巧通过"。
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Log.i(TAG, "skip: API ${android.os.Build.VERSION.SDK_INT} < 33，采样层不存在")
            return@runBlocking
        }

        // 实验室开关必须打开，否则生产入口根本不会接背景
        AppPreferences(ctx).liquidGlassLabEnabled = true
        assertTrue(
            "前置：开关打开且 API 达标时必须加载采样照片",
            shouldLoadBackdropPhoto(liquidGlassEnabled = true, sdkInt = android.os.Build.VERSION.SDK_INT)
        )

        // 造 3 张照片并移入回收站，让查看器有可翻的页
        val names = (1..3).map { index ->
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
        val rows = LocalImageRepository(ctx).loadAllData().allImages
            .filter { row -> names.any { row.displayName == it } }
        assertEquals("前置：3 张夹具照片都要能被图库读到", 3, rows.size)
        RecycleBinManager.getInstance(ctx).addAllToRecycleBin(rows)

        // 走真实入口：图库 → 回收站 → 点开照片 → 全屏查看器
        ui.launch()
        ui.waitFor("回收站", timeoutMs = 30000)
        ui.tap("回收站")
        ui.waitFor(BIN_COUNT_ANCHOR, timeoutMs = 30000)
        openFirstPhoto()

        // ===== 1. 静止（翻页完成）：必须采样 =====
        val settled = awaitAudit { audit ->
            audit["sampled"] == "true" && audit["reason"] == "NONE"
        }
        assertNotNull(
            "翻页完成、未缩放未拖动时必须采样（实际：${latestAuditText()}）",
            settled
        )
        assertEquals("静止时不应有回退原因", "NONE", settled!!["reason"])

        // ===== 2. 翻页中：必须回退 =====
        // 用一次跨屏滑动制造"正在翻页"。滑动结束后 pager 会吸附到整页，
        // 因此"翻页中"的判定必须在滑动进行中读取。
        val scrolling = swipeAndReadMidScroll()
        assertNotNull(
            "翻页中必须回退（实际：${latestAuditText()}）",
            scrolling
        )
        assertEquals(
            "翻页中的回退原因必须是 VIEWER_SCROLLING",
            BackdropFallbackReason.VIEWER_SCROLLING.name,
            scrolling!!["reason"]
        )
        assertEquals("翻页中不能继续采样", "false", scrolling["sampled"])

        // ===== 3. 翻页完成后：必须恢复采样 =====
        val afterSwipe = awaitAudit { audit ->
            audit["sampled"] == "true" && audit["reason"] == "NONE"
        }
        assertNotNull(
            "翻页完成后必须恢复采样，不能永久回退（实际：${latestAuditText()}）",
            afterSwipe
        )

        // ===== 4. 放大后：必须回退 =====
        doubleTapCenter()
        val zoomed = awaitAudit { audit ->
            audit["sampled"] == "false" &&
                audit["reason"] == BackdropFallbackReason.VIEWER_TRANSFORMED.name
        }
        assertNotNull(
            "放大后必须回退为 VIEWER_TRANSFORMED（实际：${latestAuditText()}）",
            zoomed
        )

        // ===== 5. 拖动后：必须回退 =====
        // 放大态下才有可拖动的余量（生产 clampOffset 在 scale=1 时把偏移钳到 0）
        dragOnce()
        val panned = awaitAudit { audit ->
            audit["sampled"] == "false" &&
                audit["reason"] == BackdropFallbackReason.VIEWER_TRANSFORMED.name
        }
        assertNotNull(
            "拖动后必须保持回退为 VIEWER_TRANSFORMED（实际：${latestAuditText()}）",
            panned
        )

        // ===== 6. 双指缩回原比例后：必须恢复采样 =====
        // 这条是"回退不是永久状态"的反向证明
        doubleTapCenter()
        val restored = awaitAudit { audit ->
            audit["sampled"] == "true" && audit["reason"] == "NONE"
        }
        assertNotNull(
            "缩回原比例后必须恢复采样（实际：${latestAuditText()}）",
            restored
        )
    }

    // ==================== 真实 UI 驱动 ====================

    private fun openFirstPhoto() {
        // 回收站列表里点第一张照片 → 进入全屏查看器（列表页没有"恢复"，
        // 恢复只在点开后的查看器底部，见 #84 交接记录）
        val node = ui.waitFor(seeded.first(), timeoutMs = 30000)
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        check(!bounds.isEmpty) { "照片格 bounds 为空" }
        ui.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        awaitText(PAGE_ANCHOR, timeoutMs = 20000)
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
        // 滑动可能把页面停在中间；等它吸附完成，后续步骤才有确定起点
        SystemClock.sleep(1200)
        return hit
    }

    /** 双击画面中心 → 放大到 3x（生产 `doubleTapScale`）。 */
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

    /** 无障碍树里最新的 `AUDIT ...` 行。 */
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
        // 生产侧把审计行同时写进 text 与 contentDescription：
        // 透明 1sp 文本在部分系统上不进 text，但 contentDescription 一定可读。
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if ((text != null && text.startsWith(AUDIT_PREFIX)) ||
            (desc != null && desc.startsWith(AUDIT_PREFIX))
        ) {
            out += node
        }
        for (i in 0 until node.childCount) collectAudit(node.getChild(i), out)
    }

    /** `AUDIT sampled=true reason=NONE glass=... ` → map。 */
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

    /** 轮询直到审计行满足条件；超时返回 null（调用方按红灯处理）。 */
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

    private fun saveEvidence(tag: String) {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "liquid-glass-failures")
            .apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                dir.resolve("$tag-${SystemClock.uptimeMillis()}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    companion object {
        private const val TAG = "ViewerBackdropDevice"
    }
}
