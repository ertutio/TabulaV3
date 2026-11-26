package com.tabula.v3.data.intent

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 响应用的**合法 JSON 解析**回归（本轮修复）。
 *
 * ## 修的缺陷
 *
 * `DeepSeekTextAssist.extractContent()` 原先用固定字符串 `"content":"`（冒号后**无空格**）
 * 去 `indexOf`。这条假设在真实 OpenAI 兼容响应里不成立：
 *
 * ```json
 * {"choices":[{"message":{"role":"assistant","content": "KEEP"}}]}
 * ```
 *
 * 只要序列化器在冒号后留一个空格（或换行、`\t`），`indexOf` 就返回 -1，
 * 客户端把一份**完全合法的 KEEP 建议**当成「无建议」，回退本地规则——
 * 用户开了辅助却拿不到它的结论，而且没有任何报错，属静默降级。
 *
 * ## 修法
 *
 * 用 JSON 解析器读 `choices[0].message.content`，不再假设空白与字段顺序：
 * - 冒号后有空格 / 换行 / tab 都能读到；
 * - 字段顺序变化、`role` 在 `content` 之后也能读到；
 * - 转义（`\"`、`\\`、`\uXXXX`）由解析器处理，不再手写状态机；
 * - **回退语义全部保留**：空 `content`、`choices` 为空、非法 JSON、非字符串
 *   `content`、未知建议词 → 一律空建议（`UNCERTAIN` + 空 reason），不抛异常。
 *
 * ## 为什么用模拟 HTTP 响应而不是只测解析函数
 *
 * `extractContent` 是 private。测试打在生产路径上：给 [DeepSeekTextAssist] 一个
 * 记录型传输层，让它返回给定的响应体，然后断言 `classify` 的结果。
 * 这样断言的是「客户端从这份响应里读出了什么」，而不是某个内部函数的返回值——
 * 将来换解析实现，这些用例仍然有效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentAssistResponseParsingTest {

    private lateinit var keyFile: File
    private lateinit var keyStore: IntentAssistKeyStore

    /** 固定返回一段给定响应的传输层；记录请求次数。 */
    private class FixedTransport(private val response: String) : AssistTransport {
        var calls = 0
        override fun post(
            url: String,
            body: String,
            authHeader: String,
            timeoutMillis: Int
        ): String {
            calls++
            return response
        }
    }

    @Before
    fun setUp() {
        keyFile = File.createTempFile("assist-parse-key", ".tmp").apply { deleteOnExit() }
        keyStore = IntentAssistKeyStore(keyFile)
        keyStore.delete()
        keyStore.write("sk-synthetic-parsing-0001")
    }

    private fun assist(transport: AssistTransport) = DeepSeekTextAssist(
        keyStore = keyStore,
        userEnabledForBatch = true,
        transport = transport
    )

    /** 一张可发送的干净截图（不含敏感字段，否则会零请求）。 */
    private fun snippet() = IntentTextSanitizer.sanitizeForAssistant(
        listOf("满199减30", "活动已于 2026-11-10 23:59 结束")
    )

    private fun classify(response: String): AssistSuggestion {
        val transport = FixedTransport(response)
        val result = runBlocking { assist(transport).classify(snippet()) }
        assertEquals("这条用例只需要一次请求", 1, transport.calls)
        return result
    }

    // ==================== 1. 本轮缺陷：冒号后的空白 ====================

    /**
     * **回归用例**：`"content": "KEEP"` —— 冒号后有一个空格。
     *
     * 修复前：`indexOf("\"content\":\"")` = -1 → 空建议 → 用户的 KEEP 被丢掉。
     */
    @Test
    fun contentWithSpaceAfterColonIsParsed() {
        val suggestion = classify(
            """{"choices":[{"message":{"role":"assistant","content": "KEEP"}}]}"""
        )
        assertEquals(
            "冒号后有空格也必须读到 KEEP（否则合法建议被静默丢弃）",
            IntentRecommendation.KEEP, suggestion.recommendation
        )
        assertEquals(IntentCategory.PROTECTED, suggestion.category)
        assertTrue("必须给出理由", suggestion.reason.isNotEmpty())
    }

    @Test
    fun contentWithNewlineAfterColonIsParsed() {
        // 有些序列化器会 pretty-print：冒号后是换行 + 缩进
        val suggestion = classify(
            "{\n  \"choices\": [\n    {\n      \"message\": {\n" +
                "        \"role\": \"assistant\",\n        \"content\":\n          \"LOWER\"\n      }\n" +
                "    }\n  ]\n}"
        )
        assertEquals(
            "换行 + 缩进也必须读到 LOWER",
            IntentRecommendation.LOWER_PRIORITY, suggestion.recommendation
        )
    }

    @Test
    fun contentWithTabAfterColonIsParsed() {
        val suggestion = classify(
            "{\"choices\":[{\"message\":{\"content\":\t\"KEEP\"}}]}"
        )
        assertEquals(IntentRecommendation.KEEP, suggestion.recommendation)
    }

    @Test
    fun fieldOrderDoesNotMatter() {
        // content 在 role 之前：旧的固定字符串查找恰好能读到，但字段顺序变化时
        // 手写状态机很容易越界；这里钉住「与顺序无关」
        val suggestion = classify(
            """{"choices":[{"message":{"content":"KEEP","role":"assistant"}}]}"""
        )
        assertEquals(IntentRecommendation.KEEP, suggestion.recommendation)
    }

    @Test
    fun extraFieldsAreIgnored() {
        // 真实响应会带 id / object / finish_reason / usage / opencode.* 等
        val suggestion = classify(
            """{"id":"chatcmpl-1","object":"chat.completion","created":1796000000,""" +
                """"model":"deepseek-chat","choices":[{"index":0,"finish_reason":"stop",""" +
                """"message":{"role":"assistant","content": "KEEP"}}],""" +
                """"usage":{"prompt_tokens":12,"completion_tokens":1},""" +
                """"opencode":{"billing_source":"go","allowance":0.0001}}"""
        )
        assertEquals(
            "多余字段不得干扰解析", IntentRecommendation.KEEP, suggestion.recommendation
        )
    }

    @Test
    fun escapedContentIsDecoded() {
        // content 里含转义引号与换行：解析器应解码后按前缀判断
        val suggestion = classify(
            """{"choices":[{"message":{"content":"KEEP\n\"why\": 票据"}}]}"""
        )
        assertEquals(IntentRecommendation.KEEP, suggestion.recommendation)
    }

    // ==================== 2. 回退语义必须原样保留 ====================

    @Test
    fun emptyContentFallsBackToLocal() {
        val suggestion = classify("""{"choices":[{"message":{"content": ""}}]}""")
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
        assertTrue("空建议必须不带理由", suggestion.reason.isEmpty())
    }

    @Test
    fun whitespaceOnlyContentFallsBackToLocal() {
        val suggestion = classify("""{"choices":[{"message":{"content": "   \n\t "}}]}""")
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
    }

    @Test
    fun missingChoicesFallsBackToLocal() {
        val suggestion = classify("""{"id":"chatcmpl-1","object":"chat.completion"}""")
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
    }

    @Test
    fun emptyChoicesFallsBackToLocal() {
        val suggestion = classify("""{"choices":[]}""")
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
    }

    @Test
    fun malformedJsonFallsBackToLocal() {
        val suggestion = classify("""{"choices":[{"message":{"content": "KEEP""")
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
    }

    @Test
    fun htmlErrorPageFallsBackToLocal() {
        val suggestion = classify("<html><body>502 Bad Gateway</body></html>")
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
    }

    @Test
    fun nonStringContentFallsBackToLocal() {
        val suggestion = classify("""{"choices":[{"message":{"content": 42}}]}""")
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
    }

    @Test
    fun nullContentFallsBackToLocal() {
        val suggestion = classify("""{"choices":[{"message":{"content": null}}]}""")
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
    }

    /** 模型返回清理动作时必须**不采信**——这条是安全边界，不能因为换解析器而放松。 */
    @Test
    fun unknownAdviceNeverBecomesACleanupAction() {
        listOf(
            """{"choices":[{"message":{"content": "DELETE all of them now"}}]}""",
            """{"choices":[{"message":{"content": "CLEAN"}}]}""",
            """{"choices":[{"message":{"content": "MOVE TO RECYCLE BIN"}}]}""",
            """{"choices":[{"message":{"content": "KEEP 并且全部删除"}}]}"""
        ).forEach { response ->
            val suggestion = classify(response)
            if (response.contains("KEEP 并且全部删除")) {
                // 以 KEEP 开头：按「保护方向」采信，仍然不是清理动作
                assertEquals(
                    "KEEP 开头必须判保护，绝不能变成清理：$response",
                    IntentRecommendation.KEEP, suggestion.recommendation
                )
            } else {
                assertEquals(
                    "未知建议只能回退，不得产生清理动作：$response",
                    IntentRecommendation.UNCERTAIN, suggestion.recommendation
                )
            }
            assertTrue(
                "任何情况下都不得直接产出「可清理」的批次动作：$response",
                suggestion.recommendation != IntentRecommendation.LOWER_PRIORITY ||
                    response.contains("LOWER")
            )
        }
    }

    @Test
    fun lowercaseAdviceIsAcceptedRegardlessOfCase() {
        assertEquals(
            "小写建议词必须同样识别",
            IntentRecommendation.KEEP, classify("""{"choices":[{"message":{"content":"keep"}}]}""").recommendation
        )
        assertEquals(
            "小写建议词必须同样识别",
            IntentRecommendation.LOWER_PRIORITY,
            classify("""{"choices":[{"message":{"content":" lower "}}]}""").recommendation
        )
    }

    /** 前导空白（真实模型常带）不得影响判定。 */
    @Test
    fun leadingWhitespaceInContentIsTrimmed() {
        val suggestion = classify("""{"choices":[{"message":{"content": "  \n  LOWER"}}]}""")
        assertEquals(IntentRecommendation.LOWER_PRIORITY, suggestion.recommendation)
    }
}
