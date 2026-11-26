package com.tabula.v3.data.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * 中文日期与「过期」判定的单测（#94）。
 *
 * 这一组是整个功能的**正确性底座**：只要这里把「存入时间久」误判成「活动已过期」，
 * 用户就会在「建议清理」里看到自己还想留着的东西。所以每条用例都对应
 * 路线图第 8 项里的一句约束。
 */
class IntentDateExtractorTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun isoDateWithTimeIsParsed() {
        val date = IntentDateExtractor.findAbsoluteDate("活动已于 2026-11-10 23:59 结束", 2026)
        assertNotNull(date)
        assertEquals(2026, date!!.year)
        assertEquals(11, date!!.month)
        assertEquals(10, date!!.day)
        assertTrue(date!!.hasTime)
        assertEquals(23, date!!.hour)
        assertEquals(59, date!!.minute)
    }

    @Test
    fun chineseStyleDateIsParsed() {
        val date = IntentDateExtractor.findAbsoluteDate("有效期至 2026年11月01日", 2026)
        assertNotNull(date)
        assertEquals(2026, date!!.year)
        assertEquals(11, date!!.month)
        assertEquals(1, date!!.day)
        assertFalse(date!!.hasTime)
    }

    @Test
    fun monthDayWithoutYearUsesReferenceYear() {
        val date = IntentDateExtractor.findAbsoluteDate("报名截止 11月6日 18:00", 2026)
        assertNotNull(date)
        assertEquals(2026, date!!.year)
        assertEquals(11, date!!.month)
        assertEquals(6, date!!.day)
        assertTrue(date!!.hasTime)
        assertEquals(18, date!!.hour)
    }

    @Test
    fun invalidCalendarDateIsRejected() {
        // 2 月 30 日不是有效日期：宁可返回 null（不确定），也不要编一个日期出来
        assertNull(IntentDateExtractor.findAbsoluteDate("截止 2026-02-30", 2026))
    }

    @Test
    fun dateWithoutTimeResolvesToEndOfDay() {
        val date = IntentDateExtractor.findAbsoluteDate("有效期至 2026-11-10", 2026)!!
        val millis = IntentDateExtractor.toMillis(date, utc)
        val cal = java.util.Calendar.getInstance(utc).apply { timeInMillis = millis }
        assertEquals(2026, cal.get(java.util.Calendar.YEAR))
        assertEquals(10, cal.get(java.util.Calendar.DAY_OF_MONTH))
        // 「有效期至 11-10」意味着这一天还没过完
        assertEquals(23, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(59, cal.get(java.util.Calendar.MINUTE))
    }

    @Test
    fun relativeValidityIsRecognized() {
        val relative = IntentDateExtractor.findRelativeDeadline("验证码 5分钟内有效")
        assertNotNull(relative)
        assertEquals(5, relative!!.amount)
        assertEquals(IntentDateExtractor.RelativeDeadline.Unit.MINUTE, relative.unit)
    }

    @Test
    fun relativeValidityWithoutReliableBasisStaysUnresolved() {
        val relative = IntentDateExtractor.findRelativeDeadline("链接 72小时以内有效")!!
        val resolution = IntentDateExtractor.resolveRelative(relative, basisMillis = null)
        assertTrue(resolution is IntentDateExtractor.RelativeResolution.Unresolved)
        // 关键断言：不能用「截图存入时间」兜底——那会把没到期的分享算成已失效
        assertFalse(resolution is IntentDateExtractor.RelativeResolution.Resolved)
    }

    @Test
    fun relativeValidityResolvesWhenBasisIsReliable() {
        val relative = IntentDateExtractor.findRelativeDeadline("有效期 7 天")!!
        val basis = 1_795_000_000_000L
        val resolution = IntentDateExtractor.resolveRelative(relative, basis)
        assertTrue(resolution is IntentDateExtractor.RelativeResolution.Resolved)
        assertEquals(
            basis + 7 * 86_400_000L,
            (resolution as IntentDateExtractor.RelativeResolution.Resolved).deadlineMillis
        )
    }

    @Test
    fun plainPromoCountdownIsNotValidity() {
        // 「仅剩2天」是促销文案，没有起算点，不能当有效期
        assertNull(IntentDateExtractor.findRelativeDeadline("仅剩2天"))
    }

    @Test
    fun endedWordingIsDetected() {
        assertTrue(IntentDateExtractor.hasEndedWording("本场直播已结束"))
        assertTrue(IntentDateExtractor.hasEndedWording("提取码已过期"))
        assertFalse(IntentDateExtractor.hasEndedWording("限时优惠进行中"))
    }
}
