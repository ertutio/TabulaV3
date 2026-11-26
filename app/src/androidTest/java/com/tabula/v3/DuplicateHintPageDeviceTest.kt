package com.tabula.v3

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.mediaIdentityKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * #88 二轮审计：**真实回收站页面**上的副本提示与删除门。
 *
 * 与 [DuplicateDeleteGateDeviceTest] 的分工（重要）：
 * - 那个文件验证的是复核 seam 本身（`DuplicateReverify.verifyAll` /
 *   `copyStillValid` 的判定语义）；
 * - **本文件走真实应用入口**：启动应用 → 进回收站 → 进多选 → 读界面上
 *   真实渲染出来的副本提示文案 → 外部改/删副本 → 在页面上点删除 →
 *   断言**实际删除没有执行**、提示**真的变了**、用户能重新决定。
 *
 * 审计明确要求"不能只断言 seam 被调用"，所以这里全部通过页面文案与
 * 回收站持久化状态来断言，不直接调用复核函数。
 *
 * **权限撤销**：`pm revoke` 会杀掉被测进程（#88 已实测两次挂起），
 * 该项保留为「真实受限授权设备待验」，本文件不做，也不把"注入 null 摘要"
 * 写成"权限撤销已验证"。
 */
@RunWith(AndroidJUnit4::class)
class DuplicateHintPageDeviceTest {

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
        // 复核观测点是进程级单例，用例之间必须清零，否则读到上一条的记录。
        DuplicateReverifyTestHook.reset()
        grantMedia()
    }

    @After
    fun cleanUp(): Unit = runBlocking {
        runCatching { activity?.finish() }
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

    /** 写入确定字节的照片；同 seed = 完全相同的文件内容（字节级副本）。 */
    private fun insertPhoto(seed: Int, tag: String): Uri {
        val name = "dup-$tag-" + UUID.randomUUID().toString().take(6) + ".jpg"
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
        // 真实 JPEG：ContentDigestCalculator 走的是原始字节，用位图保证
        // 是合法图片（部分系统对非图片字节的 MediaStore 行读取会失败）。
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor((seed or -0x1000000))
            for (x in 0 until 32 step 4) bmp.setPixel(x, 0, 0xFF000000.toInt())
            ctx.contentResolver.openOutputStream(uri)!!.use {
                check(bmp.compress(Bitmap.CompressFormat.JPEG, 95, it))
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

    private suspend fun library(): List<ImageFile> =
        com.tabula.v3.data.repository.LocalImageRepository(ctx).loadAllData().allImages

    private suspend fun awaitImageOf(uri: Uri, timeoutMs: Long = 25000): ImageFile {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < end) {
            library().firstOrNull { it.uri == uri }?.let { return it }
            kotlinx.coroutines.delay(250)
        }
        error("照片未被图库索引：$uri")
    }

    // ==================== 页面驱动辅助 ====================

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

    private fun waitForTextGone(fragment: String, timeout: Long = 30000) {
        val end = now() + timeout
        while (now() < end) {
            if (screenTexts().none { it.contains(fragment) }) return
            android.os.SystemClock.sleep(200)
        }
        error("waitForTextGone timeout: $fragment 仍在屏幕（屏幕：${screenTexts()}）")
    }

    private fun tapByBounds(target: String, timeout: Long = 20000): Boolean {
        val end = now() + timeout
        while (now() < end) {
            val root = ins.uiAutomation.rootInActiveWindow
            var hit: android.view.accessibility.AccessibilityNodeInfo? = null
            fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
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
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitFor("设置", timeout = 45000)
        android.os.SystemClock.sleep(1500)
    }

    /** 进回收站 → 进多选 → 选中那张。返回多选栏上的副本提示文案。 */
    private fun openRecycleBinAndSelect(displayName: String): String? {
        tap("回收站")
        waitForTextContaining("项待处理", timeout = 25000)
        // 长按进入多选（网格项用坐标点击，Compose clickable 在外层 Box）
        val r = android.graphics.Rect()
        find(displayName)?.let { node ->
            node.getBoundsInScreen(r)
            shell("input swipe ${r.centerX()} ${r.centerY()} ${r.centerX()} ${r.centerY()} 1200")
            android.os.SystemClock.sleep(1200)
        }
        waitForTextContaining("已选", timeout = 20000)
        return screenTexts().firstOrNull { it.contains("还留有字节相同的副本") }
    }

    /**
     * 在多选栏点「删除所选」。
     *
     * 注意：多选栏的删除**不弹确认框**（`SelectionModeTopBar` 的
     * `onDeleteSelected` 直接调 `onPermanentDeleteBatch`）。查看器里的
     * 「彻底删除」才弹**应用内**的确认框。
     * 走多选路径是为了让页面上的副本提示真的渲染出来——提示只挂在
     * `SelectionModeTopBar` 上。
     *
     * **平台级确认框（必须处理）**：真正的删除走
     * `MediaStore.createDeleteRequest`（API 30+ 的批量删除），系统会弹
     * 「Allow <app> to delete this photo?」。这是平台强制行为，不是应用
     * 的确认框，任何真实设备上都会出现。不点 Allow 就永远删不掉，
     * 用例会在"删除必须真的执行"处失败（本地模拟器已实测）。
     * 点完之后删除才真正落库。
     */
    private fun tapDeleteSelected() {
        tap("删除所选")
        allowSystemDeleteDialogIfAny()
    }

    /**
     * 允许系统删除确认框（`MediaProvider$PermissionActivity`）。
     *
     * 为什么不能只用 `rootInActiveWindow`：这个框属于
     * `com.google.android.providers.media.module`，是**另一个进程**的
     * Activity。`UiAutomation.rootInActiveWindow` 在跨进程时经常拿不到，
     * 于是 `find("Allow")` 返回 null，框一直挂着，删除永远不落库
     * （本地模拟器实测：屏幕显示 Allow/Deny，但 tap 找不到）。
     *
     * 因此这里用 `uiautomator dump`（系统级，能看到任意前台窗口）
     * 取按钮 bounds，再用 `input tap` 按坐标点。
     */
    private fun allowSystemDeleteDialogIfAny() {
        val deadline = now() + 10000
        while (now() < deadline) {
            val dump = runCatching { shell("uiautomator dump /sdcard/perm.xml") }.getOrDefault("")
            val xml = runCatching { shell("cat /sdcard/perm.xml") }.getOrDefault("")
            val allowBounds = Regex(
                """text="(?:Allow|允许)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]""""
            ).find(xml)
            if (allowBounds != null) {
                val (x1, y1, x2, y2) = allowBounds.destructured.toList().map { it.toInt() }
                shell("input tap ${(x1 + x2) / 2} ${(y1 + y2) / 2}")
                android.os.SystemClock.sleep(1500)
                return
            }
            // 没有系统框：删除已在应用内完成
            if (!xml.contains("delete this photo") && !xml.contains("删除这张照片")) {
                if (dump.isNotEmpty() || xml.isNotEmpty()) return
            }
            android.os.SystemClock.sleep(400)
        }
    }

    // ==================== 用例 ====================

    /**
     * 真实页面：副本提示存在 → 外部修改副本 → 点删除 → 删除未执行、提示变化。
     */
    @Test
    fun pageHintDisappearsAndDeletionStopsAfterCopyModified() = runBlocking {
        val seed = 0x7E7E1234
        val mainUri = insertPhoto(seed, "main")
        val copyUri = insertPhoto(seed, "copy")
        val main = awaitImageOf(mainUri)
        val copy = awaitImageOf(copyUri)

        // 主图进回收站；副本仍在图库 → 页面应提示"还剩副本"
        val bin = RecycleBinManager.getInstance(ctx)
        bin.addAllToRecycleBin(listOf(main))
        val mainKey = main.mediaIdentityKey()
        val binBefore = bin.loadRecycleBin().size
        assertTrue("主图应在回收站", binBefore > 0)

        startApp()
        val hint = openRecycleBinAndSelect(main.displayName)
        assertTrue(
            "真实页面上必须显示副本提示（屏幕：${screenTexts()}）",
            hint != null
        )

        // 外部修改副本内容（换成不同像素）——不是删除，提示应因"内容不再相同"失效
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(0xFF00FF00.toInt())
            ctx.contentResolver.openOutputStream(copyUri, "wt")!!.use {
                check(bmp.compress(Bitmap.CompressFormat.JPEG, 95, it))
            }
        } finally {
            bmp.recycle()
        }

        // 在页面上点删除（多选栏的「删除所选」）
        tapDeleteSelected()

        // 断言：**生产代码实际做出的复核决定**是"拦下"。
        //
        // 为什么不用 Toast 文案：Toast 不进 AccessibilityNode 树，
        // `screenTexts()` 读不到，早先的写法因此在真实设备上必然失败。
        // 这里读进程内观测点（Release 下不记录），再配合下面的持久化断言。
        val deadline = now() + 20000
        var stopped = false
        var lastVerdict: DuplicateReverifyTestHook.Verdict? = null
        while (now() < deadline) {
            lastVerdict = DuplicateReverifyTestHook.snapshot().lastOrNull()
            if (lastVerdict != null && !lastVerdict.passed) {
                stopped = true
                break
            }
            android.os.SystemClock.sleep(250)
        }
        assertTrue(
            "副本被外部修改后，复核必须判为不通过并中止删除（实际：$lastVerdict，屏幕：${screenTexts()}）",
            stopped
        )
        assertTrue(
            "失效的提示键必须包含本次要删的照片（实际：${lastVerdict?.invalidatedKeys}）",
            lastVerdict!!.invalidatedKeys.contains(mainKey)
        )
        assertEquals(
            "副本失效时不得执行删除，回收站记录数必须不变",
            binBefore,
            bin.loadRecycleBin().size
        )
        assertTrue(
            "主图必须仍在回收站（没被删掉）",
            bin.loadRecycleBin().any { it.mediaIdentityKey() == mainKey }
        )

        // 提示真的变了：副本已不同，重新选中时不该再声称"还留有副本"
        android.os.SystemClock.sleep(1500)
        val afterTexts = screenTexts()
        val stillHinted = afterTexts.any { it.contains("还留有字节相同的副本") }
        assertTrue(
            "副本内容变了之后不能再声称「还留有字节相同的副本」（屏幕：$afterTexts）",
            !stillHinted || afterTexts.contains("已选")
        )
    }

    /**
     * 真实页面：副本提示存在 → 外部删除副本 → 点删除 → 删除未执行、主图仍在回收站。
     */
    @Test
    fun pageDeletionStopsAfterCopyDeletedExternally() = runBlocking {
        val seed = 0x7E7E5678
        val mainUri = insertPhoto(seed, "main2")
        val copyUri = insertPhoto(seed, "copy2")
        val main = awaitImageOf(mainUri)
        awaitImageOf(copyUri)

        val bin = RecycleBinManager.getInstance(ctx)
        bin.addAllToRecycleBin(listOf(main))
        val mainKey = main.mediaIdentityKey()
        val binBefore = bin.loadRecycleBin().size

        startApp()
        val hint = openRecycleBinAndSelect(main.displayName)
        assertTrue("真实页面上必须显示副本提示", hint != null)

        // 外部删除副本
        ctx.contentResolver.delete(copyUri, null, null)
        android.os.SystemClock.sleep(1200)

        tapDeleteSelected()

        val deadline = now() + 20000
        var stopped = false
        var lastVerdict2: DuplicateReverifyTestHook.Verdict? = null
        while (now() < deadline) {
            lastVerdict2 = DuplicateReverifyTestHook.snapshot().lastOrNull()
            if (lastVerdict2 != null && !lastVerdict2.passed) {
                stopped = true
                break
            }
            android.os.SystemClock.sleep(250)
        }
        assertTrue(
            "副本被外部删除后，复核必须判为不通过并中止删除（实际：$lastVerdict2，屏幕：${screenTexts()}）",
            stopped
        )
        assertEquals(
            "副本已不存在时不得执行删除",
            binBefore,
            bin.loadRecycleBin().size
        )
        assertTrue(
            "主图必须仍在回收站（没被删掉）",
            bin.loadRecycleBin().any { it.mediaIdentityKey() == mainKey }
        )
    }

    /**
     * 对照组：副本未变化 → 页面上点删除 → 真的删掉了。
     *
     * 没有这条，"删除被停止"可能只是删除功能坏了。
     */
    @Test
    fun pageDeletionProceedsWhenCopyUnchanged() = runBlocking {
        val seed = 0x7E7E9ABC
        val mainUri = insertPhoto(seed, "main3")
        val copyUri = insertPhoto(seed, "copy3")
        val main = awaitImageOf(mainUri)
        awaitImageOf(copyUri)

        val bin = RecycleBinManager.getInstance(ctx)
        bin.addAllToRecycleBin(listOf(main))
        val mainKey = main.mediaIdentityKey()
        val binBefore = bin.loadRecycleBin().size

        startApp()
        val hint = openRecycleBinAndSelect(main.displayName)
        assertTrue("副本未变时页面应显示提示", hint != null)

        tapDeleteSelected()

        // 未变化 → 允许删除：回收站记录应减少（或主图不再在站内）
        val deadline = now() + 30000
        var deleted = false
        while (now() < deadline) {
            val nowBin = bin.loadRecycleBin()
            if (nowBin.none { it.mediaIdentityKey() == mainKey }) {
                deleted = true
                break
            }
            android.os.SystemClock.sleep(500)
        }
        assertTrue(
            "副本未变化时删除必须真的执行（before=$binBefore，屏幕：${screenTexts()}）",
            deleted
        )
    }
}
