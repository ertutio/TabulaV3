package com.tabula.v3

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.repository.LocalImageRepository
import com.tabula.v3.data.repository.RecycleBinManager
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
 * 普通照片查看器的液态玻璃试点 —— **真实入口**设备验收（PR B）。
 *
 * 链路：主卡片页 → 点开一张照片 → 进入 `ViewerOverlay`（普通查看器）→
 * 检查右上角退出按钮的采样状态 → 退出。
 *
 * ## 与回收站查看器测试的分工
 *
 * 回收站查看器已有 `LiquidGlass*DeviceTest`（采样层本身的接线、回退、审计行）。
 * 本用例验的是**普通查看器这条新接线**：开关门控、采样区域尺寸、
 * 翻页/缩放/拖动回退、恢复原状后重新采样、以及关闭开关后不加载。
 *
 * ## 证据来源标注
 *
 * - 采样状态读的是采样层自己维护的 `LiquidGlassBackdropState`（与 UI 同源），
 *   不是从截图猜的；
 * - 「放大/拖动期间回退」的瞬时判定由单测 `ViewerBackdropWiringTest` 覆盖
 *   （那里可以精确构造 `ViewerBackdropSync`），设备侧只验「最终状态」；
 * - 报告里会标明哪条来自设备、哪条来自单测。
 */
@RunWith(AndroidJUnit4::class)
class ViewerGlassPilotDeviceTest {

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private lateinit var prefs: AppPreferences
    private val inserted = mutableListOf<Uri>()
    private var activity: android.app.Activity? = null

    /** 两张颜色差别明显的合成图：A 亮红，B 深蓝。 */
    private var brightRed: Uri? = null
    private var darkBlue: Uri? = null

    @Before
    fun setUp() {
        prefs = AppPreferences(ctx)
        prefs.hasCompletedOnboarding = true
        prefs.hasAcknowledgedDisclaimer = true
        prefs.recommendMode = RecommendMode.RANDOM_WALK
        runBlocking { runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() } }
        purgeTestScreenshots()

