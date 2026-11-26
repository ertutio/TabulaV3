package com.tabula.v3.data.repository

import android.content.Context
import android.util.Log
import com.tabula.v3.data.model.AlbumUsageRecord
import com.tabula.v3.data.model.AlbumUsageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 图集使用频率追踪器
 * 
 * 功能：
 * - 记录每次照片归类操作
 * - 基于时间衰减算法计算图集使用得分
 * - 支持连续归类抑制，避免短期内大量归类同一图集导致排名突变
 * - 支持手动排序锁定，尊重用户的主动排序意愿
 * 
 * 算法设计：
 * - 时间衰减：Score = Σ (1 / (1 + α * hours_elapsed))
 * - 连续抑制：同一图集 60 秒内连续归类，权重递减（0.7^n）
 * - 手动锁定：用户手动排序后，锁定 7 天保持用户设定顺序
 */
class AlbumUsageTracker(private val context: Context) {

    companion object {
        private const val TAG = "AlbumUsageTracker"
        private const val USAGE_FILE = "album_usage.json"
        
        // ========== 算法参数 ==========
        
        /** 最大记录条数（滑动窗口大小） */
        const val MAX_RECORDS = 100
        
        /** 时间衰减系数（0.05 表示约 20 小时后权重减半） */
        const val TIME_DECAY_FACTOR = 0.05
        
        /** 连续归类衰减系数（每次连续归类权重乘以此值） */
        const val CONSECUTIVE_DECAY = 0.7
        
        /** 连续归类检测阈值（毫秒），60 秒内视为连续操作 */
        const val CONSECUTIVE_THRESHOLD_MS = 60_000L
        
        /** 连续归类最小权重（防止权重衰减到 0） */
        const val CONSECUTIVE_MIN_WEIGHT = 0.1
        
        /** 手动排序锁定天数 */
        const val MANUAL_LOCK_DAYS = 7
        
        /** 新图集基础分（确保新建图集不会排在最后） */
        const val NEW_ALBUM_BASE_SCORE = 0.5

        @Volatile
        private var instance: AlbumUsageTracker? = null

        fun getInstance(context: Context): AlbumUsageTracker {
            return instance ?: synchronized(this) {
                instance ?: AlbumUsageTracker(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val usageFile: File
        get() = File(context.filesDir, USAGE_FILE)

    // 内存缓存
    private var usageRecords: MutableList<AlbumUsageRecord> = mutableListOf()
    private var manualOrderLocks: MutableMap<String, Long> = mutableMapOf()  // albumId -> lockUntilTimestamp
    
    // 当前会话ID（用于检测连续归类）
    private var currentSessionId: String = UUID.randomUUID().toString()
    private var lastRecordTimestamp: Long = 0L
    private var lastRecordAlbumId: String? = null

    /**
     * 初始化：从文件加载数据
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        loadFromFile()
        Log.d(TAG, "Initialized with ${usageRecords.size} records, ${manualOrderLocks.size} manual locks")
    }

    /**
     * 记录一次归类操作
     * 
     * @param albumId 目标图集ID
     */
    fun recordUsage(albumId: String) {
        val now = System.currentTimeMillis()
        
        // 检测是否为连续操作（同一图集，60 秒内）
        val isConsecutive = lastRecordAlbumId == albumId && 
                           (now - lastRecordTimestamp) < CONSECUTIVE_THRESHOLD_MS
        
        // 如果是新的归类会话（切换了图集或超过阈值时间），更新会话ID
        if (!isConsecutive && lastRecordAlbumId != null) {
            currentSessionId = UUID.randomUUID().toString()
        }
        
        val record = AlbumUsageRecord(
            albumId = albumId,
            timestamp = now,
            sessionId = currentSessionId
        )
        
        usageRecords.add(record)
        
        // 保持窗口大小
        while (usageRecords.size > MAX_RECORDS) {
            usageRecords.removeAt(0)
        }
        
        // 更新最后记录信息
        lastRecordTimestamp = now
        lastRecordAlbumId = albumId
        
        // 异步持久化
        saveToFileAsync()
        
        Log.d(TAG, "Recorded usage: albumId=$albumId, consecutive=$isConsecutive, total=${usageRecords.size}")
    }

    /**
     * 计算所有图集的使用得分（使用默认参数）
     * 
     * @return Map<albumId, score>，得分越高排名越靠前
     */
    fun getAlbumScores(): Map<String, Double> {
        return getAlbumScores(
            timeDecayFactor = TIME_DECAY_FACTOR,
            consecutiveDecayFactor = CONSECUTIVE_DECAY
        )
    }

    /**
     * 计算所有图集的使用得分（使用自定义参数）
     * 
     * @param timeDecayFactor 时间衰减因子，值越大衰减越快
     * @param consecutiveDecayFactor 连续归类衰减因子，值越小抑制越强
     * @return Map<albumId, score>，得分越高排名越靠前
     */
    fun getAlbumScores(
        timeDecayFactor: Double,
        consecutiveDecayFactor: Double
    ): Map<String, Double> {
        val now = System.currentTimeMillis()
        val scores = mutableMapOf<String, Double>()
        
        // 按图集分组
        val recordsByAlbum = usageRecords.groupBy { it.albumId }
        
        for ((albumId, records) in recordsByAlbum) {
            // 检查手动排序锁定
            val lockUntil = manualOrderLocks[albumId]
            if (lockUntil != null && now < lockUntil) {
                // 手动锁定中，返回极大值以保持位置
                // 使用锁定时的相对顺序（通过锁定时间差来区分）
                scores[albumId] = Double.MAX_VALUE - (lockUntil - now).toDouble()
                continue
            }
            
            // 计算时间衰减 + 连续抑制得分
            scores[albumId] = calculateScore(records, now, timeDecayFactor, consecutiveDecayFactor)
        }
        
        return scores
    }

    /**
     * 计算单个图集的得分
     * 
     * 算法：
     * 1. 按时间倒序遍历记录
     * 2. 每条记录的基础分 = 1 / (1 + α * hours_elapsed)
     * 3. 连续归类时，权重递减：weight = max(minWeight, decay^n)
     * 4. 最终得分 = Σ (基础分 * 连续权重)
     * 
     * @param records 该图集的使用记录
     * @param now 当前时间戳
     * @param timeDecayFactor 时间衰减因子
     * @param consecutiveDecayFactor 连续归类衰减因子
     */
    private fun calculateScore(
        records: List<AlbumUsageRecord>,
        now: Long,
        timeDecayFactor: Double = TIME_DECAY_FACTOR,
        consecutiveDecayFactor: Double = CONSECUTIVE_DECAY
    ): Double {
        if (records.isEmpty()) return 0.0
        
        var score = 0.0
        var lastTimestamp: Long? = null
        var consecutiveWeight = 1.0
        
        // 按时间倒序（最新的在前）
        val sortedRecords = records.sortedByDescending { it.timestamp }
        
        for (record in sortedRecords) {
            // 计算时间衰减
            val hoursElapsed = (now - record.timestamp) / 3600_000.0
            val timeWeight = 1.0 / (1.0 + timeDecayFactor * hoursElapsed)
            
            // 检测连续归类
            if (lastTimestamp != null) {
                val timeDiff = lastTimestamp - record.timestamp
                if (timeDiff < CONSECUTIVE_THRESHOLD_MS) {
                    // 连续操作，递减权重
                    consecutiveWeight = (consecutiveWeight * consecutiveDecayFactor)
                        .coerceAtLeast(CONSECUTIVE_MIN_WEIGHT)
                } else {
                    // 非连续操作，重置权重
                    consecutiveWeight = 1.0
                }
            }
            
            score += timeWeight * consecutiveWeight
            lastTimestamp = record.timestamp
        }
        
        return score
    }

    /**
     * 获取图集的使用统计信息
     */
    fun getAlbumStats(albumId: String): AlbumUsageStats? {
        val records = usageRecords.filter { it.albumId == albumId }
        if (records.isEmpty()) return null
        
        val now = System.currentTimeMillis()
        return AlbumUsageStats(
            albumId = albumId,
            weightedScore = calculateScore(records, now),
            lastUsedAt = records.maxOf { it.timestamp },
            totalCount = records.size
        )
    }

    /**
     * 路线图 #83 第 3 项：所有图集的使用统计快照，供归档时的「最近图集」用。
     *
     * 与 [getAlbumStats] 口径一致（同一份窗口记录），但不做单图集过滤，
     * 一次遍历返回全部，避免下拉弹层里逐图集调用。
     */
    fun getAllAlbumStats(): Map<String, AlbumUsageStats> {
        if (usageRecords.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        return usageRecords.groupBy { it.albumId }.mapValues { (albumId, records) ->
            AlbumUsageStats(
                albumId = albumId,
                weightedScore = calculateScore(records, now),
                lastUsedAt = records.maxOf { it.timestamp },
                totalCount = records.size
            )
        }
    }

    /**
     * 设置手动排序锁定
     * 
     * 当用户手动拖拽排序图集时调用，锁定期内保持用户设定的顺序。
     * 
     * @param albumIds 按用户排序后的图集ID列表
     * @param durationDays 锁定天数，默认 7 天
     */
    fun setManualOrderLock(albumIds: List<String>, durationDays: Int = MANUAL_LOCK_DAYS) {
        val now = System.currentTimeMillis()
        val lockDurationMs = durationDays * 24 * 3600 * 1000L
        
        // 清除旧的锁定
        manualOrderLocks.clear()
        
        // 为每个图集设置锁定，使用微小时间差来保持相对顺序
        albumIds.forEachIndexed { index, albumId ->
            // 锁定截止时间递减，确保排在前面的图集锁定值更大
            manualOrderLocks[albumId] = now + lockDurationMs - index
        }
        
        saveToFileAsync()
        Log.d(TAG, "Set manual order lock for ${albumIds.size} albums, duration=$durationDays days")
    }

    /**
     * 清除所有手动排序锁定
     */
    fun clearManualOrderLock() {
        manualOrderLocks.clear()
        saveToFileAsync()
        Log.d(TAG, "Cleared all manual order locks")
    }

    /**
     * 清除指定图集的手动排序锁定
     */
    fun clearManualOrderLock(albumId: String) {
        manualOrderLocks.remove(albumId)
        saveToFileAsync()
        Log.d(TAG, "Cleared manual order lock for album: $albumId")
    }

    /**
     * 检查图集是否处于手动排序锁定状态
     */
    fun isManualOrderLocked(albumId: String): Boolean {
        val lockUntil = manualOrderLocks[albumId] ?: return false
        return System.currentTimeMillis() < lockUntil
    }

    /**
     * 检查是否有任何图集处于手动排序锁定状态
     */
    fun hasAnyManualOrderLock(): Boolean {
        val now = System.currentTimeMillis()
        return manualOrderLocks.values.any { it > now }
    }

    /**
     * 清除指定图集的所有使用记录（当图集被删除时调用）
     */
    fun clearAlbumRecords(albumId: String) {
        usageRecords.removeAll { it.albumId == albumId }
        manualOrderLocks.remove(albumId)
        saveToFileAsync()
        Log.d(TAG, "Cleared all records for album: $albumId")
    }

    // ========== 持久化 ==========

    private fun loadFromFile() {
        try {
            if (!usageFile.exists()) {
                usageRecords = mutableListOf()
                manualOrderLocks = mutableMapOf()
                return
            }

            val jsonString = usageFile.readText()
            if (jsonString.isBlank()) {
                usageRecords = mutableListOf()
                manualOrderLocks = mutableMapOf()
                return
            }

            val jsonObject = JSONObject(jsonString)
            
            // 加载使用记录
            val recordsArray = jsonObject.optJSONArray("records") ?: JSONArray()
            usageRecords = mutableListOf()
            for (i in 0 until recordsArray.length()) {
                val obj = recordsArray.getJSONObject(i)
                usageRecords.add(
                    AlbumUsageRecord(
                        albumId = obj.getString("albumId"),
                        timestamp = obj.getLong("timestamp"),
                        sessionId = obj.optString("sessionId", null)
                    )
                )
            }
            
            // 加载手动锁定
            val locksObject = jsonObject.optJSONObject("manualLocks") ?: JSONObject()
            manualOrderLocks = mutableMapOf()
            locksObject.keys().forEach { albumId ->
                manualOrderLocks[albumId] = locksObject.getLong(albumId)
            }
            
            // 清理过期的锁定
            val now = System.currentTimeMillis()
            manualOrderLocks.entries.removeIf { it.value < now }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading usage data", e)
            usageRecords = mutableListOf()
            manualOrderLocks = mutableMapOf()
        }
    }

    private fun saveToFileAsync() {
        // 简单的异步保存，不阻塞主线程
        Thread {
            saveToFile()
        }.start()
    }

    private fun saveToFile() {
        try {
            val jsonObject = JSONObject()
            
            // 保存使用记录
            val recordsArray = JSONArray()
            for (record in usageRecords) {
                val obj = JSONObject().apply {
                    put("albumId", record.albumId)
                    put("timestamp", record.timestamp)
                    record.sessionId?.let { put("sessionId", it) }
                }
                recordsArray.put(obj)
            }
            jsonObject.put("records", recordsArray)
            
            // 保存手动锁定
            val locksObject = JSONObject()
            for ((albumId, lockUntil) in manualOrderLocks) {
                locksObject.put(albumId, lockUntil)
            }
            jsonObject.put("manualLocks", locksObject)
            
            usageFile.writeText(jsonObject.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving usage data", e)
        }
    }
}
