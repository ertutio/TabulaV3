package com.tabula.v3.data.intent

/**
 * 中文截图文字的**脱敏**与「是否允许外发到文字辅助」的判定。
 *
 * ## 这一版的铁律
 *
 * **检出敏感字段后，如果无法可靠定位并完全掩盖字段值，就丢弃受影响的片段。**
 *
 * 换句话说：不是「掩掉能找到的部分，剩下的照发」，而是「要么整段值被完整掩掉，
 * 要么这一行不许出设备」。宁可少发，不发不确定的。
 *
 * ## 判定顺序（不可颠倒）
 *
 * 1. **先定位敏感字段与它的值**：密钥词 + 紧随其后的值区间。分隔符放宽到
 *    非标准形态（`：` `=` `＝` `｜` `、` `-` `·` 空格等），值的字符集覆盖中文、
 *    字母、数字与连接符（`8-2-1024` 这类取件码不会被从中间截断）。
 * 2. **值的定位失败就丢行**：字段后面什么都没有（值在下一行）、值区间为空、
 *    掩码后仍残留 ≥4 个连续字母数字 —— 都判为「无法可靠判定」。
 * 3. **再在敏感区间之外**找日期/时刻/时长，只保留不重叠的那些。
 * 4. 输出时敏感区间一律 `＊＊＊`。
 *
 * ## OCR 把字段和值拆成两行怎么办
 *
 * 单行视角看不到这种拆分：`验证码` 在一行、`123456` 或 `甲乙丙丁` 在下一行。
 * 因此 [sanitizeForAssistant] 维护跨行上下文——上一行以敏感字段结尾（值缺失）时，
 * **下一行整行丢弃**。这是「丢弃受影响片段」的直接应用：既然无法确定下一行
 * 哪些字符属于字段值，就不猜。
 *
 * ## 这不是绝对保证
 *
 * OCR 可能把值读成完全不像值的形态（连字段词本身都读错），那种情况这里认不出来。
 * 真正的兜底是上层「证据不足就不外发」的策略。该边界写进了测试与报告。
 */
object IntentTextSanitizer {

    /** 掩码占位符。 */
    private const val MASK = "＊＊＊"

