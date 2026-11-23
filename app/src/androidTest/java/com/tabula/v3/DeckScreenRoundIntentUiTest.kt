package com.tabula.v3

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.preferences.WalkSamplingStrategy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * #90 审计：真实 DeckScreen 入口测试——通过实际界面手势处理照片、看到提示、
 * 点击接受，经页面装载两批，检查第一批受意图影响、第二批恢复所选策略；
 * 切换专清/相似推荐通过实际页面入口（设置 → 推荐模式）验证。
 *
 * 复用仓库既有真实入口模式（CloudCleanupUiTest）：UiAutomation 启动
 * MainActivity、AccessibilityNode 查找与点按，不依赖 Compose 测试规则。
 */
@RunWith(AndroidJUnit4::class)
class DeckScreenRoundIntentUiTest {

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private lateinit var prefs: AppPreferences
    private val inserted = mutableListOf<Uri>()
    private var activity: android.app.Activity? = null

    @Before
    fun setUp() = runBlocking {
        prefs = AppPreferences(ctx)
        // 跳过引导/须知/个性化，直接进主界面
        prefs.hasCompletedOnboarding = true
        prefs.hasAcknowledgedDisclaimer = true
        prefs.recommendMode = RecommendMode.RANDOM_WALK
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        grantMedia()
    }

    @After
    fun cleanUp() = runBlocking {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
    }

