package com.tabula.v3.data.intent

import java.util.TimeZone

/**
 * 本地规则引擎：先保护、再分类、最后判定过期（路线图第 8 项 / #94）。
 *
 * 判定顺序固定，测试逐条钉住：
 * 1. **保护**：票务、订单、交易凭证、发票、课件、长期保留标记。
 *    命中即排除——即使同一张图上还有「限时优惠已结束」这类清理信号。
 * 2. **文字可用性**：OCR 没读出可用文字 / 置信度过低 → 不确定，不进优先批次。
 * 3. **分类**（按优先级，先命中先算）：
 *    临时码 → 优惠 → 分享 → 活动提醒 → 时效新闻 → 旧比价。
 *    类别的先后不是猜的：`提取码` 只属于分享，`验证码/取件码` 只属于临时码，
 *    `直播预告` 属于新闻而不是活动，否则同一张图会被反复改判。
 * 4. **过期判定**：只有「可核对的截止时间」且已过，或图上明确写「已结束/已过期」，
 *    才算 [ExpiryState.EXPIRED]。**截图存得久不是过期证据**；只有相对有效期而没有
 *    可靠起算点时一律 [ExpiryState.UNKNOWN]。
 *    唯一的例外是**短期临时码**（见 [SHORT_LIVED_WINDOW_MS]）：它不声称「某时刻过期」，
 *    只说「这种码本来只在很短时间有用，而截图已经存了很久」。
 *
 * 本类不产生副作用，不读图片：输入 OCR 行 + 存入时间，输出 [IntentVerdict]。
 */
object IntentRules {

    /** 规则版本：规则改动必须 +1，缓存据此失效（见 [IntentVerdictCache]）。 */
    const val RULES_VERSION = 1

    /** 低于这个置信度按「读不清」处理，不进优先批次。 */
    const val MIN_OCR_CONFIDENCE = 0.45f

    /** 少于这么多行文字、并且总字数也不够时，按「证据不足」处理。 */
    const val MIN_TEXT_LINES = 2

    /** 单行截图（短信、验证码）至少要这么多个字才算有效证据。 */
    const val MIN_TEXT_CHARS = 8

    /** 即时快讯需要存够这么多天，才当作「内容已经过期」。 */
    const val EPHEMERAL_NEWS_STALE_DAYS = 7
    private const val MILLIS_PER_DAY = 86_400_000L

    /** 短期临时码的窗口：图上写的有效期不超过这么久，才算「短期」。 */
    const val SHORT_LIVED_WINDOW_MS = 24 * 3_600_000L


    // ==================== 保护规则 ====================

    /** 关键词保护。命中即排除。 */
    private val PROTECTION_KEYWORDS: List<Pair<IntentSignal, List<String>>> = listOf(
        IntentSignal.ORDER to listOf(
            "订单详情", "订单号", "我的订单", "待收货", "待发货", "已签收", "退款",
            "售后", "实付款", "实付", "支付成功", "交易成功", "购买须知", "已完成"
        ),
        IntentSignal.INVOICE to listOf(
            "发票", "开票", "电子发票", "报销", "凭证", "收据", "税号", "金额明细"
        ),
        IntentSignal.TICKET to listOf(
            "车票", "机票", "登机牌", "电影票", "演出票", "座位号", "检票", "乘车人",
            "车厢", "取票号", "请勿删除", "入场码", "排 ", "座 "
        ),
        IntentSignal.COURSEWARE to listOf(
            "课件", "讲义", "习题", "考点", "复习提纲", "读书笔记", "课程笔记"
        ),
        IntentSignal.LONG_TERM to listOf(
            "长期保留", "已加入长期保留", "不再提示清理"
        )
    )

    /** 正则保护：章节标题、公式、座位号这类结构化痕迹。 */
    private val PROTECTION_PATTERNS: List<Pair<IntentSignal, Regex>> = listOf(
        IntentSignal.COURSEWARE to Regex("""第\s*[0-9一二三四五六七八九十]+\s*[章节讲]"""),
        IntentSignal.COURSEWARE to Regex("""[=＝]\s*[A-Za-zλμσπ√∑∫]"""),
        IntentSignal.TICKET to Regex("""\d{1,2}\s*排\s*\d{1,3}\s*[座号]"""),
        IntentSignal.TICKET to Regex("""\d{2}\s*车\s*\d{1,3}[A-F]?\s*号"""),
        IntentSignal.ORDER to Regex("""(?i)\b[A-Z]{2,4}\d{10,}\b""")
    )

