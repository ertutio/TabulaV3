package com.tabula.v3

import android.content.ContentValues
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
import com.tabula.v3.data.intent.IntentAssistKeyStore
import com.tabula.v3.data.intent.IntentVerdictCache
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.repository.RecycleBinManager
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PR A 截图取证（不参与常规回归，按需单跑）。
 *
 * 用真实设备走到每个改动过的页面，在关键节点调用 `screencap` 落盘，
 * 供 PR 正文附「修改前后」对照。**截图路径写进 report**，便于核对。
 *
 * 只走一遍正常路径，不做断言（断言由 `IntentBatchPageFlowTest` 等既有用例承担）。
 */
@RunWith(AndroidJUnit4::class)
class UiShotDeviceTest {

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private lateinit var prefs: AppPreferences
    private val inserted = mutableListOf<Uri>()

    private val shotDir = "/sdcard/ui-shots"

    @Before
    fun setUp() {
        prefs = AppPreferences(ctx)
        prefs.hasCompletedOnboarding = true
        prefs.hasAcknowledgedDisclaimer = true
        prefs.recommendMode = RecommendMode.RANDOM_WALK
        prefs.hasStagedCleanupSourceChoice = false
        prefs.stagedCleanupSourceKeys = emptySet()
        runBlocking { runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() } }
        purge()
        IntentVerdictCache(
            ctx.getSharedPreferences("tabula_intent_verdicts", android.content.Context.MODE_PRIVATE)
        ).clear()
        IntentAssistKeyStore(ctx).delete()
        listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        ).forEach { perm -> runCatching { shell("pm grant ${ctx.packageName} $perm") } }
        shell("mkdir -p $shotDir")

        insertCoupon("ui-shot-coupon-1-" + UUID.randomUUID().toString().take(4) + ".png")
        insertCoupon("ui-shot-coupon-2-" + UUID.randomUUID().toString().take(4) + ".png")
        insertOrder("ui-shot-order-" + UUID.randomUUID().toString().take(4) + ".png")
        android.os.SystemClock.sleep(2000)
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
                if (!name.startsWith("ui-shot-")) continue
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

    private fun shot(name: String) {
        shell("screencap -p $shotDir/$name")
        println("[shot] $shotDir/$name")
    }

    private fun insertCoupon(name: String): Uri = insertImage(name, expired = true)
    private fun insertOrder(name: String): Uri = insertImage(name, expired = false)

    private fun insertImage(name: String, expired: Boolean): Uri {
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        if (expired) {
            canvas.drawText("满199减30", 60f, 200f, paint)
            canvas.drawText("仅剩2天", 60f, 280f, paint)
            paint.textSize = 30f
            canvas.drawText("活动已于 2026-11-10 23:59 结束", 60f, 380f, paint)
        } else {
            canvas.drawText("订单详情", 60f, 200f, paint)
            canvas.drawText("待收货", 60f, 280f, paint)
            canvas.drawText("订单号 TB202411250009", 60f, 360f, paint)
            canvas.drawText("实付 ¥128.00", 60f, 440f, paint)
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
        println("[shot] wait timeout: $fragment / texts=${texts()}")
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

    private fun start() {
        ins.startActivitySync(
            android.content.Intent(ctx, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitText("回收站", 45000)
        android.os.SystemClock.sleep(2500)
    }

    @Test
    fun captureAll() {
        start()
        shot("01-deck.png")

        // 设置 → 暂存清理
        tap("设置"); android.os.SystemClock.sleep(1200)
        shot("02-settings.png")
        val opened = tap("暂存清理"); android.os.SystemClock.sleep(1500)
        println("[shot] staged opened=$opened")
        // 来源选择页
        shot("03-staged-source.png")
        val viewBtn = texts().firstOrNull { it.startsWith("查看") && it.endsWith("个来源的旧图") }
        if (viewBtn != null) {
            repeat(4) {
                if (texts().any { t -> t.contains("换来源") }) return@repeat
                tap(viewBtn, timeout = 3000)
                android.os.SystemClock.sleep(1200)
            }
        }
        shot("04-staged-candidates.png")

        // 进凑一批
        if (tap("按整理目的凑一批")) {
            waitText("只排序，不会自动删除；仍由你决定保留或移入回收站", 20000)
            android.os.SystemClock.sleep(1500)
            shot("05-intent-batch.png")

            // 点一张缩略图 → 选中态 + 底栏
            if (waitText("本轮：推荐", 180000)) {
                val thumb = texts().firstOrNull { it.startsWith("ui-shot-coupon-1") && it.endsWith(".png") }
                if (thumb != null) {
                    tap(thumb)
                    android.os.SystemClock.sleep(1500)
                    shot("06-intent-batch-selected.png")
                }
            }
        }
        // 返回暂存清理，再截一张（入口改动在这里）
        shell("input keyevent KEYCODE_BACK")
        android.os.SystemClock.sleep(1500)
        shot("07-staged-after-back.png")
        println("[shot] done")
    }
}
