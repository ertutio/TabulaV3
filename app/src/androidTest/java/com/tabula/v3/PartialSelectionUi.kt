package com.tabula.v3

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * PARTIAL 选择集 A→B 设备验证的真实 UI 驱动（系统权限对话框 + 照片选择器）。
 *
 * 全部经 UiAutomation 操作真实系统界面（无生产改动、无替换回调）；等待均为
 * 有截止时间的轮询；失败时截图与辅助树 dump 写入 cloud-ui-failures/
 * （CI artifact 拉取）。
 */
internal object PartialSelectionUi {
    private const val TAG = "PartialSelection"
    val ins get() = InstrumentationRegistry.getInstrumentation()
    val ctx get() = ins.targetContext

    /** Deck 在树的稳定状态文本（PixelRefreshUiTest 同款查看器区分依据）。 */
    const val DECK_MARKER = "张待整理"

    /** 系统权限对话框 "Select photos"（部分授权入口）的候选文案（API 差异容忍）。 */
    val SELECT_PHOTOS_CANDIDATES = listOf(
        "Select photos", "Select photos and videos", "Select photos & videos",
        "Allow limited access", "Select more photos", "仅选择照片", "选择照片和视频", "选择照片"
    )

    /** 系统权限对话框出现的候选文案（用于等待对话框出现）。 */
    val DIALOG_CANDIDATES = SELECT_PHOTOS_CANDIDATES + listOf(
        "Allow all", "Don't allow", "全部允许", "不允许"
    )

    private fun evidenceDir(): File = File(
        ctx.getExternalFilesDir(null) ?: ctx.filesDir, "cloud-ui-failures"
    ).apply { mkdirs() }

    fun saveScreenshot(tag: String) {
        try {
            val png = shellBytes("screencap -p")
            File(evidenceDir(), "partial-selection-$tag.png").writeBytes(png)
            Log.i(TAG, "screenshot saved: $tag")
        } catch (e: Exception) {
            Log.w(TAG, "screenshot save failed: $tag", e)
        }
    }