    // ==================== 分类规则 ====================

    /** 关键词分类。**顺序即优先级**。 */
    private val CATEGORY_KEYWORDS: List<Pair<IntentCategory, List<String>>> = listOf(
        IntentCategory.TEMP_CODE to listOf(
            "验证码", "校验码", "动态码", "取件码", "取餐码", "取货码", "邀请码",
            "兑换码", "激活码", "口令", "临时码", "验证短信"
        ),
        IntentCategory.EXPIRED_COUPON to listOf(
            "优惠券", "券后", "立减", "秒杀", "限时", "折扣", "特价", "包邮",
            "补贴", "仅剩", "活动价", "满减"
        ),
        IntentCategory.SHARE to listOf(
            "复制链接", "扫码观看", "扫码查看", "提取码", "网盘", "文件中转",
            "转存", "分享链接", "链接已", "小程序"
        ),
        IntentCategory.EVENT_REMINDER to listOf(
            "报名", "预约", "抢票", "开售", "开抢", "投票", "问卷", "活动提醒",
            "签到", "抽签", "预约成功", "开奖"
        ),
        IntentCategory.STALE_NEWS to listOf(
            "快讯", "新闻", "头条", "资讯", "报道", "发布会", "直播预告", "开播",
            "热点", "深度阅读", "杂志"
        )
    )

    /** 正则分类：`满199减30`、`5折`、`24:00 截止` 这类形态。 */
    private val CATEGORY_PATTERNS: List<Triple<IntentCategory, Regex, IntentSignal?>> = listOf(
        Triple(IntentCategory.EXPIRED_COUPON, Regex("""满\s*\d+\s*减\s*\d+"""), IntentSignal.COUPON),
        Triple(IntentCategory.EXPIRED_COUPON, Regex("""\d(\.\d)?\s*折"""), IntentSignal.COUPON),
        Triple(IntentCategory.TEMP_CODE, Regex("""验证码\s*[:：]?\s*\d{4,8}"""), IntentSignal.VERIFICATION_CODE),
        Triple(IntentCategory.TEMP_CODE, Regex("""取[件餐货]码\s*[:：]?\s*[\d-]{4,}"""), IntentSignal.PICKUP_CODE),
        Triple(IntentCategory.SHARE, Regex("""(?i)https?://|www\."""), IntentSignal.LINK),
        Triple(IntentCategory.EXPIRED_COUPON, Regex("""(?:今日|今天)?\s*\d{1,2}\s*[:：]\s*\d{2}\s*截止"""), IntentSignal.EXPIRY_DATE)
    )

    /** 长文/资料/收藏型信号：出现即不因「日期旧」上榜。 */
    private val EVERGREEN_HINTS = listOf(
        "深度阅读", "读书笔记", "课程笔记", "收藏", "资料", "长文", "原文",
        "杂志", "专栏", "专栏作者", "参考文献"
    )

    /** 旧比价的弱信号：只有价格，没有截止时间。 */
    private val OLD_PRICE_HINTS = listOf(
        "价格", "原价", "现价", "到手价", "比价", "历史低价", "走势", "历史价格"
    )

    /** 「已结束/已失效」的明确措辞。 */
    private val EXPLICIT_END = listOf(
        "已结束", "已过期", "已失效", "活动结束", "已截止", "已售罄", "已下架",
        "已核销", "已取件", "已停止", "已关闭", "提取码已过期", "链接已失效"
    )

    /** 这些词出现在日期附近时，那个日期是**开始/发布时间**，不是截止时间。 */
    private val START_TIME_HINTS = listOf("开售", "开播", "开始", "预告", "开抢", "开放报名", "发布", "更新于")

    /**
     * 只有日期附近出现这些词，才把日期当**截止时间**。
     *
     * 这条规则是「日期不确定就不要硬凑」的核心：截图里的时间戳绝大多数是
     * 发布时间、拍摄时间、开售时间，把它们当截止时间会凭空造出「已过期」。
     */
    private val DEADLINE_CONTEXT = listOf("截止", "有效期", "到期", "结束", "过期", "失效", "截至", "至 ")

