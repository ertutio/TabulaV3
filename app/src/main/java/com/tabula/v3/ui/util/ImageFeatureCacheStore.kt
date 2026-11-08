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
 *
 * PR60 收尾修复（等待者共享不可靠结果 + 所有权后复查缓存）：
 * - 修复一：在途条目由 CompletableDeferred<Unit> 改为携带结果的
 *   CompletableDeferred<ImageFeatures>——发起者正常完成（无论结果可靠与否）
 *   都 complete(features)，等待者 await 后直接共享该轮结果（含不可信阴性），
 *   不再各自重跑检测；在途条目照常移除，后续新调用仍从缓存/重试语义开始。
 *   发起者失败 → completeExceptionally、取消 → cancel：等待者按既有失败重试
 *   语义重跑，等待者自身的取消照常向上传播（语义不变）。
 * - 修复二：赢得 putIfAbsent（取得发起者所有权）后、执行 detect 前再查一次
 *   可靠缓存——消除"初始 get 检查"与"领取所有权"之间他人完成检测入库导致
 *   的重复检测窗口；命中即直接返回（并照常 complete 自己的 deferred 供
 *   后来者共享）。
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
    private val inFlight =
        ConcurrentHashMap<ImageFeatureCacheKey, CompletableDeferred<ImageFeatures>>()

    /**
     * 仅供测试注入（PR60 确定性测试）：在初始 get 与所有权领取（putIfAbsent）
     * 之间拦截调用方，用于复现"他人完成检测入库"的竞态窗口。生产路径恒为 null。
     */
    @Volatile
    internal var preOwnershipGateForTest: (() -> Unit)? = null

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
     * 取缓存或运行 single-flight 检测。同键并发时至多一个调用方执行 [detect]：
     * - 等待者 await 后直接共享该轮结果（含不可信阴性，修复一），不再各自
     *   重跑；发起者失败/取消时条目不产结果，等待者回到循环头按缓存命中/
     *   自跑检测继续（失败重试语义保持；等待者自身的取消不受影响，向上传播）。
     * - 发起者取得所有权后、执行检测前复查一次可靠缓存（修复二），命中即
     *   直接返回并 complete 自己的 deferred 供后来者共享。
     * - 检测正常完成（无论结果可靠与否）都 complete(features)；失败
     *   （completeExceptionally）或取消（cancel）后条目移除，后续新调用仍从
     *   缓存/重试语义开始（不可信阴性不入可靠缓存，putIfReliable 语义不变）。
     */
    suspend fun getOrDetect(
        key: ImageFeatureCacheKey,
        detect: suspend () -> ImageFeatures
    ): ImageFeatures {
        while (true) {
            get(key)?.let { return it }
            preOwnershipGateForTest?.invoke()
            val mine = CompletableDeferred<ImageFeatures>()
            val existing = inFlight.putIfAbsent(key, mine)
            if (existing != null) {
                // 等待者：直接共享在途轮次的结果（成功/失败都不继承发起者的取消）。
                try {
                    return existing.await()
                } catch (error: CancellationException) {
                    // 发起者取消或等待者自身取消：ensureActive 区分——自身取消
                    // 向上传播；发起者取消则落到 continue 按重试语义重跑。
                    currentCoroutineContext().ensureActive()
                } catch (_: Throwable) {
                    // 发起者失败：循环重试（可能轮到自己跑检测）
                }
                continue
            }
            try {
                // 修复二：取得所有权后、执行检测前复查可靠缓存——消除初始 get
                // 与领取所有权之间他人完成检测入库的重复检测窗口。
                get(key)?.let { cached ->
                    mine.complete(cached)
                    return cached
                }
                val features = detect()
                putIfReliable(key, features)
                // 修复一：正常完成（无论结果可靠与否）都把结果交给等待者共享。
                mine.complete(features)
                return features
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    // 发起者取消：不产结果，等待者按既有失败重试语义重跑（T11a 语义保持）。
                    mine.cancel(error)
                } else {
                    mine.completeExceptionally(error)
                }
                throw error
            } finally {
                inFlight.remove(key, mine)
            }
        }
    }

    fun clear() {
        synchronized(lock) { cache.clear() }
        inFlight.clear()
    }
}
