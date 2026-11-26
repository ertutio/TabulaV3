package com.tabula.v3.data.intent

/**
 * 文字辅助的**共享提示词**。
 *
 * 抽成独立常量是为了让两个服务实现（[DeepSeekTextAssist] 与 [OpenCodeGoTextAssist]）
 * 使用**逐字相同**的系统提示：换服务不应该改变模型被要求做什么，只改变请求发到哪里。
 * 这也让「提示词里不含任何扩权措辞」这条审计结论只需维护一处。
 */
object IntentAssistPrompt {

    /**
     * 系统提示只在说「这些文字来自一张手机截图，请判断是否值得清理」，
     * 并强制三种输出之一；不含任何引导模型扩权的措辞。
     */
    const val SYSTEM_PROMPT =
        "你是手机截图整理助手。输入是从一张截图里提取并脱敏后的文字行。" +
            "只回答一个词：KEEP（像订单、票据、凭证、课件、需要长期保存的资料）" +
            "或 LOWER（像已过期的优惠、旧新闻、临时码、失效分享）。" +
            "看不出来就回答 NONE。不要解释，不要输出其它内容。"

    /**
     * 从「OpenAI 兼容」响应里取 `choices[0].message.content`。
     *
     * 两个服务共用同一段解析：响应格式相同，分歧只会在请求侧。
     *
     * ## 为什么必须用 JSON 解析器
     *
     * 上一版用固定字符串 `"content":"` 去 `indexOf`，这假设了「冒号后紧跟引号、
     * 且没有任何空白」。真实响应常写成 `"content": "KEEP"`（冒号后一个空格）
     * 或 pretty-print 的换行缩进，于是查找失败 → 返回 null → 一份**完全合法的
     * KEEP 建议**被当成无建议，静默回退本地。这类失败没有任何日志，
     * 用户只会觉得「开了辅助也没用」。修复在 #95，这里两个服务一起受益。
     *
     * 用解析器还顺手解决了手写状态机的几个边界：字段顺序、`\uXXXX` 转义、
     * 嵌套同名键、以及 content 里含转义引号时的越界风险。
     *
     * ## 回退语义（逐条保留，皆有回归用例）
     *
     * - 非法 JSON / 不是对象 → null；
     * - `choices` 缺失、不是数组、为空 → null；
     * - `choices[0].message` 缺失、`content` 缺失/为 null/不是字符串 → null；
     * - `content` 为空白串 → null（调用方按空建议处理）。
     *
     * 解析失败返回 null（调用方 `?:` 落到空建议，即 `UNCERTAIN` + 空理由）。
     */
    fun extractContent(raw: String): String? = try {
        val choices = org.json.JSONObject(raw).optJSONArray("choices") ?: return null
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

    /** JSON 字符串转义（只用于把提示词与脱敏文字塞进手工拼的请求体）。 */
    fun escape(text: String): String = buildString {
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
}