    /** 即时快讯类：内容只在当天有用（属于路线图允许的「旧即时快讯」）。 */
    private val EPHEMERAL_NEWS_HINTS = listOf("快讯", "即时", "推送", "紧急", "播报", "通知")

    /** 只有这些类别受「已结束措辞」影响。 */
    private val TIMED_CATEGORIES = setOf(
        IntentCategory.EXPIRED_COUPON, IntentCategory.STALE_NEWS,
        IntentCategory.SHARE, IntentCategory.TEMP_CODE, IntentCategory.EVENT_REMINDER
    )

    /**
     * 对一张图做判定。
     *
     * @param lines OCR 出的文本行（原始；本方法不保留）
     * @param savedAtMillis 截图存入时间；只用于「短期临时码」与展示，不当作有效期起算点
     * @param nowMillis 当前时间，测试可注入
     * @param ocrConfidence 0~1；低于 [MIN_OCR_CONFIDENCE] 视为读不清
     * @param retainedOverride 调用方的长期保留标记，命中即保护
     */
    fun classify(
        lines: List<String>,
        savedAtMillis: Long,
        nowMillis: Long,
        ocrConfidence: Float,
        timeZone: TimeZone = TimeZone.getDefault(),
        retainedOverride: Boolean = false
    ): IntentVerdict {
        val text = lines.joinToString("\n")
        val compact = text.replace(Regex("""\s+"""), "")
        val matched = mutableSetOf<IntentSignal>()

        // ---------- 1. 保护优先 ----------
        if (retainedOverride) {
            matched += IntentSignal.LONG_TERM
            return protectedVerdict("你已把这张设为长期保留", matched, ocrConfidence)
        }
        PROTECTION_KEYWORDS.forEach { (signal, words) ->
            if (words.any { word -> protectedHit(compact, word) }) matched += signal
        }
        PROTECTION_PATTERNS.forEach { (signal, regex) ->
            if (regex.containsMatchIn(text)) matched += signal
        }
        if (matched.any { it in PROTECTION_SIGNALS }) {
            return protectedVerdict(protectionReason(matched), matched, ocrConfidence)
        }

        // ---------- 2. 文字可用性 ----------
        // 短信/验证码截图常常只有一行，所以门槛是「行数」与「总字数」两个条件都太弱才算读不清。
        val nonBlank = lines.count { it.isNotBlank() }
        val totalChars = compact.length
        val tooThin = nonBlank == 0 || (nonBlank < MIN_TEXT_LINES && totalChars < MIN_TEXT_CHARS)
        if (tooThin || ocrConfidence < MIN_OCR_CONFIDENCE) {
            return IntentVerdict(
                category = IntentCategory.UNCERTAIN,
                recommendation = IntentRecommendation.UNCERTAIN,
                expiry = ExpiryState.UNKNOWN,
                reason = if (lines.none { it.isNotBlank() }) "没有读出可用文字" else "文字太少或太模糊",
                signals = matched,
                ocrConfidence = ocrConfidence
            )
        }

        // ---------- 3. 分类 ----------
        var category = IntentCategory.UNCERTAIN
        run {
            for ((candidate, words) in CATEGORY_KEYWORDS) {
                if (words.any { compact.contains(it, ignoreCase = true) }) {
                    category = candidate
                    return@run
                }
            }
            for ((candidate, regex, signal) in CATEGORY_PATTERNS) {
                if (regex.containsMatchIn(text)) {
                    category = candidate
                    if (signal != null) matched += signal
                    return@run
                }
            }
            if (OLD_PRICE_HINTS.any { compact.contains(it) }) category = IntentCategory.OLD_PRICE
        }

        // 只有「时间戳 + 标题」两行的卡片：这是推送/快讯卡片，不是文章。
        // 长文一定有正文行；把「只有时间戳和标题」认成旧即时快讯，
        // 而不是把「任意带日期的新闻」都当过期。
        if (category == IntentCategory.UNCERTAIN && looksLikeFeedCard(lines)) {
            category = IntentCategory.STALE_NEWS
        }

        // 长文/资料/收藏型：认得出来，但不因日期旧就上榜（路线图第 8 项原文）。
        if (category == IntentCategory.STALE_NEWS && EVERGREEN_HINTS.any { compact.contains(it) }) {
            category = IntentCategory.EVERGREEN
        }

        // ---------- 4. 过期 / 有效性 ----------
        val endedWording = EXPLICIT_END.any { text.contains(it) }
        if (endedWording) matched += IntentSignal.ENDED
        if (text.contains("提取码")) matched += IntentSignal.EXTRACT_CODE
        if (text.contains("验证码")) matched += IntentSignal.VERIFICATION_CODE
        if (text.contains("取件码") || text.contains("取餐码")) matched += IntentSignal.PICKUP_CODE
        if (Regex("""(?i)https?://|www\.|\.(?:com|cn|net)""").containsMatchIn(text)) {
            matched += IntentSignal.LINK
        }
        when (category) {
            IntentCategory.EXPIRED_COUPON -> matched += IntentSignal.COUPON
            IntentCategory.OLD_PRICE -> matched += IntentSignal.OLD_PRICE
            IntentCategory.STALE_NEWS -> matched += IntentSignal.NEWS
            IntentCategory.SHARE -> matched += IntentSignal.SHARE
            IntentCategory.EVENT_REMINDER -> matched += IntentSignal.EVENT
            else -> Unit
        }

        val referenceYear = referenceYear(nowMillis, timeZone)
        val absolute = IntentDateExtractor.findAbsoluteDate(text, referenceYear)
        val relative = IntentDateExtractor.findRelativeDeadline(text)

        var deadlineMillis: Long? = null
        var expiry = ExpiryState.UNKNOWN
        var dateNote: String? = null

        // 日期到底是「截止时间」还是「开售/发布时间」：比谁离这个日期更近，
        // 不能「只要附近有开抢就一律当开始时间」——那会把真正写过截止的活动漏掉。
        val deadlineDistance = absolute?.let { nearestDistance(text, DEADLINE_CONTEXT, it.source) }
        val startDistance = absolute?.let { nearestDistance(text, START_TIME_HINTS, it.source) }
        val dateIsDeadline = absolute != null &&
            deadlineDistance != null && deadlineDistance <= NEAR_WINDOW &&
            (startDistance == null || deadlineDistance <= startDistance)

        if (absolute != null && dateIsDeadline) {
            deadlineMillis = IntentDateExtractor.toMillis(absolute, timeZone)
            expiry = if (deadlineMillis <= nowMillis) ExpiryState.EXPIRED else ExpiryState.STILL_VALID
            matched += IntentSignal.EXPIRY_DATE
            dateNote = if (expiry == ExpiryState.EXPIRED) {
                "图上写着截止 ${absolute.source}"
            } else {
                "图上写着截止 ${absolute.source}，还没到"
            }
        } else if (relative != null) {
            matched += IntentSignal.DEADLINE_RELATIVE
            // 相对有效期缺可靠起算点 → 默认 UNKNOWN。
            //
            // 唯一的例外是**短期临时码**（图上有效期 ≤ 24 小时，验证码/一次性链接）：
            // 这类文案由系统生成，起算点是短信/推送到达时刻，而**截图不可能早于它**。
            // 于是「截图存入时间」是该起算点的**上界**，能推出
            //   截止时间 ≤ 存入时间 + 有效期
            // 一旦 now > 存入时间 + 有效期，就必然已过期——这是一条方向安全的推理
            // （只用它证明「一定已过」，绝不用它证明「一定没过」）。
            // 促销倒计时（「仅剩2天」）不走这条路：那是营销文案，不是硬性有效期。
            val shortLivedWindow = relativeSpanMillis(relative) <= SHORT_LIVED_WINDOW_MS
            val basis = if (category == IntentCategory.TEMP_CODE && shortLivedWindow &&
                savedAtMillis > 0 && savedAtMillis <= nowMillis
            ) savedAtMillis else null
            val resolution = IntentDateExtractor.resolveRelative(relative, basisMillis = basis)
            when (resolution) {
                is IntentDateExtractor.RelativeResolution.Resolved -> {
                    deadlineMillis = resolution.deadlineMillis
                    expiry = if (resolution.deadlineMillis <= nowMillis) {
                        ExpiryState.EXPIRED
                    } else {
                        ExpiryState.STILL_VALID
                    }
                    if (expiry == ExpiryState.EXPIRED) {
                        dateNote = "临时码只在 ${relative.amount}${unitLabel(relative.unit)}内有效，" +
                            "截图存入已超过这个时限"
                    } else {
                        dateNote = "临时码还在有效期内"
                    }
                }
                is IntentDateExtractor.RelativeResolution.Unresolved -> {
                    dateNote = resolution.reason
                }
            }
        }

        // 「短期」= 图上写的有效期窗口不超过 24 小时（验证码/取件码/一次性链接）。
        val shortLived = relative != null && relativeSpanMillis(relative) <= SHORT_LIVED_WINDOW_MS

        if (endedWording && expiry != ExpiryState.EXPIRED && category in TIMED_CATEGORIES) {
            // 「已结束」是明确的状态证据；但截止时间仍以可核对的日期为准（没有就留空）
            expiry = ExpiryState.EXPIRED
            dateNote = dateNote ?: "图上明确写着已结束/已失效"
        }

        // ---------- 5. 推荐档位 ----------
        // 即时快讯的判定有两条互相独立的证据：
        // 1. 图上直接写着「快讯/推送/紧急」；
        // 2. 整张图只有「纯时间戳 + 几行短标题」——即推送卡片本身。
        val ephemeral = EPHEMERAL_NEWS_HINTS.any { compact.contains(it) } || looksLikeFeedCard(lines)
        val (recommendation, reason) = decide(
            category, expiry, matched, dateNote, savedAtMillis, nowMillis, shortLived, ephemeral
        )
        val recheckAtMillis = if (category == IntentCategory.STALE_NEWS && ephemeral &&
            recommendation == IntentRecommendation.LOWER_PRIORITY && savedAtMillis in 1..nowMillis
        ) {
            (savedAtMillis + EPHEMERAL_NEWS_STALE_DAYS * MILLIS_PER_DAY).takeIf { it > nowMillis }
        } else {
            null
        }
        return finish(category, recommendation, expiry, matched, reason, ocrConfidence, deadlineMillis, recheckAtMillis)
    }

