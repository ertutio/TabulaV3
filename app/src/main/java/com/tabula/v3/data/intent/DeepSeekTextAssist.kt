package com.tabula.v3.data.intent

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 可选的文字辅助：把**已脱敏的 OCR 文字片段**交给 DeepSeek 做一次分类建议。
 *
 * 这是首版里**保留但默认关闭的实验性功能**：本地 OCR + 规则负责默认排序，
 * 它只在用户对**当前批次**主动开启后才参与，且只用于「本地证据不足」的候选。
 *
 * ## 硬约束（每条都有对应测试）
 *
 * 1. **默认关闭**：没有 Key、用户没在本批次打开、实现没注册 → [isEnabled] 为 false，**零请求**。
 * 2. **只发文字**：请求体里只有脱敏后的行，没有原图、缩略图、base64、URI、路径、文件名。
 * 3. **不扩权**：模型只能给「保留」或「低优先级」建议；保护判定在 [IntentBatchBuilder]
 *    里先于模型执行，模型改不动；[AssistSuggestion] 里没有 expiry 字段，
 *    「凭空认定失效」在类型层面不可表达。
 * 4. **失败即回退**：无网、超时、Key 被拒、返回不可解析 → 返回空建议，上层沿用本地结果。
 * 5. **关闭即停**：用户关掉开关（或离开页面、换批）后，[invalidate] 让在途请求的结果
 *    作废；已发出的请求不再重试，返回值也不会被上层采纳。
 *
 * ## 「旧结果不得覆盖新批次」是怎么做到的
 *
 * 光取消协程不够：网络调用可能在取消前已经返回，而上层也已经拿到结果。
 * 因此这里维护一个**代际号** [generation]：
 * - 上层每开一个新批次、或每次切换开关，都调 [invalidate]；
 * - [classify] 在发请求前后各读一次代际号，不一致就把结果判为 [AssistSuggestion.stale]；
 * - 上层（[IntentBatchAnalyzer]）会丢弃 stale 结果。
 *
 * 这是「双保险」：协程取消负责尽快停掉在途 IO，代际号负责兜住「已经回来但已过期」的结果。
 *
 * 说明：传输层用最朴素的 [HttpURLConnection]，不引入 OkHttp，避免为一个默认关闭的
 * 可选能力增加依赖与 APK 体积。
 */
class DeepSeekTextAssist(
    private val keyStore: IntentAssistKeyStore,
    private val userEnabledForBatch: Boolean,
    private val transport: AssistTransport = HttpAssistTransport(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    /** 与上层共享的代际号；上层换批时 +1 即可让在途结果作废。 */
    private val sharedGeneration: AtomicInteger? = null
) : TextAssist {

    private val ownGeneration = AtomicInteger(0)
    private val generation: AtomicInteger get() = sharedGeneration ?: ownGeneration

    /** 关闭态：一旦置位，后续 classify 一律直接返回空建议，不再发请求。 */
    private val cancelled = AtomicBoolean(false)

    override val isEnabled: Boolean
        get() = userEnabledForBatch && !cancelled.get() && keyStore.hasKey()

    /** 最近一次实际请求体（只用于测试与「不含图像/敏感原文」的断言；不落盘）。 */
    @Volatile
    var lastRequestPayload: String? = null
        private set

    /** 最近一次失败原因（简短、不含 Key 与原文）。 */
    @Volatile
    var lastFailure: String? = null
        private set

    /** 已归零（未发出）的请求数：关闭/无 Key/片段为空时累加，供试点报告核对「零请求」。 */
    val skippedRequestCount: Int get() = skipped.get()

    private val skipped = AtomicInteger(0)

    /**
     * 作废所有在途与后续请求。
     *
     * 调用点：用户关掉开关、离开页面、换批、删除 Key。
     * 之后即使有请求已经在网络上，它的返回值也会被标成 stale，上层不会采用。
     */
    fun invalidate() {
        cancelled.set(true)
        generation.incrementAndGet()
    }

    /** 重新允许（用户在新批次里又打开了开关时调用）。 */
    fun rearm() {
        cancelled.set(false)
        generation.incrementAndGet()
    }

    override suspend fun classify(
        snippet: IntentTextSanitizer.SanitizedSnippet
    ): AssistSuggestion {
        if (!isEnabled) {
            skipped.incrementAndGet()
            return EMPTY
        }
        if (snippet.isEmpty) {
            skipped.incrementAndGet()
            return EMPTY
        }
        val key = keyStore.read() ?: run {
            skipped.incrementAndGet()
            return EMPTY
        }

        val startedAt = generation.get()
        val payload = buildPayload(snippet)
        lastRequestPayload = payload

        val response = try {
            withContext(Dispatchers.IO) {
                transport.post(
                    url = "$baseUrl/chat/completions",
                    body = payload,
                    authHeader = "Bearer $key",
                    timeoutMillis = timeoutMillis
                )
            }
        } catch (e: CancellationException) {
            lastFailure = "cancelled"
            throw e
        } catch (e: IOException) {
            lastFailure = "network"
            return EMPTY
        } catch (e: Throwable) {
            lastFailure = "unexpected"
            return EMPTY
        }

        // 请求飞出去这段时间里，用户可能关掉了开关、离开了页面或换了批。
        if (generation.get() != startedAt || cancelled.get() || !isEnabled) {
            lastFailure = "stale"
            return EMPTY.copy(stale = true)
        }
        return parse(response)
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

    /**
     * 从 OpenAI 兼容响应里取 `choices[0].message.content`。
     *
     * **为什么必须用 JSON 解析器**：上一版用固定字符串 `"content":"` 去 `indexOf`，
     * 这假设了「冒号后紧跟引号、且没有任何空白」。真实响应常写成
     * `"content": "KEEP"`（冒号后一个空格）或 pretty-print 的换行缩进，
     * 于是查找失败 → 返回 null → 一份**完全合法的 KEEP 建议**被当成无建议，
     * 静默回退本地。这类失败没有任何日志，用户只会觉得「开了辅助也没用」。
     *
     * 用解析器还顺手解决了手写状态机的几个边界：字段顺序、`\uXXXX` 转义、
     * 嵌套同名键、以及 content 里含转义引号时的越界风险。
     *
     * 回退语义**逐条保留**（皆有回归用例）：
     * - 非法 JSON / 不是对象 → null；
     * - `choices` 缺失、不是数组、为空 → null；
     * - `choices[0].message` 缺失、`content` 缺失/为 null/不是字符串 → null；
     * - `content` 为空白串 → null（调用方按空建议处理）。
     *
     * 返回 null 的调用方行为不变：`parse` 里 `?:` 落到 [EMPTY]，即 `UNCERTAIN` + 空理由。
     */
    private fun extractContent(raw: String): String? = try {
        val choices = JSONObject(raw).optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
        // optString 会把 null / 非字符串转成 ""，因此显式判类型，
        // 避免把 {"content": 42} 这种异常响应当成空建议之外的别的东西。
        if (!message.has("content") || message.isNull("content")) return null
        val content = message.opt("content")
        if (content !is String) return null
        content.trim().ifEmpty { null }
    } catch (e: Throwable) {
        // 非法 JSON（含 HTML 错误页）一律按「读不到」处理，不抛给上层
        null
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
