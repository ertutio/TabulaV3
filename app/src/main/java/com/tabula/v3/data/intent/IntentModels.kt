package com.tabula.v3.data.intent

import com.tabula.v3.data.model.ImageFile

/**
 * 「按整理目的凑一批图」（路线图第 8 项 / #94）的纯数据契约。
 *
 * 设计边界（与暂存清理的分工）：
 * - 本包只回答「这一张像什么、值不值得清、为什么」以及「怎么排序」，
 *   **不碰** MediaStore、不碰回收站、不碰 UI，也不改动任何一张原图。
 * - 输入一律是调用方已经筛好的候选（暂存清理来源 + 用户勾选 + 扣除保留/跳过/已回收），
 *   本包不做全库扫描，也不改变随机漫步 / 连拍选片 / 去重的行为。
 * - 分类结果只用于组批与排序；用户仍然手动决定保留、归档或移入回收站。
 */

/** 分类类别。命名与路线图第 8 项一致，便于在 PR 与测试里直接对账。 */
enum class IntentCategory {
    /** 明确到期的购物/优惠（有可核对的截止时间且已过）。 */
    EXPIRED_COUPON,

    /** 只有价格与商品信息、没有可核对截止时间的旧比价。 */
    OLD_PRICE,

    /** 时效性新闻 / 已结束的直播预告。 */
    STALE_NEWS,

    /** 视频或文件分享（网盘中转、提取码等）。 */
    SHARE,

    /** 临时码：短信验证码、取件码、临时邀请码。 */
    TEMP_CODE,

    /** 报名 / 抢票 / 投票等活动提醒（含已结束）。 */
    EVENT_REMINDER,

    /** 保护类：订单、票据、发票、课件、长期保留标记。 */
    PROTECTED,

    /**
     * 长文/资料/收藏型内容：认得出来，但**不该**因为日期旧就上榜。
     *
     * 与 [PROTECTED] 的区别：它不是「保护证据」，而是「这条规则不适用」。
     * 结果同样是排除，但理由不同，界面上要分开说。
     */
    EVERGREEN,

    /** 证据不足 / OCR 不可读 / 日期无法核实。 */
    UNCERTAIN;

    /** 是否属于「应当保护」的类别——保护信号永远压过清理信号。 */
    val isProtected: Boolean get() = this == PROTECTED

    /** 是否一定不进清理批次（保护 + 长文资料）。 */
    val isExcluded: Boolean get() = this == PROTECTED || this == EVERGREEN
}

/**
 * 过期状态。**刻意与类别分开**：类别说「这是什么」，这里说「能不能断定它已失效」。
 *
 * 只有 [EXPIRED] 才能让一张图进入「明确到期」的高置信批次；
 * [UNKNOWN] 一律不进优先批次，避免把「截图存得久」当成「活动已结束」。
 */
enum class ExpiryState {
    /** 有可核对的截止时间（图上写死的日期/时刻），且早于当前时间。 */
    EXPIRED,

    /** 有截止时间但还没到。 */
    STILL_VALID,

    /** 图上没有任何可核对的截止时间，或只有相对有效期却没有可靠起算时间。 */
    UNKNOWN
}

/** 推荐档位：排序只用这三档，不引入更多微调参数。 */
enum class IntentRecommendation {
    /** 高置信候选，进「建议清理」优先批次。 */
    RECOMMEND,

    /** 低置信/低优先级，排在后面但不排除。 */
    LOWER_PRIORITY,

    /** 证据不足：既不推荐也不保护，默认不勾选。 */
    UNCERTAIN,

    /** 保护类：直接排除出候选，并说明理由。 */
    KEEP;

    /** 是否可能被排进清理批次（[KEEP] 永远不进）。 */
    val isCleanable: Boolean get() = this != KEEP
}

