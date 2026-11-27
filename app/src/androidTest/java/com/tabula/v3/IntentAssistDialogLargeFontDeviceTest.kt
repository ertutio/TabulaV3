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
import com.tabula.v3.data.intent.IntentAssistKeyStore
import com.tabula.v3.data.intent.IntentAssistService
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
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
 * **大字体下文字辅助配置弹层的可达性验收**（PR A 第 3 轮）。
 *
 * ## 修的缺陷
 *
 * `AlertDialog` 把「删除 DeepSeek 的 Key」和「关闭」排在同一行 `FlowRow`。
 * 字体 1.5× 时两个按钮放不下，「关闭」被挤出弹层；选中 OpenCode Go 后
 * 删除文案更长（`删除 OpenCode Go 的 Key`），「关闭」完全不可见。
 * 键盘弹出后再压掉一截可用高度，正文与按钮一起被顶出。
 *
 * ## 本用例断言什么
 *
 * 不是"弹层能打开"，而是**两个动作在大字体下真的可点且有效**：
 * 1. DeepSeek 页：`关闭` 可见可点，点完弹层消失；
 * 2. OpenCode Go 页：`关闭` 同样可见可点；
 * 3. `保存` 可点，且 Key 真的写进**对应服务**的文件；
 * 4. 输入框聚焦 + 键盘弹出后，`保存` 与 `关闭` 仍在屏幕内且可点。
 *
 * 同时把三张现场图落到 `docs/ui-shots/large-font/`（由外部 `screencap` 拉取）。
 *
 * ## 前提
 *
 * 由外部脚本先把 `font_scale` 设成 1.5；本类只断言"在当前字体下能完成"，
 * 不改字体（改字体需要 `am force-stop` 之外的重建路径，会杀掉 instrumentation）。
 */
@RunWith(AndroidJUnit4::class)
class IntentAssistDialogLargeFontDeviceTest {

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private lateinit var prefs: AppPreferences
    private val inserted = mutableListOf<Uri>()

