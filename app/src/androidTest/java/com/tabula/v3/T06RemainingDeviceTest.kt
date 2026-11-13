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
 * T06 剩余设备级场景验收（2026-11-14，基线 master f745036，含 #70/#75/#76/#74 全部内容）。
 *
 * 按用户指令三个剩余场景中**按可观测性选 a+c 组合**（场景 b"快速连续 swipe 触发预载取消
 * →重入"的设备级稳定观测信号经真实调用链评估不成立：夹具微图解码毫秒级完成，in-flight
 * 窗口无法由真实手势时序确定性命中；100ms 防抖窗口亦小于真实手势间隔。该场景的 reducer
 * 级语义已由 PreloadingManagerTest 确定性覆盖
 * （pauseWithinDebounceWindowHoldsEnqueueUntilResumeDeterministically 等），本套件只做
 * 设备级接线，与 #74 的覆盖口径分工一致）。
 *
 * 覆盖口径：本套件只宣称设备级接线验收（真实手势/真实 BACK/真实重组 → PreloadingManager
 * 实际请求行为），不宣称 reducer 级覆盖（PreloadingManagerTest）。
 *
 * 核心改进（对照 #74 审计结论"pause/resume 日志只能证明调用发生"）：本次全部恢复结论
 * 以**实际任务状态/行为结果**支撑——新增 PreloadingManager DEBUG 门日志（生产改动逐行
 * 登记，见下）后断言真实请求行为：
 * - `window gen=.. idx=<i> identities=[<i>=card_<uri>_<dm>,...]`：防抖体计算出的窗口身份
 *   快照（索引→身份映射）——同索引换批的"请求身份同步更新"直接信号。
 * - `enqueue index=<i> token=<t> identity=<完整身份>`：真实请求发出——"实际预载请求恢复"
 *   的行为级信号（恢复后有真实新请求，且身份即该槽位当前 identity）。
 * - `staleDispose index=<i> token=<t> identity=<旧身份>`：身份失配旧请求取消+Disposable
 *   释放（仅在确有释放时输出）。
 * - `finish index=<i> token=<t> success=<b> identity=<身份>`：仅活 token 的回写可见
 *   （token 失配/已释放的旧回调在守卫处静默丢弃，不产生日志）——"旧回调无法写入新批次"
 *   的可观测边界。
 * - `dispose active=<n> completed=<n>`：组合销毁时管理器整体释放——"旧 Disposable 释放"
 *   的生命周期级信号。
 *
 * 生产改动（全部 BuildConfig.DEBUG 门 + 既有 TAG=PreloadingManager，与 debugCardStackLog
 * 同模式，逐行登记）：PreloadingManager.kt 共 5 处——
 * 1. preloadAround 防抖体内 window 构建后：window 日志；
 * 2. stale 过滤后：staleDispose 日志（stale 非空才输出）；
 * 3. enqueue 内 imageLoader.enqueue 前：enqueue 日志；
 * 4. finish 守卫（active 存在 + token 匹配 + 未 disposed）之后：finish 日志；
 * 5. dispose 内 disposed=true 之后、清空之前：dispose 日志（记录 active/completed 计数）。
 * 不动 CardStack（既有 dragStart/dragEnd/dragCancel 日志已足够）；不动 app/src/test。
 *
 * ==================== case 1（场景 a，SHUFFLE）：同索引换批请求身份同步 ====================
 *
 * 3 张合成媒体 → deck（推荐批序不预设，以日志窗口身份为准）→ 真实 swipe 上滑删除当前卡
 * → genie onComplete → DeckScreen onRemove → currentBatch 缩容且 currentIndex 不变
 * （删除当前卡后同索引承接原下一张）→ LaunchedEffect(currentIndex, images) 重发
 * preloadAround → 断言：
 * - 初始窗口（preloadRange=1）恰含 2 张夹具身份、slot0=当前交互卡（idx0 即 currentImage）；
 * - 删除后新窗口 idx=0：slot0 身份=原 slot1 的卡（同索引、内容/身份不同——换批真值），
 *   被删卡身份消失；被删卡名以回收站记录反推（观察式，#74 口径）且必须等于初始 slot0；
 * - **实际预载请求恢复**：恢复后对"从未入窗的第三张"发出真实新请求（enqueue index=1 +
 *   完整身份=新卡 + finish success），且 resume(ts) ≤ enqueue(ts)（恢复先于实际请求）；
 * - **旧回调无法写入新批次**：全程 token-身份序列完整（每个 finish 的 token 必须有先行
 *   enqueue 且身份一致）；换批标记之后无被删身份的 enqueue 重发、无被删身份的 finish
 *   迟到回写（有界扫描）；
 * - deck 计数递减 + 回收站记录与被删名一致 + MediaStore 行/行数恒定（软删除契约，#74
 *   口径）——两次删除即"后续操作能力"（SHUFFLE）。
 *
 * ==================== case 2（场景 c，DRAW）：删除动画中 BACK 退出与返回恢复 ====================
 *
 * 3 张合成媒体（prefs 预置 DRAW，#57 注入路径口径）→ deck → 真实 swipe 上滑删除
 * （dragEnd draw + pause/dragEnd/resume 三元组，生产顺序同 #74）→ **genie 删除动画进行中
 * 注入 BACK**（deck 为应用主页，无 BackHandler 拦截（DeckScreen 仅 viewerState/isSelection
 * 挂 BackHandler），KEYCODE_BACK → Activity finish → 组合销毁）→ 断言：
 * - 应用离开前台（root 包名非本应用）；teardown `dispose` 日志出现（组合销毁释放管理器）
 *   且其后到重启前无任何 enqueue/window/staleDispose/finish（已释放管理器静默——
 *   disposed 守卫 + debounce 取消 + scope cancel）；
 * - **删除未提交**（时序收敛论证：BACK 在 dragEnd 观察后立即注入，genie 440ms；无论
 *   BACK 是否先于 genie onComplete——先至则组合销毁中断动画与回调，后至则 Snackbar
 *   3000ms 计时的 LaunchedEffect 随组合销毁取消、数据层删除仅发生于 Snackbar dismiss
 *   ——回收站必无记录、媒体行/行数不变）；
 * - 重启（am start）后：进程存活（pid 非空）+ crash 缓冲无本应用记录（无崩溃）+ deck
 *   计数=退出前计数（状态正确：未提交的删除不丢失任何卡）+ 夹具名可见；
 * - **实际预载请求恢复（全量重建）**：重启后新 PreloadingManager（dispose 已清空
 *   completed）对新窗口全量重发真实请求——window 含全部 3 身份 + 3 条 enqueue 逐卡
 *   断言；
 * - **后续操作能力（DRAW）**：再次真实 swipe 删除 → dragEnd draw → 计数递减 → 回收站
 *   恰 1 条记录 → 行数恒定。
 *
 * 断言纪律：全部等待为有截止时间轮询；失败留存截图 + 树 dump + logcat 切片到
 * cloud-ui-failures/；日志归属用"手势标记（注入前采样不回退）+ 单调游标"双重下界
 * （#74 P2-1 口径）；token-完整性按管理器实例分窗（重启后新实例 token 从 1 重计，
 * 跨实例混窗会假阳性）；夹具全程带 shell 查询哨兵（CloudFixtureCleanup，@After 校验
 * 无关哨兵未被误删）。
 *
 * 时序要点：deck swipe 删除经撤销 Snackbar 延迟提交（3000ms 超时=确认删除）——回收站
 * 记录轮询先等 Snackbar 提交窗口；deck 计数在 genie onComplete（onRemove）即递减，
 * 先于 Snackbar 提交。
 *
 * 预期 case 数：2（probe 登记 expected_tests=2）。
 */