    fun dumpTree(tag: String) {
        try {
            val sb = StringBuilder()
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 25) return
                if (node.isVisibleToUser) {
                    val rect = Rect().also { node.getBoundsInScreen(it) }
                    sb.append(" ".repeat(depth))
                        .append(node.className).append(" text=").append(node.text)
                        .append(" desc=").append(node.contentDescription)
                        .append(" selected=").append(node.isSelected)
                        .append(" clickable=").append(node.isClickable)
                        .append(" bounds=").append(rect.toShortString())
                        .append('\n')
                }
                for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
            }
            walk(ins.uiAutomation.rootInActiveWindow, 0)
            File(evidenceDir(), "partial-selection-$tag-tree.txt").writeText(sb.toString())
            Log.i(TAG, "tree dump saved: $tag (${sb.length} chars)")
        } catch (e: Exception) {
            Log.w(TAG, "tree dump failed: $tag", e)
        }
    }

    fun shell(command: String): String =
        ins.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    fun shellBytes(command: String): ByteArray =
        ins.uiAutomation.executeShellCommand(command).use { p ->
            FileInputStream(p.fileDescriptor).use { stream ->
                val out = ByteArrayOutputStream()
                stream.copyTo(out)
                out.toByteArray()
            }
        }

    fun launch() {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        shell("am start -n ${ctx.packageName}/com.tabula.v3.MainActivity")
    }

    fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString()?.contains(label) == true ||
                    node.contentDescription?.toString()?.contains(label) == true)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }

    fun findAll(node: AccessibilityNodeInfo?, label: String, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isVisibleToUser && (node.text?.toString()?.contains(label) == true ||
                    node.contentDescription?.toString()?.contains(label) == true)) out += node
        for (i in 0 until node.childCount) findAll(node.getChild(i), label, out)
    }

    /** 候选文案任一命中即返回（对话框/确认按钮的 API 差异容忍）。 */
    fun findAny(node: AccessibilityNodeInfo?, candidates: List<String>): AccessibilityNodeInfo? {
        for (candidate in candidates) find(node, candidate)?.let { return it }
        return null
    }

    fun await(label: String, condition: () -> Boolean, timeoutMs: Long = 30000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        dumpTree("timeout-$label")
        saveScreenshot("timeout-$label")
        error("timed out waiting for $label")
    }

    fun waitFor(label: String, timeoutMs: Long = 30000): AccessibilityNodeInfo {
        await(label, { find(ins.uiAutomation.rootInActiveWindow, label) != null }, timeoutMs)
        return requireNotNull(find(ins.uiAutomation.rootInActiveWindow, label))
    }

    /** 集合外媒体不得出现：截止时间内节点必须始终缺席（出现即失败）。 */
    fun awaitGone(label: String, timeoutMs: Long = 10000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (find(ins.uiAutomation.rootInActiveWindow, label) != null) {
                dumpTree("unexpected-$label")
                saveScreenshot("unexpected-$label")
                error("outside-selection media unexpectedly visible: $label")
            }
            SystemClock.sleep(100)
        }
    }

    /**
     * 节点定向点击：优先 ACTION_CLICK（无坐标换算——CI 实证 input tap 与
     * 部分窗口渲染坐标存在系统级错位/吞没，run 42469308043/42471835967），
     * 失败回退节点中心坐标 tap。返回实际点击方式。
     */
    fun clickNode(node: AccessibilityNodeInfo, tag: String): String {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "$tag clicked via ACTION_CLICK")
            return "ACTION_CLICK"
        }
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 4) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "$tag clicked via ancestor ACTION_CLICK (hops=$hops)")
                return "ancestor ACTION_CLICK"
            }
            parent = parent.parent
            hops++
        }
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        Log.i(TAG, "$tag clicked via coordinate fallback bounds=${bounds.toShortString()}")
        return "coordinate"
    }

    fun tap(label: String) {
        val node = waitFor(label)
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        check(!bounds.isEmpty)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        }
    }

    fun tapAny(candidates: List<String>, tag: String) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = findAny(ins.uiAutomation.rootInActiveWindow, candidates)
            if (node != null) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (!bounds.isEmpty) {
                    clickNode(node, tag)
                    return
                }
            }
            SystemClock.sleep(100)
        }
        dumpTree("missing-$tag")
        saveScreenshot("missing-$tag")
        error("no candidate visible for $tag: $candidates")
    }

    /**
     * 系统权限对话框动作点击 + 推进核验（CI 实证单次 tap 可能被吞没）：
     * 点击后等待对话框候选文案消失（已推进到照片选择器），未消失则重试，
     * 至多 4 次；全部失败留证报错。
     */
    fun tapDialogActionAndAwaitPicker(candidates: List<String>, tag: String) {
        repeat(4) { attempt ->
            val node = findAny(ins.uiAutomation.rootInActiveWindow, candidates)
                ?: error("$tag: dialog action candidate vanished before click")
            clickNode(node, "$tag-attempt$attempt")
            val deadline = SystemClock.uptimeMillis() + 8000
            while (SystemClock.uptimeMillis() < deadline) {
                if (findAny(ins.uiAutomation.rootInActiveWindow, candidates) == null) {
                    Log.i(TAG, "$tag advanced after attempt $attempt")
                    return
                }
                SystemClock.sleep(200)
            }
            Log.w(TAG, "$tag attempt $attempt did not advance dialog; retrying")
        }
        dumpTree("dialog-not-advanced-$tag")
        saveScreenshot("dialog-not-advanced-$tag")
        error("$tag: dialog did not advance after 4 click attempts — evidence saved")
    }

    fun backToDeck() {
        shell("input keyevent KEYCODE_BACK")
        await("back-to-deck", condition = { find(ins.uiAutomation.rootInActiveWindow, DECK_MARKER) != null })
    }

    /**
     * 收集照片选择器网格单元（可见、可点击、尺寸≥250x250 的节点；排除
     * 相机入口等特殊格），按 (top,left) 排序 = 最新在前。逐格记录
     * desc/selected/勾选子节点到 logcat（失败裁决证据）。
     */
    fun collectGridCells(minCount: Int, timeoutMs: Long = 30000): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastDump = listOf<String>()
        while (SystemClock.uptimeMillis() < deadline) {
            val cells = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isVisibleToUser && node.isClickable) {
                    val rect = Rect().also { node.getBoundsInScreen(it) }
                    if (rect.width() >= 250 && rect.height() >= 250) cells += node to rect
                }
                for (i in 0 until node.childCount) walk(node.getChild(i))
            }
            walk(ins.uiAutomation.rootInActiveWindow)
            val descriptions = cells.map { (node, _) -> node.contentDescription?.toString() ?: node.text?.toString() ?: "" }
            // 排除相机入口格（desc/子树含 camera/拍照）
            val grid = cells.filterIndexed { index, (node, _) ->
                val text = descriptions[index]
                val hasCameraDesc = text.contains("拍照", ignoreCase = true) ||
                    text.contains("camera", ignoreCase = true) || hasCameraChild(node)
                !hasCameraDesc
            }.sortedWith(compareBy<Pair<AccessibilityNodeInfo, Rect>> { it.second.top }.thenBy { it.second.left })
                .map { it.first }
            if (grid.size >= minCount) {
                grid.forEachIndexed { index, node ->
                    Log.i(TAG, "gridCell[$index] desc=${node.contentDescription} selected=${node.isSelected} " +
                        "checkedChild=${checkedChild(node)} bounds=${Rect().also { node.getBoundsInScreen(it) }}")
                }
                return grid
            }
            lastDump = descriptions
            SystemClock.sleep(150)
        }
        Log.e(TAG, "grid cells short: expected>=$minCount saw=$lastDump")
        dumpTree("grid-cells-short")
        saveScreenshot("grid-cells-short")
        error("photo picker grid cells < $minCount (saw ${lastDump.size})")
    }

    private fun hasCameraChild(node: AccessibilityNodeInfo): Boolean {
        fun walk(n: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (n == null || depth > 4) return false
            if ((n.text?.toString()?.contains("拍照") == true) ||
                (n.contentDescription?.toString()?.contains("拍照") == true) ||
                (n.contentDescription?.toString()?.contains("camera", ignoreCase = true) == true)) return true
            for (i in 0 until n.childCount) if (walk(n.getChild(i), depth + 1)) return true
            return false
        }
        return walk(node, 0)
    }

    /**
     * 勾选态检测：节点 selected / 勾选子节点 / AOSP 选择器数字角标（选中格
     * 显示 1..N 编号徽标）/ desc 或文本含选中语义。
     */
    fun isCellChecked(node: AccessibilityNodeInfo): Boolean {
        if (node.isSelected) return true
        val desc = (node.contentDescription?.toString() ?: "") + " " + (node.text?.toString() ?: "")
        if (Regex("(?i)(selected|已选|勾选|checked|已选中)").containsMatchIn(desc) &&
            !Regex("仅可访问所选|selected photos").containsMatchIn(desc)) return true
        return checkedChild(node)
    }

    private fun checkedChild(node: AccessibilityNodeInfo): Boolean {
        val badge = Regex("^\\d{1,2}$")
        // API36 google_apis 选择器实证（CI run 42469308043 树 dump）：选中格的
        // 角标为子节点 content-desc="Selected"（左上角小徽标）；数字角标与
        // checkable 形态为其他版本容忍。
        val selectedDesc = Regex("^(Selected|已选|已选中)$")
        fun walk(n: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (n == null || depth > 5) return false
            if ((n.isCheckable && n.isChecked) || n.isSelected) return true
            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            if (badge.matches(text) || selectedDesc.matches(desc)) return true
            for (i in 0 until n.childCount) if (walk(n.getChild(i), depth + 1)) return true
            return false
        }
        return walk(node, 0)
    }

    /**
     * 点选网格格：优先节点原生 ACTION_CLICK（节点定向，无坐标换算——CI 实证
     * 选择器底部 sheet 的坐标 tap 与 a11y 边界存在系统级错位，run
     * 42469308043 双 API 一致复现），失败回退节点中心坐标 tap。
     */
    fun tapCell(index: Int, cells: List<AccessibilityNodeInfo>, purpose: String): String {
        val node = cells[index]
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "tapped cell[$index] for $purpose via ACTION_CLICK")
            return "ACTION_CLICK"
        }
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        check(!bounds.isEmpty) { "cell $index has empty bounds" }
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        Log.i(TAG, "tapped cell[$index] for $purpose via coordinate fallback bounds=${bounds.toShortString()}")
        return "coordinate"
    }

    /** 选择器底栏已选计数（"N photos or videos selected"）；不可见返回 null。 */
    fun pickerSelectedCount(): Int? {
        val regex = Regex("^(\\d+)")
        fun walk(node: AccessibilityNodeInfo?): Int? {
            if (node == null) return null
            val desc = node.contentDescription?.toString() ?: ""
            val match = Regex("^(\\d+) photos? or videos? selected.*$").matchEntire(desc)
            if (match != null) return match.groupValues[1].toInt()
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(ins.uiAutomation.rootInActiveWindow)
    }

    /** 确认选择（"Add N"/"添加"/"完成"），返回按钮文本供日志。 */
    fun confirmSelection(timeoutMs: Long = 20000): String {
        val regex = Regex("^(Add|添加|完成|Done|Confirm|确认)([ ]?[（(]?\\d+[)）]?)?$")
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val matches = mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isVisibleToUser) {
                    val text = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    if (regex.matches(text) || regex.matches(desc)) matches += node
                }
                for (i in 0 until node.childCount) walk(node.getChild(i))
            }
            walk(ins.uiAutomation.rootInActiveWindow)
            // 优先可点击命中；否则上溯最近可点击祖先（≤3 跳）
            val clickable = matches.firstOrNull { it.isClickable }
                ?: matches.firstOrNull { node ->
                    var parent = node.parent
                    var hops = 0
                    while (parent != null && hops < 3) {
                        if (parent.isClickable) return@firstOrNull true
                        parent = parent.parent
                        hops++
                    }
                    false
                }
            if (clickable != null) {
                val label = clickable.text?.toString() ?: clickable.contentDescription?.toString() ?: "?"
                clickNode(clickable, "confirm($label)")
                return label
            }
            SystemClock.sleep(150)
        }
        dumpTree("confirm-missing")
        saveScreenshot("confirm-missing")
        error("picker confirm button not found")
    }

    /**
     * 从候选 displayNames 的最大面积节点打开查看器（当前卡 = 最大节点；
     * deck 批次 shuffled，当前卡为候选之一）。返回命中的 displayName。
     */
    fun openViewerFromLargest(names: List<String>): String {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            var best: AccessibilityNodeInfo? = null
            var bestArea = -1L
            var bestName = ""
            for (name in names) {
                val matches = mutableListOf<AccessibilityNodeInfo>()
                findAll(ins.uiAutomation.rootInActiveWindow, name, matches)
                for (m in matches) {
                    val rect = Rect().also { m.getBoundsInScreen(it) }
                    val area = rect.width().toLong() * rect.height().toLong()
                    if (area > bestArea) { bestArea = area; best = m; bestName = name }
                }
            }
            if (best != null) {
                val rect = Rect().also { best.getBoundsInScreen(it) }
                shell("input tap ${rect.centerX()} ${rect.centerY()}")
                Log.i(TAG, "viewer opened from card $bestName bounds=${rect.toShortString()}")
                return bestName
            }
            SystemClock.sleep(100)
        }
        dumpTree("no-card-for-viewer")
        saveScreenshot("no-card-for-viewer")
        error("no visible card node for candidates $names")
    }

    /** 查看器 overlay（viewer_overlay:<identityKey>）出现的截止时间轮询。 */
    fun awaitViewerOverlay(identityKey: String, timeoutMs: Long = 15000) {
        val label = "viewer_overlay:$identityKey"
        await("viewer-overlay-$identityKey", {
            find(ins.uiAutomation.rootInActiveWindow, label) != null
        }, timeoutMs)
    }

    /**
     * 经 ACTION_PICK_IMAGES 打开照片选择器（调用方 = 被测应用包名）。
     * 多选上限 extra：API 36 起为 EXTRA_PICK_IMAGES_MAX（旧
     * EXTRA_PICK_IMAGES_MAX_LIMIT 在 android-36 平台已移除）。
     */
    fun launchPickerForReselection() {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
            .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, PartialSelectionStateContract.PICKER_MAX_LIMIT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /**
     * 驱动勾选集合到预期：取消一切预期外勾选、补齐预期格，逐轮以"节点勾选态
     * + 底栏计数"双口径核验后收敛（CI run 42469308043 实证：选择器存在系统
     * 级预选与坐标错位，盲 tap 振荡不可接受——每轮差集矫正、至多 3 轮、
     * 每轮证据入 logcat，不收敛即失败留证）。无预勾选与"当前选择预勾选"
     * 两种形态均收敛（地面真值断言兜底）。
     */
    fun driveCheckedCells(expected: Set<Int>, minCells: Int = 5, timeoutMs: Long = 30000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var rounds = 0
        while (true) {
            val cells = collectGridCells(minCount = minCells)
            val checked = cells.indices.filter { isCellChecked(cells[it]) }.toSet()
            val count = pickerSelectedCount()
            Log.i(TAG, "driveChecked round=$rounds checked=$checked count=$count expected=$expected")
            if (checked == expected && (count == null || count == expected.size)) return
            if (SystemClock.uptimeMillis() > deadline || rounds >= 3) {
                dumpTree("drive-checked-mismatch")
                saveScreenshot("drive-checked-mismatch")
                error("checked cells not converged: checked=$checked count=$count expected=$expected " +
                    "after $rounds corrective rounds — evidence saved")
            }
            rounds++
            val taps = (checked - expected).sorted() + (expected - checked).sorted()
            taps.forEach { tapCell(it, cells, purpose = "drive-selection") }
            // 等待本轮点选在树中生效（角标渲染 + 底栏计数更新），无固定断言 sleep：
            // 收敛判定交给下一轮双口径核验。
            SystemClock.sleep(600)
        }
    }

    /** 候选任一出现的截止时间等待（系统对话框出现判定）。 */
    fun awaitAny(candidates: List<String>, tag: String, timeoutMs: Long = 30000) {
        await(tag, { findAny(ins.uiAutomation.rootInActiveWindow, candidates) != null }, timeoutMs)
    }
}
