package com.tabula.v3.data.intent

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 外发边界的**请求体级**验收（第 6 轮口径）。
 *
 * 铁律：**检出敏感字段的 OCR 行整行留在本地**，不再做「值到此为止」的放行推断。
 *
 * 这一轮删掉的正是上一版那条推断——用「注意/说明/请勿…」这类自然语言词
 * 判断「敏感值已经结束，后面可以发」。它本身就是风险面：
 * `取件码 注意 说明` 把自然语言词当值用时会被放行。
 *
 * 所有断言都打在**生产代码生成的请求体**上（`buildPayload` 经记录型传输层捕获）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentOutboundBoundaryTest {

    private class RecordingTransport(
        private val response: String = """{"choices":[{"message":{"content":"LOWER"}}]}"""
    ) : AssistTransport {
        val payloads = mutableListOf<String>()
        override fun post(url: String, body: String, authHeader: String, timeoutMillis: Int): String {
            payloads += body
            return response
        }
    }

    private fun keyStore(withKey: Boolean = true): IntentAssistKeyStore {
        val file = File.createTempFile("outbound-key", ".tmp").apply { deleteOnExit() }
        val store = IntentAssistKeyStore(file)
        if (withKey) store.write("sk-synthetic-not-a-real-key") else store.delete()
        return store
    }

    /** 走生产代码生成请求体；没有可发内容时返回 null。 */
    private fun payloadFor(vararg lines: String, withKey: Boolean = true): String? {
        val transport = RecordingTransport()
        val assist = DeepSeekTextAssist(keyStore(withKey), userEnabledForBatch = true, transport = transport)
        kotlinx.coroutines.runBlocking {
            assist.classify(IntentTextSanitizer.sanitizeForAssistant(lines.toList()))
        }
        return transport.payloads.singleOrNull()
    }

    private fun assertNotInPayload(secret: String, payload: String?, label: String) {
        if (payload == null) return
        assertFalse("$label：请求体里出现了 $secret —— $payload", payload.contains(secret))
    }

    // ==================== 1. 含自然语言词的分段敏感值（本轮重点） ====================

    @Test
    fun secretLineWithNaturalLanguageWordsIsHeldBack() {
        // 上一版会因为「注意/说明」判定值已结束而放行整行；现在整行不外发
        listOf(
            "取件码 注意 说明",
            "验证码 请注意 有效期",
            "密码 说明 转发",
            "提取码 请勿 复制"
        ).forEach { line ->
            val payload = payloadFor(line)
            assertEquals("整行必须留在本地：$line", null, payload)
        }
    }

    @Test
    fun secretAndNaturalLanguageInSameLineStillNotSent() {
        // 值后面跟一句真提示语，也不再放行——整行不发
        listOf(
            "验证码 4471，请在页面上输入",
            "取件码 8-2-1024，请注意查收",
            "提取码 3f9k，复制后打开链接"
        ).forEach { line ->
            assertEquals("整行必须留在本地：$line", null, payloadFor(line))
        }
    }

    @Test
    fun secretValueSplitBySpaceWithNaturalLanguageFragmentIsHeldBack() {
        val payload = payloadFor("取件码 甲乙 注意", "密码 春天 说明")
        assertEquals("分段值含自然语言词时整批都不该有可发内容", null, payload)
    }

    // ==================== 2. 字段存在的行一律不外发 ====================

    @Test
    fun anyLineContainingAFieldWordIsHeldBack() {
        listOf(
            "验证码 123456",
            "验证码：",
            "取件码 8-2-1024",
            "订单号 TB202411250001",
            "卡号 2026-1110-3344",
            "验证码 20261110"
        ).forEach { line ->
            assertTrue(
                "含字段词的行必须判为不可外发：$line",
                IntentTextSanitizer.sanitizeLine(line).undecidable
            )
        }
    }

    @Test
    fun chineseValueLineIsHeldBack() {
        listOf("取件码 甲乙丙丁", "密码 春天来了", "验证码甲乙丙丁").forEach { line ->
            assertEquals("整行必须留在本地：$line", null, payloadFor(line))
        }
    }

    @Test
    fun nonStandardSeparatorLinesAreHeldBack() {
        listOf("验证码＝4471", "验证码|4471", "验证码、4471", "验证码-4471", "验证码 4471")
            .forEach { line -> assertEquals("整行必须留在本地：$line", null, payloadFor(line)) }
    }

    @Test
    fun dateShapedSecretIsHeldBack() {
        listOf("验证码 20261110", "提取码 2026-11-10", "卡号 2026-1110-3344").forEach { line ->
            assertEquals("整行必须留在本地：$line", null, payloadFor(line))
        }
    }

    // ==================== 3. 跨行值拦截 ====================

    @Test
    fun fieldOnItsOwnLineDropsTheFollowingLine() {
        val snippet = IntentTextSanitizer.sanitizeForAssistant(
            listOf("【示例平台】", "验证码", "123456", "5分钟内有效")
        )
        assertTrue("字段行不得外发：${snippet.lines}", snippet.lines.none { it == "验证码" })
        assertTrue("值所在行不得外发：${snippet.lines}", snippet.lines.none { it.contains("123456") })
        assertTrue("无关行仍应保留：${snippet.lines}", snippet.lines.any { it.contains("5分钟") })
    }

    @Test
    fun crossLineChineseValueIsDropped() {
        val payload = payloadFor("提取码", "甲乙丙丁", "有效期至 2026-11-02")
        assertNotInPayload("甲乙丙丁", payload, "跨行中文值")
        assertTrue("独立的安全截止日期应保留：$payload", payload?.contains("2026-11-02") == true)
    }

    @Test
    fun splitDigitsAcrossLinesAreNotSent() {
        val snippet = IntentTextSanitizer.sanitizeForAssistant(
            listOf("验证码", "1234 5678", "请在页面输入")
        )
        assertTrue(snippet.lines.none { it.contains("1234") })
        assertTrue(snippet.lines.none { it.contains("5678") })
    }

    // ==================== 4. 正向：独立的安全截止日期 ====================

    @Test
    fun standaloneSafeDeadlineIsSent() {
        // 关键正向用例：**没有**敏感字段的日期行必须完整外发
        val payload = payloadFor("满199减30", "活动已于 2026-11-10 23:59 结束")
        assertTrue("截止日期必须保留：$payload", payload?.contains("2026-11-10") == true)
        assertTrue("时刻必须保留：$payload", payload?.contains("23:59") == true)
        assertTrue("促销文案应保留：$payload", payload?.contains("满199减30") == true)
    }

    @Test
    fun standaloneMonthDayAndDurationAreSent() {
        val payload = payloadFor("报名截止 11月6日 18:00", "本活动 7 天内有效")
        assertTrue("月日应保留：$payload", payload?.contains("11月6日") == true)
        assertTrue("时长应保留：$payload", payload?.contains("7 天") == true)
    }

    @Test
    fun safeDeadlineInSameBatchSurvivesWhenAnotherLineIsHeldBack() {
        // 同批里有一行被扣留，不影响安全日期那一行
        val payload = payloadFor(
            "验证码 4471，请在页面上输入",
            "活动已于 2026-11-10 23:59 结束"
        )
        assertNotInPayload("4471", payload, "被扣留的字段行")
        assertTrue("同批的安全日期仍应发送：$payload", payload?.contains("2026-11-10") == true)
    }

    @Test
    fun dateInsideNonFieldLineIsPreserved() {
        val line = IntentTextSanitizer.sanitizeLine("活动已于 2026-11-10 23:59 结束")
        assertFalse("无字段行不应被判不可发", line.undecidable)
        assertEquals("活动已于 2026-11-10 23:59 结束", line.text)
    }

    // ==================== 5. 零请求 ====================

    @Test
    fun disabledOrKeylessMeansZeroRequests() {
        listOf(false to true, true to false).forEach { (enabled, withKey) ->
            val transport = RecordingTransport()
            val assist = DeepSeekTextAssist(keyStore(withKey), enabled, transport)
            kotlinx.coroutines.runBlocking {
                assist.classify(
                    IntentTextSanitizer.sanitizeForAssistant(listOf("满199减30", "活动已于 2026-11-10 结束"))
                )
            }
            assertEquals("enabled=$enabled withKey=$withKey 必须零请求", 0, transport.payloads.size)
        }
    }

    @Test
    fun onlyHeldBackLinesMeansZeroRequests() {
        val transport = RecordingTransport()
        val assist = DeepSeekTextAssist(keyStore(), true, transport)
        kotlinx.coroutines.runBlocking {
            assist.classify(
                IntentTextSanitizer.sanitizeForAssistant(
                    listOf("验证码 20261110", "取件码 注意 说明")
                )
            )
        }
        assertEquals(0, transport.payloads.size)
    }

    // ==================== 6. 无图像 / 无路径 ====================

    @Test
    fun payloadCarriesNoImageOrPath() {
        val payload = payloadFor("满199减30", "活动已于 2026-11-10 结束")!!
        listOf(
            "base64", "data:image", "content://", "/storage/", "DCIM", ".png", ".jpg",
            "image_url", "file://", "缩略图"
        ).forEach { forbidden ->
            assertFalse("请求体不得包含 $forbidden", payload.contains(forbidden, ignoreCase = true))
        }
        val parsed = JSONObject(payload)
        val allowed = setOf("model", "temperature", "max_tokens", "messages")
        parsed.keys().forEach { key -> assertTrue("非预期字段 $key", key in allowed) }
    }

    // ==================== 7. 非字段类形态仍按区间掩码 ====================

    @Test
    fun nonFieldShapesAreMaskedInPlace() {
        val payload = payloadFor("项目资料.zip", "复制链接 https:Wpan.example.com/s/XyZ98765")
        assertNotInPayload("pan.example.com", payload, "链接")
        assertNotInPayload("XyZ98765", payload, "链接内容")
        assertTrue("同行的普通文字应保留：$payload", payload?.contains("项目资料.zip") == true)
    }

    @Test
    fun bareDigitLineIsHeldBack() {
        assertTrue(IntentTextSanitizer.sanitizeLine("8823 4471").undecidable)
    }
}