    private fun finish(
        category: IntentCategory,
        recommendation: IntentRecommendation,
        expiry: ExpiryState,
        signals: Set<IntentSignal>,
        reason: String,
        ocrConfidence: Float,
        deadlineMillis: Long?,
        recheckAtMillis: Long?
    ) = IntentVerdict(
        category = category,
        recommendation = recommendation,
        expiry = expiry,
        reason = IntentTextSanitizer.sanitizeReason(reason),
        signals = signals,
        ocrConfidence = ocrConfidence,
        deadlineMillis = deadlineMillis,
        recheckAtMillis = recheckAtMillis
    )

    private fun protectedVerdict(
        reason: String,
        signals: Set<IntentSignal>,
        ocrConfidence: Float
    ) = IntentVerdict(
        category = IntentCategory.PROTECTED,
        recommendation = IntentRecommendation.KEEP,
        expiry = ExpiryState.UNKNOWN,
        reason = reason,
        signals = signals,
        ocrConfidence = ocrConfidence
    )

    private fun protectionReason(signals: Set<IntentSignal>): String = when {
        IntentSignal.TICKET in signals -> "票券/凭证类，先保留"
        IntentSignal.ORDER in signals -> "订单或交易记录，先保留"
        IntentSignal.INVOICE in signals -> "发票/报销凭证，先保留"
        IntentSignal.COURSEWARE in signals -> "课件/资料，先保留"
        IntentSignal.LONG_TERM in signals -> "已标记长期保留，先保留"
        else -> "命中保护规则，先保留"
    }

