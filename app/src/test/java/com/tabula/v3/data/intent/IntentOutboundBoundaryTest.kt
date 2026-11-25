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
 * 外发边界的**请求体级**验收（#95 第 3 轮审计，第 1 条）。
 *
 * 审计口径：**检出敏感字段后，如果无法可靠定位并完全掩盖字段值，就丢弃受影响的片段。**
 * 这不是「掩掉一部分、其余照发」，所以下面每条都用「生产代码真正生成的请求体」
 * 来断言——检查 `DeepSeekTextAssist.buildPayload` 的输出，而不是另写一份拼串。
 *
 * 覆盖：
 * 1. **中文值**：`取件码 甲乙丙丁`、`密码 张先生生日` —— 之前的字符集只认
 *    `[A-Za-z0-9_-]`，中文值整段漏掩；
 * 2. **OCR 把字段与值拆成两行**：`验证码` 一行、值在下一行 —— 单行视角看不见；
 * 3. **非标准分隔符**：`＝`（全角等号）、`｜`、`、`、`-`、空格；
 * 4. **日期形态的敏感值**：`验证码 20261110`、`提取码 2026-11-10` —— 不得被
 *    「保护日期」还原出来；
 * 5. **正向**：真正的安全截止日期 `活动已于 2026-11-10 23:59 结束` 必须保留；
 * 6. **零请求**：开关关闭 / 无 Key / 片段全被丢弃时，请求数为 0；
 * 7. **无图像**：请求体不含原图、缩略图、图片编码、路径。
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

    /** 走生产代码生成请求体：真正被断言的对象。 */
    private fun payloadFor(vararg lines: String, withKey: Boolean = true): String? {
        val transport = RecordingTransport()
        val assist = DeepSeekTextAssist(keyStore(withKey), userEnabledForBatch = true, transport = transport)
        kotlinx.coroutines.runBlocking {
            assist.classify(IntentTextSanitizer.sanitizeForAssistant(lines.toList()))
        }
        return transport.payloads.singleOrNull()
    }

    /** 断言某个字面量没有出现在请求体里（也没有出现在被丢弃之外的任何地方）。 */
    private fun assertNotInPayload(secret: String, payload: String?, label: String) {
        if (payload == null) return
        assertFalse("$label：请求体里出现了 $secret —— $payload", payload.contains(secret))
    }

    // ==================== 1. 中文值 ====================

    @Test
    fun chineseSecretValueNeverReachesTheRequest() {
        val payload = payloadFor("取件码 甲乙丙丁", "取件码 张三丰", "密码 春天来了")
        listOf("甲乙丙丁", "张三丰", "春天来了").forEach { secret ->
            assertNotInPayload(secret, payload, "中文值")
        }
    }

    @Test
    fun chineseSecretValueWithNoSeparatorIsAlsoDropped() {
        // 没有分隔符、字段与值直接相连：定位仍应成功
        val payload = payloadFor("验证码甲乙丙丁", "验证码是一二三四")
        listOf("甲乙丙丁", "是一二三四").forEach { secret ->
            assertNotInPayload(secret, payload, "无分隔符中文值")
        }
    }

    // ==================== 2. OCR 把字段与值拆成两行 ====================

    @Test
    fun secretKeyOnItsOwnLineDropsTheFollowingLine() {
        val snippet = IntentTextSanitizer.sanitizeForAssistant(
            listOf("【示例平台】", "验证码", "123456", "5分钟内有效")
        )
        assertTrue("字段所在行不得外发：${snippet.lines}", snippet.lines.none { it == "验证码" })
        assertTrue("值所在行不得外发：${snippet.lines}", snippet.lines.none { it.contains("123456") })
        // 无关行仍应保留，证明不是「整张图不发」
        assertTrue("有效期应保留：${snippet.lines}", snippet.lines.any { it.contains("5分钟") })
    }

    @Test
    fun secretKeyOnItsOwnLineDropsChineseValueLine() {
        val payload = payloadFor("提取码", "甲乙丙丁", "有效期至 2026-11-02")
        assertNotInPayload("甲乙丙丁", payload, "跨行中文值")
        // 安全截止日期要留下
        assertTrue("安全截止日期应保留：$payload", payload?.contains("2026-11-02") == true)
    }

    @Test
    fun splitValueAcrossLinesIsNotSentEvenIfDigitsAreSeparated() {
        val snippet = IntentTextSanitizer.sanitizeForAssistant(
            listOf("验证码", "1234 5678", "请在页面输入")
        )
        assertTrue(snippet.lines.none { it.contains("1234") })
        assertTrue(snippet.lines.none { it.contains("5678") })
    }

    // ==================== 3. 非标准分隔符 ====================

    @Test
    fun nonStandardSeparatorsStillMaskTheValue() {
        listOf(
            "验证码＝4471",
            "验证码|4471",
            "验证码、4471",
            "验证码-4471",
            "验证码 4471",
            "验证码:4471"
        ).forEach { line ->
            val payload = payloadFor(line)
            assertNotInPayload("4471", payload, "非标准分隔符 $line")
        }
    }

    @Test
    fun fullWidthEqualsWithChineseValueIsMasked() {
        val payload = payloadFor("提取码＝春夏秋冬")
        assertNotInPayload("春夏秋冬", payload, "全角等号 + 中文值")
    }

    // ==================== 4. 日期形态的敏感值 ====================

    @Test
    fun dateShapedSecretIsNeverRestoredByDatePreservation() {
        listOf(
            "验证码 20261110",
            "提取码 2026-11-10",
            "卡号 2026-1110-3344",
            "密码 2026-11-10"
        ).forEach { line ->
            val payload = payloadFor(line)
            listOf("20261110", "2026-11-10", "2026-1110-3344").forEach { secret ->
                assertNotInPayload(secret, payload, "日期形态敏感值 $line")
            }
        }
    }

    @Test
    fun dateShapedSecretIsDroppedRatherThanPartiallyMasked() {
        val line = IntentTextSanitizer.sanitizeLine("验证码 20261110")
        assertTrue("必须整行留在本地", line.undecidable)
    }

    // ==================== 5. 正向：安全截止日期 ====================

    @Test
    fun safeDeadlineSurvivesIntoTheRequest() {
        val payload = payloadFor("满199减30", "活动已于 2026-11-10 23:59 结束")
        assertTrue("安全截止日期必须保留：$payload", payload?.contains("2026-11-10") == true)
        assertTrue("时刻也要保留：$payload", payload?.contains("23:59") == true)
        assertTrue("促销文案应保留：$payload", payload?.contains("满199减30") == true)
    }

    @Test
    fun safeMonthDayAndDurationSurvive() {
        val payload = payloadFor("报名截止 11月6日 18:00", "本活动 7 天内有效")
        assertTrue("月日应保留：$payload", payload?.contains("11月6日") == true)
        assertTrue("时长应保留：$payload", payload?.contains("7 天") == true)
    }

    // ==================== 6. 零请求 ====================

    @Test
    fun disabledOrKeylessMeansZeroRequests() {
        listOf(false to true, true to false).forEach { (enabled, withKey) ->
            val transport = RecordingTransport()
            val assist = DeepSeekTextAssist(keyStore(withKey), enabled, transport)
            kotlinx.coroutines.runBlocking {
                assist.classify(IntentTextSanitizer.sanitizeForAssistant(listOf("满199减30", "活动已于 2026-11-10 结束")))
            }
            assertEquals(
                "enabled=$enabled withKey=$withKey 时必须零请求",
                0,
                transport.payloads.size
            )
        }
    }

    @Test
    fun allDroppedMeansZeroRequests() {
        val transport = RecordingTransport()
        val assist = DeepSeekTextAssist(keyStore(), true, transport)
        // 全是敏感字段行 → 片段为空 → 不得发请求
        kotlinx.coroutines.runBlocking {
            assist.classify(IntentTextSanitizer.sanitizeForAssistant(listOf("验证码 20261110", "提取码 abc123")))
        }
        assertEquals(0, transport.payloads.size)
    }

    // ==================== 7. 无图像 / 无路径 ====================

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

    // ==================== 8. 残留检查 ====================

    @Test
    fun maskLeavingLongAlphanumericResidueDropsTheLine() {
        // 值被 OCR 拆成两段，第一段掩掉后仍残留第二段 → 整行丢弃，而不是发半截
        val line = IntentTextSanitizer.sanitizeLine("验证码 4471abcd8899")
        assertFalse("不得发半截值：${line.text}", line.text.contains("8899"))
        assertTrue(line.undecidable)
    }
}
