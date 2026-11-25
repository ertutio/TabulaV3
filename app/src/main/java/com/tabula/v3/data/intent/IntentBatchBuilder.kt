package com.tabula.v3.data.intent

import com.tabula.v3.data.model.ImageFile
import java.util.TimeZone

/**
 * 组批与排序：「按整理目的凑一批图」真正落地的那一层。
 *
 * 输入是调用方（暂存清理）已经筛好的候选——来源已勾选、已扣除长期保留/本次跳过/已在回收站——
 * 本类只做三件事：
 * 1. 逐张判定（[IntentRules.classify]）；
 * 2. 可选叠加文字辅助（只作用于本地「证据不足」的那些，见 [refineWithAssist]）；
 * 3. **保护先于排序**：protected 单独拎出来，永远不进 recommend / lower；
 *    排序：证据更明确（有可核对截止时间且已过）优先 > 存入时间倒序 > ID 兜底；
 *    高置信不够时不硬凑，[IntentBatch.shortOfLimit] 让界面如实说明。
 *
 * 刻意把「判定」拆成两个纯函数（[classifyAll] + [assemble]）：文字辅助是挂起函数，
 * 由编排层（[IntentBatchAnalyzer]）插在两步之间；这样规则与排序都能被 JVM 单测逐条钉住，
 * 不需要协程、Bitmap 或网络。
 *
 * 不修改任何 [ImageFile]，不调用回收站，不写偏好。
 */
object IntentBatchBuilder {

    /** 一次判定：输入 + 结果。 */
    data class Classified(
        val input: IntentCandidateInput,
        val verdict: IntentVerdict
    )

    /**
     * 第一步：纯规则判定（不含文字辅助）。
     *
     * 保护的双重保险在这里：即使缓存/调用方给来的是低优先级判定，
     * 只要身份键在 [retainedIdentityKeys] 里，这一轮也重新按保护算。
     */
    fun classifyAll(
        candidates: List<IntentCandidateInput>,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        retainedIdentityKeys: Set<String> = emptySet()
    ): List<Classified> = candidates.map { input ->
        val identity = cleanupIdentity(input.image)
        val retained = identity in retainedIdentityKeys
        val verdict = when {
            retained -> IntentRules.classify(
                lines = input.ocrLines,
                savedAtMillis = input.savedAtMillis,
                nowMillis = nowMillis,
                ocrConfidence = input.ocrConfidence,
                timeZone = timeZone,
                retainedOverride = true
            )
            input.precomputedVerdict != null -> input.precomputedVerdict
            else -> IntentRules.classify(
                lines = input.ocrLines,
                savedAtMillis = input.savedAtMillis,
                nowMillis = nowMillis,
                ocrConfidence = input.ocrConfidence,
                timeZone = timeZone,
                retainedOverride = false
            )
        }
        Classified(input, verdict)
    }

    /**
     * 第二步（可选）：用文字辅助细化本地「证据不足」的判定。
     *
     * 硬约束（不可被模型覆盖）：
     * - 已经是 KEEP（保护）的，原样返回——模型不能把保护降级；
     * - 已经是 RECOMMEND / LOWER 的，原样返回——模型只在本地拿不准时才有话语权；
     * - 模型不能产出 EXPIRED（[AssistSuggestion] 里根本没有这个字段），
     *   因此「凭空认定失效」在类型层面就不可能；
     * - 只有本地「不确定」且脱敏后仍够长、没有不可判定行的，才真的发请求。
     *
     * @param assist 文字辅助；null 表示用户没启用（默认关闭）
     * @return 细化后的判定列表；顺序与输入一致
     */
    suspend fun refineWithAssist(
        classified: List<Classified>,
        assist: TextAssist?
    ): List<Classified> {
        if (assist == null || !assist.isEnabled) return classified
        return classified.map { item ->
            if (item.verdict.recommendation != IntentRecommendation.UNCERTAIN) return@map item
            val snippet = IntentTextSanitizer.sanitizeForAssistant(item.input.ocrLines)
            // 命中敏感字段的截图整张不进辅助（值可能跨行延续，见 SanitizedSnippet.containsSecretField）。
            // 其余截图仍按批辅助；不合格的这张走本地 OCR + 规则结果。
            if (!snippet.sendable) return@map item
            when (val suggestion = assist.classify(snippet)) {
                is AssistSuggestion -> if (suggestion.stale) item else when (suggestion.recommendation) {
                    IntentRecommendation.KEEP -> item.copy(
                        verdict = item.verdict.copy(
                            category = IntentCategory.PROTECTED,
                            recommendation = IntentRecommendation.KEEP,
                            reason = suggestion.reason,
                            assistantUsed = true
                        )
                    )
                    IntentRecommendation.LOWER_PRIORITY -> item.copy(
                        verdict = item.verdict.copy(
                            recommendation = IntentRecommendation.LOWER_PRIORITY,
                            reason = suggestion.reason,
                            assistantUsed = true
                        )
                    )
                    else -> item
                }
            }
        }
    }

