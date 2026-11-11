package com.tabula.v3

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.SwipeStyle
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.matchesSameMediaIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * T06 设备级生命周期验收（2026-11-12，基线 combination/2026-11-10 caafb96，含 #61/#62）。
 *
 * 按已定稿诊断（coord/t06-device-gap-diagnosis.md）：真缺口 = 场景 1+2 的设备级接线验收。
 * 覆盖口径：本套件只宣称设备级接线（真实手势注入 → onDragCancel 接线 → 预载恢复 →
 * 软删除 → 回收站恢复），不宣称 reducer 级覆盖（DrawCardStateLifecycleTest）。
 *
 * 场景 1（两模式各一，case 1/2）：合成媒体 → deck 真实 swipe 启动 → 手势中途注入
 * ACTION_CANCEL → 断言 CardStackDebug `dragCancel <mode>` 日志（DEBUG 构建生效）→
 * 断言 PreloadingManager `pause`（拖动开始，T06 新增 DEBUG 门日志）与 `resume`
 * （#61 onDragCancel 接线，T06 新增 DEBUG 门日志；断言 resume 时间戳 ≤ dragCancel，
 * 即恢复发生在取消处理内而非下次 dragEnd）→ 取消语义不变量（夹具行数/回收站空/
 * deck 可操作）→ 随后真实 swipe 删除 → 观察回收站记录反推被删卡名（见下）→
 * MediaStore 真值（软删除契约：行保留 + 夹具目录行数恒定）→ 恢复入口验证
 * （回收站 UI 恢复 → 记录清除 + 媒体行保留 + deck 可操作）。
 *
 * 场景 2（case 3）：SHUFFLE 模式完成一轮 cancel 循环 + 一轮非破坏 swipe → Settings
 * UI 驱动切换"轻掠模式"（deck 顶栏"设置"→"显示设置"子页→"切换样式"区"轻掠模式"→
 * 逐级返回；离页/返回经 BackHandler+重组，会话内生效——诊断口径）→ DRAW 模式重复
 * 场景 1 循环（`dragCancel draw` 日志即"切换已在会话内生效"的既定信号）→ 返回验证。
 *
 * 实现选择（登记，含轮 1 实证修正）：
 * - ACTION_CANCEL 注入：UiAutomation.injectInputEvent 显式 MotionEvent 序列
 *   DOWN → MOVE×6（共 120px，越过 touch slop 使拖动已启动）→ ACTION_CANCEL。
 *   备选"gesture 分段取消"不可达：shell input 不支持取消语义。
 * - 真实 swipe：shell `input swipe`（真实系统手势通道，UP 正常送达）。
 * - 被删卡名采用**观察式**（轮 1 修正，run 43067990321 API37 实证）：deck 推荐批
 *   序不定（实证初始批 [129,128,130] 而交互当前卡=idx0=129），"最大 displayName
 *   节点=交互当前卡"启发式不成立——手势注入以最大节点中心为原点（命中卡片区，
 *   交互卡承接手势），删除后以**回收站记录反推被删名**（恰好一条夹具记录），
 *   恢复与断言一律使用观察名。
 * - deck 移除真值采用状态计数递减（"N 张待整理" N→N-1）而非节点消失（轮 1
 *   API37 实证：被删卡节点在删除动画后残留显示，节点消失轮询不可靠）。
 * - 场景 1 的 DRAW 模式（case 2）以 prefs 预置启动（#57 口径的注入路径）；会话内
 *   切换本身由 case 3 专测，两种切换途径均有覆盖。
 * - MediaStore 真值：合成媒体行存在性 + 按 relative_path 圈定的行数计数在整个
 *   软删除-恢复周期不变（生产契约：deck swipe 删除 = 回收站记录，物理删除仅经
 *   回收站清空/对账通道；若生产改为 swipe 即物理删除，此断言即契约变更警报）。
 * - 恢复后镜像是否重新进入当前 deck 批次不在本套件断言范围：deck 批次同步
 *   （DeckScreen C-1 syncRowsToSnapshot）只剪不增，恢复行经下一批派生进入——
 *   本套件断言到"回收站记录清除 + 媒体行保留 + deck 可操作"为止。
 * - 断言纪律：全部等待为有截止时间轮询；失败留存截图 + 树 dump + logcat 切片
 *   到 cloud-ui-failures/（文件名无 `|`）；夹具全程带 shell 查询哨兵
 *   （CloudFixtureCleanup，@After 校验无关哨兵未被误删）。
 * - 预期 case 数：3（probe 登记 expected_tests=3）。
 *
 * 时序要点：deck swipe 删除经撤销 Snackbar 延迟提交（UndoSnackbar 3000ms 超时 =
 * 确认删除 → onRemove → 回收站记录写入）——回收站记录轮询先等 Snackbar 提交窗口。
 */
