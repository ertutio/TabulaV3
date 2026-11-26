package com.tabula.v3.data.intent

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 可选的文字辅助（第二个服务）：**OpenCode Go** 订阅套餐的 OpenAI 兼容端点。
 *
 * 与 [DeepSeekTextAssist] **平行实现**，行为契约逐条对齐（同一份脱敏、同一份授权、
 * 同一套本地回退、同一代际语义），差别只在请求怎么发：
 *
 * | 项目 | DeepSeek | OpenCode Go |
 * | --- | --- | --- |
 * | Base URL | `https://api.deepseek.com` | `https://opencode.ai/inference/go/openai/v1` |
 * | 模型 | `deepseek-chat` | `deepseek-v4.1-flash` / `deepseek-v4-pro` |
 * | 额外头 | 无 | **`x-opencode-session: ses_…`（缺了直接 400）** |
 * | User-Agent | 默认 | **必须自有标识**（官方明确不接受通用 SDK/HTTP 库名） |
 *
 * ## 官方契约里必须照做的三件事
 *
 * 1. **每个请求都要带 `x-opencode-session`**。缺这个头，网关在**校验 key 之前**就返回
 *    `400 {"type":"error","error":{"type":"MissingSessionID"}}`。所以它不是一个可选遥测字段，
 *    而是准入条件——[buildHeaders] 里它与 Authorization 同等对待。
 * 2. **User-Agent 必须是调用方自己的标识**（[USER_AGENT]），不能是 okhttp / HttpURLConnection
 *    这类库名：官方靠它区分「被验证过的客户端」与「没带 session 的批量脚本」。
 * 3. **模型 ID 只有两个**（[OFFICIAL_MODELS]）。`deepseek-chat` 在 Go 上不存在；
 *    传错值网关报错，因此这里在本地先把非法值收敛到默认模型（[normalizeModel]）。
 *
 * ## 与 DeepSeek 完全一致的失败语义
 *
 * 无 Key / 开关关闭 / 断网 / 超时 / 401 / 403 / 400（含 MissingSessionID）/
 * 响应不可解析 → 返回空建议（[EMPTY]），异常不外抛，上层沿用本地规则结果。
 * 传输层对任何非 2xx 抛 [IOException]，本类统一按「失败即回退」处理。
 *
 * ## 代际与作废
 *
 * 与 [DeepSeekTextAssist] 同一套机制：共享 [sharedGeneration] + 自己的 [invalidate]/[rearm]。
 * 请求发出前后各读一次代际号，不一致就把结果标成 [AssistSuggestion.stale]，
 * 上层（[IntentBatchBuilder.refineWithAssist]）直接丢弃——旧结果不会覆盖新批次。
 *
 * ## session id 为什么是「每实例一个」
 *
 * 官方要求 session id **稳定**，好让同一段对话复用它自己的 prompt cache。
 * 本功能天然按批工作：一个批次用一个 [OpenCodeGoTextAssist] 实例，因此
 * 「构造时生成一次、整个批次复用、换批随实例重建」正好同时满足「稳定」与
 * 「不要把两个不相干批次串成一段对话」。
 */
