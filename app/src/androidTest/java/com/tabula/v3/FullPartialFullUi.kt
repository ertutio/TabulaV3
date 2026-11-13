package com.tabula.v3

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/**
 * T07 FULL→PARTIAL→FULL 三阶段验证的 deck 驱动原语（真实 UI、无生产改动）。
 *
 * 通用原语（launch/tap/waitFor/awaitGone/await/shell/shellScript/截屏+树 dump 证据）
 * 全部复用 [PartialSelectionUi]（#57 现成，含文件名 sanitize）；本对象只补 deck 侧
 * 三个缺口：月份专清进入、卡片上滑处理、经真实 sheet 退出清理模式。
 * 等待一律有截止时间；失败经 PartialSelectionUi 留证（截图+树 dump，文件名不含 `|`）。
 */
internal object FullPartialFullUi {
    private const val TAG = "FullPartialFullUi"
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    /** 夹具名前缀（removeCard 防呆：只允许滑动本套件夹具卡）。 */
    const val FIXTURE_PREFIX = "fpf-"

    /**
     * 月份专清真实入口：选择图集 → 月份 → 目标月（月行 a11y 不可达时重开 sheet
     * 重试）→ 开始整理 → sheet 关闭核验。
     *
     * CI run 43049377860（API36 Phase3）实证：sheet 已切月份模式、月行渲染在屏
     * （留证截屏），但 a11y 树 30s 不含月行文本——dialog 内 LazyColumn 列表项语义
     * 更新丢失（分段控件静态文本可达，列表项不可达；R1 API36 留证同构）。定向
     * 恢复 = 重开 sheet（Close sheet = 生产 onDismissRequest 路径）强制重组重发
     * 语义，至多 3 轮；全程真实 UI 路径，无固定 sleep，逐轮留证。
     */
    fun startMonth(monthName: String) {
        PartialSelectionUi.tap("选择图集")
        PartialSelectionUi.tap("月份")
        var selected = false
        for (attempt in 0 until 3) {
            if (tapMonthIfExposed(monthName, timeoutMs = 10000)) {
                selected = true
                break
            }
            Log.w(TAG, "startMonth: '$monthName' not reachable via a11y (attempt $attempt)")
            if (attempt < 2) {
                Log.i(TAG, "startMonth: reopening cleanup sheet to force fresh semantics")
                reopenCleanupSheet()
            }
        }
        if (!selected) {
            PartialSelectionUi.dumpTree("timeout-month-$monthName")
            PartialSelectionUi.saveScreenshot("timeout-month-$monthName")
            error("month row never reachable via a11y after sheet reopen retries: $monthName")
        }
        // 点击+推进核验+重试：sheet 退场动画期内"开始整理"合法可见（过渡语义，
        // 不再首见即杀）；批次加载由调用方 waitFor 具体卡名收口
        tapAndAwaitDismissal("开始整理", "month-sheet-dismissed")
    }

    /**
     * 上滑处理卡片（FullRecoveryUi.removeCard 同款手势：中心 → 顶部，500ms）。
     * 生产路径：deck onRemove → 撤销 Snackbar → 超时提交 → 回收站 + acknowledged。
     */
    fun removeCard(displayName: String) {
        check(displayName.startsWith(FIXTURE_PREFIX)) {
            "removeCard 只允许处理本套件夹具卡：$displayName"
        }
        val node = PartialSelectionUi.waitFor(displayName)
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        check(!bounds.isEmpty) { "card $displayName has empty bounds" }
        PartialSelectionUi.shell(
            "input swipe ${bounds.centerX()} ${bounds.centerY()} " +
                "${bounds.centerX()} ${bounds.top.coerceAtLeast(80)} 500"
        )
    }

    /**
     * 经真实 sheet 退出清理模式（月份清理视图无 BACK 出口——#57 实证）：
     * 选择图集 → 分段控件"全局"（onSwitchToGlobal 生产路径：保存断点 + 退出清理）。
     * 核验链（coord/notes-B2.md §7.3）："全局"点击+推进核验（ModalBottomSheet 退场
     * 动画 ~300-500ms 内头部"选择图集进行整理"合法可见——awaitGone 首见即杀会把
     * 成功路径误杀，CI run 42666340050 实证）→ sheet 头部文本最终消失 → DECK_MARKER
     * 回归（生产退出完成核验）。
     */
    fun exitCleanupViaSheet() {
        PartialSelectionUi.tap("选择图集")
        tapAndAwaitDismissal("全局", "cleanup-exit-global")
        awaitDismissed("选择图集进行整理", "cleanup-sheet-dismissed", timeoutMs = 8000)
        PartialSelectionUi.waitFor(PartialSelectionUi.DECK_MARKER, timeoutMs = 15000)
    }

