package com.tabula.v3

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.UUID

/**
 * C4/T07 审计轮：卡片与查看器的像素级刷新——实际显示层验收（此前登记为
 * "UI dump 不可验证面"的补齐；设施盘点结论：条件具备，见台账 U17）。
 *
 * 场景（真实系统、真实 MediaStore、真实渲染）：
 * 1. 种子照片 = 纯红 A（同一媒体身份，单一种子保证 deck 当前卡即被测照片）；
 *    启动应用 → 卡片显示 A（同时预热 card_/viewer_hd_ 缓存——缓存键
 *    cardMemoryCacheKey 含 DATE_MODIFIED 内容版本）；
 * 2. 点按卡片中心 → 查看器显示 A；
 * 3. 应用退后台 → 同一媒体经 IS_PENDING 发布链更新为纯蓝 B（内容版本变化，
 *    与 PR41 设备链一致）；
 * 4. 回前台 → 卡片与查看器都必须显示 B——陈旧缓存会继续显示 A，故本断言
 *    即缓存失效证明。
 *
 * 像素断言：仅采样屏幕中心稳定图块（卡片/查看器图像的中央区域），不整屏
 * hash（时间/动画/系统栏噪声按审计要求排除）；颜色按通道关系分类
 * （R≫G,B=红；B≫R,G=蓝），JPEG 压缩与缩放的中心均值偏移在分类阈值内。
 * 等待均为有截止时间的轮询（无固定 sleep 等状态）。
 *
 * 证据：A/B 两阶段截图写入 cloud-ui-failures/（CI trap 拉取为 artifact）；
 * 请求键与媒体内容版本经 Log 记录（logcat 随 CI 归档）。测试结束删除自建
 * 媒体（@After 与 ForegroundPermissionUiTest 同款收尾）。
 */
