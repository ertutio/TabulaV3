package com.tabula.v3.di

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.IntSize
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Size
import com.tabula.v3.BuildConfig
import com.tabula.v3.data.model.ImageFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 图片预加载管理器
 *
 * 针对卡片堆叠滑动场景优化：
 * - 预加载当前索引的前后各 N 张图片
 * - 使用较小的卡片尺寸（720px）加快解码速度
 * - 支持暂停/恢复预加载（滑动时暂停）
 * - 使用防抖避免频繁触发预加载
 *
 * @param context 应用上下文
 * @param preloadRange 预加载范围（前后各多少张），默认 1（更保守）
 */
class PreloadingManager(
    private val context: Context,
    private val preloadRange: Int = 1,  // 从 2 降到 1，减少内存压力
    providedLoader: coil.ImageLoader? = null,
    // C4/T07 任务 E：测试注入确定性调度 scope（防抖时序不再依赖真实 IO
    // 调度与 Thread.sleep）；生产路径不传，默认 scope 行为不变
    providedScope: CoroutineScope? = null
) {
    private val scope: CoroutineScope = providedScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageLoader: coil.ImageLoader = providedLoader ?: CoilSetup.getImageLoader(context)

    private val lock = Any()
    private data class Active(val token: Long, val identity: String, val cacheResult: Boolean, var disposable: Disposable? = null)
    private val active = mutableMapOf<Int, Active>()
    private val completed = mutableSetOf<String>()
    private var nextToken = 0L
    private var generation = 0L
    private var disposed = false
    private val isPaused = AtomicBoolean(false)
    private var debounceJob: Job? = null
    private val debounceDelay = 100L

    fun pause() {
        if (BuildConfig.DEBUG) Log.d(TAG, "pause: preloading paused")
        isPaused.set(true)
        synchronized(lock) { generation++; debounceJob?.cancel() }
    }

    // T06（2026-11-12）：DEBUG 门日志（与 CardStack 的 debugCardStackLog 同模式）——
    // 使设备测试可直接断言"手势取消后恢复预载"（#61 接线）的恢复路径。
    fun resume() {
        if (BuildConfig.DEBUG) Log.d(TAG, "resume: preloading resumed")
        isPaused.set(false)
    }

    fun preloadAround(images: List<ImageFile>, currentIndex: Int, cardSize: IntSize) {
        if (isPaused.get()) return
        val snapshot = images.toList()
        synchronized(lock) {
            if (disposed || isPaused.get()) return
            val runGeneration = ++generation
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(debounceDelay)
                synchronized(lock) {
                    if (disposed || isPaused.get() || runGeneration != generation) return@synchronized
                    val start = (currentIndex - preloadRange).coerceAtLeast(0)
                    val end = (currentIndex + preloadRange).coerceAtMost(snapshot.lastIndex)
                    val window = if (start <= end) (start..end).mapNotNull { index ->
                        snapshot.getOrNull(index)?.let { index to requestIdentity(it) }
                    }.toMap() else emptyMap()
                    val windowKeys = window.values.toSet()
                    // T06 剩余场景（2026-11-14）：DEBUG 门日志——窗口身份快照（含索引映射），
                    // 同索引换批时设备测试可直接断言"请求身份已同步更新为新卡 identity"。
                    if (BuildConfig.DEBUG) Log.d(
                        TAG,
                        "window gen=$runGeneration idx=$currentIndex identities=[" +
                            window.entries.joinToString(",") { "${it.key}=${it.value.substringBefore('|')}" } + "]"
                    )
                    completed.retainAll(windowKeys)
                    val stale = active.filter { (index, request) -> window[index] != request.identity }
                    // T06 剩余场景（2026-11-14）：DEBUG 门日志——身份失配的旧请求被取消，
                    // 旧 Disposable 释放的可观测信号（仅在确有释放时输出）。
                    if (BuildConfig.DEBUG && stale.isNotEmpty()) Log.d(
                        TAG,
                        "staleDispose " + stale.entries.joinToString(",") {
                            "index=${it.key} token=${it.value.token} identity=${it.value.identity}"
                        }
                    )
                    stale.forEach { (index, request) -> active.remove(index); request.disposable?.dispose() }
                    window.forEach { (index, identity) ->
                        if (identity !in completed && active[index]?.identity != identity) {
                            enqueue(index, snapshot[index], identity)
                        }
                    }
                }
            }
        }
    }

    // cardSize 为兼容调用方保留；沿用固定 720px 上限，身份键记录实际解码尺寸。
    private fun loadSize(image: ImageFile): Size = cardLoadSize(image)

    private fun requestIdentity(image: ImageFile): String = "${cardMemoryCacheKey(image)}|${loadSize(image)}|hardware=false"

    // lock is held; callbacks may arrive synchronously during enqueue.
    private fun enqueue(index: Int, image: ImageFile, identity: String, cacheResult: Boolean = true) {
        val token = ++nextToken
        val requestState = Active(token, identity, cacheResult)
        active[index] = requestState
        try {
            val request = ImageRequest.Builder(context)
                .data(image.uri)
                .size(loadSize(image))
                .memoryCacheKey(cardMemoryCacheKey(image))
                .diskCacheKey(cardMemoryCacheKey(image))
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                .allowHardware(false)
                .listener(
                    onSuccess = { _, _ ->
                        // T06 剩余场景（2026-11-14）：DEBUG 门晚到回调注入钩子（见
                        // companion KDoc）——未 arm 时 tryDeferFinish 恒 false，直通
                        // finish，与既有路径行为一致；Release 恒直通。
                        if (!tryDeferFinish(index, token, identity) { finish(index, token, true) }) {
                            finish(index, token, true)
                        }
                    },
                    onError = { _, _ -> finish(index, token, false) }
                ).build()
            // T06 剩余场景（2026-11-14）：DEBUG 门日志——真实请求发出（token+完整身份），
            // "实际预载请求恢复"的行为级信号（resume 日志只证明调用发生，本日志证明
            // 恢复后有真实新请求发出且身份即该槽位的当前 identity）。
            if (BuildConfig.DEBUG) Log.d(TAG, "enqueue index=$index token=$token identity=$identity")
            val disposable = imageLoader.enqueue(request)
            if (active[index] === requestState) requestState.disposable = disposable else disposable.dispose()
        } catch (e: Exception) {
            if (active[index] === requestState) active.remove(index)
            Log.w(TAG, "预加载失败: ${image.displayName}", e)
        }
    }

    private fun finish(index: Int, token: Long, success: Boolean) {
        synchronized(lock) {
            val request = active[index] ?: return
            // 负控制变异 B（临时，验证后恢复）：移除 token 匹配门——预期 case 3
            // "旧 token 不得产生任何回写"新断言失败（失效后释放的旧回调直接写回，
            // 覆盖新请求的 Active）。
            if (disposed) return
            // T06 剩余场景（2026-11-14）：DEBUG 门日志——仅活 token 的回写可见
            // （token 失配/已释放的旧回调在本守卫处静默丢弃，不产生本日志）。
            // 设备测试据此断言"新批次回写身份=新请求身份、旧批次无迟到回写"。
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "finish index=$index token=$token success=$success identity=${request.identity}")
            }
            active.remove(index)
            if (success && request.cacheResult) completed.add(request.identity)
        }
    }

    fun preloadImmediate(images: List<ImageFile>, indices: List<Int>, cardSize: IntSize) {
        synchronized(lock) {
            if (disposed || isPaused.get()) return
            indices.forEach { index ->
                val image = images.getOrNull(index) ?: return@forEach
                val identity = requestIdentity(image)
                val previous = active.remove(index)
                previous?.disposable?.dispose()
                enqueue(index, image, identity, cacheResult = false)
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            generation++
            debounceJob?.cancel()
            val old = active.values.toList()
            active.clear()
            completed.clear()
            old.forEach { it.disposable?.dispose() }
        }
    }

    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            generation++
            debounceJob?.cancel()
            // T06 剩余场景（2026-11-14）：DEBUG 门日志——组合销毁时管理器整体释放
            // （disposable 全量 dispose + scope cancel），"旧 Disposable 释放"的
            // 生命周期级可观测信号；释放后本管理器不再产生任何 enqueue/window 日志。
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "dispose active=${active.size} completed=${completed.size}")
            }
            val old = active.values.toList()
            active.clear()
            completed.clear()
            old.forEach { it.disposable?.dispose() }
        }
        scope.cancel()
    }

    companion object {
        private const val TAG = "PreloadingManager"

        // ==================== T06 剩余场景（2026-11-14）：晚到回调注入钩子（仅 DEBUG） ====================
        //
        // 目的：T06RemainingDeviceTest 旧回调晚到场景需要可控顺序——真实手势无法命中
        // "请求完成与失效（stale remove + dispose）交错"的竞态窗口（夹具微图解码毫秒级
        // 完成）。测试先按夹具媒体 id 集合 arm；命中 identity 的**首个真实 listener 回调**
        // 被捕获（不再直接 finish），由测试显式 release 后才进入真实 finish 守卫——
        // 复现"回调交付晚于失效"并断言守卫拦截（active/token/disposed 守卫静默丢弃）。
        //
        // 控制点覆盖层（逐项登记，套件 KDoc 同名小节同步登记）：
        // - 覆盖：listener→finish 的交付时机（捕获→释放）；finish 的 active 存在 +
        //   token 匹配 + 未 disposed 守卫与 stale remove + Disposable.dispose 均按生产
        //   原样执行（钩子不改动任何守卫/取消逻辑）。
        // - 不覆盖：coil 内部取消状态机（对已完成请求 dispose 为 no-op，无观测差异）；
        //   coil 结果路径（缓存写入）未延迟——生产中结果与回调同源完成，晚到的可观测
        //   契约即回写（finish），钩子延迟该交付即复现真实竞态的守卫路径。
        //
        // Release 构建：armDeferredFinishForTest / releaseDeferredFinishForTest /
        // tryDeferFinish 全部 DEBUG 门，Release 恒直通，生产行为零变化。
        private val hookLock = Any()
        @Volatile private var hookArmedIds: Set<Long>? = null
        private var hookDeferred: (() -> Unit)? = null

        /** 测试入口：arm（绑定夹具媒体 id 集合；捕获首个命中即解除；空集合=解除）。 */
        internal fun armDeferredFinishForTest(ids: Set<Long>) {
            if (!BuildConfig.DEBUG) return
            synchronized(hookLock) { hookArmedIds = ids; hookDeferred = null }
        }

        /** 测试入口：释放被捕获的旧回调（进入真实 finish 守卫）；返回是否存在待释放。 */
        internal fun releaseDeferredFinishForTest(): Boolean {
            if (!BuildConfig.DEBUG) return false
            val deferred = synchronized(hookLock) {
                val d = hookDeferred
                hookDeferred = null
                d
            } ?: return false
            Log.d(TAG, "lateFinishReleased")
            deferred()
            return true
        }

        /** enqueue 的 listener 调用（DEBUG 门）：命中 armed 集合则捕获并延迟交付。 */
        private fun tryDeferFinish(index: Int, token: Long, identity: String, invoke: () -> Unit): Boolean {
            if (!BuildConfig.DEBUG) return false
            val mediaId = identity.substringAfter("images/media/", "").substringBefore("_").toLongOrNull()
                ?: return false
            synchronized(hookLock) {
                val ids = hookArmedIds ?: return false
                if (mediaId !in ids) return false
                hookArmedIds = null // 捕获即解除（consume-once），后续请求不受影响
                hookDeferred = {
                    // 交付日志：晚到回调真实进入 finish 守卫的直接证据；守卫拦截时
                    // 不产生 finish 日志，本日志即"已交付"与"守卫丢弃"的区分信号。
                    Log.d(TAG, "lateFinishDelivered index=$index token=$token identity=$identity")
                    invoke()
                }
            }
            Log.d(TAG, "lateFinishCaptured index=$index token=$token identity=$identity")
            return true
        }
    }
}

/**
 * T06b：卡片显示/预加载共用的缓存请求键，含内容版本（DATE_MODIFIED 毫秒，T03 契约）——
 * 同 id 内容编辑后自然失效。所有 "card_" 键的读写必须经此函数，避免各处键格式漂移。
 */
fun cardMemoryCacheKey(image: ImageFile): String = "card_${image.uri}_${image.dateModified}"

/**
 * T06b：卡片显示/预加载共用解码规格（固定 720px 上限，按纵横比适配）。
 * "card_" 键下的条目统一由此规格生产——cardMemoryCacheKey 为显式键，命中不校验
 * 尺寸，不同站点往同一键写不同规格位图会互相挤占/错配。
 */
internal fun cardLoadSize(image: ImageFile): Size = if (!image.hasDimensionInfo) {
    Size(540, 720)
} else if (image.aspectRatio < 1f) {
    Size((720 * image.aspectRatio).toInt().coerceAtLeast(270), 720)
} else {
    Size(720, (720 / image.aspectRatio).toInt().coerceAtLeast(270))
}
