package com.tabula.v3

/**
 * 查看器轮询成功判定（审计轮 6/7）：单次轮询的条件必须**同时**成立才算
 * 成功——查看器标识节点在（含被测媒体身份，关闭即消失）、颜色符合预期。
 * 独立成纯函数以便针对性回归：证明"查看器短暂打开后关闭、卡片颜色符合
 * 预期"的跨时刻拼接**不能**通过（打开与颜色不在同一次轮询中同时成立）。
 */
internal object ViewerPollPolicy {
    fun pollSuccess(
        viewerOverlayPresent: Boolean,
        mediaIdentityMatches: Boolean,
        colorClass: String,
        expected: String
    ): Boolean = viewerOverlayPresent && mediaIdentityMatches && colorClass == expected
}