    private val deepSeekStore by lazy {
        IntentAssistKeyStore(
            java.io.File(ctx.noBackupFilesDir, IntentAssistService.DEEPSEEK.keyFileName),
            IntentAssistService.DEEPSEEK
        )
    }
    private val goStore by lazy {
        IntentAssistKeyStore(
            java.io.File(ctx.noBackupFilesDir, IntentAssistService.OPENCODE_GO.keyFileName),
            IntentAssistService.OPENCODE_GO
        )
    }

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
        IntentAssistService.entries.forEach { service ->
            runCatching {
                IntentAssistKeyStore(
                    java.io.File(ctx.noBackupFilesDir, service.keyFileName), service
                ).delete()
            }
        }
        prefs.intentAssistService = IntentAssistService.DEFAULT
        listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        ).forEach { perm -> runCatching { shell("pm grant ${ctx.packageName} $perm") } }
        shell("mkdir -p /sdcard/ui-shots-large-font")
    }

    @After
    fun cleanUp() {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        runBlocking { runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() } }
        IntentAssistService.entries.forEach { service ->
            runCatching {
                IntentAssistKeyStore(
                    java.io.File(ctx.noBackupFilesDir, service.keyFileName), service
                ).delete()
            }
        }
        runCatching { prefs.intentAssistService = IntentAssistService.DEFAULT }
    }

    // ===== 基础设施（与本 PR 既有设备用例同口径） =====

    private fun shell(cmd: String): String = ins.uiAutomation.executeShellCommand(cmd).use { p ->
        java.io.FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun shot(name: String) {
        shell("screencap -p /sdcard/ui-shots-large-font/$name.png")
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

    private fun find(label: String): AccessibilityNodeInfo? = allNodes().firstOrNull {
        it.contentDescription?.toString() == label || it.text?.toString() == label
    }

    /** 目标是否**在屏幕内**且在无障碍树里可见。 */
    private fun onScreen(label: String): Boolean {
        val n = find(label) ?: return false
        if (!n.isVisibleToUser) return false
        val r = android.graphics.Rect(); n.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        val dm = ctx.resources.displayMetrics
        return r.bottom > 0 && r.top < dm.heightPixels && r.right > 0 && r.left < dm.widthPixels
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
                if (!name.startsWith("lf-coupon-")) continue
                ids += MediaStore.Images.Media
                    .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    .buildUpon().appendPath(cursor.getLong(idCol).toString()).build()
            }
            ids.forEach { uri -> runCatching { ctx.contentResolver.delete(uri, null, null) } }
        }
        android.os.SystemClock.sleep(500)
    }

    private fun insertCoupon(name: String): Uri {
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK; textSize = 46f; typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("满199减30", 60f, 200f, paint)
        canvas.drawText("仅剩2天", 60f, 280f, paint)
        val small = Paint().apply {
            color = Color.BLACK; textSize = 30f; typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("活动已于 2026-11-10 23:59 结束", 60f, 380f, small)
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

    private fun startApp() {
        ins.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitText("回收站", 45000)
        android.os.SystemClock.sleep(2500)
    }

    /** 设置 → 暂存清理 → 来源 → 候选页 → 凑一批。 */
    private fun openIntentBatch() {
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
        assertTrue("必须能看到入口", waitText("按整理目的凑一批", 20000))
        assertTrue("入口必须可点", tap("按整理目的凑一批"))
        assertTrue(
            "必须进入凑一批页",
            waitText("只排序，不会自动删除；仍由你决定保留或移入回收站", 30000)
        )
    }

    private fun openDialog() {
        val current = IntentAssistService.DEFAULT.displayName
        assertTrue("点当前服务名必须打开配置弹层", tap(current))
        assertTrue("弹层必须出现", waitText("文字辅助 API Key", 10000))
        android.os.SystemClock.sleep(600)
    }

    // ===== 用例 =====

    /** DeepSeek 页：大字体下 `关闭` 必须在屏幕内、可点、点完弹层消失。 */
    @Test
    fun closeIsReachableOnDeepSeekPageAtLargeFont() {
        insertCoupon("lf-coupon-" + UUID.randomUUID().toString().take(6) + ".png")
        android.os.SystemClock.sleep(1500)
        startApp()
        openIntentBatch()
        openDialog()

        shot("01-dialog-deepseek")
        assertTrue(
            "大字体下 DeepSeek 页必须有可见的 `关闭`（屏幕：${texts()}）",
            onScreen("关闭")
        )
        assertTrue("`关闭` 必须可点", tap("关闭"))
        android.os.SystemClock.sleep(1200)
        assertFalse(
            "点 `关闭` 后弹层必须消失（屏幕：${texts()}）",
            texts().any { it.contains("文字辅助 API Key") }
        )
    }

    /** OpenCode Go 页：文案更长，`关闭` 仍必须可见（上一版就是在这里丢的）。 */
    @Test
    fun closeIsReachableOnOpenCodeGoPageAtLargeFont() {
        insertCoupon("lf-coupon-" + UUID.randomUUID().toString().take(6) + ".png")
        android.os.SystemClock.sleep(1500)
        startApp()
        openIntentBatch()
        openDialog()

        assertTrue("弹层里必须能点 OpenCode Go", tap(IntentAssistService.OPENCODE_GO.displayName))
        android.os.SystemClock.sleep(900)
        shot("02-dialog-opencodego")

        assertTrue(
            "大字体下 OpenCode Go 页必须有可见的 `关闭`（屏幕：${texts()}）",
            onScreen("关闭")
        )
        assertTrue("OpenCode Go 页的 `关闭` 必须可点", tap("关闭"))
        android.os.SystemClock.sleep(1200)
        assertFalse(
            "点 `关闭` 后弹层必须消失（屏幕：${texts()}）",
            texts().any { it.contains("文字辅助 API Key") }
        )
    }

    /** 键盘弹出后，`保存` 与 `关闭` 仍必须在屏幕内且可点；保存要真写进目标服务。 */
    @Test
    fun saveAndCloseStayReachableWithKeyboardShown() {
        insertCoupon("lf-coupon-" + UUID.randomUUID().toString().take(6) + ".png")
        android.os.SystemClock.sleep(1500)
        startApp()
        openIntentBatch()
        openDialog()

        // 切到 OpenCode Go 再输入，覆盖"文案更长 + 键盘弹出"的最坏组合
        assertTrue(tap(IntentAssistService.OPENCODE_GO.displayName))
        android.os.SystemClock.sleep(800)

        val field = allNodes().firstOrNull { it.className?.contains("EditText") == true }
        assertNotNull("弹层必须有 Key 输入框（屏幕：${texts()}）", field)
        val r = android.graphics.Rect(); field!!.getBoundsInScreen(r)
        shell("input tap ${r.centerX()} ${r.centerY()}")
        android.os.SystemClock.sleep(900)
        // 键盘此时应已弹出：断言底栏两个动作仍在屏幕内
        shell("input text lf_synthetic_key_0001")
        android.os.SystemClock.sleep(900)
        shot("03-dialog-keyboard")

        assertTrue(
            "键盘弹出后 `保存` 仍必须在屏幕内（屏幕：${texts()}）",
            onScreen("保存")
        )
        assertTrue(
            "键盘弹出后 `关闭` 仍必须在屏幕内（屏幕：${texts()}）",
            onScreen("关闭")
        )

        assertTrue("`保存` 必须可点", tap("保存"))
        android.os.SystemClock.sleep(1500)
        assertEquals(
            "Key 必须写进 OpenCode Go 的文件",
            "lf_synthetic_key_0001", goStore.read()
        )
        assertFalse(
            "保存后弹层必须关闭（屏幕：${texts()}）",
            texts().any { it.contains("文字辅助 API Key") }
        )
    }
}
