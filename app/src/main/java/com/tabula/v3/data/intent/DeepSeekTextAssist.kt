package com.tabula.v3.data.intent

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 可选的文字辅助：把**已脱敏的 OCR 文字片段**交给 DeepSeek 做一次分类建议。
 *
 * 硬约束（#94 方案 + 本实现的测试都按这些钉）：
 * - 默认关闭：没有 Key、用户没在本批次打开、实现没注册 → [isEnabled] 为 false，完全不发请求；
 * - 只发文字：请求体里只有脱敏后的行，**没有**原图、缩略图、base64、URI、路径、文件名；
 * - 不扩权：模型只能给「保留」或「低优先级」建议，不能新建清理动作、不能认定失效；
 *   保护判定在 [IntentBatchBuilder] 里先于模型执行，模型改不动；
 * - 失败即回退：无网、超时、Key 无效、返回不可解析 → 返回 [AssistSuggestion] 的空建议，
 *   上层沿用本地结果。
 *
 * 说明：这里用最朴素的 [HttpURLConnection]，不引入 OkHttp，避免为一个默认关闭的可选能力
 * 增加依赖与 APK 体积。
 */
class DeepSeekTextAssist(
    private val keyStore: IntentAssistKeyStore,
    private val userEnabledForBatch: Boolean,
    private val transport: AssistTransport = HttpAssistTransport(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS
) : TextAssist {

    override val isEnabled: Boolean
        get() = userEnabledForBatch && keyStore.hasKey()

    /** 最近一次实际请求体（只用于测试与「不含图像/敏感原文」的断言；不落盘）。 */
    @Volatile
    var lastRequestPayload: String? = null
        private set

    /** 最近一次失败原因（简短、不含 Key 与原文）。 */
    @Volatile
    var lastFailure: String? = null
        private set

    override suspend fun classify(
        snippet: IntentTextSanitizer.SanitizedSnippet
    ): AssistSuggestion {
        if (!isEnabled) return EMPTY
        if (snippet.isEmpty) return EMPTY
        val key = keyStore.read() ?: return EMPTY
        val payload = buildPayload(snippet)
        lastRequestPayload = payload
        return try {
            val response = transport.post(
                url = "$baseUrl/chat/completions",
                body = payload,
                authHeader = "Bearer $key",
                timeoutMillis = timeoutMillis
            )
            parse(response)
        } catch (e: IOException) {
            lastFailure = "network"
            EMPTY
        } catch (e: Throwable) {
            lastFailure = "unexpected"
            EMPTY
        }
    }

    /**
     * 请求体。**唯一**写入用户文字的地方，并且输入的 [snippet] 已经过
     * [IntentTextSanitizer.sanitizeForAssistant]：掩码过、丢过不确定行、截断过长度。
     */
    private fun buildPayload(snippet: IntentTextSanitizer.SanitizedSnippet): String {
        val joined = snippet.lines.joinToString("\n")
        // 手工拼 JSON：只有两个字段会被插入，且都做过转义，避免引入序列化库
        val system = escape(SYSTEM_PROMPT)
        val user = escape(joined)
        return """{"model":"$model","temperature":0,"max_tokens":80,"messages":[""" +
            """{"role":"system","content":"$system"},""" +
            """{"role":"user","content":"$user"}]}"""
    }

    private fun parse(raw: String): AssistSuggestion = try {
        // 只认两种输出：KEEP / LOWER，其它一律无建议（含模型返回清理动作的情况）
        val content = extractContent(raw)?.trim().orEmpty()
        when {
            content.startsWith("KEEP", ignoreCase = true) -> AssistSuggestion(
                recommendation = IntentRecommendation.KEEP,
                reason = "文字辅助判断为应保留",
                category = IntentCategory.PROTECTED
            )
            content.startsWith("LOWER", ignoreCase = true) -> AssistSuggestion(
                recommendation = IntentRecommendation.LOWER_PRIORITY,
                reason = "文字辅助判断为低优先级"
            )
            else -> EMPTY
        }
    } catch (e: Throwable) {
        lastFailure = "parse"
        EMPTY
    }

    /** 从 OpenAI 兼容响应里取 choices[0].message.content，不引入 JSON 库。 */
    private fun extractContent(raw: String): String? {
        val key = "\"content\":\""
        val start = raw.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        val builder = StringBuilder()
        var i = from
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    val next = raw[i + 1]
                    builder.append(
                        when (next) {
                            'n' -> '\n'
                            't' -> '\t'
                            '"' -> '"'
                            '\\' -> '\\'
                            else -> next
                        }
                    )
                    i += 2
                }
                c == '"' -> return builder.toString()
                else -> { builder.append(c); i++ }
            }
        }
        return null
    }

    private fun escape(text: String): String = buildString {
        text.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append(' ') else append(c)
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_TIMEOUT_MILLIS = 8_000

        /**
         * 系统提示只在说「这些文字来自一张手机截图，请判断是否值得清理」，
         * 并强制三种输出之一；不含任何引导模型扩权的措辞。
         */
        const val SYSTEM_PROMPT =
            "你是手机截图整理助手。输入是从一张截图里提取并脱敏后的文字行。" +
                "只回答一个词：KEEP（像订单、票据、凭证、课件、需要长期保存的资料）" +
                "或 LOWER（像已过期的优惠、旧新闻、临时码、失效分享）。" +
                "看不出来就回答 NONE。不要解释，不要输出其它内容。"

        val EMPTY = AssistSuggestion(
            recommendation = IntentRecommendation.UNCERTAIN,
            reason = ""
        )
    }
}

/** 传输层抽象：测试用假实现即可完整断言「实际发了什么」。 */
interface AssistTransport {
    fun post(url: String, body: String, authHeader: String, timeoutMillis: Int): String
}

/** 真实的 HTTPS 传输。任何非 2xx 都按失败处理（上层回退本地结果）。 */
class HttpAssistTransport : AssistTransport {
    override fun post(url: String, body: String, authHeader: String, timeoutMillis: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", authHeader)
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("http $code")
            return BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
        } finally {
            connection.disconnect()
        }
    }
}