@RunWith(AndroidJUnit4::class)
class CardLifecycleDeviceTest {
    private val TAG = "CardLifecycle"
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private var activity: android.app.Activity? = null
    private val photos = mutableListOf<ImageFile>()
    private val fixtureRows = mutableListOf<CloudFixtureCleanup.Fixture>()
    private val fixtures get() = CloudFixtureCleanup(ctx, ins)
    private val trash get() = RecycleBinManager.getInstance(ctx)
    private val fixturePath = "Pictures/CardLifecycleFixtures/"
    private val deckStatusMarker = "张待整理"

    // ==================== 基础设施（沿用既有设备探针夹具模式） ====================

    private fun shell(command: String): String = ins.uiAutomation.executeShellCommand(command).use { p ->
        java.io.FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun evidenceDir(): File = File(
        ctx.getExternalFilesDir(null) ?: ctx.filesDir, "cloud-ui-failures"
    ).apply { mkdirs() }

    private fun saveEvidence(tag: String) {
        try {
            val dir = evidenceDir()
            ins.uiAutomation.takeScreenshot()?.let { bmp ->
                try {
                    dir.resolve("card-lifecycle-$tag.png").outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bmp.recycle() }
            }
            val sb = StringBuilder()
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 25) return
                if (node.isVisibleToUser) {
                    val rect = Rect().also { node.getBoundsInScreen(it) }
                    sb.append(" ".repeat(depth)).append(node.className)
                        .append(" text=").append(node.text)
                        .append(" desc=").append(node.contentDescription)
                        .append(" bounds=").append(rect.toShortString()).append('\n')
                }
                for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
            }
            walk(ins.uiAutomation.rootInActiveWindow, 0)
            dir.resolve("card-lifecycle-$tag-tree.txt").writeText(sb.toString())
            val logs = StringBuilder()
            dumpLogcat().joinTo(logs, separator = "\n") { "${it.ts} ${it.tag}: ${it.msg}" }
            dir.resolve("card-lifecycle-$tag-logcat.txt").writeText(logs.toString())
            Log.i(TAG, "evidence saved: $tag")
        } catch (e: Exception) {
            Log.w(TAG, "evidence save failed: $tag", e)
        }
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString()?.contains(label) == true ||
                    node.contentDescription?.toString()?.contains(label) == true)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }

    /** 精确匹配优先（避免 contains 误命中"恢复所选"等扩展节点），失配回退 contains。 */
    private fun findExact(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString() == label ||
                    node.contentDescription?.toString() == label)) return node
        for (i in 0 until node.childCount) findExact(node.getChild(i), label)?.let { return it }
        return null
    }

    private fun findAll(node: AccessibilityNodeInfo?, label: String, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isVisibleToUser && (node.text?.toString()?.contains(label) == true ||
                    node.contentDescription?.toString()?.contains(label) == true)) out += node
        for (i in 0 until node.childCount) findAll(node.getChild(i), label, out)
    }

    /** 最大面积匹配（仅作手势原点/诊断用途——交互当前卡以日志与记录观察为准，见 KDoc）。 */
    private fun largestNodeOf(label: String, timeout: Long = 25000): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            val matches = mutableListOf<AccessibilityNodeInfo>()
            findAll(ins.uiAutomation.rootInActiveWindow, label, matches)
            var best: AccessibilityNodeInfo? = null
            var bestArea = -1L
            for (m in matches) {
                val rect = Rect().also { m.getBoundsInScreen(it) }
                val area = rect.width().toLong() * rect.height().toLong()
                if (area > bestArea) { bestArea = area; best = m }
            }
            best?.let { return it }
            SystemClock.sleep(120)
        }
        saveEvidence("missing-largest-$label")
        error("UI element not found: $label")
    }

    private fun waitForNode(label: String, timeout: Long = 20000): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            findExact(ins.uiAutomation.rootInActiveWindow, label)?.let { return it }
            find(ins.uiAutomation.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(120)
        }
        saveEvidence("missing-$label")
        error("UI element not found: $label")
    }

    /** 有截止时间的复合状态等待（含失败留证）。 */
    private fun waitForState(description: String, timeoutMs: Long = 15000, poll: (AccessibilityNodeInfo) -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            val root = ins.uiAutomation.rootInActiveWindow
            if (root != null && poll(root)) return
            SystemClock.sleep(150)
        }
        saveEvidence("state-timeout-$description")
        error("state not reached: $description")
    }

    private fun tapNode(node: AccessibilityNodeInfo): String {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "node ACTION_CLICK"
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 6) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "ancestor ACTION_CLICK (hops=$hops)"
            }
            parent = parent.parent
            hops++
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        shell("input tap ${rect.centerX()} ${rect.centerY()}")
        return "coordinate tap fallback"
    }

    private fun scrollToNode(label: String, maxSwipes: Int = 10): AccessibilityNodeInfo {
        val w = ctx.resources.displayMetrics.widthPixels
        val h = ctx.resources.displayMetrics.heightPixels
        repeat(maxSwipes) { attempt ->
            findExact(ins.uiAutomation.rootInActiveWindow, label)?.let { return it }
            find(ins.uiAutomation.rootInActiveWindow, label)?.let { return it }
            shell("input swipe ${w / 2} ${(h * 0.7).toInt()} ${w / 2} ${(h * 0.3).toInt()} 300")
            SystemClock.sleep(500)
            Log.i(TAG, "scroll attempt=$attempt for $label")
        }
        saveEvidence("missing-after-scroll-$label")
        error("UI element not found after scrolling: $label")
    }

    /**
     * deck 是否为**激活屏**（非 overlay 前台）。MainActivity 的 overlay 结构下
     * （RECYCLE_BIN/SETTINGS 以 deck 为背景层），背景 deck 的状态标记在树中恒可见
     * ——轮 3 实证：仅凭标记会把回收站前台误判为已回 deck。故需叠加"无 overlay
     * 特征节点"负向条件（各 overlay 屏的独有文本）。
     */
    private val overlayMarkers = listOf(
        "回收站空空如也", "暂无被删除的照片", "项待处理", // 回收站
        "主题模式", "切换样式", "每组数量"                // 设置 / 显示设置子页
    )

    private fun deckActive(root: AccessibilityNodeInfo?): Boolean =
        root != null && find(root, deckStatusMarker) != null &&
            overlayMarkers.none { find(root, it) != null }

    /**
     * 逐级返回直至 deck 为激活屏。每步返回后有界等待重组完成再决定下一步（轮 2
     * 实证：固定短睡导致双击返回退出应用至桌面）；若已逸出应用（桌面）则 am start 重入。
     */
    private fun backUntilDeck(maxSteps: Int = 5) {
        repeat(maxSteps) { step ->
            val root = ins.uiAutomation.rootInActiveWindow
            if (deckActive(root)) return
            if (root == null || root.packageName != ctx.packageName) {
                Log.i(TAG, "back step=$step: app not foreground, relaunching")
                shell("am start -n ${ctx.packageName}/com.tabula.v3.MainActivity")
                waitForState("relaunch-deck", timeoutMs = 15000) { r -> deckActive(r) }
                return
            }
            val backNode = findExact(root, "返回")
            if (backNode != null) tapNode(backNode) else shell("input keyevent KEYCODE_BACK")
            Log.i(TAG, "back step=$step pressed")
            val settleEnd = SystemClock.uptimeMillis() + 3000
            while (SystemClock.uptimeMillis() < settleEnd) {
                if (deckActive(ins.uiAutomation.rootInActiveWindow)) return
                SystemClock.sleep(200)
            }
        }
        waitForState("back-on-deck", timeoutMs = 12000) { root -> deckActive(root) }
    }

    /** deck 状态计数（"N 张待整理"）——deck 移除的真值信号（节点消失断言的替代，见 KDoc）。 */
    private fun scanDeckPendingCount(): Int? {
        val node = find(ins.uiAutomation.rootInActiveWindow, deckStatusMarker)
        return node?.text?.toString()
            ?.let { Regex("(\\d+)\\s*张待整理").find(it)?.groupValues?.get(1) }
            ?.toInt()
    }

    private fun readDeckPendingCount(timeoutMs: Long = 10000): Int {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            scanDeckPendingCount()?.let { return it }
            SystemClock.sleep(150)
        }
        saveEvidence("deck-count-unreadable")
        error("deck pending count not readable")
    }

    // ==================== 合成媒体夹具（shell 绑定所有权 + 清单 + 哨兵） ====================

    private fun primaryItem(uri: Uri): Uri = ContentUris.withAppendedId(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentUris.parseId(uri)
    )

    private fun present(uri: Uri): Boolean = requireNotNull(
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
    ) { "MediaStore query returned null: $uri" }.use { it.moveToFirst() }

    /** 按 relative_path 圈定的行数真值：软删除-恢复全周期必须恒定（生产契约锚点）。 */
    private fun fixtureCount(): Int = requireNotNull(
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(fixturePath),
            null
        )
    ) { "MediaStore fixture count query returned null" }.use { it.count }

    private fun photo(register: Boolean = true): ImageFile {
        val name = "t6-" + UUID.randomUUID() + ".jpg"
        val uri = requireNotNull(ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, fixturePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val image = ImageFile(ContentUris.parseId(uri), uri, name, System.currentTimeMillis(), 1L, 32, 32, "CardLifecycleFixtures")
        if (register) {
            photos += image
            fixtureRows += fixtures.fixture(uri)
        }
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(name.hashCode() or -0x1000000)
            ctx.contentResolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally { bitmap.recycle() }
        check(ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        return image
    }

    private fun fixtureRecords(): List<ImageFile> = runBlocking {
        trash.loadRecycleBin().filter { record -> photos.any { it.matchesSameMediaIdentity(record) } }
    }

    // ==================== logcat 信号采集（CardStackDebug / PreloadingManager） ====================

    private data class LogLine(val ts: Double, val tag: String, val msg: String)
    private val logLineRegex = Regex("^(\\d+\\.\\d+)\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+([\\w.]+):\\s?(.*)$")

    private fun dumpLogcat(): List<LogLine> = shell(
        "logcat -d -v epoch 'CardStackDebug:V' 'PreloadingManager:V' '*:S'"
    ).lines().mapNotNull { line ->
        logLineRegex.find(line.trim())?.let { m ->
            LogLine(m.groupValues[1].toDouble(), m.groupValues[2], m.groupValues[3])
        }
    }

    /** epoch 秒（与 logcat -v epoch 同一墙钟），留 1s 余量吸收时钟粒度。 */
    private fun epochNow(): Double = System.currentTimeMillis() / 1000.0 - 1.0

    private fun describeSince(since: Double): String = dumpLogcat()
        .filter { it.ts >= since }
        .joinToString("\n") { "${it.ts} ${it.tag}: ${it.msg}" }
        .take(3000)

    /** 有截止时间的日志信号轮询；超时留证（截图+树+logcat 切片）并给出已观察信号。 */
    private fun waitForLog(tag: String, contains: String, since: Double, timeoutMs: Long = 20000): LogLine {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            dumpLogcat().firstOrNull { it.ts >= since && it.tag == tag && it.msg.contains(contains) }
                ?.let { return it }
            SystemClock.sleep(200)
        }
        saveEvidence("log-miss-$tag-$contains")
        error("log not observed: [$tag] contains=\"$contains\" since=$since; observed:\n${describeSince(since)}")
    }

    // ==================== 手势注入（ACTION_CANCEL 选择已登记） ====================

    /**
     * 拖动启动后注入 ACTION_CANCEL：DOWN → MOVE×steps（越过 touch slop，拖动已启动）
     * → ACTION_CANCEL。原点取最大夹具节点中心（命中卡片区，交互卡承接手势——
     * 交互卡身份不预设，见 KDoc 轮 1 修正）。
     */
    private fun injectDragThenCancel(cx: Float, cy: Float, steps: Int = 6, stepPx: Float = 20f) {
        val ua = ins.uiAutomation
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, x: Float, y: Float): Boolean =
            ua.injectInputEvent(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0), true)
        assertTrue("DOWN injection rejected", inject(MotionEvent.ACTION_DOWN, cx, cy))
        var y = cy
        repeat(steps) {
            y -= stepPx
            assertTrue("MOVE injection rejected at y=$y", inject(MotionEvent.ACTION_MOVE, cx, y))
            SystemClock.sleep(60)
        }
        assertTrue("CANCEL injection rejected", inject(MotionEvent.ACTION_CANCEL, cx, y))
        SystemClock.sleep(200)
    }

    /** 真实系统手势（shell input swipe）：删除用慢速长距上滑（位移超阈值，速度不必达阈）。 */
    private fun realSwipeUp(cx: Float, cy: Float, distance: Float) {
        val target = (cy - distance).coerceAtLeast(60f)
        shell("input swipe ${cx.toInt()} ${cy.toInt()} ${cx.toInt()} ${target.toInt()} 450")
        SystemClock.sleep(250)
    }

    private fun realSwipeHorizontal(cx: Float, cy: Float, dx: Float) {
        val w = ctx.resources.displayMetrics.widthPixels
        val target = (cx + dx).coerceIn(40f, (w - 40).toFloat())
        shell("input swipe ${cx.toInt()} ${cy.toInt()} ${target.toInt()} ${cy.toInt()} 300")
        SystemClock.sleep(250)
    }

    private fun cardCenter(node: AccessibilityNodeInfo): Pair<Float, Float> {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return rect.exactCenterX() to rect.exactCenterY()
    }

    /**
     * 非破坏水平 swipe（SHUFFLE 循环导航 / DRAW 收牌），信号驱动原点：逐候选尝试
     * 直至 dragEnd 日志出现。dx<0（左滑）在 DRAW 模式为收牌——任何位置都不进入
     * 完成页；SHUFFLE 循环切换无完成页风险。
     */
    private fun horizontalSwipeUntilDragEnd(modeLogFragment: String, dx: Float) {
        for (candidate in gestureCandidates()) {
            val mark = epochNow()
            val (cx, cy) = cardCenter(candidate)
            realSwipeHorizontal(cx, cy, dx)
            val windowEnd = SystemClock.uptimeMillis() + 6000
            while (SystemClock.uptimeMillis() < windowEnd) {
                if (dumpLogcat().any { it.ts >= mark && it.tag == "CardStackDebug" && it.msg.contains("dragEnd $modeLogFragment") }) {
                    Log.i(TAG, "horizontal swipe ($modeLogFragment) engaged the interactive card")
                    return
                }
                SystemClock.sleep(250)
            }
            Log.i(TAG, "horizontal swipe ($modeLogFragment) attempt did not engage, trying next origin")
        }
        saveEvidence("dragend-miss-horizontal-$modeLogFragment")
        error("no candidate origin produced a dragEnd $modeLogFragment log")
    }

    /** 任一夹具名可见即 deck 就绪（交互卡身份不预设，以日志/记录信号反推）。 */
    private fun waitForDeckReady(names: List<String>) {
        waitForState("deck-ready", timeoutMs = 25000) { root ->
            find(root, deckStatusMarker) != null && names.any { find(root, it) != null }
        }
    }

    /**
     * 手势候选原点：全部可见夹具节点按面积降序（中央/前台卡优先，侧缘窄条殿后）。
     * 交互卡身份不可由节点几何可靠推断（轮 1/轮 2 实证：推荐序漂移 + 摸牌样式
     * 侧缘窄条），由调用方以"逐候选尝试直至出现预期日志信号"确定。
     */
    private fun gestureCandidates(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<Pair<AccessibilityNodeInfo, Long>>()
        for (photo in photos) {
            val matches = mutableListOf<AccessibilityNodeInfo>()
            findAll(ins.uiAutomation.rootInActiveWindow, photo.displayName, matches)
            for (m in matches) {
                val rect = Rect().also { m.getBoundsInScreen(it) }
                out += m to (rect.width().toLong() * rect.height().toLong())
            }
        }
        if (out.isEmpty()) {
            saveEvidence("no-gesture-origin")
            error("No fixture card node visible for gesture origin")
        }
        return out.sortedByDescending { it.second }.map { it.first }
    }

    // ==================== 阶段实现（场景 1 循环 + 场景 2 切换） ====================

    /**
     * 场景 1 前半：拖动中途 ACTION_CANCEL → 断言 dragCancel <mode> 日志、pause 日志
     * （拖动开始）与 resume 日志（#61 onDragCancel 接线），并断言 resume 时间戳不晚于
     * dragCancel（恢复发生在取消处理内）；取消语义不变量：夹具行/行数不变、回收站
     * 无夹具记录、deck 可操作。
     *
     * 原点选择为信号驱动（轮 2 修正）：逐候选（面积降序）注入取消序列，以 pause 日志
     * 出现确认"拖动已启动"，未命中换下一候选——对未启动拖动的候选无副作用。
     */
    private fun phaseCancel(modeLogFragment: String) {
        var pauseLog: LogLine? = null
        var resumeLog: LogLine? = null
        var cancelLog: LogLine? = null
        for (candidate in gestureCandidates()) {
            val mark = epochNow()
            val (cx, cy) = cardCenter(candidate)
            injectDragThenCancel(cx, cy)
            val pause = dumpLogcat().firstOrNull { it.ts >= mark && it.tag == "PreloadingManager" && it.msg.contains("pause") }
            if (pause != null) {
                Log.i(TAG, "cancel attempt engaged the interactive card (pause seen at ${pause.ts})")
                // 三元组都取自本候选窗口（mark 过滤），跨阶段日志不会误配
                resumeLog = waitForLog("PreloadingManager", "resume", mark)
                cancelLog = waitForLog("CardStackDebug", "dragCancel $modeLogFragment", mark)
                pauseLog = pause
                break
            }
            Log.i(TAG, "cancel attempt did not start a drag, trying next origin")
        }
        val pause = requireNotNull(pauseLog) { "no candidate origin started a drag (pause log never observed)" }
        val resume = requireNotNull(resumeLog)
        val cancel = requireNotNull(cancelLog)
        assertTrue(
            "resume(${resume.ts}) 必须不晚于 dragCancel(${cancel.ts})" +
                "——恢复应发生在取消处理内（#61 接线），而非后续 dragEnd",
            resume.ts <= cancel.ts
        )
        assertTrue("pause 必须先于 resume（拖动开始 → 取消恢复）", pause.ts <= resume.ts)
        Log.i(TAG, "cancel phase logs: pause=${pause.ts} resume=${resume.ts} cancel=${cancel.ts}")
        // 取消语义不变量：无删除发生
        photos.forEach { assertTrue("取消不得影响媒体行: ${it.displayName}", present(primaryItem(it.uri))) }
        assertEquals("取消不得改变夹具行数", photos.size, fixtureCount())
        assertTrue("取消不得写入回收站记录", fixtureRecords().isEmpty())
        waitForState("deck-operable-after-cancel", timeoutMs = 8000) { root -> deckActive(root) }
    }

    /**
     * 场景 1 中段：真实 swipe 上滑删除 → dragEnd <mode> 日志 + pause/resume（drag end
     * 接线）→ 撤销 Snackbar 提交窗口（3000ms）→ **观察回收站记录反推被删卡名**
     * （恰好一条夹具记录；交互卡身份不预设，见 KDoc）→ 状态计数递减 + MediaStore
     * 行数不变（软删除契约）。返回观察到的被删卡名。
     *
     * 原点选择为信号驱动（轮 2 修正）：逐候选（面积降序）真实上滑，以 dragEnd 日志
     * 出现确认"交互卡承接了手势"；600px 位移恒超删除阈值，dragEnd 即必然进入删除
     * 路径——不会产生"已启动但未删除"的中间态，重试对未命中候选无副作用。
     */
    private fun phaseSwipeDelete(modeLogFragment: String): String {
        val countBefore = readDeckPendingCount()
        var engaged = false
        for (candidate in gestureCandidates()) {
            val mark = epochNow()
            val (cx, cy) = cardCenter(candidate)
            realSwipeUp(cx, cy, 600f)
            // 有界等待本候选窗口的 dragEnd（交互卡承接手势的信号）；pause 是 dragEnd
            // 的前提事件，命中后补断言
            val windowEnd = SystemClock.uptimeMillis() + 6000
            var dragEnd: LogLine? = null
            while (SystemClock.uptimeMillis() < windowEnd) {
                dragEnd = dumpLogcat().firstOrNull { it.ts >= mark && it.tag == "CardStackDebug" && it.msg.contains("dragEnd $modeLogFragment") }
                if (dragEnd != null) break
                SystemClock.sleep(250)
            }
            if (dragEnd != null) {
                Log.i(TAG, "delete attempt engaged the interactive card (dragEnd at ${dragEnd.ts})")
                waitForLog("PreloadingManager", "pause", mark)
                waitForLog("PreloadingManager", "resume", mark)
                engaged = true
                break
            }
            Log.i(TAG, "delete attempt did not reach the interactive card, trying next origin")
        }
        if (!engaged) saveEvidence("dragend-miss-delete-$modeLogFragment")
        // 撤销 Snackbar（3000ms 超时 = 确认删除）提交窗口后，回收站记录出现
        SystemClock.sleep(3600)
        val end = SystemClock.uptimeMillis() + 15000
        var records: List<ImageFile> = emptyList()
        while (SystemClock.uptimeMillis() < end) {
            records = fixtureRecords()
            if (records.isNotEmpty()) break
            SystemClock.sleep(300)
        }
        if (records.isEmpty()) saveEvidence("bin-record-missing")
        assertTrue("swipe 删除后回收站必须恰有一条夹具记录（软删除契约），实际 ${records.size}", records.size == 1)
        val deletedName = records.single().displayName
        Log.i(TAG, "observed deleted card: $deletedName")
        // deck 移除真值：状态计数递减（"N 张待整理"；节点消失不可靠——轮 1 实证残留）
        val countEnd = SystemClock.uptimeMillis() + 10000
        var countAfter = countBefore
        while (SystemClock.uptimeMillis() < countEnd) {
            scanDeckPendingCount()?.let { countAfter = it }
            if (countAfter == countBefore - 1) break
            SystemClock.sleep(200)
        }
        if (countAfter != countBefore - 1) saveEvidence("deck-count-not-decremented")
        assertEquals("swipe 删除必须使待整理计数递减", countBefore - 1, countAfter)
        // MediaStore 真值：软删除不物理删除，行数与行存在性均不变
        val deleted = photos.first { it.displayName == deletedName }
        assertTrue("软删除必须保留媒体行: $deletedName", present(primaryItem(deleted.uri)))
        assertEquals("软删除周期夹具行数必须恒定", photos.size, fixtureCount())
        return deletedName
    }

    /**
     * 场景 1 尾段：回收站 UI 恢复入口 → 条目打开 → 恢复 → 记录清除（数据真值 + 计数
     * 文本）+ 媒体行保留 + 返回 deck 可操作。
     */
    private fun phaseRestoreViaBin(deletedName: String) {
        tapNode(waitForNode("回收站"))
        waitForNode("项待处理")
        // 回收站页：条目可见 → 打开该条目页 → 恢复
        val nameNode = waitForNode(deletedName)
        Log.i(TAG, "bin item tap via ${tapNode(nameNode)}")
        SystemClock.sleep(800)
        val restoreNode = findExact(ins.uiAutomation.rootInActiveWindow, "恢复")
            ?: find(ins.uiAutomation.rootInActiveWindow, "恢复")
        if (restoreNode == null) {
            saveEvidence("restore-button-missing-$deletedName")
            error("恢复 button not found in recycle bin item page")
        }
        Log.i(TAG, "restore tap via ${tapNode(restoreNode)}")
        // 记录清除（RecycleBinManager 真值）+ 计数文本归零（回收站恰有一条 → 恢复后 0 项）
        val deleted = photos.first { it.displayName == deletedName }
        val end = SystemClock.uptimeMillis() + 15000
        var cleared = false
        while (SystemClock.uptimeMillis() < end) {
            val recordGone = runBlocking {
                trash.loadRecycleBin().none { it.matchesSameMediaIdentity(deleted) }
            }
            val root = ins.uiAutomation.rootInActiveWindow
            val countZero = root != null && (findExact(root, "0 项待处理") != null || find(root, "0 项待处理") != null)
            // 回收站空态无计数文本（"回收站空空如也"，轮 2 实证）——空态文本或条目
            // 消失任一成立即可与数据真值合判
            val emptyState = root != null && find(root, "回收站空空如也") != null
            val nameGone = root != null && find(root, deletedName) == null
            if (recordGone && (countZero || emptyState || nameGone)) { cleared = true; break }
            SystemClock.sleep(300)
        }
        if (!cleared) saveEvidence("restore-not-cleared-$deletedName")
        assertTrue("恢复后回收站记录必须清除: $deletedName", cleared)
        assertTrue("恢复周期必须保留媒体行: $deletedName", present(primaryItem(deleted.uri)))
        // 返回 deck 可操作
        backUntilDeck()
        assertEquals("恢复周期夹具行数必须恒定", photos.size, fixtureCount())
    }

    /** 完整场景 1 循环：cancel → swipe 删除（观察被删名）→ 回收站恢复。 */
    private fun fullLifecycleLoop(modeLogFragment: String) {
        val names = (1..3).map { photo().displayName }
        activity = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForDeckReady(names)
        phaseCancel(modeLogFragment)
        val deletedName = phaseSwipeDelete(modeLogFragment)
        phaseRestoreViaBin(deletedName)
    }

    // ==================== 测试用例 ====================

    @Before fun setup() {
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
        shell("logcat -c")
    }

    /** 场景 1（SHUFFLE 模式）：cancel → resume 接线 → swipe 删除 → 回收站恢复。 */
    @Test fun scenario1ShuffleCancelResumesPreloadDeleteRestoresViaBin() = runBlocking {
        AppPreferences(ctx).swipeStyle = SwipeStyle.SHUFFLE
        fullLifecycleLoop(modeLogFragment = "shuffle")
    }

    /**
     * 场景 1（DRAW 模式，prefs 预置口径——登记：会话内切换由场景 2 专测）：
     * 同一循环在 draw 分支上执行，dragCancel/dragEnd 日志自带 draw 模式串。
     */
    @Test fun scenario1DrawCancelResumesPreloadDeleteRestoresViaBin() = runBlocking {
        AppPreferences(ctx).swipeStyle = SwipeStyle.DRAW
        fullLifecycleLoop(modeLogFragment = "draw")
    }

    /**
     * 场景 2（模式切换）：SHUFFLE 完成 cancel 循环 + 一轮非破坏 swipe → Settings UI
     * 驱动切换轻掠模式（设置 → 显示设置子页 → 切换样式 → 轻掠模式 → 逐级返回；
     * 离页/返回经重组，会话内生效）→ DRAW 重复场景 1 循环（`dragCancel draw` 即
     * 切换生效信号）→ 返回验证（deck 可操作）。
     */
    @Test fun scenario2InSessionShuffleToDrawSwitchKeepsLifecycleOperable() = runBlocking {
        AppPreferences(ctx).swipeStyle = SwipeStyle.SHUFFLE
        val names = (1..3).map { photo().displayName }
        activity = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForState("deck-ready-scenario2", timeoutMs = 25000) { root ->
            find(root, deckStatusMarker) != null && names.any { find(root, it) != null }
        }

        // SHUFFLE：cancel 循环（同场景 1 前半）
        phaseCancel("shuffle")
        // SHUFFLE：一轮非破坏 swipe（右滑切牌，循环导航，无删除）
        horizontalSwipeUntilDragEnd("shuffle", dx = 420f)
        waitForState("deck-operable-after-shuffle-swipe", timeoutMs = 8000) { root -> deckActive(root) }

        // 离页：设置 → 显示设置子页 → 切换样式 → 轻掠模式 → 逐级返回（轮 1 修正：
        // "切换样式"区位于 DisplaySettingsScreen，主列表不含——run 43067990321 实证）
        tapNode(waitForNode("设置"))
        tapNode(scrollToNode("显示设置"))
        val drawOption = scrollToNode("轻掠模式")
        Log.i(TAG, "draw option tap via ${tapNode(drawOption)}")
        SystemClock.sleep(400)
        backUntilDeck()
        waitForState("back-on-deck-after-switch", timeoutMs = 12000) { root ->
            deckActive(root) && names.any { find(root, it) != null }
        }

        // DRAW 模式重复场景 1 循环（dragCancel draw 日志即切换生效信号）
        phaseCancel("draw")
        val deletedName = phaseSwipeDelete("draw")
        phaseRestoreViaBin(deletedName)

        // 返回验证：deck 可操作——非破坏 draw 左滑（收牌；末卡态亦不进入完成页）
        // 产生 dragEnd draw 日志
        horizontalSwipeUntilDragEnd("draw", dx = -420f)
        waitForState("deck-operable-final", timeoutMs = 8000) { root -> deckActive(root) }
    }

    @After fun cleanup() {
        if (activity != null) {
            try {
                ins.runOnMainSync { activity?.finish() }
                ins.waitForIdleSync()
            } catch (e: Exception) {
                Log.w(TAG, "activity finish issue", e)
            }
            activity = null
        }
        val errors = mutableListOf<Throwable>()
        fun attempt(block: () -> Unit) { try { block() } catch (e: Throwable) { errors += e } }
        attempt { runBlocking { trash.removeAllFromRecycleBin(photos) } }
        var sentinel: CloudFixtureCleanup.Fixture? = null
        attempt { sentinel = fixtures.fixture(photo(register = false).uri) }
        fixtureRows.forEach { f -> attempt { fixtures.removeFixture(f) } }
        sentinel?.let { f ->
            attempt { check(fixtures.queryFixture(f) && present(f.uri)) { "Unrelated sentinel was removed" } }
            attempt { fixtures.removeFixture(f) }
        }
        photos.clear()
        fixtureRows.clear()
        if (errors.isNotEmpty()) {
            val failure = AssertionError("Fixture cleanup failed (${errors.size} errors)")
            errors.forEach { failure.addSuppressed(it) }
            throw failure
        }
    }
}
