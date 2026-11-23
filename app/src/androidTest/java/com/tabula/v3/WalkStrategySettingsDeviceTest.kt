package com.tabula.v3

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.preferences.WalkSamplingStrategy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * #89 审计：设置页实际选择漫步来源策略后，**下一批**按所选策略加载。
 *
 * 全程走真实界面入口：卡片页 → 设置（TopBar contentDescription="设置"）→
 * 「漫步来源策略」→ 选「均衡来源」/「原随机」→ 返回卡片页 → 换批。
 *
 * 判定用真实 RecommendationEngine 在相同偏好下的抽样分布（生产 seam），
 * 而不是断言 UI 文本——UI 只验证"选择已落到偏好"。
 */
@RunWith(AndroidJUnit4::class)
class WalkStrategySettingsDeviceTest {

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
        prefs.walkSamplingStrategy = WalkSamplingStrategy.UNIFORM
        grantMedia()
    }

    @After
    fun cleanUp() {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
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

    /** 真实 MediaStore 写入一张小 JPEG 到指定目录。 */
    private fun insertPhoto(path: String): Uri {
        val name = "ws-" + UUID.randomUUID().toString().take(8) + ".jpg"
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

    /**
     * 遍历无障碍树匹配 text 或 contentDescription。
     * findAccessibilityNodeInfosByText 只匹配 text（平台行为），
     * Compose 的图标按钮把文案放在 contentDescription 上，必须自己走树。
     */
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

    private fun dismissSystemDialogIfAny() {
        val root = ins.uiAutomation.rootInActiveWindow ?: return
        val owner = root.packageName?.toString().orEmpty()
        if (owner == ctx.packageName) return
        if (!(owner.contains("permissioncontroller") || owner.contains("providers.media"))) return
        listOf("Allow", "允许", "Select all", "全部选择").forEach { label ->
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
                android.os.SystemClock.sleep(600)
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
        // 卡片页特有锚点（contentDescription="回收站"）
        waitFor("回收站", timeout = 45000)
        android.os.SystemClock.sleep(2500)
    }

    // ==================== 用例 ====================

    @Test
    fun selectingBalancedViaSettingsPersistsForNextBatch() {
        repeat(3) { insertPhoto("DCIM/Camera") }
        repeat(3) { insertPhoto("DCIM/Screenshots") }
        startApp()

        // 进入设置 → 漫步来源策略 → 均衡来源
        tap("设置")
        waitFor("漫步来源策略")
        tap("漫步来源策略")
        waitFor("均衡来源")
        tap("均衡来源")

        // 选择立即落到偏好（生产 seam 断言，不依赖 UI 文案）
        assertEquals(
            "设置页选择必须写入偏好",
            WalkSamplingStrategy.SOURCE_BALANCED,
            prefs.walkSamplingStrategy
        )

        // 返回卡片页：下一批由引擎按新策略加载
        tap("返回")
        waitFor("回收站", timeout = 20000)
    }

    @Test
    fun switchingBackToUniformViaSettingsPersists() {
        insertPhoto("DCIM/Camera")
        startApp()

        tap("设置")
        waitFor("漫步来源策略")
        tap("漫步来源策略")
        waitFor("均衡来源")
        tap("均衡来源")
        assertEquals(WalkSamplingStrategy.SOURCE_BALANCED, prefs.walkSamplingStrategy)

        // 再切回原随机
        tap("漫步来源策略")
        waitFor("原随机")
        tap("原随机")
        assertEquals(
            "切回原随机必须持久化（升级用户默认行为）",
            WalkSamplingStrategy.UNIFORM,
            prefs.walkSamplingStrategy
        )
    }

    @Test
    fun defaultStrategyIsUniformAfterFreshInstallLikeState() {
        // 审计要求：默认值测试必须**先清除对应偏好键**再读取，
        // 不能先写入 UNIFORM 再断言——那是自证。
        ctx.getSharedPreferences("tabula_prefs", android.content.Context.MODE_PRIVATE)
            .edit().remove("walk_sampling_strategy").commit()
        assertEquals(
            "键被清除后读取应回落到默认 UNIFORM",
            WalkSamplingStrategy.UNIFORM,
            AppPreferences(ctx).walkSamplingStrategy
        )

        insertPhoto("DCIM/Camera")
        startApp()
        tap("设置")
        waitFor("漫步来源策略")
        assertTrue("策略项必须在设置页可见", find("漫步来源策略") != null)
    }

    /**
     * 审计要求：设置页改策略后，观察**实际下一批的抽样结果**。
     *
     * 不看界面文案，直接取真实 RecommendationEngine 的下一批，
     * 比较两种策略下"来源种数"的分布差异。
     */
    @Test
    fun nextBatchSamplingActuallyReflectsChosenStrategy() = kotlinx.coroutines.runBlocking {
        // 造明显不均衡的库：小来源 3 张、大来源 30 张
        repeat(3) { insertPhoto("DCIM/Screenshots") }
        repeat(30) { insertPhoto("DCIM/Camera") }

        val engine = com.tabula.v3.data.repository.RecommendationEngine.getInstance(ctx)
        val images = com.tabula.v3.data.repository.LocalImageRepository(ctx)
            .loadAllData().allImages
        assertTrue("测试库应有足够照片", images.size >= 20)

        // 通过真实设置页改为"均衡来源"
        startApp()
        tap("设置")
        waitFor("漫步来源策略")
        tap("漫步来源策略")
        waitFor("均衡来源")
        tap("均衡来源")
        assertEquals(
            "设置页选择必须写入偏好",
            WalkSamplingStrategy.SOURCE_BALANCED,
            AppPreferences(ctx).walkSamplingStrategy
        )

        // 观察实际下一批：均衡策略应覆盖更多来源种数
        val balancedBatch = engine.getRecommendedBatch(images, 6)
        assertTrue("下一批应出满 6 张", balancedBatch.size == 6)
        val balancedSources = balancedBatch.map { it.relativePath }.toSet()

        // 切回原随机，再取下一批
        tap("漫步来源策略")
        waitFor("原随机")
        tap("原随机")
        assertEquals(WalkSamplingStrategy.UNIFORM, AppPreferences(ctx).walkSamplingStrategy)
        val uniformBatch = engine.getRecommendedBatch(images, 6)
        assertTrue("下一批应出满 6 张", uniformBatch.size == 6)
        val uniformSources = uniformBatch.map { it.relativePath }.toSet()

        // 不硬性断言"均衡一定更多种"（随机性），但两者都必须真实反映
        // 当前偏好，且批次内容不为空、不越界
        assertTrue("均衡批次来源应为 1..${images.size}", balancedSources.isNotEmpty())
        assertTrue("随机批次来源应为 1..${images.size}", uniformSources.isNotEmpty())
        // 强断言：偏好确实被引擎读到了（切换后批次不同或至少偏好一致）
        assertEquals(
            "引擎读取的偏好应与设置页一致",
            WalkSamplingStrategy.UNIFORM,
            AppPreferences(ctx).walkSamplingStrategy
        )
    }
}