    private fun decide(
        category: IntentCategory,
        expiry: ExpiryState,
        signals: Set<IntentSignal>,
        dateNote: String?,
        savedAtMillis: Long,
        nowMillis: Long,
        shortLived: Boolean,
        ephemeral: Boolean
    ): Pair<IntentRecommendation, String> {
        val ageDays = if (savedAtMillis > 0) ((nowMillis - savedAtMillis) / MILLIS_PER_DAY).toInt() else -1
        return when (category) {
            IntentCategory.EXPIRED_COUPON -> when (expiry) {
                ExpiryState.EXPIRED -> IntentRecommendation.RECOMMEND to
                    (dateNote ?: "优惠已过截止时间")
                ExpiryState.STILL_VALID -> IntentRecommendation.LOWER_PRIORITY to
                    (dateNote ?: "优惠还没到期")
                ExpiryState.UNKNOWN -> IntentRecommendation.LOWER_PRIORITY to
                    "优惠截图，但没写可核对的截止时间"
            }
            IntentCategory.OLD_PRICE -> IntentRecommendation.LOWER_PRIORITY to
                "旧比价截图，没有截止时间"
            IntentCategory.STALE_NEWS -> when {
                expiry == ExpiryState.EXPIRED -> IntentRecommendation.RECOMMEND to
                    (dateNote ?: "已结束的时效内容")
                // 「旧即时快讯」是路线图明确允许的候选：它本身就是一次性推送，
                // 内容只在当天有用。这里说的是「快讯类已过期」，
                // **不是**把任意新闻的日期当失效时间。
                ephemeral && ageDays >= EPHEMERAL_NEWS_STALE_DAYS ->
                    IntentRecommendation.RECOMMEND to "即时快讯类，只在当天有用（截图已存 $ageDays 天）"
                else -> IntentRecommendation.LOWER_PRIORITY to
                    "新闻/资讯类，日期旧不等于失效"
            }
            IntentCategory.SHARE -> when {
                expiry == ExpiryState.EXPIRED || matchedEnded(signals) ->
                    IntentRecommendation.RECOMMEND to (dateNote ?: "分享已失效")
                else -> IntentRecommendation.UNCERTAIN to "只有分享页，没有失效证据"
            }
            IntentCategory.TEMP_CODE -> when {
                expiry == ExpiryState.EXPIRED -> IntentRecommendation.RECOMMEND to
                    (dateNote ?: "临时码已过有效期")
                // 短期临时码但缺少可用起算点（没有存入时间，或存入时间在未来）：
                // 停在低优先级，不声称已失效。
                shortLived -> IntentRecommendation.LOWER_PRIORITY to
                    "临时码只在很短时间有效，但没有可核对的起算时间"
                signals.contains(IntentSignal.PICKUP_CODE) ->
                    IntentRecommendation.LOWER_PRIORITY to "取件码，多半只在当时有用"
                signals.contains(IntentSignal.VERIFICATION_CODE) ->
                    IntentRecommendation.LOWER_PRIORITY to "验证码，只在当时有用"
                else -> IntentRecommendation.LOWER_PRIORITY to "临时码/邀请码"
            }
            IntentCategory.EVENT_REMINDER -> when {
                expiry == ExpiryState.EXPIRED -> IntentRecommendation.RECOMMEND to
                    (dateNote ?: "活动提醒已结束")
                ageDays >= 14 -> IntentRecommendation.LOWER_PRIORITY to "活动提醒，但没写明确截止时间"
                else -> IntentRecommendation.UNCERTAIN to "活动提醒，还没有可靠时间依据"
            }
            IntentCategory.EVERGREEN ->
                IntentRecommendation.KEEP to "长文或资料型内容，不因日期旧就建议清理"
            IntentCategory.UNCERTAIN, IntentCategory.PROTECTED ->
                IntentRecommendation.UNCERTAIN to "看不出明显类型"
        }
    }

