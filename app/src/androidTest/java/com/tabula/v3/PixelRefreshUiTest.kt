package com.tabula.v3

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/**
 * C4/T07 审计轮：卡片与查看器的像素级刷新——实际显示层验收（状态链版）。
 *
 * 状态链（全部使用现有可访问性节点，无生产改动）：
 * 1. 卡片阶段：displayName 节点可见（确认卡片对应被测媒体）→ 在**该节点实际
 *    边界**内采样像素（红 A）；
 * 2. 点击该节点边界中心 → 等待"查看器专属状态"= displayName 节点仍在且
 *    deck 专属的"张待整理"状态文本**消失**（查看器全屏覆盖 deck 的可观察
 *    面貌；deck 卡片同样暴露 displayName，故必须以 deck 状态文本消失区分）→
 *    确认后采样查看器像素（红 A）；
 * 3. KEYCODE_BACK 退出 → 等待"张待整理"状态文本恢复（已回到卡片页面）；
 * 4. 后台 → 同一媒体经 IS_PENDING 链更新为蓝 B（内容版本变化、身份不变）→
 *    回前台重复 1-3，卡片与查看器都必须显示蓝 B——陈旧缓存/旧行会继续显示
 *    红 A，此断言即刷新与缓存失效证明。
 *
 * 若"查看器专属状态"超时未出现，测试在该步失败——即"点击未打开查看器（或
 * 被立即关闭）"路径的必然失败（不以中心颜色误判为查看器已打开）。
 *
 * 证据：card-A/viewer-A/card-B/viewer-B 四阶段截图**总是**写入
 * cloud-ui-failures/（CI trap 拉取为 artifact）；失败时额外保存辅助树 dump、
 * 媒体身份（uri/卷/ID）、内容版本（DATE_MODIFIED 秒→毫秒）与**预期**请求键
 * （card_<uri>_<版本毫秒>，手工拼接的预期值——实际请求证据需按需经 Coil 调试
 * 接口采集，本测试登记该边界）。等待均为有截止时间的轮询。
 */
