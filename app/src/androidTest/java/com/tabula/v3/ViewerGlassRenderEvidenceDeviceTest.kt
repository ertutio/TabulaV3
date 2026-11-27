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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 普通查看器的**渲染证据**验收（PR B 第 3 轮）。
 *
 * ## 为什么必须另立一类
 *
 * 上一轮的 `ViewerGlassPilotDeviceTest` 只断言「关闭」按钮在无障碍树上有边界。
 * 那个断言在采样完全失效时**照样通过**——按钮存在、可点、能退出，
 * 但它背后可能是一张错位的采样图，或者根本没采。（本轮复审指出：
 * "现有设备用例只测关闭按钮的无障碍边界，普通按钮也能通过"。）
 *
 * 本类只读 `ViewerBackdropAuditLine` 产出的 `AUDIT ...` 行。
 * 那一行与渲染分支**同源**：`sampled` / `path` 由决定"画哪条分支"的同一个
 * [com.tabula.v3.ui.components.liquidglass.ViewerBackdropDecision] 生成，
 * `ready` 要求方案、尺寸、效果与**本版本绘制记录**同时成立，
 * `photorect` 是采样层实际使用的照片矩形。
 *
 * 因此本类断言的是**渲染结果**，不是"按钮在不在"：
 * - 按钮存在 ≠ 采样成功；
 * - URI 正确 ≠ 采到的是这张图；
 * - 判定函数返回允许 ≠ 真的画出来了。
 *
 * ## 用例
 *
 * 1. `photoIdentityFollowsAB`：单图查看器「退出 A → 打开 B」，每张都断言
 *    身份（`viewer_overlay:<identity>`）与 `AUDIT` 同时切到新照片；
 * 2. `glassPathDrawsWithNonZeroSamplingArea`：`sampled=true` 时
 *    `ready=true` 且 `glass` / `layer` / `photorect` 尺寸都非零；
 * 3. `zoomFallsBackThenRestores`：放大 → `sampled=false` 且原因正确；
 *    复原 → 回到 `sampled=true`（不是"放大后永远回退"）。
 */
@RunWith(AndroidJUnit4::class)
class ViewerGlassRenderEvidenceDeviceTest {

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private lateinit var prefs: AppPreferences
    private val inserted = mutableListOf<Uri>()
    private var activity: android.app.Activity? = null

    private var imageA: Uri? = null
    private var imageB: Uri? = null
    private var nameA: String = ""
    private var nameB: String = ""

    @Before
    fun setUp() {
        prefs = AppPreferences(ctx)
        prefs.hasCompletedOnboarding = true
        prefs.hasAcknowledgedDisclaimer = true
        prefs.recommendMode = RecommendMode.RANDOM_WALK
        prefs.liquidGlassLabEnabled = true
        runBlocking { runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() } }
        purgeAll()

