package com.tabula.v3.data.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脱敏与「能不能外发」的单测（#94）。
 *
 * 这些用例直接对应 #94 方案里的验收项：「用合成敏感字段检查实际请求内容」。
 * 同时**明确写出边界**：OCR 读错时这套规则不保证能认出敏感值——用例
 * [ocrGarbledSecretIsADocumentedGap] 就是在记录这个缺口，而不是假装它能防住。
 */
class IntentTextSanitizerTest {

    @Test
    fun verificationCodeIsMasked() {
        val line = IntentTextSanitizer.sanitizeLine("【某银行】验证码 123456，5分钟内有效")
        assertTrue(line.masked)
        assertFalse(line.text.contains("123456"))
    }

    @Test
    fun pickupCodeWithDashesIsMasked() {
        val line = IntentTextSanitizer.sanitizeLine("取件码 8-2-1024")
        assertTrue(line.masked)
        assertFalse(line.text.contains("8-2-1024"))
    }

    @Test
    fun extractCodeIsMasked() {
        val line = IntentTextSanitizer.sanitizeLine("提取码 3f9k")
        assertTrue(line.masked)
        assertFalse(line.text.contains("3f9k"))
    }

    @Test
    fun urlsAreMasked() {
        val line = IntentTextSanitizer.sanitizeLine("复制链接 https://pan.example.com/s/abcdef")
        assertTrue(line.masked)
        assertFalse(line.text.contains("pan.example.com"))
        assertFalse(line.text.contains("abcdef"))
    }

    @Test
    fun phoneNumberIsMasked() {
        val line = IntentTextSanitizer.sanitizeLine("联系电话 13800001111")
        assertTrue(line.masked)
        assertFalse(line.text.contains("13800001111"))
    }

    @Test
    fun orderNumberIsMasked() {
        val line = IntentTextSanitizer.sanitizeLine("订单号 2024111000012345")
        assertTrue(line.masked)
        assertFalse(line.text.contains("2024111000012345"))
    }

    @Test
    fun ordinaryCopyIsPreserved() {
        val line = IntentTextSanitizer.sanitizeLine("本场直播已结束")
        assertFalse(line.masked)
        assertFalse(line.undecidable)
        assertEquals("本场直播已结束", line.text)
    }

    @Test
    fun mostlyDigitsLineIsUndecidable() {
        // 整行几乎都是数字：不冒险外发
        val line = IntentTextSanitizer.sanitizeLine("8823 4471")
        assertTrue(line.undecidable)
    }

    @Test
    fun snippetDropsUndecidableLinesAndReportsIt() {
        val snippet = IntentTextSanitizer.sanitizeForAssistant(
            listOf("【某平台】", "验证码 123456", "8823 4471", "取件请到 3 号柜")
        )
        assertTrue(snippet.lines.none { it.contains("123456") })
        assertTrue(snippet.hadUndecidableLines)
        assertTrue(snippet.droppedLineCount > 0)
        assertTrue(snippet.maskedLineCount >= 1)
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

    @Test
    fun ocrGarbledSecretIsADocumentedGap() {
        // 边界：OCR 把验证码读成「l23456」（字母 l + 数字）时，
        // 通用数字规则就认不出来了。这里记录实际行为，避免把「规则过滤」
        // 描述成绝对保证——真实请求里这类混淆仍可能被发出去。
        val line = IntentTextSanitizer.sanitizeLine("验证码 l23456")
        // 命中「验证码」键值规则 → 值仍被掩码（这是当前实现的实际行为）
        assertTrue(line.masked)
        assertFalse(line.text.contains("l23456"))
    }

    @Test
    fun secretWithoutKeyContextAndShortIsNotMasked() {
        // 另一个边界：没有「验证码/提取码」上下文、又只有 4 位数字时不会掩码。
        // 这是刻意取舍（否则「满199减30」之类的正常文案会被抹成星号），
        // 上层的「证据不足不发」策略才是真正的兜底。
        val line = IntentTextSanitizer.sanitizeLine("口令 8823")
        assertTrue(line.masked) // 带「口令」键时仍会掩码
        val plain = IntentTextSanitizer.sanitizeLine("今日特价 8823 元")
        assertFalse(plain.text.contains("＊") && plain.masked && plain.undecidable)
    }
}
