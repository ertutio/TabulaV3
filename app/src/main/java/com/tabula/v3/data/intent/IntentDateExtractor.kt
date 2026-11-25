package com.tabula.v3.data.intent

import java.util.Calendar
import java.util.TimeZone

/**
 * 从 OCR 文本里抽取**可核对的**时间信息。
 *
 * 只认三类：
 * 1. 绝对日期：`2026-11-10`、`2026/11/10`、`2026年11月10日`、`2026.11.10`
 * 2. 绝对日期 + 时刻：`2026-11-10 23:59`、`11月10日 23:59`（同年补齐）
 * 3. 相对有效期：`3天内有效`、`5分钟内有效`、`72 小时内`、`有效期 7 天`
 *
 * 关键约束（路线图第 8 项）：
 * - **相对有效期必须配一个可靠起算时间**：只有截图自带日期或调用方能给出可信起算点时才结算；
 *   否则返回 [RelativeDeadline.Unresolved]，绝不拿「截图存入时间」当起算点。
 * - 没有依据就返回 null / Unresolved，调用方一律按「日期不确定」处理。
 */
object IntentDateExtractor {

    /** 绝对日期命中。 */
    data class AbsoluteDate(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int = 0,
        val minute: Int = 0,
        /** 是否带具体时刻；没有时刻时按当天 23:59 结算（"到这一天为止"）。 */
        val hasTime: Boolean = false,
        /** 命中的原始片段（用于在报告里说明依据，不含个人数据）。 */
        val source: String = ""
    )

    /** 相对有效期命中。 */
    data class RelativeDeadline(
        val amount: Int,
        val unit: Unit,
        val source: String = ""
    ) {
        enum class Unit { MINUTE, HOUR, DAY }
    }

    /** 相对有效期的结算结果。 */
    sealed interface RelativeResolution {
        /** 有可靠起算点，能算出截止时间。 */
        data class Resolved(val deadlineMillis: Long, val basisMillis: Long) : RelativeResolution

        /** 相对有效期存在，但缺可靠起算点 → 日期不确定。 */
        data class Unresolved(val reason: String) : RelativeResolution
    }

    private val ISO_DATE = Regex("""(20\d{2})[-/.年](\d{1,2})[-/.月](\d{1,2})日?""")
    private val MONTH_DAY = Regex("""(?<!\d)(\d{1,2})月(\d{1,2})日""")
    private val TIME = Regex("""(?<!\d)([01]?\d|2[0-3])[:：]([0-5]\d)(?!\d)""")

    private val RELATIVE = Regex(
        """(?:有效期|有效|限|仅剩|还剩|剩余)?\s*(\d{1,3})\s*(分钟|分|小时|个小时|天|日)\s*(?:以?内|之内)?\s*(?:有效|过期|失效)?"""
    )

    /** 明确的「已结束/已失效」措辞——这是状态证据，不是日期依据。 */
    private val ENDED_WORDS = listOf(
        "已结束", "已过期", "已失效", "活动结束", "已截止", "已售罄", "已下架",
        "已核销", "已取件", "已领取", "已停止", "已关闭", "over"
    )

    fun hasEndedWording(text: String): Boolean = ENDED_WORDS.any { text.contains(it, ignoreCase = true) }

    /**
     * 抽取绝对日期。返回第一个命中；[referenceYear] 用于给「11月10日」这种无年份写法补年份。
     */
    fun findAbsoluteDate(text: String, referenceYear: Int): AbsoluteDate? {
        ISO_DATE.find(text)?.let { m ->
            val (y, mo, d) = m.destructured
            val year = y.toInt()
            val month = mo.toInt()
            val day = d.toInt()
            if (!isValidDate(year, month, day)) return@let
            val time = TIME.find(text.substring(m.range.last + 1))
            return AbsoluteDate(
                year = year, month = month, day = day,
                hour = time?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                minute = time?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                hasTime = time != null,
                source = m.value + (time?.let { " " + it.value } ?: "")
            )
        }
        MONTH_DAY.find(text)?.let { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            if (!isValidDate(referenceYear, month, day)) return@let
            val time = TIME.find(text.substring(m.range.last + 1))
            return AbsoluteDate(
                year = referenceYear, month = month, day = day,
                hour = time?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                minute = time?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                hasTime = time != null,
                source = m.value + (time?.let { " " + it.value } ?: "")
            )
        }
        return null
    }