/** 判定依据的机器可读标记，用于缓存失效、测试断言和脱敏过滤。 */
enum class IntentSignal(val label: String) {
    COUPON("优惠/券"),
    OLD_PRICE("旧比价"),
    EXPIRY_DATE("截止时间"),
    DEADLINE_RELATIVE("相对有效期"),
    NEWS("新闻"),
    LIVE("直播预告"),
    SHARE("分享"),
    EXTRACT_CODE("提取码"),
    TEMP_CODE("临时码"),
    VERIFICATION_CODE("验证码"),
    PICKUP_CODE("取件码"),
    EVENT("活动/报名"),
    ENDED("已结束"),
    LINK("链接"),
    PHONE("手机号"),
    ORDER_NO("订单号"),
    ORDER("订单"),
    INVOICE("发票/凭证"),
    TICKET("票券"),
    COURSEWARE("课件"),
    LONG_TERM("长期保留")
}

/**
 * 单张图的判定结果。
 *
 * @param category 类别（[IntentCategory]）
 * @param recommendation 推荐档位
 * @param expiry 过期状态——只有 [ExpiryState.EXPIRED] 才允许说「已失效」
 * @param reason 展示给用户的**脱敏**理由（不含验证码/提取码/链接正文）
 * @param signals 命中的信号
 * @param ocrConfidence OCR 置信度（0~1），0 表示没读出可用文字
 * @param deadlineMillis 可核对的截止时间（毫秒）；没有依据时为 null
 * @param assistantUsed 是否使用了可选的文字辅助（DeepSeek）
 * @param recheckAtMillis 本地规则需要重新判定的时间（毫秒），与图上截止时间独立
 */
data class IntentVerdict(
    val category: IntentCategory,
    val recommendation: IntentRecommendation,
    val expiry: ExpiryState,
    val reason: String,
    val signals: Set<IntentSignal> = emptySet(),
    val ocrConfidence: Float = 0f,
    val deadlineMillis: Long? = null,
    val assistantUsed: Boolean = false,
    val recheckAtMillis: Long? = null
) {
    /** 纳入批次时是否计为「排除」而不是「清理推荐」。 */
    val isProtectionExclusion: Boolean
        get() = recommendation == IntentRecommendation.KEEP
}

/**
 * 一张候选图及其判定。排序键全部显式列出，便于测试逐条钉住。
 *
 * @param image 原图（只读引用；本包从不修改它）
 * @param verdict 判定结果
 * @param savedAtMillis 截图存入时间（MediaStore DATE_ADDED），0 表示未知
 */
data class IntentCandidate(
    val image: ImageFile,
    val verdict: IntentVerdict,
    val savedAtMillis: Long
)

/**
 * 一批候选的排序结果。
 *
 * @param recommended 高置信、可直接勾选的候选（按证据强度、再按存入时间倒序）
 * @param lowerPriority 低优先级候选
 * @param uncertain 证据不足的候选（默认不勾选）
 * @param excluded 被排除的候选：保护类（票务/订单/凭证/课件/长期保留）与长文资料类。
 *                 永远不进批次，只用于向用户解释「为什么这张没被推荐」。
 */
data class IntentBatch(
    val recommended: List<IntentCandidate>,
    val lowerPriority: List<IntentCandidate>,
    val uncertain: List<IntentCandidate>,
    val excluded: List<IntentCandidate> = emptyList(),
    val batchLimit: Int = 0
) {
    /** 本批次最终呈现给用户的顺序（推荐在前，低优先级其次）。 */
    val ordered: List<IntentCandidate> get() = recommended + lowerPriority

    /** 需要用户自己判断的那部分（默认不勾选）。 */
    val uncertainOnly: List<IntentCandidate> get() = uncertain

    val isEmpty: Boolean get() = ordered.isEmpty() && uncertain.isEmpty()

    /** 是否因为高置信候选不够而没凑满批次——界面要如实说明，不硬凑。 */
    val shortOfLimit: Boolean get() = recommended.size + lowerPriority.size < batchLimit

    /** 被保护规则挡下的张数（不含长文资料）。 */
    val protectedCount: Int get() = excluded.count { it.verdict.category.isProtected }

    /** 因为「长文/资料不该上榜」而排除的张数。 */
    val evergreenCount: Int get() = excluded.count { it.verdict.category == IntentCategory.EVERGREEN }
}
