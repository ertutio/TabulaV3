package com.tabula.v3

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import com.tabula.v3.data.preferences.WalkSamplingStrategy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
 * 两个层次，分工明确：
 * - `nextBatchSamplingActuallyReflectsChosenStrategy`：**引擎层**。设置页点击 →
 *   偏好 → `RecommendationEngine` 分派；不经过卡片页。
 * - `deckReloadsBatchAfterStrategySwitch`：**页面层**。读生产代码自己的
 *   `currentBatch<-[batch-site-4]` 日志，断言卡片页持有的批次本身换了、且新批次
 *   覆盖两个来源——不另调引擎顶替。
 */
@RunWith(AndroidJUnit4::class)
class WalkStrategySettingsDeviceTest {

    private companion object {
        /** logcat 过滤用的测试 TAG（边界标记与批次行一起 dump）。 */
        const val TAG = "WalkStrategyDevice"
    }

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
     * **引擎层**策略分派：设置页切策略后，验证引擎在相同偏好下确实按所选策略出批。
     *
     * 说明（#89 三轮审计修正）：本用例**不经过卡片页**，它证明的是
     * "设置页的点击 → 偏好 → 引擎分派"这一段。原先 KDoc 写的"通过卡片页真实
     * 加载下一批"与实际行为不符，已改正。
     *
     * "卡片页真的消费了新批次"由同类的 `deckReloadsBatchAfterStrategySwitch`
     * 负责——那条读生产代码自己的 `currentBatch<-[batch-site-4]` 日志，
     * 断言的是页面持有的批次本身。两条分工明确，互不顶替。
     *
     * 为什么这次能做强断言：`SourceBalanceRules.balancedSample` 的**第一轮
     * 是确定性的**——只要 batchSize ≥ 来源桶数，它保证每个来源至少出现一张
     * （按平方根权重抽桶顺序，每桶先贡献 1 张）。而 `uniformSample` 是纯
     * 均匀随机，面对"3 张截图 vs 30 张相机"的悬殊库，6 张里几乎抽不到截图。
     *
     * 所以判据是**来源覆盖数**这个可复现的量：
     * - 均衡策略：批次必须覆盖**全部**来源桶（这里是 2 个：Screenshots、Camera）；
     * - 原随机策略：在小桶只有 3/33 的情况下，多轮重复抽样必须出现
     *   "覆盖不到全部桶"的批次——否则说明它其实也在做均衡，两种策略没区别。
     *
     * 关键：抽样通过**真实引擎** `RecommendationEngine.getRecommendedBatch`
     * 取，策略来自设置页真实点击写入的偏好，因此验证的是端到端分派。
     */
    @Test
    fun nextBatchSamplingActuallyReflectsChosenStrategy() = kotlinx.coroutines.runBlocking {
        // 造明显不均衡的库：小来源 3 张、大来源 30 张（2 个来源桶）
        repeat(3) { insertPhoto("DCIM/Screenshots") }
        repeat(30) { insertPhoto("DCIM/Camera") }

        val engine = com.tabula.v3.data.repository.RecommendationEngine.getInstance(ctx)
        val images = com.tabula.v3.data.repository.LocalImageRepository(ctx)
            .loadAllData().allImages
        assertTrue("测试库应有足够照片", images.size >= 20)

        // 真实设置页：改为「均衡来源」
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

        // 清掉冷却，让"下一批"由策略本身决定（不是被冷却挤空）
        fun clearCooldown() {
            AppPreferences(ctx).clearAllPickRecords()
        }

        // ---- 均衡策略：必须覆盖全部来源桶（确定性保证）----
        val balancedSources = mutableListOf<Int>()
        repeat(5) {
            clearCooldown()
            val batch = engine.getRecommendedBatch(images, 6)
            assertEquals("均衡批次应出满 6 张", 6, batch.size)
            balancedSources += batch.map {
                com.tabula.v3.data.balance.SourceBalanceRules.sourceKeyOf(it)
            }.toSet().size
        }
        assertTrue(
            "均衡策略下每批都必须覆盖全部来源桶（实际覆盖数：$balancedSources）",
            balancedSources.all { it == 2 }
        )

        // ---- 原随机策略：多轮里必须出现覆盖不全的批次 ----
        tap("漫步来源策略")
        waitFor("原随机")
        tap("原随机")
        assertEquals(
            "切回原随机必须持久化",
            WalkSamplingStrategy.UNIFORM,
            AppPreferences(ctx).walkSamplingStrategy
        )

        val uniformSources = mutableListOf<Int>()
        repeat(20) {
            clearCooldown()
            val batch = engine.getRecommendedBatch(images, 6)
            assertEquals("随机批次应出满 6 张", 6, batch.size)
            uniformSources += batch.map {
                com.tabula.v3.data.balance.SourceBalanceRules.sourceKeyOf(it)
            }.toSet().size
        }
        assertTrue(
            "原随机策略下必须出现覆盖不全的批次，否则两种策略没区别" +
                "（实际覆盖数：$uniformSources）",
            uniformSources.any { it < 2 }
        )

        // ---- 端到端对照：同一份库、同一个引擎，两种策略给出不同分布 ----
        assertTrue(
            "均衡的平均来源覆盖必须高于原随机" +
                "（均衡=${balancedSources.average()} 随机=${uniformSources.average()}）",
            balancedSources.average() > uniformSources.average()
        )
    }

