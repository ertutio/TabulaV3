package com.tabula.v3

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.ui.components.MonthInfo
import java.io.FileInputStream

/** Real activity and gestures only. No cleanup-state mutation or replacement callbacks. */
internal object FullRecoveryUi {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    fun launch() {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        shell("am start -n ${ctx.packageName}/com.tabula.v3.MainActivity")
    }

    fun startMonth(month: MonthInfo) {
        tap("选择图集")
        tap("月份")
        tap(month.displayName)
        tap("开始整理")
        await("month sheet dismissed") { find(ins.uiAutomation.rootInActiveWindow, "开始整理") == null }
        SystemClock.sleep(500)
    }

    fun waitFor(label: String): AccessibilityNodeInfo {
        await(label) { find(ins.uiAutomation.rootInActiveWindow, label) != null }
        return requireNotNull(find(ins.uiAutomation.rootInActiveWindow, label))
    }

    fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        val directory = requireNotNull(ctx.getExternalFilesDir(null)).resolve("cloud-ui-failures")
        check(directory.mkdirs() || directory.isDirectory)
        ins.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                directory.resolve("full-recovery-${SystemClock.uptimeMillis()}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                bitmap.recycle()
            }
        }
        error("FULL recovery timed out waiting for $label")
    }

    fun removeCard(displayName: String) {
        check(displayName.startsWith(FullRecoveryStateContract.SEED_PREFIX))
        val bounds = Rect()
        waitFor(displayName).getBoundsInScreen(bounds)
        check(!bounds.isEmpty)
        shell("input swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX()} ${bounds.top.coerceAtLeast(80)} 500")
    }

    private fun tap(label: String) {
        val bounds = Rect()
        waitFor(label).getBoundsInScreen(bounds)
        check(!bounds.isEmpty)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
        for (index in 0 until node.childCount) {
            find(node.getChild(index), label)?.let { return it }
        }
        return null
    }

    private fun shell(command: String): String =
        ins.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
}
