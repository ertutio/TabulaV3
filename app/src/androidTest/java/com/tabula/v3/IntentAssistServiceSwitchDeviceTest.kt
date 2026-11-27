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
import com.tabula.v3.data.intent.IntentVerdictCache
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
 * **服务切换**的真实页面验收（PR A 第 2 轮，合入阻断项的回归）。
 *
 * ## 修的缺陷
 *
 * 上一版把页内服务选择控件（DeepSeek / OpenCode Go 两个 chip）压进了配置弹层，
 * 但弹层里的服务标签只改「正在编辑哪套 Key」，**没有任何入口调用
 * `onSelectAssistService`**。于是用户从「凑一批」点当前服务名 → 在弹层选 OpenCode Go
 * 并保存 Key → 返回后当前服务仍是 DeepSeek，**根本无法切换外发对象**。
 *
 * 上一版正文里的「Key 轮转」用例是直接读写 `IntentAssistKeyStore` 的，
 * 绕过了弹层，所以抓不到这个问题。
 *
 * ## 本用例走真实弹层
 *
 * 链路：设置 → 暂存清理 → 凑一批 → 点当前服务名 → 弹层里点另一个服务 →
 * 点「使用 X」→ 返回页面后断言当前服务真的变了。
 *
 * 覆盖：
 * 1. DeepSeek → OpenCode Go 切换成功（页面显示新服务）；
 * 2. OpenCode Go → DeepSeek 切回（两个方向都能）；
 * 3. **两套 Key 独立**：给 Go 写 Key 不影响 DeepSeek 的 Key 文件，反之亦然；
 * 4. **切换后本批授权撤销**：先开辅助 → 切换服务 → 回页面后必须是关闭态，
 *    且必须由用户主动重新开启（开关不是自动打开的）。
 */
