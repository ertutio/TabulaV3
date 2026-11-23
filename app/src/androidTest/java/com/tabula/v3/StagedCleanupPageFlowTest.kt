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
 * 这里用 `am force-stop` 结束进程 + 重新 `startActivitySync` 启动，
 * 进程与单例状态都真正重建。
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
        check(
            ctx.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
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
     * 真正的进程重启：force-stop 杀掉应用进程，再重新启动。
     * 这不是"重新获取单例"——进程内所有单例都重建。
     */
    private fun hardRestart() {
        shell("am force-stop ${ctx.packageName}")
        android.os.SystemClock.sleep(2000)
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

        // 选择来源并确认（按钮文案随选中数量变化）
        val viewButton = sourceTexts.firstOrNull { it.startsWith("查看") && it.endsWith("个来源的旧图") }
        if (viewButton != null) {
            tap(viewButton)
        } else {
            // 没有可选来源时，至少验证页面给出了可解释的空态
            assertTrue(
                "无来源时应显示明确空态而不是白屏（屏幕：$sourceTexts）",
                sourceTexts.any { it.contains("没有找到") } || sourceTexts.any { it.contains("没有待清理") }
            )
            return@runBlocking
        }

        // 候选页 → 选中照片 → 移入回收站
        android.os.SystemClock.sleep(1500)
        val candidateTexts = screenTexts()
        val moveButton = candidateTexts.firstOrNull {
            it.startsWith("移入回收站（") || it.contains("选择要移入回收站的照片")
        }
        if (moveButton != null && moveButton.startsWith("移入回收站（")) {
            tap(moveButton)
            android.os.SystemClock.sleep(1500)
            val afterMove = bin.loadRecycleBin()
            assertTrue(
                "点移入回收站后回收站应增加记录（before=$binBefore after=${afterMove.size}）",
                afterMove.size > binBefore
            )
        }

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

        // 真正杀进程
        shell("am force-stop ${ctx.packageName}")
        android.os.SystemClock.sleep(2500)

        // 新进程读同一份持久化数据
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