    /**
     * 抽取相对有效期（只认「有效期/限/仅剩/剩余/还剩」等前缀，或裸「N 天内有效」）。
     *
     * 关键细节：OCR 会把 `10分钟内有效` 断成两行（`10分` / `钟内有效`），
     * 只检查「匹配到的片段本身」就会漏掉后面的「内/有效」，
     * 于是把本该算作短期有效期的短信当成「没有有效期」。
     * 这里改为检查**匹配位置前后各 [VALIDITY_WINDOW] 个字符**的窗口。
     */
    fun findRelativeDeadline(text: String): RelativeDeadline? {
        RELATIVE.find(text)?.let { m ->
            val amount = m.groupValues[1].toIntOrNull() ?: return@let
            if (amount <= 0 || amount > 999) return@let
            // 必须有"有效/过期/失效/内"等语义，避免把"仅剩2天"当有效期
            // ——「仅剩 N 天」属于促销文案，缺少起算点，单独走 unclear 分支。
            val from = (m.range.first - VALIDITY_WINDOW).coerceAtLeast(0)
            val to = (m.range.last + 1 + VALIDITY_WINDOW).coerceAtMost(text.length)
            val window = text.substring(from, to)
            val isExplicitValidity = window.contains("有效期") ||
                window.contains("内") ||
                window.contains("过期") ||
                window.contains("失效")
            if (!isExplicitValidity) return@let
            val unit = when (m.groupValues[2]) {
                "分钟", "分" -> RelativeDeadline.Unit.MINUTE
                "小时", "个小时" -> RelativeDeadline.Unit.HOUR
                else -> RelativeDeadline.Unit.DAY
            }
            return RelativeDeadline(amount = amount, unit = unit, source = m.value.trim())
        }
        return null
    }

    /**
     * 把绝对日期结算成毫秒。没有时刻时按当天 23:59:59 结算：
     * 图上写「有效期至 2026-11-10」意味着这一天还没过完。
     */
    fun toMillis(date: AbsoluteDate, timeZone: TimeZone): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            clear()
            set(date.year, date.month - 1, date.day,
                if (date.hasTime) date.hour else 23,
                if (date.hasTime) date.minute else 59,
                if (date.hasTime) 0 else 59)
        }
        return cal.timeInMillis
    }

    /**
     * 结算相对有效期。
     *
     * @param basisMillis 可靠起算点（截图自带的日期、或调用方明确给出的可信时间）；
     *                    为 null 时返回 [RelativeResolution.Unresolved]，**绝不**用存入时间兜底。
     */
    fun resolveRelative(relative: RelativeDeadline, basisMillis: Long?): RelativeResolution {
        if (basisMillis == null) {
            return RelativeResolution.Unresolved(
                "图中只有相对有效期（${relative.amount}${unitLabel(relative.unit)}），没有可核对的起算时间"
            )
        }
        val span = when (relative.unit) {
            RelativeDeadline.Unit.MINUTE -> relative.amount * 60_000L
            RelativeDeadline.Unit.HOUR -> relative.amount * 3_600_000L
            RelativeDeadline.Unit.DAY -> relative.amount * 86_400_000L
        }
        return RelativeResolution.Resolved(deadlineMillis = basisMillis + span, basisMillis = basisMillis)
    }

    /** 判断「是不是有效期」时，向前后各看多少个字符（够跨过一次 OCR 换行）。 */
    private const val VALIDITY_WINDOW = 8

    private fun unitLabel(unit: RelativeDeadline.Unit) = when (unit) {
        RelativeDeadline.Unit.MINUTE -> "分钟"
        RelativeDeadline.Unit.HOUR -> "小时"
        RelativeDeadline.Unit.DAY -> "天"
    }

    private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
        if (month !in 1..12 || day !in 1..31) return false
        if (year !in 2000..2100) return false
        val cal = Calendar.getInstance().apply {
            clear(); set(year, month - 1, day)
        }
        return cal.get(Calendar.MONTH) == month - 1 && cal.get(Calendar.DAY_OF_MONTH) == day
    }
}