class OpenCodeGoTextAssist(
    private val keyStore: IntentAssistKeyStore,
    private val userEnabledForBatch: Boolean,
    private val transport: AssistTransport = HttpAssistTransport(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * 期望的模型 id。非法值**不抛异常**，而是在构造时收敛到 [DEFAULT_MODEL]
     * （见 [normalizeModel]）：这一层是「可选辅助」，构造失败会让整批分析崩掉，
     * 代价远大于「悄悄用默认模型」。收敛原因记在 [modelFallbackFrom] 里，便于诊断。
     */
    model: String = DEFAULT_MODEL,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    /** 与上层共享的代际号；上层换批时 +1 即可让在途结果作废。 */
    private val sharedGeneration: AtomicInteger? = null,
    /** 会话 id。默认每实例生成一个 `ses_…`；测试可注入固定值。 */
    sessionId: String = newSessionId()
) : InvalidatableTextAssist {

    /** 构造时传入的原始模型名（保留下来，供 [modelFallbackFrom] 对账；避免与属性同名歧义）。 */
    private val rawModel: String = model

    /** 实际会发出去的模型 id（已校验/收敛）。 */
    val model: String = normalizeModel(model)

    /** 构造时被收敛掉的原始模型名（合法输入时为 null）。 */
    val modelFallbackFrom: String? =
        rawModel.trim().takeIf { it.isNotEmpty() && it != this.model }

    /** 本实例的会话 id，`ses_` 前缀是官方要求的形式。 */
    val sessionId: String = normalizeSessionId(sessionId)

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

    /**
     * 最近一次实际请求头（**不含** Authorization：Key 只走传输层的独立参数，
     * 因此这个 map 可以安全地拿去做断言与诊断，不会把凭据带进任何日志或报告）。
     */
    @Volatile
    var lastRequestHeaders: Map<String, String> = emptyMap()
        private set

    /** 最近一次失败原因（简短、不含 Key 与原文）。 */
    @Volatile
    var lastFailure: String? = null
        private set

    /** 已归零（未发出）的请求数：关闭/无 Key/片段为空时累加，供试点报告核对「零请求」。 */
    val skippedRequestCount: Int get() = skipped.get()

    private val skipped = AtomicInteger(0)

    override fun invalidate() {
        cancelled.set(true)
        generation.incrementAndGet()
    }

    override fun rearm() {
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
        val headers = buildHeaders()
        lastRequestPayload = payload
        lastRequestHeaders = headers

        val response = try {
            withContext(Dispatchers.IO) {
                transport.post(
                    url = "$baseUrl/chat/completions",
                    body = payload,
                    authHeader = "Bearer $key",
                    timeoutMillis = timeoutMillis,
                    headers = headers
                )
            }
        } catch (e: CancellationException) {
            lastFailure = "cancelled"
            throw e
        } catch (e: IOException) {
            // 断网、超时、401/403/400（含 MissingSessionID）都走这里：失败即回退
            lastFailure = "network"
            return EMPTY
        } catch (e: Throwable) {
            lastFailure = "unexpected"
            return EMPTY
        }

        // 请求飞出去这段时间里，用户可能关掉了开关、离开了页面、换了批或换了服务。
        if (generation.get() != startedAt || cancelled.get() || !isEnabled) {
            lastFailure = "stale"
            return EMPTY.copy(stale = true)
        }
        return parse(response)
    }

    /**
     * 自定义请求头。
     *
     * - `x-opencode-session`：**准入条件**，缺失时官方网关在验 key 之前就 400；
     * - `User-Agent`：自有标识，官方明确要求不要用通用 SDK/HTTP 库名；
     * - `Accept`：只要拿到 JSON，避免网关按浏览器/流式协商。
     *
     * 返回的 map 里**不含 Key**：Authorization 仍走传输层单独的参数，这样
     * [lastRequestHeaders] 可以安全地拿去做断言与诊断。
     */
    private fun buildHeaders(): Map<String, String> = mapOf(
        HEADER_SESSION to sessionId,
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json"
    )

    /**
     * 请求体。**唯一**写入用户文字的地方，输入已过
     * [IntentTextSanitizer.sanitizeForAssistant]（掩码过、丢过不确定行、截断过长度）。
     *
     * 字段固定为 model / temperature / max_tokens / messages —— 与 DeepSeek 实现一致，
     * 不新增任何可能携带图像或路径的槽位。
     */
    private fun buildPayload(snippet: IntentTextSanitizer.SanitizedSnippet): String {
        val joined = snippet.lines.joinToString("\n")
        val system = IntentAssistPrompt.escape(IntentAssistPrompt.SYSTEM_PROMPT)
        val user = IntentAssistPrompt.escape(joined)
        return """{"model":"$model","temperature":0,"max_tokens":80,"messages":[""" +
            """{"role":"system","content":"$system"},""" +
            """{"role":"user","content":"$user"}]}"""
    }

    /** 只认 KEEP / LOWER，其它一律无建议（含模型返回清理动作的情况）。 */
    private fun parse(raw: String): AssistSuggestion = try {
        val content = IntentAssistPrompt.extractContent(raw)?.trim().orEmpty()
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

    companion object {
        /** Go 套餐（与 pay-as-you-go 的 `/inference` 不同，少了 `/go` 会打到别的池子）。 */
        const val DEFAULT_BASE_URL = "https://opencode.ai/inference/go/openai/v1"

        /** 默认模型：成本档，够用即可（本功能每次只请求一个词）。 */
        const val DEFAULT_MODEL = "deepseek-v4.1-flash"

        /** 官方确切的模型 id。**只有这两个**，`deepseek-chat` 在 Go 上不存在。 */
        val OFFICIAL_MODELS: List<String> = listOf("deepseek-v4.1-flash", "deepseek-v4-pro")

        /**
         * 自有 User-Agent。
         *
         * 官方要求第三方客户端「identify themselves with their own user agent」，
         * 不要冒充通用 SDK 或裸 HTTP 库；这里带上应用名与功能名，便于对方按客户端排障。
         */
        const val USER_AGENT = "TabulaV3-IntentAssist/1.0"

        /** 会话头名字（服务端先校验它，再校验 key）。 */
        const val HEADER_SESSION = "x-opencode-session"

        /** 会话 id 前缀（官方形式）。 */
        const val SESSION_PREFIX = "ses_"

        const val DEFAULT_TIMEOUT_MILLIS = 8_000

        /**
         * 用量查询端点（`GET`，同一个 key，返回 rolling/weekly/monthly 三段额度）。
         *
         * **本功能刻意不调用它**：分类只需要一次 completions；为了展示额度再发一次
         * 带 key 的请求，会多一个外发面，而当前界面上没有额度展示位。
         * 常量留在这里是为了让后续要做「剩余额度」时不必再查一次文档。
         */
        const val USAGE_URL = "https://opencode.ai/inference/go/v1/usage"

        /**
         * 校验模型 id：只接受 [OFFICIAL_MODELS] 里的确切字符串（大小写无关、去空白），
         * 其它值（含 `deepseek-chat`、空串、null）一律回落到 [DEFAULT_MODEL]。
         *
         * 为什么回退而不是抛：这是默认关闭的可选辅助，一个配置错误不该让整批分析失败；
         * 收敛结果通过 [modelFallbackFrom] 暴露，测试与诊断都能看到。
         */
        fun normalizeModel(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return DEFAULT_MODEL
            return OFFICIAL_MODELS.firstOrNull { it.equals(trimmed, ignoreCase = true) }
                ?: DEFAULT_MODEL
        }

        /** 生成一个稳定的会话 id：`ses_` + 随机串（每实例一次）。 */
        fun newSessionId(): String =
            SESSION_PREFIX + java.util.UUID.randomUUID().toString().replace("-", "").take(24)

        /** 注入的 session id 缺前缀/为空时补齐，保证请求头永远是官方形式。 */
        fun normalizeSessionId(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return newSessionId()
            return if (trimmed.startsWith(SESSION_PREFIX)) trimmed else SESSION_PREFIX + trimmed
        }

        val EMPTY = AssistSuggestion(
            recommendation = IntentRecommendation.UNCERTAIN,
            reason = ""
        )
    }
}
