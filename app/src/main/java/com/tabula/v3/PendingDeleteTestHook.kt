package com.tabula.v3

import com.tabula.v3.BuildConfig
import com.tabula.v3.data.balance.Ticket

/**
 * 待删除动作记账观测（#90 第五轮，仅 DEBUG）。
 *
 * ## 为什么需要
 *
 * 「旧批待删除动作计入新批次」这个缺陷在**接线层**：凭证本该在上滑时绑定，
 * 旧实现却在提交那一刻才调 `capturedTicket()`。只看规则函数永远测不出来
 * （规则本身是对的），必须观察生产侧**实际做出的那次决策**。
 *
 * 与 [MediaLoadTestHook] 同款隔离口径：Release 构建下 [record] 直接返回，
 * 观测列表不增长，生产行为零变化。
 *
 * ## 记录面
 *
 * 每条 = 一次待删除记账决策：
 * - `imageKey`：被删照片的完整身份键（`卷|ID`）
 * - `ticketBatch`：凭证里的 batchId（应当 = 上滑那一刻的批次）
 * - `currentBatch`：提交这一刻的 batchId
 * - `recorded`：是否真的计入了来源统计
 *
 * 设备用例据此断言：跨批时 `recorded == false`，同批时 `true`。
 */
object PendingDeleteTestHook {
    private const val TAG = "PendingDeleteHook"

    data class Decision(
        val imageKey: String,
        val ticketSession: Long,
        val ticketBatch: Long,
        val currentSession: Long,
        val currentBatch: Long,
        val recorded: Boolean
    )

    private val decisions = java.util.Collections.synchronizedList(mutableListOf<Decision>())

    /** 生产接缝：每次待删除记账决策后登记一条。 */
    fun record(
        imageKey: String,
        ticket: Ticket,
        currentSession: Long,
        currentBatch: Long,
        recorded: Boolean
    ) {
        if (!BuildConfig.DEBUG) return
        decisions += Decision(
            imageKey = imageKey,
            ticketSession = ticket.sessionId,
            ticketBatch = ticket.batchId,
            currentSession = currentSession,
            currentBatch = currentBatch,
            recorded = recorded
        )
        android.util.Log.i(
            TAG,
            "decision key=$imageKey ticket=${ticket.sessionId}/${ticket.batchId} " +
                "current=$currentSession/$currentBatch recorded=$recorded"
        )
    }

    /** 测试入口：读取并清空（避免用例之间互相污染）。 */
    fun drain(): List<Decision> {
        if (!BuildConfig.DEBUG) return emptyList()
        synchronized(decisions) {
            val copy = decisions.toList()
            decisions.clear()
            return copy
        }
    }

    /** 测试入口：清空。 */
    fun reset() {
        if (!BuildConfig.DEBUG) return
        decisions.clear()
    }
}
