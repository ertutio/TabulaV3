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

    /**
     * 哈希缓存身份键。T03：不同卷的同数值 ID 是不同照片，缓存条目必须按
     * 完整媒体身份隔离；卷未知（T03 前旧数据路径）退化为裸 ID 键，保持
     * 旧条目可读、可按既有语义清理。
     */
    data class HashKey(val volumeName: String?, val imageId: Long) {
        // C1/T03-HASHVOLUME：卷已知行使用卷限定存储键（跨卷同 ID 行互不
        // 覆盖、版本键各自独立、清理可按卷判定）；卷未知行退化为裸 ID 键，
        // 与 T03 前旧条目（hash_{id}）同形，保持旧数据可读、可清。
        internal val storageKey: String
            get() = if (volumeName != null) "${volumeName}_$imageId" else "$imageId"
    }

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

    // ========== T03 身份键 API（HashKey） ==========
    // 语义与 Long 版一一对应，只是键从裸数值 ID 升级为完整媒体身份键；
    // 旧 Long API 在全部消费者迁移完成后移除。

    /** 按身份键读取缓存哈希（语义同 getHash(Long, …)）。 */
    fun getHash(key: HashKey, contentVersion: Long? = null): CachedHash? {
        val hashKey = "$PREFIX_HASH${key.storageKey}"
        val statusKey = "$PREFIX_STATUS${key.storageKey}"
        if (!prefs.contains(hashKey)) return null
        if (contentVersion != null && !isVersionCurrent(key, contentVersion)) return null

        val hashValue = prefs.getLong(hashKey, NO_HASH_MARKER)
        val status = prefs.getInt(statusKey, STATUS_FAILED)

        val hash = if (hashValue == NO_HASH_MARKER) null else hashValue
        return CachedHash(hash, status)
    }

    /** 按身份键批量读取（语义同 getHashesBatch(List<Long>, …)）。 */
    @JvmName("getHashesBatchByIdentity")
    fun getHashesBatch(
        keys: Collection<HashKey>,
        contentVersions: Map<HashKey, Long>? = null
    ): Map<HashKey, CachedHash> {
        if (keys.isEmpty()) return emptyMap()

        val result = mutableMapOf<HashKey, CachedHash>()
        val allEntries = prefs.all

        for (key in keys) {
            val hashKey = "$PREFIX_HASH${key.storageKey}"
            val statusKey = "$PREFIX_STATUS${key.storageKey}"

            val hashValue = allEntries[hashKey] as? Long
            val status = allEntries[statusKey] as? Int

            if (hashValue != null) {
                if (contentVersions != null && !isVersionCurrent(key, contentVersions[key])) continue
                val hash = if (hashValue == NO_HASH_MARKER) null else hashValue
                result[key] = CachedHash(hash, status ?: STATUS_FAILED)
            }
        }

        return result
    }

    /** 按身份键保存单个哈希结果（语义同 saveHash(Long, …)）。 */
    fun saveHash(key: HashKey, result: PerceptualHash.HashResult, contentVersion: Long? = null) {
        val hashKey = "$PREFIX_HASH${key.storageKey}"
        val statusKey = "$PREFIX_STATUS${key.storageKey}"

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
            editor.putLong("$PREFIX_VERSION${key.storageKey}", contentVersion)
        }
        editor.apply()
    }

    /** 按身份键批量保存（语义同 saveHashesBatch(Map<Long, …>, …)）。 */
    @JvmName("saveHashesBatchByIdentity")
    fun saveHashesBatch(
        results: Map<HashKey, PerceptualHash.HashResult>,
        contentVersions: Map<HashKey, Long>? = null
    ) {
        if (results.isEmpty()) return

        val editor = prefs.edit()

        for ((key, result) in results) {
            val hashKey = "$PREFIX_HASH${key.storageKey}"
            val statusKey = "$PREFIX_STATUS${key.storageKey}"

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
            contentVersions?.get(key)?.let { editor.putLong("$PREFIX_VERSION${key.storageKey}", it) }
        }

        editor.apply()
    }

    /**
     * 按身份键清理失效条目：条目存储键不在有效身份键集合内即整组移除
     * （hash/status/ver 三键）。卷限定键使跨卷同 ID 行各自独立保留；
     * 旧裸 ID 条目在有效集合（卷限定）中无对应项时按既有清理语义移除
     * （派生缓存，重算有界，TC58 一致）。
     */
    @JvmName("cleanupStaleHashesByIdentity")
    fun cleanupStaleHashes(validKeys: Set<HashKey>) {
        val validStorageKeys = validKeys.map { it.storageKey }.toSet()
        val allEntries = prefs.all
        val editor = prefs.edit()
        var cleanupCount = 0

        val cachedSuffixes = mutableSetOf<String>()
        for (key in allEntries.keys) {
            if (key.startsWith(PREFIX_HASH)) {
                cachedSuffixes.add(key.removePrefix(PREFIX_HASH))
            }
        }

        for (suffix in cachedSuffixes) {
            if (suffix !in validStorageKeys) {
                editor.remove("$PREFIX_HASH$suffix")
                editor.remove("$PREFIX_STATUS$suffix")
                editor.remove("$PREFIX_VERSION$suffix")
                cleanupCount++
            }
        }

        if (cleanupCount > 0) {
            editor.apply()
            android.util.Log.d("ImageHashCache", "Cleaned up $cleanupCount stale hash entries")
        }
    }

    /** 版本化读取（身份键版）：无 ver 键的旧条目按过期处理（保守重算，TC58）。 */
    private fun isVersionCurrent(key: HashKey, contentVersion: Long?): Boolean {
        if (contentVersion == null) return true
        val versionKey = "$PREFIX_VERSION${key.storageKey}"
        if (!prefs.contains(versionKey)) return false
        return prefs.getLong(versionKey, Long.MIN_VALUE) == contentVersion
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