    /** 第三步：分档 + 排序 + 截断。纯函数。 */
    fun assemble(
        classified: List<Classified>,
        batchLimit: Int
    ): IntentBatch {
        val recommended = mutableListOf<IntentCandidate>()
        val lower = mutableListOf<IntentCandidate>()
        val uncertain = mutableListOf<IntentCandidate>()
        val protected = mutableListOf<IntentCandidate>()

        classified.forEach { item ->
            val candidate = IntentCandidate(
                image = item.input.image,
                verdict = item.verdict,
                savedAtMillis = item.input.savedAtMillis
            )
            when (item.verdict.recommendation) {
                IntentRecommendation.RECOMMEND -> recommended += candidate
                IntentRecommendation.LOWER_PRIORITY -> lower += candidate
                IntentRecommendation.UNCERTAIN -> uncertain += candidate
                IntentRecommendation.KEEP -> protected += candidate
            }
        }

        val recommendedSorted = recommended.sortedWith(PRIMARY_ORDER)
        val lowerSorted = lower.sortedWith(PRIMARY_ORDER)
        val room = (batchLimit - recommendedSorted.size).coerceAtLeast(0)
        return IntentBatch(
            recommended = recommendedSorted.take(batchLimit),
            lowerPriority = lowerSorted.take(room),
            uncertain = uncertain.sortedWith(PRIMARY_ORDER),
            excluded = protected.sortedWith(PRIMARY_ORDER),
            batchLimit = batchLimit
        )
    }

    /** 便捷入口：一步到底（无文字辅助）。测试与纯本地路径用这个。 */
    fun build(
        candidates: List<IntentCandidateInput>,
        nowMillis: Long,
        batchLimit: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
        retainedIdentityKeys: Set<String> = emptySet()
    ): IntentBatch = assemble(
        classifyAll(candidates, nowMillis, timeZone, retainedIdentityKeys),
        batchLimit
    )

    /**
     * 排序键（从高到低）：
     * 1. 过期判定更明确（[ExpiryState.EXPIRED] 且带可核对截止时间）优先；
     * 2. 同档按存入时间倒序（越新越靠前，避免「越旧越优先」的直觉错误）；
     * 3. 最后按 ID 保证稳定。
     */
    private val PRIMARY_ORDER: Comparator<IntentCandidate> =
        compareByDescending<IntentCandidate> { evidenceStrength(it.verdict) }
            .thenByDescending { it.savedAtMillis }
            .thenByDescending { it.image.id }

    /** 证据强度 0~2：仅用于同档内排序，不改变档位。 */
    private fun evidenceStrength(verdict: IntentVerdict): Int = when {
        verdict.expiry == ExpiryState.EXPIRED && verdict.deadlineMillis != null -> 2
        verdict.expiry == ExpiryState.EXPIRED -> 1
        else -> 0
    }

    fun cleanupIdentity(image: ImageFile): String = "${image.volumeName ?: ""}|${image.id}"
}

/**
 * 组批输入：一张候选图 + 它的 OCR 文本。
 *
 * OCR 文本只在这一层流转，不落盘、不进缓存（缓存只存分类与脱敏理由）。
 */
data class IntentCandidateInput(
    val image: ImageFile,
    val savedAtMillis: Long,
    val ocrLines: List<String>,
    val ocrConfidence: Float,
    /**
     * 缓存命中时直接带过来的判定。
     *
     * 为什么留这个槽位：命中缓存时没有 OCR 文本（缓存里刻意不存原文），
     * 重新跑规则会得到「没有文字」的假结论。带上来的是同版本规则、同引擎、
     * 同辅助开关下算出的结果，语义与现算一致。
     */
    val precomputedVerdict: IntentVerdict? = null
)

/**
 * **文字辅助**的最小接口——默认不实现，不注册就是关闭。
 *
 * 与图片辅助的分界见 [IntentAssistCapability]：本接口只处理**已脱敏的文字**，
 * 且类型上不具备图片字节 / `Uri` / 路径的槽位，所以「把图塞进文字客户端」
 * 在这里写不出来。图片能力本轮不可用，将来也必须走**逐图、独立**授权。
 */
interface TextAssist {
    val isEnabled: Boolean

    /**
     * 对**已脱敏**的文字片段做分类建议。
     *
     * 实现方不得接触原图、URI、路径；返回的 [AssistSuggestion] 只能表达
     * KEEP（像该保留的）/ LOWER（低优先级）/ 其它（无建议）。
     */
    suspend fun classify(snippet: IntentTextSanitizer.SanitizedSnippet): AssistSuggestion
}

/**
 * 文字辅助的建议（刻意只有三种，防止模型扩权）。
 *
 * @param stale 这条建议是否「回来得太晚」——用户已经关掉开关、离开页面或换了批。
 *              [IntentBatchBuilder.refineWithAssist] 会直接丢弃 stale 建议，
 *              所以旧结果不会覆盖新批次。
 */
data class AssistSuggestion(
    val recommendation: IntentRecommendation,
    val reason: String,
    val category: IntentCategory = IntentCategory.UNCERTAIN,
    val usedTokens: Int = 0,
    val stale: Boolean = false
)
