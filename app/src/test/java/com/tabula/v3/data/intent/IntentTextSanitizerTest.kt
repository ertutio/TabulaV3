package com.tabula.v3.data.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脱敏单测（#94，第 2 轮审计后重写）。
 *
 * 这一版的契约变了：**先识别敏感字段与上下文，再决定日期能不能保留**。
 * 因此最重要的用例不是「验证码被掩掉」，而是
 * [secretThatLooksLikeADateIsNeverRestoredByDatePreservation]：
 * 字段值长得像日期时，绝不能因为「日期要保留」而被还原回外发文本。
 */
class IntentTextSanitizerTest {

    private fun text(raw: String) = IntentTextSanitizer.sanitizeLine(raw).text

    // ==================== 敏感字段的上下文优先 ====================

    @Test
    fun secretThatLooksLikeADateIsNeverRestoredByDatePreservation() {
        // 关键回归：上一版会先把 20261110 当日期保下来，再还原回去
        listOf(
            "验证码 20261110",
            "提取码 2026-11-10",
            "卡号 2026-1110-3344",
            "密码 20261110"
        ).forEach { raw ->
            val line = IntentTextSanitizer.sanitizeLine(raw)
            assertFalse("不得还原出原始值：$raw → ${line.text}", line.text.contains("20261110"))
            assertFalse("不得还原出原始值：$raw → ${line.text}", line.text.contains("2026-11-10"))
            assertFalse("不得还原出原始值：$raw → ${line.text}", line.text.contains("2026-1110-3344"))
            assertTrue("这类行必须留在本地：$raw", line.undecidable)
        }
    }

    @Test
    fun secretValueIsMaskedEvenWhenShort() {
        assertFalse(text("验证码 123456").contains("123456"))
        assertFalse(text("取件码 8-2-1024").contains("8-2-1024"))
        assertFalse(text("提取码 3f9k").contains("3f9k"))
        assertFalse(text("密码 Abc12345").contains("Abc12345"))
    }

    @Test
    fun linkIsMaskedIncludingOcrGarble() {
        // 真实 OCR 会把 // 读成 W；这是设备实测抓到的漏洞
        listOf(
            "复制链接 https://pan.example.com/s/AbCd1234",
            "复制链接 https:Wpan.example.com/s/AbCd1234",
            "复制链接 https:Wpan.＊＊＊＊"
        ).forEach { raw ->
            val out = text(raw)
            assertFalse("链接不得外发：$raw → $out", out.contains("pan."))
            assertFalse("链接内容不得外发：$raw → $out", out.contains("AbCd1234"))
        }
    }

    @Test
    fun phoneAndOrderNumberAreMasked() {
        assertFalse(text("联系电话 13800001111").contains("13800001111"))
        assertFalse(text("订单号 TB202411250001").contains("202411250001"))
    }

    // ==================== 安全日期必须保留 ====================

    @Test
    fun safeDeadlineDatesArePreserved() {
        // 这些是判断过期的唯一依据，掩掉就让辅助失去全部判断力
        assertEquals("活动已于 2026-11-10 23:59 结束", text("活动已于 2026-11-10 23:59 结束"))
        assertEquals("有效期至 2026年11月01日", text("有效期至 2026年11月01日"))
        assertEquals("报名截止 11月6日 18:00", text("报名截止 11月6日 18:00"))
        assertEquals("5分钟内有效", text("5分钟内有效"))
    }

    @Test
    fun dateInsideALinkIsDroppedNotPreserved() {
        val out = text("活动页 https://example.com/2026-11-10/promo")
        assertFalse("链接内日期不得保留：$out", out.contains("2026-11-10"))
        assertFalse("链接本身也不得外发：$out", out.contains("example.com"))
    }

    @Test
    fun ordinaryCopyPassesThroughUnmasked() {
        val line = IntentTextSanitizer.sanitizeLine("本场直播已结束")
        assertFalse(line.masked)
        assertFalse(line.undecidable)
        assertEquals("本场直播已结束", line.text)
    }

    // ==================== 不可判定与丢弃 ====================

    @Test
    fun mostlyDigitsLineIsUndecidable() {
        assertTrue(IntentTextSanitizer.sanitizeLine("8823 4471").undecidable)
    }

    @Test
    fun snippetDropsUndecidableLinesAndReportsIt() {
        val snippet = IntentTextSanitizer.sanitizeForAssistant(
            listOf("【某平台】", "验证码 20261110", "8823 4471", "取件请到 3 号柜")
        )
        assertTrue(snippet.hadUndecidableLines)
        assertTrue(snippet.droppedLineCount >= 2)
        snippet.lines.forEach { line ->
            assertFalse("片段里不得出现验证码值：$line", line.contains("20261110"))
        }
    }

    @Test
    fun snippetIsTruncatedToLineAndCharLimits() {
        val long = (1..30).map { "第${it}行说明文字" }
        val snippet = IntentTextSanitizer.sanitizeForAssistant(long, maxLines = 5, maxCharsPerLine = 6)
        assertEquals(5, snippet.lines.size)
        assertTrue(snippet.lines.all { it.length <= 6 })
    }

    @Test
    fun emptyInputGivesEmptySnippet() {
        assertTrue(IntentTextSanitizer.sanitizeForAssistant(emptyList()).isEmpty)
    }

    // ==================== 明确记录的边界 ====================

    @Test
    fun ocrGarbledSecretIsABoundaryNotAGuarantee() {
        // OCR 把 123456 读成 l23456 时，「验证码」键还在 → 键值规则仍能掩掉。
        val line = IntentTextSanitizer.sanitizeLine("验证码 l23456")
        assertFalse(line.text.contains("l23456"))

        // 但如果没有键、又只有 4 位，通用规则认不出来——这是刻意取舍
        // （否则「满199减30」之类正常文案会被抹成星号）。
        // 真正的兜底是上层「证据不足不发」，这里如实记录。
        val plain = IntentTextSanitizer.sanitizeLine("今日特价 8823")
        assertEquals("今日特价 8823", plain.text)
    }

    @Test
    fun canSendReflectsTheSameDecision() {
        assertTrue(IntentTextSanitizer.canSend("活动已于 2026-11-10 结束"))
        assertFalse(IntentTextSanitizer.canSend("8823 4471"))
        assertFalse(IntentTextSanitizer.canSend("验证码 20261110"))
    }
}
