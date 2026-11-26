package com.tabula.v3.data.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保护优先 + 分类 + 过期判定的单测（#94）。
 *
 * 每个用例都对应路线图第 8 项的一条硬约束。最要紧的是三条：
 * 1. 保护信号压过清理信号；
 * 2. 「截图存得久」不是「已失效」；
 * 3. 证据不足要给「不确定」，不许硬凑。
 */
class IntentRulesTest {

    private val now = 1_796_000_000_000L // 2026-11-25 附近，仅用于 ageDays/比较
    private val recent = now - 3 * 86_400_000L
    private val old = now - 40 * 86_400_000L

    private fun classify(
        vararg lines: String,
        savedAt: Long = old,
        confidence: Float = 0.9f,
        retained: Boolean = false
    ) = IntentRules.classify(
        lines = lines.toList(),
        savedAtMillis = savedAt,
        nowMillis = now,
        ocrConfidence = confidence,
        retainedOverride = retained
    )

    // ==================== 保护优先 ====================

    @Test
    fun orderScreenshotIsProtected() {
        val verdict = classify("订单详情", "订单号 2024TB0001", "待收货", "实付款 ¥199.00")
        assertEquals(IntentCategory.PROTECTED, verdict.category)
        assertEquals(IntentRecommendation.KEEP, verdict.recommendation)
        assertTrue(verdict.isProtectionExclusion)
    }

    @Test
    fun protectionWinsOverExpiredCouponOnTheSameScreenshot() {
        // 同一张图上既有「限时优惠已结束」也有订单号：必须保住
        val verdict = classify(
            "限时优惠 已结束", "订单详情", "订单号 2024TB0001", "活动已于 2026-11-10 23:59 结束"
        )
        assertEquals(IntentCategory.PROTECTED, verdict.category)
        assertEquals(IntentRecommendation.KEEP, verdict.recommendation)
        assertEquals(ExpiryState.UNKNOWN, verdict.expiry)
    }

    @Test
    fun invoiceTicketCoursewareAreProtected() {
        assertEquals(IntentCategory.PROTECTED, classify("电子发票", "开票金额 ¥88.00").category)
        assertEquals(IntentCategory.PROTECTED, classify("电影票", "座位号 7排8座", "请勿删除").category)
        assertEquals(IntentCategory.PROTECTED, classify("第三章 讲义", "考点：傅里叶变换").category)
    }

    @Test
    fun retainedOverrideBeatsEverything() {
        val verdict = classify("满199减30", "活动已于 2026-11-10 结束", retained = true)
        assertEquals(IntentCategory.PROTECTED, verdict.category)
        assertEquals(IntentRecommendation.KEEP, verdict.recommendation)
        assertTrue(IntentSignal.LONG_TERM in verdict.signals)
    }

    // ==================== 证据不足 ====================

    @Test
    fun emptyTextIsUncertain() {
        val verdict = classify()
        assertEquals(IntentCategory.UNCERTAIN, verdict.category)
        assertEquals(IntentRecommendation.UNCERTAIN, verdict.recommendation)
    }

    @Test
    fun lowConfidenceIsUncertainEvenWithText() {
        val verdict = classify("满199减30", "活动已于 2026-11-10 结束", confidence = 0.2f)
        assertEquals(IntentCategory.UNCERTAIN, verdict.category)
        assertEquals(IntentRecommendation.UNCERTAIN, verdict.recommendation)
    }

    @Test
    fun singleLineOfTextIsUncertain() {
        val verdict = classify("¥199", confidence = 0.9f)
        assertEquals(IntentCategory.UNCERTAIN, verdict.category)
    }

    // ==================== 过期判定 ====================

    @Test
    fun couponWithPastDeadlineIsRecommended() {
        val verdict = classify("满199减30", "活动已于 2026-11-10 23:59 结束")
        assertEquals(IntentCategory.EXPIRED_COUPON, verdict.category)
        assertEquals(IntentRecommendation.RECOMMEND, verdict.recommendation)
        assertEquals(ExpiryState.EXPIRED, verdict.expiry)
        assertTrue(verdict.deadlineMillis != null)
    }

    @Test
    fun couponWithFutureDeadlineIsNotExpired() {
        val verdict = classify("满199减30", "有效期至 2026-12-31")
        assertEquals(IntentCategory.EXPIRED_COUPON, verdict.category)
        assertEquals(ExpiryState.STILL_VALID, verdict.expiry)
        assertEquals(IntentRecommendation.LOWER_PRIORITY, verdict.recommendation)
    }

    @Test
    fun couponWithoutDeadlineIsLowerPriorityNotExpired() {
        val verdict = classify("满199减30", "仅剩2天", "包邮")
        assertEquals(IntentCategory.EXPIRED_COUPON, verdict.category)
        assertEquals(ExpiryState.UNKNOWN, verdict.expiry)
        assertEquals(IntentRecommendation.LOWER_PRIORITY, verdict.recommendation)
        // 理由里不能出现「已失效」
        assertFalse(verdict.reason.contains("已失效"))
    }

