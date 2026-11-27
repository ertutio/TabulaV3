package com.tabula.v3.data.intent

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.tabula.v3.data.model.ImageFile
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface IntentBatchProgress {
    data object Preparing : IntentBatchProgress
    data class Checking(val checked: Int, val total: Int) : IntentBatchProgress
    data object Organizing : IntentBatchProgress
    data object Stopped : IntentBatchProgress
}

data class IntentBatchTimings(
    val initializationNanos: Long,
    val decodeNanos: Long,
    val nativeRecognitionNanos: Long,
    val cacheWriteNanos: Long,
    val decodedSizes: List<Pair<Int, Int>>
)

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
    private val textAssist: TextAssist? = null,
    /**
     * 批次代际号。上层每开一个新批次就 +1；分析结束时若代际已变，
     * 本次结果**整体作废**，不会写回界面或缓存。
     *
     * 这是「旧结果不得覆盖新批次」的最后一层：即使 OCR/网络都已返回，
     * 只要代际不符，结果就被丢弃。
     */
    private val batchGeneration: () -> Int = { 0 },
    /**
     * 文字辅助的**服务标识**，进缓存键（`assistTag`）。
     *
     * 为什么必须进键：同一个批次换成另一个服务后，「证据不足」的那些图会被重新判断；
     * 旧服务的判定不能拿来当新服务的结果——那样用户切了服务却看不到任何变化，
     * 而且缓存里会留下一条无法解释为何如此的结果。
     *
     * 默认 "assist"，于是 #95 的缓存键（`assist-on` / `assist-off`）**逐字不变**，
     * 老用户升级后不丢已有缓存；OpenCode Go 传 "assist-opencode-go"。
     */
    private val assistCachePrefix: String = "assist"
) {

    /** 一次分析的结果。 */
    data class Analysis(
        val batch: IntentBatch,
        val engineAvailable: Boolean,
        val engineNote: String?,
        val usedCacheCount: Int,
        val ocrAttemptCount: Int,
        val assistRequestCount: Int,
        val elapsedMillis: Long,
        /** 结果是否已过期（代际不符）。为 true 时调用方不得应用它。 */
        val stale: Boolean = false
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
        timeZone: TimeZone = TimeZone.getDefault(),
        startedGeneration: Int = batchGeneration(),
        onTimings: ((IntentBatchTimings) -> Unit)? = null,
        onProgress: (suspend (IntentBatchProgress) -> Unit)? = null
    ): Analysis = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        val assistEnabled = allowTextAssist && textAssist?.isEnabled == true
        val assistTag = if (assistEnabled) "$assistCachePrefix-on" else "$assistCachePrefix-off"

        val inputs = mutableListOf<IntentCandidateInput>()
        var cachedCount = 0
        var ocrCount = 0
        var assistCount = 0
        val timings = onTimings?.let { Timings() }

        fun staleAnalysis() = Analysis(
            batch = IntentBatch(emptyList(), emptyList(), emptyList(), emptyList(), batchLimit),
            engineAvailable = true,
            engineNote = null,
            usedCacheCount = cachedCount,
            ocrAttemptCount = ocrCount,
            assistRequestCount = assistCount,
            elapsedMillis = System.currentTimeMillis() - startedAt,
            stale = true
        )

        if (batchGeneration() != startedGeneration) return@withContext staleAnalysis()
        val initializationStart = timings?.let { System.nanoTime() }
        val engineAvailable = recognizer.isAvailable
        if (timings != null && initializationStart != null) {
            timings.initializationNanos = System.nanoTime() - initializationStart
        }
        if (!engineAvailable) {
            return@withContext Analysis(
                batch = IntentBatch(emptyList(), emptyList(), emptyList(), emptyList(), batchLimit),
                engineAvailable = false,
                engineNote = "本地 OCR 不可用，先不排序",
                usedCacheCount = 0,
                ocrAttemptCount = 0,
                assistRequestCount = 0,
                elapsedMillis = 0,
                stale = batchGeneration() != startedGeneration
            )
        }

        var lastProgressAt = SystemClock.elapsedRealtime()
        suspend fun publishChecked(checked: Int) {
            if (onProgress == null || batchGeneration() != startedGeneration) return
            val now = SystemClock.elapsedRealtime()
            if (checked <= 1 || checked == images.size || now - lastProgressAt >= 200L) {
                onProgress(IntentBatchProgress.Checking(checked, images.size))
                lastProgressAt = now
            }
        }

        publishChecked(0)
        for ((index, image) in images.withIndex()) {
            ensureActive()
            if (batchGeneration() != startedGeneration) return@withContext staleAnalysis()
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
                publishChecked(index + 1)
                continue
            }
            val ocr = runCatching { recognizeOne(image, timings) }.getOrElse { error ->
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
                        elapsedMillis = System.currentTimeMillis() - startedAt,
                        stale = batchGeneration() != startedGeneration
                    )
                }
            }
            inputs += IntentCandidateInput(
                image = image,
                savedAtMillis = image.dateAdded,
                ocrLines = lines,
                ocrConfidence = confidence
            )
            publishChecked(index + 1)
        }

        ensureActive()
        if (batchGeneration() != startedGeneration) return@withContext staleAnalysis()
        onProgress?.invoke(IntentBatchProgress.Organizing)
        val classified = IntentBatchBuilder.classifyAll(
            candidates = inputs,
            nowMillis = nowMillis,
            timeZone = timeZone,
            retainedIdentityKeys = retainedIdentityKeys
        )
        val countingAssist = if (assistEnabled) {
            CountingAssist(textAssist!!) { assistCount++ }
        } else {
            null
        }
        val refined = IntentBatchBuilder.refineWithAssist(classified, countingAssist)
        val batch = IntentBatchBuilder.assemble(refined, batchLimit)
        val elapsed = System.currentTimeMillis() - startedAt

        // 代际已变（用户换批/关开关/离开页面）：本次结果整体作废，
        // 既不写缓存也不交给界面。
        ensureActive()
        if (batchGeneration() != startedGeneration) return@withContext staleAnalysis()

        // 只回写「这次真的重新算过」的条目，命中缓存的不重写（避免无谓的偏好写入）
        if (ocrCount > 0) {
            val cacheWriteStart = timings?.let { System.nanoTime() }
            batch.allCandidates().forEach { candidate ->
                val image = candidate.image
                val identity = IntentBatchBuilder.cleanupIdentity(image)
                val fingerprint = IntentVerdictCache.mediaFingerprint(
                    image.volumeName, image.id, image.size, image.dateModified
                )
                // MainActivity advances the generation under this same lock.
                synchronized(cache) {
                    ensureActive()
                    if (batchGeneration() != startedGeneration) return@withContext staleAnalysis()
                    cache.write(identity, candidate.verdict, fingerprint, recognizer.engineId, assistTag)
                }
            }
            if (timings != null && cacheWriteStart != null) {
                timings.cacheWriteNanos = System.nanoTime() - cacheWriteStart
            }
        }

        if (timings != null) onTimings?.invoke(timings.finish())
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

    private suspend fun recognizeOne(image: ImageFile, timings: Timings?): OcrResult {
        val decodeStart = timings?.let { System.nanoTime() }
        val decoded: Bitmap? = try {
            TesseractTextRecognizer.decodeForOcr(context, image.uri)
        } catch (e: SecurityException) {
            null
        }
        if (timings != null && decodeStart != null) timings.decodeNanos += System.nanoTime() - decodeStart
        val bitmap = decoded ?: return OcrResult.Failed("decode")
        return try {
            currentCoroutineContext().ensureActive()
            timings?.decodedSizes?.add(bitmap.width to bitmap.height)
            if (timings != null && recognizer is TesseractTextRecognizer) {
                recognizer.recognize(bitmap) { timings.nativeRecognitionNanos += it }
            } else {
                recognizer.recognize(bitmap)
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private class Timings {
        var initializationNanos = 0L
        var decodeNanos = 0L
        var nativeRecognitionNanos = 0L
        var cacheWriteNanos = 0L
        val decodedSizes = mutableListOf<Pair<Int, Int>>()

        fun finish() = IntentBatchTimings(
            initializationNanos, decodeNanos, nativeRecognitionNanos, cacheWriteNanos, decodedSizes.toList()
        )
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