@RunWith(AndroidJUnit4::class)
class T06RemainingDeviceTest {
    private val TAG = "T06Remaining"
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val photos = mutableListOf<ImageFile>()
    private val fixtureRows = mutableListOf<CloudFixtureCleanup.Fixture>()
    private val fixtures get() = CloudFixtureCleanup(ctx, ins)
    private val trash get() = RecycleBinManager.getInstance(ctx)
    private val fixturePath = "Pictures/T06RemainingFixtures/"
    private val deckStatusMarker = "张待整理"

    private var activity: android.app.Activity? = null

    // ==================== 基础设施（沿用既有设备探针夹具模式） ====================

    private fun shell(command: String): String = ins.uiAutomation.executeShellCommand(command).use { p ->
        java.io.FileInputStream(p.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun appPid(): String? = shell("pidof ${ctx.packageName}").trim().ifEmpty { null }

    private fun crashLogForApp(): String = shell("logcat -d -b crash -v epoch").let { full ->
        full.lines().filter { it.contains(ctx.packageName) || it.contains("FATAL EXCEPTION") }
            .joinToString("\n")
    }

    private fun evidenceDir(): File = File(
        ctx.getExternalFilesDir(null) ?: ctx.filesDir, "cloud-ui-failures"
    ).apply { mkdirs() }

    private fun saveEvidence(tag: String) {
        try {
            val dir = evidenceDir()
            ins.uiAutomation.takeScreenshot()?.let { bmp ->
                try {
                    dir.resolve("t06-remaining-$tag.png").outputStream().use {
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
            dir.resolve("t06-remaining-$tag-tree.txt").writeText(sb.toString())
            val logs = StringBuilder()
            dumpLogcat().joinTo(logs, separator = "\n") { "${it.ts} ${it.tag}: ${it.msg}" }
            dir.resolve("t06-remaining-$tag-logcat.txt").writeText(logs.toString())
            dir.resolve("t06-remaining-$tag-crash.txt").writeText(crashLogForApp())
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

    private fun findAll(node: AccessibilityNodeInfo?, label: String, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isVisibleToUser && (node.text?.toString()?.contains(label) == true ||
                    node.contentDescription?.toString()?.contains(label) == true)) out += node
        for (i in 0 until node.childCount) findAll(node.getChild(i), label, out)
    }

    /** 有截止时间的复合状态等待（含失败留证）。 */
    private fun waitForState(description: String, timeoutMs: Long = 15000, poll: (AccessibilityNodeInfo?) -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (poll(ins.uiAutomation.rootInActiveWindow)) return
            SystemClock.sleep(150)
        }
        saveEvidence("state-timeout-$description")
        error("state not reached: $description")
    }

    /** deck 状态计数（"N 张待整理"）——deck 移除的真值信号（#74 口径）。 */
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

    /** 等待 deck 计数到达期望值（有截止时间），失败留证并断言失败。 */
    private fun awaitDeckCount(expected: Int, context: String) {
        val end = SystemClock.uptimeMillis() + 12000
        var seen = -1
        while (SystemClock.uptimeMillis() < end) {
            scanDeckPendingCount()?.let { seen = it; if (it == expected) return }
            SystemClock.sleep(200)
        }
        saveEvidence("deck-count-$context-$seen")
        assertEquals("deck 待整理计数应到达 $expected（$context）", expected, seen)
    }

    /** 任一夹具名可见即 deck 就绪（交互卡身份不预设，以窗口日志/回收站记录反推）。 */
    private fun waitForDeckReady(names: List<String>) {
        waitForState("deck-ready", timeoutMs = 25000) { root ->
            root != null && find(root, deckStatusMarker) != null && names.any { find(root, it) != null }
        }
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
        val name = "t6r-" + UUID.randomUUID() + ".jpg"
        val uri = requireNotNull(ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, fixturePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val image = ImageFile(ContentUris.parseId(uri), uri, name, System.currentTimeMillis(), 1L, 32, 32, "T06RemainingFixtures")
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

    /** 手势标记：注入之前采样、不回退（#74 P2-1 口径）。 */
    private fun logMark(): Double = System.currentTimeMillis() / 1000.0

    /** 日志游标：阶段边界标记，单调推进（#74 P2-1 口径）。 */
    private var logCursor: Double = 0.0

    private fun advanceLogCursor() {
        logCursor = maxOf(logCursor, System.currentTimeMillis() / 1000.0)
    }

    private fun LogLine.isWithin(since: Double): Boolean = ts >= since && ts >= logCursor

    private fun describeSince(since: Double): String = dumpLogcat()
        .filter { it.ts >= since }
        .joinToString("\n") { "${it.ts} ${it.tag}: ${it.msg}" }
        .take(3000)

    /** 有截止时间的日志信号轮询；超时留证并给出已观察信号。 */
    private fun waitForLog(
        tag: String,
        contains: String,
        since: Double,
        timeoutMs: Long = 20000,
        extra: ((String) -> Boolean)? = null
    ): LogLine {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            dumpLogcat().firstOrNull {
                it.isWithin(since) && it.tag == tag && it.msg.contains(contains) &&
                    (extra == null || extra(it.msg))
            }?.let { return it }
            SystemClock.sleep(200)
        }
        saveEvidence("log-miss-$tag-$contains")
        error("log not observed: [$tag] contains=\"$contains\" since=$since cursor=$logCursor; observed:\n${describeSince(since)}")
    }

    // ==================== 窗口/请求日志解析 ====================

    /**
     * 夹具归属的日志匹配片段：`images/media/<id>_`（缓存键 = card_<uri>_<dateModifiedMs>，
     * 键内 uri 后必跟 `_`；Media 行 id 全卷唯一，`<path>/<id>_` 形态互不为前缀）。
     *
     * 轮 2 实证修正（run 43473978834）：不能用夹具对象自身 uri 串——插入返回
     * `content://media/external/images/media/<id>`，而应用侧缓存键为
     * `content://media/external_primary/images/media/<id>`（卷路径 external_primary），
     * 串匹配恒不中（expected:<2> but was:<0>）。以 id 路径段匹配，双形态通吃。
     */
    private fun keyFragment(photo: ImageFile): String = "images/media/${ContentUris.parseId(photo.uri)}_"

    /**
     * 取指定下界之后当前最新的 idx 匹配窗口日志（"最新"= 组合多次重组后的已结算状态，
     * 对中间态重组鲁棒；调用点均已在提交真值确认之后），解析出 索引→缓存键 身份映射。
     */
    private fun lastWindowLog(since: Double, expectedIdx: Int, description: String): LogLine {
        val end = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < end) {
            val last = dumpLogcat().lastOrNull {
                it.isWithin(since) && it.tag == "PreloadingManager" &&
                    it.msg.contains("window") && it.msg.contains("idx=$expectedIdx")
            }
            if (last != null) return last
            SystemClock.sleep(200)
        }
        saveEvidence("window-miss-$description")
        error("window log not observed: idx=$expectedIdx since=$since; observed:\n${describeSince(since)}")
    }

    private data class WindowIdentity(val line: LogLine, val slots: Map<Int, String>)

    /**
     * 解析窗口身份映射（identities=[0=card_...,1=card_...]）。缓存键不含逗号/方括号
     * （card_content://..._<ms>，经 substringBefore('|') 截断），可安全按 `,` 与
     * `[`/`]` 切分。
     */
    private fun parseWindow(line: LogLine): WindowIdentity {
        val body = line.msg.substringAfter("identities=[").removeSuffix("]")
        val slots = body.split(",").mapNotNull { entry ->
            val idx = entry.substringBefore("=").trim().toIntOrNull()
            val key = entry.substringAfter("=", missingDelimiterValue = "").trim()
            if (idx == null || key.isEmpty()) null else idx to key
        }.toMap()
        check(slots.isNotEmpty()) { "unparsable window line: ${line.msg}" }
        return WindowIdentity(line, slots)
    }

    /** 缓存键所属夹具（键含 uri，带尾分隔符匹配——见 keyFragment）。 */
    private fun photoForKey(key: String): ImageFile =
        photos.firstOrNull { key.contains(keyFragment(it)) }
            ?: error("window key does not map to any fixture: $key; photos=${photos.map { it.uri }}")

    // ==================== 手势注入 ====================

    /** 真实系统手势（shell input swipe）：删除用慢速长距上滑（#74 已实证通道）。 */
    private fun realSwipeUp(cx: Float, cy: Float, distance: Float) {
        val target = (cy - distance).coerceAtLeast(60f)
        shell("input swipe ${cx.toInt()} ${cy.toInt()} ${cx.toInt()} ${target.toInt()} 450")
        SystemClock.sleep(250)
    }

    private fun cardCenter(node: AccessibilityNodeInfo): Pair<Float, Float> {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return rect.exactCenterX() to rect.exactCenterY()
    }

    /**
     * 手势候选原点：全部可见夹具节点按面积降序（中央/前台卡优先，#74 口径——交互卡
     * 身份不可由节点几何可靠推断，逐候选尝试直至出现预期日志信号）。
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

    /**
     * 真实上滑删除（信号驱动原点，#74 phaseSwipeDelete 口径）：逐候选尝试直至
     * dragEnd <mode> 日志出现（交互卡承接手势的信号），随后等待 pause ≤ dragEnd ≤
     * resume 三元组（删除手势的接线顺序：onDragStart pause；onDragEnd 先记日志后
     * resume）。600px 位移恒超删除阈值 → dragEnd 即必然进入删除动画路径。
     */
    private fun swipeUpDeleteUntilDragEnd(modeLogFragment: String): Triple<LogLine, LogLine, LogLine> {
        advanceLogCursor() // 阶段边界：排除前序阶段日志
        for (candidate in gestureCandidates()) {
            val mark = logMark()
            val (cx, cy) = cardCenter(candidate)
            realSwipeUp(cx, cy, 600f)
            val windowEnd = SystemClock.uptimeMillis() + 6000
            var dragEnd: LogLine? = null
            while (SystemClock.uptimeMillis() < windowEnd) {
                dragEnd = dumpLogcat().firstOrNull {
                    it.isWithin(mark) && it.tag == "CardStackDebug" && it.msg.contains("dragEnd $modeLogFragment")
                }
                if (dragEnd != null) break
                SystemClock.sleep(250)
            }
            if (dragEnd != null) {
                Log.i(TAG, "delete attempt engaged ($modeLogFragment dragEnd at ${dragEnd.ts})")
                val pause = waitForLog("PreloadingManager", "pause", mark)
                val resume = waitForLog("PreloadingManager", "resume", mark)
                assertTrue(
                    "pause(${pause.ts}) 必须不晚于 dragEnd(${dragEnd.ts})（onDragStart 接线）",
                    pause.ts <= dragEnd.ts
                )
                assertTrue(
                    "dragEnd(${dragEnd.ts}) 必须不晚于 resume(${resume.ts})（onDragEnd 先记日志后恢复）",
                    dragEnd.ts <= resume.ts
                )
                return Triple(dragEnd, pause, resume)
            }
            Log.i(TAG, "delete attempt did not reach the interactive card, trying next origin")
        }
        saveEvidence("dragend-miss-$modeLogFragment")
        error("no candidate origin produced a dragEnd $modeLogFragment log")
    }

    /**
     * 等待一次删除提交的完整下游真值（genie onComplete → onRemove → 计数递减；
     * Snackbar 3000ms → 回收站记录）：返回提交后的全部夹具回收站记录。
     */
    private fun awaitDeleteCommitted(countBefore: Int, expectedRecords: Int): List<ImageFile> {
        awaitDeckCount(countBefore - 1, "after-delete")
        SystemClock.sleep(3600) // 撤销 Snackbar 提交窗口
        val end = SystemClock.uptimeMillis() + 15000
        var records: List<ImageFile> = emptyList()
        while (SystemClock.uptimeMillis() < end) {
            records = fixtureRecords()
            if (records.size >= expectedRecords) break
            SystemClock.sleep(300)
        }
        if (records.size < expectedRecords) saveEvidence("bin-record-missing")
        assertEquals("回收站必须恰有 $expectedRecords 条夹具记录（软删除契约）", expectedRecords, records.size)
        return records
    }

    /**
     * token-身份序列完整性：窗口 [since, until) 内每个 finish 必须有先行同 token 同身份
     * 的 enqueue。按管理器实例分窗（until）——重启后新实例 token 从 1 重计，跨实例混窗
     * 会假阳性。
     */
    private fun assertTokenIdentityIntegrity(since: Double, until: Double?, context: String) {
        val lines = dumpLogcat().filter {
            it.isWithin(since) && it.tag == "PreloadingManager" && (until == null || it.ts < until)
        }
        val enqueued = mutableMapOf<String, Pair<String, Double>>() // token -> (identity, ts)
        val violations = mutableListOf<String>()
        for (line in lines) {
            when {
                line.msg.contains("enqueue") -> {
                    val token = line.msg.substringAfter("token=").substringBefore(" ").trim()
                    val identity = line.msg.substringAfter("identity=").trim()
                    enqueued[token] = identity to line.ts
                }
                line.msg.contains("finish") -> {
                    val token = line.msg.substringAfter("token=").substringBefore(" ").trim()
                    val identity = line.msg.substringAfter("identity=").trim()
                    val issued = enqueued[token]
                    when {
                        issued == null -> violations += "finish token=$token 无先行 enqueue: ${line.msg}"
                        issued.first != identity -> violations +=
                            "finish token=$token 身份($identity)≠enqueue 身份(${issued.first})"
                        line.ts < issued.second -> violations += "finish(${line.ts}) 早于 enqueue(${issued.second})"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) saveEvidence("token-integrity-$context")
        assertTrue(
            "token-身份序列完整性被破坏（$context）:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
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
        // 轮 1 实证（run 43468670035）：本套件日志断言按 tag 门控（客户端过滤），对
        // logcat filter spec 是否生效不敏感；但 spec 被忽略时 dump 为全量流，用例后期
        // 的完整性扫描（token-身份序列、case 2 全程窗口）需早期行仍存活于环形缓冲——
        // 扩容主缓冲至 16M 消除轮转风险（对 spec 生效环境无害）。
        shell("logcat -G 16M")
        shell("logcat -c")
    }

    /**
     * 场景 a（SHUFFLE）：同索引换批——请求身份同步更新 + 实际预载请求恢复 + 旧回调
     * 无法写入新批次 + 后续操作能力。详见类 KDoc case 1。
     */
    @Test fun shuffleSameIndexSwapUpdatesRequestIdentityAndIssuesFreshRequest() = runBlocking {
        val names = (1..3).map { photo().displayName }
        AppPreferences(ctx).swipeStyle = SwipeStyle.SHUFFLE
        val startMark = logMark() // 必须先于 startActivitySync（组合期日志早于同步返回）
        activity = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForDeckReady(names)

        // ---- 初始窗口（preloadRange=1 → 恰 2 张身份入窗；slot0=当前交互卡） ----
        // 轮 1 实证修正（run 43468670035 双 API 同卡点）：初始 phase 不得推进日志游标
        // ——窗口/enqueue/finish 日志在 deck 组合期即已发出（早于 deck-ready 返回），
        // 此处 advanceLogCursor 会把游标推过这些日志使其被 isWithin 永久排除
        // （"window log not observed"）。startMark（startActivitySync 之前采样）即
        // 本 phase 下界；游标仍保留给后续 phase 的边界隔离。
        val initial = parseWindow(lastWindowLog(startMark, expectedIdx = 0, description = "initial"))
        val initialFixtureSlots = initial.slots.values.filter { key -> photos.any { key.contains(keyFragment(it)) } }
        assertEquals(
            "初始窗口（preloadRange=1）应恰好包含 2 张夹具身份: ${initial.line.msg}",
            2, initialFixtureSlots.size
        )
        val currentPhoto = photoForKey(initial.slots.getValue(0))
        val nextPhoto = photoForKey(initial.slots.getValue(1))
        val thirdPhoto = photos.first { it.uri != currentPhoto.uri && it.uri != nextPhoto.uri }
        Log.i(TAG, "initial window: current=${currentPhoto.displayName} next=${nextPhoto.displayName} third=${thirdPhoto.displayName}")
        // 初始批的真实请求与完成回写（实际预载行为证据）
        waitForLog("PreloadingManager", "enqueue", startMark) {
            it.contains(keyFragment(currentPhoto)) && it.contains("index=0")
        }
        waitForLog("PreloadingManager", "enqueue", startMark) {
            it.contains(keyFragment(nextPhoto)) && it.contains("index=1")
        }
        waitForLog("PreloadingManager", "finish", startMark) {
            it.contains(keyFragment(currentPhoto)) && it.contains("success=true")
        }
        waitForLog("PreloadingManager", "finish", startMark) {
            it.contains(keyFragment(nextPhoto)) && it.contains("success=true")
        }
        assertTrue(
            "初始窗口不得包含未入窗的第三张: ${initial.line.msg}",
            !initial.line.msg.contains(keyFragment(thirdPhoto))
        )

        // ---- 真实 swipe 上滑删除当前卡（观察式：回收站记录反推被删名） ----
        val countBeforeFirst = readDeckPendingCount()
        val deleteMark = logMark()
        val (dragEnd, pause, resume) = swipeUpDeleteUntilDragEnd("shuffle")
        val recordsAfterFirst = awaitDeleteCommitted(countBeforeFirst, expectedRecords = 1)
        val deletedPhoto = photos.first { it.displayName == recordsAfterFirst.single().displayName }
        Log.i(TAG, "observed deleted card: ${deletedPhoto.displayName}")
        // 换批真值锚点：被删卡必须就是初始窗口 slot0（当前交互卡）——同索引换批的前提
        assertEquals(
            "被删卡应为初始窗口 slot0 的当前卡（同索引换批前提）",
            currentPhoto.uri.toString(), deletedPhoto.uri.toString()
        )
        assertTokenIdentityIntegrity(startMark, until = null, context = "after-first-delete")

        // ---- 同索引换批：新窗口身份 = 原下一张承接同索引；被删身份消失 ----
        val swapped = parseWindow(lastWindowLog(deleteMark, expectedIdx = 0, description = "swap"))
        assertTrue(
            "换批窗口不得再含被删卡身份: ${swapped.line.msg}",
            !swapped.line.msg.contains(keyFragment(deletedPhoto))
        )
        val swapSlot0 = photoForKey(swapped.slots.getValue(0))
        assertEquals(
            "同索引换批：idx0 的新身份必须是原下一张（内容/身份不同而索引相同）",
            nextPhoto.uri.toString(), swapSlot0.uri.toString()
        )
        assertTrue(
            "换批窗口应包含第三张: ${swapped.line.msg}",
            swapped.line.msg.contains(keyFragment(thirdPhoto))
        )

        // ---- 实际预载请求恢复：从未入窗的第三张在换批后获得真实新请求 ----
        val freshEnqueue = waitForLog("PreloadingManager", "enqueue", deleteMark) {
            it.contains(keyFragment(thirdPhoto)) && it.contains("index=1")
        }
        assertTrue(
            "恢复后的新请求（${freshEnqueue.ts}）必须不早于删除手势 resume（${resume.ts}）——实际预载请求恢复",
            freshEnqueue.ts >= resume.ts
        )
        assertTrue(
            "新请求身份必须即该槽位新卡（第三张）且非被删卡: ${freshEnqueue.msg}",
            freshEnqueue.msg.contains(keyFragment(thirdPhoto)) && !freshEnqueue.msg.contains(keyFragment(deletedPhoto))
        )
        waitForLog("PreloadingManager", "finish", deleteMark) {
            it.contains(keyFragment(thirdPhoto)) && it.contains("success=true")
        }
        // 旧回调无法写入新批次（行为边界）：换批标记后无被删身份的任何 enqueue/finish
        val staleLines = dumpLogcat().filter {
            it.isWithin(deleteMark) && it.tag == "PreloadingManager" &&
                (it.msg.contains("enqueue") || it.msg.contains("finish")) &&
                it.msg.contains(keyFragment(deletedPhoto))
        }
        if (staleLines.isNotEmpty()) saveEvidence("stale-writeback-observed")
        assertTrue(
            "换批后不得有被删身份的请求发出或回写:\n" + staleLines.joinToString("\n") { it.msg },
            staleLines.isEmpty()
        )
        assertTokenIdentityIntegrity(startMark, until = null, context = "after-swap")

        // ---- 后续操作能力（SHUFFLE）：再次真实删除 → 换批继续成立 ----
        val countBeforeSecond = readDeckPendingCount()
        val secondMark = logMark()
        swipeUpDeleteUntilDragEnd("shuffle")
        val recordsAfterSecond = awaitDeleteCommitted(countBeforeSecond, expectedRecords = 2)
        val secondDeleted = recordsAfterSecond.first { it.displayName != deletedPhoto.displayName }
        assertEquals(
            "第二次删除的对象应为换批后的当前卡（原下一张）",
            nextPhoto.uri.toString(), secondDeleted.uri.toString()
        )
        val finalWindow = parseWindow(lastWindowLog(secondMark, expectedIdx = 0, description = "final"))
        assertEquals(
            "二次换批后 idx0 唯一身份应为第三张",
            thirdPhoto.uri.toString(), photoForKey(finalWindow.slots.getValue(0)).uri.toString()
        )
        assertTokenIdentityIntegrity(startMark, until = null, context = "after-second-delete")
        // MediaStore 真值：软删除不物理删除（#74 契约锚点）
        photos.forEach { assertTrue("软删除必须保留媒体行: ${it.displayName}", present(primaryItem(it.uri))) }
        assertEquals("软删除周期夹具行数必须恒定", photos.size, fixtureCount())
    }

    /**
     * 场景 c（DRAW）：删除动画中 BACK 退出 → 返回恢复——无崩溃、状态正确、全量预载
     * 重建、后续操作能力。详见类 KDoc case 2。
     */
    @Test fun drawBackDuringDeleteAnimationExitsCleanlyAndRecoversPreload() = runBlocking {
        val names = (1..3).map { photo().displayName }
        AppPreferences(ctx).swipeStyle = SwipeStyle.DRAW
        val startMark = logMark()
        activity = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val pidBeforeExit = appPid()
        Log.i(TAG, "app pid before exit: $pidBeforeExit")
        waitForDeckReady(names)

        // ---- 初始窗口（DRAW preloadRange=5 → 3 张全入窗）+ 真实请求 ----
        // （同 case 1 轮 1 修正：初始 phase 不推进日志游标——组合期日志早于 deck-ready）
        val initial = parseWindow(lastWindowLog(startMark, expectedIdx = 0, description = "initial-draw"))
        photos.forEach { photo ->
            assertTrue(
                "DRAW 初始窗口应含全部夹具身份（preloadRange=5）: ${initial.line.msg}",
                initial.line.msg.contains(keyFragment(photo))
            )
            waitForLog("PreloadingManager", "enqueue", startMark) { it.contains(keyFragment(photo)) }
        }

        // ---- 真实上滑删除（dragEnd draw + 接线三元组）→ 动画进行中立即 BACK ----
        val countBeforeExit = readDeckPendingCount()
        val deleteMark = logMark()
        val (dragEnd, _, _) = swipeUpDeleteUntilDragEnd("draw")
        // genie 删除动画进行中（440ms 窗口）注入 BACK：deck 为主页无 BackHandler 拦截，
        // KEYCODE_BACK → Activity finish → 组合销毁（时序收敛论证见类 KDoc case 2）
        shell("input keyevent KEYCODE_BACK")

        // ---- 退出观察：应用离开前台 ----
        waitForState("app-exited-foreground", timeoutMs = 15000) { root ->
            root == null || root.packageName?.toString() != ctx.packageName
        }
        Log.i(TAG, "app left foreground after BACK during delete animation")

        // ---- teardown dispose：组合销毁释放管理器，其后静默 ----
        val dispose = waitForLog("PreloadingManager", "dispose", deleteMark)
        assertTrue(
            "dispose(${dispose.ts}) 必须发生在删除手势 dragEnd(${dragEnd.ts}) 之后（组合销毁路径）",
            dispose.ts >= dragEnd.ts
        )
        SystemClock.sleep(2500)
        val postDispose = dumpLogcat().filter {
            it.ts >= dispose.ts && it.tag == "PreloadingManager" &&
                (it.msg.contains("enqueue") || it.msg.contains("window") ||
                    it.msg.contains("staleDispose") || it.msg.contains("finish"))
        }
        if (postDispose.isNotEmpty()) saveEvidence("post-dispose-activity")
        assertTrue(
            "已释放管理器在重启前不得有任何请求活动:\n" + postDispose.joinToString("\n") { it.msg },
            postDispose.isEmpty()
        )
        // 管理器 A（退出前实例）的 token-身份完整性
        assertTokenIdentityIntegrity(startMark, until = dispose.ts, context = "manager-A")

        // ---- 删除未提交（BACK 于动画中：Snackbar 计时随组合销毁取消） ----
        val end = SystemClock.uptimeMillis() + 3000
        var records: List<ImageFile> = emptyList()
        while (SystemClock.uptimeMillis() < end) {
            records = fixtureRecords()
            if (records.isNotEmpty()) break
            SystemClock.sleep(300)
        }
        if (records.isNotEmpty()) saveEvidence("unexpected-trash-record")
        assertTrue(
            "动画中退出的删除不得提交（回收站必须无夹具记录）: ${records.map { it.displayName }}",
            records.isEmpty()
        )
        photos.forEach { assertTrue("退出周期必须保留媒体行: ${it.displayName}", present(primaryItem(it.uri))) }
        assertEquals("退出周期夹具行数必须恒定", photos.size, fixtureCount())

        // ---- 重启返回：无崩溃 + deck 状态正确（计数=退出前，未提交删除不丢卡） ----
        val relaunchMark = logMark() // 必须先于 startActivitySync（组合期日志早于同步返回）
        activity = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForDeckReady(names)
        val pidAfterReturn = appPid()
        Log.i(TAG, "app pid after return: $pidAfterReturn (before=$pidBeforeExit)")
        assertTrue("返回后应用进程必须存活", pidAfterReturn != null)
        awaitDeckCount(countBeforeExit, "after-return")
        val crash = crashLogForApp()
        if (crash.isNotBlank()) saveEvidence("crash-buffer-not-clean")
        assertTrue("动画中退出后 crash 缓冲不得有本应用记录:\n$crash", crash.isBlank())

        // ---- 实际预载请求恢复（全量重建）：dispose 清空 completed → 新管理器全量重发 ----
        // （轮 1 修正：此处不推进游标——重启组合期日志早于 deck-ready 返回，同 case 1；
        //   relaunchMark 即本 phase 下界，管理器 A 的日志均早于 relaunchMark 被排除）
        val rebuilt = parseWindow(lastWindowLog(relaunchMark, expectedIdx = 0, description = "relaunch"))
        photos.forEach { photo ->
            assertTrue(
                "重启后窗口应含全部夹具身份: ${rebuilt.line.msg}",
                rebuilt.line.msg.contains(keyFragment(photo))
            )
            waitForLog("PreloadingManager", "enqueue", relaunchMark) { it.contains(keyFragment(photo)) }
        }

        // ---- 后续操作能力（DRAW）：再次真实删除 → 提交正常 ----
        val countBeforePostDelete = readDeckPendingCount()
        swipeUpDeleteUntilDragEnd("draw")
        val recordsAfterPostDelete = awaitDeleteCommitted(countBeforePostDelete, expectedRecords = 1)
        Log.i(TAG, "post-return delete committed: ${recordsAfterPostDelete.single().displayName}")
        photos.forEach { assertTrue("软删除必须保留媒体行: ${it.displayName}", present(primaryItem(it.uri))) }
        assertEquals("软删除周期夹具行数必须恒定", photos.size, fixtureCount())
        // 管理器 B（重启后新实例）的 token-身份完整性
        assertTokenIdentityIntegrity(relaunchMark, until = null, context = "manager-B")
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
