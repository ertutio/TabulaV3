package com.tabula.v3.ui.util

import com.tabula.v3.data.model.ImageFeatures
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * T11a/T11b 图片特征缓存：键 = (imageId, 内容版本毫秒, 开关组合)。
 * 内容版本按 T03 契约取 MediaStore DATE_MODIFIED（毫秒）。
 *
 * putIfReliable：阳性（HDR/动态照片）与可信阴性（definitive=true）才入缓存；
 * 忙碌/快速模式/失败产生的不可信阴性（definitive=false 且阴性）一律拒绝，
 * 避免把瞬态状态永久缓存为"非 HDR/非动态"。
 *
 * T11b（TC48–TC51，基线证据 = PR56 测量：8 并发同键=8 次独立检测、2000 键
 * 无淘汰保留）：
 * - single-flight：同键并发只运行一次检测（[getOrDetect]），等待者共享该次
 *   结果；发起者失败/取消后条目移除，等待者与后续调用按失败重试语义各自
 *   重跑（不可信阴性不缓存 = 天然可重试）。
 * - 有界缓存：可靠条目按 LRU（访问序）淘汰，上限 [MAX_ENTRIES]；大图库长会话
 *   不再无界增长。
 */
internal data class ImageFeatureCacheKey(
    val imageId: Long,
    val contentVersion: Long,
    val enableHdr: Boolean,
    val enableMotion: Boolean
)

internal object ImageFeatureCacheStore {
    /** 有界缓存上限：命中以最近访问为准，超过即淘汰最久未用条目。 */
    internal const val MAX_ENTRIES = 2048

    private val lock = Any()
    private val cache = LinkedHashMap<ImageFeatureCacheKey, ImageFeatures>(64, 0.75f, true)
    private val inFlight = ConcurrentHashMap<ImageFeatureCacheKey, CompletableDeferred<Unit>>()

    fun get(key: ImageFeatureCacheKey): ImageFeatures? = synchronized(lock) { cache[key] }

    fun putIfReliable(key: ImageFeatureCacheKey, features: ImageFeatures) {
        val reliable = features.definitive || features.isHdr || features.isMotionPhoto
        if (!reliable) return
        synchronized(lock) {
            cache[key] = features
            val eldest = cache.entries.iterator()
            while (cache.size > MAX_ENTRIES && eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
    }

    /**
     * 取缓存或运行 single-flight 检测。同键并发时至多一个调用方执行 [detect]，
     * 其余等待并共享该次结果；该次失败/取消后键从在途表移除，等待者按
     * 失败重试语义自行重跑（等待者自身的取消不受影响，向上传播）。
     */
    suspend fun getOrDetect(
        key: ImageFeatureCacheKey,
        detect: suspend () -> ImageFeatures
    ): ImageFeatures {
        while (true) {
            get(key)?.let { return it }
            val mine = CompletableDeferred<Unit>()
            val existing = inFlight.putIfAbsent(key, mine)
            if (existing != null) {
                // 等待者：等在途检测落定（成功/失败都不继承发起者的取消），
                // 落定后回到循环头按缓存命中/自跑检测三态继续。
                try {
                    existing.await()
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (_: Throwable) {
                    // 发起者失败：循环重试（可能轮到自己跑检测）
                }
                continue
            }
            try {
                val features = detect()
                putIfReliable(key, features)
                return features
            } finally {
                inFlight.remove(key, mine)
                mine.complete(Unit)
            }
        }
    }

    fun clear() {
        synchronized(lock) { cache.clear() }
        inFlight.clear()
    }
}
