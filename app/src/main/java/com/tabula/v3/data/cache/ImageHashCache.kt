package com.tabula.v3.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.tabula.v3.data.repository.PerceptualHash

/**
 * 图片哈希缓存管理器
 * 
 * 使用 SharedPreferences 存储图片的感知哈希值，避免重复计算。
 * 
 * 存储格式：
 * - "hash_{imageId}" -> Long (哈希值，使用 Long.MIN_VALUE 表示计算失败)
 * - "status_{imageId}" -> Int (状态：0=成功, 1=失败)
 * 
 * 注意：Long.MIN_VALUE 作为失败标记是因为它作为真实哈希值的概率极低
 */
class ImageHashCache(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "tabula_image_hash_cache"
        private const val PREFIX_HASH = "hash_"
        private const val PREFIX_STATUS = "status_"
        private const val PREFIX_VERSION = "ver_"
        
        // 状态常量
        const val STATUS_SUCCESS = 0
        const val STATUS_FAILED = 1
        
        // 使用 Long.MIN_VALUE 作为"无哈希"的标记值
        // 这个值作为真实哈希的概率极低（1/2^64）
        private const val NO_HASH_MARKER = Long.MIN_VALUE
        
        @Volatile
        private var instance: ImageHashCache? = null
        
        fun getInstance(context: Context): ImageHashCache {
            return instance ?: synchronized(this) {
                instance ?: ImageHashCache(context.applicationContext).also { 
                    instance = it 
                }
            }
        }
    }
    
    /**
     * 缓存的哈希数据
     * 
     * @param hash 哈希值，null 表示计算失败或未计算
     * @param status 状态（STATUS_SUCCESS 或 STATUS_FAILED）
     */
    data class CachedHash(
        val hash: Long?,
        val status: Int
    ) {
        val isSuccess: Boolean get() = status == STATUS_SUCCESS && hash != null
        val isFailed: Boolean get() = status == STATUS_FAILED
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    
    /**
     * 获取单个图片的缓存哈希
     * 
     * @param imageId 图片 ID
     * @return CachedHash，如果未缓存则返回 null
     */
    fun getHash(imageId: Long, contentVersion: Long? = null): CachedHash? {
        val hashKey = "$PREFIX_HASH$imageId"
        val statusKey = "$PREFIX_STATUS$imageId"
        if (!prefs.contains(hashKey)) return null
        if (contentVersion != null && !isVersionCurrent(imageId, contentVersion)) return null
        
        val hashValue = prefs.getLong(hashKey, NO_HASH_MARKER)
        val status = prefs.getInt(statusKey, STATUS_FAILED)
        
        val hash = if (hashValue == NO_HASH_MARKER) null else hashValue
        return CachedHash(hash, status)
    }
    
    /**
     * 批量获取图片的缓存哈希
     * 
     * @param imageIds 图片 ID 列表
     * @return Map<imageId, CachedHash>，未缓存的图片不包含在结果中
     */
    fun getHashesBatch(imageIds: List<Long>, contentVersions: Map<Long, Long>? = null): Map<Long, CachedHash> {
        if (imageIds.isEmpty()) return emptyMap()
        
        val result = mutableMapOf<Long, CachedHash>()
        
        // SharedPreferences 的 getAll() 比多次 get 更高效
        val allEntries = prefs.all
        
        for (imageId in imageIds) {
            val hashKey = "$PREFIX_HASH$imageId"
            val statusKey = "$PREFIX_STATUS$imageId"
            
            val hashValue = allEntries[hashKey] as? Long
            val status = allEntries[statusKey] as? Int
            
            if (hashValue != null) {
                if (contentVersions != null && !isVersionCurrent(imageId, contentVersions[imageId])) continue
                val hash = if (hashValue == NO_HASH_MARKER) null else hashValue
                result[imageId] = CachedHash(hash, status ?: STATUS_FAILED)
            }
        }
        
        return result
    }
    
    /**
     * 保存单个哈希计算结果
     * 
     * @param imageId 图片 ID
     * @param result 计算结果
     */
    fun saveHash(imageId: Long, result: PerceptualHash.HashResult, contentVersion: Long? = null) {
        val hashKey = "$PREFIX_HASH$imageId"
        val statusKey = "$PREFIX_STATUS$imageId"
        
        val editor = prefs.edit()
        
        when (result) {
            is PerceptualHash.HashResult.Success -> {
                editor.putLong(hashKey, result.hash)
                editor.putInt(statusKey, STATUS_SUCCESS)
            }
            is PerceptualHash.HashResult.Failed -> {
                editor.putLong(hashKey, NO_HASH_MARKER)
                editor.putInt(statusKey, STATUS_FAILED)
            }
        }
        if (contentVersion != null) {
            editor.putLong("$PREFIX_VERSION$imageId", contentVersion)
        }
        editor.apply()
    }
    
    /**
     * 批量保存哈希计算结果
     * 
     * @param results Map<imageId, HashResult>
     */
    fun saveHashesBatch(results: Map<Long, PerceptualHash.HashResult>, contentVersions: Map<Long, Long>? = null) {
        if (results.isEmpty()) return
        
        val editor = prefs.edit()
        
        for ((imageId, result) in results) {
            val hashKey = "$PREFIX_HASH$imageId"
            val statusKey = "$PREFIX_STATUS$imageId"
            
            when (result) {
                is PerceptualHash.HashResult.Success -> {
                    editor.putLong(hashKey, result.hash)
                    editor.putInt(statusKey, STATUS_SUCCESS)
                }
                is PerceptualHash.HashResult.Failed -> {
                    editor.putLong(hashKey, NO_HASH_MARKER)
                    editor.putInt(statusKey, STATUS_FAILED)
                }
            }
            contentVersions?.get(imageId)?.let { editor.putLong("$PREFIX_VERSION$imageId", it) }
        }
        
        editor.apply()
    }
    
    /**
     * 清理已不存在的图片的缓存
     * 
     * @param validImageIds 当前有效的图片 ID 集合
     */
    fun cleanupStaleHashes(validImageIds: Set<Long>) {
        val allEntries = prefs.all
        val editor = prefs.edit()
        var cleanupCount = 0
        
        // 找出所有缓存的 imageId
        val cachedIds = mutableSetOf<Long>()
        for (key in allEntries.keys) {
            if (key.startsWith(PREFIX_HASH)) {
                val idStr = key.removePrefix(PREFIX_HASH)
                idStr.toLongOrNull()?.let { cachedIds.add(it) }
            }
        }
        
        // 删除不在有效集合中的缓存
        for (cachedId in cachedIds) {
            if (cachedId !in validImageIds) {
                editor.remove("$PREFIX_HASH$cachedId")
                editor.remove("$PREFIX_STATUS$cachedId")
                editor.remove("$PREFIX_VERSION$cachedId")
                cleanupCount++
            }
        }
        
        if (cleanupCount > 0) {
            editor.apply()
            android.util.Log.d("ImageHashCache", "Cleaned up $cleanupCount stale hash entries")
        }
    }
    
    /** 版本化读取：无 ver 键的旧条目按过期处理（保守重算，TC58）。 */
    private fun isVersionCurrent(imageId: Long, contentVersion: Long?): Boolean {
        if (contentVersion == null) return true
        if (!prefs.contains("$PREFIX_VERSION$imageId")) return false
        return prefs.getLong("$PREFIX_VERSION$imageId", Long.MIN_VALUE) == contentVersion
    }

    /**
     * 获取缓存统计信息（用于调试）
     */
    fun getStats(): CacheStats {
        val allEntries = prefs.all
        var successCount = 0
        var failedCount = 0
        
        for ((key, value) in allEntries) {
            if (key.startsWith(PREFIX_STATUS)) {
                when (value as? Int) {
                    STATUS_SUCCESS -> successCount++
                    STATUS_FAILED -> failedCount++
                }
            }
        }
        
        return CacheStats(
            totalCount = successCount + failedCount,
            successCount = successCount,
            failedCount = failedCount
        )
    }
    
    /**
     * 清空所有缓存（谨慎使用）
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
    
    /**
     * 缓存统计信息
     */
    data class CacheStats(
        val totalCount: Int,
        val successCount: Int,
        val failedCount: Int
    )
}