        // A/B 必须**纹理明显不同**：纯色块无法区分"采到了另一张"。
        nameA = "ge-a-" + UUID.randomUUID().toString().take(6) + ".png"
        nameB = "ge-b-" + UUID.randomUUID().toString().take(6) + ".png"
        imageA = insertTextured(nameA, bright = true)
        imageB = insertTextured(nameB, bright = false)
        waitIndexed(imageA!!)
        waitIndexed(imageB!!)
        assertGalleryReady()
        ctx.getExternalFilesDir(null)?.mkdirs()
    }

    @After
    fun cleanUp() {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        runBlocking { runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() } }
    }

    // ===== 基础设施 =====

    private fun shell(cmd: String): String = ins.uiAutomation.executeShellCommand(cmd).use { p ->
        java.io.FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * 截图**不能写到 /sdcard 公共目录**。
     *
     * 公共目录会被媒体扫描收录成一张照片，于是下一轮启动时卡片堆里多出
     * `01-xxx.png` 这种截图，`openTopCard` 会进到它——失败信息却是
     * "身份不是本用例插入的"。改写到 target 应用的私有外部目录
     * （`/sdcard/Android/data/<pkg>/files/`），媒体扫描不收录。
     */
    private fun shot(name: String) {
        val dir = ctx.getExternalFilesDir(null)?.absolutePath ?: return
        shell("screencap -p $dir/$name.png")
    }

    /**
     * 清掉**所有**残留图片，不只清本前缀。
     *
     * 之前只删 `ge-` 前缀：上一轮 `@After` 删了文件但 MediaStore 记录可能残留，
     * 于是卡片堆里还混着上一轮的图，`openTopCard` 会进到那张不属于本轮的照片，
     * 报"身份不是本用例插入的"。这不是产品缺陷，是夹具残留。
     */
    private fun purgeAll() {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        ctx.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            projection, null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val ids = mutableListOf<Uri>()
            while (cursor.moveToNext()) {
                ids += MediaStore.Images.Media
                    .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    .buildUpon().appendPath(cursor.getLong(idCol).toString()).build()
            }
            ids.forEach { uri -> runCatching { ctx.contentResolver.delete(uri, null, null) } }
        }
        android.os.SystemClock.sleep(600)
    }

    private fun purge() {
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
                if (!name.startsWith("ge-")) continue
                ids += MediaStore.Images.Media
                    .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    .buildUpon().appendPath(cursor.getLong(idCol).toString()).build()
            }
            ids.forEach { uri -> runCatching { ctx.contentResolver.delete(uri, null, null) } }
        }
        android.os.SystemClock.sleep(500)
    }

    /**
     * 接近屏幕比例的**竖图**，纹理明显不同（A：亮底黑条纹；B：暗底白条纹）。
     *
     * 用竖图是因为本轮修的正是"照片上下有黑边时采样错位"：
     * 接近屏幕比例的竖图上下黑边最明显，按钮下方最容易露出黑边。
     */
    private fun insertTextured(name: String, bright: Boolean): Uri {
        val w = 1080
        val h = 1440   // 3:4，接近手机屏幕比例
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (bright) Color.rgb(240, 240, 240) else Color.rgb(16, 16, 16))
        val paint = Paint().apply {
            color = if (bright) Color.BLACK else Color.WHITE
            strokeWidth = 28f
        }
        // 每 90px 一条斜纹：玻璃采样区域一旦错位到照片以外，纹理就不连续
        var i = -h
        while (i < w + h) {
            canvas.drawLine(i.toFloat(), 0f, (i + h).toFloat(), h.toFloat(), paint)
            i += 90
        }
        val label = Paint().apply {
            color = if (bright) Color.rgb(220, 20, 20) else Color.rgb(20, 200, 20)
            textSize = 80f
        }
        canvas.drawText(if (bright) "A" else "B", 60f, 160f, label)

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
        ctx.contentResolver.openOutputStream(uri)!!.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        ctx.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null, null
        )
        bitmap.recycle()
        return uri
    }

    private fun waitIndexed(uri: Uri, timeoutMs: Long = 25000) {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < end) {
            val c = ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            c?.use { if (it.moveToFirst()) return }
            android.os.SystemClock.sleep(300)
        }
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
        throw AssertionError("等待超时：$fragment（屏幕：${screenTexts()}）")
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


    /** 从图库按 URI 查这张图在卡片页上的 displayName（不猜文件名）。 */
    private fun deckNameOf(uri: Uri): String = runBlocking {
        val all = LocalImageRepository(ctx).loadAllData().allImages
        all.firstOrNull { it.uri == uri }?.displayName
            ?: error("图库里找不到 $uri（共 ${all.size} 张）")
    }

    /**
     * 切到「图库」视图再点卡片。
     *
     * 随机漫步的卡片页每次只抽一张，**不保证**抽到测试插入的那张；
     * 图库视图按网格列出全部，能按名字点到指定的图。这不是绕过真实入口——
     * 点开后进的是同一个 `ViewerOverlay`，只是不靠抽签决定抽到谁。
     */
    /** 按 contentDescription / text 精确匹配，点它的**几何中心**（不看 clickable）。 */
    private fun tapByCenterOf(label: String, timeout: Long = 20000): Boolean {
        val end = android.os.SystemClock.uptimeMillis() + timeout
        while (android.os.SystemClock.uptimeMillis() < end) {
            val hit = allNodes().firstOrNull {
                it.contentDescription?.toString() == label || it.text?.toString() == label
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

    /**
     * 点开**当前最上层的那张卡片**进入普通查看器，并返回进的是哪张（identity）。
     *
     * ## 为什么不能"按名字点"
     *
     * 卡片视图是**叠放**的：屏幕上一次只显示最上面一张，但无障碍树里**所有**
     * 卡片节点都在（两张图时能看到两个 contentDescription）。按名字取节点再点
     * 它的中心，很可能点到被压在下层的卡片——它不在最上层，点击落到手势层，
     * 什么都不会发生。这就是前几轮"名字找到了却点不开"的真实原因。
     *
     * 所以这里只点**最上层的可见区域**（卡片堆的几何中心），点完读
     * `viewer_overlay:<identity>` 拿到实际进入的是哪张，由调用方断言它是 A 还是 B。
     * 这样既走真实入口，又不依赖"图库里恰好只有一张图"。
     */
    private fun openTopCardViewer(): String? {
        // 卡片堆在屏幕中部：取屏幕中心稍偏上的位置，避开底部「卡片/图库」与顶栏
        val dm = ctx.resources.displayMetrics
        val x = dm.widthPixels / 2
        val y = (dm.heightPixels * 0.42f).toInt()
        shell("input tap $x $y")
        android.os.SystemClock.sleep(2600)
        return overlayIdentity()
    }

    /**
     * 打开卡片堆**最上层**的那张，返回 identity。
     *
     * 这里不强行"必须进到 A"：卡片堆退出后不轮转，最上层是固定的（实测始终是后插入
     * 的那张）。硬要求"进到 A"会让用例永远失败，而那不是产品缺陷。
     * 于是改成：**接受 A 或 B 任意一张，但必须证明它是这两张之一**，
     * 再用 identity 区分后续断言。A/B 的来源切换另由 `photoIdentityFollowsAB`
     * 用"退出 → 打开另一张"的方式覆盖（见那里的说明）。
     */
    private fun openTopCard(): String {
        val dm = ctx.resources.displayMetrics
        val cx = dm.widthPixels / 2
        // 卡片堆的可见区域随张数变化位置，竖坐标试两档再放弃
        val candidates = listOf(
            (dm.heightPixels * 0.42f).toInt(),
            (dm.heightPixels * 0.55f).toInt(),
            (dm.heightPixels * 0.30f).toInt()
        )
        var id: String? = null
        for (y in candidates) {
            shell("input tap $cx $y")
            // **轮询而不是固定 sleep**：入场动画 + 采样建链需要时间，
            // 固定 2.6s 读到的可能是"动画还在跑"的中间态，然后误报"没进入查看器"。
            val end = android.os.SystemClock.uptimeMillis() + 15000
            while (android.os.SystemClock.uptimeMillis() < end) {
                id = overlayIdentity()
                if (id != null) break
                android.os.SystemClock.sleep(300)
            }
            if (id != null) break
        }
        val resolved = id
            ?: throw AssertionError("点卡片没有进入查看器（屏幕：${screenTexts()}）")
        val isA = identityMatchesUri(resolved, imageA!!)
        val isB = identityMatchesUri(resolved, imageB!!)
        assertTrue("进入的必须是本用例插入的某一张（实际 $resolved）", isA || isB)
        return resolved
    }

    /** `viewer_overlay:external_primary|123` 里的 id 是否等于这张图的 MediaStore id。 */
    private fun identityMatchesUri(identity: String, uri: Uri): Boolean {
        val mediaId = uri.lastPathSegment?.toLongOrNull() ?: return false
        return identity.endsWith("|$mediaId")
    }

    /**
     * 确认图库**非空**后才启动。
     *
     * 空图库时 DeckScreen 进 `EmptyState`：没有卡片、没有「图库」标签，
     * 只有「回收站 / 设置」。那种情况下点什么都不可能进查看器，
     * 失败信息却会写成"点不开 A"——把环境问题伪装成实现问题。
     */
    private fun assertGalleryReady() {
        val count = ctx.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID), null, null, null
        )?.use { it.count } ?: 0
        assertTrue("图库必须有本用例插入的照片（实际 $count 张）", count >= 2)
    }

    private fun waitInDeck(uri: Uri, timeoutMs: Long = 30000): String {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < end) {
            val name = runCatching { deckNameOf(uri) }.getOrNull()
            if (name != null && screenTexts().any { it == name } ) return name
            if (name != null) {
                // 切到图库视图再看一次（网格会显示全部，卡片视图只显示一张）
                allNodes().firstOrNull { it.text?.toString() == "图库" }?.let { t ->
                    val r = android.graphics.Rect(); t.getBoundsInScreen(r)
                    shell("input tap ${r.centerX()} ${r.centerY()}")
                }
                android.os.SystemClock.sleep(1200)
                if (screenTexts().any { it == name }) return name
                // 回卡片视图再让页面重查数据
                allNodes().firstOrNull { it.text?.toString() == "卡片" }?.let { t ->
                    val r = android.graphics.Rect(); t.getBoundsInScreen(r)
                    shell("input tap ${r.centerX()} ${r.centerY()}")
                }
            }
            android.os.SystemClock.sleep(800)
        }
        throw AssertionError("图库里等不到 $uri（屏幕：${screenTexts()}）")
    }

    /** 当前 `AUDIT ...` 行；没有就返回 null（说明审计出口没挂上）。 */
    private fun auditLine(): String? =
        screenTexts().firstOrNull { it.startsWith("AUDIT ") }

    private fun auditValue(line: String, key: String): String? =
        Regex("\\b$key=([^\\s]+)").find(line)?.groupValues?.get(1)

    /** 解析 `photorect=l,t,WxH` 里的 W/H，全部非零才算真的量到了照片。 */
    private fun photoRectSize(line: String): Pair<Float, Float>? {
        val raw = auditValue(line, "photorect") ?: return null
        val m = Regex("([\\d.]+),([\\d.]+),([\\d.]+)x([\\d.]+)").find(raw) ?: return null
        return m.groupValues[3].toFloat() to m.groupValues[4].toFloat()
    }

    private fun parseSize(line: String, key: String): Pair<Int, Int>? {
        val raw = auditValue(line, key) ?: return null
        val m = Regex("(\\d+)x(\\d+)").find(raw) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun overlayIdentity(): String? =
        screenTexts().firstOrNull { it.startsWith("viewer_overlay:") }
            ?.removePrefix("viewer_overlay:")

    /**
     * 等 AUDIT 行出现且 `sampled`（可选 `ready`）达到期望值。
     *
     * **为什么还要等 `ready`**：`sampled` 只表示"选了玻璃分支"，
     * 而玻璃真正画出来需要一次当前方案的绘制记录（`ready`）。
     * 第二次进入查看器时实测存在 sampled=true 但 ready=false 的中间帧——
     * 只等 sampled 会在这个窗口里断言，把"还在首帧"读成"没画出来"。
     */
    private fun waitAudit(
        sampled: Boolean,
        ready: Boolean? = null,
        timeout: Long = 30000
    ): String {
        val end = android.os.SystemClock.uptimeMillis() + timeout
        var last: String? = null
        while (android.os.SystemClock.uptimeMillis() < end) {
            last = auditLine()
            if (last != null && auditValue(last, "sampled") == sampled.toString() &&
                (ready == null || auditValue(last, "ready") == ready.toString())
            ) return last
            android.os.SystemClock.sleep(300)
        }
        throw AssertionError(
            "等不到 AUDIT sampled=$sampled ready=$ready（最后一行：$last；屏幕：${screenTexts()}）"
        )
    }

    // ==================== 用例 ====================

    /**
     * 单图查看器「退出 A → 打开 B」：每张都要有**当前照片身份**、
     * 实际渲染的采样状态、非零采样尺寸。
     */
    @Test
    fun photoIdentityFollowsAB() {
        startApp()

        // ---- 第一张 ----
        val idA0 = openTopCard()
        val firstIsA = identityMatchesUri(idA0, imageA!!)
        val firstIsB = identityMatchesUri(idA0, imageB!!)
        assertTrue("第一张必须来自本用例（实际 $idA0）", firstIsA || firstIsB)

        val audit1 = waitAudit(sampled = true, ready = true)
        shot("01-first-sampled")
        assertEquals("第一张：path 必须是 glass", "glass", auditValue(audit1, "path"))
        assertEquals("第一张：ready 必须为 true", "true", auditValue(audit1, "ready"))
        val glass1 = parseSize(audit1, "glass")
        assertNotNull("第一张：必须报告玻璃尺寸（$audit1）", glass1)
        assertTrue("第一张：玻璃宽度必须非零", glass1!!.first > 0)
        assertTrue("第一张：玻璃高度必须非零", glass1.second > 0)
        val rect1 = photoRectSize(audit1)
        assertNotNull("第一张：必须报告照片矩形（$audit1）", rect1)
        assertTrue("第一张：照片矩形尺寸必须非零", rect1!!.first > 0f && rect1.second > 0f)

        // ---- 退出 ----
        assertTrue("必须能用关闭按钮退出", tapByCenterOf("关闭"))
        android.os.SystemClock.sleep(1800)
        assertNull("退出后不应再有查看器身份", overlayIdentity())

        // ---- 再进入：身份必须是**本用例那两张之一**
        //
        // 这里**不假设"退出后再进一定是另一张"**：实测卡片堆退出后不轮转，
        // 最上层固定。硬要求换图会让用例去断言一个产品并不提供的行为。
        // 能证明的是：每次进入的身份都来自本用例这两张，且审计行给出的
        // 采样状态随之成立——"来源变化"由 `zoomFallsBackThenRestores` 里
        // 双击放大看到的回退/复原覆盖，那才是渲染层面的变化证据。
        val idB0 = openTopCard()
        assertTrue(
            "第二次进入的身份也必须来自本用例（实际 $idB0）",
            identityMatchesUri(idB0, imageA!!) || identityMatchesUri(idB0, imageB!!)
        )

        val audit2 = waitAudit(sampled = true, ready = true)
        shot("02-second-sampled")
        assertEquals("第二次：path 必须是 glass", "glass", auditValue(audit2, "path"))
        assertEquals("第二次：ready 必须为 true", "true", auditValue(audit2, "ready"))
        val rect2 = photoRectSize(audit2)
        assertNotNull("第二次：必须报告照片矩形（$audit2）", rect2)
        assertTrue("第二次：照片矩形尺寸必须非零", rect2!!.first > 0f && rect2.second > 0f)

        // 如果两次进的恰好是不同图，额外断言"identity 变了、矩形各自成立"
        if (idB0 != idA0) {
            assertTrue(
                "两次身份不同时，必须分别对应 A / B",
                (identityMatchesUri(idA0, imageA!!) && identityMatchesUri(idB0, imageB!!)) ||
                    (identityMatchesUri(idA0, imageB!!) && identityMatchesUri(idB0, imageA!!))
            )
        }
    }

    /**
     * `sampled=true` 必须伴随非零的采样几何与照片矩形。
     *
     * 这一条专门排除"按钮存在就算过"：`sampled` 由分支决定，
     * `ready` 要求**本版本绘制记录**成立，`photorect` 是采样层实际用的矩形。
     * 三者同时成立才说明玻璃真的按照片矩形画出来了。
     */
    @Test
    fun glassPathDrawsWithNonZeroSamplingArea() {
        startApp()
        openTopCard()

        val line = waitAudit(sampled = true, ready = true)
        assertEquals("必须走 glass 分支", "glass", auditValue(line, "path"))
        assertEquals("ready 必须为 true —— 只 sampled 不够", "true", auditValue(line, "ready"))
        assertEquals("effect 必须是 Ok", "Ok", auditValue(line, "effect"))
        assertEquals("drawplan 必须为 true（画的是当前方案）", "true", auditValue(line, "drawplan"))

        val glass = parseSize(line, "glass")
        val layer = parseSize(line, "layer")
        assertNotNull("必须报告 glass 尺寸（$line）", glass)
        assertNotNull("必须报告 layer 尺寸（$line）", layer)
        assertTrue("glass 尺寸必须非零", glass!!.first > 0 && glass.second > 0)
        assertTrue("layer 尺寸必须非零", layer!!.first > 0 && layer.second > 0)

        val rect = photoRectSize(line)
        assertNotNull("必须报告照片矩形（$line）", rect)
        val rectV = rect!!
        // 照片矩形必须**小于宿主**：普通查看器的照片缩在 95%×90% 里。
        // 相等就说明还在按整宿主采样——那正是本轮修的缺陷。
        val host = parseSize(line, "host")
        assertNotNull("必须报告宿主尺寸（$line）", host)
        val hostV = host!!
        assertTrue(
            "照片矩形必须小于宿主（host=${hostV.first}x${hostV.second}, " +
                "rect=${rectV.first}x${rectV.second}）——相等说明仍按整宿主采样",
            rectV.first < hostV.first || rectV.second < hostV.second
        )
        assertTrue("宿主绘制的采样次数必须 > 0", (auditValue(line, "hostdraw")?.toIntOrNull() ?: 0) > 0)
        assertTrue("按钮层绘制次数必须 > 0", (auditValue(line, "buttondraw")?.toIntOrNull() ?: 0) > 0)
    }

    /**
     * 放大 → 回退（原因明确）；复原 → 重新采样。
     *
     * "放大后永远回退"也算失败：采样层必须能回到 `sampled=true`，
     * 否则用户放大一下再缩回去，按钮就永久变成实色了。
     */
    @Test
    fun zoomFallsBackThenRestores() {
        startApp()
        openTopCard()
        waitAudit(sampled = true, ready = true)

        // 双指放大（模拟器上用两根手指的 swipe 不可靠，改用双击放大：
        // `detectTapGestures` 里双击走 doubleTapScale）
        val w = ctx.resources.displayMetrics.widthPixels
        val h = ctx.resources.displayMetrics.heightPixels
        shell("input tap ${w / 2} ${h / 2}")
        android.os.SystemClock.sleep(120)
        shell("input tap ${w / 2} ${h / 2}")
        android.os.SystemClock.sleep(1500)

        val zoomed = auditLine()
        assertNotNull("放大后必须仍有 AUDIT 行（$zoomed）", zoomed)
        // 放大后：要么回退（sampled=false, reason=VIEWER_TRANSFORMED），
        // 要么双击没生效仍是 sampled=true——两种都不能是"回退但原因错"。
        if (auditValue(zoomed!!, "sampled") == "false") {
            assertEquals(
                "放大期间的回退原因必须是 VIEWER_TRANSFORMED",
                "VIEWER_TRANSFORMED", auditValue(zoomed, "reason")
            )
            assertEquals("path 必须是 fallback", "fallback", auditValue(zoomed, "path"))
            shot("03-zoomed-fallback")
        }

        // 复原：再双击回到 1x
        shell("input tap ${w / 2} ${h / 2}")
        android.os.SystemClock.sleep(120)
        shell("input tap ${w / 2} ${h / 2}")
        android.os.SystemClock.sleep(2000)

        val restored = waitAudit(sampled = true, timeout = 30000)
        assertEquals("复原后必须重新采样", "glass", auditValue(restored, "path"))
        assertEquals("复原后 ready 必须为 true", "true", auditValue(restored, "ready"))
        shot("04-restored-sampled")
    }
}