    /** 集合外/已处理媒体不得出现：截止时间内节点必须始终缺席（出现即失败留证）。 */
    fun awaitGone(label: String, tag: String, timeoutMs: Long = 10000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (find(ins.uiAutomation.rootInActiveWindow, label) != null) {
                PartialSelectionUi.dumpTree("unexpected-$tag")
                PartialSelectionUi.saveScreenshot("unexpected-$tag")
                error("unexpectedly visible: $label ($tag)")
            }
            SystemClock.sleep(100)
        }
    }

    /**
     * 等待节点**最终消失**（推进语义，与 [awaitGone] 的不变量语义相对）：
     * ModalBottomSheet 退场动画 / 卡片上滑动画期内节点合法可见（成功路径也如此），
     * 故允许有界过渡——节点先保持可见，消失后在 [observeMs] 观察窗内不得重现；
     * 观察窗内重现（退场未完成/被重开）则继续等下一次消失，直至截止。
     * 截止后仍可见 → 留证报错。
     */
    fun awaitDismissed(
        label: String,
        tag: String,
        timeoutMs: Long = 12000,
        observeMs: Long = 1500
    ) {
        if (pollFinallyGone(label, timeoutMs, observeMs)) {
            Log.i(TAG, "$tag: '$label' finally dismissed (observe=${observeMs}ms)")
            return
        }
        PartialSelectionUi.dumpTree("timeout-dismissed-$tag")
        PartialSelectionUi.saveScreenshot("timeout-dismissed-$tag")
        error("node never finally dismissed: $label ($tag)")
    }

    /**
     * 点击 + 推进核验 + 重试（CI 实证单次 tap 可能被吞没——
     * tapDialogActionAndAwaitPicker 同款纪律）：点击后按 [awaitDismissed] 语义
     * 等待最终消失，未推进则重试（waitFor 重新定位节点），至多 [attempts] 次；
     * 全部失败留证报错。
     */
    fun tapAndAwaitDismissal(
        label: String,
        tag: String,
        attempts: Int = 3,
        perAttemptMs: Long = 8000
    ) {
        repeat(attempts) { attempt ->
            val node = PartialSelectionUi.waitFor(label)
            PartialSelectionUi.clickNode(node, "$tag-attempt$attempt")
            if (pollFinallyGone(label, perAttemptMs, 1500)) {
                Log.i(TAG, "$tag: '$label' dismissed after attempt $attempt")
                return
            }
            Log.w(TAG, "$tag attempt $attempt did not dismiss '$label'; retrying")
        }
        PartialSelectionUi.dumpTree("dismissal-not-advanced-$tag")
        PartialSelectionUi.saveScreenshot("dismissal-not-advanced-$tag")
        error("$tag: '$label' did not dismiss after $attempts click attempts — evidence saved")
    }

    /** 最终消失轮询（不抛错）：先等消失，再观察窗确认不重现；截止前完成=成功。 */
    private fun pollFinallyGone(label: String, timeoutMs: Long, observeMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (find(ins.uiAutomation.rootInActiveWindow, label) == null) {
                var stable = true
                val observeEnd = SystemClock.uptimeMillis() + observeMs
                while (SystemClock.uptimeMillis() < observeEnd) {
                    if (find(ins.uiAutomation.rootInActiveWindow, label) != null) {
                        stable = false
                        break
                    }
                    SystemClock.sleep(100)
                }
                if (stable) return true
            }
            SystemClock.sleep(100)
        }
        return false
    }

    /** 月行 a11y 可达即点击（有界轮询，不抛错）；点击经 clickNode 含祖先回退与坐标回退。 */
    private fun tapMonthIfExposed(monthName: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val root = ins.uiAutomation.rootInActiveWindow
            val node = root?.let { find(it, monthName) }
            if (node != null) {
                PartialSelectionUi.clickNode(node, "month-select-$monthName")
                return true
            }
            SystemClock.sleep(150)
        }
        return false
    }

    /**
     * 关闭并重开清理 sheet（月行 a11y 语义丢失的定向恢复）：scrim "Close sheet"
     * = 生产 onDismissRequest 路径；关闭核验 = sheet 头部最终消失（有界，失败
     * 留证报错）；重开后 sheet 回到默认图集模式，重新切月份模式。
     */
    private fun reopenCleanupSheet() {
        PartialSelectionUi.tapAny(listOf("Close sheet"), "cleanup-sheet-reopen-close")
        if (!pollFinallyGone("选择图集进行整理", timeoutMs = 5000, observeMs = 500)) {
            PartialSelectionUi.dumpTree("cleanup-sheet-reopen-not-closed")
            PartialSelectionUi.saveScreenshot("cleanup-sheet-reopen-not-closed")
            error("cleanup sheet did not close on reopen attempt")
        }
        PartialSelectionUi.tap("选择图集")
        PartialSelectionUi.tap("月份")
    }

    /** 精确匹配等待（月份名等短标签避免 contains 误命中）。 */
    fun waitForExact(label: String, timeoutMs: Long = 30000): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val node = findExact(ins.uiAutomation.rootInActiveWindow, label)
            if (node != null) return node
            SystemClock.sleep(100)
        }
        PartialSelectionUi.dumpTree("timeout-exact-$label")
        PartialSelectionUi.saveScreenshot("timeout-exact-$label")
        error("timed out waiting for exact node: $label")
    }

    private fun findExact(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString() == label ||
                    node.contentDescription?.toString() == label)) return node
        for (i in 0 until node.childCount) findExact(node.getChild(i), label)?.let { return it }
        return null
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString()?.contains(label) == true ||
                    node.contentDescription?.toString()?.contains(label) == true)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }

    /** 截止轮询条件等待（失败留证后抛出）。 */
    fun await(label: String, timeoutMs: Long = 30000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        PartialSelectionUi.dumpTree("timeout-$label")
        PartialSelectionUi.saveScreenshot("timeout-$label")
        error("timed out waiting for $label")
    }

    fun launchApp() {
        PartialSelectionUi.launch()
    }
}