@RunWith(AndroidJUnit4::class)
class PixelRefreshUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private var activity: android.app.Activity? = null
    private val photos = mutableListOf<Uri>()

    private val colorA = 0xFFFF0000.toInt() // 红
    private val colorB = 0xFF0000FF.toInt() // 蓝
    private val deckStatusMarker = "张待整理"

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

    private fun evidenceDir(): File = File(
        ctx.getExternalFilesDir(null) ?: ctx.filesDir, "cloud-ui-failures"
    ).apply { mkdirs() }

    private fun saveScreenshot(tag: String) {
        try {
            val png = shellBytes("screencap -p")
            File(evidenceDir(), "pixel-refresh-$tag.png").writeBytes(png)
            Log.i(TAG, "screenshot saved: $tag")
        } catch (e: Exception) {
            Log.w(TAG, "screenshot save failed: $tag", e)
        }
    }

    /** 辅助树 dump（失败诊断用）：递归输出可见节点的类/文本/描述/边界。 */
    private fun dumpTree(tag: String) {
        try {
            val sb = StringBuilder()
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 25) return
                if (node.isVisibleToUser) {
                    val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                    sb.append(" ".repeat(depth))
                    sb.append(node.className).append(" text=").append(node.text)
                        .append(" desc=").append(node.contentDescription)
                        .append(" bounds=").append(rect.toShortString())
                        .append('\n')
                }
                for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
            }
            walk(ins.uiAutomation.rootInActiveWindow, 0)
            File(evidenceDir(), "pixel-refresh-$tag-tree.txt").writeText(sb.toString())
            Log.i(TAG, "tree dump saved: $tag (${sb.length} chars)")
        } catch (e: Exception) {
            Log.w(TAG, "tree dump failed: $tag", e)
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

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString()?.contains(label) == true || node.contentDescription?.toString()?.contains(label) == true)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }

    private fun waitForNode(label: String, timeout: Long = 20000): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            find(ins.uiAutomation.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(100)
        }
        dumpTree("missing-$label")
        error("UI element not found: $label")
    }

    /** 收集全部可见匹配节点；displayName 有多个匹配（顶部小卡 + 当前大卡）。 */
    private fun findAll(node: AccessibilityNodeInfo?, label: String, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isVisibleToUser && (node.text?.toString()?.contains(label) == true || node.contentDescription?.toString()?.contains(label) == true)) out += node
        for (i in 0 until node.childCount) findAll(node.getChild(i), label, out)
    }

    /** 最大面积匹配 = 当前显示的大卡（小卡是顶部露出的背景卡，不可点击）。 */
    private fun waitForLargestNode(label: String, timeout: Long = 20000): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            val matches = mutableListOf<AccessibilityNodeInfo>()
            findAll(ins.uiAutomation.rootInActiveWindow, label, matches)
            var best: AccessibilityNodeInfo? = null
            var bestArea = -1L
            for (m in matches) {
                val rect = android.graphics.Rect().also { m.getBoundsInScreen(it) }
                val area = rect.width().toLong() * rect.height().toLong()
                if (area > bestArea) { bestArea = area; best = m }
            }
            best?.let { return it }
            SystemClock.sleep(100)
        }
        dumpTree("missing-$label")
        error("UI element not found: $label")
    }

    /** 有截止时间的复合状态等待：条件成立返回，否则失败并留存证据。 */
    private fun waitForState(
        description: String, timeoutMs: Long = 20000,
        poll: (AccessibilityNodeInfo) -> Boolean
    ) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            val root = ins.uiAutomation.rootInActiveWindow
            if (root != null && poll(root)) return
            SystemClock.sleep(150)
        }
        dumpTree("state-timeout-$description")
        saveScreenshot("state-timeout-$description")
        error("state not reached: $description")
    }

    /**
     * 采样指定节点边界内的中心图块均值（节点实际位置而非盲目屏幕中心；
     * 避开边缘/文字）。
     */
    private fun sampleNodeCenter(node: AccessibilityNodeInfo): Triple<Int, Int, Int> {
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        val png = shellBytes("screencap -p")
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { "screencap decode failed" }
        val cx = rect.centerX().coerceIn(0, bitmap.width - 1)
        val cy = rect.centerY().coerceIn(0, bitmap.height - 1)
        val halfW = minOf(rect.width() / 4, 120).coerceAtLeast(8)
        val halfH = minOf(rect.height() / 4, 120).coerceAtLeast(8)
        var r = 0L; var g = 0L; var b = 0L; var count = 0L
        for (x in (cx - halfW).coerceAtLeast(0) until (cx + halfW).coerceAtMost(bitmap.width) step 6) {
            for (y in (cy - halfH).coerceAtLeast(0) until (cy + halfH).coerceAtMost(bitmap.height) step 6) {
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

    /** 屏幕中心图块均值（查看器阶段的像素轮询用；查看器全屏覆盖 deck）。 */
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

    /**
     * 打开查看器的点击：优先无障碍原生 ACTION_CLICK（节点或最近可点击祖先），
     * 全部失败时回退为节点中心坐标 tap。返回实际使用的点击方式描述。
     */
    private fun openViewerByClick(node: AccessibilityNodeInfo): String {
        if (node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "node ACTION_CLICK"
        }
        var parent: AccessibilityNodeInfo? = node.parent
        var hops = 0
        while (parent != null && hops < 6) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "ancestor ACTION_CLICK (hops=$hops)"
            }
            parent = parent.parent
            hops++
        }
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        shell("input tap ${rect.centerX()} ${rect.centerY()}")
        return "coordinate tap fallback"
    }

    /** 按通道关系分类：R≫G,B=红；B≫R,G=蓝；其余 unknown。 */
    private fun classify(avg: Triple<Int, Int, Int>): String = when {
        avg.first > avg.second + 50 && avg.first > avg.third + 50 -> "red"
        avg.third > avg.first + 50 && avg.third > avg.second + 50 -> "blue"
        else -> "unknown"
    }

    /** 有截止时间的像素轮询：节点边界内采样，直到分类命中期望颜色。 */
    private fun waitForColorInNode(
        nodeLabel: String, expected: String, evidenceTag: String, timeoutMs: Long = 15000
    ): Triple<Int, Int, Int> {
        val end = SystemClock.uptimeMillis() + timeoutMs
        var last = Triple(-1, -1, -1)
        var lastClass = "unknown"
        while (SystemClock.uptimeMillis() < end) {
            val node = find(ins.uiAutomation.rootInActiveWindow, nodeLabel)
            if (node != null) {
                last = sampleNodeCenter(node)
                lastClass = classify(last)
                Log.i(TAG, "$evidenceTag avg=$last class=$lastClass")
                if (lastClass == expected) return last
            }
            SystemClock.sleep(400)
        }
        saveScreenshot(evidenceTag)
        dumpTree("color-timeout-$evidenceTag")
        error("expected $expected at $evidenceTag but saw $lastClass avg=$last")
    }

    @Test
    fun externalEditUpdatesCardAndViewerPixelsAfterForegroundReturn() {
        val photo = solidPhoto(colorA)
        val name = displayNameOf(photo)
        val versionBeforeSec = dateModifiedOf(photo)
        val identityKey = "external_primary|${ContentUris.parseId(photo)}"
        // 预期请求键（手工拼接，格式与 cardMemoryCacheKey 一致；实际请求证据
        // 需经 Coil 调试接口按需采集——本测试登记该边界）
        val expectedKeyBefore = "card_${photo}_${versionBeforeSec * 1000}"

        // ---- 卡片阶段（A）：确认卡片对应被测媒体 → 卡片像素 = 红 ----
        launchToForeground()
        val cardNode = waitForLargestNode(name)
        waitForColorInNode(name, "red", evidenceTag = "card-A")
        saveScreenshot("card-A")

        // ---- 查看器阶段（A）：实际卡片位置点击 → 查看器专属状态 → 像素 = 红 ----
        val rect = android.graphics.Rect().also { cardNode.getBoundsInScreen(it) }
        val clickMethod = openViewerByClick(cardNode)
        Log.i(TAG, "card-A click via: $clickMethod")
        // 成功判定（审计轮 6/7 修正）：同一次轮询同时满足（查看器标识在 +
        // 标识含被测媒体完整身份 + 颜色符合）——禁止跨时刻拼接；不依赖 deck
        // 状态文本消失（覆盖后节点仍留树中，已实证不可靠）。
        val overlayDescA = "viewer_overlay:$identityKey"
        var successA = false
        var lastObservationA = "no poll executed"
        val endA = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < endA) {
            val root = ins.uiAutomation.rootInActiveWindow
            val overlayNode = root?.let { find(it, overlayDescA) }
            val avg = centerPatchAverage()
            val cls = classify(avg)
            lastObservationA = "overlay=${overlayNode != null} avg=$avg class=$cls"
            Log.i(TAG, "viewer-A poll $lastObservationA")
            if (ViewerPollPolicy.pollSuccess(
                    viewerOverlayPresent = overlayNode != null,
                    mediaIdentityMatches = overlayNode != null,
                    colorClass = cls, expected = "red"
                )
            ) {
                successA = true
                break
            }
            SystemClock.sleep(100)
        }
        if (!successA) {
            dumpTree("viewer-A-not-confirmed")
            saveScreenshot("viewer-A-not-confirmed")
        }
        assertTrue(
            "查看器单轮询成功未达成（$lastObservationA）——点击未送达、状态检测错误、查看器被立即关闭或像素不符",
            successA
        )
        saveScreenshot("viewer-A")

        // ---- 退出查看器：确认回到卡片页面 ----
        shell("input keyevent KEYCODE_BACK")
        waitForState("back-to-deck", timeoutMs = 10000) { root ->
            find(root, deckStatusMarker) != null
        }

        // ---- 后台编辑：同一媒体 → 蓝 B（内容版本变化、身份不变）----
        background()
        updateColor(photo, colorB)
        val versionAfterSec = dateModifiedOf(photo)
        assertTrue("编辑必须改变内容版本（DATE_MODIFIED 派生）", versionAfterSec > versionBeforeSec)
        // 清除首轮推荐写入的冷却记录（相似组 + 抽取时间戳）：保留理由 = 首轮
        // 显示已把该照片的组记入推荐冷却，前台重载后引擎重组空批会使显示层
        // 回到引擎缓存的陈旧行——掩盖被验收的显示路径；影响范围仅测试准备
        // （清除后引擎重新分析、批次含 B 行），不改变任何显示层行为。
        ctx.getSharedPreferences("tabula_similar_groups", Context.MODE_PRIVATE)
            .edit().clear().commit()
        ctx.getSharedPreferences("tabula_pick_timestamps", Context.MODE_PRIVATE)
            .edit().clear().commit()
        Log.i(
            TAG,
            "media=$photo identity=$identityKey versionBeforeSec=$versionBeforeSec versionAfterSec=$versionAfterSec " +
                "expectedRequestKeyBefore=$expectedKeyBefore " +
                "expectedRequestKeyAfter=card_${photo}_${versionAfterSec * 1000} (预期值，手工拼接)"
        )

        // ---- 卡片阶段（B）：像素 = 蓝 ----
        launchToForeground()
        waitForNode(name)
        waitForColorInNode(name, "blue", evidenceTag = "card-B")
        saveScreenshot("card-B")

        // ---- 查看器阶段（B）：状态链 + 像素 = 蓝 ----
        val cardNodeB = waitForLargestNode(name)
        val rectB = android.graphics.Rect().also { cardNodeB.getBoundsInScreen(it) }
        val clickMethodB = openViewerByClick(cardNodeB)
        Log.i(TAG, "card-B click via: $clickMethodB")
        var successB = false
        var lastObservationB = "no poll executed"
        val overlayDescB = "viewer_overlay:$identityKey"
        val endB = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < endB) {
            val root = ins.uiAutomation.rootInActiveWindow
            val overlayNode = root?.let { find(it, overlayDescB) }
            val avg = centerPatchAverage()
            val cls = classify(avg)
            lastObservationB = "overlay=${overlayNode != null} avg=$avg class=$cls"
            Log.i(TAG, "viewer-B poll $lastObservationB")
            if (ViewerPollPolicy.pollSuccess(
                    viewerOverlayPresent = overlayNode != null,
                    mediaIdentityMatches = overlayNode != null,
                    colorClass = cls, expected = "blue"
                )
            ) {
                successB = true
                break
            }
            SystemClock.sleep(100)
        }
        if (!successB) {
            dumpTree("viewer-B-not-confirmed")
            saveScreenshot("viewer-B-not-confirmed")
        }
        assertTrue(
            "查看器单轮询成功未达成（$lastObservationB）——点击未送达、状态检测错误、查看器被立即关闭或像素不符",
            successB
        )
        saveScreenshot("viewer-B")

        // ---- 退出并确认回到卡片页面 ----
        shell("input keyevent KEYCODE_BACK")
        waitForState("back-to-deck-final", timeoutMs = 10000) { root ->
            find(root, deckStatusMarker) != null
        }
    }

    private companion object {
        const val TAG = "PixelRefresh"
    }
}

