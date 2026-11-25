package com.tabula.v3.data.intent

/**
 * 中文截图文字的**脱敏**与「是否允许外发到文字辅助」的判定。
 *
 * 这个文件只做一件事：把 OCR 出来的原始文本行变成「可以安全展示 / 可以安全外发」的片段。
 * 它**不是**绝对保证：OCR 可能把验证码读成完全不像验证码的字符串，
 * 也可能把码与普通文字粘在一起导致识别不到。因此这里的契约是：
 *
 * 1. 输出里的每个片段都已经过掩码，掩码后的片段不再包含原始的秘密字面量；
 * 2. 无法可靠判定是否含秘密的片段（[SanitizedLine.undecidable]）**一律不外发**；
 * 3. 调用方只能发送 [SanitizedSnippet.sendable]，不能自己拼原始文本。
 *
 * 失败开放还是失败关闭：这里的默认是**失败关闭**——宁可少发一条，也不发不确定的。
 *
 * 日期与时刻是刻意保留的：它们是判断「是否过期」的唯一依据，
 * 且本身不含个人身份信息。掩掉它们会让文字辅助失去全部判断力
 * （早期版本把 `2026-11-10` 当成 8 位数字掩成星号，已修正）。
 */
object IntentTextSanitizer {

    /** 掩码占位符。 */
    private const val MASK = "＊"

    /** 一行脱敏结果。 */
    data class SanitizedLine(
        /** 掩码后的文本；[undecidable] 为 true 时调用方不得外发这一行。 */
        val text: String,
        /** 原始行里是否命中了敏感的掩码规则（用于统计，不含原值）。 */
        val masked: Boolean,
        /** 掩码后仍无法可靠判定（例如「验证码：」后面什么都没有、或整行几乎都是数字）。 */
        val undecidable: Boolean
    )

    /** 一段可外发的片段。 */
    data class SanitizedSnippet(
        val lines: List<String>,
        val maskedLineCount: Int,
        val droppedLineCount: Int,
        /** 是否至少有一行被丢弃——报告里要如实写出这个边界。 */
        val hadUndecidableLines: Boolean
    ) {
        val isEmpty: Boolean get() = lines.isEmpty()
    }

    /** 高频敏感键：出现这些词的行要做更严格的判定。 */
    private val SENSITIVE_KEYS = listOf(
        "验证码", "校验码", "动态码", "提取码", "密码", "取件码", "取餐码",
        "取货码", "邀请码", "兑换码", "激活码", "口令", "卡号", "账号",
        "手机号", "电话", "订单号", "运单号", "流水号", "单号"
    )

    /** URL / 裸域名 / 联系方式 / 长数字串 / 订单号形态。 */
    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")

    /**
     * 更宽松的「像链接」判定。
     *
     * 为什么要宽松：真实 OCR 会把 `https://pan.example.com/s/AbCd1234` 读成
     * `https:Wpan.＊＊＊＊`（`//` 丢成 `W`）。严格匹配 `https://` 会漏过去，
     * 于是在设备测试里实测抓到了「外发片段仍带 `https:Wpan.`」。
     * 这里改为「协议名 + 冒号 + 其后非空白整段」一律掩掉，
     * 宁可把一句正常文案多掩一次，也不让半截链接溜出去。
     */
    private val URL_LIKE = Regex("""(?i)(?:h?ttps?|ftp|file)\s*[:：]\s*\S*""")

    /** OCR 把 `www.` 读成 `WWW丶` / `www.` 变体的兜底。 */
    private val WWW_LIKE = Regex("""(?i)\bw{2,3}\s*[.。丶]\s*\S+""")
    private val DOMAIN = Regex("""(?i)\b[a-z0-9][a-z0-9-]{1,}\.(?:com|cn|net|org|cc|io|me|tv|xyz|top|link)\b(?:/\S*)?""")
    private val PHONE = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    /** 6 位以上连续数字（验证码、单号、卡号…）。 */
    private val LONG_DIGITS = Regex("""(?<!\d)\d{6,}(?!\d)""")
    /** 带连字符的取件码 8-2-1024 / 2-3-0518。 */
    private val DASH_CODE = Regex("""(?<![\w-])\d{1,2}(?:-\d{1,3}){2}(?![\w-])""")
    /** 订单号形态：字母前缀 + 数字，或纯长数字。 */
    private val ORDER_NO = Regex("""\b(?:[A-Z]{1,4}[-_]?)?\d{8,}\b""")
    /** 短码形态：字母数字混合且含数字、长度 6~14（提取码/邀请码常见）。 */
    private val MIXED_CODE = Regex("""\b(?=[A-Za-z0-9_-]{6,14}\b)(?=[^\s]*\d)[A-Za-z0-9][A-Za-z0-9_-]{5,13}\b""")