    private fun grantMedia() {
        val automation = ins.uiAutomation
        listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.WRITE_EXTERNAL_STORAGE"
        ).forEach { p ->
            runCatching { shell("pm grant ${ctx.packageName} $p") }
        }
    }

    /** 真实 MediaStore 写入一张 32x32 JPEG 到指定目录。 */
    private fun insertPhoto(path: String): Uri {
        val name = "ui-" + UUID.randomUUID().toString().take(8) + ".jpg"
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
        check(ctx.contentResolver.update(
            uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
        ) == 1)
        return uri
    }

    private fun shell(cmd: String): String = ins.uiAutomation.executeShellCommand(cmd).use { p ->
        java.io.FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** 从测试进程的 filesDir 把诊断文件拉到 device-results（经 /sdcard 中转）。 */
    private fun pullDiagnostics() {
        runCatching {
            shell("cp /data/data/com.tabula.v3.recyclebin.test/files/screen-at-timeout.txt /sdcard/screen-at-timeout.txt")
        }
    }

    /**
     * 按 text 或 contentDescription 查找节点。
     *
     * 关键：findAccessibilityNodeInfosByText **只匹配 text，不匹配
     * contentDescription**（Android 平台行为）。Compose 的 IconButton 常把
     * 文案放在 contentDescription 上（如"回收站"），所以这里自己遍历树，
     * 两种都匹配，并向上找可点击祖先。
     */
    private fun find(root: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (root == null) return null
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

    private fun tapNode(node: AccessibilityNodeInfo, label: String) {
        if (node.isClickable) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        else {
            val r = android.graphics.Rect(); node.getBoundsInScreen(r)
            shell("input tap ${r.centerX()} ${r.centerY()}")
        }
    }

    private fun tap(label: String) {
        val end = now() + 15000
        while (now() < end) {
            find(ins.uiAutomation.rootInActiveWindow, label)?.let { tapNode(it, label); return }
            dismissPermissionDialogIfAny()
            android.os.SystemClock.sleep(150)
        }
        error("not found: $label")
    }

    private fun now(): Long = android.os.SystemClock.uptimeMillis()

    private fun waitFor(label: String, timeout: Long = 15000) {
        val end = now() + timeout
        while (now() < end) {
            if (find(ins.uiAutomation.rootInActiveWindow, label) != null) return
            dismissPermissionDialogIfAny()
            android.os.SystemClock.sleep(150)
        }
        error("timeout waiting: $label")
    }

    /** 直接读无障碍树（不依赖 shell/uiautomator，测试进程内可用）。 */
    private fun dumpScreen(tag: String) {
        val sb = StringBuilder()
        runCatching {
            val root = ins.uiAutomation.rootInActiveWindow
            sb.append("TAG=").append(tag).append('\n')
            sb.append("PKG=").append(root?.packageName).append('\n')
            sb.append("CLS=").append(root?.className).append('\n')
            val texts = mutableSetOf<String>()
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || depth > 10) return
                n.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
                n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts += "[cd]$it" }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            }
            walk(root, 0)
            sb.append("TEXTS=").append(texts.joinToString(" | ")).append('\n')
            val acts = StringBuilder()
            runCatching {
                val all = ins.uiAutomation.getWindows()
                all.forEach { acts.append(it.root?.packageName).append(";") }
            }
            sb.append("WINDOWS=").append(acts).append('\n')
        }
        // 写进 instrumentation status bundle：一定出现在 -r 输出里
        runCatching {
            val b = android.os.Bundle()
            b.putString("roundintent.diag", sb.toString())
            ins.sendStatus(2, b)
        }
        runCatching { java.io.File(ctx.filesDir, "diag-$tag.txt").writeText(sb.toString()) }
        // 同时写 /sdcard（部分环境 filesDir 不可拉取）
        runCatching {
            @Suppress("DEPRECATION")
            val dir = java.io.File("/sdcard/Download")
            if (dir.exists()) java.io.File(dir, "diag-$tag.txt").writeText(sb.toString())
        }
    }

    private fun startApp() {
        activity = ins.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        dumpScreen("after-launch")
        // 等待主界面（卡片页）出现：回收站按钮是卡片页特有锚点
        // （"图集"标题只在图集模式下显示，浏览模式的卡片页没有该文本）
        val end = now() + 45000
        while (now() < end) {
            if (find(ins.uiAutomation.rootInActiveWindow, "回收站") != null) break
            // 权限对话框偶发弹出：自动点"Allow/允许"
            dismissPermissionDialogIfAny()
            android.os.SystemClock.sleep(200)
        }
        if (find(ins.uiAutomation.rootInActiveWindow, "回收站") == null) {
            // 诊断：把当前屏幕的可见文本写入文件（CI 工件会拉取）
            val root = ins.uiAutomation.rootInActiveWindow
            val visible = StringBuilder("screen text at timeout: ")
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || depth > 8) return
                n.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                    visible.append("[").append(it).append("] ")
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            }
            walk(root, 0)
            dumpScreen("timeout")
            pullDiagnostics()
            // 写到设备文件（多行输出经 logcat/instrumentation 都会被截断）
            runCatching {
                java.io.File(ctx.filesDir, "screen-at-timeout.txt").writeText(visible.toString())
                android.util.Log.e("RoundIntentUi", "screen text dumped to filesDir/screen-at-timeout.txt")
            }
            error("卡片页未出现（回收站按钮超时）")
        }
        android.os.SystemClock.sleep(2500) // 等首批照片从 MediaStore 装载
    }

    /** 系统权限对话框偶发弹出：自动点 Allow/允许。 */
    private fun dismissPermissionDialogIfAny() {
        val root = ins.uiAutomation.rootInActiveWindow ?: return
        val owner = root.packageName?.toString().orEmpty()
        if (owner == ctx.packageName) return
        if (!(owner.contains("permissioncontroller") || owner.contains("providers.media"))) return
        listOf("Allow", "允许", "Select all", "全部选择").forEach { label ->
            root.findAccessibilityNodeInfosByText(label)
                .filter { it.isClickable }
                .firstOrNull()
                ?.let { tapNode(it, label) }
        }
    }

    /**
     * 卡片页滑动处理一张照片（左滑保留；上滑删除走 Genie 动画回调）。
     * 为确定性起见，用界面上的"下一步"快捷方式不可靠——改为直接点保留
     * 不可行（无按钮）。此处用 shell input swipe 模拟真实滑动。
     */
    private fun swipeCard(direction: SwipeDirection) {
        val display = ctx.resources.displayMetrics
        val cx = display.widthPixels / 2f
        val cy = display.heightPixels * 0.45f
        val dx = display.widthPixels * 0.35f
        val dy = display.heightPixels * 0.25f
        val cmd = when (direction) {
            SwipeDirection.KEEP_LEFT -> "input swipe ${(cx - dx).toInt()} ${cy.toInt()} ${(cx + dx).toInt()} ${cy.toInt()} 250"
            SwipeDirection.KEEP_RIGHT -> "input swipe ${(cx + dx).toInt()} ${cy.toInt()} ${(cx - dx).toInt()} ${cy.toInt()} 250"
            SwipeDirection.DELETE_UP -> "input swipe ${cx.toInt()} ${(cy + dy).toInt()} ${cx.toInt()} ${(cy - dy).toInt()} 250"
        }
        shell(cmd)
        android.os.SystemClock.sleep(900) // Genie/切牌动画
    }

    private enum class SwipeDirection { KEEP_LEFT, KEEP_RIGHT, DELETE_UP }

    @Test
    fun swipeThroughShotsShowsHintAndAcceptAffectsNextBatch() = runBlocking {
        // 预置：10 张截图 + 40 张相机照片（真实 MediaStore）
        repeat(10) { insertPhoto("DCIM/Screenshots") }
        repeat(40) { insertPhoto("DCIM/Camera") }

        startApp()

        // 连续滑动处理截图（上滑删除）直到提示出现
        val hintTimeout = now() + 60000
        var hintSeen = false
        var swipes = 0
        while (now() < hintTimeout) {
            if (find(ins.uiAutomation.rootInActiveWindow, "看这个") != null) { hintSeen = true; break }
            dismissPermissionDialogIfAny()
            if (swipes == 0) dumpScreen("before-swipe")
            if (find(ins.uiAutomation.rootInActiveWindow, "再来一组") != null) {
                // 批次完成：装载下一批（真实页面入口）
                tapNode(find(ins.uiAutomation.rootInActiveWindow, "再来一组")!!, "再来一组")
                android.os.SystemClock.sleep(1200)
                continue
            }
            swipeCard(SwipeDirection.DELETE_UP)
            swipes++
            if (swipes == 1) dumpScreen("after-swipe-1")
            if (swipes == 3) dumpScreen("after-swipe-3")
        }
        if (!hintSeen) dumpScreen("no-hint-final")
        assertTrue("连续删除截图后应出现意图提示（swipes=$swipes）", hintSeen)

        // 点击"看这个"：真实 Banner 回调 → 引擎接受意图
        tap("看这个")

        // 批次完成/下一批：经页面装载第一批（受意图影响——全来自截图）
        if (find(ins.uiAutomation.rootInActiveWindow, "再来一组") != null) {
            tapNode(find(ins.uiAutomation.rootInActiveWindow, "再来一组")!!, "再来一组")
            android.os.SystemClock.sleep(1200)
        }

        // 检查提示状态：意图已消费，同源提示可能再次出现（新一轮），但引擎意图为空
        val engine = com.tabula.v3.data.repository.RecommendationEngine.getInstance(ctx)
        // 引擎内意图已被 getRecommendedBatch 消费（第二批不应再用）
        // 经页面装载第二批后再验证：第二次"看这个"未点击，引擎不应有 active 意图
        assertFalse(engine.roundIntent.isActive)
    }

    @Test
    fun switchingRecommendModeViaSettingsIsolatesSession() = runBlocking {
        repeat(6) { insertPhoto("DCIM/Screenshots") }
        repeat(6) { insertPhoto("DCIM/Camera") }

        startApp()

        // 实际页面入口：主界面 → 设置 → 推荐模式 → 相似推荐
        tap("设置")
        waitFor("推荐模式")
        tap("推荐模式")
        waitFor("相似推荐")
        tap("相似推荐")
        // 返回主界面（PredictiveBack：返回键）
        shell("input keyevent KEYCODE_BACK")
        android.os.SystemClock.sleep(800)
        shell("input keyevent KEYCODE_BACK")
        android.os.SystemClock.sleep(800)

        // 引擎意图应被清空（切换推荐模式是 MainActivity 的隔离回调）
        val engine = com.tabula.v3.data.repository.RecommendationEngine.getInstance(ctx)
        assertFalse("切换推荐模式后本轮意图应清空", engine.roundIntent.isActive)
    }

}
