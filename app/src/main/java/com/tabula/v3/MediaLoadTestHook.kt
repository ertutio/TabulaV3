package com.tabula.v3

import android.util.Log
import com.tabula.v3.BuildConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch

/**
 * 旧扫描晚返回设备验收（2026-11-15，Unit B）：加载-提交链专用可控暂停钩子
 * （仅 DEBUG 生效；隔离口径与 GenieDeleteAnimTestHook 先例（GenieEffect.kt
 * T06 场景 c）同款——先例注释风格对齐）。
 *
 * 目的：StaleScanLateReturnDeviceTest 需要"旧扫描已进入读取且读取已返回、
 * 结果在手上停在可控位"的确定性注入窗——生产 load（loadAllData 毫秒级）无
 * 自然注入点。本钩子在 loadAndApplyMediaSnapshot 的 withContext(Dispatchers.IO)
 * 块内部（albumManager.initialize 之后、Pair 投影返回之前）提供一个一次性
 * 暂停点：武装后首个到达的扫描在此挂起，测试完成真实权限变化与新扫描提交后
 * 再释放，使"旧读取确实返回但提交被恢复期取消检查拦截"的竞态形态在真实生产
 * 链上成立。
 *
 * 关键语义（取消纪律，与生产契约等价）：
 * - park 必须以 withContext(NonCancellable) 承载等待——模拟"底层读取不可中断"
 *   的晚返回形态：武装态消费后到达的取消被推迟（键控重启的 CancellationException
 *   不打断挂起中的读取）；释放后暂停点返回，由**外层 withContext(Dispatchers.IO)
 *   的恢复期取消检查**（DispatchedCoroutine 完成协议对已取消调用方的
 *   CancellableMode resumption）生效——旧扫描以 CancellationException 原样退出，
 *   apply/onError 均不为被取消扫描执行。若把 park 移到 IO 块之外（设计已否决），
 *   恢复后与 apply 之间无取消检查点，守卫会被接缝自身绕过。
 * - 一次性门：armOnce() 武装；首个到达 park 的扫描消费武装态并暂停（后续扫描
 *   直通，不受影响）；release() 释放被暂停者。同一时刻至多一个扫描被暂停，
 *   杜绝多扫描叠加暂停的未定态。
 *
 * Release 构建：armOnce/release/reset/parkBeforeCommit/parkedRows/isReleased 全部
 * BuildConfig.DEBUG 门，Release 直接返回（park 不挂起、查询返回 -1/false），
 * 生产行为零变化。
 *
 * 事件面语义（三事件登记，与 StaleScanLateReturnDeviceTest KDoc 映射一致）：
 * - "parked rows=N" = 暂停事件（读取已返回、投影规模 N 的扫描停在接缝）；
 * - "resumed rows=N" = **接缝恢复 + 读取返回双事件**（同一行：park 返回 = 接缝
 *   暂停结束；rows=N 即旧读取返回的投影规模——释放后旧读取确实浮出的唯一
 *   日志面证据，判读时两事件不可拆分）；
 * - 提交处理事件对被取消扫描为**缺席面**（不可注入约束：设备层 apply 无观测
 *   点）——由消费测试以"释放标记后无新 2 行批次事件 + 无 error 行 + 终态断言"
 *   承载，不在本钩子日志面呈现。
 */
object MediaLoadTestHook {
    private const val TAG = "MediaLoadTestHook"

    @Volatile private var gate: CountDownLatch? = null
    @Volatile private var gateArmed = false
    @Volatile private var parkRows: Int = -1
    @Volatile private var released = false

    /** 测试入口（一次性武装）：下一个到达 park 的扫描暂停；此前已过接缝的扫描不受影响。 */
    fun armOnce() {
        if (!BuildConfig.DEBUG) return
        synchronized(this) {
            check(gate == null || released) { "previous media-load gate must be released or reset" }
            gate = CountDownLatch(1)
            gateArmed = true
            released = false
            parkRows = -1
        }
        Log.i(TAG, "armed")
    }

    /** 测试入口：释放被暂停的扫描（其返回路径的取消检查归生产链，本钩子不吞不裹）。 */
    fun release() {
        if (!BuildConfig.DEBUG) return
        val releasedGate = synchronized(this) {
            released = true
            gateArmed = false
            gate
        }
        releasedGate?.countDown()
        Log.i(TAG, "released")
    }

    /** 测试生命周期复位：释放旧等待者并清除尚未消费的武装态。 */
    fun reset() {
        if (!BuildConfig.DEBUG) return
        val previousGate = synchronized(this) {
            val previous = gate
            gate = null
            gateArmed = false
            parkRows = -1
            released = false
            previous
        }
        previousGate?.countDown()
        Log.i(TAG, "reset")
    }

    /**
     * 生产接缝（loadAndApplyMediaSnapshot 的 IO 块内调用）：武装态消费式暂停。
     * rows = 本次投影的选中集合规模（parkedRows 的断言输入：暂停者 = 旧选择
     * 集合时代扫描且其读取已返回）。
     */
    suspend fun parkBeforeCommit(rows: Int) {
        if (!BuildConfig.DEBUG) return
        val parkedGate = synchronized(this) {
            if (!gateArmed) return
            gateArmed = false
            parkRows = rows
            gate ?: return
        }
        Log.i(TAG, "parked rows=$rows")
        withContext(NonCancellable) {
            // 捕获本次门，reset/release 与新 arm 交错也只释放对应等待者。
            parkedGate.await()
            Log.i(TAG, "resumed rows=$rows")
        }
    }

    /** 暂停者投影规模（调试观测；非 DEBUG 返回 -1）。 */
    fun parkedRows(): Int = if (BuildConfig.DEBUG) parkRows else -1

    /** 释放是否已执行（调试观测；非 DEBUG 返回 false）。 */
    fun isReleased(): Boolean = if (BuildConfig.DEBUG) released else false
}

