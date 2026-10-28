package com.tabula.v3.di

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.IntSize
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Size
import com.tabula.v3.data.model.ImageFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
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
    providedLoader: coil.ImageLoader? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageLoader: coil.ImageLoader = providedLoader ?: CoilSetup.getImageLoader(context)

    // T06b：已成功预加载的请求键（id_内容版本）。换批/内容编辑/失败自然失效，不按索引记忆。
    private val preloadedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // T06b：在途请求 Disposable（按索引），换批/重置/释放时取消过期请求。
    private val activeDisposables = ConcurrentHashMap<Int, Disposable>()
    
    // 保护并发操作的锁
    private val mutex = Mutex()

    // 上次预加载的中心索引
    @Volatile
    private var lastPreloadCenter: Int = -1

    // 上次批次的签名（大小 + 中心图片身份）
    @Volatile
    private var lastBatchSignature: Pair<Int, Long>? = null
    
    // 预加载暂停状态（滑动时暂停）
    private val isPaused = AtomicBoolean(false)
    
    // 防抖任务
    private var debounceJob: Job? = null
    
    // 防抖延迟（毫秒）
    private val debounceDelay = 100L

    /**
     * 暂停预加载（在快速滑动时调用）
     */
    fun pause() {
        isPaused.set(true)
        debounceJob?.cancel()
        // 防抖被取消的中心索引允许下次同中心重试（T06b）。
        lastPreloadCenter = -1
        lastBatchSignature = null
    }
    
    /**
     * 恢复预加载
     */
    fun resume() {
        isPaused.set(false)
    }

    /**
     * 根据当前索引预加载相邻图片（带防抖）
     *
     * @param images 完整图片列表
     * @param currentIndex 当前显示的索引
     * @param cardSize 卡片尺寸（宽 x 高），用于精确加载
     */
    fun preloadAround(
        images: List<ImageFile>,
        currentIndex: Int,
        cardSize: IntSize
    ) {
        // 暂停中：完全 no-op（不更新中心，保证恢复后同中心可重试）
        if (isPaused.get()) return
        // 中心与批次内容都没变才跳过（T06b：同索引换批须重新预加载）
        val batchSignature = images.size to (images.getOrNull(currentIndex)?.id ?: -1L)
        if (currentIndex == lastPreloadCenter && batchSignature == lastBatchSignature) return
        lastPreloadCenter = currentIndex
        lastBatchSignature = batchSignature
        
        // 取消之前的防抖任务
        debounceJob?.cancel()
        
        // 使用防抖延迟执行预加载，避免快速滑动时触发大量加载
        debounceJob = scope.launch {
            delay(debounceDelay)
            
            // 如果被暂停，跳过
            if (isPaused.get()) return@launch
            
            mutex.withLock {
                // 计算需要预加载的索引范围
                val startIndex = (currentIndex - preloadRange).coerceAtLeast(0)
                val endIndex = (currentIndex + preloadRange).coerceAtMost(images.lastIndex)

                for (index in startIndex..endIndex) {
                    // 再次检查暂停状态
                    if (isPaused.get()) break

                    val image = images.getOrNull(index) ?: continue
                    val key = cardMemoryCacheKey(image)
                    // 跳过已成功预加载的（按请求键；失败/换批/内容变化自动重试）
                    if (key in preloadedKeys) continue

                    preloadImage(index, image, cardSize)
                }

                // 释放在途且已离开保留范围的请求（T06b）
                val keepRange = (currentIndex - preloadRange * 2)..(currentIndex + preloadRange * 2)
                activeDisposables.entries.removeIf { (index, disposable) ->
                    if (index !in keepRange) { disposable.dispose(); true } else false
                }
            }
        }
    }

    /**
     * 预加载单张图片
     *
     * @param image 要预加载的图片
     * @param targetSize 目标尺寸（未使用，改为使用固定尺寸以匹配 ImageCard）
     */
    private fun preloadImage(index: Int, image: ImageFile, targetSize: IntSize) {
        // 如果被暂停，跳过
        if (isPaused.get()) return
        
        try {
            // T06b：请求键含内容版本，与 ImageCard/CardStack 共用同一格式
            val cacheKey = cardMemoryCacheKey(image)
            
            // 优化：使用更小的尺寸（720px 而非 1080px）
            val loadSize = if (!image.hasDimensionInfo) {
                Size(540, 720)  // 从 810x1080 降到 540x720
            } else {
                val maxDimension = 720  // 从 1080 降到 720
                if (image.aspectRatio < 1f) {
                    val height = maxDimension
                    val width = (height * image.aspectRatio).toInt().coerceAtLeast(270)
                    Size(width, height)
                } else {
                    val width = maxDimension
                    val height = (width / image.aspectRatio).toInt().coerceAtLeast(270)
                    Size(width, height)
                }
            }
            
            val request = ImageRequest.Builder(context)
                .data(image.uri)
                .size(loadSize)
                // 使用与 ImageCard 相同的缓存键
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                // 禁用硬件位图，与 ImageCard 保持一致
                .allowHardware(false)
                .listener(
                    onSuccess = { _, _ -> handlePreloadResult(cacheKey, successful = true) },
                    onError = { _, _ -> handlePreloadResult(cacheKey, successful = false) }
                )
                .build()

            activeDisposables[index]?.dispose()
            activeDisposables[index] = imageLoader.enqueue(request)

            Log.d(TAG, "预加载已排队: ${image.displayName}")
        } catch (e: Exception) {
            // 排队失败：键未标记成功，下次 preloadAround 自然重试。
            Log.w(TAG, "预加载失败: ${image.displayName}", e)
        }
    }

    /** T06b：排队与结果分离——成功入记忆集，失败移除以待重试。 */
    internal fun handlePreloadResult(cacheKey: String, successful: Boolean) {
        if (successful) preloadedKeys.add(cacheKey) else preloadedKeys.remove(cacheKey)
    }

    /**
     * 立即预加载指定索引的图片（优先级更高）
     *
     * @param images 图片列表
     * @param indices 要预加载的索引列表
     * @param cardSize 卡片尺寸
     */
    fun preloadImmediate(
        images: List<ImageFile>,
        indices: List<Int>,
        cardSize: IntSize
    ) {
        scope.launch {
            mutex.withLock {
                indices.forEach { index ->
                    val image = images.getOrNull(index) ?: return@forEach
                    preloadImage(index, image, cardSize)
                }
            }
        }
    }

    /**
     * 重置预加载状态（例如切换相册时）
     */
    fun reset() {
        debounceJob?.cancel()
        scope.launch {
            mutex.withLock {
                preloadedKeys.clear()
                lastPreloadCenter = -1
                lastBatchSignature = null
                activeDisposables.values.forEach { it.dispose() }
                activeDisposables.clear()
            }
        }
    }

    /** T06b：释放自有 scope 与全部在途请求；宿主组合离开时调用。 */
    fun dispose() {
        debounceJob?.cancel()
        activeDisposables.values.forEach { it.dispose() }
        activeDisposables.clear()
        scope.cancel()
    }

    companion object {
        private const val TAG = "PreloadingManager"
    }
}

/**
 * T06b：卡片显示/预加载共用的缓存请求键，含内容版本（DATE_MODIFIED 毫秒，T03 契约）——
 * 同 id 内容编辑后自然失效。所有 "card_" 键的读写必须经此函数，避免各处键格式漂移。
 */
fun cardMemoryCacheKey(image: ImageFile): String = "card_${image.id}_${image.dateModified}"
