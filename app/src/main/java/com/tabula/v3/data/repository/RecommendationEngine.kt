package com.tabula.v3.data.repository

import android.content.Context
import com.tabula.v3.data.cache.ImageHashCache
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.RecommendMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 照片推荐引擎
 * 
 * 支持两种推荐模式：
 * 1. 随机漫步模式：真正随机抽取照片，抽取过的照片会进入冷却期，短期内不会再被抽中
 * 2. 相似推荐模式：使用多维度聚类算法检测相似照片组，整组呈现给用户
 * 
 * 两种模式使用独立的冷却机制：
 * - 随机漫步：以单张照片为单位冷却
 * - 相似推荐：以相似组为单位冷却
 * 
 * 屏蔽图集功能：
 * - 用户可标记某些图集为"已整理/屏蔽"，这些图集中的照片不会出现在推荐流中
 * - 通过 AlbumManager.getExcludedAlbumIds() 获取被屏蔽的图集列表
 */
class RecommendationEngine(
    private val context: Context
) {
    private val preferences = AppPreferences(context)
    private val hashCache = ImageHashCache.getInstance(context)
    private val similarGroupDetector = SimilarGroupDetector(context, hashCache)
    private val albumManager = AlbumManager.getInstance(context)
    
    // 缓存检测到的相似组（避免重复检测）；TC58：同 ID 改内容或成员增删随指纹失效。
    private val similarGroupsCache = SimilarGroupsCache()
    
    /**
     * 根据当前推荐模式获取一批照片
     * 
     * @param allImages 所有可用照片
     * @param batchSize 批次大小（仅随机模式使用，相似模式返回动态大小）
     * @param anchorImage 锚点照片（已废弃，保留参数兼容性）
     * @return 推荐的照片列表
     */
    suspend fun getRecommendedBatch(
        allImages: List<ImageFile>,
        batchSize: Int,
        anchorImage: ImageFile? = null
    ): List<ImageFile> = withContext(Dispatchers.IO) {
        if (allImages.isEmpty()) return@withContext emptyList()
        
        // 过滤掉被屏蔽图集的照片
        val filteredImages = filterExcludedAlbums(allImages)
        if (filteredImages.isEmpty()) {
            android.util.Log.d(TAG, "No images available after filtering excluded albums")
            return@withContext emptyList()
        }
        
        when (preferences.recommendMode) {
            RecommendMode.RANDOM_WALK -> {
                // 清理过期的抽取记录
                preferences.cleanupExpiredPickRecords()
                getRandomWalkBatch(filteredImages, batchSize)
            }
            RecommendMode.SIMILAR -> {
                // 清理过期的相似组记录
                preferences.cleanupExpiredSimilarGroupRecords()
                getSimilarBatch(filteredImages)
            }
        }
    }
    
    /**
     * 过滤掉被屏蔽图集的照片
     * 
     * @param allImages 所有照片
     * @return 过滤后的照片列表（不包含被屏蔽图集中的照片）
     */
    private fun filterExcludedAlbums(allImages: List<ImageFile>): List<ImageFile> {
        val excludedAlbumIds = albumManager.getExcludedAlbumIds()
        if (excludedAlbumIds.isEmpty()) {
            return allImages
        }
        
        val filtered = allImages.filter { image ->
            image.bucketDisplayName !in excludedAlbumIds
        }
        
        android.util.Log.d(TAG, "Filtered ${allImages.size - filtered.size} images from ${excludedAlbumIds.size} excluded albums")
        return filtered
    }
    
    /**
     * 获取下一个相似组
     * 
     * 相似推荐模式专用方法，返回完整的相似组对象。
     * 
     * @param allImages 所有可用照片
     * @return 下一个待处理的相似组，如果没有则返回 null
     */
    suspend fun getNextSimilarGroup(allImages: List<ImageFile>): SimilarGroup? = 
        withContext(Dispatchers.IO) {
            if (allImages.isEmpty()) return@withContext null
            
            // 过滤掉被屏蔽图集的照片
            val filteredImages = filterExcludedAlbums(allImages)
            if (filteredImages.isEmpty()) return@withContext null
            
            // 清理过期记录
            preferences.cleanupExpiredSimilarGroupRecords()
            
            // 获取相似组（使用缓存）
            val groups = getOrDetectSimilarGroups(filteredImages)
            
            // 获取冷却中的组ID
            val cooldownGroupIds = preferences.getSimilarGroupCooldownIds()
            
            // 过滤掉冷却中的组
            val availableGroups = groups.filter { it.id !in cooldownGroupIds }
            
            // 返回优先级最高的组（按大小降序已排好）
            availableGroups.firstOrNull()
        }
    
    /**
     * 标记相似组已处理完成
     * 
     * 处理完一个相似组后调用此方法，使该组进入冷却期。
     * 
     * @param group 已处理的相似组
     */
    fun markSimilarGroupProcessed(group: SimilarGroup) {
        preferences.recordSimilarGroupProcessed(group.id)
    }
    
    /**
     * 清除相似组缓存
     * 
     * 当图片列表发生变化时调用（如删除图片后）
     */
    fun invalidateSimilarGroupCache() {
        similarGroupsCache.invalidate()
    }
    
    /**
     * 从冷却池中移除指定的照片
     * 
     * 用于切换推荐算法时，将当前批次中未浏览的照片从冷却池中移除，
     * 确保这些照片不会因为算法切换而被"浪费"进入冷却期。
     * 
     * 注意：此方法只影响随机漫步模式的单张照片冷却池。
     * 相似推荐模式使用的是组冷却池（以组ID为单位），不受此方法影响。
     * 这是预期的行为：
     * - 从随机漫步切换走时：未浏览的单张照片被移除冷却，可被新算法重新推荐
     * - 从相似推荐切换走时：当前组已部分浏览，保留在组冷却池中是合理的
     * 
     * @param imageIds 要移除的照片ID列表
     */
    fun removeFromCooldown(imageIds: List<Long>) {
        preferences.removeImagesFromCooldown(imageIds)
    }
    
    /**
     * 随机漫步模式
     * 
     * 真正随机抽取照片，但排除在冷却期内的照片。
     * 如果可用照片不足，则从冷却期内的照片中补充（按冷却期剩余时间排序）。
     */
    private fun getRandomWalkBatch(
        allImages: List<ImageFile>,
        batchSize: Int
    ): List<ImageFile> {
        // 一次性获取所有冷却中的图片ID
        val cooldownIds = preferences.getCooldownImageIds()
        
        // 分离可用照片和冷却中的照片
        val (cooldownImages, availableImages) = allImages.partition { 
            it.id in cooldownIds
        }
        
        val result = mutableListOf<ImageFile>()
        val usedIds = mutableSetOf<Long>()  // 防止批次内重复
        
        // 优先从可用照片中随机抽取
        val shuffledAvailable = availableImages.shuffled()
        val newlyPicked = shuffledAvailable.take(batchSize)
        for (img in newlyPicked) {
            if (img.id !in usedIds) {
                result.add(img)
                usedIds.add(img.id)
            }
        }
        
        // 如果可用照片不足，从冷却中的照片补充（优先选择冷却期即将结束的）
        if (result.size < batchSize && cooldownImages.isNotEmpty()) {
            val sortedCooldown = cooldownImages.sortedBy { 
                preferences.getImagePickedTimestamp(it.id) 
            }
            for (img in sortedCooldown) {
                if (result.size >= batchSize) break
                if (img.id !in usedIds) {
                    result.add(img)
                    usedIds.add(img.id)
                }
            }
        }
        
        // 只记录新抽取的照片（不在冷却中的），避免重置从冷却中补充的照片的冷却期
        preferences.recordImagesPicked(newlyPicked.map { it.id })
        
        // 如果总照片数 <= batchSize，清理冷却池避免无限循环
        if (allImages.size <= batchSize) {
            android.util.Log.d(TAG, "Total images (${allImages.size}) <= batchSize ($batchSize), clearing cooldown to prevent loop")
            preferences.clearAllPickRecords()
        }
        
        return result
    }
    
    /**
     * 相似推荐模式（新算法）
     * 
     * 使用多维度聚类算法检测相似照片组：
     * 1. dHash 感知哈希 - 内容相似性
     * 2. 时间相近 - 连拍/多角度拍摄
     * 3. 尺寸/文件大小 - 同设备/同模式
     * 4. 同一相册
     * 
     * 返回完整的一组相似照片（动态大小），按时间顺序排列。
     */
    private suspend fun getSimilarBatch(allImages: List<ImageFile>): List<ImageFile> {
        return try {
            // 获取下一个相似组
            val group = getNextSimilarGroup(allImages)
            
            if (group != null) {
                // 标记该组已处理（进入冷却）
                markSimilarGroupProcessed(group)
                android.util.Log.d(TAG, "Returning similar group: ${group.size} images, id=${group.id}")
                group.images
            } else {
                // 如果没有可用的相似组，回退到随机模式
                android.util.Log.d(TAG, "No similar groups available, falling back to random")
                getRandomWalkBatch(allImages, preferences.batchSize)
            }
        } catch (e: CancellationException) {
            // 协程取消异常必须重新抛出
            throw e
        } catch (e: Exception) {
            // 相似推荐出错时回退到随机模式，保证用户体验
            android.util.Log.e(TAG, "Similar batch error, falling back to random: ${e.message}", e)
            getRandomWalkBatch(allImages, preferences.batchSize)
        }
    }
    
    /**
     * 获取或检测相似组（带缓存）
     */
    private suspend fun getOrDetectSimilarGroups(allImages: List<ImageFile>): List<SimilarGroup> {
        val now = System.currentTimeMillis()

        // TTL 内且输入身份+内容指纹未变才复用；同 ID 改内容/成员增删触发重算。
        similarGroupsCache.lookup(allImages, now)?.let { return it }

        // 重新检测
        android.util.Log.d(TAG, "Detecting similar groups...")
        val groups = similarGroupDetector.detectSimilarGroups(allImages)
        similarGroupsCache.store(groups, allImages, now)

        android.util.Log.d(TAG, "Detected ${groups.size} similar groups")
        return groups
    }
    
    companion object {
        private const val TAG = "RecommendationEngine"
        
        @Volatile
        private var instance: RecommendationEngine? = null
        
        fun getInstance(context: Context): RecommendationEngine {
            return instance ?: synchronized(this) {
                instance ?: RecommendationEngine(context.applicationContext).also { 
                    instance = it 
                }
            }
        }
    }
}
