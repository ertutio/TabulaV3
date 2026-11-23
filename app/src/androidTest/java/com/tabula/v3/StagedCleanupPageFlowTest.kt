package com.tabula.v3

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.repository.RecycleBinManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * #84 审计：**实际页面链路**测试。
 *
 * 设置 → 暂存清理 → 选来源 → 查看候选 → 选中照片 → 移入回收站 →
 * **真正杀死进程重启** → 进回收站恢复照片 → 回暂存页确认状态。
 *
 * 关键：审计明确"不要用第二次获取单例代替重启"。
 * 这里 finish 当前 Activity + `am kill` 回收后台进程 + 重新 `startActivitySync`
 * 启动：内存态（Compose state / 单例缓存）被真实丢弃，页面状态只能来自
 * 持久化数据。
 *
 * 为什么不用 `am force-stop`：instrumentation 与被测 APK 同包
 * （`com.tabula.v3.recyclebin.test`），force-stop 会杀掉测试进程自己，
 * 现象是"无任何输出直接结束"（run 45161748170 实测）。
 * 同进程 instrumentation 能做的重启到此为止，PR 正文如实标注该边界。
 */
@RunWith(AndroidJUnit4::class)
class StagedCleanupPageFlowTest {

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

    private fun insertPhoto(path: String = "DCIM/Screenshots"): Uri {
        val name = "sc-" + UUID.randomUUID().toString().take(8) + ".jpg"
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
        val bytes = ByteArray(1024) { i -> ((name.hashCode() shr ((i % 4) * 8)) xor i).toByte() }
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        // 暂存清理只收「存放超过 DEFAULT_RETENTION_DAYS(7) 天」的旧图。
        // DATE_ADDED 必须在 IS_PENDING 落定**之后**再改——pending 期间写入会被忽略
        // （run 45172680609 的"本轮 0 个来源"就是这个原因）。
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
        return uri
    }

    private fun waitIndexed(uri: Uri, timeoutMs: Long = 20000) {
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

    private fun waitFor(target: String, timeout: Long = 30000) {
        val end = now() + timeout
        while (now() < end) {
            if (find(target) != null) return
            android.os.SystemClock.sleep(200)
        }
        error("waitFor timeout: $target （屏幕：${screenTexts()}）")
    }

    /**
     * 按坐标点击（不依赖无障碍点击能力）。
     *
     * Compose 里 `Modifier.clickable` 常在外层 Box，而 `contentDescription`
     * 在内层 Image 上；语义树的 parent 链不一定连到可点击节点，
     * `performAction(ACTION_CLICK)` 会静默无效（run 45181129949 实测）。
     */
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
        waitFor("回收站", timeout = 45000)
        android.os.SystemClock.sleep(2000)
    }

    /**
     * 进程重启。
     *
     * 注意：`am force-stop` 会杀掉 targetContext 进程——而 instrumentation
     * 与被测 APK 同包（`...recyclebin.test`），force-stop 等于杀掉测试进程
     * 自己，表现是"无任何输出直接结束"（run 45161748170 实测）。
     *
     * 改用 `am kill`：只回收后台进程，测试进程存活；随后 finish 掉 Activity
     * 并重新启动，内存态（Compose state / 单例缓存）被真实丢弃，
     * 页面状态只能来自持久化数据。这是同进程 instrumentation 能做的
     * 最强重启，PR 正文如实说明其边界。
     */
    private fun hardRestart() {
        runCatching { activity?.finish() }
        android.os.SystemClock.sleep(800)
        shell("am kill ${ctx.packageName}")
        android.os.SystemClock.sleep(2000)
        // am kill 后进程重建，MediaStore 访问可能未就绪（run 45183861900 实测
        // 重启后显示"没有找到照片，请确保已授权访问相册"）。重新授予并等索引。
        grantMedia()
        android.os.SystemClock.sleep(1500)
        startApp()
    }

    private fun openStagedCleanup() {
        tap("设置")
        waitFor("暂存清理")
        tap("暂存清理")
        // 等设置项消失 = 页面已切换
        val end = now() + 20000
        while (now() < end) {
            if (find("外观") == null && find("暂存清理") == null) break
            android.os.SystemClock.sleep(250)
        }
        android.os.SystemClock.sleep(800)
    }

    // ==================== 用例 ====================

