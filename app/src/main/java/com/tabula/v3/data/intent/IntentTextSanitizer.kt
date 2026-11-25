package com.tabula.v3.data.intent

/**
 * 中文截图文字的**脱敏**与「是否允许外发到文字辅助」的判定。
 *
 * ## 这一版修的是什么
 *
 * 上一版的做法是「先用占位符把日期保下来，再跑掩码规则，最后把占位符还原」。
 * 那条路径有个致命顺序问题：**保护日期发生在识别敏感字段之前**。
 * 只要某个敏感字段的值长得像日期（`验证码 20261110`、`提取码 2026-1110`、
 * `卡号 2026-11-10-1234`），它就会先被当成日期存进占位符，
 * 后面的掩码规则看不到它，最后又被原样还原出来——**等于把秘密从后门放回去**。
 *
 * 现在的顺序反过来，先后不可颠倒：
 *
 * 1. **先识别敏感字段及其上下文**：密钥词（验证码/密码/提取码/卡号/订单号…）后面紧跟的值、
 *    链接、手机号、长数字、带连字符的取件码、字母数字混合短码——全部先划成红色区间；
 * 2. **再在红色区间之外**找日期/时刻/时长，才允许保留；
 * 3. 输出时红色区间一律掩成 `＊＊＊`，其余原样。
 *
 * 两种区间**重叠**时的处理分两类：
 * - 与敏感键值重叠 → 这一行**无法可靠判定**，整行留在本地（不外发）。
 *   因为我们既不能掩掉它（会丢掉判断依据）又不能保留它（会泄露）；
 * - 与链接/手机号/订单号重叠 → 掩码优先，重叠的日期直接丢弃，不判不可判定
 *   （链接里出现的日期没有任何判断价值）。
 *
 * ## 这不是绝对保证
 *
 * OCR 可能把验证码读成完全不像验证码的形态（`l23456`），也可能把两个字段粘在一起。
 * 所以真正的兜底是上层「证据不足就不外发」的策略，而不是这里的正则。
 * 这个边界写进了 `IntentTextSanitizerTest` 与试点报告的「已知缺口」。
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
        /** 掩码后仍无法可靠判定（敏感字段的值与日期形态重叠等），必须留在本地。 */
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

    /** 敏感键：这些词后面的值一律不进外发文本。 */
    private val SECRET_KEYS = listOf(
        "验证码", "校验码", "动态码", "提取码", "密码", "支付密码", "取件码", "取餐码",
        "取货码", "邀请码", "兑换码", "激活码", "口令", "卡号", "账号", "身份证",
        "手机号", "电话", "联系方式", "订单号", "运单号", "流水号", "单号", "票号"
    )

    /** 需要原样保留、且不含身份信息的日期与时长。 */
    private val KEEP_PATTERNS = listOf(
        Regex("""20\d{2}[-/.年]\d{1,2}[-/.月]\d{1,2}日?"""),
        Regex("""(?<!\d)\d{1,2}月\d{1,2}日"""),
        Regex("""(?<!\d)(?:[01]?\d|2[0-3])[:：][0-5]\d(?!\d)"""),
        Regex("""\d{1,3}\s*(?:分钟|分|小时|个小时|天|日)""")
    )

    /** 必须整段掩掉的形态。`hard` = 与日期重叠时直接丢弃日期；`secret` = 与日期重叠时整行不外发。 */
    private data class RedactionRule(val regex: Regex, val secret: Boolean)

    private val REDACTION_RULES = listOf(
        // 链接：先于日期。OCR 会把 `https://` 读成 `https:W`，所以按「协议名+冒号+其后非空」判。
        RedactionRule(Regex("""(?i)(?:h?ttps?|ftp|file)\s*[:：]\s*\S*"""), secret = false),
        RedactionRule(Regex("""(?i)\bw{2,3}\s*[.。丶]\s*\S+"""), secret = false),
        RedactionRule(
            Regex("""(?i)\b[a-z0-9][a-z0-9-]{1,}\.(?:com|cn|net|org|cc|io|me|tv|xyz|top|link)\b(?:/\S*)?"""),
            secret = false
        ),
        // 手机号
        RedactionRule(Regex("""(?<!\d)1[3-9]\d{9}(?!\d)"""), secret = false),
        // 带连字符的取件码 8-2-1024 / 2026-11-10-1234
        RedactionRule(Regex("""(?<![\w-])\d{1,4}(?:-\d{1,4}){2,}(?![\w-])"""), secret = false),
        // 6 位以上连续数字（验证码/单号/卡号/日期串）
        RedactionRule(Regex("""(?<!\d)\d{6,}(?!\d)"""), secret = false),
        // 字母前缀 + 长数字（订单号）
        RedactionRule(Regex("""\b(?:[A-Za-z]{1,4}[-_]?)?\d{8,}\b"""), secret = false),
        // 字母数字混合短码（提取码/邀请码）
        RedactionRule(
            Regex("""\b(?=[A-Za-z0-9_-]{6,14}\b)(?=[^\s]*\d)[A-Za-z0-9][A-Za-z0-9_-]{5,13}\b"""),
            secret = false
        )
    )

    /** 文本里的区间，`[start, end)`。 */
    private data class Span(val start: Int, val end: Int, val secret: Boolean) {
        fun overlaps(other: Span): Boolean = start < other.end && other.start < end
    }

    /**
     * 脱敏一行。
     *
     * @param maskMixedCodes 是否把字母数字混合短码也掩掉（默认是；关掉仅供诊断）
     */
    fun sanitizeLine(raw: String, maskMixedCodes: Boolean = true): SanitizedLine {
        val original = raw.trim()
        if (original.isEmpty()) return SanitizedLine("", false, false)

        // ---------- 1. 先划敏感区间 ----------
        val redactions = mutableListOf<Span>()

        // 1a. 敏感键 + 紧邻的值：这是「上下文优先」的核心，必须排在所有形态规则之前。
        SECRET_KEYS.forEach { key ->
            val pattern = Regex(
                """(?:${Regex.escape(key)})\s*[:：]?\s*([A-Za-z0-9][A-Za-z0-9_\-·.]{2,})"""
            )
            for (match in pattern.findAll(original)) {
                val valueGroup = match.groups[1] ?: continue
                redactions += Span(
                    start = valueGroup.range.first,
                    end = valueGroup.range.last + 1,
                    secret = true
                )
            }
        }

        // 1b. 形态规则（链接 / 手机号 / 长数字 / 混合短码…）
        //
        // 命中「完整日期」的形态匹配要跳过：`2026-11-10` 也会被「带连字符的数字」规则命中，
        // 若不放行，所有截止时间都会被当成取件码掩掉，辅助就失去判断依据。
        // 安全性由顺序保证——敏感键的值在 1a 已标记为 secret，日期落在其中会被判不可判定。
        REDACTION_RULES.forEach { rule ->
            if (!maskMixedCodes && rule.regex.pattern.contains("[A-Za-z0-9_-]{6,14}")) return@forEach
            for (match in rule.regex.findAll(original)) {
                val slice = original.substring(match.range.first, match.range.last + 1)
                if (isWholeDate(slice)) return@forEach
                redactions += Span(match.range.first, match.range.last + 1, rule.secret)
            }
        }

        if (redactions.isEmpty()) {
            return SanitizedLine(original, masked = false, undecidable = looksLikeBareNumbers(original))
        }

        // ---------- 2. 再找可保留的日期，但只认「不与敏感区间重叠」的 ----------
        val keepRanges = mutableListOf<IntRange>()
        var undecidable = false
        KEEP_PATTERNS.forEach { regex ->
            for (match in regex.findAll(original)) {
                val dateSpan = Span(match.range.first, match.range.last + 1, secret = false)
                val hit = redactions.firstOrNull { it.overlaps(dateSpan) }
                if (hit == null) {
                    keepRanges += match.range
                } else if (hit.secret) {
                    // 敏感字段的值长得像日期：既不能掩也不能保 → 这一行无法可靠判定
                    undecidable = true
                }
                // 与链接/手机号重叠：直接丢弃该日期，不判不可判定
            }
        }

        // ---------- 3. 按区间拼输出 ----------
        val output = StringBuilder()
        var cursor = 0
        val merged = mergeRanges(redactions.map { it.start..(it.end - 1) })
        merged.forEach { range ->
            if (range.first > cursor) output.append(original, cursor, range.first)
            output.append(MASK)
            cursor = range.last + 1
        }
        if (cursor < original.length) output.append(original, cursor, original.length)

        // 被掩掉的内容如果正好包含一个「本该保留」的日期，掩码已经把它吃掉了，
        // 不需要再做任何还原——这正是本版与上一版的根本区别。
        val text = output.toString()

        // 掩码之后如果只剩敏感键本身（「验证码 ＊＊＊」），这一行对外发没有信息量，
        // 而且它证明我们刚处理完一个秘密 —— 一并留在本地，不靠「看起来没泄露」赌。
        val residual = SECRET_KEYS.fold(text) { acc, key -> acc.replace(key, "") }
            .replace(MASK, "")
            .replace(Regex("""[\s:：·,，.。/|]"""), "")
        val tooThin = residual.length < 2
        return SanitizedLine(
            text = text,
            masked = true,
            undecidable = undecidable || looksLikeBareNumbers(original) || tooThin
        )
    }

    /**
     * 这一行是否「只是一串数字」，没有可靠的上下文可判断。
     *
     * 三个条件同时成立才算，避免误伤正常文案：
     * 1. 数字/连字符占比 > 70%；
     * 2. **没有汉字**——「满199减30」占比 83% 但它是正常促销文案，不能被丢掉；
     * 3. 整行不是日期/时刻——`2026-10-30 07:42` 占比 79% 但它是时效证据，必须能发。
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

    /** 仅供诊断与测试：这一行是否含有需要外发的内容（脱敏后非空且可判定）。 */
    fun canSend(raw: String): Boolean {
        val line = sanitizeLine(raw)
        return !line.undecidable && line.text.isNotBlank()
    }
}