    /** 一行脱敏结果。 */
    data class SanitizedLine(
        /** 掩码后的文本；[undecidable] 为 true 时调用方不得外发这一行。 */
        val text: String,
        /** 原始行里是否命中了敏感的掩码规则（用于统计，不含原值）。 */
        val masked: Boolean,
        /** 掩码后仍无法可靠判定（字段值定位失败、残留可疑片段等），必须留在本地。 */
        val undecidable: Boolean,
        /**
         * 这一行以敏感字段结尾、值不在本行——下一行属于「受影响的片段」，同样丢弃。
         * 跨行判断只在 [sanitizeForAssistant] 里生效；单行调用方可以忽略。
         */
        val danglingSecretKey: Boolean = false
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

    /** 敏感字段名。命中这些词就认为「后面跟着一个必须掩掉的值」。 */
    private val SECRET_KEYS = listOf(
        "验证码", "校验码", "动态码", "提取码", "密码", "支付密码", "取件码", "取餐码",
        "取货码", "邀请码", "兑换码", "激活码", "口令", "卡号", "账号", "身份证",
        "手机号", "电话", "联系方式", "订单号", "运单号", "流水号", "单号", "票号",
        "序列号", "激活密钥"
    )

    private val SECRET_KEY_REGEX = Regex(
        SECRET_KEYS.joinToString("|") { Regex.escape(it) }
    )

    /**
     * 字段与值之间允许出现的分隔符。
     *
     * 比上一版宽：真实截图里出现过 `＝`（全角等号）、`｜`、`、`、`-`、`·`，
     * 上一版只认 `:` / `：`，于是值整段漏掩。这里全部当分隔符跳过。
     */
    private val SEPARATORS = charArrayOf(
        ' ', '\t', ':', '：', '=', '＝', '|', '｜', '、', ',', '，', '·', '•', '—', '~',
        '(', '（', '"', '\'', '“', '‘', '>', '》', '\u3000'
    )

    /** 值区间的终止符：空白与中文标点。**不含** `-` `.` `_`，否则取件码会被截断。 */
    private val VALUE_STOPPERS = charArrayOf(
        ' ', '\t', '\u3000', '，', '。', ',', '.', '、', '；', ';', '：', ':', '！', '!', '？', '?',
        '（', '）', '(', ')', '【', '】', '[', ']', '《', '》', '<', '>', '“', '”', '"', '\'', '|', '｜',
        '和', '或', '请', '如', '若', '可', '将', '在', '与', '及'
    )

    /** 需要原样保留、且不含身份信息的日期与时长。 */
    private val KEEP_PATTERNS = listOf(
        Regex("""20\d{2}[-/.年]\d{1,2}[-/.月]\d{1,2}日?"""),
        Regex("""(?<!\d)\d{1,2}月\d{1,2}日"""),
        Regex("""(?<!\d)(?:[01]?\d|2[0-3])[:：][0-5]\d(?!\d)"""),
        Regex("""\d{1,3}\s*(?:分钟|分|小时|个小时|天|日)""")
    )

    /** 值定位成功后掩码；与日期重叠时整行丢弃。`secret` 标记来自字段值而非普通形态。 */
    private data class Span(val start: Int, val end: Int, val secret: Boolean) {
        fun overlaps(other: Span): Boolean = start < other.end && other.start < end
    }

    private data class RedactionRule(val regex: Regex, val secret: Boolean)

    private val REDACTION_RULES = listOf(
        // 链接：OCR 会把 `https://` 读成 `https:W`，按「协议名+冒号+其后非空」判。
        RedactionRule(Regex("""(?i)(?:h?ttps?|ftp|file)\s*[:：]\s*\S*"""), secret = false),
        RedactionRule(Regex("""(?i)\bw{2,3}\s*[.。丶]\s*\S+"""), secret = false),
        RedactionRule(
            Regex("""(?i)\b[a-z0-9][a-z0-9-]{1,}\.(?:com|cn|net|org|cc|io|me|tv|xyz|top|link)\b(?:/\S*)?"""),
            secret = false
        ),
        RedactionRule(Regex("""(?<!\d)1[3-9]\d{9}(?!\d)"""), secret = false),
        RedactionRule(Regex("""(?<![\w-])\d{1,4}(?:-\d{1,4}){2,}(?![\w-])"""), secret = false),
        RedactionRule(Regex("""(?<!\d)\d{6,}(?!\d)"""), secret = false),
        RedactionRule(Regex("""\b(?:[A-Za-z]{1,4}[-_]?)?\d{8,}\b"""), secret = false),
        RedactionRule(
            Regex("""\b(?=[A-Za-z0-9_-]{6,14}\b)(?=[^\s]*\d)[A-Za-z0-9][A-Za-z0-9_-]{5,13}\b"""),
            secret = false
        )
    )

    /** 掩码后残留的可疑片段：≥4 个连续字母数字。出现即认为值没被完整掩掉。 */
    private val SUSPICIOUS_RESIDUE = Regex("""[A-Za-z0-9]{4,}""")

    /**
     * 脱敏一行。
     *
     * @param maskMixedCodes 是否把字母数字混合短码也掩掉（默认是；关掉仅供诊断）
     */
    fun sanitizeLine(raw: String, maskMixedCodes: Boolean = true): SanitizedLine {
        val original = raw.trim()
        if (original.isEmpty()) return SanitizedLine("", false, false)

        val redactions = mutableListOf<Span>()
        var undecidable = false
        var dangling = false

        // ---------- 1a. 敏感字段：必须可靠定位并完全掩掉它的值 ----------
        for (keyMatch in SECRET_KEY_REGEX.findAll(original)) {
            val valueStart = locateValueStart(original, keyMatch.range.last + 1)
            if (valueStart == null) {
                // 字段后面只剩分隔符（或什么都没有）：值在下一行，或压根没有。
                // 两种都无法在本行判断 → 本行丢、下一行也丢。
                dangling = true
                undecidable = true
                continue
            }
            val valueEnd = locateValueEnd(original, valueStart)
            if (valueEnd <= valueStart) {
                // 分隔符之后立刻是标点：定位不到值 → 丢行
                undecidable = true
                continue
            }
            redactions += Span(valueStart, valueEnd, secret = true)
        }

        // ---------- 1b. 形态规则：跳过「完整日期」，日期留给第 2 步 ----------
        REDACTION_RULES.forEach { rule ->
            if (!maskMixedCodes && rule.regex.pattern.contains("[A-Za-z0-9_-]{6,14}")) return@forEach
            for (match in rule.regex.findAll(original)) {
                val slice = original.substring(match.range.first, match.range.last + 1)
                if (isWholeDate(slice)) return@forEach
                redactions += Span(match.range.first, match.range.last + 1, rule.secret)
            }
        }

        if (redactions.isEmpty() && !undecidable) {
            return SanitizedLine(
                original,
                masked = false,
                undecidable = looksLikeBareNumbers(original),
                danglingSecretKey = dangling
            )
        }

        // ---------- 2. 日期只在敏感区间之外才允许保留 ----------
        KEEP_PATTERNS.forEach { regex ->
            for (match in regex.findAll(original)) {
                val dateSpan = Span(match.range.first, match.range.last + 1, secret = false)
                val hit = redactions.firstOrNull { it.overlaps(dateSpan) }
                if (hit != null && hit.secret) {
                    // 字段值长得像日期：既不能掩也不能保 → 整行丢弃
                    undecidable = true
                }
            }
        }

        // ---------- 3. 拼输出 ----------
        val output = StringBuilder()
        var cursor = 0
        mergeRanges(redactions.map { it.start..(it.end - 1) }).forEach { range ->
            if (range.first > cursor) output.append(original, cursor, range.first)
            output.append(MASK)
            cursor = range.last + 1
        }
        if (cursor < original.length) output.append(original, cursor, original.length)
        val text = output.toString()

        // ---------- 4. 掩完再验收：不合格就丢行 ----------
        // 4a. 只要这一行出现过敏感字段，掩码后不允许残留 ≥4 个连续字母数字：
        //     那说明值被 OCR 拆开（`1234 5678`）或我们只掩到了前半段。
        val hadSecretKey = SECRET_KEY_REGEX.containsMatchIn(original)
        if (hadSecretKey && SUSPICIOUS_RESIDUE.findAll(text).any { it.value.length >= 4 }) {
            undecidable = true
        }

        // 4b. 掩码后只剩字段词本身（「验证码 ＊＊＊」）→ 没有外发价值，且刚处理过秘密
        val residual = SECRET_KEYS.fold(text) { acc, key -> acc.replace(key, "") }
            .replace(MASK, "")
            .replace(Regex("""[\s:：·,，.。/|、]"""), "")
        val tooThin = residual.length < 2

        return SanitizedLine(
            text = text,
            masked = true,
            undecidable = undecidable || looksLikeBareNumbers(original) || tooThin,
            danglingSecretKey = dangling
        )
    }

    /** 从 [from] 开始跳过分隔符；返回值的起始下标，若已到行尾则返回 null。 */
    private fun locateValueStart(text: String, from: Int): Int? {
        var i = from
        while (i < text.length && text[i] in SEPARATORS) i++
        return if (i >= text.length) null else i
    }

    /** 从 [start] 开始取值的结束下标（不含）。遇到空白/标点即停。 */
    private fun locateValueEnd(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i] !in VALUE_STOPPERS) i++
        return i
    }

    /**
     * 这一行是否「只是一串数字」，没有可靠的上下文可判断。
     *
     * 三个条件同时成立才算，避免误伤正常文案：
     * 1. 数字/连字符占比 > 70%；
     * 2. **没有汉字**——「满199减30」占比 83% 但它是正常促销文案；
     * 3. 整行不是日期/时刻——`2026-10-30 07:42` 占比 79% 但它是时效证据。
     */
    private fun looksLikeBareNumbers(line: String): Boolean {
        if (isWholeDate(line)) return false
        val hasCjk = line.any { it.code in 0x4E00..0x9FFF }
        if (hasCjk) return false
        val ratio = line.count { it.isDigit() || it == '-' }.toDouble() / line.length.coerceAtLeast(1)
        return ratio > 0.7
    }

    /** 这个片段是否「整体就是一个日期/时刻/时长」。 */
    private fun isWholeDate(slice: String): Boolean =
        KEEP_PATTERNS.any { pattern -> pattern.matchEntire(slice) != null }

    /** 合并重叠或相邻的区间，避免同一段被掩两次。 */
    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf(sorted.first())
        sorted.drop(1).forEach { current ->
            val last = merged.last()
            if (current.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, current.last)
            } else {
                merged += current
            }
        }
        return merged
    }

    /**
     * 把整段 OCR 行打包成可外发片段。
     *
     * **跨行上下文**：上一行以敏感字段结尾（值缺失）时，下一行整行丢弃——
     * 我们无法确定下一行哪些字符是那个字段的值，于是不猜。
     *
     * - 丢弃空行、[SanitizedLine.undecidable] 的行、以及被上一行牵连的行；
     * - 最多 [maxLines] 行、每行最多 [maxCharsPerLine] 字符；
     * - 结果按原顺序拼接。
     */
    fun sanitizeForAssistant(
        lines: List<String>,
        maxLines: Int = 12,
        maxCharsPerLine: Int = 40
    ): SanitizedSnippet {
        var maskedCount = 0
        var dropped = 0
        val kept = mutableListOf<String>()
        var previousHadDanglingKey = false

        for (raw in lines) {
            if (raw.isBlank()) continue
            if (previousHadDanglingKey) {
                // 上一行的字段值落到了这一行：整行属于「受影响的片段」
                dropped++
                previousHadDanglingKey = false
                continue
            }
            if (kept.size >= maxLines) { dropped++; continue }
            val line = sanitizeLine(raw)
            previousHadDanglingKey = line.danglingSecretKey
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

    /** 仅供诊断与测试：这一行是否含有需要外发的内容（脱敏后非空且可判定）。 */
    fun canSend(raw: String): Boolean {
        val line = sanitizeLine(raw)
        return !line.undecidable && line.text.isNotBlank()
    }
}