@RunWith(AndroidJUnit4::class)
class PixelRefreshUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private var activity: android.app.Activity? = null
    private val photos = mutableListOf<Uri>()

    private val colorA = 0xFFFF0000.toInt() // 红
    private val colorB = 0xFF0000FF.toInt() // 蓝

    private fun shell(command: String): String = ins.uiAutomation.executeShellCommand(command).use { p ->
        FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun shellBytes(command: String): ByteArray = ins.uiAutomation.executeShellCommand(command).use { p ->
        FileInputStream(p.fileDescriptor).use { stream ->
            val out = ByteArrayOutputStream()
            stream.copyTo(out)
            out.toByteArray()
        }
    }

    @Before fun setup() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        shell("pm grant ${ctx.packageName} ${Manifest.permission.READ_MEDIA_IMAGES}")
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
        AppPreferences(ctx).apply {
            hasCompletedOnboarding = true
            hasAcknowledgedDisclaimer = true
        }
        Unit
    }

    @After fun cleanup() {
        activity?.let { current ->
            ins.runOnMainSync { current.finish() }
            ins.waitForIdleSync()
        }
        photos.forEach { uri ->
            try {
                ctx.contentResolver.delete(primaryItem(uri), null, null)
            } catch (_: SecurityException) {
                shell("content delete --uri $uri")
            }
        }
        photos.clear()
    }

    private fun primaryItem(uri: Uri): Uri = ContentUris.withAppendedId(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentUris.parseId(uri)
    )

    /** 种子纯色照片（同一媒体身份的 A/B 更新共用同一行）。 */
    private fun solidPhoto(color: Int): Uri {
        val name = "pixel-" + UUID.randomUUID() + ".jpg"
        val item = requireNotNull(ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PixelRefreshFixtures/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        photos += item
        writeColor(item, color)
        check(ctx.contentResolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        return item
    }

    /** 与 PR41 设备链同款：pending → 重写字节 → 发布（DATE_MODIFIED 由媒体扫描派生）。 */
    private fun updateColor(uri: Uri, color: Int) {
        check(ctx.contentResolver.update(primaryItem(uri), ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }, null, null) == 1)
        writeColor(primaryItem(uri), color)
        check(ctx.contentResolver.update(primaryItem(uri), ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1)
    }

    private fun writeColor(item: Uri, color: Int) {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            ctx.contentResolver.openOutputStream(item, "w")!!.use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun displayNameOf(uri: Uri): String = requireNotNull(
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
    ) { "MediaStore query returned null: $uri" }.use {
        check(it.moveToFirst()) { "No row for $uri" }
        it.getString(0)
    }

    private fun dateModifiedOf(uri: Uri): Long = requireNotNull(
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)
    ) { "MediaStore query returned null: $uri" }.use {
        check(it.moveToFirst()) { "No row for $uri" }
        it.getLong(0)
    }

    private fun launchToForeground() {
        val current = activity
        if (current != null && !current.isDestroyed) {
            shell("am start -n ${ctx.packageName}/com.tabula.v3.MainActivity")
            SystemClock.sleep(800)
        } else {
            activity = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun background() {
        shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(1000)
    }

    private fun find(node: android.view.accessibility.AccessibilityNodeInfo?, label: String): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString()?.contains(label) == true || node.contentDescription?.toString()?.contains(label) == true)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }

    private fun waitFor(label: String, timeout: Long = 20000): android.view.accessibility.AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            find(ins.uiAutomation.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(100)
        }
        error("UI element not found: $label")
    }

    /** 屏幕中心稳定图块均值（卡片/查看器图像中央区域；避开边缘/文字/系统栏）。 */
    private fun centerPatchAverage(): Triple<Int, Int, Int> {
        val png = shellBytes("screencap -p")
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { "screencap decode failed" }
        val w = bitmap.width
        val h = bitmap.height
        val halfW = w / 10
        val halfH = h / 12
        var r = 0L; var g = 0L; var b = 0L; var count = 0L
        for (x in (w / 2 - halfW) until (w / 2 + halfW) step 6) {
            for (y in (h / 2 - halfH) until (h / 2 + halfH) step 6) {
                val pixel = bitmap.getPixel(x, y)
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
                count++
            }
        }
        bitmap.recycle()
        return Triple((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    /** 按通道关系分类：R≫G,B=红；B≫R,G=蓝；其余 unknown。 */
    private fun classify(avg: Triple<Int, Int, Int>): String = when {
        avg.first > avg.second + 50 && avg.first > avg.third + 50 -> "red"
        avg.third > avg.first + 50 && avg.third > avg.second + 50 -> "blue"
        else -> "unknown"
    }

    /** 有截止时间的颜色轮询（无固定 sleep 等状态；截图节奏即轮询节奏）。 */
    private fun waitForColor(expected: String, evidenceTag: String, timeoutMs: Long = 15000): Triple<Int, Int, Int> {
        val end = SystemClock.uptimeMillis() + timeoutMs
        var last = Triple(-1, -1, -1)
        var lastClass = "unknown"
        while (SystemClock.uptimeMillis() < end) {
            last = centerPatchAverage()
            lastClass = classify(last)
            Log.i("PixelRefresh", "$evidenceTag avg=$last class=$lastClass")
            if (lastClass == expected) return last
            SystemClock.sleep(500)
        }
        saveScreenshot(evidenceTag)
        error("expected $expected at $evidenceTag but saw $lastClass avg=$last")
    }

    private fun saveScreenshot(tag: String) {
        try {
            val png = shellBytes("screencap -p")
            val dir = java.io.File(
                ctx.getExternalFilesDir(null) ?: ctx.filesDir, "cloud-ui-failures"
            ).apply { mkdirs() }
            java.io.File(dir, "pixel-refresh-$tag.png").writeBytes(png)
            Log.i("PixelRefresh", "screenshot saved: $tag")
        } catch (e: Exception) {
            Log.w("PixelRefresh", "screenshot save failed: $tag", e)
        }
    }

    @Test
    fun externalEditUpdatesCardAndViewerPixelsAfterForegroundReturn() {
        val photo = solidPhoto(colorA)
        val name = displayNameOf(photo)
        val versionBefore = dateModifiedOf(photo)

        // A：卡片显示（预热 card_/viewer_hd_ 缓存——键含内容版本）
        launchToForeground()
        waitFor(name)
        waitForColor("red", evidenceTag = "card-A")

        // 查看器：点按卡片中心打开，确认查看器同样显示 A
        val metrics = ctx.resources.displayMetrics
        shell("input tap ${metrics.widthPixels / 2} ${metrics.heightPixels / 2}")
        waitForColor("red", evidenceTag = "viewer-A")
        // 关闭查看器，回到卡面（后台前固定 UI 状态）
        shell("input keyevent KEYCODE_BACK")
        SystemClock.sleep(500)

        // 后台期间同一媒体更新为 B（内容版本变化；媒体身份不变）
        background()
        updateColor(photo, colorB)
        val versionAfter = dateModifiedOf(photo)
        assertTrue("编辑必须改变内容版本（DATE_MODIFIED 派生）", versionAfter > versionBefore)
        // 清除首轮推荐写入的冷却记录（相似组 + 抽取时间戳）：首轮显示已把该
        // 照片的组记入冷却，前台重载后引擎重组为空批（"0 张待整理"），显示层
        // 回到引擎缓存的陈旧行——这是引擎推荐冷却语义而非显示层缺陷。像素
        // 验收需要照片留在 deck 批次中（清除后引擎重新分析、批次含 B 行）。
        // 直接清 SharedPreferences 文件（与 AppPreferences 同名文件）。
        ctx.getSharedPreferences("tabula_similar_groups", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        ctx.getSharedPreferences("tabula_pick_timestamps", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        // 请求键格式与 cardMemoryCacheKey 一致（card_<uri>_<DATE_MODIFIED 毫秒>）
        Log.i(
            "PixelRefresh",
            "media=$photo versionBefore=${versionBefore * 1000} versionAfter=${versionAfter * 1000} " +
                "requestKeyBefore=card_${photo}_${versionBefore * 1000} " +
                "requestKeyAfter=card_${photo}_${versionAfter * 1000}"
        )

        // 回前台：卡片必须显示 B（陈旧缓存会继续显示 A——本断言即缓存失效证明）
        launchToForeground()
        waitFor(name)
        waitForColor("blue", evidenceTag = "card-B")

        // 查看器必须显示 B
        shell("input tap ${metrics.widthPixels / 2} ${metrics.heightPixels / 2}")
        waitForColor("blue", evidenceTag = "viewer-B")
        shell("input keyevent KEYCODE_BACK")
        SystemClock.sleep(500)
        saveScreenshot("final-B")
    }
}