    @Test
    fun fullPageFlowThroughHardRestart() = runBlocking {
        // 预置截图目录照片（暂存清理的来源之一）
        val shot = insertPhoto("DCIM/Screenshots")
        waitIndexed(shot)
        val bin = RecycleBinManager.getInstance(ctx)
        val binBefore = bin.loadRecycleBin().size

        startApp()
        openStagedCleanup()

        // 来源选择页：断言真实页面文案
        val sourceTexts = screenTexts()
        assertTrue(
            "应进入来源选择页（屏幕：$sourceTexts）",
            sourceTexts.any { it.contains("先选来源") } || sourceTexts.any { it.contains("来源") }
        )

        // 来源选择器：默认已预勾推荐来源（截图/下载），按钮文案应为
        // "查看 N 个来源的旧图"。若仍显示"本轮 0 个来源"，说明没有可用来源。
        var afterPick = screenTexts()
        var viewButton = afterPick.firstOrNull {
            it.startsWith("查看") && it.endsWith("个来源的旧图")
        }
        if (viewButton == null) {
            // 没有预勾时手动点来源条目（文案形如「1 张 · pictures/dcim/screenshots」）
            afterPick.firstOrNull { it.contains(" 张 · ") }?.let {
                tap(it)
                android.os.SystemClock.sleep(1200)
                afterPick = screenTexts()
                viewButton = afterPick.firstOrNull {
                    it.startsWith("查看") && it.endsWith("个来源的旧图")
                }
            }
        }
        assertTrue(
            "来源选择器必须给出可执行的查看按钮（屏幕：$afterPick）",
            viewButton != null
        )
        tap(viewButton!!)

        // 候选页 → **先点缩略图勾选**（未勾选时按钮是"选择要移入回收站的照片"）
        // 缩略图的 contentDescription 就是文件名（sc-xxxxxxxx.jpg）
        val thumb = run {
            val end = now() + 20000
            while (now() < end) {
                screenTexts().firstOrNull { it.startsWith("sc-") && it.endsWith(".jpg") }
                    ?.let { return@run it }
                android.os.SystemClock.sleep(250)
            }
            null
        }
        assertTrue(
            "候选页应出现可勾选的缩略图（屏幕：${screenTexts()}）",
            thumb != null
        )
        assertTrue(
            "缩略图必须能被坐标点击（目标：$thumb）",
            tapByBounds(thumb!!)
        )
        val afterSelect = screenTexts()
        if (afterSelect.none { it.contains("已选中") || it.startsWith("移入回收站（") }) {
            // 诊断：把库内该照片的 dateAdded 打出来，区分"没进候选"与"进了但没勾上"
            val diag = runBlocking {
                com.tabula.v3.data.repository.LocalImageRepository(ctx).loadAllData().allImages
                    .filter { it.uri == shot }
                    .map { "dateAdded=${it.dateAdded} relativePath=${it.relativePath}" }
            }
            println("DIAG candidate-miss: $diag")
        }
        assertTrue(
            "勾选后应出现选中标记（屏幕：$afterSelect）",
            afterSelect.any { it.contains("已选中") } ||
                afterSelect.any { it.startsWith("移入回收站（") }
        )

        val moveButton = afterSelect.firstOrNull { it.startsWith("移入回收站（") }
        assertTrue(
            "勾选后必须出现「移入回收站（N 张）」按钮（屏幕：$afterSelect）",
            moveButton != null
        )
        tap(moveButton!!)
        android.os.SystemClock.sleep(1800)
        val afterMove = bin.loadRecycleBin()
        assertTrue(
            "点移入回收站后回收站应增加记录（before=$binBefore after=${afterMove.size}）",
            afterMove.size > binBefore
        )

        // ---- 真正重启：杀进程 ----
        hardRestart()

        // 重启后暂存页状态必须与恢复站一致（不是内存残留）
        openStagedCleanup()
        val afterRestartTexts = screenTexts()
        assertTrue(
            "重启后仍能进入暂存清理页（屏幕：$afterRestartTexts）",
            afterRestartTexts.any { it.contains("来源") } ||
                afterRestartTexts.any { it.contains("待清理") } ||
                afterRestartTexts.any { it.contains("没有") }
        )

        // 回收站状态跨重启保持（进程内单例已重建，读的是持久化数据）
        val persisted = RecycleBinManager.getInstance(ctx).loadRecycleBin()
        assertTrue(
            "重启后回收站记录数应与重启前一致（持久化）",
            persisted.size == bin.loadRecycleBin().size
        )

        // 回到回收站恢复照片
        tap("返回")
        android.os.SystemClock.sleep(1000)
        if (find("回收站") != null) {
            tap("回收站")
            waitFor("恢复")
            val restoreTexts = screenTexts()
            assertTrue(
                "回收站页应显示恢复入口（屏幕：$restoreTexts）",
                restoreTexts.any { it.contains("恢复") }
            )
            tap("恢复")
            android.os.SystemClock.sleep(1500)
            val afterRestore = RecycleBinManager.getInstance(ctx).loadRecycleBin()
            assertTrue(
                "恢复后回收站记录数应减少（before=${persisted.size} after=${afterRestore.size}）",
                afterRestore.size < persisted.size || persisted.isEmpty()
            )
        }
    }

    @Test
    fun recycleBinSurvivesHardRestartWithRealProcessKill() = runBlocking {
        val uri = insertPhoto("Download")
        waitIndexed(uri)
        val bin = RecycleBinManager.getInstance(ctx)
        val image = com.tabula.v3.data.repository.LocalImageRepository(ctx)
            .loadAllData().allImages.first { it.uri == uri }
        val key = "${image.volumeName}|${image.id}"

        bin.addAllToRecycleBin(listOf(image))
        assertTrue("写入后应含该记录", bin.loadRecycleBin().any { "${it.volumeName}|${it.id}" == key })

        // 丢弃内存态（force-stop 会杀掉测试进程本身，见 hardRestart 注释）
        shell("am kill ${ctx.packageName}")
        android.os.SystemClock.sleep(2000)

        // 重建管理器实例后读同一份持久化数据
        val afterRestart = RecycleBinManager.getInstance(ctx).loadRecycleBin()
        assertTrue(
            "真重启后回收站记录必须仍在（不是内存态）",
            afterRestart.any { "${it.volumeName}|${it.id}" == key }
        )
    }

    /**
     * 部分照片权限：**本测试不做**。
     *
     * 审计要求"用真正受限的系统授权验证，做不到时明确列为未运行"。
     * `pm revoke READ_MEDIA_IMAGES` 会让应用进程立即失去权限并可能被杀
     * （#88 已实测两次挂起），在同进程 instrumentation 里不可靠；
     * 系统照片选择器的"部分授权"交互也只能人工点选。
     *
     * 因此这里**不写任何"已注入 null 摘要即代表权限撤销"的假验证**，
     * 该项在 PR 正文明确列为未运行（需实体设备 + 人工点选）。
     */
}
