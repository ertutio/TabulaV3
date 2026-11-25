package com.tabula.v3.data.balance

/**
 * 批次边界标识（#90 第四轮审计）。
 *
 * ## 要解决的问题
 *
 * 删除和归档都是**延迟生效**的：删除走撤销 Snackbar，归档走"待提交动作"
 * 等 Snackbar 消失才真正复制。用户在旧批次上发起动作、动作在换批之后才完成，
 * 旧实现会在完成时把它记进**当时**的会话统计——新批次的来源判断被旧动作
 * 污染，提示因此可能问一个用户在这一批根本没碰过的相册。
 *
 * ## 做法
 *
 * 给"普通随机漫步的会话"和"页面实际展示的批次"各一个稳定标识：
 *
 * - [Ticket.sessionId]：一次整理会话。退出整理、切换专清/相似推荐、重进
 *   卡片页都会换新 id；
 * - [Ticket.batchId]：页面当前真正展示的那一批。**只有**手动「再来一组」和
 *   旧批全部失效后的自动重取会换新 id（见 [BatchBoundary]）。
 *
 * 动作在**发起时**把 ticket 绑定下来（[Ticket.capture]），随延迟动作一路带到
 * 提交/落库那一刻；成功时用 [Ticket.isStillCurrent] 判断"发起时的会话与批次
 * 是否仍然有效"，只有仍然有效才计入当前统计。失败与撤销不判断、也不记账。
 *
 * 纯数据 + 纯判定，无 Android 依赖。
 */
data class Ticket(
    val sessionId: Long,
    val batchId: Long
) {
    /** 绑定当前会话与批次（在**用户发起动作时**调用一次，之后只读携带）。 */
    fun capture(ids: SessionBatchIds): Ticket = Ticket(ids.sessionId, ids.batchId)

    /**
     * 发起时的会话与批次是否仍然是当前那一个。
     *
     * 两者任一变化都算过期：换会话意味着统计已清零重来，换批次意味着
     * 这条动作属于上一批，不该计入新批。
     */
    fun isStillCurrent(ids: SessionBatchIds): Boolean =
        sessionId == ids.sessionId && batchId == ids.batchId

    companion object {
        /** 尚未建立会话/批次时的占位（永远不会与真实 id 相等）。 */
        val None = Ticket(sessionId = -1L, batchId = -1L)
    }
}

/**
 * 当前有效的会话与批次标识。
 *
 * 由界面层持有并递增；动作发起时通过 [Ticket.capture] 取一份快照带走。
 */
data class SessionBatchIds(
    val sessionId: Long = 0L,
    val batchId: Long = 0L
) {
    /** 新会话（退出整理 / 切模式 / 重进卡片页）。 */
    fun newSession(): SessionBatchIds =
        SessionBatchIds(sessionId = sessionId + 1, batchId = batchId + 1)

    /** 同一会话内切到新的一批（手动「再来一组」/ 旧批全失效后的自动重取）。 */
    fun newBatch(): SessionBatchIds =
        SessionBatchIds(sessionId = sessionId, batchId = batchId + 1)
}

/**
 * 哪些变化算"真正换批"（#90 第四轮审计的边界定义）。
 *
 * 审计要求把边界收在一处，避免每个装载点各自判断：
 *
 * | 触发 | 是否换批 |
 * | --- | --- |
 * | 手动「再来一组」 | **是** |
 * | 旧批因图库变化/权限收窄而全部失效后的自动重取 | **是** |
 * | 删掉单张卡片 | 否（同批内容变化） |
 * | 撤销删除 / 撤销归档 | 否（同批内容变化） |
 * | 同批快照刷新（回前台、外部增删但批内仍有存活行） | 否 |
 * | 批量装载/重装同一批内容（首次装载、断点恢复） | 否 |
 */
enum class BatchChange {
    /** 手动「再来一组」：用户主动进入下一批。 */
    MANUAL_NEXT_BATCH,

    /** 旧批全部失效后的自动重取：批次已空，必须重新取。 */
    AUTO_REFILL_AFTER_INVALIDATION,

    /** 删掉单张卡片（含撤销删除）：同一批，只是内容变了。 */
    IN_BATCH_CARD_REMOVED,

    /** 归档撤销：同一批，只是把卡片放回来。 */
    IN_BATCH_ARCHIVE_UNDONE,

    /** 同批快照刷新（回前台、外部增删后仍有存活行）：同一批。 */
    IN_BATCH_SNAPSHOT_REFRESH,

    /** 首次装载 / 切换模式后的重装：建立批次，不算"换掉上一批"。 */
    INITIAL_LOAD
    ;

    /**
     * 这个变化是否开启新的批次边界。
     *
     * 只有前两项为真；其余都保持当前批次标识不变，因此旧批次上发起的
     * 延迟动作不会被误判成"跨批"或"仍有效"。
     */
    val startsNewBatch: Boolean
        get() = when (this) {
            MANUAL_NEXT_BATCH, AUTO_REFILL_AFTER_INVALIDATION -> true
            IN_BATCH_CARD_REMOVED,
            IN_BATCH_ARCHIVE_UNDONE,
            IN_BATCH_SNAPSHOT_REFRESH,
            INITIAL_LOAD -> false
        }

    /** 这个变化是否清掉上一批的统计与提示。 */
    val clearsPreviousBatchStats: Boolean get() = startsNewBatch
}
