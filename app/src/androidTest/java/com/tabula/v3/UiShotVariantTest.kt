package com.tabula.v3

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.intent.IntentAssistService
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.repository.RecycleBinManager
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PR A 第 2 轮：**布局验收截图**（不参与常规回归）。
 *
 * 复审要求补一组窄屏 / 大字体 / 深色的重点截图，覆盖：
 * - 「凑一批」的列表末项与底栏；
 * - 服务配置弹层；
 * - 分享待清与连拍选片（改过头部的两页）。
 *
 * 由外部脚本在跑之前设置 `wm size` / `font_scale` / `night`，
 * 本用例只负责走到页面并 `screencap`，产物名带当前变体后缀（见 `VARIANT`）。
 */
@RunWith(AndroidJUnit4::class)
class UiShotVariantTest {

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private lateinit var prefs: AppPreferences
    private val inserted = mutableListOf<Uri>()

    /** 由 `-e variant narrow|large|dark` 传入，只用于文件名。 */
    private val variant: String
        get() = InstrumentationRegistry.getArguments().getString("variant") ?: "default"

    @Before
    fun setUp() {
        prefs = AppPreferences(ctx)
        prefs.hasCompletedOnboarding = true
        prefs.hasAcknowledgedDisclaimer = true
        prefs.recommendMode = RecommendMode.RANDOM_WALK
        prefs.hasStagedCleanupSourceChoice = false
        prefs.stagedCleanupSourceKeys = emptySet()
        prefs.intentAssistService = IntentAssistService.DEFAULT
        runBlocking { runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() } }
        purge()
        listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        ).forEach { perm -> runCatching { shell("pm grant ${ctx.packageName} $perm") } }
        shell("mkdir -p /sdcard/ui-shots-variant")
        // 多插几张，保证列表有末项与底栏
        repeat(5) { i ->
            insertCoupon("var-shot-coupon-$i-" + UUID.randomUUID().toString().take(4) + ".png")
        }
        insertOrder("var-shot-order-" + UUID.randomUUID().toString().take(4) + ".png")
        android.os.SystemClock.sleep(2500)
    }

    @After
    fun cleanUp() {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        runBlocking { runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() } }
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
                if (!name.startsWith("var-shot-")) continue
                ids += MediaStore.Images.Media
                    .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    .buildUpon().appendPath(cursor.getLong(idCol).toString()).build()
            }
            ids.forEach { uri -> runCatching { ctx.contentResolver.delete(uri, null, null) } }
        }
        android.os.SystemClock.sleep(500)
    }

    private fun shell(cmd: String): String = ins.uiAutomation.executeShellCommand(cmd).use { p ->
        java.io.FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * 拍一张到 `/sdcard/ui-shots-variant/<variant>-<name>`。
     *
     * [name] 自带后缀（`01-deck.png`），这里不再补 `.png`——补了会得到
     * `narrow-01-deck.png.png`，拉回仓库时文件名对不上。
     */
    private fun shot(name: String) {
        shell("screencap -p /sdcard/ui-shots-variant/${variant}-$name")
    }

    private fun insertCoupon(name: String): Uri = insert(name, true)
    private fun insertOrder(name: String): Uri = insert(name, false)

    private fun insert(name: String, expired: Boolean): Uri {
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK; textSize = 46f; typeface = Typeface.DEFAULT_BOLD
        }
        if (expired) {
            canvas.drawText("满199减30", 60f, 200f, paint)
            canvas.drawText("仅剩2天", 60f, 280f, paint)
            paint.textSize = 30f
            canvas.drawText("活动已于 2026-11-10 23:59 结束", 60f, 380f, paint)
        } else {
            canvas.drawText("订单详情", 60f, 200f, paint)
            canvas.drawText("订单号 TB202411250009", 60f, 360f, paint)
        }
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
        val old = (System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000) / 1000
        ctx.contentResolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_ADDED, old)
                put(MediaStore.MediaColumns.DATE_MODIFIED, old)
            }, null, null
        )
        bitmap.recycle()
        return uri
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

    private fun texts(): Set<String> {
        val out = mutableSetOf<String>()
        allNodes().forEach { n ->
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out
    }

    private fun waitText(fragment: String, timeout: Long = 30000): Boolean {
        val end = android.os.SystemClock.uptimeMillis() + timeout
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (texts().any { it.contains(fragment) }) return true
            android.os.SystemClock.sleep(250)
        }
        return false
    }

    private fun tap(target: String, timeout: Long = 20000): Boolean {
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

    /** 当前屏幕的逻辑尺寸（受 `wm size` 覆盖影响，窄屏变体会比物理尺寸小）。 */
    private fun screenSize(): Pair<Int, Int> {
        val out = shell("wm size")
        val m = Regex("""(\d+)x(\d+)""").findAll(out).lastOrNull()
        return if (m != null) {
            m.groupValues[1].toInt() to m.groupValues[2].toInt()
        } else {
            ctx.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
    }

    /**
     * 列表滚动指纹：把每个候选卡片标题行的屏幕 y 坐标拼起来。
     *
     * 用途只有一个——判断「滑了到底动没动」。不比较整屏文本：
     * 滚动时文本集合不变，只有位置变。
     */
    private fun intentListSignature(): String {
        val ys = allNodes().mapNotNull { n ->
            val t = n.text?.toString()
            if (t != null && t.contains("没写可核对的截止时间")) {
                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                r.top
            } else null
        }
        return ys.sorted().joinToString(",")
    }

    private fun startApp() {
        ins.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitText("回收站", 45000)
        android.os.SystemClock.sleep(3000)
    }

    private fun openCandidates() {
        tap("设置"); android.os.SystemClock.sleep(1000)
        tap("暂存清理"); android.os.SystemClock.sleep(1500)
        val viewBtn = run {
            val end = android.os.SystemClock.uptimeMillis() + 20000
            while (android.os.SystemClock.uptimeMillis() < end) {
                texts().firstOrNull { it.startsWith("查看") && it.endsWith("个来源的旧图") }
                    ?.let { return@run it }
                android.os.SystemClock.sleep(250)
            }
            null
        }
        if (viewBtn != null) {
            val end = android.os.SystemClock.uptimeMillis() + 40000
            while (android.os.SystemClock.uptimeMillis() < end) {
                if (texts().any { it.contains("换来源") }) break
                tap(viewBtn, timeout = 3000)
                android.os.SystemClock.sleep(1200)
            }
        }
    }

    @Test
    fun capture() {
        startApp()
        shot("01-deck.png")

        openCandidates()
        shot("02-staged.png")

        // 凑一批
        if (tap("按整理目的凑一批")) {
            waitText("只排序，不会自动删除；仍由你决定保留或移入回收站", 30000)
            waitText("本轮：推荐", 180000)
            android.os.SystemClock.sleep(1500)
            shot("03-intent-top.png")

            // 滚到列表末项 + 底栏。
            // 起点必须按**当前屏幕**算，不能写死 1600：窄屏变体是 720x1600，
            // y=1600 正好落在手势导航区，输入会被系统当成「上滑回桌面」——
            // 之前 narrow-04 拍到的就是桌面。起点取屏高的 70%。
            val (sw, sh) = screenSize()
            val x = sw / 2
            val fromY = (sh * 0.70f).toInt()
            val toY = (sh * 0.30f).toInt()
            // 先记下列表首项的文本位置：滚完如果一点没动，说明一屏就装完了，
            // 此时**不再单独拍 04**——那会得到一张与 03 逐像素相同的图，
            // 看起来像"截到了末项"，其实什么都没验证。
            val before = intentListSignature()
            repeat(3) {
                shell("input swipe $x $fromY $x $toY 250")
                android.os.SystemClock.sleep(600)
            }
            if (intentListSignature() != before) {
                shot("04-intent-bottom.png")
            } else {
                println("[variant-shot] $variant: 列表一屏装完，跳过 04-intent-bottom")
            }

            // 服务配置弹层
            val current = IntentAssistService.DEFAULT.displayName
            if (tap(current, timeout = 10000)) {
                android.os.SystemClock.sleep(1200)
                shot("05-service-dialog.png")
                // 切到**另一个**服务再拍：服务名从枚举取，不手写（手写的
                // "Opencode" 不是 displayName，点了没反应，两张图会一模一样）。
                val other = IntentAssistService.entries
                    .firstOrNull { it != IntentAssistService.DEFAULT }
                if (other != null && tap(other.displayName, timeout = 5000)) {
                    android.os.SystemClock.sleep(1000)
                }
                shot("06-service-dialog-other.png")
                // 关闭弹层
                tap("关闭", timeout = 5000)
                android.os.SystemClock.sleep(800)
            }
            shell("input keyevent KEYCODE_BACK")
            android.os.SystemClock.sleep(1500)
        }

        // 分享待清
        shell("input keyevent KEYCODE_BACK")
        android.os.SystemClock.sleep(1000)
        shell("input keyevent KEYCODE_BACK")
        android.os.SystemClock.sleep(1500)
        if (tap("设置")) {
            android.os.SystemClock.sleep(1000)
            if (tap("分享待清", timeout = 10000)) {
                android.os.SystemClock.sleep(1500)
                shot("07-relay.png")
                shell("input keyevent KEYCODE_BACK")
                android.os.SystemClock.sleep(1200)
            }
        }
        println("[variant-shot] done variant=$variant")
    }
}
