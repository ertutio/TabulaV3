package com.tabula.v3

import android.graphics.Rect
import android.os.SystemClock
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
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    /** 夹具名前缀（removeCard 防呆：只允许滑动本套件夹具卡）。 */
    const val FIXTURE_PREFIX = "fpf-"

    /** 月份专清真实入口：选择图集 → 月份 → 目标月 → 开始整理 → sheet 关闭核验。 */
    fun startMonth(monthName: String) {
        PartialSelectionUi.tap("选择图集")
        PartialSelectionUi.tap("月份")
        PartialSelectionUi.tap(monthName)
        PartialSelectionUi.tap("开始整理")
        // sheet 关闭核验（无固定 sleep；批次加载由调用方 waitFor 具体卡名收口）
        awaitGone("开始整理", tag = "month-sheet-dismissed")
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
     * 核验 = sheet 头部文本消失（"选择图集进行整理"，surface 唯一渲染文本，
     * 不与 TopBar"选择图集"混淆）。
     */
    fun exitCleanupViaSheet() {
        PartialSelectionUi.tap("选择图集")
        PartialSelectionUi.tap("全局")
        awaitGone("选择图集进行整理", tag = "cleanup-sheet-dismissed")
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
