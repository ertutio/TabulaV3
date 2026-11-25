package com.tabula.v3.data.intent

/**
 * 中文截图文字的**脱敏**与「是否允许外发到文字辅助」的判定。
 *
 * ## 铁律（第 6 轮收紧）
 *
 * 1. **检出敏感字段的 OCR 行，整行留在本地。**
 *    不是「掩掉值、其余照发」，而是这一行不接受外发。理由：只要一行里出现过字段词，
 *    我们无法证明行内**其余文字**与那个秘密无关（值可能被 OCR 拆开、粘连、重复出现）。
 * 2. **跨行值一律拦截**：字段所在行以字段结尾（本行没有值）时，下一行整行丢弃。
 * 3. **不做「值到此为止」的放行判断。**
 *    上一版用「注意/说明/请勿…」这类词推断「敏感值已经结束，后面是自然语言，可以发」。
 *    这个推断本身就是风险面：`取件码 注意 说明` 这种把自然语言词当值用的情况会被放行。
 *    现在整行都不发，这条推断连同词表一并删除。
 * 4. 非字段类形态（链接、手机号、长数字、取件码形态、混合短码）在**不含字段词**的行里
 *    按区间掩码；这些形态的边界由正则本身完整确定，不存在「值延伸到哪」的问题。
 * 5. **日期只在没有敏感内容的位置保留**。日期是判断是否到期的唯一依据，
 *    但它不能成为把秘密还原回去的通道——敏感区间一旦覆盖日期，日期就随该行一起丢掉。
 *
 * ## 这不是绝对保证
 *
 * OCR 可能把字段词本身读错（`验证码` → `验正码`），那种情况这里认不出来。
 * 真正的兜底是上层「证据不足就不外发」的策略。
 */
object IntentTextSanitizer {

    /** 掩码占位符。 */
    private const val MASK = "＊＊＊"

