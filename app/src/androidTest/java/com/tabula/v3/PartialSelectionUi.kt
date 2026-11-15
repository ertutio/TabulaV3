package com.tabula.v3

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 *
 * 本夜 CI 实证的传感/通道事实（实现全部据此设计）：
 * - executeShellCommand = 直接 exec + 按空白朴素分词，无 shell 解释（管道/
 *   重定向/引号失效）——复合语法必须走 [shellScript] 的 IFS 通道
 *   （CloudFixtureCleanup 同款，run 42477604680 双 API 全绿实证）。
 * - 选择器辅助树**不反映勾选变化**（run 42481561828 abort 截图：角标恰在
 *   预期两格、count=2，树读数恒为初始空态）——勾选态判定用
 *   [readCheckedStateByPixel]（截屏像素比对，实时无滞后）；格位置仍取
 *   辅助树边界（跨轮实证稳定准确）。
 * - 点击实际生效（轮4 去选/轮8 选择振荡均由截图证实）——格点选以
 *   ACTION_CLICK 为主、坐标回退。
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

    /**
     * 证据文件名 sanitize：identityKey 等含 `|`（如 external_primary|108），
     * upload-artifact v4 对非法字符**整体拒绝上传**（R9-API37/R10 双 API 工件
     * 缺失的根因，裁决 F1）——非法字符统一替换为 `_`。
     */
    private fun safeFileName(tag: String): String =
        tag.replace(Regex("[|\":*?<>\\r\\n\\\\]"), "_")

    fun saveScreenshot(tag: String) {
        try {
            val png = shellBytes("screencap -p")
            File(evidenceDir(), "partial-selection-${safeFileName(tag)}.png").writeBytes(png)
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
            File(evidenceDir(), "partial-selection-${safeFileName(tag)}-tree.txt").writeText(sb.toString())
            Log.i(TAG, "tree dump saved: $tag (${sb.length} chars)")
        } catch (e: Exception) {
            Log.w(TAG, "tree dump failed: $tag", e)
        }
    }

    /** 无 shell 语法的短命令直通（am/input/screencap 等朴素参数命令）。 */
    fun shell(command: String): String =
        ins.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    /**
     * 复合 shell 语法（管道/重定向/引号）专用通道：脚本 base64 编码为单一
     * word，`/system/bin/sh -c` 直接 exec 下整串成为 -c 脚本，内层展开
     * ${IFS} 还原空格后真实解释执行（CloudFixtureCleanup 同款模式）。
     */
    fun shellScript(script: String): String {
        val encoded = android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val command = "/system/bin/sh -c printf\${IFS}%s\${IFS}$encoded|base64\${IFS}-d|/system/bin/sh"
        return shell(command)
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

    /** 节点定向点击：ACTION_CLICK 优先，失败上溯可点击祖先，最后坐标回退。 */
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
     * desc 到 logcat（失败裁决证据）。
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
                    Log.i(TAG, "gridCell[$index] desc=${node.contentDescription} " +
                        "bounds=${Rect().also { node.getBoundsInScreen(it) }}")
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
     * 点选网格格：优先节点原生 ACTION_CLICK（轮4 去选/轮8 选择振荡截图均
     * 证实点击真实生效），失败回退节点中心坐标 tap。
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

    /**
     * 定位确认按钮并坐标点击：辅助树 8s 轮询（命中后按**坐标**点击实际位置
     * 上的活体按钮），未命中走像素兜底（底栏深蓝药丸 Done 检测）。定位失败
     * 抛出（选择器大概率已关——树滞后/窗口已切，不盲点）。
     */
    private fun locateAndTapConfirm(): String {
        val regex = Regex("^(Add|添加|完成|Done|Confirm|确认)([ ]?[（(]?\\d+[)）]?)?$")
        val a11yDeadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < a11yDeadline) {
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
            val target = matches.firstOrNull()
            if (target != null) {
                val bounds = Rect().also { target.getBoundsInScreen(it) }
                if (!bounds.isEmpty) {
                    val label = target.text?.toString() ?: target.contentDescription?.toString() ?: "?"
                    shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                    Log.i(TAG, "confirm tapped via a11y bounds: $label bounds=${bounds.toShortString()}")
                    return label
                }
            }
            SystemClock.sleep(150)
        }
        // 像素兜底：底栏深蓝药丸（Done）检测
        val png = shellBytes("screencap -p")
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { "screencap decode failed" }
        try {
            val top = (bitmap.height * 0.80).toInt()
            val cols = IntArray(bitmap.width)
            var maxCount = 0
            for (y in top until bitmap.height step 3) {
                for (x in 0 until bitmap.width step 2) {
                    val p = bitmap.getPixel(x, y)
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                    if (b > 130 && b - r > 50 && b - g > 40) { cols[x]++; if (cols[x] > maxCount) maxCount = cols[x] }
                }
            }
            check(maxCount >= 12) { "confirm pill not detected in bottom strip (maxCol=$maxCount)" }
            var right = bitmap.width - 1
            while (right > 0 && cols[right] < 12) right--
            var left = right
            while (left > 0 && cols[left] >= 12) left--
            check(right > left) { "confirm pill extent degenerate (left=$left right=$right)" }
            var pillTop = -1; var pillBottom = -1
            for (y in top until bitmap.height) {
                var hasBlue = false
                for (x in left..right step 2) {
                    val p = bitmap.getPixel(x, y)
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                    if (b > 130 && b - r > 50 && b - g > 40) { hasBlue = true; break }
                }
                if (hasBlue) { if (pillTop < 0) pillTop = y; pillBottom = y }
            }
            check(pillTop >= 0) { "confirm pill rows not found" }
            val cx = (left + right) / 2
            val cy = (pillTop + pillBottom) / 2
            shell("input tap $cx $cy")
            Log.i(TAG, "confirm tapped via pixel pill: ($cx,$cy) x[$left,$right] y[$pillTop,$pillBottom]")
            return "Done(pixel)"
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 确认选择 + 提交回读（裁决 F2）：点击后轮询 ≤8s
     * rootInActiveWindow.packageName 离开 media.module/photopicker（选择器
     * 关闭）→ confirmCommitted=true；未关 → 坐标重试确认 ≤2 次（每次留证）；
     * 每步 logcat 行 confirmCommitted=true/false（S6 宿主 grep 采集）。重试
     * 仍未提交**不中止**——继续执行，由授权轮询/地面真值裁决（S3 判据）。
     * 定位失败（重试时选择器已关、树滞后）视为已提交的不确定态，返回
     * unknown(closed) 并继续。
     */
    fun confirmSelection(timeoutMs: Long = 20000): String {
        var label = "?"
        repeat(3) { attempt ->
            label = try {
                locateAndTapConfirm()
            } catch (e: IllegalStateException) {
                Log.i(TAG, "confirmCommitted=unknown attempt=$attempt (locate failed: ${e.message}) — picker likely closed")
                return "unknown(closed)"
            }
            var committed = false
            var rootPkg = ""
            val deadline = SystemClock.uptimeMillis() + 8000
            while (SystemClock.uptimeMillis() < deadline) {
                rootPkg = ins.uiAutomation.rootInActiveWindow?.packageName?.toString() ?: ""
                if (!rootPkg.contains("media.module") && !rootPkg.contains("photopicker")) {
                    committed = true
                    break
                }
                SystemClock.sleep(200)
            }
            Log.i(TAG, "confirmCommitted=$committed attempt=$attempt label=$label rootPkg=$rootPkg")
            if (committed) return label
            saveScreenshot("confirm-not-committed-$attempt")
            Log.w(TAG, "confirm attempt $attempt not committed; retrying")
        }
        Log.i(TAG, "confirmCommitted=false after 3 attempts — proceeding; grant poll / ground truth will arbitrate")
        return label
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
     * 开查看器并确认 overlay 身份（单次点击可能被 deck 发牌动画吞没——
     * run 42484538892 API37 viewer-overlay 超时；未确认即 BACK 回 deck 重试
     * 一轮，最后一轮失败如实抛出并留证）。
     */
    fun openViewerWithRetry(names: List<String>, overlayKeyFor: (String) -> String) {
        var lastError: IllegalStateException? = null
        repeat(2) { attempt ->
            val cardName = openViewerFromLargest(names)
            try {
                awaitViewerOverlay(overlayKeyFor(cardName))
                Log.i(TAG, "viewer confirmed for $cardName (attempt $attempt)")
                return
            } catch (e: IllegalStateException) {
                lastError = e
                Log.w(TAG, "viewer overlay not confirmed (attempt $attempt): ${e.message}")
                shell("input keyevent KEYCODE_BACK")
                SystemClock.sleep(800)
            }
        }
        throw lastError ?: IllegalStateException("viewer overlay not confirmed")
    }

    /** 系统设置的应用权限页（直接深链到权限列表）。 */
    val SETTINGS_PHOTOS_ENTRY_CANDIDATES = listOf("Photos and videos", "照片和视频")

    /**
     * 设置页 "Allow limited access" 行内的管理入口（gear 图标，desc=
     * "Settings"，run 42492426169 树 dump 实证：点行仅切模式不开选择器，
     * 改选须经此入口）。应用已处 PARTIAL 时该入口即活。
     */
    val SETTINGS_SELECTION_GEAR_DESC = "Settings"

    /**
     * 改选路径（裁决 (c)：ACTION_PICK_IMAGES 确认不更新持久 user-selected 集，
     * 双 API verdict 实证 + S4 佐证）——经系统设置页：App 权限页 →
     * "Photos and videos" → "Select photos" → 照片选择器（设置侧 grant 模式，
     * 当前选中集预勾选）→ 既有原语驱动/确认。官方文档背书的管理路径，
     * 无生产改动。
     */
    fun launchSettingsPhotosPage() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /**
     * App 权限页的宿主包（"Permissions" 行点击 → permissioncontroller
     * ManagePermissionsActivity；Google API 镜像 = com.google.android.permissioncontroller，
     * AOSP 镜像 = com.android.permissioncontroller）。run 43490249343 API36 实证：
     * App Info 页 SPA 渲染中段取到的 a11y bounds 落点为相邻 Notifications 行
     * （点击打开 AppNotificationSettings），仅凭 goneMarkers 消失判"已导航"
     * 会在错误页面误判成功 → 正向包名核验为裁决依据。
     */
    val PERMISSIONS_PAGE_PKGS = listOf(
        "com.google.android.permissioncontroller", "com.android.permissioncontroller"
    )

    /**
     * 带导航核验的点击（轮3/R13 实证：Settings 页行点击可能被吞没且无导航）：
     * 点击后等待 goneMarkers 全部消失（已离开当前页），未导航重试并轮换
     * ACTION_CLICK/坐标两种方式，至多 3 次；全部失败留证报错。
     *
     * verifyPkgs（正向核验，run 43490249343 API36 裁决）：goneMarkers 全部消失
     * 且 active window 包 ∈ verifyPkgs（或权限条目可见）才算到达；仅离开当前页
     * 但落在错误页面（如误触 Notifications 行 → AppNotificationSettings）时
     * 按 BACK 回到原页再重试——中途渲染的 a11y bounds 滞后实际布局，坐标
     * 回退点击可能命中相邻行（API37 同轮 attempt0 未导航、attempt1 成功；
     * API36 attempt0 误入通知页 → 误判成功 → photos 条目 40s 不可见红）。
     */
    fun tapWithNavigationVerify(
        candidates: List<String>, goneMarkers: List<String>, tag: String,
        attempts: Int = 3, verifyPkgs: List<String>? = null
    ) {
        repeat(attempts) { attempt ->
            // 候选等待（页面渲染需要时间；R14 实证 AppInfo 页 ~2-5s 才出节点）
            val waitDeadline = SystemClock.uptimeMillis() + 30000
            var node: AccessibilityNodeInfo? = null
            while (SystemClock.uptimeMillis() < waitDeadline && node == null) {
                node = findAny(ins.uiAutomation.rootInActiveWindow, candidates)
                if (node == null) SystemClock.sleep(150)
            }
            if (node == null) {
                dumpTree("missing-$tag")
                saveScreenshot("missing-$tag")
                error("$tag: candidate not visible after 30s wait — evidence saved")
            }
            if (attempt % 2 == 0) {
                clickNode(node, "$tag-attempt$attempt")
            } else {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                Log.i(TAG, "$tag-attempt$attempt clicked via coordinate")
            }
            val deadline = SystemClock.uptimeMillis() + 8000
            var leftOnWrongPage = false
            while (SystemClock.uptimeMillis() < deadline) {
                val root = ins.uiAutomation.rootInActiveWindow
                val leftPage = goneMarkers.all { marker -> find(root, marker) == null }
                val pkg = root?.packageName?.toString()
                val pkgOk = verifyPkgs == null || pkg in verifyPkgs ||
                    findAny(root, candidates) != null
                if (leftPage && pkgOk) {
                    Log.i(TAG, "$tag navigated after attempt $attempt (pkg=$pkg)")
                    return
                }
                if (leftPage && !pkgOk) leftOnWrongPage = true
                SystemClock.sleep(200)
            }
            if (leftOnWrongPage) {
                Log.w(TAG, "$tag attempt $attempt landed on wrong page; BACK and retry")
                dumpTree("wrong-page-$tag-attempt$attempt")
                shell("input keyevent 4")
                SystemClock.sleep(1000)
            } else {
                Log.w(TAG, "$tag attempt $attempt did not navigate; retrying")
            }
        }
        dumpTree("no-navigation-$tag")
        saveScreenshot("no-navigation-$tag")
        error("$tag: no navigation after $attempts attempts — evidence saved")
    }

    /**
     * 带滚动的候选点击 + 目标页核验（R-final API36 实证：权限页/设置页条目
     * 可能位于折叠区下方，find 不可见即找不到——滚动后重试；点击后等待
     * verifyMarker 出现确认已到达目标页）。返回是否成功到达。
     */
    fun tapAnyScrollableToMarker(
        candidates: List<String>, verifyMarker: String, tag: String,
        maxScrolls: Int = 4, timeoutMs: Long = 40000
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var scrolls = 0
        while (SystemClock.uptimeMillis() < deadline) {
            if (find(ins.uiAutomation.rootInActiveWindow, verifyMarker) != null) return true
            val node = findAny(ins.uiAutomation.rootInActiveWindow, candidates)
            if (node != null) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (!bounds.isEmpty) {
                    clickNode(node, tag)
                    val markerDeadline = SystemClock.uptimeMillis() + 8000
                    while (SystemClock.uptimeMillis() < markerDeadline) {
                        if (find(ins.uiAutomation.rootInActiveWindow, verifyMarker) != null) {
                            Log.i(TAG, "$tag reached target page (marker=$verifyMarker)")
                            return true
                        }
                        SystemClock.sleep(200)
                    }
                    Log.w(TAG, "$tag clicked but marker not shown; continuing polling")
                }
            } else if (scrolls < maxScrolls) {
                shell("input swipe 540 1900 540 700 300")
                scrolls++
                Log.i(TAG, "$tag scrolled ($scrolls/$maxScrolls)")
            }
            SystemClock.sleep(200)
        }
        return false
    }

    /** 打开 "Allow limited access" 行的选择管理入口（gear），进入选择器。 */
    fun tapSettingsSelectionGear(timeoutMs: Long = 15000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val gear = find(ins.uiAutomation.rootInActiveWindow, SETTINGS_SELECTION_GEAR_DESC)
            if (gear != null) {
                val bounds = Rect().also { gear.getBoundsInScreen(it) }
                if (!bounds.isEmpty) {
                    clickNode(gear, "settings-selection-gear")
                    return
                }
            }
            SystemClock.sleep(150)
        }
        dumpTree("missing-settings-gear")
        saveScreenshot("missing-settings-gear")
        error("settings selection gear (desc=) not found")
    }

    /**
     * 像素级勾选态读数（CI run 42481561828 决定性证据：选择器辅助树**不反映
     * 勾选变化**——abort 截图角标恰在预期两格、count=2，树读数恒为初始空态；
     * 点击实际生效，传感器失真）。截屏后采样各格左上角标区域，以**白色勾选
     * 笔画**为勾选判据（run 42484538892 修正：轮9 的瓷片色比对模型在 API36
     * 失效——未勾选格的渲染为内嵌圆角+背景填充，角部像素=网格背景而非瓷片
     * 色，且背景色恰好接近灰色哨兵；唯一跨 API 稳定的勾选标志=角标中的白色
     * 笔画，五种瓷片色/网格背景均无白色像素）。格位置取自辅助树边界（跨轮
     * 实证位置稳定准确），勾选态取自像素（实时无滞后）。
     */
    fun readCheckedStateByPixel(
        cells: List<AccessibilityNodeInfo>,
        @Suppress("UNUSED_PARAMETER") tileColors: List<Int>
    ): Set<Int> {
        val png = shellBytes("screencap -p")
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) {
            "screencap decode failed"
        }
        try {
            val checked = mutableSetOf<Int>()
            for (index in cells.indices) {
                val rect = Rect().also { cells[index].getBoundsInScreen(it) }
                val inset = (minOf(rect.width(), rect.height()) * 0.12f).toInt().coerceAtLeast(20)
                val cx = (rect.left + inset).coerceIn(0, bitmap.width - 1)
                val cy = (rect.top + inset).coerceIn(0, bitmap.height - 1)
                var white = 0
                for (x in (cx - 15).coerceAtLeast(0)..(cx + 15).coerceAtMost(bitmap.width - 1)) {
                    for (y in (cy - 15).coerceAtLeast(0)..(cy + 15).coerceAtMost(bitmap.height - 1)) {
                        val p = bitmap.getPixel(x, y)
                        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                        if (r > 210 && g > 210 && b > 210) white++
                    }
                }
                Log.i(TAG, "pixelCell[$index] whiteIn31x31=$white at=($cx,$cy)")
                if (white >= 15) checked += index
            }
            return checked.toSet()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 驱动勾选集合到预期：像素读数为准（辅助树不反映勾选变化），差集矫正
     * 至多 3 轮，每轮矫正后像素轮询（实时）直至达标。不收敛留证失败；
     * 地面真值断言（授权后应用内查询）仍是最终裁决。
     */
    fun driveCheckedCells(expected: Set<Int>, tileColors: List<Int>, timeoutMs: Long = 90000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var rounds = 0
        while (true) {
            val cells = collectGridCells(minCount = tileColors.size)
            check(cells.size == tileColors.size) {
                "grid cell count ${cells.size} != fixture count ${tileColors.size} — mapping undefined"
            }
            val checked = readCheckedStateByPixel(cells, tileColors)
            Log.i(TAG, "driveChecked round=$rounds pixelChecked=$checked expected=$expected")
            if (checked == expected) return
            if (SystemClock.uptimeMillis() > deadline || rounds >= 3) {
                dumpTree("drive-checked-mismatch")
                saveScreenshot("drive-checked-mismatch")
                error("checked cells not converged: pixelChecked=$checked expected=$expected " +
                    "after $rounds corrective rounds — evidence saved")
            }
            rounds++
            val taps = (checked - expected).sorted() + (expected - checked).sorted()
            taps.forEach { tapCell(it, cells, purpose = "drive-selection") }
            // 点击后像素轮询（截屏为实时画面，无辅助树滞后问题）
            val pollDeadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < pollDeadline && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(400)
                val freshCells = collectGridCells(minCount = tileColors.size)
                if (readCheckedStateByPixel(freshCells, tileColors) == expected) return
            }
        }
    }

    /** 候选任一出现的截止时间等待（系统对话框出现判定）。 */
    fun awaitAny(candidates: List<String>, tag: String, timeoutMs: Long = 30000) {
        await(tag, { findAny(ins.uiAutomation.rootInActiveWindow, candidates) != null }, timeoutMs)
    }
}