@RunWith(AndroidJUnit4::class)
class IntentAssistServiceSwitchDeviceTest {

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
        runBlocking { runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() } }
        purge()
        IntentVerdictCache(
            ctx.getSharedPreferences("tabula_intent_verdicts", android.content.Context.MODE_PRIVATE)
        ).clear()
        // 两个服务的 Key 都清掉，从干净状态开始
        IntentAssistService.entries.forEach { service ->
            IntentAssistKeyStore(
                java.io.File(ctx.noBackupFilesDir, service.keyFileName),
                service
            ).delete()
        }
        prefs.intentAssistService = IntentAssistService.DEFAULT
        grant()
    }

    @After
    fun cleanUp() {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        runBlocking { runCatching { RecycleBinManager.getInstance(ctx).clearRecycleBin() } }
        IntentAssistService.entries.forEach { service ->
            runCatching {
                IntentAssistKeyStore(java.io.File(ctx.noBackupFilesDir, service.keyFileName), service).delete()
            }
        }
        runCatching { prefs.intentAssistService = IntentAssistService.DEFAULT }
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
                if (!name.startsWith("svc-switch-")) continue
                ids += MediaStore.Images.Media
                    .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    .buildUpon().appendPath(cursor.getLong(idCol).toString()).build()
            }
            ids.forEach { uri -> runCatching { ctx.contentResolver.delete(uri, null, null) } }
        }
        android.os.SystemClock.sleep(500)
    }

    private fun grant() {
        listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        ).forEach { perm -> runCatching { shell("pm grant ${ctx.packageName} $perm") } }
    }

    private fun shell(cmd: String): String = ins.uiAutomation.executeShellCommand(cmd).use { p ->
        java.io.FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun insertCoupon(name: String): Uri {
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK; textSize = 46f; typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("满199减30", 60f, 200f, paint)
        val dl = Paint().apply { color = Color.BLACK; textSize = 30f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("活动已于 2026-11-10 23:59 结束", 60f, 380f, dl)
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

    private fun startApp() {
        activity = ins.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitText("回收站", 45000)
        android.os.SystemClock.sleep(2500)
    }

    /** 设置 → 暂存清理 → 来源 → 候选页。 */
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

    /** 候选页 → 凑一批（整行列表项）。 */
    private fun openIntentBatch() {
        assertTrue("必须能看到入口", waitText("按整理目的凑一批", 20000))
        assertTrue("入口必须可点", tap("按整理目的凑一批"))
        assertTrue(
            "必须进入凑一批页",
            waitText("只排序，不会自动删除；仍由你决定保留或移入回收站", 30000)
        )
    }

    /** 页面上当前显示的服务名（那一行里的可点服务文本）。 */
    private fun currentServiceOnPage(): IntentAssistService? {
        val t = texts()
        // 页面服务名的显示形态就是 displayName；两个服务名不会同时出现在同一行
        return IntentAssistService.entries.firstOrNull { s ->
            t.any { it == s.displayName }
        }
    }

    private fun openServiceDialog() {
        val current = currentServiceOnPage()
        assertNotNull("页面上必须能看到当前服务名（屏幕：${texts()}）", current)
        assertTrue("点当前服务名必须打开配置弹层", tap(current!!.displayName))
        assertTrue("弹层必须出现（含『使用』或『当前』字样）", waitText("当前：", 10000) ||
            texts().any { it.contains("使用 ") })
    }

    private fun currentServiceFromPrefs(): IntentAssistService = prefs.intentAssistService

    // ==================== 用例 ====================

    /** 合入阻断项的回归：DeepSeek → OpenCode Go 必须真的切过去。 */
    @Test
    fun switchingToOpenCodeGoFromDialogActuallyChangesService() {
        insertCoupon("svc-switch-" + UUID.randomUUID().toString().take(6) + ".png")
        android.os.SystemClock.sleep(1500)
        startApp()
        openCandidates()
        openIntentBatch()

        assertEquals("起始服务必须是 DeepSeek", IntentAssistService.DEEPSEEK, currentServiceFromPrefs())

        openServiceDialog()
        // 弹层里选 OpenCode Go（只是切编辑对象）
        assertTrue("弹层里必须能点 OpenCode Go", tap("OpenCode Go"))
        android.os.SystemClock.sleep(800)
        // 明确的「使用此服务」操作
        val useText = "使用 ${IntentAssistService.OPENCODE_GO.displayName}"
        assertTrue(
            "弹层必须有明确的服务切换操作（$useText，屏幕：${texts()}）",
            tap(useText, timeout = 10000)
        )
        android.os.SystemClock.sleep(2500)

        assertEquals(
            "从弹层切换后偏好必须真的变成 OpenCode Go",
            IntentAssistService.OPENCODE_GO, currentServiceFromPrefs()
        )
        // 回到页面后标题行应显示新服务
        val deadline = android.os.SystemClock.uptimeMillis() + 20000
        var onPage: IntentAssistService? = null
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            onPage = currentServiceOnPage()
            if (onPage == IntentAssistService.OPENCODE_GO) break
            android.os.SystemClock.sleep(300)
        }
        assertEquals(
            "页面必须显示切换后的服务（屏幕：${texts()}）",
            IntentAssistService.OPENCODE_GO, onPage
        )
    }

    /** 反方向：OpenCode Go → DeepSeek。 */
    @Test
    fun switchingBackToDeepSeekWorks() {
        prefs.intentAssistService = IntentAssistService.OPENCODE_GO
        insertCoupon("svc-switch-" + UUID.randomUUID().toString().take(6) + ".png")
        android.os.SystemClock.sleep(1500)
        startApp()
        openCandidates()
        openIntentBatch()

        assertEquals(IntentAssistService.OPENCODE_GO, currentServiceFromPrefs())
        openServiceDialog()
        assertTrue(tap("DeepSeek"))
        android.os.SystemClock.sleep(800)
        assertTrue(
            "必须能切回 DeepSeek（屏幕：${texts()}）",
            tap("使用 ${IntentAssistService.DEEPSEEK.displayName}", timeout = 10000)
        )
        android.os.SystemClock.sleep(2500)
        assertEquals(
            "反方向切换也必须生效",
            IntentAssistService.DEEPSEEK, currentServiceFromPrefs()
        )
    }

    /** 两套 Key 独立：在弹层里给 Go 存 Key，不能影响 DeepSeek 的文件。 */
    @Test
    fun twoKeyFilesStayIndependentThroughDialog() {
        val deepSeekStore = IntentAssistKeyStore(
            java.io.File(ctx.noBackupFilesDir, IntentAssistService.DEEPSEEK.keyFileName),
            IntentAssistService.DEEPSEEK
        )
        val goStore = IntentAssistKeyStore(
            java.io.File(ctx.noBackupFilesDir, IntentAssistService.OPENCODE_GO.keyFileName),
            IntentAssistService.OPENCODE_GO
        )
        deepSeekStore.write("sk-deepseek-keep-me")
        goStore.delete()

        insertCoupon("svc-switch-" + UUID.randomUUID().toString().take(6) + ".png")
        android.os.SystemClock.sleep(1500)
        startApp()
        openCandidates()
        openIntentBatch()
        openServiceDialog()

        // 在弹层里切到 OpenCode Go 并输入 Key
        assertTrue(tap("OpenCode Go"))
        android.os.SystemClock.sleep(600)
        val field = allNodes().firstOrNull { it.className?.contains("EditText") == true }
        assertNotNull("弹层必须有 Key 输入框（屏幕：${texts()}）", field)
        val r = android.graphics.Rect(); field!!.getBoundsInScreen(r)
        shell("input tap ${r.centerX()} ${r.centerY()}")
        android.os.SystemClock.sleep(600)
        // 注意：`input text` 不解析 shell 引号，单引号会变成字面字符。
        // 用下划线形式的键（不含空格/特殊字符）避免转义问题。
        shell("input text oc_sk_from_dialog_test")
        android.os.SystemClock.sleep(600)
        assertTrue("必须能保存", tap("保存", timeout = 10000))
        android.os.SystemClock.sleep(1500)

        assertEquals(
            "Go 的 Key 必须写进 Go 的文件",
            "oc_sk_from_dialog_test", goStore.read()
        )
        assertEquals(
            "DeepSeek 的 Key 不得被改动",
            "sk-deepseek-keep-me", deepSeekStore.read()
        )
    }

    /** 切换服务必须撤销本批授权，且不由系统自动重新开启。 */
    @Test
    fun switchingServiceRevokesBatchAuthorization() {
        // 先给当前服务配 Key，让开关可用
        val deepSeekStore = IntentAssistKeyStore(
            java.io.File(ctx.noBackupFilesDir, IntentAssistService.DEEPSEEK.keyFileName),
            IntentAssistService.DEEPSEEK
        )
        deepSeekStore.write("sk-synthetic-authorization-0001")

        insertCoupon("svc-switch-" + UUID.randomUUID().toString().take(6) + ".png")
        android.os.SystemClock.sleep(1500)
        startApp()
        openCandidates()
        openIntentBatch()

        // 1) 先在本批主动开启
        assertTrue("必须能点到开关", tapAssistSwitch())
        val dialogShown = texts().any { it.contains("开启前请确认") }
        if (dialogShown) {
            assertTrue(tap("明白，本批次开启", timeout = 10000))
            android.os.SystemClock.sleep(2000)
        }
        assertTrue("开启后必须显示已启用", waitText("本批次已启用", 60000))

        // 2) 从弹层切换服务
        openServiceDialog()
        assertTrue(tap("OpenCode Go"))
        android.os.SystemClock.sleep(800)
        assertTrue(
            "必须能切换",
            tap("使用 ${IntentAssistService.OPENCODE_GO.displayName}", timeout = 10000)
        )
        android.os.SystemClock.sleep(3000)

        // 3) 回页面：必须是关闭态，且不是自动重新开启
        val deadline = android.os.SystemClock.uptimeMillis() + 30000
        var after = texts()
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            after = texts()
            if (after.none { it.contains("本批次已启用") }) break
            android.os.SystemClock.sleep(300)
        }
        assertFalse(
            "切换服务后不得仍显示已启用（屏幕：$after）",
            after.any { it.contains("本批次已启用") }
        )
        assertTrue(
            "切换后必须回到关闭态（屏幕：$after）",
            after.any { it.contains("默认关闭") } || after.any { it.contains("未配置 Key") }
        )
    }

    /** 开关点击（Compose Switch 在无障碍树上是 checkable）。 */
    private fun tapAssistSwitch(): Boolean {
        repeat(6) {
            val checkable = allNodes().firstOrNull { it.isCheckable }
            if (checkable != null && checkable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                android.os.SystemClock.sleep(1200)
                val t = texts()
                if (t.any { it.contains("开启前请确认") } || t.any { it.contains("本批次已启用") }) return true
            }
            android.os.SystemClock.sleep(800)
        }
        return false
    }
}
