package com.tabula.v3

import com.tabula.v3.BuildConfig

/**
 * 删除前副本复核的观测点（#88 三轮，仅 DEBUG）。
 *
 * ## 为什么需要
 *
 * 「副本变化时中止删除」的**用户可见反馈**是一个 Toast。Toast 不会出现在
 * AccessibilityNode 树里，`screenTexts()` 读不到它，于是设备用例只能靠
 * "回收站记录数没变"间接推断——那无法区分"复核拦下了"和"删除压根没触发"。
 *
 * 这里把复核结论记在进程内，用例直接断言生产代码**实际做出的决定**，
 * 与 [PendingDeleteTestHook] 同款隔离口径：Release 下 [record] 直接返回。
 */
object DuplicateReverifyTestHook {
    private const val TAG = "DupReverifyHook"

    data class Verdict(
        /** 本次要删的照片身份键 */
        val targetKeys: Set<String>,
        /** 复核是否通过（true = 可以继续删除） */
        val passed: Boolean,
        /** 被判为失效的提示键（副本已变/已删/不可读） */
        val invalidatedKeys: Set<String>,
        /** 复核后提示里还剩多少条 */
        val remainingHintKeys: Int
    )

    private val verdicts = java.util.Collections.synchronizedList(mutableListOf<Verdict>())

    fun record(verdict: Verdict) {
        if (!BuildConfig.DEBUG) return
        verdicts += verdict
        android.util.Log.i(
            TAG,
            "verdict passed=${verdict.passed} invalidated=${verdict.invalidatedKeys.size} " +
                "remaining=${verdict.remainingHintKeys}"
        )
    }

    fun snapshot(): List<Verdict> = verdicts.toList()

    fun reset() {
        verdicts.clear()
    }
}