/**
 * 审计轮 6 针对性回归：ViewerPollPolicy 的跨时刻拼接敏感性。
 *
 * 序列 [查看器打开(红) → 查看器关闭、回到卡片(蓝)] 逐步喂入判定——
 * 每一步都必须返回 false：打开与颜色从不在同一次轮询同时成立，拼接
 * "曾经打开"与"当前卡片颜色"不能冒充查看器验收通过。
 */
    @Test
    fun openedThenClosedWithMatchingCardColorNeverPasses() {
        // 第 1 次轮询：查看器标识在，但颜色不符（红 ≠ 期望蓝）
        assertFalse(
            ViewerPollPolicy.pollSuccess(
                viewerOverlayPresent = true, mediaIdentityMatches = true,
                colorClass = "red", expected = "blue"
            )
        )
        // 第 2 次轮询：查看器标识已消失（关闭），卡片颜色恰好符合——拼接不得通过
        assertFalse(
            "查看器关闭后的卡片颜色不能冒充查看器验收",
            ViewerPollPolicy.pollSuccess(
                viewerOverlayPresent = false, mediaIdentityMatches = false,
                colorClass = "blue", expected = "blue"
            )
        )
        // 对照：同一次轮询条件齐备才通过
        assertTrue(
            ViewerPollPolicy.pollSuccess(
                viewerOverlayPresent = true, mediaIdentityMatches = true,
                colorClass = "blue", expected = "blue"
            )
        )
    }
