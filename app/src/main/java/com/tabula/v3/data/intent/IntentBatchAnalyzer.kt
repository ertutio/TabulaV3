package com.tabula.v3.data.intent

import android.content.Context
import android.graphics.Bitmap
import com.tabula.v3.data.model.ImageFile
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「按整理目的凑一批图」的编排层：把暂存清理已经筛好的候选 → OCR → 规则 → 可选文字辅助 → 排序。
 *
 * 与其它模块的分工（尽量不重叠，减少并行开发冲突）：
 * - **不做全库扫描**：范围由调用方给定（暂存清理来源 + 用户勾选）。
 * - **不碰回收站/偏好/原图**：这里只读 URI 解码位图做 OCR，结果只用于排序与解释。
 * - **不做连拍/去重/随机漫步**：那些入口完全不受影响。
 *
 * 缓存：每张图判定结果按「媒体内容 + 规则版本 + 引擎版本 + 辅助开关」缓存，
 * 命中缓存就不再解码图片（省电、省时间）。缓存里只有分类与脱敏理由。
 *
 * 失败处理：
 * - 单张解码失败/OCR 失败 → 该张记成 [IntentCategory.UNCERTAIN]（不删除、不阻塞整批）；
 * - OCR 引擎不可用 → 整批直接返回空批次并带原因，界面据此如实说明；
 * - 权限撤回导致读不到 → 记为该张不确定，调用方下一次媒体快照刷新时自然把它移出候选。
 */
class IntentBatchAnalyzer(
    private val context: Context,
    private val recognizer: TextRecognizer,
    private val cache: IntentVerdictCache,
    private val textAssist: TextAssist? = null
) {

    /** 一次分析的结果。 */
    data class Analysis(
        val batch: IntentBatch,
        val engineAvailable: Boolean,
        val engineNote: String?,
        val usedCacheCount: Int,
        val ocrAttemptCount: Int,
        val assistRequestCount: Int,
        val elapsedMillis: Long
    )

    /**
     * 分析一批候选。
     *
     * @param images 暂存清理给出的候选（已限定范围并扣除保留/跳过/已回收）
     * @param batchLimit 这一批最多凑多少张高置信候选
     * @param retainedIdentityKeys 长期保留集合（双保险）
     * @param allowTextAssist 用户是否**在本批次**主动启用了文字辅助
     */
    suspend fun analyze(
        images: List<ImageFile>,
        batchLimit: Int,
        nowMillis: Long,
        retainedIdentityKeys: Set<String> = emptySet(),
        allowTextAssist: Boolean = false,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Analysis = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        val assistEnabled = allowTextAssist && textAssist?.isEnabled == true
        val assistTag = if (assistEnabled) "assist-on" else "assist-off"

        if (!recognizer.isAvailable) {
            return@withContext Analysis(
                batch = IntentBatch(emptyList(), emptyList(), emptyList(), emptyList(), batchLimit),
                engineAvailable = false,
                engineNote = "本地 OCR 不可用，先不排序",
                usedCacheCount = 0,
                ocrAttemptCount = 0,
                assistRequestCount = 0,
                elapsedMillis = 0
            )
        }

        val inputs = mutableListOf<IntentCandidateInput>()
        var cachedCount = 0
        var ocrCount = 0

        for (image in images) {
            val identity = IntentBatchBuilder.cleanupIdentity(image)
            val fingerprint = IntentVerdictCache.mediaFingerprint(
                image.volumeName, image.id, image.size, image.dateModified
            )
            val cached = cache.read(identity, fingerprint, recognizer.engineId, assistTag)
            if (cached != null) {
                cachedCount++
                inputs += IntentCandidateInput(
                    image = image,
                    savedAtMillis = image.dateAdded,
                    ocrLines = emptyList(),
                    ocrConfidence = cached.ocrConfidence,
                    precomputedVerdict = cached
                )
                continue
            }
            val ocr = runCatching { recognizeOne(image) }.getOrElse { error ->
                if (error is CancellationException) throw error
                OcrResult.Failed(error.javaClass.simpleName)
            }
            ocrCount++
            val (lines, confidence) = when (ocr) {
                is OcrResult.Success -> ocr.lines to ocr.meanConfidence
                OcrResult.Empty -> emptyList<String>() to 0f
                is OcrResult.Failed -> emptyList<String>() to 0f
                is OcrResult.Unavailable -> {
                    return@withContext Analysis(
                        batch = IntentBatch(emptyList(), emptyList(), emptyList(), emptyList(), batchLimit),
                        engineAvailable = false,
                        engineNote = ocr.reason,
                        usedCacheCount = cachedCount,
                        ocrAttemptCount = ocrCount,
                        assistRequestCount = 0,
                        elapsedMillis = System.currentTimeMillis() - startedAt
                    )
                }
            }
            inputs += IntentCandidateInput(
                image = image,
                savedAtMillis = image.dateAdded,
                ocrLines = lines,
                ocrConfidence = confidence
            )
        }

        val classified = IntentBatchBuilder.classifyAll(
            candidates = inputs,
            nowMillis = nowMillis,
            timeZone = timeZone,
            retainedIdentityKeys = retainedIdentityKeys
        )
        var assistCount = 0
        val countingAssist = if (assistEnabled) {
            CountingAssist(textAssist!!) { assistCount++ }
        } else {
            null
        }
        val refined = IntentBatchBuilder.refineWithAssist(classified, countingAssist)
        val batch = IntentBatchBuilder.assemble(refined, batchLimit)
        val elapsed = System.currentTimeMillis() - startedAt

        // 只回写「这次真的重新算过」的条目，命中缓存的不重写（避免无谓的偏好写入）
        if (ocrCount > 0) {
            batch.allCandidates().forEach { candidate ->
                val image = candidate.image
                val identity = IntentBatchBuilder.cleanupIdentity(image)
                val fingerprint = IntentVerdictCache.mediaFingerprint(
                    image.volumeName, image.id, image.size, image.dateModified
                )
                cache.write(identity, candidate.verdict, fingerprint, recognizer.engineId, assistTag)
            }
        }

        Analysis(
            batch = batch,
            engineAvailable = true,
            engineNote = null,
            usedCacheCount = cachedCount,
            ocrAttemptCount = ocrCount,
            assistRequestCount = assistCount,
            elapsedMillis = elapsed
        )
    }

    /** 撤回权限 / 文件已删除时让该张的缓存立刻失效。 */
    fun invalidate(images: Collection<ImageFile>) {
        images.forEach { cache.invalidate(IntentBatchBuilder.cleanupIdentity(it)) }
    }

    private suspend fun recognizeOne(image: ImageFile): OcrResult {
        val bitmap: Bitmap = try {
            TesseractTextRecognizer.decodeForOcr(context, image.uri)
        } catch (e: SecurityException) {
            null
        } ?: return OcrResult.Failed("decode")
        return try {
            recognizer.recognize(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** 记录文字辅助的实际调用次数（「到底发了几次请求」）。 */
    private class CountingAssist(
        private val delegate: TextAssist,
        private val onCall: () -> Unit
    ) : TextAssist {
        override val isEnabled: Boolean get() = delegate.isEnabled
        override suspend fun classify(
            snippet: IntentTextSanitizer.SanitizedSnippet
        ): AssistSuggestion {
            onCall()
            return delegate.classify(snippet)
        }
    }
}

/** 批次里的全部候选（含被保护的），用于缓存回写与界面统计。 */
fun IntentBatch.allCandidates(): List<IntentCandidate> =
    recommended + lowerPriority + uncertain + excluded
