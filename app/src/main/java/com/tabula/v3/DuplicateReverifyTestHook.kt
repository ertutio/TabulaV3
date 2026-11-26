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

    // ==================== 可控时序（#88 四轮） ====================

    /**
     * 摘要读取闸门：置为 true 时，复核在**开始读摘要之前**阻塞，
     * 直到测试放行。用来制造"用户已点删除、后台扫描趁机清掉提示"
     * 这个真实存在但难以自然复现的时序。
     *
     * 仅 DEBUG 生效；Release 下 [gateBeforeDigest] 直接返回。
     */
    @Volatile
    private var gateArmed = false

    @Volatile
    private var gateOpen = true

    @Volatile
    private var reachedGate = false

    fun armGate() {
        if (!BuildConfig.DEBUG) return
        gateArmed = true
        gateOpen = false
        reachedGate = false
    }

    /** 复核是否已经走到闸门（测试据此确认时序到位，而不是靠 sleep 猜）。 */
    fun hasReachedGate(): Boolean = reachedGate

    fun openGate() {
        gateOpen = true
    }

    fun disarmGate() {
        gateArmed = false
        gateOpen = true
    }

    /**
     * 复核在计算摘要之前调用。闸门打开时立即返回；关闭时原地等待。
     *
     * @return true 表示确实在这里等过（测试可据此确认时序）
     */
    fun gateBeforeDigest() {
        if (!BuildConfig.DEBUG || !gateArmed) return
        reachedGate = true
        android.util.Log.i(TAG, "reached digest gate; waiting for release")
        val deadline = System.currentTimeMillis() + 30_000
        while (!gateOpen && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        android.util.Log.i(TAG, "digest gate released")
    }
}