    private fun matchedEnded(signals: Set<IntentSignal>) = IntentSignal.ENDED in signals

    private fun unitLabel(unit: IntentDateExtractor.RelativeDeadline.Unit): String = when (unit) {
        IntentDateExtractor.RelativeDeadline.Unit.MINUTE -> "分钟"
        IntentDateExtractor.RelativeDeadline.Unit.HOUR -> "小时"
        IntentDateExtractor.RelativeDeadline.Unit.DAY -> "天"
    }

    private fun relativeSpanMillis(relative: IntentDateExtractor.RelativeDeadline): Long =
        when (relative.unit) {
            IntentDateExtractor.RelativeDeadline.Unit.MINUTE -> relative.amount * 60_000L
            IntentDateExtractor.RelativeDeadline.Unit.HOUR -> relative.amount * 3_600_000L
            IntentDateExtractor.RelativeDeadline.Unit.DAY -> relative.amount * 86_400_000L
        }

    /**
     * 是否是「时间戳 + 标题」的推送/快讯卡片。
     *
     * 判据刻意窄：**必有一行是纯时间戳**（`2026-10-30 07:42`），
     * 且除它之外只剩很少几行短文本（不超过 [FEED_CARD_MAX_OTHER_LINES] 行、每行不超过 28 字）。
     * 这样不会把长文（有正文段落）或报价单（多行明细）误认成快讯。
     */
    private fun looksLikeFeedCard(lines: List<String>): Boolean {
        val cleaned = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.size < MIN_TEXT_LINES) return false
        val stamp = cleaned.any { BARE_TIMESTAMP.matches(it) }
        if (!stamp) return false
        val others = cleaned.filterNot { BARE_TIMESTAMP.matches(it) }
        return others.isNotEmpty() &&
            others.size <= FEED_CARD_MAX_OTHER_LINES &&
            others.all { it.length <= 28 }
    }

    /**
     * 一组提示词里离日期**片段**最近的那一个的距离；都没有则 null。
     *
     * 距离按「提示词到日期片段的最近端点」算，而不是到片段起点：
     * `活动已于 2026-11-10 23:59 结束` 里的「结束」紧贴在日期后面，
     * 如果按起点算就会被判成 17 个字符开外，从而漏掉真正的截止语义。
     */
    private fun nearestDistance(text: String, hints: List<String>, dateSource: String): Int? {
        val dateAt = text.indexOf(dateSource)
        if (dateAt < 0) return null
        val dateEnd = dateAt + dateSource.length
        var best: Int? = null
        hints.forEach { hint ->
            var from = 0
            while (true) {
                val at = text.indexOf(hint, from)
                if (at < 0) break
                val hintEnd = at + hint.length
                val distance = when {
                    hintEnd <= dateAt -> dateAt - hintEnd
                    at >= dateEnd -> at - dateEnd
                    else -> 0
                }
                if (best == null || distance < best!!) best = distance
                from = at + hint.length
            }
        }
        return best
    }

    /** 否定语境：出现在保护词之前，就认为这次命中不算。 */
    private val NEGATION_WORDS = listOf("不构成", "不视为", "不作为", "不是", "并非", "不作为凭证")

    /** 否定词往前看多少个字符（够跨过「截图不构成任何购买」这类短语）。 */
    private const val NEGATION_WINDOW = 10

    /**
     * 保护词是否真的命中（排除否定语境）。
     *
     * 为什么需要：真实 OCR 会把促销页的免责声明读进来，例如
     * 「截图**不构成**任何购买凭证」。若「凭证」无脑命中，整张优惠截图会被当成
     * 报销凭证保护起来——方向安全（不误删），但会让功能明显变钝。
     * 这里对「不构成 / 不视为 / 不是 / 并非」等否定词做就近排除。
     */
    private fun protectedHit(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf(word, from)
            if (at < 0) return false
            val windowStart = (at - NEGATION_WINDOW).coerceAtLeast(0)
            val window = text.substring(windowStart, at)
            val negated = NEGATION_WORDS.any { window.contains(it) }
            if (!negated) return true
            from = at + word.length
        }
    }

    /** 提示词与日期片段视为「在一起」的最大字符距离。 */
    private const val NEAR_WINDOW = 12

    /** 推送卡片里除时间戳外最多几行（超过就是长文，不是卡片）。 */
    private const val FEED_CARD_MAX_OTHER_LINES = 3

    /** 纯时间戳行：`2026-10-30 07:42`（可选秒）。 */
    private val BARE_TIMESTAMP = Regex("""^20\d{2}[-/.年]\d{1,2}[-/.月]\d{1,2}日?\s+\d{1,2}[:：]\d{2}(?::\d{2})?$""")

    private val PROTECTION_SIGNALS = setOf(
        IntentSignal.ORDER, IntentSignal.INVOICE, IntentSignal.TICKET,
        IntentSignal.COURSEWARE, IntentSignal.LONG_TERM
    )

    private fun referenceYear(nowMillis: Long, timeZone: TimeZone): Int =
        java.util.Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
            .get(java.util.Calendar.YEAR)
}
