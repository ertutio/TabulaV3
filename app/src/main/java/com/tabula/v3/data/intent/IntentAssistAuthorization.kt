package com.tabula.v3.data.intent

/**
 * 文字辅助的**批次授权**模型（#95 第 3 轮审计，第 2 条）。
 *
 * 审计要求把两种「重算」分开：
 *
 * | 触发 | 语义 | 授权怎么处理 |
 * | --- | --- | --- |
 * | 用户在当前批主动开启/关闭辅助 | 同一次授权下的参数变化 | **保留**当前授权 |
 * | 提交、长期保留、来源范围变化、手动重新分析 | 换了一个批次 | **吊销**授权，按本地模式重建 |
 *
 * 为什么必须分开：把「换批」也当成「保留授权」，用户上一批自愿打开的开关会
 * 悄悄作用到新一批上——那不是他在新批次里做出的同意。反过来，把「用户自己按开关」
 * 也吊销授权，就等于他刚打开就被系统关掉，功能没法用。
 *
 * 这个类刻意做成纯状态机：`MainActivity` 只负责调用 [onUserToggle] /
 * [onRebuild]，界面与测试都读同一份状态，不会出现「界面显示关闭、实际还在发请求」。
 */
class IntentAssistAuthorization(
    /** 用户是否在当前批主动开启了辅助。 */
    initiallyGranted: Boolean = false,
    /** 用户是否已在本批看过「只发送脱敏文字」的说明。 */
    initiallyAcknowledged: Boolean = false
) {

    var granted: Boolean = initiallyGranted
        private set

    var noticeAcknowledged: Boolean = initiallyAcknowledged
        private set

    /** 授权代际：每次吊销 +1。在途请求带旧代际返回时按过期处理。 */
    var epoch: Int = 0
        private set

    /** 反馈消息里用的简短状态（不含任何图像或原文）。 */
    var lastTransition: Transition = Transition.INITIAL
        private set

    enum class Transition {
        INITIAL,
        USER_ENABLED,
        USER_DISABLED,
        /** 换批重建：授权被吊销。 */
        REVOKED_ON_REBUILD
    }

    /** 用户点开关（或确认弹窗里点「开启」）。 */
    fun onUserToggle(enabled: Boolean) {
        granted = enabled
        lastTransition = if (enabled) Transition.USER_ENABLED else Transition.USER_DISABLED
    }

    /** 用户确认了外发说明。 */
    fun acknowledgeNotice() {
        noticeAcknowledged = true
    }

    /**
     * 重建批次（提交 / 长期保留 / 范围变化 / 手动重新分析 / 进入新批）。
     *
     * 吊销授权 + 代际 +1；`noticeAcknowledged` 也清掉——新批次要让用户重新看到边界说明。
     */
    fun onRebuild() {
        granted = false
        noticeAcknowledged = false
        epoch++
        lastTransition = Transition.REVOKED_ON_REBUILD
    }

    /** 当前这一刻是否应真的把文字发出去（还需要 Key 存在，由调用方叠加）。 */
    val effective: Boolean get() = granted

    /** 在途请求是否已过期。 */
    fun isStale(requestEpoch: Int): Boolean = requestEpoch != epoch
}