    /**
     * 需要**原样保留**的日期/时刻片段。
     *
     * 三件事都靠它：判断是否过期、判断该不该排进批次、给用户一个「图上写着截止 X」的解释。
     * 它不含个人身份信息，因此不掩码。
     */
    private val KEEP_DATE = Regex("""20\d{2}[-/.年]\d{1,2}[-/.月]\d{1,2}日?""")
    private val KEEP_MONTH_DAY = Regex("""\d{1,2}月\d{1,2}日""")
    private val KEEP_CLOCK = Regex("""\d{1,2}[:：]\d{2}""")
    /** 「有效期 7 天」「5 分钟内有效」这类**时长**也不是秘密，且是判断依据。 */
    private val KEEP_DURATION = Regex("""\d{1,3}\s*(?:分钟|分|小时|个小时|天|日)""")

    private val PRESERVE_PATTERNS = listOf(KEEP_DATE, KEEP_MONTH_DAY, KEEP_CLOCK, KEEP_DURATION)

    /** 占位符还原用：`\u0002<index>\u0003`。 */
    private val PLACEHOLDER = Regex("\u0002(\\d+)\u0003")

    /** 敏感键后面紧跟的值（冒号/空格分隔）。 */
    private fun keyValue(key: String) =
        Regex("""(?:$key)\s*[:：]?\s*([A-Za-z0-9_\-]{3,})""")

    /**
     * 脱敏一段文本行。
     *
     * 做法：先把日期/时刻/时长替换成占位符保下来，跑完所有掩码规则再把占位符还原。
     * 这样 `活动已于 2026-11-10 23:59 结束` 保留日期，而 `订单号 2024111000012345`
     * 仍然被掩掉——两者的区别就是「像日期」还是「像编号」。
     */
    fun sanitizeLine(raw: String, allowMixedCodeMask: Boolean = true): SanitizedLine {
        val original = raw.trim()
        if (original.isEmpty()) return SanitizedLine("", false, false)

        // 1. 保下日期类片段
        val preserved = mutableListOf<String>()
        var text = original
        PRESERVE_PATTERNS.forEach { regex ->
            text = regex.replace(text) { match ->
                preserved += match.value
                "\u0002${preserved.size - 1}\u0003"
            }
        }

        var masked = false

        fun maskAll(regex: Regex) {
            if (regex.containsMatchIn(text)) {
                text = regex.replace(text, MASK + MASK + MASK)
                masked = true
            }
        }

        maskAll(URL_LIKE)
        maskAll(URL)
        maskAll(WWW_LIKE)
        maskAll(DOMAIN)
        maskAll(PHONE)

        // 敏感键后面的值优先整体掩码（比后面的通用规则更精确）
        SENSITIVE_KEYS.forEach { key ->
            val regex = keyValue(Regex.escape(key))
            if (regex.containsMatchIn(text)) {
                text = regex.replace(text) { m ->
                    m.value.replace(m.groupValues[1], MASK + MASK + MASK)
                }
                masked = true
            }
        }

        maskAll(DASH_CODE)
        maskAll(LONG_DIGITS)
        maskAll(ORDER_NO)
        if (allowMixedCodeMask) maskAll(MIXED_CODE)

        // 2. 还原日期类片段
        text = PLACEHOLDER.replace(text) { m ->
            preserved.getOrNull(m.groupValues[1].toInt()) ?: ""
        }

        // 3. 判定这一行能不能外发
        val hasSensitiveKey = SENSITIVE_KEYS.any { original.contains(it) }
        val digitsOnlyRatio = if (original.isEmpty()) 0.0 else
            original.count { it.isDigit() || it == '-' }.toDouble() / original.length
        val undecidable =
            (hasSensitiveKey && text.contains(MASK) && text.length < 6) ||
                digitsOnlyRatio > 0.7 ||
                (hasSensitiveKey && !text.contains(MASK) && original.none { it.isLetter() })

        return SanitizedLine(text = text, masked = masked, undecidable = undecidable)
    }

    /**
     * 把整段 OCR 行打包成可外发片段。
     *
     * - 丢弃空行与 [SanitizedLine.undecidable] 的行；
     * - 最多保留 [maxLines] 行、每行最多 [maxCharsPerLine] 字符（防止把长文整篇外发）；
     * - 结果按原顺序拼接，便于模型理解截图结构。
     */
    fun sanitizeForAssistant(
        lines: List<String>,
        maxLines: Int = 12,
        maxCharsPerLine: Int = 40
    ): SanitizedSnippet {
        var maskedCount = 0
        var dropped = 0
        val kept = mutableListOf<String>()
        for (raw in lines) {
            if (raw.isBlank()) continue
            if (kept.size >= maxLines) { dropped++; continue }
            val line = sanitizeLine(raw)
            if (line.undecidable) { dropped++; continue }
            if (line.text.isBlank()) { dropped++; continue }
            if (line.masked) maskedCount++
            kept += line.text.take(maxCharsPerLine)
        }
        return SanitizedSnippet(
            lines = kept,
            maskedLineCount = maskedCount,
            droppedLineCount = dropped,
            hadUndecidableLines = dropped > 0
        )
    }

    /**
     * 展示给用户的理由串也走同一套掩码：理由模板本身不含原文，
     * 但万一将来有人把原文拼进理由，这里仍是最后一道闸门。
     */
    fun sanitizeReason(reason: String): String = sanitizeLine(reason).text
}