    /**
     * 卡片页真实换批：设置页切策略后回卡片页，**页面当前持有的批次**必须是
     * 新策略下重取的那一批。
     *
     * ## 判定方式（#89 审计要求：不能另调引擎顶替）
     *
     * 直接读**生产代码自己的**批次日志：`DeckScreen.logBatchRows` 在每次
     * `currentBatch = ...` 时打一行
     *
     * ```
     * currentBatch<-[batch-site-4]: uri1:date1,uri2:date2,...
     * ```
     *
     * 其中 `batch-site-4` 就是"推荐模式/策略变化 → 重取批"这条路径
     * （`DeckScreen.kt` 的 `LaunchedEffect(recommendModeRefreshKey)`）。
     *
     * 断言链（全部落在页面侧数据上）：
     * 1. 切策略**之前**的批次行 → 记下 id 集合 A；
     * 2. 切策略**之后**出现新的 `batch-site-4` 行 → 记下 id 集合 B；
     * 3. `A != B`（页面真的换了批，而不是原地不动）；
     * 4. B 非空，且 B 的每个 id 都是本测试造的照片；
     * 5. B 覆盖两个来源目录（Screenshots + Camera）——这是"均衡策略生效"
     *    在**页面批次**上的直接证据，不需要再调引擎。
     *
     * 边界用 logcat 行序而非时间戳（同 `B2MediaAccessRevisionDeviceTest` 的
     * 做法）：动作前先写一条边界标记，只认标记之后的批次行，避免把上一段
     * 遗留行当成结果。
     */
    @Test
    fun deckReloadsBatchAfterStrategySwitch() = kotlinx.coroutines.runBlocking {
        repeat(3) { insertPhoto("DCIM/Screenshots") }
        repeat(30) { insertPhoto("DCIM/Camera") }

        startApp()
        val beforeTexts = screenTexts()
        assertTrue(
            "切换策略前卡片页应已在浏览批次（屏幕：$beforeTexts）",
            beforeTexts.any { it.endsWith("张待整理") }
        )

        // 前置：切策略前页面已持有一批（batch-site-4 的首次触发或其它 site 均可，
        // 这里只要求"有一批"，具体 site 不重要——重点是切换之后必须出现新的）
        val batchBefore = awaitBatchLine(timeoutMs = 15000)
        assertNotNull(
            "切换策略前卡片页必须已经持有批次（否则无法证明'换了'）",
            batchBefore
        )
        val idsBefore = parseBatchIds(batchBefore!!)

        // 写边界：只认这之后出现的批次行
        val boundary = "ws-boundary:" + SystemClock.uptimeMillis()
        Log.i(TAG, boundary)
        SystemClock.sleep(300)

        tap("设置")
        waitFor("漫步来源策略")
        tap("漫步来源策略")
        waitFor("均衡来源")
        tap("均衡来源")
        assertEquals(
            WalkSamplingStrategy.SOURCE_BALANCED,
            AppPreferences(ctx).walkSamplingStrategy
        )

        tap("返回")
        waitFor("回收站", timeout = 25000)

        // 页面侧证据：边界之后必须出现一条新的 batch-site-4 行
        val batchAfter = awaitBatchLineAfterBoundary(boundary, timeoutMs = 25000)
        assertNotNull(
            "切策略回到卡片页后，页面必须重新取批（边界之后没有 batch-site-4 行）",
            batchAfter
        )
        val idsAfter = parseBatchIds(batchAfter!!)

        // 1. 页面真的换了批
        assertNotEquals(
            "页面批次必须变化，不能停在旧批次（before=$idsBefore after=$idsAfter）",
            idsBefore,
            idsAfter
        )

        // 2. 新批次非空且全是本测试造的照片
        assertTrue("新批次不能为空（$batchAfter）", idsAfter.isNotEmpty())
        val seededNames = inserted.map { uri ->
            ctx.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.filterNotNull().toSet()
        assertTrue(
            "新批次的 id 必须都是本测试造的照片（after=$idsAfter seeded=$seededNames）",
            idsAfter.all { id -> seededNames.any { it.contains(id) } }
        )

        // 3. 均衡策略在**页面批次**上生效：覆盖两个来源目录
        val sourceDirs = idsAfter.mapNotNull { id ->
            seededNames.firstOrNull { it.contains(id) }?.let { name ->
                // 夹具文件名里不带目录，改用 MediaStore 查 RELATIVE_PATH
                ctx.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(name), null
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }
        }.toSet()
        assertEquals(
            "均衡策略下页面持有的批次必须覆盖两个来源目录（实际：$sourceDirs）",
            2,
            sourceDirs.size
        )
    }

    // ==================== 页面批次日志（生产 seam） ====================

    /** 生产批次重取日志锚（DeckScreen.logBatchRows；batch-site-4 = 推荐模式刷新）。 */
    private val batchAnchor = "currentBatch<-[batch-site-4]:"

    /** logcat 里最新一条 batch-site-4 行。 */
    private fun awaitBatchLine(timeoutMs: Long): String? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            latestBatchLine()?.let { return it }
            SystemClock.sleep(150)
        }
        return null
    }

    /** 只认边界之后的 batch-site-4 行（fail-closed：边界缺失则返回 null）。 */
    private fun awaitBatchLineAfterBoundary(boundary: String, timeoutMs: Long): String? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            latestBatchLineAfter(boundary)?.let { return it }
            SystemClock.sleep(150)
        }
        return null
    }

    private fun deckLogLines(): List<String> =
        shell("logcat -d -s DeckScreen $TAG").lineSequence().toList()

    private fun latestBatchLine(): String? =
        deckLogLines().lastOrNull { it.contains(batchAnchor) }

    private fun latestBatchLineAfter(boundary: String): String? {
        val lines = deckLogLines()
        // 边界行由 Log.i(TAG, "ws-boundary:<id>") 写入，同时出现在 TAG 过滤里
        val markerIdx = lines.indexOfLast { it.contains(boundary) }
        if (markerIdx < 0) return null
        return lines.subList(markerIdx + 1, lines.size)
            .lastOrNull { it.contains(batchAnchor) }
    }

    /** `uri:dateModified,...` → id 集合（uri 末段数字）。 */
    private fun parseBatchIds(line: String): Set<String> =
        line.substringAfter(batchAnchor, "")
            .split(",")
            .mapNotNull { entry ->
                val id = entry.trim().substringBeforeLast(":").substringAfterLast("/", "")
                if (id.matches(Regex("\\d+"))) id else null
            }
            .toSet()

    private fun screenTexts(): Set<String> {
        val root = ins.uiAutomation.rootInActiveWindow ?: return emptySet()
        val out = mutableSetOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 24) return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }
}
