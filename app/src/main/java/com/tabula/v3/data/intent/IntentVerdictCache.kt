package com.tabula.v3.data.intent

import android.content.SharedPreferences

/**
 * 分类结果缓存。
 *
 * 缓存的内容**只有**分类、档位、过期状态、脱敏理由和信号集合；不存 OCR 原文、
 * 不存验证码/提取码/链接，也不存任何图片或缩略图。这一点由 [IntentCachedVerdict] 的字段
 * 强制约束（没有 rawText 之类的槽位）。
 *
 * 失效条件（任一变化即重算）：
 * 1. 媒体内容变化：`size` 或 `dateModified` 变了（同一 ID 被重新写入/编辑）；
 * 2. 规则版本变化：[IntentRules.RULES_VERSION]；
 * 3. 引擎/模型版本变化：`engineId`（换 OCR 引擎或换语言包）；
 * 4. 文字辅助开关变化：开/关 DeepSeek 辅助会算出不同结果，用 `assistTag` 区分；
 * 5. 权限撤回：移除该条目，下次读取时重新判定。
 */
class IntentVerdictCache(
    private val prefs: SharedPreferences,
    private val maxEntries: Int = MAX_ENTRIES
) {

    internal data class Write(
        val identityKey: String,
        val verdict: IntentVerdict,
        val mediaFingerprint: String
    )

    /** 一条缓存记录。字段刻意最小化。 */
    data class IntentCachedVerdict(
        val category: IntentCategory,
        val recommendation: IntentRecommendation,
        val expiry: ExpiryState,
        val reason: String,
        val signals: Set<IntentSignal>,
        val ocrConfidence: Float,
        val deadlineMillis: Long?,
        val assistantUsed: Boolean,
        /** 媒体身份+内容的指纹：`volume|id|size|dateModified`。 */
        val mediaFingerprint: String,
        val rulesVersion: Int,
        val engineId: String,
        val assistTag: String
    )

    fun read(
        identityKey: String,
        mediaFingerprint: String,
        engineId: String,
        assistTag: String
    ): IntentVerdict? {
        val raw = prefs.getString(keyOf(identityKey), null) ?: return null
        val cached = decode(raw) ?: return null
        val fresh = cached.mediaFingerprint == mediaFingerprint &&
            cached.rulesVersion == IntentRules.RULES_VERSION &&
            cached.engineId == engineId &&
            cached.assistTag == assistTag
        return if (fresh) {
            IntentVerdict(
                category = cached.category,
                recommendation = cached.recommendation,
                expiry = cached.expiry,
                reason = cached.reason,
                signals = cached.signals,
                ocrConfidence = cached.ocrConfidence,
                deadlineMillis = cached.deadlineMillis,
                assistantUsed = cached.assistantUsed
            )
        } else {
            null
        }
    }

    fun write(identityKey: String, verdict: IntentVerdict, mediaFingerprint: String, engineId: String, assistTag: String) {
        writeBatch(listOf(Write(identityKey, verdict, mediaFingerprint)), engineId, assistTag)
    }

    internal fun writeBatch(
        writes: List<Write>,
        engineId: String,
        assistTag: String,
        beforeApply: () -> Unit = {}
    ) = synchronized(prefs) {
        if (writes.isEmpty()) return@synchronized
        val pending = LinkedHashMap<String, Write>()
        writes.forEach { write ->
            val key = keyOf(write.identityKey)
            pending.remove(key)
            pending[key] = write
        }
        val all = prefs.all
        val olderKeys = all.keys.filter { it.startsWith(KEY_PREFIX) && it !in pending }
        val surplus = olderKeys.size + pending.size - maxEntries
        val evicted = if (surplus > 0) {
            // 新写入的条目比快照里的条目更新。同一批的写入按候选顺序淘汰。
            (olderKeys.sortedWith(compareBy<String> { key ->
                (all[key] as? String)?.substringBefore(SEP)?.toLongOrNull() ?: Long.MAX_VALUE
            }.thenBy { it }) + pending.keys).take(surplus).toSet()
        } else {
            emptySet()
        }
        val editor = prefs.edit()
        evicted.forEach { if (it in all) editor.remove(it) }
        val writtenAt = System.currentTimeMillis()
        pending.forEach { (key, write) ->
            if (key in evicted) return@forEach
            val verdict = write.verdict
            val entry = IntentCachedVerdict(
                category = verdict.category,
                recommendation = verdict.recommendation,
                expiry = verdict.expiry,
                reason = verdict.reason,
                signals = verdict.signals,
                ocrConfidence = verdict.ocrConfidence,
                deadlineMillis = verdict.deadlineMillis,
                assistantUsed = verdict.assistantUsed,
                mediaFingerprint = write.mediaFingerprint,
                rulesVersion = IntentRules.RULES_VERSION,
                engineId = engineId,
                assistTag = assistTag
            )
            editor.putString(key, encode(entry, writtenAt))
        }
        beforeApply()
        editor.apply()
    }

    /** 移除陈旧条目（例如权限被撤回、目录已不可访问）。下次读取返回 null。 */
    fun invalidate(identityKey: String) = synchronized(prefs) {
        prefs.edit().remove(keyOf(identityKey)).apply()
    }

    /** 全量清空（换 OCR 引擎、用户主动清除分析结果时用）。 */
    fun clear() = synchronized(prefs) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** 当前缓存条目数（测试与设置页展示用）。 */
    fun size(): Int = prefs.all.keys.count { it.startsWith(KEY_PREFIX) }

    // ---------------- 编码 ----------------

    private fun keyOf(identityKey: String): String = "$KEY_PREFIX$identityKey"

    /**
     * 极简编码：用不会出现在这些枚举名/理由里的分隔符 `\u0001`，避免引入 JSON 依赖。
     * 理由文本在写入前已经过 [IntentTextSanitizer]，不会带控制字符。
     */
    private fun encode(e: IntentCachedVerdict, writtenAt: Long): String = listOf(
        writtenAt.toString(),
        e.category.name,
        e.recommendation.name,
        e.expiry.name,
        e.reason,
        e.signals.joinToString(",") { it.name },
        e.ocrConfidence.toString(),
        e.deadlineMillis?.toString() ?: "",
        if (e.assistantUsed) "1" else "0",
        e.mediaFingerprint,
        e.rulesVersion.toString(),
        e.engineId,
        e.assistTag
    ).joinToString(SEP)

    private fun decode(raw: String): IntentCachedVerdict? {
        val parts = raw.split(SEP)
        if (parts.size != 13) return null
        return try {
            IntentCachedVerdict(
                category = IntentCategory.valueOf(parts[1]),
                recommendation = IntentRecommendation.valueOf(parts[2]),
                expiry = ExpiryState.valueOf(parts[3]),
                reason = parts[4],
                signals = parts[5].split(",").filter { it.isNotBlank() }
                    .map { IntentSignal.valueOf(it) }.toSet(),
                ocrConfidence = parts[6].toFloatOrNull() ?: 0f,
                deadlineMillis = parts[7].toLongOrNull(),
                assistantUsed = parts[8] == "1",
                mediaFingerprint = parts[9],
                rulesVersion = parts[10].toIntOrNull() ?: -1,
                engineId = parts[11],
                assistTag = parts[12]
            )
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private const val KEY_PREFIX = "intent_verdict_"
        private const val SEP = "\u0001"
        /** 上限：只保留最近若干张的判定，避免把偏好文件撑大。 */
        const val MAX_ENTRIES = 400

        /** 媒体内容指纹：尺寸 + 修改时间，同一 ID 被覆盖也会失效。 */
        fun mediaFingerprint(volumeName: String?, id: Long, size: Long, dateModified: Long): String =
            "${volumeName.orEmpty()}|$id|$size|$dateModified"
    }
}
