package com.tabula.v3.ui.components.liquidglass

/**
 * 查看器顶栏此刻**实际会走哪条渲染路径**，以及不采样时的真实原因。
 *
 * ## 为什么需要这个类型（而不是让测试自己算）
 *
 * 回退审计链路原先读的是 [LiquidGlassBackdropState.lastPlan]。但那是**玻璃节点**
 * 上报的读数，而 `viewerCanSample = false` 时顶栏根本不再渲染玻璃节点——
 * 没有节点上报，`lastPlan` 就**停在上一轮的采样结果**上。于是审计行会写着
 * `sampled=true reason=NONE`，而屏幕上的按钮早已是回退后的普通样式。
 * 设备测试若读这一行，会把"正在回退"读成"仍在采样"，反向也会误判。
 *
 * 因此这里把"顶栏走哪条路"收敛成一个值：由**同一个** [decision] 同时驱动
 * 顶栏分支与审计行输出，二者不可能分叉。它来自生产渲染决策本身，
 * 不是测试侧重算的期望值。
 *
 * ## 两层门控的顺序
 *
 * 1. [attachGateReason]：要不要接背景（实验室开关 / 系统门槛 / 有没有照片）；
 * 2. [syncReason]：接了背景之后，此刻能不能采（翻页中、放大、拖动）。
 *
 * 第 1 层不通过时第 2 层无意义（没有玻璃节点可回退），所以原因以第 1 层为准。
 *
 * @param attachGateReason 第 1 层的结论；`NONE` 表示可以接背景
 * @param syncReason 第 2 层的结论；`null` 表示调用方不参与（非查看器场景）
 */
data class ViewerBackdropDecision(
    val attachGateReason: BackdropFallbackReason,
    val syncReason: BackdropFallbackReason? = null,
    /**
     * 采样层此刻使用的照片矩形（设备测试出口）。
     *
     * 与 [sampling] 一起构成"真的按这个矩形采样了"的证据：
     * 只有 `sampling=true` **且** `photoRect` 非空，才说明玻璃采的是照片矩形内的
     * 真实像素；`photoRect=null` 而 `sampling=true` 只可能出现在
     * "调用方没有声明矩形"的满屏场景（回收站查看器）。
     */
    val photoRect: PhotoDisplayRect? = null
) {
    /** 顶栏是否真的走玻璃（采样）路径。**这一个值同时决定 UI 分支与审计行**。 */
    val sampling: Boolean
        get() = attachGateReason == BackdropFallbackReason.NONE &&
            (syncReason == null || syncReason == BackdropFallbackReason.NONE)

    /** 不采样时的原因；采样时为 [BackdropFallbackReason.NONE]。 */
    val reason: BackdropFallbackReason
        get() = when {
            attachGateReason != BackdropFallbackReason.NONE -> attachGateReason
            syncReason != null -> syncReason
            else -> BackdropFallbackReason.NONE
        }

    /** 审计行里的路径标记，供设备测试确认按钮**确实**走了回退分支。 */
    val path: String
        get() = if (sampling) "glass" else "fallback"

    /**
     * **试点是否处于开启范围**——决定"要不要显示新增的关闭按钮"。
     *
     * 与 [sampling] 是两个量，必须分开：
     * - [pilotActive] false → 按钮**不存在**，回到试点前的操作（点背景退出 + 分享）；
     * - [pilotActive] true 但 [sampling] false → 按钮**存在**，只是走实色样式
     *   （照片未就绪、放大拖动中、矩形无效）。
     *
     * 把两者混成一个布尔，会出现"照片没加载好就连退出按钮都没了"，
     * 用户被卡在查看器里只能点背景——那不是试点该改变的行为。
     */
    val pilotActive: Boolean
        get() = attachGateReason != BackdropFallbackReason.DISABLED &&
            attachGateReason != BackdropFallbackReason.UNSUPPORTED_API

    companion object {
        /** 由查看器的两层输入构造。 */
        fun of(
            liquidGlassEnabled: Boolean,
            sdkInt: Int,
            hasPhoto: Boolean,
            viewerSync: ViewerBackdropSync?
        ): ViewerBackdropDecision = ViewerBackdropDecision(
            attachGateReason = photoBackdropAttachReason(
                liquidGlassEnabled = liquidGlassEnabled,
                sdkInt = sdkInt,
                hasPhoto = hasPhoto
            ),
            syncReason = viewerSync?.fallbackReason
        )
    }
}