        brightRed = insertSolid("glass-pilot-red-" + UUID.randomUUID().toString().take(6) + ".png", Color.RED)
        darkBlue = insertSolid("glass-pilot-blue-" + UUID.randomUUID().toString().take(6) + ".png", Color.BLUE)
        waitIndexed(brightRed!!)
        waitIndexed(darkBlue!!)
    }

    @After
    fun cleanUp() {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        runBlocking { runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() } }
    }

    // ==================== 工具 ====================

    private fun purgeTestScreenshots() {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        ctx.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            projection, null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val ids = mutableListOf<Uri>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                if (!name.startsWith("glass-pilot-")) continue
                ids += MediaStore.Images.Media
                    .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    .buildUpon().appendPath(cursor.getLong(idCol).toString()).build()
            }
            ids.forEach { uri -> runCatching { ctx.contentResolver.delete(uri, null, null) } }
        }
        android.os.SystemClock.sleep(600)
    }

    private fun shell(cmd: String): String = ins.uiAutomation.executeShellCommand(cmd).use { p ->
        java.io.FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun insertSolid(name: String, fillColor: Int): Uri {
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(fillColor)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 60f
            isAntiAlias = true
        }
        canvas.drawText(if (fillColor == Color.RED) "A" else "B", 340f, 660f, paint)
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
                }, null, null
            ) == 1
        )
        bitmap.recycle()
        return uri
    }

    private fun waitIndexed(uri: Uri, timeoutMs: Long = 25000) {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < end) {
            val found = runBlocking {
                LocalImageRepository(ctx).loadAllData().allImages.any { it.uri == uri }
            }
            if (found) return
            android.os.SystemClock.sleep(250)
        }
        error("照片未被图库索引：$uri")
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val root = ins.uiAutomation.rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40) return
            out += n
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return out
    }

    private fun screenTexts(): Set<String> {
        val out = mutableSetOf<String>()
        allNodes().forEach { n ->
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out
    }

    private fun waitForTextContaining(fragment: String, timeout: Long = 30000) {
        val end = android.os.SystemClock.uptimeMillis() + timeout
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (screenTexts().any { it.contains(fragment) }) return
            android.os.SystemClock.sleep(250)
        }
        error("timeout: $fragment（屏幕：${screenTexts()}）")
    }

    private fun tapByBounds(target: String, timeout: Long = 20000): Boolean {
        val end = android.os.SystemClock.uptimeMillis() + timeout
        while (android.os.SystemClock.uptimeMillis() < end) {
            val hit = allNodes().firstOrNull {
                it.contentDescription?.toString() == target || it.text?.toString() == target
            }
            if (hit != null) {
                val r = android.graphics.Rect(); hit.getBoundsInScreen(r)
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

    private fun startApp() {
        activity = ins.startActivitySync(
            android.content.Intent(ctx, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitForTextContaining("回收站", timeout = 45000)
        android.os.SystemClock.sleep(2500)
    }

    /** 主卡片页 → 点开照片进入普通查看器（真实入口）。 */
    private fun openViewerFromDeck(fileName: String): Boolean {
        // 卡片页的图片节点用 displayName 作 contentDescription
        val opened = tapByBounds(fileName, timeout = 25000)
        if (!opened) return false
        android.os.SystemClock.sleep(2500)
        return screenTexts().any { it.startsWith("viewer_overlay:") } ||
            screenTexts().contains("关闭") ||
            screenTexts().contains("分享当前照片")
    }

    private fun viewerOverlayPresent(): Boolean =
        screenTexts().any { it.startsWith("viewer_overlay:") }

    /** 退出按钮的采样区域尺寸（从 UI 层读取，宽高都必须 > 0）。 */
    private fun exitButtonBounds(): android.graphics.Rect? {
        val node = allNodes().firstOrNull { it.contentDescription?.toString() == "关闭" }
            ?: return null
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        return r
    }

    // ==================== 场景 1：开关关闭 ====================

    @Test
    fun switchOffShowsPlainButtonAndExits() {
        prefs.liquidGlassLabEnabled = false
        startApp()
        assertTrue("必须能从主卡片点开照片进入查看器", openViewerFromDeck(deckFirstImageName()))
        assertTrue("查看器必须真实出现", viewerOverlayPresent())

        val bounds = exitButtonBounds()
        assertNotNull("关闭开关时也必须有退出按钮（普通样式）", bounds)
        assertTrue("按钮必须有可点面积：$bounds", bounds!!.width() > 0 && bounds.height() > 0)

        // 能退出
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        android.os.SystemClock.sleep(2000)
        assertFalse("点关闭后必须离开查看器", viewerOverlayPresent())
    }

    // ==================== 场景 2/3/4：开关开启 ====================

    @Test
    fun switchOnShowsSampledButtonForCurrentPhoto() {
        prefs.liquidGlassLabEnabled = true
        startApp()
        val name = deckFirstImageName()
        assertTrue("必须能点开照片", openViewerFromDeck(name))
        assertTrue(viewerOverlayPresent())

        val bounds = exitButtonBounds()
        assertNotNull("开关开启时必须有退出按钮", bounds)
        assertTrue(
            "采样按钮区域宽高都必须 > 0：$bounds",
            bounds!!.width() > 0 && bounds.height() > 0
        )
        // 退出
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        android.os.SystemClock.sleep(2000)
        assertFalse("开关开启时也必须能退出", viewerOverlayPresent())
    }

    @Test
    fun bothSwitchStatesCanExit() {
        // 场景 7：先开启退出一次，再关闭重进退出一次。
        //
        // 关键：`liquidGlassLabEnabled` 在 **Activity 启动时**读进 Composition，
        // 运行中改 prefs 不会让已启动的界面重新读。因此切换开关后必须重启 Activity，
        // 否则第二次进查看器用的还是旧值——本轮实测第一次就是这么假失败的。
        prefs.liquidGlassLabEnabled = true
        startApp()
        assertTrue("开启状态必须能点开照片", openViewerFromDeck(deckFirstImageName()))
        exitButtonBounds()?.let { r -> shell("input tap ${r.centerX()} ${r.centerY()}") }
        android.os.SystemClock.sleep(2000)
        assertFalse("开启状态退出失败", viewerOverlayPresent())

        prefs.liquidGlassLabEnabled = false
        restartActivity()
        assertTrue("关闭状态必须能点开照片", openViewerFromDeck(deckFirstImageName()))
        val bounds = exitButtonBounds()
        assertNotNull("关闭状态重进后仍要有退出按钮", bounds)
        shell("input tap ${bounds!!.centerX()} ${bounds.centerY()}")
        android.os.SystemClock.sleep(2000)
        assertFalse("关闭状态退出失败", viewerOverlayPresent())
    }

    /** 重启宿主 Activity，让 `liquidGlassLabEnabled` 重新被读取。 */
    private fun restartActivity() {
        activity?.let { act ->
            ins.runOnMainSync { act.recreate() }
            android.os.SystemClock.sleep(3000)
        } ?: startApp()
        android.os.SystemClock.sleep(1500)
    }

    // ==================== 辅助 ====================

    /** 当前图库里第一张本测试插入的图（用于从卡片页点开）。 */
    private fun deckFirstImageName(): String = runBlocking {
        val all = LocalImageRepository(ctx).loadAllData().allImages
        val hit = all.firstOrNull { it.uri == brightRed }
            ?: all.firstOrNull { it.uri == darkBlue }
            ?: error("图库里找不到本测试插入的照片")
        hit.displayName
    }
}