    @Test
    fun oldPriceScreenshotStaysLowPriority() {
        val verdict = classify("原价 ¥299", "现价 ¥199", "到手价 ¥179")
        assertEquals(IntentCategory.OLD_PRICE, verdict.category)
        assertEquals(IntentRecommendation.LOWER_PRIORITY, verdict.recommendation)
        assertEquals(ExpiryState.UNKNOWN, verdict.expiry)
    }

    @Test
    fun relativeValidityWithoutBasisIsNotExpired() {
        val verdict = classify("复制链接", "提取码 3f9k", "分享有效期 7 天")
        // 有「有效期 N 天」但没有起算点：不能认定已失效
        assertFalse(verdict.expiry == ExpiryState.EXPIRED)
    }

    @Test
    fun endedNewsLiveIsRecommended() {
        val verdict = classify("直播预告 11月8日 20:00", "本场直播已结束")
        assertEquals(IntentCategory.STALE_NEWS, verdict.category)
        assertEquals(IntentRecommendation.RECOMMEND, verdict.recommendation)
        assertEquals(ExpiryState.EXPIRED, verdict.expiry)
    }

    @Test
    fun longFormArticleIsNotRecommendedJustBecauseItIsOld() {
        val verdict = classify("深度报道：城市里的旧书店", "记者 张三", savedAt = now - 90 * 86_400_000L)
        assertEquals(IntentRecommendation.LOWER_PRIORITY, verdict.recommendation)
        assertFalse(verdict.recommendation == IntentRecommendation.RECOMMEND)
    }

    // ==================== 分享 ====================

    @Test
    fun shareWithExplicitExpiryIsRecommended() {
        val verdict = classify("百度网盘", "复制链接", "提取码已过期", "有效期至 2026-11-01")
        assertEquals(IntentCategory.SHARE, verdict.category)
        assertEquals(IntentRecommendation.RECOMMEND, verdict.recommendation)
    }

    @Test
    fun shareWithoutExpiryEvidenceIsUncertain() {
        val verdict = classify("某视频平台", "复制链接", "扫码观看")
        assertEquals(IntentCategory.SHARE, verdict.category)
        assertEquals(IntentRecommendation.UNCERTAIN, verdict.recommendation)
    }

    // ==================== 临时码 ====================

    @Test
    fun verificationCodeIsLowerPriorityAndNeverQuoted() {
        val verdict = classify("【某银行】验证码 123456", "5分钟内有效，请勿转发")
        assertEquals(IntentCategory.TEMP_CODE, verdict.category)
        assertTrue(IntentSignal.VERIFICATION_CODE in verdict.signals)
        // 理由里不能带码
        assertFalse(verdict.reason.contains("123456"))
    }

    @Test
    fun pickupCodeWithPickedUpStateIsLowerPriority() {
        val verdict = classify("取件码 8-2-1024", "已取件", "感谢使用")
        assertEquals(IntentCategory.TEMP_CODE, verdict.category)
        assertTrue(IntentSignal.PICKUP_CODE in verdict.signals)
    }

    @Test
    fun expiredInviteIsRecommended() {
        val verdict = classify("临时邀请码 INV-8842", "有效期至 2026-11-02")
        assertEquals(IntentCategory.TEMP_CODE, verdict.category)
        assertEquals(IntentRecommendation.RECOMMEND, verdict.recommendation)
        assertEquals(ExpiryState.EXPIRED, verdict.expiry)
    }

    // ==================== 活动提醒 ====================

    @Test
    fun endedEventReminderIsRecommended() {
        val verdict = classify("抢票提醒 11月5日 10:00 开售", "已结束")
        assertEquals(IntentCategory.EVENT_REMINDER, verdict.category)
        assertEquals(IntentRecommendation.RECOMMEND, verdict.recommendation)
    }

    @Test
    fun eventWithoutDeadlineAndRecentIsUncertain() {
        val verdict = classify("报名开放中", "点击报名", savedAt = recent)
        assertEquals(IntentCategory.EVENT_REMINDER, verdict.category)
        assertEquals(IntentRecommendation.UNCERTAIN, verdict.recommendation)
    }

    @Test
    fun eventWithoutDeadlineButOldIsLowerPriorityNotRecommended() {
        val verdict = classify("报名开放中", "点击报名", savedAt = old)
        assertEquals(IntentRecommendation.LOWER_PRIORITY, verdict.recommendation)
        assertFalse(verdict.recommendation == IntentRecommendation.RECOMMEND)
    }

    // ==================== 理由脱敏 ====================

    @Test
    fun reasonNeverContainsCodesOrLinks() {
        val verdict = classify(
            "取件码 8-2-1024", "取餐码 6621", "https://example.com/abc", "手机号 13800000000"
        )
        listOf("8-2-1024", "6621", "example.com", "13800000000").forEach {
            assertFalse("理由不应包含 $it", verdict.reason.contains(it))
        }
    }
}