    /** 一行脱敏结果。 */
    data class SanitizedLine(
        /** 掩码后的文本（仅供展示/诊断；[undecidable] 为 true 时**不得外发**）。 */
        val text: String,
        /** 是否做过掩码或扣留。 */
        val masked: Boolean,
        /** 这一行必须留在本地（含敏感字段、几乎全是数字、字段值在下一行等）。 */
        val undecidable: Boolean,
        /** 这一行以敏感字段结尾、值不在本行——下一行同样必须丢弃。 */
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

    /** 敏感字段名。命中即整行留在本地。 */
    private val SECRET_KEYS = listOf(
        "验证码", "校验码", "动态码", "提取码", "密码", "支付密码", "取件码", "取餐码",
        "取货码", "邀请码", "兑换码", "激活码", "口令", "卡号", "账号", "身份证",
        "手机号", "电话", "联系方式", "订单号", "运单号", "流水号", "单号", "票号",
        "序列号", "激活密钥"
    )

    private val SECRET_KEY_REGEX = Regex(SECRET_KEYS.joinToString("|") { Regex.escape(it) })

    /** 字段与值之间允许出现的分隔符（用于把值掩掉，便于展示与诊断）。 */
    private val SEPARATORS = charArrayOf(
        ' ', '\t', ':', '：', '=', '＝', '|', '｜', '、', ',', '，', '·', '•', '—', '~',
        '(', '（', '"', '\'', '“', '‘', '>', '》', '\u3000'
    )

    /** 值区间的终止符：空白与中文标点。**不含** `-` `.` `_`，否则取件码会被截断。 */
    private val VALUE_STOPPERS = charArrayOf(
        ' ', '\t', '\u3000', '，', '。', ',', '.', '、', '；', ';', '：', ':', '！', '!', '？', '?',
        '（', '）', '(', ')', '【', '】', '[', ']', '《', '》', '<', '>', '“', '”', '"', '\'', '|', '｜'
    )

    /** 需要原样保留、且不含身份信息的日期与时长。 */
    private val KEEP_PATTERNS = listOf(
        Regex("""20\d{2}[-/.年]\d{1,2}[-/.月]\d{1,2}日?"""),
        Regex("""(?<!\d)\d{1,2}月\d{1,2}日"""),
        Regex("""(?<!\d)(?:[01]?\d|2[0-3])[:：][0-5]\d(?!\d)"""),
        Regex("""\d{1,3}\s*(?:分钟|分|小时|个小时|天|日)""")
    )

    /** 非字段类形态：边界由正则完整确定，可以只掩这一段。 */
    private val MASK_RULES = listOf(
        // 链接：OCR 会把 `https://` 读成 `https:W`，按「协议名+冒号+其后非空」判。
        Regex("""(?i)(?:h?ttps?|ftp|file)\s*[:：]\s*\S*"""),
        Regex("""(?i)\bw{2,3}\s*[.。丶]\s*\S+"""),
        Regex("""(?i)\b[a-z0-9][a-z0-9-]{1,}\.(?:com|cn|net|org|cc|io|me|tv|xyz|top|link)\b(?:/\S*)?"""),
        Regex("""(?<!\d)1[3-9]\d{9}(?!\d)"""),
        Regex("""(?<![\w-])\d{1,4}(?:-\d{1,4}){2,}(?![\w-])"""),
        Regex("""(?<!\d)\d{6,}(?!\d)"""),
        Regex("""\b(?:[A-Za-z]{1,4}[-_]?)?\d{8,}\b"""),
        Regex("""\b(?=[A-Za-z0-9_-]{6,14}\b)(?=[^\s]*\d)[A-Za-z0-9][A-Za-z0-9_-]{5,13}\b""")
    )

    /**
     * 脱敏一行。
     *
     * @param maskMixedCodes 是否把字母数字混合短码也掩掉（默认是；关掉仅供诊断）
     */
    fun sanitizeLine(raw: String, maskMixedCodes: Boolean = true): SanitizedLine {
        val original = raw.trim()
        if (original.isEmpty()) return SanitizedLine("", false, false)

        // ---------- 1. 敏感字段：整行留在本地 ----------
        //
        // 只要出现字段词就不外发。这里仍然把值掩掉再返回 text，
        // 是为了让展示/诊断路径也不会把秘密打出来——但这一行永远不会进请求体。
        val keyMatch = SECRET_KEY_REGEX.find(original)
        if (keyMatch != null) {
            val valueStart = locateValueStart(original, keyMatch.range.last + 1)
            val dangling = valueStart == null
            val maskedText = if (valueStart == null) {
                original
            } else {
                val valueEnd = locateValueEnd(original, valueStart)
                if (valueEnd <= valueStart) original
                else original.replaceRange(valueStart, valueEnd, MASK)
            }
            return SanitizedLine(
                text = maskedText,
                masked = true,
                undecidable = true,
                danglingSecretKey = dangling
            )
        }

        // ---------- 2. 非字段类形态：按区间掩码 ----------
        val spans = mutableListOf<IntRange>()
        var maskedAny = false
        MASK_RULES.forEach { rule ->
            if (!maskMixedCodes && rule.pattern.contains("[A-Za-z0-9_-]{6,14}")) return@forEach
            for (match in rule.findAll(original)) {
                val slice = original.substring(match.range.first, match.range.last + 1)
                // 完整日期交给第 3 步保留，不按「带连字符的数字」掩掉
                if (isWholeDate(slice)) return@forEach
                spans += match.range
                maskedAny = true
            }
        }

        val merged = mergeRanges(spans)
        val text = buildString {
            var cursor = 0
            merged.forEach { range ->
                if (range.first > cursor) append(original, cursor, range.first)
                append(MASK)
                cursor = range.last + 1
            }
            if (cursor < original.length) append(original, cursor, original.length)
        }

        // ---------- 3. 验收：只要求「几乎全是数字」这类无法判断的行留在本地 ----------
        val digitsOnly = looksLikeBareNumbers(original)
        val tooThin = maskedAny && text.replace(MASK, "")
            .replace(Regex("""[\s:：·,，.。/|、]"""), "").length < 2

        return SanitizedLine(
            text = text,
            masked = maskedAny,
            undecidable = digitsOnly || tooThin
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
        if (line.any { it.code in 0x4E00..0x9FFF }) return false
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
     * **跨行拦截**：上一行以敏感字段结尾（本行没有值）时，下一行整行丢弃——
     * 我们无法确定下一行哪些字符是那个字段的值，于是不猜。
     *
     * - 丢弃空行与 [SanitizedLine.undecidable] 的行；
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
                // 上一行的字段值落到了这一行：整行属于受影响的片段
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

    /** 仅供诊断与测试：这一行是否含有可以外发的内容。 */
    fun canSend(raw: String): Boolean {
        val line = sanitizeLine(raw)
        return !line.undecidable && line.text.isNotBlank()
    }
}
